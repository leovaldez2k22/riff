package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 11: the top and bottom fades: dark at the edge, nothing where they stop, no banding steps. */
class EdgeFadeTest {
    @Test fun theFadeStartsAtThePeakAndEndsAtNothing() {
        val stops = edgeFadeStops(RiffEdgeFade.topPeak)
        assertEquals(EDGE_FADE_STOPS, stops.size)
        assertEquals(0f, stops.first().first, 0f)
        // A colour's alpha is kept in 8 bits.
        assertEquals(RiffEdgeFade.topPeak, stops.first().second.alpha, 1f / 255f)
        assertEquals(1f, stops.last().first, 0f)
        assertEquals(0f, stops.last().second.alpha, 1f / 255f)
    }

    @Test fun itOnlyEverGetsLighterTowardsTheMiddle() {
        val alphas = edgeFadeStops(RiffEdgeFade.bottomPeak).map { it.second.alpha }
        assertTrue(alphas.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test fun itIsOnlySlightlyDark() {
        assertTrue(RiffEdgeFade.topPeak < .8f && RiffEdgeFade.bottomPeak < .8f)
        assertTrue(RiffEdgeFade.highContrastPeak > RiffEdgeFade.topPeak)
    }
}
