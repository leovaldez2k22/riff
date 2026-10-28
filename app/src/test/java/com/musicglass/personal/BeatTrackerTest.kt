package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Automix stage 2: tempo, beats and downbeats on synthetic drums whose beats are known. */
class BeatTrackerTest {
    private val rate = 44_100

    /**
     * [seconds] of stereo 16-bit PCM at [bpm]: a kick (60 Hz, decaying) on each bar's first beat, a snare-like noise
     * burst on the others, quiet noise under it all. The first downbeat is at [firstMs].
     */
    private fun drums(bpm: Double, seconds: Int, firstMs: Double = 250.0, seed: Int = 1): ShortArray {
        val random = Random(seed)
        val frames = rate * seconds
        val mono = FloatArray(frames) { (random.nextFloat() - .5f) * .01f }
        val beat = 60.0 / bpm
        var n = 0
        while (true) {
            val at = firstMs / 1000 + n * beat
            val start = (at * rate).toInt(); if (start >= frames) break
            val kick = n % 4 == 0
            for (i in 0 until (rate * .18).toInt()) {
                val f = start + i; if (f >= frames) break
                val t = i.toDouble() / rate
                mono[f] += if (kick) (.8 * sin(2 * PI * 60 * t) * exp(-t * 18)).toFloat()
                else ((random.nextFloat() - .5f) * .6f * exp(-t * 40).toFloat())
            }
            n++
        }
        return ShortArray(frames * 2) { (mono[it / 2].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
    }

    private fun grid(samples: ShortArray, startMs: Long = 0): BeatGrid? =
        OnsetBuilder(rate, 2, startMs).apply { samples.toList().chunked(8192).forEach { add(it.toShortArray()) } }.beats()

    private fun assertBeats(bpm: Double) {
        val beats = grid(drums(bpm, 30))
        assertNotNull("$bpm BPM found", beats); beats!!
        assertTrue("$bpm BPM read as ${beats.bpm}", abs(beats.bpm / bpm - 1) < .02)
        // Every tracked beat within 70 ms of a true one (the beat F-measure's window).
        val beat = 60_000 / bpm
        val off = beats.beatsMs.count { ms -> val k = Math.round((ms - 250) / beat); abs(ms - (250 + k * beat)) > 70 }
        assertTrue("$off of ${beats.beatsMs.size} beats off at $bpm BPM", off <= beats.beatsMs.size / 10)
        // Downbeats fall on the kicks.
        val bar = beat * 4
        val wrong = beats.downbeatsMs.count { ms -> val k = Math.round((ms - 250) / bar); abs(ms - (250 + k * bar)) > 70 }
        assertTrue("$wrong of ${beats.downbeatsMs.size} downbeats off at $bpm BPM", wrong <= beats.downbeatsMs.size / 10)
        assertTrue("confident at $bpm BPM: ${beats.confidence}", beats.confidence >= .6f)
        assertTrue("downbeats confident at $bpm BPM: ${beats.downbeatConfidence}", beats.downbeatConfidence >= .3f)
    }

    @Test fun steadyDrumsAtCommonTempos() { for (bpm in listOf(92.0, 120.0, 128.0, 140.0, 174.0 / 2)) assertBeats(bpm) }

    @Test fun beatsAreInSongTime() {
        val beats = grid(drums(120.0, 20), startMs = 180_000)!!
        assertTrue(beats.beatsMs.first() >= 180_000)
    }

    @Test fun noiseHasNoConfidentPulse() {
        val random = Random(7)
        val noise = ShortArray(rate * 20 * 2) { ((random.nextFloat() - .5f) * 8000).toInt().toShort() }
        val beats = grid(noise)
        assertTrue("noise read with confidence ${beats?.confidence}", beats == null || beats.confidence < .35f)
    }

    @Test fun tooLittleAudioHasNoBeats() = assertEquals(null, grid(drums(120.0, 5)))

    @Test fun tempoGapFoldsOctavesAndTriplets() {
        assertEquals(0.0, tempoGap(87.0, 174.0), 1e-9)
        assertEquals(0.0, tempoGap(120.0, 180.0), 1e-9)
        assertEquals(128.0 / 124 - 1, tempoGap(124.0, 128.0), 1e-9)
    }

    @Test fun fftFindsATone() {
        val n = 64; val re = DoubleArray(n) { sin(2 * PI * 5 * it / n) }; val im = DoubleArray(n)
        fft(re, im)
        val peak = (0 until n / 2).maxByOrNull { re[it] * re[it] + im[it] * im[it] }
        assertEquals(5, peak)
    }
}
