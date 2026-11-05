package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class ChunkedStreamDataSourceTest {
    private val file = ByteArray(2_500) { (it % 251).toByte() }
    private val small: (Uri) -> Long = { 1_000L }

    /** Serves [file] at the range it is asked for. [served] caps what a single open sends; [refuseFrom] fails an open there. */
    private inner class Upstream(val served: Int = Int.MAX_VALUE, val refuseFrom: Long = -1) : BaseDataSource(true) {
        val specs = mutableListOf<DataSpec>()
        var opens = 0; var closes = 0
        private var at = 0; private var sent = 0; private var end = 0
        override fun open(dataSpec: DataSpec): Long {
            specs += dataSpec; opens++
            if (dataSpec.position == refuseFrom) throw HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), dataSpec, ByteArray(0))
            at = dataSpec.position.toInt(); sent = 0
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size else minOf(file.size.toLong(), dataSpec.position + dataSpec.length).toInt()
            return (end - at).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= end || sent >= served) return C.RESULT_END_OF_INPUT
            val count = minOf(length, end - at, served - sent)
            file.copyInto(buffer, offset, at, at + count)
            at += count; sent += count
            return count
        }
        override fun getUri(): Uri? = specs.lastOrNull()?.uri
        override fun close() { closes++ }
    }

    private fun DataSource.drain(): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(700)
        while (true) { val count = read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) return out.toByteArray(); out.write(buffer, 0, count) }
    }

    private fun spec(url: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build()

    private val withLength = "https://rr1.googlevideo.com/videoplayback?clen=2500"

    @Test fun `a stream of known length is read as bounded ranges in order`() {
        val upstream = Upstream()
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        assertEquals(2_500L, source.open(spec(withLength)))
        assertArrayEquals(file, source.drain())
        source.close()
        assertEquals(listOf(0L to 1_000L, 1_000L to 1_000L, 2_000L to 500L), upstream.specs.map { it.position to it.length })
        assertEquals(3, upstream.opens); assertEquals(3, upstream.closes)
    }

    @Test fun `a stream with no known length is one open-ended read and still plays`() {
        val upstream = Upstream()
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        assertEquals(2_500L, source.open(spec("https://rr1.googlevideo.com/videoplayback")))
        assertArrayEquals(file, source.drain())
        source.close()
        assertEquals(C.LENGTH_UNSET.toLong(), upstream.specs.single().length)
        assertEquals(1, upstream.closes)
    }

    @Test fun `the length the provider gave is used when the address has none`() {
        val upstream = Upstream()
        val source = ChunkedStreamDataSource(upstream, knownLength = 2_500, rangeBytes = small)
        assertEquals(2_500L, source.open(spec("https://rr1.googlevideo.com/videoplayback")))
        assertArrayEquals(file, source.drain())
        assertEquals(3, upstream.opens)
    }

    @Test fun `a read of part of the file stays inside what was asked for`() {
        val upstream = Upstream()
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        assertEquals(1_200L, source.open(spec(withLength, position = 300, length = 1_200)))
        assertArrayEquals(file.copyOfRange(300, 1_500), source.drain())
        assertEquals(listOf(300L to 1_000L, 1_300L to 200L), upstream.specs.map { it.position to it.length })
    }

    @Test fun `a range that ends early is opened again for what did not arrive`() {
        val upstream = Upstream(served = 400)
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        source.open(spec(withLength))
        assertArrayEquals(file, source.drain())
        assertTrue(upstream.specs.all { it.length <= 1_000 })
        assertEquals(listOf(0L, 400L, 800L), upstream.specs.take(3).map { it.position })
    }

    @Test fun `a host that sends nothing ends the read instead of spinning`() {
        val upstream = Upstream(served = 0)
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        source.open(spec(withLength))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        assertEquals(3, upstream.opens)
    }

    @Test fun `opening at the end of the file makes no request`() {
        val upstream = Upstream()
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        assertEquals(0L, source.open(spec(withLength, position = 2_500)))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(10), 0, 10))
        source.close()
        assertEquals(0, upstream.opens); assertEquals(0, upstream.closes)
    }

    @Test fun `a refused range reaches the caller, which can still close`() {
        val upstream = Upstream(refuseFrom = 1_000)
        val source = ChunkedStreamDataSource(upstream, rangeBytes = small)
        source.open(spec(withLength))
        val buffer = ByteArray(1_000)
        var got = 0
        while (got < 1_000) got += source.read(buffer, 0, 1_000 - got)
        val refused = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) { source.read(buffer, 0, 10) }
        assertEquals(403, refused.responseCode)
        source.close()
        assertEquals(2, upstream.closes)
    }

    @Test fun `range sizes follow the host and the client`() {
        fun size(url: String) = streamRangeBytes(Uri.parse(url))
        assertEquals(1024L * 1024, size("https://rr1.googlevideo.com/videoplayback?c=IOS&clen=5"))
        assertEquals(1024L * 1024, size("https://rr1.googlevideo.com/videoplayback?clen=5"))
        assertEquals(512L * 1024, size("https://rr1.googlevideo.com/videoplayback?c=ANDROID_VR&clen=5"))
        assertEquals(512L * 1024, size("https://rr1.googlevideo.com/videoplayback?c=TVHTML5_SIMPLY&clen=5"))
        assertEquals(Long.MAX_VALUE, size("https://example.com/song?clen=5"))
        assertEquals(Long.MAX_VALUE, size("https://notgooglevideo.com/song?clen=5"))
    }

    /** The real chain over HTTP: cache, chunking, the address resolver and OkHttp. A second read comes from disk alone. */
    @Test fun `under the cache every request is bounded and a cached song makes none`() {
        val ranges = CopyOnWriteArrayList<String?>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        Thread { while (!server.isClosed) { val socket = try { server.accept() } catch (_: Exception) { break }; Thread { serve(socket, ranges) }.start() } }
            .apply { isDaemon = true }.start()
        val folder: File = Files.createTempDirectory("chunked-cache").toFile()
        val database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val cache = SimpleCache(folder, NoOpCacheEvictor(), database)
        try {
            val url = "http://127.0.0.1:${server.localPort}/videoplayback?clen=${file.size}"
            val stream = ProviderStream(url, emptyMap(), "audio/webm", expiresAtMs = System.currentTimeMillis() + 3_600_000, itag = 251, contentLength = file.size.toLong())
            val addressed = streamUrlFactory({ OkHttpStreamDataSource(OkHttpStreamDataSource.client(), emptyMap()) }, stream, System::currentTimeMillis) { stream }
            val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { ChunkedStreamDataSource(addressed.createDataSource(), stream.contentLength, small) }
            val song = DataSpec.Builder().setUri(url).setKey("yt:song").build()

            val first = factory.createDataSource()
            assertEquals(file.size.toLong(), first.open(song))
            assertArrayEquals(file, first.drain())
            first.close()
            assertEquals(listOf("bytes=0-999", "bytes=1000-1999", "bytes=2000-2499"), ranges.toList())

            ranges.clear()
            val second = factory.createDataSource()
            second.open(song)
            assertArrayEquals(file, second.drain())
            second.close()
            assertTrue(ranges.isEmpty())
        } finally {
            cache.release(); server.close(); folder.deleteRecursively()
        }
    }

    private fun serve(socket: Socket, ranges: MutableList<String?>) = socket.use {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1); val out = socket.getOutputStream()
        while (true) {
            val requestLine = input.readLine() ?: return
            if (requestLine.isEmpty()) continue
            val range = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                .firstOrNull { it.startsWith("range:", ignoreCase = true) }?.substringAfter(':')?.trim()
            ranges += range
            val (from, to) = checkNotNull(range).removePrefix("bytes=").split('-').let { it[0].toInt() to it[1].toInt() }
            val body = file.copyOfRange(from, to + 1)
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${file.size}\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
            out.write(body); out.flush()
        }
    }
}
