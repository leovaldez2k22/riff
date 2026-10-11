package com.musicglass.personal

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertubex.sabr.SabrBootstrap
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.metrolist.innertubex.sabr.requireAllowedSabrUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** Background playback for real provider streams and on-device audio. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class])
class MusicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val volumeBoost = VolumeBoostProcessor()
    private val audioEffects by lazy {AudioSessionEffects(this, volumeBoost.boost) {events.append(it)}}
    private val boostPrefs by lazy {getSharedPreferences(AUDIO_BOOST_PREFS, MODE_PRIVATE)}
    private val boostListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener {_, _ -> updateBoost()}
    private var crossfader: Crossfader? = null
    private var tailAudioEffects:AudioSessionEffects?=null
    private fun currentBoostGain() = boostGain(boostPrefs.getBoolean("enabled", false), boostPrefs.getInt("percent", 200))
    private fun updateBoost() {
        audioEffects.setGain(currentBoostGain())
        tailAudioEffects?.setGain(currentBoostGain())
    }
    private val events by lazy { PlaybackEventLog(this) }
    private val runtime by lazy {AppContainer.get(this).runtime}
    private val serviceScope by lazy {CoroutineScope(SupervisorJob()+runtime.dispatchers.main)}
    @OptIn(ExperimentalCoroutinesApi::class)
    private val queueDecoder by lazy {runtime.dispatchers.io.limitedParallelism(1)}
    private val provider by lazy { YouTubeMusicProvider.shared(this) }
    private val sleepTimer by lazy {SleepTimerController(serviceScope,runtime.clocks::monotonicMillis,wait=runtime.wait,pause={session?.player?.pause()},publish={deadline->session?.setSessionExtras(Bundle().apply {putLong("sleepDeadlineMs",deadline)})},readVolume={session?.player?.volume?:1f},writeVolume={session?.player?.volume=it},persist=::persistSleepDeadline)}
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
    private var liveFallbackJob: Job? = null
    private val failedLiveProfiles = linkedSetOf<String>()
    private var streamRecovery = StreamRecoveryBudget()
    private var streamRecoveryJob: Job? = null
    private var recoveryReplacement: MediaItem? = null
    private val resolvedStreams = ConcurrentHashMap<MediaItem,ProviderStream>()
    private var queueJob: Job? = null
    private var queueGeneration = 0
    private var radioSeed: String? = null
    private var radioContinuation: String? = null
    private val queueCandidates = ArrayDeque<ProviderTrack>()
    private val queuedIds = linkedSetOf<String>()
    private val streamHeaders = ConcurrentHashMap<String,Map<String,String>>()
    private val sabrHttp = HttpClient(OkHttp)
    private val sabrSelections = ConcurrentHashMap<String,SabrBootstrap>()
    private val offline by lazy {OfflineMusicStore.get(this)}
    private var listeningId=""
    private var historyItem:String?=null
    private var lastListenTick=android.os.SystemClock.elapsedRealtime()
    private val snapshotPrefs by lazy {getSharedPreferences("last_playback",MODE_PRIVATE)}
    private var discardingSession=false
    private var taskRemoved=false
    private var saveJob: Job?=null
    private val snapshots by lazy {ScopedPlaybackSnapshots(PreferenceSnapshotValues(this),runtime.clocks::wallTimeMillis)}
    private val sessions by lazy {AppContainer.get(this).sessions}
    private var sessionRegistration:AutoCloseable?=null
    /** Owner of the queue now in the player; null when it is empty or may not be saved. */
    private var snapshotOwner:String?=null
    private val snapshotWrites=kotlinx.coroutines.channels.Channel<Triple<Long,String,PlaybackSnapshot>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val snapshotSequence=java.util.concurrent.atomic.AtomicLong()
    private val snapshotWriteLock=Any()
    private var cachedQueueTimeline:androidx.media3.common.Timeline?=null
    private var cachedSnapshotQueue:List<SavedQueueItem> = emptyList()
    private var cachedSnapshotIds:List<String> = emptyList()
    override fun onCreate() {
        super.onCreate()
        events.append("service_create")
        serviceScope.launch(Dispatchers.IO) {logNewProcessExits(this@MusicPlaybackService,events)}
        serviceScope.launch(Dispatchers.IO) {runCatching {offline.indexArtwork(offline.currentScope)}}
        serviceScope.launch(Dispatchers.IO) {
            for((sequence,owner,snapshot) in snapshotWrites) persistSnapshot(sequence,owner,snapshot)
        }
        val defaultFactory = DefaultMediaSourceFactory(this)
        val mediaFactory = object : MediaSource.Factory by defaultFactory {
            override fun createMediaSource(item: MediaItem): MediaSource {
                if(item.localConfiguration?.uri?.scheme=="catalog") {
                    return DeferredProviderSource(item) {
                        val track=trackFromItem(item)
                        val local=offline.audio(offline.currentScope,track.videoId)
                        if(local!=null && !track.isLive) {
                            val art=offline.artwork(offline.currentScope,track.videoId)?.let(Uri::fromFile)
                            val resolved=item.buildUpon().setUri(Uri.fromFile(local)).setCustomCacheKey(null).setMediaMetadata(item.mediaMetadata.buildUpon().apply {if(art!=null) setArtworkUri(art)}.build()).build()
                            return@DeferredProviderSource createMediaSource(resolved)
                        }
                        val start=android.os.SystemClock.elapsedRealtime()
                        val stream=withTimeout(if(track.isLive) 60_000 else 30_000) {provider.resolve(track.videoId,track.isLive,video=track.isLive).getOrThrow()}
                        events.append("selected_stream_ready ms=${android.os.SystemClock.elapsedRealtime()-start}")
                        resolvedStreams[item]=stream
                        createMediaSource(providerItem(track,stream))
                    }
                }
                item.mediaMetadata.extras?.getString("videoUri")?.let {videoUri->
                    val extra=Bundle(item.mediaMetadata.extras).apply {remove("videoUri")}
                    val audio=item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extra).build()).build()
                    val video=MediaItem.Builder().setMediaId("${item.mediaId}:video").setUri(videoUri).build()
                    return androidx.media3.exoplayer.source.MergingMediaSource(true,createMediaSource(audio),createMediaSource(video))
                }
                if(item.localConfiguration?.uri?.scheme=="file") return offlineMediaSource(this@MusicPlaybackService,item)
                val headers = streamHeaders[item.localConfiguration?.uri.toString()].orEmpty()
                val source = DataSource.Factory {
                    ProviderMediaDataSource(DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers).createDataSource(),
                        sabrHttp,findBootstrap={key->sabrSelections[key]},onSabrResponse={response->
                            if(response.requestNumber==0 || response.failureCategory!=null)
                                events.append("sabr_response http=${response.httpStatus?:0} failure=${response.failureCategory?:"none"} init=${response.initializationReceived} media=${response.selectedMediaBytes}")
                        })
                }
                val default=DefaultDataSource.Factory(this@MusicPlaybackService,source)
                val dataSource=if(item.localConfiguration?.uri?.scheme in setOf("https","sabr") && item.mediaMetadata.extras?.getBoolean("live")!=true && item.mediaMetadata.extras?.getBoolean("video")!=true)
                    MusicDiskCache.audioFactory(this@MusicPlaybackService,default)
                    else default
                return DefaultMediaSourceFactory(dataSource).createMediaSource(item)
            }
        }
        updateBoost()
        boostPrefs.registerOnSharedPreferenceChangeListener(boostListener)
        val renderers = riffAudioRenderers(this, volumeBoost)
        val audioAttributes = AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build()
        val pcmOnly = androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()
        crossfader = Crossfader(this, serviceScope, createTail = {
            // The outgoing song's copy: same sources and loudness, no audio focus or session of its own.
            // Match the main player's frequent position updates for muted synchronization.
            val processor = VolumeBoostProcessor()
            val tail = ExoPlayer.Builder(this, riffAudioRenderers(this, processor)).setMediaSourceFactory(mediaFactory)
                .experimentalSetDynamicSchedulingEnabled(false).build()
            tail.setAudioAttributes(audioAttributes, false)
            tail.trackSelectionParameters = tail.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(pcmOnly).build()
            val effects = AudioSessionEffects(this, processor.boost)
            tailAudioEffects=effects
            effects.setGain(currentBoostGain()); effects.attach(tail.audioSessionId)
            tail.addListener(object:Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId:Int)=effects.attach(audioSessionId)
            })
            tail to { if(tailAudioEffects===effects) tailAudioEffects=null; effects.release() }
        }, sleepActive = { (session?.sessionExtras?.getLong("sleepDeadlineMs") ?: 0L) > 0L },
            tailItem={item->resolvedStreams[item]?.let {providerItem(trackFromItem(item),it)}?:item},
            onBlend={SongCrossfadeState.active=it},clock=runtime.clocks::monotonicMillis,wait=runtime.wait) { events.append(it) }
        // Crossfade synchronization requires frequent decoder-position updates.
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaFactory)
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
            audioEffects.attach(audioSessionId)
            crossfader?.start(this)
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) = audioEffects.attach(audioSessionId)
                override fun onEvents(player: Player,events: Player.Events) {saveSnapshot(player);RiffWidgets.publish(this@MusicPlaybackService,player)}
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    events.append("play_when_ready=$playWhenReady reason=$reason")
                    if(playWhenReady) replenishQueue(this@apply)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    events.append("playback_state=$playbackState")
                    offline.playbackBusy=playbackState==Player.STATE_BUFFERING || this@apply.isPlaying
                    if(playbackState==Player.STATE_READY) {replenishQueue(this@apply);endNetworkRecovery()}
                }
                override fun onIsPlayingChanged(isPlaying:Boolean) {offline.playbackBusy=isPlaying || this@apply.playbackState==Player.STATE_BUFFERING;lastListenTick=android.os.SystemClock.elapsedRealtime()}
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if(reason!=Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED || mediaItem!==recoveryReplacement) {
                        listeningId="";historyItem=null;lastListenTick=android.os.SystemClock.elapsedRealtime()
                        streamRecoveryJob?.cancel();streamRecoveryJob=null;recoveryReplacement=null
                        streamRecovery=StreamRecoveryBudget()
                        providerRetrying(this@apply,false)
                    }
                    if(mediaItem == null) cancelRadio() else replenishQueue(this@apply)
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val causes = generateSequence(error as Throwable?) { it.cause }
                        .take(5).joinToString(">") { it.javaClass.simpleName }
                    events.append("player_error=${error.errorCodeName} causes=$causes")
                    this@apply.currentMediaItem?.mediaId?.takeIf {it.startsWith("yt:")}?.removePrefix("yt:")?.let(provider::invalidateStream)
                    generateSequence(error as Throwable?) {it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {failure ->
                        val uri=failure.dataSpec.uri
                        events.append("http_rejected code=${failure.responseCode} host=${uri.host} kind=${if(uri.path?.contains(".m3u8")==true) "manifest" else "segment"}")
                    }
                    if(error.errorCode==androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
                        retryLiveProfile(this@apply)
                    recoverProviderStream(this@apply,error)
                    if(NetworkRecovery.isNetworkFailure(error.errorCodeName,generateSequence(error as Throwable?) {it.cause}.take(8).toList()))
                        startNetworkRecovery(this@apply)
                    else networkFailure=false  // other errors are never retried by this path
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
                if (controller.packageName != packageName) return super.onConnect(session, controller)
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon().add(SessionCommand(PLAY_PROVIDER_STREAM, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_LOCAL_AUDIO, Bundle.EMPTY))
                        .add(SessionCommand(PLAY_CATALOG_QUEUE,Bundle.EMPTY)).add(SessionCommand(SET_SLEEP_TIMER,Bundle.EMPTY)).build())
                    .build()
            }

            override fun onCustomCommand(session: MediaSession,
                controller: MediaSession.ControllerInfo, customCommand: SessionCommand,
                args: Bundle): ListenableFuture<SessionResult> {
                if (controller.packageName != packageName || controller.uid != android.os.Process.myUid()) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                }
                if(customCommand.customAction==SET_SLEEP_TIMER) {
                    val minutes=args.getInt("minutes")
                    if(minutes !in 0..1440) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    if(minutes>0) crossfader?.cancel()
                    sleepTimer.set(minutes)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                // Not an error for the new owner: the request simply no longer applies.
                if(customCommand.customAction!=PLAY_LOCAL_AUDIO && staleSession(args)) return Futures.immediateFuture(SessionResult(SessionError.INFO_CANCELLED))
                if(customCommand.customAction==PLAY_CATALOG_QUEUE) {
                    val name=args.getString("queueFile").orEmpty()
                    if(!name.matches(Regex("queue-request-[a-f0-9-]{36}\\.json"))) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    // Queue files reach ~6 MB for 2,000 tracks, so read and decode them off the main thread.
                    // A single decoding lane keeps rapid successive requests in the order they were sent.
                    fun applyQueue(saved:PlaybackSnapshot?):SessionResult {
                        if(staleSession(args)) return SessionResult(SessionError.INFO_CANCELLED)
                        if(saved==null || saved.queue.any {!it.mediaId.startsWith("yt:")}) return SessionResult(SessionError.ERROR_BAD_VALUE)
                        val tracks=saved.queue.map {q->ProviderTrack(q.mediaId.removePrefix("yt:"),q.title,q.artist,q.artworkUrl,q.artistId,q.albumId,q.album,q.live,q.music)}
                        if(tracks.any {!it.videoId.matches(Regex("[A-Za-z0-9_-]{11}"))}) return SessionResult(SessionError.ERROR_BAD_VALUE)
                        if(args.containsKey("enqueueNext") && tracks.size==1 && player.mediaItemCount>0) {
                            val track=tracks[0];val existing=(0 until player.mediaItemCount).firstOrNull {val m=player.getMediaItemAt(it).mediaMetadata;player.getMediaItemAt(it).mediaId=="yt:${track.videoId}" || queueRecordingKey(m.title.toString(),m.artist.toString())==queueRecordingKey(track.title,track.artist)}
                            if(existing!=null) {if(args.getBoolean("enqueueNext") && existing!=player.currentMediaItemIndex) player.moveMediaItem(existing,(player.currentMediaItemIndex+1-if(existing<player.currentMediaItemIndex) 1 else 0).coerceAtMost(player.mediaItemCount-1))}
                            else {queuedIds.add(track.videoId);player.addMediaItem(if(args.getBoolean("enqueueNext")) player.currentMediaItemIndex+1 else player.mediaItemCount,catalogItem(track))}
                            return SessionResult(SessionResult.RESULT_SUCCESS)
                        }
                        if(args.containsKey("replacePosition") && tracks.size==1 && player.mediaItemCount>0) {
                            val index=player.currentMediaItemIndex;val wants=player.playWhenReady
                            player.replaceMediaItem(index,catalogItem(tracks[0]));player.seekTo(index,args.getLong("replacePosition"));player.prepare();player.playWhenReady=wants
                            return SessionResult(SessionResult.RESULT_SUCCESS)
                        }
                        player.stop();cancelRadio();sabrSelections.clear();streamHeaders.clear();queuedIds.clear()
                        player.shuffleModeEnabled=saved.shuffle;player.repeatMode=Player.REPEAT_MODE_OFF
                        queuedIds.addAll(tracks.map {it.videoId})
                        player.setMediaItems(tracks.map(::catalogItem),saved.index,0)
                        if(args.getBoolean("automatic") && !tracks[0].isLive) radioSeed=tracks[0].videoId
                        player.setPlaylistMetadata(MediaMetadata.Builder().setTitle(saved.collectionTitle).setSubtitle(if(saved.collectionTitle.isBlank()) "Album order" else "Collection order").build())
                        if(args.getBoolean("autoplay",true)) {player.prepare();player.play()} else player.playWhenReady=false
                        saveSnapshot(player);events.append("album_queue_selected count=${tracks.size}")
                        return SessionResult(SessionResult.RESULT_SUCCESS)
                    }
                    val result=com.google.common.util.concurrent.SettableFuture.create<SessionResult>()
                    serviceScope.launch {
                        val saved=withContext(queueDecoder) {val file=java.io.File(cacheDir,name)
                            try {if(file.length() in 1..6_000_000) decodePlaybackSnapshot(file.readText()) else null}
                            catch(_:Exception) {null} finally {file.delete()}}
                        result.set(applyQueue(saved))
                    }.invokeOnCompletion {if(!result.isDone) result.set(SessionResult(SessionError.ERROR_UNKNOWN))}
                    return result
                }
                if (customCommand.customAction == PLAY_LOCAL_AUDIO) {
                    if (checkSelfPermission(audioLibraryPermission()) !=
                        PackageManager.PERMISSION_GRANTED) return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    val uri = Uri.parse(args.getString("uri").orEmpty())
                    val id = args.getLong("id", -1L)
                    if (id < 0 || uri.toString() !=
                        "content://media/external/audio/media/$id") return Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_BAD_VALUE))
                    try {
                        if (contentResolver.openFileDescriptor(uri, "r")?.use { true } != true)
                            return Futures.immediateFuture(
                                SessionResult(SessionError.ERROR_BAD_VALUE))
                    } catch (_: SecurityException) {
                        return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    } catch (_: java.io.IOException) {
                        return Futures.immediateFuture(
                            SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    val item = MediaItem.Builder().setMediaId("local:$id").setUri(uri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(args.getString("title").orEmpty().take(180))
                            .setArtist(args.getString("artist").orEmpty().take(180))
                            .setAlbumTitle(args.getString("album").orEmpty().take(180))
                            .build()).build()
                    cancelRadio();sabrSelections.clear();streamHeaders.clear()
                    player.setMediaItem(item)
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.prepare()
                    player.play()
                    events.append("local_audio_selected")
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                if (customCommand.customAction != PLAY_PROVIDER_STREAM) return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                val url = args.getString("url").orEmpty()
                val sabrKey = args.getString("sabrKey")
                val bootstrap = sabrKey?.let(SabrPlaybackRegistry::take)
                if (sabrKey != null && bootstrap == null) return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_BAD_VALUE))
                val uri = if (bootstrap != null) Uri.parse("sabr://$sabrKey") else Uri.parse(url)
                val host = uri.host.orEmpty()
                if (bootstrap == null && (uri.scheme != "https" ||
                    !(host == "googlevideo.com" || host.endsWith(".googlevideo.com")))) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val videoId = args.getString("videoId").orEmpty()
                if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val streamVideoId=args.getString("streamVideoId")?:videoId
                if(!streamVideoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                if (bootstrap != null && (bootstrap.videoId != streamVideoId ||
                    runCatching { requireAllowedSabrUrl(bootstrap.serverAbrStreamingUrl) }.isFailure)) {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                }
                val track = ProviderTrack(videoId,args.getString("title").orEmpty().take(180),
                    args.getString("artist").orEmpty().take(180),validProviderArtworkUrl(args.getString("artworkUrl")),
                    args.getString("artistId"),args.getString("albumId"),args.getString("album").orEmpty().take(180),args.getBoolean("live"))
                val headers = args.getBundle("headers")?.keySet()?.associateWith { key ->
                    args.getBundle("headers")?.getString(key).orEmpty()
                }.orEmpty().filterKeys { it.lowercase() in setOf("user-agent","accept","accept-language","referer","origin") }
                val videoBootstrap=args.getString("sabrVideoKey")?.let(SabrPlaybackRegistry::take)
                val videoUrl=args.getString("videoUrl")
                if(videoBootstrap!=null && (videoBootstrap.videoId!=streamVideoId || runCatching {requireAllowedSabrUrl(videoBootstrap.serverAbrStreamingUrl)}.isFailure))
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                if(videoUrl!=null && videoBootstrap==null) {val v=Uri.parse(videoUrl)
                    if(v.scheme!="https" || !(v.host=="googlevideo.com" || v.host.orEmpty().endsWith(".googlevideo.com"))) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))}
                val stream = ProviderStream(url,headers,args.getString("mimeType"),bootstrap,args.getString("profileId"),videoUrl,args.getString("videoMimeType"),videoBootstrap,sourceVideoId=streamVideoId,
                    itag=args.getInt("itag"),bitrate=args.getInt("bitrate"),includesVideo=args.getBoolean("includesVideo"))
                if(args.getBoolean("replaceCurrent") && player.mediaItemCount>0) {
                    val index=player.currentMediaItemIndex;val wants=player.playWhenReady
                    player.replaceMediaItem(index,providerItem(track,stream));player.seekTo(index,args.getLong("position").coerceAtLeast(0))
                    player.prepare();player.playWhenReady=wants
                } else if(args.getBoolean("enqueue") && player.mediaItemCount > 0) {
                    val existing=(0 until player.mediaItemCount).firstOrNull {
                        val m=player.getMediaItemAt(it)
                        m.mediaId=="yt:$videoId" || queueRecordingKey(m.mediaMetadata.title.toString(),m.mediaMetadata.artist.toString())==queueRecordingKey(track.title,track.artist)
                    }
                    if(existing!=null) {
                        if(args.getBoolean("playNext") && existing!=player.currentMediaItemIndex) {
                            val to=(player.currentMediaItemIndex+1-if(existing<player.currentMediaItemIndex) 1 else 0).coerceAtMost(player.mediaItemCount-1)
                            player.moveMediaItem(existing,to)
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    val item = providerItem(track,stream)
                    val index = if(args.getBoolean("playNext")) player.currentMediaItemIndex+1 else player.mediaItemCount
                    queuedIds.add(videoId);player.addMediaItem(index,item)
                } else {
                    cancelRadio();sabrSelections.clear();streamHeaders.clear();queuedIds.clear()
                    queuedIds.add(videoId)
                    player.setMediaItem(providerItem(track,stream),args.getLong("position",0L).coerceAtLeast(0))
                    player.repeatMode=Player.REPEAT_MODE_OFF;player.prepare();player.play()
                    events.append("provider_stream_selected")
                    if(!track.isLive) {radioSeed=videoId;replenishQueue(player)}
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        restoreSleepTimerAfterRestart()
        // The newest of this account's queue and the on-device queue. The pre-scope shared snapshot is
        // neither read nor removed here: it is ambiguous, and Q04 quarantines it for explicit recovery.
        snapshots.newest(listOfNotNull(offline.currentScope.takeIf {it!=UNVERIFIED_SCOPE_KEY},DEVICE_LOCAL_SCOPE_KEY))?.let {restoreSnapshot(player,it)}
        sessionRegistration=sessions.register(object:SessionParticipant {
            override suspend fun quiesce(outgoing:SessionToken)=withContext(Dispatchers.Main.immediate) {quiesceForAccountSwitch()}
            override suspend fun bind(current:SessionToken)=withContext(Dispatchers.Main.immediate) {restoreForCurrentOwner()}
        })
        OfflineDownloadScheduler.schedule(this)
        saveJob=serviceScope.launch {while(isActive){delay(5000);saveSnapshot(player)
            val now=android.os.SystemClock.elapsedRealtime();val delta=(now-lastListenTick).coerceIn(0,5000);lastListenTick=now
            recordHistory(player)
            if(player.isPlaying && player.currentMediaItem?.mediaId?.startsWith("yt:")==true) {
                val track=trackFromItem(player.currentMediaItem!!);val id=track.videoId
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
                "toggle"->if(toggleShouldPause(player.isPlaying,player.playWhenReady,player.playbackState==Player.STATE_IDLE)) player.pause() else {if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
                "previous"->{player.seekToPreviousMediaItem();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
                "next"->{player.seekToNextMediaItem();if(player.playbackState==Player.STATE_IDLE) player.prepare();player.play()}
            }
        }
        return result
    }

    /** A play counts once it has been heard for one tick (about 5 s); repeats of the same item count again. */
    private fun recordHistory(player: Player) {
        val item=player.currentMediaItem?:return
        if(!player.isPlaying || item.mediaId==historyItem) return
        val m=item.mediaMetadata
        if(m.extras?.getBoolean("live")==true || !(item.mediaId.startsWith("yt:") || item.mediaId.startsWith("local:"))) return
        historyItem=item.mediaId
        val history=ListeningHistory.get(this)
        if(!ListeningHistory.enabled(this)) return
        val art=m.artworkUri?.toString()?.takeUnless {item.mediaId.startsWith("yt:") && !it.startsWith("https://")}
        val entry=HistoryEntry(item.mediaId,m.title?.toString().orEmpty(),m.artist?.toString().orEmpty(),art,System.currentTimeMillis(),m.extras?.getBoolean("music",true)!=false)
        serviceScope.launch(Dispatchers.IO) {history.record(entry)}
    }
    private fun trackFromItem(item: MediaItem):ProviderTrack {
        val m=item.mediaMetadata;val e=m.extras
        return ProviderTrack(item.mediaId.removePrefix("yt:"),m.title?.toString().orEmpty(),m.artist?.toString().orEmpty(),
            m.artworkUri?.toString(),e?.getString("artistId"),e?.getString("albumId"),m.albumTitle?.toString().orEmpty(),e?.getBoolean("live")==true,e?.getBoolean("music",true)!=false)
    }
    private fun catalogItem(track: ProviderTrack):MediaItem = MediaItem.Builder().setMediaId("yt:${track.videoId}")
        .setUri("catalog://${track.videoId}").setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setAlbumTitle(track.album).setArtworkUri(offline.indexedArtwork(offline.currentScope,track.videoId)?.let(Uri::fromFile)?:validProviderArtworkUrl(track.artworkUrl)?.let(Uri::parse))
            .setExtras(Bundle().apply {putString("artistId",track.artistId);putString("albumId",track.albumId);putBoolean("live",track.isLive);putBoolean("video",track.isLive);putBoolean("music",track.isMusic)}).build()).build()
    /** Loads an owner's saved queue paused. Never starts playback. */
    private fun restoreSnapshot(player:Player,stored:StoredSnapshot) {
        val saved=stored.snapshot
        val items=saved.queue.map {q->if(q.mediaId.startsWith("yt:")) catalogItem(ProviderTrack(q.mediaId.removePrefix("yt:"),
            q.title,q.artist,q.artworkUrl,q.artistId,q.albumId,q.album,q.live,q.music)) else MediaItem.Builder().setMediaId(q.mediaId).setUri(q.localUri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(q.title).setArtist(q.artist).setAlbumTitle(q.album).build()).build()}
        player.setMediaItems(items,saved.index,saved.positionMs)
        player.shuffleModeEnabled=saved.shuffle;player.repeatMode=saved.repeat;player.playWhenReady=false
        radioSeed=saved.radioSeed;queuedIds.addAll(saved.queue.filter {it.mediaId.startsWith("yt:")}.map {it.mediaId.removePrefix("yt:")})
        player.setPlaylistMetadata(MediaMetadata.Builder().setSubtitle(if(radioSeed==null) "Album order" else "Restored queue \u00B7 paused").build())
        events.append("session_restored count=${items.size} index=${saved.index}")
        snapshotOwner=stored.ownerKey
    }
    /** Account switch: save the outgoing owner's provider or mixed queue paused, then remove it. */
    private fun quiesceForAccountSwitch() {
        val player=session?.player?:return
        // A pure on-device queue belongs to the device and keeps playing.
        if(!queueStopsWithAccount((0 until player.mediaItemCount).map {player.getMediaItemAt(it).mediaId})) return
        crossfader?.cancel();player.pause()
        check(saveSnapshot(player,flush=true,durable=true)) {"Could not save the queue"}
        snapshotOwner=null
        cancelRadio();player.stop();player.clearMediaItems()
        sabrSelections.clear();streamHeaders.clear();queuedIds.clear()
        events.append("account_switch_queue_saved")
    }
    private fun restoreForCurrentOwner() {
        val player=session?.player?:return
        // On-device audio that kept playing is not replaced; the account's queue stays saved for later.
        if(player.mediaItemCount>0) return
        val key=offline.currentScope.takeIf {it!=UNVERIFIED_SCOPE_KEY}?:return
        snapshots.load(key)?.let {restoreSnapshot(player,it);events.append("account_switch_queue_restored")}
    }
    /** A request built for an earlier owner or generation must not change the current owner's queue. */
    private fun staleSession(args:Bundle):Boolean {
        val nonce=args.getString("sessionNonce")?:return false
        return !sessions.accepts(provider.session.value.copy(generation=args.getLong("sessionGeneration"),nonce=nonce))
    }
    private fun saveSnapshot(player:Player,flush:Boolean=false,durable:Boolean=false):Boolean {
        if(discardingSession) return true
        if(player.mediaItemCount==0) {
            // An emptied queue clears only its own owner; another owner's saved queue is untouched.
            snapshotSequence.incrementAndGet()
            snapshotOwner?.let {owner->synchronized(snapshotWriteLock) {snapshots.clear(owner)}};snapshotOwner=null
            return true
        }
        if(cachedQueueTimeline !== player.currentTimeline) {
            cachedQueueTimeline=player.currentTimeline
            cachedSnapshotQueue=(0 until player.mediaItemCount).map {i->val item=player.getMediaItemAt(i);val m=item.mediaMetadata;val e=m.extras
                SavedQueueItem(item.mediaId,m.title?.toString().orEmpty(),m.artist?.toString().orEmpty(),m.artworkUri?.toString(),
                    m.albumTitle?.toString().orEmpty(),e?.getString("artistId"),e?.getString("albumId"),e?.getBoolean("live")==true,
                    item.localConfiguration?.uri?.toString()?.takeIf {item.mediaId.startsWith("local:")},e?.getBoolean("music",true)!=false)}
            cachedSnapshotIds=cachedSnapshotQueue.map {it.mediaId}
        }
        val owner=queueOwnerKey(cachedSnapshotIds,offline.currentScope)
        if(owner!=snapshotOwner) {
            // The user replaced one kind of queue with another; the earlier one is no longer resumable.
            snapshotOwner?.let {previous->synchronized(snapshotWriteLock) {snapshots.clear(previous)}};snapshotOwner=owner
        }
        if(owner==null) return true
        val snapshot=PlaybackSnapshot(cachedSnapshotQueue,player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0),player.shuffleModeEnabled,player.repeatMode,radioSeed,
            player.playlistMetadata.title?.toString().orEmpty())
        // The lifecycle handoff must commit before the service is released; routine ticks use IO.
        val sequence=snapshotSequence.incrementAndGet()
        if(flush) return persistSnapshot(sequence,owner,snapshot,durable)
        snapshotWrites.trySend(Triple(sequence,owner,snapshot));return true
    }
    private fun persistSnapshot(sequence:Long,owner:String,snapshot:PlaybackSnapshot,durable:Boolean=false):Boolean =
        synchronized(snapshotWriteLock) {if(sequence==snapshotSequence.get()) snapshots.save(owner,snapshot,durable) else true}
    private fun providerItem(listedTrack: ProviderTrack, stream: ProviderStream): MediaItem {
        val track = resolvedPlaybackTrack(listedTrack, stream)
        val uri = stream.sabrBootstrap?.let { bootstrap ->
            val key=UUID.randomUUID().toString();sabrSelections[key]=bootstrap;Uri.parse("sabr://$key")
        } ?: Uri.parse(stream.url)
        streamHeaders[uri.toString()]=stream.headers.filterKeys { it.lowercase() in setOf("user-agent","accept","accept-language","referer","origin") }
        val videoUri=stream.sabrVideoBootstrap?.let {val key=UUID.randomUUID().toString();sabrSelections[key]=it;Uri.parse("sabrvideo://$key")}
            ?:stream.videoUrl?.let(Uri::parse)
        videoUri?.let {streamHeaders[it.toString()]=stream.headers.filterKeys {k->k.lowercase() in setOf("user-agent","accept","accept-language","referer","origin")}}
        val extra=Bundle().apply {putString("videoUri",videoUri?.toString());putBoolean("video",videoUri!=null || track.isLive || stream.includesVideo); putString("artistId",track.artistId);putString("albumId",track.albumId);putBoolean("live",track.isLive);putString("profileId",stream.profileId);putString("sourceVideoId",stream.sourceVideoId?:track.videoId);putBoolean("music",track.isMusic) }
        val hls=stream.mimeType?.contains("mpegurl",true)==true
        // HLS forbids MediaItem custom cache keys; live/video also bypass the audio cache.
        val cacheKey=if(!hls && !track.isLive && !stream.includesVideo && videoUri==null)
            audioStreamCacheKey(offline.currentScope,stream.sourceVideoId?:track.videoId,stream.itag,
                stream.mimeType,stream.bitrate,stream.sabrBootstrap!=null) else null
        return MediaItem.Builder().setMediaId("yt:${track.videoId}").setUri(uri).setCustomCacheKey(cacheKey)
            .apply { if(stream.mimeType?.contains("mpegurl",true)==true) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
                .setArtworkUri(validProviderArtworkUrl(track.artworkUrl)?.let(Uri::parse)).setExtras(extra).build()).build()
            .also {resolvedStreams[it]=stream}
    }
    private fun cancelRadio() {
        streamRecoveryJob?.cancel();streamRecoveryJob=null;recoveryReplacement=null
        streamRecovery=StreamRecoveryBudget();resolvedStreams.clear()
        liveFallbackJob?.cancel();liveFallbackJob=null;failedLiveProfiles.clear()
        queueGeneration++;queueJob?.cancel();queueJob=null;radioSeed=null;radioContinuation=null;queueCandidates.clear()
        session?.player?.setPlaylistMetadata(MediaMetadata.EMPTY)
    }
    private fun providerRetrying(player: Player, value: Boolean) {
        player.setPlaylistMetadata(player.playlistMetadata.buildUpon().setExtras(
            Bundle(player.playlistMetadata.extras ?: Bundle()).apply {putBoolean("providerRetrying",value)}).build())
    }
    /** Replace the rejected source as well as its credentials, keeping queue order and position. */
    private fun recoverProviderStream(player: Player, error: androidx.media3.common.PlaybackException) {
        val item=player.currentMediaItem ?: return
        if(!item.mediaId.startsWith("yt:") || item.mediaMetadata.extras?.getBoolean("live")==true || streamRecoveryJob?.isActive==true) return
        val causes=generateSequence(error as Throwable?) {it.cause}.take(16).toList()
        val kind=causes.filterIsInstance<SabrPlaybackFailure>().firstOrNull()?.kind
        val status=causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val previous=resolvedStreams[item]
        val profile=previous?.profileId ?: item.mediaMetadata.extras?.getString("profileId")
        val action=streamRecovery.next(kind,status,profile) ?: return
        val excluded=streamRecovery.excludedProfiles.toSet()
        val generation=queueGeneration
        val track=trackFromItem(item)
        val sourceId=item.mediaMetadata.extras?.getString("sourceVideoId") ?: previous?.sourceVideoId ?: track.videoId
        val video=previous?.includesVideo==true || item.mediaMetadata.extras?.getBoolean("video")==true
        events.append("provider_stream_recovery action=$action attempt=${excluded.size}")
        providerRetrying(player,true)
        streamRecoveryJob=serviceScope.launch {
            try {
                if(action==StreamRecoveryAction.REFRESH_ATTESTATION) provider.invalidateAttestation()
                provider.invalidateStream(sourceId)
                val stream=withTimeout(60_000) {provider.resolve(sourceId,video=video,excludedProfiles=excluded).getOrThrow()}
                if(generation!=queueGeneration || player.currentMediaItem!==item) return@launch
                val replacement=providerItem(track,stream)
                recoveryReplacement=replacement
                if(replaceFailedStream(player,item,replacement)) events.append("provider_stream_replaced")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {events.append("provider_stream_recovery_failed type=${e::class.simpleName}")}
            finally {if(generation==queueGeneration && (player.currentMediaItem===item || player.currentMediaItem===recoveryReplacement)) providerRetrying(player,false)}
        }
    }
    /** Only retry a rejected live transport through the provider's supported client selection. */
    private fun retryLiveProfile(player: Player) {
        val item=player.currentMediaItem?:return
        val meta=item.mediaMetadata;val extras=meta.extras?:return
        if(!extras.getBoolean("live") || liveFallbackJob?.isActive==true || failedLiveProfiles.size>=3) return
        val profile=extras.getString("profileId")?:return
        if(!failedLiveProfiles.add(profile)) return
        val index=player.currentMediaItemIndex;val generation=queueGeneration
        val track=ProviderTrack(item.mediaId.removePrefix("yt:"),meta.title.toString(),meta.artist.toString(),
            meta.artworkUri?.toString(),extras.getString("artistId"),extras.getString("albumId"),meta.albumTitle?.toString().orEmpty(),true)
        events.append("live_profile_rejected profile=$profile attempt=${failedLiveProfiles.size}")
        fun retrying(value:Boolean) {player.setPlaylistMetadata(player.playlistMetadata.buildUpon().setExtras(Bundle(player.playlistMetadata.extras?:Bundle()).apply {putBoolean("liveRetrying",value)}).build())}
        retrying(true)
        liveFallbackJob=serviceScope.launch {
            try {
            val stream=provider.resolve(track.videoId,true,failedLiveProfiles.toSet(),video=true).getOrNull()
            if(generation!=queueGeneration || player.currentMediaItem?.mediaId!=item.mediaId) return@launch
            if(stream==null) {events.append("live_profile_fallback_unavailable");return@launch}
            val wants=player.playWhenReady
            player.replaceMediaItem(index,providerItem(track,stream));player.seekTo(index,0)
            player.prepare();player.playWhenReady=wants
            events.append("live_profile_fallback profile=${stream.profileId.orEmpty()}")
            } finally {if(generation==queueGeneration) retrying(false)}
        }
    }
    private fun queueStatus(player: Player, loading: Boolean, message: String) {
        player.setPlaylistMetadata(MediaMetadata.Builder().setSubtitle(message)
            .setExtras(Bundle().apply {putBoolean("queueLoading",loading)}).build())
    }
    private fun replenishQueue(player: Player) {
        val seed=radioSeed?:return
        if(player.playbackState!=Player.STATE_READY || !player.isPlaying) return
        if(queueJob?.isActive==true || player.mediaItemCount-player.currentMediaItemIndex>4 || player.mediaItemCount>=80) return
        val generation=queueGeneration;val owner=sessions.capture()
        queueJob=serviceScope.launch {
            queueStatus(player,true,"Finding more music on YouTube Music\u2026")
            try {
                if(queueCandidates.isEmpty()) {
                    val (tracks,token)=provider.recommendations(seed,radioContinuation).getOrThrow()
                    if(generation!=queueGeneration || !sessions.accepts(owner)) return@launch
                    radioContinuation=token
                    val known=(0 until player.mediaItemCount).map {val m=player.getMediaItemAt(it).mediaMetadata;queueRecordingKey(m.title.toString(),m.artist.toString())}.toSet()
                    val hidden=NotInterestedStore.get(this@MusicPlaybackService).keys
                    queueCandidates.addAll(queueCandidates(tracks,queuedIds+hidden,known))
                }
                var added=0;var attempted=0
                while(queueCandidates.isNotEmpty() && added<6 && attempted<12) {
                    val track=queueCandidates.removeFirst();attempted++
                    if(track.videoId in queuedIds) continue
                    if(generation!=queueGeneration || !isActive || !sessions.accepts(owner)) return@launch
                    if(track.videoId in queuedIds || (0 until player.mediaItemCount).any {
                        val m=player.getMediaItemAt(it).mediaMetadata
                        queueRecordingKey(m.title.toString(),m.artist.toString())==queueRecordingKey(track.title,track.artist)
                    }) continue
                    if(!queuedIds.add(track.videoId)) continue
                    player.addMediaItem(catalogItem(track));added++
                    if(added==1) queueStatus(player,true,"Adding YouTube Music recommendations\u2026")
                }
                queueStatus(player,false,if(added>0) "Music from YouTube Music" else "No more playable recommendations right now")
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(generation==queueGeneration) queueStatus(player,false,"Couldn't load recommendations. Your current song can keep playing.")}
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        provider.discardPageMemory()
        taskRemoved=true
        events.append("task_removed_stop")
        session?.player?.let {saveSnapshot(it,flush=true)};discardingSession=true
        cancelRadio();session?.player?.stop();session?.player?.clearMediaItems()
        pauseAllPlayersAndStopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Network recovery (2026-10-04): resume by itself after a playback error caused by a lost connection.
    private var networkFailure=false
    private var networkRetryAttempts=0
    private var networkRetryJob:Job?=null
    private var networkCallback:android.net.ConnectivityManager.NetworkCallback?=null
    private fun startNetworkRecovery(player:Player) {
        networkFailure=true
        events.append("network_failure attempts=$networkRetryAttempts")
        networkRetryJob?.cancel()
        networkRetryJob=serviceScope.launch {for(wait in NetworkRecovery.retryDelaysMs) {delay(wait);tryNetworkRetry(player,"timer")}}
        if(networkCallback==null) runCatching {
            val callback=object:android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:android.net.Network) {serviceScope.launch {delay(1500);tryNetworkRetry(player,"network")}}
            }
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
            networkCallback=callback
        }
    }
    private fun tryNetworkRetry(player:Player,trigger:String) {
        if(!NetworkRecovery.shouldRetry(networkFailure,player.playWhenReady,player.playerError!=null,networkRetryAttempts)) return
        if(getSystemService(android.net.ConnectivityManager::class.java).activeNetwork==null) return
        networkRetryAttempts++
        events.append("network_retry attempt=$networkRetryAttempts trigger=$trigger")
        player.prepare()
    }
    private fun endNetworkRecovery() {
        if(networkFailure) events.append("network_recovered attempts=$networkRetryAttempts")
        networkFailure=false;networkRetryAttempts=0;networkRetryJob?.cancel();networkRetryJob=null
        networkCallback?.let {callback-> runCatching {getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(callback)}}
        networkCallback=null
    }

    override fun onDestroy() {
        endNetworkRecovery()
        boostPrefs.unregisterOnSharedPreferenceChangeListener(boostListener)
        sleepTimer.close()
        crossfader?.release()
        audioEffects.release()
        val player=session?.player
        val playWhenReady=player?.playWhenReady==true
        events.append(runCatching {stopContext(this,playWhenReady,player?.playbackState?:0,taskRemoved)}.getOrDefault("service_destroy"))
        if(isUnexpectedStop(playWhenReady,taskRemoved)) events.append("unexpected_stop")
        sessionRegistration?.close();sessionRegistration=null
        session?.player?.let {saveSnapshot(it,flush=true)};discardingSession=true;saveJob?.cancel()
        session?.let { media ->
            media.player.release()
            media.release()
        }
        session = null
        offline.playbackBusy=false
        cancelRadio();serviceScope.cancel();sabrSelections.clear();streamHeaders.clear()
        sabrHttp.close()
        super.onDestroy()
    }
}
