package com.musicglass.personal

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/*
 * Feature 9 (0.30.0, owner Q9): a downloaded song, whole or a part of it, as the phone's ringtone. The audio is copied
 * as it is (no re-encoding) into a file of its own in Ringtones, so the ringtone stays when the download is removed.
 * Android's "Modify system settings" access is asked only when the user taps Set; without it nothing changes.
 */

/** A part of a song, in milliseconds. A part is 5 to 60 seconds long. */
internal data class RingtoneCut(val startMs: Long, val endMs: Long) { val lengthMs get() = endMs - startMs }
internal const val RINGTONE_MIN_MS = 5_000L
internal const val RINGTONE_MAX_PART_MS = 60_000L

/** The part asked for, kept inside the song and 5 to 60 s long; null when the song is too short for one. */
internal fun ringtoneCut(durationMs: Long, startMs: Long, endMs: Long): RingtoneCut? {
    if (durationMs < RINGTONE_MIN_MS) return null
    val start = startMs.coerceIn(0, durationMs - RINGTONE_MIN_MS)
    val end = endMs.coerceIn(start + RINGTONE_MIN_MS, minOf(durationMs, start + RINGTONE_MAX_PART_MS))
    return RingtoneCut(start, end)
}

/** Where a part starts when the dialog opens: 30 s in for a long song (past most intros), for 30 s. */
internal fun ringtoneFirstCut(durationMs: Long): RingtoneCut? = ringtoneCut(durationMs, if (durationMs >= 90_000) 30_000 else 0, (if (durationMs >= 90_000) 60_000 else 30_000))

/** The file type of a download's audio: WebM for Opus, MPEG-4 for AAC. */
internal fun ringtoneMime(container: String?) = when (container) { "webm" -> "audio/webm"; "mp4" -> "audio/mp4"; else -> null }
internal fun ringtoneExtension(mime: String) = if (mime == "audio/webm") "webm" else "m4a"

/** A file name Android keeps as it is: no path separators or control characters, not too long. */
internal fun ringtoneFileName(title: String, artist: String, cut: RingtoneCut?, extension: String): String {
    val clean = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - ").map { if (it < ' ' || it in "/\\:*?\"<>|") ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(90)
    val part = cut?.let { " (${it.startMs / 1000}s)" }.orEmpty()
    return (clean.ifBlank { "Riff ringtone" } + part + "." + extension)
}

/**
 * Copies [cut] of [source] into [output] without decoding: the compressed samples from the sync point at or before
 * the start to the end, their times moved to start at zero. Null [cut] copies the whole file. The MIME type written.
 */
internal fun cutAudio(source: File, output: File, cut: RingtoneCut?): String {
    val mime = ringtoneMime(BundleValidator.container(source)) ?: throw IOException("Unknown audio")
    output.delete()
    if (cut == null) { source.copyTo(output, overwrite = true); return mime }
    val extractor = MediaExtractor(); var muxer: MediaMuxer? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IOException("No audio")
        val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
        val opus = format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
        val webm = if (opus) format.getByteBuffer("csd-0")?.let { head ->
            WebmAudioWriter(output, ByteArray(head.remaining()).also { head.duplicate().get(it) }, WebmAudioWriter.nanos(format.getByteBuffer("csd-1")),
                WebmAudioWriter.nanos(format.getByteBuffer("csd-2")), format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        } ?: throw IOException("No Opus header") else null
        try {
            if (webm == null) muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val target = muxer?.addTrack(format) ?: 0; muxer?.start()
            extractor.seekTo(cut.startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buffer = ByteBuffer.allocate(1024 * 1024); val info = MediaCodec.BufferInfo(); val copy = if (webm != null) ByteArray(buffer.capacity()) else null
            var base = -1L; var samples = 0
            while (true) {
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                val time = extractor.sampleTime
                if (time > cut.endMs * 1000) break
                if (base < 0) base = time
                if (webm != null) { buffer.position(0); buffer.get(copy!!, 0, size); webm.write(copy, size, time - base) }
                else { info.set(0, size, time - base, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer!!.writeSampleData(target, buffer, info) }
                samples++; extractor.advance()
            }
            if (samples == 0) throw IOException("Nothing in that part")
            muxer?.stop()
        } finally { webm?.close() }
        return mime
    } finally { extractor.release(); runCatching { muxer?.release() } }
}

/** Copies Riff made before, by song and part, so the same choice is not copied twice. */
private fun ringtoneCopies(context: Context) = context.getSharedPreferences("ringtone_copies", Context.MODE_PRIVATE)
private fun ringtoneCopyKey(scope: String, id: String, cut: RingtoneCut?) = "$scope/$id/" + (cut?.let { "${it.startMs}-${it.endMs}" } ?: "whole")

private fun stillThere(context: Context, uri: Uri) = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
}.getOrDefault(false)

/**
 * The song's copy in Ringtones, made now or found from before. The copy is written while Android keeps it hidden
 * (pending) and is removed if anything fails, so an unfinished file never appears in the ringtone list.
 */
internal suspend fun ringtoneCopy(context: Context, scope: String, track: ProviderTrack, cut: RingtoneCut?): Uri = withContext(Dispatchers.IO) {
    val key = ringtoneCopyKey(scope, track.videoId, cut)
    ringtoneCopies(context).getString(key, null)?.let(Uri::parse)?.takeIf { stillThere(context, it) }?.let { return@withContext it }
    val source = OfflineMusicStore.get(context).audio(scope, track.videoId) ?: throw IOException("Not downloaded")
    val staged = File(context.cacheDir, "ringtone.part")
    try {
        val mime = cutAudio(source, staged, cut)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ringtoneFileName(track.title, track.artist, cut, ringtoneExtension(mime)))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES + "/Riff")
            put(MediaStore.Audio.AudioColumns.IS_RINGTONE, 1); put(MediaStore.Audio.AudioColumns.IS_MUSIC, 0)
            put(MediaStore.MediaColumns.TITLE, track.title.take(200)); put(MediaStore.Audio.AudioColumns.ARTIST, track.artist.take(200))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw IOException("No room in Ringtones")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> staged.inputStream().use { it.copyTo(out) } } ?: throw IOException("Ringtones not writable")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { runCatching { resolver.delete(uri, null, null) }; throw e }
        ringtoneCopies(context).edit().putString(key, uri.toString()).apply()
        uri
    } finally { staged.delete() }
}

/** Whether Riff may change the ringtone: Android's "Modify system settings" access. */
internal fun canSetRingtone(context: Context) = Settings.System.canWrite(context)

/** Android's page where the user allows "Modify system settings" for Riff. */
internal fun ringtoneAccessIntent(context: Context) =
    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Makes [uri] the ringtone. On any failure the ringtone the phone had is put back; false then. */
internal fun applyRingtone(context: Context, uri: Uri): Boolean {
    if (!canSetRingtone(context)) return false
    val prior = runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) }.getOrNull()
    return try {
        RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, uri); true
    } catch (e: Exception) {
        runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE, prior) }; false
    }
}

/** The dialog: the whole song or a part, a preview of the part, then Set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RingtoneDialog(track: ProviderTrack, scopeKey: String, durationMs: Long, onDismiss: () -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val first = remember(durationMs) { ringtoneFirstCut(durationMs) }
    var whole by remember { mutableStateOf(first == null) }
    var cut by remember { mutableStateOf(first) }
    var busy by remember { mutableStateOf(false) }
    var waitingForAccess by remember { mutableStateOf(false) }
    val preview = remember { mutableStateOf<MediaPlayer?>(null) }
    fun stopPreview() { preview.value?.let { runCatching { it.stop() }; it.release() }; preview.value = null }
    DisposableEffect(Unit) { onDispose { stopPreview() } }

    fun set() {
        if (!canSetRingtone(context)) { waitingForAccess = true; return }
        busy = true; stopPreview()
        scope.launch {
            val result = try { ringtoneCopy(context, scopeKey, track, if (whole) null else cut).let { applyRingtone(context, it) } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            busy = false
            when (result) {
                true -> { uiHaptic(HapticCue.Confirm); onNotice(string(R.string.ringtone_set, track.title)); onDismiss() }
                false -> { uiHaptic(HapticCue.Reject); onNotice(string(R.string.ringtone_not_set)) }
                null -> { uiHaptic(HapticCue.Reject); onNotice(string(R.string.ringtone_not_copied)) }
            }
        }
    }
    // Back from Android's settings page: with access now, carry on; without, the dialog still says why.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForAccess && canSetRingtone(context)) { waitingForAccess = false; set() }
        }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }

    FrostedAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(string(R.string.ringtone_title)) },
        text = { Column(Modifier.testTag("ringtone_dialog")) {
            if (waitingForAccess) {
                Text(string(R.string.ringtone_access_needed))
                TextButton({ runCatching { context.startActivity(ringtoneAccessIntent(context)) } }, modifier = Modifier.testTag("ringtone_open_access")) {
                    Text(string(R.string.ringtone_open_access)) }
                return@Column
            }
            if (first != null) GlassSegmented(listOf(string(R.string.ringtone_whole), string(R.string.ringtone_part)), if (whole) 0 else 1,
                { whole = it == 0; stopPreview() }, "ringtone_choice")
            if (!whole) cut?.let { c ->
                Text(string(R.string.ringtone_part_range, clock(c.startMs), clock(c.endMs), c.lengthMs / 1000), color = Muted,
                    modifier = Modifier.padding(top = RiffSpace.item).testTag("ringtone_range"))
                RangeSlider(c.startMs.toFloat()..c.endMs.toFloat(), { range ->
                    // The end follows the start when the part would be too long; the start follows the end when too short.
                    val start = range.start.toLong(); val end = range.endInclusive.toLong()
                    cut = if (start != c.startMs) ringtoneCut(durationMs, start, end.coerceIn(start + RINGTONE_MIN_MS, start + RINGTONE_MAX_PART_MS))
                        else ringtoneCut(durationMs, start.coerceIn(end - RINGTONE_MAX_PART_MS, end - RINGTONE_MIN_MS), end)
                }, valueRange = 0f..durationMs.toFloat(), onValueChangeFinished = { stopPreview() }, modifier = Modifier.testTag("ringtone_slider"))
                Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                    TextButton(enabled = !busy, modifier = Modifier.testTag("ringtone_preview"), onClick = {
                        if (preview.value != null) { stopPreview(); return@TextButton }
                        val file = OfflineMusicStore.get(context).audio(scopeKey, track.videoId) ?: return@TextButton
                        preview.value = runCatching { MediaPlayer().apply {
                            setDataSource(file.absolutePath); prepare(); seekTo(c.startMs.toInt()); start() } }.getOrNull()
                        val playing = preview.value
                        scope.launch { kotlinx.coroutines.delay(c.lengthMs); if (preview.value === playing) stopPreview() }
                    }) { Text(string(if (preview.value != null) R.string.ringtone_stop_preview else R.string.ringtone_preview)) }
                }
            }
            Text(string(R.string.ringtone_kept_note), color = Muted, modifier = Modifier.fillMaxWidth().padding(top = RiffSpace.item))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = RiffSpace.tight))
        } },
        confirmButton = { TextButton(enabled = !busy && !waitingForAccess, modifier = Modifier.testTag("ringtone_set"), onClick = { uiHaptic(); set() }) {
            Text(string(R.string.ringtone_set_button)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(string(R.string.cancel)) } })
}

private fun clock(ms: Long) = "${ms / 60000}:${((ms / 1000) % 60).toString().padStart(2, '0')}"
