package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phone checks 29 Oct (bug 14): the player's lyric line lights word by word when the lyrics are word-synced. */
class PlayerLyricWordSyncTest {
    // "Hello world": "Hello" sung from 1.0 to 2.0 s, "world" from 2.0 to 3.0 s.
    private val parts = listOf(LyricPart(1_000, 2_000, 0, 5), LyricPart(2_000, 3_000, 6, 11))

    @Test fun nothingIsLitBeforeTheFirstWord() = assertEquals(0, playerLyricSung(parts, 500))

    @Test fun theWordBeingSungFillsAndEndedWordsStayLit() {
        assertEquals(2, playerLyricSung(parts, 1_400))
        assertEquals(4, playerLyricSung(parts, 1_999)); assertEquals(6, playerLyricSung(parts, 2_000)) // "world" has begun, so the space before it is lit too
        assertEquals(8, playerLyricSung(parts, 2_400))
        assertEquals(11, playerLyricSung(parts, 9_000))
    }

    @Test fun lineSyncedLyricsLightNothing() = assertEquals(0, playerLyricSung(emptyList(), 5_000))

    @Test fun theRowCarriesTheWordTimingsOfTheLineBeingSung() {
        val doc = LyricsDocument("test", "", listOf(LyricLine(1_000, 3_000, "Hello world", parts, knownEnd = true)))
        val row = playerLyricRow(doc, 1_500) as PlayerLyricRow.Line
        assertEquals(parts, row.parts); assertEquals(false, row.past)
    }
}
