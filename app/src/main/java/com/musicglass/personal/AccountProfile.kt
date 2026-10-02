package com.musicglass.personal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

internal fun parseAccountProfile(root: JsonElement): ProviderProfile? {
    fun text(node: JsonElement?): String = when (node) {
        is JsonPrimitive -> node.content
        is JsonObject -> text(node["simpleText"]).ifBlank {
            (node["runs"] as? JsonArray)?.joinToString("") { text((it as? JsonObject)?.get("text")) }.orEmpty()
        }
        else -> ""
    }
    fun find(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> node[key] as? JsonObject ?: node.values.firstNotNullOfOrNull { find(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { find(it, key) }
        else -> null
    }
    fun selected(node: JsonElement): JsonObject? = when (node) {
        is JsonObject -> {
            val item = node["accountItemRenderer"] as? JsonObject
            if ((item?.get("isSelected") as? JsonPrimitive)?.booleanOrNull == true) item
            else node.values.firstNotNullOfOrNull(::selected)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::selected)
        else -> null
    }
    val header = find(root, "activeAccountHeaderRenderer") ?: find(root, "accountHeaderRenderer")
        ?: selected(root) ?: return null
    val name = listOf("accountName", "channelName", "displayName", "name")
        .firstNotNullOfOrNull { key -> text(header[key]).trim().takeIf { it.isNotBlank() && !it.contains('@') } }
        .orEmpty().take(80)
    val photo = listOf("accountPhoto", "channelAvatar", "avatar", "photo")
        .firstNotNullOfOrNull { providerThumbnail(header[it]) }
    return if (name.isBlank() && photo == null) null else ProviderProfile(name, photo)
}

/** Provider subtitles also contain album, duration and view counts. Player labels show artists only. */
internal fun playerArtistLabel(subtitle: String): String {
    val parts = subtitle.split(Regex("\\s*[\\u2022\\u00B7]\\s*")).map(String::trim).filter { it.isNotBlank() }
    val kinds = setOf("song", "video", "album", "ep", "single", "playlist", "podcast", "episode")
    return parts.firstOrNull {
        !it.equals("YouTube Music", ignoreCase = true) &&
            !it.matches(Regex("(?:\\d+:)?\\d{1,2}:\\d{2}")) &&
            !(parts.size > 1 && it.lowercase() in kinds)
    }.orEmpty()
}
