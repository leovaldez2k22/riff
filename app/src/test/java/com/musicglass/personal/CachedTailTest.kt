package com.musicglass.personal

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Feature 9, Path A for streams: a stream is read from Riff's cache once its start and its tail are there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CachedTailTest {
    private companion object { const val DURATION = 240_000L }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val cache get() = MusicDiskCache.get(context)
    private val key = "audio:v2:test:direct:${UUID.randomUUID()}"
    private val bytes = ByteArray(2_000_000) { (it * 31 % 251).toByte() }
    private fun item(cacheKey: String? = key) = MediaItem.Builder().setMediaId("yt:fixtureAAAA").setUri("https://example.invalid/a").setCustomCacheKey(cacheKey).build()

    /** Caches [from] to [to] as playback would: a read that reaches the end of the stream also records its length. */
    private fun write(from: Int, to: Int) {
        val spec = DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(from.toLong())
            .apply { if (to < bytes.size) setLength((to - from).toLong()) }.build()
        CacheWriter(CacheDataSource(cache, ByteArrayDataSource(bytes)), spec, null, null).cache()
    }

    @After fun clear() { cache.removeResource(key) }

    @Test fun aStreamCachedToItsEndIsReadExactlyAndAnywhere() {
        write(0, bytes.size)
        val source = TrackAnalysis.cachedSource(context, item(), DURATION)?.source ?: throw AssertionError("a whole cached stream is read")
        source.use {
            assertEquals(bytes.size.toLong(), it.size)
            val tail = ByteArray(1_000)
            assertEquals(1_000, it.readAt(bytes.size - 1_000L, tail, 0, 1_000))
            assertArrayEquals(bytes.copyOfRange(bytes.size - 1_000, bytes.size), tail)
            // Back to the start, as an extractor does after reading the end of a file for its index.
            val head = ByteArray(4_096)
            assertEquals(4_096, it.readAt(0, head, 0, 4_096))
            assertArrayEquals(bytes.copyOfRange(0, 4_096), head)
            // Straight on from there.
            assertEquals(4_096, it.readAt(4_096, head, 0, 4_096))
            assertArrayEquals(bytes.copyOfRange(4_096, 8_192), head)
            assertEquals("a read that runs past the end is cut at it", 10, it.readAt(bytes.size - 10L, head, 0, 4_096))
            assertEquals(-1, it.readAt(bytes.size.toLong(), head, 0, 10))
        }
    }

    @Test fun aStreamNotYetCachedToItsEndIsNotRead() {
        write(0, bytes.size / 2)
        assertNull("the length is not known until the download reaches the end", TrackAnalysis.cachedSource(context, item(), DURATION))
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, bytes.size.toLong()) })
        assertNull("half of it is there", TrackAnalysis.cachedSource(context, item(), DURATION))
        write(bytes.size / 2, bytes.size)
        assertNotNull(TrackAnalysis.cachedSource(context, item(), DURATION))
    }

    @Test fun aSeekAheadLeavesAHoleTheTailNeverReads() {
        // Played from the start for a while, then sought to the last minute and played to the end.
        write(0, CACHED_HEAD_BYTES.toInt() + 40_000)
        write(bytes.size - cachedTailBytes(bytes.size.toLong(), DURATION).toInt() - 10_000, bytes.size)
        val source = TrackAnalysis.cachedSource(context, item(), DURATION)?.source ?: throw AssertionError("the head and the tail are there")
        source.use {
            val tail = ByteArray(1_000)
            assertEquals(1_000, it.readAt(bytes.size - 1_000L, tail, 0, 1_000))
            assertArrayEquals(bytes.copyOfRange(bytes.size - 1_000, bytes.size), tail)
            // The hole is an error for the extractor, never a download.
            assertThrows(java.io.IOException::class.java) { it.readAt(bytes.size / 2L, tail, 0, 10) }
        }
    }

    @Test fun aSeekIntoTheLastMinuteLeavesAShorterTail() {
        write(0, CACHED_HEAD_BYTES.toInt() + 40_000)
        // Sought to 30 s before the end of a 4 min song: 27 s of it can be read, once 3 s are kept back.
        write(bytes.size - bytes.size / 8, bytes.size)
        assertEquals(27_000L, TrackAnalysis.cachedSource(context, item(), DURATION)?.tailMs)
    }

    @Test fun tooShortARunIsLeftToPathB() {
        write(0, CACHED_HEAD_BYTES.toInt() + 40_000)
        write(bytes.size - bytes.size / 24, bytes.size)
        assertNull("10 s minus the 3 s kept back is too little", TrackAnalysis.cachedSource(context, item(), DURATION))
    }

    @Test fun theReadableTailIsTheRunsShareUpToFortyFiveSeconds() {
        assertEquals(ANALYSIS_TAIL_MS, cachedTailMs(1_000, 0, 240_000))
        assertEquals(27_000L, cachedTailMs(1_000, 875, 240_000))
        assertEquals(0L, cachedTailMs(1_000, 1_000, 240_000))
        assertEquals(0L, cachedTailMs(0, 0, 240_000))
    }

    @Test fun withoutItsStartTheStreamIsNotRead() {
        write(bytes.size / 2, bytes.size)
        assertNull(TrackAnalysis.cachedSource(context, item(), DURATION))
    }

    @Test fun theTailShareFollowsTheLengthWithRoomForAVariableBitrate() {
        assertEquals(240_000L * 58_250 / 240_000, cachedTailBytes(240_000, 240_000))
        assertEquals("a song shorter than the tail is read whole", 1_000L, cachedTailBytes(1_000, 30_000))
    }

    @Test fun aSongTheCacheDoesNotKeepIsNotRead() {
        write(0, bytes.size)
        assertNull("live, video and HLS have no cache key", TrackAnalysis.cachedSource(context, item(cacheKey = null), DURATION))
    }
}
