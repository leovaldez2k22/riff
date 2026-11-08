package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Feature 20 (Riff+): the arithmetic around the Spleeter model, done exactly as Spleeter does it (spleeter/model/
 * __init__.py, v2.4): audio at 44.1 kHz in stereo; a short-time Fourier transform of 4,096-sample frames every 1,024
 * samples with a periodic Hann window, after 4,096 zeros put in front; the magnitude of the lowest 1,024 bins given to
 * the model 512 frames at a time; the accompaniment mask it returns spread over the bins above by its average
 * ("average" mask extension, so the instrumental keeps its highs); the masked transform turned back into audio with
 * tf.signal's inverse window; the 4,096 samples in front cut off. Plain arithmetic on arrays, tested on the JVM.
 */

internal const val SING_RATE = 44_100
internal const val SING_FRAME = 4_096
internal const val SING_HOP = 1_024
internal const val SING_BINS = SING_FRAME / 2 + 1
/** The bins the model sees, and how many frames it takes at once. */
internal const val SING_MODEL_BINS = 1_024
internal const val SING_MODEL_FRAMES = 512

/** The model: magnitudes of [SING_MODEL_FRAMES] × [SING_MODEL_BINS] × 2 channels in, the accompaniment's mask (0 to 1) out, same layout. */
internal fun interface SpectrogramMask { fun mask(magnitudes: FloatArray): FloatArray }

/**
 * Turns stereo 44.1 kHz audio, fed in pieces, into its instrumental through [model], handing finished audio to [out]
 * (interleaved stereo floats) as it is ready. [finish] when the song has ended.
 */
internal class SingSeparator(private val model: SpectrogramMask, private val out: (FloatArray, Int) -> Unit) {
    private val window = DoubleArray(SING_FRAME) { .5 - .5 * cos(2 * PI * it / SING_FRAME) }
    /** tf.signal.inverse_stft_window_fn: the window over the sum of squared windows that overlap each sample. */
    private val synthesis: DoubleArray = DoubleArray(SING_FRAME).also { s ->
        val denominator = DoubleArray(SING_HOP)
        for (i in 0 until SING_FRAME) denominator[i % SING_HOP] += window[i] * window[i]
        for (i in 0 until SING_FRAME) s[i] = window[i] / denominator[i % SING_HOP]
    }
    // Input: the samples of both channels from [inBase] on, with the 4,096 zeros Spleeter puts in front.
    private val input = arrayOf(FloatList(), FloatList())
    private var inBase = 0L
    private var nextFrame = 0L
    private var fed = 0L
    // Output: the overlap-add accumulator from [outBase] on; [cropped] samples of it are still to be dropped in front.
    private val acc = arrayOf(DoubleList(), DoubleList())
    private var outBase = 0L
    private var written = 0L
    // One chunk of frames: its complex transform for both channels, and the magnitudes the model reads.
    private val re = Array(2) { Array(SING_MODEL_FRAMES) { DoubleArray(SING_BINS) } }
    private val im = Array(2) { Array(SING_MODEL_FRAMES) { DoubleArray(SING_BINS) } }
    private val fftRe = DoubleArray(SING_FRAME); private val fftIm = DoubleArray(SING_FRAME)
    private var finished = false

    init { input.forEach { it.addZeros(SING_FRAME) } }

    /** Interleaved stereo samples at 44.1 kHz. */
    fun feed(samples: FloatArray, count: Int = samples.size) {
        check(!finished)
        require(count % 2 == 0) { "half a stereo frame" }
        var i = 0
        while (i + 1 < count) { input[0].add(samples[i]); input[1].add(samples[i + 1]); i += 2 }
        fed += count / 2
        while (available() >= SING_MODEL_FRAMES) chunk(SING_MODEL_FRAMES)
    }

    /** The song has ended: the last frames are padded with silence, run, and everything left is handed out. */
    fun finish() {
        if (finished) return
        finished = true
        // Every sample up to the end must be covered by every frame that overlaps it.
        val lastFrame = (SING_FRAME + fed - 1) / SING_HOP
        val needed = lastFrame * SING_HOP + SING_FRAME
        input.forEach { it.addZeros((needed - inBase - it.size).toInt().coerceAtLeast(0)) }
        while (nextFrame <= lastFrame) chunk(minOf(SING_MODEL_FRAMES.toLong(), lastFrame - nextFrame + 1).toInt())
        release(SING_FRAME + fed)
    }

    /** Whole frames that can be cut from the input now. */
    private fun available(): Int {
        val end = inBase + input[0].size
        return if (end < nextFrame * SING_HOP + SING_FRAME) 0 else ((end - SING_FRAME) / SING_HOP - nextFrame + 1).toInt()
    }

    private fun chunk(frames: Int) {
        val magnitudes = FloatArray(SING_MODEL_FRAMES * SING_MODEL_BINS * 2)
        for (f in 0 until frames) for (c in 0..1) {
            val start = ((nextFrame + f) * SING_HOP - inBase).toInt()
            val samples = input[c]
            for (n in 0 until SING_FRAME) { fftRe[n] = samples[start + n] * window[n]; fftIm[n] = 0.0 }
            fft(fftRe, fftIm)
            val r = re[c][f]; val m = im[c][f]
            for (k in 0 until SING_BINS) { r[k] = fftRe[k]; m[k] = fftIm[k] }
            for (k in 0 until SING_MODEL_BINS) magnitudes[(f * SING_MODEL_BINS + k) * 2 + c] = sqrt(r[k] * r[k] + m[k] * m[k]).toFloat()
        }
        val mask = model.mask(magnitudes)
        require(mask.size == magnitudes.size) { "model returned ${mask.size} values" }
        for (f in 0 until frames) for (c in 0..1) {
            var sum = 0.0
            for (k in 0 until SING_MODEL_BINS) sum += mask[(f * SING_MODEL_BINS + k) * 2 + c]
            val above = sum / SING_MODEL_BINS
            val r = re[c][f]; val m = im[c][f]
            for (k in 0 until SING_BINS) {
                val g = if (k < SING_MODEL_BINS) mask[(f * SING_MODEL_BINS + k) * 2 + c].toDouble() else above
                fftRe[k] = r[k] * g; fftIm[k] = m[k] * g
            }
            // The other half of the spectrum mirrors the first: a real signal's.
            for (k in SING_BINS until SING_FRAME) { fftRe[k] = fftRe[SING_FRAME - k]; fftIm[k] = -fftIm[SING_FRAME - k] }
            // Inverse FFT through the forward one: conjugate in, conjugate out, divide by N.
            for (k in 0 until SING_FRAME) fftIm[k] = -fftIm[k]
            fft(fftRe, fftIm)
            val start = (nextFrame + f) * SING_HOP
            val accumulator = acc[c]
            accumulator.ensure((start + SING_FRAME - outBase).toInt())
            for (n in 0 until SING_FRAME) accumulator.addAt((start - outBase).toInt() + n, fftRe[n] / SING_FRAME * synthesis[n])
        }
        nextFrame += frames
        // Input before the next frame is no longer needed; output before it is final.
        val keepFrom = nextFrame * SING_HOP
        input.forEach { it.dropFront((keepFrom - inBase).toInt()) }
        inBase = keepFrom
        release(minOf(keepFrom, SING_FRAME + fed))
    }

    /** Hands out the finished output up to song position [until] (in the padded timeline), less the padding in front. */
    private fun release(until: Long) {
        val from = maxOf(outBase, SING_FRAME.toLong())
        if (until > from) {
            val n = (until - from).toInt()
            val offset = (from - outBase).toInt()
            val pcm = FloatArray(n * 2)
            for (i in 0 until n) { pcm[2 * i] = acc[0][offset + i].toFloat(); pcm[2 * i + 1] = acc[1][offset + i].toFloat() }
            out(pcm, pcm.size); written += n
        }
        if (until > outBase) { acc.forEach { it.dropFront((until - outBase).toInt()) }; outBase = until }
    }
}

/**
 * A windowed-sinc resampler for interleaved audio, fed in pieces: a Blackman-windowed kernel 32 input samples wide
 * (wider, and cut lower, when going down), from a table of 256 phases with linear interpolation between them.
 */
internal class Resampler(private val from: Int, private val to: Int, private val channels: Int, private val out: (FloatArray, Int) -> Unit) {
    private val step = from.toDouble() / to
    private val cutoff = minOf(1.0, to.toDouble() / from) * .97
    private val half = (16 / minOf(1.0, to.toDouble() / from)).toInt()
    private val phases = 256
    private val table = DoubleArray(2 * half * phases + 1) { i ->
        val x = i.toDouble() / phases - half
        val w = x / half
        val blackman = .42 + .5 * cos(PI * w) + .08 * cos(2 * PI * w)
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
        cutoff * sinc * blackman
    }
    private val buffers = Array(channels) { FloatList() }
    private var base = 0L // input index of buffers[c][0]
    private var position = 0.0 // input position of the next output sample
    private var ended = false

    fun feed(samples: FloatArray, count: Int = samples.size) {
        if (from == to) { out(samples, count); return }
        var i = 0
        while (i + channels <= count) { for (c in 0 until channels) buffers[c].add(samples[i + c]); i += channels }
        produce()
    }

    fun finish() {
        if (from == to || ended) return
        ended = true
        val total = base + buffers[0].size
        buffers.forEach { it.addZeros(half + 1) }
        produce(limit = total)
    }

    private fun produce(limit: Long = Long.MAX_VALUE) {
        val end = base + buffers[0].size
        val outList = FloatList()
        while (position + half + 1 < end && position < limit) {
            val centre = floor(position).toLong()
            val frac = position - centre
            for (c in 0 until channels) {
                val buf = buffers[c]
                var sum = 0.0
                for (k in -half + 1..half) {
                    val index = centre + k - base
                    if (index < 0 || index >= buf.size) continue
                    val x = (frac - k + half) * phases
                    val j = x.toInt()
                    if (j < 0 || j + 1 >= table.size) continue
                    val t = table[j] + (table[j + 1] - table[j]) * (x - j)
                    sum += buf[index.toInt()] * t
                }
                outList.add(sum.toFloat())
            }
            position += step
        }
        if (outList.size > 0) out(outList.toArray(), outList.size)
        val drop = (floor(position).toLong() - half - base).coerceAtLeast(0)
        if (drop > 0) { buffers.forEach { it.dropFront(drop.toInt()) }; base += drop }
    }
}

/** A growable float array with cheap removal from the front. */
internal class FloatList {
    private var data = FloatArray(16_384); private var start = 0
    var size = 0; private set
    fun add(v: Float) { grow(1); data[start + size++] = v }
    fun addZeros(n: Int) { grow(n); java.util.Arrays.fill(data, start + size, start + size + n, 0f); size += n }
    operator fun get(i: Int) = data[start + i]
    fun dropFront(n: Int) { val k = n.coerceIn(0, size); start += k; size -= k }
    fun toArray(): FloatArray = data.copyOfRange(start, start + size)
    private fun grow(n: Int) {
        if (start + size + n <= data.size) return
        val next = if (size + n <= data.size / 2) data else FloatArray(maxOf(data.size * 2, size + n))
        System.arraycopy(data, start, next, 0, size); data = next; start = 0
    }
}

/** A growable double array with cheap removal from the front, read and added to by position. */
internal class DoubleList {
    private var data = DoubleArray(16_384); private var start = 0
    var size = 0; private set
    fun ensure(n: Int) {
        if (n <= size) return
        if (start + n > data.size) {
            val next = if (n <= data.size / 2) data else DoubleArray(maxOf(data.size * 2, n))
            System.arraycopy(data, start, next, 0, size); data = next; start = 0
        }
        java.util.Arrays.fill(data, start + size, start + n, 0.0); size = n
    }
    operator fun get(i: Int) = data[start + i]
    fun addAt(i: Int, v: Double) { data[start + i] += v }
    fun dropFront(n: Int) { val k = n.coerceIn(0, size); start += k; size -= k }
}
