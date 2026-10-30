// Lint does not follow query(...).use { } and reports each cursor as unclosed. Every cursor in this file is closed by use.
@file:SuppressLint("Recycle")

package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Locale

/**
 * Feature 12, section 12.4: genres for Encore, looked up once per artist on MusicBrainz. Off by default; when on,
 * only artist names are sent, only on Wi-Fi, at most one request a second (MusicBrainz's rule), only for the top
 * [ENCORE_GENRE_ARTISTS] artists, and each answer is kept 90 days. Only tags on [ENCORE_GENRES] count, so a tag
 * like "seen live" never shows as a genre.
 */
internal const val ENCORE_GENRE_ARTISTS = 200
private const val ENCORE_GENRE_KEEP_MS = 90L * 86_400_000L
private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/artist/"

/** Real genres, with the spellings MusicBrainz tags use mapped to how Encore shows them. */
internal val ENCORE_GENRES: Map<String, String> = linkedMapOf(
    "pop" to "Pop", "dance-pop" to "Pop", "synth-pop" to "Synth-pop", "electropop" to "Pop", "indie pop" to "Indie pop",
    "rock" to "Rock", "alternative rock" to "Alternative rock", "indie rock" to "Indie rock", "hard rock" to "Rock", "pop rock" to "Pop rock",
    "punk" to "Punk", "pop punk" to "Pop punk", "metal" to "Metal", "heavy metal" to "Metal", "nu metal" to "Metal",
    "hip hop" to "Hip hop", "hip-hop" to "Hip hop", "rap" to "Hip hop", "trap" to "Trap", "r&b" to "R&B", "rnb" to "R&B",
    "contemporary r&b" to "R&B", "soul" to "Soul", "funk" to "Funk", "disco" to "Disco",
    "electronic" to "Electronic", "edm" to "Electronic", "house" to "House", "deep house" to "House", "techno" to "Techno",
    "trance" to "Trance", "drum and bass" to "Drum and bass", "dubstep" to "Dubstep", "ambient" to "Ambient", "lo-fi" to "Lo-fi",
    "indie" to "Indie", "folk" to "Folk", "indie folk" to "Folk", "country" to "Country", "blues" to "Blues", "jazz" to "Jazz",
    "classical" to "Classical", "reggae" to "Reggae", "reggaeton" to "Reggaeton", "latin" to "Latin", "latin pop" to "Latin",
    "k-pop" to "K-pop", "j-pop" to "J-pop", "bollywood" to "Bollywood", "filmi" to "Bollywood", "soundtrack" to "Soundtrack",
    "afrobeats" to "Afrobeats", "gospel" to "Gospel", "singer-songwriter" to "Singer-songwriter")

/** An artist's genres from a MusicBrainz search answer: the best match's genres (or tags) on the list, most voted first. */
internal fun musicBrainzGenres(answer: JsonElement?, name: String): List<String> {
    val artists = ((answer as? JsonObject)?.get("artists") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val wanted = name.trim().lowercase(Locale.ROOT)
    val best = artists.firstOrNull { (it["name"]?.jsonPrimitive?.contentOrNull)?.lowercase(Locale.ROOT) == wanted }
        ?: artists.firstOrNull { (it["score"]?.jsonPrimitive?.intOrNull ?: 0) >= 95 } ?: return emptyList()
    val tags = ((best["genres"] as? JsonArray).orEmpty() + (best["tags"] as? JsonArray).orEmpty()).mapNotNull { it as? JsonObject }
    return tags.mapNotNull { tag ->
        val genre = ENCORE_GENRES[tag["name"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT) ?: return@mapNotNull null] ?: return@mapNotNull null
        genre to (tag["count"]?.jsonPrimitive?.intOrNull ?: 0)
    }.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.entries.sortedByDescending { it.value }.map { it.key }.take(3)
}

/** Version 3 of library-state.db: the genre cache. */
internal fun encoreGenreSchema(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE artist_genres(artist_key TEXT PRIMARY KEY NOT NULL,genres TEXT NOT NULL,source TEXT NOT NULL,fetched_at INTEGER NOT NULL)")
}

internal fun encoreGenresOn(context: Context) = context.applicationContext
    .getSharedPreferences("music_collections", Context.MODE_PRIVATE).getBoolean("encore_genres", false)

internal fun setEncoreGenresOn(context: Context, on: Boolean) {
    context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit().putBoolean("encore_genres", on).apply()
}

internal class EncoreGenres(private val db: LibraryStateDb, private val http: Call.Factory, private val wallTime: () -> Long,
    private val enabled: () -> Boolean, private val unmetered: () -> Boolean, private val pause: suspend (Long) -> Unit = { delay(it) }) {

    private fun key(name: String) = name.trim().lowercase(Locale.ROOT)

    /** The genres already known for [names], by name. Empty when the setting is off, so Encore hides genres. */
    fun cached(names: List<String>): Map<String, List<String>> {
        if (!enabled() || names.isEmpty()) return emptyMap()
        val keys = names.associateBy(::key)
        return try {
            keys.keys.chunked(500).flatMap { chunk ->
                db.readableDatabase.query("artist_genres", arrayOf("artist_key", "genres"), chunk.joinToString(",", "artist_key IN (", ")") { "?" },
                    chunk.toTypedArray(), null, null, null).use { c ->
                    buildList { while (c.moveToNext()) { val genres = c.getString(1).split('|').filter(String::isNotBlank); if (genres.isNotEmpty()) add(keys.getValue(c.getString(0)) to genres) } }
                }
            }.toMap()
        } catch (_: SQLiteException) { emptyMap() }
    }

    /** Names not asked about in 90 days. */
    fun stale(names: List<String>): List<String> {
        val fresh = try {
            db.readableDatabase.query("artist_genres", arrayOf("artist_key"), "fetched_at>=?", arrayOf((wallTime() - ENCORE_GENRE_KEEP_MS).toString()), null, null, null)
                .use { c -> buildSet<String> { while (c.moveToNext()) add(c.getString(0)) } }
        } catch (_: SQLiteException) { return emptyList() }
        return names.distinctBy(::key).filter { key(it) !in fresh }
    }

    /**
     * Asks MusicBrainz about the top artists not yet known. Sends nothing when the setting is off or the network is
     * metered, and stops at the first failure (asked again next time). Returns how many artists were looked up.
     */
    suspend fun refresh(names: List<String>): Int {
        var asked = 0
        for (name in stale(names.take(ENCORE_GENRE_ARTISTS))) {
            if (!enabled() || !unmetered()) break
            val genres = lookup(name) ?: break
            try {
                db.write { d -> d.insertWithOnConflict("artist_genres", null, ContentValues().apply {
                    put("artist_key", key(name)); put("genres", genres.joinToString("|")); put("source", "musicbrainz"); put("fetched_at", wallTime())
                }, SQLiteDatabase.CONFLICT_REPLACE) }
            } catch (_: SQLiteException) { break }
            asked++
            pause(1_100)
        }
        return asked
    }

    private fun lookup(name: String): List<String>? {
        val url = MUSICBRAINZ.toHttpUrl().newBuilder().addQueryParameter("query", "artist:\"${name.replace("\"", "")}\"")
            .addQueryParameter("fmt", "json").addQueryParameter("limit", "3").build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Riff/${BuildConfig.VERSION_NAME} ( https://github.com/leovaldez2k22/riff )").build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                musicBrainzGenres(Json.parseToJsonElement(response.body?.string() ?: return null), name)
            }
        } catch (_: java.io.IOException) { null } catch (_: kotlinx.serialization.SerializationException) { null }
    }

    /** Clearing history clears the genre cache too: it names artists the owner listened to. */
    fun clear() { try { db.write { d -> d.delete("artist_genres", null, null) } } catch (_: SQLiteException) { } }
}
