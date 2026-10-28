package com.musicglass.personal

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Feature 4: lrc.red, a synced-lyrics database (often word-timed, with background vocals), searched by title and
 * artist. Its documents live at https://lrc.red/s/{ISRC}.ttml. It has no published API reference; the shapes
 * here are what it answered on 4 and 28 October 2026 (`{"hits":[{isrc,title,artist,album,duration}],"next":…}`).
 */
internal const val LRC_RED_SOURCE = "lrc.red"
private const val LRC_RED_BASE = "https://lrc.red/"
private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}\\d{7}")

/** Only a well-formed ISRC becomes a path: anything else from a search answer is never put in a URL. */
internal fun lrcRedDocumentUrl(isrc: String?): String? =
    isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf(ISRC::matches)?.let { "${LRC_RED_BASE}s/$it.ttml" }

/**
 * The ISRC of the search hit that is this recording: the same title (versions in brackets count, so a live cut
 * or an a cappella mix is not the studio song) by one of the same artists, within Riff's usual 3 s of length.
 */
internal fun lrcRedBestIsrc(answer: JsonElement?, track: LyricsTrack): String? {
    val hits = ((answer as? JsonObject)?.get("hits") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return hits.mapNotNull { hit ->
        val isrc = hit["isrc"]?.jsonPrimitive?.contentOrNull?.takeIf { lrcRedDocumentUrl(it) != null } ?: return@mapNotNull null
        val title = hit["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val artist = hit["artist"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val durationMs = ((hit["duration"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null) * 1000).toLong()
        val artists = listOf(artist) + artist.split(", ", " & ", " x ", " feat. ", " ft. ").map(String::trim)
        val score = artists.filter(String::isNotBlank).maxOfOrNull { lyricsMatchScore(track, title, it, durationMs) } ?: 0.0
        (isrc to score).takeIf { score > 0 }
    }.maxByOrNull { it.second }?.first
}

/** Search, then the best hit's TTML, read by the same parser as Better Lyrics (word timing and background vocals). */
internal suspend fun lrcRedLyrics(track: LyricsTrack, title: String, artist: String,
    request: suspend (url: String, params: Map<String, String>, raw: Boolean) -> JsonElement?): LyricsDocument? {
    val search = request("${LRC_RED_BASE}search.json", mapOf("q" to "$title $artist".trim()), false)
    val url = lrcRedDocumentUrl(lrcRedBestIsrc(search, track)) ?: return null
    val ttml = request(url, emptyMap(), true)?.jsonPrimitive?.contentOrNull ?: return null
    return parseTtml(ttml, track.durationMs, LRC_RED_SOURCE)?.copy(sourceUrl = url)
}
