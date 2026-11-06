package com.musicglass.desktop

import com.musicglass.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*

internal class DesktopMediaProbe(private val bridge:DesktopAudioBridge) {
    fun probe(file:File):TrackRecord {
        val path=file.canonicalPath
        val result=bridge.sendCommand(AudioCommand.ProbeMedia(bridge.nextId(),path))
        check(result.success){result.error?:"Media probe failed"}
        val data=Json.parseToJsonElement(checkNotNull(result.details)).jsonObject
        fun text(key:String)=data[key]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
        val attrs=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        val identity=WindowsFileIdentity.of(file)
        return TrackRecord(Scopes.LOCAL,path,text("title")?:file.nameWithoutExtension,text("artist")?:"Unknown artist",text("album").orEmpty(),data["durationMs"]?.jsonPrimitive?.long?:0,
            sourcePath=path,fileIdentity=identity,codec=text("codec"),artworkUrl=text("artworkFile")?.let{"incoming:$it"})
    }
}

/** Streams candidate paths into a bounded channel. Two probes cannot create an unbounded job/file list. */
internal class LibraryScanner(private val store:SqliteLibraryRepository,private val probe:(File)->TrackRecord,private val decorate:suspend(TrackRecord)->TrackRecord={it}) {
    fun scan(root:LocalRoot):Flow<ScanProgress> = channelFlow {
        val directory=try{File(root.path).toPath().toRealPath()}catch(error:java.nio.file.NoSuchFileException){
            store.markRootUnavailable(root.id)
            send(ScanProgress(root.id,0,0,0,reconciliationRequired=true,complete=true,error="Music folder is unavailable: ${root.path}"));return@channelFlow
        }
        require(Files.isDirectory(directory,NOFOLLOW_LINKS))
        store.registerRoot(root);val generation=store.nextScan(root.id)
        val discovered=AtomicLong();val imported=AtomicLong();val rejected=AtomicLong()
        val candidates=Channel<File>(64)
        val consumers=List(2){launch(Dispatchers.IO){
            for(file in candidates) {
                ensureActive()
                try {
                    val path=file.canonicalPath;require(file.toPath().toRealPath().startsWith(directory)){"File resolves outside its registered root"};val attrs=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
                    val identity=WindowsFileIdentity.of(file)
                    val prior=store.location(path)
                    val existing=store.byIdentity(identity)
                    val record=if(prior!=null&&existing!=null&&prior.second==attrs.lastModifiedTime().toMillis()&&prior.third==attrs.size())existing.copy(sourcePath=path,available=true)
                        else decorate(runInterruptible(Dispatchers.IO){probe(file)}).copy(id=existing?.id ?: store.get(Scopes.LOCAL,path)?.takeIf{it.fileIdentity==null}?.id ?: if(store.get(Scopes.LOCAL,path)==null)path else "local:${java.util.UUID.randomUUID()}",sourcePath=path,fileIdentity=identity)
                    store.transaction {val definitive=record.copy(id=byIdentity(identity)?.id?:record.id);upsert(definitive);recordLocation(path,root.id,definitive.id,identity,attrs.lastModifiedTime().toMillis(),attrs.size(),generation)}
                    imported.incrementAndGet()
                }catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){store.scanIssue(root.id,file.path,error.message?:error.javaClass.simpleName);rejected.incrementAndGet()}
                send(ScanProgress(root.id,discovered.get(),imported.get(),rejected.get()))
            }
        }}
        try {
            withContext(Dispatchers.IO){Files.walk(directory,if(root.recursive)64 else 1).use {paths->
                val iterator=paths.iterator()
                while(iterator.hasNext()) {
                    ensureActive();val path=iterator.next()
                    if(Files.isRegularFile(path,NOFOLLOW_LINKS)&&path.fileName.toString().substringAfterLast('.').lowercase() in SUPPORTED) {
                        check(discovered.incrementAndGet()<=1_000_000){"Library root exceeds the discovery limit"}
                        candidates.send(path.toFile())
                    }
                }
            }}
            candidates.close();consumers.joinAll();store.reconcile(root.id,generation)
            send(ScanProgress(root.id,discovered.get(),imported.get(),rejected.get(),complete=true))
        }finally{candidates.cancel();consumers.forEach{it.cancel()}}
    }
    companion object {val SUPPORTED=setOf("mp3","flac","m4a","mp4","wav","ogg","opus","aac","webm","mka")}
}

/** Register every real directory; OVERFLOW and invalid keys force a complete authoritative reconciliation. */
internal class DirectoryWatchCoordinator(private val roots:()->List<LocalRoot>,private val reconcile:suspend(LocalRoot)->Unit):AutoCloseable {
    private val watcher=java.nio.file.FileSystems.getDefault().newWatchService()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val keys=java.util.concurrent.ConcurrentHashMap<java.nio.file.WatchKey,LocalRoot>()
    private val pending=java.util.concurrent.ConcurrentHashMap<String,Job>()
    init {scope.launch{while(isActive){val key=try{watcher.take()}catch(_:java.nio.file.ClosedWatchServiceException){break};val root=keys[key]
        if(root!=null){key.pollEvents();pending[root.id]?.cancel();pending[root.id]=launch{delay(350);reconcile(root);register(root)}}
        if(!key.reset())keys.remove(key)
    }}
        scope.launch{while(isActive){delay(5000);roots().forEach{root->
            if(keys.values.none{it.id==root.id}&&Files.isDirectory(File(root.path).toPath(),NOFOLLOW_LINKS)){register(root);reconcile(root)}
        }}}
    }
    fun register(root:LocalRoot){
        val directory=File(root.path).toPath();if(!Files.isDirectory(directory,NOFOLLOW_LINKS))return
        Files.walk(directory,if(root.recursive)64 else 0).use{paths->paths.filter{Files.isDirectory(it,NOFOLLOW_LINKS)}.forEach{path->
            if(keys.keys.none{it.watchable()==path})keys[path.register(watcher,ENTRY_CREATE,ENTRY_DELETE,ENTRY_MODIFY)]=root
        }}
    }
    fun reconcileAll(){roots().forEach{root->scope.launch{register(root);reconcile(root);register(root)}}}
    override fun close(){watcher.close();scope.cancel();keys.keys.forEach{it.cancel()};keys.clear()}
}
