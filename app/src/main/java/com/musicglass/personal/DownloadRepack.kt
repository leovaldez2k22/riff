package com.musicglass.personal

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * Q18-057: downloads stored before [WebmAudioWriter] have a seek point only about every 30 s, so each seek in them
 * decoded up to 30 s of audio first. Each is stored again once, in place: the same compressed audio, now with a
 * point every half second. Nothing is downloaded and the sound is not changed. A song in the player's queue is left
 * for a later run, so a file the player has open is never replaced under it.
 */
internal object DownloadRepack {
    /** Wider than this between seek points is the old writer's file. Riff's own puts them [WebmAudioWriter.CLUSTER_MS] apart. */
    private const val COARSE_US = 2_000_000L

    /** A WebM download whose seek points are too far apart to seek in quickly. */
    fun coarse(audio: File): Boolean {
        if (BundleValidator.container(audio) != "webm") return false
        val index = WebmSeekIndex.read(audio) ?: return false
        return (index.timesUs.toList() + index.durationUs).zipWithNext().any { (a, b) -> b - a > COARSE_US }
    }

    private fun opus(audio: File): Boolean = runCatching {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(audio.absolutePath)
            (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS }
        } finally { extractor.release() }
    }.getOrDefault(false)

    /** Stores one download again if it needs it. True when it was replaced. */
    suspend fun repack(store: OfflineMusicStore, scope: String, id: String, assembler: AudioAssembler = AndroidAudioAssembler): Boolean {
        val dir = store.directory(scope, id)
        val audio = store.audio(scope, id) ?: return false
        if (!coarse(audio) || !opus(audio)) return false
        val durationMs = WebmSeekIndex.read(audio)?.durationUs?.div(1000) ?: return false
        // A .part file, so an interrupted run leaves nothing behind (BundleValidator.sweep).
        val part = File(dir, "repack.part")
        try {
            assembler.assemble(audio, part, durationMs) {}
            if (coarse(part)) return false
            return store.replaceAudio(scope, id, audio) { BundleValidator.commit(dir, part, BundleLoudness.read(dir)) } != null
        } finally { part.delete() }
    }

    /** Every download of [scope] that needs it, one at a time; [inQueue] is asked just before each. */
    suspend fun repackAll(store: OfflineMusicStore, scope: String, inQueue: suspend (String) -> Boolean,
        events: PlaybackEventLog, assembler: AudioAssembler = AndroidAudioAssembler) {
        var done = 0; var failed = 0
        for (row in store.rows(scope)) {
            val id = row.track.videoId
            if (inQueue(id)) continue
            runCatching { if (repack(store, scope, id, assembler)) done++ }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failed++ }
        }
        if (done + failed > 0) events.append("download_repack count=$done failed=$failed")
    }
}
