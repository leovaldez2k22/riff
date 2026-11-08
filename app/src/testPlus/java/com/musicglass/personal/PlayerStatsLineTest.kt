package com.musicglass.personal

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerStatsLineTest {
    @Test fun theStatsLineReadsLikeBitChords() {
        val stats = Bundle().apply { putString("mime", "audio/opus"); putInt("sampleRate", 48_000); putInt("formatBitrate", 270_000); putInt("channels", 2) }
        assertEquals("Opus · 48.0 kHz · 270 kbps · Stereo", nerdStatsLine(stats))
        assertNull(nerdStatsLine(Bundle()))
        assertEquals("AAC", codecName("audio/mp4a-latm"))
    }
}
