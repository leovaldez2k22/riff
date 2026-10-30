package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q18-068, since the glass: the mini player's glass keeps drawing its rim under the morph, so the morph adds none at
 * the start (a second rim was a bright outline) and none once it is the player.
 */
class MorphBorderTest {
    @Test fun theMorphAddsNoBorderAtEitherEnd() {
        assertEquals(0f, morphBorderAlpha(0f), 1e-6f)
        assertTrue("faint just after the start", morphBorderAlpha(.002f) < .002f)
        assertEquals(0f, morphBorderAlpha(1f), 1e-6f)
        assertEquals(.10f, morphBorderAlpha(.5f), 1e-6f)
    }
}
