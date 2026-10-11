// Lint does not follow query(...).use { } and reports each cursor as unclosed. Every cursor in this file is closed by use.
@file:SuppressLint("Recycle")

package com.musicglass.personal

import android.annotation.SuppressLint
import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Named so a test can stop the migration immediately before or after each one. */
internal enum class MigrationCheckpoint { Discovered, SourceFrozen, Parsed, TransactionStarted, Committed, Verified, Archived }

internal sealed interface LegacyResult {
    data object NothingToDo : LegacyResult
    data class Completed(val imported: Int, val rejected: Int) : LegacyResult
    /** [stage] and [kind] are fixed words or class names, never content. The source is left in place. */
    data class Failed(val stage: String, val kind: String) : LegacyResult
}

internal data class LegacyReport(val history: LegacyResult, val queue: LegacyResult, val interimQueues: LegacyResult)

/** What is still waiting for the user's decision. */
internal data class UnassignedInventory(val providerPlays: Int = 0, val sources: Int = 0, val queueItems: Int = 0) {
    val empty get() = providerPlays == 0 && sources == 0 && queueItems == 0
}

/** [kept] is smaller than [available] only when the per-owner ceiling would be exceeded. */
internal data class ImportPreview(val available: Int, val kept: Int, val alreadyImported: Boolean)

/**
 * Moves state written before owners existed (Q04). The old history file and the old saved queue
 * were shared by every account that used the phone, so neither is given to an account here:
 *
 * - plays of on-device audio are certain to belong to the device and are imported;
 * - plays from YouTube Music stay in a private "unassigned" folder until the user imports or deletes them;
 * - the old queue is set aside until the user restores it, paused, or deletes it.
 *
 * Safe to run on every start and after an interruption at any point. Call off the main thread.
 */
internal class LegacyStateMigration(context: Context, private val db: LibraryStateDb, private val history: HistoryRepository,
    private val snapshots: PlaybackSnapshotRepository, private val wallTime: () -> Long, private val event: (String) -> Unit = {},
    private val checkpoint: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val unassigned = File(app.filesDir, "unassigned")
    private val legacyHistory = File(app.filesDir, "listening-history/history-v1.jsonl")
    private val incoming = File(unassigned, "history-v1.incoming.jsonl")
    private val quarantinedQueue = File(unassigned, "playback-snapshot-v1.json")
    private val parsedCache = HashMap<String, ParsedSource>()

    private class Fingerprint(val full: String, val short: String)
    private class ParsedSource(val local: List<HistoryEntry>, val provider: List<HistoryEntry>, val rejected: Int)

    @Synchronized fun run(): LegacyReport {
        val report = LegacyReport(migrateHistory(), quarantineQueue(), importInterimQueues())
        listOf("history" to report.history, "queue" to report.queue, "interim_queues" to report.interimQueues).forEach { (name, result) ->
            when (result) {
                is LegacyResult.Completed -> event("legacy_migration part=$name imported=${result.imported} rejected=${result.rejected}")
                is LegacyResult.Failed -> event("legacy_migration part=$name failed_stage=${result.stage} kind=${result.kind}")
                LegacyResult.NothingToDo -> Unit
            }
        }
        return report
    }

    // ---- history

    private fun at(part: String, point: MigrationCheckpoint, phase: String) = checkpoint("$part:${point.name}:$phase")

    private fun migrateHistory(): LegacyResult {
        var stage = "discover"
        try {
            // An interrupted earlier run may have left the incoming copy; a later old build may have written a new file.
            repeat(2) {
                at("history", MigrationCheckpoint.Discovered, "before")
                if (!incoming.exists() && legacyHistory.exists()) {
                    unassigned.mkdirs()
                    // Same filesystem, so this is one atomic step and nothing can append to the file afterwards.
                    if (!legacyHistory.renameTo(incoming)) return LegacyResult.Failed("freeze", "rename")
                }
                at("history", MigrationCheckpoint.Discovered, "after")
                stage = "freeze"
                at("history", MigrationCheckpoint.SourceFrozen, "before")
                if (incoming.exists()) {
                    val frozen = frozenFile(fingerprint(incoming).short)
                    if (frozen.exists()) incoming.delete() // The identical bytes are already frozen.
                    else if (!incoming.renameTo(frozen)) return LegacyResult.Failed("freeze", "rename")
                }
                at("history", MigrationCheckpoint.SourceFrozen, "after")
            }
            val sources = frozenSources()
            if (sources.isEmpty()) return LegacyResult.NothingToDo
            var imported = 0; var rejected = 0
            for ((short, file) in sources) {
                stage = "import"
                when (val result = importDevicePlays(short, file)) {
                    is LegacyResult.Completed -> { imported += result.imported; rejected += result.rejected }
                    is LegacyResult.Failed -> return result
                    LegacyResult.NothingToDo -> Unit
                }
            }
            return LegacyResult.Completed(imported, rejected)
        } catch (error: Exception) {
            return LegacyResult.Failed(stage, error::class.simpleName ?: "error")
        }
    }

    private fun importDevicePlays(short: String, file: File): LegacyResult {
        val id = "history-v1-local:$short"
        val before = db.migrationRun(id)
        if (before?.state == "archived" || before?.state == "rejected_oversize") return LegacyResult.NothingToDo
        val now = wallTime()
        if (file.length() > MAX_SOURCE_BYTES) {
            db.write { d -> db.putMigrationRun(d, MigrationRun(id, "history-v1:${file.length()}:unread", "rejected_oversize", 0, 0, now)) }
            return LegacyResult.Completed(0, 0)
        }
        val print = fingerprint(file)
        if (print.short != short) return LegacyResult.Failed("verify", "source_changed")
        if (before == null || before.state !in setOf("committed", "verified")) {
            at("history", MigrationCheckpoint.Parsed, "before")
            val parsed = parse(short, file)
            at("history", MigrationCheckpoint.Parsed, "after")
            val alreadyImported = playsFromOtherSources(short)
            at("history", MigrationCheckpoint.TransactionStarted, "before")
            db.write { d ->
                at("history", MigrationCheckpoint.TransactionStarted, "after")
                var accepted = 0
                for (entry in parsed.local) {
                    // A second, different copy of the old file must not repeat plays an earlier copy already brought in.
                    val key = playKey(entry)
                    val remaining = alreadyImported[key] ?: 0
                    if (remaining > 0) { alreadyImported[key] = remaining - 1; continue }
                    insertHistoryEvent(d, DEVICE_LOCAL_SCOPE_KEY, entry)
                    accepted++
                }
                // The rows and their marker commit together or not at all.
                db.putMigrationRun(d, MigrationRun(id, print.full, "committed", accepted, parsed.rejected, null))
                at("history", MigrationCheckpoint.Committed, "before")
            }
            at("history", MigrationCheckpoint.Committed, "after")
            history.changed()
        }
        val run = db.migrationRun(id) ?: return LegacyResult.Failed("verify", "missing_marker")
        if (run.state == "committed") {
            at("history", MigrationCheckpoint.Verified, "before")
            val stored = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM history_events WHERE scope=? AND event_id LIKE ?",
                arrayOf(DEVICE_LOCAL_SCOPE_KEY, "legacy-$short-%")).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            // Fewer rows are expected only when the user has cleared device history since the commit.
            if (stored != run.imported && history.ticket(DEVICE_LOCAL_SCOPE_KEY).epoch == 0L && stored < run.imported)
                return LegacyResult.Failed("verify", "count")
            db.write { d -> db.putMigrationRun(d, run.copy(state = "verified")) }
            at("history", MigrationCheckpoint.Verified, "after")
        }
        // The frozen file is the archive: it still holds the plays that have no owner and is removed only by the user.
        at("history", MigrationCheckpoint.Archived, "before")
        db.write { d -> db.putMigrationRun(d, run.copy(state = "archived", completedAt = now)) }
        at("history", MigrationCheckpoint.Archived, "after")
        return LegacyResult.Completed(run.imported, run.rejected)
    }

    private fun playKey(entry: HistoryEntry) = "${entry.mediaId}|${entry.playedAt}|${entry.title}"

    private fun playsFromOtherSources(short: String): HashMap<String, Int> {
        val counts = HashMap<String, Int>()
        db.readableDatabase.query("history_events", arrayOf("media_id", "played_at", "title"),
            "scope=? AND event_id LIKE 'legacy-%' AND event_id NOT LIKE ?", arrayOf(DEVICE_LOCAL_SCOPE_KEY, "legacy-$short-%"),
            null, null, null).use { c ->
            while (c.moveToNext()) "${c.getString(0)}|${c.getLong(1)}|${c.getString(2)}".let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts
    }

    private fun frozenFile(short: String) = File(unassigned, "history-v1-$short.jsonl")

    private fun frozenSources(): List<Pair<String, File>> = unassigned.listFiles().orEmpty().mapNotNull { file ->
        FROZEN_NAME.matchEntire(file.name)?.let { it.groupValues[1] to file }
    }.sortedBy { it.first }

    private fun fingerprint(file: File): Fingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Fingerprint("history-v1:${file.length()}:$hex", hex.take(16))
    }

    /**
     * Every line is judged on its own. The event identifier comes from the source and the line number,
     * so the same play listed twice stays two plays and running again adds nothing.
     */
    private fun parse(short: String, file: File): ParsedSource = parsedCache.getOrPut("$short:${file.length()}") {
        val local = ArrayList<HistoryEntry>(); val provider = ArrayList<HistoryEntry>(); var rejected = 0
        file.bufferedReader().use { reader ->
            val line = StringBuilder(); var ordinal = 0; var oversized = false
            fun finish() {
                val text = line.toString(); line.setLength(0)
                val tooLong = oversized; oversized = false
                if (text.isBlank() && !tooLong) return
                val number = ordinal++
                val entry = if (tooLong) null else decodeHistoryEntry(text)?.copy(eventId = "legacy-$short-$number")
                when {
                    entry == null || !validHistoryEvent(entry) -> rejected++
                    entry.mediaId.startsWith("local:") -> local += entry
                    else -> provider += entry
                }
            }
            while (true) {
                val c = reader.read()
                if (c < 0) break
                if (c == '\n'.code) finish()
                else if (line.length < MAX_LINE_CHARS) line.append(c.toChar()) else oversized = true
            }
            if (line.isNotEmpty() || oversized) finish()
        }
        ParsedSource(local, provider, rejected)
    }

    private fun usableSources() = frozenSources().filter { it.second.length() <= MAX_SOURCE_BYTES }

    // ---- what the user can do with the unassigned data

    @Synchronized fun inventory(): UnassignedInventory {
        // A source whose plays were all on-device audio (or unreadable lines) leaves nothing to decide and is not counted.
        var plays = 0; var waiting = 0
        frozenSources().forEach { (short, file) ->
            val found = if (file.length() > MAX_SOURCE_BYTES) null else runCatching { parse(short, file).provider.size }.getOrNull()
            plays += found ?: 0
            if (found != 0) waiting++
        }
        return UnassignedInventory(plays, waiting, runCatching { quarantinedQueue()?.queue?.size }.getOrNull() ?: 0)
    }

    @Synchronized fun previewImport(target: String): ImportPreview {
        val sources = usableSources()
        val available = sources.sumOf { (short, file) -> parse(short, file).provider.size }
        return ImportPreview(available, minOf(available, HISTORY_ROW_CAP),
            sources.isNotEmpty() && sources.all { db.migrationRun("history-v1-provider:${it.first}:$target") != null })
    }

    /**
     * Only on the user's confirmation. The plays may come from more than one account; the caller says so
     * before asking. The unassigned copy stays until the user deletes it. Returns the plays added.
     */
    @Synchronized fun importProviderHistory(target: String): Int? {
        if (target == UNVERIFIED_SCOPE_KEY || target == DEVICE_LOCAL_SCOPE_KEY) return null
        return try {
            val perSource = usableSources().map { (short, file) -> short to parse(short, file).provider }
            val kept = perSource.flatMap { (short, plays) -> plays.map { short to it } }
                .sortedByDescending { it.second.playedAt }.take(HISTORY_ROW_CAP)
            val now = wallTime()
            val added = db.write { d ->
                var count = 0
                kept.forEach { (_, entry) -> if (insertHistoryEvent(d, target, entry)) count++ }
                perSource.forEach { (short, plays) ->
                    db.putMigrationRun(d, MigrationRun("history-v1-provider:$short:$target", "history-v1:$short", "archived",
                        kept.count { it.first == short }, plays.size - kept.count { it.first == short }, now))
                }
                count
            }
            runCatching { history.prune(target, now) }
            history.changed()
            event("legacy_history_imported count=$added")
            added
        } catch (error: Exception) {
            event("legacy_history_import_failed kind=${error::class.simpleName}")
            null
        }
    }

    /** True when nothing unassigned is left. Imported plays are not affected. */
    @Synchronized fun deleteUnassignedHistory(): Boolean {
        val root = unassigned.canonicalFile
        var clean = true
        (frozenSources().map { it.second } + incoming).forEach { file ->
            if (file.exists() && (file.canonicalFile.parentFile != root || !file.delete())) clean = false
        }
        parsedCache.clear()
        event("legacy_history_deleted complete=$clean")
        return clean
    }

    // ---- the old shared queue

    // commit() is used directly because its result decides whether the old copy was retired; the KTX form discards it.
    @SuppressLint("UseKtx")
    private fun quarantineQueue(): LegacyResult {
        try {
            val prefs = app.getSharedPreferences("last_playback", Context.MODE_PRIVATE)
            val text = prefs.getString("snapshot", null) ?: return LegacyResult.NothingToDo
            checkpoint("queue:Discovered:after")
            val now = wallTime()
            if (text.length > MAX_SNAPSHOT_BYTES || utf8Size(text) > MAX_SNAPSHOT_BYTES) {
                db.write { d -> db.putMigrationRun(d, MigrationRun("snapshot-v1", "snapshot-v1:oversize", "rejected_oversize", 0, 1, now)) }
                if (!prefs.edit().remove("snapshot").commit()) return LegacyResult.Failed("retire", "preferences")
                return LegacyResult.Completed(0, 1)
            }
            unassigned.mkdirs()
            val temp = File(unassigned, quarantinedQueue.name + ".tmp")
            temp.writeText(text)
            if (quarantinedQueue.exists() && !quarantinedQueue.delete()) return LegacyResult.Failed("freeze", "replace")
            if (!temp.renameTo(quarantinedQueue)) return LegacyResult.Failed("freeze", "rename")
            checkpoint("queue:SourceFrozen:after")
            val hex = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            db.write { d -> db.putMigrationRun(d, MigrationRun("snapshot-v1", "snapshot-v1:${text.length}:$hex", "archived", 1, 0, now)) }
            checkpoint("queue:Committed:after")
            // Only now is the old copy retired; until this line a restart simply repeats the steps above.
            if (!prefs.edit().remove("snapshot").commit()) return LegacyResult.Failed("retire", "preferences")
            checkpoint("queue:Archived:after")
            return LegacyResult.Completed(1, 0)
        } catch (error: Exception) {
            return LegacyResult.Failed("queue", error::class.simpleName ?: "error")
        }
    }

    @Synchronized fun quarantinedQueue(): PlaybackSnapshot? =
        quarantinedQueue.takeIf { it.isFile && it.length() <= MAX_SNAPSHOT_BYTES }?.readText()?.let(::decodePlaybackSnapshot)

    /**
     * Only on the user's confirmation: gives the old queue to the owner the user is in now. Returns that
     * owner, or null when it cannot be stored. The caller then asks the player to load it, paused.
     */
    @Synchronized fun adoptQuarantinedQueue(accountKey: String): String? {
        val snapshot = quarantinedQueue() ?: return null
        val owner = queueOwnerKey(snapshot.queue.map { it.mediaId }, accountKey) ?: return null
        if (snapshots.save(snapshots.ticket(owner), snapshot) != StoreOutcome.Done) return null
        quarantinedQueue.delete()
        snapshots.changed()
        event("legacy_queue_restored count=${snapshot.queue.size}")
        return owner
    }

    @Synchronized fun deleteQuarantinedQueue(): Boolean {
        val gone = !quarantinedQueue.exists() || quarantinedQueue.delete()
        event("legacy_queue_deleted complete=$gone")
        return gone
    }

    // ---- per-owner queues kept in preferences by the build that introduced owners

    @SuppressLint("UseKtx") // As above: the result of commit() is needed.
    private fun importInterimQueues(): LegacyResult {
        try {
            val prefs = app.getSharedPreferences("playback_snapshots", Context.MODE_PRIVATE)
            val all = prefs.all
            if (all.isEmpty()) return LegacyResult.NothingToDo
            var imported = 0; var rejected = 0
            val now = wallTime()
            db.write { d ->
                all.keys.filter { it.startsWith("snapshot_") }.forEach { key ->
                    val owner = key.removePrefix("snapshot_")
                    val snapshot = (all[key] as? String)?.let(::decodePlaybackSnapshot)
                    if (snapshot == null || !OWNER_KEY.matches(owner) || owner == UNVERIFIED_SCOPE_KEY) rejected++
                    else if (snapshots.importRow(d, owner, snapshot, (all["updated_$owner"] as? String)?.toLongOrNull() ?: 0L)) imported++
                }
                db.putMigrationRun(d, MigrationRun("snapshot-prefs-q03", "preferences", "archived", imported, rejected, now))
            }
            if (!prefs.edit().clear().commit()) return LegacyResult.Failed("retire", "preferences")
            snapshots.changed()
            return LegacyResult.Completed(imported, rejected)
        } catch (error: Exception) {
            return LegacyResult.Failed("interim_queues", error::class.simpleName ?: "error")
        }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        const val MAX_LINE_CHARS = 16 * 1024
        val FROZEN_NAME = Regex("history-v1-([0-9a-f]{16})\\.jsonl")
        val OWNER_KEY = Regex("[A-Za-z0-9_-]{1,80}")
    }
}
