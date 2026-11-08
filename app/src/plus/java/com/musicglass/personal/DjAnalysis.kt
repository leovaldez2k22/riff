package com.musicglass.personal

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext

/*
 * Feature 18 (Riff+, owner Q18): tempo and key for the DJ order. Only audio already on the phone is read: a download or
 * a file on the device, never a stream fetched for it (no hidden mass streaming). A streamed song can still bring the
 * tempo Automix measured from its cached end. Analysis runs only when the listener asks for it, one song at a time at
 * background priority, and stops under battery saver or once the phone reports it is warm.
 */

/** How much of a song is read for its key and tempo, and from where: 30 s from 40 % of the way in. */
internal const val DJ_WINDOW_MS = 30_000L
internal const val DJ_WINDOW_AT = .4
/** The most songs kept; past it the ones analysed longest ago go. About 150 KB. */
internal const val DJ_KEEP = 3_000
/** Raise when the analysis changes: older lines are read as not analysed. */
internal const val DJ_ANALYSIS_VERSION = 1

/** One song's analysis: its tempo and key when found (with how sure), when it was made and how long it took. */
internal data class DjRecord(val bpm: Double?, val bpmConfidence: Float, val key: MusicalKey?, val keyCorrelation: Double,
                             val keyLead: Double, val atMs: Long, val tookMs: Long) {
    val facts: DjFacts get() = DjFacts(bpm, key)
}

/**
 * The analyses, in one small text file in Riff's own files (never backed up, exported or sent). Each line is a song's
 * media id and its figures; a line from another version, or one that cannot be read, counts as not analysed.
 */
internal class DjAnalysisStore(private val file: File, private val keep: Int = DJ_KEEP) {
    companion object {
        @Volatile private var instance: DjAnalysisStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: DjAnalysisStore(File(context.applicationContext.noBackupFilesDir, "dj-analysis/analysis.tsv")).also { instance = it }
        }
    }

    private var records: MutableMap<String, DjRecord>? = null

    @Synchronized private fun loaded(): MutableMap<String, DjRecord> = records ?: LinkedHashMap<String, DjRecord>().also { map ->
        try { file.forEachLine { line -> parse(line)?.let { (id, record) -> map[id] = record } } } catch (_: IOException) { }
        records = map
    }

    @Synchronized fun read(mediaId: String): DjRecord? = loaded()[mediaId]

    @Synchronized fun write(mediaId: String, record: DjRecord) {
        val map = loaded()
        map.remove(mediaId); map[mediaId] = record
        if (map.size > keep) map.entries.sortedBy { it.value.atMs }.take(map.size - keep).forEach { map.remove(it.key) }
        try {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(map.entries.joinToString("") { (id, r) -> line(id, r) + "\n" })
            if (!part.renameTo(file)) { file.delete(); if (!part.renameTo(file)) part.delete() }
        } catch (_: IOException) { } // Not kept: analysed again next time.
    }

    @Synchronized fun clear() { records = LinkedHashMap(); file.delete() }

    private fun line(id: String, r: DjRecord) = listOf(DJ_ANALYSIS_VERSION.toString(), id.replace('\t', ' '), r.bpm?.let { "%.2f".format(Locale.ROOT, it) }.orEmpty(),
        "%.3f".format(Locale.ROOT, r.bpmConfidence), r.key?.tonic?.toString().orEmpty(), r.key?.let { if (it.minor) "m" else "M" }.orEmpty(),
        "%.4f".format(Locale.ROOT, r.keyCorrelation), "%.4f".format(Locale.ROOT, r.keyLead), r.atMs.toString(), r.tookMs.toString()).joinToString("\t")

    private fun parse(line: String): Pair<String, DjRecord>? {
        val f = line.split('\t')
        if (f.size != 10 || f[0] != DJ_ANALYSIS_VERSION.toString() || f[1].isEmpty()) return null
        val tonic = f[4].toIntOrNull()
        val key = if (tonic != null && tonic in 0..11 && f[5] in setOf("m", "M")) MusicalKey(tonic, f[5] == "m") else null
        return f[1] to DjRecord(f[2].toDoubleOrNull(), f[3].toFloatOrNull() ?: return null, key, f[6].toDoubleOrNull() ?: return null,
            f[7].toDoubleOrNull() ?: return null, f[8].toLongOrNull() ?: return null, f[9].toLongOrNull() ?: return null)
    }
}

/** A song's audio on this phone: a finished download or a file from the device's music; null for anything else. */
internal fun djSource(context: Context, mediaId: String): Uri? = when {
    mediaId.startsWith("local:") -> mediaId.removePrefix("local:").toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
    mediaId.startsWith("yt:") -> OfflineMusicStore.get(context).let { store -> store.audio(store.currentScope, mediaId.removePrefix("yt:"))?.let(Uri::fromFile) }
    else -> null
}

/** The tempo Automix already measured for a streamed song from its cached end or start, when it was sure of it. */
internal fun cachedTempo(context: Context, mediaId: String): Double? {
    val store = AnalysisStore.get(context)
    return listOf("tail", "head").firstNotNullOfOrNull { part ->
        store.read("$mediaId:$part", beatModel = false)?.beats?.takeIf { it.confidence >= BEAT_CONFIDENCE }?.bpm
    }
}

/** What the order knows of [mediaId] now, without analysing: a kept analysis, else a cached tempo, else nothing. */
internal fun djFacts(context: Context, mediaId: String): DjFacts? =
    DjAnalysisStore.get(context).read(mediaId)?.facts?.takeIf { it.bpm != null || it.key != null }
        ?: cachedTempo(context, mediaId)?.let { DjFacts(it, null) }

/** Why analysis stopped before the end, if it did. */
internal enum class DjStop { BatterySaver, Warm }

/** Battery saver on, or the phone already warm: no analysis now. */
internal fun djHeld(context: Context): DjStop? {
    val power = context.getSystemService(PowerManager::class.java) ?: return null
    if (power.isPowerSaveMode) return DjStop.BatterySaver
    if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) return DjStop.Warm
    return null
}

/** Analyses one song's window: its key from the chroma and its tempo from the onsets. Null when it could not be decoded. */
internal suspend fun analyseForDj(context: Context, mediaId: String, now: () -> Long = System::currentTimeMillis): DjRecord? {
    val source = djSource(context, mediaId) ?: return null
    val job = coroutineContext
    val started = android.os.SystemClock.elapsedRealtime()
    var chroma: ChromaBuilder? = null
    var onsets: OnsetBuilder? = null
    val duration = TrackAnalysis.window(context, source, DJ_WINDOW_AT, DJ_WINDOW_MS) { rate, channels, startMs ->
        val c = ChromaBuilder(rate, channels).also { chroma = it }
        val o = OnsetBuilder(rate, channels, startMs).also { onsets = it }
        PcmSink { samples, count -> job.ensureActive(); c.add(samples, count); o.add(samples, count) }
    } ?: return null
    if (duration <= 0) return null
    val key = chroma?.takeIf { it.frames > 0 }?.chroma()?.let(::estimateKey)
    // Automix's model beats, when a download already has them, are surer than the light tracker's.
    val stored = cachedTempo(context, mediaId)
    val beats = onsets?.beats()
    val bpm = stored ?: beats?.takeIf { it.confidence >= BEAT_CONFIDENCE }?.bpm
    return DjRecord(bpm, if (stored != null) 1f else beats?.confidence ?: 0f, key?.takeIf { it.usable }?.key, key?.correlation ?: .0, key?.lead ?: .0,
        now(), android.os.SystemClock.elapsedRealtime() - started)
}

/**
 * Analyses [mediaIds] that have audio on the phone and no analysis yet, one at a time, at background priority.
 * [progress] gets (done, total) after each. Returns why it stopped early, or null when all were done.
 */
internal suspend fun analyseQueueForDj(context: Context, mediaIds: List<String>, progress: (Int, Int) -> Unit): DjStop? = withContext(Dispatchers.IO) {
    val store = DjAnalysisStore.get(context)
    val todo = mediaIds.distinct().filter { store.read(it) == null && djSource(context, it) != null }
    val thread = Process.myTid()
    val priority = Process.getThreadPriority(thread)
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    try {
        todo.forEachIndexed { done, id ->
            ensureActive()
            djHeld(context)?.let { return@withContext it }
            progress(done, todo.size)
            analyseForDj(context, id)?.let { store.write(id, it) }
        }
        progress(todo.size, todo.size)
        null
    } finally {
        Process.setThreadPriority(priority)
    }
}

/** Songs among [mediaIds] that could be analysed now: audio on the phone, no analysis yet. */
internal fun djAnalysable(context: Context, mediaIds: List<String>): Int {
    val store = DjAnalysisStore.get(context)
    return mediaIds.distinct().count { store.read(it) == null && djSource(context, it) != null }
}
