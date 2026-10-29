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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Feature 9, Path A for streams: a stream's bytes are read from Riff's cache only once all of them are there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CachedTailTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val cache get() = MusicDiskCache.get(context)
    private val key = "audio:v2:test:direct:${UUID.randomUUID()}"
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
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
        val source = TrackAnalysis.cachedSource(context, item()) ?: throw AssertionError("a whole cached stream is read")
        source.use {
            assertEquals(bytes.size.toLong(), it.size)
            val tail = ByteArray(1_000)
            assertEquals(1_000, it.readAt(bytes.size - 1_000L, tail, 0, 1_000))
            assertArrayEquals(bytes.copyOfRange(bytes.size - 1_000, bytes.size), tail)
            // Back to the start, as an extractor does after reading the end of a file for its index.
            val head = ByteArray(4_096)
            assertEquals(4_096, it.readAt(0, head, 0, 4_096))
            assertArrayEquals(bytes.copyOfRange(0, 4_096), head)
            // Straight on from there, without opening again.
            assertEquals(4_096, it.readAt(4_096, head, 0, 4_096))
            assertArrayEquals(bytes.copyOfRange(4_096, 8_192), head)
            assertEquals("a read that runs past the end is cut at it", 10, it.readAt(bytes.size - 10L, head, 0, 4_096))
            assertEquals(-1, it.readAt(bytes.size.toLong(), head, 0, 10))
        }
    }

    @Test fun aStreamNotYetCachedToItsEndIsNotRead() {
        write(0, bytes.size / 2)
        assertNull("the length is not known until the download reaches the end", TrackAnalysis.cachedSource(context, item()))
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, bytes.size.toLong()) })
        assertNull("half of it is there", TrackAnalysis.cachedSource(context, item()))
        write(bytes.size / 2, bytes.size)
        assertNotNull(TrackAnalysis.cachedSource(context, item()))
    }

    @Test fun aSongTheCacheDoesNotKeepIsNotRead() {
        write(0, bytes.size)
        assertNull("live, video and HLS have no cache key", TrackAnalysis.cachedSource(context, item(cacheKey = null)))
    }
}
