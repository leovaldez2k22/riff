package com.musicglass.personal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs

/*
 * Feature 15 (Riff+, owner Q15): a download saved as an M4A file where the user chooses. AAC is copied as it is;
 * Opus or Vorbis is converted to AAC with the phone's own encoder, after a warning that converting loses some
 * quality. Title, artist, album, the cover and (if asked) plain lyrics are written as tags, and the file is checked
 * with Android's own reader before it is called done. The download itself is never changed.
 */

/** AAC at this rate keeps converted Opus close to how it sounded; the phone's encoder may round it. */
internal const val M4A_CONVERT_BITRATE = 256_000
/** One export at a time. */
private val exportGate = Mutex()

/** Whether a download's audio must be converted: true for Opus or Vorbis, false for AAC, null when it is neither. */
internal fun m4aNeedsConversion(audio: File): Boolean? {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(audio.absolutePath)
        val mime = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }.firstOrNull { it.startsWith("audio/") }
        when (mime) { MediaFormat.MIMETYPE_AUDIO_AAC -> false; MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS -> true; else -> null }
    } catch (_: IOException) { null } finally { extractor.release() }
}

private suspend fun alive() = currentCoroutineContext().ensureActive()

/** AAC copied into a fresh MPEG-4 file, sample for sample. */
private suspend fun remuxAac(source: File, output: File, progress: (Float) -> Unit) {
    val extractor = MediaExtractor(); var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
        val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val target = muxer.addTrack(format); muxer.start()
        val buffer = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo()
        while (true) {
            alive(); buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
            info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(target, buffer, info)
            if (durationUs > 0) progress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
            extractor.advance()
        }
        muxer.stop()
    } finally { extractor.release(); runCatching { muxer?.release() } }
}

/** Opus or Vorbis decoded and encoded again as AAC-LC with the phone's codecs. */
private suspend fun convertToAac(source: File, output: File, durationMs: Long, progress: (Float) -> Unit) {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null; var encoder: MediaCodec? = null; var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val input = extractor.getTrackFormat(track); extractor.selectTrack(track)
        decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!).apply { configure(input, null, null, 0); start() }
        var rate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        val pcm = ArrayDeque<ByteArray>(); var pcmAt = 0
        var inputDone = false; var decoded = false; var eosQueued = false; var done = false
        var frames = 0L; var target = -1
        fun startEncoder() {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, M4A_CONVERT_BITRATE); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        }
        while (!done) {
            alive()
            if (!inputDone) decoder.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                if (size < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                else { decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
            }
            if (!decoded && pcm.size < 8) {
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = decoder.outputFormat
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
                        throw IOException("Unsupported decoded format")
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) { val b = decoder.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size)
                        pcm.addLast(ByteArray(info.size).also { b.get(it) }) }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decoded = true
                }
            }
            if (encoder == null && (pcm.isNotEmpty() || decoded)) startEncoder()
            val enc = encoder ?: continue
            if (!eosQueued) enc.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                val b = enc.getInputBuffer(i)!!; b.clear(); var filled = 0
                while (pcm.isNotEmpty() && b.remaining() > 0) {
                    val chunk = pcm.first(); val n = minOf(b.remaining(), chunk.size - pcmAt)
                    b.put(chunk, pcmAt, n); pcmAt += n; filled += n
                    if (pcmAt == chunk.size) { pcm.removeFirst(); pcmAt = 0 }
                }
                val pts = frames * 1_000_000L / rate
                frames += filled / (2 * channels)
                if (filled == 0 && decoded && pcm.isEmpty()) { enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosQueued = true }
                else enc.queueInputBuffer(i, 0, filled, pts, 0)
                if (durationMs > 0) progress((pts / 1000f / durationMs).coerceIn(0f, 1f))
            }
            val o = enc.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m -> target = m.addTrack(enc.outputFormat); m.start() }
            } else if (o >= 0) {
                val m = muxer
                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && m != null) {
                    val b = enc.getOutputBuffer(o)!!; b.position(info.offset); b.limit(info.offset + info.size); m.writeSampleData(target, b, info) }
                enc.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
            }
        }
        muxer?.stop() ?: throw IOException("Nothing encoded")
    } finally {
        extractor.release()
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        runCatching { muxer?.release() }
    }
}

/** The cover as JPEG or PNG, at most 1200 px across; other formats are drawn again as JPEG. */
internal fun m4aCover(file: File?): ByteArray? = runCatching {
    val bytes = file?.takeIf { it.isFile && it.length() in 8..(16L * 1024 * 1024) }?.readBytes() ?: return null
    val jpeg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(); val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    if ((jpeg || png) && bytes.size <= Mp4Tags.MAX_COVER && maxOf(bounds.outWidth, bounds.outHeight) <= 1200) return bytes
    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1200) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(1f, 1200f / maxOf(bitmap.width, bitmap.height))
    val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
    ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
}.getOrNull()

/** Android's own reader agrees: AAC that decodes, about as long as the song, with the tags that were written. */
internal fun m4aVerified(file: File, tag: Mp4Tag, durationMs: Long): Boolean = runCatching {
    val reader = MediaMetadataRetriever()
    try {
        reader.setDataSource(file.absolutePath)
        val length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return false
        if (durationMs > 0 && abs(length - durationMs) > maxOf(3_000, durationMs / 20)) return false
        if (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) != tag.title.take(Mp4Tags.MAX_TEXT)) return false
        if (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) != tag.artist.take(Mp4Tags.MAX_TEXT)) return false
        if (tag.cover != null && reader.embeddedPicture == null) return false
    } finally { reader.release() }
    // A second of audio, decoded.
    val extractor = MediaExtractor(); var decoder: MediaCodec? = null
    try {
        extractor.setDataSource(file.absolutePath)
        val format = extractor.getTrackFormat(0)
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return false
        extractor.selectTrack(0)
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(format, null, null, 0); start() }
        val info = MediaCodec.BufferInfo(); var heard = 0L; var tries = 0
        while (heard < 1_000_000 && tries++ < 400) {
            decoder.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                if (size < 0) decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                else { decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() } }
            val o = decoder.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) { if (info.size > 0) heard = maxOf(heard, info.presentationTimeUs); decoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break }
        }
        heard > 0
    } finally { extractor.release(); runCatching { decoder?.stop() }; runCatching { decoder?.release() } }
}.getOrDefault(false)

/** The tags of a download: its own title, artist and album, cover, and plain lyrics when asked for. */
internal fun m4aTag(context: Context, scope: String, track: ProviderTrack, lyrics: Boolean): Mp4Tag {
    val store = OfflineMusicStore.get(context)
    val words = if (lyrics) store.lyrics(scope, track.videoId)?.takeUnless { it.instrumental }?.lines?.joinToString("\n") { it.text }.orEmpty() else ""
    return Mp4Tag(track.title.ifBlank { "Untitled" }, track.artist.ifBlank { "Unknown artist" }, track.album, words, m4aCover(store.artwork(scope, track.videoId)))
}

/** Builds the file in Riff's own cache: audio, then tags, then the check. Returns the finished file. */
internal suspend fun stageM4a(context: Context, scope: String, track: ProviderTrack, durationMs: Long, lyrics: Boolean, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
    val source = OfflineMusicStore.get(context).audio(scope, track.videoId) ?: throw IOException("Not downloaded")
    val dir = File(context.cacheDir, "m4a_export").apply { deleteRecursively(); mkdirs() }
    val audio = File(dir, "audio.part"); val tagged = File(dir, "tagged.m4a")
    try {
        when (m4aNeedsConversion(source)) {
            false -> remuxAac(source, audio) { progress(it * .9f) }
            true -> convertToAac(source, audio, durationMs) { progress(it * .9f) }
            null -> throw IOException("Unsupported audio")
        }
        alive()
        val tag = m4aTag(context, scope, track, lyrics)
        Mp4Tags.write(audio, tagged, tag)
        if (!m4aVerified(tagged, tag, durationMs)) throw IOException("Check failed")
        progress(.95f)
        tagged
    } catch (e: Throwable) { tagged.delete(); throw e } finally { audio.delete() }
}

/** The name the picker suggests: the song and artist, without characters a file name cannot have. */
internal fun m4aFileName(track: ProviderTrack) = "${track.title} - ${track.artist}".map { if (it < ' ' || it in "/\\:*?\"<>|") ' ' else it }
    .joinToString("").replace(Regex("\\s+"), " ").trim().take(100).ifBlank { "Riff export" } + ".m4a"

private sealed interface ExportState {
    data object Asking : ExportState
    data class Working(val progress: Float) : ExportState
}

/** The menu's "Export as M4A": the dialog, the picker and the export, one at a time. */
@Composable internal fun rememberM4aExport(track: ProviderTrack, scope: String, durationMs: Long, onNotice: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val work = rememberCoroutineScope()
    var state by remember { mutableStateOf<ExportState?>(null) }
    var convert by remember { mutableStateOf<Boolean?>(null) }
    var lyrics by remember { mutableStateOf(false) }
    var hasLyrics by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri: Uri? ->
        if (uri == null) { state = null; return@rememberLauncherForActivityResult }
        if (!exportGate.tryLock()) { state = null; onNotice(context.getString(R.string.m4a_busy)); return@rememberLauncherForActivityResult }
        state = ExportState.Working(0f); progress = 0f
        // Atomic: the gate taken above is given back even if the screen goes before the export starts.
        job = work.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            var staged: File? = null
            val outcome = try {
                staged = stageM4a(context, scope, track, durationMs, lyrics) { progress = it }
                withContext(Dispatchers.IO) {
                    val total = staged.length().coerceAtLeast(1); var copied = 0L
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> staged.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) { alive(); val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n); copied += n; progress = .95f + .05f * copied / total }
                    } } ?: throw IOException("Not writable")
                }
                null
            } catch (e: Throwable) {
                // The chosen document is unfinished: removed if the provider lets Riff, else the user is told.
                val cleaned = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false) }
                if (e is CancellationException) (if (cleaned) R.string.m4a_cancelled else R.string.m4a_cancelled_left)
                else (if (cleaned) R.string.m4a_failed else R.string.m4a_failed_left)
            } finally { staged?.delete(); exportGate.unlock() }
            job = null; state = null
            if (outcome == null) { uiHaptic(HapticCue.Confirm); onNotice(context.getString(R.string.m4a_done, track.title)) }
            else { uiHaptic(HapticCue.Reject); onNotice(context.getString(outcome)) }
        }
    }
    when (state) {
        null -> Unit
        ExportState.Asking -> FrostedAlertDialog(onDismissRequest = { state = null }, title = { Text(context.getString(R.string.m4a_title)) },
            text = { Column(Modifier.testTag("m4a_dialog")) {
                Text(context.getString(when (convert) { true -> R.string.m4a_convert_warning; false -> R.string.m4a_copy_note; null -> R.string.m4a_unsupported }),
                    modifier = Modifier.testTag("m4a_note"))
                if (hasLyrics) SettingsToggleRow(context.getString(R.string.m4a_lyrics), lyrics, { lyrics = it }, Icons.Rounded.Lyrics, "m4a_lyrics")
            } },
            confirmButton = { TextButton(enabled = convert != null, modifier = Modifier.testTag("m4a_choose"), onClick = {
                uiHaptic(); runCatching { pick.launch(m4aFileName(track)) }.onFailure { state = null; onNotice(context.getString(R.string.m4a_no_picker)) }
            }) { Text(context.getString(if (convert == true) R.string.m4a_convert_choose else R.string.m4a_choose)) } },
            dismissButton = { TextButton({ state = null }) { Text(context.getString(R.string.cancel)) } })
        is ExportState.Working -> FrostedAlertDialog(onDismissRequest = {}, title = { Text(context.getString(R.string.m4a_title)) },
            text = { Column(Modifier.testTag("m4a_working")) {
                Text(context.getString(if (convert == true) R.string.m4a_converting else R.string.m4a_copying))
                LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = RiffSpace.item))
            } },
            confirmButton = { TextButton({ job?.cancel() }, modifier = Modifier.testTag("m4a_cancel")) { Text(context.getString(R.string.cancel)) } })
    }
    return {
        state = ExportState.Asking; convert = null; lyrics = false
        work.launch {
            val (needs, words) = withContext(Dispatchers.IO) {
                val store = OfflineMusicStore.get(context)
                store.audio(scope, track.videoId)?.let(::m4aNeedsConversion) to (store.lyrics(scope, track.videoId)?.let { !it.instrumental && it.lines.isNotEmpty() } == true)
            }
            convert = needs; hasLyrics = words
        }
    }
}
