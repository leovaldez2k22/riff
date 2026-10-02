package com.musicglass.personal

import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun validProviderArtworkUrl(url: String?): String? {
    val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    val host = uri.host?.lowercase() ?: return null
    return url.takeIf { uri.scheme == "https" && uri.userInfo == null &&
        (host == "ytimg.com" || host.endsWith(".ytimg.com") || host == "ggpht.com" || host.endsWith(".ggpht.com") ||
        host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")) }
}

private data class ArtworkCandidate(val url: String, val pixels: Long)

/** Lists are not guaranteed to be ordered; choose the largest actual thumbnail. */
internal fun providerThumbnail(node: JsonElement?): String? {
    fun candidates(value: JsonElement?): List<ArtworkCandidate> = when (value) {
        is JsonObject -> {
            val url = runCatching { value["url"]?.jsonPrimitive?.content }.getOrNull()
            val uri = url?.let { runCatching { URI(it) }.getOrNull() }
            val host = uri?.host?.lowercase().orEmpty()
            val valid = uri?.scheme == "https" && uri.userInfo == null &&
                (host == "ytimg.com" || host.endsWith(".ytimg.com") ||
                    host == "ggpht.com" || host.endsWith(".ggpht.com") ||
                    host == "googleusercontent.com" || host.endsWith(".googleusercontent.com"))
            if (valid && url != null) {
                fun size(key: String) = runCatching {
                    value[key]?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull()?.coerceIn(1, 16_384) ?: 1L
                listOf(ArtworkCandidate(url, size("width") * size("height")))
            } else value.values.flatMap(::candidates)
        }
        is JsonArray -> value.flatMap(::candidates)
        else -> emptyList()
    }
    return candidates(node).maxByOrNull(ArtworkCandidate::pixels)?.url
}

/** Upgrade tiny CDN renditions before Coil chooses cache keys, including previously saved URLs. */
internal fun highQualityArtworkUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    if (uri.scheme != "https" || uri.userInfo != null) return url
    val host = uri.host?.lowercase() ?: return url
    if (host == "googleusercontent.com" || host.endsWith(".googleusercontent.com") ||
        host == "ggpht.com" || host.endsWith(".ggpht.com")) {
        val dimensions = Regex("=w(\\d+)-h(\\d+)")
        val match = dimensions.find(url)
        if (match != null) {
            val width = match.groupValues[1].toIntOrNull() ?: return url
            val height = match.groupValues[2].toIntOrNull() ?: return url
            return if (width < 1024 || height < 1024)
                url.replaceRange(match.range, "=w${maxOf(width, 1024)}-h${maxOf(height, 1024)}") else url
        }
        val square = Regex("=s(\\d+)")
        val squareMatch = square.find(url) ?: return url
        val size = squareMatch.groupValues[1].toIntOrNull() ?: return url
        return if (size < 1024) url.replaceRange(squareMatch.range, "=s1024") else url
    }
    if (host == "i.ytimg.com") {
        val path = uri.path
        val thumbnail = Regex("/(vi|vi_webp)/([A-Za-z0-9_-]{11})/(default|mqdefault|hqdefault|sddefault|maxresdefault)\\.(jpg|webp)")
        val match = thumbnail.matchEntire(path) ?: return url
        return "https://i.ytimg.com/${match.groupValues[1]}/${match.groupValues[2]}/maxresdefault.${match.groupValues[4]}"
    }
    return url
}