package com.musicglass.desktop

import com.musicglass.data.Scopes
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Reproduces a late-position public-stream restart without driving the desktop or reusing its open worker. */
internal object RemoteRestartAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2);val id=args[1];require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val output=File(args[0]).canonicalFile.apply{mkdirs()}
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        var stage="resolve";var savedPosition=-1L;var target=-1L;var restoredPosition=-1L;var seekMillis=-1L;var reloadMillis=-1L;var frames=0L
        var worker:JsonObject?=null
        fun report(status:String,error:Throwable?=null){File(output,"result-${profile.name}.json").writeText(buildJsonObject{
            put("status",status);put("stage",stage);put("videoId",id);put("protocol",AudioProtocol.BUILD_ID);put("muted",true);put("screenUse",false)
            put("targetPositionMs",target);put("savedPositionMs",savedPosition);put("restoredPositionMs",restoredPosition);put("initialSeekMs",seekMillis);put("restartLoadMs",reloadMillis);put("resumedHardwareFrames",frames)
            worker?.let{put("worker",it)};error?.let{put("errorType",it.javaClass.simpleName)}
            put("scope","One genuine resolved public recording, late seek, closed original host/worker, exact paused SQLite session reload and real WASAPI resumption; not packaged screen acceptance or indexed seek latency")
        }.toString())}
        suspend fun command(host:DesktopHost,value:DesktopCommand){host.dispatch(value);host.awaitIdle().get()}
        suspend fun await(test:()->Boolean){withTimeout(180000){while(!test())delay(25)}}
        try{withTimeout(480000){
            val metadata=DesktopProvider(File(profile,"metadata-probe")).use{checkNotNull(it.resolve(id).mediaMetadata)}
            val duration=(metadata.durationSeconds?:0)*1000L;check(duration>80000)
            target=minOf(223000L,duration-10000)
            val song=EmptyDesktopTrack.copy(id="youtube:$id",title=checkNotNull(metadata.title?.takeIf{it.isNotBlank()}),artist=metadata.author.orEmpty(),durationMs=duration,scope=Scopes.GUEST,artworkUrl=providerArtworkUrl(id))
            var expectedEntry="";var firstPid:Long?=null
            stage="late-seek";report("RUNNING")
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized};command(host,DesktopCommand.SetVolume(0f));command(host,DesktopCommand.PlayCollection(listOf(song)))
                await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>300}
                firstPid=host.audioBridge.workerPid;expectedEntry=checkNotNull(host.snapshot.currentEntryId)
                val began=System.nanoTime();command(host,DesktopCommand.SeekTo(target))
                await{host.snapshot.isPlaying&&!host.snapshot.busy&&host.snapshot.playbackPositionMs>=target+250};seekMillis=(System.nanoTime()-began)/1000000
                command(host,DesktopCommand.SetPlaying(false));host.persistPlaybackSnapshot()
                check(!host.snapshot.isPlaying&&!host.snapshot.playWhenReady);savedPosition=host.snapshot.playbackPositionMs;check(savedPosition>=target)
            }
            stage="new-host-restore";report("RUNNING");val began=System.nanoTime()
            DesktopHost(directory=profile).use{host->
                await{host.snapshot.initialized&&!host.snapshot.busy};reloadMillis=(System.nanoTime()-began)/1000000
                check(!host.snapshot.isPlaying&&!host.snapshot.playWhenReady)
                check(host.snapshot.currentTrack.id==song.id&&host.snapshot.currentEntryId==expectedEntry&&host.snapshot.queueEntries.single().queueId==expectedEntry)
                restoredPosition=host.snapshot.playbackPositionMs;check(kotlin.math.abs(restoredPosition-savedPosition)<=1)
                check(host.audioBridge.workerPid!=firstPid){"Original worker was reused across the restart boundary"}
                stage="resume-restored-stream";report("RUNNING");command(host,DesktopCommand.SetPlaying(true))
                await{host.snapshot.isPlaying&&!host.snapshot.busy}
                val initial=checkNotNull(host.clockAnchor.value)
                await{host.clockAnchor.value?.let{it.timelineEpoch==initial.timelineEpoch&&it.samplesRendered>=initial.samplesRendered+96000}==true}
                frames=checkNotNull(host.clockAnchor.value).samplesRendered-initial.samplesRendered
                command(host,DesktopCommand.SetPlaying(false))
                val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success)
                worker=Json.parseToJsonElement(checkNotNull(result.details)).jsonObject
                check(worker!!.getValue("primarySourceKind").jsonPrimitive.content=="network")
                check(worker!!.getValue("underrunFrames").jsonPrimitive.long==0L)
                check(worker!!.getValue("decodedPeak").jsonPrimitive.float>.00001f)
            }
            stage="complete";report("PASS")
        }}catch(error:Throwable){report("FAIL",error);throw IllegalStateException("Late public-stream restart failed at $stage; see redacted receipt",error)}
    }
}
