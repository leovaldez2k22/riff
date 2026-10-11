package com.musicglass.personal

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Q08: one holder of the level during a blend. Time is scripted; no player and no audio are involved. */
class CrossfadeOwnerTest {
    private class Sample(val now: Long, val levels: CrossfadeLevels)

    /** Runs a blend at the service's 16 ms cadence. [incoming] and [outgoing] say whether each renderer plays at a time. */
    private fun run(durationMs: Long, base: Float = 1f, incoming: (Long) -> Boolean = { true },
        outgoing: (Long) -> Boolean = { true }, limitMs: Long = 120_000): Pair<CrossfadeOwner, List<Sample>> {
        val owner = CrossfadeOwner()
        val grant = checkNotNull(owner.take("out", "in", durationMs, base, 0))
        val samples = mutableListOf<Sample>()
        var now = 0L
        while (now < limitMs) {
            now += 16
            val levels = checkNotNull(owner.levels(grant, now, incoming(now), outgoing(now)))
            samples += Sample(now, levels)
            if (levels.progress >= 1f) break
        }
        return owner to samples
    }

    private fun assertValid(samples: List<Sample>, base: Float) {
        samples.forEach {
            assertTrue(it.levels.outgoing.isFinite() && it.levels.incoming.isFinite() && it.levels.progress.isFinite())
            assertTrue(it.levels.outgoing in 0f..base && it.levels.incoming in 0f..base && it.levels.progress in 0f..1f)
        }
        assertTrue("Progress never decreases", samples.zipWithNext().all { (a, b) -> b.levels.progress >= a.levels.progress })
    }

    @Test fun oneSixAndTwelveSecondBlendsAreEqualPowerAndTakeTheirFullLength() {
        for (seconds in listOf(1, 6, 12)) for (base in listOf(1f, .35f)) {
            val duration = seconds * 1_000L
            val (owner, samples) = run(duration, base)
            assertValid(samples, base)
            samples.forEach {
                val out = it.levels.outgoing / base; val incoming = it.levels.incoming / base
                assertEquals("Both participate: squared gains add up to one", 1f, out * out + incoming * incoming, .03f)
            }
            assertEquals(base, samples.first().levels.outgoing, base * .03f)
            assertEquals(0f, samples.last().levels.outgoing, 1e-5f)
            assertEquals(base, samples.last().levels.incoming, 1e-5f)
            val error = abs(samples.last().now - duration)
            assertTrue("$seconds s finished after ${samples.last().now} ms", error <= max(150L, duration / 20))
            assertEquals("out" to "in", owner.active!!.outgoingId to owner.active!!.incomingId)
            assertEquals(base, owner.release()!!, 0f)
        }
    }

    @Test fun aShortOverlapIsStillGradual() {
        // The shortest overlap the service accepts when a song has little left.
        val (_, samples) = run(500)
        assertValid(samples, 1f)
        assertTrue(samples.size >= 30)
        assertTrue(samples.zipWithNext().all { (a, b) -> b.levels.incoming - a.levels.incoming <= .06f })
        assertTrue(abs(samples.last().now - 500) <= 150)
    }

    @Test fun aLateIncomingStartHoldsTheBlendAndNeverShortensIt() {
        val (_, samples) = run(6_000, incoming = { it > 3_000 })
        assertValid(samples, 1f)
        val waiting = samples.filter { it.now <= 3_000 }
        assertTrue(waiting.all { it.levels.progress == 0f && it.levels.outgoing == 1f && it.levels.incoming == 0f })
        // Wall time grows by the wait; the audible fade keeps its length.
        assertTrue(abs(samples.last().now - 9_000) <= 300)
    }

    @Test fun bufferingOfTheIncomingSongHoldsBothLevelsAndResumesWithoutAJump() {
        val (_, samples) = run(6_000, incoming = { it !in 2_000..5_000 })
        assertValid(samples, 1f)
        val held = samples.filter { it.now in 2_016..5_000 }
        assertEquals(1, held.map { it.levels.outgoing to it.levels.incoming }.distinct().size)
        samples.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(b.levels.incoming - a.levels.incoming) <= .01f && abs(b.levels.outgoing - a.levels.outgoing) <= .01f)
        }
        assertTrue(samples.last().now >= 9_000 - 32)
    }

    @Test fun anOutgoingCopyThatBuffersOrEndsEarlyStaysSilentAndTheIncomingSongDoesNotJump() {
        // The stated exception to equal power: after the early end only the incoming song is heard.
        for (resumes in listOf(false, true)) {
            val (owner, samples) = run(6_000, outgoing = { it < 2_000 || resumes && it > 2_500 })
            assertValid(samples, 1f)
            val before = samples.filter { it.now < 2_000 }; val after = samples.filter { it.now >= 2_000 }
            before.forEach { assertEquals(1f, it.levels.outgoing * it.levels.outgoing + it.levels.incoming * it.levels.incoming, .03f) }
            assertTrue("Never audible again in this blend", after.all { it.levels.outgoing == 0f })
            assertTrue(owner.active!!.outgoingEnded)
            samples.zipWithNext().forEach { (a, b) -> assertTrue(b.levels.incoming - a.levels.incoming in 0f..(.01f)) }
            assertTrue("The incoming song still takes the full length", abs(samples.last().now - 6_000) <= 300)
        }
    }

    @Test fun lateTicksAndAClockThatGoesBackCannotMakeALevelStep() {
        val owner = CrossfadeOwner(); val grant = owner.take("out", "in", 6_000, 1f, 1_000)!!
        var previous = owner.levels(grant, 1_016, true, true)!!
        for (now in listOf(3_016L, 900L, 3_032L, 9_000L, 9_016L)) {
            val next = owner.levels(grant, now, true, true)!!
            assertTrue(next.progress >= previous.progress)
            // One tick moves the ramp by at most 50 ms of a 6 s blend.
            assertTrue(next.incoming - previous.incoming <= .014f && previous.outgoing - next.outgoing <= .014f)
            previous = next
        }
    }

    @Test fun theLevelIsHandedBackExactlyOnce() {
        val owner = CrossfadeOwner()
        val grant = owner.take("local:1", "local:2", 6_000, .8f, 0)!!
        assertEquals("local:1", grant.outgoingId); assertEquals("local:2", grant.incomingId)
        assertEquals(6_000, grant.durationMs); assertEquals(.8f, grant.base, 0f)
        assertNotNull(owner.levels(grant, 16, true, true))
        // The user pauses: the service takes the level back at once.
        assertEquals(.8f, owner.release()!!, 0f)
        // The cancelled blend's own cleanup runs afterwards and finds nothing to put back.
        assertNull(owner.release(grant)); assertNull(owner.release()); assertNull(owner.active)
        assertNull("A blend that has ended writes no more levels", owner.levels(grant, 32, true, true))
    }

    @Test fun onlyOneBlendHoldsTheLevelAndAStaleOneCannotEndItsSuccessor() {
        val owner = CrossfadeOwner()
        val first = owner.take("a", "b", 1_000, 1f, 0)!!
        assertNull("A second blend is refused while one is active", owner.take("b", "c", 1_000, .2f, 0))
        owner.release()
        val second = owner.take("b", "c", 1_000, .5f, 0)!!
        assertNull(owner.release(first)); assertSame(second, owner.active)
        // A blend that never started holds nothing and puts nothing back.
        assertNull(owner.release(null)); assertSame(second, owner.active)
        assertEquals(.5f, owner.release(second)!!, 0f)
    }

    @Test fun levelsThatAreNotNumbersOrLengthsThatAreNotPositiveAreRefused() {
        val owner = CrossfadeOwner()
        assertNull(owner.take("a", "b", 1_000, Float.NaN, 0))
        assertNull(owner.take("a", "b", 1_000, Float.POSITIVE_INFINITY, 0))
        assertNull(owner.take("a", "b", 0, 1f, 0))
        assertEquals(1f, owner.take("a", "b", 1_000, 4f, 0)!!.base, 0f)
    }

    /** I07: the sleep timer is the other writer of the player's level. The two must not undo each other. */
    @Test fun aSleepTimerSetDuringABlendFadesFromTheRestoredLevelAndEndsOnIt() = runBlocking {
        var volume = .9f; var now = 0L; var pauses = 0
        val owner = CrossfadeOwner()
        val grant = owner.take("a", "b", 6_000, volume, now)!!
        repeat(120) { now += 16; volume = owner.levels(grant, now, true, true)!!.incoming }
        assertTrue(volume in .1f..(.8f))
        val written = mutableListOf<Float>()
        val timer = SleepTimerController(this, { now }, wait = { now += it; yield() }, pause = { pauses++ }, publish = {},
            readVolume = { volume }, writeVolume = { volume = it; written += it })
        // The service's order: end the blend, then set the timer.
        owner.release()?.let { volume = it }
        assertEquals(.9f, volume, 0f)
        timer.set(1)
        // The cancelled blend's cleanup arrives while the timer runs: it must not write.
        owner.release(grant)?.let { volume = it }
        assertNull(owner.levels(grant, now + 16, true, true))
        while (pauses == 0) yield()
        assertEquals("The fade starts from the level the blend handed back", .9f, written.first(), 0f)
        assertTrue(written.dropLast(1).zipWithNext().all { (a, b) -> b <= a })
        assertEquals("After the pause the level is the one from before the blend", .9f, volume, 0f)
        timer.close()
    }

    @Test fun aBlendIsNotStartedWhileTheSleepTimerOrRepeatOneIsOn() {
        fun starts(repeatOne: Boolean = false, sleep: Boolean = false, duration: Long = 200_000, fade: Long = 6_000) =
            shouldStartCrossfade(fade, duration - fade + 100, duration, playing = true, hasNext = true, repeatOne, false, sleep)
        assertTrue(starts())
        assertFalse(starts(repeatOne = true)); assertFalse(starts(sleep = true))
        // Short tracks: a song must be longer than two fades and ten seconds.
        for (seconds in listOf(1, 6, 12)) {
            val fade = seconds * 1_000L
            assertFalse(starts(duration = fade * 2 + 9_999, fade = fade))
            assertTrue(starts(duration = fade * 2 + 10_000, fade = fade))
        }
    }
}
