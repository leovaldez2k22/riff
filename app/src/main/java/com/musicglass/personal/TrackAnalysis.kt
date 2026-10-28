package com.musicglass.personal

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import java.nio.ByteOrder

/** How much of a song's end and start Automix reads. */
internal const val ANALYSIS_TAIL_MS = 45_000L
internal const val ANALYSIS_HEAD_MS = 30_000L

/**
 * Automix stage 1: reads a stretch of a song the phone holds (a download, or a file on the device) as loudness. The
 * file is decoded on the caller's thread with the platform's decoder; nothing is sent anywhere. Streams that are not
 * downloaded are not read: their blend falls back to the plain crossfade, with the bass swap.
 */
internal object TrackAnalysis {
    /** The file or content address a [item] plays from on this phone, or null when it streams. */
    fun localSource(context: Context, item: MediaItem): Uri? {
        if (item.mediaId.startsWith("local:")) return item.localConfiguration?.uri
        if (!item.mediaId.startsWith("yt:")) return null
        val store = OfflineMusicStore.get(context)
        return store.audio(store.currentScope, item.mediaId.removePrefix("yt:"))?.let(Uri::fromFile)
    }

    /** The envelope of [lengthMs] from [fromMs] (negative: that far before the end), or null when it cannot be read. */
    fun envelope(context: Context, source: Uri, fromMs: Long, lengthMs: Long): TrackEnvelope? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, source, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
            val durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1_000 else return null
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
