package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 33: dark status icons over a light picture, white over a dark one, without flicker in between. */
class StatusBarToneTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val cream = 0xFFF5EFE0.toInt()

    @Test fun luminanceFollowsWcag() {
        assertEquals(1f, relativeLuminance(white), 1e-3f)
        assertEquals(0f, relativeLuminance(black), 1e-3f)
        assertEquals(.2159f, relativeLuminance(0xFF808080.toInt()), 1e-3f)
    }

    @Test fun aLightCoverTurnsTheIconsDarkAndADarkOneWhite() {
        assertTrue(statusBarDarkIcons(relativeLuminance(cream), darkNow = false))
        assertFalse(statusBarDarkIcons(relativeLuminance(black), darkNow = true))
    }

    @Test fun betweenTheThresholdsNothingChanges() {
        assertTrue(statusBarDarkIcons(.18f, darkNow = true))
        assertFalse(statusBarDarkIcons(.18f, darkNow = false))
        assertTrue(statusBarDarkIcons(.22f, darkNow = false))
        assertFalse(statusBarDarkIcons(.14f, darkNow = true))
    }

    @Test fun aBrightSkyOverADarkBandCountsAsBright() {
        val strip = IntArray(100) { if (it < 30) white else black }
        assertEquals(1f, stripLuminance(strip), 1e-3f)
        assertEquals(0f, stripLuminance(IntArray(100) { if (it < 20) white else black }), 1e-3f)
    }

    @Test fun aScrimDarkensWhatIsUnderIt() {
        assertEquals(1f, underScrim(1f, 0f), 1e-6f)
        assertFalse("white under Feature 11's top fade keeps white icons", statusBarDarkIcons(underScrim(1f, .6f), darkNow = false))
    }

    @Test fun theCroppedRowsAreTheOnesUnderTheStatusBar() {
        // A square cover in a wide, short box: the middle rows show, so the strip starts below the picture's top.
        val rows = croppedTopRows(imageWidth = 100, imageHeight = 100, viewWidth = 400f, viewHeight = 200f, stripPx = 40f)
        assertEquals(25, rows.first)
        assertEquals(35, rows.last)
        // A wide picture in a tall box: the full height shows, so the strip is the picture's own top.
        val tall = croppedTopRows(imageWidth = 200, imageHeight = 100, viewWidth = 100f, viewHeight = 400f, stripPx = 40f)
        assertEquals(0, tall.first)
        assertEquals(10, tall.last)
    }

    @Test fun theHighestRequestWinsAndNoneMeansDarkScreens() {
        val requests = StatusBarRequests()
        assertFalse(requests.lightBackground)
        requests.set("header", StatusBarRequest(lightBackground = false, priority = STATUS_PRIORITY_HEADER))
        requests.set("player", StatusBarRequest(lightBackground = true, priority = STATUS_PRIORITY_PLAYER))
        assertTrue(requests.lightBackground)
        requests.remove("player")
        assertFalse(requests.lightBackground)
    }
}
