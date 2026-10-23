package com.musicglass.personal

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackToggleTest {
    @Test fun afterALoadErrorPlayPlays() {
        // Idle after an IO error, playWhenReady still set: the button shows Play, so it must play.
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = true, idle = true))
    }

    @Test fun normalPlayAndPause() {
        assertTrue(toggleShouldPause(isPlaying = true, playWhenReady = true, idle = false))
        assertTrue(toggleShouldPause(isPlaying = false, playWhenReady = true, idle = false)) // buffering
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = false, idle = false))
    }

    /** Q18-063 on the phone: the ended song keeps playWhenReady, so the button paused it instead of playing. */
    @Test fun anEndedSongIsPlayedNotPaused() {
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = true, idle = false, ended = true))
        assertFalse(toggleShouldPause(isPlaying = false, playWhenReady = false, idle = false, ended = true))
    }

    /** Q18-076, the owner's decision: a song waiting for a connection shows Pause, and the button does nothing then. */
    @Test fun aSongWaitingForAConnectionShowsPause() {
        assertTrue(waitingForConnection(RecoveryReason.Network, held = false))
        assertFalse("paused by the user before: it shows Play", waitingForConnection(RecoveryReason.Network, held = true))
        assertFalse("a stream retry is not a wait for a connection", waitingForConnection(RecoveryReason.ProviderStream, held = false))
        assertFalse(waitingForConnection(null, held = false))
    }

    @Test fun previousGoesBackAfterTenSecondsAndToTheSongBeforeUntilThen() {
        assertFalse(previousRestartsSong(positionMs = 4_000, hasPrevious = true))
        assertFalse(previousRestartsSong(positionMs = 10_000, hasPrevious = true))
        assertTrue(previousRestartsSong(positionMs = 10_001, hasPrevious = true))
        assertTrue(previousRestartsSong(positionMs = 95_000, hasPrevious = true))
        // The first song of the queue has nothing before it: Previous starts it again.
        assertTrue(previousRestartsSong(positionMs = 2_000, hasPrevious = false))
    }

    /** A player [positionMs] into a song, recording what it was asked to do. */
    private fun player(positionMs: Long, hasPrevious: Boolean, calls: MutableList<String>, state: Int = Player.STATE_READY) =
        java.lang.reflect.Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition" -> positionMs
                "hasPreviousMediaItem" -> hasPrevious
                "getPlaybackState" -> state
                else -> { calls += method.name + (args?.joinToString(",", "(", ")") ?: ""); null }
            }
        } as Player

    @Test fun theButtonRestartsAfterTenSecondsButASwipeAlwaysGoesToTheSongBefore() {
        val button = mutableListOf<String>(); player(95_000, true, button).previousOrRestart()
        assertEquals(listOf("seekTo(0)"), button)
        val swipe = mutableListOf<String>(); player(95_000, true, swipe).previousSong()
        assertEquals(listOf("seekToPreviousMediaItem"), swipe)
        val early = mutableListOf<String>(); player(3_000, true, early).previousSong()
        assertEquals(listOf("seekToPreviousMediaItem"), early)
        // The first song has none before it: a swipe starts it again.
        val first = mutableListOf<String>(); player(95_000, false, first).previousSong()
        assertEquals(listOf("seekTo(0)"), first)
    }

    /** Q18-063: the only song played to its end; Play did nothing until the seek bar was tapped. */
    @Test fun playAfterTheLastSongHasEndedStartsItAgain() {
        val ended = mutableListOf<String>(); player(180_000, false, ended, Player.STATE_ENDED).playFromUser()
        assertEquals(listOf("seekToDefaultPosition", "play"), ended)
        val idle = mutableListOf<String>(); player(0, false, idle, Player.STATE_IDLE).playFromUser()
        assertEquals(listOf("prepare", "play"), idle)
        val paused = mutableListOf<String>(); player(40_000, true, paused).playFromUser()
        assertEquals(listOf("play"), paused)
        // Previous on the ended song (no song before it) starts it again too.
        val previous = mutableListOf<String>(); player(180_000, false, previous, Player.STATE_ENDED).previousOrRestart()
        assertEquals(listOf("seekTo(0)"), previous)
    }
}
