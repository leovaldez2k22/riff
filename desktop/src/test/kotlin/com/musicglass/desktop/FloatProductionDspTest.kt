package com.musicglass.desktop

import kotlin.test.*
import kotlin.math.*

class FloatProductionDspTest {
    @Test fun floatEqRetainsLegacyDesktopImpulseResponseWithinPcmQuantization() {
        val old=DesktopEqualizer().apply{configure(48000,2);bandsDb[4]=3f;bandsDb[7]=-2f}
        val fresh=FloatDesktopDsp().apply{volume=1f;configure(old.bandsDb,true);reset()}
        val impulse=ShortArray(8192).apply{this[0]=1000;this[1]=1000}
        val expected=old.process(impulse)
        val actual=FloatArray(impulse.size){impulse[it]/32768f};fresh.process(actual,actual.size/2)
        actual.indices.forEach {assertEquals(expected[it]/32768f,actual[it],1.1f/32768f,"Transfer mismatch at $it")}
    }
    @Test fun boostEqLimiterAndResetStayFiniteAndBoundedOnRealLevelChanges() {
        val dsp=FloatDesktopDsp().apply{volume=1f;gain=3f;configure(FloatArray(10){6f},true);reset()}
        val samples=FloatArray(48000*2){index->(.9*sin(2*PI*1000*(index/2)/48000)).toFloat()}
        dsp.process(samples,48000)
        assertTrue(samples.all{it.isFinite()&&abs(it)<=.976563f})
        assertTrue(samples.any{abs(it)>.1f})
        dsp.reset();val silence=FloatArray(2048);dsp.process(silence,1024);assertTrue(silence.all{it==0f},"Old filter energy survived a seek reset")
    }
    @Test fun everyVisiblePresetChangesTheCanonicalProductionFilter() {
        val probe=FloatArray(4096).apply{this[0]=.01f;this[1]=.01f}
        val outputs=EQ_PRESETS.map{(name,gains)->probe.copyOf().also{samples->
            CanonicalFloatEqualizer().apply{configure(prepareEqualizer(EqualizerConfig(true,EqMode.Manual,manual=gains,preset=name)));reset()}.process(samples,samples.size/2)
        }}
        assertTrue(outputs.drop(1).all{!it.contentEquals(outputs.first())})
        assertEquals(outputs.size,outputs.map{it.contentHashCode()}.distinct().size)
        assertFailsWith<IllegalStateException>{DesktopEqPresets.bands("Unknown")}
    }
}
