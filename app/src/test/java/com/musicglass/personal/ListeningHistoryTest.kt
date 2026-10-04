package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.time.ZonedDateTime

class ListeningHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun entriesRoundTripAndRejectBadLines() {
        val yt = HistoryEntry("yt:abcdefghijk", "Song", "Artist", "https://i.ytimg.com/x.jpg", 1000L)
        val local = HistoryEntry("local:42", "Voice memo", "", "content://media/art/42", 2000L, music = false)
        assertEquals(yt, decodeHistoryEntry(encodeHistoryEntry(yt)))
        assertEquals(local, decodeHistoryEntry(encodeHistoryEntry(local)))
        assertNull(decodeHistoryEntry("{\"title\":\"no id\",\"at\":1}"))
        assertNull(decodeHistoryEntry("garbage"))
        assertNull(decodeHistoryEntry(encodeHistoryEntry(yt.copy(artworkUrl = "javascript:x")))!!.artworkUrl)
    }

    @Test fun recordsEveryPlayNewestFirstAndSurvivesReopen() {
        val file = java.io.File(folder.root, "history/history-v1.jsonl")
        val history = ListeningHistory(file)
        assertTrue(history.entries().isEmpty())
        history.record(HistoryEntry("yt:aaaaaaaaaaa", "One", "A", null, 1L))
        history.record(HistoryEntry("yt:bbbbbbbbbbb", "Two", "B", null, 2L))
        history.record(HistoryEntry("yt:aaaaaaaaaaa", "One", "A", null, 3L))
        assertEquals(3, history.revision.value)
        assertEquals(listOf(3L, 2L, 1L), ListeningHistory(file).entries().map { it.playedAt })
    }

    @Test fun onlyYouTubeEntriesBecomePlayableTracks() {
        assertEquals("abcdefghijk", HistoryEntry("yt:abcdefghijk", "S", "A", null, 1L).track()?.videoId)
        assertNull(HistoryEntry("local:42", "S", "A", null, 1L).track())
    }

    @Test fun daysAreLabelledRelativeToNow() {
        val now = at(4, 12)
        assertEquals("Today", historyDayLabel(at(4, 1), now, zone))
        assertEquals("Yesterday", historyDayLabel(at(3, 23), now, zone))
        assertTrue(historyDayLabel(at(1, 9), now, zone).contains("1"))
    }

    @Test fun searchMatchesTitleOrArtist() {
        val entry = HistoryEntry("yt:abcdefghijk", "Blinding Lights", "The Weeknd", null, 1L)
        assertTrue(historyMatches(entry, " weeknd "))
        assertTrue(historyMatches(entry, "lights"))
        assertFalse(historyMatches(entry, "adele"))
    }
}
