package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Most a single request asks for. A stream host paces a long read to about playback speed; a bounded one is not paced. */
internal const val STREAM_RANGE_BYTES = 1024L * 1024
/** The clients a stream host refuses past this size. */
internal const val NARROW_STREAM_RANGE_BYTES = 512L * 1024
/** Enough to ride out a truncated range, not enough to hang on a host that sends nothing. */
private const val MAX_EMPTY_RANGES = 3

/** The largest range to ask for at [uri]. Other hosts are not paced, so they are not split. */
internal fun streamRangeBytes(uri: Uri): Long {
    val host = uri.host.orEmpty()
    // The client behind a stand-in address is not known until its lookup, so assume the narrowest.
    if (host == CACHED_STREAM_HOST) return NARROW_STREAM_RANGE_BYTES
    if (host != "googlevideo.com" && !host.endsWith(".googlevideo.com")) return Long.MAX_VALUE
    val client = uri.getQueryParameter("c")?.uppercase().orEmpty()
    return if (client == "ANDROID_VR" || client.startsWith("TVHTML5_SIMPLY")) NARROW_STREAM_RANGE_BYTES else STREAM_RANGE_BYTES
}

/**
 * Reads a stream as a run of bounded ranges instead of one open-ended request. Everything above it sees one continuous
 * read of the length it asked for. It sits above the address resolver, so a range opened after a long pause gets a fresh
 * address, and below the disk cache, so a cached span makes no request.
 *
 * The file's size has to be known to say where the last range ends: the address's `clen`, else [knownLength]. A stream
 * with neither is read as one request.
 *
 * The design is adapted from BitChord's ChunkedDataSource (GPL-3.0, kushagrasinghx/BitChord at b690dc1); see NOTICE.md.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ChunkedStreamDataSource(private val upstream: DataSource, private val knownLength: Long? = null,
    private val rangeBytes: (Uri) -> Long = ::streamRangeBytes) : DataSource {
    private var spec: DataSpec? = null
    private var position = 0L
    private var left = 0L
    private var rangeLeft = 0L
    private var range = STREAM_RANGE_BYTES
    private var upstreamOpen = false
    private var whole = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 } ?: knownLength?.takeIf { it > 0 }
        whole = total == null
        if (total == null) {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total else minOf(total, position + dataSpec.length)
        left = (end - position).coerceAtLeast(0)
        range = rangeBytes(dataSpec.uri)
        rangeLeft = 0
        if (left > 0) openRange()
        return left
    }

    private fun openRange() {
        val length = minOf(range, left)
        // Marked first: Media3 closes a source whose open failed, and the upstream must see that close.
        upstreamOpen = true
        upstream.open(checkNotNull(spec).buildUpon().setPosition(position).setLength(length).build())
        rangeLeft = length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (whole) return upstream.read(buffer, offset, length)
        if (left == 0L) return C.RESULT_END_OF_INPUT
        // A range that ended early is opened again for what did not arrive; the next range opens the same way.
        repeat(MAX_EMPTY_RANGES) {
            if (rangeLeft == 0L) { closeUpstream(); openRange() }
            val count = upstream.read(buffer, offset, minOf(length.toLong(), rangeLeft).toInt())
            if (count != C.RESULT_END_OF_INPUT) {
                position += count; left -= count; rangeLeft -= count
                return count
            }
            rangeLeft = 0
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null; left = 0; rangeLeft = 0
        closeUpstream()
    }

    private fun closeUpstream() {
        if (upstreamOpen) { upstreamOpen = false; upstream.close() }
    }
}
