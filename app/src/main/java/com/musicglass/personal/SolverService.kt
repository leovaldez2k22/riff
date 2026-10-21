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
 * Nothing else runs in this process: RiffApplication only installs the strings and the crash recorder
 * here, so no provider, WebView or image loader is created. The pinned library may fetch a
 * pre-processed YouTube player from jsDelivr here (docs/privacy.md); this client has no cookies.
 * The prepared player script is kept in the app's cache folder ([PreprocessedPlayerCache]).
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
        val steps = PlaybackEventLog(this, PlaybackEventLog.SOLVER_FILE)
        YouTubeCipherService(http, RemotePlayerConfigStore(http, AppPlayerConfigRepository(this, "player_config_solver")),
            logger = { event -> cipherStepEvent(event.message)?.let(steps::append) })
    }

    // The prepared player script survives this process, so a solver started again does not prepare it again.
    private val preparedPlayers by lazy { PreprocessedPlayerCache(java.io.File(cacheDir, "solver-players")) }
    private val cacheInstalled by lazy {
        runBlocking { cipher.setPreprocessedPlayerCache({ preparedPlayers.read(it) }, { key, value -> preparedPlayers.write(key, value) }) }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < SolverProtocol.INITIALIZE || code > SolverProtocol.PROCESS_N) return super.onTransact(code, data, reply, flags)
            if (Binder.getCallingUid() != Process.myUid()) return false
            data.enforceInterface(SolverProtocol.DESCRIPTOR)
            // Compute fully before writing, so a failure never leaves a half-written reply.
            val result: Result<String?> = runCatching {
                cacheInstalled
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

/** The library's cipher steps that are timed, by the start of its message, and the name each is kept under. */
private val CIPHER_STEPS = linkedMapOf(
    "processFormats start" to "process_start", "processFormats done" to "process_done",
    "player JS preload done" to "player_preload", "player JS ready" to "player_ready",
    "EJS bootstrap done" to "ejs_bootstrap", "EJS evaluate done" to "ejs_evaluate",
    "signature EJS done" to "signature_ejs", "n EJS done" to "n_ejs",
    "signature fallback done" to "signature_fallback", "n fallback done" to "n_fallback",
    "n remote config done" to "n_remote", "Faraday zemer config done" to "remote_config")

/**
 * Q18-059: the cipher step costs about 1.5 s on every song. The library writes its sub-steps as text with the values
 * inside ("EJS evaluate done preprocessed=true requests=2 elapsed=1400ms player=..."); only the step's fixed name,
 * its milliseconds, the request count and two flags are kept, never the player id, a challenge or a URL.
 */
internal fun cipherStepEvent(message: String): String? {
    val stage = CIPHER_STEPS.entries.firstOrNull { message.startsWith(it.key + " ") }?.value ?: return null
    val fields = Regex("""(\w+)=(\S+)""").findAll(message).associate { it.groupValues[1] to it.groupValues[2] }
    return buildString {
        append("cipher_step stage=").append(stage)
        fields["elapsed"]?.removeSuffix("ms")?.toLongOrNull()?.let { append(" ms=").append(it) }
        (fields["requests"] ?: fields["formats"])?.toIntOrNull()?.let { append(" count=").append(it) }
        fields["preprocessed"]?.takeIf { it == "true" || it == "false" }?.let { append(" preprocessed=").append(it) }
        fields["cachedSolver"]?.takeIf { it == "true" || it == "false" }?.let { append(" cached=").append(it) }
    }
}
