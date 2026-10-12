package com.musicglass.personal
import java.time.LocalDate
import java.util.Locale
internal fun photoDay(now:LocalDate=LocalDate.now())=now.toString()
internal fun moodPhotoQuery(mood:String):String {
    val m=mood.lowercase(Locale.ROOT)
    fun has(vararg words:String)=words.any(m::contains)
    return when {
        has("sleep","dream","bedtime")->"night sky stars landscape"
        has("focus","study","work","concentration") && !has("workout")->"library interior reading"
        has("workout","fitness","run","energ")->"running track athletics"
        has("relax","chill","calm","ambient","meditat")->"mountain lake reflection landscape"
        has("romance","love","valentine")->"sunset beach landscape"
        has("happy","feel good","summer","sunshine")->"sunflowers field landscape"
        has("sad","melanchol","rain")->"rain city night street"
        has("party","dance","electronic","edm","house","techno")->"music festival concert stage lights"
        has("jazz","blues","soul","r&b")->"saxophone concert jazz"
        has("classical","piano","orchestra")->"grand piano"
        has("rock","metal","punk")->"electric guitar concert stage"
        has("hip","rap","urban")->"city skyline night"
        has("country","folk","acoustic")->"countryside sunset landscape"
        has("indian","bollywood","desi","hindi","tamil","telugu","punjab")->"India festival"
        has("latin","reggae","afro","world")->"tropical beach sunset"
        has("travel","commut","road","drive")->"mountain road landscape"
        else->"${mood.take(45)} music concert"
    }
}
internal fun photoChoiceOrder(ids:List<String>,previous:String?,mood:String,day:String):List<String> {
    val candidates=ids.distinct().filter {it!=previous}
    if(candidates.isEmpty()) return emptyList()
    val start=Math.floorMod((mood+day).hashCode(),candidates.size)
    return candidates.drop(start)+candidates.take(start)
}

internal data class PhotoCandidate(val id:String,val url:String,val author:String,val license:String,val page:String,val licenseUrl:String)
internal fun parseOpenversePhotos(root:kotlinx.serialization.json.JsonObject):List<PhotoCandidate> {
    fun kotlinx.serialization.json.JsonObject.str(k:String)=(get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    val rows=root["results"] as? kotlinx.serialization.json.JsonArray?:return emptyList()
    return rows.mapNotNull {value->
        val o=value as? kotlinx.serialization.json.JsonObject?:return@mapNotNull null
        val license=o.str("license");val width=o.str("width").toIntOrNull()?:0;val height=o.str("height").toIntOrNull()?:1
        val url=o.str("url");val host=runCatching {java.net.URI(url).host}.getOrNull().orEmpty()
        if(license !in setOf("cc0","pdm","by","by-sa") || width<800 || height<400 || width.toFloat()/height !in 1f..2.6f ||
            !url.startsWith("https://") || !(host.endsWith(".staticflickr.com") || host in setOf("upload.wikimedia.org","thumb.wikimedia.org","images.unsplash.com")) ||
            Regex("drawing|illustration|painting|diagram|logo|AI.generated",RegexOption.IGNORE_CASE).containsMatchIn(o.str("title"))) return@mapNotNull null
        val id=o.str("id");if(!id.matches(Regex("[a-f0-9-]{36}"))) return@mapNotNull null
        PhotoCandidate("ov-$id",url,o.str("creator"),when(license) {"cc0"->"CC0";"pdm"->string(R.string.public_domain);"by-sa"->"CC BY-SA ${o.str("license_version")}";else->"CC BY ${o.str("license_version")}"},o.str("foreign_landing_url"),o.str("license_url"))
    }
}
