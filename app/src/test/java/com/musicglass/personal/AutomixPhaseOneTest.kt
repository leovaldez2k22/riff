package com.musicglass.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The research report of 31 Oct, Phase 1 (section 7.1): its seven quick fixes to Automix. */
class AutomixPhaseOneTest {
    private fun envelope(startMs: Long, durationMs: Long, vararg runs: Pair<Float, Int>, beats: BeatGrid? = null): TrackEnvelope {
        val levels = runs.flatMap { (db, blocks) -> List(blocks) { db } }.toFloatArray()
        return TrackEnvelope(startMs, levels, levels.copyOf(), durationMs, beats)
    }
    private fun grid(bpm: Double, firstMs: Long, bars: Int, confidence: Float = .9f, downbeat: Float = .9f): BeatGrid {
        val beat = 60_000.0 / bpm
        return BeatGrid(bpm, LongArray(bars * 4) { (firstMs + it * beat).toLong() }, LongArray(bars) { (firstMs + it * beat * 4).toLong() }, confidence, downbeat)
    }

    // 1.1: the bass hole and sag.
    @Test fun theLowEndNeverFallsMoreThan3dBThroughTheSwap() {
        for (step in 0..20) {
            val share = step / 20f
            // The body mid-blend, at its deepest for each song: the equal-power -3 dB.
            val (body, _) = crossfadeGains(.5f)
            val out = deckLevel(body, 1 - share); val inc = deckLevel(body, share)
            fun low(deck: DeckLevel) = deck.volume * 10f.pow(deck.shelves.lowCutDb / 20)
            val power = low(out).pow(2) + low(inc).pow(2)
            assertTrue("share $share: ${10 * log10(power)} dB", 10 * log10(power) >= -3.05f)
        }
        // Before the swap the outgoing song plays at full level with its body cut to the fade; the incoming has no bass.
        val before = deckLevel(.707f, 1f)
        assertEquals(1f, before.volume); assertEquals(0f, before.shelves.lowCutDb); assertEquals(-3f, before.shelves.aboveCutDb, .05f)
        val incoming = deckLevel(.707f, 0f)
        assertEquals(.707f, incoming.volume); assertEquals(BASS_SWAP_CUT_DB, incoming.shelves.lowCutDb); assertEquals(0f, incoming.shelves.aboveCutDb)
        assertEquals("silence is silence", DeckLevel(0f, Shelves()), deckLevel(0f, 0f))
    }

    @Test fun theAboveBassShelfCutsTheBodyAndKeepsTheBass() {
        fun peak(hz: Double): Int {
            val eq = PcmEqualizer().apply { configure(48_000, 1); aboveBassCutDb = -20f; reset() }
            val input = ByteBuffer.allocate(48_000 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48_000) { input.putShort((sin(2 * PI * hz * it / 48_000) * .5 * 32767).toInt().toShort()) }
            input.flip()
            val output = ByteBuffer.allocate(input.remaining()); eq.process(input, output); output.flip(); output.order(ByteOrder.LITTLE_ENDIAN)
            var peak = 0; for (i in 24_000 until 48_000) peak = maxOf(peak, abs(output.getShort(i * 2).toInt()))
            return peak
        }
        assertTrue("2 kHz down by more than 15 dB", peak(2_000.0) < 16_384 / 5)
        assertTrue("40 Hz within 3 dB", peak(40.0) > 16_384 * .7)
    }

    // 1.2: the phrase-anchored start.
    @Test fun thePhrasePhaseIsWhereTheBassComesAndGoes() {
        // 120 BPM from 150 s: a downbeat every 2 s; the bass comes in and out at every fourth from the third (index 2).
        val beats = grid(120.0, 150_000, 23)
        val low = FloatArray(450) { block -> val bar = block / 20; if (bar < 2 || ((bar - 2) / 4) % 2 == 1) -30f else -10f }
        val tail = TrackEnvelope(150_000, FloatArray(450) { -10f }, low, 200_000, beats)
        assertEquals(2, phrasePhase(tail, beats))
        assertNull("unsure downbeats give no phrase", phrasePhase(tail, grid(120.0, 150_000, 23, downbeat = .4f)))
    }

    @Test fun withoutAClearPhaseThePhraseIsCountedBackFromTheOutro() {
        // No bar stands out: the song fades evenly over four bars from 186 s, so its outro starts (falls under 60 % of
        // the body) at 189.6 s, by the downbeat at 190 s, index 20.
        val beats = grid(120.0, 150_000, 23)
        val levels = FloatArray(450) { block -> when { block < 360 -> -10f; block < 440 -> -10f - (block - 360) / 8f; else -> -20f } }
        val tail = TrackEnvelope(150_000, levels, levels.copyOf(), 200_000, beats)
        assertEquals(20 % PHRASE_BARS, phrasePhase(tail, beats))
    }

    // 1.3: the swap on the downbeat.
    @Test fun theSwapIsOnTheDownbeatNearestTheMiddleAndHardWhenBothAreSure() {
        val plan = SmartPlan(190_300, 198_100, 2_000)
        val sure = grid(120.0, 160_000, 20)
        val swap = swapOnDownbeat(plan, sure, sure)!!
        assertEquals(194_000L, swap.atMs); assertEquals("hard", 0L, swap.lengthMs)
        val soft = swapOnDownbeat(plan, grid(120.0, 160_000, 20, downbeat = .6f), sure)!!
        assertEquals(194_000L, soft.atMs); assertEquals("one beat", 500L, soft.lengthMs)
        assertNull(swapOnDownbeat(plan, grid(120.0, 160_000, 20, downbeat = .4f), sure))
        assertEquals(0f, bassShare(193_999, swap)); assertEquals(1f, bassShare(194_000, swap))
    }

    // 1.4: lyrics as a vocal prior.
    @Test fun aBlendThatWouldSingOverItselfStartsLater() {
        val tail = envelope(155_000, 200_000, -10f to 300, -18f to 80, -70f to 70)
        val head = envelope(0, 180_000, -10f to 300)
        val sungOut = listOf(186_000L..190_000L); val sungIn = listOf(0L..5_000L)
        val plain = planSmartTransition(tail, head, onBeat = false)!!
        assertTrue(vocalOverlapBeats(plain, sungOut, sungIn, 500.0) > VOCAL_CLASH_BEATS)
        val plan = planSmartTransition(tail, head, onBeat = false, outgoingVocals = sungOut, incomingVocals = sungIn)!!
        assertTrue("starts later: ${plan.startMs}", plan.startMs > plain.startMs)
        assertEquals(plain.endMs, plan.endMs); assertEquals(plain.incomingStartMs, plan.incomingStartMs)
        assertTrue("${plan.vocalBeats}", plan.vocalBeats!! <= VOCAL_CLASH_BEATS)
        assertEquals("lyrics of another edit are left out", plain.startMs,
            planSmartTransition(tail, head, onBeat = false, outgoingVocals = listOf(196_000L..199_000L), incomingVocals = sungIn)!!.startMs)
    }

    @Test fun vocalSpansComeFromTimedSungLines() {
        val lines = listOf(LyricLine(1_000, 4_000, "one"), LyricLine(4_000, 30_000, "two"), LyricLine(30_000, 40_000, "", gap = true),
            LyricLine(-1, -1, "untimed"), LyricLine(40_000, 41_500, "held", knownEnd = true))
        assertEquals(listOf(1_000L..4_000L, 4_000L..14_000L, 40_000L..41_500L), vocalSpans(lines))
    }

    // 1.5: the echo-out.
    @Test fun temposThatCantMeetEchoOut() {
        assertEquals(.055, foldedTempoGap(128.0, 90.0), .001)
        assertEquals(0.0, foldedTempoGap(120.0, 60.0), 1e-9)
        val beats = grid(128.0, 150_000, 26)
        val tail = envelope(150_000, 200_000, -10f to 400, -80f to 50, beats = beats)
        val head = envelope(0, 180_000, -10f to 300, beats = grid(100.0, 300, 10))
        val plan = planSmartTransition(tail, head)!!
        assertNotNull(plan.echo)
        val echo = plan.echo!!
        assertTrue("the cut is a downbeat", echo.cutMs in beats.downbeatsMs)
        assertEquals(468L, echo.delayMs)
        assertEquals(echo.cutMs - 4 * 468, plan.startMs); assertEquals(echo.cutMs + ECHO_RING_MS, plan.endMs)
        assertTrue(plan.endMs <= 200_000)
        // Close tempos blend as before.
        assertNull(planSmartTransition(tail, envelope(0, 180_000, -10f to 300, beats = grid(126.0, 300, 10)))!!.echo)
        assertNull("only in Automix", planSmartTransition(tail, head, onBeat = false)!!.echo)
    }

    @Test fun theEchoStageCutsTheSongOnTimeAndLetsTheEchoesRing() {
        val rate = 48_000
        val echo = PcmEcho().apply { configure(rate, 1, 0); cue = EchoOut(cutMs = 1_000, delayMs = 250, sendFromMs = 500) }
        val input = ByteBuffer.allocate(rate * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rate * 2) { input.putShort((sin(2 * PI * 1_000 * it / rate) * .5 * 32767).toInt().toShort()) }
        input.flip()
        val samples = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        // In pieces, as the player hands them over.
        while (input.hasRemaining()) { val piece = input.slice().order(ByteOrder.LITTLE_ENDIAN).limit(minOf(4_096, input.remaining())) as ByteBuffer
            echo.process(piece, output); input.position(input.position() + piece.position()) }
        output.flip()
        fun rms(fromMs: Int, toMs: Int, buffer: ByteBuffer): Double {
            var sum = 0.0; val from = rate * fromMs / 1_000; val to = rate * toMs / 1_000
            for (i in from until to) { val v = buffer.getShort(i * 2) / 32768.0; sum += v * v }
            return sqrt(sum / (to - from))
        }
        for (i in 0 until rate / 2) assertEquals("untouched before the send", samples.getShort(i * 2), output.getShort(i * 2))
        val song = rms(0, 500, output)
        assertTrue("the echoes ring after the cut", rms(1_010, 1_240, output) > song * .1)
        assertTrue("and fade", rms(1_750, 2_000, output) < rms(1_010, 1_240, output))
        assertTrue("the song itself is gone", rms(1_760, 1_990, output) < song * .5)
    }

    @Test fun anEchoBlendHoldsTheOutgoingLevelAndBringsTheIncomingInAtTheCut() {
        val owner = CrossfadeOwner()
        val grant = owner.take("a", "b", 5_000, 1f, 0, echoCut = .4f)!!
        assertEquals(CrossfadeLevels(1f, 0f, .002f), owner.levels(grant, 10, true, true))
        var levels: CrossfadeLevels? = null
        for (t in 20..2_100L step 10) levels = owner.levels(grant, t, true, true)
        assertEquals(1f, levels!!.outgoing); assertEquals(1f, levels.incoming)
    }

    // 1.6: the predicted loudness trim.
    @Test fun theTrimHoldsTheBlendOnALineBetweenTheTwoSongs() {
        val tail = envelope(180_000, 220_000, -10f to 300)
        val plan = SmartPlan(185_000, 193_000, 0)
        val even = loudnessTrim(plan, tail, envelope(0, 200_000, -10f to 300), 0f, 0f)!!
        assertTrue("two equal songs need none", even.all { abs(it) < .05f })
        val quieter = loudnessTrim(plan, tail, envelope(0, 200_000, -20f to 300), 0f, 0f)!!
        assertTrue("a quieter next song: turned down mid-blend, ${quieter.minOrNull()}", quieter.minOrNull()!! < -1f)
        assertTrue(quieter.all { it in TRIM_DOWN_DB..TRIM_UP_DB })
        assertEquals(0f, quieter.first()); assertEquals(0f, quieter.last())
        assertTrue("normalization counts", loudnessTrim(plan, tail, envelope(0, 200_000, -20f to 300), 0f, 10f)!!.all { abs(it) < .05f })
        assertNull("no next song, no trim", loudnessTrim(plan, tail, null, 0f, 0f))
        assertEquals(0f, plan.copy(trimDb = quieter).trimAt(184_000)); assertEquals(quieter[40], plan.copy(trimDb = quieter).trimAt(189_000))
    }

    // 1.7: the longer tail for songs on the phone.
    @Test fun aTailFromBeforeTheLongerWindowIsReadAgain() {
        assertTrue(shortLocalTail(envelope(155_000, 200_000, -10f to 450)))
        assertFalse(shortLocalTail(envelope(136_000, 200_000, -10f to 640)))
        assertFalse("a short song is read whole", shortLocalTail(envelope(0, 50_000, -10f to 500)))
    }
}
