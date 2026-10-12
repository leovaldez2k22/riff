package com.musicglass.personal

import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q12 (UX04, UX05): the position and volume bars say what they are and where they stand, and can be set
 * without a drag. A live stream cannot be moved and says why.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerControlSemanticsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun said(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.let {
        Triple(it.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(), it.getOrNull(SemanticsProperties.StateDescription), it.getOrNull(SemanticsProperties.ProgressBarRangeInfo))
    }

    @Test fun thePositionBarSaysTheTimeAndCanBeSetDirectly() {
        val player = RecordingGateway(PlaybackUiState(ready = true, mediaId = "fxsong00001", durationMs = 200_000, positionMs = 65_000))
        compose.setContent { SeekControl(player.gateway, "fxsong00001", 200_000) }
        val (name, value, range) = said("playback_seek")
        assertEquals("Position", name); assertEquals("1:05 of 3:20", value)
        assertEquals(65_000f, range!!.current); assertEquals(0f..200_000f, range.range)
        compose.onNodeWithTag("playback_seek").assertIsEnabled().performSemanticsAction(SemanticsActions.SetProgress) { it(100_000f) }
        compose.waitForIdle()
        assertEquals(listOf("seekTo(100000)"), player.asked)
    }

    @Test fun aLiveStreamCannotBeMovedAndSaysWhy() {
        val player = RecordingGateway(PlaybackUiState(ready = true, mediaId = "fxlive00001", live = true, positionMs = 42_000))
        compose.setContent { SeekControl(player.gateway, "fxlive00001", 0) }
        assertEquals(LIVE_SEEK_EXPLANATION, said("playback_seek").second)
        compose.onNodeWithTag("playback_seek").assertIsNotEnabled()
        // The reason is also on screen, not only spoken.
        compose.onNodeWithText(LIVE_SEEK_EXPLANATION).assertExists(); compose.onNodeWithText("LIVE").assertExists()
        assertEquals(emptyList<String>(), player.asked)
    }

    @Test fun aSongWhoseLengthIsNotKnownYetCannotBeMoved() {
        val player = RecordingGateway(PlaybackUiState(mediaId = "fxsong00001"))
        compose.setContent { SeekControl(player.gateway, "fxsong00001", 0) }
        compose.onNodeWithTag("playback_seek").assertIsNotEnabled()
    }

    @Test fun theVolumeBarSaysItsLevelAndSetsThePhoneVolume() {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        compose.mainClock.autoAdvance = false
        compose.setContent { AudioOutputControls({}, {}) }
        compose.mainClock.advanceTimeBy(50)
        assertEquals("Volume" to "Muted", said("player_volume").let { it.first to it.second })
        compose.onNodeWithTag("player_volume").performSemanticsAction(SemanticsActions.SetProgress) { it(max.toFloat()) }
        compose.mainClock.advanceTimeBy(50)
        assertEquals(max, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("100 percent", said("player_volume").second)
    }
}
