package com.musicglass.personal

import okhttp3.HttpUrl.Companion.toHttpUrl
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.YtConfigParser
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext

internal class AppPlayerConfigRepository(context: Context, prefsName: String = "player_config") : PlayerConfigRepository {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    override val enabled = true
    override val sourceUrl =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    override val defaultSourceUrl = sourceUrl
    override var cachedJson: String
        get() = prefs.getString("json", "").orEmpty()
        set(value) { prefs.edit().putString("json", value).apply() }
    override var cachedAtMs: Long
        get() = prefs.getLong("at", 0L)
        set(value) { prefs.edit().putLong("at", value).apply() }
    override var cachedSourceUrl: String
        get() = prefs.getString("source", "").orEmpty()
        set(value) { prefs.edit().putString("source", value).apply() }
    override var cachedEtag: String
        get() = prefs.getString("etag", "").orEmpty()
        set(value) { prefs.edit().putString("etag", value).apply() }
}

/**
 * Where a song's audio comes from. Asks the pinned library for a stream, with challenges solved in the
 * separate solver process and never in this one, and keeps what it resolved in a small in-memory cache.
 * Signed addresses stay in that cache and in the player; they are never written to disk or logged.
 */
internal class ProviderStreamResolver(context: Context, private val session: ProviderSessionManager) : StreamResolver {
    private val dependencies get() = session.dependencies
    private val appContext get() = session.appContext
    private val runtimeScope get() = session.scope
    private val playbackEvents get() = session.playbackEvents
    private val innerTube get() = session.innerTube
    private val http get() = session.http
    private val authRevision get() = session.authRevision
    private val cacheLock = Any()
    private val dataSaverPrefs=appContext.getSharedPreferences(DATA_SAVER_PREFS,Context.MODE_PRIVATE)
    private var streamRevision=0L
    private val streamCache=linkedMapOf<String,ProviderStream>()
    private val streamFlights=mutableMapOf<String,Deferred<Result<ProviderStream>>>()
    private val configStore = RemotePlayerConfigStore(http, AppPlayerConfigRepository(context))
    private val configParser = YtConfigParserImpl(http, innerTube, configStore)
    private val streamTokens = AndroidStreamTokenProvider(context.applicationContext, http, onEvent = { playbackEvents.append(it) })
    // The library needs one of its own ciphers to build the extractor. It is replaced by the :solver bridge
    // before any use; if that fails, online resolution is refused (requireSolver) and this one never runs.
    private val unusedInProcessCipher=YouTubeCipherService(http,configStore)
    private val remoteCipher=RemoteCipherClient(context.applicationContext,onEvent={playbackEvents.append(it)})
    internal var cipherIsolated by mutableStateOf(false)
        private set
    /** Fail closed: challenges are never solved in the process that plays audio. */
    private fun requireSolver():RemoteCipherClient =
        if(cipherIsolated) remoteCipher else throw SolverUnavailableException("Solver bridge is not installed")
    private val extractor = InnerTubeExtractor(
        configParser = object : YtConfigParser by configParser {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try { configParser.fetchConfig(videoId, useLoginCookies) }
                catch (_: IllegalStateException) {
                    configParser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        },
        cipherService = unusedInProcessCipher,
        innerTube = innerTube,
        tokenProvider = streamTokens,
        logger = { event -> streamStageEvent(event)?.let(playbackEvents::append) },
    )
    // When the streams were last made ready (at start and by warmUp); declared before init, which sets it.
    private val warmLock=Any()
    private var warmedAtMs:Long?=null
    private var warming:Job?=null
    init {
        cipherIsolated=runCatching {dependencies.installSolverBridge(extractor,remoteCipher)}
            .onFailure {playbackEvents.append("solver_bridge_unavailable ${it.javaClass.simpleName}")}.isSuccess
        if (cipherIsolated && dependencies.startProviderBackgroundWork) {
            warmedAtMs=dependencies.clocks.monotonicMillis()
            runtimeScope.launch {dependencies.wait(2000);prewarm("start")}
        }
        session.onReset { synchronized(cacheLock) {streamCache.clear();streamFlights.values.forEach {it.cancel()};streamFlights.clear()} }
    }

    /**
     * Gets the next song ready to start quickly when the app comes back to the screen: the solver is started with
     * its player script, and an expired attestation is made again, before a song is asked for. Both are lost while
     * the app is away (the solver stops after ten idle minutes, Android may stop the app), and the first song
     * after a long break used to wait for all of it. At most once in [STREAM_REWARM_INTERVAL_MS].
     */
    fun warmUp() {
        if(!cipherIsolated || !dependencies.startProviderBackgroundWork) return
        synchronized(warmLock) {
            val now=dependencies.clocks.monotonicMillis()
            if(warming?.isActive==true || !streamRewarmDue(warmedAtMs,now)) return
            warmedAtMs=now
            warming=runtimeScope.launch {prewarm("foreground")}
        }
    }

    private suspend fun prewarm(reason:String) {
        val started=dependencies.clocks.monotonicMillis()
        suspendCatching {
            settleVisitor(innerTube.sessionSnapshot().visitorData) {innerTube.fetchFreshVisitorData(innerTube.sessionSnapshot())}
            extractor.prewarm()
        }.fold(
            onSuccess={playbackEvents.append("stream_prewarm reason=$reason ms=${dependencies.clocks.monotonicMillis()-started}")},
            onFailure={playbackEvents.append("stream_prewarm_failed reason=$reason type=${it::class.simpleName}")})
    }

    suspend fun resolve(videoId:String,live:Boolean=false,excludedProfiles:Set<String> = emptySet(),video:Boolean=false,download:Boolean=false):Result<ProviderStream> =
        resolve(StreamRequest(videoId,live,excludedProfiles,video,download,
            streamingAudioQuality(dataSaverPrefs.getBoolean("enabled",false),download)))

    /** Signed URLs stay in a bounded RAM cache and never enter the durable music database. */
    override suspend fun resolve(request:StreamRequest):Result<ProviderStream> {
        val (videoId,live,excludedProfiles,video,download,quality)=request
        if(excludedProfiles.isNotEmpty()) return resolveFresh(videoId,live,excludedProfiles,video,download,quality)
        val flight=synchronized(cacheLock) {
            val revision=streamRevision
            val key="$authRevision:$videoId:$live:$video:$download:$quality:$revision"
            streamCache[key]?.takeIf { reusableStream(it.expiresAtMs,dependencies.clocks.wallTimeMillis(),live) }?.let {return Result.success(it)}
            streamFlights[key] ?: runtimeScope.async {
                val result=withTimeout(60_000) {
                    try {resolveFresh(videoId,live,emptySet(),video,download,quality)}
                    catch(e:CancellationException) {
                        currentCoroutineContext().ensureActive()
                        playbackEvents.append("provider_session_refresh_retry")
                        dependencies.wait(150);resolveFresh(videoId,live,emptySet(),video,download,quality)
                    }
                }
                result.getOrNull()?.let {stream->synchronized(cacheLock) {if(revision==streamRevision) {streamCache[key]=stream;while(streamCache.size>32) streamCache.remove(streamCache.keys.first())}}}
                result
            }.also {job->streamFlights[key]=job;job.invokeOnCompletion {synchronized(cacheLock) {if(streamFlights[key]===job) streamFlights.remove(key)}}}
        }
        return flight.await()
    }
    /** Drops the minted attestation so the next stream mints a fresh one (it can go stale after a network change). */
    suspend fun invalidateAttestation() {
        streamTokens.invalidateAttestation()
        // Every prefetched bootstrap using that attestation is stale, not just the failed song.
        synchronized(cacheLock) {streamRevision++;streamCache.clear();streamFlights.clear()}
    }
    fun invalidateStream(videoId:String) {synchronized(cacheLock) {streamCache.keys.filter {it.contains(":$videoId:")}.forEach {streamCache.remove(it)}}}
    private suspend fun resolveFresh(videoId: String, live: Boolean, excludedProfiles: Set<String>, video:Boolean,download:Boolean,quality:AudioQuality): Result<ProviderStream> {
        val request=StreamRequest(videoId,live,excludedProfiles,video,download,quality)
        return dependencies.resolver?.resolve(request) ?: resolveUsingExtractor(request)
    }
    private suspend fun resolveUsingExtractor(request:StreamRequest):Result<ProviderStream> = withContext(dependencies.dispatchers.io) {
        val (videoId,live,excludedProfiles,video,download,quality)=request
        val solverFailures=remoteCipher.unavailableCount
        fun failure(error:Throwable)=Result.failure<ProviderStream>(keepSolverCause(error,remoteCipher.unavailableCount!=solverFailures))
        try {
            val revision = authRevision
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid video ID" }
            requireSolver()
            val stream = extractor.extract(
                videoId = videoId,
                hints = ContentHints(wantVideo = video,isLive = live,maxVideoHeight=if(quality==AudioQuality.LOW) 360 else 720).withStreamCapabilities(
                    // Tokenless SABR can fail after its first chunk; HLS can adapt between
                    // variants. Direct audio retains the format selected for this quality setting.
                    allowHls = !download && (video || live), allowSabr = video || live || download, allowBoundedRange = download),
                excludedClients = excludedProfiles + if(live || video) setOf("WEB_REMIX","WEB_REMIX_SABR","ANDROID_MUSIC","IOS_MUSIC") else emptySet(),
                audioQuality = quality,
            ) ?: return@withContext failure(
                IllegalStateException("No playable audio stream is available for this track"))
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException("Account changed while preparing the track"))
            playbackEvents.append("stream_resolved profile=${stream.profileId} " +
                "transport=${if (stream.sabrBootstrap != null) "SABR" else if (
                    stream.mimeType?.contains("mpegurl", ignoreCase = true) == true) "HLS" else "DIRECT"} " +
                "po_token=${stream.sabrBootstrap?.poToken != null}")
            if (!download && (stream.requireBoundedRange || stream.useRangeChunks)) {
                return@withContext Result.failure(
                    IllegalStateException("This track requires bounded-range streaming, which this player does not support"))
            }
            val hls=stream.mimeType?.contains("mpegurl",ignoreCase=true)==true
            val audioUrl=if(hls) prepareYoutubeHlsManifest(stream.audioUrl) {challenge ->
                val config=try {configParser.fetchConfig(videoId,useLoginCookies=false)} catch(_:IllegalStateException) {configParser.fetchEmbeddedConfig(videoId,useLoginCookies=false)}
                playbackEvents.append("manifest_challenge_found")
                val challengeUrl="https://manifest.googlevideo.com/videoplayback?n=$challenge"
                val processed=requireSolver().processNParameter(config.playerUrl,challengeUrl)
                processed?.toHttpUrl()?.queryParameter("n")
            } else stream.audioUrl
            playbackEvents.append("manifest_ready hls=$hls")
            val videoUrl=stream.videoUrl?.takeUnless {it==stream.audioUrl}
            Result.success(ProviderStream(audioUrl, stream.headers, stream.mimeType,
                stream.sabrBootstrap,stream.profileId,videoUrl,stream.videoMimeType,stream.sabrVideoBootstrap,videoId,
                stream.itag,stream.bitrate?:0,stream.contentLengthBytes,maxOf((stream.mediaMetadata?.durationSeconds?:0)*1000,stream.sabrBootstrap?.durationMs?:0),
                minOf(stream.expiresAt?.toEpochMilliseconds()?:Long.MAX_VALUE,dependencies.clocks.wallTimeMillis()+20*60_000),
                stream.requireBoundedRange || stream.useRangeChunks,video || live,stream.mediaMetadata?.title.orEmpty(),stream.mediaMetadata?.author.orEmpty(),stream.mediaMetadata?.channelId,
                validLoudnessDb(stream.loudnessDb)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            failure(IllegalStateException(if (error.reason ==
                    StreamResolveException.Reason.NO_PLAYABLE_STREAM)
                "No supported audio stream is available for this track"
            else "Track unavailable: ${error.reason}"))
        } catch (error: Exception) {
            playbackEvents.append("stream_prepare_failed type=${error::class.simpleName}")
            // The cause is kept for recovery to classify; it is never shown or logged.
            failure(IllegalStateException("Could not prepare this track (${error::class.simpleName})",error))
        }
    }


    fun close() { streamTokens.dispose() }
}

/** How long after one warm-up another may start; coming back to the app more often than this does nothing. */
internal const val STREAM_REWARM_INTERVAL_MS = 5 * 60_000L

internal fun streamRewarmDue(lastMs: Long?, nowMs: Long, intervalMs: Long = STREAM_REWARM_INTERVAL_MS) =
    lastMs == null || nowMs - lastMs >= intervalMs

/**
 * The extraction library catches a solver failure inside its own steps and reports a generic one, so recovery
 * would class a solver that is down as a timeout (Q18-022). When the solver failed during the lookup, the
 * failure carries [SolverUnavailableException] so recovery says the resolver is unavailable and offers Retry.
 */
internal fun keepSolverCause(failure:Throwable,solverFailed:Boolean):Throwable =
    if(!solverFailed || generateSequence(failure) {it.cause}.take(16).any {it is SolverUnavailableException}) failure
    else SolverUnavailableException("Online playback resolver unavailable",failure)

/** The extractor's own steps that carry a duration, so a phone run shows where a slow start goes (Q18-059). */
private val STREAM_STAGES = setOf("prewarm completed", "embedded config ready", "watch page config ready",
    "player responses received", "cipher processing completed", "cipher fallback completed")

/** Only the step's fixed name and its milliseconds: never a client, profile, track or address. */
internal fun streamStageEvent(event: com.metrolist.innertubex.InnerTubeLogEvent): String? {
    if (event.message !in STREAM_STAGES) return null
    val ms = event.details["elapsedMs"]?.toLongOrNull() ?: return null
    // A field, not a bare word: DiagnosticEvent keeps only listed key=value pairs.
    return "stream_stage stage=${event.message.replace(' ', '_')} ms=$ms"
}

/**
 * Q18-059: without a visitor in the session the prewarm mints its token for the visitor in the player config, and the
 * first song then fetches a fresh visitor, so the token provider drops that token and mints another (about 2.4 s on
 * the phone). The visitor is fetched, and kept in the session, before the prewarm, so both use the same one.
 */
internal suspend fun settleVisitor(current: String?, fetch: suspend () -> String?): Boolean {
    if (!current.isNullOrBlank()) return false
    fetch()
    return true
}
