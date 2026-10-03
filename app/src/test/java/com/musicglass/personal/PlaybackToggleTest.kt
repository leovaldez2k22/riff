package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackToggleTest {
    @Test fun afterALoadErrorPlayPlays() {
        // Idle after an IO error, playWhenReady still set: the button shows Play, so it must play.
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = true, idle = true))
    }

    @Test fun normalPlayAndPause() {
        assertTrue(toggleShouldPause(isPlaying = true, playWhenReady = true, idle = false))
        assertTrue(toggleShouldPause(isPlaying = false, playWhenReady = true, idle = false)) // buffering
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = false, idle = false))
    }
}
