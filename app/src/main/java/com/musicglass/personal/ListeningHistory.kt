package com.musicglass.personal

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.*

/** One play. [mediaId] is "yt:<videoId>" for YouTube Music or "local:<id>" for audio on this phone. */
internal data class HistoryEntry(val mediaId: String, val title: String, val artist: String,
    val artworkUrl: String?, val playedAt: Long, val music: Boolean = true,
    /** Made once per logical play, so a retried write cannot count the play twice. */
    val eventId: String = "", val durationMs: Long? = null) {
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
        today -> string(R.string.today)
        today.minusDays(1) -> string(R.string.yesterday)
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern(
            if (day.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy", java.util.Locale.getDefault()))
    }
}

/**
 * One history event per logical play. A play counts once it has been heard for one tick (about
 * five seconds). Pausing, resuming or seeking within it does not start another; moving to another
 * queue entry, repeating the entry, or playing it again after it finished does.
 */
internal class LogicalPlays {
    private var counted: String? = null
    /** The player moved to a queue entry, including a repeat of the same one. */
    fun started() { counted = null }
    /** The entry played to its end. */
    fun completed() { counted = null }
    /** True once per play. Live broadcasts are not song history. */
    fun qualifies(mediaId: String, playing: Boolean, live: Boolean): Boolean {
        if (!playing || mediaId == counted) return false
        if (live || !(mediaId.startsWith("yt:") || mediaId.startsWith("local:"))) return false
        counted = mediaId
        return true
    }
}

/** Respects Settings -> Privacy and data -> Save listening history. Device-wide. */
internal fun historyEnabled(context: Context) = context.applicationContext
    .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getBoolean("history_enabled", true)

/** 30, 90 or 365 days, or null to keep plays until they are cleared. Device-wide; applied to each owner separately. */
internal fun historyRetentionDays(context: Context): Int? = context.applicationContext
    .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getInt("history_retention_days", 0).takeIf { it in setOf(30, 90, 365) }

internal fun setHistoryRetentionDays(context: Context, days: Int?) {
    context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit {
        if (days in setOf(30, 90, 365)) putInt("history_retention_days", days!!) else remove("history_retention_days")
    }
}
