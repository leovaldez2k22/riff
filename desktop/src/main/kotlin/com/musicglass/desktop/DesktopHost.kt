package com.musicglass.desktop

import androidx.compose.ui.graphics.Color
import java.io.File
import com.musicglass.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Transport-independent application boundary commands for desktop UI. */
internal sealed interface DesktopCommand {
    data object ShowMain : DesktopCommand
    data object Quit : DesktopCommand
    data class SetReducedMotion(val enabled: Boolean) : DesktopCommand
    data class SetGlassQuality(val quality: GlassQuality) : DesktopCommand
    data class SetRoute(val route: WorkspaceRoute) : DesktopCommand
    data object NavigateBack : DesktopCommand
    data object TogglePlayPause : DesktopCommand
    data class SetPlaying(val isPlaying: Boolean) : DesktopCommand
    data class SeekTo(val positionMs: Long) : DesktopCommand
    data object NextTrack : DesktopCommand
    data object PreviousTrack : DesktopCommand
    data object ToggleShuffle : DesktopCommand
    data object ToggleRepeat : DesktopCommand
    data class SetVolume(val fraction: Float) : DesktopCommand
    data class ToggleInspector(val tab: InspectorTab) : DesktopCommand
    data class SetWindowMode(val mode: WindowMode) : DesktopCommand
    data class PlayPlaylist(val id:String):DesktopCommand
    data class PlayTrack(val track: SongItem) : DesktopCommand
    data class PlayQueueEntry(val entryId:String,val expectedRevision:Long):DesktopCommand
    data class PlayCollection(val tracks: List<SongItem>, val startIndex: Int = 0) : DesktopCommand
    data class ReorderQueue(val fromIndex: Int, val toIndex: Int) : DesktopCommand
    data class ReorderQueueByEntryId(val entryId: String, val moveUp: Boolean, val expectedRevision: Long? = null) : DesktopCommand
    data class MoveQueueEntryToPosition(val entryId: String, val targetIndex: Int, val expectedRevision: Long? = null) : DesktopCommand
    data class RemoveFromQueue(val trackId: String) : DesktopCommand
    data class RemoveQueueEntry(val queueId: String) : DesktopCommand
    data object UndoQueueAction : DesktopCommand
    data class AddFileToQueue(val file: File) : DesktopCommand
    data class DismissNotification(val dummy: Unit = Unit) : DesktopCommand
    data class SetSearchQuery(val query: String) : DesktopCommand
    data class SubmitSearch(val query:String):DesktopCommand
    data class OpenYouTubeLink(val text:String):DesktopCommand
    data class SetSearchSuggestions(val enabled:Boolean):DesktopCommand
    data class SetSidebarWidth(val widthDp: Float) : DesktopCommand
    data class SetInspectorWidth(val widthDp: Float) : DesktopCommand
    data class SetAlwaysOnTop(val enabled: Boolean) : DesktopCommand
    data class SetMoodFilter(val mood: String?) : DesktopCommand
    data class SetPlaybackSpeed(val rate:Float,val expectedEntry:String?=null):DesktopCommand
    data class SetEqualizer(val configuration:EqualizerConfig):DesktopCommand
    data class SetEqPreset(val preset: String) : DesktopCommand
    data class SetVolumeBoost(val enabled: Boolean) : DesktopCommand
    data class SetNormalization(val enabled:Boolean):DesktopCommand
    data class SetCrossfadeEnabled(val enabled: Boolean) : DesktopCommand
    data class OpenAlbumDetail(val albumId: String) : DesktopCommand
    data class OpenArtistDetail(val artistId: String) : DesktopCommand
    data class OpenPlaylistDetail(val playlistId: String) : DesktopCommand
    data class RegisterLibraryRoot(val directory:File):DesktopCommand
    data class BrowseProvider(val id:String="FEmusic_home",val cursor:String?=null,val params:String?=null,val radioVideoId:String?=null,val query:String?=null):DesktopCommand
    data class LocalCollectionPage(val offset:Long):DesktopCommand
    data class PlaylistPage(val id:String,val offset:Long):DesktopCommand
    data class MovePlaylistOccurrence(val id:String,val entry:String,val up:Boolean):DesktopCommand
    data object PreviousLibraryPage:DesktopCommand
    data object LoadMoreLibrary:DesktopCommand
    data class CreatePlaylist(val title:String):DesktopCommand
    data class RenamePlaylist(val id:String,val title:String):DesktopCommand
    data class DeletePlaylist(val id:String):DesktopCommand
    data class AddToPlaylist(val id:String,val track:SongItem):DesktopCommand
    data class RemovePlaylistEntry(val id:String,val entryId:String):DesktopCommand
    data class ReorderPlaylist(val id:String,val entries:List<String>):DesktopCommand
    data class SetHistoryPrivacy(val preferences:PrivacyPreferences):DesktopCommand
    data object SignIn:DesktopCommand
    data object SignOut:DesktopCommand
    data class MarkEncoreStorySeen(val period:com.musicglass.data.encore.EncorePeriod):DesktopCommand
    data object ClearHistory:DesktopCommand
    data class SetSleepTimer(val plan:SleepPlan):DesktopCommand
    data class ToggleSavedTrack(val track:SongItem):DesktopCommand
    data class DownloadTrack(val track:SongItem):DesktopCommand
    data class PauseDownload(val scope:String,val id:String,val paused:Boolean):DesktopCommand
    data class RemoveDownload(val scope:String,val id:String):DesktopCommand
    data object UndoDownloadRemoval:DesktopCommand
    data object LoadLyrics:DesktopCommand
    data class SelectLyricsCandidate(val id:String):DesktopCommand
    data class SetLyricsPreferences(val offsetMs:Long,val romanized:Boolean,val mode:LyricsRomanMode?=null):DesktopCommand
    data object OpenFocusedPlayer : DesktopCommand
}

/** Complete snapshot of desktop application state. */
internal data class HostSnapshot(
    val protocolMajor: Int = 2,
    val running: Boolean = true,
    val reducedMotion: Boolean = false,
    val glassQuality: GlassQuality = GlassQuality.Full,
    val currentRoute: WorkspaceRoute = WorkspaceRoute.Home,
    val previousRoute: WorkspaceRoute? = null,
    val windowMode: WindowMode = WindowMode.Main,
    val savedRouteBeforeMini: WorkspaceRoute = WorkspaceRoute.Home,
    val savedInspectorTabBeforeMini: InspectorTab = InspectorTab.None,
    val inspectorTab: InspectorTab = InspectorTab.None,
    val currentTrack: SongItem = EmptyDesktopTrack,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val volume: Float = 0.8f,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val queueEntries: List<QueueEntry> = emptyList(),
    val queueRevision: Long = 0L,
    val currentEntryId: String? = null,
    val secondaryEntryId: String? = null,
    val secondaryPositionMs: Long = 0L,
    val crossfadeEnabled: Boolean = false,
    val initialized: Boolean = false,
    val busy: Boolean = false,
    val isCurrentTrackSaved:Boolean=false,
    val downloads:List<DesktopDownloadItem> = emptyList(),
    val pendingDownloadRemoval:Pair<String,String>?=null,
    val libraryTracks: List<SongItem> = emptyList(),
    val libraryCount:Long=0,
    val libraryOffset:Long=0,
    val collectionTracks:List<SongItem> = emptyList(),
    val collectionState:PageState<SongItem> = PageState.Empty,
    val searchStatus:String?=null,
    val suggestions:SearchSuggestions=SearchSuggestions(),
    val searchSuggestionsEnabled:Boolean=true,
    val recentSearches:Map<String,List<String>> = emptyMap(),
    val sleep:SleepPlan=SleepPlan(),
    val lyricsState:LyricsLoadState=LyricsLoadState.Idle,
    val lyricsTrackId:String?=null,
    val lyricsRomanMode:LyricsRomanMode=LyricsRomanMode.Original,
    val collectionKind:String?=null,
    val collectionId:String?=null,
    val collectionOffset:Long=0,
    val collectionCount:Long=0,
    val catalogPage:PageState<ProviderHomeCard> = PageState.Empty,
    val catalogTitle:String="Discover",
    val catalogRequest:DesktopCommand.BrowseProvider=DesktopCommand.BrowseProvider(),
    val libraryPlaylists:List<PlaylistItem> = emptyList(),
    val scanStatus:String?=null,
    val scanIssues:List<Pair<String,String>> = emptyList(),
    val encore:EncoreSummary?=null,
    val encoreSeen:Set<String> = emptySet(),
    val historyPrivacy:PrivacyPreferences=PrivacyPreferences(),
    val sourceDescriptors: Map<String, DurableAudioSource> = emptyMap(),
    val searchResults: List<SongItem> = emptyList(),
    val searchCards: List<ProviderHomeCard> = emptyList(),
    val searchTopResult: ProviderHomeCard? = null,
    val shuffleSeed: Long = 0L,
    val unshuffledOrder: List<String> = emptyList(),
    val lastRemovedQueueItem: Pair<Int, QueueEntry>? = null,
    val notificationMessage: String? = null,
    val searchQuery: String = "",
    val sidebarWidthDp: Float = 224f,
    val inspectorWidthDp: Float = 360f,
    val isAlwaysOnTop: Boolean = false,
    val moodFilter: String? = null,
    val musicPlaybackRate:Float=1f,
    val episodePlaybackRate:Float=1f,
    val eqPreset: String = "Flat",
    val equalizerConfig:EqualizerConfig=EqualizerConfig(),
    val volumeBoost: Boolean = false,
    val normalizationEnabled:Boolean=false,
    val activeAlbumId: String? = null,
    val activeArtistId: String? = null,
    val activePlaylistId: String? = null
) {
    /** Backward compatible queue access for legacy callers and tests. */
    val queue: List<SongItem> get() = queueEntries.map { it.song }
}

internal val EmptyDesktopTrack = SongItem("", "Choose a track", "", "", 0L, Color(0xFF181A22), IntArray(36) { 0xFF181A22.toInt() }, emptyList())

/** One actor owns durable desired state; only worker acknowledgements/anchors establish playback. */
internal class DesktopHost(
    val audioBridge: DesktopAudioBridge = DesktopAudioBridge(),
    private val directory: File = DesktopPaths.root,
    private val executor: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "RiffController").apply { isDaemon = true } },
    initialState: HostSnapshot = HostSnapshot(),
    private val describeSource: (String) -> DurableAudioSource = ::describeAudioSource,
    private val audioExecutor:java.util.concurrent.Executor=java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,java.util.concurrent.LinkedBlockingQueue(2),{r->Thread(r,"RiffAudioOperations").apply{isDaemon=true}},java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()),
    private val enableDataLayer:Boolean = true,
    private val catalogGateway:(suspend (DesktopCommand.BrowseProvider)->DesktopCatalogPage)?=null,
    private val searchProvider: (suspend (String) -> List<SongItem>)? = null
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<HostSnapshot> = mutableState
    val snapshot get() = mutableState.value
    val clockAnchor get() = audioBridge.clockAnchor
    val spectrumFrame get() = audioBridge.spectrumFrame
    private var repository: DesktopSessionRepository? = null
    private var library:DesktopLibraryController?=null
    private val emptyGenreState=MutableStateFlow<GenreLookupState>(GenreLookupState.Idle)
    val genreLookupState:StateFlow<GenreLookupState> get()=library?.genres?.state?:emptyGenreState
    suspend fun encoreGenreFacts(names:List<String>):Map<String,List<String>> = kotlinx.coroutines.withContext(Dispatchers.IO){library?.genres?.cached(listOf(Scopes.LOCAL,Scopes.GUEST),names)?:emptyMap()}

    suspend fun encoreHistory(kind:String,key:String):com.musicglass.data.encore.EncoreHistory?=kotlinx.coroutines.withContext(Dispatchers.IO){
        val authority=library?:return@withContext null
        com.musicglass.data.encore.EncoreRepository(com.musicglass.data.encore.EncoreReadDatabase(authority.store)).use{it.history(listOf(Scopes.LOCAL,Scopes.GUEST),kind,key,java.time.LocalDate.now())}
    }
    suspend fun encoreTrack(id:String):SongItem?=kotlinx.coroutines.withContext(Dispatchers.IO){
        val authority=library?:return@withContext null
        (authority.store.get(Scopes.GUEST,id)?:authority.store.get(Scopes.LOCAL,id))?.let(authority::song)
    }
    fun observeEncore(period:com.musicglass.data.encore.EncorePeriod):kotlinx.coroutines.flow.Flow<com.musicglass.data.encore.EncoreSummary?> =
        library?.observeEncore(period)?:kotlinx.coroutines.flow.flowOf(null)

    private var downloads:DesktopDownloads?=null
    private var downloadRemovalGeneration=0L
    private val downloadProvider=lazy{DesktopProvider(File(directory,"download-provider"))}
    private val lyricsLookup=DesktopLyricsLookup()
    private var lyricsRepository:SyncedLyricsRepository?=null
    private var lyricsJob:kotlinx.coroutines.Job?=null
    val artworkRepository get()=library?.artwork
    val accounts=AccountSessionCoordinator()
    private val publicCatalogProvider=lazy{DesktopProvider(File(directory,"provider"))}
    private val authentication=DesktopAuthenticationCoordinator(directory,accounts)
    val isAuthenticationConfigured get()=authentication.configured
    private var authenticationJob:kotlinx.coroutines.Job?=null
    private var catalogJob:kotlinx.coroutines.Job?=null
    private var searchJob: kotlinx.coroutines.Job? = null
    private var suggestionsJob:kotlinx.coroutines.Job?=null
    private var linkJob:kotlinx.coroutines.Job?=null
    private val linkGeneration=java.util.concurrent.atomic.AtomicLong()
    private val searchGeneration=java.util.concurrent.atomic.AtomicLong()
    private val audioOperationGeneration=java.util.concurrent.atomic.AtomicLong()
    private var audioOperationPending=false
    private var lastEpoch = 0L
    @Volatile private var deviceRecoveryPending=false
    private val recoveryAttempt=java.util.concurrent.atomic.AtomicBoolean()
    private var workerStartedNanos = 0L
    private val recoveryTimes = java.util.ArrayDeque<Long>()
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"RiffCheckpoint").apply { isDaemon=true } }
    private val guard = Any()
    init {
        enqueue {
            repository = DesktopSessionRepository(directory)
            val saved=repository?.load()
            saved?.let { restore(it) }
            if(enableDataLayer){
                library=DesktopLibraryController(directory,audioBridge,{projection->scope.launch{enqueue{if(snapshot.running)mutableState.value=snapshot.copy(libraryTracks=projection.tracks,libraryCount=projection.count,libraryOffset=projection.offset,libraryPlaylists=projection.playlists,encore=projection.encore,historyPrivacy=projection.privacy,scanIssues=projection.scanIssues,downloads=projection.downloads)}}},{message->scope.launch{enqueue{if(snapshot.running)mutableState.value=snapshot.copy(scanStatus=message)}}})
                downloads=DesktopDownloads(library!!.store,File(directory,"downloads"),{id->downloadProvider.value.openAudio(id)})
                lyricsRepository=SyncedLyricsRepository(library!!.store)
                if(saved!=null)library!!.migrate(saved.toString(),snapshot.libraryTracks)
                if(System.getProperty("riff.data.dir")==null && directory.canonicalFile==DesktopPaths.root.canonicalFile)library!!.importLegacy(File(System.getProperty("user.home"),".riff/db"))
                // Upgrade old checkpoints from the already-authoritative local metadata, with no network fetch.
                val data=checkNotNull(library)
                val restored=snapshot.queueEntries.map{entry->
                    val record=data.store.get(entry.song.scope,entry.song.id)
                    if(record?.artworkKey!=null){val cached=data.song(record);entry.copy(song=entry.song.copy(artworkKey=cached.artworkKey,artworkUrl=cached.artworkUrl,dominantColor=cached.dominantColor,deckMesh=cached.deckMesh))}else entry
                }
                val selected=restored.firstOrNull{it.queueId==snapshot.currentEntryId}?.song?:snapshot.currentTrack
                selected.artworkKey?.let{key->runBlocking(Dispatchers.IO){data.artwork.bitmap(key)}}
                mutableState.value=snapshot.copy(queueEntries=restored,currentTrack=selected)
                library!!.refresh()
            }
            call(AudioCommand.Handshake(audioBridge.nextId(), AudioProtocol.BUILD_ID, java.security.SecureRandom().nextLong()))
            if(saved==null && System.getProperty("riff.data.dir")==null && directory.canonicalFile==DesktopPaths.root.canonicalFile) {
                try {
                    LegacySessionImporter.inspect(File(System.getProperty("user.home"),".riff/db"),directory)?.let{old->
                        old.paths.forEach{handle(DesktopCommand.AddFileToQueue(File(it)))}
                        val current=snapshot.queueEntries.first{it.song.id==old.current}
                        mutableState.value=snapshot.copy(currentEntryId=current.queueId,currentTrack=current.song,playbackPositionMs=old.positionMs)
                        persistPlaybackSnapshot()
                    }
                }catch(t:Exception){mutableState.value=snapshot.copy(notificationMessage="Legacy queue was preserved for review: ${t.message?.take(160)}")}
            }
            if(snapshot.queueEntries.isNotEmpty()) syncAudioQueue(false)
            library?.start()
            if(authentication.configured)authenticationJob=scope.launch{try{authentication.restoreSession()}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(error:Exception){enqueue{mutableState.value=snapshot.copy(notificationMessage="Session restoration failed; sign in again")}}}
            mutableState.value = snapshot.copy(initialized=true,isPlaying=false)
        }
        scope.launch { audioBridge.clockAnchor.collect { anchor -> if(anchor!=null) enqueue {
            if(!audioOperationPending&&anchor.timelineEpoch>=lastEpoch && anchor.monotonicTimestampNanos>=workerStartedNanos && snapshot.running) {
                val wasSameEpoch=lastEpoch==anchor.timelineEpoch
                val previousEntry=snapshot.currentEntryId
                val previousPosition=snapshot.playbackPositionMs
                val previousSleep=snapshot.sleep
                lastEpoch=anchor.timelineEpoch
                val entry=snapshot.queueEntries.firstOrNull { it.queueId==anchor.currentEntryId }
                if(entry!=null) library?.presented(anchor,entry.song)
                if(entry!=null) mutableState.value=snapshot.copy(currentEntryId=entry.queueId,currentTrack=entry.song,
                    playbackPositionMs=anchor.contentPositionMs,secondaryEntryId=anchor.secondaryEntryId,secondaryPositionMs=anchor.secondaryPositionMs,sleep=anchor.sleep)
                if(previousSleep!=anchor.sleep)persistPlaybackSnapshot()
                if(entry!=null&&snapshot.isPlaying&&(previousEntry!=entry.queueId||(wasSameEpoch&&anchor.contentPositionMs+200<previousPosition)))loadLyrics()
            }
        } } }
        scope.launch { audioBridge.playbackState.collect { event -> if(event!=null) enqueue {
            if(event.timelineEpoch>=lastEpoch && snapshot.running) {
                val buffering=audioBridge.bufferingState.value?.let{it.timelineEpoch>=lastEpoch && it.isBuffering}?:false
                mutableState.value=snapshot.copy(isPlaying=event.isPlaying,playWhenReady=if(buffering||audioOperationPending)snapshot.playWhenReady else event.isPlaying,isBuffering=buffering,busy=buffering||audioOperationPending)
                if(event.isPlaying)loadLyrics()
                if(!event.isPlaying){library?.stopped();persistPlaybackSnapshot()}
            }
        } } }
        scope.launch {audioBridge.bufferingState.collect {event->if(event!=null)enqueue {
            if(snapshot.running && event.timelineEpoch>=lastEpoch)mutableState.value=snapshot.copy(isBuffering=event.isBuffering,busy=event.isBuffering||audioOperationPending)
        }}}
        scope.launch { audioBridge.faultEvent.collect { fault -> if(fault!=null) enqueue {
            if(!snapshot.running)return@enqueue
            if(fault.errorCode=="NEXT_TRACK_UNAVAILABLE"){
                if(fault.timelineEpoch>=lastEpoch)mutableState.value=snapshot.copy(notificationMessage=fault.message)
                return@enqueue
            }
            val resumeDevice=fault.errorCode=="AUDIO_DEVICE_LOST"&&(snapshot.playWhenReady||snapshot.isPlaying)&&!(snapshot.sleep.mode==SleepMode.Deadline&&snapshot.sleep.deadlineMs<=System.currentTimeMillis())
            val resumeSource=fault.errorCode=="STREAM_REPRESENTATION_CHANGED"&&(snapshot.playWhenReady||snapshot.isPlaying)
            mutableState.value=snapshot.copy(isPlaying=false,playWhenReady=false,isBuffering=false,busy=false,notificationMessage=fault.message)
            persistPlaybackSnapshot()
            if(fault.errorCode in setOf("WORKER_LOST","AUDIO_ENGINE_FAILED","AUDIO_DEVICE_LOST","STREAM_REPRESENTATION_CHANGED","DECODE_FAILED","CORRUPT_AUDIO_STREAM","AUDIO_SCHEDULING_UNAVAILABLE","TEMPO_DURATION_LIMIT")) {
                val now=System.nanoTime()
                while(recoveryTimes.isNotEmpty() && now-recoveryTimes.first>60_000_000_000)recoveryTimes.removeFirst()
                if(recoveryTimes.size<3) {
                    recoveryTimes.addLast(now);workerStartedNanos=System.nanoTime();lastEpoch=0
                    audioBridge.restartWorker()
                    call(AudioCommand.Handshake(audioBridge.nextId(),AudioProtocol.BUILD_ID,java.security.SecureRandom().nextLong()))
                    syncAudioQueue(resumeSource)
                    deviceRecoveryPending=resumeDevice
                    mutableState.value=snapshot.copy(isPlaying=false,playWhenReady=resumeDevice||resumeSource,isBuffering=resumeDevice||resumeSource,notificationMessage=if(resumeSource)"Reopening the new provider representation at the last heard position"else if(resumeDevice)"Waiting for a Windows audio endpoint; Pause cancels recovery"else"${fault.message}. Audio restarted and is paused; press Play to resume.")
                }
            }
        } } }
        timer.scheduleAtFixedRate({if(snapshot.running&&deviceRecoveryPending&&recoveryAttempt.compareAndSet(false,true))enqueue{
            try{if(deviceRecoveryPending&&snapshot.sleep.mode==SleepMode.Deadline&&snapshot.sleep.deadlineMs<=System.currentTimeMillis()){deviceRecoveryPending=false;handle(DesktopCommand.SetSleepTimer(SleepPlan()));setPlaying(false);return@enqueue}
                if(deviceRecoveryPending&&!snapshot.busy){setPlaying(true,true);deviceRecoveryPending=false;mutableState.value=snapshot.copy(notificationMessage="Playback resumed on the Windows default audio endpoint")}}
            catch(_:Exception){mutableState.value=snapshot.copy(isPlaying=false,playWhenReady=true,isBuffering=true,busy=false,notificationMessage="Waiting for a Windows audio endpoint; Pause cancels recovery")}
            finally{recoveryAttempt.set(false)}
        }},2,2,java.util.concurrent.TimeUnit.SECONDS)
        timer.scheduleAtFixedRate({ if(snapshot.running&&snapshot.isPlaying) enqueue { persistPlaybackSnapshot() } },1,1,java.util.concurrent.TimeUnit.SECONDS)
    }
    private fun enqueue(action: () -> Unit) {
        try { executor.execute { synchronized(guard) { try { action();if(snapshot.running&&library!=null)mutableState.value=snapshot.copy(isCurrentTrackSaved=library!!.playlists.isSaved(snapshot.currentTrack.id)) } catch(t:Exception) {
            mutableState.value=snapshot.copy(busy=false,notificationMessage="${t.message?.take(220) ?: t.javaClass.simpleName}")
        } } } } catch(_:java.util.concurrent.RejectedExecutionException) { }
    }
    fun awaitIdle(): java.util.concurrent.CompletableFuture<HostSnapshot> {
        val result=java.util.concurrent.CompletableFuture<HostSnapshot>();executor.execute { result.complete(snapshot) };return result
    }
    private fun call(command: AudioCommand): AudioResult = audioBridge.sendCommand(command).also { check(it.success) { it.error ?: "Audio command failed" };lastEpoch=maxOf(lastEpoch,it.appliedStateRevision) }
    private fun duration(result:AudioResult):Long?=result.details?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["durationMs"]?.jsonPrimitive?.longOrNull }?.takeIf{it>0}
    private fun syncAudioQueue(play:Boolean) {
        val ticket=audioOperationGeneration.incrementAndGet()
        audioOperationPending=true
        val intended=snapshot.copy(isPlaying=false,playWhenReady=play&&snapshot.queueEntries.isNotEmpty(),isBuffering=play,busy=true)
        mutableState.value=intended
        audioBridge.sendCommand(AudioCommand.CancelPendingOperation(audioBridge.nextId()))
        audioExecutor.execute{
            fun send(command:AudioCommand)=audioBridge.sendCommand(command).also{check(it.success){it.error?:"Audio operation failed"}}
            try{
                if(ticket!=audioOperationGeneration.get())return@execute
                val descriptors=intended.sourceDescriptors.toMutableMap()
                val entries=intended.queueEntries.map{entry->library?.let{data->data.store.get(entry.song.scope,entry.song.id)?.let{entry.copy(song=data.song(it))}}?:entry}
                entries.distinctBy{it.song.playbackSource}.forEach{entry->
                    if(ticket!=audioOperationGeneration.get())return@execute
                    val actual=describeSource(entry.song.playbackSource);val prior=descriptors[entry.song.playbackSource]
                    check(prior==null||prior==actual){"Audio file changed since import; remove and import it again"};descriptors[entry.song.playbackSource]=actual
                }
                val index=entries.indexOfFirst{it.queueId==intended.currentEntryId}.coerceAtLeast(0)
                val pairIds=listOfNotNull(entries.getOrNull(index)?.queueId,entries.getOrNull(index+1)?.queueId).toSet()
                // Cache-only, bounded lookup; a large queue must not trigger thousands of SQLite projections.
                val names=entries.asSequence().flatMap{com.musicglass.desktop.automix.artistNames(it.song.artist).asSequence()}.distinct().take(200).toList()
                val cachedGenres=library?.genres?.cached(entries.map{it.song.scope}.distinct(),names).orEmpty()
                val document=(intended.lyricsState as? LyricsLoadState.Loaded)?.document?.takeIf{it.timed}
                val cachedVocals=document?.let{com.musicglass.desktop.automix.vocalSpans(it.lines)}.orEmpty().filter{it.first>=0&&it.last>=it.first&&it.last<=86400000}.take(8192)
                entries.forEach{entry->
                    if(ticket!=audioOperationGeneration.get())return@execute
                    val genres=com.musicglass.desktop.automix.artistNames(entry.song.artist).flatMap{cachedGenres[it].orEmpty()}
                    val vocals=if(entry.queueId in pairIds&&intended.lyricsTrackId==entry.song.id)cachedVocals else emptyList()
                    val facts=com.musicglass.desktop.automix.TransitionHints(!entry.song.isEpisode,com.musicglass.desktop.automix.classicalGenres(genres),entry.song.album.takeIf{it.length<=512}.orEmpty(),vocals)
                    send(AudioCommand.PrepareSource(audioBridge.nextId(),entry.queueId,downloads?.playbackSourceFor(entry.song)?:entry.song.playbackSource,0,rateFor(entry.song),facts))
                }
                if(ticket!=audioOperationGeneration.get())return@execute
                // Apply the desired timer before LoadQueue publishes its first clock anchor.
                send(AudioCommand.SetSleepTimer(audioBridge.nextId(),snapshot.sleep))
                send(AudioCommand.SetCanonicalDSP(audioBridge.nextId(),snapshot.equalizerConfig,if(snapshot.volumeBoost)1.5f else 1f,1f,snapshot.normalizationEnabled))
                val result=send(AudioCommand.LoadQueue(audioBridge.nextId(),entries.map{it.queueId},index,intended.playbackPositionMs,intended.secondaryPositionMs,intended.crossfadeEnabled,intended.repeatMode))
                enqueue{
                    if(ticket!=audioOperationGeneration.get()||!snapshot.running)return@enqueue
                    lastEpoch=maxOf(lastEpoch,result.appliedStateRevision)
                    audioOperationPending=false
                    val length=duration(result)
                    val selectedId=entries.getOrNull(index)?.queueId
                    val resolved=entries.associateBy{it.queueId}
                    val updated=snapshot.queueEntries.map{entry->
                        val refreshed=resolved[entry.queueId]?.song?:entry.song
                        val art=if(entry.song.artworkKey!=null)entry.song else refreshed
                        val merged=refreshed.copy(artworkKey=art.artworkKey,artworkUrl=art.artworkUrl?:refreshed.artworkUrl,dominantColor=art.dominantColor,deckMesh=art.deckMesh)
                        entry.copy(song=if(entry.queueId==selectedId&&length!=null)merged.copy(durationMs=length)else merged)
                    }
                    mutableState.value=snapshot.copy(queueEntries=updated,currentTrack=updated.getOrNull(index)?.song?:EmptyDesktopTrack,sourceDescriptors=descriptors)
                    sendDsp();call(AudioCommand.SetSleepTimer(audioBridge.nextId(),snapshot.sleep));call(AudioCommand.SetVolume(audioBridge.nextId(),snapshot.volume))
                    val wanted=snapshot.playWhenReady&&updated.isNotEmpty()
                    if(wanted)applyPlaybackResult(call(AudioCommand.SetPlaying(audioBridge.nextId(),true)),true)
                    mutableState.value=snapshot.copy(playWhenReady=snapshot.isPlaying,isBuffering=false,busy=false)
                }
            }catch(error:Exception){enqueue{if(ticket==audioOperationGeneration.get()&&snapshot.running){audioOperationPending=false;mutableState.value=snapshot.copy(isPlaying=false,playWhenReady=false,isBuffering=false,busy=false,notificationMessage=error.message?.take(180)?:"Audio queue could not load")}}}
        }
    }
    private fun loadLyrics(force:Boolean=false){
        val repository=lyricsRepository?:return
        val song=snapshot.currentTrack;if(song.id.isEmpty())return
        if(!force&&snapshot.lyricsTrackId==song.id&&(lyricsJob?.isActive==true||snapshot.lyricsState is LyricsLoadState.Loaded))return
        val token=accounts.token.value
        lyricsJob?.cancel();mutableState.value=snapshot.copy(lyricsState=LyricsLoadState.Loading,lyricsTrackId=song.id)
        lyricsJob=scope.launch(Dispatchers.IO){
            val result=try{lyricsLookup.load(song,repository)}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(error:Exception){LyricsLoadState.Failed(error.message?.take(180)?:"Lyrics unavailable")}
            enqueue{if(snapshot.currentTrack.id==song.id&&snapshot.currentTrack.scope==song.scope&&accounts.isCurrent(token))mutableState.value=snapshot.copy(lyricsState=if(result is LyricsLoadState.Loaded)result.copy(romanMode=snapshot.lyricsRomanMode,romanized=snapshot.lyricsRomanMode!=LyricsRomanMode.Original)else result)}
        }
    }
    private fun loadCollection(kind:String,id:String,offset:Long=0){
        val data=library?:return
        require(offset>=0)
        mutableState.value=snapshot.copy(collectionKind=kind,collectionId=id,collectionTracks=emptyList(),collectionOffset=offset,collectionState=PageState.Loading)
        scope.launch(Dispatchers.IO){
            try{
                val tracks=data.store.collection(Scopes.LOCAL,kind,id,500,offset).map(data::song)
                val count=data.store.collectionCount(Scopes.LOCAL,kind,id)
                enqueue{if(snapshot.running&&snapshot.collectionId==id&&snapshot.collectionKind==kind&&snapshot.collectionOffset==offset)mutableState.value=snapshot.copy(collectionTracks=tracks,collectionCount=count,collectionState=if(count==0L)PageState.NotFound else PageState.Ready(tracks))}
            }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(error:Exception){enqueue{if(snapshot.collectionId==id&&snapshot.collectionOffset==offset)mutableState.value=snapshot.copy(collectionState=PageState.Failed(error.message?:"Collection unavailable",true))}}

        }
    }
    private fun hydrateArtwork(entries:List<QueueEntry>){
        val data=library?:return
        val token=accounts.token.value
        scope.launch(Dispatchers.IO){entries.distinctBy{it.song.scope to it.song.id}.filter{it.song.artworkKey==null}.forEach{entry->
            val record=data.decorate(data.store.get(entry.song.scope,entry.song.id)?:entry.song.record())
            if(!accounts.isCurrent(token))return@launch
            data.store.upsert(record)
            val updated=data.song(checkNotNull(data.store.get(record.scope,record.id)))
            enqueue{if(snapshot.running && accounts.isCurrent(token)){
                val queue=snapshot.queueEntries.map{if(it.song.id==updated.id&&it.song.scope==updated.scope)it.copy(song=updated)else it}
                mutableState.value=snapshot.copy(queueEntries=queue,currentTrack=queue.firstOrNull{it.queueId==snapshot.currentEntryId}?.song?:snapshot.currentTrack)
            }}
        }}
    }
    private fun commitQueue(entries:List<QueueEntry>, current:String?=snapshot.currentEntryId, position:Long=snapshot.playbackPositionMs, play:Boolean=snapshot.playWhenReady) {
        require(entries.size<=5000 && entries.map{it.queueId}.toSet().size==entries.size){"Queue exceeds the supported size or repeats an occurrence identity"}
        val selected=entries.firstOrNull{it.queueId==current}?:entries.firstOrNull()
        val candidate=snapshot.copy(unshuffledOrder=if(snapshot.isShuffle)snapshot.unshuffledOrder else entries.map{it.queueId},queueEntries=entries,currentEntryId=selected?.queueId,currentTrack=selected?.song?:EmptyDesktopTrack,
            playbackPositionMs=if(selected?.queueId==current)position else 0,queueRevision=snapshot.queueRevision+1,secondaryEntryId=null,secondaryPositionMs=0)
        persist(candidate)
        mutableState.value=candidate.copy(busy=true)
        syncAudioQueue(play && entries.isNotEmpty())
        hydrateArtwork(entries)
    }
    private fun rateFor(song:SongItem)=effectivePlaybackSpeed(snapshot,song)
    fun dispatch(command:DesktopCommand):HostSnapshot {
        check(snapshot.running) { "Desktop host has stopped" }
        enqueue { handle(command) };return snapshot
    }
    private fun handle(command:DesktopCommand) {
        if(!snapshot.running)return
        when(command) {
            DesktopCommand.Quit -> {
                try {
                    val pendingAtQuit=audioOperationPending
                    audioOperationGeneration.incrementAndGet();audioBridge.sendCommand(AudioCommand.CancelPendingOperation(audioBridge.nextId()));(audioExecutor as? java.util.concurrent.ExecutorService)?.shutdownNow()
                    val stopped=audioBridge.sendCommand(AudioCommand.SetPlaying(audioBridge.nextId(),false))
                    // An empty/older worker timeline cannot replace the pending durable queue/seek target.
                    if(stopped.success&&!pendingAtQuit)applyPlaybackResult(stopped,false)
                    else mutableState.value=snapshot.copy(isPlaying=false,playWhenReady=false,isBuffering=false,busy=false)
                    if(repository!=null)persistPlaybackSnapshot()
                    if(!stopped.success)mutableState.value=snapshot.copy(notificationMessage=stopped.error)
                } finally {
                    mutableState.value=snapshot.copy(running=false)
                    scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                    if(publicCatalogProvider.isInitialized())publicCatalogProvider.value.close()
                    downloads?.close();downloads=null;if(downloadProvider.isInitialized())downloadProvider.value.close()
                    library?.close();library=null;audioBridge.close();repository?.close();repository=null;timer.shutdownNow()
                    mutableState.value=snapshot.copy(running=false);(executor as? java.util.concurrent.ExecutorService)?.shutdown()
                }
            }
            DesktopCommand.ShowMain -> mutableState.value=snapshot.copy(windowMode=WindowMode.Main,currentRoute=snapshot.savedRouteBeforeMini,inspectorTab=snapshot.savedInspectorTabBeforeMini)
            is DesktopCommand.SetPlaying -> setPlaying(command.isPlaying)
            DesktopCommand.TogglePlayPause -> setPlaying(!snapshot.playWhenReady)
            is DesktopCommand.SeekTo -> {
                check(snapshot.currentEntryId!=null){"Choose a track first"}
                val target=if(snapshot.currentTrack.durationMs>0)command.positionMs.coerceIn(0,snapshot.currentTrack.durationMs) else command.positionMs.coerceAtLeast(0)
                if(audioOperationPending){mutableState.value=snapshot.copy(playbackPositionMs=target,secondaryEntryId=null,secondaryPositionMs=0);persistPlaybackSnapshot();syncAudioQueue(snapshot.playWhenReady);return}
                val ticket=audioOperationGeneration.incrementAndGet();val requestedEpoch=lastEpoch+1
                audioOperationPending=true;mutableState.value=snapshot.copy(isPlaying=false,busy=true,playbackPositionMs=target,secondaryEntryId=null,secondaryPositionMs=0);persistPlaybackSnapshot()
                audioExecutor.execute{
                    val result=audioBridge.sendCommand(AudioCommand.Seek(audioBridge.nextId(),target,requestedEpoch))
                    enqueue{
                        if(ticket!=audioOperationGeneration.get()||!snapshot.running)return@enqueue
                        audioOperationPending=false
                        check(result.success){result.error?:"Seek failed"};lastEpoch=maxOf(lastEpoch,result.appliedStateRevision)
                        val actual=result.details?.let{Json.parseToJsonElement(it).jsonObject["positionMs"]?.jsonPrimitive?.long}?:target
                        mutableState.value=snapshot.copy(playbackPositionMs=actual,secondaryEntryId=null,secondaryPositionMs=0,busy=false)
                        applyPlaybackResult(call(AudioCommand.SetPlaying(audioBridge.nextId(),snapshot.playWhenReady)),snapshot.playWhenReady)
                        persistPlaybackSnapshot()
                    }
                }
            }
            DesktopCommand.NextTrack -> advance(true)
            DesktopCommand.PreviousTrack -> if(snapshot.playbackPositionMs>3000)handle(DesktopCommand.SeekTo(0)) else advance(false)
            is DesktopCommand.PlayPlaylist -> {
                val data=checkNotNull(library){"Library unavailable"}
                val count=data.playlists.count(Scopes.LOCAL,command.id)
                require(count<=5000){"This playlist has $count entries; the queue supports 5,000. Play a loaded page or select a smaller collection."}
                val tracks=data.playlists.entries(Scopes.LOCAL,command.id,5000).map{data.song(it.track)}
                handle(DesktopCommand.PlayCollection(tracks))
            }
            is DesktopCommand.PlayQueueEntry -> if(command.expectedRevision==snapshot.queueRevision){
                snapshot.queueEntries.firstOrNull{it.queueId==command.entryId}?.let{entry->
                    cancelLinkedResolution()
                    mutableState.value=snapshot.copy(currentEntryId=entry.queueId,currentTrack=entry.song,playbackPositionMs=0,secondaryEntryId=null,secondaryPositionMs=0)
                    persistPlaybackSnapshot();syncAudioQueue(true)
                }
            }
            is DesktopCommand.PlayTrack -> {
                cancelLinkedResolution()
                var entry=snapshot.queueEntries.firstOrNull{it.song.id==command.track.id&&it.song.scope==command.track.scope}
                if(entry==null) {entry=QueueEntry(song=command.track);commitQueue(snapshot.queueEntries+entry,entry.queueId,0,false)}
                else { mutableState.value=snapshot.copy(currentEntryId=entry.queueId,currentTrack=entry.song,playbackPositionMs=0,secondaryPositionMs=0);persistPlaybackSnapshot() }
                syncAudioQueue(true)
            }
            is DesktopCommand.PlayCollection -> if(command.tracks.isNotEmpty()) {
                cancelLinkedResolution()
                val entries=command.tracks.map{QueueEntry(song=it)}
                commitQueue(entries,entries[command.startIndex.coerceIn(entries.indices)].queueId,0,true)
            }
            is DesktopCommand.AddFileToQueue -> {
                check(snapshot.queueEntries.size<5000){"Queue limit is 5,000 tracks"}
                check(command.file.isFile){"Audio file is unavailable"}
                if(enableDataLayer)checkNotNull(library){"Library storage is unavailable; resolve the storage error before importing"}
                val path=command.file.canonicalPath
                val song=library?.let{runBlocking(Dispatchers.IO){it.import(command.file)}} ?: run {
                    val metadata=call(AudioCommand.ProbeMedia(audioBridge.nextId(),path))
                    val tags=metadata.details?.let{Json.parseToJsonElement(it).jsonObject}
                    SongItem(path,tags?.get("title")?.jsonPrimitive?.contentOrNull?:command.file.nameWithoutExtension,tags?.get("artist")?.jsonPrimitive?.contentOrNull?:"Unknown artist",tags?.get("album")?.jsonPrimitive?.contentOrNull?:"",duration(metadata)?:0,Color(0xFF181A22),IntArray(36){0xFF181A22.toInt()},emptyList())
                }
                val source=describeSource(path)
                mutableState.value=snapshot.copy(libraryTracks=if(library==null)snapshot.libraryTracks.filterNot{it.id==song.id}+song else snapshot.libraryTracks,sourceDescriptors=snapshot.sourceDescriptors+(path to source))
                commitQueue(snapshot.queueEntries+QueueEntry(song=song))
                mutableState.value=snapshot.copy(notificationMessage="Added ${command.file.name}")
            }
            is DesktopCommand.ReorderQueue -> move(command.fromIndex,command.toIndex)
            is DesktopCommand.ReorderQueueByEntryId -> if(command.expectedRevision==null||command.expectedRevision==snapshot.queueRevision) {
                val from=snapshot.queueEntries.indexOfFirst{it.queueId==command.entryId};move(from,from+if(command.moveUp)-1 else 1)
            }
            is DesktopCommand.MoveQueueEntryToPosition -> if(command.expectedRevision==null||command.expectedRevision==snapshot.queueRevision) move(snapshot.queueEntries.indexOfFirst{it.queueId==command.entryId},command.targetIndex)
            is DesktopCommand.RemoveFromQueue -> remove(snapshot.queueEntries.indexOfFirst{it.song.id==command.trackId})
            is DesktopCommand.RemoveQueueEntry -> remove(snapshot.queueEntries.indexOfFirst{it.queueId==command.queueId})
            DesktopCommand.UndoQueueAction -> snapshot.lastRemovedQueueItem?.let{(at,entry)->
                val entries=snapshot.queueEntries.toMutableList();entries.add(at.coerceIn(0,entries.size),entry);commitQueue(entries)
                mutableState.value=snapshot.copy(lastRemovedQueueItem=null)
            }
            DesktopCommand.ToggleRepeat -> {mutableState.value=snapshot.copy(repeatMode=RepeatMode.entries[(snapshot.repeatMode.ordinal+1)%RepeatMode.entries.size]);persistPlaybackSnapshot();syncAudioQueue(snapshot.playWhenReady)}
            DesktopCommand.ToggleShuffle -> {
                val enabled=!snapshot.isShuffle;val seed=if(enabled)java.security.SecureRandom().nextLong()else snapshot.shuffleSeed
                mutableState.value=snapshot.copy(isShuffle=enabled,shuffleSeed=seed,unshuffledOrder=if(enabled)snapshot.queueEntries.map{it.queueId}else snapshot.unshuffledOrder)
                if(enabled) {
                    val current=snapshot.queueEntries.firstOrNull{it.queueId==snapshot.currentEntryId}
                    val entries=snapshot.queueEntries.filterNot{it.queueId==current?.queueId}.shuffled(kotlin.random.Random(seed))
                    commitQueue(listOfNotNull(current)+entries)
                } else {
                    val order=snapshot.unshuffledOrder.withIndex().associate{it.value to it.index}
                    commitQueue(snapshot.queueEntries.sortedBy{order[it.queueId]?:Int.MAX_VALUE})
                }
            }
            is DesktopCommand.SetVolume -> {require(command.fraction.isFinite());call(AudioCommand.SetVolume(audioBridge.nextId(),command.fraction.coerceIn(0f,1f)));mutableState.value=snapshot.copy(volume=command.fraction.coerceIn(0f,1f));persistPlaybackSnapshot()}
            is DesktopCommand.SetCrossfadeEnabled -> {mutableState.value=snapshot.copy(crossfadeEnabled=command.enabled,busy=true,secondaryEntryId=null,secondaryPositionMs=0);persistPlaybackSnapshot();syncAudioQueue(snapshot.playWhenReady)}
            is DesktopCommand.SetPlaybackSpeed -> {
                if(command.expectedEntry!=null&&command.expectedEntry!=snapshot.currentEntryId)return
                val rate=checkedPlaybackRate(command.rate)
                if(snapshot.currentTrack.durationMs>14400000L&&rate!=1f)throw TempoDurationLimitException()
                mutableState.value=if(snapshot.currentTrack.isEpisode)snapshot.copy(episodePlaybackRate=rate)else snapshot.copy(musicPlaybackRate=rate)
                persistPlaybackSnapshot();syncAudioQueue(snapshot.playWhenReady)
            }
            is DesktopCommand.SetEqualizer -> {val config=checkedEqualizer(command.configuration);mutableState.value=snapshot.copy(equalizerConfig=config,eqPreset=config.preset);sendDsp();persistPlaybackSnapshot()}
            is DesktopCommand.SetEqPreset -> {
                val config=if(command.preset in EQ_PRESETS)EqualizerConfig(enabled=true,mode=EqMode.Manual,manual=EQ_PRESETS.getValue(command.preset),preset=command.preset)else migrateLegacyEqualizer(command.preset)
                handle(DesktopCommand.SetEqualizer(config))
            }
            is DesktopCommand.SetNormalization -> {mutableState.value=snapshot.copy(normalizationEnabled=command.enabled);sendDsp();persistPlaybackSnapshot()}
            is DesktopCommand.SetVolumeBoost -> {mutableState.value=snapshot.copy(volumeBoost=command.enabled);sendDsp();persistPlaybackSnapshot()}
            is DesktopCommand.OpenYouTubeLink -> {
                val target=parseYouTubeLink(command.text)?:error("Unsupported YouTube link")
                val ticket=linkGeneration.incrementAndGet();linkJob?.cancel();val token=accounts.token.value
                if(target is DesktopSharedTarget.Collection){handle(DesktopCommand.BrowseProvider(target.browseId));return}
                target as DesktopSharedTarget.Song
                mutableState.value=snapshot.copy(notificationMessage="Resolving the linked recording…")
                linkJob=scope.launch(Dispatchers.IO){
                    try{
                        val stream=publicCatalogProvider.value.resolve(target.videoId)
                        val metadata=checkNotNull(stream.mediaMetadata){"Provider returned no track metadata"}
                        val title=checkNotNull(metadata.title?.takeIf{it.isNotBlank()}){"Provider returned no track title"}
                        val song=EmptyDesktopTrack.copy(id="youtube:${target.videoId}",title=title,artist=metadata.author.orEmpty(),durationMs=(metadata.durationSeconds?:0)*1000L,scope=Scopes.GUEST,artworkUrl=providerArtworkUrl(target.videoId))
                        enqueue{if(ticket==linkGeneration.get()&&accounts.isCurrent(token)){
                            val entry=QueueEntry(song=song);mutableState.value=snapshot.copy(notificationMessage=null)
                            commitQueue(listOf(entry),entry.queueId,if(song.durationMs>0)target.startMs.coerceAtMost(song.durationMs)else target.startMs,true)
                        }}
                    }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                    catch(error:Exception){enqueue{if(ticket==linkGeneration.get()&&accounts.isCurrent(token))mutableState.value=snapshot.copy(notificationMessage="The linked recording could not be opened (${error.javaClass.simpleName})")}}
                }
            }
            is DesktopCommand.SetSearchSuggestions -> {
                suggestionsJob?.cancel();mutableState.value=snapshot.copy(searchSuggestionsEnabled=command.enabled,suggestions=SearchSuggestions());persistPlaybackSnapshot()
                if(command.enabled&&snapshot.searchQuery.isNotBlank())handle(DesktopCommand.SetSearchQuery(snapshot.searchQuery))
            }
            is DesktopCommand.SubmitSearch -> {
                val query=command.query.trim().take(200);if(query.isBlank())return
                if(parseYouTubeLink(command.query)!=null){handle(DesktopCommand.OpenYouTubeLink(command.query));return}
                val scope=accounts.token.value.scope
                val recent=(listOf(query)+snapshot.recentSearches[scope].orEmpty()).distinct().take(20)
                mutableState.value=snapshot.copy(recentSearches=(snapshot.recentSearches+ (scope to recent)).entries.toList().takeLast(20).associate{it.toPair()})
                persistPlaybackSnapshot();handle(DesktopCommand.SetSearchQuery(query))
            }
            is DesktopCommand.SetSearchQuery -> {
                val query=command.query.take(200);val generation=searchGeneration.incrementAndGet()
                mutableState.value=snapshot.copy(searchQuery=query,searchResults=emptyList(),searchCards=emptyList(),searchTopResult=null,suggestions=SearchSuggestions(),searchStatus=if(query.isBlank())null else "Searching…")
                searchJob?.cancel();suggestionsJob?.cancel()
                if(query.isNotBlank()){
                    val token=accounts.token.value
                    fun current()=generation==searchGeneration.get()&&accounts.isCurrent(token)
                    if(snapshot.searchSuggestionsEnabled)suggestionsJob=scope.launch(Dispatchers.IO){
                        try{delay(SEARCH_SUGGESTIONS_DEBOUNCE_MS);val suggestions=publicCatalogProvider.value.suggestions(query.take(100));enqueue{if(current()&&snapshot.searchSuggestionsEnabled)mutableState.value=snapshot.copy(suggestions=suggestions)}}
                        catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(_:Exception){enqueue{if(current())mutableState.value=snapshot.copy(suggestions=SearchSuggestions())}}
                    }
                    searchJob=scope.launch {
                        try {delay(450);val local=library?.search(query).orEmpty();enqueue{if(current())mutableState.value=snapshot.copy(searchResults=local)}
                            val response=if(searchProvider==null)publicCatalogProvider.value.searchResults(query)else null
                            val remote=response?.tracks?:checkNotNull(searchProvider).invoke(query);if(!current())return@launch
                            val results=library?.saveProvider(remote){current()}?:remote
                            enqueue{if(current())mutableState.value=snapshot.copy(searchResults=(local+results).distinctBy{it.scope to it.id},searchCards=response?.cards.orEmpty(),searchTopResult=response?.topResult,searchStatus=null)}
                        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                        catch(t:Exception){enqueue{if(current())mutableState.value=snapshot.copy(searchStatus="Provider unavailable; showing matching local/cached tracks",notificationMessage="Provider search failed (${t.javaClass.simpleName})")}}
                    }
                }
            }
            is DesktopCommand.BrowseProvider -> {
                catalogJob?.cancel();val token=accounts.token.value
                val existing=if(command.cursor!=null)(snapshot.catalogPage as? PageState.Ready)?.items.orEmpty()else emptyList()
                mutableState.value=snapshot.copy(currentRoute=WorkspaceRoute.Discover,catalogPage=PageState.Loading,catalogRequest=command)
                catalogJob=scope.launch(Dispatchers.IO){
                    val cache=library?.store?.let(::CatalogCacheRepository)
                    val key="${command.id}:${command.params.orEmpty()}:${command.radioVideoId.orEmpty()}:${command.query.orEmpty()}"
                    try {
                        val page=catalogGateway?.invoke(command)?:publicCatalogProvider.value.let{provider->command.query?.let{provider.searchPage(it,command.cursor)}?:command.radioVideoId?.let{provider.recommendations(it,command.cursor)}?:provider.catalog(command.id,command.cursor,command.params)}
                        if(!accounts.isCurrent(token))return@launch
                        page.raw?.let{cache?.put(token.scope,key,command.cursor,it,page.next)}
                        enqueue{if(accounts.isCurrent(token)&&snapshot.catalogRequest==command)mutableState.value=snapshot.copy(catalogTitle=page.title.ifBlank{"Discover"},catalogPage=if(page.cards.isEmpty()&&existing.isEmpty())PageState.Empty else PageState.Ready(existing+page.cards,page.next))}
                    }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                    catch(error:Exception){
                        if(!accounts.isCurrent(token))return@launch
                        val saved=cache?.get(token.scope,key,command.cursor)
                        val page=saved?.let{parseDesktopCatalogPage(Json.parseToJsonElement(it.payload))}
                        enqueue{if(accounts.isCurrent(token)&&snapshot.catalogRequest==command)mutableState.value=snapshot.copy(catalogPage=if(page!=null)PageState.Ready(existing+page.cards,page.next,true)else if(error is ProviderUnavailableException)PageState.Unsupported(error.message.orEmpty())else PageState.Failed(error.message?.take(160)?:"Catalog unavailable",true))}
                    }
                }
            }
            is DesktopCommand.RegisterLibraryRoot -> checkNotNull(library){"Library storage is unavailable"}.register(command.directory)
            is DesktopCommand.LocalCollectionPage -> snapshot.collectionKind?.let{kind->snapshot.collectionId?.let{id->loadCollection(kind,id,command.offset)}}
            is DesktopCommand.PlaylistPage -> library?.selectPlaylist(command.id,command.offset)
            is DesktopCommand.MovePlaylistOccurrence -> library?.playlists?.moveBy(Scopes.LOCAL,command.id,command.entry,command.up)
            DesktopCommand.PreviousLibraryPage -> library?.previousPage()
            DesktopCommand.LoadMoreLibrary -> library?.loadMore()
            is DesktopCommand.CreatePlaylist -> library?.playlists?.create(Scopes.LOCAL,command.title)
            is DesktopCommand.RenamePlaylist -> library?.playlists?.rename(Scopes.LOCAL,command.id,command.title)
            is DesktopCommand.DeletePlaylist -> library?.playlists?.delete(Scopes.LOCAL,command.id)
            is DesktopCommand.AddToPlaylist -> library?.playlists?.add(Scopes.LOCAL,command.id,command.track.record())
            is DesktopCommand.RemovePlaylistEntry -> library?.playlists?.remove(Scopes.LOCAL,command.id,command.entryId)
            is DesktopCommand.ReorderPlaylist -> library?.playlists?.reorder(Scopes.LOCAL,command.id,command.entries)
            is DesktopCommand.SetHistoryPrivacy -> library?.setHistoryPrivacy(command.preferences)
            DesktopCommand.SignIn -> {authenticationJob?.cancel();authenticationJob=scope.launch{try{authentication.signIn();enqueue{mutableState.value=snapshot.copy(notificationMessage="YouTube read-only session verified. Music private-account integration remains unavailable.")}}catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}catch(error:Exception){enqueue{mutableState.value=snapshot.copy(notificationMessage=error.message?.take(160))}}}
            }
            DesktopCommand.SignOut -> scope.launch{authenticationJob?.cancel();authentication.signOut();linkJob?.cancel();linkGeneration.incrementAndGet();searchJob?.cancel();catalogJob?.cancel();suggestionsJob?.cancel();searchGeneration.incrementAndGet();lyricsJob?.cancel();enqueue{mutableState.value=snapshot.copy(lyricsState=LyricsLoadState.Idle,lyricsTrackId=null,suggestions=SearchSuggestions(),searchResults=emptyList(),searchCards=emptyList(),searchTopResult=null,catalogPage=PageState.Empty,notificationMessage="Signed out")}}
            is DesktopCommand.MarkEncoreStorySeen -> {
                val key="${command.period.range}:${command.period.start}"
                mutableState.value=snapshot.copy(encoreSeen=(snapshot.encoreSeen+key).toList().takeLast(256).toSet());persistPlaybackSnapshot()
            }
            DesktopCommand.ClearHistory -> {library?.clearHistory();mutableState.value=snapshot.copy(encoreSeen=emptySet());persistPlaybackSnapshot()}
            is DesktopCommand.SetRoute -> mutableState.value=snapshot.copy(previousRoute=snapshot.currentRoute,currentRoute=command.route)
            DesktopCommand.NavigateBack -> mutableState.value=snapshot.copy(currentRoute=snapshot.previousRoute?:WorkspaceRoute.Home,previousRoute=null)
            is DesktopCommand.SetReducedMotion -> mutableState.value=snapshot.copy(reducedMotion=command.enabled)
            is DesktopCommand.SetGlassQuality -> mutableState.value=snapshot.copy(glassQuality=command.quality)
            is DesktopCommand.SetMoodFilter -> mutableState.value=snapshot.copy(moodFilter=command.mood)
            is DesktopCommand.SetSleepTimer -> {
                val plan=command.plan.restored(System.currentTimeMillis())
                call(AudioCommand.SetSleepTimer(audioBridge.nextId(),plan));mutableState.value=snapshot.copy(sleep=plan);persistPlaybackSnapshot()
            }
            DesktopCommand.LoadLyrics -> loadLyrics(true)
            is DesktopCommand.SelectLyricsCandidate -> {
                val candidate=(snapshot.lyricsState as? LyricsLoadState.Ambiguous)?.candidates?.singleOrNull{it.id==command.id}?:error("That lyrics choice is no longer current")
                val repository=checkNotNull(lyricsRepository);val song=snapshot.currentTrack;val token=accounts.token.value
                lyricsJob?.cancel();mutableState.value=snapshot.copy(lyricsState=LyricsLoadState.Loading)
                lyricsJob=scope.launch(Dispatchers.IO){
                    val result=repository.accept(song,candidate.raw,candidate.format,candidate.source,candidate.url)
                    enqueue{if(snapshot.currentTrack.id==song.id&&snapshot.currentTrack.scope==song.scope&&accounts.isCurrent(token))mutableState.value=snapshot.copy(lyricsState=if(result is LyricsLoadState.Loaded)result.copy(romanMode=snapshot.lyricsRomanMode,romanized=snapshot.lyricsRomanMode!=LyricsRomanMode.Original)else result)}
                }
            }
            is DesktopCommand.SetLyricsPreferences -> lyricsRepository?.let{repository->
                val song=snapshot.currentTrack;val token=accounts.token.value
                val mode=command.mode?:snapshot.lyricsRomanMode
                mutableState.value=snapshot.copy(lyricsRomanMode=mode);persistPlaybackSnapshot()
                scope.launch(Dispatchers.IO){val result=repository.preferences(song,command.offsetMs,mode!=LyricsRomanMode.Original,mode);enqueue{if(snapshot.currentTrack.id==song.id&&snapshot.currentTrack.scope==song.scope&&accounts.isCurrent(token))mutableState.value=snapshot.copy(lyricsState=if(result is LyricsLoadState.Loaded)result.copy(romanMode=snapshot.lyricsRomanMode,romanized=snapshot.lyricsRomanMode!=LyricsRomanMode.Original)else result)}}
            }
            is DesktopCommand.ToggleInspector -> {mutableState.value=snapshot.copy(inspectorTab=if(snapshot.inspectorTab==command.tab)InspectorTab.None else command.tab);if(snapshot.inspectorTab==InspectorTab.Lyrics)loadLyrics()}
            is DesktopCommand.SetWindowMode -> mutableState.value=if(command.mode!=WindowMode.Main&&snapshot.windowMode==WindowMode.Main) snapshot.copy(windowMode=command.mode,savedRouteBeforeMini=snapshot.currentRoute,savedInspectorTabBeforeMini=snapshot.inspectorTab) else snapshot.copy(windowMode=command.mode)
            is DesktopCommand.SetAlwaysOnTop -> mutableState.value=snapshot.copy(isAlwaysOnTop=command.enabled)
            is DesktopCommand.SetSidebarWidth -> mutableState.value=snapshot.copy(sidebarWidthDp=command.widthDp.coerceIn(WorkspaceLayoutCalculator.SIDEBAR_MIN_WIDTH.toFloat(),WorkspaceLayoutCalculator.SIDEBAR_MAX_WIDTH.toFloat()))
            is DesktopCommand.SetInspectorWidth -> mutableState.value=snapshot.copy(inspectorWidthDp=command.widthDp.coerceIn(WorkspaceLayoutCalculator.INSPECTOR_MIN_WIDTH.toFloat(),WorkspaceLayoutCalculator.INSPECTOR_MAX_WIDTH.toFloat()))
            is DesktopCommand.OpenAlbumDetail -> {mutableState.value=snapshot.copy(previousRoute=snapshot.currentRoute,currentRoute=WorkspaceRoute.AlbumDetail,activeAlbumId=command.albumId);loadCollection("album",command.albumId)}
            is DesktopCommand.OpenArtistDetail -> {mutableState.value=snapshot.copy(previousRoute=snapshot.currentRoute,currentRoute=WorkspaceRoute.ArtistDetail,activeArtistId=command.artistId);loadCollection("artist",command.artistId)}
            is DesktopCommand.OpenPlaylistDetail -> {mutableState.value=snapshot.copy(previousRoute=snapshot.currentRoute,currentRoute=WorkspaceRoute.PlaylistDetail,activePlaylistId=command.playlistId);library?.selectPlaylist(command.playlistId)}
            DesktopCommand.OpenFocusedPlayer -> mutableState.value=snapshot.copy(previousRoute=snapshot.currentRoute,currentRoute=WorkspaceRoute.FocusedPlayer)
            is DesktopCommand.ToggleSavedTrack -> {
                check(command.track.id.isNotBlank()){"Choose a track first"}
                val data=checkNotNull(library){"Library storage is unavailable"}
                val saved=data.playlists.toggleSaved(command.track.record())
                mutableState.value=snapshot.copy(notificationMessage=if(saved)"Saved locally"else"Removed from local saved tracks")
            }
            is DesktopCommand.DownloadTrack -> {
                checkNotNull(downloads){"Downloads need local storage"}
                if(snapshot.pendingDownloadRemoval==downloads!!.keyFor(command.track)){
                    downloadRemovalGeneration++;mutableState.value=snapshot.copy(pendingDownloadRemoval=null,notificationMessage="Download kept")
                }
                downloads!!.request(command.track)
            }
            is DesktopCommand.PauseDownload -> if(command.paused)downloads?.pause(command.scope,command.id)else downloads?.resume(command.scope,command.id)
            is DesktopCommand.RemoveDownload -> {
                val manager=checkNotNull(downloads)
                val job=manager.repository.get(command.scope,command.id)
                if(job?.state==DownloadState.Complete){
                    snapshot.pendingDownloadRemoval?.let{manager.remove(it.first,it.second)}
                    val pending=command.scope to command.id;val generation=++downloadRemovalGeneration
                    mutableState.value=snapshot.copy(pendingDownloadRemoval=pending,notificationMessage="Download removed")
                    timer.schedule({enqueue{if(snapshot.running&&generation==downloadRemovalGeneration){manager.remove(pending.first,pending.second);mutableState.value=snapshot.copy(pendingDownloadRemoval=null,notificationMessage=null)}}},4,java.util.concurrent.TimeUnit.SECONDS)
                }else manager.remove(command.scope,command.id)
            }
            DesktopCommand.UndoDownloadRemoval -> {downloadRemovalGeneration++;mutableState.value=snapshot.copy(pendingDownloadRemoval=null,notificationMessage="Download kept")}
            is DesktopCommand.DismissNotification -> {
                snapshot.pendingDownloadRemoval?.let{downloads?.remove(it.first,it.second)}
                downloadRemovalGeneration++;mutableState.value=snapshot.copy(notificationMessage=null,pendingDownloadRemoval=null)
            }
        }
    }
    private fun cancelLinkedResolution(){
        linkGeneration.incrementAndGet();linkJob?.cancel()
        if(snapshot.notificationMessage=="Resolving the linked recording…")mutableState.value=snapshot.copy(notificationMessage=null)
    }
    private fun setPlaying(value:Boolean,deviceRetry:Boolean=false) {
        if(!deviceRetry){deviceRecoveryPending=false;if(!value)cancelLinkedResolution()}
        if(value&&snapshot.sleep.mode==SleepMode.Deadline&&snapshot.sleep.deadlineMs<=System.currentTimeMillis())handle(DesktopCommand.SetSleepTimer(SleepPlan()))
        if(audioOperationPending){mutableState.value=snapshot.copy(playWhenReady=value,isBuffering=value);if(!value)call(AudioCommand.SetPlaying(audioBridge.nextId(),false));return}
        check(!value||snapshot.currentEntryId!=null){"Import or find a track first"}
        mutableState.value=snapshot.copy(playWhenReady=value,busy=value,isBuffering=false)
        val applied=call(AudioCommand.SetPlaying(audioBridge.nextId(),value));applyPlaybackResult(applied,value);persistPlaybackSnapshot()
    }
    private fun applyPlaybackResult(result:AudioResult,fallback:Boolean) {
        val details=result.details?.let{Json.parseToJsonElement(it).jsonObject}
        val entry=details?.get("entryId")?.jsonPrimitive?.contentOrNull?.let{id->snapshot.queueEntries.firstOrNull{it.queueId==id}}
        mutableState.value=snapshot.copy(isPlaying=details?.get("isPlaying")?.jsonPrimitive?.boolean?:fallback,
            playbackPositionMs=details?.get("positionMs")?.jsonPrimitive?.long?:snapshot.playbackPositionMs,
            currentEntryId=entry?.queueId?:snapshot.currentEntryId,currentTrack=entry?.song?:snapshot.currentTrack,
            secondaryEntryId=details?.get("secondary")?.jsonPrimitive?.contentOrNull,
            secondaryPositionMs=details?.get("secondaryPositionMs")?.jsonPrimitive?.long?:0,isBuffering=false,busy=false)
    }
    private fun advance(next:Boolean) {
        cancelLinkedResolution()
        if(snapshot.queueEntries.isEmpty())return
        val index=snapshot.queueEntries.indexOfFirst{it.queueId==snapshot.currentEntryId}.coerceAtLeast(0)
        val target=index+if(next)1 else -1
        if(target !in snapshot.queueEntries.indices) {
            if(snapshot.repeatMode!=RepeatMode.All){setPlaying(false);return}
        }
        val entry=snapshot.queueEntries[(target+snapshot.queueEntries.size)%snapshot.queueEntries.size]
        mutableState.value=snapshot.copy(currentEntryId=entry.queueId,currentTrack=entry.song,playbackPositionMs=0,secondaryEntryId=null,secondaryPositionMs=0)
        persistPlaybackSnapshot();syncAudioQueue(true)
    }
    private fun move(from:Int,to:Int) {
        if(from !in snapshot.queueEntries.indices||to !in snapshot.queueEntries.indices||from==to)return
        val list=snapshot.queueEntries.toMutableList();list.add(to,list.removeAt(from));commitQueue(list)
    }
    private fun remove(index:Int) {
        if(index !in snapshot.queueEntries.indices)return
        val list=snapshot.queueEntries.toMutableList();val entry=list.removeAt(index)
        commitQueue(list);mutableState.value=snapshot.copy(lastRemovedQueueItem=index to entry)
    }
    private fun sendDsp() {
        call(AudioCommand.SetCanonicalDSP(audioBridge.nextId(),snapshot.equalizerConfig,if(snapshot.volumeBoost)1.5f else 1f,1f,snapshot.normalizationEnabled))
    }
    private fun persist(value:HostSnapshot) {
        val json=kotlinx.serialization.json.buildJsonObject {
            put("sources",JsonArray(value.sourceDescriptors.filterKeys{key->value.queueEntries.any{it.song.playbackSource==key}}.values.map{it.toJson()}))
            put("schema",1);put("revision",value.queueRevision);put("current",value.currentEntryId?.let(::JsonPrimitive)?:JsonNull)
            put("position",value.playbackPositionMs);put("secondary",value.secondaryEntryId?.let(::JsonPrimitive)?:JsonNull);put("secondaryPosition",value.secondaryPositionMs)
            put("volume",value.volume);put("repeat",value.repeatMode.name);put("shuffle",value.isShuffle);put("seed",value.shuffleSeed)
            put("unshuffled",JsonArray(value.unshuffledOrder.map(::JsonPrimitive)))
            put("sleep",buildJsonObject{put("mode",value.sleep.mode.name);put("deadline",value.sleep.deadlineMs);put("left",value.sleep.songsLeft);put("fade",value.sleep.fadeMs)})
            put("searchSuggestionsEnabled",value.searchSuggestionsEnabled)
            put("recentSearches",JsonObject(value.recentSearches.mapValues{JsonArray(it.value.map(::JsonPrimitive))}))
            put("lyricsRomanMode",value.lyricsRomanMode.name);put("encoreSeen",JsonArray(value.encoreSeen.map(::JsonPrimitive)))
            put("episodeRate",value.episodePlaybackRate);put("equalizer",value.equalizerConfig.toJson());put("eq",value.eqPreset);put("boost",value.volumeBoost);put("normalize",value.normalizationEnabled);put("crossfade",value.crossfadeEnabled)
            put("queue",JsonArray(value.queueEntries.map{entry->buildJsonObject{put("entry",entry.queueId);put("song",entry.song.toSessionJson())}}))
            if(!enableDataLayer)put("library",JsonArray(value.libraryTracks.map{it.toSessionJson()}))
        }
        checkNotNull(repository){"Session storage is unavailable"}.save(json,value.queueRevision)
    }
    fun persistPlaybackSnapshot() {persist(snapshot)}
    private fun restore(json:JsonObject) {
        val entries=json.getValue("queue").jsonArray.map{el->val obj=el.jsonObject;QueueEntry(obj.getValue("entry").jsonPrimitive.content,songFromSession(obj.getValue("song").jsonObject))}
        check(entries.map{it.queueId}.toSet().size==entries.size){"Duplicate saved queue identities"}
        val current=json["current"]?.jsonPrimitive?.contentOrNull
        val selected=entries.firstOrNull{it.queueId==current}?:entries.firstOrNull()
        mutableState.value=snapshot.copy(queueEntries=entries,currentEntryId=selected?.queueId,currentTrack=selected?.song?:EmptyDesktopTrack,
            queueRevision=json.getValue("revision").jsonPrimitive.long,playbackPositionMs=json.getValue("position").jsonPrimitive.long,
            secondaryEntryId=json["secondary"]?.jsonPrimitive?.contentOrNull,secondaryPositionMs=json["secondaryPosition"]?.jsonPrimitive?.long?:0,
            volume=json.getValue("volume").jsonPrimitive.float,repeatMode=RepeatMode.valueOf(json.getValue("repeat").jsonPrimitive.content),
            isShuffle=json.getValue("shuffle").jsonPrimitive.boolean,shuffleSeed=json.getValue("seed").jsonPrimitive.long,
            unshuffledOrder=json["unshuffled"]?.jsonArray?.map{it.jsonPrimitive.content}?:entries.map{it.queueId},
            musicPlaybackRate=1f,episodePlaybackRate=json["episodeRate"]?.jsonPrimitive?.float?.let(::checkedPlaybackRate)?:1f,
            equalizerConfig=json["equalizer"]?.jsonObject?.let(::equalizerFromJson)?:migrateLegacyEqualizer(json.getValue("eq").jsonPrimitive.content),
            eqPreset=json.getValue("eq").jsonPrimitive.content,volumeBoost=json.getValue("boost").jsonPrimitive.boolean,normalizationEnabled=json["normalize"]?.jsonPrimitive?.boolean?:false,
            crossfadeEnabled=json["crossfade"]?.jsonPrimitive?.boolean?:false,
            searchSuggestionsEnabled=json["searchSuggestionsEnabled"]?.jsonPrimitive?.boolean?:true,
            recentSearches=json["recentSearches"]?.jsonObject?.entries?.take(20)?.associate{(scope,queries)->scope to queries.jsonArray.take(20).map{it.jsonPrimitive.content.take(200)}}?:emptyMap(),
            encoreSeen=json["encoreSeen"]?.jsonArray?.takeLast(256)?.mapNotNull{item->item.jsonPrimitive.content.takeIf{it.matches(Regex("(Day|Week|Month|Year|All):[0-9]{4}-[0-9]{2}-[0-9]{2}"))}}?.toSet()?:emptySet(),
            lyricsRomanMode=json["lyricsRomanMode"]?.jsonPrimitive?.content?.let{LyricsRomanMode.valueOf(it)}?:LyricsRomanMode.Original,
            sourceDescriptors=json["sources"]?.jsonArray?.map{sourceFromJson(it.jsonObject)}?.associateBy{it.key}?:emptyMap(),
            sleep=json["sleep"]?.jsonObject?.let{SleepPlan(SleepMode.valueOf(it.getValue("mode").jsonPrimitive.content),it.getValue("deadline").jsonPrimitive.long,it.getValue("left").jsonPrimitive.int,it.getValue("fade").jsonPrimitive.long).restored(System.currentTimeMillis())}?:SleepPlan(),
            libraryTracks=json["library"]?.jsonArray?.map{songFromSession(it.jsonObject)}?:emptyList(),isPlaying=false)
    }
    override fun close() {
        if(snapshot.running) {
            val done=java.util.concurrent.CompletableFuture<Unit>()
            executor.execute { synchronized(guard) { try { handle(DesktopCommand.Quit);done.complete(Unit) } catch(t:Exception){done.completeExceptionally(t)} } }
            done.get(20,java.util.concurrent.TimeUnit.SECONDS)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
