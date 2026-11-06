package com.musicglass.desktop.automix

import kotlin.test.*

class AnalysisCacheValidationTest {
    private fun envelope(beats:LongArray=longArrayOf(100,600,1100),down:LongArray=longArrayOf(100))=TrackEnvelope(0,FloatArray(30){-12f},FloatArray(30){-20f},3000,BeatGrid(120.0,beats,down,.9f,.9f))
    @Test fun validCachePreservesVersionAndIdentity(){
        val bytes=encodeEnvelope(envelope(),"source-sha:head",ANALYSIS_VERSION_MODEL)
        val result=assertNotNull(decodeEnvelope(bytes,"source-sha:head",ANALYSIS_VERSION_MODEL))
        assertContentEquals(longArrayOf(100,600,1100),result.beats!!.beatsMs)
        assertNull(decodeEnvelope(bytes,"another-source:head",ANALYSIS_VERSION_MODEL))
        assertNull(decodeEnvelope(bytes,"source-sha:head",ANALYSIS_VERSION))
    }
    @Test fun unorderedDuplicateAndOutOfWindowBeatsAreNeverPlanned(){
        for(values in listOf(longArrayOf(600,100),longArrayOf(100,100),longArrayOf(-1,100),longArrayOf(100,3001))){
            assertNull(decodeEnvelope(encodeEnvelope(envelope(beats=values),"key"),"key"))
            assertNull(decodeEnvelope(encodeEnvelope(envelope(down=values),"key"),"key"))
        }
    }
    @Test fun partialTrailingOversizeAndNonfiniteCacheAreMisses(){
        val valid=encodeEnvelope(envelope(),"key")
        assertNull(decodeEnvelope(valid.copyOf(valid.size-1),"key"))
        assertNull(decodeEnvelope(valid+byteArrayOf(0),"key"))
        assertNull(decodeEnvelope(valid.copyOf(131073),"key"))
        val invalid=TrackEnvelope(0,floatArrayOf(Float.NaN),floatArrayOf(-12f),100)
        assertNull(decodeEnvelope(encodeEnvelope(invalid,"key"),"key"))
    }
}
