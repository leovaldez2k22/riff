package com.musicglass.personal

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.File

/** One play. [mediaId] is "yt:<videoId>" for YouTube Music or "local:<id>" for audio on this phone. */
internal data class HistoryEntry(val mediaId: String, val title: String, val artist: String,
    val artworkUrl: String?, val playedAt: Long, val music: Boolean = true) {
    val videoId: String? get() = mediaId.removePrefix("yt:").takeIf { mediaId.startsWith("yt:") }
    fun track(): ProviderTrack? = videoId?.let { ProviderTrack(it, title, artist, artworkUrl ?: providerArtworkUrl(it), isMusic = music) }
}

internal fun encodeHistoryEntry(e: HistoryEntry): String = buildJsonObject {
    put("id", e.mediaId); put("title", e.title.take(180)); put("artist", e.artist.take(180))
    put("artwork", e.artworkUrl ?: ""); put("at", e.playedAt); put("music", e.music)
}.toString()

internal fun decodeHistoryEntry(line: String): HistoryEntry? = runCatching {
    val o = Json.parseToJsonElement(line).jsonObject
    fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    HistoryEntry(text("id").takeIf(String::isNotBlank) ?: return null, text("title"), text("artist"),
        text("artwork").takeIf { it.startsWith("https://") || it.startsWith("content://") || it.startsWith("file://") },
        (o["at"] as? JsonPrimitive)?.longOrNull ?: return null, (o["music"] as? JsonPrimitive)?.booleanOrNull ?: true)
}.getOrNull()

/** Newest first, grouped under "Today", "Yesterday" or the date. */
internal fun historyDayLabel(playedAt: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val day = java.time.Instant.ofEpochMilli(playedAt).atZone(zone).toLocalDate()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern(
            if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy", java.util.Locale.getDefault()))
    }
}

/**
 * Every song played, kept in the app's files (not its cache), so Android's "Clear cache" keeps it
 * and only "Clear storage"/"Clear data" removes it. One JSON line per play, oldest first.
 */
internal class ListeningHistory(private val file: File) {
    private val lock = Any()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun record(entry: HistoryEntry) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(encodeHistoryEntry(entry) + "\n")
            if (file.length() > MAX_BYTES) trim()
        }
        _revision.value++
    }

    /** Newest first. Call off the main thread. */
    fun entries(): List<HistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { file.readLines().mapNotNull(::decodeHistoryEntry).asReversed() }.getOrDefault(emptyList())
    }

    private fun trim() {
        val keep = file.readLines().takeLast(KEEP_AFTER_TRIM)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(keep.joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
    }

    companion object {
        private const val MAX_BYTES = 16L * 1024 * 1024
        private const val KEEP_AFTER_TRIM = 40_000
        @Volatile private var instance: ListeningHistory? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: ListeningHistory(File(context.applicationContext.filesDir, "listening-history/history-v1.jsonl")).also { instance = it }
        }
        /** Respects Settings → Library → Save listening history. */
        fun enabled(context: Context) = context.applicationContext
            .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getBoolean("history_enabled", true)
    }
}
