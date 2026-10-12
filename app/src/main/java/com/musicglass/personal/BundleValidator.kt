package com.musicglass.personal

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** What is wrong with a download's files. [wire] goes to the event log; nothing else does. */
internal enum class BundleProblem(val wire: String) {
    NoAudio("no_audio"), NoMarker("no_marker"), MarkerMismatch("marker_mismatch"), UnknownContainer("container")
}

/**
 * A download's folder (Q09). It holds `audio.media`, a `ready` marker written last, and optionally
 * `artwork`, `lyrics.json` and `loudness`. Anything ending in `.part` belongs to a transfer that has
 * not committed and is never played.
 *
 * The marker holds the audio's length. Builds before rc11 wrote "1"; that is still accepted.
 */
internal object BundleValidator {
    const val AUDIO = "audio.media"
    const val READY = "ready"
    const val SOURCE_PART = "audio.part"
    const val ASSEMBLED_PART = "assembled.part"

    /** The audio if the marker vouches for it, else null. A few file-system calls; no audio is read. */
    fun audio(dir: File): File? = runCatching { File(dir, AUDIO).takeIf { markerProblem(dir) == null } }.getOrNull()

    private fun markerProblem(dir: File): BundleProblem? {
        val audio = File(dir, AUDIO)
        if (!audio.isFile || audio.length() <= 0) return BundleProblem.NoAudio
        val ready = File(dir, READY)
        if (!ready.isFile || ready.length() > 32) return BundleProblem.NoMarker
        val text = ready.readText().trim()
        return if (text == "1" || text.toLongOrNull() == audio.length()) null else BundleProblem.MarkerMismatch
    }

    /**
     * The full check made when a download is opened for playing: the marker, the length it records, and
     * that the file begins as one of the two containers a download is ever written in. Null means sound.
     * Duration is checked when the download is committed, against what the provider declared.
     */
    fun problem(dir: File): BundleProblem? = runCatching {
        markerProblem(dir) ?: if (container(File(dir, AUDIO)) == null) BundleProblem.UnknownContainer else null
    }.getOrElse { BundleProblem.NoAudio }

    /** "webm" or "mp4" from the first bytes, or null. */
    fun container(file: File): String? = runCatching {
        val head = ByteArray(12)
        RandomAccessFile(file, "r").use { if (it.length() < head.size) return null; it.readFully(head) }
        when {
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() && head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "webm"
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> "mp4"
            else -> null
        }
    }.getOrNull()

    /**
     * Makes the rebuilt audio the download: flushed to storage, renamed in its own folder, then the marker
     * written beside it and renamed into place. A stop at any point leaves either no marker (not a
     * download yet) or a marker that matches the audio.
     */
    fun commit(dir: File, assembled: File, loudnessDb: Double?): File {
        if (container(assembled) == null) throw IOException("Rebuilt audio is not a known container")
        sync(assembled)
        val audio = File(dir, AUDIO)
        File(dir, READY).delete() // A marker from an earlier copy must not vouch for this one.
        if (!assembled.renameTo(audio)) { audio.delete(); if (!assembled.renameTo(audio)) throw IOException("Could not commit audio") }
        // Before the marker, so a complete bundle never gains or loses its value later.
        BundleLoudness.write(dir, loudnessDb)
        val part = File(dir, "$READY.part")
        FileOutputStream(part).use { it.write(audio.length().toString().toByteArray()); it.fd.sync() }
        val ready = File(dir, READY)
        if (!part.renameTo(ready)) { ready.delete(); if (!part.renameTo(ready)) { part.delete(); throw IOException("Could not mark the download ready") } }
        syncDirectory(dir)
        return audio
    }

    /** Withdraws the marker of a download found to be unusable, so it is not offered for playing again. */
    fun revoke(dir: File) { File(dir, READY).delete() }

    /** Removes what an interrupted transfer left: every `.part` file, and audio that never got its marker. */
    fun sweep(dir: File): Int {
        var removed = 0
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".part") && it.delete()) removed++ }
        if (File(dir, AUDIO).isFile && markerProblem(dir) != null) {
            if (File(dir, AUDIO).delete()) removed++
            File(dir, READY).delete()
        }
        return removed
    }

    private fun sync(file: File) { RandomAccessFile(file, "rw").use { it.fd.sync() } }
    /** Best effort: not every file system lets a folder be opened. The marker's own content is already flushed. */
    private fun syncDirectory(dir: File) { runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } } }
}
