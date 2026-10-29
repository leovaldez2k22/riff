package com.musicglass.personal

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteOrder

/** How much of a song's end and start Automix reads. */
internal const val ANALYSIS_TAIL_MS = 45_000L
internal const val ANALYSIS_HEAD_MS = 30_000L
/** The start of a cached stream that must be there: the container's header and index sit in it. */
internal const val CACHED_HEAD_BYTES = 256L * 1024

/** How many of a cached stream's last bytes hold its tail: the tail's share of the length, with a quarter more for a variable bitrate. */
internal fun cachedTailBytes(length: Long, durationMs: Long): Long =
    (length.toDouble() * minOf(1.0, (ANALYSIS_TAIL_MS * 1.25 + 2_000) / durationMs)).toLong().coerceIn(1, length)
/** How many of a cached stream's first bytes hold its head: as for the tail, at least the container's header. */
internal fun cachedHeadBytes(length: Long, durationMs: Long): Long =
    maxOf(CACHED_HEAD_BYTES, (length.toDouble() * minOf(1.0, (ANALYSIS_HEAD_MS * 1.25 + 2_000) / durationMs)).toLong()).coerceIn(1, length)
/** Kept back from the start of a cached run, for a variable bitrate and the extractor landing on the cluster before. */
internal const val CACHED_RUN_MARGIN_MS = 3_000L
/** The shortest cached tail worth reading: less, and the end is left to Path B. */
internal const val CACHED_TAIL_MIN_MS = 10_000L

/**
 * How much of a stream's end can be read from a cached run of bytes that starts at [runFrom] and reaches the end:
 * up to [ANALYSIS_TAIL_MS]. A seek into the last minute leaves the run shorter, and the tail with it.
 */
internal fun cachedTailMs(length: Long, runFrom: Long, durationMs: Long): Long =
    if (length <= 0 || durationMs <= 0) 0
    else minOf(ANALYSIS_TAIL_MS, (durationMs.toDouble() * (length - runFrom) / length).toLong() - CACHED_RUN_MARGIN_MS).coerceAtLeast(0)

/**
 * Automix stage 1: reads a stretch of a song the phone holds (a download, or a file on the device) as loudness. The
 * file is decoded on the caller's thread with the platform's decoder; nothing is sent anywhere. A stream is read only
 * from Riff's audio cache, once all of it is there (Feature 9, Path A for streams); nothing is fetched for it. Until
 * then, and for streams the cache does not keep, the blend falls back to the plain crossfade, with the bass swap.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal object TrackAnalysis {
    /** The file or content address a [item] plays from on this phone, or null when it streams. */
    fun localSource(context: Context, item: MediaItem): Uri? {
        if (item.mediaId.startsWith("local:")) return item.localConfiguration?.uri
        if (!item.mediaId.startsWith("yt:")) return null
        val store = OfflineMusicStore.get(context)
        return store.audio(store.currentScope, item.mediaId.removePrefix("yt:"))?.let(Uri::fromFile)
    }

    /**
     * A streamed [item]'s bytes in Riff's audio cache, when its start (the container's header) is there and an unbroken
     * run reaches its end, long enough for [CACHED_TAIL_MIN_MS] of tail. A seek ahead leaves a hole before that run,
     * which is never read. The cache learns a stream's length when its download reaches the end. Null for a song not
     * streamed or not cached that far.
     */
    fun cachedSource(context: Context, item: MediaItem, durationMs: Long): CachedTail? {
        val key = item.localConfiguration?.customCacheKey ?: return null
        val cache = MusicDiskCache.get(context)
        val state = cachedState(cache, key, durationMs)
        if (!(state.known && state.head && state.tail)) return null
        val tailFrom = maxOf(state.runFrom, state.length - cachedTailBytes(state.length, durationMs))
        return CachedTail(CachedBytes.copy(cache, key, state.length, minOf(state.length, CACHED_HEAD_BYTES), state.length - tailFrom), state.tailMs)
    }

    /**
     * A streamed [item]'s start in Riff's audio cache, for its head: when the cache knows its length (so it has played
     * to the end) and holds an unbroken run from the start long enough for [ANALYSIS_HEAD_MS]. The analysis is kept
     * (`AnalysisStore`), so the next time the song comes up next its start is known. Null otherwise; nothing is fetched.
     */
    fun cachedHead(context: Context, item: MediaItem, durationMs: Long): CachedTail? {
        val key = item.localConfiguration?.customCacheKey ?: return null
        val cache = MusicDiskCache.get(context)
        val state = cachedState(cache, key, durationMs)
        if (!(state.known && state.tail)) return null
        val headBytes = cachedHeadBytes(state.length, durationMs)
        if (!cache.isCached(key, 0, headBytes)) return null
        val tailFrom = maxOf(state.runFrom, state.length - cachedTailBytes(state.length, durationMs), headBytes)
        // The tail too, where a container keeps its index at the end.
        return CachedTail(CachedBytes.copy(cache, key, state.length, headBytes, state.length - tailFrom), ANALYSIS_HEAD_MS)
    }

    /** What the cache holds of a stream: whether it knows the length, and has the start and the tail. For the event log. */
    fun cachedState(context: Context, item: MediaItem, durationMs: Long): CachedState? =
        item.localConfiguration?.customCacheKey?.let { cachedState(MusicDiskCache.get(context), it, durationMs) }

    private fun cachedState(cache: Cache, key: String, durationMs: Long): CachedState {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || durationMs <= 0) return CachedState(length, false, cache.isCached(key, 0, CACHED_HEAD_BYTES), length, 0)
        // Where the unbroken run of cached bytes that reaches the end begins.
        var runFrom = length
        for (span in cache.getCachedSpans(key).descendingSet()) {
            if (span.position + span.length < runFrom) break
            runFrom = minOf(runFrom, span.position)
        }
        return CachedState(length, true, cache.isCached(key, 0, minOf(length, CACHED_HEAD_BYTES)), runFrom, cachedTailMs(length, runFrom, durationMs))
    }

    /** The envelope of [lengthMs] from [fromMs] (negative: that far before the end), or null when it cannot be read. */
    fun envelope(context: Context, source: Uri, fromMs: Long, lengthMs: Long): TrackEnvelope? =
        envelope({ PlatformDemuxer(MediaExtractor().also { it.setDataSource(context, source, null) }) }, fromMs, lengthMs, null)

    /**
     * A cached stream's tail. Read with Media3's extractor, which goes to the tail by the container's index; the
     * platform's reads a WebM file's clusters from the start, into the part that was never cached. [knownDurationMs]
     * stands in for a container that does not say its length.
     */
    fun envelope(context: Context, cached: CachedTail, knownDurationMs: Long?, head: Boolean = false): TrackEnvelope? = cached.source.use { bytes ->
        envelope({ Media3Demuxer(MediaExtractorCompat(context).also { it.setDataSource(bytes) }) }, if (head) 0 else -cached.tailMs, cached.tailMs, knownDurationMs)
    }

    private fun envelope(open: () -> Demuxer, fromMs: Long, lengthMs: Long, knownDurationMs: Long?): TrackEnvelope? {
        var extractor: Demuxer? = null
        var codec: MediaCodec? = null
        try {
            val extractor = open().also { extractor = it }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.format(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val format = extractor.format(track); extractor.select(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000 else knownDurationMs ?: return null
            val startMs = (if (fromMs < 0) durationMs + fromMs else fromMs).coerceIn(0, durationMs)
            val endMs = minOf(durationMs, startMs + lengthMs)
            extractor.seekTo(startMs * 1_000)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            var builder: EnvelopeBuilder? = null
            val info = MediaCodec.BufferInfo(); var inputDone = false; var samples = ShortArray(0); var turns = 0
            while (true) {
                if (++turns > 200_000) return null // A decoder that never ends its stream.
                if (!inputDone) decoder.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { index ->
                    val size = extractor.read(decoder.getInputBuffer(index)!!)
                    if (size < 0 || extractor.sampleTime > endMs * 1_000) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out < 0) continue
                val atMs = info.presentationTimeUs / 1_000
                if (info.size > 0 && atMs >= startMs) {
                    val output = decoder.outputFormat
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null
                    val b = builder ?: EnvelopeBuilder(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), output.getInteger(MediaFormat.KEY_CHANNEL_COUNT), atMs).also { builder = it }
                    val shorts = decoder.getOutputBuffer(out)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    if (samples.size < shorts.remaining()) samples = ShortArray(shorts.remaining())
                    val count = shorts.remaining(); shorts.get(samples, 0, count); b.add(samples, count)
                }
                decoder.releaseOutputBuffer(out, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            return builder?.build(durationMs)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            return null // A file the platform cannot decode is a song without a smart blend.
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor?.release() }
        }
    }
}

/** The few calls an envelope makes of an extractor: the platform's for a file, Media3's for a cached stream. */
private interface Demuxer {
    val trackCount: Int
    val sampleTime: Long
    fun format(track: Int): MediaFormat
    fun select(track: Int)
    /** To the sync sample at or before [us]. */
    fun seekTo(us: Long)
    fun read(buffer: java.nio.ByteBuffer): Int
    fun advance()
    fun release()
}

private class PlatformDemuxer(private val e: MediaExtractor) : Demuxer {
    override val trackCount get() = e.trackCount
    override val sampleTime get() = e.sampleTime
    override fun format(track: Int): MediaFormat = e.getTrackFormat(track)
    override fun select(track: Int) = e.selectTrack(track)
    override fun seekTo(us: Long) = e.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    override fun read(buffer: java.nio.ByteBuffer) = e.readSampleData(buffer, 0)
    override fun advance() { e.advance() }
    override fun release() = e.release()
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class Media3Demuxer(private val e: MediaExtractorCompat) : Demuxer {
    override val trackCount get() = e.trackCount
    override val sampleTime get() = e.sampleTime
    override fun format(track: Int): MediaFormat = e.getTrackFormat(track)
    override fun select(track: Int) = e.selectTrack(track)
    override fun seekTo(us: Long) = e.seekTo(us, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
    override fun read(buffer: java.nio.ByteBuffer) = e.readSampleData(buffer, 0)
    override fun advance() { e.advance() }
    override fun release() = e.release()
}

internal class CachedState(val length: Long, val known: Boolean, val head: Boolean, val runFrom: Long, val tailMs: Long) {
    val tail get() = tailMs >= CACHED_TAIL_MIN_MS
}

/** A cached stream ready to read, and how much of its end can be: up to [ANALYSIS_TAIL_MS]. */
internal class CachedTail(val source: MediaDataSource, val tailMs: Long)

/**
 * A cached stream's start and tail, copied into memory in two reads, as the platform extractor reads them. The
 * extractor reads in many small jumps; reading each from the cache took tens of seconds on the phone. A read in
 * the part not copied is an error, so the song is left to Path B; nothing is ever fetched.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class CachedBytes(private val length: Long, private val head: ByteArray, private val tailFrom: Long, private val tail: ByteArray) : MediaDataSource() {
    companion object {
        fun copy(cache: Cache, key: String, length: Long, headBytes: Long, tailBytes: Long): CachedBytes {
            fun read(from: Long, size: Long): ByteArray {
                val source = CacheDataSource(cache, null)
                val out = ByteArray(size.toInt())
                source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(from).setLength(size).build())
                try {
                    var done = 0
                    while (done < out.size) { val n = source.read(out, done, out.size - done); if (n < 0) break; done += n }
                    if (done < out.size) throw java.io.IOException("cache ended early")
                } finally { source.close() }
                return out
            }
            val tailFrom = maxOf(length - tailBytes, headBytes)
            return CachedBytes(length, read(0, headBytes), tailFrom, read(tailFrom, length - tailFrom))
        }
    }

    override fun getSize() = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        val (part, at) = when {
            position < head.size -> head to position.toInt()
            position >= tailFrom -> tail to (position - tailFrom).toInt()
            else -> throw java.io.IOException("not cached")
        }
        val n = minOf(size, part.size - at)
        System.arraycopy(part, at, buffer, offset, n)
        return n
    }

    override fun close() = Unit
}
