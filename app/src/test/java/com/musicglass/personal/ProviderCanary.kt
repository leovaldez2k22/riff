package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** What a scripted provider answers in the canary's own tests. */
internal class CanaryReply(val body: String, val status: Int = 200, val failure: IOException? = null)

/** One search that a long-lived public song answers. */
internal const val CANARY_SEARCH = "Never Gonna Give You Up Rick Astley"
/** Long-lived public albums, tried in order; the next is asked for only when the one before no longer exists. */
internal val CANARY_COLLECTIONS = listOf("MPREb_dcYZhAh5urI", "MPREb_eEpQf8QskKl")
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/**
 * The app's provider class on the pinned library, signed out, for the canary's two catalog phases. With
 * [script] null its requests go to the provider; otherwise each is answered by the script and nothing
 * leaves the process. Either way only the status of each request is noted.
 */
internal class CanaryWorld(private val context: Context, script: ((endpoint: String) -> CanaryReply)? = null) : AutoCloseable {
    val seen = CopyOnWriteArrayList<CanaryExchange>()
    private val transport = { OriginalAudioEngine.create { config { addInterceptor { chain ->
        val request = chain.request()
        if (script != null) {
            val reply = script(request.url.encodedPath.substringAfter("/youtubei/v1/", request.url.encodedPath))
            reply.failure?.let { seen += CanaryExchange(unreachable = true); throw it }
            seen += CanaryExchange(reply.status)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("scripted")
                .body(reply.body.toResponseBody("application/json".toMediaType())).build()
        } else try { chain.proceed(request).also { seen += CanaryExchange(it.code) } }
            catch (e: IOException) { seen += CanaryExchange(unreachable = true); throw e }
    } } } }
    val provider: YouTubeMusicProvider
    init {
        // Signed out, and no cookie of any earlier test: the canary has no account.
        context.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear().putBoolean("signed_out", true).commit()
        CookieManager.getInstance().removeAllCookies(null)
        val runtime = RuntimeDependencies(
            networkConditions = { NetworkConditions(true, true, true) }, storageCapacity = { StorageCapacity(1L shl 40, 1L shl 41) }, batteryPercent = { 80 },
            httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("The catalog canary downloads nothing") }.build(),
            providerTransport = transport,
            // Streams need the Android solver; that phase runs on a device (ProviderCanaryDeviceTest).
            resolver = StreamResolver { Result.failure(IllegalStateException("Online playback resolver unavailable")) },
            storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)
        provider = AppContainer(context, runtime).provider
    }

    suspend fun search(): CanaryPhase = phase("search") {
        val result = provider.discover(CANARY_SEARCH, "Songs")
        classifyCatalogPhase(result.exceptionOrNull()?.message, result.getOrNull()?.cards.orEmpty().count { it.videoId?.matches(VIDEO_ID) == true }, seen)
    }

    suspend fun collection(targets: List<String> = CANARY_COLLECTIONS): CanaryPhase {
        var requests = 0
        return canaryPhase("collection", { requests }) {
            // Every target gone is a stale list, which is the canary's fault and not the app's.
            var last = CanaryClass.HarnessFault to "targets_stale"
            for (id in targets.take(CANARY_TARGET_TRIES)) {
                seen.clear()
                val result = withTimeout(PHASE_TIMEOUT_MS) { provider.browseCatalog(id) }
                requests += seen.size
                val page = result.getOrNull()
                val rows = page?.albumTracks.orEmpty().ifEmpty { page?.cards.orEmpty() }.count { it.videoId?.matches(VIDEO_ID) == true }
                last = classifyCatalogPhase(result.exceptionOrNull()?.message, rows, seen)
                if (last.second != "not_found") return@canaryPhase last
                last = CanaryClass.HarnessFault to "targets_stale"
            }
            last
        }
    }

    private suspend fun phase(name: String, body: suspend () -> Pair<CanaryClass, String>): CanaryPhase {
        seen.clear()
        return canaryPhase(name, { seen.size }) { withTimeout(PHASE_TIMEOUT_MS) { body() } }
    }

    override fun close() {
        runCatching { provider.close() }
        CookieManager.getInstance().removeAllCookies(null)
    }

    private companion object { const val PHASE_TIMEOUT_MS = 45_000L }
}
