package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Q11: saving and loading the queue, apart from the service. A real player and a real store; synthetic owners and songs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db")
    private val epochs = ScopeEpochs(db).also { it.warm() }
    private val rejected = File(context.filesDir, "rejected-${UUID.randomUUID()}.json")
    private val snapshots = PlaybackSnapshotRepository(db, epochs, clock::wallTimeMillis, rejected) {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val ready = CompletableDeferred(Unit)
    private var owner = "owner-a"
    private var seed: String? = null
    // Writes run where they are sent, so each save is on disk when the call returns.
    private val persistence = PlaybackPersistence(scope, snapshots, { ready }, { owner }, { seed }, Dispatchers.Unconfined).also { it.start() }
    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    @After fun close() { player.release(); scope.cancel(); db.close(); rejected.delete() }

    private fun song(id: String, title: String = "Song $id") = MediaItem.Builder().setMediaId("yt:$id").setUri("catalog://$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist("Artist").setAlbumTitle("Album")
            .setExtras(Bundle().apply { putString(ENTRY_ID, UUID.randomUUID().toString()); putString("artistId", "UCfixture"); putBoolean("music", true) }).build()).build()
    private fun file(id: Long) = MediaItem.Builder().setMediaId("local:$id").setUri("content://media/external/audio/media/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("File $id").setArtist("Artist").build()).build()

    @Test fun aSavedQueueIsWrittenForItsOwnerWithWhatIsNeededToShowIt() {
        seed = "fixtureA001"
        player.setMediaItems(listOf(song("fixtureA001"), song("fixtureA002")), 1, 0)
        player.shuffleModeEnabled = true; player.repeatMode = Player.REPEAT_MODE_ALL
        persistence.save(player)
        assertEquals("owner-a", persistence.owner)
        val stored = snapshots.load("owner-a")!!
        assertEquals(listOf("yt:fixtureA001", "yt:fixtureA002"), stored.snapshot.queue.map { it.mediaId })
        assertEquals(1, stored.snapshot.index)
        assertTrue(stored.snapshot.shuffle); assertEquals(Player.REPEAT_MODE_ALL, stored.snapshot.repeat)
        assertEquals("fixtureA001", stored.snapshot.radioSeed)
        assertEquals("Song fixtureA002", stored.snapshot.queue[1].title)
        assertEquals("UCfixture", stored.snapshot.queue[1].artistId)
        assertNull("a catalog address is not a file to restore", stored.snapshot.queue[1].localUri)
        assertNull(snapshots.load(DEVICE_LOCAL_SCOPE_KEY))
    }

    @Test fun onDeviceAudioBelongsToTheDeviceAndKeepsItsAddress() {
        player.setMediaItems(listOf(file(7), file(8)))
        persistence.save(player)
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, persistence.owner)
        assertEquals("content://media/external/audio/media/8", snapshots.load(DEVICE_LOCAL_SCOPE_KEY)!!.snapshot.queue[1].localUri)
        assertNull(snapshots.load("owner-a"))
    }

    @Test fun anUnverifiedAccountSavesNothingForProviderSongs() {
        owner = UNVERIFIED_SCOPE_KEY
        player.setMediaItems(listOf(song("fixtureA001")))
        assertNull(persistence.capture(player))
        assertEquals("", persistence.owner)
        assertNull(snapshots.load(UNVERIFIED_SCOPE_KEY))
    }

    @Test fun emptyingTheQueueClearsOnlyItsOwnOwner() {
        player.setMediaItems(listOf(file(7))); persistence.save(player)
        player.setMediaItems(listOf(song("fixtureA001"))); persistence.save(player)
        assertNull("replaced by another kind of queue: no longer resumable", snapshots.load(DEVICE_LOCAL_SCOPE_KEY))
        snapshots.save(snapshots.ticket("owner-b"), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureB001", "Other", "Artist")), 0, 0))
        player.clearMediaItems(); persistence.save(player)
        assertNull(snapshots.load("owner-a"))
        assertNotNull("another owner's queue is untouched", snapshots.load("owner-b"))
        assertEquals("", persistence.owner)
    }

    @Test fun aQueueTakenFromThePlayerForAnAccountSwitchStaysSaved() = runBlocking {
        player.setMediaItems(listOf(song("fixtureA001"), song("fixtureA002")), 1, 0)
        val pending = persistence.capture(player)!!
        // A later routine capture does not stop the committed write.
        persistence.capture(player)
        assertEquals(StoreOutcome.Done, persistence.commit(pending))
        persistence.release(); player.clearMediaItems(); persistence.save(player)
        assertEquals("the outgoing owner keeps its queue", 2, snapshots.load("owner-a")!!.snapshot.queue.size)
        assertEquals(snapshots.load("owner-a")!!.snapshot.queue.map { it.mediaId }, persistence.load("owner-a")!!.snapshot.queue.map { it.mediaId })
    }

    @Test fun anAdoptedQueueIsClearedWhenItsOwnerEmptiesIt() {
        snapshots.save(snapshots.ticket("owner-a"), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Song", "Artist")), 0, 0))
        persistence.adopt("owner-a")
        assertEquals("owner-a", persistence.owner)
        persistence.save(player)
        assertNull(snapshots.load("owner-a"))
    }

    @Test fun onlyTheNewestOfSeveralCapturedSavesIsWritten() {
        // The writer is held back, as it is while the disk is busy.
        val held = PlaybackPersistence(scope, snapshots, { ready }, { owner }, { seed }, Dispatchers.Unconfined)
        player.setMediaItems(listOf(song("fixtureA001"), song("fixtureA002")), 0, 0); held.save(player)
        player.seekTo(1, 0); held.save(player)
        assertNull(snapshots.load("owner-a"))
        held.start()
        assertEquals(1, snapshots.load("owner-a")!!.snapshot.index)
    }

    @Test fun theLastStateIsWrittenBeforeClosingAndNothingAfter() {
        player.setMediaItems(listOf(song("fixtureA001"), song("fixtureA002")), 0, 0); persistence.save(player)
        player.seekTo(1, 0)
        persistence.flushAndClose(player)
        assertTrue(persistence.closed)
        assertEquals(1, snapshots.load("owner-a")!!.snapshot.index)
        // Teardown empties the player; that must not read as the owner emptying the queue.
        player.clearMediaItems(); persistence.save(player); persistence.flushAndClose(player)
        assertEquals(2, snapshots.load("owner-a")!!.snapshot.queue.size)
        persistence.flushAndClose(null)
    }

    @Test fun theNewestOfTheOwnersAndTheDevicesQueueIsLoadedOnceOlderStateHasMoved() = runBlocking {
        val moving = CompletableDeferred<Unit>()
        val waiting = PlaybackPersistence(scope, snapshots, { moving }, { owner }, { seed }, Dispatchers.Unconfined)
        clock.wall = 1_000; snapshots.save(snapshots.ticket("owner-a"), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Song", "Artist")), 0, 0))
        clock.wall = 2_000; snapshots.save(snapshots.ticket(DEVICE_LOCAL_SCOPE_KEY), PlaybackSnapshot(listOf(SavedQueueItem("local:7", "File", "Artist", localUri = "content://media/external/audio/media/7")), 0, 0))
        clock.wall = 3_000; snapshots.save(snapshots.ticket("owner-b"), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureB001", "Other", "Artist")), 0, 0))
        val loading = scope.async { waiting.loadNewest() }
        assertFalse("waits for older state to be moved", loading.isCompleted)
        moving.complete(Unit)
        assertEquals("another owner's newer queue is never offered", DEVICE_LOCAL_SCOPE_KEY, loading.await()!!.ownerKey)
        owner = UNVERIFIED_SCOPE_KEY
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, waiting.loadNewest()!!.ownerKey)
    }

    @Test fun aFailedMoveOfOlderStateIsNothingSavedButACancelledCallerStaysCancelled() = runBlocking {
        val failed = CompletableDeferred<Unit>().also { it.completeExceptionally(IllegalStateException("disk")) }
        assertNull(PlaybackPersistence(scope, snapshots, { failed }, { owner }, { seed }, Dispatchers.Unconfined).loadNewest())
        val never = CompletableDeferred<Unit>()
        val waiting = PlaybackPersistence(scope, snapshots, { never }, { owner }, { seed }, Dispatchers.Unconfined)
        var finishedNormally = false
        val caller = scope.async { waiting.loadNewest(); finishedNormally = true }
        caller.cancel(); caller.join()
        assertTrue(caller.isCancelled)
        assertFalse("cancellation is not turned into an empty result", finishedNormally)
    }
}
