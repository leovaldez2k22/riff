package com.musicglass.personal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Q09: what the store makes of every point at which the app can stop during a download or a removal,
 * and who may write a row. The stopping points are built on disk and in the table as they would be left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadReconcileTest {
    private val context:Context get()=RuntimeEnvironment.getApplication()
    /** A store opened as the app opens it after a restart. */
    private fun open()=OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply {isAccessible=true}.newInstance(context)
    private val scope="reconcile"
    private val mp4=byteArrayOf(0,0,0,0x18)+"ftypisom".toByteArray()+ByteArray(60) {7}
    private fun track(n:Int)=ProviderTrack("reconcile%02d".format(n),"Song $n","Artist")
    private fun id(n:Int)=track(n).videoId
    private fun OfflineMusicStore.files(n:Int,vararg names:String):File {
        val dir=directory(scope,id(n)).apply {mkdirs()}
        for(name in names) when(name) {
            "audio.media" -> File(dir,name).writeBytes(mp4)
            "ready" -> File(dir,name).writeText(mp4.size.toString())
            else -> File(dir,name).writeText("unfinished")
        }
        return dir
    }
    private fun OfflineMusicStore.stoppedIn(n:Int,state:String,vararg files:String):File {
        enqueue(track(n),scope=scope);update(scope,id(n),"state" to state)
        return files(n,*files)
    }
    private fun File.names()=list()?.toSet().orEmpty()

    // ---- DLD01: a stop in each phase

    @Test fun aStopInEachPhaseOfATransferLeavesTheSongWaitingAndNeverFalselyComplete() {
        val before=open()
        before.stoppedIn(1,"resolving")
        before.stoppedIn(2,"transferring","audio.part")
        before.stoppedIn(3,"validating","audio.part","assembled.part")
        // The rebuilt audio was renamed into place; the stop came before its marker.
        before.stoppedIn(4,"validating","audio.part","audio.media","ready.part")
        before.stoppedIn(5,"downloading","audio.part")
        val generations=(1..5).associateWith {before.song(scope,id(it))!!.generation}

        val after=open()
        assertEquals(ReconcileReport(requeued=5,filesSwept=7),after.startupReport)
        for(n in 1..5) {
            val row=after.song(scope,id(n))!!
            assertEquals("song $n",DownloadState.Queued,row.status);assertTrue(row.pinned)
            assertNull("song $n is not playable",after.audio(scope,id(n)))
            assertEquals("song $n has nothing unfinished left",emptySet<String>(),after.directory(scope,id(n)).names())
            assertEquals("The transfer that was stopped can no longer write",generations.getValue(n)+1,row.generation)
            assertFalse(after.updateDownload(scope,id(n),generations.getValue(n),"state" to "complete"))
        }
        assertEquals("Opening it again changes nothing",ReconcileReport(),open().startupReport)
    }
    @Test fun audioCommittedWithItsMarkerIsKeptAndOnlyWhatIsMissingIsFetched() {
        val before=open()
        // After the marker, before the table said so.
        before.stoppedIn(1,"validating","audio.media","ready","audio.part")
        // While artwork and lyrics were being saved.
        before.stoppedIn(2,"assets","audio.media","ready","artwork.part","lyrics.part")
        val after=open()
        for(n in 1..2) {
            assertEquals(DownloadState.Queued,after.song(scope,id(n))!!.status)
            assertNotNull("song $n plays",after.audio(scope,id(n)))
            assertEquals(setOf("audio.media","ready"),after.directory(scope,id(n)).names())
        }
        assertEquals(ReconcileReport(requeued=2,filesSwept=3),after.startupReport)
    }
    @Test fun aRemovalThatWasInterruptedIsFinished() {
        val before=open()
        before.stoppedIn(1,"complete","audio.media","ready","artwork");before.update(scope,id(1),"plays" to 4,"lastPlayed" to 900L)
        // remove() marks the row, then deletes; the stop came between the two.
        before.update(scope,id(1),"state" to "cancelled","pinned" to 0,"excluded" to 1)
        val after=open()
        val row=after.song(scope,id(1))!!
        assertEquals(DownloadState.Metadata,row.status);assertFalse(row.pinned);assertTrue("Still excluded from Smart Downloads",row.excluded)
        assertEquals(4,row.plays);assertEquals(900L,row.lastPlayed);assertEquals(0L,row.bytes)
        assertFalse(after.directory(scope,id(1)).exists());assertNull(after.audio(scope,id(1)))
        assertEquals(ReconcileReport(removalsFinished=1),after.startupReport)
    }
    @Test fun aStateNoBuildWritesIsRepairedAndTheRowIsKept() {
        val before=open()
        before.stoppedIn(1,"weird","audio.media","ready");before.stoppedIn(2,"weird","audio.part")
        before.stoppedIn(3,"")
        val after=open()
        assertEquals(DownloadState.Complete,after.song(scope,id(1))!!.status);assertNotNull(after.audio(scope,id(1)))
        for(n in 2..3) {
            val row=after.song(scope,id(n))!!
            assertEquals(DownloadState.Failed,row.status);assertEquals(DownloadFailure.Unrecognised.message,row.error);assertTrue(row.pinned)
            assertNull(after.audio(scope,id(n)))
        }
        assertEquals(3,after.rows(scope).size);assertEquals(3,after.startupReport.unrecognised)
    }
    @Test fun waitingPausedAndFailedSongsKeepTheirStateAndLoseOnlyUnfinishedFiles() {
        val before=open()
        before.stoppedIn(1,"queued","audio.part");before.stoppedIn(2,"paused","assembled.part");before.stoppedIn(3,"failed","audio.part")
        before.update(scope,id(3),"error" to "kept","failures" to 2)
        val after=open()
        assertEquals(listOf("queued","paused","failed"),(1..3).map {after.song(scope,id(it))!!.state})
        assertEquals("kept",after.song(scope,id(3))!!.error);assertEquals(2,after.song(scope,id(3))!!.failures)
        for(n in 1..3) assertEquals(emptySet<String>(),after.directory(scope,id(n)).names())
        assertEquals(ReconcileReport(filesSwept=3),after.startupReport)
    }
    @Test fun finishedDownloadsAndPlainRowsAreNotTouchedAtStart() {
        val before=open()
        val done=before.stoppedIn(1,"complete","audio.media","ready","artwork","lyrics.json","loudness")
        val stamp=File(done,"audio.media").lastModified()
        before.remember(track(2),scope)
        // Its files went away while the app was not running. Start-up does not open finished downloads.
        before.stoppedIn(3,"complete")
        val after=open()
        assertEquals(ReconcileReport(),after.startupReport)
        assertEquals(setOf("audio.media","ready","artwork","lyrics.json","loudness"),done.names());assertEquals(stamp,File(done,"audio.media").lastModified())
        assertEquals(DownloadState.Metadata,after.song(scope,id(2))!!.status)
        assertEquals(DownloadState.Complete,after.song(scope,id(3))!!.status)
    }

    // ---- never playable without sound files

    @Test fun aRowIsNeverPlayableByItsStateAlone() {
        val store=open()
        store.remember(track(1),scope)
        store.importMetadata(mapOf(scope to listOf(track(2))))
        store.stoppedIn(3,"complete");store.stoppedIn(4,"complete","audio.media");store.stoppedIn(5,"complete","ready")
        for(n in 1..5) assertNull("song $n",store.audio(scope,id(n)))
        // The check made when Downloads is shown and before a run: the row says what is true and can be downloaded again.
        assertEquals(3,store.verifyComplete(scope))
        for(n in 3..5) {
            val row=store.song(scope,id(n))!!
            assertEquals(DownloadState.Failed,row.status);assertEquals(DownloadFailure.Integrity.message,row.error);assertTrue("Still wanted",row.pinned)
        }
        assertEquals(0,store.verifyComplete(scope))
        assertEquals(listOf(DownloadState.Metadata,DownloadState.Metadata),(1..2).map {store.song(scope,id(it))!!.status})
    }
    @Test fun aDamagedDownloadLosesItsMarkerNotItsRowAndIsQueuedAgainOnRequest() {
        val store=open()
        val dir=store.stoppedIn(1,"complete","audio.media","ready","artwork")
        val generation=store.song(scope,id(1))!!.generation
        assertTrue(store.markDamaged(scope,id(1)))
        assertNull(store.audio(scope,id(1)));assertTrue("The audio is not deleted behind the user's back",File(dir,"audio.media").isFile)
        val row=store.song(scope,id(1))!!
        assertEquals(DownloadState.Failed,row.status);assertEquals(generation+1,row.generation);assertEquals(0L,row.bytes)
        store.enqueue(track(1),scope=scope)
        assertEquals(DownloadState.Queued,store.song(scope,id(1))!!.status);assertEquals("",store.song(scope,id(1))!!.error)
        // A transfer in flight is not interrupted by a report about the files it is replacing.
        val held=store.beginDownload(scope,id(1))!!
        assertFalse(store.markDamaged(scope,id(1)));assertTrue(store.holds(scope,id(1),held))
        assertFalse(store.markDamaged(scope,"reconcile99"));assertFalse(store.markDamaged(scope,"../../etc"))
    }

    // ---- who may write a row

    @Test fun onlyTheTransferThatHoldsARowWritesIt() {
        val store=open()
        store.enqueue(track(1),scope=scope)
        assertFalse("Not begun",store.updateDownload(scope,id(1),0,"state" to "complete"))
        val ticket=store.beginDownload(scope,id(1))!!
        assertEquals(DownloadState.Resolving,store.song(scope,id(1))!!.status)
        assertNull("One transfer at a time",store.beginDownload(scope,id(1)))
        assertTrue(store.holds(scope,id(1),ticket));assertFalse(store.holds(scope,id(1),ticket+1));assertFalse(store.holds("other",id(1),ticket))
        assertTrue(store.updateDownload(scope,id(1),ticket,"state" to "transferring","bytes" to 10L))
        assertEquals("ran",store.commitAudio(scope,id(1),ticket) {"ran"})
        // Asking for it again while it is in flight does not restart it.
        store.enqueue(track(1),scope=scope)
        assertEquals(DownloadState.Transferring,store.song(scope,id(1))!!.status);assertTrue(store.holds(scope,id(1),ticket))
        assertEquals(1,store.enqueueSongs(listOf(track(1)),scope));assertTrue(store.holds(scope,id(1),ticket))
        // Pause takes it away.
        assertTrue(store.pause(scope,id(1)))
        assertFalse(store.holds(scope,id(1),ticket));assertFalse(store.updateDownload(scope,id(1),ticket,"state" to "complete"))
        assertNull(store.commitAudio(scope,id(1),ticket) {fail("A commit after a pause");"x"})
        assertFalse(store.failDownload(scope,id(1),ticket,DownloadFailure.Other))
        assertEquals(DownloadState.Paused,store.song(scope,id(1))!!.status)
        assertFalse("Nothing to pause twice",store.pause(scope,id(1)))
        // Queued again and begun again: the old ticket is still dead.
        store.enqueue(track(1),scope=scope);val second=store.beginDownload(scope,id(1))!!
        assertNotEquals(ticket,second);assertFalse(store.holds(scope,id(1),ticket));assertTrue(store.holds(scope,id(1),second))
        // Remove takes it away for good.
        store.remove(scope,id(1))
        assertFalse(store.updateDownload(scope,id(1),second,"state" to "assets"));assertNull(store.beginDownload(scope,id(1)))
        assertEquals(DownloadState.Metadata,store.song(scope,id(1))!!.status)
    }
    @Test fun failuresCountRunsAndAreForgottenWhenTheUserAsksAgain() {
        val store=open();store.enqueue(track(1),scope=scope)
        repeat(3) {attempt->
            val ticket=store.beginDownload(scope,id(1))!!
            assertTrue(store.failDownload(scope,id(1),ticket,DownloadFailure.Rejected))
            val row=store.song(scope,id(1))!!
            assertEquals(attempt+1,row.failures);assertEquals(DownloadFailure.Rejected.message,row.error);assertEquals(DownloadState.Failed,row.status)
        }
        store.enqueue(track(1),scope=scope)
        assertEquals(0,store.song(scope,id(1))!!.failures);assertEquals("",store.song(scope,id(1))!!.error)
    }
    @Test fun pausingByHandAndSwitchingSmartDownloadsOffTouchDifferentSongs() {
        val store=open()
        store.enqueue(track(1),pinned=true,scope=scope);store.enqueue(track(2),pinned=false,scope=scope)
        store.enqueue(track(3),pinned=true,scope=scope);store.beginDownload(scope,id(3))
        store.enqueue(track(4),pinned=false,scope=scope);store.beginDownload(scope,id(4))
        store.stoppedIn(5,"complete","audio.media","ready");store.update(scope,id(5),"pinned" to 0)
        store.stoppedIn(6,"complete","audio.media","ready")
        store.enqueue(track(7),pinned=true,scope="other")

        store.cancelAutomatic(scope)
        assertEquals(listOf("queued","paused","resolving","paused","complete","complete"),(1..6).map {store.song(scope,id(it))!!.state})
        assertTrue("Songs kept by hand stay kept",listOf(1,3,6).all {store.song(scope,id(it))!!.pinned})
        assertNotNull(store.audio(scope,id(5)));assertNotNull(store.audio(scope,id(6)))

        assertEquals(2,store.pauseManual(scope))
        assertEquals(listOf("paused","paused","paused","paused","complete","complete"),(1..6).map {store.song(scope,id(it))!!.state})
        assertEquals("Another account's queue is not this one's to pause",DownloadState.Queued,store.song("other",id(7))!!.status)
    }
    @Test fun aBulkRequestKeepsFinishedPausedAndRunningSongsAndCountsEachOnce() {
        val store=open()
        val done=store.stoppedIn(1,"complete","audio.media","ready");store.update(scope,id(1),"pinned" to 0)
        val stamp=File(done,"audio.media").lastModified()
        store.enqueue(track(2),scope=scope);val ticket=store.beginDownload(scope,id(2))!!
        store.enqueue(track(3),scope=scope);store.pause(scope,id(3))
        // Listed as complete, but its files are gone: it is fetched again rather than counted as done.
        store.stoppedIn(4,"complete")
        val count=store.enqueueSongs(listOf(track(1),track(2),track(3),track(4),track(5),track(5),track(1),track(6).copy(isLive=true)),scope)
        assertEquals(5,count)
        assertEquals(listOf("complete","resolving","queued","queued","queued"),(1..5).map {store.song(scope,id(it))!!.state})
        assertTrue((1..5).all {store.song(scope,id(it))!!.pinned});assertTrue(store.holds(scope,id(2),ticket))
        assertEquals(stamp,File(done,"audio.media").lastModified());assertNull(store.song(scope,id(6)))
    }
    @Test fun deletingOneAccountsDownloadsLeavesAnotherAccountsAlone() {
        val store=open()
        store.stoppedIn(1,"complete","audio.media","ready");store.enqueue(track(2),scope=scope);val ticket=store.beginDownload(scope,id(2))!!
        store.enqueue(track(1),scope="other");File(store.directory("other",id(1)).apply {mkdirs()},"audio.media").writeBytes(mp4)
        File(store.directory("other",id(1)),"ready").writeText(mp4.size.toString());store.update("other",id(1),"state" to "complete")
        store.enqueue(track(2),scope="other");val otherTicket=store.beginDownload("other",id(2))!!

        assertEquals(2 to 0,store.removeAllCounted(scope))
        assertTrue(store.rows(scope).all {it.status==DownloadState.Metadata && it.excluded && !it.pinned})
        assertFalse(store.holds(scope,id(2),ticket));assertNull(store.audio(scope,id(1)))
        assertNotNull(store.audio("other",id(1)));assertTrue(store.holds("other",id(2),otherTicket))
        assertEquals(DownloadState.Complete,store.song("other",id(1))!!.status)
    }

    // ---- the table from before Q09

    @Test fun theOlderTableIsUpgradedInPlaceAndEveryRowSurvives() {
        val path=context.getDatabasePath("offline-music.db");path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use {db->
            db.execSQL("CREATE TABLE songs(scope TEXT NOT NULL,id TEXT NOT NULL,track TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'metadata',pinned INTEGER NOT NULL DEFAULT 0,bytes INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL DEFAULT 0,quality TEXT NOT NULL DEFAULT '',artwork TEXT NOT NULL DEFAULT 'pending',lyrics TEXT NOT NULL DEFAULT 'pending',lastPlayed INTEGER NOT NULL DEFAULT 0,plays INTEGER NOT NULL DEFAULT 0,listened INTEGER NOT NULL DEFAULT 0,priority INTEGER NOT NULL DEFAULT 0,excluded INTEGER NOT NULL DEFAULT 0,downloaded_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,id))")
            for((n,state) in listOf("metadata","queued","downloading","assets","complete","paused","failed","cancelled").withIndex()) {
                db.insert("songs",null,ContentValues().apply {
                    put("scope",scope);put("id",id(n+1));put("track","""{"title":"Old ${n+1}","artist":"Artist","music":true}""")
                    put("state",state);put("pinned",1);put("plays",n+1);put("error",if(state=="failed") "Old message" else "")
                })
            }
            db.version=3
        }
        // The one that was complete has the files an older build wrote, with the older marker.
        val root=File(context.filesDir,"offline-music-v1")
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        val old=File(root,"$digest/${id(5)}").apply {mkdirs()};File(old,"audio.media").writeBytes(mp4);File(old,"ready").writeText("1")

        val store=open()
        val rows=store.rows(scope).associateBy {it.track.videoId}
        assertEquals(8,rows.size)
        assertEquals(listOf("metadata","queued","queued","queued","complete","paused","failed","metadata"),(1..8).map {rows.getValue(id(it)).state})
        assertEquals((1..8).toList(),(1..8).map {rows.getValue(id(it)).plays})
        assertEquals("Old 3",rows.getValue(id(3)).track.title);assertEquals("Old message",rows.getValue(id(7)).error)
        assertTrue(rows.values.all {it.failures==0})
        assertNotNull("The older marker is still read",store.audio(scope,id(5)));assertTrue(rows.getValue(id(5)).downloadedAt>0)
        assertEquals(ReconcileReport(requeued=2,removalsFinished=1),store.startupReport)
        // And it works as a table of this version.
        assertNotNull(store.beginDownload(scope,id(2)))
    }
}
