package com.musicglass.personal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Q10A: the provider canary. It asks the provider, signed out, for one search, one collection page and one
 * stream with its first bytes, and says what it saw as one of these classes. It never changes anything at
 * the provider and never keeps an address, a header or a body: a result is a class, a reason word, a
 * duration and a count. See docs/quality/provider-compatibility.md.
 */
internal enum class CanaryClass { Healthy, ParserBreak, ResolutionBreak, Blocked, Outage, HarnessFault }

/** What one request showed: its status number, or that no answer came. Nothing else of it is kept. */
internal data class CanaryExchange(val status: Int? = null, val unreachable: Boolean = false)

internal data class CanaryPhase(val phase: String, val classification: CanaryClass, val reason: String, val millis: Long, val requests: Int) {
    fun toJson() = """{"phase":"$phase","classification":"${classification.name}","reason":"$reason","millis":$millis,"requests":$requests}"""
}

internal fun canaryJson(phases: List<CanaryPhase>) = """{"schema":1,"phases":[${phases.joinToString(",") { it.toJson() }}]}"""

/** Long-lived public songs, tried in order; the next is asked for only when the one before is reported unavailable. */
internal val CANARY_TRACKS = listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk", "9bZkp7q19f0")
/** At most this many targets are tried in one phase, to keep the number of requests small. */
internal const val CANARY_TARGET_TRIES = 2
internal const val CANARY_FIRST_BYTES = 2048

/**
 * A catalog phase (search or collection) from what was observed: the failure sentence the provider class
 * gave, the number of usable rows, and the status of each request. A refusal or a limit is a block, and no
 * answer or a server error is an outage: neither says anything about the app. Only an answer that arrived
 * and could not be used is a break.
 */
internal fun classifyCatalogPhase(failure: String?, rows: Int, seen: List<CanaryExchange>): Pair<CanaryClass, String> {
    val statuses = seen.mapNotNull { it.status }
    return when {
        failure == null && rows > 0 -> CanaryClass.Healthy to "ok"
        failure == TIMEOUT_MESSAGE -> CanaryClass.Outage to "timeout"
        failure == OFFLINE_MESSAGE || (statuses.isEmpty() && seen.any { it.unreachable }) -> CanaryClass.Outage to "unreachable"
        429 in statuses || failure == ProviderFault.RateLimited.message -> CanaryClass.Blocked to "rate_limited"
        401 in statuses -> CanaryClass.Blocked to "sign_in_required"
        403 in statuses || failure == ProviderFault.Refused.message -> CanaryClass.Blocked to "refused"
        statuses.any { it >= 500 } || failure == ProviderFault.Provider.message -> CanaryClass.Outage to "server_error"
        statuses.any { it == 404 || it == 410 } || failure == ProviderFault.NotFound.message -> CanaryClass.ParserBreak to "not_found"
        statuses.any { it in 400..499 } -> CanaryClass.ParserBreak to "rejected"
        failure != null -> CanaryClass.ParserBreak to "unreadable"
        else -> CanaryClass.ParserBreak to "empty"
    }
}

/** Why a stream was not resolved, from the sentence the resolver gave. "target" means this song, not the app. */
internal fun classifyResolveFailure(message: String): Pair<CanaryClass, String> = when {
    message.contains("resolver unavailable", true) || message.contains("Solver", true) -> CanaryClass.HarnessFault to "solver_unavailable"
    message == OFFLINE_MESSAGE || listOf("UnknownHost", "ConnectException", "NoRouteToHost", "SocketException", "SSL", "NETWORK").any { message.contains(it) } ->
        CanaryClass.Outage to "unreachable"
    message == TIMEOUT_MESSAGE || message.contains("Timeout", true) -> CanaryClass.Outage to "timeout"
    listOf("UNAVAILABLE", "AGE_RESTRICTED", "NO_MUSIC_VIDEO", "EXPLICIT_UNSUPPORTED").any { message.endsWith(it) } -> CanaryClass.Blocked to "target"
    else -> CanaryClass.ResolutionBreak to "no_stream"
}

/** The first bytes of a resolved stream: what the audio host answered and how many bytes came. */
internal fun classifyFirstBytes(answer: CanaryExchange, received: Int): Pair<CanaryClass, String> = when {
    answer.status == null -> CanaryClass.Outage to "unreachable"
    answer.status == 429 -> CanaryClass.Blocked to "rate_limited"
    answer.status == 403 -> CanaryClass.ResolutionBreak to "stream_refused"
    answer.status >= 500 -> CanaryClass.Outage to "server_error"
    answer.status in 200..299 && received > 0 -> CanaryClass.Healthy to "ok"
    else -> CanaryClass.ResolutionBreak to "no_bytes"
}

/** Runs [body] as one phase: times it, and turns anything it throws into a fault of the canary itself. */
internal suspend fun canaryPhase(name: String, requests: () -> Int, body: suspend () -> Pair<CanaryClass, String>): CanaryPhase {
    val started = System.nanoTime()
    val (classification, reason) = try { body() }
        catch (_: TimeoutCancellationException) { CanaryClass.Outage to "timeout" }
        catch (e: CancellationException) { throw e }
        catch (_: Throwable) { CanaryClass.HarnessFault to "harness_exception" }
    return CanaryPhase(name, classification, reason, (System.nanoTime() - started) / 1_000_000, requests())
}

/**
 * The stream phase. [resolve] is the app's resolver, which needs the Android solver process, so this phase
 * runs on a device or an emulator. [firstBytes] asks the audio host for the start of the stream only.
 */
internal class StreamCanary(private val signedIn: () -> Boolean, private val solverReady: () -> Boolean,
    private val resolve: suspend (String) -> Result<ProviderStream>,
    private val firstBytes: (ProviderStream) -> Pair<CanaryExchange, Int> = ::fetchFirstBytes,
    private val targets: List<String> = CANARY_TRACKS) {
    suspend fun run(): CanaryPhase {
        var requests = 0
        return canaryPhase("stream", { requests }) {
            if (signedIn()) return@canaryPhase CanaryClass.HarnessFault to "signed_in"
            if (!solverReady()) return@canaryPhase CanaryClass.HarnessFault to "solver_unavailable"
            var last = CanaryClass.Blocked to "all_targets_unavailable"
            for (videoId in targets.take(CANARY_TARGET_TRIES)) {
                requests++
                val resolved = resolve(videoId)
                val failure = resolved.exceptionOrNull()
                if (failure != null) {
                    last = classifyResolveFailure(failure.message.orEmpty())
                    if (last.second == "target") { last = CanaryClass.Blocked to "all_targets_unavailable"; continue }
                    return@canaryPhase last
                }
                requests++
                val (answer, received) = firstBytes(resolved.getOrThrow())
                return@canaryPhase classifyFirstBytes(answer, received)
            }
            last
        }
    }
}

/** Asks for the first [CANARY_FIRST_BYTES] bytes and reads no more. The address and headers are used once and not kept. */
internal fun fetchFirstBytes(stream: ProviderStream): Pair<CanaryExchange, Int> {
    val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    val request = Request.Builder().url(stream.url).apply { stream.headers.forEach { (name, value) -> header(name, value) } }
        .header("Range", "bytes=0-${CANARY_FIRST_BYTES - 1}").build()
    return try {
        client.newCall(request).execute().use { response ->
            val buffer = ByteArray(CANARY_FIRST_BYTES)
            var received = 0
            response.body.byteStream().let { input ->
                while (received < buffer.size) { val n = input.read(buffer, received, buffer.size - received); if (n < 0) break; received += n }
            }
            CanaryExchange(response.code) to received
        }
    } catch (_: IOException) { CanaryExchange(unreachable = true) to 0 }
}
