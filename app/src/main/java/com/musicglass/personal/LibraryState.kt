// Lint does not follow query(...).use { } and reports each cursor as unclosed. Every cursor in this file is closed by use.
@file:SuppressLint("Recycle")

package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal const val HISTORY_PAGE_SIZE = 100
/** Safety ceiling per owner. Older plays beyond it are removed when new ones are recorded. */
internal const val HISTORY_ROW_CAP = 100_000

/**
 * Detailed listening history, saved queues, owner epochs, migration records and the alias journal
 * (ADR-Q03). Downloads and saved tracks keep their own stores. Call off the main thread.
 */
internal class LibraryStateDb(context: Context, name: String? = "library-state.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history_events(scope TEXT NOT NULL,event_id TEXT NOT NULL,media_id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,artwork TEXT,played_at INTEGER NOT NULL,duration_ms INTEGER,music INTEGER NOT NULL,search_text TEXT NOT NULL,PRIMARY KEY(scope,event_id))")
        db.execSQL("CREATE INDEX history_recent ON history_events(scope,played_at DESC,event_id)")
        db.execSQL("CREATE TABLE playback_snapshots(scope TEXT PRIMARY KEY NOT NULL,payload_version INTEGER NOT NULL,payload TEXT NOT NULL,updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE scope_generations(scope TEXT PRIMARY KEY NOT NULL,history_epoch INTEGER NOT NULL DEFAULT 0,snapshot_epoch INTEGER NOT NULL DEFAULT 0,deletion_epoch INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE migration_runs(migration_id TEXT PRIMARY KEY NOT NULL,source_fingerprint TEXT NOT NULL,state TEXT NOT NULL,imported_count INTEGER NOT NULL,rejected_count INTEGER NOT NULL,completed_at INTEGER)")
        db.execSQL("CREATE TABLE scope_aliases(legacy_scope TEXT PRIMARY KEY NOT NULL,canonical_scope TEXT NOT NULL,verification_version INTEGER NOT NULL,state TEXT NOT NULL)")
    }

    // Version 1 is the first. The inherited onDowngrade throws, so a newer schema is never rewritten by an older build.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** One transaction. An exception or a rollback request leaves the database as it was. */
    fun <T> write(block: (SQLiteDatabase) -> T): T = writableDatabase.transaction { block(this) }

    fun migrationRun(id: String): MigrationRun? =
        readableDatabase.query("migration_runs", null, "migration_id=?", arrayOf(id), null, null, null).use { c ->
            if (!c.moveToFirst()) null else MigrationRun(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
                if (c.isNull(5)) null else c.getLong(5))
        }

    fun putMigrationRun(db: SQLiteDatabase, run: MigrationRun) {
        db.insertWithOnConflict("migration_runs", null, ContentValues().apply {
            put("migration_id", run.id); put("source_fingerprint", run.fingerprint); put("state", run.state)
            put("imported_count", run.imported); put("rejected_count", run.rejected)
            if (run.completedAt == null) putNull("completed_at") else put("completed_at", run.completedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

internal data class MigrationRun(val id: String, val fingerprint: String, val state: String, val imported: Int,
    val rejected: Int, val completedAt: Long?)

/** What a write did. Nothing here is reported as success unless it was stored. */
internal sealed interface StoreOutcome {
    data object Done : StoreOutcome
    /** Already stored, for example a retry of the same play. */
    data object Duplicate : StoreOutcome
    /** The owner's data was cleared after this write was prepared. */
    data object Stale : StoreOutcome
    /** No verified owner to store under. */
    data object Unavailable : StoreOutcome
    /** [kind] is an error class name or a fixed word, never content. */
    data class Failed(val kind: String) : StoreOutcome
}

internal enum class EpochKind(val column: String) { History("history_epoch"), Snapshot("snapshot_epoch"), Deletion("deletion_epoch") }

/** Captured when a write is prepared. A null epoch means the cache was not loaded yet, and is accepted. */
internal data class ScopeTicket(val scope: String, val epoch: Long?)

/**
 * A clear advances its owner's epoch in the same transaction, so a write prepared before the clear
 * is rejected instead of bringing the data back. Tickets come from memory and are safe on any thread.
 */
internal class ScopeEpochs(private val db: LibraryStateDb) {
    private val cache = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    fun warm() {
        db.readableDatabase.query("scope_generations", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) EpochKind.entries.forEach { cache["${it.name}:${c.getString(0)}"] = c.getLong(c.getColumnIndexOrThrow(it.column)) }
        }
        loaded = true
    }

    fun ticket(kind: EpochKind, scope: String) = ScopeTicket(scope, if (loaded) cache["${kind.name}:$scope"] ?: 0L else null)

    fun current(db: SQLiteDatabase, kind: EpochKind, scope: String): Long =
        db.query("scope_generations", arrayOf(kind.column), "scope=?", arrayOf(scope), null, null, null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** Inside the caller's transaction. Call [committed] with the result once it has committed. */
    fun advance(db: SQLiteDatabase, kind: EpochKind, scope: String): Long {
        db.execSQL("INSERT OR IGNORE INTO scope_generations(scope) VALUES(?)", arrayOf(scope))
        db.execSQL("UPDATE scope_generations SET ${kind.column}=${kind.column}+1 WHERE scope=?", arrayOf(scope))
        return current(db, kind, scope)
    }

    fun committed(kind: EpochKind, scope: String, value: Long) { cache["${kind.name}:$scope"] = value }
}

internal data class HistoryCursor(val playedAt: Long, val eventId: String)
internal data class HistoryPage(val entries: List<HistoryEntry>, val next: HistoryCursor?)

/** Lower case with accents removed, so "beyonce" finds "Beyoncé". */
internal fun normalizeForSearch(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

/** Makes % and _ in what the user typed match themselves. Use with ESCAPE '\'. */
internal fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private val HISTORY_MEDIA_ID = Regex("yt:[A-Za-z0-9_-]{11}|local:[0-9]{1,19}")
private val HISTORY_EVENT_ID = Regex("[A-Za-z0-9-]{1,80}")

/** Shared by live recording and the legacy import. False when the event is already stored. */
internal fun insertHistoryEvent(db: SQLiteDatabase, scope: String, entry: HistoryEntry): Boolean =
    db.insertWithOnConflict("history_events", null, ContentValues().apply {
        val title = entry.title.take(180); val artist = entry.artist.take(180)
        put("scope", scope); put("event_id", entry.eventId); put("media_id", entry.mediaId)
        put("title", title); put("artist", artist)
        entry.artworkUrl?.takeIf { it.length <= 2048 }?.let { put("artwork", it) } ?: putNull("artwork")
        put("played_at", entry.playedAt.coerceAtLeast(0))
        entry.durationMs?.takeIf { it >= 0 }?.let { put("duration_ms", it) } ?: putNull("duration_ms")
        put("music", if (entry.music) 1 else 0); put("search_text", normalizeForSearch(title) + "\n" + normalizeForSearch(artist))
    }, SQLiteDatabase.CONFLICT_IGNORE) != -1L

internal fun validHistoryEvent(entry: HistoryEntry) = HISTORY_MEDIA_ID.matches(entry.mediaId) && HISTORY_EVENT_ID.matches(entry.eventId)

/** Every qualified play, one row per logical play, kept per owner. */
internal class HistoryRepository(private val db: LibraryStateDb, private val epochs: ScopeEpochs,
    private val wallTime: () -> Long, private val rowCap: Int = HISTORY_ROW_CAP, private val retentionDays: () -> Int? = { null }) {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision
    private val lastPrune = ConcurrentHashMap<String, Long>()

    fun ticket(scope: String) = epochs.ticket(EpochKind.History, scope)

    /** For writers that add rows in their own transaction, such as the legacy import. */
    fun changed() { _revision.value++ }

    fun record(entry: HistoryEntry, ticket: ScopeTicket): StoreOutcome {
        if (ticket.scope == UNVERIFIED_SCOPE_KEY) return StoreOutcome.Unavailable
        if (!validHistoryEvent(entry)) return StoreOutcome.Failed("invalid")
        val outcome = try {
            db.write { d ->
                if (ticket.epoch != null && epochs.current(d, EpochKind.History, ticket.scope) != ticket.epoch) StoreOutcome.Stale
                else if (insertHistoryEvent(d, ticket.scope, entry)) StoreOutcome.Done else StoreOutcome.Duplicate
            }
        } catch (error: SQLiteException) { return StoreOutcome.Failed(error::class.simpleName ?: "sqlite") }
        if (outcome == StoreOutcome.Done) {
            _revision.value++
            val now = wallTime()
            if (now - (lastPrune[ticket.scope] ?: Long.MIN_VALUE / 2) >= PRUNE_INTERVAL_MS) {
                lastPrune[ticket.scope] = now
                runCatching { prune(ticket.scope, now) }
            }
        }
        return outcome
    }

    /** Newest first; plays at the same instant are ordered by event identifier, so pages never overlap. */
    fun page(scope: String, cursor: HistoryCursor? = null, query: String = "", limit: Int = HISTORY_PAGE_SIZE): HistoryPage {
        val where = StringBuilder("scope=?"); val args = mutableListOf(scope)
        if (cursor != null) {
            where.append(" AND (played_at<? OR (played_at=? AND event_id<?))")
            args += cursor.playedAt.toString(); args += cursor.playedAt.toString(); args += cursor.eventId
        }
        val wanted = normalizeForSearch(query.trim())
        if (wanted.isNotEmpty()) { where.append(" AND search_text LIKE ? ESCAPE '\\'"); args += "%" + escapeLike(wanted) + "%" }
        val rows = mutableListOf<HistoryEntry>()
        db.readableDatabase.query("history_events", arrayOf("event_id", "media_id", "title", "artist", "artwork", "played_at", "duration_ms", "music"),
            where.toString(), args.toTypedArray(), null, null, "played_at DESC,event_id DESC", (limit + 1).toString()).use { c ->
            while (c.moveToNext()) rows += HistoryEntry(c.getString(1), c.getString(2), c.getString(3), if (c.isNull(4)) null else c.getString(4),
                c.getLong(5), c.getInt(7) != 0, c.getString(0), if (c.isNull(6)) null else c.getLong(6))
        }
        val shown = rows.take(limit)
        return HistoryPage(shown, if (rows.size > limit) shown.last().let { HistoryCursor(it.playedAt, it.eventId) } else null)
    }

    fun count(scope: String): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM history_events WHERE scope=?", arrayOf(scope)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Removes this owner's plays only. Writes prepared before this call are rejected afterwards. */
    fun clear(scope: String): StoreOutcome = if (clearCounted(scope) == null) StoreOutcome.Failed("sqlite") else StoreOutcome.Done

    /** As [clear]; returns how many plays were removed, or null when nothing was changed. */
    fun clearCounted(scope: String): Int? {
        val (removed, epoch) = try {
            db.write { d -> d.delete("history_events", "scope=?", arrayOf(scope)) to epochs.advance(d, EpochKind.History, scope) }
        } catch (error: SQLiteException) { return null }
        epochs.committed(EpochKind.History, scope, epoch)
        _revision.value++
        return removed
    }

    /** Every owner that has plays. */
    fun scopes(): List<String> = db.readableDatabase.rawQuery("SELECT DISTINCT scope FROM history_events", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Plays, across every owner, that a retention period of [days] would remove now. */
    fun countOlderThan(days: Int, now: Long = wallTime()): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM history_events WHERE played_at<?", arrayOf((now - days * 86_400_000L).toString()))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Applies retention and the ceiling to every owner. */
    fun pruneAll(now: Long = wallTime()): Int = scopes().sumOf { prune(it, now) }

    /** Applies the retention setting and the row ceiling to one owner. Returns the plays removed. */
    fun prune(scope: String, now: Long = wallTime()): Int {
        var removed = 0
        db.write { d ->
            retentionDays()?.takeIf { it > 0 }?.let { days ->
                removed += d.delete("history_events", "scope=? AND played_at<?", arrayOf(scope, (now - days * 86_400_000L).toString()))
            }
            d.query("history_events", arrayOf("played_at", "event_id"), "scope=?", arrayOf(scope), null, null,
                "played_at DESC,event_id DESC", "$rowCap,1").use { c ->
                if (c.moveToFirst()) removed += d.delete("history_events", "scope=? AND (played_at<? OR (played_at=? AND event_id<=?))",
                    arrayOf(scope, c.getLong(0).toString(), c.getLong(0).toString(), c.getString(1)))
            }
        }
        if (removed > 0) _revision.value++
        return removed
    }

    private companion object { const val PRUNE_INTERVAL_MS = 3_600_000L }
}

/**
 * One paused queue per owner, so switching accounts never shows or overwrites another owner's
 * queue. Payloads hold identifiers and metadata only; streams are resolved again on playback.
 */
internal class PlaybackSnapshotRepository(private val db: LibraryStateDb, private val epochs: ScopeEpochs,
    private val wallTime: () -> Long, private val rejectedCopy: File, private val event: (String) -> Unit = {}) {
    private val _revision = MutableStateFlow(0)
    /** Changes whenever a saved queue appears or disappears. */
    val revision: StateFlow<Int> = _revision

    private val available = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun ticket(scope: String) = epochs.ticket(EpochKind.Snapshot, scope)

    fun save(ticket: ScopeTicket, snapshot: PlaybackSnapshot): StoreOutcome {
        if (ticket.scope == UNVERIFIED_SCOPE_KEY) return StoreOutcome.Unavailable
        val payload = encodePlaybackSnapshotBounded(snapshot) ?: return StoreOutcome.Failed("too_large")
        return try {
            db.write { d ->
                if (ticket.epoch != null && epochs.current(d, EpochKind.Snapshot, ticket.scope) != ticket.epoch) return@write StoreOutcome.Stale
                // A queue written by a newer build is kept aside before this build replaces it.
                row(d, ticket.scope)?.takeIf { it.first > SNAPSHOT_VERSION }?.let { preserve(it.second, "newer") }
                put(d, ticket.scope, payload, wallTime())
                StoreOutcome.Done
            }.also { if (it == StoreOutcome.Done && available.add(ticket.scope)) _revision.value++ }
        } catch (error: SQLiteException) { StoreOutcome.Failed(error::class.simpleName ?: "sqlite") }
    }

    fun load(scope: String): StoredSnapshot? {
        val (version, payload, updatedAt) = db.readableDatabase.let { row(it, scope) } ?: return null
        if (version > SNAPSHOT_VERSION) { event("snapshot_held reason=newer"); return null }
        return when (val decoded = decodePlaybackSnapshotDetailed(payload)) {
            is SnapshotDecode.Ok -> StoredSnapshot(scope, decoded.snapshot, updatedAt)
            is SnapshotDecode.Newer -> { event("snapshot_held reason=newer"); null }
            is SnapshotDecode.Invalid -> {
                // Kept once for recovery and removed from the table, so it is not parsed again on every launch.
                preserve(payload, decoded.reason)
                runCatching { db.write { d -> d.delete("playback_snapshots", "scope=?", arrayOf(scope)) } }
                _revision.value++
                null
            }
        }
    }

    /** The most recently saved valid queue among these owners. */
    fun newest(scopes: List<String>): StoredSnapshot? = scopes.distinct().mapNotNull(::load).maxByOrNull { it.updatedAt }

    /** The owner emptied its queue. Clears this owner only; an empty Guest queue never erases an account's queue. */
    fun clear(scope: String) {
        runCatching { db.write { d -> d.delete("playback_snapshots", "scope=?", arrayOf(scope)) } }
        available.remove(scope)
        _revision.value++
    }

    /** The owner's data is being deleted. A save prepared before this call cannot bring the queue back. */
    fun delete(scope: String): StoreOutcome {
        val epoch = try {
            db.write { d -> d.delete("playback_snapshots", "scope=?", arrayOf(scope)); epochs.advance(d, EpochKind.Snapshot, scope) }
        } catch (error: SQLiteException) { return StoreOutcome.Failed(error::class.simpleName ?: "sqlite") }
        epochs.committed(EpochKind.Snapshot, scope, epoch)
        available.remove(scope)
        _revision.value++
        return StoreOutcome.Done
    }

    /** Migration only: keeps the original save time and never replaces a queue that is already stored. */
    fun importRow(db: SQLiteDatabase, scope: String, snapshot: PlaybackSnapshot, updatedAt: Long): Boolean {
        if (row(db, scope) != null) return false
        put(db, scope, encodePlaybackSnapshotBounded(snapshot) ?: return false, updatedAt)
        return true
    }

    fun changed() { _revision.value++ }

    private fun row(db: SQLiteDatabase, scope: String): Triple<Int, String, Long>? =
        db.query("playback_snapshots", arrayOf("payload_version", "payload", "updated_at"), "scope=?", arrayOf(scope), null, null, null).use { c ->
            if (c.moveToFirst()) Triple(c.getInt(0), c.getString(1), c.getLong(2)) else null
        }

    private fun put(db: SQLiteDatabase, scope: String, payload: String, updatedAt: Long) {
        db.insertWithOnConflict("playback_snapshots", null, ContentValues().apply {
            put("scope", scope); put("payload_version", SNAPSHOT_VERSION); put("payload", payload); put("updated_at", updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** One private bounded copy of the last queue this build could not use. The event names the reason only. */
    private fun preserve(payload: String, reason: String) {
        runCatching {
            rejectedCopy.parentFile?.mkdirs()
            rejectedCopy.writeText(if (utf8Size(payload) <= MAX_SNAPSHOT_BYTES) payload else payload.take(MAX_SNAPSHOT_BYTES / 4))
        }
        event("snapshot_rejected reason=$reason")
    }
}

/**
 * Journal for folding one account's older storage key into its primary key across stores that do
 * not share a transaction: pending before any store is touched, then merged, or active when
 * some rows had to stay under the older key.
 */
internal class ScopeAliasJournal(private val db: LibraryStateDb) {
    /** False when the older key is already recorded for a different account. Nothing may be merged then. */
    fun begin(legacy: String, canonical: String): Boolean = db.write { d ->
        d.insertWithOnConflict("scope_aliases", null, ContentValues().apply {
            put("legacy_scope", legacy); put("canonical_scope", canonical); put("verification_version", 1); put("state", "pending")
        }, SQLiteDatabase.CONFLICT_IGNORE)
        entry(d, legacy)?.first == canonical
    }

    fun finish(legacy: String, canonical: String, merged: Boolean) {
        db.write { d ->
            d.update("scope_aliases", ContentValues().apply { put("state", if (merged) "merged" else "active") },
                "legacy_scope=? AND canonical_scope=?", arrayOf(legacy, canonical))
        }
    }

    /** Canonical key and state. */
    fun entry(legacy: String): Pair<String, String>? = entry(db.readableDatabase, legacy)

    private fun entry(db: SQLiteDatabase, legacy: String): Pair<String, String>? =
        db.query("scope_aliases", arrayOf("canonical_scope", "state"), "legacy_scope=?", arrayOf(legacy), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) to c.getString(1) else null
        }
}
