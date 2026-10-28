package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Feature 30: the rail and the pane by window size; Feature 6: the saved artwork motion. */
class ShellWidthTest {
    @Test fun widthClassesFollowTheAdaptiveThresholds() {
        assertEquals(ShellWidth.Compact, shellWidth(411f, 891f))
        assertEquals(ShellWidth.Medium, shellWidth(600f, 960f))
        assertEquals(ShellWidth.Medium, shellWidth(839f, 1280f))
        assertEquals(ShellWidth.Expanded, shellWidth(840f, 600f))
        assertEquals(ShellWidth.Expanded, shellWidth(1280f, 800f))
    }

    @Test fun aPhoneOnItsSideKeepsTheBottomBar() {
        assertEquals(ShellWidth.Compact, shellWidth(891f, 411f))
    }

    @Test fun artworkMotionReadsWhatWasSavedAndDefaultsToOff() {
        assertEquals(ArtworkMotion.Mesh, ArtworkMotion.of("mesh"))
        assertEquals(ArtworkMotion.Off, ArtworkMotion.of(null))
        assertEquals(ArtworkMotion.Off, ArtworkMotion.of("sparkle"))
    }
}
