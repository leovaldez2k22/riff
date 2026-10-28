package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Automix stage 2 (engine report, section 2.2): beats found by signal analysis, no model. Onset strength is the
 * spectral flux of the decoded audio at about 11 kHz; the tempo is the strongest autocorrelation lag between 60 and
 * 200 BPM, leaning towards 120; beats are placed by dynamic programming (Ellis, 2007); the downbeat is the phase of
 * four whose beats carry the most bass. Everything here is plain arithmetic on arrays, so it is tested on the JVM.
 */

/** The rate the analysis runs at, and its frame and hop in samples at that rate. */
internal const val ONSET_RATE = 11_025
private const val ONSET_FRAME = 512
internal const val ONSET_HOP = 128
internal const val ONSET_HOP_MS = ONSET_HOP * 1_000.0 / ONSET_RATE
private const val SLOWEST_BPM = 60.0
private const val FASTEST_BPM = 200.0
/** The tempo prior: a log-normal around 120 BPM, one octave wide (Ellis's choice). */
private const val PRIOR_BPM = 120.0
private const val PRIOR_OCTAVES = 1.0
/** How hard the dynamic programme holds the beat to the tempo; Ellis uses 100 at his rate, about the same here. */
private const val TIGHTNESS = 100.0
/** Below 150 Hz: where the kick and the bass are, for the downbeat. */
private const val LOW_HZ = 150.0

/** Beats in a stretch of a song: [beatsMs] and [downbeatsMs] in song time, with how sure each is (0 to 1). */
internal class BeatGrid(val bpm: Double, val beatsMs: LongArray, val downbeatsMs: LongArray, val confidence: Float, val downbeatConfidence: Float) {
    val beatMs: Double get() = 60_000.0 / bpm
}

/** Builds the onset strength and the bass per hop from 16-bit PCM, fed in pieces as it is decoded. */
internal class OnsetBuilder(sampleRate: Int, private val channels: Int, private val startMs: Long) {
    private val step = (sampleRate.toDouble() / ONSET_RATE)
    private var position = 0.0; private var acc = 0f; private var accCount = 0
    private val ring = FloatArray(ONSET_FRAME); private var head = 0; private var filled = 0; private var sinceHop = 0
    private val window = FloatArray(ONSET_FRAME) { (0.5 - 0.5 * cos(2 * PI * it / ONSET_FRAME)).toFloat() }
    private val re = DoubleArray(ONSET_FRAME); private val im = DoubleArray(ONSET_FRAME)
    private var previous: FloatArray? = null
    private val lowBins = (LOW_HZ * ONSET_FRAME / ONSET_RATE).roundToInt().coerceAtLeast(2)
    private val flux = ArrayList<Float>(); private val low = ArrayList<Float>()

    fun add(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i + channels <= count) {
            var mono = 0f
            for (c in 0 until channels) mono += samples[i + c] / 32768f
            i += channels
            // Averaging the frames that fall in one output sample: a plain low-pass before taking every nth.
            acc += mono / channels; accCount++
            position += 1.0
            if (position >= step) { position -= step; push(acc / accCount); acc = 0f; accCount = 0 }
        }
    }

    private fun push(sample: Float) {
        // A circular buffer: [head] is the oldest sample once it is full.
        ring[head] = sample; head = (head + 1) % ONSET_FRAME
        if (filled < ONSET_FRAME) filled++
        if (++sinceHop < ONSET_HOP || filled < ONSET_FRAME) return
        sinceHop = 0
        for (k in 0 until ONSET_FRAME) { re[k] = (ring[(head + k) % ONSET_FRAME] * window[k]).toDouble(); im[k] = 0.0 }
        fft(re, im)
        val half = ONSET_FRAME / 2
        val magnitude = FloatArray(half) { k -> ln(1 + 100 * sqrt(re[k] * re[k] + im[k] * im[k])).toFloat() }
        val before = previous
        var rise = 0f
        if (before != null) for (k in 1 until half) { val d = magnitude[k] - before[k]; if (d > 0) rise += d }
        var bass = 0f
        for (k in 1..lowBins) bass += magnitude[k]
        flux += rise; low += bass
        previous = magnitude
    }

    /** The beats in what was fed, or null when there is too little of it or no steady pulse. */
    fun beats(): BeatGrid? = trackBeats(flux.toFloatArray(), low.toFloatArray(), startMs + (ONSET_FRAME / 2 * 1_000L / ONSET_RATE))
}

/** In-place radix-2 FFT; [re] and [im] have a power-of-two length. */
internal fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
    }
    var length = 2
    while (length <= n) {
        val angle = -2 * PI / length
        val wr = cos(angle); val wi = sin(angle)
        var start = 0
        while (start < n) {
            var cr = 1.0; var ci = 0.0
            for (k in 0 until length / 2) {
                val a = start + k; val b = a + length / 2
                val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                val next = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = next
            }
            start += length
        }
        length = length shl 1
    }
}

/**
 * Beats from an onset strength [onset] (one value per hop of [ONSET_HOP_MS]) and the bass per hop [low]; [originMs] is
 * the song time of the first hop. Null with under 8 s of audio, or when nothing repeats.
 */
internal fun trackBeats(onset: FloatArray, low: FloatArray, originMs: Long): BeatGrid? {
    val hops = onset.size
    if (hops * ONSET_HOP_MS < 8_000) return null
    // Remove the slow trend and keep only rises, then normalise: loud and quiet songs score alike.
    val smooth = movingAverage(onset, (300 / ONSET_HOP_MS).roundToInt())
    val strength = FloatArray(hops) { (onset[it] - smooth[it]).coerceAtLeast(0f) }
    val sd = sqrt(strength.map { it.toDouble() * it }.average()).toFloat()
    if (sd <= 1e-6f) return null
    for (i in strength.indices) strength[i] /= sd

    // Tempo: the autocorrelation lag with the best weighted score in 60–200 BPM.
    val minLag = (60_000 / FASTEST_BPM / ONSET_HOP_MS).toInt(); val maxLag = (60_000 / SLOWEST_BPM / ONSET_HOP_MS).toInt().coerceAtMost(hops / 2)
    if (maxLag <= minLag) return null
    val ac = DoubleArray(maxLag + 1)
    for (lag in minLag..maxLag) { var s = 0.0; for (t in lag until hops) s += strength[t] * strength[t - lag]; ac[lag] = s / (hops - lag) }
    fun weight(lag: Int): Double { val bpm = 60_000 / (lag * ONSET_HOP_MS); val o = ln(bpm / PRIOR_BPM) / ln(2.0) / PRIOR_OCTAVES; return exp(-0.5 * o * o) }
    // A beat lag also scores where its double and half land (half and double time), as Ellis's tempo picker does.
    fun score(lag: Int) = weight(lag) * (ac[lag] + 0.5 * (ac.getOrElse(lag * 2) { 0.0 }) + 0.25 * (if (lag / 2 >= minLag) ac[lag / 2] else 0.0))
    val best = (minLag..maxLag).maxByOrNull(::score) ?: return null
    // Refine to a fraction of a hop with a parabola through the peak.
    val period = if (best in (minLag + 1) until maxLag) {
        val a = ac[best - 1]; val b = ac[best]; val c = ac[best + 1]; val d = a - 2 * b + c
        best + if (d < 0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
    } else best.toDouble()
    val acMean = (minLag..maxLag).map { ac[it] }.average().coerceAtLeast(1e-9)
    val peakRatio = ac[best] / acMean

    // Beats by dynamic programming: each hop's best score is its strength plus the best earlier beat a period away.
    val total = DoubleArray(hops); val back = IntArray(hops) { -1 }
    for (t in 0 until hops) {
        var bestPrev = -1; var bestScore = Double.NEGATIVE_INFINITY
        val from = (t - (2 * period).roundToInt()).coerceAtLeast(0); val to = t - (period / 2).roundToInt()
        for (p in from..to) {
            val gap = ln((t - p) / period)
            val s = total[p] - TIGHTNESS * gap * gap
            if (s > bestScore) { bestScore = s; bestPrev = p }
        }
        total[t] = strength[t] + if (bestPrev >= 0 && bestScore > 0) bestScore else 0.0
        back[t] = if (bestPrev >= 0 && bestScore > 0) bestPrev else -1
    }
    // The last beat: the best score within the last period.
    var last = ((hops - period.roundToInt()).coerceAtLeast(0) until hops).maxByOrNull { total[it] } ?: return null
    val beats = ArrayList<Int>()
    while (last >= 0) { beats += last; last = back[last] }
    beats.reverse()
    if (beats.size < 8) return null

    // How steady: the spread of the gaps between beats, and how far the tempo's peak stands out.
    val gaps = beats.zipWithNext { a, b -> (b - a).toDouble() }
    val mean = gaps.average(); val spread = sqrt(gaps.map { (it - mean) * (it - mean) }.average()) / mean
    val steadiness = (1 - spread / 0.1).coerceIn(0.0, 1.0)
    val salience = ((peakRatio - 1) / 3).coerceIn(0.0, 1.0)
    val confidence = (steadiness * salience).toFloat()

    // Downbeats: of the four phases, the one whose beats carry the most bass.
    val phaseBass = DoubleArray(4)
    val phaseCount = IntArray(4)
    beats.forEachIndexed { i, b -> phaseBass[i % 4] += low.getOrElse(b) { 0f }; phaseCount[i % 4]++ }
    for (p in 0 until 4) if (phaseCount[p] > 0) phaseBass[p] /= phaseCount[p]
    val order = (0 until 4).sortedByDescending { phaseBass[it] }
    val phase = order[0]
    val margin = if (phaseBass[order[0]] <= 0) 0.0 else (phaseBass[order[0]] - phaseBass[order[1]]) / phaseBass[order[0]]
    val downbeatConfidence = ((margin / 0.15).coerceIn(0.0, 1.0) * confidence).toFloat()

    val beatsMs = LongArray(beats.size) { originMs + (beats[it] * ONSET_HOP_MS).roundToLong() }
    val downbeatsMs = beatsMs.filterIndexed { i, _ -> i % 4 == phase }.toLongArray()
    return BeatGrid(60_000 / (period * ONSET_HOP_MS), beatsMs, downbeatsMs, confidence, downbeatConfidence)
}

private fun Double.roundToLong() = kotlin.math.round(this).toLong()

private fun movingAverage(values: FloatArray, width: Int): FloatArray {
    val half = (width / 2).coerceAtLeast(1)
    val prefix = DoubleArray(values.size + 1)
    for (i in values.indices) prefix[i + 1] = prefix[i] + values[i]
    return FloatArray(values.size) { i ->
        val a = (i - half).coerceAtLeast(0); val b = (i + half + 1).coerceAtMost(values.size)
        ((prefix[b] - prefix[a]) / (b - a)).toFloat()
    }
}

/** Two songs' tempos compared after folding by 2, 1/2, 3/2 and 2/3: how far apart they are (0.04 is 4 %). */
internal fun tempoGap(a: Double, b: Double): Double =
    listOf(1.0, 2.0, 0.5, 1.5, 2.0 / 3).minOf { abs(b / a * it - 1) }
