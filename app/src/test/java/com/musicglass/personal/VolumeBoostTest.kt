package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.compose.ui.unit.*

class VolumeBoostTest {
    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        .apply {samples.forEach {putShort(it.toShort())};flip()}
    private fun apply(boost: PcmVolumeBoost, input: ByteBuffer, rate: Int=48000, channels: Int=1): List<Int> {
        val output=ByteBuffer.allocate(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        boost.process(input,output,rate,channels);output.flip()
        return buildList {while(output.hasRemaining()) add(output.short.toInt())}
    }
    @Test fun unityIsBitExactIncludingPcmExtremes() {
        val source=(-32768..32767).toList()
        assertEquals(source,apply(PcmVolumeBoost(),pcm(*source.toIntArray())))
    }
    @Test fun quietSignalReachesThreeTimesAmplitude() {
        val boost=PcmVolumeBoost().apply {targetGain=3f;reset()}
        assertEquals(listOf(3000,-3000,0),apply(boost,pcm(1000,-1000,0)))
    }
    @Test fun peaksStayBoundedAndPreservePolarity() {
        val boost=PcmVolumeBoost().apply {targetGain=3f;reset()}
        val result=apply(boost,pcm(32767,-32768,16000,-16000))
        assertTrue(result[0] in 30000..32767);assertTrue(result[1] in -32768..-30000)
        assertTrue(result[2]>0 && result[3]<0);assertTrue(result[2]<result[0])
    }
    @Test fun liveGainRampsAndUsesIdenticalGainForStereoChannels() {
        val boost=PcmVolumeBoost().apply {targetGain=3f}
        val result=apply(boost,pcm(*IntArray(3000) {1000}),channels=2)
        assertTrue(result.first() in 1000..1010);assertEquals(3000,result.last())
        result.chunked(2).forEach {assertEquals(it[0],it[1])}
        assertTrue(result.zipWithNext().all {(a,b)->b>=a && b-a<=2})
        boost.targetGain=1f
        val restored=apply(boost,pcm(*IntArray(3000) {-1000}),channels=2)
        assertEquals(-1000,restored.last())
    }
    /** Q08: a generated full-scale tone at off, the default and the maximum, alone and under a blend's levels. */
    @Test fun generatedToneStaysFiniteAndBoundedAtOffDefaultAndMaximum() {
        val tone=IntArray(48_000) {(kotlin.math.sin(2*Math.PI*440*it/48_000)*32767).toInt()}
        val quiet=IntArray(48_000) {tone[it]/8}
        for((enabled,percent) in listOf(false to 200,true to 200,true to 300)) {
            val gain=boostGain(enabled,percent)
            assertTrue(gain.isFinite() && gain in 1f..3f)
            assertTrue(boostMillibels(gain) in 0..955)
            for(source in listOf(tone,quiet)) {
                val boost=PcmVolumeBoost().apply {targetGain=gain;reset()}
                val result=apply(boost,pcm(*source))
                assertEquals(source.size,result.size)
                assertTrue("Samples stay inside 16-bit PCM",result.all {it in -32768..32767})
                // The knee limits, it does not wrap: every sample keeps the sign of its source.
                assertTrue(result.indices.all {source[it]==0 || result[it]==0 || (source[it]>0)==(result[it]>0)})
                // A louder sample in never comes out quieter than a softer one: the limiter is monotonic.
                val ramp=apply(PcmVolumeBoost().apply {targetGain=gain;reset()},pcm(*IntArray(32_768) {it}))
                assertTrue(ramp.zipWithNext().all {(a,b)->b>=a})
                if(gain==1f) assertEquals(source.toList(),result)
                // What the player's level then does to it, at every point of an equal-power blend.
                for(step in 0..100) {
                    val (out,incoming)=crossfadeGains(step/100f)
                    val peak=result.maxOf {kotlin.math.abs(it)}
                    assertTrue((peak*out).isFinite() && peak*out<=32768f && peak*incoming<=32768f)
                }
            }
        }
        // Two full-scale songs in a blend can add up to more than full scale at the output mixer: 1.41 at the midpoint.
        val (out,incoming)=crossfadeGains(.5f)
        assertEquals(1.414f,out+incoming,.001f)
    }
    @Test fun invalidAndOutOfRangePreferencesStaySafe() {
        assertEquals(1f,boostGain(false,300),0f);assertEquals(3f,boostGain(true,900),0f)
        assertEquals(1f,boostGain(true,-1),0f)
        val boost=PcmVolumeBoost();boost.targetGain=Float.NaN;assertEquals(1f,boost.targetGain,0f)
        boost.targetGain=20f;assertEquals(3f,boost.targetGain,0f)
    }
    @Test fun malformedStereoFramesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {apply(PcmVolumeBoost(),pcm(1),channels=2)}
    }
    @Test fun menuAboveButtonAndWithinSafeArea() {
        val anchor=IntRect(900,1400,1010,1510)
        val position=playerMenuPosition(anchor,IntSize(1080,2400),IntSize(780,1800),33,80,70)
        assertTrue(position.x>=33 && position.x+780<=1047)
        assertTrue(position.y>=113 && position.y+1800<=2297)
        val short=playerMenuPosition(anchor,IntSize(1080,2400),IntSize(780,700),33,80,70)
        assertEquals(IntOffset(230,810),short)
    }
    @Test fun menuNearTopAndTinyWindowRemainVisible() {
        assertEquals(IntOffset(12,36),playerMenuPosition(IntRect(5,10,45,50),IntSize(320,480),IntSize(296,400),12,24,24))
        assertEquals(IntOffset(12,36),playerMenuPosition(IntRect.Zero,IntSize(30,50),IntSize(20,20),12,24,24))
    }
    @Test fun emptyBuffersDoNotCopyOntoThemselves() {
        val shared=ByteBuffer.allocateDirect(0)
        PcmVolumeBoost().process(shared,shared,48000,2)
        assertEquals(0,shared.remaining())
    }
    @Test @androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
    fun media3ProcessorAcceptsEmptyBuffersAndReconfiguredStreams() {
        val processor=VolumeBoostProcessor()
        val metadata=androidx.media3.common.audio.AudioProcessor.StreamMetadata(androidx.media3.common.C.TIME_UNSET)
        val stereo=androidx.media3.common.audio.AudioProcessor.AudioFormat(48000,2,androidx.media3.common.C.ENCODING_PCM_16BIT)
        processor.configure(stereo);processor.flush(metadata)
        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        processor.queueInput(pcm(1000,-1000));val output=processor.output.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000,output.short.toInt());assertEquals(-1000,output.short.toInt())
        processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
        processor.queueEndOfStream();assertTrue(processor.isEnded)
        processor.boost.targetGain=3f
        processor.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(44100,1,androidx.media3.common.C.ENCODING_PCM_16BIT))
        processor.flush(metadata);processor.queueInput(pcm(1000))
        assertEquals(3000,processor.output.order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        processor.reset()
    }

}
