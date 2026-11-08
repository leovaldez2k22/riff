package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/**
 * Feature 12 (owner, Q12): playlists brought in from a file (a CSV such as a Spotify export, an M3U or M3U8, or the
 * playlist file of Spotify's own account-data download), kept as named playlists on this phone for each account.
 * A file is only read: no address in it is fetched and no path in it is opened. YouTube links are taken as they are;
 * every other row is looked up with a YouTube Music song search, one at a time. Copying a playlist to the YouTube
 * Music account is a separate step that runs only when confirmed, each time.
 */
internal const val IMPORT_MAX_BYTES = 2_000_000
internal const val IMPORT_MAX_ROWS = 5_000
internal const val PHONE_PLAYLISTS_LIMIT = 200
private const val IMPORT_FIELD = 300

/** One row of an imported file, at [position] (from 1) in its list; repeats are kept as separate rows. */
internal data class ImportRow(val position: Int, val title: String, val artist: String = "", val album: String = "",
    val durationMs: Long? = null, val videoId: String? = null)

/** One list read from a file. [unreadable] counts rows that had neither a title nor a YouTube link. */
internal data class ImportedList(val name: String, val rows: List<ImportRow>, val unreadable: Int = 0, val cut: Boolean = false)

private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

/**
 * The video id in a YouTube or YouTube Music link; with [bare], also an id on its own (only in a column named for
 * links: a title can be eleven letters too). Null for anything else. Nothing is fetched.
 */
internal fun importVideoId(text: String, bare: Boolean = false): String? {
    val value = text.trim()
    if (VIDEO_ID.matches(value)) return value.takeIf { bare }
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("https", "http")) return null
    val host = uri.host?.lowercase() ?: return null
    if (host !in YOUTUBE_HOSTS) return null
    val id = if (host == "youtu.be") uri.path.orEmpty().trim('/').substringBefore('/')
        else uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.removePrefix("v=")
            ?: uri.path.orEmpty().split('/').let { parts -> parts.getOrNull(parts.indexOf("shorts").takeIf { it >= 0 }?.plus(1) ?: -1) }
    return id?.takeIf(VIDEO_ID::matches)
}

private fun field(text: String?) = text.orEmpty().filter { it >= ' ' }.trim().take(IMPORT_FIELD)

/** "3:25", "1:02:03", seconds or milliseconds; null when it is none of these. */
internal fun importDuration(text: String): Long? {
    val value = text.trim()
    if (value.isEmpty()) return null
    if (':' in value) {
        val parts = value.split(':').map { it.toLongOrNull() ?: return null }
        if (parts.size > 3 || parts.drop(1).any { it !in 0..59 }) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1000
    }
    val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
    // Spotify's exports give milliseconds; a plain count of seconds is never this large for a song.
    return if (number >= 10_000) number.toLong() else (number * 1000).toLong()
}

/** RFC 4180 rows: quoted fields, doubled quotes, line breaks inside quotes; the delimiter is the first line's commonest. */
internal fun csvRows(text: String): List<List<String>> {
    val first = text.lineSequence().firstOrNull().orEmpty()
    val delimiter = listOf(',', ';', '\t').maxBy { d -> first.count { it == d } }
    val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cell = StringBuilder()
    var quoted = false; var i = 0
    while (i < text.length && rows.size <= IMPORT_MAX_ROWS + 1) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
            !quoted && c == delimiter -> { row.add(cell.toString()); cell.clear() }
            !quoted && (c == '\n' || c == '\r') -> {
                if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                row.add(cell.toString()); cell.clear()
                if (row.any { it.isNotBlank() }) rows.add(row)
                row = ArrayList()
            }
            else -> if (cell.length < 4 * IMPORT_FIELD) cell.append(c)
        }
        i++
    }
    row.add(cell.toString()); if (row.any { it.isNotBlank() }) rows.add(row)
    return rows
}

private fun header(cell: String) = cell.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z()]+"), " ").trim()
private val TITLE_HEADERS = setOf("track name", "title", "name", "song", "track", "song name", "track title")
private val ARTIST_HEADERS = setOf("artist name(s)", "artist name", "artist", "artists", "artist names", "creator", "album artist name(s)")
private val ALBUM_HEADERS = setOf("album name", "album", "album title")
private val DURATION_HEADERS = setOf("duration (ms)", "duration ms", "duration", "length", "time", "track duration (ms)")
private val LINK_HEADERS = setOf("url", "link", "youtube url", "youtube link", "video id", "videoid", "youtube id", "track uri", "spotify uri", "uri")

/** A CSV with a header row naming its columns; without one, the columns are title, artist and album. */
internal fun readCsvImport(name: String, text: String): ImportedList {
    val rows = csvRows(text)
    val head = rows.firstOrNull()?.map(::header).orEmpty()
    fun column(names: Set<String>) = head.indexOfFirst { it in names }
    val title = column(TITLE_HEADERS); val artist = column(ARTIST_HEADERS)
    val named = title >= 0 || column(LINK_HEADERS) >= 0
    val t = if (named) title else 0
    val a = if (named) artist else 1
    val al = if (named) column(ALBUM_HEADERS) else 2
    val d = if (named) column(DURATION_HEADERS) else -1
    val l = if (named) column(LINK_HEADERS) else -1
    val body = if (named) rows.drop(1) else rows
    var unreadable = 0
    val out = body.take(IMPORT_MAX_ROWS).mapIndexedNotNull { index, cells ->
        fun at(i: Int) = if (i >= 0) field(cells.getOrNull(i)) else ""
        val link = importVideoId(at(l), bare = true) ?: cells.firstNotNullOfOrNull { importVideoId(it) }
        val row = ImportRow(index + 1, at(t), at(a), at(al), at(d).let(::importDuration), link)
        if (row.title.isBlank() && row.videoId == null) { unreadable++; null } else row
    }
    return ImportedList(name, out, unreadable, body.size > IMPORT_MAX_ROWS)
}

/** M3U or M3U8: `#EXTINF:seconds,Artist - Title` before each entry; `#PLAYLIST:` names it. Paths are never opened. */
internal fun readM3uImport(name: String, text: String): ImportedList {
    var listName = name; var info: Pair<Long?, String>? = null; var unreadable = 0; var cut = false
    val rows = ArrayList<ImportRow>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#PLAYLIST:", ignoreCase = true) -> listName = field(line.substringAfter(':')).ifBlank { listName }
            line.startsWith("#EXTINF:", ignoreCase = true) -> {
                val body = line.substringAfter(':')
                val seconds = body.substringBefore(',').substringBefore(' ').toLongOrNull()?.takeIf { it > 0 }
                info = seconds?.times(1000) to body.substringAfter(',', "")
            }
            line.startsWith("#") -> Unit
            else -> {
                if (rows.size >= IMPORT_MAX_ROWS) { cut = true; break }
                val id = importVideoId(line)
                // Without EXTINF a file's own name is all there is: "Artist - Title.mp3".
                val label = info?.second?.takeIf(String::isNotBlank)
                    ?: if (id == null) line.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') else ""
                val artist = label.substringBefore(" - ", "").trim()
                val title = if (artist.isEmpty()) label else label.substringAfter(" - ")
                if (field(title).isBlank() && id == null) unreadable++
                else rows.add(ImportRow(rows.size + 1, field(title), field(artist), durationMs = info?.first, videoId = id))
                info = null
            }
        }
    }
    return ImportedList(listName, rows, unreadable, cut)
}

/** The playlist file of Spotify's account-data download: `{"playlists":[{"name","items":[{"track":{...}}]}]}`. */
internal fun readSpotifyJsonImport(root: JsonElement): List<ImportedList> {
    val lists = ((root as? JsonObject)?.get("playlists") as? JsonArray) ?: return emptyList()
    return lists.take(PHONE_PLAYLISTS_LIMIT).mapNotNull { node ->
        val list = node as? JsonObject ?: return@mapNotNull null
        val items = list["items"] as? JsonArray ?: return@mapNotNull null
        var unreadable = 0
        val rows = items.take(IMPORT_MAX_ROWS).mapNotNull { item ->
            val track = ((item as? JsonObject)?.get("track") as? JsonObject)
            fun text(key: String) = field((track?.get(key) as? JsonPrimitive)?.contentOrNull)
            val title = text("trackName")
            if (title.isBlank()) { unreadable++; null } else ImportRow(0, title, text("artistName"), text("albumName"))
        }.mapIndexed { i, row -> row.copy(position = i + 1) }
        ImportedList(field((list["name"] as? JsonPrimitive)?.contentOrNull).ifBlank { "Spotify" }, rows, unreadable, items.size > IMPORT_MAX_ROWS)
    }.filter { it.rows.isNotEmpty() }
}

/** Reads a picked file by what it holds; an empty list when it is none of the kinds above or holds nothing. */
internal fun readImportFile(fileName: String, bytes: ByteArray): List<ImportedList> {
    if (bytes.size > IMPORT_MAX_BYTES) return emptyList()
    val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
    val name = field(fileName.substringBeforeLast('.')).ifBlank { "Imported" }
    val lower = fileName.lowercase(Locale.ROOT)
    val trimmed = text.trimStart()
    val lists = when {
        trimmed.startsWith("{") -> runCatching { readSpotifyJsonImport(Json.parseToJsonElement(text)) }.getOrDefault(emptyList())
        trimmed.startsWith("#EXTM3U", ignoreCase = true) || lower.endsWith(".m3u") || lower.endsWith(".m3u8") -> listOf(readM3uImport(name, text))
        else -> listOf(readCsvImport(name, text))
    }
    return lists.filter { it.rows.isNotEmpty() }
}

// ---- Matching ----

internal enum class ImportStatus { Matched, Ambiguous, Missing }

/** A row and what was found for it: [chosen] is what will be saved (null skips it); [options] the closest songs. */
internal data class ImportMatch(val row: ImportRow, val status: ImportStatus, val chosen: ProviderTrack?, val options: List<ProviderTrack> = emptyList(),
    val failed: Boolean = false)

/** A search answer: a song and its stated length, when YouTube Music shows one. */
internal data class ImportCandidate(val track: ProviderTrack, val durationMs: Long?)

private val MARKS = Regex("\\p{M}+")
private val BRACKETS = Regex("[(\\[][^)\\]]*[)\\]]")
private val FEATURING = Regex("\\s(feat\\.?|ft\\.?|featuring|with)\\s.*$")
private val VERSION_WORDS = setOf("live", "remix", "acoustic", "instrumental", "karaoke", "cover", "sped", "slowed", "demo", "edit", "remaster", "remastered", "version", "mix", "reprise", "unplugged")

private fun fold(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)
private fun words(text: String) = fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotEmpty)
/** A title without what is in brackets, after " - " or after "feat.". */
private fun baseTitle(text: String) = fold(text).replace(BRACKETS, " ").substringBefore(" - ").replace(FEATURING, "").trim()
private fun versions(text: String) = words(text).filterTo(HashSet()) { it in VERSION_WORDS }

private fun overlap(a: List<String>, b: List<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val common = a.toSet().intersect(b.toSet()).size
    return common.toDouble() / maxOf(a.toSet().size, b.toSet().size)
}

/** 0–1: how well [candidate] fits [row] by title, artist, length and version (live, remix and the like). */
internal fun importScore(row: ImportRow, candidate: ImportCandidate): Double {
    val title = overlap(words(baseTitle(row.title)), words(baseTitle(candidate.track.title)))
    val rowArtists = row.artist.split(Regex(",|&|;| x | and ")).map { words(it) }.filter { it.isNotEmpty() }
    val artistWords = words(candidate.track.artist)
    val artist = if (rowArtists.isEmpty()) .5 else rowArtists.maxOf { overlap(it, artistWords).let { o -> if (artistWords.containsAll(it)) 1.0 else o } }
    var score = .6 * title + .4 * artist
    val (a, b) = row.durationMs to candidate.durationMs
    if (a != null && b != null) { val gap = kotlin.math.abs(a - b); if (gap <= 3_000) score += .05 else if (gap > 15_000) score -= .25 }
    if (versions(row.title) != versions(candidate.track.title)) score -= .2
    return score.coerceIn(0.0, 1.0)
}

internal const val IMPORT_STRONG = .85
internal const val IMPORT_POSSIBLE = .5

/** What the closest search answers mean for [row]: one clear song, a few to choose from, or none. */
internal fun classifyImport(row: ImportRow, found: List<ImportCandidate>): ImportMatch {
    val ranked = found.distinctBy { it.track.videoId }.map { it to importScore(row, it) }.sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return ImportMatch(row, ImportStatus.Missing, null)
    val next = ranked.getOrNull(1)?.second ?: 0.0
    val options = ranked.filter { it.second >= IMPORT_POSSIBLE }.take(3).map { it.first.track }
    return when {
        best.second >= IMPORT_STRONG && best.second - next >= .1 -> ImportMatch(row, ImportStatus.Matched, best.first.track, options)
        best.second >= IMPORT_POSSIBLE -> ImportMatch(row, ImportStatus.Ambiguous, null, options)
        else -> ImportMatch(row, ImportStatus.Missing, null)
    }
}

/**
 * Matches every row, in order and one search at a time ([pauseMs] apart). A row with a YouTube link is taken as it
 * is; a repeated row is searched once. A search that fails marks its row [ImportMatch.failed], to try again later.
 */
internal suspend fun matchImport(rows: List<ImportRow>, search: suspend (String) -> Result<List<ImportCandidate>>,
    pauseMs: Long = 350, progress: (Int) -> Unit = {}): List<ImportMatch> {
    val answers = HashMap<String, Result<List<ImportCandidate>>>()
    return rows.mapIndexed { index, row ->
        progress(index)
        row.videoId?.let { id -> return@mapIndexed ImportMatch(row, ImportStatus.Matched, ProviderTrack(id, row.title.ifBlank { id }, row.artist)) }
        val query = listOf(baseTitle(row.title).ifBlank { row.title }, row.artist.substringBefore(',')).filter(String::isNotBlank).joinToString(" ")
        val key = fold(query)
        val answer = answers[key] ?: run {
            if (answers.isNotEmpty()) delay(pauseMs)
            runCatching { search(query) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; Result.failure(it) }
                .also { answers[key] = it }
        }
        answer.fold(onSuccess = { classifyImport(row, it) }, onFailure = { ImportMatch(row, ImportStatus.Missing, null, failed = true) })
    }
}

/** YouTube Music's song search, as [matchImport] asks it. */
internal fun providerImportSearch(reader: ProviderCatalogReader): suspend (String) -> Result<List<ImportCandidate>> = { query ->
    reader.discover(query, null, "Songs").map { page -> page.cards.take(8).mapNotNull { card ->
        card.track()?.takeIf { !it.isLive && it.isMusic }?.let { ImportCandidate(it.copy(artist = playerArtistLabel(it.artist)), card.durationMs) } } }
}

// ---- Playlists on this phone ----

/** One song in a phone playlist; [occurrence] tells two rows of one song apart. */
internal data class PhonePlaylistSong(val occurrence: String, val track: ProviderTrack)
/**
 * A playlist kept on this phone. [remoteId] is the YouTube Music playlist it was copied to, once made, and [copied]
 * the occurrences known to be there, so a copy that stopped carries on without making a second playlist.
 */
internal data class PhonePlaylist(val id: String, val name: String, val createdAt: Long, val songs: List<PhonePlaylistSong>,
    val remoteId: String? = null, val copied: Set<String> = emptySet())

internal fun encodePhonePlaylists(lists: List<PhonePlaylist>) = buildJsonArray { lists.forEach { p -> addJsonObject {
    put("id", p.id); put("name", p.name); put("at", p.createdAt); p.remoteId?.let { put("remote", it) }
    putJsonArray("copied") { p.copied.forEach { add(it) } }
    putJsonArray("songs") { p.songs.forEach { s -> addJsonObject {
        put("o", s.occurrence); put("id", s.track.videoId); put("t", s.track.title); put("a", s.track.artist)
        s.track.artistId?.let { put("ai", it) } } } }
} } }.toString()

internal fun decodePhonePlaylists(raw: String?): List<PhonePlaylist> = runCatching {
    Json.parseToJsonElement(raw ?: "[]").jsonArray.mapNotNull { node -> (node as? JsonObject)?.let { o ->
        fun s(key: String, from: JsonObject = o) = (from[key] as? JsonPrimitive)?.contentOrNull
        val songs = (o["songs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { e ->
            val id = s("id", e)?.takeIf(VIDEO_ID::matches) ?: return@let null
            PhonePlaylistSong(s("o", e) ?: return@let null, ProviderTrack(id, field(s("t", e)), field(s("a", e)), artistId = s("ai", e)))
        } }.distinctBy { it.occurrence }.take(IMPORT_MAX_ROWS)
        PhonePlaylist(s("id") ?: return@let null, field(s("name")).ifBlank { "Playlist" }, (o["at"] as? JsonPrimitive)?.longOrNull ?: 0, songs,
            s("remote")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{2,200}")) },
            (o["copied"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { (it as? JsonPrimitive)?.contentOrNull })
    } }.distinctBy { it.id }.take(PHONE_PLAYLISTS_LIMIT)
}.getOrDefault(emptyList())

/** The phone's playlists, one list for each account and one for the guest; never sent anywhere unless copied. */
internal class PhonePlaylistStore internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phone_playlists", Context.MODE_PRIVATE)
    var revision by mutableIntStateOf(0); private set
    private fun key(scope: String) = OWNER_PREFIX + scope
    /** Every owner with playlists here, for the export file. */
    fun owners(): List<String> = prefs.all.keys.filter { it.startsWith(OWNER_PREFIX) }.map { it.removePrefix(OWNER_PREFIX) }
    @Synchronized fun lists(scope: String): List<PhonePlaylist> = decodePhonePlaylists(prefs.getString(key(scope), null))
    @Synchronized private fun write(scope: String, lists: List<PhonePlaylist>) {
        prefs.edit(commit = true) { if (lists.isEmpty()) remove(key(scope)) else putString(key(scope), encodePhonePlaylists(lists)) }
        revision++
    }
    /** A new playlist of [tracks] in order; null when this account already has [PHONE_PLAYLISTS_LIMIT]. */
    @Synchronized fun create(scope: String, name: String, tracks: List<ProviderTrack>, now: Long = System.currentTimeMillis()): PhonePlaylist? {
        if (scope == UNVERIFIED_SCOPE_KEY) return null
        val lists = lists(scope); if (lists.size >= PHONE_PLAYLISTS_LIMIT) return null
        val list = PhonePlaylist(UUID.randomUUID().toString(), field(name).ifBlank { "Imported" }, now,
            tracks.take(IMPORT_MAX_ROWS).map { PhonePlaylistSong(UUID.randomUUID().toString(), it) })
        write(scope, lists + list); return list
    }
    @Synchronized fun update(scope: String, id: String, change: (PhonePlaylist) -> PhonePlaylist) =
        write(scope, lists(scope).map { if (it.id == id) change(it) else it })
    @Synchronized fun delete(scope: String, id: String) = write(scope, lists(scope).filterNot { it.id == id })
    /** Delete local data for this account: every playlist of [scope]; how many went. A copy on YouTube Music stays. */
    @Synchronized fun clear(scope: String): Int = lists(scope).size.also { if (it > 0 || prefs.contains(key(scope))) write(scope, emptyList()) }

    /** What an import of playlists did: added, already here (the same playlist), or over [PHONE_PLAYLISTS_LIMIT]. */
    data class Imported(val added: Int, val present: Int, val full: Int)
    /**
     * An import: each playlist not here yet (by its id) is added after what is kept, one write for every owner. A
     * playlist arrives without its YouTube Music copy: that belongs to the account it was copied from. Null when
     * nothing could be stored.
     */
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref") // The result of commit() is reported.
    @Synchronized fun importAll(additions: Map<String, List<PhonePlaylist>>): Imported? {
        val editor = prefs.edit(); var added = 0; var present = 0; var full = 0; var changed = false
        for ((scope, wanted) in additions) {
            if (scope == UNVERIFIED_SCOPE_KEY) continue
            val kept = lists(scope).toMutableList(); val ids = kept.mapTo(HashSet()) { it.id }
            for (list in wanted) when {
                !ids.add(list.id) -> present++
                kept.size >= PHONE_PLAYLISTS_LIMIT -> full++
                else -> { kept += list.copy(remoteId = null, copied = emptySet(), songs = list.songs.take(IMPORT_MAX_ROWS)); added++; changed = true }
            }
            editor.putString(key(scope), encodePhonePlaylists(kept))
        }
        if (!changed) return Imported(0, present, full)
        if (!editor.commit()) return null
        revision++; return Imported(added, present, full)
    }    companion object {
        private const val OWNER_PREFIX = "owner_"
        @Volatile private var instance: PhonePlaylistStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: PhonePlaylistStore(context).also { instance = it } }
    }
}

/** Where [copyPhonePlaylist] writes: the account's playlists in the app, fakes in tests. */
internal class PhoneCopyTarget(val create: suspend (String) -> Result<String>, val read: suspend (String) -> Result<List<String>>,
    val add: suspend (String, String) -> Result<Unit>)
internal fun providerCopyTarget(reader: ProviderCatalogReader, mutations: ProviderMutations) = PhoneCopyTarget(
    { mutations.createPlaylist(it) }, { id -> reader.completeCollection(id).mapCatching { page ->
        check(page.truncation == null) { string(R.string.phone_playlist_too_large) }; page.albumTracks.mapNotNull { it.videoId } } },
    { id, video -> mutations.addPlaylistSong(id, video) })

/** How a copy to YouTube Music ended: songs added now, songs already there, and the first failure, if any. */
internal data class PhoneCopyResult(val added: Int, val alreadyThere: Int, val error: String? = null)

/**
 * Copies [list] to the signed-in account, only when the user confirmed it ([PhonePlaylistsScreen] asks each time).
 * The YouTube Music playlist is made once and its id kept at once; each song is marked as it is added. When a
 * playlist was already made, it is read first: songs already there (as many times as the phone list has them) are
 * not added again, so a copy cut short, or one whose answer never came, carries on where it stopped.
 */
internal suspend fun copyPhonePlaylist(store: PhonePlaylistStore, scope: String, list: PhonePlaylist, remote: PhoneCopyTarget,
    still: () -> Boolean, progress: (Int) -> Unit = {}): PhoneCopyResult {
    var current = list
    if (!still()) return PhoneCopyResult(0, 0, string(R.string.account_changed))
    val made = current.remoteId ?: remote.create(current.name).getOrElse { return PhoneCopyResult(0, 0, it.message ?: "") }
        .also { id -> store.update(scope, list.id) { it.copy(remoteId = id) }; current = current.copy(remoteId = id) }
    var copied = current.copied
    if (list.remoteId != null) {
        // A copy was started before: what is on the account now decides what is left.
        val there = remote.read(made).getOrElse { return PhoneCopyResult(0, 0, it.message ?: "") }.groupingBy { it }.eachCount().toMutableMap()
        current.songs.filter { it.occurrence in copied }.forEach { s -> there[s.track.videoId] = (there[s.track.videoId] ?: 0) - 1 }
        val found = HashSet<String>()
        for (s in current.songs) if (s.occurrence !in copied && (there[s.track.videoId] ?: 0) > 0) {
            there[s.track.videoId] = there.getValue(s.track.videoId) - 1; found += s.occurrence }
        copied = copied + found
        store.update(scope, list.id) { it.copy(copied = copied) }
    }
    val before = copied.size; var added = 0
    for (song in current.songs) {
        if (song.occurrence in copied) continue
        if (!still()) return PhoneCopyResult(added, before, string(R.string.account_changed))
        val result = remote.add(made, song.track.videoId)
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
            return PhoneCopyResult(added, before, error?.message ?: string(R.string.could_not_update_playlist))
        }
        added++; copied = copied + song.occurrence
        store.update(scope, list.id) { it.copy(copied = copied) }
        progress(copied.size)
    }
    return PhoneCopyResult(added, before)
}
