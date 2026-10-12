package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.datasource.PlaceholderDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Q11: the queue's commands and recommendations, apart from the service. A real player, the real provider with its
 * requests answered from fixtures, and a real store; every identifier, title and address is invented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QueueCoordinatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    // The solver process does not exist here; its client is told so rather than handed an empty connection.
    private val world = shadowOf(context as android.app.Application).declareComponentUnbindable(android.content.ComponentName(context, SolverService::class.java)).let { ProviderWorld(context) }
    private val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db")
    private val rejected = File(context.filesDir, "rejected-${UUID.randomUUID()}.json")
    private val snapshots = PlaybackSnapshotRepository(db, ScopeEpochs(db).also { it.warm() }, world.clock::wallTimeMillis, rejected) {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val events = PlaybackEventLog(context)
    private val order = PlaybackCommandOrder()
    // Nothing is fetched or decoded here: every source refuses to open.
    private val player: ExoPlayer = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(PlaceholderDataSource.FACTORY)).build()
    private val audio = AudioTransitionCoordinator(context, scope, world.runtime, events) { player }
    private val sessions = SessionCoordinator({ world.provider.session.value })
    private var resets = 0
    private val queue: QueueCoordinator = QueueCoordinator(context, scope, world.provider, sessions, OfflineMusicStore.get(context), events, order, audio,
        { persistence }, InternalChange(), { player }) { resets++ }
    private val persistence: PlaybackPersistence = PlaybackPersistence(scope, snapshots, { CompletableDeferred(Unit) }, { "owner-a" }, { queue.radioSeed }, Dispatchers.Unconfined).also { it.start() }
    @After fun close() { player.release(); scope.cancel(); world.close(); db.close(); rejected.delete() }

    private fun saved(vararg ids: String, index: Int = 0, title: String = "Fixture Album") =
        PlaybackSnapshot(ids.map { SavedQueueItem(it, "Song ${it.takeLast(2)}", "Artist ${it.takeLast(2)}") }, index, 0, collectionTitle = title)
    private fun ticket(replaces: Boolean = true) = order.arrive(replaces)
    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    private fun entries() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).entryId() }
    private fun stream(url: String = "https://rr1---sn-fixture.googlevideo.com/videoplayback?id=fixture") =
        ProviderStream(url, mapOf("User-Agent" to "fixture-agent"), "audio/webm", profileId = "fixture-profile", sourceVideoId = "fxsong00001")
    private fun streamArgs(vararg pairs: Pair<String, Any?>) = Bundle().apply {
        putString("url", "https://rr1---sn-fixture.googlevideo.com/videoplayback?id=fixture"); putString("videoId", "fxsong00001")
        putString("title", "Fixture Song 1"); putString("artist", "Fixture Artist")
        pairs.forEach { (k, v) -> when (v) { is String -> putString(k, v); is Boolean -> putBoolean(k, v); is Long -> putLong(k, v); is Bundle -> putBundle(k, v); null -> remove(k); else -> error(k) } }
    }
    private fun code(result: SessionResult) = result.resultCode
    private fun until(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5); if (System.nanoTime() > end) fail("Timed out: $what") }
    }

    // ---- what the service accepts as a resolved stream

    @Test fun aResolvedStreamIsAcceptedOnlyFromTheProvidersMediaHosts() {
        assertNotNull(providerStreamRequest(streamArgs()))
        assertNull(providerStreamRequest(streamArgs("url" to "http://rr1---sn-fixture.googlevideo.com/videoplayback")))
        assertNull(providerStreamRequest(streamArgs("url" to "https://googlevideo.com.example.invalid/videoplayback")))
        assertNull(providerStreamRequest(streamArgs("url" to "file:///data/data/other/files/a.mp3")))
        assertNull(providerStreamRequest(streamArgs("url" to null)))
        assertNull(providerStreamRequest(streamArgs("videoUrl" to "https://example.invalid/video")))
        assertNotNull(providerStreamRequest(streamArgs("videoUrl" to "https://rr2---sn-fixture.googlevideo.com/videoplayback")))
    }

    @Test fun aResolvedStreamNeedsWellFormedIdentifiersAndARegisteredSabrSelection() {
        assertNull(providerStreamRequest(streamArgs("videoId" to "short")))
        assertNull(providerStreamRequest(streamArgs("videoId" to "fxsong0000/")))
        assertNull(providerStreamRequest(streamArgs("streamVideoId" to "../../secret")))
        assertNull("a selection nobody registered", providerStreamRequest(streamArgs("sabrKey" to UUID.randomUUID().toString())))
        assertEquals("fxsong00002", providerStreamRequest(streamArgs("streamVideoId" to "fxsong00002"))!!.stream.sourceVideoId)
    }

    @Test fun onlyTheHeadersAStreamNeedsAreKeptAndTextIsBounded() {
        val headers = Bundle().apply { putString("User-Agent", "fixture-agent"); putString("Cookie", "fixture-cookie"); putString("Authorization", "fixture-auth"); putString("Referer", "https://music.youtube.com/") }
        val request = providerStreamRequest(streamArgs("headers" to headers, "title" to "T".repeat(500), "artworkUrl" to "http://example.invalid/art.jpg"))!!
        assertEquals(setOf("User-Agent", "Referer"), request.stream.headers.keys)
        assertEquals(180, request.track.title.length)
        assertNull("artwork from an unknown place is dropped", request.track.artworkUrl)
    }

    // ---- a queue of catalog entries

    @Test fun aCollectionReplacesTheQueueWithEntriesThatAreLookedUpLater() {
        val result = queue.playCatalogQueue(player, Bundle().apply { putBoolean("autoplay", false) }, saved("yt:fxsong00001", "yt:fxsong00002", "yt:fxsong00003", index = 1), ticket()) { false }
        assertEquals(SessionResult.RESULT_SUCCESS, code(result))
        assertEquals(listOf("yt:fxsong00001", "yt:fxsong00002", "yt:fxsong00003"), ids())
        assertEquals(1, player.currentMediaItemIndex); assertFalse(player.playWhenReady)
        assertEquals(3, entries().filter { it.isNotEmpty() }.toSet().size)
        assertTrue((0 until 3).all { player.getMediaItemAt(it).localConfiguration!!.uri.scheme == "catalog" })
        assertEquals("Fixture Album", player.playlistMetadata.title.toString())
        assertNull("a collection does not start recommendations", queue.radioSeed)
        assertEquals("saved at once for its owner", 3, snapshots.load("owner-a")!!.snapshot.queue.size)
        assertEquals(1, resets)
    }

    @Test fun aQueueWithAnythingButCatalogSongsIsRefusedAndNothingChanges() {
        queue.playCatalogQueue(player, Bundle(), saved("yt:fxsong00001"), ticket()) { false }
        val before = entries()
        assertEquals(SessionError.ERROR_BAD_VALUE, code(queue.playCatalogQueue(player, Bundle(), saved("yt:fxsong00002", "local:7"), ticket()) { false }))
        assertEquals(SessionError.ERROR_BAD_VALUE, code(queue.playCatalogQueue(player, Bundle(), saved("yt:../../etc/passwd"), ticket()) { false }))
        assertEquals(SessionError.ERROR_BAD_VALUE, code(queue.playCatalogQueue(player, Bundle(), null, ticket()) { false }))
        assertEquals(before, entries())
    }

    @Test fun aRequestFromAnEarlierOwnerOrOneAlreadyReplacedIsCancelled() {
        assertEquals(SessionError.INFO_CANCELLED, code(queue.playCatalogQueue(player, Bundle(), saved("yt:fxsong00001"), ticket()) { true }))
        val older = ticket(); ticket()
        assertEquals(SessionError.INFO_CANCELLED, code(queue.playCatalogQueue(player, Bundle(), saved("yt:fxsong00001"), older) { false }))
        assertEquals(0, player.mediaItemCount)
        assertEquals(SessionError.INFO_CANCELLED, code(queue.playProviderStream(player, streamArgs(), providerStreamRequest(streamArgs())!!, ticket()) { true }))
        assertEquals(0, player.mediaItemCount)
    }

    @Test fun aPauseMadeAfterAQueueWasAskedForWins() {
        val asked = ticket()
        order.userIntent(false)
        queue.playCatalogQueue(player, Bundle(), saved("yt:fxsong00001"), asked) { false }
        assertFalse(player.playWhenReady)
        assertEquals(1, player.mediaItemCount)
    }

    @Test fun addingASongPutsItNextOrLastAndNeverTwice() {
        queue.playCatalogQueue(player, Bundle().apply { putBoolean("autoplay", false) }, saved("yt:fxsong00001", "yt:fxsong00002", "yt:fxsong00003"), ticket()) { false }
        val next = Bundle().apply { putBoolean("enqueueNext", true) }; val last = Bundle().apply { putBoolean("enqueueNext", false) }
        queue.playCatalogQueue(player, next, saved("yt:fxsong00004"), ticket(false)) { false }
        assertEquals(listOf("yt:fxsong00001", "yt:fxsong00004", "yt:fxsong00002", "yt:fxsong00003"), ids())
        queue.playCatalogQueue(player, last, saved("yt:fxsong00005"), ticket(false)) { false }
        assertEquals("yt:fxsong00005", ids().last())
        // Already queued: moved to play next rather than added again.
        val entry = entries()[3]
        queue.playCatalogQueue(player, next, saved("yt:fxsong00003"), ticket(false)) { false }
        assertEquals(listOf("yt:fxsong00001", "yt:fxsong00003", "yt:fxsong00004", "yt:fxsong00002", "yt:fxsong00005"), ids())
        assertEquals("the same entry, moved", entry, entries()[1])
        queue.playCatalogQueue(player, last, saved("yt:fxsong00002"), ticket(false)) { false }
        assertEquals(5, player.mediaItemCount)
        assertEquals(1, resets)
    }

    @Test fun replacingTheSelectedEntryKeepsItsIdentityAndIsCancelledWhenTheUserMovedOn() {
        queue.playCatalogQueue(player, Bundle().apply { putBoolean("autoplay", false) }, saved("yt:fxsong00001", "yt:fxsong00002"), ticket()) { false }
        val entry = entries()[0]
        val args = Bundle().apply { putLong("replacePosition", 42_000); putString("expectedEntry", entry) }
        assertEquals(SessionResult.RESULT_SUCCESS, code(queue.playCatalogQueue(player, args, saved("yt:fxvideo0001"), ticket(false)) { false }))
        assertEquals(listOf("yt:fxvideo0001", "yt:fxsong00002"), ids())
        assertEquals(entry, entries()[0]); assertEquals(42_000, player.currentPosition); assertFalse(player.playWhenReady)
        player.seekTo(1, 0)
        assertEquals(SessionError.INFO_CANCELLED, code(queue.playCatalogQueue(player, args, saved("yt:fxsong00003"), ticket(false)) { false }))
        assertEquals(listOf("yt:fxvideo0001", "yt:fxsong00002"), ids())
    }

    // ---- a resolved stream

    @Test fun aResolvedStreamStartsANewQueueAndItsAddressStaysInThisProcess() {
        val request = providerStreamRequest(streamArgs("headers" to Bundle().apply { putString("User-Agent", "fixture-agent") }))!!
        order.userIntent(false); val asked = ticket(); order.userIntent(false)
        assertEquals(SessionResult.RESULT_SUCCESS, code(queue.playProviderStream(player, streamArgs(), request, asked) { false }))
        val item = player.getMediaItemAt(0)
        assertEquals("googlevideo.com", item.localConfiguration!!.uri.host!!.takeLast(15))
        assertEquals(mapOf("User-Agent" to "fixture-agent"), queue.headersFor(item.localConfiguration!!.uri.toString()))
        assertSame(request.stream, queue.streamFor(item))
        assertEquals("one song starts recommendations from it", "fxsong00001", queue.radioSeed)
        // What is saved for the owner names the song, never where its audio came from.
        persistence.save(player)
        val stored = snapshots.load("owner-a")!!
        assertEquals(listOf("yt:fxsong00001"), stored.snapshot.queue.map { it.mediaId })
        assertNull(stored.snapshot.queue[0].localUri)
        assertFalse(encodePlaybackSnapshot(stored.snapshot).contains("googlevideo"))
        assertFalse(encodePlaybackSnapshot(stored.snapshot).contains("fixture-agent"))
        // A new queue: the old addresses can no longer be answered.
        queue.resetForNewQueue()
        assertTrue(queue.headersFor(item.localConfiguration!!.uri.toString()).isEmpty())
        assertNull(queue.streamFor(item)); assertNull(queue.radioSeed)
    }

    @Test fun aResolvedStreamCanReplaceTheSelectedEntryOrJoinTheQueue() {
        queue.playCatalogQueue(player, Bundle().apply { putBoolean("autoplay", false) }, saved("yt:fxsong00002", "yt:fxsong00003"), ticket()) { false }
        val entry = entries()[0]; val request = providerStreamRequest(streamArgs())!!
        queue.playProviderStream(player, streamArgs("replaceCurrent" to true, "position" to 9_000L, "expectedEntry" to entry), request, ticket(false)) { false }
        assertEquals(listOf("yt:fxsong00001", "yt:fxsong00003"), ids()); assertEquals(entry, entries()[0]); assertEquals(9_000, player.currentPosition)
        assertEquals(SessionError.INFO_CANCELLED, code(queue.playProviderStream(player, streamArgs("replaceCurrent" to true, "expectedEntry" to "someone-else"), request, ticket(false)) { false }))
        // The same recording is not queued twice.
        queue.playProviderStream(player, streamArgs("enqueue" to true), request, ticket(false)) { false }
        assertEquals(2, player.mediaItemCount)
        val other = providerStreamRequest(streamArgs("videoId" to "fxsong00009", "title" to "Another", "streamVideoId" to "fxsong00009"))!!
        queue.playProviderStream(player, streamArgs("enqueue" to true, "playNext" to true), other, ticket(false)) { false }
        assertEquals(listOf("yt:fxsong00001", "yt:fxsong00009", "yt:fxsong00003"), ids())
        assertEquals(1, resets)
    }

    // ---- saved queues

    @Test fun aSavedQueueIsLoadedPausedAndLooksItsSongsUpAgain() {
        val stored = StoredSnapshot("owner-a", PlaybackSnapshot(listOf(SavedQueueItem("yt:fxsong00001", "Song", "Artist"),
            SavedQueueItem("local:7", "File", "Artist", localUri = "content://media/external/audio/media/7")), 1, 30_000, shuffle = true, repeat = Player.REPEAT_MODE_ONE, radioSeed = "fxsong00001"), 1)
        player.playWhenReady = true
        queue.replaceWithSaved(player, stored)
        assertFalse("restoring never starts playback", player.playWhenReady)
        assertEquals(listOf("yt:fxsong00001", "local:7"), ids())
        assertEquals("catalog", player.getMediaItemAt(0).localConfiguration!!.uri.scheme)
        assertEquals("content://media/external/audio/media/7", player.getMediaItemAt(1).localConfiguration!!.uri.toString())
        assertEquals(1, player.currentMediaItemIndex); assertEquals(30_000, player.currentPosition)
        assertTrue(player.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
        assertEquals("fxsong00001", queue.radioSeed)
        assertEquals("owner-a", persistence.owner)
        assertEquals(2, entries().filter { it.isNotEmpty() }.toSet().size)
    }

    @Test fun anOnDeviceFileReplacesTheQueueAndEndsRecommendations() {
        queue.playProviderStream(player, streamArgs(), providerStreamRequest(streamArgs())!!, ticket()) { false }
        val file = androidx.media3.common.MediaItem.Builder().setMediaId("local:7").setUri("content://media/external/audio/media/7").build()
        order.userIntent(false)
        assertEquals(SessionResult.RESULT_SUCCESS, code(queue.playLocal(player, file, order.arrive(true).also { order.userIntent(false) })))
        assertEquals(listOf("local:7"), ids()); assertNull(queue.radioSeed); assertFalse(player.playWhenReady)
    }

    // ---- recommendations

    /** The real player with the state a playing song has; nothing is decoded in these tests. */
    private fun playing() = object : ForwardingPlayer(player) {
        override fun getPlaybackState() = Player.STATE_READY
        override fun isPlaying() = true
    }

    @Test fun recommendationsAreAddedOnceAndOnlyThoseMayBePassedOver() {
        order.userIntent(false); val asked = ticket(); order.userIntent(false)
        queue.playProviderStream(player, streamArgs(), providerStreamRequest(streamArgs())!!, asked) { false }
        world.route = { ProviderWorld.Reply(providerFixture("watch-next.json")) }
        val before = queue.generation
        queue.replenish(playing())
        until("recommendations") { player.mediaItemCount == 3 && player.playlistMetadata.extras?.getBoolean("queueLoading") == false }
        assertEquals("the seed is not added again", listOf("yt:fxsong00001", "yt:fxsong00002", "yt:fxsong00003"), ids())
        assertEquals("Music from YouTube Music", player.playlistMetadata.subtitle.toString())
        assertEquals(before, queue.generation)
        val chosen = entries()[0]; val added = entries()[1]
        assertFalse(queue.isAutomatic(chosen)); assertTrue(queue.isAutomatic(added))
        assertFalse("a song the user chose is never passed over", queue.skipUnavailable(chosen))
        player.seekTo(1, 0)
        assertTrue(queue.skipUnavailable(added)); assertEquals(2, player.currentMediaItemIndex)
        assertFalse("nothing after the last entry", queue.skipUnavailable(entries()[2]))
    }

    @Test fun recommendationsForAQueueThatWasReplacedAreDropped() {
        order.userIntent(false); val asked = ticket(); order.userIntent(false)
        queue.playProviderStream(player, streamArgs(), providerStreamRequest(streamArgs())!!, asked) { false }
        world.route = { ProviderWorld.Reply(providerFixture("watch-next.json")) }
        queue.replenish(playing())
        shadowOf(Looper.getMainLooper()).idle()
        queue.playCatalogQueue(player, Bundle().apply { putBoolean("autoplay", false) }, saved("yt:fxsong00007"), ticket()) { false }
        repeat(40) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertEquals(listOf("yt:fxsong00007"), ids())
        assertEquals("Fixture Album", player.playlistMetadata.title.toString())
    }

    @Test fun aFailedLookupSaysSoAndLeavesTheQueueAlone() {
        order.userIntent(false); val asked = ticket(); order.userIntent(false)
        queue.playProviderStream(player, streamArgs(), providerStreamRequest(streamArgs())!!, asked) { false }
        world.route = { ProviderWorld.Reply("{}", status = 500) }
        queue.replenish(playing())
        until("status") { player.playlistMetadata.subtitle?.toString()?.startsWith("Couldn't load recommendations") == true }
        assertEquals(1, player.mediaItemCount)
    }
}
