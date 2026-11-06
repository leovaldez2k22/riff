package com.musicglass.desktop

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Bounded genuine provider-to-WASAPI endurance, intentionally muted and entirely windowless. */
internal object RemotePlaybackEnduranceAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==3)
        val output=File(args[0]).canonicalFile.apply{mkdirs()};val id=args[1];val seconds=args[2].toLong()
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))&&seconds in 90..1800)
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        var heard=0L;var seekMs=-1L;var elapsedMs=0L;var diagnostics:JsonObject?=null
        fun receipt(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("videoId",id);put("secondsRequested",seconds);put("hardwarePresentedFrames",heard);put("seekOperationMs",seekMs);put("elapsedPlaybackMs",elapsedMs);diagnostics?.let{put("worker",it)};error?.let{put("error",it.take(240))}
            put("scope","One actual provider recording, continuous hardware playback and one real seek; no claim for all providers or lease-renewal lifetimes")
        }.toString())}
        try{withTimeout((seconds+180)*1000){
            val metadata=DesktopProvider(File(profile,"metadata")).use{checkNotNull(it.resolve(id).mediaMetadata){"Actual provider metadata is unavailable"}}
            val title=checkNotNull(metadata.title?.takeIf{it.isNotBlank()})
            val song=EmptyDesktopTrack.copy(id="youtube:$id",title=title,artist=metadata.author.orEmpty(),durationMs=(metadata.durationSeconds?:0)*1000L,scope=com.musicglass.data.Scopes.GUEST,artworkUrl=providerArtworkUrl(id))
            DesktopHost(directory=profile).use{host->
                suspend fun await(test:()->Boolean){withTimeout(90000){while(!test()){check(host.snapshot.running);delay(25)}}}
                suspend fun command(value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
                await{host.snapshot.initialized};command(DesktopCommand.SetVolume(0f));command(DesktopCommand.PlayCollection(listOf(song)))
                await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>200}
                val pid=host.audioBridge.workerPid;val started=System.nanoTime();var last=host.clockAnchor.value;var lastProgress=started;var nextReport=started;var sought=false
                while(System.nanoTime()-started<seconds*1000000000L){
                    delay(50);val now=System.nanoTime();val anchor=host.clockAnchor.value
                    check(host.audioBridge.workerPid==pid){"Real audio worker restarted: ${host.snapshot.notificationMessage}"}
                    check(host.snapshot.isPlaying||host.snapshot.busy){"Remote playback stopped: ${host.snapshot.notificationMessage}"}
                    val prior=last
                    if(anchor!=null&&prior!=null&&anchor.timelineEpoch==prior.timelineEpoch&&anchor.samplesRendered>=prior.samplesRendered){val delta=anchor.samplesRendered-prior.samplesRendered;heard+=delta;if(delta>0)lastProgress=now}
                    last=anchor;check(now-lastProgress<15000000000L){"No genuine hardware progress for 15 seconds"}
                    if(!sought&&now-started>=60000000000L){
                        val began=System.nanoTime();command(DesktopCommand.SeekTo(65000));await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=65000}
                        seekMs=(System.nanoTime()-began)/1000000;sought=true
                    }
                    elapsedMs=(System.nanoTime()-started)/1000000
                    if(now>=nextReport){
                        val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success)
                        diagnostics=Json.parseToJsonElement(checkNotNull(result.details)).jsonObject
                        check(diagnostics!!.getValue("producerMmcss").jsonPrimitive.boolean)
                        check(diagnostics!!.getValue("underrunFrames").jsonPrimitive.long==0L){"Actual remote output underrun"}
                        check(diagnostics!!.getValue("decodedPeak").jsonPrimitive.float>.00001f)
                        receipt("RUNNING");nextReport=now+30000000000L
                    }
                }
                check(sought&&heard>=seconds*48000*.97){"Hardware presentation did not cover 97 percent of the measured period"}
                command(DesktopCommand.SetPlaying(false));receipt("PASS")
            }
        }}catch(error:Throwable){receipt("FAIL",error.message);throw error}
    }
}
