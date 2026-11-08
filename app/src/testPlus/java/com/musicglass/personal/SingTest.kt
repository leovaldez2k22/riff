package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Feature 20 (Riff+): Sing's store, its chain at a song's own rate, and the player's switch. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SingTest {
    @get:Rule val folder = TemporaryFolder()

    private fun pcm(frames: Int, channels: Int, value: (Int, Int) -> Int): ByteArray {
        val b = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) for (c in 0 until channels) b.putShort(value(f, c).toShort())
        return b.array()
    }

    private fun SingStore.keep(id: String, frames: Int, channels: Int = 2, rate: Int = 44_100, value: (Int, Int) -> Int = { f, c -> f % 1000 + c }) =
        put(id, rate, channels) { it.write(pcm(frames, channels, value)); frames.toLong() }

    @Test fun keptInstrumentalsReadBackAndBrokenOnesAreRemoved() {
        val store = SingStore(File(folder.root, "sing"))
        assertTrue(store.keep("yt:abc", 5_000))
        val track = store.open("yt:abc")!!
        assertEquals(44_100, track.rate); assertEquals(2, track.channels); assertEquals(5_000L, track.frames)
        assertEquals((1234 % 1000 + 1).toShort(), track.samples.get(1234 * 2 + 1))
        assertNull(store.open("yt:other"))
        // (A different song: Windows will not rewrite a file while it is mapped.)
        store.file("yt:bad").writeBytes(ByteArray(40))
        assertNull(store.open("yt:bad")); assertFalse(store.has("yt:bad"))
    }

    @Test fun theOldestInstrumentalsGoPastTheLimit() {
        val store = SingStore(File(folder.root, "sing"), limit = 3 * (44_100 * 4L + 64)) // three songs and their headers
        for ((i, id) in listOf("a", "b", "c").withIndex()) {
            assertTrue(store.keep(id, 44_100)); store.file(id).setLastModified(1_000_000L + i * 1_000)
        }
        assertTrue(store.keep("d", 44_100))
        assertFalse(store.has("a")); assertTrue(listOf("b", "c", "d").all(store::has))
        assertTrue(store.generation.value >= 4)
    }

    @Test fun withNoCapTheOldestGoOnlyWhenThePhoneRunsLowOnSpace() {
        // Owner, 9 November 2026: no size cap; only a phone short of free space lets the oldest go.
        var free = 10L shl 30
        val store = SingStore(File(folder.root, "sing"), free = { free })
        for ((i, id) in listOf("a", "b", "c").withIndex()) {
            assertTrue(store.keep(id, 44_100)); store.file(id).setLastModified(1_000_000L + i * 1_000)
        }
        assertTrue(listOf("a", "b", "c").all(store::has))
        free = 1L shl 30
        assertTrue(store.keep("d", 44_100))
        assertTrue(store.has("d")); assertFalse(listOf("a", "b", "c").any(store::has))
        store.clear()
        assertFalse(store.has("d")); assertEquals(0L, store.size())
    }

    @Test fun theChainGivesBackTheSongAtItsOwnRateAndChannels() {
        // Mask of ones: 48 kHz mono in, the same song out at 48 kHz mono after a trip through 44.1 kHz stereo.
        val rate = 48_000; val n = rate * 5
        val song = ShortArray(n) { (8_000 * sin(2 * PI * 440 * it / rate)).toInt().toShort() }
        val out = FloatList()
        val chain = SingChain({ FloatArray(it.size) { 1f } }, rate, 1) { pcm, k -> for (i in 0 until k) out.add(pcm[i]) }
        song.toList().chunked(4_096).forEach { piece -> chain.feed(piece.toShortArray(), piece.size) }
        chain.finish()
        val result = out.toArray()
        assertEquals(n.toDouble(), result.size.toDouble(), 3.0)
        var worst = 0.0
        for (i in rate / 10 until n - rate / 10) worst = maxOf(worst, abs(result[i] * 32768 - song[i]).toDouble())
        assertTrue("worst difference $worst of 8000", worst < 40)
    }

    private fun processor(store: SingStore, id: String, rate: Int = 44_100, channels: Int = 2, atUs: Long = 0): SingProcessor {
        val timeline = SinglePeriodTimeline(60_000_000, true, false, false, null, MediaItem.Builder().setMediaId(id).build())
        return SingProcessor { store }.apply {
            configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata.Builder().setTimeline(timeline).setPeriodUid(timeline.getUidOfPeriod(0)).setPositionOffsetUs(atUs).build())
        }
    }

    private fun SingProcessor.run(input: ByteArray): ShortArray {
        queueInput(ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN))
        val out = output.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(out.remaining()).also { out.get(it) }
    }

    @Test fun withSingOnTheInstrumentalPlaysFromTheFlushedPosition() {
        val store = SingStore(File(folder.root, "sing"))
        store.keep("local:7", 44_100) { f, c -> 10_000 + f % 100 + c }
        SingSwitch.setForTest(true)
        try {
            // From 0.5 s: frame 22,050 of the instrumental, after a flush that put the switch fully on.
            val p = processor(store, "local:7", atUs = 500_000)
            val out = p.run(pcm(100, 2) { _, _ -> 5 })
            assertEquals((10_000 + 22_050 % 100).toShort(), out[0]); assertEquals((10_000 + 22_050 % 100 + 1).toShort(), out[1])
            // Past the instrumental's end the song passes through.
            val late = processor(store, "local:7", atUs = 1_100_000).run(pcm(10, 2) { _, _ -> 5 })
            assertTrue(late.all { it == 5.toShort() })
            // Another song, or another format, passes through too.
            assertTrue(processor(store, "local:8").run(pcm(10, 2) { _, _ -> 5 }).all { it == 5.toShort() })
            assertTrue(processor(store, "local:7", rate = 48_000).run(pcm(10, 2) { _, _ -> 5 }).all { it == 5.toShort() })
        } finally { SingSwitch.setForTest(false) }
    }

    @Test fun turningSingOffGlidesBackToTheSongWithoutAJump() {
        val store = SingStore(File(folder.root, "sing"))
        store.keep("local:7", 44_100) { _, _ -> 10_000 }
        SingSwitch.setForTest(true)
        val p = processor(store, "local:7")
        try {
            assertTrue(p.run(pcm(10, 2) { _, _ -> 0 }).all { it == 10_000.toShort() })
        } finally { SingSwitch.setForTest(false) }
        val out = p.run(pcm(2_000, 2) { _, _ -> 0 })
        val left = ShortArray(2_000) { out[it * 2] }
        assertTrue("starts near the instrumental", left[0] > 9_900)
        assertTrue("falls steadily", (1 until left.size).all { left[it] <= left[it - 1] && left[it - 1] - left[it] < 20 })
        assertEquals(0.toShort(), left.last())
        assertArrayEquals(ShortArray(20), p.run(pcm(10, 2) { _, _ -> 0 }))
    }
}
