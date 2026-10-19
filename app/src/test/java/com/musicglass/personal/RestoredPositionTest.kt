package com.musicglass.personal

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q18-019: a queue restored after Riff was stopped is not prepared until Play, so the player knows no length. The
 * saved length is kept with the queue and carried by the restored song, so the player shows where it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class RestoredPositionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val song = SavedQueueItem("yt:fixtureA001", "Fixture Song", "Fixture Artist")

    @Test fun theSelectedSongsLengthIsSavedWithTheQueue() {
        val saved = PlaybackSnapshot(listOf(song), 0, 22_184, durationMs = 233_000)
        assertEquals(saved, decodePlaybackSnapshot(encodePlaybackSnapshot(saved)))
        val older = encodePlaybackSnapshot(saved.copy(durationMs = 0))
        assertFalse("Nothing is written when the length is unknown", older.contains("duration"))
        assertEquals("A queue saved before Q18-019 still reads", 0L, decodePlaybackSnapshot(older)!!.durationMs)
        assertEquals("A live stream has no length", 0L,
            decodePlaybackSnapshot(encodePlaybackSnapshot(saved.copy(queue = listOf(song.copy(live = true)))))!!.durationMs)
        val absurd = encodePlaybackSnapshot(saved).replace("\"duration\":233000", "\"duration\":${MAX_SAVED_DURATION_MS + 1}")
        assertEquals(0L, decodePlaybackSnapshot(absurd)!!.durationMs)
    }

    @Test fun anUnpreparedSongShowsItsCarriedLengthAndASeekBeforePlayMovesIt() {
        val player = ExoPlayer.Builder(context).build()
        try {
            val item = MediaItem.Builder().setMediaId("yt:fixtureA001").setUri("file:///fixture/a.m4a")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Fixture Song").setDurationMs(233_000).build()).build()
            player.setMediaItems(listOf(item), 0, 22_184)
            assertTrue("Not prepared: the player itself knows no length", player.duration <= 0)
            assertEquals(233_000L, knownDurationMs(player))
            assertEquals(22_184L, player.currentPosition)
            player.seekTo(60_000)
            assertEquals("A seek before Play moves the saved position", 60_000L, player.currentPosition)
            player.setMediaItems(listOf(MediaItem.Builder().setMediaId("yt:fixtureB002").setUri("file:///fixture/b.m4a").build()), 0, 0)
            assertEquals("Neither knows: 0, as before", 0L, knownDurationMs(player))
        } finally { player.release() }
    }

    @Test fun theSeekBarShowsTheRestoredPositionNotTheStart() {
        val shown = RecordingGateway(PlaybackUiState(ready = true, mediaId = "yt:fixtureA001", positionMs = 22_184, durationMs = 233_000))
        compose.setContent { SeekControl(shown.gateway, "yt:fixtureA001", 233_000) }
        compose.onNodeWithText("0:22").assertExists()
        compose.onNodeWithText("−3:30").assertExists()
        compose.onNodeWithTag("playback_seek").assertIsEnabled()
    }
}
