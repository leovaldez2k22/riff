package com.musicglass.desktop

import com.musicglass.desktop.automix.BlendMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.*
import kotlin.test.*

/** Opt-in real-recording acceptance. No generated beats, tone decoder, fake model or endpoint claim. */
class AutomixRecordedIntegrationTest {
    @Test fun actualRecordedBeatAndEchoTransitionsReachMutedWasapiAfterSeek(){
        val root=File(checkNotNull(System.getProperty("riff.recorded.dir")))
        val receipts=mutableListOf<JsonObject>()
        for((incoming,kind) in listOf("0.head.flac" to "BeatAligned","1.head.flac" to "Echo")){
            val directory=kotlin.io.path.createTempDirectory("riff-wasapi-blend-").toFile()
            val faults=CopyOnWriteArrayList<AudioEvent.Fault>();val transitions=CopyOnWriteArrayList<AudioEvent.TrackTransition>()
            fun awaitPlan(mixer:DesktopDualDeckMixer):JsonObject{
                val deadline=System.nanoTime()+60_000_000_000L;var state=mixer.diagnostics()
                while(state.getValue("blendReason").jsonPrimitive.content!="MODEL_PLAN"&&System.nanoTime()<deadline){Thread.sleep(50);state=mixer.diagnostics()}
                assertEquals("MODEL_PLAN",state.getValue("blendReason").jsonPrimitive.content,"Real cached/native analysis did not prepare: $state")
                assertEquals(kind,state.getValue("blendKind").jsonPrimitive.content)
                return state
            }
            try{
                DesktopDualDeckMixer(onFault={faults+=it},onTrackTransition={transitions+=it},analysisDirectory=directory).use{mixer->
                    mixer.volume=0f;mixer.configureCanonicalDsp(EqualizerConfig(),1f,1f);mixer.configureBlend(BlendMode.Automix)
                    mixer.prepareSource("outgoing",File(root,"0.tail.flac").path);mixer.prepareSource("incoming",File(root,incoming).path)
                    mixer.loadQueue(listOf("outgoing","incoming"),crossfade=true)
                    val plan=awaitPlan(mixer);val start=plan.getValue("blendStartMs").jsonPrimitive.long;val end=plan.getValue("blendEndMs").jsonPrimitive.long
                    assertTrue(start>1000)
                    mixer.seek(start-500);awaitPlan(mixer)
                    mixer.setPlaying(true)
                    val deadline=System.nanoTime()+(end-start+5000)*1_000_000L
                    var sawRealOverlap=false
                    while(transitions.none{it.nextTrackId=="incoming"}&&faults.isEmpty()&&System.nanoTime()<deadline){
                        val state=mixer.diagnostics()
                        if(state.getValue("blendStarted").jsonPrimitive.boolean&&state.getValue("presentedFrames").jsonPrimitive.long>48000)sawRealOverlap=true
                        Thread.sleep(50)
                    }
                    mixer.setPlaying(false);val state=mixer.diagnostics();val anchor=checkNotNull(mixer.checkpoint())
                    assertTrue(faults.isEmpty(),faults.toString());assertTrue(transitions.any{it.nextTrackId=="incoming"},"The actual hardware clock never reached handoff")
                    assertTrue(sawRealOverlap,"The actual render path never entered overlap with genuine hardware progress")
                    assertEquals("incoming",anchor.currentEntryId);assertTrue(anchor.contentPositionMs>0)
                    assertTrue(state.getValue("presentedFrames").jsonPrimitive.long>48000)
                    assertEquals(0L,state.getValue("underrunFrames").jsonPrimitive.long)
                    assertTrue(state.getValue("decodedPeak").jsonPrimitive.float>.0001f)
                    assertTrue(state.getValue("producerMmcss").jsonPrimitive.boolean)
                    receipts+=buildJsonObject{put("kind",kind);put("seekMs",start-500);put("planStartMs",start);put("planEndMs",end);put("muted",true);put("hardwareFrames",state.getValue("presentedFrames"));put("underruns",state.getValue("underrunFrames"));put("actualDecodedPeak",state.getValue("decodedPeak"));put("incomingPositionMs",anchor.contentPositionMs)}
                }
                assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
            }finally{directory.deleteRecursively()}
        }
        File(root,"wasapi-result.json").writeText(buildJsonObject{put("status","PASS");put("scope","Real model-planned compressed song excerpts, real seek/replan, actual muted WASAPI clock and both-deck handoff; not audible loopback, app controls or session restoration");put("cases",JsonArray(receipts))}.toString())
    }

    @Test fun actualRecordedSmartBeatAlignedAndEchoPairsReachTheMixer(){
        val root=File(checkNotNull(System.getProperty("riff.recorded.dir")))
        val manifest=Json.parseToJsonElement(File(root,"manifest.json").readText()).jsonObject
        fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
        for(row in manifest.getValue("inputs").jsonArray){val value=row.jsonObject;assertEquals(value.getValue("encodedSha256").jsonPrimitive.content,digest(File(root,value.getValue("encodedFile").jsonPrimitive.content)))}
        data class Case(val mode:BlendMode,val outgoing:String,val incoming:String,val expectedKind:String)
        val cases=listOf(Case(BlendMode.Smart,"0.tail.flac","0.head.flac","Smart"),Case(BlendMode.Automix,"0.tail.flac","0.head.flac","BeatAligned"),Case(BlendMode.Automix,"0.tail.flac","1.head.flac","Echo"))
        val receipts=mutableListOf<JsonObject>()
        for(case in cases){
            val a=File(root,case.outgoing);val b=File(root,case.incoming)
            val incomingFrames=FfmpegPcmDecoder(b.path).use{decoder->val block=FloatArray(8192);var n=0L;while(true){val got=decoder.read(block,4096);if(got==0)break;n+=got};n}
            val analysis=kotlin.io.path.createTempDirectory("riff-recorded-analysis-").toFile()
            val submitted=AtomicLong();val presented=AtomicLong();val buffering=AtomicBoolean();val done=CountDownLatch(1)
            val faults=CopyOnWriteArrayList<AudioEvent.Fault>();var peak=0f
            val tap=object:PcmOutput{
                override val sampleRate=48000;override val channels=2
                @Volatile var active=false
                override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int{
                    for(i in offsetFrames*2 until (offsetFrames+frames)*2){assertTrue(samples[i].isFinite()&&kotlin.math.abs(samples[i])<=1.00001f);peak=maxOf(peak,kotlin.math.abs(samples[i]))}
                    submitted.addAndGet(frames.toLong());if(active)presented.set(submitted.get());return frames
                }
                override fun setPlaying(playing:Boolean){active=playing;if(playing)presented.set(submitted.get())}
                override fun reset(){submitted.set(0);presented.set(0);active=false}
                override fun presentedFrames()=presented.get()
                override fun close(){}
            }
            try{
                DesktopDualDeckMixer(outputFactory={tap},onFault={faults+=it;done.countDown()},onBufferingState={buffering.set(it.isBuffering)},
                    onPlaybackState={if(!it.isPlaying&&!buffering.get()&&it.currentTrackId=="incoming")done.countDown()},analysisDirectory=analysis).use{mixer->
                    mixer.volume=.4f;mixer.configureCanonicalDsp(EqualizerConfig(),1f,1f);mixer.configureBlend(case.mode)
                    mixer.prepareSource("outgoing",a.path);mixer.prepareSource("incoming",b.path);mixer.loadQueue(listOf("outgoing","incoming"),crossfade=true)
                    val reason=if(case.mode==BlendMode.Automix)"MODEL_PLAN"else"LIGHT_PLAN"
                    val deadline=System.nanoTime()+60_000_000_000L;var state=mixer.diagnostics()
                    while(state.getValue("blendReason").jsonPrimitive.content!=reason&&System.nanoTime()<deadline){Thread.sleep(50);state=mixer.diagnostics()}
                    assertEquals(reason,state.getValue("blendReason").jsonPrimitive.content,"Actual analysis did not prepare the real pair: $state")
                    assertEquals(case.expectedKind,state.getValue("blendKind").jsonPrimitive.content,"Real recording did not exercise the required transition branch: $state")
                    val start=state.getValue("blendStartMs").jsonPrimitive.long;val end=state.getValue("blendEndMs").jsonPrimitive.long
                    val entry=state.getValue("blendIncomingStartMs").jsonPrimitive.long
                    mixer.setPlaying(true);assertTrue(done.await(30,TimeUnit.SECONDS));assertTrue(faults.isEmpty(),faults.toString());assertTrue(peak>.0001f)
                    assertEquals(start*48+incomingFrames-entry*48,submitted.get(),"Real planned entry/end lost or inserted PCM")
                    receipts+=buildJsonObject{put("mode",case.mode.wire);put("kind",case.expectedKind);put("outgoingSha256",digest(a));put("incomingSha256",digest(b));put("analysis",reason);put("startMs",start);put("endMs",end);put("entryMs",entry);put("submittedFrames",submitted.get());put("peak",peak)}
                }
                assertContentEquals(longArrayOf(0,0),NativeAudio.handleCounts())
            }finally{analysis.deleteRecursively()}
        }
        File(root,"mixer-result.json").writeText(buildJsonObject{put("status","PASS");put("scope","Real song excerpts, real compressed decoding/model/plan, exact dual-deck submitted PCM counts; not full recordings, application controls, restoration, listening or endpoint loopback");put("cases",JsonArray(receipts))}.toString())
    }
}
