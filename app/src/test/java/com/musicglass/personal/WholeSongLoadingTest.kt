package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 9 November 2026: a streamed song loads to its end as it plays, except under Data saver. */
class WholeSongLoadingTest {
    @Test fun loadingGoesOnToTheEndUnlessDataSaverIsOn() {
        assertTrue(wholeSongMayLoad(10 * 60_000_000L, dataSaver = false))
        assertTrue(wholeSongMayLoad(59_000_000L, dataSaver = true))
        assertFalse(wholeSongMayLoad(RIFF_AHEAD_SAVER_MS * 1000L, dataSaver = true))
        assertTrue("nearly every song loads whole", RIFF_AHEAD_MAX_MS >= 15 * 60_000)
    }
}
