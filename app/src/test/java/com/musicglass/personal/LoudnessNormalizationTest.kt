package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.models.response.PlayerResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin

/**
 * Q08A: Normalize volume. The gain rule, the value kept with a download, and what the one PCM stage does with
 * the gain next to the booster and under a blend's levels. Tones are generated; loudness values are scripted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class LoudnessNormalizationTest {
    @get:Rule val folder=TemporaryFolder()
    private val rate=48_000
    private fun tone(amplitude:Int,hz:Int=440,frames:Int=rate)=IntArray(frames) {(sin(2*Math.PI*hz*it/rate)*amplitude).toInt()}
    private fun pcm(samples:IntArray)=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        .apply {samples.forEach {putShort(it.toShort())};flip()}
    private fun apply(stage:PcmVolumeBoost,samples:IntArray,channels:Int=1):List<Int> {
        val output=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        stage.process(pcm(samples),output,rate,channels);output.flip()
        return buildList {while(output.hasRemaining()) add(output.short.toInt())}
    }
    private fun stage(boost:Float=1f,loudness:Double?=null,on:Boolean=true)=
        PcmVolumeBoost().apply {targetGain=boost;trim=normalizationGain(on,loudness);reset()}

    // ---- AUD01, AUD02: the rule

    @Test fun aLouderTrackIsTurnedDownByItsValueAndNothingIsEverTurnedUp() {
        assertEquals(.5012f,normalizationGain(true,6.0),.0001f)
        assertEquals(.7079f,normalizationGain(true,3.0),.0001f)
        assertEquals(.9886f,normalizationGain(true,.1),.0001f)
        for(quiet in listOf(0.0,-.1,-3.0,-20.0,-60.0)) assertEquals("A track at or below the reference is left alone",1f,normalizationGain(true,quiet),0f)
        for(value in listOf(-60.0,-1.0,0.0,.5,6.0,12.0,40.0,60.0)) assertTrue(normalizationGain(true,value) in NORMALIZE_MIN_GAIN..1f)
    }
    @Test fun attenuationStopsAtTwelveDecibels() {
        assertEquals(.2512f,NORMALIZE_MIN_GAIN,.0001f)
        assertEquals(NORMALIZE_MIN_GAIN,normalizationGain(true,12.0),0f)
        for(value in listOf(12.5,20.0,59.9,60.0)) assertEquals(NORMALIZE_MIN_GAIN,normalizationGain(true,value),0f)
        assertEquals("-12.0",normalizationDecibels(normalizationGain(true,45.0)))
    }
    @Test fun aMissingOrInvalidValueLeavesTheGainAlone() {
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,60.1,-60.1,1e9,-1e9)) {
            assertNull(validLoudnessDb(value));assertEquals(1f,normalizationGain(true,value),0f)
        }
        assertEquals(6.0,validLoudnessDb(6.0)!!,0.0);assertEquals(-60.0,validLoudnessDb(-60.0)!!,0.0)
    }
    @Test fun offMeansUnchangedWhateverTheValue() {
        for(value in listOf(null,-5.0,0.0,6.0,30.0,Double.NaN)) assertEquals(1f,normalizationGain(false,value),0f)
    }
    @Test fun theLoggedGainIsOneDecimalInAFixedLocale() {
        val before=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("-6.0",normalizationDecibels(normalizationGain(true,6.0)))
            assertEquals("0.0",normalizationDecibels(1f));assertEquals("0.0",normalizationDecibels(1.5f))
        } finally {java.util.Locale.setDefault(before)}
    }

    // ---- where the value comes from

    private val json=Json {ignoreUnknownKeys=true}
    private fun fixture(name:String)=json.decodeFromString(PlayerResponse.serializer(),javaClass.getResource("/quality/$name")!!.readText())
    /** Written by hand to the field names of the pinned library's model; it is not a recorded provider response. */
    @Test fun thePinnedLibraryReadsPerTrackLoudnessFromAPlayerResponse() {
        val audio=fixture("player-response-loudness.json").playerConfig!!.audioConfig!!
        assertEquals(5.21,audio.loudnessDb!!,0.0);assertEquals(-8.79,audio.perceptualLoudnessDb!!,0.0)
        assertEquals(.5489f,normalizationGain(true,validLoudnessDb(audio.loudnessDb)),.0001f)
        val silent=fixture("player-response-no-loudness.json").playerConfig!!.audioConfig!!
        assertNull(silent.loudnessDb);assertEquals(1f,normalizationGain(true,validLoudnessDb(silent.loudnessDb)),0f)
    }
    @Test fun theResolvedStreamCarriesTheValueAndDefaultsToNone() {
        // What YouTubeMusicProvider reads: a nullable Double on the library's resolved stream.
        assertEquals(java.lang.Double::class.java,ExtractedStream::class.java.getMethod("getLoudnessDb").returnType)
        val plain=ProviderStream("https://example.invalid/a",emptyMap(),"audio/webm")
        assertNull(plain.loudnessDb)
        assertEquals(4.5,plain.copy(loudnessDb=4.5).loudnessDb!!,0.0)
    }

    // ---- the value kept with a download

    @Test fun aDownloadKeepsItsValueNextToItsAudio() {
        val bundle=folder.newFolder("bundle")
        BundleLoudness.write(bundle,6.5)
        assertEquals("6.5",File(bundle,"loudness").readText());assertEquals(6.5,BundleLoudness.read(bundle)!!,0.0)
        BundleLoudness.write(bundle,-2.25);assertEquals(-2.25,BundleLoudness.read(bundle)!!,0.0)
        assertEquals("Only the value file is left",listOf("loudness"),bundle.list()!!.toList())
    }
    @Test fun nothingIsWrittenWithoutAValidValue() {
        val bundle=folder.newFolder("bundle")
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,99.0)) BundleLoudness.write(bundle,value)
        assertEquals(emptyList<String>(),bundle.list()!!.toList());assertNull(BundleLoudness.read(bundle))
    }
    @Test fun aBundleFromBeforeThisExistedOrWithADamagedValueReadsAsNone() {
        val older=folder.newFolder("older");File(older,"audio.media").writeBytes(ByteArray(64));File(older,"ready").writeText("64")
        assertNull(BundleLoudness.read(older));assertEquals(1f,normalizationGain(true,BundleLoudness.read(older)),0f)
        assertEquals("Reading changes nothing in the bundle",setOf("audio.media","ready"),older.list()!!.toSet())
        val damaged=folder.newFolder("damaged")
        for(text in listOf("","loud","6,5","NaN","Infinity","1e400","700","6.5 extra","9".repeat(65))) {
            File(damaged,"loudness").writeText(text);assertNull("'${text.take(12)}'",BundleLoudness.read(damaged))
        }
        File(damaged,"loudness").writeText(" 6.5\n");assertEquals(6.5,BundleLoudness.read(damaged)!!,0.0)
        File(damaged,"loudness").delete();File(damaged,"loudness").mkdir();assertNull(BundleLoudness.read(damaged))
        assertNull(BundleLoudness.read(null));assertNull(BundleLoudness.read(File(folder.root,"missing")))
    }

    // ---- the PCM stage

    @Test fun tonesAtDifferentLevelsFollowTheirScriptedLoudness() {
        val loud=tone(32_767);val moderate=tone(16_000,hz=660);val quiet=tone(4_000,hz=880)
        val turnedDown=apply(stage(loudness=6.0),loud)
        assertEquals(16_422.0,turnedDown.maxOf {abs(it)}.toDouble(),2.0)
        assertTrue("Each sample is its source times the gain",loud.indices.all {abs(turnedDown[it]-loud[it]*.5012f)<=1f})
        val slightly=apply(stage(loudness=3.0),moderate)
        assertEquals(11_327.0,slightly.maxOf {abs(it)}.toDouble(),2.0)
        // At or below the reference, without a value, or switched off: bit for bit what went in.
        assertEquals(quiet.toList(),apply(stage(loudness=-9.0),quiet))
        assertEquals(loud.toList(),apply(stage(loudness=null),loud))
        assertEquals(loud.toList(),apply(stage(loudness=Double.NaN),loud))
        assertEquals(loud.toList(),apply(stage(loudness=6.0,on=false),loud))
        // After normalization the two louder tones are closer in level than before, and their order is kept.
        assertTrue(turnedDown.maxOf {abs(it)}-slightly.maxOf {abs(it)}<32_767-16_000)
        assertTrue(turnedDown.maxOf {abs(it)}>slightly.maxOf {abs(it)})
    }
    @Test fun turningDownNeverLimitsSoTheShapeOfTheSignalIsKept() {
        val ramp=IntArray(65_536) {it-32_768}
        val result=apply(stage(loudness=12.0),ramp)
        assertTrue(result.indices.all {abs(result[it]-ramp[it]*NORMALIZE_MIN_GAIN)<=.5001f})
        assertEquals(-8_231,result.first());assertEquals(8_231,result.last())
    }
    @Test fun aTrackStartsAtItsGainWithoutARamp() {
        val result=apply(stage(loudness=6.0),IntArray(64) {20_000})
        assertTrue("The first sample is already turned down",result.all {it==10_024})
    }
    @Test fun aToggleInTheMiddleOfATrackRampsWithoutAStep() {
        val level=20_000;val gain=normalizationGain(true,6.0)
        // 30 ms from no sound to full scale: the largest change between two samples at this level.
        val largest=(level*2f/(rate*.03f)).toInt()+1
        val stage=PcmVolumeBoost().apply {reset()}
        assertEquals(List(480) {level},apply(stage,IntArray(480) {level}))
        stage.trim=gain
        val down=apply(stage,IntArray(4_800) {level})
        assertTrue("No step when it is switched on",(listOf(level)+down).zipWithNext().all {(a,b)->b<=a && a-b<=largest})
        assertEquals(10_024,down.last())
        assertTrue("It takes time to get there",down.indexOfFirst {it==10_024}>200)
        stage.trim=1f
        val up=apply(stage,IntArray(4_800) {level})
        assertTrue("No step when it is switched off",(listOf(down.last())+up).zipWithNext().all {(a,b)->b>=a && b-a<=largest})
        assertEquals(level,up.last())
        assertEquals("Then bit exact again",List(480) {-level},apply(stage,IntArray(480) {-level}))
    }
    @Test fun stereoChannelsShareTheGainThroughARamp() {
        val stage=PcmVolumeBoost().apply {reset()};stage.trim=normalizationGain(true,9.0)
        apply(stage,IntArray(4_000) {12_000},channels=2).chunked(2).forEach {assertEquals(it[0],it[1])}
    }
    @Test fun theStageRefusesAGainOutsideItsBounds() {
        val stage=PcmVolumeBoost()
        stage.trim=Float.NaN;assertEquals(1f,stage.trim,0f)
        stage.trim=Float.POSITIVE_INFINITY;assertEquals(1f,stage.trim,0f)
        stage.trim=5f;assertEquals(1f,stage.trim,0f)
        stage.trim=0f;assertEquals(NORMALIZE_MIN_GAIN,stage.trim,0f)
        stage.trim=-3f;assertEquals(NORMALIZE_MIN_GAIN,stage.trim,0f)
        stage.trim=.5f;assertEquals(.5f,stage.trim,0f)
        assertEquals("The booster's own gain is not touched",1f,stage.targetGain,0f)
    }

    // ---- with the booster, in every combination

    private val boosts=listOf(1f,1.5f,2f,3f)
    private val values=listOf(null,Double.NaN,-6.0,0.0,1.0,3.0,6.0,9.0,12.0,30.0)
    @Test fun everyCombinationWithTheBoosterStaysFiniteBoundedAndNoLouderThanWithoutIt() {
        val sources=listOf(tone(32_767,frames=4_800),tone(9_000,hz=1_000,frames=4_800),IntArray(4_800) {if(it%2==0) 32_767 else -32_768})
        for(boost in boosts) for(source in sources) {
            val without=apply(stage(boost),source)
            for(value in values) {
                val result=apply(stage(boost,value),source)
                assertEquals(source.size,result.size)
                assertTrue("In 16-bit range at boost $boost, loudness $value",result.all {it in -32_768..32_767})
                assertTrue("Sign kept",source.indices.all {source[it]==0 || result[it]==0 || (source[it]>0)==(result[it]>0)})
                assertTrue("Never louder than without normalization",source.indices.all {abs(result[it])<=abs(without[it])})
                if(normalizationGain(true,value)==1f) assertEquals(without,result)
            }
        }
    }
    @Test fun theBoosterAtItsMaximumStillHasTheLastWordOnPeaks() {
        // 3 times 0.71 is above 1: the limiter holds the product at full scale, exactly as it holds the booster alone.
        val boosted=apply(stage(3f,3.0),tone(32_767))
        assertTrue(boosted.maxOf {abs(it)} in 28_000..32_767)
        // 3 times 0.25 is below 1: plain scaling.
        assertEquals(24_693.0,apply(stage(3f,12.0),tone(32_767)).maxOf {abs(it)}.toDouble(),3.0)
        // Changing the booster on a normalized track ramps too.
        val stage=stage(1f,6.0);stage.targetGain=3f
        val ramp=apply(stage,IntArray(4_800) {8_000})
        assertTrue(ramp.zipWithNext().all {(a,b)->b>=a && b-a<=12});assertEquals(12_029.0,ramp.last().toDouble(),2.0)
    }

    // ---- under a blend: each player keeps its own gain

    @Test fun twoPlayersInABlendEachKeepTheirOwnGain() {
        val outgoing=stage(loudness=6.0);val incoming=stage(loudness=3.0)
        val a=tone(32_767,frames=4_800);val b=tone(32_767,hz=660,frames=4_800)
        val first=apply(outgoing,a);val second=apply(incoming,b)
        assertEquals(16_422.0,first.maxOf {abs(it)}.toDouble(),2.0);assertEquals(23_196.0,second.maxOf {abs(it)}.toDouble(),2.0)
        // Changing one player's gain leaves the other's samples as they were.
        incoming.trim=1f;incoming.reset()
        assertEquals(first,apply(outgoing.apply {reset()},a))
        incoming.trim=normalizationGain(true,3.0);incoming.reset()
        val plain=apply(stage(),a).maxOf {abs(it)}
        for(step in 0..100) {
            val (out,inn)=crossfadeGains(step/100f)
            // The player's level is the blend's alone and is applied after this stage; both stay in range.
            assertTrue(out in 0f..1f && inn in 0f..1f)
            val sum=first.maxOf {abs(it)}*out+second.maxOf {abs(it)}*inn
            assertTrue(sum.isFinite() && sum<=plain*(out+inn))
            assertTrue("Two normalized songs never add up beyond full scale here",sum<=32_768f)
        }
    }

    // ---- the setting

    @Test fun normalizeVolumeIsOffUntilTheUserTurnsItOn() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=context.getSharedPreferences(AUDIO_BOOST_PREFS,Context.MODE_PRIVATE);prefs.edit().clear().commit()
        val settings=AudioBoostSettings(context)
        assertFalse(settings.normalize);assertFalse(prefs.contains("normalize"))
        settings.updateNormalize(true)
        assertTrue(settings.normalize);assertTrue(prefs.getBoolean("normalize",false));assertTrue(AudioBoostSettings(context).normalize)
        // It is its own switch: the booster stays as it was, and the other way round.
        assertFalse(settings.enabled);settings.updateEnabled(true);assertTrue(AudioBoostSettings(context).normalize)
        settings.updateNormalize(false);assertFalse(AudioBoostSettings(context).normalize);assertTrue(AudioBoostSettings(context).enabled)
        prefs.edit().clear().commit()
    }
    /** Q18-077: each track says whether its loudness was known, so "no value" and "a quiet song" read apart. */
    @Test fun eachTrackSaysWhetherItsLoudnessWasKnown() {
        assertEquals("loudness_track known=false enabled=true gain_db=0.0",loudnessTrackEvent(true,null,1f))
        assertEquals("loudness_track known=true enabled=true loudness_db=-2.5 gain_db=0.0",loudnessTrackEvent(true,-2.5,1f))
        assertEquals("loudness_track known=true enabled=true loudness_db=6.0 gain_db=-6.0",loudnessTrackEvent(true,6.0,normalizationGain(true,6.0)))
        assertEquals("loudness_track known=false enabled=false gain_db=0.0",loudnessTrackEvent(false,Double.NaN,1f))
        for(event in listOf(loudnessTrackEvent(true,6.0,.5f),loudnessTrackEvent(false,null,1f)))
            assertEquals("every field is kept by the diagnostic log",event,DiagnosticEvent.parse(event).text)
    }
    /** Q18-106: one line per track, written when its loudness is settled, not first as unknown and then again. */
    @Test fun eachTrackWritesItsLoudnessLineOnceItIsSettled() {
        assertFalse("not while the stream is still being found",loudnessTrackDue("a",null,false,null))
        assertTrue("once the loudness is known",loudnessTrackDue("a",-7.0,false,null))
        assertFalse("and not again for the same track",loudnessTrackDue("a",-7.0,true,"a" to -7.0))
        assertTrue("a stream opened without a loudness says so",loudnessTrackDue("b",null,true,"a" to -7.0))
        assertFalse("once",loudnessTrackDue("b",null,true,"b" to null))
        assertTrue("the same song played again after another is a new line",loudnessTrackDue("a",-7.0,false,"b" to null))
        assertFalse("no entry, no line",loudnessTrackDue("",-7.0,true,null))
    }
}
