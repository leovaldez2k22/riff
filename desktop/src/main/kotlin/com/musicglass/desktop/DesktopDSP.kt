package com.musicglass.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** 10 Standard ISO Octave Equalizer center frequencies (Hz). */
internal val EQ_CENTERS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

/**
 * 10-Band TPT (Topology-Preserving Transform) State-Variable Equalizer.
 * Legacy desktop TPT equations. Android's seven manual plus three dynamic layout is separate parity work.
 */
internal class DesktopEqualizer {
    var enabled: Boolean = true
    val bandsDb = FloatArray(10)
    var bassCutDb: Float = 0f

    private var sampleRate = 44100
    private var channels = 2

    // Filter integrator states per band, per channel
    private var ic1 = Array(10) { DoubleArray(2) }
    private var ic2 = Array(10) { DoubleArray(2) }

    fun configure(rate: Int, channelCount: Int) {
        sampleRate = rate
        channels = channelCount
        ic1 = Array(10) { DoubleArray(channelCount) }
        ic2 = Array(10) { DoubleArray(channelCount) }
        reset()
    }

    fun reset() {
        for (i in 0 until 10) {
            ic1[i].fill(0.0)
            ic2[i].fill(0.0)
        }
    }

    fun process(samples: ShortArray): ShortArray {
        if (!enabled) return samples
        val output = ShortArray(samples.size)
        val numFrames = samples.size / channels

        for (frame in 0 until numFrames) {
            for (ch in 0 until channels) {
                var currentSample = samples[frame * channels + ch].toDouble()

                for (band in 0 until 10) {
                    val gainDb = bandsDb[band] - (if (band <= 1) bassCutDb else 0f)
                    if (abs(gainDb) < 0.05f) continue

                    val a = 10.0.pow(gainDb.toDouble() / 40.0)
                    val w0 = 2.0 * PI * EQ_CENTERS[band] / sampleRate
                    val g = tan(w0 / 2.0)
                    val k = 1.0 / (a * 1.414) // Q factor ~ 1.414

                    val a1 = 1.0 / (1.0 + g * (g + k))
                    val a2 = g * a1
                    val a3 = g * a2

                    val v3 = currentSample - ic2[band][ch]
                    val v1 = a1 * ic1[band][ch] + a2 * v3
                    val v2 = ic2[band][ch] + a2 * ic1[band][ch] + a3 * v3

                    ic1[band][ch] = 2.0 * v1 - ic1[band][ch]
                    ic2[band][ch] = 2.0 * v2 - ic2[band][ch]

                    // Peaking EQ combination: input + (A^2 - 1) * bandpass
                    val bandpass = v1
                    currentSample += (a * a - 1.0) * bandpass
                }

                output[frame * channels + ch] = currentSample.roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return output
    }
}

/**
 * Volume Booster and Normalization with Dynamic Peak Limiter.
 * Legacy desktop gain/limiter component; the active pipeline uses FloatDesktopDsp.
 */
internal class DesktopVolumeBoost {
    var targetGain: Float = 1.0f
        set(value) { field = value.coerceIn(1.0f, 3.0f) }

    var trim: Float = 1.0f
        set(value) { field = value.coerceIn(0.1f, 1.0f) }

    private var gain = 1.0f
    private var limit = 1.0f

    fun reset() {
        gain = targetGain * trim
        limit = 1.0f
    }

    fun process(samples: ShortArray, sampleRate: Int = 44100, channels: Int = 2): ShortArray {
        val target = targetGain * trim
        if (gain == 1.0f && target == 1.0f) return samples

        val output = ShortArray(samples.size)
        val step = 2f / (sampleRate * 0.03f)
        val release = 1f / (sampleRate * 0.100f) // 100ms limiter release
        val ceiling = 32000f

        val numFrames = samples.size / channels
        for (f in 0 until numFrames) {
            gain += (target - gain).coerceIn(-step, step)

            if (gain > 1.0f) {
                var peak = 0
                for (ch in 0 until channels) {
                    peak = maxOf(peak, abs(samples[f * channels + ch].toInt()))
                }
                val boosted = peak * gain
                limit = minOf(1.0f, limit + release).let {
                    if (boosted * it > ceiling) ceiling / boosted else it
                }
            } else {
                limit = 1.0f
            }

            for (ch in 0 until channels) {
                val sample = samples[f * channels + ch]
                val scaled = if (gain == 1.0f) {
                    sample.toInt()
                } else if (gain < 1.0f) {
                    (sample * gain).roundToInt()
                } else {
                    (sample * gain * limit).roundToInt().coerceIn(-32768, 32767)
                }
                output[f * channels + ch] = scaled.toShort()
            }
        }
        return output
    }
}

/**
 * Automix Feedback Delay and Echo-out effect.
 * Preserves exact equations from Riff's Android PcmEcho.
 */
internal class DesktopEcho {
    var enabled: Boolean = false
    var feedback: Float = 0.55f // -5.2 dB per repeat
    var delayMs: Int = 500

    private var delayBuffer = FloatArray(44100 * 2) // Stereo buffer
    private var writeHead = 0

    fun configure(rate: Int, channels: Int = 2) {
        val bufferSize = rate * 2 * channels // Up to 2 seconds
        delayBuffer = FloatArray(bufferSize)
        writeHead = 0
    }

    fun reset() {
        delayBuffer.fill(0f)
        writeHead = 0
    }

    fun process(samples: ShortArray, sampleRate: Int = 44100, channels: Int = 2): ShortArray {
        if (!enabled) return samples

        val output = ShortArray(samples.size)
        val delaySamples = (sampleRate * (delayMs / 1000f)).toInt() * channels
        val bufferLen = delayBuffer.size

        for (i in samples.indices) {
            val readIdx = (writeHead - delaySamples + bufferLen) % bufferLen
            val echoSample = delayBuffer[readIdx]

            val inSample = samples[i].toFloat()
            val outSample = (inSample + echoSample * feedback).coerceIn(-32768f, 32767f)

            delayBuffer[writeHead] = inSample + echoSample * feedback
            writeHead = (writeHead + 1) % bufferLen

            output[i] = outSample.roundToInt().toShort()
        }
        return output
    }
}
