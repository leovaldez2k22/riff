package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Play on a restored song: the player reads ahead while paused only from bytes already on the phone. */
class PreparesOnReturnTest {
    @Test fun aWholeSongOnThePhoneIsPreparedEvenOffline() = assertTrue(preparesOnReturn(true, false, false, 40_000, 200_000))
    @Test fun aPartlyHeldSongIsPreparedWhenItsPositionIsThereAndThereIsANetwork() = assertTrue(preparesOnReturn(false, true, true, 40_000, 200_000))
    @Test fun aPartlyHeldSongWaitsForPlayOfflineSinceAFailedReadWouldStartRecovery() = assertFalse(preparesOnReturn(false, true, false, 40_000, 200_000))
    @Test fun aSongWhosePositionIsNotOnThePhoneWaitsForPlay() = assertFalse(preparesOnReturn(false, false, true, 40_000, 200_000))
    @Test fun nearTheEndItWouldLookUpTheNextSongSoItWaitsForPlay() {
        assertFalse(preparesOnReturn(true, true, true, 150_000, 200_000))
        assertFalse(preparesOnReturn(true, true, true, 140_000, 200_000))
        assertTrue(preparesOnReturn(true, true, true, 139_999, 200_000))
    }
    @Test fun anUnknownLengthWaitsForPlay() = assertFalse(preparesOnReturn(true, true, true, 0, 0))

    @Test fun cachedAroundAsksForTheBytesEitherSideOfThePosition() {
        var asked: Pair<Long, Long>? = null
        assertTrue(cachedAround(100_000, 200_000, 2_000_000, { position, length -> asked = position to length; true }))
        assertEquals(850_000L to 300_000L, asked)
    }
    @Test fun cachedAroundStopsAtBothEndsOfTheSong() {
        val asked = mutableListOf<Pair<Long, Long>>()
        cachedAround(0, 200_000, 2_000_000, { position, length -> asked += position to length; true })
        cachedAround(199_000, 200_000, 2_000_000, { position, length -> asked += position to length; true })
        assertEquals(listOf(0L to 150_000L, 1_840_000L to 160_000L), asked)
    }
    @Test fun cachedAroundIsFalseWhenTheBytesAreMissingOrTheLengthsUnknown() {
        assertFalse(cachedAround(100_000, 200_000, 2_000_000, { _, _ -> false }))
        assertFalse(cachedAround(100_000, 0, 2_000_000, { _, _ -> true }))
        assertFalse(cachedAround(100_000, 200_000, 0, { _, _ -> true }))
    }
}
