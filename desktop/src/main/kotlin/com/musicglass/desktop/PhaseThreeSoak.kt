package com.musicglass.desktop

import java.io.File
import java.security.MessageDigest
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Eight-hour production host + separate worker test. Real encoded files and WASAPI; explicitly muted. */
internal object PhaseThreeSoak {
    private const val HEARTBEAT_SECONDS=30L
    private interface Power:StdCallLibrary{fun SetThreadExecutionState(flags:Int):Int}
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size in 3..4);val recordStalls=args.getOrNull(3)=="--record-stalls";require(args.size==3||recordStalls)
        val corpus=File(args[0]).canonicalFile
        val output=File(args[1]).canonicalFile.apply{mkdirs()}
        val seconds=args[2].toLong();require(seconds in 60..28800)
        val profile=File(output,"profile").apply{mkdirs()};System.setProperty("riff.data.dir",profile.path)
        val power=Native.load("kernel32",Power::class.java)
        check(power.SetThreadExecutionState(0x80000001.toInt())!=0){"Cannot hold the system playback power request"}
        val started=System.nanoTime();var deadline=started+seconds*1_000_000_000
        val log=File(output,"samples.jsonl")
        val report=File(output,"result.json")
        var heard=0L;var last:AudioEvent.ClockAnchor?=null;var lastProgress=started;var nextSample=started
        var nextSeek=started+17_000_000_000;var nextFade=started+180_000_000_000
        var speedChanges=0;var equalizerChanges=0;var nextSpeed=Long.MAX_VALUE;var nextEqualizer=Long.MAX_VALUE
        var seeks=0;var maxHostHeap=0L;var maxWorkerHeap=0L;var maxHandles=0;var initialHandles:Int?=null;var finalHandles=0;var underruns=0L
        // Liveness for scripts/desktop/soak-status.py: a RUNNING file is believed only while this exact process
        // (PID plus start time, so a reused PID doesn't count) is alive and the heartbeat is fresh.
        val self=ProcessHandle.current();val processStartMs=self.info().startInstant().map{it.toEpochMilli()}.orElse(0L)
        var nextHeartbeat=Long.MAX_VALUE
        fun writeStatus(status:String,error:String?=null){
            val text=buildJsonObject{
            put("status",status);put("pid",self.pid());put("processStartMs",processStartMs);put("heartbeatEpochMs",System.currentTimeMillis());put("heartbeatIntervalSeconds",HEARTBEAT_SECONDS);put("recordStalls",recordStalls);put("speedChanges",speedChanges);put("equalizerChanges",equalizerChanges);put("muted",true);put("warmupSeconds",60);put("secondsRequested",seconds);put("elapsedSeconds",(System.nanoTime()-started)/1_000_000_000);put("hardwarePresentedFrames",heard);put("seekActions",seeks);put("workerHeapPeak",maxWorkerHeap);put("hostHeapPeak",maxHostHeap);put("workerHandlePeak",maxHandles);put("workerInitialHandles",initialHandles?:0);put("workerFinalHandles",finalHandles);put("workerUnderruns",underruns);put("protocol",AudioProtocol.BUILD_ID);error?.let{put("error",it)}
            }.toString()
            // Replace atomically so a reader never sees half a file.
            val staging=File(output,"result.json.tmp");staging.writeText(text)
            java.nio.file.Files.move(staging.toPath(),report.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            nextHeartbeat=System.nanoTime()+HEARTBEAT_SECONDS*1_000_000_000
        }
        try{DesktopHost(directory=profile).use{host->
            suspend fun await(test:()->Boolean){withTimeout(60000){while(!test())delay(50)}}
            await{host.snapshot.initialized}
            if(recordStalls){
            // Recorder initialization may reach a JVM safepoint. Complete it before starting any audio.
            val jcmd=File(System.getProperty("java.home"),"bin/jcmd.exe");check(jcmd.isFile){"Diagnostic soak requires the pinned development JDK"}
            val relative=File(profile,"worker-stalls.jfr").relativeTo(File(".").canonicalFile).invariantSeparatorsPath
            require(!relative.contains(' ')){"Use a space-free relative evidence directory for jcmd"}
            val configure=ProcessBuilder(jcmd.path,checkNotNull(host.audioBridge.workerPid).toString(),"JFR.configure","maxchunksize=1m")
                .redirectErrorStream(true).redirectOutput(File(output,"recorder-configure.log")).start()
            if(!configure.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){configure.destroyForcibly();error("Recorder configuration timed out before playback")}
            check(configure.exitValue()==0){"Recorder chunk bounds could not be configured"}
            val recorder=ProcessBuilder(jcmd.path,checkNotNull(host.audioBridge.workerPid).toString(),"JFR.start","name=riff-stalls","settings=profile","maxsize=32m","maxage=10m","dumponexit=true","filename=$relative")
                .redirectErrorStream(true).redirectOutput(File(output,"recorder-start.log")).start()
            if(!recorder.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){recorder.destroyForcibly();error("Recorder startup timed out before playback")}
            check(recorder.exitValue()==0){"Recorder could not start; see recorder-start.log"}
            }
            val actions=java.util.ArrayDeque<String>()
            fun trace(action:String){actions.addLast("${System.nanoTime()-started}: $action");while(actions.size>64)actions.removeFirst()}
            fun progressFailure(message:String):Nothing{
                val snapshot=host.snapshot
                File(output,"failure-context.json").writeText(buildJsonObject{
                    put("message",message);put("playWhenReady",snapshot.playWhenReady);put("isPlaying",snapshot.isPlaying);put("busy",snapshot.busy)
                    put("positionMs",snapshot.playbackPositionMs);put("entry",snapshot.currentEntryId?:"");put("notification",snapshot.notificationMessage?:"")
                    put("actions",JsonArray(actions.map(::JsonPrimitive)))
                    put("clock",host.clockAnchor.value?.toString()?:"none")
                }.toString());error(message)
            }
            host.dispatch(DesktopCommand.SetVolume(0f));host.awaitIdle().get()
            for(name in listOf("mp3.mp3","flac24.flac","alac.m4a","opus.opus")){host.dispatch(DesktopCommand.AddFileToQueue(File(corpus,name)));host.awaitIdle().get()}
            await{!host.snapshot.busy&&host.snapshot.queueEntries.size==4}
            host.dispatch(DesktopCommand.ToggleRepeat);host.awaitIdle().get()
            host.dispatch(DesktopCommand.SetPlaying(true));await{host.snapshot.isPlaying}
            // Exercise codec transitions and JFR's lazy thread/file setup before measuring steady-state handles.
            // Warmup is separate from (and never counted toward) the requested playback duration.
            val warmupStart=System.nanoTime();var warmAnchor=host.clockAnchor.value;var warmProgress=warmupStart;var changed=false;var warmSpeed=0
            host.dispatch(DesktopCommand.SetCrossfadeEnabled(true))
            while(System.nanoTime()-warmupStart<60_000_000_000){
                delay(100);val now=System.nanoTime();val anchor=host.clockAnchor.value
                if(anchor!=null&&warmAnchor?.timelineEpoch==anchor.timelineEpoch&&anchor.samplesRendered>(warmAnchor?.samplesRendered?:0))warmProgress=now
                warmAnchor=anchor;if(now-warmProgress>=15_000_000_000)progressFailure("No real hardware progress during warmup")
                val step=((now-warmupStart)/20_000_000_000).toInt()
                if(step>=warmSpeed&&!host.snapshot.busy){val rate=listOf(.75f,1.5f,1f)[step.coerceAtMost(2)];host.dispatch(DesktopCommand.SetPlaybackSpeed(rate));trace("warmup speed $rate");warmSpeed=step+1}
                if(!changed&&now-warmupStart>30_000_000_000&&!host.snapshot.busy){host.dispatch(DesktopCommand.SetCrossfadeEnabled(false));changed=true}
            }
            await{host.snapshot.isPlaying&&!host.snapshot.busy}
            val warmDiagnostics=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(warmDiagnostics.success)
            check(Json.parseToJsonElement(checkNotNull(warmDiagnostics.details)).jsonObject.getValue("underrunFrames").jsonPrimitive.long==0L){"Underruns during warmup still fail acceptance"}
            // Acceptance duration begins after genuine startup/warmup, never during file import.
            val playingStarted=System.nanoTime();deadline=playingStarted+seconds*1_000_000_000
            last=host.clockAnchor.value;lastProgress=playingStarted;nextSeek=playingStarted+17_000_000_000;nextFade=playingStarted+180_000_000_000
            nextSpeed=playingStarted+300_000_000_000;nextEqualizer=playingStarted+240_000_000_000
            writeStatus("RUNNING")
            while(System.nanoTime()<deadline){
                delay(100);val now=System.nanoTime();val anchor=host.clockAnchor.value
                if(now>=nextHeartbeat)writeStatus("RUNNING")
                if(anchor!=null){val previous=last;if(previous!=null&&previous.timelineEpoch==anchor.timelineEpoch&&anchor.samplesRendered>=previous.samplesRendered){val delta=anchor.samplesRendered-previous.samplesRendered;heard+=delta;if(delta>0)lastProgress=now};last=anchor}
                check(host.snapshot.notificationMessage?.contains("failed",true)!=true){host.snapshot.notificationMessage.orEmpty()}
                if(now-lastProgress>=15_000_000_000)progressFailure("No genuine hardware progress for 15 seconds")
                if(now>=nextSeek&&host.snapshot.isPlaying&&!host.snapshot.busy){host.dispatch(DesktopCommand.SeekTo(1000));trace("seek 1000");seeks++;nextSeek=now+17_000_000_000}
                if(now>=nextFade&&!host.snapshot.busy){host.dispatch(DesktopCommand.SetCrossfadeEnabled(!host.snapshot.crossfadeEnabled));trace("toggle crossfade");nextFade=now+180_000_000_000}
                if(now>=nextSpeed&&!host.snapshot.busy){
                    val rate=listOf(1.25f,.75f,1.5f,1f)[speedChanges%4]
                    host.dispatch(DesktopCommand.SetPlaybackSpeed(rate));trace("speed $rate");speedChanges++;nextSpeed=now+300_000_000_000
                }
                if(now>=nextEqualizer&&!host.snapshot.busy){
                    val preset=listOf("Bass boost","Vocal","Flat")[equalizerChanges%3]
                    host.dispatch(DesktopCommand.SetEqualizer(EqualizerConfig(enabled=true,mode=EqMode.Manual,preset=preset,manual=EQ_PRESETS.getValue(preset))))
                    trace("EQ $preset");equalizerChanges++;nextEqualizer=now+240_000_000_000
                }
                if(now>=nextSample){
                    val result=host.audioBridge.sendCommand(AudioCommand.QueryDiagnostics(host.audioBridge.nextId()));check(result.success){result.error.orEmpty()}
                    val sample=Json.parseToJsonElement(checkNotNull(result.details)).jsonObject
                    check(sample["producerMmcss"]?.jsonPrimitive?.boolean==true){"Actual Windows mixer producer is not registered with MMCSS"}
                    val hostHeap=java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
                    maxHostHeap=maxOf(maxHostHeap,hostHeap);check(hostHeap<512L*1024*1024){"Host heap exceeded 512 MiB"}
                    val heap=sample.getValue("heapBytes").jsonPrimitive.long;val handles=sample.getValue("processHandles").jsonPrimitive.int
                    maxWorkerHeap=maxOf(maxWorkerHeap,heap);maxHandles=maxOf(maxHandles,handles);if(initialHandles==null)initialHandles=handles;finalHandles=handles
                    underruns=sample.getValue("underrunFrames").jsonPrimitive.long
                    check(sample.getValue("nativeDecoders").jsonPrimitive.long<=4&&sample.getValue("nativeSinks").jsonPrimitive.long<=1&&sample.getValue("tempoHandles").jsonPrimitive.long<=2){"Unbounded native handle ownership"}
                    check(sample.getValue("decodedPeak").jsonPrimitive.float>.00001f){"The real decoder produced no non-silent source PCM before user mute"}
                    check(heap<256L*1024*1024){"Worker heap exceeded 256 MiB"}
                    log.appendText(buildJsonObject{put("elapsedSeconds",(now-started)/1_000_000_000);put("hardwarePresentedFrames",heard);put("worker",sample);put("desiredPlay",host.snapshot.playWhenReady);put("observedPlaying",host.snapshot.isPlaying);put("busy",host.snapshot.busy);put("hostHeapBytes",hostHeap)}.toString()+"\n")
                    writeStatus("RUNNING");check(underruns==0L){"Observed $underruns underrun frames; preserve native scheduling telemetry and worker-stalls.jfr"};nextSample=now+60_000_000_000
                }
            }
            check(heard>=seconds*48000*.97){"Hardware playback did not cover the requested elapsed duration"}
            check(finalHandles-(initialHandles?:finalHandles)<64){"Windows handle growth exceeded 64"}
            check(underruns==0L){"Underruns require diagnosis: $underruns frames"}
            host.dispatch(DesktopCommand.SetPlaying(false));host.awaitIdle().get();writeStatus("PASS")
        }}catch(error:Throwable){writeStatus("FAIL",error.message?:error.javaClass.simpleName);throw error}
        finally{power.SetThreadExecutionState(0x80000000.toInt())}
    }
}
