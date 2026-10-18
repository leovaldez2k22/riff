package com.musicglass.personal

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken as MediaSessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Saving, clearing and deleting local data against the real playback service and database.
 * Emulator or the side-by-side debug build only (disposableInstall), because it clears history and deletes an owner's data: identities are synthetic,
 * the only audio played is a fixture file, and the network is expected to be off.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class LocalDataDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val target:Context get()=instrumentation.targetContext
    private val process by lazy {AppContainer.get(target)}
    private val provider by lazy {YouTubeMusicProvider.shared(target)}
    private val sessionPrefs get()=target.getSharedPreferences("provider_session",Context.MODE_PRIVATE)
    private val settings get()=target.getSharedPreferences("music_collections",Context.MODE_PRIVATE)
    private val ownerA by lazy {providerScope(verifiedAccountIdentity("fixture-sync-A")!!).opaqueId}
    private val owners by lazy {listOf(ownerA,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY)}
    private lateinit var controller:MediaController
    private var gateway:MusicPlaybackGateway?=null
    private var localRow:Uri?=null
    private var savingBefore=true

    @Before fun connect() {
        assumeTrue("Emulator or the side-by-side debug build only",disposableInstall())
        val sync=sessionPrefs.getString("sync",null)
        assumeTrue("Install holds a non-fixture account",!provider.signedIn || sync?.startsWith("fixture-sync-")==true)
        runBlocking {process.stateReady.await()}
        savingBefore=historyEnabled(target)
        val future=MediaController.Builder(target,MediaSessionToken(target,ComponentName(target,MusicPlaybackService::class.java)))
            .setApplicationLooper(Looper.getMainLooper()).buildAsync()
        controller=future.get(15,TimeUnit.SECONDS)
        become(null)
        instrumentation.runOnMainSync {controller.stop();controller.clearMediaItems()}
        await("empty player") {ids().isEmpty()}
        clean()
    }

    @After fun disconnect() {
        if(!::controller.isInitialized) return
        settings.edit().putBoolean("history_enabled",savingBefore).commit()
        become(null)
        instrumentation.runOnMainSync {controller.stop();controller.clearMediaItems();controller.release();gateway?.close()}
        localRow?.let {target.contentResolver.delete(it,null,null)}
        clean()
    }

    private fun clean() {
        owners.forEach {process.snapshots.clear(it);process.history.clear(it)}
        runBlocking {process.localData.clearSearches(ownerA)}
    }
    private fun become(sync:String?):SessionTransition = runBlocking {
        withContext(Dispatchers.Main) {
            if(sync==null) process.sessions.transition("fixture_sign_out",AccountScope.Guest) {
                provider.signOut();CookieManager.getInstance().removeAllCookies(null);true
            } else process.sessions.transition("fixture_switch",providerScope(verifiedAccountIdentity(sync)!!)) {
                sessionPrefs.edit().clear().putString("sync",sync).putString("account","0").commit()
                CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")
                provider.refreshAccount()
            }
        }
    }
    private fun <T> onMain(block:()->T):T {var value:T?=null;instrumentation.runOnMainSync {value=block()};@Suppress("UNCHECKED_CAST") return value as T}
    private fun ids()=onMain {(0 until controller.mediaItemCount).map {controller.getMediaItemAt(it).mediaId}}
    private fun await(what:String,timeoutMs:Long=10_000,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<deadline) {if(condition()) return;Thread.sleep(50)}
        fail("Timed out waiting for $what; queue=${ids()}")
    }
    private fun send(action:String,args:Bundle):Int =
        onMain {controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)}.get(10,TimeUnit.SECONDS).resultCode
    private fun sessionArgs()=provider.session.value.let {Bundle().apply {putLong("sessionGeneration",it.generation);putString("sessionNonce",it.nonce)}}
    private fun openQueue(videoIds:List<String>) {
        val file=File(target.cacheDir,"queue-request-${UUID.randomUUID()}.json")
        file.writeText(encodePlaybackSnapshot(PlaybackSnapshot(videoIds.map {SavedQueueItem("yt:$it","Fixture song","Fixture artist")},0,0)))
        assertEquals(SessionResult.RESULT_SUCCESS,send(PLAY_CATALOG_QUEUE,sessionArgs().apply {putString("queueFile",file.name);putBoolean("autoplay",false)}))
        await("queue $videoIds") {ids()==videoIds.map {"yt:$it"}}
    }
    private fun fixtureTone():Long {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName,audioLibraryPermission())
        val audio=instrumentation.context.assets.open("quality/audio/tone-a.wav").use {it.readBytes()}
        val row=target.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"riff-fixture-${UUID.randomUUID()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/x-wav")
        })!!
        localRow=row
        target.contentResolver.openOutputStream(row)!!.use {it.write(audio)}
        return row.lastPathSegment!!.toLong()
    }
    /** Starts the fixture tone from the beginning, muted: a new play as far as history is concerned. */
    private fun playTone(id:Long) {
        onMain {controller.volume=0f}
        assertEquals(SessionResult.RESULT_SUCCESS,send(PLAY_LOCAL_AUDIO,Bundle().apply {
            putString("uri","content://media/external/audio/media/$id");putLong("id",id)
            putString("title","Fixture tone");putString("artist","Fixture")
        }))
        await("on-device audio to start") {ids()==listOf("local:$id") && onMain {controller.isPlaying}}
    }
    private fun devicePlays()=process.history.count(DEVICE_LOCAL_SCOPE_KEY)

    @Test fun savingOffRecordsNothingAndAClearIsNotUndoneByThePlayInProgress() {
        val id=fixtureTone()
        settings.edit().putBoolean("history_enabled",false).commit()
        playTone(id)
        Thread.sleep(8_000) // longer than the service's 5 s tick
        assertTrue(onMain {controller.isPlaying})
        assertEquals("Saving is off: nothing may be written",0,devicePlays())

        settings.edit().putBoolean("history_enabled",true).commit()
        playTone(id)
        await("the play to be recorded",15_000) {devicePlays()==1}
        assertEquals("Fixture tone",process.history.page(DEVICE_LOCAL_SCOPE_KEY).entries.single().title)

        assertEquals(DataActionResult.Done(1),runBlocking {process.localData.clearHistory(DEVICE_LOCAL_SCOPE_KEY)})
        Thread.sleep(7_000)
        assertTrue(onMain {controller.isPlaying})
        assertEquals("The play already counted is not written again after a clear",0,devicePlays())

        // History can be used again at once: no Android "Clear storage" and no restart.
        playTone(id)
        await("a play made after the clear to be recorded",15_000) {devicePlays()==1}
        onMain {controller.pause()}
    }

    @Test fun deletingAnAccountsDataStopsItsQueueAndNothingBringsItBack() {
        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        assertEquals(ownerA,provider.storageScopeKey())
        openQueue(listOf("fixtureA001","fixtureA002"))
        await("the queue to be saved for its owner",15_000) {process.snapshots.load(ownerA)!=null}
        fun entry(event:String)=HistoryEntry("yt:fixtureA001","Fixture song","Fixture artist",null,System.currentTimeMillis(),eventId=event)
        assertEquals(StoreOutcome.Done,process.history.record(entry("fixture-a"),process.history.ticket(ownerA)))
        assertEquals(StoreOutcome.Done,process.history.record(entry("fixture-guest"),process.history.ticket(GUEST_SCOPE_KEY)))
        val real=onMain {MusicPlaybackGateway(target).also {gateway=it}}
        await("the app's own controller") {onMain {real.state.ready}}
        await("the app to see the account's queue") {onMain {real.hasProviderItems()}}

        val result=runBlocking {
            process.localData.deleteAccountData(ownerA) {
                withContext(Dispatchers.Main) {real.clearProviderPlayback()}
                val deadline=SystemClock.elapsedRealtime()+2_000
                while(withContext(Dispatchers.Main) {real.hasProviderItems()} && SystemClock.elapsedRealtime()<deadline) kotlinx.coroutines.delay(50)
            }
        }
        assertEquals(DataActionResult.Done(1),result)
        await("the account's queue to leave the player") {ids().isEmpty()}
        assertEquals(0,process.history.count(ownerA));assertEquals(1,process.history.count(GUEST_SCOPE_KEY))
        Thread.sleep(6_500) // a periodic save that was already scheduled must not write the queue back
        assertNull(process.snapshots.load(ownerA))
        assertEquals(SessionError.ERROR_BAD_VALUE,send(RESTORE_SAVED_QUEUE,sessionArgs()))
        assertTrue("Deleting local data does not sign out",provider.signedIn)

        // The account can be used again at once.
        openQueue(listOf("fixtureA003"))
        await("a new queue to be saved",15_000) {process.snapshots.load(ownerA)?.snapshot?.queue?.map {it.mediaId}==listOf("yt:fixtureA003")}
    }
}
