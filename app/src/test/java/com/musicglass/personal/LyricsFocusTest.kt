package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 5: lines not being sung are very slightly blurred, more the further they are. */
class LyricsFocusTest {
    @Test fun theSungLineIsSharpAndOthersBlurWithDistance() {
        assertEquals(0f, lyricLineBlur(index = 5, focusIndex = 5, active = true, enabled = true).value, 0f)
        assertEquals(RiffLyrics.blurNear, lyricLineBlur(4, 5, active = false, enabled = true))
        assertEquals(RiffLyrics.blurNear, lyricLineBlur(6, 5, active = false, enabled = true))
        assertEquals(RiffLyrics.blurMid, lyricLineBlur(7, 5, active = false, enabled = true))
        assertEquals(RiffLyrics.blurFar, lyricLineBlur(9, 5, active = false, enabled = true))
        assertEquals(RiffLyrics.blurFar, lyricLineBlur(0, 5, active = false, enabled = true))
    }

    @Test fun anOverlappingActiveLineIsSharpToo() {
        // Two voices at once: both lines are being sung, so neither is blurred.
        assertEquals(0f, lyricLineBlur(6, 5, active = true, enabled = true).value, 0f)
    }

    @Test fun nothingBlursWhenOffOrBeforeTheFirstLine() {
        assertEquals(0f, lyricLineBlur(9, 5, active = false, enabled = false).value, 0f)
        assertEquals(0f, lyricLineBlur(3, -1, active = false, enabled = true).value, 0f)
    }

    @Test fun blurIsOffWhileReadingSharingOrWithoutTiming() {
        assertTrue(lyricsBlurOn(setting = true, reducedMotion = false, timed = true, following = true, sharing = false))
        assertFalse(lyricsBlurOn(setting = false, reducedMotion = false, timed = true, following = true, sharing = false))
        assertFalse(lyricsBlurOn(setting = true, reducedMotion = true, timed = true, following = true, sharing = false))
        assertFalse(lyricsBlurOn(setting = true, reducedMotion = false, timed = false, following = true, sharing = false))
        assertFalse("the listener scrolled away to read", lyricsBlurOn(true, false, true, following = false, sharing = false))
        assertFalse(lyricsBlurOn(setting = true, reducedMotion = false, timed = true, following = true, sharing = true))
    }
}
