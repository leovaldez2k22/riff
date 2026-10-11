package com.musicglass.personal

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Media3 pipeline on Android. Decoder deadlines always use real monotonic time. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class FixturePlaybackTest {
    @Test fun loopbackAudioPreparesAdvancesAndSeeks() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val audio=instrumentation.context.assets.open("quality/audio/tone-a.wav").use {it.readBytes()}
        FixtureHttpServer(mapOf("/tone-a.wav" to FixtureHttpServer.Reply(audio))).use {server->
            val prepared=CountDownLatch(1);val failure=AtomicReference<PlaybackException?>()
            lateinit var player:ExoPlayer
            var created=false
            try {
                instrumentation.runOnMainSync {
                    player=ExoPlayer.Builder(instrumentation.targetContext).build();created=true
                    player.volume=0f
                    player.addListener(object:Player.Listener {
                        override fun onPlaybackStateChanged(state:Int) {if(state==Player.STATE_READY) prepared.countDown()}
                        override fun onPlayerError(error:PlaybackException) {failure.set(error);prepared.countDown()}
                    })
                    player.setMediaItem(MediaItem.fromUri(server.baseUrl+"/tone-a.wav"))
                    player.prepare();player.play()
                }
                assertTrue("Decoder did not prepare within 10s",prepared.await(10,TimeUnit.SECONDS))
                assertNull("Decoder failure",failure.get())
                val deadline=SystemClock.elapsedRealtime()+8000
                var position=0L;var duration=0L
                while(SystemClock.elapsedRealtime()<deadline && position<500) {
                    instrumentation.runOnMainSync {position=player.currentPosition;duration=player.duration}
                    Thread.sleep(50)
                }
                assertTrue("Real playback position did not advance: $position",position in 500..8000)
                assertTrue("Fixture duration differs: $duration",duration in 29_900..30_100)
                instrumentation.runOnMainSync {player.seekTo(10_000)}
                val seekDeadline=SystemClock.elapsedRealtime()+5000
                do {
                    instrumentation.runOnMainSync {position=player.currentPosition}
                    if(position in 9_900..14_000) break
                    Thread.sleep(50)
                } while(SystemClock.elapsedRealtime()<seekDeadline)
                assertTrue("Seek did not reach fixture position: $position",position in 9_900..14_000)
                assertNull(failure.get())
            } finally {instrumentation.runOnMainSync {if(created) player.release()}}
        }
    }
}
