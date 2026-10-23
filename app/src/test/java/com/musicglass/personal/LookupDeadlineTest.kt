package com.musicglass.personal

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Q18-103: a song chosen offline never started after the network came back. Its lookup hung after the player responses,
 * the lookup's deadline ended in a CancellationException, and the deferred source took that for its own release, so no
 * failure was ever raised and recovery never began its wait for a connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LookupDeadlineTest {
    private val caller = MediaSource.MediaSourceCaller { _: MediaSource, _: Timeline -> }
    private fun prepared(lookup: suspend () -> MediaSource) =
        DeferredProviderSource(MediaItem.fromUri("catalog://fixtureA001"), lookup).also {
            it.prepareSource(caller, PlayerId.UNSET, DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build()) }
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test fun aLookupPastItsDeadlineFailsInsteadOfBufferingForever() {
        val source = prepared { withTimeout(30_000) { awaitCancellation() } }
        advance(5)
        source.maybeThrowSourceInfoRefreshError() // still looking up: no failure yet
        advance(30)
        val error = assertThrows(IOException::class.java) { source.maybeThrowSourceInfoRefreshError() }
        assertTrue("the cause is kept for recovery", generateSequence<Throwable>(error) { it.cause }.any { it.javaClass.simpleName == "TimeoutCancellationException" })
    }

    @Test fun releasingTheSourceIsNotAFailure() {
        var cancelled = false
        val source = prepared { try { awaitCancellation() } finally { cancelled = true } }
        advance(1)
        source.releaseSource(caller)
        advance(1)
        assertTrue(cancelled)
        source.maybeThrowSourceInfoRefreshError() // released: nothing to report
    }

    @Test fun theSharedDeadlineGivesAFailureNotACancellation() = runBlocking {
        val timedOut = withinLookupDeadline<String>(50) { awaitCancellation() }
        assertTrue(timedOut.exceptionOrNull() is StreamLookupTimeoutException)
        assertEquals("fixture", withinLookupDeadline(1_000) { Result.success("fixture") }.getOrThrow())
        val failed = IllegalStateException("fixture")
        assertSame("a lookup's own failure is kept", failed, withinLookupDeadline<String>(1_000) { Result.failure(failed) }.exceptionOrNull())
    }

    @Test fun aTimedOutLookupWaitsForTheConnectionOfflineAndRetriesOnline() {
        val cause = IOException("The selected YouTube track could not be prepared", StreamLookupTimeoutException(30_000))
        val causes = generateSequence<Throwable>(cause) { it.cause }.toList()
        assertEquals(RecoveryClass.Offline, classifyFault("ERROR_CODE_IO_UNSPECIFIED", causes, FaultSource.Provider, online = false, sessionExpired = false).kind)
        assertEquals(RecoveryClass.Timeout, classifyFault("ERROR_CODE_IO_UNSPECIFIED", causes, FaultSource.Provider, online = true, sessionExpired = false).kind)
    }
}
