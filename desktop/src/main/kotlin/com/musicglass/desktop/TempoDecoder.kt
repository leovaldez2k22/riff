package com.musicglass.desktop
import java.nio.ByteBuffer
import java.nio.ByteOrder
/** Content-time-preserving wrapper. SoundTouch Float32 does tempo only; pitch/rate transposition remains 1x. */
internal class TempoDecoder(private val source:PcmDecoder,override val playbackRate:Float):PcmDecoder{
    private var handle=try{if(source.durationMs>14400000L)throw TempoDurationLimitException();NativeAudio.tempoOpen(checkedPlaybackRate(playbackRate))}catch(error:Throwable){source.close();throw error}
    private val input=FloatArray(8192)
    private val inputBytes=ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())
    private val outputBytes=ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())
    private var flushed=false
    private var inputFrames=0L
    override val durationMs get()=source.durationMs
    override val codecName get()=source.codecName
    override val sourceKind get()=source.sourceKind
    override val loudnessDb get()=source.loudnessDb
    override val analysisFile get()=source.analysisFile
    override fun retainAnalysisInput()=source.retainAnalysisInput()
    override fun cachedEncodedSnapshot(destination:java.io.File)=source.cachedEncodedSnapshot(destination)
    override val sourceChannelCount get()=source.sourceChannelCount
    override val tags get()=source.tags
    override val artworkBytes get()=source.artworkBytes
    override fun read(target:FloatArray,frames:Int):Int{
        require(handle!=0L&&frames in 1..4096&&target.size>=frames*2)
        while(true){
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Tempo read cancelled")
            val count=decodeFrames{NativeAudio.tempoRead(handle,outputBytes,frames)}
            if(count>0){outputBytes.asFloatBuffer().get(target,0,count*2);return count}
            if(flushed)return 0
            val incoming=source.read(input,4096);inputFrames+=incoming;if(inputFrames>48000L*60*60*4)throw TempoDurationLimitException()
            if(incoming==0){decodeFrames{NativeAudio.tempoFlush(handle)};flushed=true}
            else{inputBytes.asFloatBuffer().put(input,0,incoming*2);decodeFrames{NativeAudio.tempoPut(handle,inputBytes,incoming)}}
        }
    }
    override fun seek(frame:Long):Long{require(frame>=0);val actual=source.seek(frame);NativeAudio.tempoClear(handle);flushed=false;inputFrames=0;return actual}
    override fun close(){if(handle!=0L){try{NativeAudio.tempoClose(handle)}finally{handle=0;source.close()}}}
}
internal fun checkedPlaybackRate(value:Float):Float{
    require(value.isFinite()&&value in .5f..2f){"Playback speed must be between 0.5x and 2x"}
    val result=kotlin.math.round(value*20)/20
    require(kotlin.math.abs(value-result)<.00001f){"Playback speed uses 0.05x steps"}
    return result
}

internal fun effectivePlaybackSpeed(state:HostSnapshot,song:SongItem=state.currentTrack):Float=if(song.durationMs>14400000L)1f else if(song.isEpisode)state.episodePlaybackRate else state.musicPlaybackRate
