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
    )
    init {
        cipherIsolated=runCatching {dependencies.installSolverBridge(extractor,remoteCipher)}
            .onFailure {playbackEvents.append("solver_bridge_unavailable ${it.javaClass.simpleName}")}.isSuccess
        if (cipherIsolated && dependencies.startProviderBackgroundWork) runtimeScope.launch {dependencies.wait(2000);suspendCatching {extractor.prewarm()}}
        session.onReset { synchronized(cacheLock) {streamCache.clear();streamFlights.values.forEach {it.cancel()};streamFlights.clear()} }
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
            ) ?: return@withContext Result.failure(
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
            Result.failure(IllegalStateException(if (error.reason ==
                    StreamResolveException.Reason.NO_PLAYABLE_STREAM)
                "No supported audio stream is available for this track"
            else "Track unavailable: ${error.reason}"))
        } catch (error: Exception) {
            playbackEvents.append("stream_prepare_failed type=${error::class.simpleName}")
            // The cause is kept for recovery to classify; it is never shown or logged.
            Result.failure(IllegalStateException("Could not prepare this track (${error::class.simpleName})",error))
        }
    }

    fun close() { streamTokens.dispose() }
}
