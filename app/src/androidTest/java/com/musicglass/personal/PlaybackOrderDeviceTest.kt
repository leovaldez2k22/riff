package com.musicglass.personal

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * What the two playback device tests share: a controller on the real service, signed-out use,
 * synthetic queue entries and fixture audio. Emulator or the side-by-side debug build only (disposableInstall); the network is expected to be off, so
 * nothing queued from "YouTube Music" can actually be fetched.
 */
/**
 * Where the fixture tests may clear history, delete an owner's data and seed synthetic entries: an emulator, or
 * (Q18C1) the side-by-side debug build com.musicglass.personal.debug, whose data is its own on a real phone. Never
 * Riff, Riff Preview or Riff Lite.
 */
internal fun disposableInstall():Boolean =
    Build.HARDWARE in setOf("ranchu","goldfish") || Build.MODEL.startsWith("sdk_gphone") ||
        InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.musicglass.personal.debug"

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal open class PlaybackDeviceHarness {
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val target:Context get()=instrumentation.targetContext
    val process by lazy {AppContainer.get(target)}
    val provider by lazy {YouTubeMusicProvider.shared(target)}
    lateinit var controller:MediaController
    val connected get()=::controller.isInitialized

    fun requireDisposableInstall() {
        assumeTrue("Emulator or the side-by-side debug build only",disposableInstall())
        val sync=target.getSharedPreferences("provider_session",Context.MODE_PRIVATE).getString("sync",null)
        assumeTrue("Install holds a non-fixture account",!provider.signedIn || sync?.startsWith("fixture-sync-")==true)
    }
    fun connect() {
        // Q18-071: every wait here has a deadline, so a test that cannot start fails instead of hanging.
        runBlocking {withTimeout(30_000) {process.stateReady.await()}}
        controller=MediaController.Builder(target,MediaSessionToken(target,ComponentName(target,MusicPlaybackService::class.java)))
            .setApplicationLooper(Looper.getMainLooper()).buildAsync().get(15,TimeUnit.SECONDS)
    }
    fun signedOut() = runBlocking {withTimeout(30_000) {withContext(Dispatchers.Main) {
        process.sessions.transition("fixture_sign_out",AccountScope.Guest) {provider.signOut();CookieManager.getInstance().removeAllCookies(null);true}
    }}}
    fun emptyPlayer() {
        onMain {controller.stop();controller.clearMediaItems()}
        await("empty player") {ids().isEmpty()}
    }
    fun <T> onMain(block:()->T):T {var value:T?=null;instrumentation.runOnMainSync {value=block()};@Suppress("UNCHECKED_CAST") return value as T}
    fun ids()=onMain {(0 until controller.mediaItemCount).map {controller.getMediaItemAt(it).mediaId}}
    fun await(what:String,timeoutMs:Long=10_000,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<deadline) {if(condition()) return;Thread.sleep(50)}
        fail("Timed out waiting for $what; queue=${ids()}")
    }
    /** A queue request as the app writes it: (video id, title) pairs. */
    fun queueArgs(entries:List<Pair<String,String>>,index:Int=0,autoplay:Boolean=false):Bundle {
        val file=File(target.cacheDir,"queue-request-${UUID.randomUUID()}.json")
        file.writeText(encodePlaybackSnapshot(PlaybackSnapshot(entries.map {(id,title)->SavedQueueItem("yt:$id",title,"Fixture artist")},index,0)))
        val session=provider.session.value
        return Bundle().apply {putString("queueFile",file.name);putBoolean("autoplay",autoplay)
            putLong("sessionGeneration",session.generation);putString("sessionNonce",session.nonce)}
    }
    fun command(action:String,args:Bundle):ListenableFuture<SessionResult> = controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)
    fun send(action:String,args:Bundle):Int = onMain {command(action,args)}.get(15,TimeUnit.SECONDS).resultCode
    fun revision()=onMain {controller.sessionExtras.getLong("queueRevision")}
    /** Puts a fixture file in the phone's audio library and returns its row. The caller deletes it. */
    fun fixtureAudio(asset:String):Uri {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName,audioLibraryPermission())
        val audio=instrumentation.context.assets.open("quality/audio/$asset").use {it.readBytes()}
        val row=target.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME,"riff-fixture-${UUID.randomUUID()}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE,"audio/x-wav")
        })!!
        target.contentResolver.openOutputStream(row)!!.use {it.write(audio)}
        return row
    }
    fun playLocal(row:Uri) {
        val id=row.lastPathSegment!!.toLong()
        onMain {controller.volume=0f}
        assertEquals(SessionResult.RESULT_SUCCESS,send(PLAY_LOCAL_AUDIO,Bundle().apply {
            putString("uri","content://media/external/audio/media/$id");putLong("id",id)
            putString("title","Fixture tone");putString("artist","Fixture")
        }))
        await("on-device audio to start") {ids()==listOf("local:$id") && onMain {controller.isPlaying}}
    }
}

/**
 * Command order, queue-entry identity and typed state against the real playback service (Q06).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackOrderDeviceTest {
    private val device=PlaybackDeviceHarness()
    private var gateway:MusicPlaybackGateway?=null
    private var localRow:Uri?=null
    private fun fixture(n:Int)="fixtureQ%03d".format(n)

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
    }
    @After fun disconnect() {
        if(!device.connected) return
        device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.release();gateway?.close()}
        localRow?.let {device.target.contentResolver.delete(it,null,null)}
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
    }
    private fun appGateway():MusicPlaybackGateway {
        val real=device.onMain {MusicPlaybackGateway(device.target).also {gateway=it}}
        device.await("the app's own controller") {device.onMain {real.state.ready}}
        return real
    }

    @Test fun rapidQueueReplacementsLeaveOnlyTheNewest() {
        val before=device.revision()
        val large=device.queueArgs((1..2000).map {"f%010d".format(it) to "Large $it"})
        val middle=device.queueArgs((1..3).map {fixture(it) to "Middle $it"})
        val newest=device.queueArgs(listOf(fixture(8) to "Newest one",fixture(9) to "Newest two"),index=1)
        // All three are sent before the service has handled any of them.
        val results=device.onMain {listOf(large,middle,newest).map {device.command(PLAY_CATALOG_QUEUE,it)}}.map {it.get(20,TimeUnit.SECONDS).resultCode}
        assertEquals(listOf(SessionError.INFO_CANCELLED,SessionError.INFO_CANCELLED,SessionResult.RESULT_SUCCESS),results)
        device.await("the newest queue") {device.ids()==listOf("yt:${fixture(8)}","yt:${fixture(9)}")}
        assertEquals(1,device.onMain {device.controller.currentMediaItemIndex})
        Thread.sleep(1_000)
        assertEquals("nothing older lands afterwards",listOf("yt:${fixture(8)}","yt:${fixture(9)}"),device.ids())
        assertTrue("the queue revision is raised by the replacement",device.revision()>before)
        assertTrue("requests that were dropped leave no file behind",
            listOf(large,middle,newest).none {File(device.target.cacheDir,it.getString("queueFile")!!).exists()})
    }

    @Test fun aLargeOlderRequestStillBeingReadDoesNotOverwriteANewerOne() {
        val large=device.queueArgs((1..2000).map {"f%010d".format(it) to "Large $it"})
        val newest=device.queueArgs(listOf(fixture(1) to "Newest"))
        val first=device.onMain {device.command(PLAY_CATALOG_QUEUE,large)}
        val second=device.onMain {device.command(PLAY_CATALOG_QUEUE,newest)}
        assertEquals(SessionResult.RESULT_SUCCESS,second.get(20,TimeUnit.SECONDS).resultCode)
        // The older one was either dropped while it was read, or applied first and then replaced.
        assertTrue(first.get(20,TimeUnit.SECONDS).resultCode in setOf(SessionError.INFO_CANCELLED,SessionResult.RESULT_SUCCESS))
        device.await("the newest queue") {device.ids()==listOf("yt:${fixture(1)}")}
        Thread.sleep(1_000)
        assertEquals(listOf("yt:${fixture(1)}"),device.ids())
    }

    @Test fun aPauseMadeWhileAQueueIsBeingOpenedLeavesItSilent() {
        val row=device.fixtureAudio("tone-a.wav").also {localRow=it}
        device.playLocal(row)
        val request=device.queueArgs(listOf(fixture(1) to "Asked to play"),autoplay=true)
        // The request and the pause reach the service in this order, before the queue has been read.
        val result=device.onMain {device.command(PLAY_CATALOG_QUEUE,request).also {device.controller.pause()}}
        assertEquals(SessionResult.RESULT_SUCCESS,result.get(15,TimeUnit.SECONDS).resultCode)
        device.await("the requested queue") {device.ids()==listOf("yt:${fixture(1)}")}
        Thread.sleep(1_500)
        assertFalse("the pause was the last thing the user asked for",device.onMain {device.controller.playWhenReady || device.controller.isPlaying})
    }

    @Test fun theSelectedEntryIsTheEntryNotTheVideoAndEditsFollowIt() {
        val real=appGateway()
        fun state()=device.onMain {real.state}
        assertEquals(PlaybackStatus.Idle,state().playback.status)
        val before=device.revision()
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,
            device.queueArgs(listOf(fixture(1) to "First copy",fixture(1) to "Second copy",fixture(2) to "Other"),index=1)))
        device.await("the queue in the app") {state().queue.size==3 && state().playback.queueItemId.isNotEmpty()}
        val opened=state()
        assertEquals("the same recording twice is two entries",3,opened.queue.map {it.queueId}.toSet().size)
        assertEquals(opened.queue[0].mediaId,opened.queue[1].mediaId)
        val selected=opened.playback.queueItemId
        assertEquals(opened.queue[1].queueId,selected)
        assertEquals("Second copy",opened.title)
        assertEquals(PlaybackStatus.Paused,opened.playback.status);assertFalse(opened.playback.desiredPlayWhenReady)
        device.await("the owner of the queue") {state().playback.owner==GUEST_SCOPE_KEY}
        device.await("the revision to reach the app") {state().playback.queueRevision>before}

        val afterOpen=state().playback.queueRevision
        device.onMain {real.moveQueueItem(1,2)}
        device.await("the move") {state().queue.map {it.title}==listOf("First copy","Other","Second copy")}
        assertEquals(selected,state().playback.queueItemId);assertEquals(2,state().index);assertEquals("Second copy",state().title)
        device.await("the move to raise the revision") {state().playback.queueRevision>afterOpen}

        device.onMain {real.removeQueueItem(0)}
        device.await("the removal") {state().queue.map {it.title}==listOf("Other","Second copy")}
        assertEquals("removing the other copy does not move the selection",selected,state().playback.queueItemId)

        // A row that is no longer there: nothing happens.
        device.onMain {real.removeQueueItem(7);real.moveQueueItem(7,0)}
        Thread.sleep(500)
        assertEquals(listOf("Other","Second copy"),state().queue.map {it.title})

        // The selected entry is last: removing it selects the one before, paused.
        device.onMain {real.removeQueueItem(1)}
        device.await("the entry before to be selected") {state().queue.map {it.title}==listOf("Other") && state().title=="Other"}
        assertEquals(state().queue[0].queueId,state().playback.queueItemId)
        assertEquals(PlaybackStatus.Paused,state().playback.status)
        device.onMain {real.removeQueueItem(0)}
        device.await("an empty queue") {state().queue.isEmpty() && state().playback.status==PlaybackStatus.Idle}
        assertFalse(state().playback.desiredPlayWhenReady)
    }

    @Test fun aSavedQueueKeepsBothCopiesOfARecordingWhenItIsRestored() {
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,
            device.queueArgs(listOf(fixture(1) to "First copy",fixture(2) to "Other",fixture(1) to "Second copy"),index=2)))
        device.await("the queue to be saved",15_000) {device.process.snapshots.load(GUEST_SCOPE_KEY)?.snapshot?.queue?.size==3}
        device.onMain {device.controller.stop();device.controller.clearMediaItems()}
        device.await("empty player") {device.ids().isEmpty()}
        // Emptying the queue forgets it, so put the saved copy back the way a restart finds it.
        val saved=PlaybackSnapshot(listOf("First copy" to 1,"Other" to 2,"Second copy" to 1).map {(title,n)->SavedQueueItem("yt:${fixture(n)}",title,"Fixture artist")},2,0)
        device.await("the emptied queue to be forgotten",15_000) {device.process.snapshots.load(GUEST_SCOPE_KEY)==null}
        assertEquals(StoreOutcome.Done,device.process.snapshots.save(device.process.snapshots.ticket(GUEST_SCOPE_KEY),saved))
        val session=device.provider.session.value
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(RESTORE_SAVED_QUEUE,Bundle().apply {putLong("sessionGeneration",session.generation);putString("sessionNonce",session.nonce)}))
        device.await("the restored queue") {device.ids().size==3}
        val restored=device.onMain {(0 until device.controller.mediaItemCount).map {device.controller.getMediaItemAt(it)}}
        assertEquals(listOf("First copy","Other","Second copy"),restored.map {it.mediaMetadata.title.toString()})
        assertEquals(3,restored.map {it.entryId()}.toSet().size);assertTrue(restored.none {it.entryId().isEmpty()})
        assertEquals("Second copy",device.onMain {device.controller.currentMediaItem?.mediaMetadata?.title.toString()})
        assertFalse(device.onMain {device.controller.playWhenReady})
    }

    @Test fun nothingIsLookedUpUntilPlaybackIsAskedForAndThenOnlyTheSelectedEntry() {
        val real=appGateway()
        val started=System.currentTimeMillis()
        fun lookups()=PlaybackEventLog(device.target).recent().mapNotNull {line->
            val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
            line.substringAfter("stream_resolve_start lookup=","").takeIf {it.isNotEmpty() && stamp>=started}
        }
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,device.queueArgs((1..30).map {fixture(it) to "Song $it"})))
        device.await("the queue") {device.ids().size==30}
        Thread.sleep(2_000)
        assertTrue("opening a queue paused looks nothing up",lookups().isEmpty())

        device.onMain {device.controller.prepare();device.controller.play()}
        device.await("a lookup for the selected entry") {lookups().isNotEmpty()}
        Thread.sleep(4_000)
        val entries=lookups().toSet()
        assertTrue("only the selected entry and at most the next are looked up, not all 30: ${entries.size}",entries.size in 1..2)
        // With no network the lookup cannot succeed; the state must say so and never claim to be playing.
        val status=device.onMain {real.state.playback.status}
        assertTrue("status=$status",status in setOf(PlaybackStatus.Resolving,PlaybackStatus.Buffering,PlaybackStatus.Recovering,PlaybackStatus.Failed))
        assertTrue(device.onMain {real.state.playback.desiredPlayWhenReady})
        device.onMain {device.controller.pause()}
    }
}

/**
 * Restoration after the app process is killed without warning (Q06, PLY07). Two steps in two
 * processes, chosen with the instrumentation argument `riffPhase`:
 *
 *     am instrument -w -r -e class com.musicglass.personal.ProcessDeathDeviceTest -e riffPhase 1 ...
 *     am instrument -w -r -e class com.musicglass.personal.ProcessDeathDeviceTest -e riffPhase 2 ...
 *
 * Step 1 leaves fixture audio playing and ends; Android kills the process at the end of an
 * instrumentation run without calling the service's shutdown code. Step 2 checks what comes back.
 * Without the argument both steps are skipped. The measurements go to logcat under the tag RiffQ06.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ProcessDeathDeviceTest {
    private val device=PlaybackDeviceHarness()
    private val phase get()=InstrumentationRegistry.getArguments().getString("riffPhase")
    private val notes get()=File(device.target.filesDir,"q06-process-death.txt")
    private fun durable()=device.process.snapshots.load(DEVICE_LOCAL_SCOPE_KEY)?.snapshot

    @Test fun step1LeaveOnDeviceAudioPlaying() {
        assumeTrue("riffPhase=1 only",phase=="1")
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
        val row=device.fixtureAudio("long.wav")
        device.playLocal(row)
        // How far the stored position trails the player while it plays: the most that a kill can lose.
        var worst=0L;var checkpoints=0;var last=-1L
        val until=SystemClock.elapsedRealtime()+17_000
        while(SystemClock.elapsedRealtime()<until) {
            val stored=durable()?.positionMs
            val live=device.onMain {device.controller.currentPosition}
            if(stored!=null) {worst=maxOf(worst,live-stored);if(stored!=last) {checkpoints++;last=stored}}
            Thread.sleep(100)
        }
        assertTrue("checkpoints=$checkpoints",checkpoints>=3)
        assertTrue("stored position trailed by $worst ms",worst<=SNAPSHOT_CADENCE_MS+SCHEDULING_TOLERANCE_MS)
        assertTrue(device.onMain {device.controller.isPlaying})
        val live=device.onMain {device.controller.currentPosition}
        notes.writeText("$row\n$live\n$worst\n$checkpoints")
        android.util.Log.i("RiffQ06","worst_lag_ms=$worst checkpoints=$checkpoints position_at_exit_ms=$live")
        // No clean-up: the process is killed with audio playing.
    }

    @Test fun step2TheQueueIsBackPausedNearTheLastStoredPosition() {
        assumeTrue("riffPhase=2 only",phase=="2")
        device.requireDisposableInstall()
        assertTrue("step 1 has not run",notes.exists())
        val (rowText,liveText,_,_)=notes.readLines()
        val row=Uri.parse(rowText);val atExit=liveText.toLong()
        try {
            runBlocking {device.process.stateReady.await()}
            // Read before the service starts, so this is what the killed process left on disk.
            val stored=durable()
            assertNotNull("nothing was stored",stored);stored!!
            assertEquals(listOf("local:${row.lastPathSegment}"),stored.queue.map {it.mediaId})
            val lost=atExit-stored.positionMs
            assertTrue("lost $lost ms",lost in -500..SNAPSHOT_CADENCE_MS+SCHEDULING_TOLERANCE_MS)

            device.connect()
            device.await("the queue to come back") {device.ids()==listOf("local:${row.lastPathSegment}")}
            val restored=device.onMain {device.controller.currentPosition}
            assertTrue("restored at $restored, stored ${stored.positionMs}",kotlin.math.abs(restored-stored.positionMs)<=1_000)
            Thread.sleep(2_500)
            assertFalse("a restart never starts playback",device.onMain {device.controller.playWhenReady || device.controller.isPlaying})
            assertTrue("it stays where it was",kotlin.math.abs(device.onMain {device.controller.currentPosition}-stored.positionMs)<=1_000)
            android.util.Log.i("RiffQ06","position_at_exit_ms=$atExit stored_ms=${stored.positionMs} restored_ms=$restored lost_ms=$lost")
        } finally {
            if(device.connected) device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.release()}
            device.target.contentResolver.delete(row,null,null)
            listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
            notes.delete()
        }
    }

    private companion object {
        const val SNAPSHOT_CADENCE_MS=5_000L
        /** Allowed on top of the cadence for the timer, the hand-off to the writer thread and the write itself. */
        const val SCHEDULING_TOLERANCE_MS=1_500L
    }
}
