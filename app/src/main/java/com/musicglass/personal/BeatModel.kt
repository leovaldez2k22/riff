package com.musicglass.personal

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Automix stage 4 (owner, 30 Oct): the beats from Beat This! small0 (CPJKU, ISMIR 2024; code and weights MIT), the
 * model that met every target on the owner's 50 songs where stage 2's own tracker did not
 * (docs/automix-stage4-groundwork-2026-10-30.md). It is run as the PC ran it for those measurements: the window
 * resampled to 22,050 Hz, Beat This!'s log-mel, chunks of 1,500 frames with 6 frames of border, and its "minimal"
 * peak picking, with no DBN. The int8 model ships in the APK (owner's decision 1) and runs on ONNX Runtime as
 * published (decision 2), for now in debug builds only: a release build has no runtime, finds no model, and keeps
 * stage 2's tracker.
 */

internal const val MODEL_RATE = 22_050
internal const val MODEL_FFT = 1_024
internal const val MODEL_HOP = 441
internal const val MODEL_BANDS = 128
/** One frame: 441 samples at 22,050 Hz, 50 a second. */
internal const val MODEL_FRAME_MS = 20.0
internal const val MODEL_CHUNK = 1_500
internal const val MODEL_BORDER = 6
private const val MEL_LOW_HZ = 30.0
private const val MEL_HIGH_HZ = 11_000.0
private const val LOG_MULTIPLIER = 1_000.0
/** How far either side of a peak another may not be higher: Beat This!'s max-pool of 7 frames. */
private const val PEAK_REACH = 3
/** Beats this far (as a share of the usual gap) from the usual gap still count as steady. */
private const val STEADY_SHARE = .15
private const val FEWEST_BEATS = 4

/** The model: [spect] is [frames] rows of 128 bands; gives the beat and the downbeat logit of each frame. */
internal fun interface BeatLogits {
    fun run(spect: FloatArray, frames: Int): Pair<FloatArray, FloatArray>
}

/** The beats in [mono] (one channel at [rate] Hz, starting at [startMs] of the song) by [model], or null with no pulse. */
internal fun modelBeats(mono: FloatArray, rate: Int, startMs: Long, model: BeatLogits): BeatGrid? {
    val spect = logMel(resample(mono, rate, MODEL_RATE))
    val frames = spect.size / MODEL_BANDS
    if (frames < MODEL_BORDER * 4) return null
    val (beat, downbeat) = predictChunks(spect, frames, model)
    return gridFromLogits(beat, downbeat, startMs)
}

/**
 * Resampled with a windowed sinc (Blackman), its cut-off just under the lower of the two Nyquists. The PC used soxr;
 * the difference is above 10 kHz, where the model's top bands are.
 */
internal fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || x.isEmpty()) return x
    val g = gcd(from, to); val up = to / g; val down = from / g
    val cutoff = minOf(1.0, to.toDouble() / from) * .95
    val half = (16 / cutoff).toInt() + 1
    val outSize = (x.size.toLong() * up / down).toInt()
    // One row of taps for each of the [up] places an output sample can fall between two input samples.
    val phases = up.coerceAtMost(4_096)
    val taps = Array(phases) { p ->
        val frac = p.toDouble() / phases
        FloatArray(2 * half) { j ->
            val d = (j - half + 1) - frac // input sample minus output position
            val w = abs(d) / half
            if (w >= 1) 0f else (cutoff * sinc(cutoff * d) * (.42 + .5 * cos(PI * w) + .08 * cos(2 * PI * w))).toFloat()
        }
    }
    return FloatArray(outSize) { n ->
        val t = n.toLong() * down
        val base = (t / up).toInt()
        val row = taps[(((t % up) * phases) / up).toInt()]
        var sum = 0f
        val first = base - half + 1
        for (j in row.indices) { val i = first + j; if (i >= 0 && i < x.size) sum += x[i] * row[j] }
        sum
    }
}

private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** Mel filters as torchaudio makes them (Slaney scale, unnormalised), each as its first bin and its weights. */
private object MelBank {
    private fun hzToMel(hz: Double) = if (hz < 1_000) hz / (200.0 / 3) else 15 + ln(hz / 1_000) / (ln(6.4) / 27)
    private fun melToHz(mel: Double) = if (mel < 15) mel * (200.0 / 3) else 1_000 * exp(ln(6.4) / 27 * (mel - 15))
    val first = IntArray(MODEL_BANDS)
    val weights: Array<FloatArray>
    init {
        val bins = MODEL_FFT / 2 + 1
        val freqs = DoubleArray(bins) { it * (MODEL_RATE / 2).toDouble() / (bins - 1) }
        val low = hzToMel(MEL_LOW_HZ); val high = hzToMel(MEL_HIGH_HZ)
        val points = DoubleArray(MODEL_BANDS + 2) { melToHz(low + (high - low) * it / (MODEL_BANDS + 1)) }
        weights = Array(MODEL_BANDS) { m ->
            val w = DoubleArray(bins) { k ->
                val down = (freqs[k] - points[m]) / (points[m + 1] - points[m])
                val up = (points[m + 2] - freqs[k]) / (points[m + 2] - points[m + 1])
                maxOf(0.0, minOf(down, up))
            }
            val from = w.indexOfFirst { it > 0 }.coerceAtLeast(0); val to = w.indexOfLast { it > 0 }.coerceAtLeast(from)
            first[m] = from
            FloatArray(to - from + 1) { w[from + it].toFloat() }
        }
    }
}

/**
 * Beat This!'s log-mel: frames of 1,024 centred every 441 samples (reflected at the ends), a periodic Hann window,
 * magnitudes divided by the square root of 1,024 (torchaudio's "frame_length" normalising, which torch.stft does by
 * the frame's length, not the window's energy), 128 mel bands from 30 Hz to 11 kHz, then log(1 + 1000·x). Row by row.
 */
internal fun logMel(x: FloatArray): FloatArray {
    if (x.isEmpty()) return FloatArray(0)
    val pad = MODEL_FFT / 2
    val frames = 1 + x.size / MODEL_HOP
    val window = DoubleArray(MODEL_FFT) { .5 - .5 * cos(2 * PI * it / MODEL_FFT) }
    val scale = 1 / sqrt(MODEL_FFT.toDouble())
    val re = DoubleArray(MODEL_FFT); val im = DoubleArray(MODEL_FFT)
    val magnitude = FloatArray(MODEL_FFT / 2 + 1)
    val out = FloatArray(frames * MODEL_BANDS)
    fun at(i: Int): Float {
        // Reflected padding, as torch.stft's "reflect": the edge sample is not repeated.
        var j = i
        if (x.size == 1) return x[0]
        while (j < 0 || j >= x.size) j = if (j < 0) -j else 2 * (x.size - 1) - j
        return x[j]
    }
    for (f in 0 until frames) {
        val start = f * MODEL_HOP - pad
        for (k in 0 until MODEL_FFT) { re[k] = at(start + k) * window[k]; im[k] = 0.0 }
        fft(re, im)
        for (k in magnitude.indices) magnitude[k] = (sqrt(re[k] * re[k] + im[k] * im[k]) * scale).toFloat()
        for (m in 0 until MODEL_BANDS) {
            val w = MelBank.weights[m]; val from = MelBank.first[m]
            var sum = 0.0
            for (k in w.indices) sum += w[k] * magnitude[from + k]
            out[f * MODEL_BANDS + m] = ln1p(LOG_MULTIPLIER * sum).toFloat()
        }
    }
    return out
}

/**
 * Beat This!'s split_predict_aggregate: chunks of 1,500 frames overlapping by the border, the first and last padded
 * with silence, the last moved back to end with the piece; each chunk's border is dropped, and where chunks overlap
 * the earlier one is kept.
 */
internal fun predictChunks(spect: FloatArray, frames: Int, model: BeatLogits): Pair<FloatArray, FloatArray> {
    val step = MODEL_CHUNK - 2 * MODEL_BORDER
    val starts = generateSequence(-MODEL_BORDER) { it + step }.takeWhile { it < frames - MODEL_BORDER }.toMutableList()
    if (frames > step) starts[starts.lastIndex] = frames - (MODEL_CHUNK - MODEL_BORDER)
    val beat = FloatArray(frames) { -1_000f }; val downbeat = FloatArray(frames) { -1_000f }
    for (start in starts.asReversed()) {
        val from = maxOf(start, 0); val to = minOf(start + MODEL_CHUNK, frames)
        val left = maxOf(0, -start); val right = maxOf(0, minOf(MODEL_BORDER, start + MODEL_CHUNK - frames))
        val length = left + (to - from) + right
        val chunk = FloatArray(length * MODEL_BANDS)
        System.arraycopy(spect, from * MODEL_BANDS, chunk, left * MODEL_BANDS, (to - from) * MODEL_BANDS)
        val (b, d) = model.run(chunk, length)
        for (i in MODEL_BORDER until length - MODEL_BORDER) {
            val at = start + i
            if (at in 0 until frames) { beat[at] = b[i]; downbeat[at] = d[i] }
        }
    }
    return beat to downbeat
}

/** Beat This!'s "minimal" peaks: frames that are the highest within 3 either side and more likely than not. */
internal fun pickPeaks(logits: FloatArray): DoubleArray {
    val peaks = ArrayList<Int>()
    for (i in logits.indices) {
        if (logits[i] <= 0f) continue
        var top = true
        for (j in maxOf(0, i - PEAK_REACH)..minOf(logits.lastIndex, i + PEAK_REACH)) if (logits[j] > logits[i]) { top = false; break }
        if (top) peaks += i
    }
    // Neighbouring peaks (a tie) become one at their average.
    val merged = ArrayList<Double>()
    var p = 0.0; var count = 0; var last = Int.MIN_VALUE
    for (q in peaks) {
        if (count > 0 && q - last <= 1) { count++; p += (q - p) / count }
        else { if (count > 0) merged += p; p = q.toDouble(); count = 1 }
        last = q
    }
    if (count > 0) merged += p
    return merged.toDoubleArray()
}

/**
 * The grid from the logits: beats at their peaks, each downbeat moved to the nearest beat. How sure: the beats' mean
 * likelihood times how steady they are; the downbeats' likewise, steady meaning a whole number of bars apart.
 */
internal fun gridFromLogits(beat: FloatArray, downbeat: FloatArray, startMs: Long): BeatGrid? {
    val beatFrames = pickPeaks(beat)
    if (beatFrames.size < FEWEST_BEATS) return null
    val beatsMs = LongArray(beatFrames.size) { startMs + (beatFrames[it] * MODEL_FRAME_MS).roundToInt() }
    val downFrames = pickPeaks(downbeat)
    val downbeatsMs = downFrames.map { d -> beatsMs[beatFrames.indices.minBy { abs(beatFrames[it] - d) }] }.distinct().toLongArray()
    val gaps = (1 until beatsMs.size).map { (beatsMs[it] - beatsMs[it - 1]).toDouble() }.sorted()
    val usual = gaps[gaps.size / 2]
    if (usual <= 0) return null
    val steady = gaps.count { abs(it - usual) <= usual * STEADY_SHARE }.toFloat() / gaps.size
    val sure = beatFrames.map { sigmoid(beat[it.roundToInt()]) }.average().toFloat()
    val downSure = if (downbeatsMs.size < 2) 0f else {
        val bars = (1 until downbeatsMs.size).map { (downbeatsMs[it] - downbeatsMs[it - 1]).toDouble() }
        val bar = bars.sorted()[bars.size / 2]
        val whole = bars.count { abs(it / bar - floor(it / bar + .5)) <= STEADY_SHARE }.toFloat() / bars.size
        downFrames.map { sigmoid(downbeat[it.roundToInt()]) }.average().toFloat() * whole
    }
    return BeatGrid(60_000 / usual, beatsMs, downbeatsMs, sure * steady, downSure)
}

private fun sigmoid(x: Float) = (1 / (1 + exp(-x.toDouble()))).toFloat()

/**
 * The model on the phone, when this build has ONNX Runtime and the model file (debug builds, for now). Loaded once,
 * on first use, off the main thread; null when either is missing or the runtime cannot load (a 32-bit phone).
 */
internal object BeatModel {
    internal const val ASSET = "automix/beat-this-small0-int8.onnx"
    @Volatile private var model: BeatLogits? = null
    @Volatile private var failed = false

    /** Whether this build carries the model and its runtime, without loading either. */
    fun present(context: Context): Boolean =
        runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isSuccess &&
            runCatching { context.assets.open(ASSET).close() }.isSuccess

    fun get(context: Context): BeatLogits? {
        model?.let { return it }
        if (failed) return null
        return synchronized(this) {
            model ?: run {
                val loaded = if (present(context)) runCatching { OrtBeatLogits(context.assets.open(ASSET).use { it.readBytes() }) }.getOrNull() else null
                if (loaded == null) failed = true
                model = loaded
                loaded
            }
        }
    }
}
