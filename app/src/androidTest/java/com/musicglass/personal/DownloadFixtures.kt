package com.musicglass.personal

import android.app.Instrumentation
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio in the form a download is stored in. The fixture tones are WAV, which a download never is, so
 * they are encoded here, once for each tone, to AAC in MP4 with the device's own encoder. Nothing is sampled
 * from a recording and nothing comes from a provider.
 */
internal object DownloadFixtures {
    private val made=HashMap<String,ByteArray>()

    /** The tone as AAC in MP4. The same length as the WAV it is made from. */
    @Synchronized fun aac(instrumentation:Instrumentation,asset:String="tone-a.wav"):ByteArray=made.getOrPut(asset) {
        val wav=instrumentation.context.assets.open("quality/audio/$asset").use {it.readBytes()}
        val file=File(instrumentation.targetContext.cacheDir,"fixture-${asset.substringBefore('.')}.m4a")
        try {encode(wav,file);file.readBytes()} finally {file.delete()}
    }

    /** A complete download as the engine leaves it, without a row: audio, the marker and, when given, the loudness value. */
    fun bundle(instrumentation:Instrumentation,dir:File,asset:String="tone-a.wav",loudness:String?=null):File {
        dir.deleteRecursively();check(dir.mkdirs())
        val audio=File(dir,BundleValidator.AUDIO).apply {writeBytes(aac(instrumentation,asset))}
        if(loudness!=null) File(dir,"loudness").writeText(loudness)
        File(dir,BundleValidator.READY).writeText(audio.length().toString())
        check(BundleValidator.problem(dir)==null)
        return audio
    }

    private fun encode(wav:ByteArray,output:File) {
        val bytes=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at=12;var rate=0;var channels=0;var start=-1;var length=0
        while(at+8<=wav.size) {
            val name=String(wav,at,4,Charsets.US_ASCII);val size=bytes.getInt(at+4)
            if(name=="fmt ") {channels=bytes.getShort(at+10).toInt();rate=bytes.getInt(at+12);check(bytes.getShort(at+22).toInt()==16) {"PCM16 only"}}
            if(name=="data") {start=at+8;length=minOf(size,wav.size-start);break}
            at+=8+size+(size and 1)
        }
        check(rate>0 && channels>0 && start>0 && length>0) {"Not a PCM WAV"}
        val format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,48_000);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16_384)
        }
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        output.delete();val muxer=MediaMuxer(output.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val info=MediaCodec.BufferInfo();val frame=2*channels
            var fed=0;var inputDone=false;var track=-1;var started=false
            val deadline=System.nanoTime()+120_000_000_000L
            while(true) {
                check(System.nanoTime()<deadline) {"The encoder did not finish"}
                if(!inputDone) {
                    val index=codec.dequeueInputBuffer(10_000)
                    if(index>=0) {
                        val buffer=codec.getInputBuffer(index)!!
                        val count=minOf(buffer.capacity()/frame*frame,length-fed)
                        val timeUs=fed.toLong()/frame*1_000_000/rate
                        if(count<=0) {codec.queueInputBuffer(index,0,0,timeUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true}
                        else {buffer.clear();buffer.put(wav,start+fed,count);codec.queueInputBuffer(index,0,count,timeUs,0);fed+=count}
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10_000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {track=muxer.addTrack(codec.outputFormat);muxer.start();started=true}
                else if(index>=0) {
                    if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0 && info.size>0) {check(started);muxer.writeSampleData(track,codec.getOutputBuffer(index)!!,info)}
                    codec.releaseOutputBuffer(index,false)
                    if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) break
                }
            }
            codec.stop();muxer.stop()
        } finally {codec.release();muxer.release()}
    }
}
