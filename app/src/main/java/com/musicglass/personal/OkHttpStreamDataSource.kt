package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.HttpUtil
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Streams over OkHttp (Q18-057). A seek outside the buffer abandons the open response; Android's own
 * HttpURLConnection then drops the connection, so every seek paid for a new TCP and TLS handshake. OkHttp
 * speaks HTTP/2 to the stream hosts and resets only the abandoned stream, so the next range request reuses
 * the connection. One [client] is shared by every source so its pool lasts across songs.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class OkHttpStreamDataSource(private val client: OkHttpClient, private val defaults: Map<String, String>) :
    BaseDataSource(true), HttpDataSource {
    private val properties = HttpDataSource.RequestProperties()
    private var spec: DataSpec? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec; bytesRead = 0; bytesToRead = 0
        transferInitializing(dataSpec)
        val request = Request.Builder().url(dataSpec.uri.toString()).apply {
            // HttpURLConnection's agent unless the stream asked for its own, as before.
            System.getProperty("http.agent")?.let { header("User-Agent", it) }
            (defaults + properties.snapshot + dataSpec.httpRequestHeaders).forEach { (name, value) -> header(name, value) }
            HttpUtil.buildRangeRequestHeader(dataSpec.position, dataSpec.length)?.let { header("Range", it) }
            if (!dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) header("Accept-Encoding", "identity")
            val body = dataSpec.httpBody?.toRequestBody() ?: if (dataSpec.httpMethod == DataSpec.HTTP_METHOD_POST) ByteArray(0).toRequestBody() else null
            method(dataSpec.httpMethodString, body)
        }.build()
        val answer = try { client.newCall(request).execute() }
            catch (error: IOException) { throw HttpDataSourceException.createForIOException(error, dataSpec, HttpDataSourceException.TYPE_OPEN) }
        response = answer
        stream = answer.body.byteStream()
        val code = answer.code
        if (code !in 200..299) {
            if (code == 416 && dataSpec.position == HttpUtil.getDocumentSize(answer.header("Content-Range"))) {
                // Asked for the bytes after the last one: nothing is left.
                opened = true; transferStarted(dataSpec)
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else 0
            }
            val body = try { stream?.readBytes() ?: ByteArray(0) } catch (_: IOException) { ByteArray(0) }
            val headers = answer.headers.toMultimap()
            closeResponse()
            val cause = if (code == 416) DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) else null
            throw InvalidResponseCodeException(code, answer.message, cause, headers, dataSpec, body)
        }
        // A server that ignored the range sent the whole file: skip to the position asked for.
        val skip = if (code == 200 && dataSpec.position != 0L) dataSpec.position else 0L
        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else answer.body.contentLength().let { if (it != -1L) it - skip else C.LENGTH_UNSET.toLong() }
        opened = true
        transferStarted(dataSpec)
        try { skipFully(skip, dataSpec) } catch (error: HttpDataSourceException) { closeResponse(); throw error }
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var wanted = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val left = bytesToRead - bytesRead
            if (left == 0L) return C.RESULT_END_OF_INPUT
            wanted = minOf(wanted.toLong(), left).toInt()
        }
        val count = try { checkNotNull(stream).read(buffer, offset, wanted) }
            catch (error: IOException) { throw HttpDataSourceException.createForIOException(error, checkNotNull(spec), HttpDataSourceException.TYPE_READ) }
        if (count == -1) return C.RESULT_END_OF_INPUT
        bytesRead += count
        bytesTransferred(count)
        return count
    }

    private fun skipFully(count: Long, dataSpec: DataSpec) {
        if (count == 0L) return
        val scratch = ByteArray(4096)
        var left = count
        while (left > 0) {
            val read = try { checkNotNull(stream).read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()) }
                catch (error: IOException) { throw HttpDataSourceException.createForIOException(error, dataSpec, HttpDataSourceException.TYPE_OPEN) }
            if (Thread.currentThread().isInterrupted) throw HttpDataSourceException(java.io.InterruptedIOException(), dataSpec,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSourceException.TYPE_OPEN)
            if (read == -1) throw HttpDataSourceException(dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSourceException.TYPE_OPEN)
            left -= read
            bytesTransferred(read)
        }
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let(Uri::parse) ?: spec?.uri
    override fun getResponseCode(): Int = response?.code ?: -1
    override fun getResponseHeaders(): Map<String, List<String>> = response?.headers?.toMultimap().orEmpty()
    override fun setRequestProperty(name: String, value: String) = properties.set(name, value)
    override fun clearRequestProperty(name: String) = properties.remove(name)
    override fun clearAllRequestProperties() = properties.clear()

    override fun close() {
        if (opened) { opened = false; transferEnded() }
        closeResponse()
        spec = null
    }

    private fun closeResponse() { response?.close(); response = null; stream = null }

    companion object {
        /** The same limits as Media3's default HTTP source. */
        fun client(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }
}
