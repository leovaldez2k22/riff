package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

/** A late decoder must never replay an earlier part of the outgoing song after handoff. */
internal fun crossfadeTailAligned(mainPosition: Long, tailPosition: Long): Boolean =
    abs(mainPosition - tailPosition) <= 120L

/** Align well inside the audible cutoff, leaving room for decoder timestamp jitter at handoff. */
internal class CrossfadeTailSynchronizer {
    private var direction=0
    private var alignedSince:Long?=null
    fun update(difference:Long,now:Long,settled:Boolean,playing:Boolean):Float {
        if(!settled || !playing) {direction=0;alignedSince=null;return 1f}
        if(direction==1 && difference<=20 || direction == -1 && difference>=-20) direction=0
        if(direction==0 && abs(difference)>80) direction=if(difference>0) 1 else -1
        if(direction==0 && abs(difference)<=80) {
            if(alignedSince==null) alignedSince=now
        } else alignedSince=null
        // AudioTrack may already hold hundreds of milliseconds at the previous speed.
        // Gentle correction keeps that delayed response inside the alignment margin.
        return when(direction) {1->1.05f;-1->.95f;else->1f}
    }
    fun ready(now:Long):Boolean=alignedSince?.let {now-it>=200}==true
}

/** Once the audible tail loses continuity, it stays silent for the rest of this blend. */
internal class CrossfadeTailContinuity {
    var ended = false; private set
    fun audible(playing: Boolean): Boolean {
        if (!playing) ended = true
        return !ended
    }
}

internal const val CROSSFADE_PREFS = "crossfade"
internal fun crossfadeSeconds(value: Int) = value.coerceIn(1, 12)

internal fun crossfadeSkipsItem(item:MediaItem):Boolean {
    val extras=item.mediaMetadata.extras
    // Audio items also carry videoUri=null. Presence of the key does not mean there is a video.
    return extras?.getBoolean("live")==true || extras?.getBoolean("video")==true ||
        !extras?.getString("videoUri").isNullOrBlank() || extras?.getBoolean("music",true)==false ||
        !(item.mediaId.startsWith("yt:") || item.mediaId.startsWith("local:"))
}

/** Settings → Audio → Crossfade. Off by default. */
internal class CrossfadeSettings(context: Context) {
    companion object {
        @Volatile private var instance:CrossfadeSettings?=null
        fun get(context:Context)=instance?:synchronized(this) {instance?:CrossfadeSettings(context.applicationContext).also {instance=it}}
    }
    private val prefs = context.applicationContext.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var seconds by mutableIntStateOf(crossfadeSeconds(prefs.getInt("seconds", 6))); private set
    fun updateEnabled(value: Boolean) { enabled = value; prefs.edit { putBoolean("enabled", value) } }
    fun updateSeconds(value: Int) { seconds = crossfadeSeconds(value); prefs.edit { putInt("seconds", seconds) } }
}

/** Equal-power curves keep the blend about as loud as either song: (outgoing, incoming). */
internal fun crossfadeGains(progress: Float): Pair<Float, Float> {
    val t = progress.coerceIn(0f, 1f) * (PI / 2).toFloat()
    return cos(t) to sin(t)
}

/** A single smooth clock for both gains and the visuals, independent of decoder timestamps. */
internal class CrossfadeEnvelope(durationMs:Long,startedMs:Long) {
    private val duration=durationMs.coerceAtLeast(1)
    private var lastTick=startedMs
    private var elapsed=0L
    val progress:Float get()=(elapsed.toDouble()/duration).toFloat().coerceIn(0f,1f)
    fun advance(nowMs:Long,playing:Boolean):Float {
        // A delayed callback must not turn either volume into a large, single-step change.
        val delta=(nowMs-lastTick).coerceIn(0,50)
        lastTick=maxOf(lastTick,nowMs)
        if(playing) elapsed=(elapsed+delta).coerceAtMost(duration)
        return progress
    }
}

/**
 * Only automatic song changes are blended: not the last song, repeat-one, live radio, videos, songs too
 * short to fade, or while the sleep timer runs (it owns the player volume then).
 */
internal fun shouldStartCrossfade(fadeMs: Long, positionMs: Long, durationMs: Long, playing: Boolean, hasNext: Boolean,
    repeatOne: Boolean, skipItem: Boolean, sleepActive: Boolean): Boolean {
    if (!playing || !hasNext || repeatOne || skipItem || sleepActive || durationMs <= 0) return false
    if (durationMs < fadeMs * 2 + 10_000) return false
    val remaining = durationMs - positionMs
    return remaining in minOf(1_500L,fadeMs/2)..fadeMs
}

internal fun shouldPrepareCrossfade(fadeMs:Long,positionMs:Long,durationMs:Long,playing:Boolean,hasNext:Boolean,
    repeatOne:Boolean,skipItem:Boolean,sleepActive:Boolean):Boolean =
    durationMs-positionMs<=fadeMs+30_000 && shouldStartCrossfade(fadeMs,maxOf(positionMs,durationMs-fadeMs),durationMs,
        playing,hasNext,repeatOne,skipItem,sleepActive)

/**
 * Blends songs with a second, short-lived player: it picks up the end of the current song at the same
 * position, the main player moves on to the next song, and their volumes cross over. The main player
 * (and its media session, queue and notification) is never paused or rebuilt.
 */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class Crossfader(private val context: Context, private val scope: CoroutineScope,
    private val createTail: () -> Pair<ExoPlayer, () -> Unit>, private val sleepActive: () -> Boolean,
    private val tailItem:(MediaItem)->MediaItem={it},private val onBlend:(SongCrossfade?)->Unit={},private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    private var monitor: Job? = null
    private var fade: Job? = null
    private var handledKey: String? = null
    private var mainPlayer:ExoPlayer?=null
    private var handoff=false
    private var originalVolume:Float?=null
    private var visualBlend:SongCrossfade?=null
    private var activeTail:ExoPlayer?=null
    private var expectedTransition:String?=null
    private var expectedSeek:String?=null
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key=="enabled" && !prefs.getBoolean("enabled",false)) cancel()
        // Duration may change after the outgoing decoder has already been prepared.
        if(key=="seconds" && visualBlend==null) {cancel();handledKey=null}
    }
    private val listener=object:Player.Listener {
        override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {
            val expected = expectedTransition
            if (expected != null && mainPlayer?.let(::itemKey) == expected &&
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) expectedTransition = null
            else if(!handoff) cancel()
            handledKey=null
        }
        override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
            if(!playWhenReady) {cancel();handledKey=null}
        }
        override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
            val target="${newPosition.mediaItemIndex}:${newPosition.mediaItem?.mediaId}"
            if(reason==Player.DISCONTINUITY_REASON_SEEK && expectedSeek==target) expectedSeek=null
            else if(reason==Player.DISCONTINUITY_REASON_SEEK && !handoff) {cancel();handledKey=null}
        }
    }

    private fun itemKey(player: Player) = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}"

    fun start(main: ExoPlayer) {
        monitor?.cancel()
        mainPlayer?.removeListener(listener)
        mainPlayer=main;main.addListener(listener)
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        monitor = scope.launch {
            var preloading:Boolean?=null
            while (isActive) {
                delay(if (main.isPlaying) 200 else 1_000)
                val enabled=prefs.getBoolean("enabled",false)
                if(preloading!=enabled) {
                    main.setPreloadConfiguration(if(enabled) ExoPlayer.PreloadConfiguration(12_000_000L) else ExoPlayer.PreloadConfiguration.DEFAULT)
                    preloading=enabled
                }
                // Qualify the member: CoroutineScope.cancel() would kill this monitor forever.
                if(!enabled) {this@Crossfader.cancel();handledKey=null;continue}
                if (fade?.isActive == true) {
                    continue
                }
                val item = main.currentMediaItem ?: continue
                val key = itemKey(main)
                if (key == handledKey) {
                    continue
                }
                val fadeMs = crossfadeSeconds(prefs.getInt("seconds", 6)) * 1_000L
                val nextIndex=main.nextMediaItemIndex
                val skipNext=nextIndex<0 || crossfadeSkipsItem(main.getMediaItemAt(nextIndex))
                if (shouldPrepareCrossfade(fadeMs, main.currentPosition, main.duration, main.isPlaying, main.hasNextMediaItem(),
                        main.repeatMode == Player.REPEAT_MODE_ONE, crossfadeSkipsItem(item) || skipNext, sleepActive())) {
                    handledKey = key
                    fade = launch { blend(main, item, key, fadeMs) }
                }
            }
        }
    }

    private suspend fun blend(main: ExoPlayer, item: MediaItem, key: String, fadeMs: Long) {
        val base = main.volume
        val (tail, releaseTail) = createTail()
        activeTail=tail
        try {
            tail.volume = 0f
            tail.setSeekParameters(SeekParameters.EXACT)
            tail.playbackParameters=main.playbackParameters
            tail.setMediaItem(tailItem(item), main.currentPosition)
            tail.playWhenReady = false
            tail.prepare()
            if (withTimeoutOrNull(25_000) {
                    while (tail.playbackState != Player.STATE_READY) {
                        if(itemKey(main)!=key || !main.playWhenReady || sleepActive() || tail.playerError!=null) return@withTimeoutOrNull false
                        delay(20)
                    }
                    true
                } != true) {
                log("crossfade_skipped reason=tail_not_ready"); return
            }
            log("crossfade_prepared")
            var bufferedSince:Long?=null
            while(main.duration-main.currentPosition>fadeMs+12_000 || !main.isPlaying) {
                if(itemKey(main)!=key || !main.playWhenReady || sleepActive() ||
                    main.repeatMode==Player.REPEAT_MODE_ONE || main.playerError!=null ||
                    main.playbackState==Player.STATE_ENDED) return
                // A short network stall is not a user pause. Keep the prepared, muted copy;
                // abandoning it here marks this song handled and loses its eventual crossfade.
                if(!main.isPlaying) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(bufferedSince==null) {bufferedSince=now;log("crossfade_waiting reason=main_buffering")}
                    if(now-bufferedSince>=15_000) {log("crossfade_skipped reason=main_stalled_before_warmup");return}
                } else bufferedSince=null
                delay(50)
            }
            if (itemKey(main) != key || !main.isPlaying) return
            // Seek ahead while muted and paused, giving the decoder time to finish the seek.
            // A seek followed immediately by play can report old READY state before it buffers.
            val target=main.currentPosition+750
            tail.seekTo(target)
            if(withTimeoutOrNull(3_000) {
                    while(main.currentPosition<target || tail.playbackState!=Player.STATE_READY) {
                        if(itemKey(main)!=key || !main.playWhenReady || sleepActive() || tail.playerError!=null) return@withTimeoutOrNull false
                        delay(10)
                    }
                    true
                } != true) {
                log("crossfade_skipped reason=tail_seek_not_ready ahead=${target-main.currentPosition} tailState=${tail.playbackState} mainState=${main.playbackState}");return
            }
            tail.play()
            // READY/isPlaying may initially use the software clock before AudioTrack timestamps
            // settle. Warm up muted before checking alignment against the playing decoder.
            val clockStarted=android.os.SystemClock.elapsedRealtime()
            var lastSyncLog=0L
            if (withTimeoutOrNull(14_000) {
                    var synchronizer=CrossfadeTailSynchronizer()
                    var coarseAligned=false
                    var lagReference:Long?=null
                    var lagSince=0L
                    var settleUntil=clockStarted+2_000
                    while(true) {
                        if(itemKey(main)!=key || !main.playWhenReady || sleepActive() || tail.playerError!=null) return@withTimeoutOrNull false
                        val now=android.os.SystemClock.elapsedRealtime()
                        val difference=main.currentPosition-tail.currentPosition
                        val parameters=main.playbackParameters
                        // A compressed stream can start hundreds of milliseconds late. Small speed
                        // corrections alone cannot recover that lag after a seek near the end.
                        // Measure a stable startup lag, then seek the still-muted copy ahead once.
                        // Include that lag in the target to compensate for its queued audio.
                        val bothPlaying=main.isPlaying && tail.isPlaying
                        if(!coarseAligned && now-clockStarted>=1_000 && bothPlaying && difference>120 &&
                            main.duration-main.currentPosition>difference+500) {
                            if(lagReference==null || abs(difference-lagReference!!)>20) {
                                lagReference=difference;lagSince=now
                            } else if(now-lagSince>=200) {
                                coarseAligned=true
                                tail.playbackParameters=parameters
                                // Center post-seek timestamp variation inside the 80 ms target.
                                tail.seekTo(main.currentPosition+difference+40)
                                synchronizer=CrossfadeTailSynchronizer()
                                settleUntil=now+1_000
                                log("crossfade_align_seek ahead_ms=$difference")
                                continue
                            }
                        } else {lagReference=null;lagSince=now}
                        val settled=now>=settleUntil
                        val correction=synchronizer.update(difference,now,settled,main.isPlaying && tail.isPlaying)
                        if(BuildConfig.DEBUG && now-lastSyncLog>=250) {
                            log("crossfade_sync elapsed=${now-clockStarted} remaining=${main.duration-main.currentPosition} delta=$difference speed=${tail.playbackParameters.speed} playing=${tail.isPlaying}")
                            lastSyncLog=now
                        }
                        val desired=if(correction==1f) parameters
                            else PlaybackParameters(parameters.speed*correction,parameters.pitch)
                        if(tail.playbackParameters!=desired) tail.playbackParameters=desired
                        if(main.duration-main.currentPosition<=fadeMs && synchronizer.ready(now))
                            return@withTimeoutOrNull true
                        delay(10)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                } != true) {
                log("crossfade_skipped reason=tail_not_aligned delta=${main.currentPosition-tail.currentPosition} state=${tail.playbackState} playing=${tail.isPlaying} speed=${tail.playbackParameters.speed} mainState=${main.playbackState} mainPlaying=${main.isPlaying} wants=${main.playWhenReady} suppression=${main.playbackSuppressionReason}"); return
            }
            tail.playbackParameters=main.playbackParameters
            if (itemKey(main) != key || !main.isPlaying || sleepActive()) return
            if(!tail.isPlaying || !crossfadeTailAligned(main.currentPosition,tail.currentPosition)) {
                log("crossfade_skipped reason=handoff_drift delta=${main.currentPosition-tail.currentPosition}");return
            }
            // Muted preparation prevents two copies of the outgoing song doubling its volume.
            val tailDuration=tail.duration.takeIf {it>0}?:main.duration
            val overlap = minOf(main.duration-main.currentPosition,tailDuration-tail.currentPosition,fadeMs)
            if(overlap<500) return
            originalVolume=base
            main.volume = 0f
            tail.volume = base
            val visual=SongCrossfade(songPresentation(item),main.getMediaItemAt(main.nextMediaItemIndex).mediaId,overlap)
            visualBlend=visual;onBlend(visual)
            expectedTransition="${main.nextMediaItemIndex}:${main.getMediaItemAt(main.nextMediaItemIndex).mediaId}"
            expectedSeek=expectedTransition
            handoff=true
            try {main.seekToNextMediaItem()} finally {handoff=false}
            val next = itemKey(main)
            log("crossfade_start ms=$overlap")
            val envelope=CrossfadeEnvelope(overlap,android.os.SystemClock.elapsedRealtime())
            val continuity=CrossfadeTailContinuity()
            var stalledSince:Long?=null
            while (true) {
                delay(16)
                if (itemKey(main) != next || !main.playWhenReady || sleepActive() ||
                    main.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE || main.playerError!=null) break
                val now=android.os.SystemClock.elapsedRealtime()
                if(main.isPlaying) stalledSince=null
                else {
                    if(stalledSince==null) stalledSince=now
                    if(now-stalledSince>=15_000) {log("crossfade_cancelled reason=incoming_stalled");break}
                }
                // Decoder startup/buffering pauses the ramp; it never compresses the selected fade.
                val progress=envelope.advance(android.os.SystemClock.elapsedRealtime(),main.isPlaying)
                val (out,incoming)=crossfadeGains(progress)
                // A buffered/ended tail cannot become audible again over the next song.
                tail.volume = if(continuity.audible(tail.isPlaying)) base * out else 0f
                main.volume = base * incoming
                visual.progress=progress
                if (progress>=1f) break
                // An early tail end/error must not jump the incoming song straight to full volume.
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("crossfade_failed type=${e::class.simpleName}") }
        finally {
            tail.volume=0f
            clearVisualBlend()
            if(!sleepActive()) main.volume = base
            originalVolume=null
            if(activeTail===tail) activeTail=null
            expectedTransition=null
            expectedSeek=null
            runCatching { tail.stop() }
            runCatching { releaseTail() }
            runCatching { tail.release() }
        }
    }

    /** Ends any blend at once: used when the user pauses, skips or the service stops. */
    fun cancel() {
        // Coroutine cleanup runs later: mute and pause now so user actions cannot leave overlap.
        activeTail?.let {it.volume=0f;it.pause()}
        expectedTransition=null
        expectedSeek=null
        // Restore synchronously before a sleep timer or another volume owner captures the level.
        originalVolume?.let {mainPlayer?.volume=it};originalVolume=null
        clearVisualBlend()
        fade?.cancel()
    }

    private fun clearVisualBlend() {
        if(visualBlend!=null) {visualBlend=null;onBlend(null)}
    }
    fun release() { cancel(); monitor?.cancel();mainPlayer?.removeListener(listener);mainPlayer=null
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
}
