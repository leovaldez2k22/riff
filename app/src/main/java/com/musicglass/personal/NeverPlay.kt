package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.serialization.json.*

/**
 * Feature 5 (owner, Q5): songs and artists never to play, kept on this phone for each account (and the guest)
 * apart. Matched only by stable ids: a song's video id, an artist's channel id. A song whose artist id is missing is
 * never taken for a namesake. Blocking a song from a menu also tells YouTube Music (neverPlayEverywhere, owner 9 November 2026).
 */
internal data class NeverPlayEntry(val key: String, val artist: Boolean, val name: String = "", val at: Long = 0)

private val SONG_KEY = Regex("[A-Za-z0-9_-]{11}")
private val ARTIST_KEY = Regex("UC[A-Za-z0-9_-]{10,40}")
internal const val NEVER_PLAY_LIMIT = 1000
/** How many blocked entries in a row are passed over before playback stops with a reason. */
internal const val NEVER_PLAY_SKIP_LIMIT = 50

internal fun neverPlayValid(entry: NeverPlayEntry) = (if (entry.artist) ARTIST_KEY else SONG_KEY).matches(entry.key)

internal fun encodeNeverPlay(entries: List<NeverPlayEntry>) = buildJsonArray { entries.forEach { e -> addJsonObject {
    put("key", e.key); put("artist", e.artist); put("name", e.name.take(180)); put("at", e.at) } } }.toString()

internal fun decodeNeverPlay(raw: String?): List<NeverPlayEntry> = runCatching {
    Json.parseToJsonElement(raw ?: "[]").jsonArray.mapNotNull { node -> (node as? JsonObject)?.let { o ->
        NeverPlayEntry((o["key"] as? JsonPrimitive)?.contentOrNull ?: return@let null, (o["artist"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(180), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0)
    } }.filter(::neverPlayValid).distinctBy { it.artist to it.key }.takeLast(NEVER_PLAY_LIMIT)
}.getOrDefault(emptyList())

/** The blocked ids of one owner, for quick checks. */
internal class NeverPlaySet(entries: List<NeverPlayEntry>) {
    private val songs = entries.filter { !it.artist }.mapTo(HashSet()) { it.key }
    private val artists = entries.filter { it.artist }.mapTo(HashSet()) { it.key }
    val isEmpty get() = songs.isEmpty() && artists.isEmpty()
    fun blocks(videoId: String?, artistId: String?): Boolean =
        (videoId != null && videoId in songs) || (artistId != null && artistId in artists)
}

/**
 * Where playback goes from a blocked entry at [from]: the next entry after it that is not blocked, or -1 when none
 * is within [NEVER_PLAY_SKIP_LIMIT] (the queue ends or everything left is blocked). [blocked] reads an index.
 */
internal fun neverPlayNext(from: Int, count: Int, blocked: (Int) -> Boolean): Int {
    var index = from + 1; var skipped = 0
    while (index < count && skipped < NEVER_PLAY_SKIP_LIMIT) { if (!blocked(index)) return index; index++; skipped++ }
    return -1
}

internal class NeverPlayStore internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("never_play", Context.MODE_PRIVATE)
    /** Raised on every change; reading it subscribes Compose. */
    var revision by mutableIntStateOf(0); private set
    private val cache = HashMap<String, List<NeverPlayEntry>>()
    private fun key(scope: String) = OWNER_PREFIX + scope
    /** Every owner with a list here, for the export file. */
    fun owners(): List<String> = prefs.all.keys.filter { it.startsWith(OWNER_PREFIX) }.map { it.removePrefix(OWNER_PREFIX) }
    fun entries(scope: String): List<NeverPlayEntry> = synchronized(this) { cache.getOrPut(scope) { decodeNeverPlay(prefs.getString(key(scope), null)) } }
    fun set(scope: String): NeverPlaySet = NeverPlaySet(entries(scope))
    fun contains(scope: String, key: String, artist: Boolean) = entries(scope).any { it.key == key && it.artist == artist }
    private fun save(scope: String, next: List<NeverPlayEntry>) = synchronized(this) {
        cache[scope] = next; prefs.edit { if (next.isEmpty()) remove(key(scope)) else putString(key(scope), encodeNeverPlay(next)) }
        revision++
    }
    fun add(scope: String, entry: NeverPlayEntry) {
        if (!neverPlayValid(entry)) return
        save(scope, (entries(scope).filterNot { it.key == entry.key && it.artist == entry.artist } + entry).takeLast(NEVER_PLAY_LIMIT))
    }
    fun remove(scope: String, key: String, artist: Boolean) = save(scope, entries(scope).filterNot { it.key == key && it.artist == artist })
    /** Delete local data for this account: the whole list of [scope]; how many entries went. */
    fun clear(scope: String): Int = synchronized(this) { entries(scope).size.also { if (it > 0 || prefs.contains(key(scope))) save(scope, emptyList()) } }
    /**
     * An import: adds what is not here yet, keeping what is, within [NEVER_PLAY_LIMIT]; one write for every owner.
     * Returns how many were added, or null when nothing could be stored.
     */
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref") // The result of commit() is reported.
    fun addAll(additions: Map<String, List<NeverPlayEntry>>): Int? = synchronized(this) {
        val editor = prefs.edit(); var added = 0; val next = HashMap<String, List<NeverPlayEntry>>()
        for ((scope, wanted) in additions) {
            if (scope == UNVERIFIED_SCOPE_KEY) continue
            val kept = entries(scope); val seen = kept.mapTo(HashSet()) { it.artist to it.key }
            val fresh = wanted.filter { neverPlayValid(it) && seen.add(it.artist to it.key) }.take((NEVER_PLAY_LIMIT - kept.size).coerceAtLeast(0))
            if (fresh.isEmpty()) continue
            next[scope] = kept + fresh; added += fresh.size; editor.putString(key(scope), encodeNeverPlay(kept + fresh))
        }
        if (next.isEmpty()) return 0
        if (!editor.commit()) return null
        cache.putAll(next); revision++; added
    }
    companion object {
        private const val OWNER_PREFIX = "owner_"
        @Volatile private var instance: NeverPlayStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: NeverPlayStore(context).also { instance = it } }
    }
}

/**
 * Adds or removes a song or artist for the current account. Adding the song or artist playing now skips it at once
 * ("skip now", Q5); the queue's own check passes over the rest.
 */
internal fun toggleNeverPlay(context: Context, playback: PlaybackGateway, entry: NeverPlayEntry, onNotice: (String) -> Unit) {
    val store = NeverPlayStore.get(context)
    val scope = OfflineMusicStore.get(context).currentScope
    if (store.contains(scope, entry.key, entry.artist)) {
        store.remove(scope, entry.key, entry.artist); uiHaptic(HapticCue.Confirm)
        onNotice(string(R.string.never_play_removed, entry.name))
        return
    }
    store.add(scope, entry.copy(at = System.currentTimeMillis())); uiHaptic(HapticCue.Confirm)
    val playing = playback.state
    val current = if (entry.artist) playing.artistId == entry.key else playing.mediaId == "yt:${entry.key}"
    if (current && playing.queue.size > 1) playback.next() else if (current) playback.pause()
    onNotice(string(if (entry.artist) R.string.never_play_artist_added else R.string.never_play_song_added, entry.name))
}

/** What the player's menu offers to block: the song playing, and its artist when the artist's channel is known. */
internal fun nowPlayingNeverPlay(context: Context, state: PlaybackUiState): List<NeverPlayEntry> {
    if (state.live || !state.mediaId.startsWith("yt:")) return emptyList()
    return listOfNotNull(NeverPlayEntry(state.mediaId.removePrefix("yt:"), false, state.title),
        state.artistId?.let { NeverPlayEntry(it, true, state.artist) }).filter(::neverPlayValid)
}

/** The menu item's label for [entry]: add it, or take it off the list. */
internal fun neverPlayLabel(context: Context, entry: NeverPlayEntry): String =
    if (NeverPlayStore.get(context).contains(OfflineMusicStore.get(context).currentScope, entry.key, entry.artist))
        if (entry.artist) "Play this artist again" else "Play this song again"
    else if (entry.artist) "Never play this artist" else "Never play this song"

/** Settings → Library → Not interested: the never-play list of the current account, each with Undo. */
internal fun LazyListScope.neverPlayItems(entries: List<NeverPlayEntry>, onRemove: (NeverPlayEntry) -> Unit) {
    item { Text(string(R.string.never_play_heading), color = White, fontSize = RiffType.heading, modifier = Modifier.padding(top = RiffSpace.section).testTag("never_play_heading")) }
    item { Text(string(R.string.never_play_explained), color = Muted, fontSize = RiffType.detail) }
    if (entries.isEmpty()) item { Text(string(R.string.never_play_empty), color = Muted) }
    items(entries.asReversed(), key = { "never:${it.artist}:${it.key}" }) { entry ->
        Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.compactRow).testTag("never_play_row"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name.ifBlank { entry.key }, color = White, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(string(if (entry.artist) R.string.artist else R.string.song), color = Muted, fontSize = RiffType.detail)
            }
            TextButton({ uiHaptic(HapticCue.Confirm); onRemove(entry) }, modifier = Modifier.testTag("never_play_undo")) { Text(string(R.string.undo), color = Pink) }
        }
    }
}
