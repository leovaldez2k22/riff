package com.musicglass.desktop
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
/** Genuine host/worker/WASAPI acceptance of overlapping seek and queue-configuration intent. */
internal object PlaybackIntentAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2);val output=File(args[0]).canonicalFile.apply{mkdirs()};val recording=File(args[1]).canonicalFile
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        var overlaps=0;var heard=0L;var paused=false;var diagnostic:JsonObject?=null
        fun report(status:String,error:String?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("muted",true);put("completedOverlaps",overlaps);put("genuineHardwareFramesAfterOperations",heard);put("pauseWins",paused);diagnostic?.let{put("worker",it)};error?.let{put("error",it.take(200))}
        }.toString())}
        suspend fun await(test:()->Boolean){withTimeout(30000){while(!test())delay(10)}}
        try{withTimeout(240000){DesktopHost(directory=profile).use{host->
            await{host.snapshot.initialized};host.dispatch(DesktopCommand.SetVolume(0f));host.dispatch(DesktopCommand.AddFileToQueue(recording));host.awaitIdle().get();await{host.snapshot.queueEntries.size==1&&!host.snapshot.busy}
            host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying&&!host.snapshot.busy}
            val worker=host.audioBridge.workerPid
            repeat(90){index->
                val destination=1000L+(index%4)*1000
                host.dispatch(DesktopCommand.SeekTo(destination))
                if(index%3==0)host.dispatch(DesktopCommand.ToggleRepeat)else host.dispatch(DesktopCommand.SetCrossfadeEnabled(index%2==0))
                host.awaitIdle().get()
                await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=destination}
                check(host.snapshot.playWhenReady);check(host.audioBridge.workerPid==worker){"Worker restarted instead of preserving intent"}
                val anchor=checkNotNull(host.clockAnchor.value)
                await{host.clockAnchor.value?.let{it.timelineEpoch==anchor.timelineEpoch&&it.samplesRendered>=anchor.samplesRendered+4800}==true}
                heard+=checkNotNull(host.clockAnchor.value).samplesRendered-anchor.samplesRendered
                overlaps++;if(overlaps%10==0)report("RUNNING")
            }
            host.dispatch(DesktopCommand.SeekTo(1000));host.dispatch(DesktopCommand.SetPlaying(false));host.dispatch(DesktopCommand.SetCrossfadeEnabled(true));host.awaitIdle().get()
            await{!host.snapshot.busy};check(!host.snapshot.playWhenReady&&!host.snapshot.isPlaying);paused=true
            val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success)
            diagnostic=Json.parseToJsonElement(checkNotNull(result.details)).jsonObject
            check(diagnostic!!.getValue("decodedPeak").jsonPrimitive.float>.00001f);check(diagnostic!!.getValue("underrunFrames").jsonPrimitive.long==0L)
            report("PASS")
        }}}catch(error:Throwable){report("FAIL",error.message);throw error}
    }
}
