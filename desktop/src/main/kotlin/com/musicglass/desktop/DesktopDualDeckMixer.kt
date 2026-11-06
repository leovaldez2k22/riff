package com.musicglass.desktop

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import com.musicglass.desktop.automix.*

/** Two actual decoder decks, Float32 DSP and native output. No generated-source fallback. */
internal class DesktopDualDeckMixer(
    private val outputFactory: () -> PcmOutput = { WasapiPcmOutput() },
    private val decoderFactory: PcmDecoderFactory = PcmDecoderFactory { FfmpegPcmDecoder(it) },
    val onClockAnchor: (AudioEvent.ClockAnchor) -> Unit = {},
    val onSpectrumFrame: (AudioEvent.FeatureSpectrumFrame) -> Unit = {},
    val onTrackTransition: (AudioEvent.TrackTransition) -> Unit = {},
    val onBufferingState: (AudioEvent.BufferingState) -> Unit = {},
    val onFault: (AudioEvent.Fault) -> Unit = {},
    val onPlaybackState: (AudioEvent.PlaybackStateChanged) -> Unit = {},
    private val analysisDirectory:java.io.File=java.io.File(DesktopPaths.root,"automix")
) : AutoCloseable {
    val equalizer = DesktopEqualizer()
    val volumeBoost = DesktopVolumeBoost()
    val echo = DesktopEcho()
    private val dsp = FloatDesktopDsp()
    private var canonicalDsp=false
    private var normalizationEnabled=false
    private var primaryEq=CanonicalFloatEqualizer()
    private var secondaryEq=CanonicalFloatEqualizer()
    private val sequence = AtomicLong()
    private val epoch = AtomicLong(1)
    private val commands = ArrayBlockingQueue<() -> Unit>(64)
    private val ownedDeckGuard=Any()
    private val ownedDecks=linkedSetOf<BufferedAudioDeck>()
    private val sourceRates=java.util.concurrent.ConcurrentHashMap<String,Float>()
    private fun deck(entry:String,input:AnalysisFileLease?=null,originalKind:String?=null,loudness:Double?=null):BufferedAudioDeck=synchronized(ownedDeckGuard){
        check(running){"Audio engine closed before decoder creation"}
        val rate=sourceRates[entry]?:playbackRate
        val retained=input?.retain()
        val deck=try{BufferedAudioDeck(retained?.file?.path?:sources[entry]?:entry,PcmDecoderFactory{path->
            val opened=decoderFactory.open(path)
            val decoded=if(retained==null)opened else object:PcmDecoder by opened{
                override val sourceKind=originalKind?:opened.sourceKind
                override val loudnessDb=loudness
                override fun retainAnalysisInput()=retained.retain()
            }
            if(rate==1f)decoded else TempoDecoder(decoded,rate)
        }){deck->try{retained?.close()}finally{synchronized(ownedDeckGuard){ownedDecks.remove(deck)}}}}catch(error:Throwable){retained?.close();throw error}
        ownedDecks.add(deck);if(deck.isTerminated)ownedDecks.remove(deck)
        deck
    }
    private val defaultTransitionHints=TransitionHints()
    private val transitionHints=java.util.concurrent.ConcurrentHashMap<String,TransitionHints>()
    private var blendMode=BlendMode.Crossfade
    private var blendRevision=0L
    private val analysisGuard=Any()
    @Volatile private var blendAnalysis:DesktopAutomixAnalysis?=null
    private data class PreparedBlend(val outgoing:BufferedAudioDeck,val incoming:BufferedAudioDeck,val renderer:DesktopTransitionRenderer,val kind:TransitionKind)
    private var preparedBlend:PreparedBlend?=null
    private var blendStarted=false
    private var blendReason="CROSSFADE"
    /** Model/session creation and directory IO are deliberately outside the audio producer. */
    fun configureBlend(mode:BlendMode,durationMs:Int=6000){
        require(durationMs in 1000..12000)
        if(mode!=BlendMode.Crossfade)synchronized(analysisGuard){check(running);if(blendAnalysis==null)blendAnalysis=DesktopAutomixAnalysis(analysisDirectory)}
        control{
            blendMode=mode;crossfadeDurationMs=durationMs
            // A live transition owns its immutable plan until handoff; settings apply to the next pair.
            if(!blendStarted&&preparedBlend==null){invalidateBlend();analysePair()}
        }
    }
    private fun invalidateBlend(){
        blendRevision++;blendAnalysis?.cancel();preparedBlend=null;blendStarted=false;blendReason=if(blendMode==BlendMode.Crossfade)"CROSSFADE"else"ANALYSIS_PENDING"
        primaryEq.blendShelves(0f,0f);secondaryEq.blendShelves(0f,0f)
    }
    private fun pairIsMusic():Boolean=(queue.getOrNull(queueIndex)?.let{transitionHints[it]}?:defaultTransitionHints).isMusic&&
        (nextIndex()?.let{transitionHints[queue[it]]}?:defaultTransitionHints).isMusic
    private fun smartEligible(a:BufferedAudioDeck,b:BufferedAudioDeck):Boolean{
        val first=queue.getOrNull(queueIndex)?.let{transitionHints[it]}?:defaultTransitionHints
        val second=nextIndex()?.let{transitionHints[queue[it]]}?:defaultTransitionHints
        return blendMode!=BlendMode.Crossfade&&a.playbackRate==1f&&b.playbackRate==1f&&first.isMusic&&second.isMusic&&!first.classical&&!second.classical&&
            !albumInOrder(first.album,first.trackNumber,second.album,second.trackNumber)
    }
    private fun analysePair(){
        val a=deckA?:return;val b=deckB?:return;val owner=blendAnalysis?:return
        if(!isCrossfadeEnabled||!smartEligible(a,b))return
        val revision=blendRevision;val mode=blendMode;val incomingId=nextIndex()?.let{queue[it]}?:return
        val first=queue.getOrNull(queueIndex)?.let{transitionHints[it]}?:defaultTransitionHints
        val second=nextIndex()?.let{transitionHints[queue[it]]}?:defaultTransitionHints
        val normalize=normalizationEnabled
        owner.prepare(a,b,mode){analysed->
            val gainA=normalizationGain(normalize,a.loudnessDb);val gainB=normalizationGain(normalize,b.loudnessDb)
            val plan=planSmartTransition(analysed.outgoing.envelope,analysed.incoming.envelope,onBeat=mode==BlendMode.Automix,
                outgoingVocals=first.vocals.takeIf{it.isNotEmpty()},incomingVocals=second.vocals.takeIf{it.isNotEmpty()},
                outgoingGainDb=(20*log10(gainA)).toFloat(),incomingGainDb=(20*log10(gainB)).toFloat())?:return@prepare
            val renderer=DesktopTransitionRenderer(plan,true)
            // Seek a separate owned, fully acquired source. The live incoming SPSC ring is untouched.
            val replacement=deck(incomingId,analysed.incomingInput,b.sourceKind,b.loudnessDb)
            var transferred=false
            try{
                replacement.durationMs
                if(plan.incomingStartMs>0)check(replacement.seekTo(plan.incomingStartMs*48)==plan.incomingStartMs*48)
                check(replacement.playbackRate==1f)
                val offered=commands.offer({
                    if(running&&revision==blendRevision&&deckA===a&&deckB===b&&!blendStarted&&a.contentFrame+4800<renderer.startFrame){
                        deckB=replacement;b.close();secondaryEq.blendShelves(0f,0f);secondaryEq.reset()
                        preparedBlend=PreparedBlend(a,replacement,renderer,if(plan.echo!=null)TransitionKind.Echo else if(plan.onBeat)TransitionKind.BeatAligned else TransitionKind.Smart)
                        blendReason=if(analysed.outgoing.kind==BeatAnalysisKind.Model&&analysed.incoming.kind==BeatAnalysisKind.Model)"MODEL_PLAN"else"LIGHT_PLAN"
                    }else replacement.close()
                })
                transferred=offered
            }finally{if(!transferred)replacement.close()}
        }
    }
    private fun selectedBlend(a:BufferedAudioDeck,b:BufferedAudioDeck):PreparedBlend?{
        preparedBlend?.takeIf{it.outgoing===a&&it.incoming===b}?.let{return it}
        if(!smartEligible(a,b)||a.knownDurationMs<=0)return null
        val end=a.knownDurationMs;val start=(end-SMART_FALLBACK_BLEND_MS).coerceAtLeast(0)
        if(end<=start)return null
        return PreparedBlend(a,b,DesktopTransitionRenderer(SmartPlan(start,end,0),true),TransitionKind.Smart).also{
            preparedBlend=it;blendReason="ANALYSIS_NOT_READY_SMART_TIMING"
        }
    }
    private val sources = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var running = true
    @Volatile private var playing = false
    @Volatile private var currentPosition = 0L
    @Volatile private var currentId: String? = null
    @Volatile var volume = .8f
        set(value) { require(value.isFinite() && value in 0f..1f); field = value }
    var playbackRate: Float = 1f
        set(value) { check(!playing){"Pause before changing the default decoder speed"};field=checkedPlaybackRate(value) }
    @Volatile var isCrossfadeEnabled = false
    var crossfadeDurationMs = 2000
    @Volatile private var output: PcmOutput? = null
    private var producerScheduling:AutoCloseable?=null
    @Volatile private var outputActive=false
    @Volatile private var buffering=false
    private var deckA: BufferedAudioDeck? = null
    private var deckB: BufferedAudioDeck? = null
    private var primaryMixGain=1.0
    private var recoveryGain=1.0
    private var recoveryStep=0.0
    private var recoveryFrames=0
    private fun resetTransitionGain(){primaryMixGain=1.0;recoveryGain=1.0;recoveryFrames=0;invalidateBlend()}
    /** A speculative next source cannot tear down valid current playback. Stop at its boundary; do not silently skip. */
    private fun rejectNext(next:BufferedAudioDeck){
        if(deckB!==next)return
        if(primaryMixGain<1.0){recoveryGain=primaryMixGain;recoveryFrames=240;recoveryStep=(1.0-recoveryGain)/240}
        invalidateBlend();deckB=null;next.close();secondaryEq.reset()
        onFault(AudioEvent.Fault(sequence.incrementAndGet(),epoch.get(),"NEXT_TRACK_UNAVAILABLE","The next track is unavailable. The current track will finish; choose another track to continue.",true))
    }
    private fun seekSecondary(frame:Long){
        val next=deckB?:return
        try{next.seekTo(frame)}catch(error:Exception){control{rejectNext(next)}}
    }

    private var queue = emptyList<String>()
    private var queueIndex = 0
    private var sleep=SleepPlan()
    private var sleepGeneration=0L
    private var cycle=0L
    private var anchorCycle=0L
    private var sleepDraining=false
    fun configureSleep(plan:SleepPlan)=control{sleep=plan.restored(System.currentTimeMillis());sleepGeneration++;sleepDraining=false;emitClockAnchor()}
    private var repeatMode = RepeatMode.Off
    private fun nextIndex(): Int? = when { repeatMode==RepeatMode.One -> queueIndex; queueIndex+1<queue.size -> queueIndex+1; repeatMode==RepeatMode.All && queue.isNotEmpty() -> 0; else -> null }
    private val operationGeneration=AtomicLong()
    private val playbackIntent=AtomicLong()
    private var decodedFrames=0L
    private var decodedPeak=0f
    fun cancelPendingOperation(){operationGeneration.incrementAndGet();setPlaying(false);sources.keys.retainAll(queue.toSet());sourceRates.keys.retainAll(queue.toSet());transitionHints.keys.retainAll(queue.toSet())}
    fun diagnostics()=control{kotlinx.serialization.json.buildJsonObject{
        put("primarySourceKind",kotlinx.serialization.json.JsonPrimitive(deckA?.sourceKind?:"none"));put("secondarySourceKind",kotlinx.serialization.json.JsonPrimitive(deckB?.sourceKind?:"none"))
        put("primaryIsMusic",kotlinx.serialization.json.JsonPrimitive((queue.getOrNull(queueIndex)?.let{transitionHints[it]}?:defaultTransitionHints).isMusic))
        put("normalizationEnabled",kotlinx.serialization.json.JsonPrimitive(normalizationEnabled));put("primaryLoudnessDb",deckA?.loudnessDb?.let{kotlinx.serialization.json.JsonPrimitive(it)}?:kotlinx.serialization.json.JsonNull);put("primaryNormalizationGain",kotlinx.serialization.json.JsonPrimitive(normalizationGain(normalizationEnabled,deckA?.loudnessDb)));put("secondaryNormalizationGain",kotlinx.serialization.json.JsonPrimitive(normalizationGain(normalizationEnabled,deckB?.loudnessDb)))
        put("tempoHandles",kotlinx.serialization.json.JsonPrimitive(NativeAudio.tempoHandleCount()));put("playbackRate",kotlinx.serialization.json.JsonPrimitive(deckA?.playbackRate?:1f))
        put("blendMode",kotlinx.serialization.json.JsonPrimitive(blendMode.wire));put("blendReason",kotlinx.serialization.json.JsonPrimitive(blendReason));put("blendStarted",kotlinx.serialization.json.JsonPrimitive(blendStarted));put("blendKind",kotlinx.serialization.json.JsonPrimitive(preparedBlend?.kind?.name?:"Plain"));put("blendOnBeat",kotlinx.serialization.json.JsonPrimitive(preparedBlend?.renderer?.plan?.onBeat==true));put("blendAnalysis",kotlinx.serialization.json.JsonPrimitive(blendAnalysis?.state.toString()));put("blendStartMs",kotlinx.serialization.json.JsonPrimitive(preparedBlend?.renderer?.plan?.startMs?:-1));put("blendEndMs",kotlinx.serialization.json.JsonPrimitive(preparedBlend?.renderer?.plan?.endMs?:-1));put("blendIncomingStartMs",kotlinx.serialization.json.JsonPrimitive(preparedBlend?.renderer?.plan?.incomingStartMs?:-1))
        put("canonicalEqualizer",kotlinx.serialization.json.JsonPrimitive(canonicalDsp));put("equalizerMode",kotlinx.serialization.json.JsonPrimitive(primaryEq.configuration.mode.key));put("equalizerEnabled",kotlinx.serialization.json.JsonPrimitive(primaryEq.configuration.enabled))
        val scheduling=output?.schedulingTelemetry()?:LongArray(8)
        put("producerMmcss",kotlinx.serialization.json.JsonPrimitive(output?.producerSchedulingActive==true))
        listOf("maxProducerGapUs","maxRenderGapUs","firstUnderrunUs","underrunProducerGapUs","underrunPaddingFrames","endpointBufferFrames","endpointPaddingFrames","nativeQueuedFrames").forEachIndexed{index,key->put(key,kotlinx.serialization.json.JsonPrimitive(scheduling.getOrElse(index){0}))}
        put("renderedFrames",kotlinx.serialization.json.JsonPrimitive(rendered));put("presentedFrames",kotlinx.serialization.json.JsonPrimitive(output?.presentedFrames()?:0));put("underrunFrames",kotlinx.serialization.json.JsonPrimitive(output?.underrunFrames()?:0));put("decodedFrames",kotlinx.serialization.json.JsonPrimitive(decodedFrames));put("decodedPeak",kotlinx.serialization.json.JsonPrimitive(decodedPeak));put("nativeDecoders",kotlinx.serialization.json.JsonPrimitive(NativeAudio.handleCounts()[0]));put("nativeSinks",kotlinx.serialization.json.JsonPrimitive(NativeAudio.handleCounts()[1]));put("processHandles",kotlinx.serialization.json.JsonPrimitive(NativeAudio.processHandleCount()));put("heapBytes",kotlinx.serialization.json.JsonPrimitive(java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage.used))
    }}
    private var rendered = 0L
    private var anchorTrack: String? = null
    private var transitionUnderruns=0L
    private var starvedSinceTransition=false
    @Volatile private var latestAnchor:AudioEvent.ClockAnchor?=null
    fun checkpoint()=latestAnchor
    private data class Span(val start: Long, val end: Long, val id: String, val sourceFrame: Long, val secondaryId: String? = null, val secondaryStart: Long = 0,val cycle:Long=0,val sleepGeneration:Long=0,val sleepRemaining:Int=0,val rateUnits:Long=100,val secondaryRateUnits:Long=100)
    private val spans = java.util.ArrayDeque<Span>()
    private class AnalysisSlot { val samples=ShortArray(1024);var frame=0L;var epoch=0L;@Volatile var ready=false }
    private val analysisSlots=Array(24){AnalysisSlot()}
    private var analysisIndex=0
    private var startAck: CompletableFuture<Unit>? = null
    private val thread = Thread({ loop() }, "RiffFloatMixer").apply { isDaemon = true; start() }
    private val fft = Thread({
        while (running) {
            val presented=try{output?.presentedFrames()?:0}catch(_:AudioDeviceLostException){Thread.sleep(20);continue}
            for(slot in analysisSlots) if(slot.ready && (slot.epoch!=epoch.get() || slot.frame<=presented)) {
                if(slot.epoch==epoch.get()) {
                    val result=DesktopFFT.analyze(slot.samples,2)
                    onSpectrumFrame(AudioEvent.FeatureSpectrumFrame(sequence.incrementAndGet(),slot.epoch,System.nanoTime(),result.first,result.second))
                }
                slot.ready=false
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
    }, "RiffPostDspAnalysis").apply { isDaemon = true; start() }
    private fun <T> control(action: () -> T): T {
        check(running) { "Audio engine closed" }
        val done = CompletableFuture<T>()
        check(commands.offer({ try { done.complete(action()) } catch (t: Exception) { done.completeExceptionally(t) } }, 2, TimeUnit.SECONDS)) { "Audio command queue full" }
        return done.get(35, TimeUnit.SECONDS)
    }
    private fun open(entry: String): BufferedAudioDeck {
        val deck=deck(entry)
        try {deck.durationMs;return deck} catch(t:Throwable){deck.close();throw t}
    }
    fun prepareSource(trackId:String,streamUrl:String,rate:Float=1f,hints:TransitionHints=TransitionHints()){require(trackId.isNotBlank()&&streamUrl.isNotBlank());sourceRates[trackId]=checkedPlaybackRate(rate);transitionHints[trackId]=checkedTransitionHints(hints);sources[trackId]=streamUrl}
    fun loadQueue(trackIds: List<String>, startIndex: Int = 0, startPositionMs: Long = 0, secondaryPositionMs: Long = 0, crossfade: Boolean = false, repeat: RepeatMode = RepeatMode.Off) {
        require(startPositionMs in 0..86_400_000 && secondaryPositionMs in 0..86_400_000)
        require(trackIds.size <= 5000 && (trackIds.isEmpty() || startIndex in trackIds.indices))
        val operation=operationGeneration.get()
        val a = trackIds.getOrNull(startIndex)?.let(::open)
        try {
            if (startPositionMs > 0) a?.seekTo(startPositionMs * 48)
            control {
                check(operation==operationGeneration.get()){"Audio queue operation was superseded"}
                playing = false; output?.setPlaying(false);outputActive=false; output?.reset()
                deckA?.close(); deckB?.close(); deckA = a; deckB = null
                sources.keys.retainAll(trackIds.toSet());sourceRates.keys.retainAll(trackIds.toSet());transitionHints.keys.retainAll(trackIds.toSet())
                queue = trackIds.toList(); queueIndex = startIndex;cycle=0;anchorCycle=0;sleepDraining=false; repeatMode=repeat; rendered = 0; spans.clear()
                currentId = trackIds.getOrNull(startIndex); anchorTrack = currentId; currentPosition = startPositionMs
                epoch.incrementAndGet(); dsp.reset();primaryEq.reset();secondaryEq.reset();resetTransitionGain();transitionUnderruns=output?.underrunFrames()?:0;starvedSinceTransition=false
            }
            isCrossfadeEnabled = crossfade
            prepareNext()
            if (secondaryPositionMs > 0) seekSecondary(secondaryPositionMs * 48)
            isCrossfadeEnabled = crossfade
            control{emitClockAnchor()}
        } catch (t: Exception) { a?.close(); throw t }
    }
    private fun prepareNext() {
        val id = nextIndex()?.let{queue[it]} ?: return
        val next = deck(id)
        control { deckB?.close(); deckB = next;analysePair() }
    }
    fun setPlaying(value: Boolean) {
        playbackIntent.incrementAndGet()
        val ack=CompletableFuture<Unit>()
        control {
            if(value) { check(deckA!=null){"No playable track is loaded"};if(output==null){
                val created=outputFactory()
                try{producerScheduling=created.acquireProducerScheduling();output=created}catch(error:Throwable){created.close();throw error}
            } }
            playing=value
            if(!value) { output?.setPlaying(false);outputActive=false;buffering=false;onBufferingState(AudioEvent.BufferingState(sequence.incrementAndGet(),epoch.get(),false,currentPosition));emitClockAnchor();ack.complete(Unit) }
            else {
                startAck=ack
                if(rendered>(output?.presentedFrames()?:0))startOutput()
            }
        }
        ack.get(30,TimeUnit.SECONDS)
        onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),isPlaying(),currentId,currentPosition))
    }
    private fun startOutput() {
        checkNotNull(output).setPlaying(true);outputActive=true
        if(buffering){buffering=false;onBufferingState(AudioEvent.BufferingState(sequence.incrementAndGet(),epoch.get(),false,currentPosition));onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),true,currentId,currentPosition))}
        startAck?.complete(Unit);startAck=null
    }
    fun signalTimeout() {
        playing=false;running=false;thread.interrupt()
        onFault(AudioEvent.Fault(sequence.incrementAndGet(),epoch.get(),"AUDIO_ENGINE_FAILED","The audio engine timed out; playback is paused",true))
    }
    fun isPlaying() = playing && outputActive && !buffering
    fun seek(positionMs: Long, requestedEpoch: Long = epoch.incrementAndGet()) {
        val intent=playbackIntent.get();val operation=operationGeneration.get()
        require(positionMs in 0..86_400_000)
        val wasPlaying = playing
        control { playing = false; output?.setPlaying(false);outputActive=false }
        try {
            val actualFrame=checkNotNull(deckA).seekTo(positionMs * 48)
            seekSecondary(0)
            control {
                output?.reset(); rendered = 0; spans.clear(); currentPosition = actualFrame/48
                check(operation==operationGeneration.get()){"Seek was superseded"};epoch.set(maxOf(epoch.get() + 1, requestedEpoch)); dsp.reset();primaryEq.reset();secondaryEq.reset();resetTransitionGain(); if(intent==playbackIntent.get())playing = wasPlaying;analysePair(); emitClockAnchor()
            }
        } catch (t: Exception) { control { playing = false }; throw t }
    }
    fun configureCanonicalDsp(configuration:EqualizerConfig,boost:Float,trim:Float,normalize:Boolean=false){
        require(boost.isFinite()&&boost in 1f..3f&&trim.isFinite()&&trim in .1f..1f)
        val target=prepareEqualizer(configuration)
        control{canonicalDsp=true;normalizationEnabled=normalize;primaryEq.configure(target);secondaryEq.configure(target);dsp.configure(FloatArray(10),false);dsp.gain=boost*trim}
    }
    fun configureDsp() = control {
        canonicalDsp=false
        dsp.configure(equalizer.bandsDb.copyOf(), equalizer.enabled)
        dsp.gain = volumeBoost.targetGain * volumeBoost.trim
    }
    fun getCurrentPositionMs() = currentPosition
    fun getCurrentTrackId() = currentId
    fun getTimelineEpoch() = epoch.get()
    fun getDurationMs() = deckA?.durationMs ?: 0L
    fun getCodecName() = deckA?.codecName ?: "none"
    private fun emitClockAnchor() {
        val presented = output?.presentedFrames() ?: 0
        while (spans.size > 1 && spans.first.end <= presented) spans.removeFirst()
        spans.firstOrNull()?.let { span ->
            currentPosition = (span.sourceFrame + (presented - span.start).coerceIn(0, span.end - span.start)*span.rateUnits/100) / 48
            currentId = span.id
            if (anchorTrack != span.id || anchorCycle!=span.cycle) {
                val underruns=output?.underrunFrames()?:0
                val contiguous=!starvedSinceTransition && underruns==transitionUnderruns
                onTrackTransition(AudioEvent.TrackTransition(sequence.incrementAndGet(), epoch.get(), anchorTrack.orEmpty(), span.id, contiguous))
                transitionUnderruns=underruns;starvedSinceTransition=false
                anchorTrack = span.id;anchorCycle=span.cycle
            }
        }
        val span = spans.firstOrNull()
        val pendingSecondary=if(span==null && isCrossfadeEnabled && (deckB?.contentFrame?:0)>0)nextIndex()?.let{queue[it]} else null
        val secondaryPosition = if(span?.secondaryId != null) (span.secondaryStart + (presented-span.start).coerceIn(0,span.end-span.start)*span.secondaryRateUnits/100)/48 else if(pendingSecondary!=null)(deckB?.contentFrame?:0)/48 else 0
        val anchor=AudioEvent.ClockAnchor(sequence.incrementAndGet(), epoch.get(), currentPosition, System.nanoTime(), 48000, presented, ((rendered-presented).coerceAtLeast(0)/48f), (span?.rateUnits?:kotlin.math.round((deckA?.playbackRate?:1f)*100).toLong())/100f, currentId, span?.secondaryId?:pendingSecondary, secondaryPosition,if(sleep.mode==SleepMode.Tracks&&span!=null&&span.sleepGeneration==sleepGeneration&&span.sleepRemaining>0)sleep.copy(songsLeft=span.sleepRemaining)else sleep)
        latestAnchor=anchor;onClockAnchor(anchor)
    }
    private fun loop() {
        val a = FloatArray(1024 * 2); val b = FloatArray(1024 * 2)
        var lastAnchor = 0L
        try {
            while (running) {
                while (true) { val command = commands.poll() ?: break; command() }
                if (!playing || deckA == null) { Thread.sleep(3); continue }
                val out = checkNotNull(output)
                if (rendered - out.presentedFrames() > 16800) { Thread.sleep(2); if (System.nanoTime()-lastAnchor > 100_000_000) { emitClockAnchor(); lastAnchor=System.nanoTime() }; continue }
                if(sleep.mode==SleepMode.Deadline&&System.currentTimeMillis()>=sleep.deadlineMs){
                    sleep=SleepPlan();playing=false;out.setPlaying(false);outputActive=false;emitClockAnchor();onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),false,currentId,currentPosition));continue
                }
                deckB?.takeIf{it.decodeFailure!=null}?.let{rejectNext(it)}
                val primary = checkNotNull(deckA); val start = primary.contentFrame
                // Never consume A before knowing that the same real frames from B exist.
                // A stalled incoming decoder must freeze both timelines, not fade A into zeroes.
                var requested=1024
                val candidate=deckB
                val fadeAllowed=isCrossfadeEnabled&&pairIsMusic()&&sleep.mode!=SleepMode.Tracks&&sleep.mode!=SleepMode.Queue&&candidate!=null&&primary.knownDurationMs>0
                val planned=if(fadeAllowed)selectedBlend(primary,checkNotNull(candidate))else null
                val plannedEnd=planned?.renderer?.endFrame
                if(fadeAllowed){
                    val boundary=planned?.renderer?.startFrame?:(primary.knownDurationMs-crossfadeDurationMs).coerceAtLeast(0)*48
                    val units=kotlin.math.round(primary.playbackRate*100).toLong()
                    if(start<boundary)requested=minOf(requested,((boundary-start)*100/units).coerceAtLeast(1).toInt())
                    else if(start<(plannedEnd?:primary.knownDurationMs*48)){
                        val available=checkNotNull(candidate).availableFrames
                        if(available==0){
                            if(candidate.exhausted||candidate.decodeFailure!=null){rejectNext(candidate);continue}
                            if(rendered-out.presentedFrames()<=512&&!buffering){
                                out.setPlaying(false);outputActive=false;buffering=true;starvedSinceTransition=true;emitClockAnchor()
                                onBufferingState(AudioEvent.BufferingState(sequence.incrementAndGet(),epoch.get(),true,currentPosition))
                                onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),false,currentId,currentPosition))
                            }
                            Thread.sleep(2);continue
                        }
                        requested=minOf(requested,available)
                        if(plannedEnd!=null)requested=minOf(requested,(plannedEnd-start).coerceAtLeast(1).toInt())
                    }
                }
                val count = if(plannedEnd!=null&&start>=plannedEnd)-1 else primary.readInto(a, requested)
                if (count < 0) {
                    if(!sleepDraining&&((sleep.mode==SleepMode.Tracks&&sleep.songsLeft==1)||(sleep.mode==SleepMode.Queue&&queueIndex==queue.lastIndex))){deckB?.close();deckB=null;sleepDraining=true}
                    if (deckB == null) {
                        if(!outputActive && rendered>0)startOutput()
                        if (out.presentedFrames() < rendered) { Thread.sleep(2); emitClockAnchor(); continue }
                        playing = false; out.setPlaying(false);outputActive=false;if(sleepDraining){sleep=SleepPlan();sleepDraining=false}; emitClockAnchor()
                        onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),false,currentId,currentPosition)); continue
                    }
                    deckA?.close(); deckA = deckB; deckB = null;val oldEq=primaryEq;primaryEq=secondaryEq;secondaryEq=oldEq;secondaryEq.reset();resetTransitionGain(); queueIndex=checkNotNull(nextIndex());cycle++;if(sleep.mode==SleepMode.Tracks&&sleep.songsLeft>1)sleep=sleep.copy(songsLeft=sleep.songsLeft-1)
                    val next = nextIndex()?.let{queue[it]}
                    if (next != null) deckB = deck(next)
                    analysePair()
                    continue
                }
                if (count == 0) {
                    if(rendered-out.presentedFrames()>512) {Thread.sleep(2);continue}
                    if(!buffering) {
                        out.setPlaying(false);outputActive=false;buffering=true;starvedSinceTransition=true;emitClockAnchor()
                        onBufferingState(AudioEvent.BufferingState(sequence.incrementAndGet(),epoch.get(),true,currentPosition))
                        onPlaybackState(AudioEvent.PlaybackStateChanged(sequence.incrementAndGet(),epoch.get(),false,currentId,currentPosition))
                    }
                    Thread.sleep(3); continue
                }
                primary.normalize(a,count,normalizationEnabled)
                val plannedActive=planned!=null&&start>=planned.renderer.startFrame
                if(canonicalDsp&&!plannedActive)primaryEq.process(a,count,primary.sourceChannels==2)
                val next = deckB
                var secondaryId: String? = null
                val secondaryStart = next?.contentFrame ?: 0
                if (isCrossfadeEnabled && pairIsMusic() && sleep.mode!=SleepMode.Tracks&&sleep.mode!=SleepMode.Queue && next != null && primary.knownDurationMs > 0) {
                    val remaining = primary.knownDurationMs * 48 - start
                    val fade = crossfadeDurationMs * 48L;val fadeRateUnits=kotlin.math.round(primary.playbackRate*100).toLong()
                    if (if(planned!=null)plannedActive else remaining in 1..fade) {
                        val got = try{next.readInto(b,count)}catch(error:java.io.IOException){rejectNext(next);0}
                        if(got>0)next.normalize(b,got,normalizationEnabled)
                        if(got>0&&canonicalDsp&&!plannedActive)secondaryEq.process(b,got,next.sourceChannels==2)
                        if(got > 0) secondaryId = nextIndex()?.let{queue[it]}
                        if(got>0)check(got==count){"Incoming decoder availability changed during a blend"}
                        if(got>0&&plannedActive){
                            blendStarted=true
                            checkNotNull(planned).renderer.render(a,b,count,got,start,primaryEq,secondaryEq,primary.sourceChannels==2,next.sourceChannels==2,volume)
                            val p=((start+count-planned.renderer.startFrame).toDouble()/(planned.renderer.endFrame-planned.renderer.startFrame)).coerceIn(0.0,1.0)
                            primaryMixGain=if(planned.kind==TransitionKind.Echo)1.0 else cos(p*PI/2)
                        }
                        else if (got > 0) for (frame in 0 until count) {
                            val progress = (1.0 - (remaining - frame*fadeRateUnits/100).toDouble() / fade).coerceIn(0.0, 1.0)
                            primaryMixGain=cos(progress*PI/2)
                            for (ch in 0..1) a[frame*2+ch] = (a[frame*2+ch]*cos(progress*PI/2) + (if(frame<got)b[frame*2+ch] else 0f)*sin(progress*PI/2)).toFloat()
                        }
                    }
                }
                if(recoveryFrames>0)for(frame in 0 until count){
                    if(recoveryFrames==0)break
                    recoveryGain=(recoveryGain+recoveryStep).coerceAtMost(1.0)
                    for(ch in 0..1)a[frame*2+ch]=(a[frame*2+ch]*recoveryGain).toFloat()
                    recoveryFrames--;if(recoveryFrames==0)primaryMixGain=1.0
                }
                decodedFrames+=count
                for(index in 0 until count*2)decodedPeak=maxOf(decodedPeak,kotlin.math.abs(a[index]))
                val remaining=when(sleep.mode){
                    SleepMode.Deadline->sleep.deadlineMs-System.currentTimeMillis()
                    SleepMode.Tracks->if(sleep.songsLeft==1&&primary.durationMs>0)primary.durationMs-start/48 else Long.MAX_VALUE
                    SleepMode.Queue->if(queueIndex==queue.lastIndex&&primary.durationMs>0)primary.durationMs-start/48 else Long.MAX_VALUE
                    SleepMode.Off->Long.MAX_VALUE
                }
                val gain=if(remaining==Long.MAX_VALUE)1f else sleepFadeGain(remaining,if(sleep.mode==SleepMode.Deadline)sleep.fadeMs else minOf(sleep.fadeMs,primary.durationMs))
                dsp.volume = volume*gain; dsp.process(a, count)
                spans.addLast(Span(rendered, rendered + count, queue[queueIndex], start, secondaryId, secondaryStart,cycle,sleepGeneration,sleep.songsLeft,kotlin.math.round(primary.playbackRate*100).toLong(),kotlin.math.round((next?.playbackRate?:1f)*100).toLong()))
                var sent = 0
                while (sent < count && running) {
                    val accepted = out.write(a, sent, count - sent)
                    if (accepted == 0) Thread.sleep(1) else sent += accepted
                }
                rendered += count
                if(!outputActive && rendered-out.presentedFrames()>=4800)startOutput()
                val slot=analysisSlots[analysisIndex]
                if(!slot.ready && count>=512) {
                    for(i in slot.samples.indices)slot.samples[i]=(a[i]*32767).toInt().coerceIn(-32768,32767).toShort()
                    slot.frame=rendered;slot.epoch=epoch.get();slot.ready=true;analysisIndex=(analysisIndex+1)%analysisSlots.size
                }
                if (System.nanoTime() - lastAnchor > 100_000_000) { lastAnchor = System.nanoTime(); emitClockAnchor() }
            }
        } catch (t: Throwable) {
            startAck?.completeExceptionally(t)
            val failed=running
            running = false; playing = false
            if(failed){val failure=generateSequence<Throwable>(t){it.cause}.filterIsInstance<AudioOperationException>().firstOrNull();onFault(AudioEvent.Fault(sequence.incrementAndGet(),epoch.get(),failure?.code?:"AUDIO_ENGINE_FAILED",failure?.message?:"Audio decoding or output failed; check the file, connection or Windows audio device",true))}
        } finally {
            try{output?.close()}finally{try{producerScheduling?.close()}finally{deckA?.close();deckB?.close()}}
        }
    }
    override fun close(){
        val closing=synchronized(ownedDeckGuard){running=false;ownedDecks.toList()};blendAnalysis?.close();thread.interrupt();fft.interrupt();thread.join(5000);fft.join(1000)
        check(!thread.isAlive){"Mixer shutdown did not complete within five seconds"}
        // Transition retirement stays nonblocking; final shutdown waits for actual decoder/native ownership release.
        val deadline=System.nanoTime()+5000000000L
        for(deck in closing){
            deck.close();val remaining=((deadline-System.nanoTime())/1000000).coerceAtLeast(0)
            check(deck.awaitTermination(remaining)){"Decoder shutdown did not complete within five seconds"}
        }
        synchronized(ownedDeckGuard){ownedDecks.removeIf{it.isTerminated};check(ownedDecks.isEmpty()){"Decoder ownership remains after shutdown: ${ownedDecks.size}"}}
    }
}