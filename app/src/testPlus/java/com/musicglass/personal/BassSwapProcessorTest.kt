package com.musicglass.personal

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class BassSwapProcessorTest {
    @Test fun theBassCutTakesTheLowEndOut() {
        val eq = PcmEqualizer().apply { configure(48_000, 1); bassCutDb = BASS_SWAP_CUT_DB; reset() }
        val input = java.nio.ByteBuffer.allocate(48_000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { input.putShort((sin(2 * PI * 50 * it / 48_000) * .5 * 32767).toInt().toShort()) }
        input.flip()
        val output = java.nio.ByteBuffer.allocate(input.remaining()); eq.process(input, output); output.flip()
        var peak = 0; for (i in 24_000 until 48_000) peak = maxOf(peak, kotlin.math.abs(output.order(java.nio.ByteOrder.LITTLE_ENDIAN).getShort(i * 2).toInt()))
        assertTrue("50 Hz down by more than 18 dB: $peak", peak < 16_384 / 8)
    }

}
