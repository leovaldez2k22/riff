package com.musicglass.personal
import kotlinx.serialization.json.*

/** Request the exact same song/video switcher used by YouTube Music's watch queue. */
internal fun musicPairingRequest(raw:String):String {
    val root=runCatching {Json.parseToJsonElement(raw) as? JsonObject}.getOrNull()?:return raw
    if((root["videoId"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() || !((root["continuation"] as? JsonPrimitive)?.contentOrNull).isNullOrBlank()) return raw
    return JsonObject(root+ ("watchEndpointMusicSupportedConfigs" to buildJsonObject {
        put("watchEndpointMusicConfig",buildJsonObject {put("hasPersistentPlaylistPanel",true);put("musicVideoType","MUSIC_VIDEO_TYPE_ATV")})
    })).toString()
}
internal fun pairedVideoTitleAllowed(track:ProviderTrack,title:String):Boolean {
    if(Regex("\\b(lyrics?|audio|visuali[sz]er|karaoke|cover|reaction|fanmade|fan made|sped up|slowed)\\b",RegexOption.IGNORE_CASE).containsMatchIn(title)) return false
    val descriptors=Regex("\\b(official (?:music )?video|music video|4k upgrade|4k|1080p|hd|hq)\\b",RegexOption.IGNORE_CASE)
    val normalized=lyricsNormalize(descriptors.replace(title,""))
    val wanted=lyricsNormalize(lyricsQueryTitle(track.title))
    val artist=lyricsNormalize(playerArtistLabel(track.artist).removeSuffix(" - Topic").substringBefore(",").substringBefore(" feat.").substringBefore(" & "))
    return wanted.isNotBlank() && normalized in setOf(wanted,artist+wanted,wanted+artist)
}
internal fun musicVideoPair(root:JsonElement,track:ProviderTrack):String? {
    fun renderer(value:JsonElement?):JsonObject? = (value as? JsonObject)?.let {o->(o["playlistPanelVideoRenderer"] as? JsonObject)?:o}
    fun id(r:JsonObject?)=(r?.get("videoId") as? JsonPrimitive)?.contentOrNull ?:catalogValue(r?.get("navigationEndpoint"),"videoId")
    fun valid(r:JsonObject?):String? {
        val video=id(r)?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{11}"))}?:return null
        if(catalogValue(r?.get("navigationEndpoint"),"musicVideoType")!="MUSIC_VIDEO_TYPE_OMV") return null
        return video.takeIf {pairedVideoTitleAllowed(track,catalogText(r?.get("title")))}
    }
    fun visit(node:JsonElement):String? = when(node) {
        is JsonObject -> {
            val wrapper=node["playlistPanelVideoWrapperRenderer"] as? JsonObject
            if(wrapper!=null) {
                val primary=renderer(wrapper["primaryRenderer"])
                if(id(primary)==track.videoId) {
                    valid(primary) ?: (wrapper["counterpart"] as? JsonArray)?.firstNotNullOfOrNull {c->
                        valid(renderer((c as? JsonObject)?.get("counterpartRenderer")))
                    }
                } else null
            } else {
                val current=node["playlistPanelVideoRenderer"] as? JsonObject
                if(id(current)==track.videoId) valid(current) else node.values.firstNotNullOfOrNull(::visit)
            }
        }
        is JsonArray->node.firstNotNullOfOrNull(::visit)
        else->null
    }
    return visit(root)
}
