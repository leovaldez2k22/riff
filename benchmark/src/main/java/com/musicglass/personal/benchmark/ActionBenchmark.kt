package com.musicglass.personal.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Player and queue actions, each timed by the app from its handler to the frame that shows the result, and the
 * ways playback is prepared: a long queue, a downloaded bundle, a controlled connection and an unlimited one. The sections are PerfTrace's; docs/quality/performance.md lists them.
 */
@RunWith(AndroidJUnit4::class)
class ActionBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()
    @Before fun begin() = cleanSlate()
    private val shipped = CompilationMode.Partial(BaselineProfileMode.Require)

    /** 30 of each action: six measured blocks of five. */
    @Test fun playerAndQueueActions() = rule.measureRepeated(
        packageName = TARGET, compilationMode = shipped, iterations = iterations(6),
        metrics = listOf("player_open", "queue_open", "queue_close", "play_toggle", "skip", "player_close").map(::SectionMetric),
        // Twelve songs, so each of the five Nexts has a song to go to: the fixture's four left the last two with none.
        setupBlock = { plainFeed(); feed("tracks", "12"); coldHome(); openPlaylist(); playPlaylist() },
    ) {
        repeat(5) {
            openPlayer(); openQueue(); closeQueue()
            tap("playback_toggle"); paused()
            tap("playback_toggle"); playing()
            tap("playback_next"); device.waitForIdle()
            closePlayer()
        }
    }

    /** A 2,000 entry collection into the queue, 10 times. queue_io_* is off the main thread, queue_main on it. */
    @Test fun queueOfTwoThousand() = rule.measureRepeated(
        packageName = TARGET, compilationMode = shipped, iterations = iterations(10),
        metrics = listOf("queue_prepare", "queue_io_encode", "queue_io_decode", "queue_main", "first_audio").map(::SectionMetric),
        setupBlock = { plainFeed(); feed("tracks", "2000"); coldHome(); openPlaylist() },
    ) { playPlaylist() }

    /** First audio over the controlled connection: 100 ms before each answer, 5 Mbit/s. 20 starts. */
    @Test fun firstAudioOnline() = rule.measureRepeated(
        packageName = TARGET, compilationMode = shipped, iterations = iterations(20),
        metrics = listOf(SectionMetric("first_audio")),
        setupBlock = { plainFeed(); feed("latency", "100"); feed("rate", "625000"); coldHome(); openPlaylist() },
    ) { playPlaylist() }

    /**
     * First audio from complete local bundles: the playlist is downloaded once, then each of the 20 starts is a
     * cold process playing from Downloads. The feed's server takes no part in the measured block.
     */
    @Test fun firstAudioLocalBundle() {
        var downloaded = false
        rule.measureRepeated(
            packageName = TARGET, compilationMode = shipped, iterations = iterations(20),
            metrics = listOf(SectionMetric("first_audio")),
            setupBlock = {
                plainFeed(); coldHome()
                if (!downloaded) { downloadPlaylist(); downloaded = true; coldHome() }
                openDownloads()
            },
        ) { playDownloads() }
    }

    /** First audio with nothing in the way: the feed's loopback server, no latency and no pacing. 20 starts. */
    @Test fun firstAudioUnthrottled() = rule.measureRepeated(
        packageName = TARGET, compilationMode = shipped, iterations = iterations(20),
        metrics = listOf(SectionMetric("first_audio")),
        setupBlock = { plainFeed(); coldHome(); openPlaylist() },
    ) { playPlaylist() }
}
