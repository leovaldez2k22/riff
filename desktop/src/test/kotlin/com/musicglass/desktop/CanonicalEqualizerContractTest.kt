package com.musicglass.desktop
import kotlin.test.*
import java.io.*
class CanonicalEqualizerContractTest {
    @Test fun strictCanonicalCommandAndSettingsRoundTrip(){
        val config=EqualizerConfig(true,EqMode.Manual,-5,4,true,listOf(-12f,1.5f,0f,6f,-.5f,2f,12f),"Custom",-.35f)
        val command=AudioCommand.SetCanonicalDSP(91,config,1.5f,.8f)
        val bytes=ByteArrayOutputStream();AudioIpcTransport.writeCommand(DataOutputStream(bytes),command)
        assertEquals(command,AudioIpcTransport.readCommand(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
        assertEquals(config,equalizerFromJson(config.toJson()))
    }
    @Test fun invalidValuesCannotReachTheAudioThread(){
        for(config in listOf(EqualizerConfig(toneX=6),EqualizerConfig(manual=listOf(0f)),EqualizerConfig(manual=List(7){Float.NaN}),EqualizerConfig(balance=Float.POSITIVE_INFINITY),EqualizerConfig(preset="unknown"))){
            assertFailsWith<IllegalArgumentException>{prepareEqualizer(config)}
        }
        val mutable=MutableList(7){0f};val copied=checkedEqualizer(EqualizerConfig(manual=mutable));mutable[0]=12f;assertEquals(0f,copied.manual[0])
    }
    @Test fun disabledAndFlatAreBitExactAndMonoIgnoresStereoBalance(){
        // Mathematical DSP test data only; not evidence of decoding or working production playback.
        val input=FloatArray(4096){if(it%2==0).03125f else -.0625f}
        for(config in listOf(EqualizerConfig(),EqualizerConfig(enabled=true),EqualizerConfig(enabled=true,balance=-1f))){
            val output=input.copyOf();CanonicalFloatEqualizer().apply{configure(prepareEqualizer(config));reset()}.process(output,2048,balanceEnabled=false)
            assertContentEquals(input,output)
        }
    }
    @Test fun manualAndDynamicUseTheAndroidSectionShapesAndHeadroom(){
        val manual=EqualizerConfig(true,EqMode.Manual,manual=listOf(6f,0f,0f,0f,0f,0f,-6f),preset="Custom")
        val sections=eqSections(manual);assertEquals(EqShape.LowShelf,sections[0].shape);assertEquals(EqShape.HighShelf,sections[6].shape)
        assertTrue(sections.drop(7).all{it.gainDb==0f})
        val broad=EqualizerConfig(true,EqMode.Dynamic,-5,5);val focused=broad.copy(focused=true)
        assertEquals(6f,eqSections(broad)[7].gainDb);assertEquals(6f,eqSections(broad)[8].gainDb);assertEquals(-6f,eqSections(broad)[9].gainDb)
        assertTrue(eqSections(focused)[8].q>eqSections(broad)[8].q)
        val target=prepareEqualizer(manual);assertTrue(target.preamp in 0f..1f)
        assertEquals(-eqCurve(eqSections(manual)).max().coerceAtLeast(0f),preampFor(eqSections(manual)))
    }
}
