package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Features 10, 11 and 23 (Riff+, owner Q10, Q11, Q23). Every headphone name and profile here is invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhaseFiveTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun pcm(frames: Int, channels: Int, value: (Int, Int) -> Double): ByteBuffer =
        ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (f in 0 until frames) for (c in 0 until channels) putShort((value(f, c) * 32767).toInt().toShort()); flip() }

    @Test fun theTapOnlyReadsAndIsSilentWhenNothingIsShown() {
        VisualizerTap.stop()
        val audio = pcm(512, 2) { f, c -> if (c == 0) .5 else -.25 + f * 0.0 }
        val before = ByteArray(audio.remaining()).also { audio.duplicate().get(it) }
        VisualizerTap.feed(audio, 2, 48_000)
        assertFalse(VisualizerTap.active); assertEquals(0L, VisualizerTap.written)
        VisualizerTap.start()
        VisualizerTap.feed(audio, 2, 44_100)
        val after = ByteArray(audio.remaining()).also { audio.duplicate().get(it) }
        assertArrayEquals("The sound is not touched", before, after); assertEquals(0, audio.position())
        assertEquals(512L, VisualizerTap.written); assertEquals(44_100, VisualizerTap.sampleRate)
        val window = FloatArray(256)
        assertTrue(VisualizerTap.latest(window))
        // Mono: the two channels averaged.
        assertEquals(((.5 * 32767).toInt() + (-.25 * 32767).toInt()) / (2 * 32768f), window.last(), 1e-6f)
        VisualizerTap.stop(); assertFalse(VisualizerTap.latest(window))
    }

    @Test fun barsRiseWhereTheToneIsAndStayDownForSilence() {
        val bars = SpectrumBars()
        val tone = FloatArray(bars.size) { (.8 * sin(2 * PI * 1_000 * it / 48_000)).toFloat() }
        val levels = bars.compute(tone, 48_000).copyOf()
        val loudest = levels.indices.maxBy { levels[it] }
        val lo = 40.0 * Math.pow(400.0, loudest.toDouble() / bars.bands); val hi = 40.0 * Math.pow(400.0, (loudest + 1.0) / bars.bands)
        assertTrue("1 kHz falls in band $loudest ($lo..$hi)", 1_000.0 in lo * .9..hi * 1.1)
        assertTrue(levels[loudest] > .8f); assertTrue(levels[0] < .3f)
        assertTrue(bars.compute(FloatArray(bars.size), 48_000).all { it == 0f })
    }

    private val parametric = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 180 Hz Gain -2.4 dB Q 0.80
        Filter 3: ON PK Fc 2400 Hz Gain 3.1 dB Q 1.20
        Filter 4: ON PK Fc 5600 Hz Gain -4.0 dB Q 2.10
        Filter 5: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.70
        Filter 6: ON XX Fc 300 Hz Gain 1 dB Q 1
    """.trimIndent()

    @Test fun anAutoEqParametricFileIsReadAndFittedClosely() {
        val target = readAutoEq("Fixture Cans ParametricEQ.txt", parametric)!!
        assertEquals("Fixture Cans", target.name); assertEquals(1, target.skipped)
        val fit48 = fitAutoEq(target, 48_000); val fit44 = fitAutoEq(target, 44_100)
        assertEquals(7, fit48.gains.size); assertTrue(fit48.gains.all { it in -12f..12f })
        assertTrue("error ${fit48.errorDb}", fit48.errorDb < 2f); assertNotEquals(FitQuality.Poor, fit48.quality)
        assertTrue("44.1 kHz fits as well", kotlin.math.abs(fit44.errorDb - fit48.errorDb) < .3f)
        assertTrue("The bass is raised against the middle: ${fit48.gains}", fit48.gains[0] - fit48.gains[3] > 2f)
    }

    @Test fun aCurveTheBandsCanMakeComesBackExactlyAndANarrowOneIsCalledRough() {
        val made = listOf(4f, -2f, 1f, 0f, 3f, -3f, 2f)
        val curve = eqCurve(eqSections(EqualizerConfig(enabled = true, mode = EqMode.Manual, manual = made)))
        val fit = fitAutoEq(AutoEqTarget("Exact", curve, 0))
        assertTrue("error ${fit.errorDb}", fit.errorDb < .2f); assertEquals(FitQuality.Good, fit.quality)
        made.indices.forEach { assertEquals(made[it], fit.gains[it], .4f) }
        val narrow = readAutoEq("Spiky.txt", (1..8).joinToString("\n") { "Filter $it: ON PK Fc ${it * 900} Hz Gain ${if (it % 2 == 0) 10 else -10} dB Q 8" })!!
        assertEquals(FitQuality.Poor, fitAutoEq(narrow).quality)
    }

    @Test fun graphicEqIsReadAndGarbageIsNot() {
        val graphic = readAutoEq("Fixture GraphicEQ.txt", "GraphicEQ: 20 6.0; 100 6.0; 1000 0.0; 10000 -3.0; 20000 -3.0")!!
        assertEquals(6f, graphic.curve.first(), 1e-3f); assertEquals(-3f, graphic.curve.last(), 1e-3f)
        assertNull(readAutoEq("notes.txt", "hello"))
        assertNull(readAutoEq("big.txt", "x".repeat(AUTOEQ_MAX_BYTES + 1)))
        assertNull(readAutoEq("bad.txt", "Filter 1: ON PK Fc 99999 Hz Gain 900 dB Q 1"))
    }

    @Test fun applyingAProfileKeepsTheListenersCurveForRevert() {
        HeadphoneProfile.forget(context)
        val mine = EqualizerConfig(enabled = false, mode = EqMode.Dynamic, toneX = 2, manual = listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f), preset = "Custom")
        val a = HeadphoneProfile.apply(context, AutoEqFit("A", List(7) { 2f }, .5f), mine)
        assertTrue(a.enabled); assertEquals(EqMode.Manual, a.mode); assertEquals(List(7) { 2f }, a.manual); assertEquals("A", HeadphoneProfile.name(context))
        val b = HeadphoneProfile.apply(context, AutoEqFit("B", List(7) { -1f }, .9f), a)
        val back = HeadphoneProfile.revert(context, b)
        assertEquals("Back to the listener's own, not to profile A", mine, back.copy(toneX = 2))
        assertNull(HeadphoneProfile.name(context))
    }

    @Test fun pitchIsWholeSemitonesForSongsOnThePhone() {
        assertEquals(1f, pitchRatio(0)); assertEquals("Clamped to +6", pitchRatio(6), pitchRatio(12))
        assertEquals(1.4142135f, pitchRatio(6), 1e-5f); assertEquals(pitchRatio(6), pitchRatio(9))
        (-6..6).forEach { assertEquals(it, pitchSteps(pitchRatio(it))) }
        assertEquals(0, pitchSteps(0f)); assertEquals("+2", pitchLabel(2)); assertEquals("−3", pitchLabel(-3))
        assertNull(pitchUnavailable(PlaybackUiState(music = true)))
        assertEquals(R.string.pitch_not_on_cast, pitchUnavailable(PlaybackUiState(remote = true)))
        assertEquals(R.string.pitch_not_live, pitchUnavailable(PlaybackUiState(live = true)))
        assertEquals(R.string.pitch_songs_only, pitchUnavailable(PlaybackUiState(music = false)))
        val speeds = PlaybackSpeeds(context)
        speeds.rememberPitch(true, pitchRatio(3)); assertEquals(pitchRatio(3), speeds.pitchFor(true)); assertEquals(1f, speeds.pitchFor(false))
        speeds.rememberPitch(false, pitchRatio(-2)); assertEquals("An episode's pitch is not the songs'", pitchRatio(3), speeds.pitchFor(true))
        assertEquals("A new run of Riff starts at its own pitch", 1f, PlaybackSpeeds(context).pitchFor(true))
    }
}
