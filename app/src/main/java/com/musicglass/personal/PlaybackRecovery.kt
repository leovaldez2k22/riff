package com.musicglass.personal

import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.sabr.SabrFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why playback of the selected entry failed (Q07). Only [wire] reaches events and other processes. */
internal enum class RecoveryClass(val wire: String) {
    Offline("offline"), Timeout("timeout"), RejectedStream("rejected"), AttestationExpired("attestation"),
    SessionExpired("session"), RateLimited("rate_limited"), ProviderUnavailable("provider"),
    SolverUnavailable("solver"), UnsupportedMedia("unsupported"), LocalFileMissing("local_missing"),
}

/** Where the failed entry plays from. A download is a provider song played from its file on this device. */
internal enum class FaultSource { Provider, Live, Download, Device }

/** One classified failure. It carries no exception text, address or account data. */
internal data class PlaybackFault(val kind: RecoveryClass, val source: FaultSource, val httpStatus: Int? = null,
    val retryAfterMs: Long? = null, val profile: String? = null)

private val networkCauses = listOf("UnknownHostException", "ConnectException", "SocketTimeoutException",
    "NoRouteToHostException", "SocketException", "SSLException", "HttpRequestTimeoutException", "EOFException",
    "TimeoutCancellationException")
private val unplayableReasons = listOf("NO_PLAYABLE_STREAM", "EXPLICIT_UNSUPPORTED", "UNAVAILABLE", "AGE_RESTRICTED", "NO_MUSIC_VIDEO")
    .map { "Track unavailable: $it" } + listOf("No supported audio stream is available for this track",
    "No playable audio stream is available for this track",
    "This track requires bounded-range streaming, which this player does not support")
private val unplayableCodes = listOf("ERROR_CODE_DECOD", "ERROR_CODE_PARSING_", "ERROR_CODE_AUDIO_TRACK_", "ERROR_CODE_DRM_")

/**
 * Sorts a failure into one class. [errorCodeName] is the player's code, or blank for a failure that
 * never reached the player; [causes] is the cause chain. The first rule that matches wins: a
 * definite answer about the item itself comes before the state of the connection, and the
 * connection before anything the provider said.
 */
internal fun classifyFault(errorCodeName: String, causes: List<Throwable>, source: FaultSource, online: Boolean,
    sessionExpired: Boolean, httpStatus: Int? = null, retryAfterMs: Long? = null, profile: String? = null): PlaybackFault {
    val status = httpStatus ?: causes.firstNotNullOfOrNull { (it as? InnerTubeHttpException)?.status?.value }
    fun fault(kind: RecoveryClass) = PlaybackFault(kind, source, status, retryAfterMs, profile)
    val unplayable = unplayableCodes.any { errorCodeName.startsWith(it) }
    if (source == FaultSource.Download) return fault(RecoveryClass.LocalFileMissing)
    if (source == FaultSource.Device) return fault(if (unplayable) RecoveryClass.UnsupportedMedia else RecoveryClass.LocalFileMissing)
    val messages = causes.map { it.message.orEmpty() }
    if (status == 401 || SESSION_EXPIRED_MESSAGE in messages) return fault(RecoveryClass.SessionExpired)
    if (unplayable || messages.any { it in unplayableReasons }) return fault(RecoveryClass.UnsupportedMedia)
    if (!online) return fault(RecoveryClass.Offline)
    if (status == 429) return fault(RecoveryClass.RateLimited)
    val sabr = causes.firstNotNullOfOrNull { it as? SabrPlaybackFailure }
    if (sabr?.kind == SabrFailureKind.ATTESTATION_REQUIRED) return fault(RecoveryClass.AttestationExpired)
    if (causes.any { it is SolverUnavailableException }) return fault(RecoveryClass.SolverUnavailable)
    val network = errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" || errorCodeName == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" ||
        causes.any { cause -> networkCauses.any { name -> cause.javaClass.simpleName == name || cause.message.orEmpty().contains(name) } } ||
        // innertubex's StreamResolveException.Reason.NETWORK, as YouTubeMusicProvider.resolve reports it.
        messages.any { it == OFFLINE_MESSAGE || it == TIMEOUT_MESSAGE || it == "Track unavailable: NETWORK" }
    if (network) return fault(RecoveryClass.Timeout)
    if (sessionExpired) return fault(RecoveryClass.SessionExpired)
    if (sabr != null || status in 400..499) return fault(RecoveryClass.RejectedStream)
    return fault(RecoveryClass.ProviderUnavailable)
}

/** The limits every automatic retry shares. */
internal object RecoveryPolicy {
    /** Automatic attempts for one entry in one episode. */
    const val MAX_ATTEMPTS = 3
    /** Before the first, second and third attempt. */
    val delaysMs = listOf(0L, 1_000L, 3_000L)
    const val MAX_JITTER_MS = 250L
    /** Time an episode may spend trying while online. Waiting for a connection or for the provider's own delay is not counted. */
    const val EPISODE_MS = 60_000L
    /** A connection must stay validated this long before it is used. */
    const val STABLE_ONLINE_MS = 500L
    const val MAX_RETRY_AFTER_MS = 60_000L
    /** Unavailable entries a recommendation queue may pass over in a row. */
    const val MAX_AUTO_SKIPS = 3
    /** Playback this long after a recovery ends the episode; an earlier failure continues it. */
    const val SETTLED_MS = 10_000L
    /** While offline the connection is also checked on this interval, in case a change was not reported. */
    const val OFFLINE_POLL_MS = 15_000L
}

internal enum class RecoveryAction(val wire: String) {
    /** Prepare the same source again; the player keeps its position. */
    Prepare("prepare"),
    /** Look the stream up again. */
    Refresh("refresh"),
    /** Drop the attestation, then look the stream up again. */
    RefreshAttestation("attestation"),
    /** Look the stream up again without the profile that was rejected. */
    ExcludeProfile("exclude"),
}

internal sealed interface RecoveryStep {
    /** [holdMs] is a delay the provider asked for; [delayMs] is the app's own back-off. */
    data class Attempt(val action: RecoveryAction, val number: Int, val delayMs: Long, val holdMs: Long,
        val excluded: Set<String>) : RecoveryStep
    data class Stop(val failure: PlaybackFailureKind) : RecoveryStep
}

/**
 * The budget of one episode: every automatic retry for one entry draws on it, whatever the cause.
 * It replaces the separate stream, live and network budgets.
 */
internal class RecoveryEpisode(val entry: String) {
    var attempts = 0; private set
    var activeMs = 0L; private set
    private var attestationRefreshed = false
    private var streamRefreshed = false
    private val excluded = linkedSetOf<String>()

    fun spent(ms: Long) { activeMs += ms.coerceAtLeast(0) }
    /** An attempt that was not made after all, because the connection went away first. */
    fun refund() { if (attempts > 0) attempts-- }
    fun remainingMs() = (RecoveryPolicy.EPISODE_MS - activeMs).coerceAtLeast(0)

    /** The first step after the user asked to try again: one fresh look, whatever went wrong before. */
    fun manual(source: FaultSource): RecoveryStep.Attempt {
        attempts++
        val fresh = source != FaultSource.Device
        return RecoveryStep.Attempt(if (fresh) RecoveryAction.Refresh else RecoveryAction.Prepare, attempts, 0, 0, excluded.toSet())
    }

    fun next(fault: PlaybackFault, jitter: (Int) -> Long): RecoveryStep {
        when (fault.kind) {
            RecoveryClass.SessionExpired -> return RecoveryStep.Stop(PlaybackFailureKind.SessionExpired)
            RecoveryClass.UnsupportedMedia -> return RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay)
            RecoveryClass.LocalFileMissing -> return RecoveryStep.Stop(
                if (fault.source == FaultSource.Download) PlaybackFailureKind.DownloadInvalid else PlaybackFailureKind.LocalFileMissing)
            else -> Unit
        }
        val exhausted = RecoveryStep.Stop(if (fault.kind == RecoveryClass.SolverUnavailable) PlaybackFailureKind.SolverUnavailable
            else PlaybackFailureKind.CouldNotPlay)
        val asked = fault.retryAfterMs?.takeIf { fault.kind == RecoveryClass.RateLimited }
        if (asked != null && asked > RecoveryPolicy.MAX_RETRY_AFTER_MS) return exhausted
        if (attempts >= RecoveryPolicy.MAX_ATTEMPTS) return exhausted
        val base = if (asked != null) 0L else RecoveryPolicy.delaysMs[attempts]
        val delay = if (base == 0L) 0L else base + jitter(attempts).coerceIn(0L, RecoveryPolicy.MAX_JITTER_MS)
        if (activeMs + delay >= RecoveryPolicy.EPISODE_MS) return exhausted
        val action = when (fault.kind) {
            RecoveryClass.Offline, RecoveryClass.Timeout, RecoveryClass.RateLimited, RecoveryClass.SolverUnavailable -> RecoveryAction.Prepare
            // A profile that never supplied a token cannot benefit from minting it again.
            RecoveryClass.AttestationExpired -> if (!attestationRefreshed && fault.profile?.endsWith("__nopo") != true) {
                attestationRefreshed = true; RecoveryAction.RefreshAttestation } else exclude(fault.profile)
            RecoveryClass.RejectedStream -> if (fault.httpStatus in setOf(403, 410) && !streamRefreshed && fault.source != FaultSource.Live) {
                streamRefreshed = true; RecoveryAction.Refresh } else exclude(fault.profile)
            else -> RecoveryAction.Refresh
        }
        attempts++
        return RecoveryStep.Attempt(action, attempts, delay, asked?.coerceAtLeast(0L) ?: 0L, excluded.toSet())
    }

    private fun exclude(profile: String?): RecoveryAction =
        if (!profile.isNullOrBlank() && excluded.add(profile)) RecoveryAction.ExcludeProfile else RecoveryAction.Refresh
}

internal sealed interface AttemptOutcome {
    /** The player was asked to load the entry again; it will report playback or another failure. */
    data object Applied : AttemptOutcome
    /** The entry is no longer selected; nothing was changed. */
    data object Obsolete : AttemptOutcome
    /** The attempt failed before it reached the player. */
    data class Failed(val fault: PlaybackFault) : AttemptOutcome
}

/** What controllers are told: retrying ([recovery]), stopped ([failure]), held by a pause, or nothing. */
internal data class RecoverySignal(val recovery: RecoveryReason? = null, val failure: PlaybackFailureKind? = null,
    val held: Boolean = false)

/** The player and the device, as recovery needs them. Every call is made on the coordinator's thread. */
internal interface RecoveryHost {
    fun wantsPlayback(): Boolean
    /** A validated connection is present. */
    fun online(): Boolean
    suspend fun attempt(entry: String, step: RecoveryStep.Attempt): AttemptOutcome
    /** Moves to the next entry when [entry] was added by recommendations and one follows. */
    fun skipUnavailable(entry: String): Boolean
    fun publish(signal: RecoverySignal)
    fun event(text: String)
}

/**
 * The single place that retries playback. One episode exists at a time, for the selected entry;
 * a failure that arrives while it is waiting or trying is the same failure seen twice and starts
 * nothing. A pause holds the episode, another entry ends it, and nothing here starts playback the
 * user did not ask for. Used on one thread (the main thread in the service).
 */
internal class RecoveryCoordinator(private val scope: CoroutineScope, private val clock: () -> Long,
    private val wait: suspend (Long) -> Unit, private val jitter: (Int) -> Long, private val host: RecoveryHost) {
    private enum class Phase { None, Waiting, Trying, Awaiting, Recovered, Held, Stopped }
    private var episode: RecoveryEpisode? = null
    private var phase = Phase.None
    private var job: Job? = null
    private var fault: PlaybackFault? = null
    private var readyAt = 0L
    private var awaitingSince = 0L
    private var skips = 0
    private var flaps = 0
    private var connectivity: CompletableDeferred<Unit>? = null
    private var playedEntry: String? = null
    private var playedSince = 0L

    /** True while an episode is waiting, trying or watching the result of an attempt. */
    val active get() = phase == Phase.Waiting || phase == Phase.Trying || phase == Phase.Awaiting

    /** The player reported a failure for [entry]. */
    fun failed(entry: String, fault: PlaybackFault) {
        val current = episode
        val settled = phase == Phase.Recovered && clock() - readyAt >= RecoveryPolicy.SETTLED_MS
        settle()
        this.fault = fault
        when {
            current == null || current.entry != entry || settled -> { cancel(); episode = RecoveryEpisode(entry); run(fault, manual = false) }
            phase == Phase.Waiting || phase == Phase.Trying -> Unit
            phase == Phase.Stopped -> Unit
            else -> { if (phase == Phase.Awaiting) current.spent(clock() - awaitingSince); cancel(); run(fault, manual = false) }
        }
    }

    /** The player can play [entry]. */
    fun ready(entry: String) {
        if (playedEntry != entry) { playedEntry = entry; playedSince = clock() }
        val current = episode?.takeIf { it.entry == entry } ?: return
        if (phase == Phase.Recovered) return
        if (phase == Phase.Awaiting) current.spent(clock() - awaitingSince)
        cancel()
        phase = Phase.Recovered; readyAt = clock()
        host.event("recovery_done attempts=${current.attempts} active_ms=${current.activeMs}")
        host.publish(RecoverySignal())
    }

    /** Another entry is selected, or none. Whatever was being done for the previous one is dropped. */
    fun entryChanged(entry: String?) {
        if (playedEntry != entry) settle()
        if (episode == null || episode?.entry == entry) return
        if (active) host.event("recovery_cancelled reason=entry")
        clear()
    }

    /** Play or pause was asked for. A pause holds recovery; play after a hold or a stop starts over. */
    fun intentChanged(play: Boolean) {
        if (episode == null) return
        if (!play) {
            if (!active) return
            episode?.let { if (phase == Phase.Awaiting) it.spent(clock() - awaitingSince) }
            cancel(); phase = Phase.Held
            host.event("recovery_held")
            host.publish(RecoverySignal(held = true))
        } else if (phase == Phase.Held || phase == Phase.Stopped) retry()
    }

    /** The user asked to try again. False when there is nothing to retry. */
    fun retry(): Boolean {
        val current = episode ?: return false
        val last = fault ?: return false
        if (phase == Phase.Recovered || phase == Phase.None) return false
        if (active) return true
        skips = 0
        episode = RecoveryEpisode(current.entry)
        host.event("recovery_user_retry")
        run(last, manual = true)
        return true
    }

    /** The default network or its capabilities changed. */
    fun connectivityChanged() {
        if (!host.online()) flaps++
        connectivity?.complete(Unit)
    }

    fun clear() {
        cancel(); episode = null; fault = null; phase = Phase.None
        host.publish(RecoverySignal())
    }

    private fun cancel() { job?.cancel(); job = null }

    /** An entry that played for a while without failing ends a run of skipped entries. */
    private fun settle() {
        if (playedEntry != null && clock() - playedSince >= RecoveryPolicy.SETTLED_MS) skips = 0
        playedEntry = null
    }

    private fun reason(fault: PlaybackFault) = if (fault.source == FaultSource.Live) RecoveryReason.LiveProfile else RecoveryReason.ProviderStream

    private fun run(first: PlaybackFault, manual: Boolean) {
        val current = episode ?: return
        phase = Phase.Trying
        job = scope.launch {
            var fault = first
            var forced = manual
            while (true) {
                val remote = fault.source == FaultSource.Provider || fault.source == FaultSource.Live
                val terminal = fault.kind == RecoveryClass.SessionExpired || fault.kind == RecoveryClass.UnsupportedMedia ||
                    fault.kind == RecoveryClass.LocalFileMissing
                if (!forced && terminal) { stop(current, fault, current.next(fault, jitter) as RecoveryStep.Stop); return@launch }
                if (!host.wantsPlayback()) { hold(); return@launch }
                if (remote && !host.online()) {
                    phase = Phase.Waiting
                    host.event("recovery_wait_online class=${fault.kind.wire}")
                    host.publish(RecoverySignal(recovery = RecoveryReason.Network))
                    awaitStableOnline()
                    phase = Phase.Trying
                    if (!host.wantsPlayback()) { hold(); return@launch }
                }
                val step = if (forced) current.manual(fault.source) else current.next(fault, jitter)
                forced = false
                if (step !is RecoveryStep.Attempt) { stop(current, fault, step as RecoveryStep.Stop); return@launch }
                host.publish(RecoverySignal(recovery = reason(fault)))
                host.event("recovery class=${fault.kind.wire} attempt=${step.number} action=${step.action.wire} " +
                    "delay_ms=${step.delayMs} hold_ms=${step.holdMs} active_ms=${current.activeMs}")
                if (step.holdMs > 0) wait(step.holdMs)
                val started = clock()
                if (step.delayMs > 0) wait(step.delayMs)
                if (!host.wantsPlayback()) { current.spent(clock() - started); hold(); return@launch }
                if (remote && !host.online()) {
                    // The connection went away during the delay: the attempt was not made.
                    current.spent(clock() - started); current.refund()
                    fault = fault.copy(kind = RecoveryClass.Offline); continue
                }
                val outcome = withTimeoutOrNull(current.remainingMs().coerceAtLeast(1)) { host.attempt(current.entry, step) }
                current.spent(clock() - started)
                when (outcome) {
                    null -> { stop(current, fault, RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay)); return@launch }
                    AttemptOutcome.Obsolete -> { host.event("recovery_obsolete"); clear(); return@launch }
                    is AttemptOutcome.Failed -> { fault = outcome.fault; this@RecoveryCoordinator.fault = fault }
                    AttemptOutcome.Applied -> {
                        // The player answers with ready() or failed(). If it does neither, the episode still ends in time.
                        phase = Phase.Awaiting; awaitingSince = clock()
                        wait(current.remainingMs().coerceAtLeast(1))
                        current.spent(clock() - awaitingSince)
                        stop(current, fault, RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay)); return@launch
                    }
                }
            }
        }
    }

    private fun hold() {
        phase = Phase.Held
        host.event("recovery_held")
        host.publish(RecoverySignal(held = true))
    }

    private fun stop(current: RecoveryEpisode, fault: PlaybackFault, step: RecoveryStep.Stop) {
        host.event("recovery_stop class=${fault.kind.wire} attempts=${current.attempts} active_ms=${current.activeMs}")
        if (step.failure == PlaybackFailureKind.CouldNotPlay && skips < RecoveryPolicy.MAX_AUTO_SKIPS && host.wantsPlayback()) {
            skips++
            val count = skips
            // Moving on ends this episode through entryChanged(); nothing may follow the call.
            if (host.skipUnavailable(current.entry)) { host.event("recovery_skip count=$count"); return }
            skips--
        }
        phase = Phase.Stopped
        host.publish(RecoverySignal(failure = step.failure))
    }

    private suspend fun awaitStableOnline() {
        while (true) {
            while (!host.online()) {
                val signal = CompletableDeferred<Unit>().also { connectivity = it }
                withTimeoutOrNull(RecoveryPolicy.OFFLINE_POLL_MS) { signal.await() }
            }
            val seen = flaps
            wait(RecoveryPolicy.STABLE_ONLINE_MS)
            if (host.online() && flaps == seen) return
        }
    }
}

/** A failure of the solver process: it could not be started, died, or refused the request. */
internal class SolverUnavailableException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
