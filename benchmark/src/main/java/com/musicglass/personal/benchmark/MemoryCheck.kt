package com.musicglass.personal.benchmark

import android.os.Bundle
import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The budget "retained memory grows by no more than 10% or 20 MiB, whichever is larger", over a short run: the
 * journey is made twice to settle, the app's total PSS is read, the journey is made ten more times, and PSS is
 * read again, each time after ten seconds in the background. The two readings go to the instrumentation output as riff.memory. This is not the soak: hours of
 * playback are a separate, physical-device measurement (docs/quality/performance.md).
 */
@RunWith(AndroidJUnit4::class)
class MemoryCheck {
    @Test fun tenMoreJourneysStayInsideTheGrowthBudget() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("androidx.benchmark.enabledRules") == null)
        cleanSlate()
        MacrobenchmarkScope(TARGET, launchWithClearTask = true).run {
            try {
                feed("tracks", "200")
                coldHome(); openPlaylist(); playPlaylist()
                repeat(2) { journey() }
                // More blocks than the budget needs when asked (-e riff.memoryBlocks 3): growth that levels off is
                // a cache filling, growth that keeps its pace is a leak.
                val blocks = InstrumentationRegistry.getArguments().getString("riff.memoryBlocks")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
                val readings = mutableListOf(memory())
                repeat(blocks) { repeat(10) { journey() }; readings += memory() }
                val before = readings.first().first; val after = readings[1].first
                val allowed = maxOf(before / 10, 20 * 1024)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("riff.memory", "pssBeforeKb=$before pssAfterKb=$after grewKb=${after - before} allowedKb=$allowed")
                    putString("riff.memory.readings", readings.joinToString(" | ") { "${it.first} kB (${it.second})" })
                })
                check(after - before <= allowed) { "PSS grew ${after - before} kB over ten journeys ($before to $after); $allowed kB is allowed" }
            } finally { plainFeed(); killProcess() }
        }
    }

    private fun MacrobenchmarkScope.journey() {
        scrollScreen("playlist_detail")
        openPlayer(); openQueue(); scrollScreen("queue_list"); closeQueue()
        tap("playback_next"); device.waitForIdle()
        closePlayer()
    }

    /**
     * Total proportional set size of the app's process, and where it is. Read with the app in the background for
     * a while: that is when the runtime collects and trims its heap, so what is left is what the app holds on to
     * and not what it has yet to throw away. Read in the foreground, the Java heap alone moved the total by
     * 10 MB from one reading to the next.
     */
    private fun MacrobenchmarkScope.memory(): Pair<Int, String> {
        pressHome()
        SystemClock.sleep(10_000)
        val report = device.executeShellCommand("dumpsys meminfo $TARGET")
        openTarget(device, clearTask = false)
        shown("playlist_detail")
        val total = Regex("TOTAL PSS:\\s+(\\d+)").find(report) ?: Regex("TOTAL\\s+(\\d+)").find(report)
        val parts = listOf("Java Heap", "Native Heap", "Graphics", "Code").mapNotNull { name ->
            Regex("$name:\\s+(\\d+)").find(report)?.let { "$name ${it.groupValues[1]}" }
        }
        return checkNotNull(total) { "No total in dumpsys meminfo" }.groupValues[1].toInt() to parts.joinToString(", ")
    }
}
