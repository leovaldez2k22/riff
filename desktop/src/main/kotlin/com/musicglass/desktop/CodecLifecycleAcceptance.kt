package com.musicglass.desktop

import java.io.File
import kotlinx.serialization.json.*

/** Windowless decoder ownership stress using actual compressed recordings; no output device or generated PCM. */
internal object CodecLifecycleAcceptance {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==2)
        val corpus=File(args[0]).canonicalFile;val output=File(args[1]).canonicalFile.apply{parentFile.mkdirs()}
        val sources=listOf("mp3.mp3","flac24.flac","alac.m4a","opus.opus").map{File(corpus,it).apply{check(isFile)}}
        Thread.currentThread().priority=Thread.MIN_PRIORITY
        val pcm=FloatArray(2048);var cycles=0;var peak=0f
        fun cycle(){
            val source=sources[cycles%sources.size]
            FfmpegPcmDecoder(source.path).use{decoder->
                check(decoder.read(pcm,1024)>0);check(decoder.seek(24000)==24000L);check(decoder.read(pcm,1024)>0)
                pcm.forEach{check(it.isFinite());peak=maxOf(peak,kotlin.math.abs(it))}
            }
            check(NativeAudio.handleCounts().contentEquals(longArrayOf(0,0))){"Closed decoder retained native ownership"}
            cycles++;Thread.sleep(1)
        }
        repeat(40){cycle()};System.gc();Thread.sleep(200)
        val baseline=NativeAudio.processHandleCount();val snapshots=mutableListOf<JsonObject>()
        repeat(2){batch->
            repeat(400){cycle()}
            val beforeGc=NativeAudio.processHandleCount();System.gc();Thread.sleep(200)
            val afterGc=NativeAudio.processHandleCount()
            snapshots+=buildJsonObject{put("batch",batch+1);put("cycles",cycles);put("handlesBeforeGc",beforeGc);put("handlesAfterGc",afterGc)}
        }
        val end=NativeAudio.processHandleCount()
        output.writeText(buildJsonObject{put("status",if(end-baseline<16)"PASS"else"FAIL");put("warmupCycles",40);put("measuredCycles",800);put("baselineHandles",baseline);put("finalHandles",end);put("sourceDecodedPeak",peak);put("samples",JsonArray(snapshots));put("note","Decode/seek/close ownership diagnostic, without WASAPI or JFR; GC-separated values distinguish delayed JVM cleanup from persistent growth")}.toString())
        check(end-baseline<16){"Decoder lifecycle handle growth requires diagnosis: $baseline to $end"}
        check(peak>.00001f){"Actual compressed recordings decoded as silence"}
    }
}
