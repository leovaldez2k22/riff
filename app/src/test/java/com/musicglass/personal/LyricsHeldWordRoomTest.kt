package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone checks 29 Oct (bug 12): a held word grows only into the room it has, so its letters never touch a neighbour. */
class LyricsHeldWordRoomTest {
    @Test fun aLongWordBetweenNeighboursGrowsOnlyIntoHalfOfEachGap() {
        // "Revolutionaries" (380 px) with a 10 px space on either side: 5 px each way, less 1 px for the swell.
        val grow = heldWordMaxGrow(left = 100f, right = 480f, before = 90f, after = 490f, lineWidth = 1000f, margin = 1f)
        assertEquals(1f + 2f * 4f / 380f, grow, 1e-5f)
        // Grown around its centre, it stays inside the gaps.
        val spill = 380f * (grow - 1f) / 2f
        assertTrue(100f - spill > 90f + 4f); assertTrue(480f + spill < 490f - 4f)
    }

    @Test fun aShortWordStillPopsFully() =
        assertEquals(RiffLyrics.heldScale, heldWordMaxGrow(100f, 160f, 80f, 180f, 1000f, 1f), 0f)

    @Test fun theLineEdgeIsTheWordsOwnRoom() {
        // Starting the line, at its edge, it can't grow at all; with room to the edge it uses all of it.
        assertEquals(1f, heldWordMaxGrow(0f, 380f, null, 400f, 1000f, 0f), 0f)
        assertEquals(1f + 2f * 6f / 380f, heldWordMaxGrow(6f, 386f, null, 420f, 1000f, 0f), 1e-5f)
    }

    @Test fun noRoomOrNoWidthMeansNoGrowth() {
        assertEquals(1f, heldWordMaxGrow(100f, 480f, 99f, 481f, 1000f, 2f), 0f)
        assertEquals(1f, heldWordMaxGrow(100f, 100f, null, null, 1000f, 0f), 0f)
    }
}
