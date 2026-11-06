package com.musicglass.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** All decoder work runs on worker threads. WASAPI never calls into the JVM. */
internal object NativeAudio {
    private interface Loader : StdCallLibrary { fun AddDllDirectory(path: WString): Pointer? }
    init {
        val directory = File(checkNotNull(System.getProperty("riff.native.dir")) { "Native audio directory is not configured" }).canonicalFile
        check(directory.isDirectory) { "Native audio directory is missing" }
        val cookie = Native.load("kernel32", Loader::class.java).AddDllDirectory(WString(directory.path))
        check(cookie != null) { "Could not configure native dependency search" }
        // Explicit absolute loads keep dependency selection independent of PATH/current directory.
        listOf("avutil-60.dll", "swresample-6.dll", "avcodec-62.dll", "avformat-62.dll", "riff_audio.dll").forEach {
            System.load(File(directory, it).absolutePath)
        }
    }
    external fun tempoOpen(speed:Float):Long
    external fun tempoPut(handle:Long,buffer:ByteBuffer,frames:Int)
    external fun tempoRead(handle:Long,buffer:ByteBuffer,frames:Int):Int
    external fun tempoFlush(handle:Long)
    external fun tempoClear(handle:Long)
    external fun tempoClose(handle:Long)
    external fun tempoHandleCount():Int
    external fun tempoVersion():String
    external fun audioThreadEnter():Long
    external fun audioThreadLeave(handle:Long)
    external fun audioThreadRegistrationCount():Int
    external fun version(): String
    external fun decoderOpen(path: String, sampleRate: Int, channels: Int): Long
    external fun decoderOpenReader(reader: CachedByteSource, sampleRate: Int, channels: Int): Long
    external fun decoderCodecName(handle: Long): String
    external fun decoderProfile(handle:Long):String
    external fun decoderSourceRate(handle:Long):Int
    external fun decoderSourceChannels(handle:Long):Int
    external fun handleCounts():LongArray
    external fun processHandleCount():Int
    external fun decoderArtwork(handle:Long):ByteArray?
    external fun decoderTags(handle: Long): Array<String?>
    external fun decoderDuration(handle: Long): Long
    external fun decoderRead(handle: Long, buffer: ByteBuffer, frames: Int): Int
    external fun decoderRewind(handle: Long)
    external fun decoderClose(handle: Long)
    external fun captureLoopback(buffer: ByteBuffer, milliseconds: Int): Long
    external fun sinkOpen(): Long
    external fun sinkWrite(handle: Long, buffer: ByteBuffer, frames: Int): Int
    external fun sinkPlaying(handle: Long, playing: Boolean)
    external fun sinkReset(handle: Long)
    external fun sinkPosition(handle: Long): Long
    external fun sinkUnderruns(handle: Long): Long
    external fun sinkTelemetry(handle:Long):LongArray
    external fun sinkClose(handle: Long)
}

internal interface PcmDecoder : AutoCloseable {
    val durationMs: Long
    val tags: List<String?> get() = emptyList()
    val codecName: String get() = "unknown"
    val sourceKind:String get()="unknown"
    val loudnessDb:Double? get()=null
    val analysisFile:File? get()=null
    fun retainAnalysisInput():com.musicglass.desktop.automix.AnalysisFileLease?=analysisFile?.let{com.musicglass.desktop.automix.AnalysisFileLease.borrowed(it)}
    fun cachedEncodedSnapshot(destination:File):Boolean=false
    val sourceChannelCount:Int get()=2
    val playbackRate:Float get()=1f
    val artworkBytes:ByteArray? get()=null
    fun read(target: FloatArray, frames: Int): Int
    fun seek(frame: Long): Long
}
internal fun interface PcmDecoderFactory { fun open(source: String): PcmDecoder }
internal interface PcmOutput : AutoCloseable {
    val sampleRate: Int
    val channels: Int
    fun write(samples: FloatArray, offsetFrames: Int, frames: Int): Int
    fun setPlaying(playing: Boolean)
    fun reset()
    fun presentedFrames(): Long
    fun underrunFrames(): Long = 0
    fun schedulingTelemetry():LongArray=LongArray(5)
    fun acquireProducerScheduling():AutoCloseable=AutoCloseable{}
    val producerSchedulingActive:Boolean get()=false
}

internal class FfmpegPcmDecoder(source: String?, private val byteSource: CachedByteSource? = null) : PcmDecoder {
    constructor(source: CachedByteSource) : this(null, source)
    private val sourceFile=source?.let{if(it.startsWith("file:"))File(java.net.URI(it))else File(it)}
    private var handle: Long
    private val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder())
    init {
        handle = if (byteSource != null) {
            try { NativeAudio.decoderOpenReader(byteSource, 48000, 2) } catch(t:Exception) { byteSource.close();throw t }
        } else {
            val name=checkNotNull(source)
            val file = if (name.startsWith("file:")) File(java.net.URI(name)) else File(name)
            require(file.isFile) { "Audio source is not a readable local/cache file" }
            try {NativeAudio.decoderOpen(file.canonicalPath, 48000, 2)} catch(error:java.io.IOException){throw UnsupportedContainerException(error)}
        }
    }
    override val analysisFile:File? get()=sourceFile
    override fun cachedEncodedSnapshot(destination:File)=byteSource?.cachedEncodedSnapshot(destination)==true
    override val sourceChannelCount:Int get()=sourceChannels
    override val sourceKind:String get()=if(byteSource==null)"local"else"network"
    override val loudnessDb:Double?=if(byteSource!=null)validLoudnessDb(byteSource.loudnessDb)else source?.let{DesktopLoudnessReceipt.read(File(if(it.startsWith("file:"))java.net.URI(it).path else it))}
    override val artworkBytes:ByteArray? get()=NativeAudio.decoderArtwork(handle)
    override val codecName=NativeAudio.decoderCodecName(handle)
    val profile=NativeAudio.decoderProfile(handle)
    val sourceRate get()=NativeAudio.decoderSourceRate(handle)
    val sourceChannels get()=NativeAudio.decoderSourceChannels(handle)
    override val tags = NativeAudio.decoderTags(handle).toList()
    override val durationMs = NativeAudio.decoderDuration(handle).takeIf { it > 0 } ?: byteSource?.durationMs ?: 0
    @Synchronized override fun read(target: FloatArray, frames: Int): Int {
        check(handle != 0L) { "Decoder closed" }
        require(frames in 0..4096 && target.size >= frames * 2)
        val count = decodeFrames{NativeAudio.decoderRead(handle, buffer, frames)}
        buffer.asFloatBuffer().get(target, 0, count * 2)
        return count
    }
    @Synchronized override fun seek(frame: Long): Long {
        require(frame >= 0)
        NativeAudio.decoderRewind(handle)
        var remaining = frame
        val discarded = FloatArray(4096 * 2)
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Seek cancelled")
            val count = read(discarded, minOf(remaining, 4096).toInt())
            if (count == 0) break
            remaining -= count
        }
        return frame-remaining
    }
    @Synchronized override fun close() { if (handle != 0L) { NativeAudio.decoderClose(handle); handle = 0; byteSource?.close() } }
}

internal class WasapiPcmOutput : PcmOutput {
    @Volatile override var producerSchedulingActive=false;private set
    override fun acquireProducerScheduling():AutoCloseable {
        check(!producerSchedulingActive){"Audio producer already registered"}
        val owner=Thread.currentThread()
        val registration=try{NativeAudio.audioThreadEnter()}catch(error:java.io.IOException){throw AudioSchedulingUnavailableException(error)}
        producerSchedulingActive=true
        var closed=false
        return AutoCloseable{
            check(Thread.currentThread()===owner){"Audio scheduling must close on its owning thread"}
            if(!closed){NativeAudio.audioThreadLeave(registration);closed=true;producerSchedulingActive=false}
        }
    }
    override val sampleRate = 48000
    override val channels = 2
    private var handle = endpointOperation{NativeAudio.sinkOpen()}
    private val buffer = ByteBuffer.allocateDirect(4096 * 8).order(ByteOrder.nativeOrder())
    override fun write(samples: FloatArray, offsetFrames: Int, frames: Int): Int {
        check(handle != 0L)
        require(frames in 0..4096 && offsetFrames >= 0 && (offsetFrames + frames) * 2 <= samples.size)
        buffer.asFloatBuffer().put(samples, offsetFrames * 2, frames * 2)
        return endpointOperation{NativeAudio.sinkWrite(handle, buffer, frames)}
    }
    override fun setPlaying(playing: Boolean) = endpointOperation{NativeAudio.sinkPlaying(handle, playing)}
    override fun reset() = endpointOperation{NativeAudio.sinkReset(handle)}
    override fun presentedFrames(): Long = endpointOperation{NativeAudio.sinkPosition(handle)}
    override fun underrunFrames(): Long = NativeAudio.sinkUnderruns(handle)
    override fun schedulingTelemetry()=NativeAudio.sinkTelemetry(handle)
    override fun close() { if (handle != 0L) { NativeAudio.sinkClose(handle); handle = 0 } }
}