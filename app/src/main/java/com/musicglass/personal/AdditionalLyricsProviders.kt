package com.musicglass.personal

import java.io.IOException
import kotlinx.serialization.json.*

internal fun checkNetEaseResponse(row: JsonObject?) {
    val code = row?.get("code")?.jsonPrimitive?.intOrNull
    if (code != null && code != 200) throw IOException("NetEase lyrics unavailable (code $code)")
}

internal fun netEaseMatchScore(row: JsonObject, track: LyricsTrack): Double {
    val title = row["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val duration = (row["duration"] ?: row["dt"])?.jsonPrimitive?.longOrNull ?: return 0.0
    val artists = ((row["artists"] ?: row["ar"]) as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
    return (artists + artists.joinToString(", ")).maxOfOrNull { lyricsMatchScore(track,title,it,duration) } ?: 0.0
}

internal fun netEaseLyrics(row: JsonObject?, durationMs: Long, id: String): LyricsDocument? {
    val url = "https://music.163.com/song?id=$id"
    // Some NetEase responses include YRC. Use supplied word durations, never interpolate them.
    (row?.get("yrc") as? JsonObject)?.get("lyric")?.jsonPrimitive?.contentOrNull
        ?.let { parseNetEaseYrc(it,durationMs,url) }?.let { return it }
    (row?.get("lrc") as? JsonObject)?.get("lyric")?.jsonPrimitive?.contentOrNull
        ?.let { parseLrc(it,durationMs,"NetEase Cloud Music",url) }?.let { return it }
    return null
}

internal fun parseNetEaseYrc(raw: String, durationMs: Long, url: String): LyricsDocument? {
    val header = Regex("^\\[(\\d+),(\\d+)\\]")
    val word = Regex("\\((\\d+),(\\d+),\\d+\\)")
    val lines = raw.lineSequence().take(2000).mapNotNull { row ->
        val h = header.find(row) ?: return@mapNotNull null
        val start = h.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val length = h.groupValues[2].toLongOrNull()?.takeIf { it in 1..durationMs } ?: return@mapNotNull null
        if (start !in 0..durationMs) return@mapNotNull null
        val body = row.substring(h.range.last + 1)
        val stamps = word.findAll(body).toList()
        if (stamps.isEmpty()) return@mapNotNull null
        val text = StringBuilder()
        val parts = stamps.mapIndexedNotNull { index, stamp ->
            val from = text.length
            text.append(body.substring(stamp.range.last+1,stamps.getOrNull(index+1)?.range?.first ?: body.length))
            val time = stamp.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val span = stamp.groupValues[2].toLongOrNull()?.takeIf { it in 1..durationMs } ?: return@mapIndexedNotNull null
            if (text.length == from || time < start || time+span > start+length+500) return@mapIndexedNotNull null
            LyricPart(time,time+span,from,text.length)
        }
        LyricLine(start,start+length,text.toString(),parts)
    }.toList()
    val result = finishLyricLines(lines,durationMs)
    return result.takeIf { rows -> rows.any { it.text.isNotBlank() } }
        ?.let { LyricsDocument("NetEase Cloud Music",url,it) }
}

internal fun plainLyrics(raw: String, source: String, url: String): LyricsDocument? = raw.trim()
    .takeIf { it.isNotBlank() }?.let { text ->
        LyricsDocument(source,url,text.lineSequence().take(2000).map { LyricLine(-1,-1,it) }.toList())
    }

internal fun lyricsProviderPriority(source: String): Int = when(source) {
    "Better Lyrics" -> 0
    "Better Lyrics · QQ" -> 1
    LRC_RED_SOURCE -> 2
    "NetEase Cloud Music" -> 3
    "Better Lyrics · Kugou" -> 4
    "LRCLIB" -> 5
    "YouTube Music" -> 6
    "Lyrics.ovh" -> 7
    else -> 8
}
