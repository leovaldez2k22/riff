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

    @Test fun theMainPlayerAndTheCrossfadeTailBothKeepIt() {
        val source = File("src/main/java/com/musicglass/personal")
        for (name in listOf("MusicPlaybackService.kt", "AudioTransitionCoordinator.kt")) {
            val builders = Regex("""ExoPlayer\.Builder\([^\n]*""").findAll(File(source, name).readText()).map { it.value }.toList()
            assertTrue(name, builders.isNotEmpty())
            builders.forEach { assertTrue("$name: $it", it.contains(if (name == "MusicPlaybackService.kt") ".setLoadControl(riffWholeSongLoadControl(this))" else ".setLoadControl(riffLoadControl())")) }
        }
    }

    @Test fun theWholeSongControlPassesEverySettingOn() {
        // Kotlin's delegation skipped Java's default methods, and Media3 threw when the player was made (phone, 9 November 2026).
        val inner = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBackBuffer(RIFF_BACK_BUFFER_MS, true).build()
        val control = WholeSongLoadControl(inner) { false }
        assertEquals(5 * 60_000_000L, control.getBackBufferDurationUs(PlayerId.UNSET))
        assertTrue(control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        // DefaultLoadControl may hand each call its own allocator before a player is prepared: what matters is that one comes back.
        org.junit.Assert.assertNotNull(control.getAllocator(PlayerId.UNSET))
    }
}
