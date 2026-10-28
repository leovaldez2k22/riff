package com.musicglass.personal

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 34: the backdrop's mesh, the lyric row and the stats line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerRedesignTest {
    private val red = 0xFFFF0000.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun theMeshOfOneColourIsThatColour() {
        val mesh = meshColors(IntArray(64 * 64) { red }, 64, 64)
        assertTrue(mesh.all { it == red })
    }

    @Test fun aHalfBlackCoverGivesAHalfBlackMesh() {
        // Top half black, bottom half red: proportions are kept, and the row at the seam is the cover's last.
        val pixels = IntArray(60 * 60) { if (it / 60 < 30) black else red }
        val mesh = meshColors(pixels, 60, 60)
        assertEquals(18, mesh.count { it == black })
        val deck = deckMesh(mesh)
        assertTrue("the seam row continues the cover's bottom", (0 until BACKDROP_GRID).all { deck[it] == red })
        assertTrue("the cover's dark top still shows in the deck", deck.any { it == black })
    }

    @Test fun nearBlackKeepsSomeDepth() {
        val tone = deckTone(black)
        assertTrue((tone shr 16 and 0xFF) >= (BACKDROP_FLOOR * 255).toInt())
    }

    @Test fun aBlurOfOneColourIsThatColour() {
        assertTrue(boxBlur(IntArray(16 * 16) { red }, 16, 16).all { it == red })
    }

    private fun line(start: Long, end: Long, text: String) = LyricLine(start, end, text, knownEnd = true)
    private val timed = LyricsDocument("test", "", listOf(line(1_000, 3_000, "First"), line(3_000, 5_000, "Second"), line(15_000, 17_000, "After the break")))

    @Test fun theRowShowsTheLineBeingSung() {
        assertEquals(PlayerLyricRow.Line("Second", past = false), playerLyricRow(timed, 4_000))
    }

    @Test fun beforeTheFirstLineItShowsTheFirstDimmed() {
        assertEquals(PlayerLyricRow.Line("First", past = true), playerLyricRow(timed, 0))
    }

    @Test fun aLongBreakShowsTheDots() {
        val row = playerLyricRow(timed, 10_000)
        assertTrue(row is PlayerLyricRow.Gap)
    }

    @Test fun untimedLyricsShowTheirFirstLineAndInstrumentalsNone() {
        val plain = LyricsDocument("test", "", listOf(LyricLine(-1, -1, ""), LyricLine(-1, -1, "Plain first")))
        assertEquals(PlayerLyricRow.Line("Plain first", past = false), playerLyricRow(plain, 0))
        assertNull(playerLyricRow(LyricsDocument("test", "", emptyList(), instrumental = true), 0))
        assertNull(playerLyricRow(null, 0))
    }

    @Test fun theStatsLineReadsLikeBitChords() {
        val stats = Bundle().apply { putString("mime", "audio/opus"); putInt("sampleRate", 48_000); putInt("formatBitrate", 270_000); putInt("channels", 2) }
        assertEquals("Opus · 48.0 kHz · 270 kbps · Stereo", nerdStatsLine(stats))
        assertNull(nerdStatsLine(Bundle()))
        assertEquals("AAC", codecName("audio/mp4a-latm"))
    }
}
