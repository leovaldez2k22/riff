package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** Coefficients are moved toward their targets every this many frames. */
private const val EQ_UPDATE_FRAMES = 32
/** How long a change of gain or width takes to settle. */
private const val EQ_SMOOTH_S = .03f

/**
 * Feature 10: Riff's equalizer on 16-bit PCM, before the booster. Each of the ten sections is a TPT state-variable
 * filter; its gain and width glide to a new setting over about 30 ms, so a dragged fader or puck never clicks. With
 * the equalizer off, flat and centred, samples pass through untouched.
 */
internal class PcmEqualizer {
    @Volatile var config: EqualizerConfig = EqualizerConfig()
    /** Automix's bass swap: how far the low end is turned down during a blend, 0 the rest of the time. */
    @Volatile var bassCutDb: Float = 0f
    private var sampleRate = 0
    private var channels = 0
    /** Per section, the bass swap's last: its current gain and width, gliding toward the config's. */
    private val count = EqLayout.SECTIONS + 1
    private val gain = FloatArray(count)
    private val width = FloatArray(count) { 1f }
    private var preamp = 1f
    private var left = 1f
    private var right = 1f
    /** Per section, per channel: the filter's two integrator states. */
    private var ic1 = Array(count) { DoubleArray(0) }
    private var ic2 = Array(count) { DoubleArray(0) }
    private val a1 = DoubleArray(count); private val a2 = DoubleArray(count); private val a3 = DoubleArray(count)
    private val m0 = DoubleArray(count); private val m1 = DoubleArray(count); private val m2 = DoubleArray(count)
    private var countdown = 0

    fun configure(rate: Int, channelCount: Int) {
        sampleRate = rate; channels = channelCount
        ic1 = Array(count) { DoubleArray(channelCount) }; ic2 = Array(count) { DoubleArray(channelCount) }
        reset()
    }

    /** Jumps to the current settings at once: at a seek or a new stream, where there is nothing to glide from. */
    fun reset() {
        val shaping = eqSections(config); val sections = shaping + bassSwapSection(bassCutDb)
        sections.forEachIndexed { i, s -> gain[i] = s.gainDb; width[i] = s.q }
        preamp = dbToGain(if (config.enabled) preampFor(shaping, sampleRate.coerceAtLeast(8_000)) else 0f)
        balanceGains(if (config.enabled && channels == 2) config.balance else 0f).let { (l, r) -> left = l; right = r }
        ic1.forEach { it.fill(0.0) }; ic2.forEach { it.fill(0.0) }
        sections.forEachIndexed { i, s -> coefficients(i, s.shape, s.frequency) }
        countdown = 0
    }

    /** True when nothing would change a sample: the pass-through case. */
    private fun idle(): Boolean = !config.enabled && bassCutDb == 0f && gain.all { it == 0f } && preamp == 1f && left == 1f && right == 1f

    fun process(input: ByteBuffer, output: ByteBuffer) {
        require(sampleRate > 0 && channels > 0 && input.remaining() % (2 * channels) == 0)
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        if (idle()) { output.put(input); return }
        val shaping = eqSections(config); val sections = shaping + bassSwapSection(bassCutDb)
        val targetPreamp = dbToGain(if (config.enabled) preampFor(shaping, sampleRate) else 0f)
        val (targetLeft, targetRight) = balanceGains(if (config.enabled && channels == 2) config.balance else 0f)
        val glide = (1 - exp(-EQ_UPDATE_FRAMES / (EQ_SMOOTH_S * sampleRate))).toFloat()
        while (input.hasRemaining()) {
            if (countdown-- <= 0) {
                countdown = EQ_UPDATE_FRAMES
                sections.forEachIndexed { i, s ->
                    val was = gain[i]
                    gain[i] = approach(gain[i], s.gainDb, glide); width[i] = approach(width[i], s.q, glide)
                    // A section that has glided back to flat starts from rest when it is next used.
                    if (was != 0f && gain[i] == 0f) { ic1[i].fill(0.0); ic2[i].fill(0.0) }
                    coefficients(i, s.shape, s.frequency)
                }
                preamp = approach(preamp, targetPreamp, glide); left = approach(left, targetLeft, glide); right = approach(right, targetRight, glide)
            }
            for (channel in 0 until channels) {
                var v = input.short / 32768.0 * preamp
                for (i in 0 until count) {
                    if (gain[i] == 0f) continue
                    val v3 = v - ic2[i][channel]
                    val v1 = a1[i] * ic1[i][channel] + a2[i] * v3
                    val v2 = ic2[i][channel] + a2[i] * ic1[i][channel] + a3[i] * v3
                    ic1[i][channel] = 2 * v1 - ic1[i][channel]; ic2[i][channel] = 2 * v2 - ic2[i][channel]
                    v = m0[i] * v + m1[i] * v1 + m2[i] * v2
                }
                if (channels == 2) v *= if (channel == 0) left else right
                output.putShort((v * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
    }

    private fun approach(current: Float, target: Float, glide: Float): Float {
        val next = current + (target - current) * glide
        return if (kotlin.math.abs(target - next) < 1e-3f) target else next
    }

    /** Simper's mixes for a TPT SVF: bell, low shelf and high shelf at the section's current gain and width. */
    private fun coefficients(i: Int, shape: EqShape, frequency: Float) {
        if (gain[i] == 0f) return
        val a = 10.0.pow(gain[i] / 40.0); val q = width[i].toDouble().coerceAtLeast(.05)
        val base = tan(PI * frequency.coerceAtMost(sampleRate * .49f) / sampleRate)
        val g: Double; val k: Double
        when (shape) {
            EqShape.Bell -> { g = base; k = 1 / (q * a); m0[i] = 1.0; m1[i] = k * (a * a - 1); m2[i] = 0.0 }
            EqShape.LowShelf -> { g = base / sqrt(a); k = 1 / q; m0[i] = 1.0; m1[i] = k * (a - 1); m2[i] = a * a - 1 }
            EqShape.HighShelf -> { g = base * sqrt(a); k = 1 / q; m0[i] = a * a; m1[i] = k * (1 - a) * a; m2[i] = 1 - a * a }
        }
        a1[i] = 1 / (1 + g * (g + k)); a2[i] = g * a1[i]; a3[i] = g * a2[i]
    }
}

internal fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class EqualizerProcessor : BaseAudioProcessor() {
    val equalizer = PcmEqualizer()
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat // Stays in the chain, so turning it on applies at once.
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        equalizer.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        equalizer.process(inputBuffer, output)
        output.flip()
    }
}
