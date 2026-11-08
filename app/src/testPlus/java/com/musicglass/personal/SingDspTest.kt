package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Feature 20 (Riff+): the transform around the model gives back exactly what went in, and the resampler is clean. */
class SingDspTest {
    private fun stereo(seconds: Double, rate: Int = SING_RATE): FloatArray {
        val random = Random(3)
        val n = (seconds * rate).toInt()
        return FloatArray(n * 2) { i ->
            val t = (i / 2).toDouble() / rate
            (if (i % 2 == 0) .3 * sin(2 * PI * 440 * t) + .1 * sin(2 * PI * 3_000 * t) else .25 * sin(2 * PI * 660 * t)).toFloat() +
                (random.nextDouble(-.05, .05)).toFloat()
        }
    }

    private fun separate(input: FloatArray, piece: Int, model: SpectrogramMask): FloatArray {
        val out = FloatList()
        val separator = SingSeparator(model) { pcm, n -> for (i in 0 until n) out.add(pcm[i]) }
        var i = 0
        while (i < input.size) { val n = minOf(piece, input.size - i); separator.feed(input.copyOfRange(i, i + n)); i += n }
        separator.finish()
        return out.toArray()
    }

    @Test fun aMaskOfOnesGivesBackTheSongAcrossChunks() {
        // 30 s: more than two chunks of 512 frames, fed in uneven pieces.
        val input = stereo(30.0)
        val out = separate(input, 7_778) { FloatArray(it.size) { 1f } }
        assertEquals("same length", input.size, out.size)
        var worst = 0.0
        for (i in input.indices) worst = maxOf(worst, abs((input[i] - out[i]).toDouble()))
        assertTrue("reconstruction error $worst", worst < 1e-4)
    }

    @Test fun aMaskOfZerosIsSilence() {
        val out = separate(stereo(3.0), 44_100) { FloatArray(it.size) }
        assertEquals(stereo(3.0).size, out.size)
        assertTrue(out.all { abs(it) < 1e-6 })
    }

    @Test fun theModelSeesMagnitudesOfTheLowestBinsInItsLayout() {
        // A 1 kHz tone in the left channel only: bin 1000 / 44100 * 4096 ≈ 92.9, nothing in the right channel.
        val n = SING_RATE * 4
        val input = FloatArray(n * 2) { i -> if (i % 2 == 0) (.5 * sin(2 * PI * 1_000 * (i / 2) / SING_RATE)).toFloat() else 0f }
        var seen: FloatArray? = null
        separate(input, n * 2) { m -> if (seen == null) seen = m.copyOf(); FloatArray(m.size) { 1f } }
        val m = seen!!
        val frame = 100
        val left = (0 until SING_MODEL_BINS).maxBy { m[(frame * SING_MODEL_BINS + it) * 2] }
        assertEquals(93, left)
        assertTrue((0 until SING_MODEL_BINS).all { m[(frame * SING_MODEL_BINS + it) * 2 + 1] < 1e-3f })
    }

    @Test fun resamplingKeepsATonesShapeAndTheSongsLength() {
        for ((from, to) in listOf(48_000 to 44_100, 44_100 to 48_000, 22_050 to 44_100)) {
            val seconds = 2.0
            val n = (seconds * from).toInt()
            val input = FloatArray(n) { (.5 * sin(2 * PI * 1_000 * it / from)).toFloat() }
            val out = FloatList()
            val resampler = Resampler(from, to, 1) { pcm, k -> for (i in 0 until k) out.add(pcm[i]) }
            input.toList().chunked(5_000).forEach { resampler.feed(it.toFloatArray()) }
            resampler.finish()
            val result = out.toArray()
            assertEquals("$from→$to length", (n.toLong() * to / from).toDouble(), result.size.toDouble(), 2.0)
            // Away from the edges, the output is the same tone sampled at the new rate.
            var error = 0.0; var count = 0
            for (i in to / 10 until result.size - to / 10) { val ideal = .5 * sin(2 * PI * 1_000 * i / to); error += (result[i] - ideal).let { it * it }; count++ }
            val rms = sqrt(error / count)
            assertTrue("$from→$to RMS error $rms", rms < 2e-3)
        }
    }
}
