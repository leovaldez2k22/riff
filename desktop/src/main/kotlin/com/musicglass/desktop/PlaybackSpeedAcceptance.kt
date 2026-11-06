package com.musicglass.desktop
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** Real compressed file -> variable-rate Float32 pipeline -> WASAPI content/hardware clock and seek. */
internal object PlaybackSpeedAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val input=File(args[1]).canonicalFile
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        val cases=mutableListOf<JsonObject>();var reset=false;var savedRestored=false
        fun report(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{put("status",status);put("muted",true);put("cases",JsonArray(cases));put("musicResetTo1xAfterRestart",reset);put("localSaveRestoredAndRemoved",savedRestored);put("protocol",AudioProtocol.BUILD_ID);put("scope","Actual local-media rate/seek/hardware/restore; remembered remote episode rate has separate acceptance");error?.let{put("error",it.take(180))}}.toString())}
        suspend fun await(test:()->Boolean){withTimeout(30000){while(!test())delay(10)}}
        fun diagnostics(host:DesktopHost):JsonObject{val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success);return Json.parseToJsonElement(checkNotNull(result.details)).jsonObject}
        try{withTimeout(240000){
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};host.dispatch(DesktopCommand.SetVolume(0f));host.dispatch(DesktopCommand.AddFileToQueue(input));host.awaitIdle().get();await{!host.snapshot.busy&&host.snapshot.queueEntries.size==1};host.dispatch(DesktopCommand.ToggleSavedTrack(host.snapshot.currentTrack));host.awaitIdle().get();check(host.snapshot.isCurrentTrackSaved);host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying};val worker=host.audioBridge.workerPid
                for(rate in listOf(.5f,.75f,1f,1.25f,1.5f,2f)){
                    host.dispatch(DesktopCommand.SetPlaybackSpeed(rate));host.awaitIdle().get()
                    await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.clockAnchor.value?.let{it.playbackRate==rate&&it.samplesRendered>4800}==true}
                    val before=checkNotNull(host.clockAnchor.value)
                    await{host.clockAnchor.value?.let{it.timelineEpoch==before.timelineEpoch&&it.samplesRendered>=before.samplesRendered+24000}==true}
                    val after=checkNotNull(host.clockAnchor.value);val media=after.contentPositionMs-before.contentPositionMs;val hardware=after.samplesRendered-before.samplesRendered
                    val error=kotlin.math.abs(media-hardware*rate/48.0);check(error<3){"Source/hardware clock rate mismatch: $rate / $error ms"}
                    val epoch=after.timelineEpoch;host.dispatch(DesktopCommand.SeekTo(2000));host.awaitIdle().get()
                    await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=2000&&host.clockAnchor.value?.let{it.timelineEpoch>epoch&&it.playbackRate==rate}==true}
                    val data=diagnostics(host);check(data.getValue("playbackRate").jsonPrimitive.float==rate);check(data.getValue("underrunFrames").jsonPrimitive.long==0L);check(data.getValue("decodedPeak").jsonPrimitive.float>.00001f);check(host.audioBridge.workerPid==worker)
                    cases+=buildJsonObject{put("rate",rate);put("hardwareFrames",hardware);put("mediaDeltaMs",media);put("clockErrorMs",error);put("seekPositionMs",host.snapshot.playbackPositionMs);put("tempoHandles",data.getValue("tempoHandles"))};report("RUNNING")
                }
                host.dispatch(DesktopCommand.SetPlaying(false));host.awaitIdle().get()
            }
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy};check(host.snapshot.musicPlaybackRate==1f);check(host.snapshot.isCurrentTrackSaved);host.dispatch(DesktopCommand.ToggleSavedTrack(host.snapshot.currentTrack));host.awaitIdle().get();check(!host.snapshot.isCurrentTrackSaved);savedRestored=true;host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying&&host.clockAnchor.value?.let{it.playbackRate==1f&&it.samplesRendered>4800}==true}
                val data=diagnostics(host);check(data.getValue("playbackRate").jsonPrimitive.float==1f);check(data.getValue("tempoHandles").jsonPrimitive.int==0);reset=true
            }
            report("PASS")
        }}catch(error:Throwable){report("FAIL",error.message);throw error}
    }
}
