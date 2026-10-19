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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val audio by lazy {AudioTransitionCoordinator(this,serviceScope,runtime,events) {session?.player}}
    private val events by lazy { PlaybackEventLog(this) }
    private val runtime by lazy {AppContainer.get(this).runtime}
    private val serviceScope by lazy {CoroutineScope(SupervisorJob()+runtime.dispatchers.main)}
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queueDecoder by lazy {runtime.dispatchers.io.limitedParallelism(1)}
    private val provider by lazy { YouTubeMusicProvider.shared(this) }
    private val sleepTimer by lazy {SleepTimerController(serviceScope,runtime.clocks::monotonicMillis,wait=runtime.wait,pause={session?.player?.pause()},publish={deadline->sleepDeadlineMs=deadline;publishState()},readVolume={session?.player?.volume?:1f},writeVolume={session?.player?.volume=it},persist=::persistSleepDeadline)}
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
    private inline fun <T> internally(block:()->T):T=internalChange.run(block)
    private val queue:QueueCoordinator by lazy {QueueCoordinator(this,serviceScope,provider,sessions,offline,events,order,audio,{persistence},internalChange,{session?.player}) {recovery.clear()}}
    private val faults:PlaybackFaults by lazy {PlaybackFaults(serviceScope,runtime,provider,offline,events,queue)}
    private val sources:PlaybackSources by lazy {PlaybackSources(this,serviceScope,provider,offline,events,audio,queue,sabrHttp,::publishState)}
    private var sleepDeadlineMs=0L
    private var publishedState=""
    /** Queue revision, owner and what is being done for the selected entry, for every controller. Main thread. */
    private fun publishState() {
        val media=session?:return;val player=media.player
        val entry=player.currentMediaItem?.entryId().orEmpty().takeIf(sources::isResolving).orEmpty()
        val signal=recoverySignal
        val owner=persistence.owner
        val key="$sleepDeadlineMs|${order.queueRevision}|$owner|$entry|$signal"
        if(key==publishedState) return
        publishedState=key
        media.setSessionExtras(Bundle().apply {putLong("sleepDeadlineMs",sleepDeadlineMs);putLong("queueRevision",order.queueRevision)
            putString("queueOwner",owner);putString("resolving",entry);putString("recovery",signal.recovery?.wire.orEmpty())
            putString("failure",signal.failure?.name.orEmpty());putBoolean("recoveryHeld",signal.held)})
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
        persistence.start()
        val mediaFactory = sources
        audio.start()
        val renderers = audio.renderers()
        audio.createCrossfade(mediaFactory, sleepActive = { (session?.sessionExtras?.getLong("sleepDeadlineMs") ?: 0L) > 0L },
            tailItem=queue::tailItem)
        // Crossfade synchronization requires frequent decoder-position updates.
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaFactory).setLoadControl(riffLoadControl())
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
            audio.attach(this)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) = audio.audioSessionChanged(audioSessionId)
                override fun onEvents(player: Player,events: Player.Events) {audio.update();persistence.save(player);publishState();RiffWidgets.publish(this@MusicPlaybackService,player)}
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
                    if(playbackState==Player.STATE_ENDED) plays.completed()
                }
                override fun onIsPlayingChanged(isPlaying:Boolean) {offline.playbackBusy=isPlaying || this@apply.playbackState==Player.STATE_BUFFERING;lastListenTick=android.os.SystemClock.elapsedRealtime()}
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if(reason!=Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || mediaItem!==queue.replacement) {
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
        session = MediaSession.Builder(this, player).setSessionActivity(sessionActivity)
            .setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                // Another app: transport and reading, not rewriting the queue, and none of the app's own commands.
                if (!isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid()))
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(foreignPlayerCommands()).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon().add(SessionCommand(PLAY_PROVIDER_STREAM, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_LOCAL_AUDIO, Bundle.EMPTY)).add(SessionCommand(RETRY_PLAYBACK, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY)).add(SessionCommand(RESTORE_SAVED_QUEUE,Bundle.EMPTY)).add(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY)).build())
                    .build()
            }

            override fun onCustomCommand(session: MediaSession,
                controller: MediaSession.ControllerInfo, customCommand: SessionCommand,
                args: Bundle): ListenableFuture<SessionResult> {
                if (!isOwnController(controller.packageName, controller.uid, packageName, android.os.Process.myUid())) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                if(customCommand.customAction==SET_SLEEP_TIMER) {
                    val minutes=args.getInt("minutes")
                    if(minutes !in 0..1440) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    if(minutes>0) audio.cancelBlend()
                    sleepTimer.set(minutes)
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
        }).build()
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
            val player=session?.player?:return result
            if(player.mediaItemCount==0) return result
            when(intent.getStringExtra("command")) {
                "toggle"->if(toggleShouldPause(player.isPlaying,player.playWhenReady,player.playbackState==Player.STATE_IDLE)) player.pause() else retryPlayback(player)
                "previous"->{player.seekToPreviousMediaItem();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
                "next"->{player.seekToNextMediaItem();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
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
        val entry=HistoryEntry(item.mediaId,m.title?.toString().orEmpty(),m.artist?.toString().orEmpty(),art,runtime.clocks.wallTimeMillis(),m.extras?.getBoolean("music",true)!=false,
            java.util.UUID.randomUUID().toString(),player.duration.takeIf {it>0})
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
            val player=session?.player?:return@withContext null
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
            val player=session?.player?:return@withContext
            persistence.release()
            queue.resetForNewQueue();player.stop();player.clearMediaItems()
            events.append("account_switch_queue_saved")
        }
    }
    private suspend fun restoreForCurrentOwner() {
        val key=withContext(Dispatchers.Main.immediate) {
            // On-device audio that kept playing is not replaced; the account's queue stays saved for later.
            val player=session?.player
            if(player==null || player.mediaItemCount>0) null else offline.currentScope.takeIf {it!=UNVERIFIED_SCOPE_KEY}
        }?:return
        val stored=persistence.load(key)?:return
        withContext(Dispatchers.Main.immediate) {
            val player=session?.player?:return@withContext
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
        player.play()
        if(!recovery.retry() && player.playbackState==Player.STATE_IDLE) player.prepare()
    }
    private val recovery:RecoveryCoordinator by lazy {RecoveryCoordinator(serviceScope,runtime.clocks::monotonicMillis,runtime.wait,
        {kotlin.random.Random.nextLong(RecoveryPolicy.MAX_JITTER_MS+1)},object:RecoveryHost {
        override fun wantsPlayback()=session?.player?.playWhenReady==true
        override fun online()=runtime.networkConditions().validated
        override fun publish(signal:RecoverySignal) {recoverySignal=signal;publishState()}
        override fun event(text:String) {events.append(text)}
        override fun skipUnavailable(entry:String)=queue.skipUnavailable(entry)
        override suspend fun attempt(entry:String,step:RecoveryStep.Attempt)=queue.reload(entry,step) {item,error->faults.of(item,"",error)}
    })}
    override fun onTaskRemoved(rootIntent: Intent?) {
        provider.discardPageMemory()
        taskRemoved=true
        events.append("task_removed_stop")
        persistence.flushAndClose(session?.player)
        queue.reset();session?.player?.stop();session?.player?.clearMediaItems()
        pauseAllPlayersAndStopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        recovery.clear()
        networkCallback?.let {callback-> runCatching {getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback)}}
        networkCallback=null
        sleepTimer.close()
        audio.release()
        val player=session?.player
        val playWhenReady=player?.playWhenReady==true
        events.append(runCatching {stopContext(this,playWhenReady,player?.playbackState?:0,taskRemoved)}.getOrDefault("service_destroy"))
        if(isUnexpectedStop(playWhenReady,taskRemoved)) events.append("unexpected_stop")
        sessionRegistration?.close();sessionRegistration=null
        persistence.flushAndClose(session?.player);saveJob?.cancel()
        session?.let { media ->
            media.player.release()
            media.release()
        }
        session = null
        offline.playbackBusy=false
        queue.resetForNewQueue(keepQueuedIds=true);serviceScope.cancel()
        sabrHttp.close()
        super.onDestroy()
    }
}
