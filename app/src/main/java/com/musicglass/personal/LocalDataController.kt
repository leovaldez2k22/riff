package com.musicglass.personal

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a clear or delete did. Nothing here is ever sent to YouTube Music. */
internal sealed interface DataActionResult {
    /** Everything asked for is gone. [removed] counts items: plays, searches, songs or saved tracks. */
    data class Done(val removed: Int) : DataActionResult
    /** Some of it is gone. [remaining] names what is still on the phone, in the user's words. Asking again retries only that. */
    data class Partial(val removed: Int, val remaining: List<String>) : DataActionResult
    /** Nothing was changed. [kind] is a fixed word, never content. */
    data class Failed(val kind: String) : DataActionResult
}

/** What is kept on this phone for one owner, and for the device. */
internal data class LocalDataSummary(val accountPlays: Int = 0, val devicePlays: Int = 0, val searches: Int = 0,
    val downloads: Int = 0, val savedTracks: Int = 0, val hasQueue: Boolean = false)

/**
 * Clearing and deleting what Riff keeps on the phone (Q05). Every action names one owner and touches
 * only that owner's rows and files; device settings and the audio files in the phone's own library
 * are never touched. Actions run one at a time, off the main thread, and say what they could not do.
 */
internal class LocalDataController(context: Context, private val history: HistoryRepository, private val snapshots: PlaybackSnapshotRepository,
    private val downloads: () -> OfflineMusicStore, private val searches: () -> SearchHistory, private val io: CoroutineDispatcher,
    private val wallTime: () -> Long, private val event: (String) -> Unit = {}) {
    private val app = context.applicationContext
    /** Shared with export and import, so they never run alongside a clear or a delete. */
    internal val gate = Mutex()
    private val meta get() = app.getSharedPreferences("library_state_meta", Context.MODE_PRIVATE)

    private fun owned(owner: String) = owner != UNVERIFIED_SCOPE_KEY && owner != DEVICE_LOCAL_SCOPE_KEY

    suspend fun summary(owner: String): LocalDataSummary = withContext(io) {
        val account = owned(owner)
        LocalDataSummary(
            accountPlays = if (account) history.count(owner) else 0,
            devicePlays = history.count(DEVICE_LOCAL_SCOPE_KEY),
            searches = if (account) searches().count(owner) else 0,
            downloads = if (account) downloads().downloadCount(owner) else 0,
            savedTracks = if (account) savedCollectionCount(app, owner) else 0,
            hasQueue = account && snapshots.load(owner) != null)
    }

    private suspend fun act(name: String, block: suspend () -> DataActionResult): DataActionResult = withContext(io) {
        gate.withLock {
            val result = try { block() } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { DataActionResult.Failed(error::class.simpleName ?: "error") }
            event("local_data action=$name result=" + when (result) {
                is DataActionResult.Done -> "done count=${result.removed}"
                is DataActionResult.Partial -> "partial count=${result.removed} remaining=${result.remaining.size}"
                is DataActionResult.Failed -> "failed kind=${result.kind}"
            })
            result
        }
    }

    /**
     * The plays of one owner: an account, signed-out use, or the device. Clearing an account's history
     * leaves the device's, and the other way round. For an account the recents list and the play
     * counts kept with its downloads go too, since they are the same record in another place.
     */
    suspend fun clearHistory(scope: String): DataActionResult = act("clear_history") {
        if (scope == UNVERIFIED_SCOPE_KEY) return@act DataActionResult.Failed("unverified")
        val removed = history.clearCounted(scope) ?: return@act DataActionResult.Failed("storage")
        val remaining = buildList {
            if (scope != DEVICE_LOCAL_SCOPE_KEY) {
                if (clearCollectionRecents(app, scope) == null) add("recently played")
                if (!downloads().resetListening(scope)) add("play counts")
            }
        }
        if (remaining.isEmpty()) DataActionResult.Done(removed) else DataActionResult.Partial(removed, remaining)
    }

    suspend fun clearSearches(owner: String): DataActionResult = act("clear_searches") {
        if (!owned(owner)) return@act DataActionResult.Failed("unverified")
        searches().clearOwner(owner)?.let { DataActionResult.Done(it) } ?: DataActionResult.Failed("storage")
    }

    /** Downloaded songs, artwork and lyrics of one owner, and its waiting transfers. Listening totals stay. */
    suspend fun deleteDownloads(owner: String): DataActionResult = act("delete_downloads") {
        if (!owned(owner)) return@act DataActionResult.Failed("unverified")
        val (removed, failed) = downloads().removeAllCounted(owner)
        when {
            failed == 0 -> DataActionResult.Done(removed)
            removed == 0 -> DataActionResult.Failed("files")
            else -> DataActionResult.Partial(removed, listOf("$failed ${if (failed == 1) "download" else "downloads"}"))
        }
    }

    /**
     * Everything kept for [owner]: plays, searches, the saved queue, tracks saved in the app and
     * downloads. [quiesce] must stop and empty the owner's queue first, so nothing still playing can
     * write a row back. The sign-in itself is not touched; signing out is a separate action.
     */
    suspend fun deleteAccountData(owner: String, quiesce: suspend () -> Unit): DataActionResult {
        if (!owned(owner)) return DataActionResult.Failed("unverified")
        quiesce()
        return act("delete_account_data") {
            var removed = 0
            val remaining = mutableListOf<String>()
            fun part(name: String, count: Int?) { if (count == null) remaining += name else removed += count }
            part("listening history", runCatching { history.clearCounted(owner) }.getOrNull())
            part("search history", runCatching { searches().clearOwner(owner) }.getOrNull())
            // Raises the owner's counter: a save prepared before this cannot bring the queue back.
            part("saved queue", if (runCatching { snapshots.delete(owner) }.getOrNull() == StoreOutcome.Done) 0 else null)
            part("saved tracks", runCatching { clearCollectionScope(app, owner) }.getOrNull())
            val files = runCatching { downloads().deleteScope(owner) }.getOrNull()
            when {
                files == null -> remaining += "downloads"
                files.second > 0 -> { removed += files.first; remaining += "${files.second} download ${if (files.second == 1) "file" else "files"}" }
                else -> removed += files.first
            }
            if (remaining.isEmpty()) DataActionResult.Done(removed) else DataActionResult.Partial(removed, remaining)
        }
    }

    /** Plays, across every owner, that choosing [days] would remove at once. */
    suspend fun retentionPreview(days: Int): Int = withContext(io) { history.countOlderThan(days, wallTime()) }

    /** Stores the period and applies it to every owner now. Null keeps plays until they are cleared. */
    suspend fun setRetention(days: Int?): DataActionResult = act("set_retention") {
        setHistoryRetentionDays(app, days)
        val removed = history.pruneAll(wallTime())
        meta.edit { putLong("history_pruned_at", wallTime()) }
        DataActionResult.Done(removed)
    }

    /**
     * Applies retention to every owner at most once a day, when the app is doing other work anyway.
     * There is no alarm: plays can outlive the period until Riff next runs.
     */
    suspend fun pruneIfDue(): Int = withContext(io) {
        val now = wallTime()
        val last = meta.getLong("history_pruned_at", 0L)
        // A clock set backwards must not postpone pruning indefinitely.
        if (last in (now - DAY_MS + 1)..now) return@withContext 0
        gate.withLock {
            val removed = runCatching { history.pruneAll(now) }.getOrElse { return@withLock 0 }
            meta.edit { putLong("history_pruned_at", now) }
            if (removed > 0) event("history_retention removed=$removed")
            removed
        }
    }

    private companion object { const val DAY_MS = 86_400_000L }
}
