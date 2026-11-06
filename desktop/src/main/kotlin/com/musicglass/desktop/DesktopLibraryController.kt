package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.compose.ui.graphics.Color

internal data class LibraryProjection(val tracks:List<SongItem>,val count:Long,val offset:Long,val playlists:List<PlaylistItem>,val encore:EncoreSummary,val privacy:PrivacyPreferences,val scanIssues:List<Pair<String,String>>,val downloads:List<DesktopDownloadItem>)

/** The relational store is production authority. There is no fixture or file-store fallback. */
internal class DesktopLibraryController(directory:File,bridge:DesktopAudioBridge,private val publish:(LibraryProjection)->Unit,private val progress:(String)->Unit):AutoCloseable {
    val store=SqliteLibraryRepository(File(directory,"library"))
    val artwork=ArtworkRepository(File(directory,"cover-art"),File(directory,"artwork-incoming"))
    val playlists=PlaylistRepository(store)
    val history=HistoryRepository(store)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val genres=DesktopEncoreGenres(store)
    private var genreJob:Job?=null
    private var lastGenreCheck=0L
    private val probe=DesktopMediaProbe(bridge)
    private val scanLock=kotlinx.coroutines.sync.Mutex()
    @Volatile private var pageOffset=0L
    private val limit=500
    @Volatile private var selectedPlaylist:String?=null
    @Volatile private var playlistOffset=0L
    fun selectPlaylist(id:String,offset:Long=0){require(offset>=0);selectedPlaylist=id;playlistOffset=offset;scope.launch{refresh()}}
    private var priorAnchor:AudioEvent.ClockAnchor?=null
    private var pendingHeard=0L
    private var pendingSong:SongItem?=null
    private var pendingOccurrence:String?=null
    private var pendingStarted=0L
    private var pendingHistoryEpoch=0L
    private var listeningEntry:String?=null
    private var listeningOccurrence=java.util.UUID.randomUUID().toString()
    private lateinit var historyJob:Job
    private val heardChannel=kotlinx.coroutines.channels.Channel<Triple<SongItem,ListeningSpan,Long>>(512)
    fun presented(anchor:AudioEvent.ClockAnchor,song:SongItem){
        val prior=priorAnchor;priorAnchor=anchor
        if(listeningEntry!=anchor.currentEntryId||(prior!=null&&prior.timelineEpoch==anchor.timelineEpoch&&prior.contentPositionMs>anchor.contentPositionMs+200)){
            flushListening();listeningEntry=anchor.currentEntryId;listeningOccurrence=java.util.UUID.randomUUID().toString()
        }
        if(prior==null || anchor.timelineEpoch!=prior.timelineEpoch || anchor.currentEntryId!=prior.currentEntryId || anchor.sampleRate!=prior.sampleRate){flushListening();return}
        val ms=(anchor.samplesRendered-prior.samplesRendered)*1000/anchor.sampleRate
        if(ms !in 1..5000)return
        if(pendingSong?.id!=song.id || pendingOccurrence!=listeningOccurrence)flushListening()
        if(pendingHeard==0L){pendingStarted=System.currentTimeMillis()-ms;pendingSong=song;pendingOccurrence=listeningOccurrence;pendingHistoryEpoch=history.epoch(song.scope)}
        pendingHeard+=ms
        if(pendingHeard>=1000)flushListening()
    }
    private fun flushListening(){
        val song=pendingSong;val occurrence=pendingOccurrence;val ms=pendingHeard
        if(song!=null&&occurrence!=null&&ms>0){
            var remaining=ms;var started=pendingStarted
            while(remaining>0){
                val heard=minOf(remaining,5000)
                val span=ListeningSpan(song.scope,java.util.UUID.randomUUID().toString(),occurrence,song.id,started,heard)
                check(heardChannel.trySend(Triple(song,span,pendingHistoryEpoch)).isSuccess){"Listening history writer is overloaded"}
                started+=heard;remaining-=heard
            }
        }
        pendingHeard=0;pendingSong=null;pendingOccurrence=null
    }
    private val scanner=LibraryScanner(store,probe::probe){record->decorate(record)}
    private val watcher=DirectoryWatchCoordinator(store::roots){root->scan(root)}
    init {
        scope.launch {store.revisions.debounce(100).collect{refresh()}}
        history.retain(Scopes.LOCAL)
        history.retain(Scopes.GUEST)
        historyJob=scope.launch{for((song,span,epoch) in heardChannel){if(store.privacy(song.scope).saveHistory){if(store.get(song.scope,song.id)==null)store.upsert(song.record());history.record(span,epoch)}}}
    }
    fun stopped(){flushListening();priorAnchor=null}
    private fun resetListening(){pendingHeard=0;pendingSong=null;pendingOccurrence=null;priorAnchor=null;listeningEntry=null}
    fun clearHistory(){
        resetListening()
        store.transaction(durable=true){history.clear(Scopes.LOCAL);history.clear(Scopes.GUEST)}
    }
    fun setHistoryPrivacy(preferences:PrivacyPreferences){
        if(store.privacy(Scopes.LOCAL).genreLookup!=preferences.genreLookup){genres.invalidate();genreJob?.cancel();lastGenreCheck=0L}
        resetListening()
        store.transaction(durable=true){setPrivacy(Scopes.LOCAL,preferences);setPrivacy(Scopes.GUEST,preferences)}
        history.retain(Scopes.LOCAL);history.retain(Scopes.GUEST)
    }
    suspend fun decorate(record:TrackRecord):TrackRecord=try{artwork.obtain(record)}catch(cancel:CancellationException){throw cancel}catch(error:Exception){progress("Cover unavailable for ${record.title}: ${error.message?.take(100)}");record.copy(artworkUrl=record.artworkUrl?.takeUnless{it.startsWith("incoming:")})}
    fun migrate(snapshot:String,tracks:List<SongItem>){
        if(tracks.isNotEmpty())LegacyDesktopStoreReader(store).importVerifiedSnapshot(snapshot,tracks.map{it.record()})
    }
    fun importLegacy(directory:File){
        val files=listOf(File(directory,"library-state.db"),File(directory,"library-state.db-wal")).filter{it.isFile}
        if(files.isEmpty())return
        val reader=LegacyDesktopStoreReader(store)
        // Every original is independently backed up and audited before any new authority is used.
        val schema=files.firstOrNull{it.name=="library-state.db"&&it.length()<=32L*1024*1024}?.bufferedReader()?.use{it.readLine()?.removePrefix("SCHEMA|")?.toIntOrNull()}
        files.forEach{file->val result=reader.importFile(file,expectedSchema=schema);progress("Legacy import: ${result.imported} adopted, ${result.quarantined} quarantined. Report: ${result.reportPath}")}
    }
    fun start(){watcher.reconcileAll();scope.launch{refresh()}}
    fun loadMore(){pageOffset=(pageOffset+limit).coerceAtMost(((store.count(Scopes.LOCAL)-1).coerceAtLeast(0)/limit)*limit);scope.launch{refresh()}}
    fun previousPage(){pageOffset=(pageOffset-limit).coerceAtLeast(0);scope.launch{refresh()}}
    fun register(directory:File){scope.launch{try{
        val canonical=directory.canonicalPath
        val root=LocalRoot(SqliteLibraryRepository.digest(canonical),canonical,true)
        store.registerRoot(root);watcher.register(root);scan(root);watcher.register(root)
    }catch(cancel:CancellationException){throw cancel}catch(error:Exception){progress("Library scan failed: ${error.message?.take(160)}")}}}
    private suspend fun scan(root:LocalRoot){scanLock.lock();try{scanner.scan(root).collect{state->progress(state.error?:if(state.complete)"Library: ${state.imported} imported, ${state.rejected} rejected" else "Scanning: ${state.imported}/${state.discovered}, ${state.rejected} rejected")}}catch(cancel:CancellationException){throw cancel}catch(error:Exception){progress("Library scan failed: ${error.message?.take(160)}")}finally{scanLock.unlock()}}
    suspend fun import(file:File):SongItem {
        val record=decorate(runInterruptible(Dispatchers.IO){probe.probe(file)})
        val existing=record.fileIdentity?.let(store::byIdentity)
        val actual=record.copy(id=existing?.id?:record.id)
        store.transaction(durable=true){
            upsert(actual)
            val attrs=java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java)
            recordLocation(file.canonicalPath,null,actual.id,checkNotNull(actual.fileIdentity),attrs.lastModifiedTime().toMillis(),attrs.size(),0)
        }
        return song(checkNotNull(store.get(actual.scope,actual.id)))
    }
    fun search(query:String):List<SongItem> =(store.search(Scopes.LOCAL,query)+store.search(Scopes.GUEST,query)).map(::song)
    suspend fun saveProvider(tracks:List<SongItem>,isCurrent:()->Boolean={true}):List<SongItem> =withContext(Dispatchers.IO){tracks.map{item->
        val record=decorate(item.record().copy(scope=Scopes.GUEST,sourceKind="youtube",sourcePath=null))
        if(!isCurrent())throw CancellationException("Account changed while loading")
        store.upsert(record);song(checkNotNull(store.get(record.scope,record.id)))
    }}
    fun song(record:TrackRecord):SongItem {
        val art=record.artworkKey?.let(artwork::metadata)
        return SongItem(record.id,record.title,record.artist,record.album,record.durationMs,Color(art?.dominant?:0xFF181A22.toInt()),art?.mesh?:IntArray(36){0xFF181A22.toInt()},emptyList(),record.scope,record.sourcePath,record.artworkKey,record.albumId,record.artistId,record.available,record.artworkUrl,record.isEpisode)
    }
    fun observeEncore(period:com.musicglass.data.encore.EncorePeriod):Flow<com.musicglass.data.encore.EncoreSummary> = flow {
        val scopes=listOf(Scopes.LOCAL,Scopes.GUEST)
        com.musicglass.data.encore.EncoreRepository(com.musicglass.data.encore.EncoreReadDatabase(store),genres={names->genres.cached(scopes,names)}).use{repository->
            fun read():com.musicglass.data.encore.EncoreSummary {
                val actual=if(period.range==com.musicglass.data.encore.EncoreRange.All)
                    com.musicglass.data.encore.EncorePeriod.containing(period.range,period.end.minusDays(1),com.musicglass.data.encore.EncorePeriod.firstDayOfWeek(),repository.historyStart(scopes))else period
                val value=repository.summary(scopes,actual,System.currentTimeMillis())
                if(scopes.any{store.privacy(it).genreLookup}&&genreJob?.isActive!=true&&System.currentTimeMillis()-lastGenreCheck>=60000){
                    val candidates=repository.genreArtists(scopes);lastGenreCheck=System.currentTimeMillis()
                    genreJob=this@DesktopLibraryController.scope.launch{genres.refresh(candidates)}
                }
                return value
            }
            emit(read());emitAll(store.revisions.sample(2000).map{read()})
        }
    }.flowOn(Dispatchers.IO)
    fun refresh(){
        val tracks=store.tracks(Scopes.LOCAL,limit,pageOffset).map(::song)
        val list=playlists.list(Scopes.LOCAL).map{p->val selected=selectedPlaylist==p.id;val entries=playlists.entries(p.scope,p.id,if(selected)500 else 1,if(selected)playlistOffset else 0);val songs=entries.map{song(it.track)};PlaylistItem(p.id,p.title,p.description,songs.firstOrNull()?.dominantColor?:Color(0xFF181A22),songs,if(selected)entries.map{it.entryId}else emptyList(),playlists.count(p.scope,p.id),if(selected)playlistOffset else 0)}
        publish(LibraryProjection(tracks,store.count(Scopes.LOCAL),pageOffset,list,history.summary(listOf(Scopes.LOCAL,Scopes.GUEST)),store.privacy(Scopes.LOCAL),store.scanIssues(),DownloadRepository(store).list().mapNotNull{job->store.get(job.scope,job.trackId)?.let{DesktopDownloadItem(job,song(it))}}))
    }
    override fun close(){flushListening();heardChannel.close();watcher.close();runBlocking{withTimeoutOrNull(5000){historyJob.join();scope.coroutineContext[Job]?.children?.toList()?.let{jobs->jobs.forEach{it.cancel()};jobs.joinAll()}}};scope.cancel();genres.close();store.close()}
}

internal fun SongItem.record()=TrackRecord(scope,id,title,artist,album,durationMs,if(playbackSource.startsWith("youtube:")||playbackSource.startsWith("yt:"))"youtube"else"local",sourcePath?:id.takeIf{File(it).isAbsolute},artistId=artistId,albumId=albumId,artworkKey=artworkKey,artworkUrl=artworkUrl,available=available,isEpisode=isEpisode)
