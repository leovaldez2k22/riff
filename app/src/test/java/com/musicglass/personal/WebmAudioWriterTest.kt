package com.musicglass.personal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Q18-057: the WebM Riff now stores Opus downloads in. Played and sought on a device in SeekTimingDeviceTest. */
class WebmAudioWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)

    private fun write(seconds: Int, clusterMs: Long = WebmAudioWriter.CLUSTER_MS): File {
        val file = folder.newFile()
        WebmAudioWriter(file, head, 6_500_000, 80_000_000, 48_000, 2, clusterMs).use { w ->
            val frame = ByteArray(300) { it.toByte() }
            for (i in 0 until seconds * 50) w.write(frame, 120 + i % 40, i * 20_000L)
        }
        return file
    }

    @Test fun aSeekPointEveryHalfSecondAndTheLengthOfTheSong() {
        val file = write(10)
        assertEquals("webm", BundleValidator.container(file))
        val index = WebmSeekIndex.read(file)!!
        assertEquals(20, index.timesUs.size)
        assertTrue((index.timesUs.toList() + index.durationUs).zipWithNext().all { (a, b) -> b - a <= 500_000 })
        assertEquals(10_000_000, index.durationUs)
        assertFalse(DownloadRepack.coarse(file))
    }

    @Test fun theCuesListEveryClusterWhereTheSeekHeadSays() {
        val bytes = write(3).readBytes()
        val cues = indexOf(bytes, byteArrayOf(0x1C, 0x53, -69, 0x6B), last = true) // the first is the SeekHead naming it
        assertTrue(cues > 0)
        // The SeekHead's last entry is the Cues: its 8-byte position, from the start of the segment's data.
        val segmentData = indexOf(bytes, byteArrayOf(0x18, 0x53, -128, 0x67)) + 4 + 8
        val seekHead = indexOf(bytes, byteArrayOf(0x11, 0x4D, -101, 0x74))
        val positionAt = indexOf(bytes, byteArrayOf(0x53, -84, -120), seekHead, last = true) + 3
        val position = (0 until 8).fold(0L) { v, i -> (v shl 8) or (bytes[positionAt + i].toLong() and 255) }
        assertEquals(cues.toLong(), segmentData + position)
        // The segment's size, an 8-byte number after its ID, is the rest of the file.
        val size = (1 until 8).fold(0L) { v, i -> (v shl 8) or (bytes[segmentData - 8 + i].toLong() and 255) }
        assertEquals(bytes.size.toLong(), segmentData + size)
    }

    @Test fun theOldWritersSpacingIsCoarse() {
        assertTrue(DownloadRepack.coarse(write(70, clusterMs = 30_000)))
        assertFalse(DownloadRepack.coarse(folder.newFile().apply { writeBytes(ByteArray(64)) }))
    }

    private fun indexOf(data: ByteArray, what: ByteArray, from: Int = 0, last: Boolean = false): Int {
        var found = -1
        for (i in from..data.size - what.size) if (what.indices.all { data[i + it] == what[it] }) { found = i; if (!last) return i }
        return found
    }
}
