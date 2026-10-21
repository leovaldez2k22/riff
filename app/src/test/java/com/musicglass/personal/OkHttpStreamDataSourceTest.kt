package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q18-057: streamed songs read through OkHttp, with ranges, errors and headers as Media3's own HTTP source handled them. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class OkHttpStreamDataSourceTest {
    private val file = ByteArray(10_000) { (it % 251).toByte() }
    private val ranges = CopyOnWriteArrayList<String?>()
    private val agents = CopyOnWriteArrayList<String?>()
    private val ports = CopyOnWriteArrayList<Int>()
    private var ignoreRange = false
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val client = OkHttpStreamDataSource.client()

    /** A small HTTP/1.1 server with keep-alive: /song honours ranges (unless [ignoreRange]), /gone refuses. */
    @Before fun start() {
        Thread { while (!server.isClosed) { val socket = try { server.accept() } catch (_: Exception) { break }; Thread { serve(socket) }.start() } }
            .apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1); val out = socket.getOutputStream()
        while (true) {
            val requestLine = input.readLine() ?: return
            if (requestLine.isEmpty()) continue
            val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }
                .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val path = requestLine.split(' ')[1]
            ranges += headers["range"]; agents += headers["user-agent"]; ports += socket.port
            val from = headers["range"]?.takeUnless { ignoreRange }?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
            val (status, extra, body) = when {
                path == "/gone" -> Triple("403 Forbidden", "", "expired".toByteArray())
                from != null -> Triple("206 Partial Content", "Content-Range: bytes $from-${file.size - 1}/${file.size}\r\n", file.copyOfRange(from, file.size))
                else -> Triple("200 OK", "", file)
            }
            out.write("HTTP/1.1 $status\r\n${extra}Content-Length: ${body.size}\r\n\r\n".toByteArray()); out.write(body); out.flush()
        }
    }

    @After fun stop() { server.close() }

    private fun url(path: String) = Uri.parse("http://127.0.0.1:${server.localPort}$path")

    private fun readAll(source: OkHttpStreamDataSource, spec: DataSpec): ByteArray {
        val length = source.open(spec)
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(1024)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, n) }
        source.close()
        if (length != C.LENGTH_UNSET.toLong()) assertEquals(length, out.size().toLong())
        return out.toByteArray()
    }

    @Test fun aSeekAsksForTheRestFromThatByte() {
        val source = OkHttpStreamDataSource(client, mapOf("User-Agent" to "riff-test"))
        assertArrayEquals(file, readAll(source, DataSpec(url("/song"))))
        assertArrayEquals(file.copyOfRange(6_000, file.size), readAll(source, DataSpec.Builder().setUri(url("/song")).setPosition(6_000).build()))
        assertEquals(listOf(null, "bytes=6000-"), ranges.toList())
        // The stream's own agent wins over the platform's.
        assertEquals(listOf("riff-test", "riff-test"), agents.toList())
        assertEquals(file.copyOfRange(100, 300).toList(), readAll(source,
            DataSpec.Builder().setUri(url("/song")).setPosition(100).setLength(200).build()).toList())
    }

    @Test fun aServerThatIgnoresTheRangeIsSkippedToThePosition() {
        ignoreRange = true
        val source = OkHttpStreamDataSource(client, emptyMap())
        assertArrayEquals(file.copyOfRange(7_500, file.size), readAll(source, DataSpec.Builder().setUri(url("/song")).setPosition(7_500).build()))
    }

    @Test fun aRefusedStreamIsAnInvalidResponseThatRecoveryCanRead() {
        val source = OkHttpStreamDataSource(client, emptyMap())
        try { source.open(DataSpec(url("/gone"))); fail("403 must fail") }
        catch (error: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, error.responseCode); assertEquals("expired", String(error.responseBody))
        }
        source.close()
    }

    @Test fun anUnreachableHostIsANetworkFailure() {
        val source = OkHttpStreamDataSource(client, emptyMap())
        val closed = ServerSocket(0).let { val port = it.localPort; it.close(); port }
        try { source.open(DataSpec(Uri.parse("http://127.0.0.1:$closed/song"))); fail("must fail") }
        catch (error: HttpDataSource.HttpDataSourceException) {
            assertEquals(HttpDataSource.HttpDataSourceException.TYPE_OPEN, error.type)
            assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it is java.net.ConnectException })
        }
    }

    @Test fun oneConnectionServesSongAfterSong() {
        val source = OkHttpStreamDataSource(client, emptyMap())
        repeat(3) { readAll(source, DataSpec(url("/song"))) }
        assertEquals(1, ports.distinct().size)
    }
}
