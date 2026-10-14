package com.musicglass.personal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Q16A: the held word's pop and glow, the white sweep and the background vocal, drawn for real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LyricsKaraokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = mutableLongStateOf(0)

    // "Go go go" is quick, "true" is held for 1.2 s; the background vocal answers after the line.
    private val line = LyricLine(1000, 3100, "Go go go true", listOf(LyricPart(1000, 1300, 0, 2), LyricPart(1300, 1600, 3, 5),
        LyricPart(1600, 1900, 6, 8), LyricPart(1900, 3100, 9, 13)),
        LyricBacking(3200, 4200, "(true)", listOf(LyricPart(3200, 4200, 0, 6))))

    private fun show(reduced: Boolean, backingActive: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                KaraokeLine(line, active = true, backingActive = backingActive, position = { now.longValue },
                    motion = MotionPolicy(reduced, if (reduced) 0f else 1f), backingTag = "backing", modifier = Modifier.testTag("line"))
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun at(ms: Long): Bitmap {
        now.longValue = ms
        compose.mainClock.advanceTimeBy(32)
        val view = compose.activity.findViewById<View>(android.R.id.content)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /** Glow: clearly pink, not the white or grey of letters. */
    private fun pink(bitmap: Bitmap) = pixels(bitmap) { r, g, b -> r > 90 && r - g > 40 && b - g > 10 }
    private fun pixels(bitmap: Bitmap, test: (Int, Int, Int) -> Boolean): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            if (test((c shr 16) and 255, (c shr 8) and 255, c and 255)) count++
        }
        return count
    }
    /** The top of the white letters (sung and lit) in the main line's band. */
    private fun topOfLit(bitmap: Bitmap): Int {
        for (y in 0 until bitmap.height / 3) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            if (minOf((c shr 16) and 255, (c shr 8) and 255, c and 255) > 225) return y
        }
        return -1
    }

    @Test fun aHeldWordGlowsAndRisesWhileSungAndNotBeforeOrAfter() {
        show(reduced = false)
        val before = at(1200)            // "Go" is being sung; "true" has not started
        val held = at(2700)              // well into "true"
        val after = at(3100 + HELD_RELEASE_MS + 100)
        assertEquals("no pink before the held word: the sweep is white", 0, pink(before))
        assertTrue("the held word glows pink while sung", pink(held) > 200)
        assertEquals("the glow is gone once it has settled", 0, pink(after))
        assertTrue("the held word rises and grows above the line's lit letters", topOfLit(held) < topOfLit(after) - 2)
    }

    @Test fun reducedMotionKeepsTheGlowButNotTheRise() {
        show(reduced = true)
        val held = at(2700)
        val after = at(3100 + HELD_RELEASE_MS + 100)
        assertTrue(pink(held) > 200)
        assertEquals("no rise or growth under reduced motion", topOfLit(after), topOfLit(held))
    }

    @Test fun theBackgroundVocalIsItsOwnSmallerLineAndTheLineSaysItIsCurrent() {
        show(reduced = false, backingActive = true)
        compose.onNodeWithTag("backing", useUnmergedTree = true).assertExists()
        val description = compose.onNodeWithTag("line").fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
        assertEquals(string(R.string.current_lyric), description)
        val main = compose.onNodeWithTag("line").fetchSemanticsNode().boundsInRoot
        val backing = compose.onNodeWithTag("backing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("under the main line", backing.top >= main.top + (main.height - backing.height) / 2)
        // Lit on its own timing: white letters appear in its band once it is sung.
        val sung = at(4100)
        var white = 0
        for (y in backing.top.toInt() until backing.bottom.toInt().coerceAtMost(sung.height))
            for (x in 0 until sung.width) { val c = sung.getPixel(x, y); if (minOf((c shr 16) and 255, (c shr 8) and 255, c and 255) > 225) white++ }
        assertTrue("the background vocal is lit", white > 50)
    }
}
