package com.musicglass.desktop
import kotlin.test.*
/** Resource/concurrency test only; silent test buffers never count as codec or production playback evidence. */
class DecoderOwnershipContractTest {
    @Test fun asynchronousRetirementCannotRaceFinalOwnershipSnapshot(){
        val owned=java.util.concurrent.atomic.AtomicInteger()
        val factory=PcmDecoderFactory{
            owned.incrementAndGet()
            object:PcmDecoder{
                override val durationMs=60000L
                override fun read(target:FloatArray,frames:Int)=frames
                override fun seek(frame:Long)=frame
                override fun close(){owned.decrementAndGet()}
            }
        }
        repeat(100){
            DesktopDualDeckMixer(outputFactory={TestPcmOutput()},decoderFactory=factory).use{mixer->
                mixer.loadQueue(listOf("ownership-only-a","ownership-only-b"))
            }
            assertEquals(0,owned.get(),"Engine close returned before decoder ownership ended")
        }
    }
}
