package com.musicglass.personal

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.UUID
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Background playback for real provider streams and on-device audio. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class MusicPlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    /** The player Riff drives. The session is given [sessionPlayer], which shows a recovering song as waiting, not failed (Q18-020). */
    private var mainPlayer: ExoPlayer? = null
    private var sessionPlayer: WaitingSessionPlayer? = null
    private val audio by lazy {AudioTransitionCoordinator(this,serviceScope,runtime,events) {mainPlayer}}
    private val events by lazy { PlaybackEventLog(this) }
    private val runtime by lazy {AppContainer.get(this).runtime}
    private val serviceScope by lazy {CoroutineScope(SupervisorJob()+runtime.dispatchers.main)}
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queueDecoder by lazy {runtime.dispatchers.io.limitedParallelism(1)}
    private val provider by lazy { YouTubeMusicProvider.shared(this) }
    private val sleepTimer by lazy {SleepTimerController(serviceScope,runtime.clocks::monotonicMillis,wait=runtime.wait,pause={mainPlayer?.pause()},publish={deadline->sleepDeadlineMs=deadline;publishState()},readVolume={mainPlayer?.volume?:1f},writeVolume={mainPlayer?.volume=it},persist=::persistSleepDeadline,fadeMs={sleepFadeMs(this)})}
    /** Feature 18: a timer counted in songs ("End of this song", "After 3 songs", "End of queue"). */
    private val sleepSongs=SleepSongs()
    /** Feature 15: songs and episodes keep their own speed. */
    private val speeds by lazy {PlaybackSpeeds(this)}
    /** Feature 16: decoder, dropouts and the network estimate for the stats for nerds panel. */
    private val nerdStats=NerdStatsCollector()
    /** Feature 23: Like and Shuffle on the notification and lock screen; sent again only when they change. */
    private var mediaButtonState:MediaButtonState?=null
    private val buttonVideoId=androidx.compose.runtime.mutableStateOf("")
    private fun refreshMediaButtons() {
        val player=mainPlayer?:return
        val id=player.currentMediaItem?.mediaId?.takeIf {it.startsWith("yt:")}?.removePrefix("yt:").orEmpty()
        buttonVideoId.value=id
        val state=MediaButtonState(likeable=id.isNotEmpty() && provider.signedIn && !isLive(player.currentMediaItem),
            liked=provider.songLikes[id]==true,shuffle=player.shuffleModeEnabled)
        if(state==mediaButtonState) return
        mediaButtonState=state
        session?.setMediaButtonPreferences(mediaButtons(state))
    }
    private fun isLive(item:MediaItem?)=item?.mediaMetadata?.extras?.getBoolean("live")==true
    /** The home widgets show what the lock screen's buttons show: whether the song is liked, and shuffle. */
    private fun publishWidgets(player:Player,seeked:Boolean=false)=RiffWidgets.publish(this,player,mediaButtonState?.takeIf {it.likeable}?.liked,seeked)
    /** Moves the widgets' progress on while playing with the screen on; stopped when playing stops. */
    private var widgetTicker:kotlinx.coroutines.Job?=null
    private fun tickWidgets(player:Player) {
        if(!player.isPlaying) {widgetTicker?.cancel();widgetTicker=null;return}
        if(widgetTicker!=null) return
        val power=getSystemService(android.os.PowerManager::class.java)
        widgetTicker=serviceScope.launch {while(true) {delay(WIDGET_TICK_MS);if(power.isInteractive) RiffWidgets.tick(this@MusicPlaybackService,player)}}
    }
    private fun pressMediaButton(player:Player,action:String):Int {
        if(action==TOGGLE_SHUFFLE) {player.shuffleModeEnabled=!player.shuffleModeEnabled;return SessionResult.RESULT_SUCCESS}
        val id=buttonVideoId.value
        if(id.isEmpty() || !provider.signedIn) return SessionError.ERROR_NOT_SUPPORTED
        val liked=provider.songLikes[id]==true
        serviceScope.launch {
            provider.setSongLike(id,!liked).onFailure {events.append("media_button_like_failed")}
            refreshMediaButtons()
        }
        return SessionResult.RESULT_SUCCESS
    }
    private fun isMusic(item:MediaItem?)=item?.mediaMetadata?.extras?.getBoolean("music",true)!=false
    /** Points the timer's deadline at the end of the last counted song, so the fade leads up to it. */
    private fun rearmSleepSong() {
        val player=mainPlayer?:return
        if(!sleepSongs.onLastSong || player.duration<=0) return
        val deadline=sleepSongDeadline(runtime.clocks.monotonicMillis(),player.duration,player.currentPosition,player.playbackParameters.speed)
        if(kotlin.math.abs(deadline-sleepDeadlineMs)>1_000) sleepTimer.arm(deadline)
    }
    private fun bootCount()=android.provider.Settings.Global.getInt(contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
    private fun persistSleepDeadline(deadline:Long) {
        val edit=snapshotPrefs.edit()
        if(deadline>0) edit.putLong("sleep_deadline",deadline).putInt("sleep_boot",bootCount()) else edit.remove("sleep_deadline").remove("sleep_boot")
        edit.apply()
    }
    /** W6: a timer set before the process was killed keeps its original deadline. */
    private fun restoreSleepTimerAfterRestart() {
        val saved=snapshotPrefs.getLong("sleep_deadline",0)
        when(val restore=restoreSleepTimer(saved,snapshotPrefs.getInt("sleep_boot",-1),android.os.SystemClock.elapsedRealtime(),bootCount())) {
            is SleepRestore.Rearm -> {sleepTimer.arm(restore.deadline);events.append("sleep_timer_restored")}
            SleepRestore.Expired -> {persistSleepDeadline(0);events.append("sleep_timer_expired_while_stopped")}
            SleepRestore.None -> if(saved>0) persistSleepDeadline(0)
        }
    }
    private var recoverySignal = RecoverySignal()
    private val sabrHttp = HttpClient(OkHttp)
    private val offline by lazy {OfflineMusicStore.get(this)}
    private var listeningId=""
    /** Feature 12: listened time per play for Encore, written off the main thread. */
    private val encore=EncoreListening({s,e,ms->serviceScope.launch(Dispatchers.IO) {history.addListened(s,e,ms)}},
        {s,e,how->serviceScope.launch(Dispatchers.IO) {history.markEnded(s,e,how);EncoreWidgets.refresh(this@MusicPlaybackService)}})
    private var lastListenTick=android.os.SystemClock.elapsedRealtime()
    private val snapshotPrefs by lazy {getSharedPreferences("last_playback",MODE_PRIVATE)}
    private var taskRemoved=false
    private var saveJob: Job?=null
    private val persistence:PlaybackPersistence by lazy {AppContainer.get(this).let {process->PlaybackPersistence(serviceScope,process.snapshots,{process.stateReady},{offline.currentScope},{queue.radioSeed})}}
    private val history by lazy {AppContainer.get(this).history}
    private val plays=LogicalPlays()
    private val sessions by lazy {AppContainer.get(this).sessions}
    private var sessionRegistration:AutoCloseable?=null
    // Q06: commands are ticketed on arrival and applied one at a time, in that order.
    private val order=PlaybackCommandOrder()
    private val commands by lazy {OrderedCommands(serviceScope,order,SessionResult(SessionError.INFO_CANCELLED),SessionResult(SessionError.ERROR_UNKNOWN))}
    private val internalChange=InternalChange()
    /** Feature 19: null on a phone without Google Play services, where Riff plays only on the phone. */
    private var cast: CastSync? = null

    /**
     * The session's player: [shown] alone, or, where Cast works, Media3's player that moves between [shown] and a
     * speaker. Riff's queue stays on [local] either way.
     */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun castable(shown: Player, local: ExoPlayer): Player = try {
        androidx.media3.cast.Cast.getSingletonInstance(this).initialize()
        val sync = CastSync(serviceScope, {mainPlayer}, {item->provider.resolve(item.mediaId.removePrefix("yt:")).getOrNull()}, events::append) {item->
            android.widget.Toast.makeText(this, getString(R.string.cast_song_refused, item.mediaMetadata.title ?: ""), android.widget.Toast.LENGTH_SHORT).show()
        }
        val remote = androidx.media3.cast.RemoteCastPlayer.Builder(this).setMediaItemConverter(RiffCastConverter).build()
        sync.remote = remote
        local.addListener(sync.localListener)
        cast = sync
        androidx.media3.cast.CastPlayer.Builder(this).setLocalPlayer(shown).setRemotePlayer(remote).setTransferCallback(sync).build()
    } catch (e: RuntimeException) {
        events.append("cast_unavailable type=${e.javaClass.simpleName}")
        shown
    }
    private inline fun <T> internally(block:()->T):T=internalChange.run(block)
    private val queue:QueueCoordinator by lazy {QueueCoordinator(this,serviceScope,provider,sessions,offline,events,order,audio,{persistence},internalChange,{mainPlayer}) {recovery.clear()}}
    private val faults:PlaybackFaults by lazy {PlaybackFaults(serviceScope,runtime,provider,offline,events,queue)}
    private val sources:PlaybackSources by lazy {PlaybackSources(this,serviceScope,provider,offline,events,audio,queue,sabrHttp,::publishState)}
    private var sleepDeadlineMs=0L
    private var publishedState=""
    /** Feature 14: the library Android Auto browses. */
    private val car by lazy {CarBrowse(serviceScope,CarLibrary(ServiceCarSource(this) {runtime.networkConditions().validated},runtime.clocks::monotonicMillis))}
    /** Queue revision, owner and what is being done for the selected entry, for every controller. Main thread. */
    private fun publishState() {
        val media=session?:return;val player=mainPlayer?:return
        val entry=player.currentMediaItem?.entryId().orEmpty().takeIf(sources::isResolving).orEmpty()
        val signal=recoverySignal
        val owner=persistence.owner
        val key="$sleepDeadlineMs|${order.queueRevision}|$owner|$entry|$signal"
        if(key==publishedState) return
        publishedState=key
        media.setSessionExtras(Bundle().apply {putLong("sleepDeadlineMs",sleepDeadlineMs);putLong("queueRevision",order.queueRevision)
            putString("queueOwner",owner);putString("resolving",entry);putString("recovery",signal.recovery?.wire.orEmpty())
            putString("failure",signal.failure?.name.orEmpty());putBoolean("recoveryHeld",signal.held)})
        sessionPlayer?.refresh()
    }
    /**
     * A command that changes the queue. [prepare] may read files off the main thread; [apply] then
     * runs on the main thread, after every command that arrived earlier. A request that a later one
     * has replaced is answered as cancelled without touching the queue.
     */
    private fun <P> queueCommand(replaces:Boolean,onSuperseded:()->Unit={},prepare:suspend ()->P,apply:(PlaybackCommandOrder.Ticket,P)->SessionResult):ListenableFuture<SessionResult> {
        val result=com.google.common.util.concurrent.SettableFuture.create<SessionResult>()
        commands.submit(replaces,{onSuperseded();events.append("command_superseded")},prepare,{ticket,prepared->internally {apply(ticket,prepared)}}) {result.set(it)}
        return result
    }
    override fun onCreate() {
        super.onCreate()
        events.append("service_create")
        serviceScope.launch(Dispatchers.IO) {logNewProcessExits(this@MusicPlaybackService,events)}
        runCatching {
            val callback=object:android.net.ConnectivityManager.NetworkCallback() {
                private fun changed() {serviceScope.launch {recovery.connectivityChanged()}}
                override fun onAvailable(network:android.net.Network)=changed()
                override fun onLost(network:android.net.Network)=changed()
                override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities)=changed()
            }
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            networkCallback=callback
        }
        serviceScope.launch(Dispatchers.IO) {runCatching {offline.indexArtwork(offline.currentScope)}}
        // Q18-057: downloads stored with too few seek points are stored again once, after start-up has settled.
        serviceScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(30_000)
            suspendCatching {DownloadRepack.repackAll(offline,offline.currentScope,inQueue={id->withContext(Dispatchers.Main) {
                mainPlayer?.let {p->(0 until p.mediaItemCount).any {p.getMediaItemAt(it).mediaId.removePrefix("yt:")==id}}?:false}},events=events)}
        }
        persistence.start()
        // The provider is made here, not inside the snapshot below: making it writes state, which a read-only snapshot
        // refuses. That happened when a car or a test started the service before the app had made the provider.
        val likes=provider
        serviceScope.launch {androidx.compose.runtime.snapshotFlow {likes.songLikes[buttonVideoId.value]}.collect {refreshMediaButtons();mainPlayer?.let {publishWidgets(it)}}}
        val mediaFactory = sources
        audio.start()
        val renderers = audio.renderers()
        audio.createCrossfade(mediaFactory, sleepActive = { (session?.sessionExtras?.getLong("sleepDeadlineMs") ?: 0L) > 0L },
            tailItem=queue::tailItem)
        // Crossfade synchronization requires frequent decoder-position updates.
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaFactory).setLoadControl(riffLoadControl())
            .setMaxSeekToPreviousPositionMs(PREVIOUS_RESTARTS_AFTER_MS)
            .experimentalSetDynamicSchedulingEnabled(false)
            .build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build(), true)
            // Decoded PCM is required for amplification, including at unity gain.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setAudioOffloadPreferences(
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
            setHandleAudioBecomingNoisy(true)
            addAnalyticsListener(nerdStats)
            audio.attach(this)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) = audio.audioSessionChanged(audioSessionId)
                override fun onEvents(player: Player,events: Player.Events) {refreshMediaButtons();audio.update();persistence.save(player);publishState();publishWidgets(player,events.contains(Player.EVENT_POSITION_DISCONTINUITY));tickWidgets(player);sources.lookUpNext(player)}
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    if(reason==Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) order.edited()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    events.append("play_when_ready=$playWhenReady reason=$reason")
                    // The app, the notification, a headset or a widget: all arrive as a user request.
                    if(reason==Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !internalChange.active) order.userIntent(playWhenReady)
                    // A pause holds recovery whatever caused it; play after that starts it again.
                    if(!internalChange.active) recovery.intentChanged(playWhenReady)
                    if(playWhenReady) queue.replenish(this@apply)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    events.append("playback_state=$playbackState")
                    offline.playbackBusy=playbackState==Player.STATE_BUFFERING || this@apply.isPlaying
                    if(playbackState==Player.STATE_READY) {queue.replenish(this@apply);this@apply.currentMediaItem?.entryId()?.let(recovery::ready)}
                    if(playbackState==Player.STATE_ENDED) {plays.completed();encore.ended(EncoreEnded.FINISHED)
                        if(sleepSongs.active) {sleepSongs.cancel();sleepTimer.close()}}
                }
                override fun onIsPlayingChanged(isPlaying:Boolean) {offline.playbackBusy=isPlaying || this@apply.playbackState==Player.STATE_BUFFERING;lastListenTick=android.os.SystemClock.elapsedRealtime()}
                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    // Feature 15: the speed asked for is remembered for this kind of item. The crossfade's own small
                    // corrections are made on its tail player, never here.
                    speeds.remember(isMusic(this@apply.currentMediaItem),playbackParameters.speed)
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val wanted=speeds.forKind(isMusic(mediaItem))
                    if(kotlin.math.abs(this@apply.playbackParameters.speed-wanted)>.001f) this@apply.setPlaybackSpeed(wanted)
                    if(reason!=Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || mediaItem!==queue.replacement) {
                        encore.ended(encoreEndedBy(reason))
                        if(sleepSongs.active) {
                            val finished=reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                            if(finished && sleepSongs.finishedOne()) {this@apply.pause();sleepTimer.close();events.append("sleep_timer_songs_done")}
                            else serviceScope.launch {rearmSleepSong()}
                        }
                        listeningId="";plays.started();lastListenTick=android.os.SystemClock.elapsedRealtime()
                        queue.replacement=null
                    }
                    // Recovery for an entry the user has left is obsolete; its own replacement keeps the entry.
                    recovery.entryChanged(mediaItem?.entryId())
                    if(mediaItem == null) queue.reset() else queue.replenish(this@apply)
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val causes = generateSequence(error as Throwable?) { it.cause }
                        .take(5).joinToString(">") { it.javaClass.simpleName }
                    events.append("player_error=${error.errorCodeName} causes=$causes")
                    this@apply.currentMediaItem?.mediaId?.takeIf {it.startsWith("yt:")}?.removePrefix("yt:")?.let(provider::invalidateStream)
                    generateSequence(error as Throwable?) {it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {failure ->
                        val uri=failure.dataSpec.uri
                        events.append("http_rejected code=${failure.responseCode} host=${hostClass(uri.host)} kind=${if(uri.path?.contains(".m3u8")==true) "manifest" else "segment"}")
                    }
                    // Every retry is decided in one place (Q07).
                    this@apply.currentMediaItem?.let {item->recovery.failed(item.entryId(),faults.of(item,error.errorCodeName,error))}
                }
            })
        }
        val sessionActivity = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        mainPlayer = player
        val shown = WaitingSessionPlayer(player) { recovery.masksError }.also { sessionPlayer = it }
        session = MediaLibrarySession.Builder(this, castable(shown, player), object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Another app: transport and reading, not rewriting the queue, and none of the app's own commands.
                if (!isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid())) {
                    // Feature 14: Android Auto may browse, and play what it chose from the library (onSetMediaItems decides what).
                    val browses=mayBrowse(session,controller)
                    val accepted=MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(if(browses) MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS else foreignPlayerCommands())
                    // Feature 23: the lock screen and the car may press Like and Shuffle.
                    val commands=if(browses) MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS else MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    accepted.setAvailableSessionCommands(commands.buildUpon().apply {
                        if(mayPressMediaButtons(session,controller,own=false)) mediaButtonCommands.forEach {add(SessionCommand(it,Bundle.EMPTY))}}.build())
                    return accepted.build()
                }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon().add(SessionCommand(PLAY_PROVIDER_STREAM, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_LOCAL_AUDIO, Bundle.EMPTY)).add(SessionCommand(RETRY_PLAYBACK, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY)).add(SessionCommand(RESTORE_SAVED_QUEUE,Bundle.EMPTY)).add(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY)).add(SessionCommand(LOAD_MORE_QUEUE,Bundle.EMPTY)).add(SessionCommand(GET_NERD_STATS,Bundle.EMPTY))
                        .apply {mediaButtonCommands.forEach {add(SessionCommand(it,Bundle.EMPTY))}}.build())
                    .build()
            }

            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) = car.root()
            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
                params: LibraryParams?) = car.children(parentId,page,pageSize,params)
            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) = car.item(mediaId)
            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) =
                car.search(session,browser,query,params)
            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
                params: LibraryParams?) = car.searchResult(query,page,pageSize,params)
            // The app's own controller sets and adds items as before; for the car, only Riff's own ids, made into Riff's queue.
            override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
                startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
                if(isOwnController(controller.packageName,controller.uid,packageName,android.os.Process.myUid())) super.onSetMediaItems(mediaSession,controller,mediaItems,startIndex,startPositionMs)
                else car.play(mediaItems,startIndex) {tracks->queue.carQueue(player,tracks)}
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                if(isOwnController(controller.packageName,controller.uid,packageName,android.os.Process.myUid())) super.onAddMediaItems(mediaSession,controller,mediaItems)
                else Futures.transform(car.add(mediaItems) {queue.carItem(it)},{it.toMutableList()},com.google.common.util.concurrent.MoreExecutors.directExecutor())

            override fun onCustomCommand(session: MediaSession,
                controller: MediaSession.ControllerInfo, customCommand: SessionCommand,
                args: Bundle): ListenableFuture<SessionResult> {
                if (!isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid())) {
                    // Feature 23: the lock screen and the car may press Like and Shuffle; nothing else of the app's.
                    if(!mayPressMediaButtons(session,controller,own=false)) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    return Futures.immediateFuture(if(customCommand.customAction in mediaButtonCommands) SessionResult(pressMediaButton(player,customCommand.customAction))
                        else SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                if(customCommand.customAction in mediaButtonCommands) return Futures.immediateFuture(SessionResult(pressMediaButton(player,customCommand.customAction)))
                if(customCommand.customAction==SET_SLEEP_TIMER) {
                    val songs=args.getInt("songs",0)
                    if(songs>0) {
                        // Feature 18: counted in songs; the crossfade stays off once the last one is playing.
                        audio.cancelBlend();sleepTimer.close();sleepSongs.set(songs);rearmSleepSong()
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    val minutes=args.getInt("minutes")
                    if(minutes !in 0..1440) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    if(minutes>0) audio.cancelBlend()
                    sleepSongs.cancel()
                    sleepTimer.set(minutes)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if(customCommand.customAction==GET_NERD_STATS)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS,nerdStatsBundle(player,nerdStats,audio.lastBlend)))
                if(customCommand.customAction==LOAD_MORE_QUEUE) {
                    queue.replenish(player,onDemand=true)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if(customCommand.customAction==RETRY_PLAYBACK) {
                    // The user asked again for the selected entry: a new episode with a full budget.
                    val entry=args.getString("entry").orEmpty()
                    if(player.currentMediaItem?.entryId()!=entry) return Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
                    retryPlayback(player)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Not an error for the new owner: the request simply no longer applies.
                if(customCommand.customAction!=PLAY_LOCAL_AUDIO && staleSession(args)) return Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
                if(customCommand.customAction==RESTORE_SAVED_QUEUE) {
                    // The user asked for the saved queue of the owner they are in. It replaces the player's queue, paused.
                    return queueCommand(replaces=true,prepare={persistence.loadNewest()}) {_,stored->
                        SessionResult(when {
                            staleSession(args) -> SessionError.INFO_CANCELLED
                            stored==null -> SessionError.ERROR_BAD_VALUE
                            else -> {
                                queue.replaceWithSaved(player,stored);SessionResult.RESULT_SUCCESS
                            }
                        })
                    }
                }
                if(customCommand.customAction==PLAY_CATALOG_QUEUE) {
                    val name=args.getString("queueFile").orEmpty()
                    if(!name.matches(Regex("queue-request-[a-f0-9-]{36}\\.json"))) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    // Queue files reach ~6 MB for 2,000 tracks, so read and decode them off the main thread.
                    // A single decoding lane keeps rapid successive requests in the order they were sent.
                    return queueCommand(replaces=!args.containsKey("enqueueNext") && !args.containsKey("replacePosition"),
                        onSuperseded={java.io.File(cacheDir,name).delete()},prepare={
                        withContext(queueDecoder) {val file=java.io.File(cacheDir,name)
                            try {PerfTrace.block("queue_io_decode") {if(file.length() in 1..MAX_SNAPSHOT_BYTES) decodePlaybackSnapshot(file.readText()) else null}}
                            catch(_:Exception) {null} finally {file.delete()}}
                    }) {ticket,saved->PerfTrace.block("queue_main") {queue.playCatalogQueue(player,args,saved,ticket) {staleSession(args)}}}
                }
                if (customCommand.customAction == PLAY_LOCAL_AUDIO) {
                    if (checkSelfPermission(audioLibraryPermission()) !=
                        PackageManager.PERMISSION_GRANTED) return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    val id = args.getLong("id", -1L)
                    val uri = localAudioUri(args.getString("uri"), id) ?: return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_BAD_VALUE))
                    val item = MediaItem.Builder().setMediaId("local:$id").setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(args.getString("title").orEmpty().take(180))
                            .setArtist(args.getString("artist").orEmpty().take(180))
                            .setAlbumTitle(args.getString("album").orEmpty().take(180))
                            .setExtras(Bundle().apply {putString(ENTRY_ID,queue.newEntryId())})
                            .build()).build()
                    // Opening the file asks the media provider, which is another process: never on the main thread.
                    return queueCommand(replaces=true,prepare={withContext(Dispatchers.IO) {
                        try {if(contentResolver.openFileDescriptor(uri,"r")?.use {true}==true) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE}
                        catch(_:SecurityException) {SessionError.ERROR_PERMISSION_DENIED} catch(_:java.io.IOException) {SessionError.ERROR_BAD_VALUE}
                    }}) {ticket,readable->if(readable!=SessionResult.RESULT_SUCCESS) SessionResult(readable) else queue.playLocal(player,item,ticket)}
                }
                if (customCommand.customAction != PLAY_PROVIDER_STREAM) return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val request=providerStreamRequest(args)?:return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                return queueCommand(replaces=!args.getBoolean("replaceCurrent") && !args.getBoolean("enqueue"),prepare={}) {ticket,_->
                    queue.playProviderStream(player,args,request,ticket) {staleSession(args)}
                }
            }
        }).setSessionActivity(sessionActivity).build()
        restoreSleepTimerAfterRestart()
        // The newest of this owner's queue and the on-device queue, read off the main thread once older
        // state has been moved. A queue from before owners existed is never loaded here.
        serviceScope.launch {
            val stored=persistence.loadNewest()?:return@launch
            if(persistence.closed || player.mediaItemCount>0) return@launch
            // Play pressed while the queue was still loading, for example from a widget on a cold start.
            val wanted=player.playWhenReady
            internally {queue.restore(player,stored)}
            if(wanted) {player.prepare();player.play()}
        }
        publishState()
        sessionRegistration=sessions.register(object:SessionParticipant {
            override suspend fun quiesce(outgoing:SessionToken)=quiesceForAccountSwitch()
            override suspend fun bind(current:SessionToken)=restoreForCurrentOwner()
        })
        OfflineDownloadScheduler.schedule(this)
        saveJob=serviceScope.launch {while(isActive){delay(5000);persistence.save(player)
            val now=android.os.SystemClock.elapsedRealtime();val delta=(now-lastListenTick).coerceIn(0,5000);lastListenTick=now
            recordHistory(player)
            if(player.isPlaying) encore.heard(delta)
            if(player.isPlaying && sleepSongs.onLastSong) rearmSleepSong()
            if(player.isPlaying && player.currentMediaItem?.mediaId?.startsWith("yt:")==true) {
                val track=queue.trackFromItem(player.currentMediaItem!!);val id=track.videoId
                val duration=player.duration.coerceAtLeast(0);val newPlay=id!=listeningId;listeningId=id
                withContext(Dispatchers.IO) {offline.listened(track,duration,delta,newPlay)}
            }
        }}
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val result=super.onStartCommand(intent,flags,startId)
        if(intent?.action==WIDGET_CONTROL && intent.getStringExtra("widgetToken")==RiffWidgets.token(this)) {
            val player=mainPlayer?:return result
            if(player.mediaItemCount==0) return result
            when(intent.getStringExtra("command")) {
                "toggle"->if(toggleShouldPause(player.isPlaying,player.playWhenReady,player.playbackState==Player.STATE_IDLE,player.playbackState==Player.STATE_ENDED)) player.pause() else retryPlayback(player)
                "previous"->{player.previousOrRestart();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
                "next"->{player.seekToNextMediaItem();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
                "like"->pressMediaButton(player,TOGGLE_LIKE)
                "shuffle"->pressMediaButton(player,TOGGLE_SHUFFLE)
                // Large's Up next rows: the song one or two on, in the order it will play.
                "queue"->player.indexAhead(intent.getIntExtra("ahead",1).coerceIn(1,2))?.let {player.seekTo(it,0);if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
            }
        }
        return result
    }

    /** A play counts once it has been heard for one tick (about 5 s); repeats of the same item count again. */
    private fun recordHistory(player: Player) {
        val item=player.currentMediaItem?:return
        val m=item.mediaMetadata
        if(!plays.qualifies(item.mediaId,player.isPlaying,m.extras?.getBoolean("live")==true)) return
        if(!historyEnabled(this)) return
        // On-device audio belongs to the device; everything else to the owner active now. An unverified account records nothing.
        val scope=historyScopeKey(item.mediaId,offline.currentScope)?:return
        val art=m.artworkUri?.toString()?.takeUnless {item.mediaId.startsWith("yt:") && !it.startsWith("https://")}
        val source=when {item.mediaId.startsWith("local:") -> EncoreSource.DEVICE
            item.localConfiguration?.uri?.scheme in setOf("file","content") -> EncoreSource.DOWNLOAD; else -> EncoreSource.STREAM}
        val entry=HistoryEntry(item.mediaId,m.title?.toString().orEmpty(),m.artist?.toString().orEmpty(),art,runtime.clocks.wallTimeMillis(),m.extras?.getBoolean("music",true)!=false,
            java.util.UUID.randomUUID().toString(),player.duration.takeIf {it>0},album=m.albumTitle?.toString().orEmpty(),
            albumId=m.extras?.getString("albumId"),artistId=m.extras?.getString("artistId"),source=source)
        encore.started(scope,entry.eventId)
        // The ticket is taken now, so clearing history before the write lands rejects it.
        val ticket=history.ticket(scope)
        serviceScope.launch(Dispatchers.IO) {
            val outcome=history.record(entry,ticket)
            if(outcome is StoreOutcome.Failed) events.append("history_write_failed kind=${outcome.kind}")
        }
    }
    /** Account switch: save the outgoing owner's provider or mixed queue paused, then remove it. */
    private suspend fun quiesceForAccountSwitch() {
        var stops=false
        val save=withContext(Dispatchers.Main.immediate) {
            val player=mainPlayer?:return@withContext null
            // A pure on-device queue belongs to the device and keeps playing.
            if(!queueStopsWithAccount((0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId})) return@withContext null
            stops=true;audio.cancelBlend();internally {player.pause()}
            persistence.capture(player)
        }
        if(!stops) return
        if(save!=null) {
            // Committed before the queue is cleared. Stale means the owner's data was deleted meanwhile: nothing to keep.
            val outcome=persistence.commit(save)
            check(outcome==StoreOutcome.Done || outcome==StoreOutcome.Stale) {string(R.string.could_not_save_the_queue)}
        }
        withContext(Dispatchers.Main.immediate) {
            val player=mainPlayer?:return@withContext
            persistence.release()
            queue.resetForNewQueue();player.stop();player.clearMediaItems()
            events.append("account_switch_queue_saved")
        }
    }
    private suspend fun restoreForCurrentOwner() {
        val key=withContext(Dispatchers.Main.immediate) {
            // On-device audio that kept playing is not replaced; the account's queue stays saved for later.
            val player=mainPlayer
            if(player==null || player.mediaItemCount>0) null else offline.currentScope.takeIf {it!=UNVERIFIED_SCOPE_KEY}
        }?:return
        val stored=persistence.load(key)?:return
        withContext(Dispatchers.Main.immediate) {
            val player=mainPlayer?:return@withContext
            if(player.mediaItemCount==0 && offline.currentScope==key) {internally {queue.restore(player,stored)};events.append("account_switch_queue_restored")}
        }
    }
    /** A request built for an earlier owner or generation must not change the current owner's queue. */
    private fun staleSession(args:Bundle):Boolean {
        val nonce=args.getString("sessionNonce")?:return false
        return !sessions.accepts(provider.session.value.copy(generation=args.getLong("sessionGeneration"),nonce=nonce))
    }
    private var networkCallback:android.net.ConnectivityManager.NetworkCallback?=null
    /** Play was asked for while the selected entry is in a failed state. */
    private fun retryPlayback(player:Player) {
        if(player.playbackState==Player.STATE_ENDED) player.seekToDefaultPosition()
        player.play()
        if(!recovery.retry() && player.playbackState==Player.STATE_IDLE) player.prepare()
    }
    private val recovery:RecoveryCoordinator by lazy {RecoveryCoordinator(serviceScope,runtime.clocks::monotonicMillis,runtime.wait,
        {kotlin.random.Random.nextLong(RecoveryPolicy.MAX_JITTER_MS+1)},object:RecoveryHost {
        override fun wantsPlayback()=mainPlayer?.playWhenReady==true
        override fun online()=runtime.networkConditions().validated
        override fun publish(signal:RecoverySignal) {recoverySignal=signal;publishState()}
        override fun event(text:String) {events.append(text)}
        override fun skipUnavailable(entry:String)=queue.skipUnavailable(entry)
        override suspend fun attempt(entry:String,step:RecoveryStep.Attempt)=queue.reload(entry,step) {item,error->faults.of(item,"",error)}
    })}
    override fun onTaskRemoved(rootIntent: Intent?) {
        provider.discardPageMemory()
        taskRemoved=true
        encore.ended(EncoreEnded.STOPPED)
        events.append("task_removed_stop")
        persistence.flushAndClose(mainPlayer)
        queue.reset();mainPlayer?.stop();mainPlayer?.clearMediaItems()
        pauseAllPlayersAndStopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        encore.ended(EncoreEnded.STOPPED)
        recovery.clear()
        networkCallback?.let {callback-> runCatching {getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback)}}
        networkCallback=null
        sleepTimer.close()
        audio.release()
        val player=mainPlayer
        val playWhenReady=player?.playWhenReady==true
        events.append(runCatching {stopContext(this,playWhenReady,player?.playbackState?:0,taskRemoved)}.getOrDefault("service_destroy"))
        if(isUnexpectedStop(playWhenReady,taskRemoved)) events.append("unexpected_stop")
        sessionRegistration?.close();sessionRegistration=null
        persistence.flushAndClose(mainPlayer);saveJob?.cancel()
        session?.let { media ->
            media.player.release()
            media.release()
        }
        session = null; sessionPlayer = null; mainPlayer = null
        offline.playbackBusy=false
        queue.resetForNewQueue(keepQueuedIds=true);serviceScope.cancel()
        sabrHttp.close()
        super.onDestroy()
    }
}
