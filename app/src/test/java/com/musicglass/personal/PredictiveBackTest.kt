package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Feature 22: the player shrinks to 90 % and drops; a page slides 15 % and fades; nothing moves at rest. */
class PredictiveBackTest {
    @Test fun atRestNothingMoves() {
        assertEquals(BackPreview(1f, 0f, 0f, 1f), backPreview(0f, player = true))
        assertEquals(BackPreview(1f, 0f, 0f, 1f), backPreview(0f, player = false))
    }

    @Test fun thePlayerShrinksTowardTheMiniPlayer() {
        val end = backPreview(1f, player = true)
        assertEquals(.9f, end.scale, 1e-6f)
        assertEquals(1f, end.dropFraction, 0f)
        assertEquals(.95f, backPreview(.5f, player = true).scale, 1e-6f)
    }

    @Test fun aPageSlidesAndFades() {
        val half = backPreview(.5f, player = false)
        assertEquals(.075f, half.slideFraction, 1e-6f)
        assertEquals(.9f, half.alpha, 1e-6f)
        assertEquals("progress past the end is held", backPreview(1f, false), backPreview(3f, false))
    }
}
