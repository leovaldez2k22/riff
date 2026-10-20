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

private val PIN_VIDEO = Regex("[A-Za-z0-9_-]{11}")
private val PIN_PAGE = Regex("[A-Za-z0-9_-]{1,128}")
private val PIN_ID = Regex("[A-Za-z0-9_-]{1,64}")

/** A pin as a line of the local data file (LocalDataTransfer). Artwork only from the provider's image hosts. */
internal fun pinRecord(card: ProviderHomeCard): org.json.JSONObject = org.json.JSONObject().put("t", "pin")
    .put("title", card.title.take(180)).put("subtitle", card.subtitle.take(180)).put("kind", card.kind.name).also { o ->
        card.videoId?.let { o.put("id", it) }; card.browseId?.let { o.put("browse", it) }
        exportableArtwork(card.artworkUrl)?.let { o.put("artwork", it) }
        card.artistId?.takeIf(PIN_ID::matches)?.let { o.put("artistId", it) }
        card.albumId?.takeIf(PIN_ID::matches)?.let { o.put("albumId", it) }
        if (card.album.isNotBlank()) o.put("album", card.album.take(180))
    }

/** A pin read back from the file; every field is checked, nothing in the file is trusted. Null when it cannot be opened. */
internal fun parsePinRecord(o: org.json.JSONObject): ProviderHomeCard? {
    fun text(name: String, max: Int = 180) = (o.opt(name) as? String).orEmpty().take(max)
    val video = text("id", 32).takeIf(PIN_VIDEO::matches); val page = text("browse", 200).takeIf(PIN_PAGE::matches)
    if (video == null && page == null) return null
    return ProviderHomeCard(text("title"), text("subtitle"), exportableArtwork(o.opt("artwork") as? String), video, page,
        enumValues<CatalogKind>().firstOrNull { it.name == text("kind", 16) } ?: if (video != null) CatalogKind.Song else CatalogKind.Playlist,
        text("artistId", 64).takeIf(PIN_ID::matches), text("albumId", 64).takeIf(PIN_ID::matches), text("album"))
}

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
    /** What [addAll] did with an imported list. */
    data class Added(val added: Int, val present: Int, val full: Int)
    /**
     * Adds imported pins after the ones here, which are kept as they are; none is dropped to make room.
     * Null when nothing could be stored.
     */
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref") // The result of commit() is reported.
    fun addAll(imported: List<ProviderHomeCard>): Added? = synchronized(this) {
        val known = cards.mapTo(HashSet()) { it.key() }
        val fresh = imported.filter { it.pinnable() && known.add(it.key()) }
        val fits = fresh.take((SPEED_DIAL_PIN_LIMIT - cards.size).coerceAtLeast(0))
        val result = Added(fits.size, imported.size - fresh.size, fresh.size - fits.size)
        if (fits.isEmpty()) return result
        val next = cards + fits
        if (!prefs.edit().putString("pins", encodeSpeedDialPins(next)).commit()) return null
        cards = next
        result
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
