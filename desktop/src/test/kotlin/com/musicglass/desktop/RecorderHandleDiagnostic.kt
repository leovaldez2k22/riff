package com.musicglass.desktop

import java.io.File
import java.time.Duration
import jdk.jfr.*
import kotlinx.serialization.json.*

@Name("riff.RecorderHandlePulse")
@StackTrace(false)
class RecorderHandlePulse:Event(){@JvmField var sequence:Long=0}

/** Profiler-only attribution diagnostic: no audio decoder, output device, application UI or music-clock claim. */
object RecorderHandleDiagnostic {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==1);val output=File(args[0]).canonicalFile.apply{parentFile.mkdirs()}
        Thread.currentThread().priority=Thread.MIN_PRIORITY
        FlightRecorder.register(RecorderHandlePulse::class.java)
        val beforeRecording=NativeAudio.processHandleCount()
        val samples=mutableListOf<JsonObject>();var committed=0L;var baseline=0;var maximum=0;var retainedBytes=0L
        Recording(mapOf("riff.RecorderHandlePulse#enabled" to "true","riff.RecorderHandlePulse#stackTrace" to "false")).use{recording->
            recording.setToDisk(true);recording.setMaxSize(8L*1024*1024);recording.setMaxAge(Duration.ofSeconds(10));recording.start()
            val pulse=RecorderHandlePulse();val started=System.nanoTime();var nextSample=started+15000000000L
            while(System.nanoTime()-started<75000000000L){
                val tick=System.nanoTime();repeat(50000){pulse.sequence++;pulse.commit();committed++}
                val now=System.nanoTime()
                if(now>=nextSample){
                    val handles=NativeAudio.processHandleCount();if(baseline==0)baseline=handles;maximum=maxOf(maximum,handles);retainedBytes=maxOf(retainedBytes,recording.size)
                    samples+=buildJsonObject{put("seconds",(now-started)/1000000000);put("handles",handles);put("retainedRecordingBytes",recording.size)}
                    nextSample=now+5000000000L
                }
                val remaining=1000000000L-(System.nanoTime()-tick);if(remaining>0)Thread.sleep(remaining/1000000)
            }
            recording.stop()
        }
        val afterClose=NativeAudio.processHandleCount();System.gc();Thread.sleep(500);val afterGc=NativeAudio.processHandleCount()
        output.writeText(buildJsonObject{
            put("status","OBSERVATION_ONLY");put("beforeRecordingHandles",beforeRecording);put("afterWarmupHandles",baseline);put("maximumHandles",maximum);put("afterRecorderCloseHandles",afterClose);put("afterGcHandles",afterGc);put("committedEventCalls",committed);put("maximumRetainedBytes",retainedBytes);put("samples",JsonArray(samples))
            put("note","Isolates rolling JFR ownership. No audio, WASAPI, application window or playback acceptance. Existing audio handle and underrun thresholds are unchanged.")
        }.toString())
    }
}
