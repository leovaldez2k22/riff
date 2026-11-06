package com.musicglass.desktop

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal sealed interface DurableAudioSource {
    val key:String
    data class Local(override val key:String,val bytes:Long,val modified:Long,val sha256:String):DurableAudioSource
    data class Provider(override val key:String,val scope:String="anonymous"):DurableAudioSource
}
internal fun describeAudioSource(key:String):DurableAudioSource {
    if(key.startsWith("youtube:")) {require(key.removePrefix("youtube:").matches(Regex("[A-Za-z0-9_-]{11}")));return DurableAudioSource.Provider(key)}
    val file=if(key.startsWith("file:"))File(java.net.URI(key)) else File(key)
    require(file.isFile){"Saved audio file is unavailable"}
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
    return DurableAudioSource.Local(file.canonicalPath,file.length(),file.lastModified(),digest.digest().joinToString(""){"%02x".format(it)})
}
internal fun DurableAudioSource.toJson():JsonObject=buildJsonObject {
    put("version",1);put("key",key)
    when(val source=this@toJson){
        is DurableAudioSource.Local->{put("kind","local");put("bytes",source.bytes);put("modified",source.modified);put("sha256",source.sha256)}
        is DurableAudioSource.Provider->{put("kind","provider");put("scope",source.scope)}
    }
}
internal fun sourceFromJson(json:JsonObject):DurableAudioSource {
    require(json.getValue("version").jsonPrimitive.int==1)
    val key=json.getValue("key").jsonPrimitive.content
    return when(json.getValue("kind").jsonPrimitive.content){
        "local"->DurableAudioSource.Local(key,json.getValue("bytes").jsonPrimitive.long,json.getValue("modified").jsonPrimitive.long,json.getValue("sha256").jsonPrimitive.content.also{require(it.matches(Regex("[0-9a-f]{64}")))})
        "provider"->DurableAudioSource.Provider(key,json.getValue("scope").jsonPrimitive.content.also{require(it=="anonymous")})
        else->error("Unknown durable audio source")
    }
}
