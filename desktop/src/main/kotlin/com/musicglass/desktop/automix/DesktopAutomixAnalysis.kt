package com.musicglass.desktop.automix

import com.musicglass.desktop.BufferedAudioDeck
import com.musicglass.desktop.FfmpegPcmDecoder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

internal enum class BeatAnalysisKind{Light,Model}
internal data class AnalysedEnvelope(val envelope:TrackEnvelope,val kind:BeatAnalysisKind,val cacheKey:String)
internal data class AnalysedPair(val outgoing:AnalysedEnvelope,val incoming:AnalysedEnvelope,val incomingInput:AnalysisFileLease)
internal sealed interface AutomixAnalysisState{
    data object Idle:AutomixAnalysisState
    data object WaitingForAcquiredAudio:AutomixAnalysisState
    data class Analysing(val part:String):AutomixAnalysisState
    data class Ready(val modelCovered:Boolean):AutomixAnalysisState
    data class Fallback(val code:String):AutomixAnalysisState
}
/** One low-priority analysis owner. Never opens a provider URL or causes a range/cache miss. */
internal class DesktopAutomixAnalysis(private val directory:File):AutoCloseable{
    private val generation=AtomicLong()
    private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"RiffAutomixAnalysis").apply{isDaemon=true;priority=Thread.MIN_PRIORITY}}
    private val store=AnalysisStore(File(directory,"envelopes"),version=ANALYSIS_VERSION_MODEL)
    private var work:Future<*>?=null
    private var runtime:DesktopBeatRuntime?=null
    private var modelUnavailable=false
    @Volatile private var closed=false
    @Volatile var state:AutomixAnalysisState=AutomixAnalysisState.Idle;private set
    init{check(directory.mkdirs()||directory.isDirectory)}
    @Synchronized fun cancel(){generation.incrementAndGet();work?.cancel(true);work=null;state=AutomixAnalysisState.Idle}
    @Synchronized fun prepare(outgoing:BufferedAudioDeck,incoming:BufferedAudioDeck,mode:BlendMode,onReady:(AnalysedPair)->Unit){
        check(!closed)
        cancel()
        if(mode==BlendMode.Crossfade)return
        val ticket=generation.get()
        state=AutomixAnalysisState.WaitingForAcquiredAudio
        work=executor.submit{
            var a:AnalysisFileLease?=null;var b:AnalysisFileLease?=null
            fun current(){if(closed||generation.get()!=ticket||Thread.currentThread().isInterrupted)throw InterruptedException("Automix analysis superseded")}
            fun status(value:AutomixAnalysisState){if(!closed&&generation.get()==ticket)state=value}
            try{
                fun input(deck:BufferedAudioDeck):AnalysisFileLease?{
                    return deck.analysisInput(File(directory,"analysis-input-${UUID.randomUUID()}.media"))
                }
                val deadline=System.nanoTime()+180_000_000_000L
                while(a==null||b==null){
                    current()
                    if(outgoing.isTerminated||incoming.isTerminated){status(AutomixAnalysisState.Fallback("DECODER_RETIRED"));return@submit}
                    if(a==null)a=input(outgoing)
                    if(b==null)b=input(incoming)
                    if(a==null||b==null){if(System.nanoTime()>=deadline){status(AutomixAnalysisState.Fallback("AUDIO_NOT_FULLY_ACQUIRED"));return@submit};Thread.sleep(2000)}
                }
                current()
                val first=checkNotNull(a).file;val second=checkNotNull(b).file
                val firstHash=digest(first,::current);val secondHash=digest(second,::current)
                status(AutomixAnalysisState.Analysing("outgoing tail"))
                val tail=analyse(first,firstHash,"tail",outgoing.durationMs,mode,::current)
                current();status(AutomixAnalysisState.Analysing("incoming head"))
                val head=analyse(second,secondHash,"head",incoming.durationMs,mode,::current)
                current()
                check(firstHash==digest(first,::current)&&secondHash==digest(second,::current)){"ANALYSIS_SOURCE_CHANGED"}
                store.write(tail.cacheKey,tail.envelope,tail.kind==BeatAnalysisKind.Model);store.write(head.cacheKey,head.envelope,head.kind==BeatAnalysisKind.Model)
                status(AutomixAnalysisState.Ready(tail.kind==BeatAnalysisKind.Model&&head.kind==BeatAnalysisKind.Model))
                current();onReady(AnalysedPair(tail,head,checkNotNull(b)))
            }catch(cancel:InterruptedException){Thread.currentThread().interrupt()}
            catch(cancel:kotlinx.coroutines.CancellationException){Thread.currentThread().interrupt()}
            catch(error:Exception){status(AutomixAnalysisState.Fallback(if(error.message=="ANALYSIS_SOURCE_CHANGED")"ANALYSIS_SOURCE_CHANGED"else"ANALYSIS_FAILED"))}
            finally{try{a?.close()}finally{b?.close()}}
        }
    }
    private fun analyse(file:File,sha:String,part:String,knownDuration:Long,mode:BlendMode,current:()->Unit):AnalysedEnvelope{
        val key="$sha:${com.musicglass.desktop.NativeAudio.version()}:windows-f32-s16-v1:$part"
        fun fits(value:TrackEnvelope):Boolean=knownDuration in 1..14400000&&kotlin.math.abs(value.durationMs-knownDuration)<=2000&&
            value.loudnessDb.size<=640&&value.startMs==(if(part=="tail")(value.durationMs-64000).coerceAtLeast(0)else 0L)&&
            value.endMs<=value.durationMs+100&&value.endMs-value.startMs<=(if(part=="tail")64000 else 30000)
        store.read(key,true)?.takeIf(::fits)?.let{return AnalysedEnvelope(it,BeatAnalysisKind.Model,key)}
        if(mode==BlendMode.Smart)store.read(key,false)?.takeIf(::fits)?.let{return AnalysedEnvelope(it,BeatAnalysisKind.Light,key)}
        current()
        val model=if(mode==BlendMode.Automix&&!modelUnavailable)try{runtime?:DesktopBeatRuntime().also{runtime=it}}catch(error:Exception){modelUnavailable=true;null}else null
        val successes=model?.successfulRuns?:0;val failures=model?.failedRuns?:0
        val result=FfmpegPcmDecoder(file.path).use{decoder->
            val duration=decoder.durationMs
            check(duration>0&&duration<=14400000&&knownDuration>0&&kotlin.math.abs(duration-knownDuration)<=2000){"ANALYSIS_DURATION_MISMATCH"}
            val from=if(part=="tail")(duration-64000).coerceAtLeast(0)else 0
            val length=if(part=="tail")64000L else 30000L
            if(from>0)check(decoder.seek(from*48)==from*48){"ANALYSIS_SEEK_FAILED"}
            val builder=EnvelopeBuilder(48000,2,from,model);val block=FloatArray(8192);val pcm=ShortArray(8192)
            var frames=0L;val deadline=System.nanoTime()+90_000_000_000L
            while(frames<length*48){
                current();check(System.nanoTime()<deadline){"ANALYSIS_DECODE_TIMEOUT"}
                val n=decoder.read(block,minOf(4096L,length*48-frames).toInt());if(n==0)break
                for(i in 0 until n*2){check(block[i].isFinite());pcm[i]=(block[i]*32768f).roundToInt().coerceIn(-32768,32767).toShort()}
                builder.add(pcm,n*2);frames+=n
            }
            check(frames>0);current();builder.build(duration)
        }
        current()
        val used=model!=null&&model.failedRuns==failures&&model.successfulRuns>successes
        return AnalysedEnvelope(result,if(used)BeatAnalysisKind.Model else BeatAnalysisKind.Light,key)
    }
    private fun digest(file:File,current:()->Unit):String{
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(65536);while(true){current();val n=input.read(buffer);if(n<0)break;sha.update(buffer,0,n)}}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    override fun close(){
        synchronized(this){if(closed)return;closed=true;cancel();executor.shutdownNow()}
        check(executor.awaitTermination(5,TimeUnit.SECONDS)){"Automix analysis did not stop"}
        runtime?.close();runtime=null
    }
}
