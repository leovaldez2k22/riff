package com.musicglass.desktop

import com.musicglass.desktop.automix.*
import java.io.*
import kotlin.test.*

class TransitionFactsContractTest {
    @Test fun boundedSourceFactsSurviveTheActualWorkerWireFormat(){
        val facts=TransitionHints(false,true,"Real album identity",listOf(100L..900L,1200L..2400L),4)
        val command=AudioCommand.PrepareSource(71,"occurrence","local-file.flac",0,1.5f,facts)
        val buffer=ByteArrayOutputStream();AudioIpcTransport.writeCommand(DataOutputStream(buffer),command)
        assertEquals(command,AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(buffer.toByteArray()))))
    }
    @Test fun invalidSourceFactsNeverWriteAWorkerFrame(){
        for(facts in listOf(TransitionHints(album="x".repeat(513)),TransitionHints(vocals=listOf(-1L..20L)),TransitionHints(trackNumber=-2),TransitionHints(vocals=List(8193){0L..1L}))){
            val bytes=ByteArrayOutputStream()
            assertFailsWith<IllegalArgumentException>{AudioIpcTransport.writeCommand(DataOutputStream(bytes),AudioCommand.PrepareSource(2,"occurrence","local.flac",0,transitionHints=facts))}
            assertEquals(0,bytes.size())
        }
    }
    @Test fun maliciousVocalCountIsRejectedBeforeAllocation(){
        val payload=ByteArrayOutputStream()
        DataOutputStream(payload).use{it.writeByte(AudioIpcTransport.TAG_CMD_PREPARE_SOURCE.toInt());it.writeLong(2);it.writeUTF("entry");it.writeUTF("local.flac");it.writeLong(0);it.writeFloat(1f);it.writeBoolean(true);it.writeBoolean(false);it.writeUTF("");it.writeInt(0);it.writeInt(Int.MAX_VALUE)}
        val frame=ByteArrayOutputStream();DataOutputStream(frame).use{it.writeInt(payload.size());it.write(payload.toByteArray())}
        assertFailsWith<IllegalArgumentException>{AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(frame.toByteArray())))}
    }
}
