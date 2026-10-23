package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Q18-068: the morph's border takes over from the mini player's in one frame, so it must start exactly as bright. */
class MorphBorderTest {
    @Test fun theMorphBorderStartsAsTheMiniPlayersAndFadesOut() {
        for (solid in listOf(false, true)) {
            assertEquals("solid controls $solid", miniBorderAlpha(solid), morphBorderAlpha(0f, solid), 1e-6f)
            assertEquals(miniBorderAlpha(solid), morphBorderAlpha(.002f, solid), .005f)
            assertEquals(0f, morphBorderAlpha(1f, solid), 1e-6f)
        }
        assertEquals(.72f, miniBorderAlpha(true), 0f); assertEquals(.42f, miniBorderAlpha(false), 0f)
    }
}
