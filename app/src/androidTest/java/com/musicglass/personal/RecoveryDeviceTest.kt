package com.musicglass.personal

import android.net.Uri
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recovery against the real playback service (Q07). Emulator only, with the network off: a queued
 * "YouTube Music" entry cannot be fetched, which is the fault these tests need. Nothing here
 * reaches a provider.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class RecoveryDeviceTest {
    private val device=PlaybackDeviceHarness()
    private var gateway:MusicPlaybackGateway?=null
    private var localRow:Uri?=null
    private var started=0L
    private fun fixture(n:Int)="fixtureR%03d".format(n)
    /** Recovery events written since the test began, without their time stamp. */
    private fun events()=PlaybackEventLog(device.target).recent().mapNotNull {line->
        val stamp=line.substringBefore(' ').toLongOrNull()?:return@mapNotNull null
        line.substringAfter(' ').takeIf {stamp>=started && it.startsWith("recovery")}
    }
    private fun state()=device.onMain {gateway!!.state}

    @Before fun connect() {
        device.requireDisposableInstall()
        device.connect();device.signedOut();device.emptyPlayer()
        started=System.currentTimeMillis()
        gateway=device.onMain {MusicPlaybackGateway(device.target)}
        device.await("the app's own controller") {state().ready}
    }
    @After fun disconnect() {
        if(!device.connected) return
        device.onMain {device.controller.stop();device.controller.clearMediaItems();device.controller.release();gateway?.close()}
        localRow?.let {device.target.contentResolver.delete(it,null,null)}
        listOf(GUEST_SCOPE_KEY,DEVICE_LOCAL_SCOPE_KEY).forEach(device.process.snapshots::clear)
    }

    @Test fun withoutAConnectionTheEntryWaitsAndAPauseEndsTheWait() {
        assumeTrue("The network must be off",!device.process.runtime.networkConditions().validated)
        assertEquals(SessionResult.RESULT_SUCCESS,device.send(PLAY_CATALOG_QUEUE,device.queueArgs((1..3).map {fixture(it) to "Song $it"},autoplay=true)))
        device.await("waiting to reconnect",40_000) {state().playback.recovery==RecoveryReason.Network}
        var now=state()
        assertEquals(PlaybackStatus.Recovering,now.playback.status)
        assertEquals("Connection lost; waiting to reconnect",now.notice)
        assertNull("waiting is not a failure",now.error)
        assertTrue("the wish to play is kept",now.playback.desiredPlayWhenReady)
        val entry=now.playback.queueItemId
        // Waiting uses no attempt, however long it lasts.
        Thread.sleep(6_000)
        assertTrue(events().toString(),events().none {it.startsWith("recovery class=")})
        assertEquals(1,events().count {it.startsWith("recovery_wait_online")})

        // Pause: the wait ends, nothing is shown as failed, and nothing resumes.
        device.onMain {gateway!!.pause()}
        device.await("the wait to end") {state().playback.status==PlaybackStatus.Paused}
        now=state()
        assertNull(now.error);assertNull(now.notice);assertNull(now.playback.recovery)
        assertFalse(now.playback.desiredPlayWhenReady)
        Thread.sleep(3_000)
        assertEquals(PlaybackStatus.Paused,state().playback.status)
        assertEquals(1,events().count {it=="recovery_held"})
        assertEquals("no new wait began while paused",1,events().count {it.startsWith("recovery_wait_online")})

        // Play: the same entry waits again, by the user's request.
        device.onMain {gateway!!.resume()}
        device.await("waiting again") {state().playback.recovery==RecoveryReason.Network}
        assertEquals(entry,state().playback.queueItemId)
        assertEquals(3,device.ids().size)

        // Another entry: recovery for the one left behind ends, and the new one is on its own.
        device.onMain {gateway!!.next()}
        device.await("the next entry to wait",40_000) {state().let {it.playback.queueItemId!=entry && it.playback.recovery==RecoveryReason.Network}}
        assertTrue(events().toString(),events().contains("recovery_cancelled reason=entry"))
        assertTrue(events().none {it.startsWith("recovery_stop") || it.startsWith("recovery_skip")})

        // Emptying the queue ends it.
        device.emptyPlayer()
        device.await("recovery to end") {state().playback.recovery==null}
        assertEquals(PlaybackStatus.Idle,state().playback.status)
    }

    @Test fun aFileThatHasGoneIsAStorageFailureThatIsNotRetried() {
        val row=device.fixtureAudio("long.wav").also {localRow=it}
        device.playLocal(row)
        device.onMain {device.controller.stop()}
        device.target.contentResolver.delete(row,null,null);localRow=null
        device.onMain {device.controller.prepare();device.controller.play()}
        device.await("the storage failure") {state().playback.failure?.kind==PlaybackFailureKind.LocalFileMissing}
        val now=state()
        assertEquals(PlaybackStatus.Failed,now.playback.status)
        assertEquals("File unavailable on this device",now.error)
        assertEquals(listOf(FailureAction.Skip),now.playback.failure!!.actions())
        Thread.sleep(5_000)
        assertTrue(events().toString(),events().none {it.startsWith("recovery class=")})
        assertTrue(events().any {it.startsWith("recovery_stop class=local_missing attempts=0")})
        assertFalse(device.onMain {device.controller.isPlaying})
        // Asked again by the user, it is tried once and fails the same way; nothing loops.
        device.onMain {gateway!!.retry()}
        device.await("the user's retry") {events().contains("recovery_user_retry")}
        device.await("the same failure") {events().count {it.startsWith("recovery_stop class=local_missing")}==2}
        Thread.sleep(3_000)
        assertEquals(1,events().count {it.startsWith("recovery class=")})
        assertEquals(PlaybackFailureKind.LocalFileMissing,state().playback.failure?.kind)
    }
}
