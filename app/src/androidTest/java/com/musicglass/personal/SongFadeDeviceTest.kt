package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Android rendering check: art and both metadata fields preserve the outgoing content mid-fade. */
class SongFadeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<FeatureTestActivity>()
    @Test fun artworkTitleAndArtistFadeTogether() {
        var state by mutableStateOf(PlaybackUiState(mediaId="old",title="Old title",artist="Old artist"))
        var blend by mutableStateOf<SongCrossfade?>(null)
        compose.mainClock.autoAdvance=false
        compose.setContent {
            CompositionLocalProvider(LocalSongCrossfade provides blend) {
                val transition=rememberSongPresentationTransition(state)
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    transition.SongContentFade(Modifier.testTag("fade_art")) {
                        Box(Modifier.size(96.dp).background(if(it.mediaId=="old") Color.Red else Color.Blue))
                    }
                    transition.SongContentFade {Text(it.title,color=Color.White)}
                    transition.SongContentFade {Text(it.artist,color=Color.White)}
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            blend=SongCrossfade(songPresentation(state),"next",2000).apply {progress=.5f}
            state=state.copy(mediaId="next",title="Next title",artist="Next artist")
        }
        repeat(2) {compose.mainClock.advanceTimeByFrame();compose.waitForIdle()}
        compose.mainClock.advanceTimeBy(1000)
        listOf("Old title","Old artist","Next title","Next artist").forEach {compose.onNodeWithText(it).assertExists()}
        fun center():Color {
            val image=compose.onNodeWithTag("fade_art").captureToImage()
            return image.toPixelMap()[image.width/2,image.height/2]
        }
        val blendedPixel=center()
        assertTrue("Both artwork layers should be visible: red=${blendedPixel.red} blue=${blendedPixel.blue}",blendedPixel.red>.1f && blendedPixel.blue>.1f)
        // The visual state follows live audio progress, not a separately running tween.
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("Old title").assertExists()
        compose.runOnIdle {blend=null}
        compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithText("Old title").assertDoesNotExist()
        compose.onNodeWithText("Old artist").assertDoesNotExist()
        compose.onNodeWithText("Next title").assertIsDisplayed()
        compose.onNodeWithText("Next artist").assertIsDisplayed()
        val finished=center()
        assertTrue("Only incoming artwork should remain",finished.blue>.9f && finished.red<.05f)
    }
}
