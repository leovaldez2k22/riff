package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Feature 1: the tab bar's pill against the owner's reference video (30 Oct), in that video's pixels. */
class TabLensMotionTest {
    private val slot = 258f
    private val bar = 192f
    private var now = 0L

    private fun motion(selected: Int = 0, reduced: Boolean = false) = TabLensMotion().apply {
        now = 0L
        layout(slot, bar, 4 * slot + 8f + bar / 2f, bar, selected, reduced)
    }

    /** Runs [ms] at 60 frames a second, calling [each] before each frame. */
    private fun TabLensMotion.run(ms: Int, each: (Int) -> Unit = {}): List<TabLensFrame> = (0 until ms / 16).map { i ->
        each(i); now += 16; step(now, .016f); frame()
    }

    @Test fun atRestThePillIsFrostedAndFitsItsTab() {
        val f = motion(1).frame()
        assertEquals(slot * 1.5f, f.centerX, .01f)
        assertEquals(160f, f.height, 1f); assertEquals(slot - .05f * bar, f.width, 1f)
        assertEquals(0f, f.lift, 0f); assertEquals(0f, f.ghostAlpha, 0f)
    }

    @Test fun aDragLiftsItIntoALensThatStandsOutOfTheBarWithASmallBounce() {
        val m = motion()
        m.down(slot / 2f, now); m.move(slot / 2f + 20f, 8f)
        assertTrue(m.lifted)
        val frames = m.run(400)
        val peak = frames.maxOf { it.lift }
        val firstNear = frames.indexOfFirst { it.lift > .95f }
        assertTrue("grown within about 0.1 s", firstNear in 3..8)
        assertTrue("a bounce of about a sixth, not more", peak in 1.08f..1.25f)
        val settled = frames.last()
        assertEquals(1f, settled.lift, .01f)
        assertEquals(1.36f * 160f, settled.height, 2f)
        assertTrue("out of the top and the bottom of the bar", settled.height > bar * 1.1f)
        assertEquals(1.33f * (slot - .05f * bar), settled.width, 2f)
    }

    @Test fun aHeldFingerLiftsItWithoutMoving() {
        val m = motion()
        m.down(slot / 2f, now)
        m.run(300); assertFalse(m.lifted)
        m.run(100); assertTrue(m.lifted)
    }

    @Test fun aTapNeverLifts() {
        val m = motion()
        m.down(slot * 2.5f, now); m.run(100); m.cancel()
        m.select(2)
        val frames = m.run(600)
        assertTrue(frames.all { it.lift == 0f })
        assertEquals(slot * 2.5f, frames.last().centerX, 1f)
    }

    @Test fun takenOffCentreItKeepsTheGrabAfterAFifthOfAStepTowardTheFinger() {
        // The video: the pill at 178, a finger at 288; the lens settled at 194 and then followed one to one.
        val m = motion()
        val pill = slot / 2f
        m.down(pill + 110f, now); m.move(pill + 120f, 8f)
        m.run(300) { m.move(pill + 120f, 8f) }
        val taken = m.frame().centerX
        assertEquals(pill + .18f * 120f, taken, 3f)
        m.run(200) { i -> m.move(pill + 120f + 4f * (i + 1), 8f) }
        assertEquals("one to one", taken + 4f * 12, m.frame().centerX, 3f)
    }

    @Test fun itStaysBetweenTheEndTabsAndSquashesWhenPushedPast() {
        val m = motion()
        m.down(slot / 2f, now); m.move(slot / 2f + 10f, 8f)
        m.run(300) { i -> m.move(slot / 2f - 10f * i, 8f) }
        val pushed = m.frame()
        assertEquals(slot / 2f, pushed.centerX, .5f)
        val lifted = 1.33f * (slot - .05f * bar)
        assertTrue("narrower", pushed.width < lifted * .97f)
        assertTrue("taller", pushed.height > 1.36f * 160f * 1.02f)
        m.run(500) { m.move(slot / 2f + 10f, 8f) }
        assertEquals("springs back as the finger comes away", lifted, m.frame().width, 3f)
    }

    @Test fun speedWidensAndFlattensItAndAGhostTrails() {
        val m = motion()
        m.down(slot / 2f, now); m.move(slot / 2f + 10f, 8f)
        m.run(200) { m.move(slot / 2f + 10f, 8f) }
        // Three tabs a second.
        val frames = m.run(500) { i -> m.move(slot / 2f + 10f + 3f * slot * .016f * (i + 1), 8f) }
        val fast = frames.last()
        val lifted = 1.33f * (slot - .05f * bar)
        assertEquals("about 12 % wider", 1.12f, fast.width / lifted, .03f)
        assertTrue("a little flatter", fast.height < 1.36f * 160f)
        assertTrue("the ghost behind", fast.ghostX < fast.centerX - 30f && fast.ghostX > fast.centerX - 80f)
        assertTrue(fast.ghostAlpha in .1f..GHOST_MAX)
    }

    @Test fun theSelectionWaitsForTheReleaseThenTheLensGlidesHomeAndOnlyThenShrinks() {
        val m = motion()
        m.down(slot / 2f, now); m.move(slot / 2f + 10f, 8f)
        m.run(300) { i -> m.move(slot / 2f + 10f + 6f * i, 8f) }
        // Let go 20 px past the third tab's centre, still moving.
        m.run(300) { i -> m.move(slot / 2f + 10f + 6f * 18 + (2f * slot - 6f * 18 + 10f) * (i + 1) / 18f, 8f) }
        assertEquals(2, m.hovered())
        assertEquals(2, m.up())
        val frames = m.run(700)
        val target = 2.5f * slot
        val overshoot = frames.maxOf { abs(it.centerX - target) * if ((it.centerX - target) * (frames.first().centerX - target) < 0) 1 else 0 }
        assertTrue("no overshoot", overshoot < 2f)
        val home = frames.indexOfFirst { abs(it.centerX - target) < .06f * slot }
        assertTrue("home in about 0.2 s", home in 0..15)
        assertTrue("still a lens on the way", frames.take(home.coerceAtLeast(1)).all { it.lift > .8f })
        assertEquals(0f, frames.last().lift, .01f)
        assertEquals(target, frames.last().centerX, 1f)
        assertFalse(m.moving)
    }

    @Test fun reducedMotionNeverLiftsAndSnaps() {
        val m = motion(reduced = true)
        m.down(slot / 2f, now); m.move(slot, 8f); m.run(500)
        assertFalse(m.lifted); m.cancel()
        m.select(3); m.run(16)
        assertEquals(3.5f * slot, m.frame().centerX, 0f); assertEquals(0f, m.frame().lift, 0f)
    }

    @Test fun searchGetsARoundPill() {
        val m = motion()
        m.select(4); m.run(800)
        val f = m.frame()
        assertEquals(4 * slot + 8f + bar / 2f, f.centerX, 1f)
        assertEquals(f.height, f.width, 1f)
    }

    private companion object { const val GHOST_MAX = TabLensMotion.GHOST_ALPHA + .001f }
}
