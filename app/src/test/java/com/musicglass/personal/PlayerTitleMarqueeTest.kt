package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 7: the full player's title scrolls only when the player is fully open and nothing argues against it. */
class PlayerTitleMarqueeTest {
    @Test fun aFullyOpenPlayerScrollsItsTitle() {
        assertTrue(playerTitleScrolls(reducedMotion = false, fontScale = 1f, fullyOpen = true, queueOpen = false, dragging = false))
    }

    @Test fun reducedMotionKeepsTheEllipsis() {
        assertFalse(playerTitleScrolls(reducedMotion = true, fontScale = 1f, fullyOpen = true, queueOpen = false, dragging = false))
    }

    @Test fun largeTextKeepsTwoLinesInstead() {
        assertFalse(playerTitleScrolls(reducedMotion = false, fontScale = 1.5f, fullyOpen = true, queueOpen = false, dragging = false))
        assertTrue(playerTitleScrolls(reducedMotion = false, fontScale = 1.3f, fullyOpen = true, queueOpen = false, dragging = false))
    }

    @Test fun itWaitsForTheMorphAndStopsForTheQueueOrADrag() {
        assertFalse(playerTitleScrolls(reducedMotion = false, fontScale = 1f, fullyOpen = false, queueOpen = false, dragging = false))
        assertFalse(playerTitleScrolls(reducedMotion = false, fontScale = 1f, fullyOpen = true, queueOpen = true, dragging = false))
        assertFalse(playerTitleScrolls(reducedMotion = false, fontScale = 1f, fullyOpen = true, queueOpen = false, dragging = true))
    }
}
