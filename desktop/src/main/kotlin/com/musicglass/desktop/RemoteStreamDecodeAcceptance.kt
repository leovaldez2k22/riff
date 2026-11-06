package com.musicglass.desktop

import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Explicit live, complete compressed-track decode; no audio fixture, output tap or synthetic fallback. */
internal object RemoteStreamDecodeAcceptance {
    @JvmStatic fun main(args:Array<String>)=runBlocking{
        require(args.size==2)
        val output=File(args[0]).canonicalFile.apply{mkdirs()}
        val video=args[1];require(video.matches(Regex("[A-Za-z0-9_-]{11}")))
        val profile=File(output,"profile-${System.currentTimeMillis()}").apply{mkdirs()}
        System.setProperty("riff.data.dir",profile.path)
        var frames=0L;var durationMs=0L;var peak=0f;var codec="";val started=System.nanoTime()
        try{withTimeout(180000){DesktopProvider(File(profile,"provider")).use{provider->
            val source=provider.openAudio(video)
            FfmpegPcmDecoder(source).use{decoder->
                codec=decoder.codecName;durationMs=decoder.durationMs
                check(durationMs in 10001..900000){"This gate needs a real stated-duration track between 10 seconds and 15 minutes"}
                val pcm=FloatArray(8192)
                while(true){
                    ensureActive();check(System.nanoTime()-started<180_000_000_000){"Full remote decode exceeded three-minute diagnostic budget"}
                    val count=decoder.read(pcm,4096);if(count==0)break
                    repeat(count*2){index->check(pcm[index].isFinite());peak=maxOf(peak,kotlin.math.abs(pcm[index]))}
                    frames+=count;check(frames<=durationMs*48+48000){"Decoded beyond declared duration bound"}
                }
                check(frames>=durationMs*48-24000){"Remote source ended early: $frames frames for $durationMs ms"}
                check(peak>.00001f){"Real remote decoded PCM was silent"}
            }
        }}}
        catch(error:Throwable){
            val chain=generateSequence(error){it.cause}.take(12).map{it.javaClass.simpleName+": "+it.message.orEmpty().replace(Regex("https?://[^\\s]+"),"[remote URL redacted]").take(240)}.toList()
            File(output,"result-$video.json").writeText(buildJsonObject{put("status","FAIL");put("videoId",video);put("decodedFrames",frames);put("declaredDurationMs",durationMs);put("codec",codec);put("failureChain",JsonArray(chain.map(::JsonPrimitive)))}.toString())
            throw error
        }
        check(NativeAudio.handleCounts().contentEquals(longArrayOf(0,0))){"Remote decode leaked native ownership"}
        File(output,"result-$video.json").writeText(buildJsonObject{put("status","PASS");put("videoId",video);put("decodedFrames",frames);put("declaredDurationMs",durationMs);put("codec",codec);put("decodedPeak",peak);put("elapsedMs",(System.nanoTime()-started)/1000000);put("note","Complete real compressed source decode; not a hardware-output or real-time network endurance claim")}.toString())
    }
}
