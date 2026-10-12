package com.musicglass.personal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Q11: a cancelled caller stays cancelled; a cancellation from inside, with the caller still wanted, is a failure to report. */
class CancellationTest {
    @Test fun aValueAndAnOrdinaryFailureAreResults() = runBlocking {
        assertEquals(7, suspendCatching { 7 }.getOrNull())
        val failure = suspendCatching<Int> { error("disk") }
        assertTrue(failure.isFailure); assertEquals("disk", failure.exceptionOrNull()!!.message)
    }

    @Test fun aCancelledCallerStaysCancelledAndNothingAfterItRuns() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var after = false; var result: Result<Unit>? = null
        val caller = scope.async { result = suspendCatching { awaitCancellation() }; after = true }
        caller.cancel(); caller.join()
        assertTrue(caller.isCancelled)
        assertNull("cancellation never becomes a result", result)
        assertFalse(after)
    }

    @Test fun theSamePlaceWithRunCatchingWouldHaveCarriedOn() = runBlocking {
        // What the helper replaces, kept as a record of the fault: the cancelled work continues past the call.
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var after = false
        val caller = scope.async { runCatching { awaitCancellation() }; after = true }
        caller.cancel(); caller.join()
        assertTrue(after)
    }

    @Test fun aTimeoutInsideIsAFailureWhenTheCallerIsStillWanted() = runBlocking {
        val result = suspendCatching { withTimeout(10) { awaitCancellation() } }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test fun waitingOnWorkThatSomeoneElseStoppedIsAFailureNotACancellation() = runBlocking {
        // The provider's library cancels a request in flight when it refreshes its session.
        val stopped = CompletableDeferred<Int>().also { it.cancel(CancellationException("session refreshed")) }
        val result = suspendCatching { stopped.await() }
        assertTrue(result.isFailure)
        assertEquals("session refreshed", result.exceptionOrNull()!!.message)
    }
}
