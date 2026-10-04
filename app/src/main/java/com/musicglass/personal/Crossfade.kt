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

/** Settings → Audio → Crossfade. Off by default. */
internal class CrossfadeSettings(context: Context) {
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

/**
 * Blends songs with a second, short-lived player: it picks up the end of the current song at the same
 * position, the main player moves on to the next song, and their volumes cross over. The main player
 * (and its media session, queue and notification) is never paused or rebuilt.
 */
internal class Crossfader(private val context: Context, private val scope: CoroutineScope,
    private val createTail: () -> Pair<ExoPlayer, () -> Unit>, private val sleepActive: () -> Boolean, private val log: (String) -> Unit) {
    private val prefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
    private var monitor: Job? = null
    private var fade: Job? = null
    private var handledKey: String? = null

    private fun itemKey(player: Player) = "${player.currentMediaItemIndex}:${player.currentMediaItem?.mediaId}"
    private fun skip(item: MediaItem): Boolean {
        val extras = item.mediaMetadata.extras
        return extras?.getBoolean("live") == true || extras?.getBoolean("video") == true || extras?.containsKey("videoUri") == true ||
            !(item.mediaId.startsWith("yt:") || item.mediaId.startsWith("local:"))
    }

    fun start(main: ExoPlayer) {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(if (main.isPlaying) 200 else 1_000)
                if (fade?.isActive == true || !prefs.getBoolean("enabled", false)) continue
                val item = main.currentMediaItem ?: continue
                val key = itemKey(main)
                if (key == handledKey) continue
                val fadeMs = crossfadeSeconds(prefs.getInt("seconds", 6)) * 1_000L
                if (shouldStartCrossfade(fadeMs, main.currentPosition, main.duration, main.isPlaying, main.hasNextMediaItem(),
                        main.repeatMode == Player.REPEAT_MODE_ONE, skip(item), sleepActive())) {
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
            tail.volume = base
            tail.setMediaItem(item, main.currentPosition + 400)
            tail.playWhenReady = false
            tail.prepare()
            if (withTimeoutOrNull(2_500) { while (tail.playbackState != Player.STATE_READY) delay(20) } == null) {
                log("crossfade_skipped reason=tail_not_ready"); return
            }
            if (itemKey(main) != key || !main.isPlaying) return
            // Line the copy up with what is playing now, then hand over as soon as it sounds.
            tail.seekTo(main.currentPosition + 60)
            tail.play()
            if (withTimeoutOrNull(1_500) { while (!tail.isPlaying) delay(10) } == null) {
                log("crossfade_skipped reason=tail_not_playing"); return
            }
            if (itemKey(main) != key || !main.isPlaying) return
            val overlap = (main.duration - main.currentPosition).coerceIn(1_000, fadeMs)
            main.volume = 0f
            main.seekToNextMediaItem()
            val next = itemKey(main)
            log("crossfade_start ms=$overlap")
            val started = System.nanoTime()
            var heard = -1L
            while (true) {
                delay(16)
                if (itemKey(main) != next || !main.playWhenReady) break // Skipped, paused or stopped: stop blending.
                val now = System.nanoTime()
                if (heard < 0 && main.isPlaying) heard = now
                val out = crossfadeGains((now - started) / 1e6f / overlap).first
                val incoming = if (heard < 0) 0f else crossfadeGains((now - heard) / 1e6f / overlap).second
                tail.volume = base * out
                main.volume = base * incoming
                if (out <= 0.001f && incoming >= .999f) break
                if (tail.playbackState == Player.STATE_ENDED && heard >= 0 && now - heard > overlap * 1_000_000) break
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { log("crossfade_failed type=${e::class.simpleName}") }
        finally {
            main.volume = base
            runCatching { tail.stop(); releaseTail(); tail.release() }
        }
    }

    /** Ends any blend at once: used when the user pauses, skips or the service stops. */
    fun cancel() { fade?.cancel() }

    fun release() { cancel(); monitor?.cancel() }
}
