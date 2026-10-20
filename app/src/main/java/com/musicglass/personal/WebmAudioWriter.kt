package com.musicglass.personal

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Q18-057: a WebM file of one Opus track, written with a cluster every half second and a Cues index of them, so a
 * seek lands at most half a second before its target. Android's own WebM writer starts a cluster about every 30 s and
 * writes no Cues: every seek in a download had to decode up to 30 s of audio first (1 to 3 s of silence on a phone).
 * The compressed audio is copied as it came; nothing is decoded or re-encoded.
 */
internal class WebmAudioWriter(private val file: File, private val opusHead: ByteArray, private val codecDelayNs: Long,
    private val seekPreRollNs: Long, private val sampleRate: Int, private val channels: Int,
    private val clusterEveryMs: Long = CLUSTER_MS) : AutoCloseable {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var segmentData = 0L
    private var durationAt = 0L
    private var cuesSeekAt = 0L
    private val cluster = java.io.ByteArrayOutputStream()
    private var clusterMs = -1L
    private val cues = ArrayList<Pair<Long, Long>>()
    private var lastMs = 0L
    private var frameMs = 20L
    private var closed = false

    init {
        out.write(element(0x1A45DFA3, uint(0x4286, 1) + uint(0x42F7, 1) + uint(0x42F2, 4) + uint(0x42F3, 8) +
            string(0x4282, "webm") + uint(0x4287, 4) + uint(0x4285, 2)))
        out.write(id(0x18538067)); out.write(UNKNOWN_8) // Segment; its size is written in close()
        segmentData = out.filePointer
        // SeekHead, with room for the Cues position, known only at the end.
        val seekHeadAt = out.filePointer
        val info = uint(0x2AD7B1, 1_000_000) + float(0x4489, 0.0) + string(0x4D80, "Riff") + string(0x5741, "Riff")
        val tracks = element(0x1654AE6B, element(0xAE, uint(0xD7, 1) + uint(0x73C5, 1) + uint(0x83, 2) +
            string(0x86, "A_OPUS") + bytes(0x63A2, opusHead) + uint(0x56AA, codecDelayNs) + uint(0x56BB, seekPreRollNs) +
            element(0xE1, float(0xB5, sampleRate.toDouble()) + uint(0x9F, channels.toLong()))))
        val seekHeadSize = element(0x114D9B74, seekEntry(0x1549A966, 0) + seekEntry(0x1654AE6B, 0) + seekEntry(0x1C53BB6B, 0)).size
        val infoAt = seekHeadAt + seekHeadSize - segmentData
        val tracksAt = infoAt + element(0x1549A966, info).size
        out.write(element(0x114D9B74, seekEntry(0x1549A966, infoAt) + seekEntry(0x1654AE6B, tracksAt) + seekEntry(0x1C53BB6B, 0)))
        cuesSeekAt = out.filePointer - 8
        val infoStart = out.filePointer
        out.write(element(0x1549A966, info))
        durationAt = infoStart + element(0x1549A966, info).size - (string(0x4D80, "Riff") + string(0x5741, "Riff")).size - 8
        out.write(tracks)
    }

    /** One compressed frame at [timeUs]. Frames come in time order. */
    fun write(data: ByteArray, size: Int, timeUs: Long) {
        val ms = (timeUs / 1000).coerceAtLeast(lastMs)
        if (clusterMs >= 0) frameMs = (ms - lastMs).takeIf { it in 1..1000 } ?: frameMs
        if (clusterMs < 0 || ms - clusterMs >= clusterEveryMs) { flushCluster(); clusterMs = ms; cluster.write(uint(0xE7, ms)) }
        val block = ByteBuffer.allocate(4 + size).put(0x81.toByte()).putShort((ms - clusterMs).toInt().toShort()).put(0x80.toByte())
            .put(data, 0, size).array()
        cluster.write(element(0xA3, block))
        lastMs = ms
    }

    private fun flushCluster() {
        if (clusterMs < 0) return
        cues += clusterMs to (out.filePointer - segmentData)
        out.write(element(0x1F43B675, cluster.toByteArray())); cluster.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushCluster()
            val cuesAt = out.filePointer - segmentData
            val points = java.io.ByteArrayOutputStream()
            for ((ms, at) in cues) points.write(element(0xBB, uint(0xB3, ms) + element(0xB7, uint(0xF7, 1) + uint(0xF1, at))))
            out.write(element(0x1C53BB6B, points.toByteArray()))
            val end = out.filePointer
            out.seek(segmentData - 8); out.write(size8(end - segmentData))
            out.seek(cuesSeekAt); out.write(ByteBuffer.allocate(8).putLong(cuesAt).array())
            out.seek(durationAt); out.write(ByteBuffer.allocate(8).putDouble((lastMs + frameMs).toDouble()).array())
            out.fd.sync()
        } finally { out.close() }
    }

    private fun seekEntry(target: Long, at: Long) = element(0x4DBB, bytes(0x53AB, id(target)) + fixed8(0x53AC, at))

    companion object {
        const val CLUSTER_MS = 500L
        private val UNKNOWN_8 = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

        /** Opus' codec delay or seek pre-roll from Android's csd-1 and csd-2: nanoseconds, little-endian. */
        fun nanos(csd: ByteBuffer?): Long = csd?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)?.takeIf { it.remaining() >= 8 }?.long ?: 0L

        private fun id(value: Long): ByteArray {
            val width = when { value > 0xFFFFFF -> 4; value > 0xFFFF -> 3; value > 0xFF -> 2; else -> 1 }
            return ByteArray(width) { (value shr (8 * (width - 1 - it))).toByte() }
        }
        private fun size(value: Long): ByteArray {
            var width = 1
            while (width < 8 && value >= (1L shl (7 * width)) - 1) width++
            return ByteArray(width) { i -> ((value shr (8 * (width - 1 - i))) or if (i == 0) (1L shl (8 - width)) else 0L).toByte() }
        }
        private fun size8(value: Long) = ByteArray(8) { i -> ((value shr (8 * (7 - i))) or if (i == 0) 1L else 0L).toByte() }
        private fun element(id: Long, body: ByteArray) = id(id) + size(body.size.toLong()) + body
        private fun uint(id: Long, value: Long): ByteArray {
            var width = 1
            while (width < 8 && value ushr (8 * width) != 0L) width++
            return element(id, ByteArray(width) { (value shr (8 * (width - 1 - it))).toByte() })
        }
        private fun fixed8(id: Long, value: Long) = element(id, ByteBuffer.allocate(8).putLong(value).array())
        private fun float(id: Long, value: Double) = element(id, ByteBuffer.allocate(8).putDouble(value).array())
        private fun string(id: Long, value: String) = element(id, value.toByteArray(Charsets.US_ASCII))
        private fun bytes(id: Long, value: ByteArray) = element(id, value)
    }
}
