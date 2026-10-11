package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/** History and saved queues in library-state.db; synthetic owners and plays only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryStateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val db = LibraryStateDb(context, "state-${UUID.randomUUID()}.db")
    private val epochs = ScopeEpochs(db).also { it.warm() }
    private val events = mutableListOf<String>()
    private val rejected = File(context.filesDir, "rejected-${UUID.randomUUID()}.json")
    private fun history(cap: Int = HISTORY_ROW_CAP, retention: Int? = null) = HistoryRepository(db, epochs, clock::wallTimeMillis, cap) { retention }
    private val snapshots = PlaybackSnapshotRepository(db, epochs, clock::wallTimeMillis, rejected, events::add)
    @After fun close() { db.close(); rejected.delete() }

    private var serial = 0
    private fun play(title: String = "Song", artist: String = "Artist", at: Long = clock.wall, id: String = "yt:fixtureA001",
        event: String = "e-%06d".format(serial++)) = HistoryEntry(id, title, artist, null, at, eventId = event)
    private fun queue(vararg ids: String, position: Long = 0, index: Int = 0) = PlaybackSnapshot(ids.map {
        SavedQueueItem(it, "Song", "Artist", localUri = if (it.startsWith("local:")) "content://media/external/audio/media/${it.removePrefix("local:")}" else null)
    }, index, position)

    // ---- history

    @Test fun pagesAreStableWhenManyPlaysShareOneInstant() {
        val history = history()
        repeat(250) { assertEquals(StoreOutcome.Done, history.record(play(at = 5_000), history.ticket("owner-a"))) }
        val seen = mutableListOf<String>(); var cursor: HistoryCursor? = null; var pages = 0
        do {
            val page = history.page("owner-a", cursor)
            assertTrue(page.entries.size <= HISTORY_PAGE_SIZE)
            seen += page.entries.map { it.eventId }; cursor = page.next; pages++
        } while (cursor != null)
        assertEquals(3, pages)
        assertEquals(250, seen.size); assertEquals(250, seen.toSet().size)
        assertEquals(seen.sortedDescending(), seen)
        assertEquals(250, history.count("owner-a"))
    }

    @Test fun newestFirstAcrossDifferentTimes() {
        val history = history()
        listOf(3_000L, 1_000L, 2_000L).forEach { history.record(play(at = it), history.ticket("owner-a")) }
        assertEquals(listOf(3_000L, 2_000L, 1_000L), history.page("owner-a").entries.map { it.playedAt })
    }

    @Test fun eachOwnerSeesOnlyItsOwnPlays() {
        val history = history()
        history.record(play("Account song"), history.ticket("owner-a"))
        history.record(play("Other account song"), history.ticket("owner-b"))
        history.record(play("Guest song"), history.ticket(GUEST_SCOPE_KEY))
        history.record(play("Voice memo", id = "local:42"), history.ticket(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(listOf("Account song"), history.page("owner-a").entries.map { it.title })
        assertEquals(listOf("Voice memo"), history.page(DEVICE_LOCAL_SCOPE_KEY).entries.map { it.title })
        assertEquals(1, history.count(GUEST_SCOPE_KEY))
        assertTrue(history.page("owner-c").entries.isEmpty())
    }

    @Test fun searchTreatsPercentAndUnderscoreAsTypedAndIgnoresAccentsAndCase() {
        val history = history()
        listOf("100% Pure" to "A", "100 Percent" to "B", "a_b" to "C", "axb" to "D", "Halo" to "Beyoncé", "Déjà Vu" to "E", "Back\\Slash" to "F")
            .forEach { (title, artist) -> history.record(play(title, artist), history.ticket("owner-a")) }
        fun found(query: String) = history.page("owner-a", query = query).entries.map { it.title }.toSet()
        assertEquals(setOf("100% Pure"), found("100%"))
        assertEquals(setOf("a_b"), found("a_b"))
        assertEquals(setOf("Halo"), found("beyonce"))
        assertEquals(setOf("Halo"), found("  BEYONCÉ "))
        assertEquals(setOf("Déjà Vu"), found("deja"))
        assertEquals(setOf("Back\\Slash"), found("k\\s"))
        assertEquals(7, found("").size)
        assertTrue(found("%").size == 1 && found("_").size == 1)
        assertTrue(found("'; DROP TABLE history_events; --").isEmpty())
        assertEquals(7, history.count("owner-a"))
    }

    @Test fun retriedWriteOfTheSamePlayIsStoredOnce() {
        val history = history()
        val event = play(event = "same-play")
        assertEquals(StoreOutcome.Done, history.record(event, history.ticket("owner-a")))
        assertEquals(StoreOutcome.Duplicate, history.record(event, history.ticket("owner-a")))
        // The same song played again is another play.
        assertEquals(StoreOutcome.Done, history.record(event.copy(eventId = "second-play"), history.ticket("owner-a")))
        assertEquals(2, history.count("owner-a"))
    }

    @Test fun clearRejectsWritesPreparedBeforeItAndAcceptsFreshOnes() {
        val history = history()
        history.record(play(), history.ticket("owner-a"))
        history.record(play(), history.ticket("owner-b"))
        val prepared = history.ticket("owner-a")
        val revision = history.revision.value
        assertEquals(StoreOutcome.Done, history.clear("owner-a"))
        assertTrue(history.revision.value > revision)
        assertEquals(StoreOutcome.Stale, history.record(play(), prepared))
        assertEquals(0, history.count("owner-a"))
        assertEquals(StoreOutcome.Done, history.record(play(), history.ticket("owner-a")))
        assertEquals(1, history.count("owner-a")); assertEquals(1, history.count("owner-b"))
    }

    @Test fun clearSurvivesARestartOfTheProcess() {
        val prepared = history().ticket("owner-a")
        history().clear("owner-a")
        val reopened = ScopeEpochs(db).also { it.warm() }
        assertEquals(StoreOutcome.Stale, HistoryRepository(db, reopened, clock::wallTimeMillis).record(play(), prepared))
    }

    @Test fun unverifiedOwnerAndMalformedEventsAreRefusedWithAReason() {
        val history = history()
        assertEquals(StoreOutcome.Unavailable, history.record(play(), history.ticket(UNVERIFIED_SCOPE_KEY)))
        assertEquals(StoreOutcome.Failed("invalid"), history.record(play(id = "file:///etc/passwd"), history.ticket("owner-a")))
        assertEquals(StoreOutcome.Failed("invalid"), history.record(play(event = ""), history.ticket("owner-a")))
        assertEquals(0, history.count("owner-a"))
    }

    @Test fun longTextIsBoundedAndNegativeTimeIsClamped() {
        val history = history()
        history.record(play("x".repeat(500), "y".repeat(500), at = -5), history.ticket("owner-a"))
        val stored = history.page("owner-a").entries.single()
        assertEquals(180, stored.title.length); assertEquals(180, stored.artist.length); assertEquals(0L, stored.playedAt)
    }

    @Test fun retentionRemovesOldPlaysPerOwnerAndKeepsFutureDatedOnes() {
        val now = clock.wall; val day = 86_400_000L
        var days: Int? = null // the user shortens retention after these were recorded
        val history = HistoryRepository(db, epochs, clock::wallTimeMillis) { days }
        history.record(play("old", at = now - 31 * day), history.ticket("owner-a"))
        history.record(play("recent", at = now - 29 * day), history.ticket("owner-a"))
        history.record(play("future", at = now + 400 * day), history.ticket("owner-a"))
        history.record(play("other owner old", at = now - 31 * day), history.ticket("owner-b"))
        days = 30
        assertEquals(1, history.prune("owner-a", now))
        assertEquals(setOf("recent", "future"), history.page("owner-a").entries.map { it.title }.toSet())
        assertEquals(1, history.count("owner-b"))
    }

    @Test fun keepUntilClearedRemovesNothingBelowTheCeiling() {
        val history = history()
        history.record(play(at = 1), history.ticket("owner-a"))
        assertEquals(0, history.prune("owner-a", clock.wall))
        assertEquals(1, history.count("owner-a"))
    }

    @Test fun ceilingKeepsTheNewestPlays() {
        val history = history(cap = 5)
        repeat(8) { history.record(play(at = 1_000L + it), history.ticket("owner-a")) }
        history.prune("owner-a", clock.wall)
        assertEquals((1_003L..1_007L).toList().reversed(), history.page("owner-a").entries.map { it.playedAt })
    }

    // ---- saved queues

    @Test fun eachOwnerGetsItsOwnQueueBack() {
        snapshots.save(snapshots.ticket("owner-a"), queue("yt:fixtureA001", position = 41_000))
        snapshots.save(snapshots.ticket("owner-b"), queue("yt:fixtureB001", "yt:fixtureB002"))
        assertEquals(listOf("yt:fixtureA001"), snapshots.load("owner-a")?.snapshot?.queue?.map { it.mediaId })
        assertEquals(41_000L, snapshots.load("owner-a")?.snapshot?.positionMs)
        assertEquals(2, snapshots.load("owner-b")?.snapshot?.queue?.size)
        assertNull(snapshots.load("owner-c"))
    }

    @Test fun clearingOneOwnerLeavesTheOthers() {
        snapshots.save(snapshots.ticket("owner-a"), queue("yt:fixtureA001"))
        snapshots.save(snapshots.ticket(GUEST_SCOPE_KEY), queue("yt:fixtureG001"))
        snapshots.clear(GUEST_SCOPE_KEY)
        assertNull(snapshots.load(GUEST_SCOPE_KEY)); assertNotNull(snapshots.load("owner-a"))
    }

    @Test fun newestPicksTheMostRecentlySavedAmongTheOwnersAsked() {
        snapshots.save(snapshots.ticket("owner-a"), queue("yt:fixtureA001"))
        clock.wall += 1_000
        snapshots.save(snapshots.ticket(DEVICE_LOCAL_SCOPE_KEY), queue("local:1"))
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, snapshots.newest(listOf("owner-a", DEVICE_LOCAL_SCOPE_KEY))?.ownerKey)
        clock.wall += 1_000
        snapshots.save(snapshots.ticket("owner-b"), queue("yt:fixtureB001"))
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, snapshots.newest(listOf("owner-a", DEVICE_LOCAL_SCOPE_KEY))?.ownerKey)
        assertNull(snapshots.newest(listOf("owner-c")))
    }

    @Test fun deletedOwnersQueueCannotBeRewrittenByAnEarlierSave() {
        val prepared = snapshots.ticket("owner-a")
        snapshots.save(prepared, queue("yt:fixtureA001"))
        assertEquals(StoreOutcome.Done, snapshots.delete("owner-a"))
        assertEquals(StoreOutcome.Stale, snapshots.save(prepared, queue("yt:fixtureA001")))
        assertNull(snapshots.load("owner-a"))
        assertEquals(StoreOutcome.Done, snapshots.save(snapshots.ticket("owner-a"), queue("yt:fixtureA002")))
    }

    @Test fun routineClearDoesNotBlockTheOwnersNextQueue() {
        val ticket = snapshots.ticket("owner-a")
        snapshots.save(ticket, queue("yt:fixtureA001"))
        snapshots.clear("owner-a")
        assertEquals(StoreOutcome.Done, snapshots.save(ticket, queue("yt:fixtureA002")))
    }

    @Test fun unverifiedOwnerHasNoSavedQueue() {
        assertEquals(StoreOutcome.Unavailable, snapshots.save(snapshots.ticket(UNVERIFIED_SCOPE_KEY), queue("yt:fixtureA001")))
    }

    private fun raw(scope: String, version: Int, payload: String) = db.write { d ->
        d.execSQL("INSERT OR REPLACE INTO playback_snapshots(scope,payload_version,payload,updated_at) VALUES(?,?,?,?)", arrayOf<Any>(scope, version, payload, 1L))
    }
    private fun stored(scope: String) = db.readableDatabase.rawQuery("SELECT payload_version,payload FROM playback_snapshots WHERE scope=?", arrayOf(scope))
        .use { c -> if (c.moveToFirst()) c.getInt(0) to c.getString(1) else null }

    @Test fun damagedQueueIsKeptOnceAsideAndNotParsedAgain() {
        raw("owner-a", 2, "{not json")
        assertNull(snapshots.load("owner-a"))
        assertEquals("{not json", rejected.readText())
        assertNull(stored("owner-a"))
        assertEquals(listOf("snapshot_rejected reason=json"), events)
        assertNull(snapshots.load("owner-a"))
        assertEquals(1, events.size)
    }

    @Test fun queueFromANewerBuildIsLeftAloneOnReadAndKeptAsideBeforeBeingReplaced() {
        val newer = """{"format":"riff.playback-snapshot","version":3,"queue":[]}"""
        raw("owner-a", 3, newer)
        assertNull(snapshots.load("owner-a"))
        assertEquals(3 to newer, stored("owner-a"))
        assertFalse(rejected.exists())
        assertEquals(StoreOutcome.Done, snapshots.save(snapshots.ticket("owner-a"), queue("yt:fixtureA001")))
        assertEquals(newer, rejected.readText())
        assertEquals(2, stored("owner-a")?.first)
        assertTrue("snapshot_rejected reason=newer" in events)
    }

    @Test fun versionOnePayloadIsReadAndRewrittenAsNamedVersionTwo() {
        val v1 = """{"version":1,"index":1,"position":7000,"shuffle":true,"repeat":2,"queue":[{"id":"yt:fixtureA001","title":"One","artist":"A"},{"id":"yt:fixtureA002","title":"Two","artist":"B"}]}"""
        raw("owner-a", 1, v1)
        val loaded = snapshots.load("owner-a")!!.snapshot
        assertEquals(listOf("One", "Two"), loaded.queue.map { it.title })
        assertEquals(1, loaded.index); assertEquals(7_000L, loaded.positionMs); assertTrue(loaded.shuffle); assertEquals(2, loaded.repeat)
        snapshots.save(snapshots.ticket("owner-a"), loaded)
        val (version, payload) = stored("owner-a")!!
        assertEquals(2, version)
        assertTrue(payload.contains("\"format\":\"riff.playback-snapshot\"") && payload.contains("\"version\":2"))
        assertEquals(loaded, snapshots.load("owner-a")!!.snapshot)
    }

    @Test fun twoThousandItemQueueWithRepeatsRoundTrips() {
        val items = (0 until 2000).map { SavedQueueItem("yt:fixture%04d".format(it % 700), "Track $it", "Artist", album = "Album") }
        val saved = PlaybackSnapshot(items, 1_999, 12_345, repeat = 1)
        assertEquals(StoreOutcome.Done, snapshots.save(snapshots.ticket("owner-a"), saved))
        assertEquals(saved, snapshots.load("owner-a")!!.snapshot)
    }

    @Test fun queueOverTheByteCeilingIsRefusedNotTruncated() {
        // Every text field is within its own bound; the artwork addresses take the whole over the ceiling.
        val items = (0 until 2000).map { SavedQueueItem("yt:fixture%04d".format(it), "Song", "Artist", "https://fixture.invalid/" + "日".repeat(1_200)) }
        val huge = PlaybackSnapshot(items, 0, 0)
        assertTrue(encodePlaybackSnapshot(huge).length < MAX_SNAPSHOT_BYTES && utf8Size(encodePlaybackSnapshot(huge)) > MAX_SNAPSHOT_BYTES)
        assertNull(encodePlaybackSnapshotBounded(huge))
        assertEquals(StoreOutcome.Failed("too_large"), snapshots.save(snapshots.ticket("owner-a"), huge))
        assertNull(snapshots.load("owner-a"))
    }

    @Test fun aliasJournalRefusesAKeyRecordedForAnotherAccount() {
        val journal = ScopeAliasJournal(db)
        assertTrue(journal.begin("legacy-key", "owner-a"))
        assertEquals("owner-a" to "pending", journal.entry("legacy-key"))
        assertFalse(journal.begin("legacy-key", "owner-b"))
        journal.finish("legacy-key", "owner-b", merged = true) // must not touch A's entry
        assertEquals("owner-a" to "pending", journal.entry("legacy-key"))
        assertTrue(journal.begin("legacy-key", "owner-a")) // repeating after an interruption is fine
        journal.finish("legacy-key", "owner-a", merged = true)
        assertEquals("owner-a" to "merged", journal.entry("legacy-key"))
    }
}
