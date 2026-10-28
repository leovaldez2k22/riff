package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 32: instrumental stretches are marked, and their three dots fill one after another. */
class LyricGapsTest {
    private fun sung(start: Long, end: Long, text: String = "la", knownEnd: Boolean = true) =
        LyricLine(start, end, text, knownEnd = knownEnd)

    @Test fun anIntroOfFourSecondsOrMoreGetsDots() {
        val shown = withInstrumentalGaps(listOf(sung(4_000, 6_000)))
        assertTrue(shown.first().gap)
        assertEquals(0L to 4_000L, shown.first().startMs to shown.first().endMs)
        assertFalse(withInstrumentalGaps(listOf(sung(3_900, 6_000))).first().gap)
    }

    @Test fun aBreakAfterALineWithAKnownEndGetsDots() {
        val shown = withInstrumentalGaps(listOf(sung(0, 2_000), sung(8_000, 9_000)))
        assertEquals(listOf(false, true, false), shown.map { it.gap })
        assertEquals(2_000L to 8_000L, shown[1].startMs to shown[1].endMs)
    }

    @Test fun aShortPauseGetsNone() {
        assertEquals(2, withInstrumentalGaps(listOf(sung(0, 2_000), sung(5_900, 7_000))).size)
    }

    @Test fun anEndFilledInFromTheNextLineSaysNothingAboutSilence() {
        // Plain LRC: every line "ends" where the next begins; a gap there would come after every line.
        val lrc = listOf(sung(0, 8_000, knownEnd = false), sung(8_000, 12_000, knownEnd = false))
        assertTrue(withInstrumentalGaps(lrc).none { it.gap })
    }

    @Test fun anLrcBareTimestampBecomesABreakWhenItLasts() {
        val lrc = listOf(sung(0, 2_000, knownEnd = false), sung(2_000, 9_000, text = "", knownEnd = false), sung(9_000, 11_000, knownEnd = false))
        val shown = withInstrumentalGaps(lrc)
        assertEquals(listOf(false, true, false), shown.map { it.gap })
        val short = listOf(sung(0, 2_000, knownEnd = false), sung(2_000, 3_000, text = "", knownEnd = false), sung(3_000, 5_000, knownEnd = false))
        assertTrue("a blank line under 4 s is dropped, not shown as dots", withInstrumentalGaps(short).none { it.gap || it.text.isBlank() })
    }

    @Test fun untimedLyricsAreLeftAlone() {
        val plain = listOf(LyricLine(-1, -1, "one"), LyricLine(-1, -1, "two"))
        assertEquals(plain, withInstrumentalGaps(plain))
    }

    @Test fun theDotsFillLeftToRightEachOverAThird() {
        fun dots(p: Float) = (0 until LYRIC_GAP_DOTS).map { lyricGapDotFill(p, it) }
        assertEquals(listOf(0f, 0f, 0f), dots(0f))
        assertEquals(listOf(.5f, 0f, 0f), dots(1f / 6f).map { Math.round(it * 100) / 100f })
        assertEquals(listOf(1f, 0f, 0f), dots(1f / 3f).map { Math.round(it * 100) / 100f })
        assertEquals(listOf(1f, .5f, 0f), dots(.5f).map { Math.round(it * 100) / 100f })
        assertEquals(listOf(1f, 1f, 1f), dots(1f))
    }

    @Test fun aBreakIsNeverShared() {
        assertEquals(null, lyricShareText(LyricLine(2_000, 8_000, "", gap = true)))
    }
}
