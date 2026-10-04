package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CrossfadeAndSeekTest {
    @Test fun doubleTapMovesTenSecondsInsideTheSong() {
        assertEquals(40_000L, seekNudgeTarget(30_000, 200_000, forward = true))
        assertEquals(20_000L, seekNudgeTarget(30_000, 200_000, forward = false))
        assertEquals(0L, seekNudgeTarget(4_000, 200_000, forward = false))
        assertEquals(199_000L, seekNudgeTarget(195_000, 200_000, forward = true))
        assertEquals(199_500L, seekNudgeTarget(199_500, 200_000, forward = true)) // Never jumps back or skips the song.
        assertEquals(15_000L, seekNudgeTarget(5_000, 0, forward = true)) // Unknown length.
    }

    @Test fun repeatedDoubleTapsAddUpOnTheSameSide() {
        assertEquals(20, seekNudgeTotal(10, sameSide = true, sinceLastMs = 400))
        assertEquals(10, seekNudgeTotal(20, sameSide = false, sinceLastMs = 400))
        assertEquals(10, seekNudgeTotal(20, sameSide = true, sinceLastMs = 5_000))
    }

    @Test fun equalPowerCurvesStayLoudThroughTheBlend() {
        assertEquals(1f to 0f, crossfadeGains(0f))
        val (out, incoming) = crossfadeGains(.5f)
        assertEquals(1f, out * out + incoming * incoming, 1e-5f)
        assertTrue(abs(crossfadeGains(1f).first) < 1e-6f)
        assertEquals(1f, crossfadeGains(2f).second, 1e-6f)
    }

    @Test fun crossfadeStartsOnlyNearTheEndOfAnEligibleSong() {
        fun start(position: Long, duration: Long = 200_000, hasNext: Boolean = true, repeatOne: Boolean = false,
            skip: Boolean = false, sleep: Boolean = false, playing: Boolean = true) =
            shouldStartCrossfade(6_000, position, duration, playing, hasNext, repeatOne, skip, sleep)
        assertTrue(start(195_000))
        assertFalse(start(190_000))
        assertFalse(start(199_000)) // Too late to blend smoothly.
        assertFalse(start(195_000, hasNext = false))
        assertFalse(start(195_000, repeatOne = true))
        assertFalse(start(195_000, skip = true))
        assertFalse(start(195_000, sleep = true))
        assertFalse(start(195_000, playing = false))
        assertFalse(start(15_000, duration = 20_000)) // Too short to fade.
        assertEquals(12, crossfadeSeconds(30)); assertEquals(1, crossfadeSeconds(0))
    }
}
