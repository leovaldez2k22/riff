package com.musicglass.personal

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

internal data class PlaybackUiState(
    val sleepDeadlineMs:Long=0,
    val ready: Boolean = false,
    val index: Int = 0,
    val playing: Boolean = false,
    val wantsPlayback: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    /** What the user is told while the app retries by itself; not a failure. */
    val notice: String? = null,
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artistId: String? = null, val albumId: String? = null, val live: Boolean = false,val music:Boolean=true,
    val embedded:Boolean=false,val video:Boolean=false,val videoAspectRatio:Float=16f/9f,
    val queueLoading: Boolean = false, val queueMessage: String = "", val queueTitle:String="",
    val mediaId: String = "",
    val artworkUrl: String? = null,
    val queue: List<QueueEntry> = emptyList(),
    /** The typed state. [playing], [wantsPlayback] and [error] above are kept for the screens that read them. */
    val playback: PlaybackState = PlaybackState(),
)

internal data class QueueEntry(val mediaId: String, val title: String, val artist: String, val artworkUrl: String? = null,val queueId:String="")

internal interface PlaybackGateway {
    val state: PlaybackUiState
    val positionMs: Long
    fun currentPositionMs(): Long = positionMs
    fun toggle()
    fun setSleepTimer(minutes:Int)
    fun pause()
    fun resume()
    /** Tries the selected entry again after a failure, and plays it. */
    fun retry() = resume()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun moveQueueItem(from: Int, to: Int)
    fun seekQueueItem(index: Int)
    fun enqueueProvider(track: ProviderTrack, stream: ProviderStream, playNext: Boolean = false)
    fun playSong(track:ProviderTrack)
    fun restoreAudio(track:ProviderTrack,position:Long)
    fun playAlbum(tracks: List<ProviderTrack>, index: Int)
    fun playCollection(tracks:List<ProviderTrack>,index:Int,title:String,shuffle:Boolean=false)
    fun stageLive(track:ProviderTrack)
    fun replaceProvider(track:ProviderTrack,stream:ProviderStream,position:Long)
    fun attachVideo(surface:android.view.SurfaceView?)
    fun detachVideo(surface:android.view.SurfaceView)
    fun removeQueueItem(index: Int)
    /** Skips [videoId] if it is playing and takes every copy of it out of the queue. */
    fun dropTrack(videoId: String)
    fun playProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long = 0L)
    fun playLocal(track: LocalAudioTrack)
    fun clearProviderPlayback()
    /** True while the player still holds a song from YouTube Music. */
    fun hasProviderItems():Boolean=false
    /** Loads the active owner's saved queue, paused, replacing the current one. [done] reports whether a queue was loaded. */
    fun restoreSavedQueue(done:(Boolean)->Unit={}) {done(false)}
    fun close()
}

internal class MusicPlaybackGateway(context: Context,
    private val dependencies:RuntimeDependencies=AppContainer.get(context).runtime) : PlaybackGateway {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    var onLivePlay:((ProviderTrack)->Unit)?=null
    private var embeddedId:String?=null
    private var embeddedPlaying=false
    private var embeddedControl:((Boolean)->Unit)?=null
    fun bindEmbedded(control:((Boolean)->Unit)?) {embeddedControl=control;if(control==null) embeddedPlaying=false;refresh(false)}
    fun reportEmbedded(playing:Boolean) {embeddedPlaying=playing;refresh(false)}
    private var controller: MediaController? = null
    private var videoSurface:android.view.SurfaceView?=null
    private var commandError: PlaybackFailure? = null
    // What the user last pressed, so a queue still being prepared does not start playing after a pause.
    private var transportSequence = 0L
    private var transportPlay = true
    private fun transport(play: Boolean) { transportSequence++; transportPlay = play }
    private val future = MediaController.Builder(appContext,
        SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java)))
        .setListener(object : MediaController.Listener {
            // Queue revision, owner and recovery state arrive here, not as player events.
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) = refresh(readQueue = false)
        })
        .buildAsync()
    private var pending: ((MediaController) -> Unit)? = null
    override var state by mutableStateOf(PlaybackUiState())
        private set
    override var positionMs by mutableLongStateOf(0L)
        private set
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }
    private val ticker = object : Runnable {
        override fun run() {
            refresh(readQueue = false)
            handler.postDelayed(this, 500L)
        }
    }

    init {
        future.addListener({
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(listener)
                refresh()
                pending?.invoke(connected)
                pending = null
                handler.post(ticker)
            } catch (error: Exception) {
                fail(PlaybackFailure(PlaybackFailureKind.Connection, error.message.orEmpty()))
            }
        }, appContext.mainExecutor)
    }

    private fun withPlayer(command: (MediaController) -> Unit) {
        val current = controller
        if (current == null) pending = command else command(current)
    }
    private fun fail(failure: PlaybackFailure) {
        commandError = failure
        state = state.copy(error = failure.message(), playback = state.playback.copy(failure = failure,
            status = if (state.playback.status == PlaybackStatus.Idle) PlaybackStatus.Idle else PlaybackStatus.Failed))
    }
    /** The entry's own identifier; an item without one falls back to the window identity. */
    private fun entryKey(player: Player, index: Int): String = player.getMediaItemAt(index).entryId().ifBlank {
        player.currentTimeline.getWindow(index, androidx.media3.common.Timeline.Window()).uid.toString() }
    /**
     * The position in the player of the entry the user acted on. [shown] is its position in the queue
     * as displayed, which can be behind the player's; null when that entry is no longer queued.
     */
    private fun entryIndex(player: Player, shown: Int): Int? {
        val id = state.queue.getOrNull(shown)?.queueId
        if (id.isNullOrBlank()) return shown.takeIf { it in 0 until player.mediaItemCount }
        if (shown in 0 until player.mediaItemCount && entryKey(player, shown) == id) return shown
        return (0 until player.mediaItemCount).firstOrNull { entryKey(player, it) == id }
    }
    private fun refresh(readQueue: Boolean = true) {
        val player = controller ?: return
        val embedded = embeddedId != null && embeddedId == player.currentMediaItem?.mediaId
        val extras = player.sessionExtras
        // A player failure is described by the service, which decides whether it is retried (Q07).
        // The player's own error is not read here: it arrives before that decision does.
        val failure = if (embedded) null else commandError ?: PlaybackFailureKind.of(extras.getString("failure"))?.let(::PlaybackFailure)
        val recovery = RecoveryReason.of(extras.getString("recovery")).takeIf { !embedded && failure == null }
        val entry = player.currentMediaItem?.entryId().orEmpty()
        val typed = PlaybackState(
            status = if (embedded) { if (embeddedPlaying) PlaybackStatus.Playing else PlaybackStatus.Paused }
                else playbackStatus(player.mediaItemCount, player.playbackState, player.playWhenReady, player.isPlaying,
                    failed = failure != null, recovering = recovery != null,
                    resolving = entry.isNotEmpty() && extras.getString("resolving") == entry),
            desiredPlayWhenReady = if (embedded) embeddedPlaying else player.playWhenReady,
            queueRevision = extras.getLong("queueRevision"),
            queueItemId = entry,
            owner = extras.getString("queueOwner").orEmpty(),
            durationMs = player.duration.coerceAtLeast(0),
            failure = failure,
            recovery = recovery,
        )
        val updated = PlaybackUiState(
            playback = typed,
            sleepDeadlineMs=player.sessionExtras.getLong("sleepDeadlineMs"),
            ready = true,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            playing = if((embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId)) embeddedPlaying else player.isPlaying,
            wantsPlayback = if((embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId)) embeddedPlaying else player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            error = failure?.message(),
            notice = recovery?.notice(),
            source = when {
                player.currentMediaItem?.mediaId?.startsWith("yt:") == true -> "YouTube Music"
                player.currentMediaItem?.mediaId?.startsWith("local:") == true -> "On device"
                else -> ""
            },
            title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "",
            artist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: "",
            album = player.currentMediaItem?.mediaMetadata?.albumTitle?.toString().orEmpty(),
            artistId = player.currentMediaItem?.mediaMetadata?.extras?.getString("artistId"),
            albumId = player.currentMediaItem?.mediaMetadata?.extras?.getString("albumId"),
            live = player.isCurrentMediaItemLive || player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live") == true,
            music=player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("music",true)!=false,
            embedded=(embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId),
            video=(embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId) || player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("video")==true,
            videoAspectRatio=player.videoSize.let {if(it.height>0) it.width*it.pixelWidthHeightRatio/it.height else 16f/9f},
            queueLoading = player.playlistMetadata.extras?.getBoolean("queueLoading") == true,
            queueMessage = player.playlistMetadata.subtitle?.toString().orEmpty(),
            queueTitle = player.playlistMetadata.title?.toString().orEmpty(),
            mediaId = player.currentMediaItem?.mediaId ?: "",
            artworkUrl = player.currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
            queue = if (!readQueue) state.queue else (0 until player.mediaItemCount).map { index ->
                val item = player.getMediaItemAt(index)
                QueueEntry(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
                    item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString(),entryKey(player,index))
            },
        )
        positionMs = updated.positionMs
        // Position ticks must not invalidate metadata, artwork, queue, and chrome.
        if (updated.copy(positionMs = state.positionMs) != state) state = updated
    }
    override fun toggle() = withPlayer {
        PerfTrace.began("play_toggle")
        if(state.embedded) {
            val control=embeddedControl
            if(control!=null) control(!embeddedPlaying) else onLivePlay?.invoke(ProviderTrack(state.mediaId.removePrefix("yt:"),state.title,state.artist,state.artworkUrl,isLive=true))
            return@withPlayer
        }
        commandError = null
        if (toggleShouldPause(it.isPlaying,it.playWhenReady,it.playbackState==Player.STATE_IDLE)) { transport(false); it.pause() }
        else { transport(true); start(it) }
    }
    /** Play, as asked by the user. An entry that failed is tried again by the service, which keeps the queue and the position. */
    private fun start(player: MediaController) {
        if (player.playerError == null) { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play(); return }
        player.play()
        player.sendCustomCommand(SessionCommand(RETRY_PLAYBACK, Bundle.EMPTY), Bundle().apply { putString("entry", player.currentMediaItem?.entryId().orEmpty()) })
    }
    override fun retry() { transport(true); withPlayer { commandError = null; start(it) } }
    override fun setSleepTimer(minutes:Int) = withPlayer {it.sendCustomCommand(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY),Bundle().apply {putInt("minutes",minutes)})}
    override fun pause() { transport(false); withPlayer { it.pause();if(state.embedded) embeddedControl?.invoke(false) } }
    override fun resume() { transport(true); withPlayer { start(it) } }
    override fun next() { PerfTrace.began("skip"); transport(true); withPlayer { commandError = null; it.seekToNextMediaItem(); if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() } }
    override fun previous() { transport(true); withPlayer { commandError = null; it.seekToPreviousMediaItem(); if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() } }
    override fun currentPositionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: positionMs
    override fun seekTo(positionMs: Long) = withPlayer {
        it.seekTo(positionMs.coerceAtLeast(0))
        refresh(readQueue = false)
    }
    override fun toggleShuffle() = withPlayer { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    override fun cycleRepeat() = withPlayer {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    // Queue edits name a row of the queue as displayed. They act on that entry wherever it is now,
    // and do nothing when it has left the queue.
    override fun moveQueueItem(from: Int, to: Int) = withPlayer { player ->
        val at = entryIndex(player, from) ?: return@withPlayer
        val target = (to + (at - from)).coerceIn(0, player.mediaItemCount - 1)
        if (to in 0 until player.mediaItemCount && at != target) player.moveMediaItem(at, target)
    }
    override fun removeQueueItem(index: Int) = withPlayer { player ->
        val at = entryIndex(player, index) ?: return@withPlayer
        commandError = null
        removePlaybackQueueItem(player,at)
    }
    override fun dropTrack(videoId: String) = withPlayer { player ->
        val matches = (0 until player.mediaItemCount).filter { player.getMediaItemAt(it).mediaId == "yt:$videoId" }
        if (matches.isEmpty()) return@withPlayer
        val current = player.currentMediaItemIndex in matches
        val remaining = player.mediaItemCount - matches.size
        if (remaining == 0) { player.pause(); matches.drop(1).asReversed().forEach(player::removeMediaItem); return@withPlayer }
        // In shuffled order the player picks the survivor itself.
        val plan = if (player.shuffleModeEnabled) RemovalPlan.Proceed else removalPlan(player.mediaItemCount, player.currentMediaItemIndex, matches.toSet())
        if (plan is RemovalPlan.SelectBefore) { player.pause(); player.seekTo(plan.index, 0) }
        matches.asReversed().forEach(player::removeMediaItem)
        if (current && plan !is RemovalPlan.SelectBefore) { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
    }
    override fun seekQueueItem(index: Int) { transport(true); withPlayer { player ->
        val at = entryIndex(player, index) ?: return@withPlayer
        player.seekTo(at,0);if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()
    } }
    override fun attachVideo(surface:android.view.SurfaceView?)=withPlayer {
        videoSurface=surface
        if(surface==null) it.clearVideoSurface() else it.setVideoSurfaceView(surface)
    }
    override fun detachVideo(surface:android.view.SurfaceView)=withPlayer {
        // During a video crossfade the retiring view must not detach the new view.
        if(videoSurface===surface) {videoSurface=null;it.clearVideoSurface()}
    }
    override fun stageLive(track:ProviderTrack) {embeddedId="yt:${track.videoId}";embeddedPlaying=false;refresh();sendCatalogQueue(listOf(track),0,false);onLivePlay?.invoke(track)}
    override fun playAlbum(tracks: List<ProviderTrack>, index: Int)=sendCatalogQueue(tracks,index,true)
    override fun playCollection(tracks:List<ProviderTrack>,index:Int,title:String,shuffle:Boolean) {embeddedControl?.invoke(false);embeddedId=null;embeddedPlaying=false;sendCatalogQueue(tracks,index,true,title,shuffle)}
    override fun playSong(track:ProviderTrack) {embeddedControl?.invoke(false);embeddedId=null;embeddedPlaying=false;sendCatalogQueue(listOf(track),0,true,automatic=true)}
    fun enqueueSong(track:ProviderTrack,next:Boolean) {sendCatalogQueue(listOf(track),0,true,enqueueNext=next)}
    override fun restoreAudio(track:ProviderTrack,position:Long) {sendCatalogQueue(listOf(track),0,state.wantsPlayback,replacePosition=position)}
    // Raised by every request that replaces the queue. A request still being prepared when a later
    // replacement is made is dropped; requests that only add to the queue do not drop one another.
    private var replaceSequence=0L
    private val catalogScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+dependencies.dispatchers.main)
    // One lane, so requests reach the service in the order the user made them.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val requestLane=dependencies.dispatchers.io.limitedParallelism(1)
    private fun sendCatalogQueue(tracks:List<ProviderTrack>,index:Int,autoplay:Boolean,title:String="",shuffle:Boolean=false,automatic:Boolean=false,replacePosition:Long?=null,enqueueNext:Boolean?=null) {
        if(tracks.isEmpty() || index !in tracks.indices || tracks.size>2000) {commandError=PlaybackFailure(PlaybackFailureKind.CollectionInvalid);return}
        val replaces=replacePosition==null && enqueueNext==null
        val generation=if(replaces) ++replaceSequence else replaceSequence
        val owner=YouTubeMusicProvider.shared(appContext).session.value
        val pressed=transportSequence
        // A stream swap is meant for the entry selected now, not for whichever is selected when it lands.
        val entry=state.playback.queueItemId.takeIf {replacePosition!=null}
        if(replaces) {PerfTrace.began("queue_prepare");if(autoplay) PerfTrace.began("first_audio")}
        catalogScope.launch {
            val file=java.io.File(appContext.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json")
            try {
                kotlinx.coroutines.withContext(requestLane) { PerfTrace.block("queue_io_encode") {
                    val items=tracks.map {t->SavedQueueItem("yt:${t.videoId}",t.title.take(180),t.artist.take(180),t.artworkUrl,t.album.take(180),t.artistId,t.albumId,t.isLive,music=t.isMusic)}
                    // Checked before anything is sent: the service refuses a larger request.
                    val payload=encodePlaybackSnapshotBounded(PlaybackSnapshot(items,index,0,shuffle,collectionTitle=title.take(180)))?:return@block false
                    file.writeText(payload);true
                } }.let {fits->if(!fits) {commandError=PlaybackFailure(PlaybackFailureKind.CollectionTooLarge);refresh();return@launch}}
                if(generation!=replaceSequence) {file.delete();return@launch}
                // Pause pressed while the queue was being prepared: load it, but do not start it.
                val play=if(transportSequence!=pressed) autoplay && transportPlay else autoplay
                withPlayer {player->
                    commandError=null
                    val args=Bundle().apply {putString("queueFile",file.name);putBoolean("autoplay",play);entry?.takeIf {it.isNotEmpty()}?.let {putString("expectedEntry",it)};putBoolean("automatic",automatic);putLong("sessionGeneration",owner.generation);putString("sessionNonce",owner.nonce);replacePosition?.let {putLong("replacePosition",it)};enqueueNext?.let {putBoolean("enqueueNext",it)}}
                    val command=player.sendCustomCommand(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),args)
                    command.addListener({if(replaces) PerfTrace.ended("queue_prepare");try {if(command.get().resultCode !in setOf(SessionResult.RESULT_SUCCESS,SessionError.INFO_CANCELLED)) commandError=PlaybackFailure(PlaybackFailureKind.CollectionRejected);refresh()}
                        catch(_:Exception){commandError=PlaybackFailure(PlaybackFailureKind.CollectionRejected);refresh()}
                        finally {file.delete()}},appContext.mainExecutor)
                }
            } catch(e:kotlinx.coroutines.CancellationException) {file.delete();throw e}
            catch(_:Exception) {file.delete();commandError=PlaybackFailure(PlaybackFailureKind.CollectionPrepare);refresh()}
        }
    }
    override fun enqueueProvider(track: ProviderTrack, stream: ProviderStream, playNext: Boolean) =
        sendProvider(track,stream,0,true,playNext)
    override fun playProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long) {
        embeddedControl?.invoke(false);embeddedId=null;embeddedPlaying=false
        replaceSequence++
        sendProvider(track,stream,startPositionMs,false,false)
    }
    override fun replaceProvider(track:ProviderTrack,stream:ProviderStream,position:Long)=sendProvider(track,stream,position,false,false,true)
    private fun sendProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long,
        enqueue: Boolean, playNext: Boolean, replace:Boolean=false) {
        val entry=state.playback.queueItemId.takeIf {replace && it.isNotEmpty()}
        withPlayer { player ->
        commandError = null
        val headers = Bundle().apply { stream.headers.forEach { (key, value) -> putString(key, value) } }
        val args = Bundle().apply {
            putString("url", stream.url)
            putString("mimeType", stream.mimeType)
            putInt("itag",stream.itag);putInt("bitrate",stream.bitrate)
            putString("profileId",stream.profileId);putBoolean("includesVideo",stream.includesVideo)
            putString("streamVideoId",stream.sourceVideoId);putString("videoUrl",stream.videoUrl);putString("videoMimeType",stream.videoMimeType)
            stream.sabrVideoBootstrap?.let {putString("sabrVideoKey",SabrPlaybackRegistry.register(it))}
            putBoolean("replaceCurrent",replace);entry?.let {putString("expectedEntry",it)}
            stream.sabrBootstrap?.let { putString("sabrKey", SabrPlaybackRegistry.register(it)) }
            putString("videoId", track.videoId)
            putString("title", track.title)
            putString("artist", track.artist)
            putString("artworkUrl", track.artworkUrl)
            putString("artistId",track.artistId);putString("albumId",track.albumId);putString("album",track.album)
            putBoolean("live",track.isLive);putBoolean("enqueue",enqueue);putBoolean("playNext",playNext)
            putLong("position", startPositionMs.coerceAtLeast(0L))
            putBundle("headers", headers)
            YouTubeMusicProvider.shared(appContext).session.value.let {
                putLong("sessionGeneration", it.generation); putString("sessionNonce", it.nonce)
            }
        }
        val command = player.sendCustomCommand(SessionCommand(PLAY_PROVIDER_STREAM, Bundle.EMPTY), args)
        command.addListener({
                // The service never logs signed URLs or request headers.
                try {
                    val result = command.get()
                    // Skipped: the account changed after this stream was requested. Nothing to report.
                    if (result.resultCode == SessionResult.RESULT_SUCCESS || result.resultCode == SessionError.INFO_CANCELLED) refresh()
                    else fail(PlaybackFailure(PlaybackFailureKind.StreamRejected))
                } catch (error: Exception) {
                    fail(PlaybackFailure(PlaybackFailureKind.StreamCommand))
                }
        }, appContext.mainExecutor)
        }
    }
    override fun playLocal(track: LocalAudioTrack) { replaceSequence++; withPlayer { player ->
        commandError = null
        val args = Bundle().apply {
            putString("uri", track.uri.toString())
            putLong("id", track.id)
            putString("title", track.title.take(180))
            putString("artist", track.artist.take(180))
            putString("album", track.album.take(180))
        }
        val command = player.sendCustomCommand(SessionCommand(PLAY_LOCAL_AUDIO, Bundle.EMPTY), args)
        command.addListener({
            try {
                // Cancelled: a later request replaced this one. Nothing to report.
                if (command.get().resultCode in setOf(SessionResult.RESULT_SUCCESS, SessionError.INFO_CANCELLED)) refresh()
                else fail(PlaybackFailure(PlaybackFailureKind.LocalUnavailable))
            } catch (_: Exception) {
                fail(PlaybackFailure(PlaybackFailureKind.LocalCommand))
            }
        }, appContext.mainExecutor)
    } }
    override fun restoreSavedQueue(done:(Boolean)->Unit) { replaceSequence++; withPlayer { player ->
        val owner=YouTubeMusicProvider.shared(appContext).session.value
        val command=player.sendCustomCommand(SessionCommand(RESTORE_SAVED_QUEUE,Bundle.EMPTY),
            Bundle().apply {putLong("sessionGeneration",owner.generation);putString("sessionNonce",owner.nonce)})
        command.addListener({
            val loaded=runCatching {command.get().resultCode==SessionResult.RESULT_SUCCESS}.getOrDefault(false)
            refresh();done(loaded)
        },appContext.mainExecutor)
    } }
    override fun hasProviderItems():Boolean=controller?.let {player->(0 until player.mediaItemCount).any {player.getMediaItemAt(it).mediaId.startsWith("yt:")}}==true
    override fun clearProviderPlayback() = withPlayer { player ->
        if ((0 until player.mediaItemCount).any {
                player.getMediaItemAt(it).mediaId.startsWith("yt:")
            }) {
            player.pause()
            player.clearMediaItems()
            commandError = null
            refresh()
        }
    }
    override fun close() {
        catalogScope.cancel()
        handler.removeCallbacks(ticker)
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
        controller = null
    }
}

/**
 * Play/pause decides on what the user sees. After a load error the player is idle but keeps
 * playWhenReady, so "pause if playWhenReady" made the first tap on Play do nothing visible
 * (found when a track change fell in a network drop, 2026-10-04). Idle always means play.
 */
internal fun toggleShouldPause(isPlaying: Boolean, playWhenReady: Boolean, idle: Boolean): Boolean =
    !idle && (isPlaying || playWhenReady)

/**
 * Deleting the only item leaves an empty, paused queue. Deleting the current item when nothing follows
 * it selects the one before, paused at its start, where the player by itself would jump to the first.
 */
internal fun removePlaybackQueueItem(player: Player, index: Int) {
    val count = player.mediaItemCount
    if (index !in 0 until count) return
    if (count == 1) player.pause()
    else if (index == player.currentMediaItemIndex) {
        val plan = removalPlan(count, index, index, player.nextMediaItemIndex, player.previousMediaItemIndex)
        if (plan is RemovalPlan.SelectBefore) { player.pause(); player.seekTo(plan.index, 0) }
    }
    player.removeMediaItem(index)
}
