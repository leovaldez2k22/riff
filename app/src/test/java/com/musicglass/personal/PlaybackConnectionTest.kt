package com.musicglass.personal

import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class PlaybackConnectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("Controller did not reach expected state")
            Thread.sleep(5)
        }
    }

    @Test fun backgroundServiceDeathRebindsOnForegroundAndQueuedPauseWins() {
        ProviderWorld(context).use { world ->
            val first = ExoPlayer.Builder(context).build()
            val second = ExoPlayer.Builder(context).build()
            var session = MediaSession.Builder(context, first).setId("first").build()
            var builds = 0
            val gateway = MusicPlaybackGateway(context, world.runtime.copy(dispatchers = AppDispatchers())) { listener ->
                builds++
                MediaController.Builder(context, session.token).setListener(listener).buildAsync()
            }
            try {
                until { gateway.state.ready }
                gateway.backgrounded()
                session.release()
                until { !gateway.state.ready }
                assertEquals(1, builds)
                session = MediaSession.Builder(context, second).setId("second").build()
                second.setMediaItem(MediaItem.fromUri("file:///fixture.wav"))
                gateway.foregrounded()
                gateway.resume(); gateway.pause()
                until { gateway.state.ready && builds == 2 }
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse("the final user intent survives rebinding", second.playWhenReady)
                gateway.foregrounded()
                assertEquals("foreground must not replace a live controller", 2, builds)
            } finally { gateway.close(); session.release(); first.release(); second.release() }
        }
    }

    @Test fun aCommandFromAFailedConnectionDoesNotRunOnALaterReconnect() {
        ProviderWorld(context).use { world ->
            val player = ExoPlayer.Builder(context).build()
            val session = MediaSession.Builder(context, player).setId("later").build()
            val failed = SettableFuture.create<MediaController>()
            var builds = 0
            val gateway = MusicPlaybackGateway(context, world.runtime.copy(dispatchers = AppDispatchers())) { listener ->
                if (++builds == 1) failed else MediaController.Builder(context, session.token).setListener(listener).buildAsync()
            }
            try {
                player.setMediaItem(MediaItem.fromUri("file:///fixture.wav"))
                gateway.resume()
                failed.setException(IllegalStateException("no service"))
                until { gateway.state.playback.failure != null }
                gateway.foregrounded()
                until { gateway.state.ready }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, builds)
                assertFalse("the failed Play stays failed; the user presses it again", player.playWhenReady)
                assertNull("a working connection clears the notice", gateway.state.playback.failure)
            } finally { gateway.close(); session.release(); player.release() }
        }
    }

    @Test fun closingDuringConnectionCancelsFutureAndCannotReconnect() {
        ProviderWorld(context).use { world ->
            val connection = SettableFuture.create<MediaController>()
            var builds = 0
            val gateway = MusicPlaybackGateway(context, world.runtime.copy(dispatchers = AppDispatchers())) { builds++; connection }
            gateway.resume()
            gateway.close()
            gateway.foregrounded(); gateway.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(connection.isCancelled)
            assertFalse(gateway.state.ready)
            assertEquals(1, builds)
        }
    }
}
