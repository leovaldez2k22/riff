package com.musicglass.personal

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class StreamUrlDataSourceTest {
    private class RecordingSource : DataSource {
        val opened = mutableListOf<DataSpec>()
        override fun open(spec: DataSpec): Long { opened += spec; return spec.length }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = opened.lastOrNull()?.uri
        override fun addTransferListener(listener: TransferListener) {}
        override fun close() {}
    }
    private fun stream(url: String, expiry: Long) = ProviderStream(url, mapOf("User-Agent" to "fresh-agent"),
        "audio/webm", sourceVideoId = "fxsong00001", itag = 251, bitrate = 128000, contentLength = 9000, expiresAtMs = expiry)

    @Test fun expiredAddressRefreshesAtOpenAndPreservesRangeAndCacheIdentity() {
        var now = 1_000_000L
        val original = stream("https://media.example/old", now + 120_000)
        val upstream = RecordingSource()
        var calls = 0
        val factory = streamUrlFactory(DataSource.Factory { upstream }, original, { now }) {
            calls++; stream("https://media.example/fresh", now + 600_000)
        }
        val spec = DataSpec.Builder().setUri(original.url).setPosition(3000).setLength(1000).setKey("owner:track:251").build()
        factory.createDataSource().also { it.open(spec); it.close() }
        assertEquals(0, calls)
        now += 90_000
        factory.createDataSource().also { it.open(spec); it.close() }
        assertEquals(1, calls)
        val opened = upstream.opened.last()
        assertEquals("https://media.example/fresh", opened.uri.toString())
        assertEquals(3000L, opened.position); assertEquals(1000L, opened.length)
        assertEquals(spec.key, opened.key); assertEquals("fresh-agent", opened.httpRequestHeaders["User-Agent"])
        factory.createDataSource().also { it.open(spec); it.close() }
        assertEquals("subsequent opens reuse the renewed URL", 1, calls)
    }

    @Test fun aDifferentEncodingNeverReceivesAnOldByteOffset() {
        val upstream = RecordingSource()
        val old = stream("https://media.example/old", 1)
        val source = streamUrlFactory(DataSource.Factory { upstream }, old, { 1_000_000 }) {
            stream("https://media.example/fresh", 2_000_000).copy(itag = 140, mimeType = "audio/mp4")
        }.createDataSource()
        try { source.open(DataSpec.Builder().setUri(old.url).setPosition(3000).build()); fail("changed encoding") }
        catch (_: StreamRepresentationChanged) { assertTrue(upstream.opened.isEmpty()) }
        finally { source.close() }
    }

    @Test fun onlyAPlainProgressiveAddressCountsAsDirectAudio() {
        val direct = stream("https://media.example/a", 2_000_000)
        assertTrue(isDirectAudio(direct, "https", live = false))
        assertFalse("nothing resolved", isDirectAudio(null, "https", live = false))
        assertFalse("a live entry", isDirectAudio(direct, "https", live = true))
        assertFalse("a download or other scheme", isDirectAudio(direct, "file", live = false))
        assertFalse("HLS", isDirectAudio(direct.copy(mimeType = "application/x-mpegURL"), "https", live = false))
        assertFalse("carries video", isDirectAudio(direct.copy(includesVideo = true), "https", live = false))
        assertFalse("separate video", isDirectAudio(direct.copy(videoUrl = "https://media.example/v"), "https", live = false))
    }

    @Test fun connectionsAreDroppedOnlyAfterALongQuietCountedAcrossSleep() {
        var now = 1_000L
        val quiet = QuietConnections({ now })
        assertFalse("the first request has nothing before it", quiet.afterLongQuiet())
        now += QUIET_CONNECTIONS_MS - 1
        assertFalse(quiet.afterLongQuiet())
        now += QUIET_CONNECTIONS_MS
        assertTrue(quiet.afterLongQuiet())
        assertFalse("the requests right after it keep the new connections", quiet.afterLongQuiet())
    }

    @Test fun signedExpiryWinsOverDeclaredTtlAndInvalidExpiryIsBounded() {
        assertEquals(1_100_000L, streamUrlExpiry("https://media.example/a?expire=1100", 2_000_000, 1_000_000))
        assertEquals(2_200_000L, streamUrlExpiry("https://media.example/a?expire=9223372036854775807", 0, 1_000_000))
        assertFalse(reusableStream(1_060_000, 1_000_000, false))
    }
}
