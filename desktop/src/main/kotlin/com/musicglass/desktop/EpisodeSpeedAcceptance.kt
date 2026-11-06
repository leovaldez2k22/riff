package com.musicglass.desktop
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** Genuine typed public episode plus local music. Never substitute a song marked as an episode. */
internal object EpisodeSpeedAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val local=File(args[1]).canonicalFile
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        var stage="catalog";var episode:SongItem?=null;var musicReset=false;var episodeRestored=false;var frames=0L;var transitionFactsVerified=false
        fun report(status:String,error:Throwable?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("stage",stage);put("episodeId",episode?.id);put("authoritativeEpisodeKind",episode?.isEpisode==true);put("musicReset",musicReset);put("episodeRateRestored",episodeRestored);put("genuineEpisodeHardwareFrames",frames);put("protocol",AudioProtocol.BUILD_ID);put("transitionSourceFactsVerified",transitionFactsVerified);error?.let{put("errorType",it.javaClass.simpleName)}
        }.toString())}
        suspend fun await(test:()->Boolean){withTimeout(90000){while(!test())delay(25)}}
        fun diagnostic(host:DesktopHost):JsonObject{val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success);return Json.parseToJsonElement(checkNotNull(result.details)).jsonObject}
        suspend fun rateWindow(host:DesktopHost,rate:Float,sourceKind:String){
            await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.clockAnchor.value?.let{it.playbackRate==rate&&it.samplesRendered>4800}==true}
            val first=checkNotNull(host.clockAnchor.value);await{host.clockAnchor.value?.let{it.timelineEpoch==first.timelineEpoch&&it.samplesRendered>=first.samplesRendered+24000}==true}
            val last=checkNotNull(host.clockAnchor.value);val rendered=last.samplesRendered-first.samplesRendered
            check(kotlin.math.abs(last.contentPositionMs-first.contentPositionMs-rendered*rate/48.0)<3)
            val data=diagnostic(host);check(data.getValue("primarySourceKind").jsonPrimitive.content==sourceKind);check(data.getValue("playbackRate").jsonPrimitive.float==rate);check(data.getValue("underrunFrames").jsonPrimitive.long==0L);check(data.getValue("decodedPeak").jsonPrimitive.float>.00001f)
            check(data.getValue("primaryIsMusic").jsonPrimitive.boolean==!host.snapshot.currentTrack.isEpisode)
            if(sourceKind=="network"){frames+=rendered;transitionFactsVerified=true}
        }
        try{withTimeout(420000){
            DesktopProvider(File(profile,"discovery")).use{provider->
                for(query in listOf("Daft Punk","podcast")){
                    val candidates=provider.searchResults(query).tracks.filter{it.isEpisode}.take(3)
                    for(candidate in candidates)try{
                        val actual=provider.resolve(candidate.id.removePrefix("youtube:"));val metadata=checkNotNull(actual.mediaMetadata)
                        val duration=(metadata.durationSeconds?:0)*1000L
                        if(duration>14400000L)continue
                        episode=candidate.copy(title=metadata.title?.takeIf{it.isNotBlank()}?:candidate.title,artist=metadata.author.orEmpty(),durationMs=duration);break
                    }catch(cancel:CancellationException){throw cancel}catch(_:Exception){ }
                    if(episode!=null)break
                }
            }
            val selected=checkNotNull(episode){"No genuinely typed public episode resolved"};report("RUNNING");stage="play-and-remember"
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};host.dispatch(DesktopCommand.SetVolume(0f));host.dispatch(DesktopCommand.AddFileToQueue(local));host.awaitIdle().get();await{!host.snapshot.busy&&host.snapshot.queueEntries.size==1}
                val music=host.snapshot.queueEntries.first().song
                host.dispatch(DesktopCommand.PlayTrack(selected));host.awaitIdle().get();await{host.snapshot.isPlaying&&!host.snapshot.busy}
                check(host.snapshot.currentTrack.isEpisode);host.dispatch(DesktopCommand.SetPlaybackSpeed(1.5f));host.awaitIdle().get();rateWindow(host,1.5f,"network")
                host.dispatch(DesktopCommand.PlayTrack(music));host.awaitIdle().get();await{host.snapshot.isPlaying&&!host.snapshot.busy}
                host.dispatch(DesktopCommand.SetPlaybackSpeed(1.75f));host.awaitIdle().get();rateWindow(host,1.75f,"local")
                host.dispatch(DesktopCommand.SetPlaying(false));host.awaitIdle().get()
            }
            stage="restart-and-replay";report("RUNNING")
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy};check(host.snapshot.musicPlaybackRate==1f&&host.snapshot.episodePlaybackRate==1.5f);musicReset=true
                host.dispatch(DesktopCommand.SetPlaying(true));host.awaitIdle().get();rateWindow(host,1f,"local")
                host.dispatch(DesktopCommand.PlayTrack(selected));host.awaitIdle().get();rateWindow(host,1.5f,"network");episodeRestored=true
            }
            stage="complete";report("PASS")
        }}catch(error:Throwable){report("FAIL",error);throw IllegalStateException("Episode speed acceptance failed at $stage; see redacted receipt")}
    }
}
