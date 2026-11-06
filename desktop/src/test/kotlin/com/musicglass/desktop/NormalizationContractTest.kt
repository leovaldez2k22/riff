package com.musicglass.desktop
import kotlin.test.*
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
class NormalizationContractTest {
    @Test fun androidAttenuationPolicyNeverBoosts(){
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-61.0,61.0,-20.0,0.0))assertEquals(1f,normalizationGain(true,value))
        for(db in listOf(1.0,6.0,12.0,60.0))assertEquals(1f,normalizationGain(false,db))
        assertEquals(.5011872f,normalizationGain(true,6.0),.000001f)
        assertEquals(.25118864f,normalizationGain(true,60.0),.000001f)
    }
    @Test fun independentlyOwnedDeckEnvelopesAndBoundedToggleGlide(){
        // Mathematical DSP inputs only; not codec/player acceptance.
        val outgoing=DeckNormalization();val incoming=DeckNormalization()
        val a=FloatArray(4096){.1f};val b=a.copyOf()
        outgoing.process(a,2048,normalizationGain(true,12.0));incoming.process(b,2048,1f)
        assertTrue(a.all{kotlin.math.abs(it-.025118865f)<.000001f});assertTrue(b.all{it==.1f})
        val changed=FloatArray(4096){.1f};outgoing.process(changed,2048,1f)
        assertTrue(changed.first()<.026f);assertEquals(.1f,changed.last())
    }
    @Test fun protocolCarriesNormalizationAndRejectsTruncation(){
        val command=AudioCommand.SetCanonicalDSP(12,EqualizerConfig(),1f,1f,true)
        val bytes=ByteArrayOutputStream();AudioIpcTransport.writeCommand(DataOutputStream(bytes),command)
        assertEquals(command,AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        assertFails{AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(bytes.toByteArray().dropLast(1).toByteArray())))}
    }
    @Test fun downloadReceiptIsBoundToActualBytesAndFailsClosed(){
        val dir=Files.createTempDirectory("riff-loudness-").toFile()
        try{
            val audio=File(dir,"track.media").apply{writeBytes(byteArrayOf(1,2,3))}
            val sha=MessageDigest.getInstance("SHA-256").digest(audio.readBytes()).joinToString(""){"%02x".format(it)}
            DesktopLoudnessReceipt.write(audio,sha,6.0);assertEquals(6.0,DesktopLoudnessReceipt.read(audio))
            audio.appendBytes(byteArrayOf(4));assertNull(DesktopLoudnessReceipt.read(audio))
            DesktopLoudnessReceipt.path(audio).writeText("invalid");assertNull(DesktopLoudnessReceipt.read(audio))
            DesktopLoudnessReceipt.write(audio,sha,null);assertFalse(DesktopLoudnessReceipt.path(audio).exists())
        }finally{dir.deleteRecursively()}
    }
}
