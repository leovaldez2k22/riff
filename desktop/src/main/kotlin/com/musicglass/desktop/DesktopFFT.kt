package com.musicglass.desktop

import kotlin.math.*

/**
 * Fast Fourier Transform (FFT) and RMS audio analyzer.
 * Computes 256 uint16 frequency magnitude bins and peak RMS amplitude from 16-bit PCM audio.
 * Conforms to Section 4.3 and 4.4 of Riff Windows desktop architecture.
 */
internal object DesktopFFT {
    private const val FFT_SIZE = 512 // 512 samples -> 256 magnitude bins
    private val HANN_WINDOW = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    /**
     * Computes 256 frequency magnitude bins scaled to 0..32767 (uint16 max in Short)
     * and the peak RMS level in range 0.0f..1.0f from 16-bit stereo PCM samples.
     */
    fun analyze(pcmSamples: ShortArray, channels: Int = 2): Pair<ShortArray, Float> {
        val bins = ShortArray(256)
        if (pcmSamples.isEmpty()) return bins to 0.0f

        // Convert stereo/multichannel to mono floating point windowed buffer
        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        val totalFrames = pcmSamples.size / channels
        val framesToTake = minOf(totalFrames, FFT_SIZE)
        var sumSquares = 0.0

        for (i in 0 until framesToTake) {
            var monoSample = 0f
            for (ch in 0 until channels) {
                monoSample += pcmSamples[i * channels + ch].toFloat()
            }
            monoSample /= channels
            val normSample = monoSample / 32768.0f
            sumSquares += normSample * normSample

            real[i] = normSample * HANN_WINDOW[i]
        }

        val rms = sqrt(sumSquares / maxOf(1, framesToTake)).toFloat().coerceIn(0f, 1f)

        // Perform in-place Radix-2 Cooley-Tukey FFT
        fftRadix2(real, imag)

        // Compute positive frequency magnitudes for the first 256 bins
        for (i in 0 until 256) {
            val r = real[i]
            val im = imag[i]
            val mag = sqrt(r * r + im * im)
            // Scale magnitude into 0..32767 with slight log compression for visual responsiveness
            val compressed = (log10(1.0 + mag * 9.0) * 32767.0).toInt().coerceIn(0, 32767)
            bins[i] = compressed.toShort()
        }

        return bins to rms
    }

    /** In-place radix-2 decimation-in-time FFT. */
    private fun fftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR

                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }
}
