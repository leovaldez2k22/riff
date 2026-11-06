package com.musicglass.desktop

import java.io.*
import kotlin.test.*

class PhaseOneProtocolSafetyTest {
    @Test fun trailingBytesAndOversizedQueueAreRejectedBeforeDispatch() {
        val encoded=ByteArrayOutputStream().also {AudioIpcTransport.writeCommand(DataOutputStream(it),AudioCommand.SetPlaying(8,true))}.toByteArray()
        val tampered=encoded.copyOf(encoded.size+1)
        java.nio.ByteBuffer.wrap(tampered).putInt(encoded.size-4+1)
        assertFailsWith<IllegalArgumentException>{AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(tampered)))}
        val attack=ByteArrayOutputStream();DataOutputStream(attack).use{out->out.writeInt(13);out.writeByte(AudioIpcTransport.TAG_CMD_LOAD_QUEUE.toInt());out.writeLong(1);out.writeInt(Int.MAX_VALUE)}
        assertFailsWith<IllegalArgumentException>{AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(attack.toByteArray())))}
    }
    @Test fun mismatchedWorkerBuildAndOutOfRangeSpeedAreExplicitFailures() {
        DesktopAudioRuntime({}, {TestPcmOutput()},TestDecoderFactory).use {runtime->
            assertFalse(runtime.execute(AudioCommand.Handshake(1,"riff-audio/old",0)).success)
            assertTrue(runtime.execute(AudioCommand.Handshake(2,AudioProtocol.BUILD_ID,0)).success)
            val unsupported=runtime.execute(AudioCommand.SetPlaybackRate(3,2.05f))
            assertFalse(unsupported.success);assertTrue(unsupported.details.orEmpty().contains("AUDIO_COMMAND_REJECTED"))
            // Configuration acknowledgement only; actual tempo decoding has separate native/real-media acceptance.
            assertTrue(runtime.execute(AudioCommand.SetPlaybackRate(4,2f)).success)
        }
    }
}
