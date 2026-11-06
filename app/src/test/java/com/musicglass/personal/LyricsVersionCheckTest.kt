package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsVersionCheckTest {
    private fun line(text: String, at: Long = 20_000) = LyricLine(at, at + 1_000, text, emptyList())
    private fun doc(source: String, words: String) =
        LyricsDocument(source, "", words.split("\n").mapIndexed { i, text -> line(text, 20_000L + i * 2_000) })
    private val original = "the coast of italy lemons in the sun\nyou and me driving nowhere\nmy heart is on the road"
    private val remix = "fall fall in fall in love\nwhat can you tell\nburn old city down tonight"
    private val sameSong = "$original\none more line for the guest"

    @Test fun titlesThatNameAGuest() {
        assertTrue(lyricsTitleNamesGuest("Everything Is Romantic (feat. caroline polachek)"))
        assertTrue(lyricsTitleNamesGuest("Song ft. Someone"))
        assertTrue(lyricsTitleNamesGuest("Song featuring Someone"))
        assertFalse(lyricsTitleNamesGuest("Everything Is Romantic"))
        assertFalse(lyricsTitleNamesGuest("Feather"))
        assertFalse(lyricsTitleNamesGuest("Left Behind"))
    }

    @Test fun aCheckedSourceReplacesAnUncheckedOneWithOtherWords() {
        assertTrue(lyricsReplaces(doc("LRCLIB", remix), doc("Better Lyrics", original), 60_000, 2_000, guestTitle = true))
    }

    @Test fun anUncheckedSourceCannotReplaceACheckedOneWithOtherWords() {
        assertFalse(lyricsReplaces(doc("Better Lyrics", original), doc("LRCLIB", remix), 0, 2_000, guestTitle = true))
    }

    @Test fun theSameSongKeepsItsUsualRanking() {
        val shown = doc("Better Lyrics", original)
        val checked = doc("LRCLIB", sameSong)
        assertFalse(lyricsOtherVersion(checked, shown))
        assertEquals(lyricsOutranks(checked, shown, 60_000, 2_000), lyricsReplaces(checked, shown, 60_000, 2_000, guestTitle = true))
    }

    @Test fun aTitleWithoutAGuestIsNeverCompared() {
        val shown = doc("Better Lyrics", original)
        val other = doc("LRCLIB", remix)
        assertEquals(lyricsOutranks(other, shown, 60_000, 2_000), lyricsReplaces(other, shown, 60_000, 2_000, guestTitle = false))
    }

    @Test fun qqsHeadingIsRecognised() {
        assertTrue(lyricsIsTitleLine("Ribs - Lorde (洛德)", "Ribs", "Lorde"))
        assertTrue(lyricsIsTitleLine("Ribs – Lorde", "Ribs", "Lorde"))
        assertTrue(lyricsIsTitleLine("Everything Is Romantic - Charli XCX", "Everything Is Romantic (feat. caroline polachek)", "Charli xcx & Caroline Polachek"))
        assertFalse(lyricsIsTitleLine("Ribs - and my heart", "Ribs", "Lorde"))
        assertFalse(lyricsIsTitleLine("It drags you down", "Ribs", "Lorde"))
        assertFalse(lyricsIsTitleLine("Ribs - Lorde", "", ""))
    }

    @Test fun parsedQqLeavesOutItsHeadingOnlyAtTheStart() {
        val raw = "[0,3000]Ribs(0,1000) - (1000,500)Lorde(1500,500) (洛德)(2000,1000)\n" +
            "[20000,2000]The(20000,500) drink(20500,500)\n[60000,2000]Ribs(60000,500) - (60500,500)Lorde(61000,500)"
        val parsed = parseQrc(raw, 200_000, "Ribs", "Lorde")!!
        assertEquals(listOf("The drink", "Ribs - Lorde"), parsed.lines.map { it.text.trim() })
    }

    @Test fun savedCopiesLoseTheHeadingWhenRead() {
        val track = LyricsTrack("yt:x", "Ribs", "Lorde", "", 200_000)
        val saved = LyricsDocument("Better Lyrics · QQ", "", listOf(line("Ribs - Lorde (洛德)", 0), line("The drink")))
        assertEquals(listOf("The drink"), saved.withoutTitleLine(track).lines.map { it.text })
        assertEquals(2, LyricsDocument("LRCLIB", "", saved.lines).withoutTitleLine(track).lines.size)
    }
}
