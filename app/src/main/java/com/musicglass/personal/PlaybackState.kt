package com.musicglass.personal

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the player is doing, as one value (Q06). Exactly one applies at a time. */
internal enum class PlaybackStatus {
    /** Nothing is queued. */
    Idle,
    /** The selected song's stream is being looked up; no audio has been requested yet. */
    Resolving,
    Buffering,
    Playing,
    /** A queue is loaded and silent: paused by the user, restored after a restart, or held by the system. */
    Paused,
    /** Playback failed and the app is trying again by itself. */
    Recovering,
    /** The queue played to its end. */
    Ended,
    /** Playback failed and nothing is being retried. */
    Failed,
}

/**
 * Why the app is retrying by itself: looking a stream up again, the same for a live stream, or
 * waiting for the connection. The text is what the service publishes; it never carries content.
 */
internal enum class RecoveryReason(val wire: String) {
    ProviderStream("stream"), LiveProfile("live"), Network("network");
    /** What the user is told while this goes on; null when the retry needs no words. */
    fun notice(): String? = if (this == Network) "Connection lost; waiting to reconnect" else null
    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

internal enum class PlaybackFailureKind {
    Connection,
    CollectionInvalid, CollectionTooLarge, CollectionRejected, CollectionPrepare,
    StreamRejected, StreamCommand, LocalUnavailable, LocalCommand,
    // Decided by recovery (Q07) and published by the service.
    CouldNotPlay, SessionExpired, LocalFileMissing, DownloadInvalid, SolverUnavailable;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } }
}

/** What the user can do about a failure. */
internal enum class FailureAction(val label: String) {
    Retry("Retry"), Skip("Skip"), SignIn("Sign in"), Redownload("Redownload"), RemoveDownload("Remove"),
}

/** A failure the user is told about. [detail] is a fixed code, never provider text, a URL or account data. */
internal data class PlaybackFailure(val kind: PlaybackFailureKind, val detail: String = "") {
    fun message(): String = when (kind) {
        PlaybackFailureKind.Connection -> "Playback could not connect: $detail"
        PlaybackFailureKind.CollectionInvalid -> "Could not open this collection"
        PlaybackFailureKind.CollectionTooLarge -> "This collection is too large to queue"
        PlaybackFailureKind.CollectionRejected -> "Could not open collection queue"
        PlaybackFailureKind.CollectionPrepare -> "Could not prepare collection queue"
        PlaybackFailureKind.StreamRejected -> "Provider playback is unavailable"
        PlaybackFailureKind.StreamCommand -> "Could not start provider playback"
        PlaybackFailureKind.LocalUnavailable -> "This on-device audio file is unavailable"
        PlaybackFailureKind.LocalCommand -> "Could not open this on-device audio file"
        PlaybackFailureKind.CouldNotPlay -> "Could not play this item"
        PlaybackFailureKind.SessionExpired -> "Sign in again to access account content"
        PlaybackFailureKind.LocalFileMissing -> "File unavailable on this device"
        PlaybackFailureKind.DownloadInvalid -> "Download incomplete or unavailable"
        PlaybackFailureKind.SolverUnavailable -> "Online playback resolver unavailable"
    }
    fun actions(): List<FailureAction> = when (kind) {
        PlaybackFailureKind.CouldNotPlay -> listOf(FailureAction.Retry, FailureAction.Skip)
        PlaybackFailureKind.SessionExpired -> listOf(FailureAction.SignIn)
        PlaybackFailureKind.LocalFileMissing -> listOf(FailureAction.Skip)
        PlaybackFailureKind.DownloadInvalid -> listOf(FailureAction.Redownload, FailureAction.RemoveDownload)
        PlaybackFailureKind.SolverUnavailable -> listOf(FailureAction.Retry)
        else -> emptyList()
    }
}

/**
 * The broad playback state. It changes when something the user can see or act on changes, not on
 * every position tick: the position is published separately ([PlaybackGateway.positionMs]).
 */
internal data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    /** What the user last asked for. It can be true while the status is Resolving, Buffering or Recovering. */
    val desiredPlayWhenReady: Boolean = false,
    /** Raised by every structural change to the queue: replace, add, remove, move. */
    val queueRevision: Long = 0,
    /** The selected entry. Two copies of one recording have different identifiers. */
    val queueItemId: String = "",
    /** Storage key of the owner whose queue is loaded; blank when none is, or when it may not be saved. */
    val owner: String = "",
    val durationMs: Long = 0,
    val failure: PlaybackFailure? = null,
    val recovery: RecoveryReason? = null,
)

internal fun playbackStatus(itemCount: Int, playerState: Int, playWhenReady: Boolean, isPlaying: Boolean,
    failed: Boolean, recovering: Boolean, resolving: Boolean): PlaybackStatus = when {
    itemCount == 0 -> PlaybackStatus.Idle
    recovering -> PlaybackStatus.Recovering
    failed -> PlaybackStatus.Failed
    playerState == Player.STATE_ENDED -> PlaybackStatus.Ended
    isPlaying -> PlaybackStatus.Playing
    playerState == Player.STATE_BUFFERING -> if (resolving) PlaybackStatus.Resolving else PlaybackStatus.Buffering
    else -> PlaybackStatus.Paused
}

/** The identifier of this queue entry, given when it entered the queue. Blank for an item that was never queued. */
internal fun MediaItem.entryId(): String = mediaMetadata.extras?.getString(ENTRY_ID).orEmpty()
internal const val ENTRY_ID = "entryId"

/**
 * Order of the commands that reach the playback service. Used on the main thread only.
 *
 * Every command gets a ticket when it arrives. Work for it may finish later, after reading a file;
 * before it touches the queue it asks whether it still applies. A request to replace the queue makes
 * every earlier request obsolete, and a pause or play made after a request decides whether it may
 * start playback.
 */
internal class PlaybackCommandOrder {
    data class Ticket(val sequence: Long, val replaces: Boolean)
    var queueRevision = 0L; private set
    var commandSequence = 0L; private set
    private var newestReplace = 0L
    private var transportSequence = 0L
    private var transportPlay = false

    fun arrive(replaces: Boolean): Ticket = Ticket(++commandSequence, replaces).also { if (replaces) newestReplace = it.sequence }
    /** The user pressed play or pause, from the app, the notification, a headset or a widget. */
    fun userIntent(play: Boolean) { transportSequence = ++commandSequence; transportPlay = play }
    fun edited(): Long = ++queueRevision
    /** A later request replaces the queue this one was meant for. */
    fun superseded(ticket: Ticket) = newestReplace > ticket.sequence
    /** Whether a request that asked to start playing may. A pause made after it wins; so does a play made after that. */
    fun autoplay(ticket: Ticket, requested: Boolean) = if (transportSequence > ticket.sequence) requested && transportPlay else requested
}

/**
 * Applies queue commands one at a time, in the order they arrived, on [scope]'s dispatcher (the main
 * thread in the service). [R] is the answer given to whoever sent the command.
 */
internal class OrderedCommands<R>(private val scope: CoroutineScope, private val order: PlaybackCommandOrder,
    private val cancelled: R, private val failed: R) {
    private val lane = Mutex()

    /**
     * [prepare] is the slow part and may suspend; [apply] changes the queue. A command that a later
     * replacement has made obsolete is answered [cancelled], whether that happened before it started
     * or while it was preparing. [done] is called exactly once.
     */
    fun <P> submit(replaces: Boolean, onSuperseded: () -> Unit = {}, prepare: suspend () -> P,
        apply: (PlaybackCommandOrder.Ticket, P) -> R, done: (R) -> Unit) {
        val ticket = order.arrive(replaces)
        var answered = false
        fun answer(result: R) { if (!answered) { answered = true; done(result) } }
        scope.launch {
            answer(lane.withLock {
                // A command that throws is answered as failed; it must not take the service down or block the lane.
                try {
                    if (order.superseded(ticket)) { onSuperseded(); cancelled }
                    else {
                        val prepared = prepare()
                        if (order.superseded(ticket)) { onSuperseded(); cancelled } else apply(ticket, prepared)
                    }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { failed }
            })
        }.invokeOnCompletion { answer(failed) }
    }
}

/** Where selection goes when entries are removed from the queue. */
internal sealed interface RemovalPlan {
    /** The queue becomes empty: pause, then remove. */
    data object Empties : RemovalPlan
    /** The player's own handling is right: the current entry stays, or the entry that follows takes over. */
    data object Proceed : RemovalPlan
    /** Nothing follows the current entry: select [index] at its start, paused, then remove. */
    data class SelectBefore(val index: Int) : RemovalPlan
}

/**
 * Removing the current entry selects the survivor that follows it in play order, otherwise the one
 * before it. Removing the only entry leaves an empty, paused queue. [next] and [previous] are the
 * player's own neighbours, so shuffle and repeat are respected.
 */
internal fun removalPlan(count: Int, current: Int, removed: Int, next: Int, previous: Int): RemovalPlan = when {
    count <= 1 -> RemovalPlan.Empties
    removed != current || next != C.INDEX_UNSET -> RemovalPlan.Proceed
    previous == C.INDEX_UNSET -> RemovalPlan.Proceed
    else -> RemovalPlan.SelectBefore(previous)
}

/** Removal of several entries at once (every copy of a hidden song), in list order with shuffle off. */
internal fun removalPlan(count: Int, current: Int, removed: Set<Int>): RemovalPlan = when {
    removed.size >= count -> RemovalPlan.Empties
    current !in removed || (current + 1 until count).any { it !in removed } -> RemovalPlan.Proceed
    else -> (current - 1 downTo 0).firstOrNull { it !in removed }?.let { RemovalPlan.SelectBefore(it) } ?: RemovalPlan.Proceed
}
