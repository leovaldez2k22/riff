package com.musicglass.personal.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shipped Baseline Profile: cold start to the usable shell, one collection scroll, the mini player
 * expanding to the full player, and the queue opening. Fixture content only; nothing here reaches a provider.
 * Run with :app:generateReleaseBaselineProfile (docs/quality/performance.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()
    @Before fun begin() = cleanSlate()

    @Test fun shellPlayerQueueAndCollection() = rule.collect(packageName = TARGET) {
        plainFeed(); feed("tracks", "200")
        pressHome()
        startActivityAndWait()
        home()
        scrollScreen()
        openPlaylist()
        playPlaylist()
        scrollScreen("playlist_detail")
        openPlayer()
        openQueue()
        scrollScreen("queue_list")
        closeQueue()
        closePlayer()
    }
}
