package com.musicglass.personal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqualizerTest {
    private val rate = 48_000

    private fun sine(frequency: Double, frames: Int, channels: Int = 2, amplitude: Double = .25): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * frequency * i / rate) * amplitude * 32767).toInt().toShort()
            repeat(channels) { buffer.putShort(v) }
        }
        buffer.flip(); return buffer
    }

    private fun run(eq: PcmEqualizer, input: ByteBuffer): ByteBuffer {
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        eq.process(input, output); output.flip(); return output
    }

    /** RMS in dB of one channel over the last half of [buffer], after the filters have settled. */
    private fun rmsDb(buffer: ByteBuffer, channel: Int = 0, channels: Int = 2): Double {
        val frames = buffer.remaining() / (2 * channels); var sum = 0.0; var n = 0
        for (i in frames / 2 until frames) { val v = buffer.getShort((i * channels + channel) * 2) / 32768.0; sum += v * v; n++ }
        return 20 * log10(sqrt(sum / n))
    }

    private fun gainAt(config: EqualizerConfig, frequency: Double, channel: Int = 0): Double {
        val flat = PcmEqualizer().apply { configure(rate, 2) }
        val eq = PcmEqualizer().apply { this.config = config; configure(rate, 2) }
        val tone = sine(frequency, rate / 2)
        return rmsDb(run(eq, tone.duplicate()), channel) - rmsDb(run(flat, tone), channel)
    }

    @Test fun offIsBitExact() {
        val eq = PcmEqualizer().apply { configure(rate, 2) }
        val input = sine(440.0, 4_800)
        val output = run(eq, input.duplicate())
        assertEquals(input, output)
    }

    @Test fun onButFlatIsBitExact() {
        val eq = PcmEqualizer().apply { config = EqualizerConfig(enabled = true); configure(rate, 2) }
        val input = sine(1_000.0, 4_800)
        assertEquals(input, run(eq, input.duplicate()))
    }

    @Test fun manualBellBoostsItsBandAndLeavesOthers() {
        val manual = listOf(0f, 0f, 0f, 6f, 0f, 0f, 0f)
        val config = EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = manual)
        val preamp = preampFor(eqSections(config), rate).toDouble()
        assertEquals(6 + preamp, gainAt(config, 1_000.0), .3)
        assertEquals(preamp, gainAt(config, 60.0), .5)
        assertEquals(preamp, gainAt(config, 14_000.0), .5)
    }

    @Test fun processorMatchesThePredictedCurve() {
        val config = EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = listOf(-6f, 3f, 0f, -4f, 0f, 5f, 8f))
        val sections = eqSections(config); val preamp = preampFor(sections, rate)
        for (f in listOf(60.0, 400.0, 1_000.0, 3_000.0, 9_000.0)) {
            val predicted = sections.sumOf { sectionGainDb(it, f.toFloat(), rate).toDouble() } + preamp
            assertEquals("at $f Hz", predicted, gainAt(config, f), .4)
        }
    }

    @Test fun toneShelvesTiltTheSound() {
        val bright = EqualizerConfig(enabled = true, toneX = 5)
        assertTrue(gainAt(bright, 8_000.0) - gainAt(bright, 80.0) > 10)
        val warm = EqualizerConfig(enabled = true, toneX = -5)
        assertTrue(gainAt(warm, 80.0) - gainAt(warm, 8_000.0) > 10)
    }

    @Test fun focusedContourIsNarrowerThanBroad() {
        val focused = EqualizerConfig(enabled = true, toneY = 5, focused = true)
        val broad = focused.copy(focused = false)
        val preamp = preampFor(eqSections(focused), rate).toDouble()
        assertEquals(6 + preamp, gainAt(focused, 1_000.0), .3)
        assertTrue(gainAt(focused, 250.0) - preamp < gainAt(broad, 250.0) - preampFor(eqSections(broad), rate))
    }

    @Test fun preampKeepsThePeakAtZero() {
        val config = EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = List(7) { 12f })
        val sections = eqSections(config)
        val peak = eqCurve(sections).max() + preampFor(sections)
        assertEquals(0f, peak, .01f)
        assertEquals(0f, preampFor(eqSections(EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = List(7) { -6f }))), 0f)
    }

    @Test fun loudInputClampsInsteadOfWrapping() {
        val eq = PcmEqualizer().apply { config = EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = listOf(0f, 0f, 0f, 12f, 0f, 0f, 0f)); configure(rate, 1) }
        val out = run(eq, sine(1_000.0, 9_600, channels = 1, amplitude = 1.0))
        var previous = 0
        for (i in 0 until out.remaining() / 2) {
            val v = out.getShort(i * 2).toInt(); assertTrue(abs(v - previous) < 20_000); previous = v
        }
    }

    @Test fun balanceTurnsOneSideDown() {
        val config = EqualizerConfig(enabled = true, balance = 1f)
        assertTrue(gainAt(config, 1_000.0, channel = 0) < -30)
        assertEquals(0.0, gainAt(config, 1_000.0, channel = 1), .1)
        assertEquals(1f to 1f, balanceGains(0f))
        val (l, r) = balanceGains(.3f); assertEquals(1f, r, 0f); assertTrue(l < 1f)
    }

    @Test fun aChangeGlidesWithoutAJump() {
        val eq = PcmEqualizer().apply { config = EqualizerConfig(enabled = true, mode = EqMode.Manual); configure(rate, 1) }
        run(eq, sine(60.0, 4_800, channels = 1))
        eq.config = EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = listOf(12f, 0f, 0f, 0f, 0f, 0f, 0f))
        val out = run(eq, sine(60.0, 48, channels = 1))
        // Within the first millisecond the low shelf has barely moved: no step in level.
        assertTrue(abs(out.getShort(0).toInt()) < 2_000)
    }

    @Test fun settingsRoundTripAndPresets() {
        assertEquals(List(7) { 0f }, EqualizerSettings.parseManual(null))
        assertEquals(listOf(1f, -2.5f, 12f, -12f, 0f, 0f, 0f), EqualizerSettings.parseManual("1,-2.5,40,-40,x"))
        assertEquals("Rock", presetFor(EQ_PRESETS.getValue("Rock")))
        assertEquals("Custom", presetFor(listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)))
        assertEquals(0f, manualStep(.6f)); assertEquals(3.5f, manualStep(3.4f)); assertEquals(12f, manualStep(20f))
        assertEquals("+6", eqLabel(6f)); assertEquals("−3.5", eqLabel(-3.5f)); assertEquals("0", eqLabel(0f))
    }
}
