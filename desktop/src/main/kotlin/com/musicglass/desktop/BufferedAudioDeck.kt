package com.musicglass.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Bounded SPSC decoded ring. File/network/codec work never runs on the mix thread. */
internal class BufferedAudioDeck(source:String,factory:PcmDecoderFactory,private val onTerminated:(BufferedAudioDeck)->Unit={}) : AutoCloseable {
    private val terminated=java.util.concurrent.CountDownLatch(1)
    val isTerminated:Boolean get()=terminated.count==0L
    fun awaitTermination(milliseconds:Long):Boolean=terminated.await(milliseconds.coerceAtLeast(0),TimeUnit.MILLISECONDS)
    private val samples = FloatArray(48000 * 2)
    private val written = AtomicLong()
    private val read = AtomicLong()
    private val seek = AtomicReference<Pair<Long, CompletableFuture<Long>>?>()
    private val ready = CompletableFuture<Long>()
    private val primeSeek=AtomicReference<CompletableFuture<Long>?>()
    @Volatile private var analysisDecoder:PcmDecoder?=null
    fun analysisInput(destination:java.io.File):com.musicglass.desktop.automix.AnalysisFileLease? {
        val actual=analysisDecoder?:return null
        actual.retainAnalysisInput()?.let{return it}
        return if(actual.cachedEncodedSnapshot(destination))com.musicglass.desktop.automix.AnalysisFileLease.snapshot(destination,destination.parentFile)else null
    }
    @Volatile private var closed = false
    @Volatile private var eof = false
    @Volatile private var failure: Throwable? = null
    val decodeFailure:Throwable? get()=failure
    val availableFrames:Int get()=(written.get()-read.get()).coerceIn(0,48000).toInt()
    val exhausted:Boolean get()=eof&&availableFrames==0
    @Volatile var knownDurationMs:Long=0;private set
    @Volatile var sourceChannels=2;private set
    @Volatile var sourceKind="unknown";private set
    @Volatile var loudnessDb:Double?=null;private set
    private val normalization=DeckNormalization()
    fun normalize(samples:FloatArray,frames:Int,enabled:Boolean)=normalization.process(samples,frames,normalizationGain(enabled,loudnessDb))
    @Volatile var codecName="unknown";private set
    @Volatile var playbackRate=1f;private set
    @Volatile private var contentUnits=0L
    var contentFrame:Long
        get()=contentUnits/100
        private set(value){contentUnits=value*100}
    private val worker = Thread({
        try {
            factory.open(source).use { decoder ->
                analysisDecoder=decoder
                codecName=decoder.codecName;sourceKind=decoder.sourceKind;playbackRate=decoder.playbackRate;loudnessDb=validLoudnessDb(decoder.loudnessDb)
                val block = FloatArray(1024 * 2)
                while (!closed) {
                    seek.getAndSet(null)?.let { request ->
                        try {
                            val actual=decoder.seek(request.first); written.set(0); read.set(0); contentFrame = actual; eof = false
                            primeSeek.set(request.second)
                        } catch (t: Throwable) { request.second.completeExceptionally(t); throw t }
                    }
                    if (eof || written.get() - read.get() > 48000 - 1024) { Thread.sleep(2); continue }
                    val count = decoder.read(block, 1024)
                    if (count == 0) { eof = true; primeSeek.getAndSet(null)?.complete(contentFrame); if (!ready.isDone) ready.completeExceptionally(java.io.EOFException("Audio contains no decoded frames")); continue }
                    sourceChannels=decoder.sourceChannelCount
                    val at = written.get()
                    for (i in 0 until count * 2) samples[((at * 2 + i) % samples.size).toInt()] = block[i]
                    written.set(at + count)
                    primeSeek.getAndSet(null)?.complete(contentFrame)
                    if (!ready.isDone) {knownDurationMs=decoder.durationMs;ready.complete(knownDurationMs)}
                }
            }
        } catch (t: Throwable) {
            failure = t; ready.completeExceptionally(t); seek.getAndSet(null)?.second?.completeExceptionally(t);primeSeek.getAndSet(null)?.completeExceptionally(t)
        }finally{analysisDecoder=null;try{onTerminated(this@BufferedAudioDeck)}finally{terminated.countDown()}}
    }, "RiffDecodeDeck").apply { isDaemon = true; start() }
    val durationMs: Long get() = ready.get(30, TimeUnit.SECONDS)
    fun readInto(target: FloatArray, frames: Int): Int {
        val at = read.get(); val available = (written.get() - at).toInt()
        if (available == 0) {failure?.let{throw java.io.IOException("Audio decoding failed",it)};return if(eof)-1 else 0}
        val count = minOf(frames, available)
        for (i in 0 until count * 2) target[i] = samples[((at * 2 + i) % samples.size).toInt()]
        read.set(at + count);contentUnits+=count*kotlin.math.round(playbackRate*100).toLong()
        return count
    }
    /** Caller pauses the consumer before changing its decode timeline. */
    fun seekTo(frame: Long): Long {
        val done = CompletableFuture<Long>()
        check(seek.compareAndSet(null, frame to done)) { "Seek already in progress" }
        // A growing SABR source may have to acquire an unseen encoded prefix. Its readAt
        // stall deadline remains 30 s; permit up to 90 s total acquisition/decode, cancellable
        // by the worker operation. This is bounded recovery, not indexed-seek latency parity.
        return done.get(if(sourceKind=="network")90 else 30, TimeUnit.SECONDS)
    }
    override fun close() { closed = true;val error=java.nio.channels.ClosedChannelException();ready.completeExceptionally(error);seek.getAndSet(null)?.second?.completeExceptionally(error);primeSeek.getAndSet(null)?.completeExceptionally(error);worker.interrupt() }
}