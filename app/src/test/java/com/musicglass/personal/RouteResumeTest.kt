package com.musicglass.personal

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

/** Feature 6 (owner, Q6): resume after Bluetooth reconnects, and pause on mute. */
class RouteResumeTest {
    private val buds = "AA:BB|Buds"

    @Test fun theSameOutputBackInTimeResumesOnce() {
        val r = RouteResume()
        r.routeGone(buds, 1_000); r.noisyPause(1_400)
        assertTrue(r.waiting)
        assertFalse("Another output leaves the wait in place", r.routeBack("CC:DD|Car", 60_000, false))
        assertTrue(r.waiting)
        assertTrue(r.routeBack(buds, 60_000, false))
        assertFalse("Only once", r.routeBack(buds, 61_000, false))
        // Noisy first, then the removal: the same event.
        r.noisyPause(5_000); r.routeGone(buds, 6_000)
        assertTrue(r.routeBack(buds, 7_000, false))
    }

    @Test fun aLateReturnACallOrAnyOtherChangeEndsTheWait() {
        val r = RouteResume()
        r.routeGone(buds, 0); r.noisyPause(0)
        assertFalse(r.routeBack(buds, BT_RESUME_WINDOW_MS + 1, false)); assertFalse(r.waiting)
        r.routeGone(buds, 0); r.noisyPause(0)
        assertFalse(r.routeBack(buds, 1_000, inCall = true)); assertFalse(r.waiting)
        r.routeGone(buds, 0); r.noisyPause(0); r.cancel()
        assertFalse(r.routeBack(buds, 1_000, false))
    }

    @Test fun aPauseNotCausedByTheOutputLeavingIsNeverResumed() {
        val r = RouteResume()
        r.routeGone(buds, 0); r.noisyPause(BT_PAIR_MS + 1)
        assertFalse(r.waiting)
        r.noisyPause(50_000)
        assertFalse(r.waiting)
        // The later of the two unpaired halves still pairs with what comes next.
        r.routeGone(buds, 51_000)
        assertTrue(r.waiting)
    }

    @Test fun onlyBluetoothOutputsAreRoutes() {
        assertEquals("AA|Buds", bluetoothRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, true, "Buds", "AA"))
        assertEquals(bluetoothRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, true, "Buds", "AA"),
            bluetoothRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, true, "Buds", "AA"))
        assertNull(bluetoothRouteKey(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, true, "Wired", ""))
        assertNull(bluetoothRouteKey(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, false, "Buds", "AA"))
    }

    @Test fun muteOnlyPausesLocalPlayingMusic() {
        assertTrue(shouldPauseOnMute(true, true, true, 0))
        assertFalse(shouldPauseOnMute(false, true, true, 0))
        assertFalse(shouldPauseOnMute(true, false, true, 0))
        assertFalse("Casting has its own volume", shouldPauseOnMute(true, true, false, 0))
        assertFalse(shouldPauseOnMute(true, true, true, 3))
    }

    @Test fun unmutingPlaysOnlyWhatTheMutePaused() {
        // Owner, 10 November 2026: turning the volume up again always resumes what the mute paused.
        assertTrue(shouldResumeOnUnmute(true, true, false, true, 1))
        assertFalse("not paused by the mute", shouldResumeOnUnmute(true, false, false, true, 1))
        assertFalse("already playing again", shouldResumeOnUnmute(true, true, true, true, 1))
        assertFalse("still silent", shouldResumeOnUnmute(true, true, false, true, 0))
        assertFalse("switch turned off", shouldResumeOnUnmute(false, true, false, true, 4))
        assertFalse("casting", shouldResumeOnUnmute(true, true, false, false, 4))
    }
}
