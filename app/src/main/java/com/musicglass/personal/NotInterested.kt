package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.serialization.json.*

/** The "Not interested" item in a YouTube Music menu; its token tells YouTube Music to stop suggesting the item. */
internal fun notInterestedToken(menu: JsonElement?): String? = when (menu) {
    is JsonObject -> (menu["menuServiceItemRenderer"] as? JsonObject)?.let { item ->
        val token = catalogValue(item["serviceEndpoint"], "feedbackToken")
        val icon = catalogValue(item["icon"], "iconType")
        token?.takeIf { icon == "NOT_INTERESTED" || catalogText(item["text"]).contains("not interested", true) }
    } ?: menu.values.firstNotNullOfOrNull(::notInterestedToken)
    is JsonArray -> menu.firstNotNullOfOrNull(::notInterestedToken)
    else -> null
}

/** What YouTube Music was told when something was marked "Not interested". */
internal enum class NotInterestedSent { None, Feedback, Disliked }

/**
 * One hidden song or collection; [key] is its video or browse ID. [dislikedFor] is the account scope a dislike was
 * sent for (Riff's opaque key), so Undo removes it from that account only; it stays on this phone.
 */
internal data class NotInterestedEntry(val key: String, val title: String = "", val subtitle: String = "",
    val artworkUrl: String? = null, val song: Boolean = true, val at: Long = 0L, val dislikedFor: String? = null)

/** Entries are newest last; re-adding moves an entry to the end; the oldest are dropped past [limit]. */
internal fun addNotInterested(entries: List<NotInterestedEntry>, entry: NotInterestedEntry, limit: Int = 2000) =
    (entries.filterNot { it.key == entry.key } + entry).takeLast(limit)

internal fun encodeNotInterested(entries: List<NotInterestedEntry>) = buildJsonArray {
    entries.forEach { e -> addJsonObject { put("key", e.key); put("title", e.title.take(180)); put("subtitle", e.subtitle.take(180))
        put("artwork", e.artworkUrl ?: ""); put("song", e.song); put("at", e.at); e.dislikedFor?.let { put("disliked_for", it) } } }
}.toString()

internal fun decodeNotInterested(json: String?, legacyKeys: String?): List<NotInterestedEntry> {
    fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val parsed = runCatching {
        Json.parseToJsonElement(json ?: "[]").jsonArray.mapNotNull { item -> (item as? JsonObject)?.let { o ->
            NotInterestedEntry(o.text("key").takeIf(String::isNotBlank) ?: return@let null, o.text("title"), o.text("subtitle"),
                o.text("artwork").takeIf { it.startsWith("https://") }, (o["song"] as? JsonPrimitive)?.booleanOrNull ?: true,
                (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L, o.text("disliked_for").takeIf(String::isNotBlank))
        } }
    }.getOrDefault(emptyList())
    // Before the list in Settings, only the keys were saved.
    val legacy = legacyKeys.orEmpty().split('\n').filter(String::isNotBlank).filter { key -> parsed.none { it.key == key } }
        .map { NotInterestedEntry(it, song = it.matches(Regex("[A-Za-z0-9_-]{11}"))) }
    return legacy + parsed
}

/** Songs and collections the listener marked "Not interested". Hidden from Home and never added by autoplay. */
internal class NotInterestedStore internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("not_interested", Context.MODE_PRIVATE)
    var entries by mutableStateOf(load()); private set
    /** Reading it subscribes Compose to changes, like [entries]. */
    var keys by mutableStateOf(entries.mapTo(HashSet()) { it.key }.toSet()); private set
    private fun load() = decodeNotInterested(prefs.getString("entries", null), prefs.getString("keys", null))
    private fun save(next: List<NotInterestedEntry>) {
        entries = next; keys = next.mapTo(HashSet()) { it.key }; prefs.edit { putString("entries", encodeNotInterested(next)); remove("keys") }
    }
    operator fun contains(key: String?) = key != null && key in keys
    fun add(entry: NotInterestedEntry) = synchronized(this) { save(addNotInterested(load(), entry)) }
    fun remove(key: String) = synchronized(this) { save(load().filterNot { it.key == key }) }
    /** Records the account a dislike went to; nothing happens if the entry was undone meanwhile. */
    fun markDisliked(key: String, scope: String) = synchronized(this) {
        val now = load(); if (now.any { it.key == key }) save(now.map { if (it.key == key) it.copy(dislikedFor = scope) else it })
    }
    /**
     * Adds entries from an imported file. Entries already here are kept as they are and none is
     * dropped to make room. Returns how many were added, or null when nothing was stored.
     */
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref") // The result of commit() is reported.
    fun addAll(imported: List<NotInterestedEntry>, limit: Int = 2000): Int? = synchronized(this) {
        val kept = load(); val known = kept.mapTo(HashSet()) { it.key }
        val fresh = imported.filter { known.add(it.key) }.takeLast((limit - kept.size).coerceAtLeast(0))
        if (fresh.isEmpty()) return 0
        val next = (fresh + kept).sortedBy { it.at }
        if (!prefs.edit().putString("entries", encodeNotInterested(next)).remove("keys").commit()) return null
        entries = next; keys = next.mapTo(HashSet()) { it.key }
        fresh.size
    }
    companion object {
        @Volatile private var instance: NotInterestedStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: NotInterestedStore(context).also { instance = it } }
    }
}

internal fun List<ProviderHomeCard>.withoutNotInterested(store: NotInterestedStore) = filterNot { it.key() in store }
internal fun List<ProviderHomeSection>.withoutNotInterestedCards(store: NotInterestedStore) =
    map { it.copy(cards = it.cards.withoutNotInterested(store)) }.filter { it.cards.isNotEmpty() || it.title.contains("forgotten fav", true) }

/**
 * Marks a song or collection "Not interested": hidden locally, and a song is skipped and taken out of the queue.
 * Signed in, YouTube Music is told too: its own "Not interested" where Riff has the token, else a song is disliked.
 */
internal suspend fun markNotInterested(context: Context, provider: YouTubeMusicProvider, playback: PlaybackGateway,
    entry: NotInterestedEntry, token: String?, onNotice: (String) -> Unit) {
    val store = NotInterestedStore.get(context)
    store.add(entry.copy(at = System.currentTimeMillis(), dislikedFor = null))
    if (entry.song) playback.dropTrack(entry.key)
    val scope = provider.accountScopeKey()
    val sent = provider.sendNotInterested(entry.key.takeIf { entry.song }, token)
    if (sent.getOrNull() == NotInterestedSent.Disliked && scope != null) store.markDisliked(entry.key, scope)
    onNotice(notInterestedNotice(sent, entry.song))
}

internal fun notInterestedNotice(sent: Result<NotInterestedSent>, song: Boolean): String = when (sent.getOrNull()) {
    NotInterestedSent.Feedback -> string(R.string.got_it_youtube_music_will_suggest_this)
    NotInterestedSent.Disliked -> string(R.string.got_it_disliked_on_youtube_music_too)
    else -> if (sent.isFailure) string(R.string.hidden_in_riff_youtube_music_was_not_told)
        else if (song) string(R.string.got_it_riff_won_t_suggest_this) else string(R.string.got_it_riff_will_hide_this)
}

/**
 * Undo: shown again in Riff, and a dislike Riff sent is taken back, but only on the account it was sent for; another
 * account signed in now is never touched.
 */
internal suspend fun undoNotInterested(context: Context, provider: YouTubeMusicProvider, entry: NotInterestedEntry, onNotice: (String) -> Unit) {
    NotInterestedStore.get(context).remove(entry.key)
    val scope = entry.dislikedFor ?: return onNotice(string(R.string.riff_can_suggest_this_again))
    onNotice(when {
        provider.accountScopeKey() != scope -> string(R.string.riff_can_suggest_this_again_the_dislike_stays)
        provider.setSongLike(entry.key, false).isSuccess -> string(R.string.riff_and_youtube_music_can_suggest_this_again)
        else -> string(R.string.riff_can_suggest_this_again_youtube_music_kept_the_dislike)
    })
}
