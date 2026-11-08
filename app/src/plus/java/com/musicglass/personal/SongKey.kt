package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Feature 18 (Riff+): a song's musical key from its audio, no model. The decoded window is taken to about 11 kHz, cut
 * into 0.74 s frames, and each frame's spectrum between C2 and C7 is folded into the twelve note classes (a chroma
 * vector, each frame weighted equally). The summed chroma is compared with the Krumhansl–Kessler key profiles for all 24
 * keys; the best correlation is the key, and its lead over the runner-up that is not the same key's relative is how
 * sure it is. Everything here is plain arithmetic on arrays, so it is tested on the JVM.
 */

private const val CHROMA_RATE = 11_025
private const val CHROMA_FRAME = 8_192
private const val CHROMA_HOP = 4_096
private const val LOWEST_HZ = 65.4 // C2
private const val HIGHEST_HZ = 2_093.0 // C7
/** A frame quieter than this (mean square, full scale 1) is silence and adds nothing. */
private const val SILENT_POWER = 1e-7

/** The best key's correlation must reach this, and lead by [KEY_MIN_LEAD], for the key to be used. */
internal const val KEY_MIN_CORRELATION = .5
internal const val KEY_MIN_LEAD = .02

private val NOTE_NAMES = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

/** A key: [tonic] 0 (C) to 11 (B), major or [minor]. */
internal data class MusicalKey(val tonic: Int, val minor: Boolean) {
    init { require(tonic in 0..11) }
    /** The Camelot wheel's number, 1 to 12: neighbours a fifth apart, a key and its relative share one. */
    val camelot: Int get() = ((if (minor) tonic + 3 else tonic) * 7 % 12 + 7) % 12 + 1
    /** "8A" (minor) or "8B" (major), the label DJs read. */
    val camelotLabel: String get() = "$camelot${if (minor) "A" else "B"}"
    /** "A minor", "E♭ major"; note names are the same in every language Riff ships. */
    val noteName: String get() = NOTE_NAMES[tonic]

    /** Steps apart on the Camelot wheel; changing between major and minor is one step more. 0 or 1 mix well. */
    fun distance(other: MusicalKey): Int {
        val d = abs(camelot - other.camelot).let { minOf(it, 12 - it) }
        return d + if (minor != other.minor) 1 else 0
    }

    companion object {
        /** The key a DJ order's score counts as the same (MIREX weights: same 1, fifth .5, relative .3, parallel .2). */
        fun mirexScore(found: MusicalKey, truth: MusicalKey): Double = when {
            found == truth -> 1.0
            found.minor == truth.minor && (found.tonic - truth.tonic + 12) % 12 in setOf(5, 7) -> .5
            found.minor != truth.minor && found.camelot == truth.camelot -> .3
            found.minor != truth.minor && found.tonic == truth.tonic -> .2
            else -> .0
        }
    }
}

/** A key found, with its correlation and its lead over the next key that is not its relative. */
internal data class KeyEstimate(val key: MusicalKey, val correlation: Double, val lead: Double) {
    val usable: Boolean get() = correlation >= KEY_MIN_CORRELATION && lead >= KEY_MIN_LEAD
}

/** Folds 16-bit PCM, fed in pieces as it is decoded, into the twelve note classes. */
internal class ChromaBuilder(sampleRate: Int, private val channels: Int) {
    private val step = sampleRate.toDouble() / CHROMA_RATE
    private var position = 0.0; private var acc = 0.0; private var accCount = 0
    private val frame = DoubleArray(CHROMA_FRAME); private var filled = 0
    private val window = DoubleArray(CHROMA_FRAME) { .5 - .5 * cos(2 * PI * it / CHROMA_FRAME) }
    private val re = DoubleArray(CHROMA_FRAME); private val im = DoubleArray(CHROMA_FRAME)
    private val sum = DoubleArray(12)
    /** Frames that were not silence. */
    var frames = 0; private set
    private val binClass: IntArray
    private val binWeight: DoubleArray

    init {
        val bins = CHROMA_FRAME / 2
        binClass = IntArray(bins) { -1 }; binWeight = DoubleArray(bins)
        for (k in 1 until bins) {
            val hz = k.toDouble() * CHROMA_RATE / CHROMA_FRAME
            if (hz < LOWEST_HZ || hz > HIGHEST_HZ) continue
            val midi = 69 + 12 * ln(hz / 440.0) / ln(2.0)
            val nearest = midi.roundToInt()
            binClass[k] = Math.floorMod(nearest, 12)
            // A bin between two notes counts less: cos² of its distance from the note, zero half a semitone away.
            binWeight[k] = cos(PI * (midi - nearest)).let { it * it }
        }
    }

    fun add(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i + channels <= count) {
            var mono = 0.0
            for (c in 0 until channels) mono += samples[i + c] / 32768.0
            i += channels
            acc += mono / channels; accCount++
            position += 1.0
            if (position >= step) {
                position -= step
                push(acc / accCount); acc = 0.0; accCount = 0
            }
        }
    }

    private fun push(sample: Double) {
        frame[filled++] = sample
        if (filled < CHROMA_FRAME) return
        analyse()
        System.arraycopy(frame, CHROMA_HOP, frame, 0, CHROMA_FRAME - CHROMA_HOP)
        filled = CHROMA_FRAME - CHROMA_HOP
    }

    private fun analyse() {
        var power = 0.0
        for (n in 0 until CHROMA_FRAME) { power += frame[n] * frame[n]; re[n] = frame[n] * window[n]; im[n] = 0.0 }
        if (power / CHROMA_FRAME < SILENT_POWER) return
        fft(re, im)
        val chroma = DoubleArray(12)
        for (k in binClass.indices) {
            val c = binClass[k]; if (c < 0) continue
            chroma[c] += binWeight[k] * sqrt(re[k] * re[k] + im[k] * im[k])
        }
        val total = chroma.sum()
        if (total <= 0) return
        for (c in 0 until 12) sum[c] += chroma[c] / total
        frames++
    }

    /** The summed chroma, C first; zeros when nothing but silence came in. */
    fun chroma(): DoubleArray = sum.copyOf()
}

private fun correlation(a: DoubleArray, b: DoubleArray, shift: Int): Double {
    val ma = a.average(); val mb = b.average()
    var num = 0.0; var da = 0.0; var db = 0.0
    for (i in 0 until 12) {
        val x = a[(i + shift) % 12] - ma; val y = b[i] - mb
        num += x * y; da += x * x; db += y * y
    }
    return if (da <= 0 || db <= 0) 0.0 else num / sqrt(da * db)
}

/** The key whose profile [chroma] matches best, or null for a chroma with no shape (silence, or noise). */
internal fun estimateKey(chroma: DoubleArray): KeyEstimate? {
    if (chroma.size != 12 || chroma.all { it <= 0 }) return null
    val scores = (0 until 24).map { k ->
        val key = MusicalKey(k % 12, k >= 12)
        key to correlation(chroma, if (key.minor) MINOR_PROFILE else MAJOR_PROFILE, key.tonic)
    }.sortedByDescending { it.second }
    val (best, score) = scores.first()
    // A key and its relative share their notes; the lead is over the best key that is not that pair.
    val rival = scores.drop(1).firstOrNull { it.first.camelot != best.camelot }?.second ?: return null
    return KeyEstimate(best, score, score - rival)
}
