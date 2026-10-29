package com.musicglass.personal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    /** Q18-036: the owner's blue-to-pink card (sampled from the mockup): blue top left, pink bottom right, navy between. */
    @Test fun theAuroraLookGoesFromBlueTopLeftToPinkBottomRightOverNavy() {
        val card = renderLyricCard(listOf("Hold this moment"), "Song", "Artist", LyricCardLook.Aurora, 0)
        fun rgb(x: Int, y: Int) = card.getPixel(x, y).let { Triple(it shr 16 and 255, it shr 8 and 255, it and 255) }
        val (r1, g1, b1) = rgb(30, 30)
        assertTrue("top left is blue: $r1,$g1,$b1", b1 > 170 && b1 > r1 + 80 && b1 > g1 + 60)
        val (r2, g2, b2) = rgb(1050, 1050)
        assertTrue("bottom right is pink: $r2,$g2,$b2", r2 > 170 && r2 > g2 + 80 && b2 > g2)
        for ((x, y) in listOf(1050 to 30, 30 to 1050)) {
            val (r, g, b) = rgb(x, y)
            assertTrue("$x,$y is deep navy: $r,$g,$b", r < 70 && g < 70 && b in 50..130 && b > r)
        }
        // White words.
        assertTrue(count(card, 0, 1080, 200, 860) { r, g, b -> r > 230 && g > 230 && b > 230 } > 2_000)
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

    /**
     * Q18-037: a line's look, chosen or not, must never cut the sung line's first letter. Since the phone checks of 29 Oct
     * (bug 15) the line grows from its start, so the letter stays inside the line's edge rather than past it.
     */
    @Test fun theSungLinesFirstLetterIsNeverCutChosenOrNot() {
        val chosen = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 28.dp)) {
                KaraokeLine(LyricLine(0, 60_000, "Cinnamon in my teeth"), active = true, backingActive = false,
                    position = { 1_000 }, motion = MotionPolicy(false, 1f), backingTag = "backing",
                    modifier = Modifier.testTag("line").lyricLineLook(dim = false, chosen = chosen.value))
            }
        }
        for (isChosen in listOf(false, true)) {
            chosen.value = isChosen
            compose.mainClock.advanceTimeBy(2_000)
            val left = compose.onNodeWithTag("line").fetchSemanticsNode().boundsInRoot.left.toInt()
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            val white = { r: Int, g: Int, b: Int -> minOf(r, g, b) > 150 }
            val outside = count(image, 0, left, 0, image.height, white)
            assertEquals("chosen=$isChosen: nothing of the line crosses its start edge", 0, outside)
            val first = count(image, left, left + 12, 0, image.height, white)
            assertTrue("chosen=$isChosen: the C is drawn at the line's start ($first px)", first > 0)
        }
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
