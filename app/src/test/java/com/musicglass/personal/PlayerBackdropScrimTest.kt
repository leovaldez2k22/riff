package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The adaptive fade on ultra-white covers (5 Nov): ordinary covers get nothing, a white one reads. */
class PlayerBackdropScrimTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun ordinaryCoversAreUnchanged() {
        for (v in listOf(0, 40, 90, 128, 150)) assertEquals("gray $v", 0f, backdropScrimAlpha(backdropLight(gray(v))), 0f)
    }

    @Test fun whiteWordsReadOnAWhiteCover() {
        val light = backdropLight(gray(255))
        assertEquals(1f, light, .001f)
        val after = light * (1f - backdropScrimAlpha(light)).pow(2.2f)
        assertTrue("contrast ${1.05f / (after + .05f)}", 1.05f / (after + .05f) >= 4.5f)
    }

    @Test fun itGrowsWithoutAJump() {
        var last = 0f
        for (v in 0..255) {
            val alpha = backdropScrimAlpha(backdropLight(gray(v)))
            assertTrue("gray $v", alpha >= last - .001f && alpha - last < .05f)
            last = alpha
        }
    }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun onlyWhiteAndOffWhiteDarkenThePlayerWhileAnyBrightCoverDarkensTheQueue() {
        for ((name, colour) in listOf("white" to gray(255), "off white" to rgb(245, 240, 230), "cream" to rgb(255, 245, 225), "pale grey" to gray(232))) {
            val (deck, panel) = backdropScrims(IntArray(16) { colour }, 4)
            assertTrue(name, deck > .4f); assertTrue(name, panel > .4f)
        }
        for ((name, colour) in listOf("BRAT green" to rgb(138, 206, 0), "yellow" to rgb(250, 220, 40), "sky blue" to rgb(120, 200, 245), "mid grey" to gray(170))) {
            val (deck, _) = backdropScrims(IntArray(16) { colour }, 4)
            assertEquals(name, 0f, deck, 0f)
        }
        assertTrue("the queue keeps darkening BRAT", backdropScrims(IntArray(16) { rgb(138, 206, 0) }, 4).second > .2f)
    }

    @Test fun theTitleLooksAtTheLowerHalfAndTheQueueAtAll() {
        val darkBelow = IntArray(16) { if (it < 8) gray(255) else gray(30) }
        val (deck, panel) = backdropScrims(darkBelow, 4)
        assertEquals(0f, deck, 0f)
        assertTrue(panel > .5f)
    }
}
