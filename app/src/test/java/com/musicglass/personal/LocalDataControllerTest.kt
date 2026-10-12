package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** Clearing and deleting what is kept on the phone, one owner at a time; synthetic owners and files only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalDataControllerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val events = mutableListOf<String>()
    private val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db")
    private val epochs = ScopeEpochs(db).also { it.warm() }
    private val history = HistoryRepository(db, epochs, clock::wallTimeMillis) { historyRetentionDays(context) }
    private val snapshots = PlaybackSnapshotRepository(db, epochs, clock::wallTimeMillis, File(context.filesDir, "rejected.json")) {}
    private val store = OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
    private val searches = SearchHistory(context, Dispatchers.Unconfined)
    private var searchesBroken = false
    private val data = LocalDataController(context, history, snapshots, { store },
        { if (searchesBroken) throw IllegalStateException("fixture") else searches }, Dispatchers.Unconfined, clock::wallTimeMillis, events::add)
    private val collections get() = context.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    @After fun close() { db.close() }

    private val a = "owner-a"; private val b = "owner-b"
    private var serial = 0
    private fun play(owner: String, at: Long = clock.wall, id: String = if (owner == DEVICE_LOCAL_SCOPE_KEY) "local:7" else "yt:fixtureA001") =
        assertEquals(StoreOutcome.Done, history.record(HistoryEntry(id, "Song", "Artist", null, at, eventId = "e-${serial++}"), history.ticket(owner)))
    private fun track(id: String) = ProviderTrack(id, "Song", "Artist")
    private fun download(owner: String, id: String) {
        store.enqueue(track(id), scope = owner)
        val dir = store.directory(owner, id).apply { mkdirs() }
        File(dir, "audio.media").writeBytes(byteArrayOf(1, 2, 3)); File(dir, "ready").writeText("3"); File(dir, "artwork").writeText("art")
        store.update(owner, id, "state" to "complete", "bytes" to 3L, "plays" to 4, "lastPlayed" to 900L)
    }
    private fun saved(owner: String, list: String, vararg ids: String) = collections.edit().putString("account_${owner}_$list",
        JSONArray(ids.map { JSONObject().put("id", it).put("title", "Song").put("artist", "Artist") }).toString()).commit()
    private fun savedCount(owner: String, list: String) = JSONArray(collections.getString("account_${owner}_$list", "[]")).length()
    private fun search(owner: String, vararg queries: String) = runBlocking { searches.bind(owner); queries.forEach(searches::record) }
    private fun queue(owner: String) = assertEquals(StoreOutcome.Done,
        snapshots.save(snapshots.ticket(owner), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Song", "Artist")), 0, 0)))
    private fun seed(owner: String) {
        repeat(3) { play(owner) }; search(owner, "first $owner", "second $owner"); queue(owner)
        saved(owner, "online_favorites", "fixtureF001", "fixtureF002"); saved(owner, "online_playlist", "fixtureP001"); saved(owner, "online_history", "fixtureH001")
        download(owner, "fixtureD001"); download(owner, "fixtureD002")
    }

    // ---- what is kept

    @Test fun summaryCountsOnlyTheOwnerAskedAboutAndTheDevice() = runBlocking {
        seed(a); play(b); repeat(2) { play(DEVICE_LOCAL_SCOPE_KEY) }
        assertEquals(LocalDataSummary(accountPlays = 3, devicePlays = 2, searches = 2, downloads = 2, savedTracks = 3, hasQueue = true), data.summary(a))
        assertEquals(LocalDataSummary(accountPlays = 1, devicePlays = 2), data.summary(b))
        assertEquals(LocalDataSummary(devicePlays = 2), data.summary(UNVERIFIED_SCOPE_KEY))
    }

    // ---- history

    @Test fun clearingAnAccountsHistoryLeavesTheDevicesAndEveryOtherOwners() = runBlocking {
        seed(a); play(b); play(GUEST_SCOPE_KEY); play(DEVICE_LOCAL_SCOPE_KEY)
        assertEquals(DataActionResult.Done(3), data.clearHistory(a))
        assertEquals(0, history.count(a)); assertEquals(1, history.count(b)); assertEquals(1, history.count(GUEST_SCOPE_KEY)); assertEquals(1, history.count(DEVICE_LOCAL_SCOPE_KEY))
        // The same record kept elsewhere goes with it; what the user saved or downloaded does not.
        assertEquals(0, savedCount(a, "online_history")); assertEquals(2, savedCount(a, "online_favorites")); assertEquals(1, savedCount(a, "online_playlist"))
        store.rows(a).forEach { assertEquals(0, it.plays); assertEquals(0L, it.lastPlayed); assertEquals("complete", it.state) }
        assertNotNull(store.audio(a, "fixtureD001"))
        assertEquals(2, searches.count(a)); assertNotNull(snapshots.load(a))
        assertEquals(listOf("local_data action=clear_history result=done count=3"), events)
    }

    @Test fun clearingTheDevicesHistoryLeavesEveryAccounts() = runBlocking {
        seed(a); repeat(2) { play(DEVICE_LOCAL_SCOPE_KEY) }
        assertEquals(DataActionResult.Done(2), data.clearHistory(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(0, history.count(DEVICE_LOCAL_SCOPE_KEY)); assertEquals(3, history.count(a))
        assertEquals(1, savedCount(a, "online_history")); assertEquals(4, store.rows(a).first().plays)
    }

    @Test fun aPlayQueuedBeforeAClearIsRefusedAndAFreshOneIsKept() = runBlocking {
        play(a)
        val queued = history.ticket(a)
        assertEquals(DataActionResult.Done(1), data.clearHistory(a))
        assertEquals(StoreOutcome.Stale, history.record(HistoryEntry("yt:fixtureA001", "Late", "Artist", null, clock.wall, eventId = "late"), queued))
        assertEquals(0, history.count(a))
        play(a)
        assertEquals(1, history.count(a))
    }

    @Test fun clearingWithNothingStoredSucceedsWithZero() = runBlocking {
        assertEquals(DataActionResult.Done(0), data.clearHistory(a))
        assertEquals(DataActionResult.Failed("unverified"), data.clearHistory(UNVERIFIED_SCOPE_KEY))
    }

    @Test fun turningHistoryOffStopsNewWritesAtOnceAndKeepsWhatIsThere() {
        play(a)
        val settings = LocalCollections(context, a)
        assertTrue(historyEnabled(context))
        settings.updateHistoryPreference(false)
        assertFalse("the playback service reads this before every write", historyEnabled(context))
        assertEquals(1, history.count(a))
        settings.updateHistoryPreference(true)
        assertTrue(historyEnabled(context))
    }

    // ---- searches

    @Test fun clearingSearchesRemovesOnlyThatOwners() = runBlocking {
        search(b, "kept"); search(a, "one", "two")
        assertEquals(DataActionResult.Done(2), data.clearSearches(a))
        assertEquals(0, searches.count(a)); assertEquals(1, searches.count(b))
        assertTrue("the list on screen empties too", searches.entries.isEmpty())
        searches.record("after"); assertEquals(1, searches.count(a))
        assertEquals(DataActionResult.Failed("unverified"), data.clearSearches(UNVERIFIED_SCOPE_KEY))
        assertEquals(DataActionResult.Failed("unverified"), data.clearSearches(DEVICE_LOCAL_SCOPE_KEY))
    }

    // ---- downloads

    @Test fun deletingDownloadsRemovesTheFilesOfThatOwnerOnlyAndKeepsListeningTotals() = runBlocking {
        seed(a); download(b, "fixtureD001")
        assertEquals(DataActionResult.Done(2), data.deleteDownloads(a))
        assertNull(store.audio(a, "fixtureD001")); assertFalse(store.directory(a, "fixtureD002").exists())
        assertEquals(0, store.downloadCount(a))
        assertEquals("what was played is not a download", 4, store.song(a, "fixtureD001")!!.plays)
        assertNotNull(store.audio(b, "fixtureD001"))
        assertEquals(3, history.count(a)); assertEquals(2, searches.count(a))
    }

    // ---- everything for one account

    @Test fun deletingAnAccountsLocalDataRemovesAllOfItAndNothingElse() = runBlocking {
        seed(a); seed(b); play(DEVICE_LOCAL_SCOPE_KEY); queue(DEVICE_LOCAL_SCOPE_KEY)
        collections.edit().putBoolean("history_enabled", false).putInt("history_retention_days", 90).commit()
        val order = mutableListOf<String>()
        val preparedQueueSave = snapshots.ticket(a)
        val result = data.deleteAccountData(a) { order += "quiesce plays=${history.count(a)}" }
        // 3 plays, 2 searches, 4 saved tracks, 2 download rows.
        assertEquals(DataActionResult.Done(11), result)
        assertEquals("playback is stopped before anything is removed", listOf("quiesce plays=3"), order)
        assertEquals(LocalDataSummary(devicePlays = 1), data.summary(a))
        assertTrue(store.rows(a).isEmpty()); assertFalse(store.directory(a, "fixtureD001").parentFile!!.exists())
        listOf("online_favorites", "online_playlist", "online_history").forEach { assertFalse(collections.contains("account_${a}_$it")) }
        // The other account, the device and device settings are as they were.
        assertEquals(LocalDataSummary(accountPlays = 3, devicePlays = 1, searches = 2, downloads = 2, savedTracks = 3, hasQueue = true), data.summary(b))
        assertNotNull(store.audio(b, "fixtureD002")); assertNotNull(snapshots.load(DEVICE_LOCAL_SCOPE_KEY))
        assertFalse(collections.getBoolean("history_enabled", true)); assertEquals(90, collections.getInt("history_retention_days", 0))
        // Work queued before the deletion cannot bring any of it back.
        assertEquals(StoreOutcome.Stale, snapshots.save(preparedQueueSave, PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Song", "Artist")), 0, 0)))
        assertNull(snapshots.load(a))
        assertNull(store.beginDownload(a, "fixtureD001")); assertFalse(store.updateDownload(a, "fixtureD001", 0, "state" to "complete"))
        store.update(a, "fixtureD001", "artwork" to "available")
        assertTrue(store.rows(a).isEmpty())
        // A transfer that had the folder open finds its song gone and removes what it wrote.
        File(store.directory(a, "fixtureD001").apply { mkdirs() }, "audio.part").writeText("late")
        store.discardUnlisted(a, "fixtureD001")
        assertFalse(store.directory(a, "fixtureD001").parentFile!!.exists())
        // A new queue made afterwards is saved normally.
        queue(a)
    }

    @Test fun discardingNeverTouchesASongThatIsStillListed() {
        download(a, "fixtureD001")
        store.discardUnlisted(a, "fixtureD001"); store.discardUnlisted(a, "../../escape")
        assertNotNull(store.audio(a, "fixtureD001"))
    }

    @Test fun unverifiedAndDeviceAreRefusedBeforeAnythingIsStopped() = runBlocking {
        seed(a); play(DEVICE_LOCAL_SCOPE_KEY)
        var stopped = false
        assertEquals(DataActionResult.Failed("unverified"), data.deleteAccountData(UNVERIFIED_SCOPE_KEY) { stopped = true })
        assertEquals(DataActionResult.Failed("unverified"), data.deleteAccountData(DEVICE_LOCAL_SCOPE_KEY) { stopped = true })
        assertEquals(DataActionResult.Failed("unverified"), data.deleteDownloads(UNVERIFIED_SCOPE_KEY))
        assertFalse(stopped); assertEquals(3, history.count(a)); assertEquals(1, history.count(DEVICE_LOCAL_SCOPE_KEY))
    }

    @Test fun aPartThatCannotBeRemovedIsNamedAndARetryFinishesIt() = runBlocking {
        seed(a)
        searchesBroken = true
        assertEquals(DataActionResult.Partial(9, listOf("search history")), data.deleteAccountData(a) {})
        assertEquals("local_data action=delete_account_data result=partial count=9 remaining=1", events.last())
        assertEquals(0, history.count(a)); assertTrue(store.rows(a).isEmpty())
        assertEquals(2, searches.count(a))
        searchesBroken = false
        assertEquals(DataActionResult.Done(2), data.deleteAccountData(a) {})
        assertEquals(LocalDataSummary(), data.summary(a))
    }

    @Test fun aFailureIsReportedAsOneAndChangesNothing() = runBlocking {
        search(a, "kept")
        searchesBroken = true
        assertEquals(DataActionResult.Failed("IllegalStateException"), data.clearSearches(a))
        assertEquals("local_data action=clear_searches result=failed kind=IllegalStateException", events.last())
        searchesBroken = false
        assertEquals(1, searches.count(a))
    }

    // ---- retention

    private val day = 86_400_000L

    @Test fun shorterRetentionSaysHowManyPlaysItRemovesThenRemovesThemForEveryOwner() = runBlocking {
        val now = clock.wall
        play(a, now - 100 * day); play(a, now - 40 * day); play(a, now - 5 * day); play(a, now + 400 * day)
        play(b, now - 95 * day); play(DEVICE_LOCAL_SCOPE_KEY, now - 31 * day)
        assertEquals(4, data.retentionPreview(30)); assertEquals(2, data.retentionPreview(90)); assertEquals(0, data.retentionPreview(365))
        assertEquals(6, listOf(a, b, DEVICE_LOCAL_SCOPE_KEY).sumOf(history::count))
        assertEquals(DataActionResult.Done(2), data.setRetention(90))
        assertEquals(90, historyRetentionDays(context))
        assertEquals(3, history.count(a)); assertEquals(0, history.count(b)); assertEquals(1, history.count(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(DataActionResult.Done(2), data.setRetention(30))
        assertEquals("a play dated in the future is not removed", 2, history.count(a))
        assertEquals(DataActionResult.Done(0), data.setRetention(null))
        assertNull(historyRetentionDays(context))
    }

    @Test fun keepUntilClearedIsTheDefaultAndRemovesNothing() = runBlocking {
        assertNull(historyRetentionDays(context))
        play(a, clock.wall - 4000 * day)
        assertEquals(0, data.pruneIfDue())
        assertEquals(1, history.count(a))
    }

    @Test fun retentionIsAppliedAtMostOnceADayWhenTheAppRuns() = runBlocking {
        setHistoryRetentionDays(context, 30)
        assertEquals(0, data.pruneIfDue())
        // Recorded under the default and aged past the period while the app was closed.
        db.write { d -> insertHistoryEvent(d, a, HistoryEntry("yt:fixtureA001", "Old", "Artist", null, clock.wall - 29 * day, eventId = "aged")) }
        clock.advance(12 * 3_600_000L)
        assertEquals("not yet a day since the last pass", 0, data.pruneIfDue())
        clock.advance(2 * day)
        assertEquals(1, data.pruneIfDue())
        assertEquals(0, history.count(a))
        assertEquals("history_retention removed=1", events.last())
        assertEquals(0, data.pruneIfDue())
    }

    @Test fun aClockSetBackwardsDoesNotPostponeRetention() = runBlocking {
        setHistoryRetentionDays(context, 30)
        data.pruneIfDue()
        clock.wall -= 10 * day
        db.write { d -> insertHistoryEvent(d, a, HistoryEntry("yt:fixtureA001", "Old", "Artist", null, clock.wall - 31 * day, eventId = "aged")) }
        assertEquals(1, data.pruneIfDue())
    }
}
