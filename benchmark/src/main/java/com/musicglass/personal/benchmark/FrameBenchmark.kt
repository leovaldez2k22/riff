package com.musicglass.personal.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frames over five warmed loops of each journey. The app is started and taken to the journey's first screen
 * before measuring; the loop is run once unmeasured, then measured. missedDeadlinePercent is the budgeted
 * number; the display's refresh rate during the run decides which budget it is held to (60 Hz or high refresh).
 */
@RunWith(AndroidJUnit4::class)
class FrameBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @Before fun begin() = cleanSlate()

    @Test fun homeScroll() = frames(CompilationMode.Partial(BaselineProfileMode.Require), {}) { scrollScreen() }
    @Test fun homeScrollWithoutProfile() = frames(CompilationMode.None(), {}) { scrollScreen() }

    @Test fun collectionScroll() = frames(CompilationMode.Partial(BaselineProfileMode.Require), { openPlaylist() }) { scrollScreen("playlist_detail") }

    @Test fun playerAndQueue() = frames(CompilationMode.Partial(BaselineProfileMode.Require), { openPlaylist(); playPlaylist() }) {
        openPlayer(); openQueue(); scrollScreen("queue_list"); closeQueue(); closePlayer()
    }

    private fun frames(mode: CompilationMode, arrive: MacrobenchmarkScope.() -> Unit, loop: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = TARGET, metrics = listOf(FrameTimingMetric(), MissedDeadlineMetric()), compilationMode = mode,
        iterations = iterations(5),
        setupBlock = {
            // A long playlist, so that the collection and the queue have something to scroll.
            plainFeed(); feed("tracks", "200")
            coldHome(); arrive(); loop()
        },
    ) { loop() }
}
