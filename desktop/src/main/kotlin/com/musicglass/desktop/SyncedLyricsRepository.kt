package com.musicglass.desktop

import com.musicglass.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal data class LyricsCandidate(val id:String,val title:String,val artist:String,val durationMs:Long,val raw:String,val format:String,val source:String,val url:String)

internal sealed interface LyricsLoadState {
    data object Idle:LyricsLoadState
    data object Loading:LyricsLoadState
    data object NoLyrics:LyricsLoadState
    data class Loaded(val document:LyricsDocument,val offsetMs:Long=0,val romanized:Boolean=false,val cached:Boolean=false,val romanMode:LyricsRomanMode=if(romanized)LyricsRomanMode.Romanized else LyricsRomanMode.Original):LyricsLoadState
    data class Ambiguous(val candidates:List<LyricsCandidate>):LyricsLoadState{val count get()=candidates.size}
    data class Failed(val message:String):LyricsLoadState
}

/** Local sidecars and scoped persisted documents. This repository performs no network requests. */
internal class SyncedLyricsRepository(store:SqliteLibraryRepository){
    private val cache=LyricsCacheRepository(store)
    suspend fun load(song:SongItem):LyricsLoadState=withContext(Dispatchers.IO){
        song.sourcePath?.let{source->
            val audio=File(source)
            for(format in listOf("ttml","lrc","txt")){
                val file=File(audio.parentFile,audio.nameWithoutExtension+".$format")
                if(file.isFile){
                    require(file.length()<=1_000_000){"Lyrics file exceeds size limit"}
                    return@withContext accept(song,file.readText(),format,"Local ${format.uppercase()}",file.name)
                }
            }
        }
        cached(song)?:LyricsLoadState.NoLyrics
    }
    fun cached(song:SongItem):LyricsLoadState.Loaded?=cache.get(song.scope,song.id)?.let{entry->try{
        val value=Json.parseToJsonElement(entry.payload).jsonObject;require(value.getValue("v").jsonPrimitive.int==1)
        val parsed=parse(song,value.getValue("raw").jsonPrimitive.content,value.getValue("format").jsonPrimitive.content,value.getValue("source").jsonPrimitive.content,value.getValue("url").jsonPrimitive.content)?:return@let null
        val doc=parsed.copy(instrumental=value["instrumental"]?.jsonPrimitive?.booleanOrNull?:false)
        LyricsLoadState.Loaded(doc,value["offset"]?.jsonPrimitive?.long?:200,value["romanized"]?.jsonPrimitive?.boolean?:false,true,value["romanMode"]?.jsonPrimitive?.content?.let{runCatching{LyricsRomanMode.valueOf(it)}.getOrNull()}?:if(value["romanized"]?.jsonPrimitive?.boolean==true)LyricsRomanMode.Romanized else LyricsRomanMode.Original)
    }catch(_:Exception){null}}
    fun accept(song:SongItem,raw:String,format:String,source:String,url:String,instrumental:Boolean=false):LyricsLoadState {
        require(raw.length<=1_000_000)
        val parsed=parse(song,raw,format,source,url)?:return LyricsLoadState.Failed("Lyrics document is malformed or exceeds parsing bounds")
        val doc=parsed.copy(instrumental=instrumental)
        val previous=cached(song)
        val payload=buildJsonObject{put("v",1);put("instrumental",instrumental);put("raw",raw);put("format",format);put("source",source);put("url",url);put("offset",previous?.offsetMs?:200);put("romanized",previous?.romanized?:false);put("romanMode",previous?.romanMode?.name?:LyricsRomanMode.Original.name)}
        cache.put(song.scope,song.id,payload.toString())
        return LyricsLoadState.Loaded(doc,previous?.offsetMs?:200,previous?.romanized?:false,romanMode=previous?.romanMode?:LyricsRomanMode.Original)
    }
    fun preferences(song:SongItem,offset:Long,romanized:Boolean,mode:LyricsRomanMode?=null):LyricsLoadState.Loaded {
        require(offset in -5000..5000)
        val entry=checkNotNull(cache.get(song.scope,song.id)){"No lyrics document selected"}
        val payload=Json.parseToJsonElement(entry.payload).jsonObject.toMutableMap().apply{put("offset",JsonPrimitive(offset));put("romanized",JsonPrimitive(romanized));put("romanMode",JsonPrimitive((mode?:if(romanized)LyricsRomanMode.Romanized else LyricsRomanMode.Original).name))}
        cache.put(song.scope,song.id,JsonObject(payload).toString(),entry.fetchedMs)
        return checkNotNull(cached(song))
    }
    private fun parse(song:SongItem,raw:String,format:String,source:String,url:String):LyricsDocument? {
        val duration=song.durationMs.takeIf{it>0}?:86_400_000
        val doc=when(format){
            "ttml"->parseTtml(raw,duration,source)?.copy(sourceUrl=url)
            "lrc"->parseLrc(raw,duration,source,url)
            "txt"->LyricsDocument(source,url,raw.lineSequence().take(2000).filterNot(::lyricCredit).map{LyricLine(-1,-1,it.take(16384))}.toList())
            else->null
        }?:return null
        val enriched=DesktopLyricsRomanize.enrich(doc)
        return if(song.durationMs==0L)enriched.copy(lines=enriched.lines.mapIndexed{index,line->if(index==doc.lines.lastIndex&&line.startMs>=0&&!line.knownEnd)line.copy(endMs=Long.MAX_VALUE)else line})else enriched
    }
}
