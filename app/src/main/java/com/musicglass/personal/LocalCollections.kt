package com.musicglass.personal

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folds a verified alias of the same account into [to]: the target entries first, then the new ones
 * from the alias. The alias copy is removed only after the merged lists are stored.
 */
internal fun mergeCollectionScope(context: Context, from: String, to: String): Boolean {
    if (from == to) return false
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    val names = listOf("online_favorites" to 200, "online_playlist" to 200, "online_history" to 50)
    val merged = prefs.edit(); var found = false
    for ((name, limit) in names) {
        val source = prefs.getString("account_${from}_$name", null) ?: continue
        found = true
        val combined = JSONArray(); val seen = mutableSetOf<String>()
        for (text in listOf(prefs.getString("account_${to}_$name", "[]"), source)) {
            val array = runCatching { JSONArray(text) }.getOrNull() ?: continue
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (combined.length() < limit && seen.add(item.optString("id"))) combined.put(item)
            }
        }
        merged.putString("account_${to}_$name", combined.toString())
    }
    if (!found || !merged.commit()) return false
    prefs.edit { names.forEach { (name, _) -> remove("account_${from}_$name") } }
    return true
}

private val SCOPED_COLLECTIONS = listOf("online_favorites", "online_playlist", "online_history")
/** How many tracks each saved list holds at most. */
internal val COLLECTION_LIMITS = mapOf("online_favorites" to 200, "online_playlist" to 200, "online_history" to 50)

/** One saved list of [owner], as stored. For the export; nothing is bound or changed. */
internal fun storedCollection(context: Context, owner: String, name: String): List<ProviderTrack> = runCatching {
    val array = JSONArray(context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
        .getString("account_${owner}_$name", "[]"))
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id")
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@mapNotNull null
        ProviderTrack(id, item.optString("title"), item.optString("artist"), item.optString("artwork").takeIf { it.startsWith("https://") },
            isLive = item.optBoolean("live", false), isMusic = item.optBoolean("music", true))
    }
}.getOrDefault(emptyList())

/** What [addToCollections] did: tracks added to favorites and the playlist, added to recents, already there, and left out of a full list. */
internal data class CollectionImport(val saved: Int, val recents: Int, val present: Int, val full: Int)

/**
 * Adds tracks to saved lists, by owner and list name. What is already saved stays first and is never
 * removed or reordered; a track already in a list is not added twice; a full list takes no more.
 * One write for every owner: null means it was not stored and nothing changed.
 */
@android.annotation.SuppressLint("UseKtx") // The result of commit() is reported; the KTX form discards it.
internal fun addToCollections(context: Context, additions: Map<String, Map<String, List<ProviderTrack>>>): CollectionImport? {
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    val editor = prefs.edit(); var saved = 0; var recents = 0; var present = 0; var full = 0
    for ((owner, lists) in additions) for ((name, tracks) in lists) {
        val limit = COLLECTION_LIMITS[name] ?: continue
        if (owner == UNVERIFIED_SCOPE_KEY || tracks.isEmpty()) continue
        val key = "account_${owner}_$name"
        val kept = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrDefault(JSONArray())
        val seen = (0 until kept.length()).mapNotNullTo(mutableSetOf()) { kept.optJSONObject(it)?.optString("id") }
        val before = kept.length()
        for (track in tracks) when {
            !seen.add(track.videoId) -> present++
            kept.length() >= limit -> full++
            else -> { if (name == "online_history") recents++ else saved++; kept.put(JSONObject().put("id", track.videoId).put("title", track.title.take(180))
                .put("artist", track.artist.take(180)).put("artwork", track.artworkUrl).put("live", track.isLive).put("music", track.isMusic)) }
        }
        if (kept.length() != before) editor.putString(key, kept.toString())
    }
    return if (editor.commit()) CollectionImport(saved, recents, present, full) else null
}

private fun storedCount(prefs: android.content.SharedPreferences, key: String): Int =
    runCatching { JSONArray(prefs.getString(key, "[]")).length() }.getOrDefault(0)

/** Tracks the app itself saved for [owner] (favorites and the app playlist), not counting recents. */
internal fun savedCollectionCount(context: Context, owner: String): Int {
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    return storedCount(prefs, "account_${owner}_online_favorites") + storedCount(prefs, "account_${owner}_online_playlist")
}

/** Removes the recents list kept for [owner]. Null when the change could not be stored. */
@android.annotation.SuppressLint("UseKtx") // The result of commit() is reported; the KTX form discards it.
internal fun clearCollectionRecents(context: Context, owner: String): Int? {
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    val key = "account_${owner}_online_history"
    val count = storedCount(prefs, key)
    return if (prefs.edit().remove(key).commit()) count else null
}

/** Removes everything the app saved for [owner]: favorites, the app playlist and recents. Null when it could not be stored. */
internal fun clearCollectionScope(context: Context, owner: String): Int? {
    val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    val keys = SCOPED_COLLECTIONS.map { "account_${owner}_$it" }
    val count = keys.sumOf { storedCount(prefs, it) }
    val editor = prefs.edit(); keys.forEach(editor::remove)
    return if (editor.commit()) count else null
}

/** Account-scoped saves for real YouTube Music tracks. */
internal class LocalCollections(context: Context, initialAccountScope: String? = null) {
    private val prefs = context.applicationContext.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    private var accountScope: String? = initialAccountScope
    val canSave: Boolean get() = accountScope != null
    var historyEnabled by mutableStateOf(prefs.getBoolean("history_enabled", true))
        private set
    var onlineFavorites by mutableStateOf(readScopedProvider("online_favorites"))
        private set
    var onlinePlaylist by mutableStateOf(readScopedProvider("online_playlist"))
        private set
    var onlineHistory by mutableStateOf(readScopedProvider("online_history"))
        private set
    private fun accountKey(key: String): String? = accountScope?.let { "account_${it}_$key" }

    private fun readProvider(key: String): List<ProviderTrack> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@mapNotNull null
            ProviderTrack(id, item.optString("title"), item.optString("artist"),
                item.optString("artwork").takeIf { it.startsWith("https://") } ?: providerArtworkUrl(id),
                isLive = item.optBoolean("live", false), isMusic = item.optBoolean("music", true))
        }
    }.getOrDefault(emptyList())
    private fun readScopedProvider(key: String): List<ProviderTrack> =
        accountKey(key)?.let(::readProvider).orEmpty()

    private fun saveProvider(key: String, tracks: List<ProviderTrack>) {
        val scopedKey = accountKey(key) ?: return
        val array = JSONArray()
        tracks.take(200).forEach { track ->
            array.put(JSONObject().put("id", track.videoId)
                .put("title", track.title.take(180)).put("artist", track.artist.take(180)).put("artwork", track.artworkUrl)
                .put("live", track.isLive).put("music", track.isMusic))
        }
        prefs.edit().putString(scopedKey, array.toString()).apply()
    }

    fun bindAccountScope(scope: String?) {
        if (accountScope == scope) return
        accountScope = scope
        onlineFavorites = readScopedProvider("online_favorites")
        onlinePlaylist = readScopedProvider("online_playlist")
        onlineHistory = readScopedProvider("online_history")
    }

    /** Old unscoped rows are deliberately not assigned to any signed-in account. */
    fun legacyOnlineCount(): Int = listOf("online_favorites", "online_playlist", "online_history")
        .sumOf { readProvider(it).size }

    fun importLegacyOnlineToCurrentAccount(): Boolean {
        if (accountScope == null) return false
        val legacyFavorites = readProvider("online_favorites")
        val legacyPlaylist = readProvider("online_playlist")
        val legacyHistory = readProvider("online_history")
        if (legacyFavorites.isEmpty() && legacyPlaylist.isEmpty() && legacyHistory.isEmpty())
            return false
        onlineFavorites = (onlineFavorites + legacyFavorites).distinctBy(ProviderTrack::videoId)
        onlinePlaylist = (onlinePlaylist + legacyPlaylist).distinctBy(ProviderTrack::videoId)
        onlineHistory = (onlineHistory + legacyHistory).distinctBy(ProviderTrack::videoId).take(50)
        saveProvider("online_favorites", onlineFavorites)
        saveProvider("online_playlist", onlinePlaylist)
        saveProvider("online_history", onlineHistory)
        prefs.edit().remove("online_favorites").remove("online_playlist")
            .remove("online_history").apply()
        return true
    }

    fun refresh() {
        historyEnabled = prefs.getBoolean("history_enabled", true)
        onlineFavorites = readScopedProvider("online_favorites")
        onlinePlaylist = readScopedProvider("online_playlist")
        onlineHistory = readScopedProvider("online_history")
    }
    fun updateHistoryPreference(enabled: Boolean) {
        historyEnabled = enabled
        prefs.edit().putBoolean("history_enabled", enabled).apply()
    }
    fun setOnlineFavorite(track: ProviderTrack, saved: Boolean) {
        if(accountScope==null) return
        onlineFavorites=if(saved) (onlineFavorites+track).distinctBy(ProviderTrack::videoId) else onlineFavorites.filterNot {it.videoId==track.videoId}
        saveProvider("online_favorites",onlineFavorites)
    }
    fun recordOnlinePlayed(track: ProviderTrack) {
        if (accountScope == null || !historyEnabled) return
        onlineHistory = (listOf(track) + onlineHistory.filterNot { it.videoId == track.videoId }).take(50)
        saveProvider("online_history", onlineHistory)
    }
}
