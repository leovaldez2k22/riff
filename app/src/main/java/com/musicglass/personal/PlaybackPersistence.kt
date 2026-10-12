package com.musicglass.personal

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A queue captured on the main thread, waiting to be written. */
internal class PendingSnapshot(val sequence: Long, val ticket: ScopeTicket, val snapshot: PlaybackSnapshot)

/**
 * Saving and loading the queue for the owner it belongs to. The queue is captured on the main thread and written
 * on [io], in the order it was captured; a routine save is skipped when a newer one exists. Owned by the playback
 * service. What a loaded queue does to the player is the service's business, not this class's.
 */
internal class PlaybackPersistence(private val scope: CoroutineScope, private val snapshots: PlaybackSnapshotRepository,
    private val stateReady: () -> Deferred<Unit>, private val currentScope: () -> String, private val radioSeed: () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    private sealed interface Write {
        class Save(val pending: PendingSnapshot) : Write
        class Clear(val sequence: Long, val scope: String) : Write
    }
    /** Owner of the queue now in the player, with its epoch; null when it is empty or may not be saved. */
    private var ticket: ScopeTicket? = null
    // Writes are applied in order on IO. A routine save is skipped when a newer one exists.
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val lastSave = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong()
    private val writeLock = Any()
    private var cachedTimeline: Timeline? = null
    private var cachedQueue: List<SavedQueueItem> = emptyList()
    private var cachedIds: List<String> = emptyList()
    /** Set when the session is being torn down: nothing captured after this may replace what was flushed. */
    var closed = false
        private set

    /** The owner whose queue is in the player, or empty. */
    val owner get() = ticket?.scope.orEmpty()

    /** Starts the writer. It ends with [scope]. */
    fun start() {
        scope.launch(io) {
            for (write in writes) runCatching { when (write) {
                is Write.Save -> persist(write.pending)
                // An owner that saved again after emptying its queue keeps the newer queue.
                is Write.Clear -> synchronized(writeLock) { if ((lastSave[write.scope] ?: -1) < write.sequence) snapshots.clear(write.scope) }
            } }
        }
    }

    /** The queue as it is now, ready to store. Runs on the main thread and never touches the database. */
    fun capture(player: Player): PendingSnapshot? {
        if (closed) return null
        if (player.mediaItemCount == 0) {
            // An emptied queue clears only its own owner; another owner's saved queue is untouched.
            val cleared = sequence.incrementAndGet()
            ticket?.let { writes.trySend(Write.Clear(cleared, it.scope)) }; ticket = null
            return null
        }
        if (cachedTimeline !== player.currentTimeline) {
            cachedTimeline = player.currentTimeline
            cachedQueue = (0 until player.mediaItemCount).map { i -> val item = player.getMediaItemAt(i); val m = item.mediaMetadata; val e = m.extras
                SavedQueueItem(item.mediaId, m.title?.toString().orEmpty(), m.artist?.toString().orEmpty(), m.artworkUri?.toString(),
                    m.albumTitle?.toString().orEmpty(), e?.getString("artistId"), e?.getString("albumId"), e?.getBoolean("live") == true,
                    item.localConfiguration?.uri?.toString()?.takeIf { item.mediaId.startsWith("local:") }, e?.getBoolean("music", true) != false) }
            cachedIds = cachedQueue.map { it.mediaId }
        }
        val queueOwner = queueOwnerKey(cachedIds, currentScope())
        if (queueOwner != ticket?.scope) {
            // The user replaced one kind of queue with another; the earlier one is no longer resumable.
            val cleared = sequence.incrementAndGet()
            ticket?.let { writes.trySend(Write.Clear(cleared, it.scope)) }
            ticket = queueOwner?.let(snapshots::ticket)
        }
        val current = ticket ?: return null
        val snapshot = PlaybackSnapshot(cachedQueue, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode, radioSeed(),
            player.playlistMetadata.title?.toString().orEmpty())
        val saved = sequence.incrementAndGet(); lastSave[current.scope] = saved
        return PendingSnapshot(saved, current, snapshot)
    }

    /** A routine save: queued behind earlier writes. */
    fun save(player: Player) { capture(player)?.let { writes.trySend(Write.Save(it)) } }

    private fun persist(pending: PendingSnapshot): StoreOutcome =
        synchronized(writeLock) { if (pending.sequence == sequence.get()) snapshots.save(pending.ticket, pending.snapshot) else StoreOutcome.Done }

    /**
     * Lifecycle handoff, after which nothing more is saved. Waits a bounded time for the write: Android gives no
     * later moment to finish it. If the process ends first, the last routine save stands.
     */
    fun flushAndClose(player: Player?) {
        val pending = player?.let(::capture); closed = true
        if (pending == null) return
        runBlocking { withTimeoutOrNull(FLUSH_WAIT_MS) { withContext(io) { runCatching { persist(pending) } } } }
    }

    /** Writes [pending] now, whatever was captured after it. For a queue that is about to be removed from the player. */
    suspend fun commit(pending: PendingSnapshot): StoreOutcome = withContext(io) { synchronized(writeLock) { snapshots.save(pending.ticket, pending.snapshot) } }

    /** The queue in the player is now [ownerKey]'s, as loaded. */
    fun adopt(ownerKey: String) { ticket = snapshots.ticket(ownerKey) }
    /** The player's queue was removed without being emptied by its owner; that owner's saved queue stays. */
    fun release() { ticket = null }

    /** The saved queue for the active owner or for the device, whichever is newer. Waits for older state to be moved first. */
    suspend fun loadNewest(): StoredSnapshot? = withContext(io) {
        try {
            stateReady().await()
            snapshots.newest(listOfNotNull(currentScope().takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY))
        } catch (failure: Exception) {
            // A caller that was cancelled stays cancelled; a migration that was itself cancelled is only "nothing saved".
            coroutineContext.ensureActive(); null
        }
    }
    suspend fun load(ownerKey: String): StoredSnapshot? = withContext(io) { runCatching { snapshots.load(ownerKey) }.getOrNull() }

    private companion object { const val FLUSH_WAIT_MS = 1500L }
}
