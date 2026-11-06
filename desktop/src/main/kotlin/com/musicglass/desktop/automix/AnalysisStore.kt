package com.musicglass.desktop.automix

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Automix (engine report, stage A): each song's analysed end and start, kept in Riff's desktop data directory so a song is analysed once,
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
        if(f.length()>131072)return null
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
            val encoded=encodeEnvelope(envelope,key,if(beatModel)version else ANALYSIS_VERSION)
            require(encoded.size<=131072)
            java.nio.channels.FileChannel.open(part.toPath(),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE).use{channel->val bytes=java.nio.ByteBuffer.wrap(encoded);while(bytes.hasRemaining())channel.write(bytes);channel.force(true)}
            java.nio.file.Files.move(part.toPath(),f.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
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
    if(bytes.size>131072)return null
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
        if(input.available()!=0||start<0||duration<=0||start>duration||loudness.any{!it.isFinite()}||low.any{!it.isFinite()})return null
        if(beats!=null&&(!beats.bpm.isFinite()||beats.bpm !in 20.0..400.0||!beats.confidence.isFinite()||beats.confidence !in 0f..1f||!beats.downbeatConfidence.isFinite()||beats.downbeatConfidence !in 0f..1f||beats.beatsMs.any{it<start||it>duration}||beats.downbeatsMs.any{it<start||it>duration}))return null
        fun ordered(values:LongArray):Boolean{for(i in 1 until values.size)if(values[i]<=values[i-1])return false;return true}
        if(beats!=null&&(!ordered(beats.beatsMs)||!ordered(beats.downbeatsMs)))return null
        TrackEnvelope(start, loudness, low, duration, beats)
    }
} catch (_: IOException) { null }
