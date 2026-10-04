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

/** Keys (video or browse IDs) are newest last; the oldest are dropped past [limit]. */
internal fun addNotInterested(keys: List<String>, key: String, limit: Int = 2000) = (keys - key + key).takeLast(limit)

/** Songs and collections the listener marked "Not interested". Hidden from Home and never added by autoplay. */
internal class NotInterestedStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("not_interested", Context.MODE_PRIVATE)
    var keys by mutableStateOf(load()); private set
    private fun load() = prefs.getString("keys", "").orEmpty().split('\n').filter(String::isNotBlank)
    operator fun contains(key: String?) = key != null && key in keys
    fun add(key: String) = synchronized(this) { keys = addNotInterested(load(), key); prefs.edit {putString("keys", keys.joinToString("\n"))} }
    fun remove(key: String) = synchronized(this) { keys = load() - key; prefs.edit {putString("keys", keys.joinToString("\n"))} }
    companion object {
        @Volatile private var instance: NotInterestedStore? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: NotInterestedStore(context).also { instance = it } }
    }
}

internal fun List<ProviderHomeCard>.withoutNotInterested(store: NotInterestedStore) = filterNot { it.key() in store }
internal fun List<ProviderHomeSection>.withoutNotInterestedCards(store: NotInterestedStore) =
    map { it.copy(cards = it.cards.withoutNotInterested(store)) }.filter { it.cards.isNotEmpty() || it.title.contains("forgotten fav", true) }

/**
 * Marks a song or collection "Not interested": hidden locally, sent to YouTube Music when signed in,
 * and a song is skipped and taken out of the queue.
 */
internal suspend fun markNotInterested(context: Context, provider: YouTubeMusicProvider, playback: PlaybackGateway,
    key: String, token: String?, song: Boolean, onNotice: (String) -> Unit) {
    NotInterestedStore.get(context).add(key)
    if (song) playback.dropTrack(key)
    val sent = provider.sendNotInterested(key.takeIf { song }, token).getOrDefault(false)
    onNotice(if (sent) "Got it. YouTube Music will suggest this less"
        else if (song) "Got it. Riff won't suggest this song" else "Got it. Riff will hide this")
}
