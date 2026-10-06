package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    return remaining in 1_500..fadeMs
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
    private val preferenceListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key=="enabled" && !prefs.getBoolean("enabled",false)) cancel()
    }
    private val listener=object:Player.Listener {
        override fun onMediaItemTransition(mediaItem:MediaItem?,reason:Int) {
            if(!handoff) cancel()
            handledKey=null
        }
        override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) {
            if(!playWhenReady) {cancel();handledKey=null}
        }
        override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) {
            if(reason==Player.DISCONTINUITY_REASON_SEEK && !handoff) {cancel();handledKey=null}
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
                if(!enabled) {cancel();handledKey=null;continue}
                if (fade?.isActive == true) continue
                val item = main.currentMediaItem ?: continue
                val key = itemKey(main)
                if (key == handledKey) continue
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
        try {
            tail.volume = 0f
            tail.playbackParameters=main.playbackParameters
            tail.setMediaItem(tailItem(item), main.currentPosition)
            tail.playWhenReady = false
            tail.prepare()
            if (withTimeoutOrNull(25_000) {
                    while (tail.playbackState != Player.STATE_READY) {
                        if(itemKey(main)!=key || !main.isPlaying || sleepActive() || tail.playerError!=null) return@withTimeoutOrNull false
                        delay(20)
                    }
                    true
                } != true) {
                log("crossfade_skipped reason=tail_not_ready"); return
            }
            log("crossfade_prepared")
            while(main.duration-main.currentPosition>fadeMs) {
                if(itemKey(main)!=key || !main.isPlaying || sleepActive() || main.repeatMode==Player.REPEAT_MODE_ONE) return
                delay(50)
            }
            if (itemKey(main) != key || !main.isPlaying) return
            // Line the copy up with what is playing now, then hand over as soon as it sounds.
            tail.seekTo(main.currentPosition)
            tail.play()
            if (withTimeoutOrNull(1_500) { while (!tail.isPlaying) delay(10) } == null) {
                log("crossfade_skipped reason=tail_not_playing"); return
            }
            if (itemKey(main) != key || !main.isPlaying || sleepActive()) return
            // Muted preparation prevents two copies of the outgoing song doubling its volume.
            val tailDuration=tail.duration.takeIf {it>0}?:main.duration
            val overlap = minOf(main.duration-main.currentPosition,tailDuration-tail.currentPosition,fadeMs)
            if(overlap<500) return
            originalVolume=base
            main.volume = 0f
            tail.volume = base
            val visual=SongCrossfade(songPresentation(item),main.getMediaItemAt(main.nextMediaItemIndex).mediaId,overlap)
            visualBlend=visual;onBlend(visual)
            handoff=true
            try {main.seekToNextMediaItem()} finally {handoff=false}
            val next = itemKey(main)
            log("crossfade_start ms=$overlap")
            val envelope=CrossfadeEnvelope(overlap,android.os.SystemClock.elapsedRealtime())
            while (true) {
                delay(16)
                if (itemKey(main) != next || !main.playWhenReady || sleepActive() ||
                    main.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE || main.playerError!=null) break
                // Decoder startup/buffering pauses the ramp; it never compresses the selected fade.
                val progress=envelope.advance(android.os.SystemClock.elapsedRealtime(),main.isPlaying)
                val (out,incoming)=crossfadeGains(progress)
                tail.volume = base * out
                main.volume = base * incoming
                visual.progress=progress
                if (progress>=1f) break
                // An early tail end/error must not jump the incoming song straight to full volume.
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("crossfade_failed type=${e::class.simpleName}") }
        finally {
            clearVisualBlend()
            if(!sleepActive()) main.volume = base
            originalVolume=null
            runCatching { tail.stop(); releaseTail(); tail.release() }
        }
    }

    /** Ends any blend at once: used when the user pauses, skips or the service stops. */
    fun cancel() {
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
