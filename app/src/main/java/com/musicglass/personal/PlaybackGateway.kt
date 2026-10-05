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
)

internal data class QueueEntry(val mediaId: String, val title: String, val artist: String, val artworkUrl: String? = null,val queueId:String="")

internal interface PlaybackGateway {
    val state: PlaybackUiState
    val positionMs: Long
    fun currentPositionMs(): Long = positionMs
    fun toggle()
    fun setSleepTimer(minutes:Int) {}
    fun pause()
    fun resume()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun cycleRepeat()
    fun moveQueueItem(from: Int, to: Int)
    fun seekQueueItem(index: Int) {}
    fun enqueueProvider(track: ProviderTrack, stream: ProviderStream, playNext: Boolean = false) {}
    fun playSong(track:ProviderTrack) {}
    fun restoreAudio(track:ProviderTrack,position:Long) {}
    fun playAlbum(tracks: List<ProviderTrack>, index: Int) {}
    fun playCollection(tracks:List<ProviderTrack>,index:Int,title:String,shuffle:Boolean=false) {playAlbum(tracks,index)}
    fun stageLive(track:ProviderTrack) {}
    fun replaceProvider(track:ProviderTrack,stream:ProviderStream,position:Long) {}
    fun attachVideo(surface:android.view.SurfaceView?) {}
    fun detachVideo(surface:android.view.SurfaceView) {attachVideo(null)}
    fun removeQueueItem(index: Int)
    /** Skips [videoId] if it is playing and takes every copy of it out of the queue. */
    fun dropTrack(videoId: String) {}
    fun playProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long = 0L)
    fun playLocal(track: LocalAudioTrack)
    fun clearProviderPlayback()
    fun close()
}

internal class MusicPlaybackGateway(context: Context) : PlaybackGateway {
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
    private var commandError: String? = null
    private val future = MediaController.Builder(appContext,
        SessionToken(appContext, ComponentName(appContext, MusicPlaybackService::class.java)))
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
                state = state.copy(error = "Playback could not connect: ${error.message}")
            }
        }, appContext.mainExecutor)
    }

    private fun withPlayer(command: (MediaController) -> Unit) {
        val current = controller
        if (current == null) pending = command else command(current)
    }
    private fun refresh(readQueue: Boolean = true) {
        val player = controller ?: return
        val updated = PlaybackUiState(
            sleepDeadlineMs=player.sessionExtras.getLong("sleepDeadlineMs"),
            ready = true,
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            playing = if((embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId)) embeddedPlaying else player.isPlaying,
            wantsPlayback = if((embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId)) embeddedPlaying else player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeat = player.repeatMode,
            error = if(player.playlistMetadata.extras?.getBoolean("providerRetrying")==true || player.playlistMetadata.extras?.getBoolean("liveRetrying")==true || (embeddedId!=null && embeddedId==player.currentMediaItem?.mediaId)) null else commandError ?: player.playerError?.let { error ->
                if(player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live")==true) "YouTube could not start this live stream. Try refreshing Radio."
                else if (player.currentMediaItem?.mediaId?.startsWith("yt:") == true)
                    "YouTube Music could not start this track. Try another track."
                else "Playback failed (${error.errorCodeName})"
            },
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
                    item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString(),
                    player.currentTimeline.getWindow(index,androidx.media3.common.Timeline.Window()).uid.toString())
            },
        )
        positionMs = updated.positionMs
        // Position ticks must not invalidate metadata, artwork, queue, and chrome.
        if (updated.copy(positionMs = state.positionMs) != state) state = updated
    }
    override fun toggle() = withPlayer {
        if(state.embedded) {
            val control=embeddedControl
            if(control!=null) control(!embeddedPlaying) else onLivePlay?.invoke(ProviderTrack(state.mediaId.removePrefix("yt:"),state.title,state.artist,state.artworkUrl,isLive=true))
            return@withPlayer
        }
        commandError = null
        if (toggleShouldPause(it.isPlaying,it.playWhenReady,it.playbackState==Player.STATE_IDLE)) it.pause()
        else { if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() }
    }
    override fun setSleepTimer(minutes:Int) = withPlayer {it.sendCustomCommand(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY),Bundle().apply {putInt("minutes",minutes)})}
    override fun pause() = withPlayer { it.pause();if(state.embedded) embeddedControl?.invoke(false) }
    override fun resume() = withPlayer { if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() }
    override fun next() = withPlayer { commandError = null; it.seekToNextMediaItem(); if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() }
    override fun previous() = withPlayer { commandError = null; it.seekToPreviousMediaItem(); if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() }
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
    override fun moveQueueItem(from: Int, to: Int) = withPlayer { player ->
        if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount && from != to)
            player.moveMediaItem(from, to)
    }
    override fun removeQueueItem(index: Int) = withPlayer { player ->
        if (index in 0 until player.mediaItemCount) {
            commandError = null
            removePlaybackQueueItem(player,index)
        }
    }
    override fun dropTrack(videoId: String) = withPlayer { player ->
        val matches = (0 until player.mediaItemCount).filter { player.getMediaItemAt(it).mediaId == "yt:$videoId" }
        if (matches.isEmpty()) return@withPlayer
        val current = player.currentMediaItemIndex in matches
        val remaining = player.mediaItemCount - matches.size
        if (remaining == 0) { player.pause(); matches.drop(1).asReversed().forEach(player::removeMediaItem); return@withPlayer }
        matches.asReversed().forEach(player::removeMediaItem)
        if (current) { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
    }
    override fun seekQueueItem(index: Int) = withPlayer { player ->
        if(index in 0 until player.mediaItemCount) { player.seekTo(index,0);if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play() }
    }
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
    private var catalogGeneration=0
    private val catalogScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.Main.immediate)
    private fun sendCatalogQueue(tracks:List<ProviderTrack>,index:Int,autoplay:Boolean,title:String="",shuffle:Boolean=false,automatic:Boolean=false,replacePosition:Long?=null,enqueueNext:Boolean?=null) {
        if(tracks.isEmpty() || index !in tracks.indices || tracks.size>2000) {commandError="Could not open this collection";return}
        val generation=++catalogGeneration
        catalogScope.launch {
            val file=java.io.File(appContext.cacheDir,"queue-request-${java.util.UUID.randomUUID()}.json")
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val items=tracks.map {t->SavedQueueItem("yt:${t.videoId}",t.title.take(180),t.artist.take(180),t.artworkUrl,t.album.take(180),t.artistId,t.albumId,t.isLive,music=t.isMusic)}
                    file.writeText(encodePlaybackSnapshot(PlaybackSnapshot(items,index,0,shuffle,collectionTitle=title.take(180))))
                }
                if(generation!=catalogGeneration) {file.delete();return@launch}
                withPlayer {player->
                    commandError=null
                    val args=Bundle().apply {putString("queueFile",file.name);putBoolean("autoplay",autoplay);putBoolean("automatic",automatic);replacePosition?.let {putLong("replacePosition",it)};enqueueNext?.let {putBoolean("enqueueNext",it)}}
                    val command=player.sendCustomCommand(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY),args)
                    command.addListener({try {if(command.get().resultCode!=SessionResult.RESULT_SUCCESS) commandError="Could not open collection queue";refresh()}
                        catch(_:Exception){commandError="Could not open collection queue";refresh()}
                        finally {file.delete()}},appContext.mainExecutor)
                }
            } catch(e:kotlinx.coroutines.CancellationException) {file.delete();throw e}
            catch(_:Exception) {file.delete();commandError="Could not prepare collection queue";refresh()}
        }
    }
    override fun enqueueProvider(track: ProviderTrack, stream: ProviderStream, playNext: Boolean) =
        sendProvider(track,stream,0,true,playNext)
    override fun playProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long) {
        embeddedControl?.invoke(false);embeddedId=null;embeddedPlaying=false
        sendProvider(track,stream,startPositionMs,false,false)
    }
    override fun replaceProvider(track:ProviderTrack,stream:ProviderStream,position:Long)=sendProvider(track,stream,position,false,false,true)
    private fun sendProvider(track: ProviderTrack, stream: ProviderStream, startPositionMs: Long,
        enqueue: Boolean, playNext: Boolean, replace:Boolean=false) = withPlayer { player ->
        commandError = null
        val headers = Bundle().apply { stream.headers.forEach { (key, value) -> putString(key, value) } }
        val args = Bundle().apply {
            putString("url", stream.url)
            putString("mimeType", stream.mimeType)
            putInt("itag",stream.itag);putInt("bitrate",stream.bitrate)
            putString("profileId",stream.profileId);putBoolean("includesVideo",stream.includesVideo)
            putString("streamVideoId",stream.sourceVideoId);putString("videoUrl",stream.videoUrl);putString("videoMimeType",stream.videoMimeType)
            stream.sabrVideoBootstrap?.let {putString("sabrVideoKey",SabrPlaybackRegistry.register(it))}
            putBoolean("replaceCurrent",replace)
            stream.sabrBootstrap?.let { putString("sabrKey", SabrPlaybackRegistry.register(it)) }
            putString("videoId", track.videoId)
            putString("title", track.title)
            putString("artist", track.artist)
            putString("artworkUrl", track.artworkUrl)
            putString("artistId",track.artistId);putString("albumId",track.albumId);putString("album",track.album)
            putBoolean("live",track.isLive);putBoolean("enqueue",enqueue);putBoolean("playNext",playNext)
            putLong("position", startPositionMs.coerceAtLeast(0L))
            putBundle("headers", headers)
        }
        val command = player.sendCustomCommand(SessionCommand(PLAY_PROVIDER_STREAM, Bundle.EMPTY), args)
        command.addListener({
                // The service never logs signed URLs or request headers.
                try {
                    val result = command.get()
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) refresh()
                    else {
                        commandError = "Provider playback is unavailable"
                        state = state.copy(error = commandError)
                    }
                } catch (error: Exception) {
                    commandError = "Could not start provider playback"
                    state = state.copy(error = commandError)
                }
        }, appContext.mainExecutor)
    }
    override fun playLocal(track: LocalAudioTrack) = withPlayer { player ->
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
                if (command.get().resultCode == SessionResult.RESULT_SUCCESS) refresh()
                else {
                    commandError = "This on-device audio file is unavailable"
                    state = state.copy(error = commandError)
                }
            } catch (_: Exception) {
                commandError = "Could not open this on-device audio file"
                state = state.copy(error = commandError)
            }
        }, appContext.mainExecutor)
    }
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

/** Deleting the final item leaves an empty, paused queue. Media3 adjusts the index for other removals. */
internal fun removePlaybackQueueItem(player: Player, index: Int) {
    if (index !in 0 until player.mediaItemCount) return
    if (player.mediaItemCount == 1) player.pause()
    player.removeMediaItem(index)
}
