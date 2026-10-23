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
 *
 * Q18-092: a boosted frame that would pass full scale is turned down just enough by a limiter, which lets go over
 * 100 ms; everything below full scale is scaled exactly. (Before, a soft knee bent every peak above 80 % of full
 * scale, which audibly changed loud music.) The samples stay 16-bit: with float output on, Media3 1.11.1's audio sink
 * leaves the app's processors out of the chain for high-resolution PCM, so Boost and Normalize would stop working.
 */
internal class PcmVolumeBoost {
    @Volatile var targetGain = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(1f, 3f) else 1f }
    /** Normalize volume: a static gain for the track this player is playing. Only ever turns down. */
    @Volatile var trim = 1f
        set(value) { field = if (value.isFinite()) value.coerceIn(NORMALIZE_MIN_GAIN, 1f) else 1f }
    private var gain = 1f
    /** The limiter's own gain: 1 when it is not acting. */
    private var limit = 1f
    fun reset() { gain = targetGain * trim; limit = 1f }
    fun process(input: ByteBuffer, output: ByteBuffer, sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0 && input.remaining() % (2 * channels) == 0)
        if (!input.hasRemaining()) return
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        val target = targetGain * trim
        if (gain == 1f && target == 1f) { output.put(input); return }
        val step = 2f / (sampleRate * .03f)
        val release = 1f / (sampleRate * LIMITER_RELEASE_S)
        while (input.hasRemaining()) {
            gain += (target - gain).coerceIn(-step, step)
            if (gain > 1f) {
                // The loudest channel of the frame decides, so every channel keeps the same gain.
                val at = input.position()
                var peak = 0
                for (channel in 0 until channels) peak = maxOf(peak, abs(input.getShort(at + 2 * channel).toInt()))
                val boosted = peak / 32768f * gain
                limit = minOf(1f, limit + release).let { if (boosted * it > LIMITER_CEILING) LIMITER_CEILING / boosted else it }
            } else limit = 1f
            repeat(channels) {
                val sample = input.short
                val value = if (gain == 1f) sample.toInt()
                // Turning down cannot leave the PCM range, so nothing is limited and quiet tracks keep their shape.
                else if (gain < 1f) (sample * gain).roundToInt()
                else (sample * gain * limit).roundToInt().coerceIn(-32768, 32767)
                output.putShort(value.toShort())
            }
        }
    }
}

/** The highest a boosted sample may reach: full scale in 16-bit PCM. */
private const val LIMITER_CEILING = 32767f / 32768f
/** How long the limiter takes to let go of a peak. */
private const val LIMITER_RELEASE_S = .1f

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

/** How much already-played audio a player keeps: enough to seek back to the start of most songs without fetching it again. */
internal const val RIFF_BACK_BUFFER_MS = 5*60_000
/**
 * Media3's default load control keeps no back buffer, so every seek back in a streamed song threw away and downloaded
 * again what had just played (Q18-005). Five minutes of compressed audio is a few megabytes, inside the default
 * audio target, so the forward buffer is unchanged.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun riffLoadControl(): androidx.media3.exoplayer.DefaultLoadControl =
    androidx.media3.exoplayer.DefaultLoadControl.Builder().setBackBuffer(RIFF_BACK_BUFFER_MS, true).build()
