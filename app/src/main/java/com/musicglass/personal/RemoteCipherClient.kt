package com.musicglass.personal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Main-process side of [SolverService]. Binds on first use, retries a call once if the solver died
 * during it, stops restarting it after repeated crashes ([SolverHealth]), and unbinds after
 * [idleMs] without requests so its memory is released.
 */
internal class RemoteCipherClient(context: Context, private val onEvent: (String) -> Unit,
                                  private val idleMs: Long = 10 * 60_000L) : CipherBackend {
    private val app = context.applicationContext
    private val health = SolverHealth(SystemClock::elapsedRealtime)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    @Volatile private var binder: IBinder? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null
    private var bindGeneration = 0L

    override suspend fun initialize() { call(SolverProtocol.INITIALIZE) {} }

    override suspend fun preloadPlayerCode(playerUrl: String) { call(SolverProtocol.PRELOAD) { it.writeString(playerUrl) } }

    override suspend fun prewarmEjs() { call(SolverProtocol.PREWARM) {} }

    override suspend fun processFormats(playerUrl: String, formats: List<Format>): List<Format> {
        if (formats.isEmpty()) return formats
        val encoded = SolverProtocol.encodeFormats(formats)
        val result = call(SolverProtocol.PROCESS_FORMATS) { it.writeString(playerUrl); it.writeString(encoded) }
        return SolverProtocol.decodeFormats(checkNotNull(result) { "Solver returned no formats" })
    }

    override suspend fun processNParameter(playerUrl: String, url: String): String? =
        call(SolverProtocol.PROCESS_N) { it.writeString(playerUrl); it.writeString(url) }

    private suspend fun call(code: Int, write: (Parcel) -> Unit): String? = withContext(Dispatchers.IO) {
        var lost: DeadObjectException? = null
        repeat(2) { attempt ->
            val target = connect()
            try {
                return@withContext transact(target, code, write).also { scheduleIdleUnbind() }
            } catch (died: DeadObjectException) {
                // Retry once on a fresh process; the death itself is counted in connect()/onSolverDeath().
                onEvent("solver_call_lost code=${code - SolverProtocol.INITIALIZE} attempt=$attempt")
                lost = died
                awaitProcessGone(target)
            } catch (failed: RemoteException) {
                throw IOException("Solver request failed (${failed.javaClass.simpleName})", failed)
            }
        }
        throw IOException("Solver process died during the request", lost)
    }

    /**
     * A crashing solver is held by the crash reporter (debuggerd) for a few hundred ms while it writes
     * the tombstone, and Android keeps handing out its binder until the process really exits. Wait
     * for that before reconnecting, or the retry lands on the same dying process (seen on device).
     */
    private suspend fun awaitProcessGone(target: IBinder) {
        withTimeoutOrNull(5_000) { while (target.isBinderAlive) delay(50) }
        delay(250) // let the system process the death and schedule a fresh solver
    }

    private fun transact(target: IBinder, code: Int, write: (Parcel) -> Unit): String? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SolverProtocol.DESCRIPTOR)
            write(data)
            if (!target.transact(code, data, reply, 0)) throw IOException("Solver rejected the request")
            val status = reply.readInt()
            val payload = reply.readString()
            if (status != SolverProtocol.STATUS_OK) throw IOException("Solver failed ($payload)")
            return payload
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private suspend fun connect(): IBinder = connectLock.withLock {
        val current = binder
        if (current != null) {
            if (current.isBinderAlive) return current
            // Died before its death notification arrived: count it here instead.
            if (clearIfCurrent(current)) recordDeath()
        }
        unbindLocked()
        if (!health.available()) throw IOException("Solver paused after repeated crashes")
        val ready = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                val linked = runCatching { service.linkToDeath({ onSolverDeath(service) }, 0) }.isSuccess
                if (linked) ready.complete(service) else ready.completeExceptionally(IOException("Solver died while starting"))
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit // the death recipient handles it
            override fun onBindingDied(name: ComponentName?) { ready.completeExceptionally(IOException("Solver binding died")) }
            override fun onNullBinding(name: ComponentName?) { ready.completeExceptionally(IOException("Solver refused to bind")) }
        }
        // A unique identity per binding: Android caches the binder per (filter-equal) Intent and, while a
        // crashed solver's restart is pending, hands that dead binder to new bindings (seen on device).
        val intent = Intent(app, SolverService::class.java).setIdentifier("solver-${++bindGeneration}")
        val bound = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { runCatching { app.unbindService(conn) }; throw IOException("Solver could not be started") }
        connection = conn
        try {
            withTimeout(15_000) { ready.await() }.also { synchronized(this) { binder = it }; onEvent("solver_connected") }
        } catch (e: Exception) {
            unbindLocked()
            throw if (e is IOException) e else IOException("Solver did not start in time", e)
        }
    }

    /** Runs on a binder thread. Only the current solver's death counts: an idle unbind is not a crash. */
    private fun onSolverDeath(dead: IBinder) {
        if (!clearIfCurrent(dead)) return
        recordDeath()
        scope.launch { connectLock.withLock { if (binder == null) unbindLocked() } }
    }

    /** Exactly one of connect() and onSolverDeath() wins for a given dead binder, so each crash counts once. */
    private fun clearIfCurrent(dead: IBinder): Boolean = synchronized(this) {
        if (binder === dead) { binder = null; true } else false
    }

    private fun recordDeath() { health.recordDeath(); onEvent("solver_died") }

    @Synchronized private fun scheduleIdleUnbind() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleMs)
            connectLock.withLock { synchronized(this@RemoteCipherClient) { binder = null }; unbindLocked(); onEvent("solver_idle_release") }
        }
    }

    private fun unbindLocked() {
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }
}
