package com.musicglass.personal

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class SongPresentationTest {
    @get:Rule val compose=createComposeRule()

    @Test fun songChangeKeepsOldArtTitleAndArtistUntilTheirSharedFadeCompletes() {
        var state by mutableStateOf(PlaybackUiState(mediaId="old",title="Old song",artist="Old artist",artworkUrl="old cover"))
        var blend by mutableStateOf<SongCrossfade?>(null)
        compose.mainClock.autoAdvance=false
        compose.setContent {
            CompositionLocalProvider(LocalSongCrossfade provides blend) {
            val transition=rememberSongPresentationTransition(state)
            Column {
                transition.SongContentFade {Text(it.title)}
                transition.SongContentFade {Text(it.artist)}
                transition.SongContentFade {Text(it.artworkUrl.orEmpty())}
            }
            }
        }
        compose.runOnIdle {
            blend=SongCrossfade(songPresentation(state),"next",2000).apply {progress=.5f}
            state=state.copy(mediaId="next",title="Next song",artist="Next artist",artworkUrl="next cover")
        }
        repeat(2) {compose.mainClock.advanceTimeByFrame();compose.waitForIdle()}
        listOf("Old song","Old artist","old cover","Next song","Next artist","next cover").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        // Wall/test-clock time cannot finish the UI fade before the audio engine finishes.
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithText("Old song").assertExists()
        compose.runOnIdle {blend=null}
        repeat(2) {compose.mainClock.advanceTimeByFrame();compose.waitForIdle()}
        listOf("Old song","Old artist","old cover").forEach {compose.onNodeWithText(it).assertDoesNotExist()}
        listOf("Next song","Next artist","next cover").forEach {compose.onNodeWithText(it).assertIsDisplayed()}
    }

    @Test fun playbackTicksDoNotChangeSongIdentity() {
        val state=PlaybackUiState(mediaId="song",title="Title",artist="Artist",artworkUrl="cover")
        assertEquals(songPresentation(state),songPresentation(state.copy(positionMs=50_000,playing=true,index=2)))
    }

    @Test fun ordinaryTrackChangesAndManualSkipsDuringABlendReplaceTheSongImmediately() {
        var state by mutableStateOf(PlaybackUiState(mediaId="old",title="Old song"))
        var blend by mutableStateOf<SongCrossfade?>(null)
        compose.setContent {CompositionLocalProvider(LocalSongCrossfade provides blend) {
            rememberSongPresentationTransition(state).SongContentFade {Text(it.title)}
        }}
        val old=songPresentation(state)
        compose.runOnIdle {state=state.copy(mediaId="next",title="Next song")}
        compose.onNodeWithText("Old song").assertDoesNotExist()
        compose.onNodeWithText("Next song").assertIsDisplayed()
        compose.runOnIdle {blend=SongCrossfade(old,"next",6000).apply {progress=.25f}}
        compose.onNodeWithText("Old song").assertExists()
        compose.runOnIdle {state=state.copy(mediaId="manual",title="Manual song")}
        compose.onNodeWithText("Old song").assertDoesNotExist()
        compose.onNodeWithText("Next song").assertDoesNotExist()
        compose.onNodeWithText("Manual song").assertIsDisplayed()
    }

    @Test fun cancelingOrDisablingAnActiveCrossfadeRemovesOutgoingContentImmediately() {
        val outgoing=SongPresentation("old","Old song","Artist",null,0)
        var blend by mutableStateOf<SongCrossfade?>(SongCrossfade(outgoing,"next",6000).apply {progress=.25f})
        compose.setContent {CompositionLocalProvider(LocalSongCrossfade provides blend) {
            rememberSongPresentationTransition(PlaybackUiState(mediaId="next",title="Next song")).SongContentFade {Text(it.title)}
        }}
        compose.onNodeWithText("Old song").assertExists()
        compose.runOnIdle {blend=null}
        compose.onNodeWithText("Old song").assertDoesNotExist()
        compose.onNodeWithText("Next song").assertIsDisplayed()
    }

    @Test fun reducedMotionReplacesContentEvenDuringAnAudioCrossfade() {
        val blend=SongCrossfade(SongPresentation("old","Old song","Artist",null,0),"next",6000)
        compose.setContent {CompositionLocalProvider(LocalSongCrossfade provides blend,
            LocalMotionPolicy provides MotionPolicy(true,1f)) {
            rememberSongPresentationTransition(PlaybackUiState(mediaId="next",title="Next song")).SongContentFade {Text(it.title)}
        }}
        compose.onNodeWithText("Old song").assertDoesNotExist()
        compose.onNodeWithText("Next song").assertIsDisplayed()
    }
}
