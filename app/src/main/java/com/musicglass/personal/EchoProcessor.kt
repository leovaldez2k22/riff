package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/** Report 4.5: each echo is this much of the last (−5.2 dB a repeat). */
internal const val ECHO_FEEDBACK = .55f
/** The longest delay kept: one beat at 40 BPM. */
private const val ECHO_MAX_DELAY_MS = 1_500
/** How long the outgoing song takes to stop at the cut: long enough not to click. */
private const val ECHO_CUT_FADE_US = 5_000L

/**
 * Automix's echo-out (research report 31 Oct, 1.5 and 4.5), on the outgoing (tail) player's 16-bit PCM. With [cue]
 * set, the song is sent into a one-beat feedback delay, more and more over the bar before the cut; at the cut it stops,
 * and only the echoes ring on, thinner at each repeat (a 200 Hz high-pass and a gentle low-pass in the loop). It works
 * in the song's own time, frame by frame from the position the player gave at its last flush, so the cut lands on the
 * downbeat however far ahead of the speaker the audio is processed. Without a cue, samples pass through untouched.
 */
internal class PcmEcho {
    @Volatile var cue: EchoOut? = null
    private var sampleRate = 0
    private var channels = 0
    private var line = FloatArray(0)
    private var head = 0
    /** Where the next frame is in the song, in microseconds, and the time one frame takes. */
    private var positionUs = 0.0
    private var frameUs = 0.0
    private var high = FloatArray(0); private var highIn = FloatArray(0); private var low = FloatArray(0)
    private var highCoefficient = 0f; private var lowCoefficient = 0f
    /** True while echoes may still be ringing: the delay line isn't known to be silent. */
    private var ringing = false

    fun configure(rate: Int, channelCount: Int, positionUs: Long) {
        sampleRate = rate; channels = channelCount
        this.positionUs = positionUs.toDouble(); frameUs = 1e6 / rate
        line = FloatArray(rate * ECHO_MAX_DELAY_MS / 1_000 * channelCount); head = 0
        high = FloatArray(channelCount); highIn = FloatArray(channelCount); low = FloatArray(channelCount)
        highCoefficient = exp(-2 * PI * 200 / rate).toFloat()
        lowCoefficient = (1 - exp(-2 * PI * 6_000 / rate)).toFloat()
        ringing = false
    }

    fun process(input: ByteBuffer, output: ByteBuffer) {
        require(sampleRate > 0 && channels > 0 && input.remaining() % (2 * channels) == 0)
        input.order(ByteOrder.LITTLE_ENDIAN); output.order(ByteOrder.LITTLE_ENDIAN)
        val cue = cue
        if (cue == null && !ringing) {
            positionUs += input.remaining() / (2 * channels) * frameUs
            output.put(input); return
        }
        val delay = (((cue?.delayMs ?: 0L) * sampleRate / 1_000).toInt() * channels).coerceIn(channels, line.size)
        val sendFrom = (cue?.sendFromMs ?: 0L) * 1_000.0; val cut = (cue?.cutMs ?: 0L) * 1_000.0
        while (input.hasRemaining()) {
            val at = positionUs
            // The send rises over the bar before the cut; after it, the song is gone and the echoes are left.
            val send = when { cue == null || at < sendFrom -> 0f; at < cut -> ((at - sendFrom) / (cut - sendFrom)).toFloat(); else -> 0f }
            val dry = if (cue == null || at < cut) 1f else (1 - (at - cut) / ECHO_CUT_FADE_US).toFloat().coerceAtLeast(0f)
            for (channel in 0 until channels) {
                val x = input.short / 32768f
                val read = (head - delay).mod(line.size)
                val echo = line[read]
                // One-pole high-pass (200 Hz) then low-pass (6 kHz) on what goes round again.
                high[channel] = highCoefficient * (high[channel] + echo - highIn[channel]); highIn[channel] = echo
                low[channel] += (high[channel] - low[channel]) * lowCoefficient
                line[head] = x * send * dry + low[channel] * ECHO_FEEDBACK
                head = (head + 1) % line.size
                output.putShort(((x * dry + echo) * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
            }
            positionUs += frameUs
        }
        ringing = cue != null || line.any { kotlin.math.abs(it) > 1e-5f }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class EchoProcessor : BaseAudioProcessor() {
    val echo = PcmEcho()
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat // Stays in the chain, so an echo can be armed at any time.
    }
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // positionOffsetUs is where in the song the first frame after this flush is (DefaultAudioSink, Media3 1.11).
        echo.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, streamMetadata.positionOffsetUs)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        echo.process(inputBuffer, output)
        output.flip()
    }
}
