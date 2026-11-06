package com.musicglass.desktop

import kotlin.math.*

/** Float32 counterpart of the existing TPT filter equations, with preallocated filter state. */
internal class FloatDesktopDsp(private val rate: Int = 48000, private val channels: Int = 2) {
    val bandsDb = FloatArray(10)
    var equalizerEnabled = false
    var gain = 1f
    var volume = .8f
    private val ic1 = Array(10) { DoubleArray(channels) }
    private val ic2 = Array(10) { DoubleArray(channels) }
    private val coefficients = Array(10) { DoubleArray(4) }
    private var limit = 1.0
    private var gainState = 1f
    private var volumeState = .8f
    fun configure(bands: FloatArray, enabled: Boolean) {
        require(bands.size == 10 && bands.all { it.isFinite() && it in -24f..24f })
        bands.copyInto(bandsDb); equalizerEnabled = enabled
        for (b in 0..9) {
            val a = 10.0.pow(bands[b] / 40.0); val g = tan(PI * EQ_CENTERS[b] / rate)
            val k = 1.0 / (a * 1.414); val a1 = 1.0 / (1.0 + g * (g + k))
            coefficients[b][0] = a1; coefficients[b][1] = g * a1
            coefficients[b][2] = g * g * a1; coefficients[b][3] = a * a - 1.0
        }
    }
    fun reset() { ic1.forEach { it.fill(0.0) }; ic2.forEach { it.fill(0.0) }; limit = 1.0;gainState=gain;volumeState=volume }
    fun process(samples: FloatArray, frames: Int) {
        require(gain.isFinite() && gain in 0f..3f && volume.isFinite() && volume in 0f..1f)
        for (f in 0 until frames) {
            gainState+=(gain-gainState).coerceIn(-2f/(rate*.03f),2f/(rate*.03f))
            volumeState+=(volume-volumeState).coerceIn(-1f/(rate*.005f),1f/(rate*.005f))
            var peak = 0.0
            for (ch in 0 until channels) {
                val idx = f * channels + ch
                var value = samples[idx].toDouble()
                if(!value.isFinite())throw StreamCorruptedException(java.io.IOException("Non-finite decoder PCM"))
                if (equalizerEnabled) for (b in 0..9) {
                    if (abs(bandsDb[b]) < .05f) continue
                    val c = coefficients[b]; val v3 = value - ic2[b][ch]
                    val v1 = c[0] * ic1[b][ch] + c[1] * v3
                    val v2 = ic2[b][ch] + c[1] * ic1[b][ch] + c[2] * v3
                    ic1[b][ch] = 2 * v1 - ic1[b][ch]; ic2[b][ch] = 2 * v2 - ic2[b][ch]
                    value += c[3] * v1
                }
                value *= gainState * volumeState
                samples[idx] = value.toFloat(); peak = max(peak, abs(value))
            }
            limit = min(1.0, limit + 1.0 / (rate * .1))
            if (peak * limit > .9765625) limit = .9765625 / peak
            for (ch in 0 until channels) samples[f * channels + ch] = (samples[f * channels + ch] * limit).toFloat().coerceIn(-.9765625f, .9765625f)
        }
    }
}