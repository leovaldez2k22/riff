package com.musicglass.desktop

import java.net.URI
import java.net.http.*
import java.time.Duration
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Owner-authorized destinations and fields: track title, artist, album and duration, on playback start. */
internal class DesktopLyricsLookup {
    private val client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build()
    suspend fun load(song:SongItem,repository:SyncedLyricsRepository):LyricsLoadState=withContext(Dispatchers.IO){
        val local=repository.load(song)
        if(local is LyricsLoadState.Loaded)return@withContext local
        if(song.artist.isBlank()||song.artist=="Unknown artist")return@withContext local
        val artist=playerArtistLabel(song.artist)
        val track=LyricsTrack(song.id,lyricsRecordingTitle(song.title,artist),artist,song.album,song.durationMs)
        var answered=0;var errors=0
        var selected:Triple<String,String,String>?=null
        var source="";var quality=-1
        suspend fun offer(raw:String,format:String,name:String,url:String){
            val duration=track.durationMs.takeIf{it>0}?:86_400_000
            val doc=when(format){"ttml"->parseTtml(raw,duration,name);"lrc"->parseLrc(raw,duration,name,url);else->LyricsDocument(name,url,listOf(LyricLine(-1,-1,raw)))}?:return
            if(doc.quality>quality){quality=doc.quality;selected=Triple(raw,format,url);source=name}
        }
        try{
            val parameters=mapOf("track_name" to lyricsQueryTitle(track.title),"artist_name" to track.artist,"album_name" to track.album)+if(track.durationMs>0)mapOf("duration" to (track.durationMs/1000).toString())else emptyMap()
            val exact=request("https://lrclib.net/api/get",parameters) as? JsonObject
            answered++
            fun score(value:JsonObject)=lyricsMatchScore(track,value["trackName"]?.jsonPrimitive?.content.orEmpty(),value["artistName"]?.jsonPrimitive?.content.orEmpty(),((value["duration"]?.jsonPrimitive?.doubleOrNull?:0.0)*1000).toLong())
            val row=exact?.takeIf{score(it)>0}?:run{
                val list=(request("https://lrclib.net/api/search",parameters.filterKeys{it!="duration"}) as? JsonArray).orEmpty().take(100).mapNotNull{it as? JsonObject}
                val candidates=list.map{value->value to lyricsMatchScore(track,value["trackName"]?.jsonPrimitive?.content.orEmpty(),value["artistName"]?.jsonPrimitive?.content.orEmpty(),((value["duration"]?.jsonPrimitive?.doubleOrNull?:0.0)*1000).toLong())}.filter{it.second>0}.sortedByDescending{it.second}
                if(candidates.size>1&&candidates[0].second-candidates[1].second<.01){
                    val options=candidates.take(20).mapNotNull{(value,_)->
                        val id=value["id"]?.jsonPrimitive?.longOrNull?:return@mapNotNull null
                        val synced=value["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
                        val plain=value["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}
                        val raw=synced?:plain?:return@mapNotNull null
                        LyricsCandidate(id.toString(),value["trackName"]?.jsonPrimitive?.content.orEmpty(),value["artistName"]?.jsonPrimitive?.content.orEmpty(),((value["duration"]?.jsonPrimitive?.doubleOrNull?:0.0)*1000).toLong(),raw,if(synced!=null)"lrc"else"txt","LRCLIB","https://lrclib.net/lyrics/$id")
                    }.distinctBy{it.id}
                    if(options.isNotEmpty())return@withContext LyricsLoadState.Ambiguous(options)
                }
                candidates.firstOrNull()?.first
            }
            if(row!=null){
                val id=row["id"]?.jsonPrimitive?.longOrNull
                if(id!=null){val url="https://lrclib.net/lyrics/$id"
                    row["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}?.let{offer(it,"lrc","LRCLIB",url)}
                    if(selected==null)row["plainLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf{it.isNotBlank()}?.let{offer(it,"txt","LRCLIB",url)}
                    if(row["instrumental"]?.jsonPrimitive?.booleanOrNull==true&&selected==null)return@withContext repository.accept(song,"","txt","LRCLIB",url,instrumental=true)
                }
            }
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){errors++}
        try{
            val answer=request("https://lrc.red/search.json",mapOf("q" to "${lyricsQueryTitle(track.title)} ${track.artist}"))
            answered++
            val url=lrcRedDocumentUrl(lrcRedBestIsrc(answer,track))
            if(url!=null){val raw=request(url,emptyMap(),true)?.jsonPrimitive?.contentOrNull;if(raw!=null)offer(raw,"ttml",LRC_RED_SOURCE,url)}
        }catch(cancel:CancellationException){throw cancel}catch(_:Exception){errors++}
        ensureActive()
        selected?.let{repository.accept(song,it.first,it.second,source,it.third)}?:if(answered==0&&errors>0)LyricsLoadState.Failed("Lyrics services could not be reached; local files and cached documents remain available")else LyricsLoadState.NoLyrics
    }
    private suspend fun request(base:String,parameters:Map<String,String>,raw:Boolean=false):JsonElement?=runInterruptible(Dispatchers.IO) {
        val destination=URI(base)
        require(destination.scheme=="https"&&destination.host in setOf("lrclib.net","lrc.red")&&destination.userInfo==null)
        val query=parameters.entries.joinToString("&"){java.net.URLEncoder.encode(it.key,Charsets.UTF_8)+"="+java.net.URLEncoder.encode(it.value,Charsets.UTF_8)}
        val uri=if(query.isBlank())destination else URI("$base?$query")
        val exchange=client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent","RiffDesktop/0.1.0 (https://github.com/leovaldez2k22/riff)").GET().build(),HttpResponse.BodyHandler<ByteArray>{BoundedMediaBody(1_000_000)})
        val response=try{exchange.get(20,java.util.concurrent.TimeUnit.SECONDS)}catch(error:Exception){exchange.cancel(true);throw error}
        if(response.statusCode()==404)return@runInterruptible null
        check(response.statusCode()==200){"Lyrics HTTP ${response.statusCode()}"}
        val text=response.body().toString(Charsets.UTF_8)
        if(raw)JsonPrimitive(text)else Json.parseToJsonElement(text)
    }
}

/** Strip an exact uploader/artist prefix and explicitly visual decorations, never live/remix/version text. */
internal fun lyricsRecordingTitle(title:String,artist:String):String {
    val prefix=Regex("^"+Regex.escape(artist)+"\\s+[-–—]\\s+",RegexOption.IGNORE_CASE)
    val withoutArtist=if(artist.isNotBlank())title.replaceFirst(prefix,"")else title
    return lyricsQueryTitle(withoutArtist)
        .replace(Regex("\\s*[\\[(](?:4k|hd|hq)(?: remaster(?:ed)?)?[\\])]",RegexOption.IGNORE_CASE),"").trim()
}
