package com.musicglass.personal

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Runs YouTube's signature and n-parameter challenges (QuickJS, native code) in the `:solver`
 * process. If QuickJS crashes, only this process dies: playback, the media session and the UI keep
 * running, and [RemoteCipherClient] restarts it for the next request.
 *
 * Nothing else runs in this process: the app has no Application class, so no provider, WebView
 * or image loader is created here.
 */
class SolverService : Service() {
    private val http by lazy {
        HttpClient(OriginalAudioEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            install(HttpTimeout) { connectTimeoutMillis = 12_000; requestTimeoutMillis = 30_000 }
        }
    }

    // Own preferences file: SharedPreferences are not safe to share between processes.
    private val cipher by lazy {
        YouTubeCipherService(http, RemotePlayerConfigStore(http, AppPlayerConfigRepository(this, "player_config_solver")))
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < SolverProtocol.INITIALIZE || code > SolverProtocol.PROCESS_N) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) return false
            data.enforceInterface(SolverProtocol.DESCRIPTOR)
            // Compute fully before writing, so a failure never leaves a half-written reply.
            val result: Result<String?> = runCatching {
                runBlocking {
                    when (code) {
                        SolverProtocol.INITIALIZE -> { cipher.initialize(); null }
                        SolverProtocol.PRELOAD -> { cipher.preloadPlayerCode(checkNotNull(data.readString())); null }
                        SolverProtocol.PREWARM -> { cipher.prewarmEjs(); null }
                        SolverProtocol.PROCESS_FORMATS -> {
                            val playerUrl = checkNotNull(data.readString())
                            val formats = SolverProtocol.decodeFormats(checkNotNull(data.readString()))
                            SolverProtocol.encodeFormats(cipher.processFormats(playerUrl, formats))
                        }
                        else -> cipher.processNParameter(checkNotNull(data.readString()), checkNotNull(data.readString()))
                    }
                }
            }
            reply ?: return true
            result.fold(
                onSuccess = { reply.writeInt(SolverProtocol.STATUS_OK); reply.writeString(it) },
                // Type only: library messages can contain signed URLs.
                onFailure = { reply.writeInt(SolverProtocol.STATUS_FAILED); reply.writeString(it.javaClass.simpleName) },
            )
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        // Frees the QuickJS heaps (up to ~200 MB of native memory) once the app stops using the solver.
        runBlocking { runCatching { cipher.dispose() } }
        runCatching { http.close() }
        super.onDestroy()
    }
}
