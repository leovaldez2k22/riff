package com.musicglass.personal

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F2: choosing lyric lines, the hold that starts it, and the card they become. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsShareTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun linesAreChosenAboveOrBelowTheFirstUpToFive() {
        assertEquals(10..10, lyricShareSelection(anchor = 10, tapped = 10, lineCount = 40))
        assertEquals(10..13, lyricShareSelection(10, 13, 40))
        assertEquals(7..10, lyricShareSelection(10, 7, 40))
        // Further than five lines away: the five nearest the first line, in that direction.
        assertEquals(10..14, lyricShareSelection(10, 30, 40))
        assertEquals(6..10, lyricShareSelection(10, 0, 40))
        // A tap back inside a choice shrinks it.
        assertEquals(10..11, lyricShareSelection(10, 11, 40))
        // Near the ends of the song.
        assertEquals(38..39, lyricShareSelection(38, 45, 40))
        assertEquals(0..1, lyricShareSelection(1, -3, 40))
    }

    @Test fun blankLinesPutNothingOnTheCardAndBackingVocalsStandIn() {
        assertEquals("Hello", lyricShareText(LyricLine(0, 1, "  Hello ")))
        assertNull(lyricShareText(LyricLine(0, 1, " ")))
        assertEquals("(ooh)", lyricShareText(LyricLine(0, 1, "", backing = LyricBacking(0, 1, "(ooh)"))))
    }

    @Test fun theCardCarriesTheWordsAndRiffsPinkWavesBottomRight() {
        val card = renderLyricCard(listOf("Is this the real life?", "Is this just fantasy?"), "A song", "An artist",
            LyricCardLook.Dark, 0xFF336699.toInt())
        assertEquals(1080, card.width)
        assertEquals(1080, card.height)
        // Riff's waves, pink, sit in the bottom-right corner; the song's name in the bottom left is white, not pink.
        assertTrue(count(card, 760, 1080, 860, 1080) { r, g, b -> r > 200 && g < 140 && b > 110 } > 400)
        assertEquals(0, count(card, 0, 400, 860, 1080) { r, g, b -> r > 200 && g < 140 && b > 110 })
        // The words are white on the dark look.
        assertTrue(count(card, 0, 1080, 200, 860) { r, g, b -> r > 230 && g > 230 && b > 230 } > 2_000)
    }

    @Test fun theRiffPinkLookIsPinkWithDarkWordsAndFiveLongLinesMakeItTaller() {
        val pink = renderLyricCard(listOf("One"), "Song", "Artist", LyricCardLook.Pink, 0)
        val corner = pink.getPixel(20, 20)
        assertTrue((corner shr 16 and 255) > 220 && (corner shr 8 and 255) < 150)
        val long = renderLyricCard(List(5) { "A very long line of words that will wrap across the card more than once" },
            "Song", "Artist", LyricCardLook.Cover, 0xFF336699.toInt())
        assertTrue(long.height > long.width)
    }

    @Test fun aTapStillSeeksAndOnlyAHoldStartsSharing() {
        var taps = 0; var holds = 0
        compose.setContent {
            CompositionLocalProvider(LocalViewConfiguration provides rememberLyricHoldConfiguration()) {
                Column {
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("line")
                        .lyricLineTouch({ taps++ }, "Play from this line", { holds++ }, "Share lyrics"))
                }
            }
        }
        compose.onNodeWithTag("line").performClick()
        compose.waitForIdle()
        assertEquals(1 to 0, taps to holds)
        compose.onNodeWithTag("line").performTouchInput { longClick(durationMillis = LYRIC_HOLD_MS + 200) }
        compose.waitForIdle()
        assertEquals(1 to 1, taps to holds)
        // TalkBack has both, with their own names.
        val node = compose.onNodeWithTag("line").fetchSemanticsNode()
        assertEquals("Play from this line", node.config[SemanticsActions.OnClick].label)
        assertEquals("Share lyrics", node.config[SemanticsActions.OnLongClick].label)
    }

    @Test fun aHoldShorterThanRiffsHoldTimeIsNotALongPress() {
        var holds = 0
        compose.setContent {
            CompositionLocalProvider(LocalViewConfiguration provides rememberLyricHoldConfiguration()) {
                Box(Modifier.fillMaxWidth().height(80.dp).testTag("line").lyricLineTouch(null, "", { holds++ }, "Share lyrics"))
            }
        }
        compose.onNodeWithTag("line").performTouchInput { down(center); advanceEventTime(450); up() }
        compose.waitForIdle()
        assertEquals(0, holds)
    }

    private fun count(bitmap: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bitmap.getPixel(x, y)
            if (test(c shr 16 and 255, c shr 8 and 255, c and 255)) n++
        }
        return n
    }
}
