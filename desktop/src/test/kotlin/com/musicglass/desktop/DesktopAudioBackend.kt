package com.musicglass.desktop

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.*

/**
 * Windows audio output backend wrapping javax.sound.sampled.SourceDataLine.
 * Manages sample-accurate output clocks, hardware buffer latency calculation,
 * and graceful silent fallback for headless / CI environments.
 * Conforms to Section 4.3 of Riff Windows desktop architecture.
 */
internal class DesktopAudioBackend(
    val sampleRate: Int = 44100,
    val channels: Int = 2
) : AutoCloseable {
    private val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
    private var line: SourceDataLine? = null
    private val isClosed = AtomicBoolean(false)
    private val totalSamplesRendered = AtomicLong(0L)

    private var isHeadlessFallback = false
    private val byteBuffer = ByteArray(8192 * channels * 2)

    init {
        openLine()
    }

    private fun openLine() {
        try {
            val info = DataLine.Info(SourceDataLine::class.java, format)
            if (AudioSystem.isLineSupported(info)) {
                val openedLine = AudioSystem.getLine(info) as SourceDataLine
                // Buffer size ~ 100ms (4410 frames = 17640 bytes)
                val desiredBufferSize = (sampleRate * 0.100 * channels * 2).toInt()
                openedLine.open(format, desiredBufferSize)
                openedLine.start()
                line = openedLine
                isHeadlessFallback = false
            } else {
                isHeadlessFallback = true
            }
        } catch (e: Throwable) {
            isHeadlessFallback = true
        }
    }

    /**
     * Writes 16-bit stereo PCM samples to the output line.
     * Returns the number of samples actually written.
     */
    fun writePcm(samples: ShortArray, offset: Int = 0, length: Int = samples.size): Int {
        if (isClosed.get()) return 0
        val sampleCount = minOf(length, samples.size - offset)
        if (sampleCount <= 0) return 0

        val currentLine = line
        if (!isHeadlessFallback && currentLine != null && currentLine.isOpen) {
            val bytesNeeded = sampleCount * 2
            val targetBuf = if (byteBuffer.size >= bytesNeeded) byteBuffer else ByteArray(bytesNeeded)

            val bb = ByteBuffer.wrap(targetBuf).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                bb.putShort(samples[offset + i])
            }

            var bytesWritten = 0
            while (bytesWritten < bytesNeeded && !isClosed.get()) {
                val chunkSize = minOf(bytesNeeded - bytesWritten, currentLine.available())
                if (chunkSize > 0) {
                    val written = currentLine.write(targetBuf, bytesWritten, chunkSize)
                    if (written > 0) {
                        bytesWritten += written
                    } else break
                } else {
                    // Small yield while hardware buffer drains
                    Thread.sleep(5)
                }
            }
            val framesWritten = (bytesWritten / (channels * 2))
            totalSamplesRendered.addAndGet(framesWritten.toLong())
            return framesWritten * channels
        } else {
            // Headless / CI simulation fallback: throttle by real audio duration
            val frames = sampleCount / channels
            totalSamplesRendered.addAndGet(frames.toLong())
            val durationMs = (frames * 1000L) / sampleRate
            if (durationMs > 0) {
                Thread.sleep(durationMs.coerceAtMost(20L))
            }
            return sampleCount
        }
    }

    /**
     * Estimated hardware output latency in milliseconds based on line buffer fullness.
     */
    fun getLatencyEstimateMs(): Float {
        val currentLine = line
        if (!isHeadlessFallback && currentLine != null && currentLine.isOpen) {
            val unrenderedBytes = currentLine.bufferSize - currentLine.available()
            val unrenderedFrames = unrenderedBytes / (channels * 2)
            return (unrenderedFrames * 1000f) / sampleRate
        }
        return 15.0f
    }

    fun getSamplesRendered(): Long = totalSamplesRendered.get()

    fun flush() {
        line?.flush()
    }

    fun isUsingHeadlessFallback(): Boolean = isHeadlessFallback

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            line?.stop()
            line?.flush()
            line?.close()
            line = null
        }
    }
}
