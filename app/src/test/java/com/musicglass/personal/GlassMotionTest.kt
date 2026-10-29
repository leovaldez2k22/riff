package com.musicglass.personal

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 35: one calm pace for the whole app, with Quick and reduced motion on top. */
class GlassMotionTest {
    private val calm = MotionPolicy(reduced = false, scale = 1f)

    @Test fun theCalmTimingsAreLongerThanBefore() {
        assertEquals(130, GlassMotion.pressMs)
        assertEquals(260, GlassMotion.selectionMs)
        assertEquals(420, GlassMotion.screenMs)
        assertEquals(450, GlassMotion.sheetMs)
        assertEquals(520, GlassMotion.playerMs)
        assertEquals(1200, GlassMotion.ambientMs)
    }

    @Test fun timedUsesTheTokenAndTheCalmCurve() {
        val spec = GlassMotion.timed<Float>(GlassMotion.screenMs, calm) as TweenSpec<Float>
        assertEquals(420, spec.durationMillis)
        assertEquals(GlassMotion.calmEasing, spec.easing)
    }

    @Test fun exitsTakeThreeQuartersOfTheEntry() {
        assertEquals(315, GlassMotion.exitMs(GlassMotion.screenMs))
        assertEquals(315, (GlassMotion.exit<Float>(GlassMotion.screenMs, calm) as TweenSpec<Float>).durationMillis)
    }

    @Test fun quickRestoresRoughlyThePreviousPace() {
        val quick = MotionPolicy(reduced = false, scale = MotionPace.Quick.factor)
        assertEquals(294, (GlassMotion.timed<Float>(GlassMotion.screenMs, quick) as TweenSpec<Float>).durationMillis)
        assertEquals(182, (GlassMotion.timed<Float>(GlassMotion.selectionMs, quick) as TweenSpec<Float>).durationMillis)
    }

    @Test fun theSystemAnimatorScaleStillApplies() {
        val half = MotionPolicy(reduced = false, scale = .5f)
        assertEquals(210, (GlassMotion.timed<Float>(GlassMotion.screenMs, half) as TweenSpec<Float>).durationMillis)
    }

    @Test fun reducedMotionSnaps() {
        val reduced = MotionPolicy(reduced = true, scale = 1f)
        assertTrue(GlassMotion.timed<Float>(GlassMotion.screenMs, reduced) is SnapSpec<Float>)
        assertTrue(GlassMotion.exit<Float>(GlassMotion.screenMs, reduced) is SnapSpec<Float>)
        assertTrue(GlassMotion.settle<Float>(reduced) is SnapSpec<Float>)
    }

    // Phone checks 29 Oct (item 18): the full-screen player opens and closes slower, and closing is not quicker by a quarter.
    @Test fun thePlayerOpensAndClosesAtItsOwnSlowerPace() {
        val open = GlassMotion.player<Float>(true, calm) as TweenSpec<Float>
        val close = GlassMotion.player<Float>(false, calm) as TweenSpec<Float>
        assertEquals(700, open.durationMillis); assertEquals(600, close.durationMillis)
        assertEquals(GlassMotion.playerEasing, open.easing)
        assertTrue(GlassMotion.player<Float>(true, MotionPolicy(reduced = true, scale = 1f)) is SnapSpec<Float>)
        assertEquals(Spring.StiffnessLow, (GlassMotion.playerSettle<Float>(calm) as SpringSpec<Float>).stiffness, 1e-3f)
    }

    @Test fun settlingIsSofterWithAHintOfGive() {
        val spec = GlassMotion.settle<Float>(calm) as SpringSpec<Float>
        assertEquals(.9f, spec.dampingRatio, 1e-6f)
        assertEquals(Spring.StiffnessMediumLow, spec.stiffness, 1e-3f)
    }

    @Test fun theStoredPaceIsReadBackAndUnknownValuesAreCalm() {
        assertEquals(MotionPace.Quick, motionPaceFrom("quick"))
        assertEquals(MotionPace.Calm, motionPaceFrom("calm"))
        assertEquals(MotionPace.Calm, motionPaceFrom(null))
        assertEquals(MotionPace.Calm, motionPaceFrom("fast"))
    }
}
