package com.musicglass.personal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.UUID

/** Feature 12, section 12.3: what Encore is drawn from: listened time per play, and daily totals that follow history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncoreStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val clock = FakeClock()
    private val name = "encore-${UUID.randomUUID()}.db"
    private val db = LibraryStateDb(context, name)
    private val epochs = ScopeEpochs(db).also { it.warm() }
    private var keep = false
    private fun history(retention: Int? = null) = HistoryRepository(db, epochs, clock::wallTimeMillis, keepEncoreTotals = { keep }) { retention }
    @After fun close() { db.close(); context.deleteDatabase(name) }

    private var serial = 0
    private fun play(at: Long = clock.wall, artist: String = "Meira Omar", album: String = "Hush Hush", music: Boolean = true) =
        HistoryEntry("yt:fixtureA001", "Hush Hush", artist, null, at, music, "e-%06d".format(serial++), 180_000L,
            album = album, albumId = "MPREalbum01", artistId = "UCartist01", source = EncoreSource.STREAM)

    private fun total(kind: String, column: String = "listened_ms"): Long = db.readableDatabase.rawQuery(
        "SELECT COALESCE(SUM($column),0) FROM stats_daily WHERE kind=?", arrayOf(kind)).use { it.moveToFirst(); it.getLong(0) }
    private fun hourly(): Long = db.readableDatabase.rawQuery("SELECT COALESCE(SUM(listened_ms),0) FROM stats_hourly", null).use { it.moveToFirst(); it.getLong(0) }
    private fun listened(eventId: String): Long = db.readableDatabase.rawQuery("SELECT listened_ms FROM history_events WHERE event_id=?", arrayOf(eventId)).use { it.moveToFirst(); it.getLong(0) }

    @Test fun aPlayCountsOnceForItsSongAlbumAndArtist() {
        val history = history()
        assertEquals(StoreOutcome.Done, history.record(play(), history.ticket("owner")))
        assertEquals(1L, total(EncoreKind.SONG, "plays"))
        assertEquals(1L, total(EncoreKind.ALBUM, "plays"))
        assertEquals(1L, total(EncoreKind.ARTIST, "plays"))
    }

    @Test fun listenedTimeAddsUpOnThePlayItsDayAndItsHour() {
        val history = history()
        val entry = play(); history.record(entry, history.ticket("owner"))
        assertTrue(history.addListened("owner", entry.eventId, 30_000, clock.wall))
        assertTrue(history.addListened("owner", entry.eventId, 12_000, clock.wall))
        assertEquals(42_000L, listened(entry.eventId))
        assertEquals(42_000L, total(EncoreKind.SONG))
        assertEquals(42_000L, total(EncoreKind.ARTIST))
        assertEquals(42_000L, hourly())
    }

    @Test fun everyCreditedArtistGetsThePlay() {
        val credited = encoreArtists("Mithoon, Arijit Singh & Sachet feat. Parampara", artistId = "UCmithoon")
        assertEquals(listOf("Mithoon", "Arijit Singh", "Sachet", "Parampara"), credited.map { it.second })
        assertEquals("the channel keys the first artist", "id:UCmithoon", credited.first().first)
        assertEquals("name:arijit singh", credited[1].first)
    }

    @Test fun aClearedPlayTakesNoMoreTimeAndClearingClearsEncore() {
        val history = history()
        val entry = play(); history.record(entry, history.ticket("owner"))
        history.addListened("owner", entry.eventId, 10_000, clock.wall)
        history.clear("owner")
        assertEquals(0L, total(EncoreKind.SONG, "plays"))
        assertEquals(0L, hourly())
        assertFalse("the play is gone, so nothing is written for it", history.addListened("owner", entry.eventId, 5_000, clock.wall))
        assertEquals(0L, total(EncoreKind.SONG))
    }

    @Test fun retentionTakesOldDaysUnlessTotalsAreKept() {
        val day = 86_400_000L
        val old = play(at = clock.wall - 40 * day)
        history().record(old, history().ticket("owner"))
        history(retention = 30).prune("owner", clock.wall)
        assertEquals(0L, total(EncoreKind.SONG, "plays"))

        keep = true
        val older = play(at = clock.wall - 40 * day)
        history().record(older, history().ticket("owner"))
        history(retention = 30).prune("owner", clock.wall)
        assertEquals("the play is gone but the day's total stays", 1L, total(EncoreKind.SONG, "plays"))
    }

    @Test fun aPodcastOrAnythingNotMusicIsNotCounted() {
        val history = history()
        val talk = play(music = false); history.record(talk, history.ticket("owner"))
        history.addListened("owner", talk.eventId, 20_000, clock.wall)
        assertEquals(0L, total(EncoreKind.SONG, "plays"))
        assertEquals(0L, hourly())
        assertEquals("the play itself still keeps its time", 20_000L, listened(talk.eventId))
    }

    @Test fun howAPlayEndedIsRecordedOnce() {
        val history = history()
        val entry = play(); history.record(entry, history.ticket("owner"))
        history.markEnded("owner", entry.eventId, EncoreEnded.SKIPPED)
        history.markEnded("owner", entry.eventId, EncoreEnded.FINISHED)
        val ended = db.readableDatabase.rawQuery("SELECT ended FROM history_events WHERE event_id=?", arrayOf(entry.eventId)).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(EncoreEnded.SKIPPED, ended)
    }

    @Test fun aRebuildMatchesTheTotalsKeptAsPlaysHappened() {
        val history = history()
        val a = play(); history.record(a, history.ticket("owner")); history.addListened("owner", a.eventId, 60_000, clock.wall)
        val b = play(artist = "Somebody Else", album = ""); history.record(b, history.ticket("owner"))
        val plays = total(EncoreKind.ARTIST, "plays")
        history.rebuildEncore()
        assertEquals(plays, total(EncoreKind.ARTIST, "plays"))
        assertEquals(1L, total(EncoreKind.ALBUM, "plays"))
    }

    @Test fun daysAreLocalDates() {
        val utc = ZoneId.of("UTC"); val kolkata = ZoneId.of("Asia/Kolkata")
        val lateEvening = 1_761_670_800_000L // 2025-10-28T17:00Z: 22:30 in Kolkata
        assertEquals("2025-10-28", encoreDay(lateEvening, utc))
        assertEquals(22, encoreHour(lateEvening, kolkata))
        assertEquals("2025-10-29", encoreDay(lateEvening + 2 * 3_600_000L, kolkata))
    }

    @Test fun aVersionOneDatabaseIsUpgradedWithItsPlaysKept() {
        val oldName = "encore-v1-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(oldName).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE history_events(scope TEXT NOT NULL,event_id TEXT NOT NULL,media_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,artwork TEXT,played_at INTEGER NOT NULL,duration_ms INTEGER,music INTEGER NOT NULL,search_text TEXT NOT NULL,PRIMARY KEY(scope,event_id))")
            // The rest of version 1, as 0.10.0 made it.
            old.execSQL("CREATE TABLE playback_snapshots(scope TEXT PRIMARY KEY NOT NULL,payload_version INTEGER NOT NULL,payload TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE scope_generations(scope TEXT PRIMARY KEY NOT NULL,history_epoch INTEGER NOT NULL DEFAULT 0,snapshot_epoch INTEGER NOT NULL DEFAULT 0,deletion_epoch INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE migration_runs(migration_id TEXT PRIMARY KEY NOT NULL,source_fingerprint TEXT NOT NULL,state TEXT NOT NULL,imported_count INTEGER NOT NULL,rejected_count INTEGER NOT NULL,completed_at INTEGER)")
            old.execSQL("CREATE TABLE scope_aliases(legacy_scope TEXT PRIMARY KEY NOT NULL,canonical_scope TEXT NOT NULL,verification_version INTEGER NOT NULL,state TEXT NOT NULL)")
            old.execSQL("INSERT INTO history_events VALUES('owner','e1','yt:fixtureA001','Old Song','Old Artist',NULL,1000,NULL,1,'old song')")
            old.version = 1
        }
        val upgraded = LibraryStateDb(context, oldName)
        try {
            upgraded.readableDatabase.rawQuery("SELECT listened_ms, ended FROM history_events WHERE event_id='e1'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)); assertEquals(0, it.getInt(1))
            }
            assertTrue(upgraded.upgradedFrom1)
            HistoryRepository(upgraded, ScopeEpochs(upgraded).also { it.warm() }, clock::wallTimeMillis).rebuildEncore()
            upgraded.readableDatabase.rawQuery("SELECT plays FROM stats_daily WHERE kind='song'", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        } finally { upgraded.close(); context.deleteDatabase(oldName) }
    }

    @Test fun listeningIsWrittenEveryHalfMinuteAndWhenAPlayEnds() {
        val writes = mutableListOf<Long>(); val ends = mutableListOf<Int>()
        val listening = EncoreListening({ _, _, ms -> writes += ms }, { _, _, how -> ends += how })
        listening.heard(5_000)
        assertTrue("nothing before a play is open", writes.isEmpty())
        listening.started("owner", "e1")
        repeat(6) { listening.heard(5_000) }
        assertEquals(listOf(30_000L), writes)
        listening.heard(5_000)
        listening.ended(encoreEndedBy(androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
        assertEquals(listOf(30_000L, 5_000L), writes)
        assertEquals(listOf(EncoreEnded.FINISHED), ends)
        listening.ended(EncoreEnded.STOPPED)
        assertEquals("a play ends once", 1, ends.size)
    }
}
