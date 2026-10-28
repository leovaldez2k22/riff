package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/*
 * Feature 9: skipping the silence at the end of a song, found while it plays (the plan's Path B). The watch sits
 * first in the PCM chain and changes no sample; it measures 50 ms blocks and keeps how long the current quiet run is.
 * The service skips when that run has lasted long enough near the end. Path A, scanning a downloaded file's end
 * ahead of time, is not built yet.
 */

/** Quieter than this, in dBFS on the louder channel, a block is silence: far below a fading outro. */
internal const val SILENCE_DBFS = -50.0
internal const val SILENCE_BLOCK_MS = 50
/** How much silence is heard before the skip. */
internal const val SILENCE_SKIP_AFTER_MS = 1_500L
/** The quiet must start within the last quarter of the song or the last minute, whichever is shorter. */
internal const val SILENCE_WINDOW_MS = 60_000L

internal const val SKIP_END_SILENCE_KEY = "skip_end_silence"
internal const val SKIP_ALL_SILENCE_KEY = "skip_all_silence"

/** The detector, apart from Media3, so it can be fed generated PCM. */
internal class SilenceMeter {
    @Volatile var quietMs = 0L; private set
    private var sampleRate = 0
    private var channels = 0
    private var blockFrames = 0
    private var framesInBlock = 0
    private var sums = DoubleArray(0)

    fun configure(rate: Int, channelCount: Int) {
        sampleRate = rate; channels = channelCount; blockFrames = rate * SILENCE_BLOCK_MS / 1_000
        sums = DoubleArray(channelCount); reset()
    }
    fun reset() { quietMs = 0; framesInBlock = 0; sums.fill(0.0) }

    /** Reads [input] without moving its position. */
    fun measure(input: ByteBuffer) {
        if (channels == 0 || blockFrames == 0) return
        val data = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (data.remaining() >= 2 * channels) {
            for (c in 0 until channels) { val v = data.short / 32768.0; sums[c] += v * v }
            if (++framesInBlock == blockFrames) {
                val loudest = sums.max() / blockFrames
                val db = if (loudest <= 0) -120.0 else 10 * log10(loudest)
                quietMs = if (db < SILENCE_DBFS) quietMs + SILENCE_BLOCK_MS else 0
                framesInBlock = 0; sums.fill(0.0)
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SilenceWatchProcessor : BaseAudioProcessor() {
    val meter = SilenceMeter()
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    // A seek or a new stream starts the count again.
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = meter.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        meter.measure(inputBuffer)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer); output.flip()
    }
}

/**
 * Whether to skip to the next song now. The quiet run is measured where the decoder is, a little ahead of what is
 * heard, so its start is placed at [positionMs] − [quietMs]: earlier than it really began, which only makes a skip
 * less likely. Never on repeat-one, the last song, live radio, videos or podcasts ([skipItem]), nor in a song the
 * user sought in near its end.
 */
internal fun shouldSkipSilence(enabled: Boolean, quietMs: Long, positionMs: Long, durationMs: Long, playing: Boolean,
    hasNext: Boolean, repeatOne: Boolean, skipItem: Boolean, sought: Boolean): Boolean {
    if (!enabled || !playing || !hasNext || repeatOne || skipItem || sought || durationMs <= 0) return false
    if (quietMs < SILENCE_SKIP_AFTER_MS || positionMs >= durationMs) return false
    val window = minOf(durationMs / 4, SILENCE_WINDOW_MS)
    return positionMs - quietMs >= durationMs - window
}
