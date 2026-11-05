package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class CachedStreamIndexTest {
    private val entry = CachedStreamEntry("audio:v2:me:direct:song:251:audio/webm:144000", 251, "audio/webm; codecs=\"opus\"", 144_000,
        2_000_000, 200_000, -3.1, null, false)
    private fun indexFile() = Files.createTempDirectory("index").resolve("index.tsv").toFile()

    @Test fun `a song with all its bytes on disk is whole`() {
        assertEquals(CachedPart.Whole, cachedPart(entry, 0) { _, _ -> true })
    }

    @Test fun `a song with only its start on disk starts from there`() {
        val asked = mutableListOf<Pair<Long, Long>>()
        val part = cachedPart(entry, 0) { position, length -> asked += position to length; length <= CACHED_START_BYTES }
        assertEquals(CachedPart.Start, part)
        assertEquals(listOf(0L to entry.contentLength!!, 0L to CACHED_START_BYTES), asked)
    }

    @Test fun `too little on disk means a lookup`() {
        assertNull(cachedPart(entry, 0) { _, _ -> false })
    }

    @Test fun `a SABR song needs every byte because continuing it needs a lookup's session`() {
        val sabr = entry.copy(sabr = true)
        assertNull(cachedPart(sabr, 0) { _, length -> length <= CACHED_START_BYTES })
        assertEquals(CachedPart.Whole, cachedPart(sabr, 0) { _, _ -> true })
    }

    @Test fun `with no declared length only a whole song the cache knows the size of will do`() {
        val undeclared = entry.copy(contentLength = null)
        assertNull(cachedPart(undeclared, 0) { _, _ -> true })
        assertNull(cachedPart(undeclared, 900_000) { _, length -> length < 900_000 })
        assertEquals(CachedPart.Whole, cachedPart(undeclared, 900_000) { _, _ -> true })
    }

    @Test fun `a stream whose bytes are not kept or cannot be continued gets no entry`() {
        val stream = ProviderStream("https://x", emptyMap(), "audio/webm", itag = 251, bitrate = 144_000, contentLength = 5)
        assertEquals("key", cachedStreamEntry(stream, "key")?.cacheKey)
        assertNull(cachedStreamEntry(stream, null))
        assertNull(cachedStreamEntry(stream.copy(itag = 0), "key"))
        assertNull(cachedStreamEntry(stream.copy(includesVideo = true), "key"))
        assertNull(cachedStreamEntry(stream.copy(boundedRange = true), "key"))
        assertNull(cachedStreamEntry(stream.copy(videoUrl = "https://v"), "key"))
    }

    @Test fun `entries survive a restart and are separate by account and quality`() {
        val file = indexFile()
        val bare = entry.copy(mimeType = null, contentLength = null, loudnessDb = null, sourceVideoId = "other", sabr = true)
        val first = CachedStreamIndex(file)
        first.put("me", "song", "HIGH", entry)
        first.put("me", "bare", "HIGH", bare)
        val again = CachedStreamIndex(file)
        assertEquals(entry, again.get("me", "song", "HIGH"))
        assertEquals(bare, again.get("me", "bare", "HIGH"))
        assertNull(again.get("you", "song", "HIGH"))
        assertNull(again.get("me", "song", "LOW"))
    }

    @Test fun `the oldest entries go first and clearing takes everything`() {
        val index = CachedStreamIndex(indexFile(), keep = 2)
        index.put("me", "a", "HIGH", entry); index.put("me", "b", "HIGH", entry); index.put("me", "c", "HIGH", entry)
        assertNull(index.get("me", "a", "HIGH"))
        assertEquals(entry, index.get("me", "c", "HIGH"))
        index.remove("me", "c", "HIGH")
        assertNull(index.get("me", "c", "HIGH"))
        index.clear()
        assertNull(index.get("me", "b", "HIGH"))
    }

    @Test fun `a damaged index line costs only that line`() {
        val file = indexFile()
        CachedStreamIndex(file).put("me", "song", "HIGH", entry)
        file.appendText("\nnot an entry\n")
        assertEquals(entry, CachedStreamIndex(file).get("me", "song", "HIGH"))
    }

    @Test fun `a stand-in address names no real host, is out of date, and keeps the cache key`() {
        val direct = cachedStreamStandIn(entry, "song")
        assertEquals(CACHED_STREAM_HOST, Uri.parse(direct.url).host)
        assertEquals("https", Uri.parse(direct.url).scheme)
        assertEquals(entry.cacheKey, direct.cachedKey)
        assertEquals(entry.loudnessDb, direct.loudnessDb)
        assertFalse(reusableStream(streamUrlExpiry(direct.url, direct.expiresAtMs, 1_000), 1_000, false))
        assertEquals("sabr", Uri.parse(cachedStreamStandIn(entry.copy(sabr = true), "song").url).scheme)
        assertEquals(NARROW_STREAM_RANGE_BYTES, streamRangeBytes(Uri.parse(direct.url)))
    }

    @Test fun `a real lookup for the same bytes passes the representation check`() {
        val real = ProviderStream("https://rr1.googlevideo.com/videoplayback", emptyMap(), entry.mimeType, itag = entry.itag,
            bitrate = entry.bitrate, contentLength = entry.contentLength, expiresAtMs = Long.MAX_VALUE)
        assertTrue(sameStreamRepresentation(cachedStreamStandIn(entry, "song"), real))
        assertFalse(sameStreamRepresentation(cachedStreamStandIn(entry, "song"), real.copy(itag = 140)))
    }

    /** The whole chain, as playback builds it: nothing is looked up until a read goes past the bytes on disk. */
    @Test fun `only a read past the cached bytes looks up an address`() {
        val bytes = ByteArray(3_000) { (it % 251).toByte() }
        val cache = SimpleCache(Files.createTempDirectory("cache").toFile(), NoOpCacheEvictor(), StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()))
        try {
            val lookups = AtomicInteger()
            val standIn = cachedStreamStandIn(entry.copy(cacheKey = "song", contentLength = 3_000), "song")
            val network = object : BaseDataSource(true) {
                var at = 0; var end = 0; var lastUri: Uri? = null
                override fun open(dataSpec: DataSpec): Long {
                    lastUri = dataSpec.uri; at = dataSpec.position.toInt()
                    end = minOf(3_000L, dataSpec.position + dataSpec.length).toInt()
                    return (end - at).toLong()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (at >= end) return C.RESULT_END_OF_INPUT
                    val count = minOf(length, end - at); bytes.copyInto(buffer, offset, at, at + count); at += count; return count
                }
                override fun getUri() = lastUri
                override fun close() {}
            }
            // An earlier play left the song's first 1,000 bytes in the cache.
            val earlier = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { network }.createDataSource()
            earlier.open(DataSpec.Builder().setUri("https://x").setKey("song").setLength(1_000).build())
            val seed = ByteArray(400)
            while (earlier.read(seed, 0, seed.size) >= 0) { /* written through to the cache */ }
            earlier.close()
            val addressed = streamUrlFactory({ network }, standIn, { 1_000L }) {
                lookups.incrementAndGet()
                standIn.copy(url = "https://rr1.googlevideo.com/videoplayback", expiresAtMs = Long.MAX_VALUE)
            }
            val upstream = DataSource.Factory { ChunkedStreamDataSource(addressed.createDataSource(), standIn.contentLength) }
            val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            val song = DataSpec.Builder().setUri(standIn.url).setKey("song").build()
            val buffer = ByteArray(400)

            val partOnDisk = factory.createDataSource()
            partOnDisk.open(song.buildUpon().setLength(1_000).build())
            var total = 0
            while (true) { val n = partOnDisk.read(buffer, 0, buffer.size); if (n < 0) break; total += n }
            partOnDisk.close()
            assertEquals(1_000, total)
            assertEquals("the bytes on disk need no address", 0, lookups.get())

            val onwards = factory.createDataSource()
            onwards.open(song)
            val out = ByteArrayOutputStream()
            while (true) { val n = onwards.read(buffer, 0, buffer.size); if (n < 0) break; out.write(buffer, 0, n) }
            onwards.close()
            assertArrayEquals(bytes, out.toByteArray())
            assertEquals("going past them looks up once", 1, lookups.get())
            assertEquals("https://rr1.googlevideo.com/videoplayback", network.lastUri.toString())
        } finally { cache.release() }
    }

    @Test fun `an address that does not lead to the same bytes is refused, not stitched on`() {
        val standIn = cachedStreamStandIn(entry.copy(contentLength = 3_000), "song")
        val noRequest = object : BaseDataSource(true) {
            override fun open(dataSpec: DataSpec): Long = throw AssertionError("no request may be made")
            override fun read(buffer: ByteArray, offset: Int, length: Int) = C.RESULT_END_OF_INPUT
            override fun getUri(): Uri? = null
            override fun close() {}
        }
        val addressed = streamUrlFactory({ noRequest }, standIn, { 1_000L }) {
            standIn.copy(url = "https://rr1.googlevideo.com/videoplayback", itag = 140, expiresAtMs = Long.MAX_VALUE)
        }
        assertThrows(StreamRepresentationChanged::class.java) { addressed.createDataSource().open(DataSpec(Uri.parse(standIn.url))) }
    }

    @Test fun `only a media key that found no queue holds the foreground`() {
        assertTrue(mediaKeyNeedsHold(android.content.Intent.ACTION_MEDIA_BUTTON, 0))
        assertFalse(mediaKeyNeedsHold(android.content.Intent.ACTION_MEDIA_BUTTON, 3))
        assertFalse(mediaKeyNeedsHold(null, 0))
        assertFalse(mediaKeyNeedsHold("other", 0))
    }

    @Test fun `only a waiting catalog song is looked up on return`() {
        assertTrue(returnLookupWanted(false, false, "catalog", "yt:song", false))
        assertFalse(returnLookupWanted(true, true, "catalog", "yt:song", false))
        assertFalse("Play was already pressed: its own lookup runs", returnLookupWanted(false, true, "catalog", "yt:song", false))
        assertFalse(returnLookupWanted(false, false, "file", "yt:song", false))
        assertFalse(returnLookupWanted(false, false, "catalog", "local:song", false))
        assertFalse(returnLookupWanted(false, false, "catalog", "yt:song", true))
    }

    @Test fun `an address keeps its own expiry, and one with none gets twenty minutes`() {
        val now = 1_000_000L
        val sixHours = now / 1000 + 6 * 3_600
        assertEquals(sixHours * 1000, streamUrlExpiry("https://rr1.googlevideo.com/x?expire=$sixHours", 0, now))
        assertEquals(now + 1_000, streamUrlExpiry("https://rr1.googlevideo.com/x?expire=$sixHours", now + 1_000, now))
        assertEquals(now + 5_000, streamUrlExpiry("https://rr1.googlevideo.com/x", now + 5_000, now))
        assertEquals(now + UNDATED_STREAM_MS, streamUrlExpiry("https://rr1.googlevideo.com/x", 0, now))
    }
}
