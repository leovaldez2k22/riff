package com.musicglass.desktop.automix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Actual owner-selected recording windows -> JVM front end -> bundled native ONNX model -> real beat grids.
 * This does not certify the player transition renderer, listening quality or unavailable receiver hardware.
 */
internal object DesktopAutomixAnalysisAcceptance {
    @JvmStatic fun main(args:Array<String>){
        require(args.size==2)
        val corpus=File(args[0]).canonicalFile;val output=File(args[1]).canonicalFile.apply{mkdirs()}
        val windows=Json.parseToJsonElement(File(corpus,"windows.json").readText()).jsonArray
        check(windows.size==50){"Requires the existing 50 distinct owner-selected recordings"}
        val rows=mutableListOf<JsonObject>();var worstMs=0L;var count=0
        val digest=MessageDigest.getInstance("SHA-256")
        fun sha(bytes:ByteArray)=digest.digest(bytes).joinToString(""){"%02x".format(it)}
        val identities=mutableSetOf<String>()
        try{
            DesktopBeatRuntime().use{model->
                for(window in windows){
                    val w=window.jsonObject;val n=w.getValue("n").jsonPrimitive.int
                    check(identities.add(w.getValue("file").jsonPrimitive.content)){"Duplicate recording identity"}
                    val duration=w.getValue("durationMs").jsonPrimitive.long
                    for(part in listOf("head","tail")){
                        val pcm=File(corpus,"$n.$part.pcm");check(pcm.isFile&&pcm.length() in 4..13000000&&pcm.length()%4==0L)
                        val bytes=pcm.readBytes();val short=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();val samples=ShortArray(short.remaining());short.get(samples)
                        val start=if(part=="head")0 else w.getValue("tailStartMs").jsonPrimitive.long
                        val modelBefore=model.successfulRuns;val began=System.nanoTime();val builder=EnvelopeBuilder(48000,2,start,model);builder.add(samples);val envelope=builder.build(duration)
                        check(model.successfulRuns>modelBefore&&model.failedRuns==0L){"Model failed or silently fell back to light tracking"}
                        val elapsed=(System.nanoTime()-began)/1_000_000;worstMs=maxOf(worstMs,elapsed)
                        check(envelope.loudnessDb.isNotEmpty()&&envelope.loudnessDb.all{it.isFinite()})
                        val grid=envelope.beats
                        rows+=buildJsonObject{
                            put("n",n);put("part",part);put("ms",elapsed);put("pcmSha256",sha(bytes));put("actualFrames",samples.size/2);put("nativeModelRuns",model.successfulRuns-modelBefore)
                            put("beats",JsonArray((grid?.beatsMs?:LongArray(0)).map(::JsonPrimitive)));put("downbeats",JsonArray((grid?.downbeatsMs?:LongArray(0)).map(::JsonPrimitive)))
                            if(grid!=null){put("bpm",grid.bpm);put("confidence",grid.confidence);put("downbeatConfidence",grid.downbeatConfidence)}
                        }
                        count++;File(output,"desktop-beats.json").writeText(JsonArray(rows).toString())
                        println("RIFF_AUTOMIX_WINDOW n=$n part=$part frames=${samples.size/2} elapsedMs=$elapsed grid=${grid!=null}")
                    }
                }
            }
            check(count==100)
            File(output,"result.json").writeText(buildJsonObject{put("status","INFERENCE_PASS_SCORING_PENDING");put("actualRecordingWindows",count);put("maximumAnalysisMs",worstMs);put("scope","Actual model/front-end/cache analysis only; not full Automix playback acceptance")}.toString())
        }catch(error:Throwable){File(output,"result.json").writeText(buildJsonObject{put("status","FAIL");put("completedWindows",count);put("error",error.javaClass.simpleName+": "+error.message?.take(180))}.toString());throw error}
    }
}
