package com.musicglass.personal

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Q18-020: while Riff recovers a song (waiting for a connection, trying again, or held by a pause) the media session
 * shows it buffering, or paused once paused, never ERROR; a song Riff gave up on keeps its error. Android maps a
 * session in buffering to "paused" when the play button shows (Media3 LegacyConversions), so paused is what it reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class WaitingSessionPlayerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** A player stopped by an error, as the main player is while Riff waits for a connection. */
    private class FailedPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var state = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf(MediaItemData.Builder("entry-1").setMediaItem(MediaItem.Builder().setMediaId("yt:fixtureA001").build()).build()))
            .setPlaybackState(Player.STATE_IDLE).setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlayerError(PlaybackException("connection lost", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            .build()
        override fun getState() = state
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            state = state.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
            return Futures.immediateVoidFuture()
        }
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }
    private fun failedPlayer() = FailedPlayer()

    @Test fun aSongBeingRecoveredIsBufferingNotAnError() {
        val player = failedPlayer()
        var recovering = true
        val shown = WaitingSessionPlayer(player) { recovering }
        try {
            shown.refresh(); shadowOf(Looper.getMainLooper()).idle()
            assertNull(shown.playerError)
            assertEquals(Player.STATE_BUFFERING, shown.playbackState)
            assertFalse("Waiting to play: Android shows buffering", Util.shouldShowPlayButton(shown))

            shown.pause(); shadowOf(Looper.getMainLooper()).idle()
            assertFalse("The pause reaches the player Riff drives", player.playWhenReady)
            assertNull(shown.playerError)
            assertTrue("Paused while waiting: Android shows paused", Util.shouldShowPlayButton(shown))

            shown.play(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Play reaches the player, which starts recovery again", player.playWhenReady)

            recovering = false; shown.refresh(); shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("A song Riff has given up on keeps its error", shown.playerError)
        } finally { shown.release() }
    }

    @Test fun nothingIsMaskedWhenThereIsNoError() {
        val player = ExoPlayer.Builder(context).build()
        val shown = WaitingSessionPlayer(player) { true }
        try {
            assertNull(shown.playerError)
            assertEquals(Player.STATE_IDLE, shown.playbackState)
        } finally { shown.release() }
    }

    @Test fun theServiceGivesTheSessionTheWrapperAndDrivesTheRealPlayer() {
        val service = java.io.File("src/main/java/com/musicglass/personal/MusicPlaybackService.kt").readText()
        assertTrue(service.contains("MediaSession.Builder(this, shown)"))
        assertTrue(service.contains("WaitingSessionPlayer(player) { recovery.masksError }"))
        assertFalse("Riff's own code does not read the session's view of the player", service.contains("session?.player"))
    }
}
