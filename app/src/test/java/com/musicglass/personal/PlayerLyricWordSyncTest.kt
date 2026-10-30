package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phone checks 29 Oct (bug 14): the player's lyric line lights word by word when the lyrics are word-synced. */
class PlayerLyricWordSyncTest {
    // "Hello world": "Hello" sung from 1.0 to 2.0 s, "world" from 2.0 to 3.0 s.
    private val parts = listOf(LyricPart(1_000, 2_000, 0, 5), LyricPart(2_000, 3_000, 6, 11))

    @Test fun nothingIsLitBeforeAWordStarts() = assertEquals(0f, playerLyricSweep(parts[0], 500), 0f)

    @Test fun theSweepMovesSmoothlyAcrossTheWordBeingSungAndStaysAfter() {
        // Owner, 30 Oct: smooth, as in the full lyrics, not a letter at a time.
        assertEquals(.4f, playerLyricSweep(parts[0], 1_400), .001f)
        assertEquals(.401f, playerLyricSweep(parts[0], 1_401), .001f)
        assertEquals(1f, playerLyricSweep(parts[0], 2_000), 0f)
        assertEquals(.4f, playerLyricSweep(parts[1], 2_400), .001f)
        assertEquals(0f, playerLyricSweep(parts[1], 1_999), 0f)
        assertEquals(1f, playerLyricSweep(parts[1], 9_000), 0f)
    }

    @Test fun theRowCarriesTheWordTimingsOfTheLineBeingSung() {
        val doc = LyricsDocument("test", "", listOf(LyricLine(1_000, 3_000, "Hello world", parts, knownEnd = true)))
        val row = playerLyricRow(doc, 1_500) as PlayerLyricRow.Line
        assertEquals(parts, row.parts); assertEquals(false, row.past)
    }
}
