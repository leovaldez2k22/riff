package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Q08A: Normalize volume through the real playback service. Downloads are built here from generated tones,
 * encoded as a download is stored, with a scripted loudness value next to them; nothing comes from the provider. Emulator or the side-by-side debug build only (disposableInstall), signed out. What is
 * read is the gain the service says it applied; the emulator has no audio output to listen to.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class NormalizationDeviceTest {
    private val device=PlaybackDeviceHarness()
    private val bundles=mutableListOf<File>()
    private val rows=mutableListOf<Uri>()
    private var started=0L
    private val audioPrefs get()=device.target.getSharedPreferences(AUDIO_BOOST_PREFS,Context.MODE_PRIVATE)
    private val fadePrefs get()=device.target.getSharedPreferences(CROSSFADE_PREFS,Context.MODE_PRIVATE)
    private val base=.6f
    private fun id(n:Int)="fixtureN%03d".format(n)

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        audioPrefs.edit().clear().commit();fadePrefs.edit().clear().commit()
        device.onMain {CrossfadeSettings.get(device.target).reload()}
        settle()
    }
    /** Lets the service finish what the last queue left behind, then counts events from here. */
    private fun settle() {Thread.sleep(400);started=System.currentTimeMillis()}
    @After fun disconnect() {
        if(!device.connected) return
        audioPrefs.edit().clear().commit();fadePrefs.edit().clear().commit()
        device.onMain {CrossfadeSettings.get(device.target).reload()}
        device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.volume=1f;device.controller.release()}
        bundles.forEach {it.deleteRecursively()}
        rows.forEach {device.target.contentResolver.delete(it,null,null)}
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
    }

    /** A complete download as the download engine leaves it: audio, the ready mark and, when given, the value. */
    private fun download(n:Int,loudness:String?,asset:String="tone-a.wav"):String {
        val store=OfflineMusicStore.get(device.target)
        val dir=store.directory(store.currentScope,id(n)).also {bundles+=it}
        DownloadFixtures.bundle(device.instrumentation,dir,asset,loudness)
        assertNotNull("The store sees a finished download",store.audio(store.currentScope,id(n)))
        return id(n)
    }
    private fun normalize(on:Boolean)=assertTrue(audioPrefs.edit().putBoolean(NORMALIZE_KEY,on).commit())
    private fun play(vararg ids:String) {
        device.onMain {device.controller.volume=base}
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,device.queueArgs(ids.map {it to "Fixture $it"},autoplay=true)))
        device.await("the download to play",20_000) {current()=="yt:${ids[0]}" && playing()}
    }
    private fun current()=device.onMain {device.controller.currentMediaItem?.mediaId}
    private fun playing()=device.onMain {device.controller.isPlaying}
    private fun position()=device.onMain {device.controller.currentPosition}
    private fun events(prefix:String="normalize")=PlaybackEventLog(device.target).recent().mapNotNull {line->
        val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
        line.substringAfter(' ').takeIf {stamp>=started && it.startsWith(prefix)}
    }
    /** Gains the main player was given since the test began, in order. */
    private fun gains()=events("normalize gain_db=").map {it.substringAfter('=')}
    /** The same without "unchanged", which is also what the player has for a moment before a song's value is read. */
    private fun applied()=gains().filterNot {it=="0.0"}
    private fun turnedDown()=events().filterNot {it=="normalize gain_db=0.0"}
    private fun advances(what:String) {
        val before=position()
        device.await(what,10_000) {playing() && position()>before+300}
    }

    // ---- AUD01

    @Test fun aLouderDownloadIsTurnedDownByItsStoredValueAndATogglePutsItBack() {
        normalize(true)
        play(download(1,"6.0"))
        device.await("the stored value to be applied") {gains().lastOrNull()=="-6.0"}
        assertEquals("The player's level is not what normalizes",base,device.onMain {device.controller.volume},0f)
        advances("playback while turned down")
        normalize(false)
        device.await("the gain to go back when it is switched off") {gains().lastOrNull()=="0.0"}
        advances("playback after switching off")
        normalize(true)
        device.await("the gain to return when it is switched on") {gains().lastOrNull()=="-6.0"}
        advances("playback after switching on")
        assertEquals(listOf("-6.0","0.0","-6.0"),gains())
        assertEquals(base,device.onMain {device.controller.volume},0f)
    }
    @Test fun attenuationIsClampedAtTwelveDecibels() {
        normalize(true)
        play(download(2,"30.0"))
        device.await("the clamped gain") {gains().lastOrNull()=="-12.0"}
        advances("playback at the clamp")
    }
    @Test fun eachSongInAQueueGetsItsOwnGain() {
        normalize(true)
        val first=download(3,"6.0");val second=download(4,"3.0",asset="tone-b.wav");val third=download(5,null)
        play(first,second,third)
        device.await("the first song's gain") {gains().lastOrNull()=="-6.0"}
        device.onMain {device.controller.seekToNextMediaItem()}
        device.await("the second song's gain",15_000) {current()=="yt:$second" && gains().lastOrNull()=="-3.0"}
        device.onMain {device.controller.seekToNextMediaItem()}
        device.await("a song without a value to be left alone",15_000) {current()=="yt:$third" && gains().lastOrNull()=="0.0"}
        advances("playback of the song without a value")
        device.onMain {device.controller.seekToPreviousMediaItem()}
        device.await("the value to be remembered on the way back",15_000) {current()=="yt:$second" && gains().lastOrNull()=="-3.0"}
        assertEquals(listOf("-6.0","-3.0","-3.0"),applied())
    }
    @Test fun withTheBoosterAtItsMaximumBothAreApplied() {
        audioPrefs.edit().putBoolean("enabled",true).putInt("percent",300).putBoolean(NORMALIZE_KEY,true).commit()
        play(download(6,"6.0"))
        device.await("the stored value to be applied") {gains().lastOrNull()=="-6.0"}
        advances("playback with boost and normalization")
        audioPrefs.edit().putInt("percent",100).commit()
        advances("playback after the booster changed")
        assertEquals("A booster change does not change the track's gain",listOf("-6.0"),applied())
    }

    // ---- AUD02

    @Test fun aDownloadWithoutAValuePlaysUnchanged() {
        normalize(true)
        play(download(7,null))
        advances("playback without a value")
        assertEquals(emptyList<String>(),turnedDown())
    }
    @Test fun aDamagedValuePlaysUnchangedAndDoesNotFail() {
        normalize(true)
        val first=download(8,"loud");val second=download(9,"NaN",asset="tone-b.wav")
        play(first,second)
        advances("playback with a value that is not a number")
        device.onMain {device.controller.seekToNextMediaItem()}
        device.await("the next song",15_000) {current()=="yt:$second" && playing()}
        advances("playback with a value that is not finite")
        assertEquals(emptyList<String>(),turnedDown())
        assertNull(device.onMain {device.controller.playerError})
    }
    @Test fun switchedOffNothingIsTurnedDown() {
        play(download(10,"6.0"))
        advances("playback with normalization off")
        assertEquals(emptyList<String>(),events())
        assertFalse("Off is the default",audioPrefs.contains(NORMALIZE_KEY))
    }
    @Test fun audioFromThePhoneIsNeverNormalized() {
        normalize(true)
        device.playLocal(device.fixtureAudio("tone-a.wav").also(rows::add))
        advances("on-device audio")
        assertEquals(emptyList<String>(),turnedDown())
    }

    // ---- with crossfade (XFD04): the level belongs to the blend, each player keeps its own gain

    @Test fun inABlendTheOutgoingSongKeepsItsGainAndTheLevelComesBack() {
        normalize(true)
        fadePrefs.edit().putBoolean("enabled",true).putInt("seconds",3).commit()
        device.onMain {CrossfadeSettings.get(device.target).reload()}
        val first=download(11,"6.0");val second=download(12,"3.0",asset="tone-b.wav")
        repeat(4) {attempt->
            play(first,second)
            device.await("the first song's gain") {gains().lastOrNull()=="-6.0"}
            device.await("its length") {device.onMain {device.controller.duration>0}}
            device.onMain {device.controller.seekTo(device.controller.duration-12_000)}
            device.await("a blend or the next song",60_000) {device.onMain {SongCrossfadeState.active}!=null || current()=="yt:$second"}
            val blended=device.onMain {SongCrossfadeState.active}!=null || events("crossfade_start").isNotEmpty()
            device.await("the blend to end",30_000) {device.onMain {SongCrossfadeState.active}==null && current()=="yt:$second" && playing()}
            device.await("the incoming song's gain") {gains().lastOrNull()=="-3.0"}
            assertEquals("The level is back where the user had it",base,device.onMain {device.controller.volume},0f)
            assertEquals("The gains the main player had, in order",listOf("-6.0","-3.0"),applied())
            if(blended) {
                assertTrue("The outgoing copy had the outgoing song's gain: ${events()}",events().contains("normalize_outgoing gain_db=-6.0"))
                assertFalse("The copy never took the incoming song's gain",events().contains("normalize_outgoing gain_db=-3.0"))
                device.await("the copy to be released",10_000) {events("crossfade_tail_created").size==events("crossfade_tail_released").size}
                return
            }
            // An emulator's two audio clocks often disagree and the service then gives the blend up safely.
            android.util.Log.i("RiffDeviceTest","normalization blend attempt ${attempt+1} was given up; playing the queue again")
            device.emptyPlayer()
            device.await("no copy left",10_000) {events("crossfade_tail_created").size==events("crossfade_tail_released").size}
            settle()
        }
        throw AssertionError("The two decoders did not align in four attempts; no blend to examine")
    }
}
