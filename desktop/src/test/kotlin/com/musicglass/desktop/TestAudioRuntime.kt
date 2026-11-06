package com.musicglass.desktop

import java.io.File

/** Explicit component-test doubles. Production has no generated decoder or silent sink selection. */
internal class TestPcmOutput : PcmOutput {
    override val sampleRate=48000
    override val channels=2
    @Volatile private var presented=0L
    private var submitted=0L
    private var playing=false
    override fun write(samples:FloatArray,offsetFrames:Int,frames:Int):Int {Thread.sleep(2);submitted+=frames;if(playing)presented=submitted;return frames}
    override fun setPlaying(playing:Boolean){this.playing=playing;if(playing)presented=submitted}
    override fun reset(){presented=0;submitted=0}
    override fun presentedFrames()=presented
    override fun close(){}
}
internal val TestDecoderFactory=PcmDecoderFactory { source ->
    val known=FixtureTracks.all.firstOrNull{it.id==source}
    val duration=if(known!=null)known.durationMs else {
        val file=if(source.startsWith("file:"))File(java.net.URI(source)) else File(source)
        if(!file.isFile)throw java.io.IOException("Unknown test source")
        val info=javax.sound.sampled.AudioSystem.getAudioFileFormat(file)
        info.frameLength*1000L/info.format.frameRate.toLong()
    }
    object:PcmDecoder {
        override val durationMs=duration
        private var frame=0L
        override fun read(target:FloatArray,frames:Int):Int {
            val count=minOf(frames.toLong(),durationMs*48-frame).coerceAtLeast(0).toInt()
            for(i in 0 until count)for(ch in 0..1)target[i*2+ch]=(((frame+i)%96)/96f-.5f)*.1f
            frame+=count;return count
        }
        override fun seek(frame:Long):Long {this.frame=frame;return frame}
        override fun close(){}
    }
}
internal fun testAudioBridge()=DesktopAudioBridge { emit -> DesktopAudioRuntime(emit,{TestPcmOutput()},TestDecoderFactory) }