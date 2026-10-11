package com.musicglass.personal

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/** Moving the shared pre-owner history file and queue; synthetic plays and owners only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyStateMigrationTest {
    private class Died : Error("simulated process death")

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val events = mutableListOf<String>()
    private val opened = mutableListOf<LibraryStateDb>()
    private var dbName = "state-${UUID.randomUUID()}.db"
    private val legacyFile get() = File(context.filesDir, "listening-history/history-v1.jsonl")
    private val unassigned get() = File(context.filesDir, "unassigned")
    private val oldQueue get() = context.getSharedPreferences("last_playback", Context.MODE_PRIVATE)
    private val interim get() = context.getSharedPreferences("playback_snapshots", Context.MODE_PRIVATE)
    @After fun close() { opened.forEach { it.close() } }

    /** Everything a fresh process would build, over the same files. */
    private inner class Process(hook: (String) -> Unit = {}) {
        val db = LibraryStateDb(context, dbName).also { opened += it }
        val epochs = ScopeEpochs(db).also { it.warm() }
        val history = HistoryRepository(db, epochs, clock::wallTimeMillis)
        val snapshots = PlaybackSnapshotRepository(db, epochs, clock::wallTimeMillis, File(unassigned, "rejected.json"), events::add)
        val legacy = LegacyStateMigration(context, db, history, snapshots, clock::wallTimeMillis, events::add, hook)
        fun rows(scope: String? = null) = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM history_events" + if (scope == null) "" else " WHERE scope=?", scope?.let { arrayOf(it) }).use { it.moveToFirst(); it.getInt(0) }
        fun titles(scope: String) = history.page(scope, limit = 1000).entries.map { it.title }
    }

    private fun line(id: String, title: String, at: Long, artist: String = "Artist") = encodeHistoryEntry(HistoryEntry(id, title, artist, null, at))
    private fun writeLegacy(vararg lines: String) { legacyFile.parentFile!!.mkdirs(); legacyFile.writeText(lines.joinToString("") { it + "\n" }) }
    private fun queue(vararg ids: String) = PlaybackSnapshot(ids.map {
        SavedQueueItem(it, "Song", "Artist", localUri = if (it.startsWith("local:")) "content://media/external/audio/media/${it.removePrefix("local:")}" else null)
    }, 0, 30_000)
    private fun writeOldQueue(snapshot: PlaybackSnapshot = queue("yt:fixtureA001", "yt:fixtureA002", "local:7")) {
        // The shape the old build wrote: version 1 and no format name.
        val v1 = encodePlaybackSnapshot(snapshot).replace("\"format\":\"$SNAPSHOT_FORMAT\",", "").replace("\"version\":2", "\"version\":1")
        assertFalse(v1.contains("format")); assertTrue(v1.contains("\"version\":1"))
        oldQueue.edit().putString("snapshot", v1).commit()
    }
    private val mixed = arrayOf(
        line("local:1", "Voice memo", 1_000), line("yt:fixtureA001", "Account A song", 2_000), line("local:2", "Field recording", 3_000),
        line("yt:fixtureB001", "Account B song", 4_000), line("yt:fixtureA001", "Account A song", 5_000))
    private val everyOwner = listOf("owner-a", "owner-b", GUEST_SCOPE_KEY, DEVICE_LOCAL_SCOPE_KEY)

    // ---- sources

    @Test fun nothingFromEarlierVersionsMeansNothingHappens() {
        val p = Process()
        assertEquals(LegacyReport(LegacyResult.NothingToDo, LegacyResult.NothingToDo, LegacyResult.NothingToDo), p.legacy.run())
        assertTrue(p.legacy.inventory().empty); assertEquals(0, p.rows()); assertTrue(events.isEmpty())
    }

    @Test fun emptySourceIsRetiredAndLeavesNothingToDecide() {
        writeLegacy()
        val p = Process()
        assertEquals(LegacyResult.Completed(0, 0), p.legacy.run().history)
        assertFalse(legacyFile.exists()); assertTrue(p.legacy.inventory().empty); assertEquals(0, p.rows())
    }

    @Test fun mixedAccountsAreNeverAssignedAutomatically() {
        writeLegacy(*mixed)
        val p = Process()
        assertEquals(LegacyResult.Completed(2, 0), p.legacy.run().history)
        assertEquals(listOf("Field recording", "Voice memo"), p.titles(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(2, p.rows())
        listOf("owner-a", "owner-b", GUEST_SCOPE_KEY).forEach { assertEquals(0, p.history.count(it)) }
        assertEquals(UnassignedInventory(providerPlays = 3, sources = 1, queueItems = 0), p.legacy.inventory())
        assertFalse("the old file can no longer be appended to", legacyFile.exists())
        assertEquals(1, unassigned.listFiles()!!.count { it.name.matches(Regex("history-v1-[0-9a-f]{16}\\.jsonl")) })
        assertEquals(listOf("legacy_migration part=history imported=2 rejected=0"), events)
    }

    @Test fun corruptOverlongAndTruncatedLinesAreCountedAndTheRestIsKept() {
        legacyFile.parentFile!!.mkdirs()
        legacyFile.writeText(listOf(line("local:1", "Before", 1_000), "{not json", "", line("file:///etc/passwd", "Bad id", 2_000),
            "{\"id\":\"local:2\",\"title\":\"${"x".repeat(20_000)}\",\"artist\":\"A\",\"artwork\":\"\",\"at\":3000,\"music\":true}", line("local:3", "After", 4_000), line("yt:fixtureA001", "Kept unassigned", 5_000))
            .joinToString("\n") + "\n" + line("local:4", "Cut off", 6_000).dropLast(9))
        val p = Process()
        // Not counted: the blank line. Rejected: bad JSON, bad id, the over-long line, the truncated last line.
        assertEquals(LegacyResult.Completed(2, 4), p.legacy.run().history)
        assertEquals(listOf("After", "Before"), p.titles(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(1, p.legacy.inventory().providerPlays)
        assertEquals(4, p.db.migrationRun(p.db.readableDatabase.rawQuery("SELECT migration_id FROM migration_runs", null).use { it.moveToFirst(); it.getString(0) })!!.rejected)
    }

    @Test fun multibyteTextSurvivesAndAFileCutInsideACharacterLosesOnlyThatLine() {
        val title = "Beyoncé – 日本語 🎵"
        val whole = (line("local:1", title, 1_000, "Sigur Rós") + "\n").toByteArray()
        val cut = line("local:2", "終わり", 2_000).toByteArray().let { bytes -> bytes.copyOf(bytes.indexOfFirst { it < 0 } + 1) }
        legacyFile.parentFile!!.mkdirs(); legacyFile.writeBytes(whole + cut)
        val p = Process()
        assertEquals(LegacyResult.Completed(1, 1), p.legacy.run().history)
        val stored = p.history.page(DEVICE_LOCAL_SCOPE_KEY).entries.single()
        assertEquals(title, stored.title); assertEquals("Sigur Rós", stored.artist)
        assertEquals(listOf(title), p.history.page(DEVICE_LOCAL_SCOPE_KEY, query = "beyonce").entries.map { it.title })
    }

    @Test fun theSamePlayListedThreeTimesStaysThreePlays() {
        val same = line("local:1", "Loop", 1_000)
        writeLegacy(same, same, same)
        val p = Process()
        assertEquals(LegacyResult.Completed(3, 0), p.legacy.run().history)
        val stored = p.history.page(DEVICE_LOCAL_SCOPE_KEY).entries
        assertEquals(3, stored.size); assertEquals(3, stored.map { it.eventId }.toSet().size)
        assertTrue(stored.all { it.eventId.matches(Regex("legacy-[0-9a-f]{16}-[0-2]")) })
    }

    // ---- running again

    @Test fun runningAgainAddsNothing() {
        writeLegacy(*mixed); writeOldQueue()
        Process().legacy.run()
        val again = Process()
        val report = again.legacy.run()
        assertEquals(LegacyResult.Completed(0, 0), report.history); assertEquals(LegacyResult.NothingToDo, report.queue)
        assertEquals(2, again.rows()); assertEquals(3, again.legacy.inventory().providerPlays); assertEquals(3, again.legacy.inventory().queueItems)
    }

    @Test fun theSameFileWrittenAgainByAnOlderBuildIsNotCountedTwice() {
        writeLegacy(*mixed); Process().legacy.run()
        writeLegacy(*mixed)
        val p = Process(); p.legacy.run()
        assertFalse(legacyFile.exists()); assertEquals(2, p.rows())
        assertEquals(UnassignedInventory(3, 1, 0), p.legacy.inventory())
    }

    @Test fun aLongerCopyOfTheOldFileAddsOnlyThePlaysNotSeenBefore() {
        writeLegacy(*mixed); Process().legacy.run()
        writeLegacy(*mixed, line("local:1", "Voice memo", 1_000), line("local:9", "New memo", 9_000), line("yt:fixtureC001", "Later song", 9_500))
        val p = Process()
        // The two device plays of the first copy are recognised; the repeat of "Voice memo" is a further play.
        assertEquals(LegacyResult.Completed(2, 0), p.legacy.run().history)
        assertEquals(listOf("New memo", "Field recording", "Voice memo", "Voice memo"), p.titles(DEVICE_LOCAL_SCOPE_KEY))
    }

    // ---- interruption

    private fun reset() {
        opened.forEach { it.close() }; opened.clear()
        dbName = "state-${UUID.randomUUID()}.db"
        unassigned.deleteRecursively(); legacyFile.delete()
        oldQueue.edit().clear().commit(); interim.edit().clear().commit(); events.clear()
        writeLegacy(*mixed); writeOldQueue()
        interim.edit().putString("snapshot_owner-a", encodePlaybackSnapshot(queue("yt:fixtureA009"))).putString("updated_owner-a", "77").commit()
    }

    @Test fun deathAtEveryCheckpointThenARestartEndsInTheSameState() {
        reset()
        val points = mutableListOf<String>()
        Process(points::add).legacy.run()
        val named = points.map { it.split(":")[1] }.toSet()
        assertEquals(MigrationCheckpoint.entries.map { it.name }.toSet(), named)
        assertTrue(points.size >= 20)
        for (fatal in points.indices) {
            reset()
            var seen = 0
            val died = runCatching { Process { if (seen++ == fatal) throw Died() }.legacy.run() }.exceptionOrNull()
            assertTrue("the run stopped at ${points[fatal]}", died is Died)
            val p = Process()
            val report = p.legacy.run()
            val where = "after death at #$fatal ${points[fatal]}"
            assertFalse(where, report.history is LegacyResult.Failed); assertFalse(where, report.queue is LegacyResult.Failed)
            assertEquals(where, listOf("Field recording", "Voice memo"), p.titles(DEVICE_LOCAL_SCOPE_KEY))
            assertEquals(where, 2, p.rows())
            assertEquals(where, UnassignedInventory(3, 1, 3), p.legacy.inventory())
            assertFalse(where, legacyFile.exists()); assertNull(where, oldQueue.getString("snapshot", null))
            assertEquals(where, 1, unassigned.listFiles()!!.count { it.name.startsWith("history-v1-") })
            assertEquals(where, "archived", p.db.readableDatabase.rawQuery("SELECT state FROM migration_runs WHERE migration_id LIKE 'history-v1-local:%'", null)
                .use { it.moveToFirst(); it.getString(0) })
            assertEquals(where, 77L, p.snapshots.load("owner-a")?.updatedAt)
            assertNull(where, p.snapshots.newest(listOf(GUEST_SCOPE_KEY, DEVICE_LOCAL_SCOPE_KEY, "owner-b")))
            // And a third start changes nothing.
            Process().legacy.run().let { assertEquals(where, LegacyResult.Completed(0, 0), it.history) }
            assertEquals(where, 2, p.rows())
        }
    }

    @Test fun deathInsideTheTransactionLeavesNeitherRowsNorMarker() {
        writeLegacy(*mixed)
        assertTrue(runCatching { Process { if (it == "history:Committed:before") throw Died() }.legacy.run() }.exceptionOrNull() is Died)
        val p = Process()
        assertEquals(0, p.rows())
        assertEquals(0, p.db.readableDatabase.rawQuery("SELECT COUNT(*) FROM migration_runs", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(LegacyResult.Completed(2, 0), p.legacy.run().history)
        assertEquals(2, p.rows())
    }

    @Test fun fullDiskFailsWithTheSourceKeptAndSucceedsOnceThereIsRoom() {
        writeLegacy(*mixed)
        var full = true
        val p = Process { if (full && it == "history:TransactionStarted:after") throw SQLiteFullException("fixture: database or disk is full") }
        assertEquals(LegacyResult.Failed("import", "SQLiteFullException"), p.legacy.run().history)
        assertEquals(0, p.rows())
        assertEquals("the plays are still on disk", 1, unassigned.listFiles()!!.count { it.name.startsWith("history-v1-") && it.length() > 0 })
        assertEquals(listOf("legacy_migration part=history failed_stage=import kind=SQLiteFullException"), events)
        full = false
        assertEquals(LegacyResult.Completed(2, 0), p.legacy.run().history)
        assertEquals(2, p.rows()); assertEquals(3, p.legacy.inventory().providerPlays)
    }

    // ---- what the user decides

    @Test fun importGoesOnlyToTheOwnerTheUserConfirmedAndOnlyOnce() {
        writeLegacy(*mixed)
        val p = Process(); p.legacy.run()
        assertEquals(ImportPreview(available = 3, kept = 3, alreadyImported = false), p.legacy.previewImport("owner-a"))
        val revision = p.history.revision.value
        assertEquals(3, p.legacy.importProviderHistory("owner-a"))
        assertTrue(p.history.revision.value > revision)
        assertEquals(listOf("Account A song", "Account B song", "Account A song"), p.titles("owner-a"))
        assertEquals(0, p.history.count("owner-b")); assertEquals(0, p.history.count(GUEST_SCOPE_KEY)); assertEquals(2, p.history.count(DEVICE_LOCAL_SCOPE_KEY))
        assertTrue(p.legacy.previewImport("owner-a").alreadyImported); assertFalse(p.legacy.previewImport("owner-b").alreadyImported)
        assertEquals("a second confirmation adds nothing", 0, p.legacy.importProviderHistory("owner-a"))
        assertEquals(3, p.history.count("owner-a"))
        assertEquals("the unassigned copy stays until deleted", 3, p.legacy.inventory().providerPlays)
        // A restart remembers the import.
        assertTrue(Process().legacy.previewImport("owner-a").alreadyImported)
    }

    @Test fun importIsRefusedForAnUnverifiedAccountAndForTheDevice() {
        writeLegacy(*mixed)
        val p = Process(); p.legacy.run()
        assertNull(p.legacy.importProviderHistory(UNVERIFIED_SCOPE_KEY)); assertNull(p.legacy.importProviderHistory(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(2, p.rows())
    }

    @Test fun deletingUnassignedPlaysKeepsWhatWasImported() {
        writeLegacy(*mixed)
        val p = Process(); p.legacy.run(); p.legacy.importProviderHistory(GUEST_SCOPE_KEY)
        assertTrue(p.legacy.deleteUnassignedHistory())
        assertTrue(p.legacy.inventory().empty)
        assertEquals(3, p.history.count(GUEST_SCOPE_KEY)); assertEquals(2, p.history.count(DEVICE_LOCAL_SCOPE_KEY))
        assertTrue(Process().legacy.run().history == LegacyResult.NothingToDo)
    }

    // ---- the old shared queue

    @Test fun oldQueueIsSetAsideAndNoOwnerGetsIt() {
        writeOldQueue()
        val p = Process()
        assertEquals(LegacyResult.Completed(1, 0), p.legacy.run().queue)
        assertNull(oldQueue.getString("snapshot", null))
        assertNull(p.snapshots.newest(everyOwner))
        assertEquals(UnassignedInventory(queueItems = 3), p.legacy.inventory())
        assertEquals(30_000L, p.legacy.quarantinedQueue()!!.positionMs)
    }

    @Test fun restoringGivesTheOldQueueToTheConfirmedOwnerOnly() {
        writeOldQueue()
        val p = Process(); p.legacy.run()
        assertNull("an unverified account cannot take a queue with provider songs", p.legacy.adoptQuarantinedQueue(UNVERIFIED_SCOPE_KEY))
        assertEquals(3, p.legacy.inventory().queueItems)
        assertEquals("owner-a", p.legacy.adoptQuarantinedQueue("owner-a"))
        assertEquals(listOf("yt:fixtureA001", "yt:fixtureA002", "local:7"), p.snapshots.load("owner-a")!!.snapshot.queue.map { it.mediaId })
        assertNull(p.snapshots.newest(listOf("owner-b", GUEST_SCOPE_KEY, DEVICE_LOCAL_SCOPE_KEY)))
        assertTrue(p.legacy.inventory().empty); assertNull(p.legacy.adoptQuarantinedQueue("owner-a"))
    }

    @Test fun anOldQueueOfOnDeviceAudioBelongsToTheDevice() {
        writeOldQueue(queue("local:1", "local:2"))
        val p = Process(); p.legacy.run()
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, p.legacy.adoptQuarantinedQueue(UNVERIFIED_SCOPE_KEY))
        assertEquals(2, p.snapshots.load(DEVICE_LOCAL_SCOPE_KEY)!!.snapshot.queue.size)
    }

    @Test fun deletingTheOldQueueRemovesIt() {
        writeOldQueue()
        val p = Process(); p.legacy.run()
        assertTrue(p.legacy.deleteQuarantinedQueue())
        assertTrue(p.legacy.inventory().empty); assertNull(p.snapshots.newest(everyOwner))
    }

    @Test fun oversizedOldQueueIsRetiredWithoutBeingParsed() {
        oldQueue.edit().putString("snapshot", "{\"version\":1,\"queue\":[" + " ".repeat(MAX_SNAPSHOT_BYTES + 1)).commit()
        val p = Process()
        assertEquals(LegacyResult.Completed(0, 1), p.legacy.run().queue)
        assertNull(oldQueue.getString("snapshot", null)); assertTrue(p.legacy.inventory().empty)
        assertEquals("rejected_oversize", p.db.migrationRun("snapshot-v1")!!.state)
    }

    @Test fun unreadableOldQueueIsKeptAsideButOffersNothingToRestore() {
        oldQueue.edit().putString("snapshot", "{not json").commit()
        val p = Process(); p.legacy.run()
        assertNull(p.legacy.quarantinedQueue()); assertEquals(0, p.legacy.inventory().queueItems)
        assertEquals("{not json", File(unassigned, "playback-snapshot-v1.json").readText())
    }

    @Test fun perOwnerQueuesFromTheInterimBuildKeepTheirOwners() {
        interim.edit().putString("snapshot_owner-a", encodePlaybackSnapshot(queue("yt:fixtureA001"))).putString("updated_owner-a", "500")
            .putString("snapshot_$GUEST_SCOPE_KEY", encodePlaybackSnapshot(queue("yt:fixtureG001", "yt:fixtureG002"))).putString("updated_$GUEST_SCOPE_KEY", "900")
            .putString("snapshot_owner-b", "{not json").putString("snapshot_$UNVERIFIED_SCOPE_KEY", encodePlaybackSnapshot(queue("yt:fixtureU001")))
            .putString("snapshot_../escape", encodePlaybackSnapshot(queue("yt:fixtureX001"))).commit()
        val p = Process()
        assertEquals(LegacyResult.Completed(2, 3), p.legacy.run().interimQueues)
        assertEquals(500L, p.snapshots.load("owner-a")!!.updatedAt)
        assertEquals(GUEST_SCOPE_KEY, p.snapshots.newest(everyOwner)!!.ownerKey)
        assertNull(p.snapshots.load("owner-b")); assertTrue(interim.all.isEmpty())
        assertEquals(LegacyResult.NothingToDo, Process().legacy.run().interimQueues)
    }

    // ---- threading

    @Test fun theContainerMigratesOnItsBackgroundDispatcherNotOnTheCaller() {
        writeLegacy(*mixed)
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val executor = Executors.newSingleThreadExecutor { Thread(it, "fixture-state-io") }
        val recording = object : AppClocks {
            override fun wallTimeMillis(): Long { threads += Thread.currentThread().name; return 1_800_000_000_000L }
            override fun monotonicMillis() = 0L
        }
        val runtime = RuntimeDependencies(networkConditions = { NetworkConditions(false, false, false) }, storageCapacity = { StorageCapacity(0, 1_000_000) },
            batteryPercent = { 15 }, httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
            clocks = recording, dispatchers = AppDispatchers(executor.asCoroutineDispatcher(), Dispatchers.Unconfined),
            resolver = ScriptedResolver(), storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)
        val container = AppContainer(context, runtime)
        try {
            runBlocking { container.stateReady.await() }
            assertEquals(2, container.history.count(DEVICE_LOCAL_SCOPE_KEY))
            assertTrue(threads.isNotEmpty() && threads.all { it.startsWith("fixture-state-io") })
        } finally { container.libraryState.close(); executor.shutdownNow() }
    }
}
