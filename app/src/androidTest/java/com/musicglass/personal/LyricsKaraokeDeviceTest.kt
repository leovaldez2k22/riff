package com.musicglass.personal

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Q16A on a device: a synthetic word-timed song drawn by the real karaoke renderer on the device's own graphics.
 * The lines follow the reference the user gave: "Find your dreams come true" with "true" held, an answering line
 * that overlaps it, and a background vocal. No provider, no network.
 */
class LyricsKaraokeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FeatureTestActivity>()

    private val song = listOf(
        LyricLine(1000, 3700, "Find your dreams come true", listOf(LyricPart(1000, 1300, 0, 4), LyricPart(1300, 1600, 5, 9),
            LyricPart(1600, 2000, 10, 16), LyricPart(2000, 2300, 17, 21), LyricPart(2300, 3700, 22, 26))),
        // Starts while "true" is still held: both lines are lit together.
        LyricLine(3000, 4800, "And I wonder", listOf(LyricPart(3000, 3300, 0, 3), LyricPart(3300, 3600, 4, 5),
            LyricPart(3600, 4800, 6, 12)),
            LyricBacking(4200, 5600, "(I wonder)", listOf(LyricPart(4200, 4600, 0, 2), LyricPart(4600, 5600, 3, 10)))),
        LyricLine(6000, 7600, "If you know what it means", listOf(LyricPart(6000, 6300, 0, 2), LyricPart(6300, 6600, 3, 6),
            LyricPart(6600, 6900, 7, 11), LyricPart(6900, 7200, 12, 16), LyricPart(7200, 7400, 17, 19), LyricPart(7400, 7600, 20, 25))))

    private fun show(position: () -> Long) = compose.setContent {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF231524), Color(0xFF0B0C12), Color.Black)))
            .padding(horizontal = 28.dp, vertical = 48.dp).testTag("lyrics"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            val now = position()
            val active = activeLyricLines(song, now)
            song.forEachIndexed { index, line ->
                KaraokeLine(line, index in active, line.backing?.let { now in it.startMs until it.endMs } == true, position,
                    MotionPolicy(false, 1f), "backing_$index", Modifier.testTag("line_$index"))
            }
        }
    }

    private fun pink(name: String): Int {
        compose.waitForIdle(); Thread.sleep(150)
        val image = compose.onNodeWithTag("lyrics").captureToImage()
        // Kept for the evidence: the target app's external files folder, read back with adb.
        compose.activity.getExternalFilesDir(null)?.resolve("q16a-$name.png")?.outputStream()?.use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = image.toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val c = pixels[x, y]
            // The device draws the glow paler than the JVM renderer: pink, but only 25-40 levels redder than green.
            if (c.red > .27f && c.red - c.green > .10f && c.blue - c.green > .04f) count++
        }
        return count
    }

    /**
     * The numbers go to logcat and to a text file beside the PNGs, not to an instrumentation status: the lane's parser
     * reads every status code as a test case, and a free-standing one fails the run.
     */
    private fun report(name: String, value: String) {
        Log.i("RiffQ16A", "$name: $value")
        compose.activity.getExternalFilesDir(null)?.resolve("$name.txt")?.writeText(value + "\n")
    }

    @Test fun theHeldWordGlowsAndTwoLinesAreCurrentTogether() {
        var now by mutableLongStateOf(1100L)
        show { now }
        val before = pink("before")
        compose.runOnIdle { now = 3200 }              // "true" held, "And" started on the next line
        val held = pink("held")
        val current = (0..2).count { index ->
            compose.onNodeWithTag("line_$index").fetchSemanticsNode().config
                .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { null } != null
        }
        compose.runOnIdle { now = 3700 + HELD_RELEASE_MS + 200 }
        val after = pink("after")
        report("q16a-pixels", "pink before=$before held=$held after=$after current=$current")
        assertTrue("held word glows on the device ($held)", held > 200)
        assertTrue("no glow before the held word ($before)", before < held / 10)
        assertTrue("the glow settles ($after)", after < held / 10)
        assertEquals("both overlapping lines are current", 2, current)
    }

    /** Plays the song in real time, for a screen recording and frame timing; asserts only that frames kept coming. */
    @Test fun playsTheSongInRealTime() {
        var now by mutableLongStateOf(0L)
        show { now }
        // The clock starts once the screen is up, and the song plays twice, so a recording sees it whole.
        compose.waitForIdle(); Thread.sleep(1_500)
        val frames = mutableListOf<Long>()
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 17_000) {
            compose.runOnIdle { now = (SystemClock.uptimeMillis() - start) % 8_500 }
            frames += SystemClock.uptimeMillis()
            Thread.sleep(8)
        }
        val gaps = frames.zipWithNext { a, b -> b - a }.sorted()
        report("q16a-frames", "updates=${frames.size} median=${gaps[gaps.size / 2]}ms p95=${gaps[gaps.size * 95 / 100]}ms")
        assertTrue(frames.size > 100)
    }
}
