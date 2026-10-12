package com.musicglass.personal.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The budget "no network and no avoidable disk access on the main thread in a warmed journey". The fixture
 * feed turns on the platform's thread policy (its "strict" switch), every screen of the journey is visited once
 * to warm it, and then the journey is made again; whatever the policy reports during that second pass fails
 * this test, with the kinds and the first stacks in the message. Nothing is timed. Install the nonMinified
 * variant of the app for stacks that can be read.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadAudit {
    @Test fun aWarmedJourneyKeepsDiskAndNetworkOffTheMainThread() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("androidx.benchmark.enabledRules") == null)
        cleanSlate()
        MacrobenchmarkScope(TARGET, launchWithClearTask = true).run {
            try {
                feed("strict", "on"); feed("tracks", "200")
                coldHome(); scrollScreen()
                openPlaylist(); playPlaylist(); journey()
                device.executeShellCommand("log -t RiffBench warmed")
                tap("tab_library"); tap("tab_home"); home(); scrollScreen()
                openPlaylist(); journey()
                val log = device.executeShellCommand("logcat -d -v brief StrictMode:D RiffBench:I *:S").substringAfterLast("RiffBench")
                val kinds = Regex("StrictMode policy violation[^\\n]*?: (\\S+)").findAll(log).map { it.groupValues[1].substringAfterLast('.') }.toList()
                check(kinds.isEmpty()) {
                    "${kinds.size} main-thread violations in the warmed pass: ${kinds.groupingBy { it }.eachCount()}\n" + log.take(6_000)
                }
            } finally { plainFeed(); killProcess() }
        }
    }

    private fun MacrobenchmarkScope.journey() {
        scrollScreen("playlist_detail")
        openPlayer(); openQueue(); scrollScreen("queue_list"); closeQueue()
        tap("playback_toggle"); paused()
        tap("playback_toggle"); playing()
        tap("playback_next"); device.waitForIdle()
        closePlayer()
    }
}
