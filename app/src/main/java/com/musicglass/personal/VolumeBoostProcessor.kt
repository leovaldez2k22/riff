package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one place a player's samples are scaled: the booster's gain (1 to 3) times the track's normalization
 * gain ([trim], at most 1). A change of either ramps over 30 ms. Both channels use the same gain for each PCM frame.
 */
internal class PcmVolumeBoost {
    @Volatile var targetGain = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(1f, 3f) else 1f }
    /** Normalize volume: a static gain for the track this player is playing. Only ever turns down. */
    @Volatile var trim = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(NORMALIZE_MIN_GAIN, 1f) else 1f }
    private var gain = 1f
    fun reset() { gain = targetGain * trim }
    fun process(input: ByteBuffer, output: ByteBuffer, sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0 && input.remaining() % (2 * channels) == 0)
        if (!input.hasRemaining()) return
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        val target = targetGain * trim
        if (gain == 1f && target == 1f) { output.put(input); return }
        val step = 2f / (sampleRate * .03f)
        while (input.hasRemaining()) {
            gain += (target - gain).coerceIn(-step, step)
            repeat(channels) {
                val sample = input.short
                val value = if (gain == 1f) sample.toInt()
                // Turning down cannot leave the PCM range, so nothing is limited and quiet tracks keep their shape.
                else if (gain < 1f) (sample * gain).roundToInt()
                else {
                    val amplified = sample / 32768f * gain
                    val magnitude = abs(amplified)
                    // A continuous soft knee keeps boosted peaks inside the PCM range, without wrapping.
                    val limited = if (magnitude <= .8f) magnitude else {
                        val excess = magnitude - .8f
                        .8f + .2f * excess / (.2f + excess)
                    }
                    (limited * if (amplified < 0f) -32768f else 32768f).roundToInt().coerceIn(-32768, 32767)
                }
                output.putShort(value.toShort())
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class VolumeBoostProcessor : BaseAudioProcessor() {
    val boost = PcmVolumeBoost()
    @Volatile var processedFrames = 0L; private set
    private var format = AudioProcessor.AudioFormat.NOT_SET
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat // Remain in the PCM chain at 100% so live settings changes work.
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) { format = inputAudioFormat; boost.reset() }
    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline sends EMPTY_BUFFER between stages and at stream boundaries.
        // Input and output can then be the same shared empty instance; never copy it onto itself.
        if (!inputBuffer.hasRemaining()) return
        processedFrames += inputBuffer.remaining() / (2 * format.channelCount)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        boost.process(inputBuffer, output, format.sampleRate, format.channelCount)
        output.flip()
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun riffAudioRenderers(context: android.content.Context, processor: VolumeBoostProcessor) =
    object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(processor))
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams).build()
    }
