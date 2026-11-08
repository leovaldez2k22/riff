package com.musicglass.personal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/*
 * Feature 17 (Riff+, owner Q17, 8 November): background for the song playing and its artist, looked up only when
 * the panel is opened. MusicBrainz identifies the recording (and through it the artist, so namesakes are told
 * apart), its work and writers; the encyclopedia link MusicBrainz keeps (a Wikidata item) names the Wikipedia
 * article, read in the phone's language or, labelled, in English. Only the title and artist go out, never account,
 * cookies, lyrics or plays. Plain text only; a capped copy is kept in the app's cache for 30 days.
 */

internal data class BackgroundArticle(val title: String, val extract: String, val url: String, val lang: String, val fallback: Boolean)
internal data class SongFacts(val title: String, val artist: String, val released: String?, val album: String?, val writers: List<String>,
    val musicBrainzUrl: String, val article: BackgroundArticle?)
internal data class ArtistFacts(val name: String, val disambiguation: String?, val kind: String?, val country: String?, val begin: String?,
    val musicBrainzUrl: String, val article: BackgroundArticle?)

internal enum class BackgroundStatus { Found, NotFound, Ambiguous, Unavailable }
internal data class SongBackground(val status: BackgroundStatus, val song: SongFacts? = null, val artist: ArtistFacts? = null)

/** The hosts the panel may contact or open; anything else in an answer is ignored. */
internal val BACKGROUND_HOSTS = listOf("musicbrainz.org", "www.wikidata.org", "wikipedia.org")
private const val MB = "https://musicbrainz.org/ws/2/"
private const val EXTRACT_MAX = 1_500
private const val BODY_MAX = 512 * 1024

/** A song title without what YouTube adds: "(feat. …)", "[Official Video]", "- Remastered 2011" and the like. */
internal fun backgroundTitle(title: String): String = title
    .replace(Regex("(?i)\\s*[(\\[](feat\\.?|ft\\.?|with|official|lyric|audio|video|visuali[sz]er|remaster|live|explicit|clean)[^)\\]]*[)\\]]"), "")
    .replace(Regex("(?i)\\s+-\\s+(\\d{4}\\s+)?remaster(ed)?.*$"), "")
    .trim().ifBlank { title.trim() }

private fun norm(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
private fun luceneQuoted(text: String) = "\"" + text.replace(Regex("[\"\\\\]"), " ").trim() + "\""

private val JsonElement?.obj get() = this as? JsonObject
private val JsonElement?.arr get() = this as? JsonArray
private fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** The Wikidata item a MusicBrainz entity links to ("Q" and digits), if any. */
internal fun wikidataItem(entity: JsonObject?): String? = entity?.get("relations").arr.orEmpty().mapNotNull { it.obj }
    .firstOrNull { it.str("type") == "wikidata" }?.get("url").obj.str("resource")
    ?.substringAfterLast('/')?.takeIf { Regex("Q[0-9]{1,12}").matches(it) }

/**
 * Looks up the background of [title] by [artist] through [get] (one GET returning the body, or null on any failure;
 * MusicBrainz requests wait their turn at [mbTurn]). [lang] is the preferred Wikipedia language.
 */
internal class BackgroundLookup(private val get: suspend (HttpUrl) -> String?, private val mbTurn: suspend () -> Unit, private val lang: String) {
    private var failed = false

    private suspend fun json(url: HttpUrl): JsonObject? {
        coroutineContext.ensureActive()
        if (url.host == "musicbrainz.org") mbTurn()
        val body = get(url) ?: run { failed = true; return null }
        return runCatching { Json.parseToJsonElement(body).obj }.getOrNull()
    }

    private fun mb(path: String, vararg query: Pair<String, String>) = (MB + path).toHttpUrl().newBuilder().apply {
        query.forEach { (k, v) -> addQueryParameter(k, v) }; addQueryParameter("fmt", "json") }.build()

    suspend fun find(title: String, artist: String): SongBackground {
        val wantedTitle = backgroundTitle(title)
        val credit = encoreArtists(artist, null).firstOrNull()?.second ?: artist
        val search = json(mb("recording", "query" to "recording:${luceneQuoted(wantedTitle)} AND artist:${luceneQuoted(credit)}", "limit" to "10"))
            ?: return SongBackground(BackgroundStatus.Unavailable)
        val matches = search["recordings"].arr.orEmpty().mapNotNull { it.obj }.filter { r ->
            ((r["score"] as? JsonPrimitive)?.intOrNull ?: 0) >= 90 && norm(r.str("title").orEmpty()) == norm(wantedTitle) &&
                r["artist-credit"].arr.orEmpty().any { norm(it.obj?.get("artist").obj.str("name").orEmpty()) == norm(credit) }
        }
        val artistIds = matches.mapNotNull { r -> r["artist-credit"].arr.orEmpty().mapNotNull { it.obj?.get("artist").obj }
            .firstOrNull { norm(it.str("name").orEmpty()) == norm(credit) }?.str("id") }.distinct()
        if (matches.isEmpty()) return artistOnly(credit)
        if (artistIds.size > 1) return SongBackground(BackgroundStatus.Ambiguous)
        // The earliest release among the matching recordings dates the song.
        val first = matches.minByOrNull { it.str("first-release-date") ?: "9999" }!!
        val song = songFacts(first, credit)
        val artistFacts = artistIds.firstOrNull()?.let { artistFacts(it) }
        if (failed && song == null && artistFacts == null) return SongBackground(BackgroundStatus.Unavailable)
        return SongBackground(BackgroundStatus.Found, song, artistFacts)
    }

    /** No recording matched: the artist alone, when only one artist carries that exact name. */
    private suspend fun artistOnly(name: String): SongBackground {
        val found = json(mb("artist", "query" to "artist:${luceneQuoted(name)}", "limit" to "5")) ?: return SongBackground(BackgroundStatus.Unavailable)
        val exact = found["artists"].arr.orEmpty().mapNotNull { it.obj }.filter { ((it["score"] as? JsonPrimitive)?.intOrNull ?: 0) >= 90 && norm(it.str("name").orEmpty()) == norm(name) }
        return when (exact.size) {
            0 -> SongBackground(BackgroundStatus.NotFound)
            1 -> artistFacts(exact.single().str("id") ?: return SongBackground(BackgroundStatus.NotFound))?.let { SongBackground(BackgroundStatus.Found, null, it) }
                ?: SongBackground(BackgroundStatus.Unavailable)
            else -> SongBackground(BackgroundStatus.Ambiguous)
        }
    }

    private suspend fun songFacts(recording: JsonObject, credit: String): SongFacts? {
        val id = recording.str("id") ?: return null
        val album = recording["releases"].arr.orEmpty().mapNotNull { it.obj }.minByOrNull { it.str("date") ?: "9999" }?.str("title")
        val work = json(mb("recording/$id", "inc" to "work-rels"))?.get("relations").arr.orEmpty().mapNotNull { it.obj }
            .firstOrNull { it.str("type") == "performance" }?.get("work").obj.str("id")
        var writers = emptyList<String>(); var article: BackgroundArticle? = null
        if (work != null) json(mb("work/$work", "inc" to "artist-rels+url-rels"))?.let { w ->
            writers = w["relations"].arr.orEmpty().mapNotNull { it.obj }.filter { it.str("type") in setOf("composer", "lyricist", "writer") }
                .mapNotNull { it["artist"].obj.str("name") }.distinct().take(8)
            article = wikidataItem(w)?.let { article(it) }
        }
        return SongFacts(recording.str("title") ?: return null, credit, recording.str("first-release-date"), album, writers,
            "https://musicbrainz.org/recording/$id", article)
    }

    private suspend fun artistFacts(id: String): ArtistFacts? {
        if (!Regex("[0-9a-f-]{36}").matches(id)) return null
        val a = json(mb("artist/$id", "inc" to "url-rels")) ?: return null
        return ArtistFacts(a.str("name") ?: return null, a.str("disambiguation"), a.str("type"), a.str("country"),
            a["life-span"].obj.str("begin"), "https://musicbrainz.org/artist/$id", wikidataItem(a)?.let { article(it) })
    }

    /** The Wikipedia article of [item], in [lang] or else English (labelled); null when neither exists or it is a disambiguation page. */
    internal suspend fun article(item: String): BackgroundArticle? {
        val wanted = lang.takeIf { Regex("[a-z]{2,3}").matches(it) } ?: "en"
        val sites = listOf(wanted, "en").distinct()
        val links = json("https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder().addQueryParameter("action", "wbgetentities")
            .addQueryParameter("ids", item).addQueryParameter("props", "sitelinks").addQueryParameter("sitefilter", sites.joinToString("|") { it + "wiki" })
            .addQueryParameter("format", "json").build())?.get("entities").obj?.get(item).obj?.get("sitelinks").obj ?: return null
        for (site in sites) {
            val title = links[site + "wiki"].obj.str("title") ?: continue
            val url = HttpUrl.Builder().scheme("https").host("$site.wikipedia.org").addPathSegments("api/rest_v1/page/summary").addPathSegment(title.replace(' ', '_')).build()
            val page = json(url) ?: continue
            if (page.str("type") != "standard") continue
            val extract = page.str("extract")?.replace(Regex("\\s+"), " ")?.trim()?.take(EXTRACT_MAX) ?: continue
            val link = page["content_urls"].obj?.get("desktop").obj.str("page")?.takeIf { httpsAddressOn(it, listOf("$site.wikipedia.org")) != null }
                ?: "https://$site.wikipedia.org/wiki/" + title.replace(' ', '_')
            return BackgroundArticle(page.str("title") ?: title, extract, link, site, fallback = site != wanted)
        }
        return null
    }
}

/** The kept copy: one small JSON file per song in the app's cache, 30 days, at most 200 files. */
internal class BackgroundCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val keepMs = 30L * 86_400_000
    private fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".json")

    fun get(key: String): SongBackground? = runCatching {
        val f = file(key); if (!f.isFile || now() - f.lastModified() > keepMs) return null
        decode(Json.parseToJsonElement(f.readText()).obj ?: return null)
    }.getOrNull()

    fun put(key: String, value: SongBackground) {
        if (value.status == BackgroundStatus.Unavailable) return
        runCatching {
            dir.mkdirs(); file(key).writeText(encode(value).toString())
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
        }
    }

    private fun article(a: BackgroundArticle?) = a?.let { buildJsonObject { put("title", it.title); put("extract", it.extract); put("url", it.url); put("lang", it.lang); put("fallback", it.fallback) } }
    private fun encode(v: SongBackground) = buildJsonObject {
        put("status", v.status.name)
        v.song?.let { s -> put("song", buildJsonObject { put("title", s.title); put("artist", s.artist); s.released?.let { put("released", it) }; s.album?.let { put("album", it) }
            put("writers", JsonArray(s.writers.map(::JsonPrimitive))); put("mb", s.musicBrainzUrl); article(s.article)?.let { put("article", it) } }) }
        v.artist?.let { a -> put("artist", buildJsonObject { put("name", a.name); a.disambiguation?.let { put("disambiguation", it) }; a.kind?.let { put("kind", it) }
            a.country?.let { put("country", it) }; a.begin?.let { put("begin", it) }; put("mb", a.musicBrainzUrl); article(a.article)?.let { put("article", it) } }) }
    }
    private fun articleOf(o: JsonObject?) = o?.let { BackgroundArticle(it.str("title") ?: return null, it.str("extract") ?: return null, it.str("url") ?: return null,
        it.str("lang") ?: "en", it["fallback"]?.jsonPrimitive?.contentOrNull == "true") }
    private fun decode(o: JsonObject): SongBackground? {
        val status = BackgroundStatus.entries.firstOrNull { it.name == o.str("status") } ?: return null
        val song = o["song"].obj?.let { s -> SongFacts(s.str("title") ?: return null, s.str("artist").orEmpty(), s.str("released"), s.str("album"),
            s["writers"].arr.orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, s.str("mb") ?: return null, articleOf(s["article"].obj)) }
        val artist = o["artist"].obj?.let { a -> ArtistFacts(a.str("name") ?: return null, a.str("disambiguation"), a.str("kind"), a.str("country"), a.str("begin"),
            a.str("mb") ?: return null, articleOf(a["article"].obj)) }
        return SongBackground(status, song, artist)
    }
}

/** One GET over Riff's own client: HTTPS to [BACKGROUND_HOSTS] only, Riff's user agent, at most 512 KB read. */
internal fun backgroundGet(http: Call.Factory): suspend (HttpUrl) -> String? = { url ->
    if (!url.isHttps || BACKGROUND_HOSTS.none { url.host == it || url.host.endsWith(".$it") }) null
    else withContext(Dispatchers.IO) {
        suspendCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Riff/${BuildConfig.VERSION_NAME} ( https://github.com/leovaldez2k22/riff )").build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val source = r.body?.source() ?: return@use null
                if (!source.request(BODY_MAX + 1L)) source.buffer.readUtf8() else null
            }
        }.getOrNull()
    }
}

/** The background for a song, from the cache or looked up now. */
internal suspend fun songBackground(context: Context, title: String, artist: String): SongBackground {
    val cache = BackgroundCache(File(context.cacheDir, "song-background"))
    val lang = Locale.getDefault().language.lowercase(Locale.ROOT)
    val key = "$lang\u0000${norm(title)}\u0000${norm(artist)}"
    withContext(Dispatchers.IO) { cache.get(key) }?.let { return it }
    val found = BackgroundLookup(backgroundGet(AppContainer.get(context).runtime.httpCalls), { MusicBrainzGate.awaitTurn() }, lang).find(title, artist)
    withContext(Dispatchers.IO) { cache.put(key, found) }
    return found
}
