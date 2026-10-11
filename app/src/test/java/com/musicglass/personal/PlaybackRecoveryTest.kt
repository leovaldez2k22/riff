package com.musicglass.personal

import com.metrolist.innertubex.sabr.SabrFailureKind
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Q07: one place decides every automatic retry. Time is virtual throughout. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryTest {
    private fun classify(code: String = "ERROR_CODE_IO_UNSPECIFIED", vararg causes: Throwable, source: FaultSource = FaultSource.Provider,
        online: Boolean = true, expired: Boolean = false, status: Int? = null) =
        // The whole cause chain, as the service passes it.
        classifyFault(code, causes.flatMap { generateSequence(it) { cause -> cause.cause } }, source, online, expired, status).kind

    // ---- Classification ----

    @Test fun everyClassIsReachedByItsOwnSignal() {
        assertEquals(RecoveryClass.Offline, classify("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", UnknownHostException("host"), online = false))
        assertEquals(RecoveryClass.Offline, classify(causes = arrayOf(IllegalStateException(OFFLINE_MESSAGE)), online = false))
        assertEquals(RecoveryClass.Timeout, classify("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
        assertEquals(RecoveryClass.Timeout, classify(causes = arrayOf(IOException("x", SocketTimeoutException()))))
        assertEquals(RecoveryClass.Timeout, classify(causes = arrayOf(IllegalStateException("Track unavailable: NETWORK"))))
        assertEquals(RecoveryClass.Timeout, classify(causes = arrayOf(IllegalStateException(TIMEOUT_MESSAGE))))
        // SABR wraps the name of its cause in the message.
        assertEquals(RecoveryClass.Timeout, classify(causes = arrayOf(SabrPlaybackFailure("SABR failed (UnknownHostException)", null))))
        assertEquals(RecoveryClass.RejectedStream, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 403))
        assertEquals(RecoveryClass.RejectedStream, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 410))
        assertEquals(RecoveryClass.RejectedStream, classify(causes = arrayOf(SabrPlaybackFailure("x", SabrFailureKind.PROTOCOL))))
        assertEquals(RecoveryClass.RejectedStream, classify(causes = arrayOf(SabrPlaybackFailure("x", SabrFailureKind.RELOAD_PLAYER))))
        assertEquals(RecoveryClass.AttestationExpired, classify(causes = arrayOf(IOException("w", SabrPlaybackFailure("x", SabrFailureKind.ATTESTATION_REQUIRED)))))
        assertEquals(RecoveryClass.SessionExpired, classify(status = 401))
        assertEquals(RecoveryClass.SessionExpired, classify(causes = arrayOf(IllegalStateException(SESSION_EXPIRED_MESSAGE))))
        assertEquals(RecoveryClass.SessionExpired, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 403, expired = true))
        assertEquals(RecoveryClass.RateLimited, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 429))
        assertEquals(RecoveryClass.ProviderUnavailable, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 503))
        assertEquals(RecoveryClass.ProviderUnavailable, classify(causes = arrayOf(IllegalStateException("Could not prepare this track (JsonException)"))))
        assertEquals(RecoveryClass.SolverUnavailable, classify(causes = arrayOf(IllegalStateException("w", SolverUnavailableException("Solver paused after repeated crashes")))))
        assertEquals(RecoveryClass.UnsupportedMedia, classify(causes = arrayOf(IllegalStateException("Track unavailable: AGE_RESTRICTED"))))
        assertEquals(RecoveryClass.UnsupportedMedia, classify(causes = arrayOf(IllegalStateException("No supported audio stream is available for this track"))))
        assertEquals(RecoveryClass.UnsupportedMedia, classify("ERROR_CODE_DECODING_FAILED"))
        assertEquals(RecoveryClass.LocalFileMissing, classify("ERROR_CODE_IO_FILE_NOT_FOUND", FileNotFoundException(), source = FaultSource.Device))
        assertEquals(RecoveryClass.LocalFileMissing, classify("ERROR_CODE_IO_UNSPECIFIED", source = FaultSource.Download))
        assertEquals(RecoveryClass.UnsupportedMedia, classify("ERROR_CODE_DECODER_INIT_FAILED", source = FaultSource.Device))
    }

    @Test fun whatTheItemIsComesBeforeTheConnectionAndTheConnectionBeforeTheProvider() {
        // A song that cannot be played is not waited for, even without a connection.
        assertEquals(RecoveryClass.UnsupportedMedia, classify(causes = arrayOf(IllegalStateException("Track unavailable: UNAVAILABLE")), online = false))
        // Without a validated connection nothing the provider said is trusted.
        assertEquals(RecoveryClass.Offline, classify("ERROR_CODE_IO_BAD_HTTP_STATUS", status = 403, online = false))
        assertEquals(RecoveryClass.Offline, classify(causes = arrayOf(SabrPlaybackFailure("x", SabrFailureKind.ATTESTATION_REQUIRED)), online = false))
        // An outage with an expired session is still an outage; a file on the device never needs the network.
        assertEquals(RecoveryClass.Timeout, classify("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", expired = true))
        assertEquals(RecoveryClass.LocalFileMissing, classify("ERROR_CODE_IO_FILE_NOT_FOUND", source = FaultSource.Device, online = false))
    }

    @Test fun aFaultCarriesNoTextFromTheFailure() {
        val fault = classifyFault("ERROR_CODE_IO_BAD_HTTP_STATUS", listOf(IOException("https://host/videoplayback?sig=SECRET")),
            FaultSource.Provider, true, false, 403, null, "WEB_REMIX")
        assertFalse(fault.toString().contains("SECRET")); assertFalse(fault.toString().contains("https"))
    }

    // ---- The budget of one episode ----

    private fun fault(kind: RecoveryClass, source: FaultSource = FaultSource.Provider, status: Int? = null, retryAfter: Long? = null, profile: String? = "p1") =
        PlaybackFault(kind, source, status, retryAfter, profile)

    @Test fun threeAttemptsWithDelaysOfOneThenThreeSecondsAndBoundedJitter() {
        val episode = RecoveryEpisode("e")
        val steps = (1..3).map { episode.next(fault(RecoveryClass.Timeout)) { 10_000 } as RecoveryStep.Attempt }
        assertEquals(listOf(0L, 1_250L, 3_250L), steps.map { it.delayMs })
        assertEquals(listOf(1, 2, 3), steps.map { it.number })
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay), episode.next(fault(RecoveryClass.Timeout)) { 0 })
        val negative = RecoveryEpisode("e")
        negative.next(fault(RecoveryClass.Timeout)) { -5 }
        assertEquals(1_000L, (negative.next(fault(RecoveryClass.Timeout)) { -5 } as RecoveryStep.Attempt).delayMs)
    }

    @Test fun everyCauseDrawsOnTheSameThreeAttempts() {
        val episode = RecoveryEpisode("e")
        assertTrue(episode.next(fault(RecoveryClass.Timeout)) { 0 } is RecoveryStep.Attempt)
        assertTrue(episode.next(fault(RecoveryClass.RejectedStream, status = 403)) { 0 } is RecoveryStep.Attempt)
        assertTrue(episode.next(fault(RecoveryClass.AttestationExpired)) { 0 } is RecoveryStep.Attempt)
        for (kind in listOf(RecoveryClass.Timeout, RecoveryClass.RejectedStream, RecoveryClass.AttestationExpired, RecoveryClass.ProviderUnavailable,
            RecoveryClass.Offline, RecoveryClass.RateLimited))
            assertEquals("$kind", RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay), episode.next(fault(kind)) { 0 })
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.SolverUnavailable), episode.next(fault(RecoveryClass.SolverUnavailable)) { 0 })
    }

    @Test fun aRejectedStreamIsRefreshedOnceAndThenItsProfileIsExcluded() {
        for (status in listOf(403, 410)) {
            val episode = RecoveryEpisode("e")
            val first = episode.next(fault(RecoveryClass.RejectedStream, status = status)) { 0 } as RecoveryStep.Attempt
            assertEquals(RecoveryAction.Refresh, first.action); assertTrue(first.excluded.isEmpty())
            val second = episode.next(fault(RecoveryClass.RejectedStream, status = status)) { 0 } as RecoveryStep.Attempt
            assertEquals(RecoveryAction.ExcludeProfile, second.action); assertEquals(setOf("p1"), second.excluded)
            val third = episode.next(fault(RecoveryClass.RejectedStream, status = status, profile = "p2")) { 0 } as RecoveryStep.Attempt
            assertEquals(setOf("p1", "p2"), third.excluded)
        }
        // A live stream has no stream to refresh: the rejected profile is excluded at once.
        val live = RecoveryEpisode("e").next(fault(RecoveryClass.RejectedStream, FaultSource.Live, 403)) { 0 } as RecoveryStep.Attempt
        assertEquals(RecoveryAction.ExcludeProfile, live.action)
    }

    @Test fun attestationIsRefreshedOnceUnlessTheProfileNeverHadAToken() {
        val episode = RecoveryEpisode("e")
        assertEquals(RecoveryAction.RefreshAttestation, (episode.next(fault(RecoveryClass.AttestationExpired)) { 0 } as RecoveryStep.Attempt).action)
        assertEquals(RecoveryAction.ExcludeProfile, (episode.next(fault(RecoveryClass.AttestationExpired)) { 0 } as RecoveryStep.Attempt).action)
        // The same profile again: nothing new to exclude, so a plain fresh look.
        assertEquals(RecoveryAction.Refresh, (episode.next(fault(RecoveryClass.AttestationExpired)) { 0 } as RecoveryStep.Attempt).action)
        val tokenless = RecoveryEpisode("e").next(fault(RecoveryClass.AttestationExpired, profile = "VISIONOS_SABR__nopo")) { 0 } as RecoveryStep.Attempt
        assertEquals(RecoveryAction.ExcludeProfile, tokenless.action); assertEquals(setOf("VISIONOS_SABR__nopo"), tokenless.excluded)
    }

    @Test fun theProvidersOwnDelayIsHonouredUpToSixtySeconds() {
        val asked = RecoveryEpisode("e").next(fault(RecoveryClass.RateLimited, status = 429, retryAfter = 60_000)) { 99 } as RecoveryStep.Attempt
        assertEquals(60_000L, asked.holdMs); assertEquals(0L, asked.delayMs)
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay),
            RecoveryEpisode("e").next(fault(RecoveryClass.RateLimited, status = 429, retryAfter = 60_001)) { 0 })
        // Without a stated delay the usual back-off applies.
        val plain = RecoveryEpisode("e")
        plain.next(fault(RecoveryClass.RateLimited, status = 429)) { 0 }
        assertEquals(1_000L, (plain.next(fault(RecoveryClass.RateLimited, status = 429)) { 0 } as RecoveryStep.Attempt).delayMs)
    }

    @Test fun someFailuresAreNeverRetried() {
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.SessionExpired), RecoveryEpisode("e").next(fault(RecoveryClass.SessionExpired)) { 0 })
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay), RecoveryEpisode("e").next(fault(RecoveryClass.UnsupportedMedia)) { 0 })
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.LocalFileMissing), RecoveryEpisode("e").next(fault(RecoveryClass.LocalFileMissing, FaultSource.Device)) { 0 })
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.DownloadInvalid), RecoveryEpisode("e").next(fault(RecoveryClass.LocalFileMissing, FaultSource.Download)) { 0 })
    }

    @Test fun anEpisodeEndsAfterSixtySecondsOfTrying() {
        val episode = RecoveryEpisode("e")
        episode.next(fault(RecoveryClass.Timeout)) { 0 }
        episode.spent(59_500)
        assertEquals(500L, episode.remainingMs())
        assertEquals(RecoveryStep.Stop(PlaybackFailureKind.CouldNotPlay), episode.next(fault(RecoveryClass.Timeout)) { 0 })
    }

    @Test fun failuresSayWhatTheUserCanDo() {
        fun actions(kind: PlaybackFailureKind) = PlaybackFailure(kind).actions()
        assertEquals("Could not play this item", PlaybackFailure(PlaybackFailureKind.CouldNotPlay).message())
        assertEquals(listOf(FailureAction.Retry, FailureAction.Skip), actions(PlaybackFailureKind.CouldNotPlay))
        assertEquals("Sign in again to access account content", PlaybackFailure(PlaybackFailureKind.SessionExpired).message())
        assertEquals(listOf(FailureAction.SignIn), actions(PlaybackFailureKind.SessionExpired))
        assertEquals("File unavailable on this device", PlaybackFailure(PlaybackFailureKind.LocalFileMissing).message())
        assertEquals("Download incomplete or unavailable", PlaybackFailure(PlaybackFailureKind.DownloadInvalid).message())
        assertEquals(listOf(FailureAction.Redownload, FailureAction.RemoveDownload), actions(PlaybackFailureKind.DownloadInvalid))
        assertEquals("Online playback resolver unavailable", PlaybackFailure(PlaybackFailureKind.SolverUnavailable).message())
        assertEquals(listOf(FailureAction.Retry), actions(PlaybackFailureKind.SolverUnavailable))
        assertEquals("Connection lost; waiting to reconnect", RecoveryReason.Network.notice())
        assertNull(RecoveryReason.ProviderStream.notice()); assertNull(RecoveryReason.LiveProfile.notice())
        PlaybackFailureKind.entries.forEach { assertEquals(it, PlaybackFailureKind.of(it.name)) }
        assertNull(PlaybackFailureKind.of("")); assertNull(PlaybackFailureKind.of(null))
    }

    // ---- The coordinator ----

    private class Host : RecoveryHost {
        var wants = true
        var isOnline = true
        var auto = false
        val events = mutableListOf<String>()
        val signals = mutableListOf<RecoverySignal>()
        val attempts = mutableListOf<Pair<String, RecoveryStep.Attempt>>()
        val attemptTimes = mutableListOf<Long>()
        var now: () -> Long = { 0 }
        var outcome: suspend (RecoveryStep.Attempt) -> AttemptOutcome = { AttemptOutcome.Applied }
        var onSkip: (String) -> Unit = {}
        var skipped = 0
        override fun wantsPlayback() = wants
        override fun online() = isOnline
        override suspend fun attempt(entry: String, step: RecoveryStep.Attempt): AttemptOutcome {
            attempts += entry to step; attemptTimes += now(); return outcome(step) }
        override fun skipUnavailable(entry: String): Boolean { if (!auto) return false; skipped++; onSkip(entry); return true }
        override fun publish(signal: RecoverySignal) { signals += signal }
        override fun event(text: String) { events += text }
        val signal get() = signals.last()
    }

    private fun TestScope.coordinator(host: Host, jitter: Long = 0): RecoveryCoordinator {
        host.now = { testScheduler.currentTime }
        return RecoveryCoordinator(backgroundScope, { testScheduler.currentTime }, { delay(it) }, { jitter }, host)
    }
    private fun TestScope.pass(ms: Long) { advanceTimeBy(ms); runCurrent() }
    private val timeout = fault(RecoveryClass.Timeout)
    private val offline = fault(RecoveryClass.Offline)

    @Test fun exhaustionStopsAfterThreeAttemptsAndStartsNothingElse() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout)
        assertEquals(1, host.attempts.size); assertEquals(RecoverySignal(recovery = RecoveryReason.ProviderStream), host.signal)
        recovery.failed("e", timeout); assertEquals(1, host.attempts.size)
        pass(999); assertEquals(1, host.attempts.size)
        pass(1); assertEquals(2, host.attempts.size)
        recovery.failed("e", timeout)
        pass(2_999); assertEquals(2, host.attempts.size)
        pass(1); assertEquals(3, host.attempts.size)
        recovery.failed("e", timeout)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
        assertFalse(recovery.active)
        // Further failures of the same entry, and time, start nothing.
        recovery.failed("e", timeout); recovery.failed("e", fault(RecoveryClass.RejectedStream, status = 403)); pass(600_000)
        assertEquals(3, host.attempts.size)
        assertEquals(listOf(0L, 1_000L, 4_000L), host.attemptTimes)
        assertTrue(host.events.any { it.startsWith("recovery_stop class=timeout attempts=3") })
    }

    @Test fun aFailureSeenTwiceStartsOneRecovery() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        val pending = CompletableDeferred<AttemptOutcome>()
        host.outcome = { pending.await() }
        recovery.failed("e", fault(RecoveryClass.RejectedStream, status = 403))
        // The same failure reported by a second path while the first attempt is still being made.
        repeat(5) { recovery.failed("e", fault(RecoveryClass.RejectedStream, status = 403)) }
        recovery.failed("e", timeout); recovery.failed("e", fault(RecoveryClass.AttestationExpired))
        pass(30_000)
        assertEquals(1, host.attempts.size)
        assertEquals(1, host.events.count { it.startsWith("recovery class=") })
    }

    @Test fun offlineWaitsForAConnectionThatStaysForHalfASecond() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host)
        recovery.failed("e", offline)
        assertEquals(RecoverySignal(recovery = RecoveryReason.Network), host.signal)
        // Waiting is not trying: an hour without a connection uses no attempt and ends nothing.
        pass(3_600_000)
        assertTrue(host.attempts.isEmpty()); assertTrue(recovery.active)
        host.isOnline = true; recovery.connectivityChanged()
        pass(499); assertTrue(host.attempts.isEmpty())
        pass(1)
        assertEquals(1, host.attempts.size)
        assertEquals(RecoveryAction.Prepare, host.attempts[0].second.action)
        assertEquals(RecoverySignal(recovery = RecoveryReason.ProviderStream), host.signal)
    }

    @Test fun aFlappingConnectionIsNotUsedUntilItSettles() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host)
        recovery.failed("e", offline)
        repeat(6) {
            host.isOnline = true; recovery.connectivityChanged(); pass(300)
            host.isOnline = false; recovery.connectivityChanged(); pass(300)
        }
        assertTrue(host.attempts.isEmpty())
        // Up, down and up again inside the half second: the first rise does not count.
        host.isOnline = true; recovery.connectivityChanged(); pass(200)
        host.isOnline = false; recovery.connectivityChanged()
        host.isOnline = true; recovery.connectivityChanged()
        pass(400); assertTrue(host.attempts.isEmpty())
        pass(700); assertEquals(1, host.attempts.size)
    }

    @Test fun aConnectionChangeThatWasNotReportedIsStillNoticed() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host)
        recovery.failed("e", offline)
        pass(1_000); host.isOnline = true
        pass(RecoveryPolicy.OFFLINE_POLL_MS + RecoveryPolicy.STABLE_ONLINE_MS)
        assertEquals(1, host.attempts.size)
    }

    @Test fun aConnectionLostDuringTheDelayCostsNoAttempt() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout); recovery.failed("e", timeout)
        pass(500); host.isOnline = false; recovery.connectivityChanged()
        pass(500)
        assertEquals(1, host.attempts.size); assertEquals(RecoverySignal(recovery = RecoveryReason.Network), host.signal)
        host.isOnline = true; recovery.connectivityChanged(); pass(500)
        // Offline has its own first step, so the refunded attempt is the second of three.
        pass(1_000)
        assertEquals(2, host.attempts.size); assertEquals(2, host.attempts[1].second.number)
    }

    @Test fun pauseWhileWaitingPreventsTheResumeAndPlayStartsOver() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host)
        recovery.failed("e", offline)
        host.wants = false; recovery.intentChanged(false)
        assertEquals(RecoverySignal(held = true), host.signal); assertFalse(recovery.active)
        host.isOnline = true; recovery.connectivityChanged(); pass(600_000)
        assertTrue("nothing resumes after a pause", host.attempts.isEmpty())
        host.wants = true; recovery.intentChanged(true)
        pass(500)
        assertEquals(1, host.attempts.size); assertEquals(RecoveryAction.Refresh, host.attempts[0].second.action)
    }

    @Test fun pauseDuringTheDelayCancelsTheAttempt() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout); recovery.failed("e", timeout)
        pass(400); host.wants = false; recovery.intentChanged(false)
        pass(60_000)
        assertEquals(1, host.attempts.size); assertEquals(RecoverySignal(held = true), host.signal)
    }

    @Test fun aPauseTheCoordinatorWasNotToldAboutStillHoldsIt() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout); recovery.failed("e", timeout)
        pass(400); host.wants = false
        pass(600)
        assertEquals(1, host.attempts.size); assertEquals(RecoverySignal(held = true), host.signal)
    }

    @Test fun aFailureWhilePausedIsHeldAndNeverRetried() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { wants = false }; val recovery = coordinator(host)
        recovery.failed("e", fault(RecoveryClass.RejectedStream, status = 403))
        pass(600_000)
        assertTrue(host.attempts.isEmpty()); assertEquals(RecoverySignal(held = true), host.signal)
        // What cannot be retried is said at once, paused or not.
        recovery.failed("f", fault(RecoveryClass.SessionExpired))
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.SessionExpired), host.signal)
    }

    @Test fun skipOrSwitchDuringARetryEndsIt() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout); recovery.failed("e", timeout)
        pass(500)
        recovery.entryChanged("other")
        assertEquals(RecoverySignal(), host.signal); assertFalse(recovery.active)
        pass(60_000)
        assertEquals(1, host.attempts.size)
        // The entry now selected has its own, full budget.
        recovery.failed("other", timeout)
        assertEquals("other" to 1, host.attempts.last().let { it.first to it.second.number })
        // A replacement made for the same entry is not a switch.
        recovery.entryChanged("other"); assertTrue(recovery.active)
    }

    @Test fun aCompletionForAnEntryTheUserLeftIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        val pending = CompletableDeferred<Unit>(); var applied = 0
        host.outcome = { pending.await(); applied++; AttemptOutcome.Applied }
        recovery.failed("e", fault(RecoveryClass.RejectedStream, status = 403))
        recovery.entryChanged("other")
        pending.complete(Unit); pass(1_000)
        assertEquals("the late answer must not reach the player", 0, applied)
        assertEquals(RecoverySignal(), host.signal)
        // The host can also find the entry gone by itself.
        host.outcome = { AttemptOutcome.Obsolete }
        recovery.failed("other", fault(RecoveryClass.RejectedStream, status = 403))
        assertFalse(recovery.active); assertEquals(RecoverySignal(), host.signal)
        assertTrue(host.events.contains("recovery_obsolete"))
        host.outcome = { AttemptOutcome.Applied }
        recovery.failed("third", timeout); assertEquals(1, host.attempts.last().second.number)
    }

    @Test fun aMissingDownloadIsAStorageFailureWithNoRetryAndNoSkip() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { auto = true }; val recovery = coordinator(host)
        recovery.failed("e", fault(RecoveryClass.LocalFileMissing, FaultSource.Download))
        pass(600_000)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.DownloadInvalid), host.signal)
        assertTrue(host.attempts.isEmpty()); assertEquals(0, host.skipped)
        // Asked by the user, the entry is looked up again; that uses the files only when they are complete.
        assertTrue(recovery.retry())
        assertEquals(RecoveryAction.Refresh, host.attempts.single().second.action)
        // A file on the device itself is only prepared again.
        recovery.failed("f", fault(RecoveryClass.LocalFileMissing, FaultSource.Device))
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.LocalFileMissing), host.signal)
        assertTrue(recovery.retry())
        assertEquals(RecoveryAction.Prepare, host.attempts.last().second.action)
    }

    @Test fun anExpiredSessionStopsRetriesAndSigningInStartsNothing() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", fault(RecoveryClass.SessionExpired))
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.SessionExpired), host.signal)
        // The session becomes valid again; nothing tells recovery, and nothing plays by itself.
        recovery.connectivityChanged(); pass(600_000)
        assertTrue(host.attempts.isEmpty())
        assertTrue(recovery.retry()); assertEquals(1, host.attempts.size)
    }

    @Test fun recommendationsPassOverAtMostThreeUnavailableEntriesInARow() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { auto = true }; val recovery = coordinator(host)
        host.onSkip = { recovery.entryChanged("next-of-$it") }
        val gone = fault(RecoveryClass.UnsupportedMedia)
        recovery.failed("a", gone); recovery.failed("b", gone); recovery.failed("c", gone)
        assertEquals(3, host.skipped); assertEquals(RecoverySignal(), host.signal)
        recovery.failed("d", gone)
        assertEquals(3, host.skipped); assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
        // An entry that only became ready and failed again does not reset the count.
        recovery.entryChanged("e"); recovery.ready("e"); pass(2_000)
        recovery.failed("e", gone); assertEquals(3, host.skipped)
        // One that played for a while does.
        recovery.entryChanged("f"); recovery.ready("f"); pass(RecoveryPolicy.SETTLED_MS)
        recovery.entryChanged("g"); recovery.failed("g", gone)
        assertEquals(4, host.skipped)
        // Nothing is skipped for a user who paused.
        host.wants = false; recovery.failed("h", gone)
        assertEquals(4, host.skipped); assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
    }

    @Test fun anAuthoredCollectionStopsAtTheEntryThatFailed() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("a", fault(RecoveryClass.UnsupportedMedia))
        assertEquals(0, host.skipped); assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
        assertEquals(listOf(FailureAction.Retry, FailureAction.Skip), PlaybackFailure(host.signal.failure!!).actions())
    }

    @Test fun aRecoveryThatDoesNotLastContinuesTheSameEpisode() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        val rejected = fault(RecoveryClass.RejectedStream, status = 403)
        recovery.failed("e", rejected); pass(200)
        recovery.ready("e")
        assertEquals(RecoverySignal(), host.signal); assertFalse(recovery.active)
        assertTrue(host.events.contains("recovery_done attempts=1 active_ms=200"))
        // Ready, then rejected again two seconds later: still the same budget.
        pass(2_000); recovery.failed("e", rejected); pass(1_000)
        recovery.ready("e"); pass(2_000); recovery.failed("e", rejected); pass(3_000)
        assertEquals(listOf(1, 2, 3), host.attempts.map { it.second.number })
        recovery.ready("e"); pass(2_000); recovery.failed("e", rejected)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
        assertEquals(3, host.attempts.size)
    }

    @Test fun playbackThatLastedStartsAFreshEpisode() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout); recovery.ready("e")
        pass(RecoveryPolicy.SETTLED_MS)
        recovery.failed("e", timeout)
        assertEquals(listOf(1, 1), host.attempts.map { it.second.number })
        assertEquals(listOf(0L, RecoveryPolicy.SETTLED_MS), host.attemptTimes)
    }

    @Test fun anAttemptThePlayerNeverAnswersEndsTheEpisodeInTime() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", timeout)
        pass(59_999); assertTrue(recovery.active)
        pass(1)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal); assertEquals(1, host.attempts.size)
    }

    @Test fun aLookupThatHangsIsCutOffAtTheEpisodeLimit() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        host.outcome = { CompletableDeferred<AttemptOutcome>().await() }
        recovery.failed("e", fault(RecoveryClass.ProviderUnavailable))
        pass(60_000)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
        assertTrue(host.events.any { it.startsWith("recovery_stop class=provider attempts=1 active_ms=60000") })
    }

    @Test fun lookupsThatFailAreRetriedInsideTheSameBudget() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        host.outcome = { AttemptOutcome.Failed(fault(RecoveryClass.SolverUnavailable)) }
        recovery.failed("e", fault(RecoveryClass.ProviderUnavailable))
        pass(60_000)
        assertEquals(listOf(0L, 1_000L, 4_000L), host.attemptTimes)
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.SolverUnavailable), host.signal)
    }

    @Test fun aLongDelayAskedByTheProviderIsNotCountedAsTrying() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        recovery.failed("e", fault(RecoveryClass.RateLimited, status = 429, retryAfter = 45_000))
        pass(44_999); assertTrue(host.attempts.isEmpty())
        pass(1); assertEquals(1, host.attempts.size)
        recovery.failed("e", fault(RecoveryClass.RateLimited, status = 429, retryAfter = 45_000))
        pass(45_000)
        assertEquals(2, host.attempts.size)
        // Longer than a minute: the user decides when to try again.
        recovery.failed("e", fault(RecoveryClass.RateLimited, status = 429, retryAfter = 120_000))
        assertEquals(RecoverySignal(failure = PlaybackFailureKind.CouldNotPlay), host.signal)
    }

    @Test fun retryByTheUserGetsAFullBudgetAndIsHarmlessWhileOneIsRunning() = runTest(UnconfinedTestDispatcher()) {
        val host = Host(); val recovery = coordinator(host)
        assertFalse("nothing failed", recovery.retry())
        recovery.failed("e", timeout)
        assertTrue(recovery.retry()); assertEquals(1, host.attempts.size)
        recovery.failed("e", timeout); pass(1_000); recovery.failed("e", timeout); pass(3_000); recovery.failed("e", timeout)
        assertEquals(3, host.attempts.size)
        assertTrue(recovery.retry())
        assertEquals(4, host.attempts.size); assertEquals(1, host.attempts.last().second.number)
        assertEquals(RecoveryAction.Refresh, host.attempts.last().second.action)
        recovery.ready("e")
        assertFalse("playing again", recovery.retry())
    }

    @Test fun eventsCarryClassAttemptAndTimingOnly() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host, jitter = 100)
        recovery.failed("0123456789abcdef", offline)
        host.isOnline = true; recovery.connectivityChanged(); pass(500)
        recovery.failed("0123456789abcdef", fault(RecoveryClass.RejectedStream, status = 403, profile = "WEB_REMIX")); pass(1_100)
        recovery.ready("0123456789abcdef")
        val allowed = Regex("[a-z_]+( [a-z_]+=[a-z0-9_]+)*")
        host.events.forEach { assertTrue(it, allowed.matches(it)) }
        assertTrue(host.events.none { it.contains("WEB_REMIX") || it.contains("0123456789abcdef") })
        assertEquals("recovery class=rejected attempt=2 action=refresh delay_ms=1100 hold_ms=0 active_ms=0", host.events.first { it.contains("attempt=2") })
    }

    @Test fun clearingEndsEverything() = runTest(UnconfinedTestDispatcher()) {
        val host = Host().apply { isOnline = false }; val recovery = coordinator(host)
        recovery.failed("e", offline)
        recovery.clear()
        host.isOnline = true; recovery.connectivityChanged(); pass(60_000)
        assertTrue(host.attempts.isEmpty()); assertEquals(RecoverySignal(), host.signal)
        assertFalse(recovery.retry())
    }
}
