package com.musicglass.desktop

import java.net.URI
import java.net.URLDecoder

internal sealed interface DesktopSharedTarget{
    data class Song(val videoId:String,val startMs:Long=0):DesktopSharedTarget
    data class Collection(val browseId:String):DesktopSharedTarget
}
/** Interpret only validated IDs. Neither the supplied host nor query can become a media fetch destination. */
internal fun parseYouTubeLink(text:String?):DesktopSharedTarget?=runCatching{
    if(text==null||text.length>32768)return null
    val raw=Regex("https?://[^\\s\"'<>]+").find(text)?.value?:return null
    val uri=URI(raw);if(uri.userInfo!=null||uri.port !in setOf(-1,80,443)||uri.host?.lowercase() !in setOf("music.youtube.com","www.youtube.com","m.youtube.com","youtube.com","youtu.be"))return null
    val path=uri.path.trim('/').split('/');val parameters=uri.rawQuery.orEmpty().split('&').filter{it.isNotBlank()}.map{val pair=it.split('=',limit=2);URLDecoder.decode(pair[0],Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1){""},Charsets.UTF_8)}.groupBy({it.first},{it.second})
    if(parameters.any{it.key in setOf("v","t","list")&&it.value.size!=1})return null
    fun parameter(name:String)=parameters[name]?.singleOrNull()
    val time=linkStartMs(parameter("t"))
    val video=Regex("[A-Za-z0-9_-]{11}");val collection=Regex("[A-Za-z0-9_-]{2,200}")
    when{
        uri.host.equals("youtu.be",true)&&path.size==1->path[0].takeIf(video::matches)?.let{DesktopSharedTarget.Song(it,time)}
        path==listOf("watch")->parameter("v")?.takeIf(video::matches)?.let{DesktopSharedTarget.Song(it,time)}
        path.size==2&&path[0]=="shorts"->path[1].takeIf(video::matches)?.let{DesktopSharedTarget.Song(it,time)}
        path==listOf("playlist")->parameter("list")?.takeIf(collection::matches)?.let{DesktopSharedTarget.Collection("VL$it")}
        path.size==2&&path[0] in setOf("browse","channel")&&collection.matches(path[1])&&path[1].startsWith(if(path[0]=="channel")"UC"else"MPRE")->DesktopSharedTarget.Collection(path[1])
        path.size==2&&path[0]=="browse"&&collection.matches(path[1])&&(path[1].startsWith("VL")||path[1].startsWith("UC"))->DesktopSharedTarget.Collection(path[1])
        else->null
    }
}.getOrNull()
internal fun linkStartMs(value:String?):Long{
    if(value.isNullOrBlank()||value.length>32)return 0
    value.toLongOrNull()?.let{return it.coerceIn(0,86400)*1000}
    val match=Regex("^(?:(\\d{1,5})h)?(?:(\\d{1,5})m)?(?:(\\d{1,5})s)?$").matchEntire(value)?:return 0
    val(h,m,s)=match.destructured
    return ((h.toLongOrNull()?:0)*3600+(m.toLongOrNull()?:0)*60+(s.toLongOrNull()?:0)).coerceIn(0,86400)*1000
}
