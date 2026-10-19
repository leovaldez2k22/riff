package com.musicglass.personal

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Q18-005: both players keep what was just played, so a seek back in a streamed song does not fetch it again. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SeekBackBufferTest {
    @Test fun theLoadControlKeepsFiveMinutesBehindThePlayhead() {
        val control = riffLoadControl()
        assertEquals(5 * 60_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue("Kept from the keyframe before, so a seek inside it needs no new request", control.retainBackBufferFromKeyframe(PlayerId.UNSET))
    }

    @Test fun theMainPlayerAndTheCrossfadeTailBothUseIt() {
        val source = File("src/main/java/com/musicglass/personal")
        for (name in listOf("MusicPlaybackService.kt", "AudioTransitionCoordinator.kt")) {
            val builders = Regex("""ExoPlayer\.Builder\([^\n]*""").findAll(File(source, name).readText()).map { it.value }.toList()
            assertTrue(name, builders.isNotEmpty())
            builders.forEach { assertTrue("$name: $it", it.contains(".setLoadControl(riffLoadControl())")) }
        }
    }
}
