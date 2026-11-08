package com.musicglass.personal

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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

/**
 * Feature 4 (Riff+; owner, 8 Nov 2026): where a song is replayed most, from the heatmap YouTube's web player gets
 * with a video (YouTube Music's own answers carry none). Asked once per song, without cookies or account, only after
 * the current song has played [REPLAY_LOOKUP_AFTER_MS], so it never competes with the start of playback. The answer
 * puts a mark on the seek bar and a button that jumps there.
 */
internal const val REPLAY_LOOKUP_AFTER_MS = 5_000L
/** Buckets that start this early are left out: every play starts there, so the opening always looks replayed. */
internal const val REPLAY_SKIP_OPENING_MS = 10_000L
private const val REPLAY_RESPONSE_LIMIT = 4_000_000L
private const val REPLAY_MISS_RETRY_MS = 10 * 60_000L
private val REPLAY_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

/** One heatmap bucket: from [startMs] for [durationMs], with YouTube's 0–1 score. */
internal data class ReplayBucket(val startMs: Long, val durationMs: Long, val intensity: Double)

/**
 * The heatmap for [videoId] in a web `next` response: `frameworkUpdates.entityBatchUpdate.mutations[].payload.
 * macroMarkersListEntity` of type MARKER_TYPE_HEATMAP for that same video. Null when absent or malformed: buckets
 * must be finite, in order, not overlapping, scored 0–1, and inside the song ([durationMs], with 3 s to spare).
 */
internal fun parseReplayHeatmap(root: JsonElement, videoId: String, durationMs: Long): List<ReplayBucket>? {
    val mutations = (((root as? JsonObject)?.get("frameworkUpdates") as? JsonObject)?.get("entityBatchUpdate") as? JsonObject)
        ?.get("mutations") as? JsonArray ?: return null
    for (mutation in mutations.take(200)) {
        val entity = ((mutation as? JsonObject)?.get("payload") as? JsonObject)?.get("macroMarkersListEntity") as? JsonObject ?: continue
        if (entity["externalVideoId"]?.jsonPrimitive?.contentOrNull != videoId) continue
        val list = entity["markersList"] as? JsonObject ?: continue
        if (list["markerType"]?.jsonPrimitive?.contentOrNull != "MARKER_TYPE_HEATMAP") continue
        val markers = (list["markers"] as? JsonArray)?.takeIf { it.size in 2..1000 } ?: return null
        val buckets = markers.map { node ->
            val m = node as? JsonObject ?: return null
            val start = m["startMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            val length = m["durationMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
            val score = m["intensityScoreNormalized"]?.jsonPrimitive?.doubleOrNull ?: return null
            if (start < 0 || length <= 0 || !score.isFinite() || score < 0 || score > 1) return null
            ReplayBucket(start, length, score)
        }
        if (buckets.zipWithNext().any { (a, b) -> b.startMs < a.startMs + a.durationMs }) return null
        if (durationMs > 0 && buckets.last().let { it.startMs + it.durationMs } > durationMs + 3_000) return null
        return buckets
    }
    return null
}

/** The most replayed moment: the highest bucket after the opening, the earliest when tied; null when all are flat. */
internal fun replayPeakMs(buckets: List<ReplayBucket>): Long? {
    val candidates = buckets.filter { it.startMs >= REPLAY_SKIP_OPENING_MS }
    val top = candidates.maxOfOrNull { it.intensity } ?: return null
    if (top <= 0 || candidates.all { it.intensity == top }) return null
    return candidates.first { it.intensity == top }.startMs
}

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

    suspend fun lookup(context: Context, videoId: String, durationMs: Long, fetch: suspend (String) -> String? = ::request,
        online: () -> Boolean = { context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null }): Long? {
        cached(videoId).let { (peak, known) -> if (known) return peak }
        if (!online()) return null
        val body = runCatching { fetch(videoId) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
        val peak = withContext(Dispatchers.Default) {
            body?.let { runCatching { parseReplayHeatmap(Json.parseToJsonElement(it), videoId, durationMs) }.getOrNull() }?.let(::replayPeakMs)
        }
        synchronized(answers) { answers[videoId] = ReplayAnswer(peak, now()) }
        return peak
    }

    private suspend fun request(videoId: String): String? {
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
                    val text = runCatching { response.use { r ->
                        if (!r.isSuccessful) null
                        else r.body.source().let { source -> source.request(REPLAY_RESPONSE_LIMIT + 1); if (source.buffer.size > REPLAY_RESPONSE_LIMIT) null else source.buffer.readUtf8() }
                    } }.getOrNull()
                    if (continuation.isActive) continuation.resume(text)
                }
            })
        }
    }
}

/** The public song id of a media id, or null for phone-only files and anything else. */
internal fun replayVideoId(mediaId: String): String? = mediaId.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")?.takeIf(REPLAY_VIDEO_ID::matches)

/** The seek bar with the most-replayed mark under it and, once known, the button above it. */
@Composable
internal fun ReplayPeakBar(playback: PlaybackGateway, mediaId: String, durationMs: Long,
    lookup: (suspend (String, Long) -> Long?)? = null, slider: @Composable () -> Unit) {
    val context = LocalContext.current
    val videoId = replayVideoId(mediaId)
    val live = playback.state.live
    var peakMs by remember(mediaId) { mutableStateOf(videoId?.let { ReplayPeaks.cached(it).first }) }
    LaunchedEffect(videoId, durationMs, live) {
        if (videoId == null || live || durationMs <= 0 || ReplayPeaks.cached(videoId).second) return@LaunchedEffect
        // Only once the song has played a few seconds: its first sound is never waiting on this.
        snapshotFlow { playback.state.playing && playback.positionMs >= REPLAY_LOOKUP_AFTER_MS }.first { it }
        peakMs = if (lookup != null) lookup(videoId, durationMs) else ReplayPeaks.lookup(context, videoId, durationMs)
    }
    val peak = peakMs?.takeIf { !live && it in 0 until durationMs }
    Column {
        AnimatedVisibility(peak != null, Modifier.align(Alignment.End)) {
            val at = playerClock(peak ?: 0)
            val label = string(R.string.replay_peak_jump, at)
            Text(string(R.string.replay_peak, at), color = White, fontSize = RiffType.detail,
                modifier = Modifier.clip(RoundedCornerShape(RiffRadius.pill)).background(White.copy(alpha = .12f))
                    .clickable { uiHaptic(); peak?.let(playback::seekTo) }
                    .testTag("replay_peak").clearAndSetSemantics { contentDescription = label; onClick(label) { peak?.let(playback::seekTo); true } }
                    .padding(horizontal = RiffSpace.rowGap, vertical = RiffSpace.tight))
        }
        Box {
            slider()
            if (peak != null) Box(Modifier.matchParentSize().padding(horizontal = RiffPlayer.seekThumbSpace)) {
                Box(Modifier.fillMaxWidth((peak.toFloat() / durationMs).coerceIn(0f, 1f)).align(Alignment.CenterStart)) {
                    Box(Modifier.align(Alignment.CenterEnd).size(2.dp, 10.dp).background(Pink.copy(alpha = .9f), RoundedCornerShape(1.dp))
                        .testTag("replay_peak_mark"))
                }
            }
        }
    }
}
