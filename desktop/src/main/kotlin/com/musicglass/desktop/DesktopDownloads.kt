package com.musicglass.desktop

import com.musicglass.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal data class DesktopDownloadItem(val job:DownloadJob,val song:SongItem)
internal enum class DownloadCheckpoint { BEFORE_VERIFY_RECEIPT,AFTER_VERIFY_RECEIPT,AFTER_RENAME,AFTER_COMPLETE }
internal class DownloadFailure(val code:String):IOException(code)

/** One bounded transfer at a time. Persistent intent, physical bytes and a verified commit are distinct. */
internal class DesktopDownloads(
    private val store:SqliteLibraryRepository,
    private val directory:File,
    private val openSource:suspend (String)->CachedByteSource,
    private val quotaBytes:Long=2L*1024*1024*1024,
    private val maximumTrackBytes:Long=128L*1024*1024,
    private val verify:(File)->Unit=::verifyDownloadedAudio,
    private val checkpoint:(DownloadCheckpoint)->Unit={}
):AutoCloseable {
    val repository=DownloadRepository(store)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val wake=Channel<Unit>(Channel.CONFLATED)
    private val guard=Any()
    private var active:Pair<Pair<String,String>,Job>?=null
    private val ownershipChannel:java.nio.channels.FileChannel
    private val ownership:java.nio.channels.FileLock
    private val worker:Job
    init {
        require(quotaBytes>0&&maximumTrackBytes>0&&maximumTrackBytes<=quotaBytes)
        check(directory.mkdirs()||directory.isDirectory)
        ownershipChannel=java.nio.channels.FileChannel.open(File(directory,"downloads.lock").toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)
        ownership=try{checkNotNull(ownershipChannel.tryLock()){"Download directory already has an active owner"}}catch(error:Throwable){ownershipChannel.close();throw error}
        worker=scope.launch {
            runInterruptible(Dispatchers.IO){recover()}
            while(isActive){
                cleanRemovals()
                val next=repository.list().firstOrNull{it.state==DownloadState.Queued}
                if(next==null){withTimeoutOrNull(30000){wake.receive()};continue}
                val task=scope.launch(start=CoroutineStart.LAZY){transfer(next)}
                synchronized(guard){active=(next.scope to next.trackId) to task;task.start()}
                task.join()
                synchronized(guard){if(active?.second===task)active=null}
            }
        }
    }
    fun keyFor(song:SongItem):Pair<String,String>{
        require(song.scope in setOf(Scopes.LOCAL,Scopes.GUEST)&&song.playbackSource.startsWith("youtube:")){"Only public provider audio can be downloaded"}
        return Scopes.GUEST to song.id
    }
    fun request(song:SongItem){val key=keyFor(song);request(song.record().copy(scope=key.first))}
    fun playbackSourceFor(song:SongItem):String?=when{
        song.scope==Scopes.GUEST->playbackSource(song.scope,song.id)
        song.scope==Scopes.LOCAL&&song.playbackSource.startsWith("youtube:")->playbackSource(Scopes.GUEST,song.id)
        else->null
    }
    fun request(track:TrackRecord){repository.request(track);wake.trySend(Unit)}
    fun pause(scope:String,id:String)=synchronized(guard){
        val job=repository.get(scope,id)?:return@synchronized
        if(job.state in setOf(DownloadState.Queued,DownloadState.Resolving,DownloadState.Transferring,DownloadState.Verifying)){
            repository.change(job.copy(state=DownloadState.Paused),setOf(job.state))
            if(active?.first==(scope to id))active?.second?.cancel()
        }
        wake.trySend(Unit)
    }
    fun resume(scope:String,id:String)=synchronized(guard){
        val job=repository.get(scope,id)?:return@synchronized
        if(job.state in setOf(DownloadState.Paused,DownloadState.Failed))repository.change(job.copy(state=DownloadState.Queued,errorCode=null),setOf(job.state))
        wake.trySend(Unit)
    }
    fun remove(scope:String,id:String)=synchronized(guard){
        val job=repository.get(scope,id)?:return@synchronized
        repository.change(job.copy(state=DownloadState.Removing),setOf(job.state))
        if(active?.first==(scope to id))active?.second?.cancel()
        wake.trySend(Unit)
    }
    private fun file(job:DownloadJob,partial:Boolean):File=File(directory,SqliteLibraryRepository.digest(job.scope+"\u0000"+job.trackId)+if(partial)".part"else".media")
    /** Called off the controller/UI thread. Never return a filename as proof of completion. */
    fun playbackSource(scope:String,id:String):String? {
        val job=repository.get(scope,id)?:return null
        if(job.state!=DownloadState.Complete)return null
        val path=file(job,false)
        if(!valid(path,job)){
            repository.change(job.copy(state=DownloadState.Failed,errorCode="INTEGRITY_FAILED"),setOf(DownloadState.Complete))
            throw DownloadFailure("INTEGRITY_FAILED")
        }
        return path.canonicalPath
    }
    private fun valid(path:File,job:DownloadJob)=path.isFile&&!Files.isSymbolicLink(path.toPath())&&path.length()==job.bytes&&job.sha256!=null&&digest(path)==job.sha256
    private fun recover(){
        for(job in repository.list())try{
            when(job.state){
                DownloadState.Complete->if(!valid(file(job,false),job))repository.change(job.copy(state=DownloadState.Failed,errorCode="INTEGRITY_FAILED"),setOf(job.state))
                DownloadState.Verifying->{
                    val committed=file(job,false);val partial=file(job,true)
                    if(job.sha256==null)repository.change(job.copy(state=DownloadState.Queued),setOf(job.state))
                    else if(valid(committed,job))repository.change(job.copy(state=DownloadState.Complete,errorCode=null),setOf(job.state))
                    else if(valid(partial,job)){Files.move(partial.toPath(),committed.toPath(),ATOMIC_MOVE);repository.change(job.copy(state=DownloadState.Complete,errorCode=null),setOf(job.state))}
                    else repository.change(job.copy(state=DownloadState.Failed,errorCode="INTEGRITY_FAILED"),setOf(job.state))
                }
                DownloadState.Resolving,DownloadState.Transferring->repository.change(job.copy(state=DownloadState.Queued),setOf(job.state))
                else->Unit
            }
        }catch(cancel:CancellationException){throw cancel}catch(error:Exception){if(Thread.currentThread().isInterrupted)throw error;repository.change(job.copy(state=DownloadState.Failed,errorCode="STORAGE_UNAVAILABLE"),setOf(job.state))}
    }
    private fun cleanRemovals(){
        for(job in repository.list().filter{it.state==DownloadState.Removing})try{
            Files.deleteIfExists(file(job,true).toPath());Files.deleteIfExists(file(job,false).toPath());Files.deleteIfExists(DesktopLoudnessReceipt.path(file(job,false)).toPath());repository.forget(job.scope,job.trackId)
        }catch(error:IOException){ /* Windows may keep an active decoder file locked; retain Removing and retry. */ }
    }
    private fun storedBytes():Long=Files.list(directory.toPath()).use{paths->
        val files=paths.limit(2002).toList();if(files.size>2000)throw DownloadFailure("STORAGE_LIMIT")
        files.sumOf{Files.size(it)}
    }
    private suspend fun transfer(original:DownloadJob){
        if(!repository.change(original.copy(state=DownloadState.Resolving,errorCode=null),setOf(DownloadState.Queued)))return
        val partial=file(original,true)
        try {
            require(original.scope==Scopes.GUEST)
            val id=original.trackId.removePrefix("youtube:").removePrefix("yt:")
            require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
            val final=file(original,false)
            val current=checkNotNull(repository.get(original.scope,original.trackId))
            if(current.sha256!=null&&valid(final,current)){
                repository.change(current.copy(state=DownloadState.Complete,errorCode=null),setOf(DownloadState.Resolving));return
            }
            if(final.exists())Files.delete(final.toPath())
            val source=openSource(id)
            val sourceLoudness=validLoudnessDb(source.loudnessDb)
            source.use { input->runInterruptible(Dispatchers.IO){
                val total=input.length()
                if(total>maximumTrackBytes)throw DownloadFailure("TRACK_TOO_LARGE")
                if(Files.isSymbolicLink(partial.toPath()))throw DownloadFailure("STORAGE_UNAVAILABLE")
                if(!repository.change(original.copy(state=DownloadState.Transferring,bytes=partial.length(),total=total,sha256=null,errorCode=null),setOf(DownloadState.Resolving)))return@runInterruptible
                RandomAccessFile(partial,"rw").use{output->
                    var position=0L;val existing=output.length();if(existing>maximumTrackBytes)throw DownloadFailure("TRACK_TOO_LARGE")
                    val received=ByteArray(32768);val previous=ByteArray(32768);var checkpointAt=0L
                    while(true){
                        if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
                        if(repository.get(original.scope,original.trackId)?.state!=DownloadState.Transferring)throw CancellationException("Download intent changed")
                        val count=input.readAt(position,received,0,received.size)
                        if(count<0)break
                        if(count==0||count>received.size)throw DownloadFailure("INCOMPLETE_SOURCE")
                        if(position+count>maximumTrackBytes)throw DownloadFailure("TRACK_TOO_LARGE")
                        val compare=minOf(count.toLong(),(existing-position).coerceAtLeast(0)).toInt()
                        if(compare>0){output.seek(position);output.readFully(previous,0,compare);for(i in 0 until compare)if(previous[i]!=received[i])throw DownloadFailure("REPRESENTATION_CHANGED")}
                        if(compare<count){
                            val added=count-compare
                            if(storedBytes()+added>quotaBytes||directory.usableSpace<added+32L*1024*1024)throw DownloadFailure("STORAGE_LIMIT")
                            output.seek(position+compare);output.write(received,compare,added)
                        }
                        position+=count
                        if(position-checkpointAt>=1048576){output.channel.force(true);repository.change(original.copy(state=DownloadState.Transferring,bytes=position,total=total),setOf(DownloadState.Transferring),durable=false);checkpointAt=position}
                    }
                    if(position<existing||(total>=0&&position!=total)||position==0L)throw DownloadFailure("INCOMPLETE_SOURCE")
                    output.setLength(position);output.channel.force(true)
                }
            } }
            currentCoroutineContext().ensureActive()
            if(!repository.change(original.copy(state=DownloadState.Verifying,bytes=partial.length(),total=partial.length()),setOf(DownloadState.Transferring)))return
            runInterruptible(Dispatchers.IO){verify(partial)}
            val bytes=partial.length();val sha=runInterruptible(Dispatchers.IO){digest(partial)}
            val receipt=original.copy(state=DownloadState.Verifying,bytes=bytes,total=bytes,sha256=sha,errorCode=null)
            checkpoint(DownloadCheckpoint.BEFORE_VERIFY_RECEIPT)
            synchronized(guard){
                if(!repository.change(receipt,setOf(DownloadState.Verifying)))return
                checkpoint(DownloadCheckpoint.AFTER_VERIFY_RECEIPT)
                DesktopLoudnessReceipt.write(final,sha,sourceLoudness)
                Files.move(partial.toPath(),final.toPath(),ATOMIC_MOVE)
                checkpoint(DownloadCheckpoint.AFTER_RENAME)
                check(repository.change(receipt.copy(state=DownloadState.Complete),setOf(DownloadState.Verifying)))
                checkpoint(DownloadCheckpoint.AFTER_COMPLETE)
            }
        }catch(cancel:CancellationException){
            repository.get(original.scope,original.trackId)?.let{job->repository.change(job.copy(state=DownloadState.Paused),setOf(DownloadState.Resolving,DownloadState.Transferring))}
            throw cancel
        }catch(error:Exception){
            val code=(error as? DownloadFailure)?.code?:if(error is IOException)"TRANSFER_FAILED"else"DECODE_OR_STORAGE_FAILED"
            if(code=="REPRESENTATION_CHANGED")try{Files.deleteIfExists(partial.toPath())}catch(_:IOException){}
            repository.get(original.scope,original.trackId)?.let{job->repository.change(job.copy(state=DownloadState.Failed,errorCode=code),setOf(DownloadState.Resolving,DownloadState.Transferring,DownloadState.Verifying))}
        }
    }
    override fun close(){
        scope.cancel();runBlocking{withTimeout(10000){worker.join();scope.coroutineContext[Job]?.children?.toList()?.joinAll()}};wake.close();ownership.release();ownershipChannel.close()
    }
    companion object {
        private fun digest(file:File):String {
            val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().use{input->val buffer=ByteArray(65536);while(true){if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException();val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)}}
            return hash.digest().joinToString(""){"%02x".format(it)}
        }
    }
}
internal fun verifyDownloadedAudio(file:File){
    FfmpegPcmDecoder(file.path).use{decoder->
        val pcm=FloatArray(8192);var frames=0L
        while(true){
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException()
            val count=decoder.read(pcm,4096);if(count==0)break
            for(i in 0 until count*2)if(!pcm[i].isFinite())throw DownloadFailure("CORRUPT_AUDIO")
            frames+=count;if(frames>48000L*60*60*4)throw DownloadFailure("DURATION_LIMIT")
        }
        if(frames==0L)throw DownloadFailure("EMPTY_AUDIO")
    }
}
