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

    @Test fun decoderStartupDoesNotCompressEitherVolumeRamp() {
        val envelope=CrossfadeEnvelope(6000,0)
        for(now in 16L..4000L step 16) assertEquals(0f,envelope.advance(now,false),0f)
        for(now in 4016L..7000L step 16) envelope.advance(now,true)
        assertEquals(.5f,envelope.progress,.003f)
        val (outgoing,incoming)=crossfadeGains(envelope.progress)
        assertEquals(.707f,outgoing,.005f)
        assertEquals(.707f,incoming,.005f)
        for(now in 7008L..10_032L step 16) envelope.advance(now,true)
        assertEquals(1f,envelope.progress,0f)
    }

    @Test fun bufferingHoldsBothVolumesAndResumesWithoutAJump() {
        val envelope=CrossfadeEnvelope(6000,0)
        for(now in 16L..3008L step 16) envelope.advance(now,true)
        val held=envelope.progress
        for(now in 3024L..7008L step 16) assertEquals(held,envelope.advance(now,false),0f)
        val resumed=envelope.advance(7024,true)
        assertEquals(16f/6000,resumed-held,1e-6f)
    }

    @Test fun lateCallbacksCannotAbruptlyDropOutgoingOrRaiseIncomingVolume() {
        for(duration in listOf(1000L,6000L,12_000L)) {
            val envelope=CrossfadeEnvelope(duration,0)
            var now=0L
            var previous=crossfadeGains(0f)
            while(envelope.progress<1f) {
                now+=if(now%3L==0L) 2000 else 16
                val gains=crossfadeGains(envelope.advance(now,true))
                assertTrue("Outgoing volume must decrease smoothly",previous.first-gains.first in -.000001f.. .079f)
                assertTrue("Incoming volume must increase smoothly",gains.second-previous.second in -.000001f.. .079f)
                assertEquals(1f,gains.first*gains.first+gains.second*gains.second,1e-5f)
                previous=gains
            }
            assertEquals(0f,previous.first,1e-6f)
            assertEquals(1f,previous.second,0f)
        }
    }

    @Test fun clockRegressionCannotReverseOrAccelerateTheFade() {
        val envelope=CrossfadeEnvelope(1000,100)
        assertEquals(.016f,envelope.advance(116,true),1e-6f)
        assertEquals(.016f,envelope.advance(50,true),1e-6f)
        assertEquals(.032f,envelope.advance(132,true),1e-6f)
    }

    @Test fun neitherGainFinishesBeforeTheFullConfiguredDuration() {
        val envelope=CrossfadeEnvelope(6000,0)
        for(now in 16L..5984L step 16) envelope.advance(now,true)
        assertTrue(envelope.progress<1f)
        val before=crossfadeGains(envelope.progress)
        assertTrue(before.first>0f)
        assertTrue(before.second<1f)
        assertEquals(1f,envelope.advance(6000,true),0f)
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

    @Test fun preparationHasTimeToResolveAndBufferBeforeTheAudibleFade() {
        assertTrue(shouldPrepareCrossfade(6000,164_000,200_000,true,true,false,false,false))
        assertFalse(shouldStartCrossfade(6000,164_000,200_000,true,true,false,false,false))
        assertFalse(shouldPrepareCrossfade(6000,150_000,200_000,true,true,false,false,false))
        assertFalse(shouldPrepareCrossfade(6000,199_000,200_000,true,true,false,false,false))
        assertFalse(shouldPrepareCrossfade(6000,164_000,200_000,true,true,false,true,false))
        assertFalse(shouldPrepareCrossfade(6000,164_000,200_000,true,true,false,false,true))
        assertFalse(shouldPrepareCrossfade(6000,164_000,200_000,false,true,false,false,false))
        assertFalse(shouldPrepareCrossfade(6000,164_000,200_000,true,false,false,false,false))
    }
}
