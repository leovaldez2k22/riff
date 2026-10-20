package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.serialization.json.*

/** As many pins as speed dial has tiles. */
internal const val SPEED_DIAL_PIN_LIMIT = SPEED_DIAL_SIZE

/** Only what opening the card needs; tokens and queue ids from the feed are not kept. */
internal fun encodeSpeedDialPins(cards: List<ProviderHomeCard>) = buildJsonArray {
    cards.forEach { c -> addJsonObject {
        put("title", c.title.take(180)); put("subtitle", c.subtitle.take(180)); put("artwork", c.artworkUrl ?: "")
        put("videoId", c.videoId ?: ""); put("browseId", c.browseId ?: ""); put("kind", c.kind.name)
        put("artistId", c.artistId ?: ""); put("albumId", c.albumId ?: ""); put("album", c.album.take(180))
    } }
}.toString()

internal fun decodeSpeedDialPins(json: String?): List<ProviderHomeCard> = runCatching {
    Json.parseToJsonElement(json ?: "[]").jsonArray.mapNotNull { item -> (item as? JsonObject)?.let { o ->
        fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val videoId = text("videoId").ifBlank { null }; val browseId = text("browseId").ifBlank { null }
        if (videoId == null && browseId == null) return@let null
        ProviderHomeCard(text("title"), text("subtitle"), text("artwork").takeIf { it.startsWith("https://") }, videoId, browseId,
            enumValues<CatalogKind>().firstOrNull { it.name == text("kind") } ?: CatalogKind.Song,
            text("artistId").ifBlank { null }, text("albumId").ifBlank { null }, text("album"))
    } }
}.getOrDefault(emptyList()).distinctBy { it.key() }.take(SPEED_DIAL_PIN_LIMIT)

/** A card can be pinned when it can be opened again later: a song or episode by its video, anything else by its page. */
internal fun ProviderHomeCard.pinnable() = videoId != null || browseId != null

/** Songs, episodes and collections pinned to speed dial. Kept on this phone only, first pinned first. */
internal class SpeedDialPins internal constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("speed_dial_pins", Context.MODE_PRIVATE)
    var cards by mutableStateOf(decodeSpeedDialPins(prefs.getString("pins", null))); private set
    operator fun contains(key: String?) = key != null && cards.any { it.key() == key }
    /** False when every tile is already pinned. */
    fun pin(card: ProviderHomeCard): Boolean = synchronized(this) {
        if (card.key() in this) return true
        if (cards.size >= SPEED_DIAL_PIN_LIMIT || !card.pinnable()) return false
        save(cards + card.copy(notInterestedToken = null, setVideoId = null, addedAt = null)); true
    }
    fun unpin(key: String) = synchronized(this) { save(cards.filterNot { it.key() == key }) }
    private fun save(next: List<ProviderHomeCard>) {
        cards = next; prefs.edit { putString("pins", encodeSpeedDialPins(next)) }
    }
    companion object {
        @Volatile private var instance: SpeedDialPins? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: SpeedDialPins(context).also { instance = it } }
    }
}

/** Pins or unpins [card] and says so. */
internal fun toggleSpeedDialPin(context: Context, card: ProviderHomeCard, onNotice: (String) -> Unit) {
    val pins = SpeedDialPins.get(context)
    if (card.key() in pins) { pins.unpin(card.key()); onNotice(string(R.string.removed_from_speed_dial)) }
    else if (pins.pin(card)) { uiHaptic(HapticCue.Confirm); onNotice(string(R.string.pinned_to_speed_dial)) }
    else { uiHaptic(HapticCue.Reject); onNotice(string(R.string.speed_dial_is_full)) }
}
