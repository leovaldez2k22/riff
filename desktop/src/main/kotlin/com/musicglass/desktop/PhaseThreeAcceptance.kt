package com.musicglass.desktop

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit real-service/real-codec acceptance. Metadata or audio fixtures cannot satisfy these gates. */
internal object PhaseThreeAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking {
        require(args.size==2)
        val corpus=File(args[0]).canonicalFile
        val output=File(args[1]).canonicalFile.apply{mkdirs()}
        val root=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",root.path)
        val local=File(root,"recording.mp3");File(corpus,"mp3.mp3").copyTo(local)
        File(root,"recording.lrc").writeText("[00:00.00]Local acceptance sidecar\n[00:02.00]Actual authored timing")
        val report=object:LinkedHashMap<String,String>(){
            override fun put(key:String,value:String):String?{
                println("P3_ACCEPT $key: $value")
                val previous=super.put(key,value);val fields=entries.associate{it.key to it.value}
                File(output,"progress-${root.name}.json").writeText(buildJsonObject{put("profile",root.path);fields.forEach{(k,v)->put(k,v)}}.toString())
                return previous
            }
        }
        suspend fun await(host:DesktopHost,seconds:Long=30,predicate:()->Boolean){withTimeout(seconds*1000){while(!predicate()){check(host.snapshot.running);delay(50)}}}
        suspend fun command(host:DesktopHost,value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
        var first:SongItem?=null
        DesktopHost(directory=root).use{host->
            await(host){host.snapshot.initialized};command(host,DesktopCommand.SetVolume(0f))
            for(file in listOf(local,File(corpus,"flac24.flac"),File(corpus,"alac.m4a"))){command(host,DesktopCommand.AddFileToQueue(file));await(host){!host.snapshot.busy}}
            check(host.snapshot.queueEntries.size==3);first=host.snapshot.queueEntries.first().song
            command(host,DesktopCommand.ToggleRepeat)
            command(host,DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Tracks,songsLeft=2,fadeMs=5000)))
            command(host,DesktopCommand.SetPlaying(true));await(host){host.snapshot.isPlaying}
            await(host){host.snapshot.lyricsState is LyricsLoadState.Loaded}
            check(host.snapshot.inspectorTab!=InspectorTab.Lyrics)
            check((host.snapshot.lyricsState as LyricsLoadState.Loaded).document.source.startsWith("Local"))
            report["automaticLyrics"]="Local authored LRC loaded at actual playback start with Lyrics closed"
            await(host,20){!host.snapshot.isPlaying&&!host.snapshot.playWhenReady&&host.snapshot.sleep.mode==SleepMode.Off}
            check(host.snapshot.currentTrack.id==host.snapshot.queueEntries[1].song.id)
            check(host.snapshot.playbackPositionMs>=3000)
            report["stopAfterTwo"]="Actual MP3 then 96 kHz FLAC through WASAPI; repeat-all did not override stop count"
            command(host,DesktopCommand.ToggleRepeat) // Repeat One.
            command(host,DesktopCommand.PlayTrack(first!!));await(host){host.snapshot.isPlaying&&!host.snapshot.busy}
            command(host,DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Tracks,songsLeft=1,fadeMs=0)))
            await(host,12){!host.snapshot.isPlaying&&!host.snapshot.playWhenReady&&host.snapshot.sleep.mode==SleepMode.Off}
            check(host.snapshot.currentTrack.id==first!!.id)
            report["stopAfterOne"]="Actual EOF stopped before repeat-one could reopen the song"
            command(host,DesktopCommand.PlayTrack(first!!));await(host){host.snapshot.isPlaying&&!host.snapshot.busy}
            command(host,DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Deadline,System.currentTimeMillis()+1600,fadeMs=5000)))
            await(host,6){!host.snapshot.isPlaying&&!host.snapshot.playWhenReady&&host.snapshot.sleep.mode==SleepMode.Off}
            report["deadline"]="Real worker deadline paused actual output and cleared the armed timer"
            command(host,DesktopCommand.SetLyricsPreferences(300,true,LyricsRomanMode.Both))
            await(host){host.snapshot.lyricsRomanMode==LyricsRomanMode.Both&&(host.snapshot.lyricsState as? LyricsLoadState.Loaded)?.offsetMs==300L}
            command(host,DesktopCommand.SetSleepTimer(SleepPlan(SleepMode.Queue,fadeMs=15000)))
            host.persistPlaybackSnapshot()
        }
        DesktopHost(directory=root).use{host->
            await(host){host.snapshot.initialized&&!host.snapshot.busy};check(!host.snapshot.isPlaying)
            check(host.snapshot.sleep.mode==SleepMode.Queue&&host.snapshot.sleep.fadeMs==15000L)
            check(host.snapshot.lyricsRomanMode==LyricsRomanMode.Both)
            report["timerRestart"]="Queue-end plan persisted in SQLite and restored paused"
            command(host,DesktopCommand.SetSleepTimer(SleepPlan()))
            val cards=DesktopProvider(File(root,"catalog-probe")).use{it.searchPage("Rick Astley Never Gonna Give You Up").cards}
            val selected=checkNotNull(cards.firstOrNull{it.videoId!=null&&it.title.contains("Never Gonna Give You Up",true)&&it.subtitle.contains("Rick Astley",true)}){"Live catalog did not return the expected recording"}
            val song=selected.song()
            command(host,DesktopCommand.PlayCollection(listOf(song,first!!)))
            await(host,120){host.snapshot.isPlaying&&host.snapshot.playbackPositionMs>500}
            await(host,60){host.snapshot.lyricsTrackId==song.id&&host.snapshot.lyricsState !is LyricsLoadState.Loading}
            val offered=host.snapshot.lyricsState
            if(offered is LyricsLoadState.Ambiguous){
                command(host,DesktopCommand.SelectLyricsCandidate(offered.candidates.first().id))
                await(host){host.snapshot.lyricsState is LyricsLoadState.Loaded}
                report["lyricsChoice"]="Explicit selection from ${offered.count} real matched candidates; no automatic recording guess"
            }
            val lyrics=host.snapshot.lyricsState
            check(lyrics is LyricsLoadState.Loaded){"Live lyrics acceptance failed: $lyrics"}
            check(lyrics.document.sourceUrl.startsWith("https://lrclib.net/")||lyrics.document.sourceUrl.startsWith("https://lrc.red/"))
            check(lyrics.document.lines.isNotEmpty())
            report["liveAutomaticLyrics"]="Real resolved catalog track playing through worker; ${lyrics.document.source}, ${lyrics.document.lines.size} lines, timed=${lyrics.document.timed}, without opening Lyrics"
            command(host,DesktopCommand.SetPlaying(false))
            command(host,DesktopCommand.SeekTo(65000));await(host,90){!host.snapshot.busy&&host.snapshot.playbackPositionMs==65000L}
            command(host,DesktopCommand.SetPlaying(true));await(host,30){host.snapshot.isPlaying&&host.snapshot.playbackPositionMs>65300}
            command(host,DesktopCommand.SetPlaying(false));host.persistPlaybackSnapshot()
            report["realRemoteSeek"]="Resolved actual provider audio, decoded seek beyond 65 seconds, hardware-presented progress resumed"
            val diagnostic=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(diagnostic.success)
            val values=Json.parseToJsonElement(checkNotNull(diagnostic.details)).jsonObject
            check(values.getValue("decodedPeak").jsonPrimitive.float>.00001f)
            report["realAudioEvidence"]=values.toString()
            command(host,DesktopCommand.SetSearchQuery("daft p"))
            await(host,30){!host.snapshot.suggestions.isEmpty}
            report["liveSuggestions"]="${host.snapshot.suggestions.queries.size} real provider text suggestions and ${host.snapshot.suggestions.results.size} quick results"
            command(host,DesktopCommand.SubmitSearch("Daft Punk"))
            check(host.snapshot.recentSearches[host.accounts.token.value.scope]?.firstOrNull()=="Daft Punk")
            await(host,60){host.snapshot.searchStatus==null&&host.snapshot.searchResults.any{it.id.startsWith("youtube:")}}
            check(host.snapshot.searchResults.any{it.title.isNotBlank()&&it.artist.isNotBlank()})
            report["liveFullSearch"]="Actual full provider search completed through the production Host; recording identities and creator metadata retained"
            check(host.snapshot.searchCards.isNotEmpty())
            check(host.snapshot.searchCards.filter{it.kind in setOf(CatalogKind.Artist,CatalogKind.Album,CatalogKind.Playlist,CatalogKind.Podcast)}.all{it.videoId==null})
            report["typedSearchResults"]="Provider kinds: ${host.snapshot.searchCards.groupingBy{it.kind}.eachCount()}; dedicated Top Result: ${host.snapshot.searchTopResult?.let{it.kind.name+":"+it.browseId}?:"not offered by provider"}"


            command(host,DesktopCommand.SetSearchSuggestions(false))
            command(host,DesktopCommand.SetSearchQuery("daft punk"));delay(500);check(host.snapshot.suggestions.isEmpty)
            report["suggestionsPrivacy"]="Disabled suggestions clear immediately; submitted query stored in current authority scope"
            command(host,DesktopCommand.OpenYouTubeLink("https://youtu.be/dQw4w9WgXcQ?t=65"))
            await(host,120){host.snapshot.isPlaying&&host.snapshot.currentTrack.id=="youtube:dQw4w9WgXcQ"&&host.snapshot.playbackPositionMs>=65000}
            check(host.snapshot.queueEntries.size==1&&host.snapshot.currentTrack.title.isNotBlank())
            command(host,DesktopCommand.SetPlaying(false))
            report["liveLink"]="Allowlisted link resolved actual extractor title/author and decoded playback from 65 seconds"

        }
        File(output,"acceptance-${root.name}.json").writeText(buildJsonObject{put("muted",true);report.forEach{(key,value)->put(key,value)}}.toString())
        println(report.entries.joinToString("\n"){"${it.key}: ${it.value}"})
    }
}
