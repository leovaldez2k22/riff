package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.metrolist.innertubex.sabr.SabrVideoStream
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrResponseDiagnostics
import com.metrolist.innertubex.sabr.SabrFailureKind
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.innertubex.sabr.findSabrProtocolException
import io.ktor.client.HttpClient
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect

/** The UI and media service share a process; keep signed SABR details out of session bundles. */
internal object SabrPlaybackRegistry {
    private val pending = LinkedHashMap<String, SabrBootstrap>()

    @Synchronized fun register(bootstrap: SabrBootstrap): String {
        if (pending.size >= 4) pending.remove(pending.keys.first())
        return UUID.randomUUID().toString().also { pending[it] = bootstrap }
    }

    @Synchronized fun take(key: String): SabrBootstrap? = pending.remove(key)
}

/** Routes ordinary HTTPS media to Media3 and SABR media to the segmented stream adapter. */
@OptIn(ExperimentalSabrApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ProviderMediaDataSource(
    private val http: DataSource,
    private val sabrHttp: HttpClient,
    private val findBootstrap: (String) -> SabrBootstrap?,
    private val onSabrResponse: (SabrResponseDiagnostics) -> Unit,
) : DataSource {
    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        check(active == null) { "Data source is already open" }
        val source = if (dataSpec.uri.scheme in setOf("sabr","sabrvideo")) {
            SabrAudioDataSource(sabrHttp, findBootstrap, onSabrResponse,dataSpec.uri.scheme=="sabrvideo")
        } else http
        active = source
        return try { source.open(dataSpec) }
        catch (error: Exception) { active = null; source.close(); throw error }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "Data source is not open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders.orEmpty()

    override fun addTransferListener(transferListener: TransferListener) {
        http.addTransferListener(transferListener)
    }

    override fun close() {
        active?.close()
        active = null
    }
}

/** SABR failure without the library message (it can contain signed URLs), keeping the kind for recovery. */
internal class SabrPlaybackFailure(message: String, val kind: SabrFailureKind?) : IOException(message)

/** Presents ordered SABR audio chunks as a progressive byte stream for Media3. */
@OptIn(ExperimentalSabrApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class SabrAudioDataSource(
    private val http: HttpClient,
    private val findBootstrap: (String) -> SabrBootstrap?,
    private val onResponse: (SabrResponseDiagnostics) -> Unit,
    private val video:Boolean=false,
) : DataSource {
    private var scope: CoroutineScope? = null
    private var producer: Job? = null
    private var chunks: Channel<ByteArray>? = null
    private var currentChunk: ByteArray? = null
    private var chunkOffset = 0
    private var bytesRemaining = Long.MAX_VALUE
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        if (openedUri != null) throw IOException("SABR source is already open")
        val bootstrap = dataSpec.uri.host?.let(findBootstrap)
            ?: throw IOException("SABR playback session expired")
        if (dataSpec.position < 0 || dataSpec.length < C.LENGTH_UNSET.toLong())
            throw IOException("Invalid SABR byte range")
        val firstByte = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.length
        openedUri = dataSpec.uri
        val channel = Channel<ByteArray>(capacity = 4)
        chunks = channel
        val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = streamScope
        producer = streamScope.launch {
            try {
                var emittedBytes = 0L
                if (!video && SabrFaultInjection.consume())
                    throw SabrProtocolException("Injected attestation failure", SabrFailureKind.ATTESTATION_REQUIRED)
                val flow=if(video) SabrVideoStream(http,bootstrap,onResponse=onResponse).chunks() else SabrAudioStream(http,bootstrap,onResponse=onResponse).chunks()
                flow.collect { chunk ->
                    val chunkStart = emittedBytes
                    emittedBytes += chunk.data.size
                    if (emittedBytes > firstByte) {
                        val skip = (firstByte - chunkStart).coerceAtLeast(0).toInt()
                        channel.send(if (skip == 0) chunk.data else
                            chunk.data.copyOfRange(skip, chunk.data.size))
                    }
                }
                channel.close()
            } catch (error: CancellationException) {
                channel.cancel()
            } catch (error: Exception) {
                // Library errors can contain signed URLs; keep them out of Media3 logs.
                channel.close(SabrPlaybackFailure("SABR playback failed (${error::class.simpleName})",
                    error.findSabrProtocolException()?.kind))
            }
        }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (openedUri == null) throw IOException("SABR source is not open")
        if (offset < 0 || length < 0 || offset > buffer.size - length)
            throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        while (currentChunk == null || chunkOffset == currentChunk!!.size) {
            val result = runBlocking { checkNotNull(chunks).receiveCatching() }
            val data = result.getOrNull()
            if (data == null) {
                result.exceptionOrNull()?.let { throw it as? SabrPlaybackFailure ?: IOException("SABR playback failed") }
                return C.RESULT_END_OF_INPUT
            }
            currentChunk = data
            chunkOffset = 0
        }
        val data = checkNotNull(currentChunk)
        val count = minOf(length, data.size - chunkOffset, bytesRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        data.copyInto(buffer, offset, chunkOffset, chunkOffset + count)
        chunkOffset += count
        bytesRemaining -= count
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun close() {
        producer?.cancel()
        chunks?.cancel()
        scope?.cancel()
        producer = null
        chunks = null
        scope = null
        currentChunk = null
        chunkOffset = 0
        bytesRemaining = Long.MAX_VALUE
        openedUri = null
    }
}
