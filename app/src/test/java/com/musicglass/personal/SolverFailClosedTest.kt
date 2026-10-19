package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.Looper
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Q08, ADR-Q05: challenges are solved in the solver process or not at all. A bridge that cannot be installed,
 * and a solver that keeps dying, both end as a typed failure; neither moves the work into the process that plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SolverFailClosedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun runtime(requests: AtomicInteger, bridge: ((com.metrolist.innertubex.extraction.InnerTubeExtractor, CipherBackend) -> Unit)?) =
        RuntimeDependencies(
            networkConditions = { NetworkConditions(true, false, false) },
            storageCapacity = { StorageCapacity(0, 1_000_000) },
            batteryPercent = { 80 },
            httpCalls = OkHttpClient.Builder().addInterceptor { requests.incrementAndGet(); throw IOException("Unexpected outbound fixture request") }.build(),
            clocks = FakeClock(), dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
            providerTransport = { OriginalAudioEngine.create { config { addInterceptor { requests.incrementAndGet(); throw IOException("Unexpected provider HTTP request") } } } },
            startProviderBackgroundWork = false, wait = {}).let { if (bridge == null) it else it.copy(installSolverBridge = bridge) }

    @Test fun aBridgeThatCannotBeInstalledRefusesOnlineResolutionWithATypedFailure() = runBlocking {
        val requests = AtomicInteger()
        val provider = AppContainer(context, runtime(requests) { _, _ -> throw NoSuchFieldException("cipherService") }).provider
        try {
            assertFalse(provider.cipherIsolated)
            val failure = withTimeout(5_000) { provider.resolve("fixtureA001") }.exceptionOrNull()
            val causes = generateSequence(failure) { it.cause }.toList()
            assertTrue("The failure carries its type for recovery", causes.any { it is SolverUnavailableException })
            assertEquals(RecoveryClass.SolverUnavailable,
                classifyFault("ERROR_CODE_IO_UNSPECIFIED", causes, FaultSource.Provider, online = true, sessionExpired = false).kind)
            assertEquals("Nothing was fetched or solved in this process", 0, requests.get())
            assertTrue(PlaybackEventLog(context).recent().any { "solver_bridge_unavailable NoSuchFieldException" in it })
        } finally { provider.close() }
    }

    @Test fun thePinnedLibraryAcceptsTheBridge() {
        // The reflection seam is still in use (innertubex has no public way to supply a cipher); this is its contract.
        val provider = AppContainer(context, runtime(AtomicInteger(), null)).provider
        try { assertTrue(provider.cipherIsolated) } finally { provider.close() }
    }

    /** Stands in for the solver process: answers, or dies the way a crashed process does. */
    private class FakeSolver : Binder() {
        @Volatile var deathsLeft = 0
        @Volatile var alive = true
        val transactions = AtomicInteger()
        override fun isBinderAlive() = alive
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Only the request under test is scripted and counted. Robolectric gives this binder to every client in
            // the process, and a provider left by an earlier test may still be asking for something else.
            if (code != SolverProtocol.PROCESS_N) { reply?.writeInt(SolverProtocol.STATUS_OK); reply?.writeString(null); return true }
            transactions.incrementAndGet()
            if (deathsLeft > 0) { deathsLeft--; alive = false; throw DeadObjectException() }
            alive = true
            data.enforceInterface(SolverProtocol.DESCRIPTOR)
            reply?.writeInt(SolverProtocol.STATUS_OK); reply?.writeString("https://manifest.example/videoplayback?n=solved")
            return true
        }
    }

    private fun client(solver: FakeSolver, events: MutableList<String>): RemoteCipherClient {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, SolverService::class.java), solver)
        return RemoteCipherClient(app, onEvent = { synchronized(events) { events += it } })
    }

    /** The client connects through the main looper, so the test keeps it turning while the request runs elsewhere. */
    private fun <T> request(block: suspend () -> T): Result<T> {
        @OptIn(DelicateCoroutinesApi::class)
        val job = GlobalScope.async(Dispatchers.IO) { runCatching { block() } }
        val deadline = System.currentTimeMillis() + 30_000
        while (!job.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "The request did not finish" }
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        return runBlocking { job.await() }
    }

    @Test fun aSolverThatDiesOnceIsReplacedAndTheRequestAnswered() {
        val events = mutableListOf<String>(); val solver = FakeSolver().apply { deathsLeft = 1 }
        val client = client(solver, events)
        assertEquals("https://manifest.example/videoplayback?n=solved",
            request { client.processNParameter("player", "https://manifest.example/videoplayback?n=x") }.getOrThrow())
        assertEquals(2, solver.transactions.get())
        assertEquals(1, events.count { it == "solver_died" })
        assertEquals(2, events.count { it == "solver_connected" })
        assertTrue(events.any { it.startsWith("solver_call_lost") })
    }

    @Test fun aSolverThatKeepsDyingEndsInATypedFailureAfterABoundedNumberOfStarts() {
        val events = mutableListOf<String>(); val solver = FakeSolver().apply { deathsLeft = Int.MAX_VALUE }
        val client = client(solver, events)
        val failures = (1..4).map { request { client.processNParameter("player", "https://manifest.example/videoplayback?n=x") }.exceptionOrNull() }
        assertTrue("Every failure is the typed one", failures.all { it is SolverUnavailableException })
        // Two attempts for the first request, one for the second; the third death pauses the solver.
        assertEquals(events.toString()+failures.map {it?.message}, 3, solver.transactions.get())
        assertEquals(3, events.count { it == "solver_died" })
        assertEquals(3, events.count { it == "solver_connected" })
        assertEquals("Solver paused after repeated crashes", failures.last()!!.message)
        // Nothing here touched a player: a dead solver is a failed request and no more.
        assertEquals(RecoveryClass.SolverUnavailable, classifyFault("ERROR_CODE_IO_UNSPECIFIED",
            generateSequence<Throwable>(IllegalStateException("Could not prepare this track", failures.last())) { it.cause }.toList(),
            FaultSource.Provider, online = true, sessionExpired = false).kind)
        assertEquals("Each typed failure is counted for the resolver", 4L, client.unavailableCount)
    }

    /** Q18-022: the library reports a solver that is down as a generic failure; the resolver puts the type back. */
    @Test fun aLookupDuringWhichTheSolverFailedIsClassedAsResolverUnavailable() {
        val generic = IllegalStateException("Track unavailable: OTHER")
        val kept = keepSolverCause(generic, solverFailed = true)
        assertTrue(kept is SolverUnavailableException); assertSame(generic, kept.cause)
        val chain = generateSequence<Throwable>(java.io.IOException("Source error", kept)) { it.cause }.toList()
        assertEquals(RecoveryClass.SolverUnavailable, classifyFault("ERROR_CODE_IO_UNSPECIFIED", chain, FaultSource.Provider, online = true, sessionExpired = false).kind)
        assertSame("A failure without a solver failure is left as it is", generic, keepSolverCause(generic, solverFailed = false))
        val typed = SolverUnavailableException("Solver paused after repeated crashes")
        val already = IllegalStateException("Could not prepare this track", typed)
        assertSame("Not wrapped twice", already, keepSolverCause(already, solverFailed = true))
        assertEquals("The class without the solver failure is still a timeout", RecoveryClass.Timeout,
            classifyFault("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", listOf(generic), FaultSource.Provider, online = true, sessionExpired = false).kind)
    }
}
