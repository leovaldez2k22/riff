package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Feature 7 (owner, Q7): a phrase found in the lyrics held with downloads; made-up lyrics only. */
class LyricsSearchTest {
    private fun track(id: String, title: String) = ProviderTrack(id, title, "Someone")
    private fun doc(vararg lines: String) = LyricsDocument("LRCLIB", "https://lrclib.net", lines.mapIndexed { i, text -> LyricLine(i * 1000L, i * 1000L + 900, text) })
    private val index = listOfNotNull(
        lyricsIndexSong(track("a", "Untitled one"), doc("Under the copper moon", "we were dancing, slow")),
        lyricsIndexSong(track("b", "Canción"), doc("Mi corazón está en la playa")),
        lyricsIndexSong(track("c", "Song C"), doc("夜空に光る星", "遠くへ行こう")),
        lyricsIndexSong(track("d", "Empty"), doc()),
        lyricsIndexSong(track("e", "Instrumental"), LyricsDocument("LRCLIB", "x", emptyList(), true)))

    @Test fun aVerseFindsASongWhoseTitleHasNoneOfItsWords() {
        val hits = lyricsSearch(index, "copper moon")
        assertEquals(listOf("a"), hits.map { it.track.videoId })
        assertEquals("Under the copper moon", hits.single().excerpt)
    }

    @Test fun accentsCasePunctuationAndLineBreaksDoNotMatter() {
        assertEquals(listOf("b"), lyricsSearch(index, "CORAZON esta").map { it.track.videoId })
        val across = lyricsSearch(index, "moon, we were")
        assertEquals("Under the copper moon / we were dancing, slow", across.single().excerpt)
    }

    @Test fun scriptsWithoutSpacesMatchInsideALine() {
        assertEquals(listOf("c"), lyricsSearch(index, "光る星").map { it.track.videoId })
    }

    @Test fun tooShortOrMissingPhrasesFindNothing() {
        assertTrue(lyricsSearch(index, "the").isEmpty())
        assertTrue(lyricsSearch(index, "  ,, ").isEmpty())
        assertTrue(lyricsSearch(index, "nothing like this").isEmpty())
        assertTrue(lyricsSearch(emptyList(), "copper moon").isEmpty())
        assertEquals(3, index.size)
    }
}
