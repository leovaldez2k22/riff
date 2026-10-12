package com.musicglass.personal.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every step the benchmarks take, with nothing measured and nothing traced. It answers one question: do the
 * journeys still find their way through the app? It runs where a benchmark cannot, such as an emulator whose
 * tracing does not work, and it is quick enough to run after a change to a screen.
 */
@RunWith(AndroidJUnit4::class)
class JourneyCheck {
    private fun journey(block: MacrobenchmarkScope.() -> Unit) {
        // Not during profile generation or a run narrowed to one kind of rule: those are not asking for this.
        assumeTrue(InstrumentationRegistry.getArguments().getString("androidx.benchmark.enabledRules") == null)
        cleanSlate()
        MacrobenchmarkScope(TARGET, launchWithClearTask = true).run {
            try { plainFeed(); block() } finally { plainFeed(); killProcess() }
        }
    }

    @Test fun coldStartReachesTheFixtureHome() = journey { pressHome(); coldHome(); scrollScreen() }

    @Test fun playerAndQueueActions() = journey {
        coldHome(); openPlaylist(); playPlaylist()
        openPlayer(); openQueue(); closeQueue()
        tap("playback_toggle"); paused()
        tap("playback_toggle"); playing()
        tap("playback_next"); device.waitForIdle()
        closePlayer()
    }

    @Test fun aLongCollectionScrollsAndItsQueueOpens() = journey {
        feed("tracks", "200")
        // Play first: after a scroll the Play button need not be back where a tap reaches it.
        coldHome(); openPlaylist(); playPlaylist(); scrollScreen("playlist_detail")
        openPlayer(); openQueue(); scrollScreen("queue_list"); closeQueue(); closePlayer()
    }

    @Test fun aQueueOfTwoThousandStartsPlaying() = journey { feed("tracks", "2000"); coldHome(); openPlaylist(); playPlaylist() }

    @Test fun aDownloadedPlaylistPlaysFromItsBundles() = journey {
        coldHome(); downloadPlaylist()
        coldHome(); openDownloads(); playDownloads()
    }

    @Test fun playbackStartsOverTheControlledConnection() = journey {
        feed("latency", "100"); feed("rate", "625000")
        coldHome(); openPlaylist(); playPlaylist()
    }
}
