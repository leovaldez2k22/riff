package com.musicglass.personal

import kotlinx.serialization.json.*
import java.net.URLDecoder

/** Preserve source-language audio before codec/bitrate ranking; a locale default can be a dub. */
internal fun originalAudioPlayerResponse(raw:String,onCompressed:(present:Int,removed:Int)->Unit={_,_->}):String =
    sourceLanguageAudio(withoutCompressedAudio(raw,onCompressed))

/** Q18-091: how many compressed audio copies a player answer offered and how many were dropped. Counts only. */
internal fun compressedAudioEvent(present:Int,removed:Int)="audio_drc present=$present removed=$removed"

private fun audioFormat(f:JsonObject)=f["mimeType"]?.textOrNull?.startsWith("audio/")==true || f["audioQuality"]!=null || f["audioTrack"]!=null
private fun formatTags(f:JsonObject):String {
    val tag=f["xtags"]?.textOrNull.orEmpty()
    val url=f["url"]?.textOrNull.orEmpty()
    val encoded=Regex("[?&]xtags=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
    return runCatching {URLDecoder.decode("$tag:$encoded","UTF-8")}.getOrDefault(tag).lowercase()
}

/**
 * Q18-091: YouTube can offer a dynamic-range-compressed copy of a format beside the normal one (isDrc, or drc=1 in its
 * tags); the compressed copy squashes a song's loud and quiet parts. It is dropped whenever a normal copy is offered.
 */
internal fun withoutCompressedAudio(raw:String,onCompressed:(present:Int,removed:Int)->Unit={_,_->}):String {
    if(!raw.contains("isDrc") && !raw.contains("drc")) return raw
    val root=Json.parseToJsonElement(raw) as? JsonObject ?: return raw
    val data=root["streamingData"] as? JsonObject ?: return raw
    val arrays=listOf("adaptiveFormats","formats")
    fun compressed(f:JsonObject)=(f["isDrc"] as? JsonPrimitive)?.booleanOrNull==true || Regex("(?:^|[:;&])drc=1(?:$|[:;&])").containsMatchIn(formatTags(f))
    val audios=arrays.flatMap {(data[it] as? JsonArray).orEmpty()}.filterIsInstance<JsonObject>().filter(::audioFormat)
    val present=audios.count(::compressed)
    if(present==0) return raw
    if(present==audios.size) {onCompressed(present,0);return raw}
    onCompressed(present,present)
    val edited=data.toMutableMap()
    for(key in arrays) (data[key] as? JsonArray)?.let {array->
        edited[key]=JsonArray(array.filter {node->(node as? JsonObject)?.let {!audioFormat(it) || !compressed(it)}?:true})
    }
    return JsonObject(root.toMutableMap().apply {put("streamingData",JsonObject(edited))}).toString()
}

private fun sourceLanguageAudio(raw:String):String {
    if(!raw.contains("audioTrack") && !raw.contains("acont")) return raw
    val root=Json.parseToJsonElement(raw) as? JsonObject ?: return raw
    val data=root["streamingData"] as? JsonObject ?: return raw
    val arrays=listOf("adaptiveFormats","formats")
    val formats=arrays.flatMap {(data[it] as? JsonArray).orEmpty()}.filterIsInstance<JsonObject>()
    fun audio(f:JsonObject)=audioFormat(f)
    fun tags(f:JsonObject)=formatTags(f)
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
