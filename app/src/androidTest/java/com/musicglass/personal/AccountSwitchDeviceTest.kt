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
 * Account switches against the real playback service on a disposable emulator. Identities are
 * synthetic session fields and nothing is resolved or played from the provider: run with the
 * emulator's network off. Skips itself on an install that holds a real account.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class AccountSwitchDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val target:Context get()=instrumentation.targetContext
    private val provider by lazy {YouTubeMusicProvider.shared(target)}
    private val sessions by lazy {AppContainer.get(target).sessions}
    private val sessionPrefs get()=target.getSharedPreferences("provider_session",Context.MODE_PRIVATE)
    private lateinit var controller:MediaController
    private var localRow:Uri?=null

    @Before fun connect() {
        val sync=sessionPrefs.getString("sync",null)
        assumeTrue("Install holds a non-fixture account",!provider.signedIn || sync?.startsWith("fixture-sync-")==true)
        clearSavedQueues()
        val future=MediaController.Builder(target,MediaSessionToken(target,ComponentName(target,MusicPlaybackService::class.java)))
            .setApplicationLooper(Looper.getMainLooper()).buildAsync()
        controller=future.get(15,TimeUnit.SECONDS)
        become(null)
        instrumentation.runOnMainSync {controller.stop();controller.clearMediaItems()}
        await("empty player") {ids().isEmpty()}
    }

    @After fun disconnect() {
        if(::controller.isInitialized) {
            become(null)
            instrumentation.runOnMainSync {controller.stop();controller.clearMediaItems();controller.release()}
        }
        localRow?.let {target.contentResolver.delete(it,null,null)}
        clearSavedQueues()
    }

    private fun clearSavedQueues() {
        val process=AppContainer.get(target)
        runBlocking {process.stateReady.await()}
        (listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY)+listOf("fixture-sync-A","fixture-sync-B").map {providerScope(verifiedAccountIdentity(it)!!).opaqueId})
            .forEach(process.snapshots::clear)
    }
    /** Switches to a synthetic account, or to Guest for null, through the coordinator. */
    private fun become(sync:String?):SessionTransition = runBlocking {
        withContext(Dispatchers.Main) {
            if(sync==null) sessions.transition("fixture_sign_out",AccountScope.Guest) {
                provider.signOut();CookieManager.getInstance().removeAllCookies(null);true
            } else sessions.transition("fixture_switch",providerScope(verifiedAccountIdentity(sync)!!)) {
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

    private fun queueArgs(videoIds:List<String>,index:Int,session:SessionToken):Bundle {
        val file=File(target.cacheDir,"queue-request-${UUID.randomUUID()}.json")
        file.writeText(encodePlaybackSnapshot(PlaybackSnapshot(videoIds.map {SavedQueueItem("yt:$it","Fixture song","Fixture artist")},index,0)))
        return Bundle().apply {
            putString("queueFile",file.name);putBoolean("autoplay",false)
            putLong("sessionGeneration",session.generation);putString("sessionNonce",session.nonce)
        }
    }
    private fun send(action:String,args:Bundle):Int {
        val result=onMain {controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)}
        return result.get(10,TimeUnit.SECONDS).resultCode
    }
    private fun openQueue(videoIds:List<String>,index:Int=0) {
        assertEquals(SessionResult.RESULT_SUCCESS,send(PLAY_CATALOG_QUEUE,queueArgs(videoIds,index,provider.session.value)))
        await("queue $videoIds") {ids()==videoIds.map {"yt:$it"}}
    }

    @Test fun eachAccountGetsItsOwnQueueBackPaused() {
        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        openQueue(listOf("fixtureA001","fixtureA002","fixtureA003"),index=1)

        assertTrue(become("fixture-sync-B") is SessionTransition.Committed)
        await("A's queue to leave the player") {ids().isEmpty()}
        openQueue(listOf("fixtureB001"))

        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        await("A's queue to come back") {ids()==listOf("yt:fixtureA001","yt:fixtureA002","yt:fixtureA003")}
        assertEquals(1,onMain {controller.currentMediaItemIndex})
        assertFalse("A restored queue must stay paused",onMain {controller.playWhenReady || controller.isPlaying})

        assertTrue(become("fixture-sync-B") is SessionTransition.Committed)
        await("B's queue to come back") {ids()==listOf("yt:fixtureB001")}
        assertFalse(onMain {controller.playWhenReady || controller.isPlaying})

        assertTrue(become(null) is SessionTransition.Committed)
        await("the account's queue to leave on sign-out") {ids().isEmpty()}
        assertFalse(provider.signedIn)
    }

    @Test fun queueRequestBuiltForThePreviousAccountIsSkipped() {
        become("fixture-sync-A")
        val requestForA=queueArgs(listOf("fixtureA001"),0,provider.session.value)
        become("fixture-sync-B")
        assertEquals(SessionError.INFO_CANCELLED,send(PLAY_CATALOG_QUEUE,requestForA))
        Thread.sleep(500)
        assertTrue("B must not receive A's queue",ids().isEmpty())
        // The same request shape with B's own session is accepted.
        openQueue(listOf("fixtureB001"))
    }

    @Test fun onDeviceQueueKeepsItsPlaceAcrossAnAccountSwitch() {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName,audioLibraryPermission())
        val audio=instrumentation.context.assets.open("quality/audio/tone-a.wav").use {it.readBytes()}
        val row=target.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"riff-fixture-${UUID.randomUUID()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/x-wav")
        })!!
        localRow=row
        target.contentResolver.openOutputStream(row)!!.use {it.write(audio)}
        val id=row.lastPathSegment!!.toLong()

        become("fixture-sync-A")
        onMain {controller.volume=0f}
        assertEquals(SessionResult.RESULT_SUCCESS,send(PLAY_LOCAL_AUDIO,Bundle().apply {
            putString("uri","content://media/external/audio/media/$id");putLong("id",id)
            putString("title","Fixture tone");putString("artist","Fixture")
        }))
        await("on-device audio to start") {ids()==listOf("local:$id") && onMain {controller.isPlaying}}

        assertTrue(become("fixture-sync-B") is SessionTransition.Committed)
        Thread.sleep(500)
        assertEquals(listOf("local:$id"),ids())
        assertTrue("On-device audio must keep playing through the switch",onMain {controller.playWhenReady})
        onMain {controller.pause()}
    }
}
