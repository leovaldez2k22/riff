package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** F1: tapping a song to play it opens the full player once that song is playing. */
class SongTapOpensPlayerTest {
    @Test fun aTappedSongOpensThePlayerWhenItStarts() {
        val model = ShellModel()
        model.songTapped("yt:a", currentMediaId = "", nowMs = 1_000)
        assertEquals("", model.sheet)
        model.miniVisible = true
        model.mediaChanged("yt:a", nowMs = 2_500)
        assertEquals("mini", model.sheet)
    }

    @Test fun tappingTheSongAlreadyPlayingOpensThePlayerAtOnce() {
        val model = ShellModel().apply { miniVisible = true }
        model.songTapped("yt:a", currentMediaId = "yt:a", nowMs = 1_000)
        assertEquals("mini", model.sheet)
    }

    @Test fun aSongThatTakesTooLongToStartDoesNotPullThePlayerOpenLater() {
        val model = ShellModel().apply { miniVisible = true }
        model.songTapped("yt:a", currentMediaId = "yt:b", nowMs = 1_000)
        model.mediaChanged("yt:a", nowMs = 1_000 + SONG_TAP_OPENS_PLAYER_WITHIN_MS + 1)
        assertEquals("", model.sheet)
        // The tap is used up: the next song change on its own does not open it either.
        model.mediaChanged("yt:c", nowMs = 1_000 + SONG_TAP_OPENS_PLAYER_WITHIN_MS + 5_000)
        assertEquals("", model.sheet)
    }

    @Test fun songsChangingWithoutATapLeaveThePlayerClosed() {
        val model = ShellModel().apply { miniVisible = true }
        model.mediaChanged("yt:a", nowMs = 1_000)
        model.mediaChanged("yt:b", nowMs = 200_000)
        assertEquals("", model.sheet)
    }

    @Test fun anOpenLyricsOrQueueIsNotReplaced() {
        val model = ShellModel().apply { miniVisible = true; sheet = "queue" }
        model.songTapped("yt:a", currentMediaId = "yt:a", nowMs = 1_000)
        assertEquals("queue", model.sheet)
    }
}
