package com.musicglass.personal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val UNDATED_STREAM_MS = 20 * 60_000L

/**
 * When an address stops working: the earlier of what it says of itself and what the provider said. An address that says
 * nothing is kept for [UNDATED_STREAM_MS] only. A refused address recovers quickly (`StreamLoadErrorPolicy`), so there is
 * no reason to replace one early, and replacing it is what makes a pause followed by a seek wait for a lookup.
 */
internal fun streamUrlExpiry(url: String, declared: Long, now: Long): Long {
    val seconds = url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()
    val signed = seconds?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000) ?: Long.MAX_VALUE
    val known = minOf(signed, declared.takeIf { it > 0 } ?: Long.MAX_VALUE)
    return if (known == Long.MAX_VALUE) now + UNDATED_STREAM_MS else known
}

/** One progressive audio file at a signed address: not SABR, HLS, live or video, which have their own sources and retries. */
internal fun isDirectAudio(stream: ProviderStream?, scheme: String?, live: Boolean): Boolean =
    stream != null && scheme == "https" && !live && stream.sabrBootstrap == null && !stream.includesVideo &&
        stream.videoUrl == null && stream.mimeType?.contains("mpegurl", true) != true

/** A new encoding must restart extraction, never continue an old byte offset in a new representation. */
internal class StreamRepresentationChanged : IOException("Stream representation changed; prepare a fresh source")

internal fun sameStreamRepresentation(a: ProviderStream, b: ProviderStream): Boolean =
    a.itag > 0 && a.itag == b.itag && a.mimeType == b.mimeType && a.bitrate == b.bitrate &&
        a.contentLength == b.contentLength && a.sourceVideoId == b.sourceVideoId &&
        b.sabrBootstrap == null && b.videoUrl == null && !b.includesVideo && !b.boundedRange

/**
 * Below the byte cache: cached spans need no extraction. The resolver runs only on Media3's loader
 * thread. An item pins its encoding; refreshing its address cannot change quality mid-stream.
 * HTTP rejection recovery is owned by the service, not a second retry loop inside this source.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun streamUrlFactory(upstream: DataSource.Factory, initial: ProviderStream, now: () -> Long,
    resolve: suspend () -> ProviderStream): DataSource.Factory {
    val lock = Any()
    var current = initial.copy(expiresAtMs = streamUrlExpiry(initial.url, initial.expiresAtMs, now()))
    return ResolvingDataSource.Factory(upstream) { spec ->
        val stream = synchronized(lock) {
            if (!reusableStream(current.expiresAtMs, now(), false)) {
                val fresh = runBlocking(Dispatchers.IO) { withinLookupDeadline(30_000) { Result.success(resolve()) }.getOrThrow() }
                if (!sameStreamRepresentation(initial, fresh)) throw StreamRepresentationChanged()
                val expiry = streamUrlExpiry(fresh.url, fresh.expiresAtMs, now())
                if (!reusableStream(expiry, now(), false)) throw IOException("Provider returned an expired stream")
                current = fresh.copy(expiresAtMs = expiry)
            }
            current
        }
        spec.withUri(Uri.parse(stream.url)).buildUpon()
            .setHttpRequestHeaders(spec.httpRequestHeaders + stream.headers.filterKeys { it.lowercase() in STREAM_HEADERS })
            .build()
    }
}

/** Do not spend Media3's loader retry budget replaying an address the service must replace. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (generateSequence(info.exception as Throwable?) { it.cause }.any {
                it is StreamRepresentationChanged || it is HttpDataSource.InvalidResponseCodeException &&
                    it.responseCode in setOf(403, 404, 410, 416)
            }) C.TIME_UNSET else super.getRetryDelayMsFor(info)
}
