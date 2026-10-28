package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 18: "End of this song", "After N songs", "End of queue", and a fade of the owner's chosen length. */
class SleepSongsTest {
    @Test fun endOfThisSongStopsWhenItEnds() {
        val songs = SleepSongs().apply { set(1) }
        assertTrue(songs.onLastSong)
        assertTrue(songs.finishedOne())
        assertFalse(songs.active)
    }

    @Test fun afterThreeSongsCountsDownAndOnlyTheLastLeadsTheFade() {
        val songs = SleepSongs().apply { set(3) }
        assertFalse(songs.onLastSong)
        assertFalse(songs.finishedOne())
        assertFalse(songs.finishedOne())
        assertTrue(songs.onLastSong)
        assertTrue(songs.finishedOne())
        assertFalse("nothing more once done", songs.finishedOne())
    }

    @Test fun cancellingStopsCounting() {
        val songs = SleepSongs().apply { set(2); cancel() }
        assertFalse(songs.active)
        assertFalse(songs.finishedOne())
    }

    @Test fun theDeadlineIsTheSongsEndAtItsSpeed() {
        assertEquals(1_000L + 60_000L + SLEEP_SONG_GRACE_MS, sleepSongDeadline(1_000, durationMs = 180_000, positionMs = 120_000, speed = 1f))
        assertEquals("at 1.5× a minute of song lasts 40 s", 1_000L + 40_000L + SLEEP_SONG_GRACE_MS, sleepSongDeadline(1_000, 180_000, 120_000, 1.5f))
    }

    @Test fun theFadeFollowsTheChosenLength() {
        assertEquals(.5f, sleepFadeGain(7_500), 0f)
        assertEquals(.5f, sleepFadeGain(30_000, 60_000), 0f)
        assertEquals(1f, sleepFadeGain(4_000, 0), 0f)
        assertEquals(0f, sleepFadeGain(0, 0), 0f)
        assertTrue(SLEEP_FADE_DEFAULT_MS in SLEEP_FADE_CHOICES_MS)
    }
}
