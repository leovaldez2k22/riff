package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Feature 18 (Riff+), phase 7a: the key finder on synthesised music in all 24 keys. This is a reference result on
 * signals whose key is certain, not a measurement on recordings; that needs licensed reference files (plan, 7a).
 */
class SongKeyTest {
    private val rate = 44_100

    /** Notes (MIDI) as a tone with four harmonics, [seconds] long, added into [out] from [at] (stereo, 16-bit). */
    private fun chord(out: FloatArray, at: Int, seconds: Double, notes: List<Int>) {
        val frames = (seconds * rate).toInt()
        for (n in notes) {
            val hz = 440.0 * 2.0.pow((n - 69) / 12.0)
            for (i in 0 until frames) {
                val t = i.toDouble() / rate
                val fade = minOf(1.0, i / 2_000.0, (frames - i) / 2_000.0)
                var v = 0.0
                for (h in 1..4) v += sin(2 * PI * hz * h * t) / h
                out[at + i] += (v * fade * .06).toFloat()
            }
        }
    }

    /** I–IV–V–I (major) or i–iv–V–i (minor, harmonic V), with the root in the bass, 2 s a chord. */
    private fun progression(key: MusicalKey, noise: Double = .0): ShortArray {
        val root = 48 + key.tonic
        val third = if (key.minor) 3 else 4
        fun triad(r: Int, t: Int) = listOf(r - 12, r, r + t, r + 7, r + 12)
        val chords = listOf(triad(root, third), triad(root + 5, third), triad(root + 7, 4), triad(root, third))
        val mono = FloatArray(rate * 8)
        chords.forEachIndexed { i, c -> chord(mono, i * rate * 2, 2.0, c) }
        val random = Random(key.tonic * 2 + if (key.minor) 1 else 0)
        return ShortArray(mono.size * 2) { i ->
            val v = mono[i / 2] + (random.nextDouble(-1.0, 1.0) * noise).toFloat()
            (v.coerceIn(-1f, 1f) * 32_000).toInt().toShort()
        }
    }

    private fun find(pcm: ShortArray): KeyEstimate? {
        val builder = ChromaBuilder(rate, 2)
        pcm.toList().chunked(4_096).forEach { piece -> builder.add(piece.toShortArray()) }
        return estimateKey(builder.chroma())
    }

    @Test fun findsAll24KeysOfSynthesisedProgressions() {
        val keys = (0 until 24).map { MusicalKey(it % 12, it >= 12) }
        var score = 0.0; var exact = 0
        val misses = mutableListOf<String>()
        keys.forEach { truth ->
            val found = find(progression(truth, noise = .02))
            val s = found?.let { MusicalKey.mirexScore(it.key, truth) } ?: .0
            score += s; if (s == 1.0) exact++ else misses += "${truth.camelotLabel}→${found?.key?.camelotLabel}"
        }
        val weighted = score / keys.size
        println("7a key reference: exact $exact/24, MIREX weighted ${"%.3f".format(weighted)}, misses $misses")
        assertTrue("MIREX weighted score $weighted (plan: at least .65), misses $misses", weighted >= .9)
        assertTrue("exact $exact of 24, misses $misses", exact >= 22)
    }

    @Test fun silenceAndNoiseHaveNoUsableKey() {
        assertNull(find(ShortArray(rate * 2 * 4)))
        val random = Random(7)
        val noise = ShortArray(rate * 2 * 8) { (random.nextDouble(-1.0, 1.0) * 12_000).toInt().toShort() }
        assertFalse("white noise has no key", find(noise)?.usable == true)
    }

    @Test fun camelotWheelAndDistances() {
        assertEquals("8B", MusicalKey(0, false).camelotLabel) // C major
        assertEquals("8A", MusicalKey(9, true).camelotLabel) // A minor
        assertEquals("9B", MusicalKey(7, false).camelotLabel) // G major
        assertEquals("12A", MusicalKey(1, true).camelotLabel) // C♯ minor
        assertEquals(0, MusicalKey(0, false).distance(MusicalKey(0, false)))
        assertEquals("relative", 1, MusicalKey(0, false).distance(MusicalKey(9, true)))
        assertEquals("a fifth", 1, MusicalKey(0, false).distance(MusicalKey(7, false)))
        assertEquals("a tritone", 6, MusicalKey(0, false).distance(MusicalKey(6, false)))
        assertEquals(.5, MusicalKey.mirexScore(MusicalKey(7, false), MusicalKey(0, false)), 0.0)
        assertEquals(.3, MusicalKey.mirexScore(MusicalKey(9, true), MusicalKey(0, false)), 0.0)
        assertEquals(.2, MusicalKey.mirexScore(MusicalKey(0, true), MusicalKey(0, false)), 0.0)
    }

    @Test fun analysingAWindowIsCheapOnTheJvm() {
        // 30 s of stereo audio at 44.1 kHz through the chroma and the onset tracker, as a song's window is read.
        val pcm = ShortArray(rate * 2 * 30) { i -> (sin(i * .013) * 9_000 + sin(i * .0031) * 6_000).toInt().toShort() }
        val started = System.nanoTime()
        val chroma = ChromaBuilder(rate, 2); val onsets = OnsetBuilder(rate, 2, 0)
        var i = 0
        while (i < pcm.size) { val n = minOf(8_192, pcm.size - i); val piece = pcm.copyOfRange(i, i + n); chroma.add(piece, n); onsets.add(piece, n); i += n }
        estimateKey(chroma.chroma()); onsets.beats()
        val ms = (System.nanoTime() - started) / 1_000_000
        println("7a JVM reference: 30 s window analysed in $ms ms (decoding not included; phone time to be measured)")
        assertTrue("30 s analysed in $ms ms", ms < 10_000)
    }
}
