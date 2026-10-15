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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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

    private val reduced = mutableStateOf(false)

    private fun show(position: () -> Long) = compose.setContent {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF231524), Color(0xFF0B0C12), Color.Black)))
            .padding(horizontal = 28.dp, vertical = 48.dp).testTag("lyrics"), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            val now = position()
            val active = activeLyricLines(song, now)
            song.forEachIndexed { index, line ->
                KaraokeLine(line, index in active, line.backing?.let { now in it.startMs until it.endMs } == true, position,
                    MotionPolicy(reduced.value, if (reduced.value) 0f else 1f), "backing_$index", Modifier.testTag("line_$index"))
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

    /** Where characters [from, to) of the first line are in the captured image, with room above for the lift. */
    private fun regionOf(from: Int, to: Int): Pair<IntRange, IntRange> {
        val node = compose.onNodeWithText(song[0].text, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.first()
        val origin = compose.onNodeWithTag("lyrics").fetchSemanticsNode().boundsInRoot.topLeft
        val text = node.fetchSemanticsNode().boundsInRoot.topLeft - origin
        val boxes = (from until to).map { layout.getBoundingBox(it) }
        val row = layout.getLineForOffset(from)
        val margin = 12.dp.value * compose.density.density
        return (text.x + boxes.minOf { it.left } - margin / 2).toInt().coerceAtLeast(0)..(text.x + boxes.maxOf { it.right }).toInt() to
            (text.y + layout.getLineTop(row) - margin).toInt().coerceAtLeast(0)..(text.y + layout.getLineBottom(row)).toInt()
    }

    /** The middle of what is drawn in a region, weighted by brightness: it moves with the letters, to a fraction of a pixel. */
    private fun middle(name: String?, region: Pair<IntRange, IntRange>): Double {
        compose.waitForIdle()
        val image = compose.onNodeWithTag("lyrics").captureToImage()
        if (name != null) compose.activity.getExternalFilesDir(null)?.resolve("q16b-$name.png")?.outputStream()?.use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val pixels = image.toPixelMap()
        var sum = 0.0; var weight = 0.0
        for (y in region.second) for (x in region.first) {
            if (x >= pixels.width || y >= pixels.height) continue
            val c = pixels[x, y]
            // The background is a dark gradient; only what stands out from it counts.
            val v = (maxOf(c.red, c.green, c.blue) - .3f).coerceAtLeast(0f).toDouble()
            sum += y * v; weight += v
        }
        return sum / weight
    }

    /** How far the middle of a region wanders over a run of moments. */
    private fun spread(times: LongProgression, region: Pair<IntRange, IntRange>, set: (Long) -> Unit): Double {
        settle { set(times.first) }
        val middles = times.map { time -> compose.runOnIdle { set(time) }; middle(null, region) }
        return middles.max() - middles.min()
    }

    /** Moves the clock and lets the line's own animations (its size and colour as it becomes current) finish. */
    private fun settle(set: () -> Unit) { compose.runOnIdle(set); compose.waitForIdle(); Thread.sleep(700) }

    @Test fun sungLettersStayUpAndAHeldWordsLettersShake() {
        var now by mutableLongStateOf(900L)
        show { now }
        val find = regionOf(0, 4)
        val held = regionOf(22, 23)                     // the "t" of "true"
        val lift = RiffLyrics.letterRise.value * compose.density.density
        settle { }
        val waiting = middle("waiting", find)
        settle { now = 3300 }
        middle("held", find)
        settle { now = 8000 }                            // the line is over and no longer current
        val raised = waiting - middle("over", find)
        // A short run of moments: long enough for the shake to swing both ways, too short for the glow to change much.
        val shaking = spread(3000L..3160L step 10, held) { now = it }
        val still = spread(4200L..4360L step 10, held) { now = it }
        reduced.value = true
        settle { now = 900 }
        val reducedWaiting = middle(null, find)
        settle { now = 8000 }
        val reducedRaised = reducedWaiting - middle(null, find)
        val reducedShaking = spread(3000L..3160L step 10, held) { now = it }
        report("q16b-letters", "lift=${"%.2f".format(lift)}px raised=${"%.2f".format(raised)} shake=${"%.2f".format(shaking)} " +
            "after=${"%.2f".format(still)} reduced_raised=${"%.2f".format(reducedRaised)} reduced_shake=${"%.2f".format(reducedShaking)}")
        assertEquals("sung letters stand ${RiffLyrics.letterRise} higher once the line is over", lift.toDouble(), raised, .5)
        assertTrue("a held word's letters shake while it is sung ($shaking)", shaking > .5)
        assertTrue("and are still once it has ended ($still)", still < .15)
        assertEquals("reduced motion: nothing pops", 0.0, reducedRaised, .3)
        // Under reduced motion only the glow still changes, brightening with the sweep: about 0.2 px on the emulator.
        assertTrue("reduced motion: nothing shakes ($reducedShaking)", reducedShaking < .3)
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
