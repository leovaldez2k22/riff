package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 26: removing a download waits behind Undo; the files go only once the offer has passed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadUndoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = OfflineMusicStore.get(context)
    private val scope get() = store.currentScope
    /** The shell's notice, held by hand: the last offer's undo and expire, and how many were shown. */
    private var undo: (() -> Unit)? = null
    private var expire: (() -> Unit)? = null
    private var shown = 0

    @Before fun shell() {
        YouTubeMusicProvider.shared(context).close()
        // As RiffApp does: a newer notice dismisses the one before, which lets that removal go ahead.
        UndoNotices.listener = { _, u, e -> expire?.invoke(); undo = u; expire = e; shown++ }
    }
    @After fun noShell() {
        UndoNotices.listener = null
        // The store is shared by every test in this JVM; other screens expect no downloads.
        store.shown(scope).forEach { store.unhide(scope, it.track.videoId) }
        (1..6).forEach { store.unhide(scope, "undodl0000$it") }
        store.removeAll(scope)
    }

    private fun downloaded(id: String): ProviderTrack {
        val track = ProviderTrack(id, "Song $id", "Artist")
        store.enqueue(track, scope = scope)
        val dir = store.directory(scope, id).apply { mkdirs() }
        File(dir, BundleValidator.AUDIO).writeBytes(byteArrayOf(1, 2, 3, 4)); File(dir, BundleValidator.READY).writeText("4")
        store.update(scope, id, "state" to DownloadState.Complete.db, "bytes" to 4L)
        return track
    }
    /** On the Downloads screen: shown, and neither a bare name nor a cancelled row. */
    private fun listed(id: String) = store.shown(scope).any { it.track.videoId == id && it.status != DownloadState.Metadata && it.status != DownloadState.Cancelled }
    private fun waitFor(what: String, test: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!test()) { assertTrue(what, System.currentTimeMillis() < until); Thread.sleep(20) }
    }
    private fun remove(track: ProviderTrack) = runBlocking { requestSongRemoval(context, track, { }) }

    @Test fun theSongLeavesTheListsAtOnceButItsFilesWaitAndUndoBringsItBack() {
        val track = downloaded("undodl00001")
        remove(track)
        assertEquals(1, shown)
        assertFalse("hidden from Downloads, the Library tile and Auto", listed(track.videoId))
        assertNotNull("the files are still there", store.audio(scope, track.videoId))
        undo!!()
        assertTrue(listed(track.videoId)); assertNotNull(store.audio(scope, track.videoId))
    }

    @Test fun whenTheOfferPassesTheFilesGo() {
        val track = downloaded("undodl00002")
        remove(track); expire!!()
        waitFor("the files were removed") { store.audio(scope, track.videoId) == null }
        waitFor("no longer waiting") { !store.awaitingRemoval(scope, track.videoId) }
        assertFalse(listed(track.videoId))
    }

    @Test fun aSecondRemovalCommitsTheFirst() {
        val first = downloaded("undodl00003"); val second = downloaded("undodl00004")
        remove(first); remove(second)
        waitFor("the first song's files went when the second notice replaced it") { store.audio(scope, first.videoId) == null }
        assertNotNull("the second can still be undone", store.audio(scope, second.videoId))
        undo!!(); assertTrue(listed(second.videoId))
    }

    @Test fun downloadingItAgainMeanwhileKeepsIt() {
        val track = downloaded("undodl00005")
        remove(track)
        runBlocking { requestSongDownload(context, track) { } }
        expire!!()
        Thread.sleep(200)
        assertNotNull(store.audio(scope, track.videoId)); assertTrue(listed(track.videoId))
    }

    @Test fun aDownloadStillOnItsWayIsRemovedAtOnceWithoutUndo() {
        val track = ProviderTrack("undodl00006", "Song", "Artist")
        store.enqueue(track, scope = scope)
        remove(track)
        assertEquals("no Undo offered", 0, shown)
        assertNull(store.audio(scope, track.videoId)); assertFalse(listed(track.videoId))
    }

    @Test fun withNoShellTheRemovalGoesAheadAtOnce() {
        UndoNotices.listener = null
        var expired = false
        assertFalse(UndoNotices.show("Removed", expire = { expired = true }) { fail("nothing to undo") })
        assertTrue(expired)
    }
}
