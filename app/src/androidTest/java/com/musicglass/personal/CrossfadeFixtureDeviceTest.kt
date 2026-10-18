package com.musicglass.personal

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Q08: blends between generated tones through the real playback service, its second player and its effects.
 * Emulator or the side-by-side debug build only (disposableInstall), signed out, nothing from the provider. What is read is the player's level and the blend the
 * screens draw from; the emulator has no audio output to listen to.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class CrossfadeFixtureDeviceTest {
    private val device=PlaybackDeviceHarness()
    private val rows=mutableListOf<Uri>()
    private var started=0L
    private val fadePrefs get()=device.target.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE)
    private val boostPrefs get()=device.target.getSharedPreferences(AUDIO_BOOST_PREFS,Context.MODE_PRIVATE)
    private val base=.6f

    private class Sample(val at:Long,val progress:Float,val level:Float,val playing:Boolean)

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        started=System.currentTimeMillis()
    }
    @After fun disconnect() {
        if(!device.connected) return
        fadePrefs.edit().clear().commit();boostPrefs.edit().clear().commit()
        device.onMain {CrossfadeSettings.get(device.target).reload()}
        device.send(SET_SLEEP_TIMER,Bundle().apply {putInt("minutes",0)})
        device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.volume=1f;device.controller.release()}
        rows.forEach {device.target.contentResolver.delete(it,null,null)}
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
    }

    private fun events()=PlaybackEventLog(device.target).recent().mapNotNull {line->
        val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
        line.substringAfter(' ').takeIf {stamp>=started && it.startsWith("crossfade")}
    }
    private fun blend()=device.onMain {SongCrossfadeState.active}
    private fun level()=device.onMain {device.controller.volume}
    private fun current()=device.onMain {device.controller.currentMediaItem?.mediaId}
    private fun tailsLeft()=events().count {it=="crossfade_tail_created"}-events().count {it=="crossfade_tail_released"}

    /** Loads the files as a paused queue the way a restart does, then plays from [lead] before the first one ends. */
    private fun start(seconds:Int,asset:String="tone-a.wav",lead:Long=seconds*1_000L+9_000,boost:Int?=null,extra:List<SavedQueueItem> =emptyList()):List<String> {
        fadePrefs.edit().putBoolean("enabled",true).putInt("seconds",seconds).commit()
        boostPrefs.edit().putBoolean("enabled",boost!=null).putInt("percent",boost?:200).commit()
        val files=listOf(asset,if(asset=="tone-a.wav") "tone-b.wav" else asset).map {device.fixtureAudio(it).also(rows::add)}
        val items=files.mapIndexed {index,row->
            val id=row.lastPathSegment!!.toLong()
            SavedQueueItem("local:$id","Fixture tone ${index+1}","Fixture",localUri="content://media/external/audio/media/$id")
        }+extra
        device.await("no saved queue",15_000) {device.process.snapshots.load(GUEST_SCOPE_KEY)==null}
        assertEquals(StoreOutcome.Done,device.process.snapshots.save(device.process.snapshots.ticket(GUEST_SCOPE_KEY),PlaybackSnapshot(items,0,0)))
        val session=device.provider.session.value
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(RESTORE_SAVED_QUEUE,Bundle().apply {putLong("sessionGeneration",session.generation);putString("sessionNonce",session.nonce)}))
        device.await("the queue") {device.ids().size==items.size}
        device.onMain {device.controller.volume=base;device.controller.prepare()}
        device.await("the first file to be ready") {device.onMain {device.controller.duration>0}}
        device.onMain {device.controller.seekTo(device.controller.duration-lead);device.controller.play()}
        device.await("playback") {device.onMain {device.controller.isPlaying}}
        return items.map {it.mediaId}
    }

    /**
     * Plays up to the blend. The service blends only once the two decoders agree on their position, and gives up
     * safely if they do not in time; an emulator's two audio clocks often disagree. A blend that was given up is
     * checked for what it must leave behind (the level, no copy, the next song playing) and the queue is played again.
     */
    private fun startBlend(seconds:Int,asset:String="tone-a.wav",boost:Int?=null,extra:List<SavedQueueItem> =emptyList()):List<String> {
        repeat(4) {attempt->
            val ids=start(seconds,asset,boost=boost,extra=extra)
            device.await("a blend or the next song",60_000) {blend()!=null || current()==ids[1]}
            if(blend()!=null) return ids
            device.await("the next song to play alone") {current()==ids[1] && device.onMain {device.controller.isPlaying}}
            assertEquals("A blend that was given up leaves the level alone",base,level(),0f)
            assertEquals(0,events().count {it.startsWith("crossfade_start")})
            assertTrue("The reason is logged: ${events().filterNot {it.startsWith("crossfade_sync")}}",
                events().any {it.startsWith("crossfade_skipped") || it.startsWith("crossfade_cancelled")})
            android.util.Log.i("RiffDeviceTest","crossfade_fixture attempt ${attempt+1} gave up the blend safely: ${events().lastOrNull {it.startsWith("crossfade_skipped") || it.startsWith("crossfade_cancelled")}?.take(80)}")
            // Emptying the queue also ends the preparation already begun for the song that followed.
            device.emptyPlayer()
            device.await("no copy left after a blend that was given up",10_000) {tailsLeft()==0}
            rows.forEach {device.target.contentResolver.delete(it,null,null)};rows.clear()
            started=System.currentTimeMillis()
        }
        throw AssertionError("The two decoders did not align in four attempts; no blend to examine")
    }

    /** Follows one blend from its first frame to its last and returns what was read on the way. */
    private fun follow(until:(Sample)->Boolean={false}):List<Sample> {
        device.await("the blend to start",40_000) {blend()!=null}
        val samples=mutableListOf<Sample>()
        val deadline=SystemClock.elapsedRealtime()+40_000
        while(SystemClock.elapsedRealtime()<deadline) {
            // One read on the main thread, so progress and level belong to the same moment.
            val sample=device.onMain {SongCrossfadeState.active?.let {Sample(SystemClock.elapsedRealtime(),it.progress,device.controller.volume,device.controller.isPlaying)}}?:break
            samples+=sample
            if(until(sample)) break
            Thread.sleep(8)
        }
        return samples
    }

    private fun assertBlend(seconds:Int,asset:String="tone-a.wav",boost:Int?=null) {
        val ids=startBlend(seconds,asset,boost=boost)
        val visual=blend()!!
        assertEquals(ids[0],visual.outgoing.mediaId);assertEquals(ids[1],visual.incomingId)
        val duration=seconds*1_000L
        // The overlap is the setting, or what the song had left once the two decoders were aligned; never more.
        assertTrue("Declared length ${visual.durationMs}",visual.durationMs in 500..duration)
        val samples=follow()
        device.await("the copy of the outgoing song to be released",10_000) {tailsLeft()==0}
        assertTrue("Sampled the ramp: ${samples.size}",samples.size>=max(10,(visual.durationMs/50).toInt()))
        assertTrue("Progress never decreases",samples.zipWithNext().all {(a,b)->b.progress>=a.progress})
        samples.forEach {
            assertTrue(it.level.isFinite() && it.level in 0f..base+.001f)
            // The controller's level arrives a moment after the service set it.
            assertEquals("Level follows the equal-power curve at ${it.progress}",base*sin(it.progress*PI/2).toFloat(),it.level,.12f*base+.02f)
        }
        // Between two reads the level may rise by what the curve allows for the time that passed, and no more.
        samples.zipWithNext().forEach {(a,b)->
            val allowed=base*(PI/2).toFloat()*(b.at-a.at+50)/visual.durationMs+.03f
            assertTrue("The level rose by ${b.level-a.level} in ${b.at-a.at} ms",b.level-a.level in -.03f..allowed)
        }
        // The first read comes a moment after the blend began, so time is measured against the progress read with it.
        val first=samples.first();val last=samples.last()
        val heard=last.at-first.at;val due=((last.progress-first.progress)*visual.durationMs).toLong()
        // Time in which the incoming song was not playing holds the blend and is not part of its length.
        val held=samples.zipWithNext().sumOf {(a,b)->if(a.playing && b.playing) 0L else b.at-a.at}
        val gaps=samples.zipWithNext().map {(a,b)->b.at-a.at}
        val note="A $seconds s blend: ${visual.durationMs} ms declared, $due ms of it took $heard ms, held $held ms, ${samples.size} reads, longest gap ${gaps.max()} ms"
        android.util.Log.i("RiffDeviceTest","crossfade_fixture $note")
        assertTrue("Followed to the end: ${last.progress}",last.progress>=.9f)
        // Never compressed: at no read is the blend further along than the clock allows.
        samples.forEach {assertTrue("$note; ${it.progress} after ${it.at-first.at} ms",(it.progress-first.progress)*visual.durationMs<=it.at-first.at+60)}
        assertTrue(note,heard-held<=due+max(150L,visual.durationMs/20))
        device.await("the level to be back") {level()==base}
        assertEquals(ids[1],current());assertTrue(device.onMain {device.controller.isPlaying})
        assertNull(blend());assertNull(device.onMain {device.controller.playerError})
        assertEquals(1,events().count {it.startsWith("crossfade_start")})
        assertEquals(base,level(),0f)
    }

    @Test fun aOneSecondBlendIsGradualAndPutsTheLevelBack()=assertBlend(1)
    @Test fun aSixSecondBlendIsGradualAndPutsTheLevelBack()=assertBlend(6)
    @Test fun aTwelveSecondBlendIsGradualAndPutsTheLevelBack()=assertBlend(12,"long.wav")
    @Test fun aBlendWithTheBoostAtItsMaximumKeepsTheSameLevels()=assertBlend(3,boost=300)

    private fun assertEnded(what:String) {
        device.await("the blend to end after $what") {blend()==null}
        assertEquals("The level is back after $what",base,level(),0f)
        device.await("the copy of the outgoing song to be released after $what",10_000) {tailsLeft()==0}
        // Nothing writes the level again once the cancelled blend has cleaned up.
        Thread.sleep(400);assertEquals(base,level(),0f);assertNull(blend())
    }
    private fun midway(seconds:Int=6,extra:List<SavedQueueItem> =emptyList()):List<String> {
        val ids=startBlend(seconds,extra=extra)
        val samples=follow {it.progress>=.3f}
        assertTrue("Stopped inside the blend: ${samples.last().progress}",samples.last().progress<.9f)
        return ids
    }

    @Test fun pauseDuringABlendEndsItAndResumeContinuesWithTheNextSongAlone() {
        val ids=midway()
        device.onMain {device.controller.pause()}
        assertEnded("pause")
        assertFalse(device.onMain {device.controller.isPlaying});assertEquals(ids[1],current())
        device.onMain {device.controller.play()}
        device.await("playback again") {device.onMain {device.controller.isPlaying}}
        Thread.sleep(1_000);assertNull(blend());assertEquals(base,level(),0f)
    }

    @Test fun seekDuringABlendEndsIt() {
        val ids=midway()
        device.onMain {device.controller.seekTo(5_000)}
        assertEnded("seek")
        assertEquals(ids[1],current());assertTrue(device.onMain {device.controller.isPlaying})
    }

    @Test fun selectingAnotherSongDuringABlendEndsIt() {
        val ids=midway()
        device.onMain {device.controller.seekTo(0,2_000)}
        assertEnded("select")
        device.await("the selected song") {current()==ids[0] && device.onMain {device.controller.isPlaying}}
    }

    @Test fun turningCrossfadeOffDuringABlendEndsIt() {
        val ids=midway()
        fadePrefs.edit().putBoolean("enabled",false).commit()
        assertEnded("off")
        assertEquals(ids[1],current());assertTrue(device.onMain {device.controller.isPlaying})
    }

    @Test fun aSleepTimerSetDuringABlendEndsItAndKeepsTheLevelForItsOwnFade() {
        midway()
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(SET_SLEEP_TIMER,Bundle().apply {putInt("minutes",1)}))
        assertEnded("sleep timer")
        assertTrue(device.onMain {device.controller.sessionExtras.getLong("sleepDeadlineMs")}>0)
        // More than fifteen seconds remain, so the timer has not begun its own fade.
        Thread.sleep(1_500);assertEquals(base,level(),0f)
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(SET_SLEEP_TIMER,Bundle().apply {putInt("minutes",0)}))
        assertEquals(base,level(),0f)
    }

    @Test fun repeatOneNeverBlendsIntoTheNextSong() {
        val ids=start(1,lead=6_000)
        device.onMain {device.controller.repeatMode=Player.REPEAT_MODE_ONE}
        device.await("the song to start again",20_000) {device.onMain {device.controller.currentPosition<3_000}}
        assertEquals(ids[0],current());assertNull(blend())
        assertEquals(0,events().count {it.startsWith("crossfade_start")})
        assertEquals(base,level(),0f)
        device.await("no copy left",10_000) {tailsLeft()==0}
    }

    @Test fun losingAudioFocusDuringABlendEndsItAndPausesAsAndroidAsks() {
        val ids=midway()
        val audio=device.target.getSystemService(AudioManager::class.java)
        // Another player takes focus for good. Media3 pauses the player; the blend must not outlive that.
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener {}.build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,device.onMain {audio.requestAudioFocus(request)})
        try {
            device.await("the player to pause for the other app") {!device.onMain {device.controller.playWhenReady}}
            assertEnded("focus loss")
            assertEquals(ids[1],current())
        } finally {device.onMain {audio.abandonAudioFocusRequest(request)}}
    }

    @Test fun aShortInterruptionDuringABlendEndsItAndPlaybackComesBackAtTheSameLevel() {
        val ids=midway()
        val audio=device.target.getSystemService(AudioManager::class.java)
        // A call or a spoken direction: focus is lost for a moment, without ducking.
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener {}.build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,device.onMain {audio.requestAudioFocus(request)})
        try {
            device.await("playback to be held") {device.onMain {device.controller.playbackSuppressionReason!=Player.PLAYBACK_SUPPRESSION_REASON_NONE}}
            assertEnded("a short interruption")
            assertTrue("The wish to play is kept",device.onMain {device.controller.playWhenReady})
        } finally {device.onMain {audio.abandonAudioFocusRequest(request)}}
        device.await("playback to come back") {device.onMain {device.controller.isPlaying}}
        assertEquals(ids[1],current());assertEquals(base,level(),0f)
    }

    @Test fun anAccountSwitchDuringABlendEndsItAndStopsAQueueThatHoldsProviderSongs() {
        midway(extra=listOf(SavedQueueItem("yt:fixtureX001","Provider song","Fixture artist")))
        val sync="fixture-sync-A"
        try {
            runBlocking {withContext(Dispatchers.Main) {
                device.process.sessions.transition("fixture_switch",providerScope(verifiedAccountIdentity(sync)!!)) {
                    device.target.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear().putString("sync",sync).putString("account","0").commit()
                    CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")
                    device.provider.refreshAccount()
                }
            }}
            device.await("the blend to end with the switch") {blend()==null}
            device.await("no copy left",10_000) {tailsLeft()==0}
            assertFalse(device.onMain {device.controller.isPlaying})
        } finally {
            device.signedOut()
            device.process.snapshots.clear(providerScope(verifiedAccountIdentity(sync)!!).opaqueId)
        }
    }
}
