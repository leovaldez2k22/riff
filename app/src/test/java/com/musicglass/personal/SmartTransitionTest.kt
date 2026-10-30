package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automix stage 1: the envelope, the plan and the bass swap. */
class SmartTransitionTest {
    /** An envelope from levels in dB, one per 100 ms block. */
    private fun envelope(startMs: Long, durationMs: Long, vararg runs: Pair<Float, Int>): TrackEnvelope {
        val levels = runs.flatMap { (db, blocks) -> List(blocks) { db } }.toFloatArray()
        return TrackEnvelope(startMs, levels, levels.copyOf(), durationMs)
    }

    @Test fun findsTheEndsTheOutroAndTheIntro() {
        // A 45 s tail of a 200 s song: 30 s of body, 8 s fading, then silence.
        val tail = envelope(155_000, 200_000, -10f to 300, -18f to 80, -70f to 70)
        assertEquals(155_000L + 38_000 + 300, tail.audibleEndMs())
        assertEquals(-10f, tail.bodyDb)
        assertEquals(185_000L, tail.outroStartMs())
        val head = envelope(0, 180_000, -70f to 10, -30f to 40, -9f to 250)
        assertEquals(1_000L, head.audibleStartMs())
        assertEquals(5_000L, head.introEndMs())
    }

    @Test fun planStartsAtTheOutroAndEndsWithTheSound() {
        val tail = envelope(155_000, 200_000, -10f to 300, -18f to 80, -70f to 70)
        val plan = planSmartTransition(tail, null)!!
        // The blend is the song's own outro, 8.3 s, whatever the crossfade slider says.
        assertEquals(193_300L, plan.endMs); assertEquals(185_000L, plan.startMs); assertEquals(8_300L, plan.blendMs)
        val shortOutro = envelope(155_000, 200_000, -10f to 330, -18f to 50, -70f to 70)
        assertEquals("a 5 s outro sets a shorter blend", 5_300L, planSmartTransition(shortOutro, null)!!.blendMs)
        assertEquals(0L, plan.incomingStartMs)
    }

    @Test fun aShortOutroUsesTheSettingAndBlendsStayInBounds() {
        val abrupt = envelope(155_000, 200_000, -10f to 440, -70f to 10)
        // An abrupt end has no outro to blend over: the shortest blend.
        assertEquals(SMART_MIN_BLEND_MS, planSmartTransition(abrupt, null)!!.blendMs)
        // A long, slow outro: 12 s at most.
        val long = envelope(155_000, 200_000, -10f to 300, -16f to 140, -70f to 10)
        assertEquals(SMART_MAX_BLEND_MS, planSmartTransition(long, null)!!.blendMs)
    }

    @Test fun theIncomingSongSkipsAShortQuietIntroOnly() {
        val tail = envelope(155_000, 200_000, -10f to 440, -70f to 10)
        val quietIntro = envelope(0, 180_000, -70f to 5, -30f to 45, -9f to 250)
        assertEquals(4_000L, planSmartTransition(tail, quietIntro, 6_000)!!.incomingStartMs)
        assertEquals("not for speech", 500L, planSmartTransition(tail, quietIntro, 6_000, incomingIsMusic = false)!!.incomingStartMs)
        val longIntro = envelope(0, 180_000, -70f to 5, -30f to 150, -9f to 145)
        assertEquals(500L, planSmartTransition(tail, longIntro, 6_000)!!.incomingStartMs)
    }

    @Test fun noPlanWithoutAReadableEnd() {
        assertNull(planSmartTransition(null, null, 6_000))
        assertNull(planSmartTransition(envelope(0, 10_000, -80f to 50), null, 6_000))
    }

    @Test fun theBassSwapsAtTheMidpoint() {
        assertEquals(0f to BASS_SWAP_CUT_DB, bassSwapCuts(0f, 8_000))
        assertEquals(0f to BASS_SWAP_CUT_DB, bassSwapCuts(.4f, 8_000))
        val (out, inc) = bassSwapCuts(.5f, 8_000); assertEquals(BASS_SWAP_CUT_DB / 2, out, .01f); assertEquals(BASS_SWAP_CUT_DB / 2, inc, .01f)
        assertEquals(BASS_SWAP_CUT_DB to 0f, bassSwapCuts(.6f, 8_000))
        assertEquals(BASS_SWAP_CUT_DB to 0f, bassSwapCuts(1f, 8_000))
    }

    @Test fun anAlbumInOrderIsLeftAlone() {
        assertTrue(albumInOrder("Album", 3, "Album", 4))
        assertFalse(albumInOrder("Album", 3, "Album", 6)); assertFalse(albumInOrder("Album", 3, "Other", 4)); assertFalse(albumInOrder(null, 3, null, 4))
    }

    @Test fun theBuilderMeasuresLevelAndLowBand() {
        val rate = 48_000; val builder = EnvelopeBuilder(rate, 2, 0)
        fun tone(hz: Double, amplitude: Double) = ShortArray(rate * 2) { (sin(2 * PI * hz * (it / 2) / rate) * amplitude * 32767).toInt().toShort() }
        builder.add(tone(60.0, .5)); builder.add(tone(5_000.0, .5))
        val env = builder.build(2_000)
        assertEquals(20, env.loudnessDb.size)
        assertEquals(-9f, env.loudnessDb[5], .3f)
        assertTrue("60 Hz is low", env.lowDb[5] > -12f)
        assertTrue("5 kHz is not", env.lowDb[15] < -40f)
    }

    @Test fun theBassCutTakesTheLowEndOut() {
        val eq = PcmEqualizer().apply { configure(48_000, 1); bassCutDb = BASS_SWAP_CUT_DB; reset() }
        val input = java.nio.ByteBuffer.allocate(48_000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { input.putShort((sin(2 * PI * 50 * it / 48_000) * .5 * 32767).toInt().toShort()) }
        input.flip()
        val output = java.nio.ByteBuffer.allocate(input.remaining()); eq.process(input, output); output.flip()
        var peak = 0; for (i in 24_000 until 48_000) peak = maxOf(peak, kotlin.math.abs(output.order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(i * 2).toInt()))
        assertTrue("50 Hz down by more than 18 dB: $peak", peak < 16_384 / 8)
    }

    // Automix stage 2: a 120 BPM grid (500 ms beats) with downbeats every 2 s.
    private fun grid(firstMs: Long, count: Int, confidence: Float = .8f, downbeat: Float = .6f) = BeatGrid(120.0,
        LongArray(count) { firstMs + it * 500L }, LongArray(count / 4) { firstMs + it * 2_000L }, confidence, downbeat)

    @Test fun aSureBlendStartsOnADownbeatAndEntersOnOne() {
        val plan = SmartPlan(190_300, 198_000, 1_200)
        val aligned = alignToBeats(plan, grid(160_000, 80), grid(150, 60))!!
        assertTrue(aligned.onBeat)
        assertEquals("the phrase line nearest the stage 1 start", 192_000L, aligned.startMs)
        assertEquals(198_000L, aligned.endMs)
        assertEquals("the first downbeat after the entry", 2_150L, aligned.incomingStartMs)
    }

    @Test fun unsureBeatsKeepStageOne() {
        val plan = SmartPlan(190_300, 198_000, 1_200)
        assertNull(alignToBeats(plan, grid(160_000, 80, confidence = .2f), grid(150, 60)))
        assertNull(alignToBeats(plan, grid(160_000, 80), grid(150, 60, downbeat = .1f)))
        assertNull(alignToBeats(plan, null, grid(150, 60)))
    }

    @Test fun noDownbeatNearTheStartKeepsStageOne() =
        assertNull(alignToBeats(SmartPlan(190_300, 198_000, 1_200), grid(100_000, 80), grid(150, 60)))
}
