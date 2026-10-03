package com.musicglass.personal

import android.os.IBinder
import com.metrolist.innertubex.models.response.PlayerResponse.StreamingData.Format
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the main process asks of the cipher. [RemoteCipherClient] sends it to the `:solver`
 * process, so a native crash in QuickJS (stress test F1) can't take playback down with it.
 */
internal interface CipherBackend {
    suspend fun initialize()
    suspend fun preloadPlayerCode(playerUrl: String)
    suspend fun prewarmEjs()
    suspend fun processFormats(playerUrl: String, formats: List<Format>): List<Format>
    suspend fun processNParameter(playerUrl: String, url: String): String?
}

/** Binder transaction codes and payload encoding shared by [SolverService] and [RemoteCipherClient]. */
internal object SolverProtocol {
    const val DESCRIPTOR = "com.musicglass.personal.Solver"
    const val INITIALIZE = IBinder.FIRST_CALL_TRANSACTION
    const val PRELOAD = IBinder.FIRST_CALL_TRANSACTION + 1
    const val PREWARM = IBinder.FIRST_CALL_TRANSACTION + 2
    const val PROCESS_FORMATS = IBinder.FIRST_CALL_TRANSACTION + 3
    const val PROCESS_N = IBinder.FIRST_CALL_TRANSACTION + 4

    const val STATUS_OK = 0
    const val STATUS_FAILED = 1

    /**
     * Binder transactions share a buffer of about 1 MB per process. Format lists are a few KB;
     * anything near this limit is a bug, and the player script itself never crosses the binder
     * (the solver downloads it).
     */
    const val MAX_PAYLOAD_CHARS = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val formatList = ListSerializer(Format.serializer())

    fun encodeFormats(formats: List<Format>): String = json.encodeToString(formatList, formats).also {
        if (it.length > MAX_PAYLOAD_CHARS) throw java.io.IOException("Solver payload too large (${it.length} chars)")
    }

    fun decodeFormats(encoded: String): List<Format> = json.decodeFromString(formatList, encoded)
}

/**
 * Crash-loop guard: after [limit] solver deaths within [windowMs], stop starting it for [cooldownMs]
 * so a player script that always crashes fails fast instead of crashing in a loop.
 */
internal class SolverHealth(private val clock: () -> Long, private val limit: Int = 3,
                            private val windowMs: Long = 10 * 60_000L, private val cooldownMs: Long = 10 * 60_000L) {
    private val deaths = ArrayDeque<Long>()
    private var blockedUntil = Long.MIN_VALUE

    @Synchronized fun recordDeath() {
        val now = clock()
        deaths.addLast(now)
        while (deaths.isNotEmpty() && now - deaths.first() > windowMs) deaths.removeFirst()
        if (deaths.size >= limit) { blockedUntil = now + cooldownMs; deaths.clear() }
    }

    @Synchronized fun available(): Boolean = clock() >= blockedUntil
}
