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
import java.nio.ByteOrder

/** How much of a song's end and start Automix reads. */
internal const val ANALYSIS_TAIL_MS = 45_000L
internal const val ANALYSIS_HEAD_MS = 30_000L

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
     * A streamed [item]'s bytes in Riff's audio cache, when every one of them is there: the cache learns a stream's
     * length when its download reaches the end. Null for a song not streamed, not cached, or not cached to its end.
     */
    fun cachedSource(context: Context, item: MediaItem): MediaDataSource? {
        val key = item.localConfiguration?.customCacheKey ?: return null
        val cache = MusicDiskCache.get(context)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length <= 0 || !cache.isCached(key, 0, length)) return null
        return CachedBytes(cache, key, length)
    }

    /** The envelope of [lengthMs] from [fromMs] (negative: that far before the end), or null when it cannot be read. */
    fun envelope(context: Context, source: Uri, fromMs: Long, lengthMs: Long): TrackEnvelope? =
        envelope({ it.setDataSource(context, source, null) }, fromMs, lengthMs, null)

    /**
     * The same from bytes [open] gives the extractor. [knownDurationMs] stands in for a container that does not say
     * its length (a stream's fragments often do not).
     */
    fun envelope(open: (MediaExtractor) -> Unit, fromMs: Long, lengthMs: Long, knownDurationMs: Long?): TrackEnvelope? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000 else knownDurationMs ?: return null
            val startMs = (if (fromMs < 0) durationMs + fromMs else fromMs).coerceIn(0, durationMs)
            val endMs = minOf(durationMs, startMs + lengthMs)
            extractor.seekTo(startMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            var builder: EnvelopeBuilder? = null
            val info = MediaCodec.BufferInfo(); var inputDone = false; var samples = ShortArray(0); var turns = 0
            while (true) {
                if (++turns > 200_000) return null // A decoder that never ends its stream.
                if (!inputDone) decoder.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { index ->
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
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
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }
}

/** A whole cached stream as the platform extractor reads it: cache only, so a missing byte is an error, never a fetch. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class CachedBytes(cache: Cache, private val key: String, private val length: Long) : MediaDataSource() {
    private val source = CacheDataSource(cache, null)
    /** Where the open read is; a read anywhere else opens again there. */
    private var at = -1L

    override fun getSize() = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        if (position != at) {
            if (at >= 0) source.close()
            source.open(DataSpec.Builder().setUri(Uri.EMPTY).setKey(key).setPosition(position).build())
            at = position
        }
        var read = 0
        while (read < size) {
            val n = source.read(buffer, offset + read, size - read)
            if (n < 0) break
            read += n
        }
        at += read
        return if (read == 0) -1 else read
    }

    override fun close() { if (at >= 0) source.close(); at = -1 }
}
