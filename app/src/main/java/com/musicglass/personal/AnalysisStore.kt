package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Automix (engine report, stage A): each song's analysed end and start, kept on the phone so a song is decoded once,
 * not each time it plays. One small file per part (about 4 KB for a tail), in Riff's own files, never backed up or
 * sent anywhere. Raise [ANALYSIS_VERSION] when the analysis changes, and the old files are read as missing.
 */
internal const val ANALYSIS_VERSION = 1
/**
 * Automix stage 4: a build with the beat model keeps its analyses apart, so stage 2's beats are not read as the model's.
 * The model runs only for Automix (owner, 31 Oct): Smart and the silence skip write [ANALYSIS_VERSION] files even then.
 */
internal const val ANALYSIS_VERSION_MODEL = 2
/** The most parts kept; past it the least recently used go. About 12 MB. */
internal const val ANALYSIS_KEEP = 3_000

internal class AnalysisStore(private val dir: File, private val keep: Int = ANALYSIS_KEEP, private val version: Int = ANALYSIS_VERSION) {
    companion object {
        @Volatile private var instance: AnalysisStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: AnalysisStore(File(context.applicationContext.noBackupFilesDir, "automix-analysis"),
                version = if (BeatModel.present(context)) ANALYSIS_VERSION_MODEL else ANALYSIS_VERSION).also { instance = it }
        }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".env")
    }

    /**
     * [key]'s analysis ("<media id>:tail" or ":head"), or null when it was never kept or is from an older analysis.
     * [beatModel]: only an analysis with the model's beats will do (Automix); otherwise one with stage 2's will too.
     */
    fun read(key: String, beatModel: Boolean = true): TrackEnvelope? {
        val f = file(key)
        val bytes = try { f.readBytes() } catch (_: IOException) { return null }
        val light = { decodeEnvelope(bytes, key, ANALYSIS_VERSION) }
        val envelope = decodeEnvelope(bytes, key, version) ?: (if (beatModel) null else light()) ?: run {
            // Stage 2's analysis where Automix wants the model's is kept until the model's replaces it; anything else goes.
            if (light() == null) f.delete()
            return null
        }
        // Used: it goes to the back of the line for pruning.
        f.setLastModified(System.currentTimeMillis())
        return envelope
    }

    /** [beatModel]: [envelope]'s beats are the model's. */
    @Synchronized fun write(key: String, envelope: TrackEnvelope, beatModel: Boolean = true) {
        try {
            dir.mkdirs()
            val f = file(key)
            val part = File(dir, f.name + ".part")
            part.writeBytes(encodeEnvelope(envelope, key, if (beatModel) version else ANALYSIS_VERSION))
            if (!part.renameTo(f)) { f.delete(); if (!part.renameTo(f)) part.delete() }
            prune()
        } catch (_: IOException) { } // Not kept: the song is analysed again next time.
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".env") } ?: return
        if (files.size <= keep) return
        files.sortedBy { it.lastModified() }.take(files.size - keep).forEach { it.delete() }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/**
 * After a download: its end and start read now, so its first blend and Feature 9's silence skip have them. Only when
 * a smart blend or the silence skip is on, and not under battery saver; a failure leaves it for when it plays.
 */
internal fun analyseDownload(context: Context, mediaId: String, audio: File) {
    val mode = blendMode(context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE))
    val smart = mode != BlendMode.Crossfade
    // The beat model only for Automix: Smart and the silence skip never use the beats (owner, 31 Oct).
    val model = mode == BlendMode.Automix
    val silence = context.getSharedPreferences(AUDIO_BOOST_PREFS, Context.MODE_PRIVATE).getBoolean(SKIP_END_SILENCE_KEY, false)
    if (!smart && !silence) return
    if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) return
    val store = AnalysisStore.get(context)
    for ((part, from, length) in listOf(Triple("tail", -ANALYSIS_LOCAL_TAIL_MS, ANALYSIS_LOCAL_TAIL_MS), Triple("head", 0L, ANALYSIS_HEAD_MS))) {
        if (part == "head" && !smart) continue
        val key = "$mediaId:$part"
        if (store.read(key, model)?.takeIf { part == "head" || !shortLocalTail(it) } != null) continue
        TrackAnalysis.envelope(context, Uri.fromFile(audio), from, length, model)?.let { store.write(key, it, model) }
    }
}

private const val MAGIC = 0x52414E31 // "RAN1"

internal fun encodeEnvelope(e: TrackEnvelope, key: String, version: Int = ANALYSIS_VERSION): ByteArray = ByteArrayOutputStream().also { bytes ->
    DataOutputStream(bytes).use { out ->
        out.writeInt(MAGIC); out.writeInt(version); out.writeUTF(key)
        out.writeLong(e.startMs); out.writeLong(e.durationMs)
        out.writeInt(e.loudnessDb.size); e.loudnessDb.forEach(out::writeFloat); e.lowDb.forEach(out::writeFloat)
        val beats = e.beats
        out.writeBoolean(beats != null)
        if (beats != null) {
            out.writeDouble(beats.bpm); out.writeFloat(beats.confidence); out.writeFloat(beats.downbeatConfidence)
            out.writeInt(beats.beatsMs.size); beats.beatsMs.forEach(out::writeLong)
            out.writeInt(beats.downbeatsMs.size); beats.downbeatsMs.forEach(out::writeLong)
        }
    }
}.toByteArray()

/** Null for a file of another analysis version, another key (a hash clash), or one cut short. */
internal fun decodeEnvelope(bytes: ByteArray, key: String, version: Int = ANALYSIS_VERSION): TrackEnvelope? = try {
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input.readInt() != MAGIC || input.readInt() != version || input.readUTF() != key) return null
        val start = input.readLong(); val duration = input.readLong()
        val n = input.readInt().takeIf { it in 0..100_000 } ?: return null
        val loudness = FloatArray(n) { input.readFloat() }; val low = FloatArray(n) { input.readFloat() }
        val beats = if (input.readBoolean()) {
            val bpm = input.readDouble(); val confidence = input.readFloat(); val downbeat = input.readFloat()
            val b = input.readInt().takeIf { it in 0..10_000 } ?: return null
            val beatsMs = LongArray(b) { input.readLong() }
            val d = input.readInt().takeIf { it in 0..10_000 } ?: return null
            BeatGrid(bpm, beatsMs, LongArray(d) { input.readLong() }, confidence, downbeat)
        } else null
        TrackEnvelope(start, loudness, low, duration, beats)
    }
} catch (_: IOException) { null }
