package com.musicglass.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 9: the end-of-song silence watch and the rule that skips. */
class SilenceSkipTest {
    private val rate = 48_000

    private fun pcm(ms: Int, amplitude: Double, channels: Int = 2): ByteBuffer {
        val frames = rate * ms / 1_000
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 440 * i / rate) * amplitude * 32767).toInt().toShort()
            repeat(channels) { buffer.putShort(v) }
        }
        buffer.flip(); return buffer
    }

    private fun meter() = SilenceMeter().apply { configure(rate, 2) }

    @Test fun musicThenSilenceCountsTheQuietRun() {
        val meter = meter()
        meter.measure(pcm(3_000, .5)); assertEquals(0, meter.quietMs)
        meter.measure(pcm(2_000, 0.0)); assertEquals(2_000, meter.quietMs)
    }

    @Test fun soundAgainEndsTheRun() {
        val meter = meter()
        meter.measure(pcm(2_000, 0.0)); meter.measure(pcm(200, .3))
        assertEquals(0, meter.quietMs)
    }

    @Test fun aQuietFadeIsNotSilence() {
        // −40 dBFS, where a fading outro sits, stays sound.
        val meter = meter(); meter.measure(pcm(2_000, .014))
        assertEquals(0, meter.quietMs)
        // −60 dBFS is silence.
        meter.measure(pcm(1_000, .0014)); assertEquals(1_000, meter.quietMs)
    }

    @Test fun measuringLeavesTheBufferAsItWas() {
        val buffer = pcm(100, .5); val before = buffer.duplicate()
        meter().measure(buffer)
        assertEquals(before, buffer); assertEquals(0, buffer.position())
    }

    @Test fun skipsOnlyNearTheEndAfterTheQuietHasLasted() {
        fun skip(quiet: Long, position: Long, duration: Long = 240_000L) =
            shouldSkipSilence(true, quiet, position, duration, playing = true, hasNext = true, repeatOne = false, skipItem = false, soughtToMs = null)
        assertTrue(skip(1_500, 230_000))
        assertFalse("not yet long enough", skip(1_000, 230_000))
        assertFalse("a pause in the middle", skip(5_000, 120_000))
        // The window is the shorter of a quarter of the song and a minute: a minute here.
        assertTrue(skip(2_000, 183_000)); assertFalse(skip(2_000, 180_000))
        // A short song: a quarter of 100 s is 25 s.
        assertTrue(skip(2_000, 78_000, 100_000)); assertFalse(skip(2_000, 70_000, 100_000))
    }

    @Test fun guards() {
        fun skip(enabled: Boolean = true, hasNext: Boolean = true, repeatOne: Boolean = false, skipItem: Boolean = false, soughtTo: Long? = null, playing: Boolean = true) =
            shouldSkipSilence(enabled, 3_000, 230_000, 240_000, playing, hasNext, repeatOne, skipItem, soughtTo)
        assertTrue(skip())
        assertFalse(skip(enabled = false)); assertFalse("the last song", skip(hasNext = false)); assertFalse(skip(repeatOne = true))
        assertFalse("podcast, live or video", skip(skipItem = true)); assertFalse(skip(playing = false))
        assertFalse("unknown length", shouldSkipSilence(true, 3_000, 230_000, -1, true, true, false, false, null))
    }

    // Phone checks 29 Oct (item 1): scrubbing near the end to try the switch used to turn it off for that song.
    @Test fun aSeekOnlyKeepsTheSilenceItLandedIn() {
        fun skip(soughtTo: Long?) = shouldSkipSilence(true, 3_000, 230_000, 240_000, true, true, false, false, soughtTo)
        assertTrue("landed in the music, before the quiet began at 227 s", skip(220_000))
        assertFalse("landed in the silence", skip(227_000))
    }

    @Test fun pathASkipsWhereTheSoundEnds() {
        fun skip(position: Long, end: Long = 245_100, soughtTo: Long? = null, quiet: Long = 0) =
            shouldSkipSilence(true, quiet, position, 247_640, true, true, false, false, soughtTo, audibleEndMs = end)
        // The owner's download owTWCbq_nSk: silent from 244.8 s of 247.6 s; the end is read 300 ms after the last sound.
        assertFalse("still sounding", skip(245_000)); assertTrue("at once, without waiting for 1.5 s of quiet", skip(245_100))
        assertFalse("under a second would be saved", skip(247_000, end = 246_800))
        assertFalse("sought into the silence", skip(246_000, soughtTo = 245_500)); assertTrue(skip(246_000, soughtTo = 200_000))
        // A known end decides alone: Path B's quiet run is not consulted.
        assertFalse(skip(240_000, quiet = 5_000))
    }

    @Test fun theEnvelopeEndIsThreeHundredMillisecondsAfterTheLastSound() {
        val loud = FloatArray(30) { if (it < 20) -12f else -90f }
        assertEquals(1_000L + 2_000 + 300, TrackEnvelope(1_000, loud, loud, 10_000).audibleEndMs())
    }

    @Test fun theEventIsKept() {
        assertEquals("silence_skip at=230000 quiet_ms=1500 path=b", DiagnosticEvent.parse("silence_skip at=230000 quiet_ms=1500 path=b").text)
        assertEquals("silence_skip at=245100 quiet_ms=0 path=a", DiagnosticEvent.parse("silence_skip at=245100 quiet_ms=0 path=a").text)
    }
}
