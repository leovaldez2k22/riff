package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ListeningHistoryTest {
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

    @Test fun pauseResumeAndSeekToStartStayOnePlay() {
        val plays = LogicalPlays()
        assertFalse("not heard yet", plays.qualifies("yt:abcdefghijk", playing = false, live = false))
        assertTrue(plays.qualifies("yt:abcdefghijk", playing = true, live = false))
        // Later ticks of the same play, a pause and resume, and a seek back to the start: no transition, no new play.
        assertFalse(plays.qualifies("yt:abcdefghijk", playing = true, live = false))
        assertFalse(plays.qualifies("yt:abcdefghijk", playing = false, live = false))
        assertFalse(plays.qualifies("yt:abcdefghijk", playing = true, live = false))
    }

    @Test fun repeatOneCountsEachRepeat() {
        val plays = LogicalPlays()
        assertTrue(plays.qualifies("yt:abcdefghijk", true, false))
        plays.started() // the player reports a transition to the same entry
        assertTrue(plays.qualifies("yt:abcdefghijk", true, false))
        assertFalse(plays.qualifies("yt:abcdefghijk", true, false))
    }

    @Test fun replayAfterTheSongFinishedIsANewPlay() {
        val plays = LogicalPlays()
        assertTrue(plays.qualifies("local:42", true, false))
        plays.completed()
        assertTrue(plays.qualifies("local:42", true, false))
    }

    @Test fun liveBroadcastsAndUnknownItemsAreNotSongHistory() {
        val plays = LogicalPlays()
        assertFalse(plays.qualifies("yt:abcdefghijk", true, live = true))
        assertFalse(plays.qualifies("other:1", true, false))
        // Still a first play once it is no longer live-only.
        assertTrue(plays.qualifies("yt:abcdefghijk", true, false))
    }

    @Test fun onDevicePlaysBelongToTheDeviceAndUnverifiedAccountsRecordNothing() {
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, historyScopeKey("local:42", "owner-a"))
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, historyScopeKey("local:42", UNVERIFIED_SCOPE_KEY))
        assertEquals("owner-a", historyScopeKey("yt:abcdefghijk", "owner-a"))
        assertEquals(GUEST_SCOPE_KEY, historyScopeKey("yt:abcdefghijk", GUEST_SCOPE_KEY))
        assertNull(historyScopeKey("yt:abcdefghijk", UNVERIFIED_SCOPE_KEY))
    }
}
