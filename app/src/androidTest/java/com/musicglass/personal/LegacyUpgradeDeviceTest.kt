package com.musicglass.personal

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
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
import java.util.concurrent.TimeUnit

/**
 * What a build that keeps data per owner does with the shared history file and shared queue of an
 * earlier build, against the real playback service and the real database. Emulator only, because it
 * clears history it wrote: identities are synthetic and the network is expected to be off.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class LegacyUpgradeDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val target:Context get()=instrumentation.targetContext
    private val process by lazy {AppContainer.get(target)}
    private val provider by lazy {YouTubeMusicProvider.shared(target)}
    private val sessionPrefs get()=target.getSharedPreferences("provider_session",Context.MODE_PRIVATE)
    private val oldQueue get()=target.getSharedPreferences("last_playback",Context.MODE_PRIVATE)
    private val oldHistory get()=File(target.filesDir,"listening-history/history-v1.jsonl")
    private val ownerA by lazy {providerScope(verifiedAccountIdentity("fixture-sync-A")!!).opaqueId}
    private val owners by lazy {listOf(ownerA,GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY)}
    private lateinit var controller:MediaController

    @Before fun connect() {
        assumeTrue("Emulator only",Build.HARDWARE in setOf("ranchu","goldfish") || Build.MODEL.startsWith("sdk_gphone"))
        val sync=sessionPrefs.getString("sync",null)
        assumeTrue("Install holds a non-fixture account",!provider.signedIn || sync?.startsWith("fixture-sync-")==true)
        runBlocking {process.stateReady.await()}
        assumeTrue("Earlier data is already waiting on this install",process.legacyState.inventory().empty && !oldHistory.exists() && !oldQueue.contains("snapshot"))
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
        become(null)
        instrumentation.runOnMainSync {controller.stop();controller.clearMediaItems();controller.release()}
        clean()
    }

    private fun clean() {
        oldHistory.delete();oldQueue.edit().remove("snapshot").commit()
        process.legacyState.deleteUnassignedHistory();process.legacyState.deleteQuarantinedQueue()
        owners.forEach {process.snapshots.clear(it);process.history.clear(it)}
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
    private fun restoreSavedQueue():Int {
        val session=provider.session.value
        val args=Bundle().apply {putLong("sessionGeneration",session.generation);putString("sessionNonce",session.nonce)}
        return onMain {controller.sendCustomCommand(SessionCommand(RESTORE_SAVED_QUEUE,Bundle.EMPTY),args)}.get(10,TimeUnit.SECONDS).resultCode
    }

    private fun line(id:String,title:String,at:Long)=encodeHistoryEntry(HistoryEntry(id,title,"Fixture artist",null,at))
    /** The two things an earlier build left behind: one history file and one queue for every account. */
    private fun writeEarlierBuildState() {
        oldHistory.parentFile!!.mkdirs()
        // Different bytes on every run: a file already brought in once is recognised and adds nothing a second time.
        val base=System.currentTimeMillis()-86_400_000L
        oldHistory.writeText(listOf(line("local:990001","Fixture memo",base+1_000),line("yt:fixtureA001","Fixture play one",base+2_000),
            line("yt:fixtureB001","Fixture play two",base+3_000),line("local:990002","Fixture recording",base+4_000)).joinToString("") {it+"\n"})
        val queue=PlaybackSnapshot(listOf("fixtureL001","fixtureL002","fixtureL003").map {SavedQueueItem("yt:$it","Fixture song","Fixture artist")},2,42_000)
        oldQueue.edit().putString("snapshot",encodePlaybackSnapshot(queue).replace("\"format\":\"$SNAPSHOT_FORMAT\",","").replace("\"version\":2","\"version\":1")).commit()
    }

    @Test fun earlierHistoryAndQueueAreSetAsideAndOnlyTheUsersChoiceBringsThemBack() {
        assertTrue(become("fixture-sync-A") is SessionTransition.Committed)
        writeEarlierBuildState()
        // The step every start of this build runs before saved queues are read.
        val report=process.legacyState.run()
        assertEquals(LegacyResult.Completed(2,0),report.history);assertEquals(LegacyResult.Completed(1,0),report.queue)

        assertFalse(oldHistory.exists());assertFalse(oldQueue.contains("snapshot"))
        assertEquals(UnassignedInventory(providerPlays=2,sources=1,queueItems=3),process.legacyState.inventory())
        assertEquals(listOf("Fixture recording","Fixture memo"),process.history.page(DEVICE_LOCAL_SCOPE_KEY).entries.map {it.title})
        assertEquals(0,process.history.count(ownerA));assertEquals(0,process.history.count(GUEST_SCOPE_KEY))
        assertNull(process.snapshots.newest(owners))
        // With nothing saved for this owner there is nothing to restore, and the player is left as it was.
        assertEquals(SessionError.ERROR_BAD_VALUE,restoreSavedQueue())
        assertTrue(ids().isEmpty())

        // The user confirms "Restore it, paused" while account A is active.
        assertEquals(ownerA,process.legacyState.adoptQuarantinedQueue(provider.storageScopeKey()))
        assertEquals(SessionResult.RESULT_SUCCESS,restoreSavedQueue())
        await("the earlier queue") {ids()==listOf("yt:fixtureL001","yt:fixtureL002","yt:fixtureL003")}
        assertEquals(2,onMain {controller.currentMediaItemIndex})
        assertFalse("A restored queue must stay paused",onMain {controller.playWhenReady || controller.isPlaying})
        assertEquals(0,process.legacyState.inventory().queueItems)

        // The user confirms the import while account A is active.
        assertEquals(2,process.legacyState.importProviderHistory(provider.storageScopeKey()))
        assertEquals(listOf("Fixture play two","Fixture play one"),process.history.page(ownerA).entries.map {it.title})
        assertEquals(0,process.history.count(GUEST_SCOPE_KEY))

        // It is A's queue now: signing out takes it out of the player and Guest has none.
        assertTrue(become(null) is SessionTransition.Committed)
        await("the account's queue to leave on sign-out") {ids().isEmpty()}
        assertEquals(SessionError.ERROR_BAD_VALUE,restoreSavedQueue())
        assertNotNull(process.snapshots.load(ownerA))
    }

    @Test fun runningTheUpgradeStepAgainChangesNothing() {
        become("fixture-sync-A")
        writeEarlierBuildState()
        process.legacyState.run()
        val again=process.legacyState.run()
        assertEquals(LegacyResult.Completed(0,0),again.history);assertEquals(LegacyResult.NothingToDo,again.queue)
        assertEquals(2,process.history.count(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(UnassignedInventory(2,1,3),process.legacyState.inventory())
        assertTrue(ids().isEmpty())
    }
}
