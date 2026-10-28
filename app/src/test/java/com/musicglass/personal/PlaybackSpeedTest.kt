package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 15: 0.5× to 2× on 0.05× steps; episodes keep their speed on the phone, songs for as long as Riff runs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackSpeedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun speedsStayInRangeAndOnAStep() {
        assertEquals(1.25f, playbackSpeedStep(1.26f), 0f)
        assertEquals(.5f, playbackSpeedStep(.1f), 0f)
        assertEquals(2f, playbackSpeedStep(3f), 0f)
        assertEquals(1f, playbackSpeedStep(.9999999f), 0f)
    }

    @Test fun labelsAreShort() {
        assertEquals("1×", playbackSpeedLabel(1f))
        assertEquals("1.25×", playbackSpeedLabel(1.25f))
        assertEquals("1.5×", playbackSpeedLabel(1.5f))
        assertEquals("2×", playbackSpeedLabel(2f))
    }

    @Test fun songsAndEpisodesKeepTheirOwnSpeed() {
        context.getSharedPreferences("playback_speed", Context.MODE_PRIVATE).edit().clear().commit()
        val speeds = PlaybackSpeeds(context)
        speeds.remember(music = false, 1.5f)
        speeds.remember(music = true, 1.1f)
        assertEquals(1.5f, speeds.forKind(music = false), 0f)
        assertEquals(1.1f, speeds.forKind(music = true), 0f)
        val afterRestart = PlaybackSpeeds(context)
        assertEquals("an episode's speed is kept", 1.5f, afterRestart.forKind(music = false), 0f)
        assertEquals("a song starts at normal speed again", 1f, afterRestart.forKind(music = true), 0f)
    }
}
