package com.musicglass.personal

import kotlinx.serialization.json.*
import java.net.URLDecoder

/** Preserve source-language audio before codec/bitrate ranking; a locale default can be a dub. */
internal fun originalAudioPlayerResponse(raw:String):String {
    if(!raw.contains("audioTrack") && !raw.contains("acont")) return raw
    val root=Json.parseToJsonElement(raw) as? JsonObject ?: return raw
    val data=root["streamingData"] as? JsonObject ?: return raw
    val arrays=listOf("adaptiveFormats","formats")
    val formats=arrays.flatMap {(data[it] as? JsonArray).orEmpty()}.filterIsInstance<JsonObject>()
    fun audio(f:JsonObject)=f["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/")==true || f["audioQuality"]!=null || f["audioTrack"]!=null
    fun tags(f:JsonObject):String {
        val tag=f["xtags"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val url=f["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val encoded=Regex("[?&]xtags=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
        return runCatching {URLDecoder.decode("$tag:$encoded","UTF-8")}.getOrDefault(tag).lowercase()
    }
    fun name(f:JsonObject)=((f["audioTrack"] as? JsonObject)?.get("displayName") as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
    fun id(f:JsonObject)=((f["audioTrack"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
    fun original(f:JsonObject)=Regex("(?:^|[:;&])acont=original(?:$|[:;&])").containsMatchIn(tags(f)) || name(f).contains("original")
    fun alternate(f:JsonObject)=Regex("(?:^|[:;&])acont=(?:dubbed|dub|descriptive|auto_dubbed)(?:$|[:;&])").containsMatchIn(tags(f)) || name(f).contains("dubbed") || name(f).contains("audio description")
    val audios=formats.filter(::audio)
    val originals=audios.filter(::original)
    val originalIds=originals.mapNotNull(::id).toSet()
    val trackIds=audios.mapNotNull(::id).toSet()
    val multi=trackIds.size>1 || audios.any(::alternate)
    if(originals.isEmpty() && !multi) return raw
    val edited=data.toMutableMap()
    for(key in arrays) (data[key] as? JsonArray)?.let {array->
        edited[key]=JsonArray(array.filter {node->
            val f=node as? JsonObject ?: return@filter true
            if(!audio(f)) true else !alternate(f) && (original(f) || id(f)?.let {it in originalIds}==true)
        })
    }
    // A manifest default could reintroduce the removed dub; resolve one selected original format.
    edited.remove("hlsManifestUrl");edited.remove("dashManifestUrl")
    if(originals.isEmpty()) edited.remove("serverAbrStreamingUrl")
    return JsonObject(root.toMutableMap().apply {put("streamingData",JsonObject(edited))}).toString()
}
