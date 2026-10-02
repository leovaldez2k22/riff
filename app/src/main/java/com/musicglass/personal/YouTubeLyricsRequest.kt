package com.musicglass.personal

import kotlinx.serialization.json.*

/** Public timed-lyrics metadata uses the native music identity and no account fields. */
internal fun youtubeTimedLyricsBody(browseId: String, language: String, region: String): JsonObject {
    require(browseId.startsWith("MPLY"))
    return buildJsonObject {
        put("browseId", browseId)
        putJsonObject("context") { putJsonObject("client") {
            put("clientName", "ANDROID_MUSIC"); put("clientVersion", "7.21.50")
            put("hl", language); put("gl", region)
        } }
    }
}
