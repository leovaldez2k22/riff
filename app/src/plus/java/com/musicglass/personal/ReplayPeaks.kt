package com.musicglass.personal

import android.content.Context
import android.net.ConnectivityManager
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// Feature 4 (Riff+): the most-replayed lookup, apart from the seek bar that shows it (a screen file builds no HTTP client).
private const val REPLAY_RESPONSE_LIMIT = 4_000_000L
private const val REPLAY_MISS_RETRY_MS = 10 * 60_000L
/** What is known for a song: its peak, or a miss that may be asked again after [REPLAY_MISS_RETRY_MS] (null peak). */
private data class ReplayAnswer(val peakMs: Long?, val atMs: Long)

internal object ReplayPeaks {
    private val client by lazy {
        // Its own client: no cookie jar, so no YouTube account cookie is ever sent with this request.
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).cookieJar(CookieJar.NO_COOKIES).build()
    }
    private val answers = object : LinkedHashMap<String, ReplayAnswer>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplayAnswer>?) = size > 64
    }
    private fun now() = android.os.SystemClock.elapsedRealtime()
    /** The known peak, or null; [known] says whether asking again is pointless for now. */
    fun cached(videoId: String): Pair<Long?, Boolean> = synchronized(answers) {
        answers[videoId]?.let { it.peakMs to (it.peakMs != null || now() - it.atMs < REPLAY_MISS_RETRY_MS) } ?: (null to false)
    }

    suspend fun lookup(context: Context, videoId: String, durationMs: Long, fetch: suspend (String) -> String? = ::fetchNext,
        online: () -> Boolean = { context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null }): Long? {
        cached(videoId).let { (peak, known) -> if (known) return peak }
        if (!online()) return null
        val body = suspendCatching { fetch(videoId) }.getOrNull()
        val peak = withContext(Dispatchers.Default) {
            body?.let { runCatching { parseReplayHeatmap(Json.parseToJsonElement(it), videoId, durationMs) }.getOrNull() }?.let(::replayPeakMs)
        }
        synchronized(answers) { answers[videoId] = ReplayAnswer(peak, now()) }
        return peak
    }

    private suspend fun fetchNext(videoId: String): String? {
        val payload = buildJsonObject {
            put("context", buildJsonObject { put("client", buildJsonObject {
                put("clientName", "WEB"); put("clientVersion", "2.20251015.01.00"); put("hl", "en"); put("gl", "US") }) })
            put("videoId", videoId)
        }.toString()
        val request = Request.Builder().url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("Origin", "https://www.youtube.com").header("User-Agent", "Mozilla/5.0 (Android) Riff")
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    // OkHttp's own thread, not a coroutine: an unreadable body is simply no answer.
                    val text = try { response.use { r ->
                        if (!r.isSuccessful) null
                        else r.body.source().let { source -> source.request(REPLAY_RESPONSE_LIMIT + 1); if (source.buffer.size > REPLAY_RESPONSE_LIMIT) null else source.buffer.readUtf8() }
                    } } catch (_: IOException) { null }
                    if (continuation.isActive) continuation.resume(text)
                }
            })
        }
    }
}
