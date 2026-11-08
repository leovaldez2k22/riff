package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Feature 10 (Riff+, owner Q10): bars drawn from Riff's own decoded audio, never the microphone. The main player's
 * equalizer stage hands a mono copy of what it has just written to [VisualizerTap] while a visualizer is on screen;
 * it only reads, so the sound is the same with it on or off. During a blend the bars follow the main player (the
 * song coming in), so the two songs are never counted twice.
 */

/** The last [VisualizerTap.SIZE] mono samples, written by the audio thread, read by the bars' worker. */
internal object VisualizerTap {
    const val SIZE = 2048
    /** Null while no visualizer is shown: then the audio thread does nothing more than read this field. */
    @Volatile private var ring: FloatArray? = null
    @Volatile var sampleRate = 48_000; private set
    /** How many samples were ever written; a reader that sees the same count twice knows nothing new came. */
    @Volatile var written = 0L; private set

    val active get() = ring != null
    fun start() { if (ring == null) { ring = FloatArray(SIZE); written = 0 } }
    fun stop() { ring = null; written = 0 }

    /** Called by the audio thread with what it has just output; [pcm] is read from its position to its limit, not moved. */
    fun feed(pcm: ByteBuffer, channels: Int, rate: Int) {
        val buffer = ring ?: return
        if (channels <= 0) return
        sampleRate = rate
        val data = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frames = data.remaining() / (2 * channels)
        var at = (written % SIZE).toInt()
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += data.short.toInt()
            buffer[at] = sum / (channels * 32768f)
            at = if (at + 1 == SIZE) 0 else at + 1
        }
        written += frames
    }

    /** Copies the newest [out].size samples into [out], oldest first. False when nothing has been written. */
    fun latest(out: FloatArray): Boolean {
        val buffer = ring ?: return false
        val end = written; if (end == 0L) return false
        val n = out.size.coerceAtMost(SIZE)
        var at = ((end - n) % SIZE + SIZE).toInt() % SIZE
        for (i in 0 until n) { out[i] = buffer[at]; at = if (at + 1 == SIZE) 0 else at + 1 }
        return true
    }
}

/**
 * Bar heights from a window of samples: a Hann window, a radix-2 FFT and [bands] bars spaced on a log scale from
 * 40 Hz to 16 kHz, each mapped from −60..0 dB to 0..1. Every array is made once.
 */
internal class SpectrumBars(val size: Int = 1024, val bands: Int = 24) {
    init { require(size >= 64 && size and (size - 1) == 0) }
    private val re = DoubleArray(size); private val im = DoubleArray(size)
    private val hann = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / (size - 1)) }
    private val cosT = DoubleArray(size / 2) { cos(2 * PI * it / size) }; private val sinT = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    val levels = FloatArray(bands)

    fun compute(window: FloatArray, sampleRate: Int): FloatArray {
        for (i in 0 until size) { re[i] = window[i] * hann[i]; im[i] = 0.0 }
        fft()
        val binHz = sampleRate.toDouble() / size
        for (b in 0 until bands) {
            val lo = 40.0 * Math.pow(400.0, b.toDouble() / bands); val hi = 40.0 * Math.pow(400.0, (b + 1.0) / bands)
            val from = (lo / binHz).toInt().coerceIn(1, size / 2 - 1); val to = (hi / binHz).toInt().coerceIn(from, size / 2 - 1)
            var peak = 0.0
            for (k in from..to) { val m = sqrt(re[k] * re[k] + im[k] * im[k]); if (m > peak) peak = m }
            // A full-scale sine through the Hann window peaks at size/4.
            val db = 20 * log10((peak / (size / 4.0)).coerceAtLeast(1e-9))
            levels[b] = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
        return levels
    }

    private fun fft() {
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= size) {
            val step = size / len
            for (start in 0 until size step len) for (k in 0 until len / 2) {
                val wr = cosT[k * step]; val wi = sinT[k * step]
                val a = start + k; val b = a + len / 2
                val xr = re[b] * wr - im[b] * wi; val xi = re[b] * wi + im[b] * wr
                re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi
            }
            len = len shl 1
        }
    }
}

/** Off until the listener turns it on (owner, Q10). */
internal object VisualizerSettings {
    /** Bumped on each change, so the strip and the menu follow it. */
    var revision by androidx.compose.runtime.mutableIntStateOf(0); private set
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("visualizer", Context.MODE_PRIVATE)
    fun shown(context: Context) = prefs(context).getBoolean("shown", false)
    fun setShown(context: Context, on: Boolean) { prefs(context).edit().putBoolean("shown", on).apply(); revision++ }
}

/** At most this many pictures a second; bars fall back this fast when nothing new comes (paused or buffering). */
private const val VISUALIZER_FRAME_MS = 33L
private const val VISUALIZER_FALL = .85f

/** The strip of bars above the seek bar, while it is on and Now Playing is on screen. */
@Composable internal fun VisualizerStrip(playback: PlaybackGateway, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shown = remember(VisualizerSettings.revision) { VisualizerSettings.shown(context) }
    if (!shown) return
    val state = playback.state
    val motion = LocalMotionPolicy.current
    val strip = modifier.fillMaxWidth().height(36.dp).testTag("visualizer")
    if (state.remote) { Box(strip, contentAlignment = Alignment.Center) { Text(string(R.string.visualizer_cast), color = Muted, fontSize = RiffType.detail) }; return }
    if (motion.reduced) {
        // Reduced motion: a still picture, the same each time, so nothing moves.
        val still = remember { FloatArray(24) { i -> (.25f + .5f * sin(i / 23f * PI.toFloat()) * (.6f + .4f * cos(i * 1.7f))).coerceIn(.1f, .9f) } }
        Bars(still, strip.semantics { contentDescription = string(R.string.visualizer_still) }, .35f)
        return
    }
    var levels by remember { mutableStateOf(FloatArray(24)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        // Only while Now Playing is in front: off with the screen, in the background or when it is closed.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            VisualizerTap.start()
            val bars = SpectrumBars(); val window = FloatArray(bars.size); val shownLevels = FloatArray(bars.bands)
            var seen = -1L
            try {
                while (true) {
                    val fresh = VisualizerTap.written != seen
                    seen = VisualizerTap.written
                    val next = withContext(Dispatchers.Default) {
                        if (fresh && VisualizerTap.latest(window)) {
                            val target = bars.compute(window, VisualizerTap.sampleRate)
                            for (i in shownLevels.indices) shownLevels[i] = maxOf(target[i], shownLevels[i] * VISUALIZER_FALL)
                        } else for (i in shownLevels.indices) shownLevels[i] *= VISUALIZER_FALL
                        shownLevels.copyOf()
                    }
                    levels = next
                    delay(VISUALIZER_FRAME_MS)
                }
            } finally { VisualizerTap.stop() }
        }
    }
    Bars(levels, strip.semantics { contentDescription = string(R.string.visualizer_label) }, 1f)
}

@Composable private fun Bars(levels: FloatArray, modifier: Modifier, alpha: Float) {
    Canvas(modifier) {
        val gap = 3.dp.toPx(); val width = (size.width - gap * (levels.size - 1)) / levels.size
        levels.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(2.dp.toPx())
            drawRoundRect(Pink.copy(alpha = alpha * (.45f + .55f * level)), Offset(i * (width + gap), size.height - h), Size(width, h), CornerRadius(width / 2))
        }
    }
}
