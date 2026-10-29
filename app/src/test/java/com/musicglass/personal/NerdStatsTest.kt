package com.musicglass.personal

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 16: the panel reads the service's figures; what the player hasn't reported yet is left out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NerdStatsTest {
    @Test fun deliveryComesFromTheAddressOnly() {
        assertEquals("SABR", nerdDelivery("sabr", null))
        assertEquals("HLS", nerdDelivery("https", "application/x-mpegURL"))
        assertEquals("Direct", nerdDelivery("https", "audio/webm"))
        assertEquals("On this phone", nerdDelivery("content", null))
    }

    @Test fun aFullReadingShowsEveryRow() {
        val rows = nerdStatsRows(Bundle().apply {
            putString("delivery", "SABR"); putInt("itag", 251); putString("mime", "audio/opus"); putString("codecs", "opus")
            putInt("formatBitrate", 0); putInt("streamBitrate", 140_000); putInt("sampleRate", 48_000); putInt("channels", 2)
            putString("decoder", "c2.android.opus.decoder"); putLong("bufferAhead", 12_340); putLong("bandwidth", 4_200_000)
            putInt("underruns", 1); putFloat("speed", 1.25f); putFloat("volume", .5f); putInt("audioSession", 17)
        })
        val byLabel = rows.associate { it.label to it.value }
        assertEquals("251", byLabel[R.string.nerd_itag])
        assertEquals("audio/opus · opus", byLabel[R.string.nerd_codec])
        assertEquals("the stream's bitrate stands in until the decoder reports one", "140 kbps", byLabel[R.string.nerd_bitrate])
        assertEquals("48000 Hz · 2 ch", byLabel[R.string.nerd_sample_rate])
        assertEquals("12.3 s", byLabel[R.string.nerd_buffer])
        assertEquals("4200 kbps", byLabel[R.string.nerd_network])
        assertEquals("1.25×", byLabel[R.string.playback_speed])
        assertEquals("50 %", byLabel[R.string.nerd_volume])
        assertEquals(R.string.nerd_delivery, rows.first().label)
    }

    @Test fun unreportedFiguresAreLeftOut() {
        val labels = nerdStatsRows(Bundle()).map { it.label }
        assertFalse(R.string.nerd_itag in labels)
        assertFalse(R.string.nerd_decoder in labels)
        assertFalse(R.string.nerd_network in labels)
        assertTrue(R.string.nerd_buffer in labels)
    }

    @Test fun theLastSmartBlendIsShownByItsKind() {
        fun row(mode: BlendMode, onBeat: Boolean) = nerdStatsRows(Bundle().apply {
            putString("blendMode", mode.wire); putLong("blendMs", 7_640); putBoolean("blendOnBeat", onBeat) }).last()
        assertEquals(NerdRow(R.string.nerd_blend_smart, "7.6 s"), row(BlendMode.Smart, false))
        assertEquals(NerdRow(R.string.nerd_blend_automix, "7.6 s"), row(BlendMode.Automix, false))
        assertEquals(R.string.nerd_blend_on_beat, row(BlendMode.Automix, true).label)
        assertFalse("none planned yet", nerdStatsRows(Bundle()).any { it.label == R.string.nerd_blend_smart })
    }

    @Test fun copiedTextIsOneLineARow() {
        val text = nerdStatsText(listOf(NerdRow(R.string.nerd_itag, "251"), NerdRow(R.string.nerd_dropouts, "0"))) { if (it == R.string.nerd_itag) "Format" else "Dropouts" }
        assertEquals("Format: 251\nDropouts: 0", text)
    }
}
