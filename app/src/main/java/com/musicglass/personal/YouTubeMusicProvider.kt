package com.musicglass.personal

import okhttp3.HttpUrl.Companion.toHttpUrl
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.sabr.SabrBootstrap
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.content.TextContent
import io.ktor.client.request.setBody
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.post
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.get
import io.ktor.client.request.header
import androidx.compose.runtime.mutableStateMapOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

internal fun providerArtworkUrl(videoId: String): String? =
    videoId.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
        ?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

internal data class ProviderTrack(val videoId: String, val title: String, val artist: String,
    val artworkUrl: String? = providerArtworkUrl(videoId),
    val artistId: String? = null, val albumId: String? = null, val album: String = "",
    val isLive: Boolean = false, val isMusic: Boolean = true)
internal data class ProviderSearchPage(val tracks: List<ProviderTrack>, val nextToken: String?)
internal data class ProviderHomeCard(val title: String, val subtitle: String,
    val artworkUrl: String?, val videoId: String? = null, val browseId: String? = null, val kind: CatalogKind = CatalogKind.Song,
    val artistId: String? = null, val albumId: String? = null, val album: String = "",val musicVideo:Boolean=false,val officialMusicVideo:Boolean=false,val setVideoId:String?=null,val addedAt:Long?=null)
internal data class ProviderHomeSection(val title: String, val cards: List<ProviderHomeCard>)
internal data class ProviderProfile(val name: String, val artworkUrl: String?)
internal data class ProviderStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String?,
    val sabrBootstrap: SabrBootstrap? = null,
    val profileId: String? = null,
    val videoUrl:String?=null,val videoMimeType:String?=null,val sabrVideoBootstrap:SabrBootstrap?=null,val sourceVideoId:String?=null,
    val itag:Int=0,val bitrate:Int=0,val contentLength:Long?=null,val durationMs:Long=0,val expiresAtMs:Long=0,
    val boundedRange:Boolean=false,val includesVideo:Boolean=false,val resolvedTitle:String="",val resolvedAuthor:String="",val resolvedChannelId:String?=null,
)

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

/** WebView owns cookies; app-private preferences retain the noncookie page session fields. */
internal class YouTubeMusicProvider(context: Context) : AutoCloseable {
    companion object {
        @Volatile private var sharedInstance:YouTubeMusicProvider?=null
        fun checkExistingFeedWindow() {sharedInstance?.checkFeedWindow()}
        fun checkExistingSession() {sharedInstance?.checkSessionIfDue()}
        fun shared(context:Context):YouTubeMusicProvider=sharedInstance ?: synchronized(this) {
            sharedInstance ?: YouTubeMusicProvider(context.applicationContext).also {sharedInstance=it}
        }
    }
    private val appContext=context.applicationContext
    private val runtimeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    internal val feedCache=PageMemoryCache(runtimeScope,retain={key->val page=key.substringAfter(':');page.startsWith("home:") || page.startsWith("podcasts:") || page=="radio" || page.startsWith("radio:")})
    internal val collectionCache=PageMemoryCache(runtimeScope,scheduled=false)
    var libraryScanRevision by mutableIntStateOf(0)
        private set
    var libraryPlaylistSnapshot by mutableStateOf<List<ProviderHomeCard>>(emptyList())
        private set
    var homeMoods by mutableStateOf<List<HomeMood>>(emptyList())
        private set
    fun feedKey(key:String)="$authRevision:$key"
    fun cachedFeed(key:String):List<ProviderHomeSection>?=feedCache.peek(feedKey(key))
    // Home always waits for a fresh feed (rc17 behaviour, kept by the owner on 2026-10-03), so no Home
    // snapshot is stored. Unreleased test builds of plan W3 wrote one here; remove it once.
    private val retiredSnapshots=runtimeScope.launch {runCatching {java.io.File(appContext.noBackupFilesDir,"feed-snapshots").deleteRecursively()}}
    fun cachedCollection(id:String):CatalogPage?=collectionCache.peek(feedKey("collection:$id"))
    fun cachedLibrary(kind:String):CatalogPage?=collectionCache.peek(feedKey("library:$libraryRevision:$kind"))
    fun checkFeedWindow()=feedCache.checkWindow()
    fun discardPageMemory() {cachedHome=emptyList();podcastEndpoint=null;feedCache.clear();collectionCache.clear();homeMoods=emptyList();libraryPlaylistSnapshot=emptyList();libraryScanRevision=0}
    private val streamCache=linkedMapOf<String,ProviderStream>()
    private val streamFlights=mutableMapOf<String,Deferred<Result<ProviderStream>>>()
    private val playbackEvents = PlaybackEventLog(context)
    private val sessionPrefs = context.applicationContext.getSharedPreferences(
        "provider_session", Context.MODE_PRIVATE)
    var signedIn by mutableStateOf(false)
        private set
    /** Whether Google still accepts the saved sign-in (W5); signedIn only means cookies exist. */
    var sessionState by mutableStateOf(SessionState.SignedOut)
        private set
    private val sessionChecks=SessionCheckSchedule(android.os.SystemClock::elapsedRealtime)
    private val sessionProbeGate=Mutex()
    /** On resume at most every 6 hours; [forced] right after a 401 (at most every 30 s). */
    private var deferredSessionCheck:kotlinx.coroutines.Job?=null
    fun checkSessionIfDue(forced:Boolean=false) {
        if(!signedIn) {sessionState=SessionState.SignedOut;return}
        if(sessionChecks.due(forced)) {runtimeScope.launch {probeSession()};return}
        // A 401 right after a check must not be dropped: run it once the 30 s gap has passed.
        if(forced && deferredSessionCheck?.isActive!=true)
            deferredSessionCheck=runtimeScope.launch {delay(sessionChecks.waitMs(forced=true));probeSession()}
    }
    private suspend fun probeSession() = sessionProbeGate.withLock {
        if(!signedIn) {sessionState=SessionState.SignedOut;return@withLock}
        val revision=authRevision
        sessionChecks.checked()
        val state=try {
            val response=innerTube.accountMenu(YouTubeClient.WEB_REMIX)
            val text=response.bodyAsText()
            classifySessionProbe(response.status.value,runCatching {json.parseToJsonElement(text) as? JsonObject}.getOrNull(),text,networkError=false)
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {if(isAuthFailure(e)) SessionState.Expired else SessionState.Unverified}
        if(revision!=authRevision) return@withLock
        // Offline or a server hiccup never downgrades a session already known to be good.
        val next=if(state==SessionState.Unverified && sessionState==SessionState.Valid) SessionState.Valid else state
        if(next!=sessionState) playbackEvents.append("session_state=${next.name.lowercase()}")
        sessionState=next
    }
    /**
     * Stress builds only (src/stress, FAKE_EXPIRED broadcast): makes requests use scrambled copies of
     * the cookies, in memory only, so the expired-session UI can be checked without signing out.
     * The saved cookies are untouched; refreshAccount() (Finish sign-in, or a restart) restores them.
     */
    internal fun simulateExpiredSession() {
        if(!signedIn) return
        val cookies=CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return
        val scrambled=cookies.split(';').joinToString("; ") {part->
            val t=part.trim();val i=t.indexOf('=')
            if(i<=0) t else t.substring(0,i+1)+t.substring(i+1).map {"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[(Math.random()*36).toInt()]}.joinToString("")
        }
        innerTube.replaceSession(cookie=scrambled,visitorData=visitorData,dataSyncId=dataSyncId,authUser=authUser,useLoginForBrowse=true)
        simulatedExpiry=true
        playbackEvents.append("session_simulated_expiry")
        checkSessionIfDue(forced=true)
    }
    var accountProfile by mutableStateOf<ProviderProfile?>(null)
        private set
    var authRevision by mutableIntStateOf(0)
        private set
    private var visitorData: String? = sessionPrefs.getString("visitor", null)
    private var dataSyncId: String? = sessionPrefs.getString("sync", null)
    private var authUser: String = sessionPrefs.getString("account", "0").orEmpty()
    private val searchCache = object : LinkedHashMap<String, Pair<Long, ProviderSearchPage>>(24, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, ProviderSearchPage>>?) =
            size > 24
    }
    private val cacheLock = Any()
    private val searchPacer = Mutex()
    private var lastSearchMs = 0L
    private val songsFilter = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http: HttpClient = HttpClient(OriginalAudioEngine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 12_000
            requestTimeoutMillis = 30_000
        }
        install(cookieRotationPlugin(enabled = { signedIn && !simulatedExpiry }, persist = ::persistRotatedCookies))
    }
    private val innerTube: InnerTube = InnerTube(http)
    @Volatile private var simulatedExpiry = false
    private val cookieLock = Any()
    /** Writes rotated session cookies back to WebView's store and the live InnerTube session (W5). */
    private fun persistRotatedCookies(host: String, cookies: List<Pair<String, String>>): Unit = synchronized(cookieLock) {
        val manager = CookieManager.getInstance()
        val before = manager.getCookie("https://music.youtube.com").orEmpty().split(';')
            .associate { it.trim().substringBefore('=') to it.trim().substringAfter('=', "") }
        val changed = cookies.filter { (name, header) -> before[name] != header.substringBefore(';').substringAfter('=').trim() }
        if (changed.isEmpty()) return@synchronized
        changed.forEach { (_, header) -> manager.setCookie("https://$host", header) }
        manager.flush()
        val current = manager.getCookie("https://music.youtube.com") ?: return@synchronized
        if (signedIn && !simulatedExpiry) innerTube.replaceSession(cookie = current, visitorData = visitorData,
            dataSyncId = dataSyncId, authUser = authUser, useLoginForBrowse = true)
        playbackEvents.append("cookies_rotated names=${changed.joinToString(",") { it.first }}")
    }
    private val configStore = RemotePlayerConfigStore(http, AppPlayerConfigRepository(context))
    private val configParser = YtConfigParserImpl(http, innerTube, configStore)
    private val streamTokens = AndroidStreamTokenProvider(context.applicationContext, http, onEvent = { playbackEvents.append(it) })
    // In-process cipher: used only if the :solver bridge can't be installed (see CipherBridgeInstaller).
    private val streamCipher=YouTubeCipherService(http,configStore)
    private val remoteCipher=RemoteCipherClient(context.applicationContext,onEvent={playbackEvents.append(it)})
    private var cipherIsolated=false
    private val extractor = InnerTubeExtractor(
        configParser = object : YtConfigParser by configParser {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try { configParser.fetchConfig(videoId, useLoginCookies) }
                catch (_: IllegalStateException) {
                    configParser.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        },
        cipherService = streamCipher,
        innerTube = innerTube,
        tokenProvider = streamTokens,
    )

    val songLikes = mutableStateMapOf<String,Boolean>()
    var libraryRevision by mutableIntStateOf(0)
        private set
    private val likeGate = Mutex()
    private var cachedHome: List<ProviderHomeSection> = emptyList()
    private var podcastEndpoint: Pair<String,String?>? = null


    internal var musicPairingRequests=0
        private set
    init {
        http.plugin(HttpSend).intercept {request->
            // Fixed provider endpoint only; preserve the library's account/session headers.
            if(request.url.host=="music.youtube.com" && request.url.build().encodedPath=="/youtubei/v1/next") {
                val body=request.body as? TextContent
                if(body!=null) {val paired=musicPairingRequest(body.text);if(paired!=body.text) musicPairingRequests++;request.setBody(TextContent(paired,body.contentType))}
            }
            execute(request)
        }
        cipherIsolated=runCatching {CipherBridgeInstaller.install(extractor,remoteCipher)}
            .onFailure {playbackEvents.append("solver_bridge_unavailable ${it.javaClass.simpleName}")}.isSuccess
        refreshAccount();runtimeScope.launch {delay(2000);runCatching {extractor.prewarm()}}
    }

    fun refreshAccount(): Boolean {
        simulatedExpiry=false
        visitorData=sessionPrefs.getString("visitor",null);dataSyncId=sessionPrefs.getString("sync",null)
        authUser=sessionPrefs.getString("account","0").orEmpty()
        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
            ?: CookieManager.getInstance().getCookie("https://www.youtube.com")
        val valid = !sessionPrefs.getBoolean("signed_out", false) && cookies?.split(';')?.any {
            val name = it.trim().substringBefore('=')
            name == "SAPISID" || name == "__Secure-3PAPISID" || name == "__Secure-1PAPISID"
        } == true
        innerTube.replaceSession(cookie = cookies.takeIf { valid }, visitorData = visitorData,
            dataSyncId = dataSyncId, authUser = authUser, useLoginForBrowse = valid)
        signedIn = valid
        accountProfile = null
        songLikes.clear(); cachedHome = emptyList();podcastEndpoint=null
        synchronized(cacheLock) { searchCache.clear() }
        feedCache.clear();collectionCache.clear();homeMoods=emptyList();libraryPlaylistSnapshot=emptyList();libraryScanRevision=0
        authRevision++
        synchronized(cacheLock) {streamCache.clear();streamFlights.values.forEach {it.cancel()};streamFlights.clear()}
        sessionChecks.reset();sessionState=if(valid) SessionState.Checking else SessionState.SignedOut
        if(valid) runtimeScope.launch {delay(1500);probeSession()}
        return valid
    }

    fun useWebSession(visitor: String?, syncId: String?, accountIndex: String?): Boolean {
        visitorData = visitor?.takeIf { it.isNotBlank() && it != "null" }
        dataSyncId = syncId?.substringBefore("||")?.takeIf { it.isNotBlank() && it != "null" }
        authUser = accountIndex?.filter(Char::isDigit)?.ifBlank { "0" } ?: "0"
        sessionPrefs.edit().putString("visitor", visitorData)
            .putString("sync", dataSyncId).putString("account", authUser)
            .remove("signed_out").apply()
        CookieManager.getInstance().flush()
        return refreshAccount()
    }

    fun signOut() {
        visitorData = null
        dataSyncId = null
        authUser = "0"
        sessionPrefs.edit().clear().putBoolean("signed_out", true).apply()
        innerTube.replaceSession(cookie = null, visitorData = null, dataSyncId = null,
            authUser = "0", useLoginForBrowse = false)
        signedIn = false
        accountProfile = null
        songLikes.clear(); cachedHome = emptyList();podcastEndpoint=null
        synchronized(cacheLock) { searchCache.clear() }
        feedCache.clear();collectionCache.clear();homeMoods=emptyList();libraryPlaylistSnapshot=emptyList();libraryScanRevision=0
        authRevision++
        synchronized(cacheLock) {streamCache.clear();streamFlights.values.forEach {it.cancel()};streamFlights.clear()}
        sessionChecks.reset();sessionState=SessionState.SignedOut
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
    }

    @Volatile private var cachedAccountScope:Pair<String,String>?=null
    /** Stable, opaque local namespace; raw account identifiers never become preference keys. */
    fun accountScopeKey(): String? {
        if (!signedIn) return null
        val sync = dataSyncId?.takeIf { it.isNotBlank() } ?: return null
        val identity="musicglass-account:$sync:$authUser"
        cachedAccountScope?.takeIf {it.first==identity}?.let {return it.second}
        val key=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        cachedAccountScope=identity to key
        return key
    }

    private suspend fun requestCatalog(browseId: String, token: String? = null, params: String? = null, paced: Boolean = true): CatalogPage {
        val revision = authRevision
        if (paced) paceSearch()
        val response = innerTube.browse(YouTubeClient.WEB_REMIX,browseId = browseId,params = params,
            continuation = token,setLogin = signedIn)
        if (response.status.value >= 400) throw CatalogHttpException(response.status.value)
        check(revision == authRevision) { "Account changed while loading" }
        return catalogPage(json.parseToJsonElement(response.bodyAsText()))
    }
    private class CatalogHttpException(val status: Int) : IllegalStateException("YouTube Music is unavailable (HTTP $status)")
    // Retries a collection page on network errors, timeouts, 429 and 5xx; other HTTP errors and account changes fail at once.
    private suspend fun requestCollectionPage(id: String, token: String? = null): CatalogPage {
        val revision = authRevision
        var attempt = 0
        while (true) {
            try {
                return withTimeout(15_000) { requestCatalog(id, token, paced = false) }
            } catch (e: Exception) {
                val retryable = when (e) {
                    is TimeoutCancellationException, is java.io.IOException -> true
                    is CancellationException -> throw e
                    is CatalogHttpException -> e.status == 429 || e.status >= 500
                    else -> false
                }
                if (!retryable || attempt >= 2 || revision != authRevision) {
                    if (e is TimeoutCancellationException) throw IllegalStateException("YouTube Music took too long to respond")
                    throw e
                }
                attempt++
                delay(700L * attempt)
            }
        }
    }
    private suspend fun <T> providerResult(block: suspend () -> T): Result<T> {
        val caller=currentCoroutineContext()
        return try {withContext(Dispatchers.IO) {Result.success(block())}}
        catch(e:CancellationException) {
            caller.ensureActive()
            Result.failure(IllegalStateException("YouTube Music refreshed its session. Please retry."))
        } catch(e:Exception) {
            // A 401 on a signed-in request means the session likely expired: confirm it and say so.
            if(signedIn && isAuthFailure(e)) {checkSessionIfDue(forced=true);return Result.failure(IllegalStateException(SESSION_EXPIRED_MESSAGE))}
            Result.failure(IllegalStateException(providerErrorMessage(e)))
        }
    }
    suspend fun discover(query: String, token: String? = null, filter: String = "All"): Result<CatalogPage> = providerResult {
        val revision = authRevision
        val params = when(filter) {
            "Songs" -> songsFilter
            "Videos" -> "EgWKAQIQAWoMEA4QChADEAQQCRAF"
            "Albums" -> "EgWKAQIYAWoMEA4QChADEAQQCRAF"
            "Artists" -> "EgWKAQIgAWoMEA4QChADEAQQCRAF"
            "Playlists" -> "Eg-KAQwIABAAGAAgACgBMABqChAEEAMQCRAFEAo%3D"
            "Podcasts" -> "EgWKAQJQAWoMEA4QChADEAQQCRAF"
            else -> null
        }
        paceSearch()
        val response = innerTube.search(YouTubeClient.WEB_REMIX,query = query.trim().take(100),params = params,
            continuation = token,setLogin = signedIn)
        check(response.status.value < 400) { "Search unavailable (HTTP ${response.status.value})" }
        check(revision == authRevision) { "Account changed while searching" }
        fun matches(card: ProviderHomeCard): Boolean = when(filter) {
            "Videos" -> card.videoId!=null && card.kind==CatalogKind.Song
            "Albums" -> card.kind == CatalogKind.Album
            "Artists" -> card.kind == CatalogKind.Artist
            "Playlists" -> card.kind == CatalogKind.Playlist
            "Podcasts" -> card.kind in setOf(CatalogKind.Podcast,CatalogKind.Episode)
            "Songs" -> card.kind == CatalogKind.Song && card.videoId != null
            else -> true
        }
        var page = catalogPage(json.parseToJsonElement(response.bodyAsText()))
        if(token==null && params!=null && page.cards.none(::matches)) {
            paceSearch()
            val mixed=innerTube.search(YouTubeClient.WEB_REMIX,query=query.trim().take(100),setLogin=signedIn)
            if(mixed.status.value<400) {
                val root=json.parseToJsonElement(mixed.bodyAsText())
                val dynamic=catalogSearchFilter(root,filter)
                val mixedPage=catalogPage(root)
                if(dynamic!=null && dynamic!=params) {
                    paceSearch()
                    val filtered=innerTube.search(YouTubeClient.WEB_REMIX,query=query.trim().take(100),params=dynamic,setLogin=signedIn)
                    if(filtered.status.value<400) page=catalogPage(json.parseToJsonElement(filtered.bodyAsText()))
                }
                if(page.cards.none(::matches)) page=mixedPage.copy(next=null)
            }
        }
        check(revision == authRevision) { "Account changed while searching" }
        page.copy(cards=page.cards.filter(::matches))
    }

    suspend fun browseCatalog(id: String, token: String? = null): Result<CatalogPage> = providerResult {
        require(id.matches(Regex("[A-Za-z0-9_-]{2,200}"))) { "Collection is unavailable" }
        if(token!=null) requestCatalog(id,token) else collectionCache.get(feedKey("collection:$id")) {requestCatalog(id)}
    }
    suspend fun completeCollection(id:String):Result<CatalogPage> = providerResult {
        collectionCache.get(feedKey("collection:$id")) {completeCollectionFresh(id).getOrThrow()}
    }
    // Pages loaded so far for collections still fetching their continuations, so screens can render before the last page.
    private val partialCollections=mutableStateMapOf<String,CatalogPage>()
    fun partialCollection(id:String):CatalogPage?=partialCollections[feedKey("collection:$id")]
    private suspend fun completeCollectionFresh(id:String):Result<CatalogPage> = providerResult {
        val revision=authRevision;val key=feedKey("collection:$id")
        fun styled(page:CatalogPage)=if(id=="VLLM") page.copy(artworkUrl=likedArtwork()) else page
        try {
            // Collection pages arrive one after another, so they skip the search pacer rather than queueing 800 ms apart.
            val first=requestCollectionPage(id)
            var page=first.copy(next=first.collectionNext)
            val seen=mutableSetOf<String>()
            while(page.next!=null && seen.add(page.next!!)) {
                check(page.albumTracks.size<2000) {"This collection exceeds the supported 2,000-song queue"}
                if(revision==authRevision) partialCollections[key]=styled(page.copy(next=null))
                val more=requestCollectionPage(id,page.next)
                page=page.copy(cards=page.cards+more.cards,albumTracks=page.albumTracks+more.albumTracks,next=more.collectionNext)
            }
            check(page.albumTracks.size<=2000) {"This collection exceeds the supported 2,000-song queue"}
            check(revision==authRevision) {"Account changed while loading this collection"}
            styled(page)
        } finally {partialCollections.remove(key)}
    }
    private fun likedArtwork()="android.resource://${appContext.packageName}/${R.drawable.liked_music_art}"
    private fun libraryArtwork(page:CatalogPage)=page.copy(cards=page.cards.map {if(it.browseId=="VLLM") it.copy(artworkUrl=likedArtwork()) else it})
    suspend fun libraryCatalog(kind:String,token:String?=null):Result<CatalogPage> = providerResult {
        if(token!=null) libraryCatalogFresh(kind,token).getOrThrow()
        else collectionCache.get(feedKey("library:$libraryRevision:$kind")) {libraryArtwork(libraryCatalogFresh(kind).getOrThrow())}
    }
    private suspend fun libraryCatalogFresh(kind: String, token: String? = null): Result<CatalogPage> = providerResult {
        check(signedIn) { "Sign in to view your YouTube Music library" }
        requestCatalog(when(kind) { "Playlists" -> "FEmusic_liked_playlists"; "Albums" -> "FEmusic_liked_albums"
            "Artists" -> "FEmusic_library_corpus_track_artists"; "Podcasts" -> "FEmusic_library_non_music_audio_list"; "All" -> "FEmusic_library_landing"; else -> "FEmusic_liked_videos" },token)
    }
    suspend fun podcastSections(genre:String="For you"):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey("podcasts:$genre")) {
            if(genre=="For you") podcastSectionsFresh().getOrThrow() else discover("$genre podcasts",filter="Podcasts").getOrThrow().let {listOf(ProviderHomeSection(genre,it.cards))}
        }
    }
    private suspend fun podcastSectionsFresh(): Result<List<ProviderHomeSection>> = providerResult {
        homeSections().getOrThrow()
        val existing=cachedHome.filter(::podcastShelf)
        val endpoint=podcastEndpoint
        val page=if(endpoint!=null) try {requestCatalog(endpoint.first,params=endpoint.second)}
            catch(e:CancellationException) {throw e} catch(_:Exception) {null} else null
        val shelves=(page?.shelves.orEmpty()+existing).distinctBy(ProviderHomeSection::title)
        if(shelves.isNotEmpty()) shelves else {
            val shows=discover("podcasts",filter="Podcasts").getOrThrow().cards
            if(shows.isEmpty()) emptyList() else listOf(ProviderHomeSection("Discover shows",shows))
        }
    }
    suspend fun scanLibraryPlaylists():Result<List<ProviderHomeCard>> = providerResult {
        val revision=authRevision
        var page=libraryCatalogFresh("Playlists").getOrThrow()
        val cards=page.cards.toMutableList();val seen=mutableSetOf<String>()
        while(page.next!=null && seen.add(page.next!!) && cards.size<2000) {page=libraryCatalogFresh("Playlists",page.next).getOrThrow();cards.addAll(page.cards)}
        check(revision==authRevision) {"Account changed while scanning library"}
        val latest=libraryArtwork(CatalogPage(cards)).cards.filter {it.kind==CatalogKind.Playlist}.distinctBy {it.key()}
        val old=cachedLibrary("Playlists")?.cards.orEmpty()
        old.filter {c->latest.none {it.key()==c.key()}}.forEach {collectionCache.invalidate(feedKey("collection:${it.browseId}"))}
        collectionCache.put(feedKey("library:$libraryRevision:Playlists"),CatalogPage(latest))
        libraryPlaylistSnapshot=latest;libraryScanRevision++
        cachedLibrary("All")?.let {collectionCache.put(feedKey("library:$libraryRevision:All"),it.copy(cards=it.cards.filterNot {c->c.kind==CatalogKind.Playlist}+latest))}
        latest
    }
    fun refreshLibraryIndex() {runtimeScope.launch {if(signedIn) scanLibraryPlaylists()}}
    suspend fun editablePlaylists():Result<List<ProviderHomeCard>> = providerResult {
        val list=scanLibraryPlaylists().getOrThrow()
        list.filter {it.browseId!=null && it.browseId!="VLLM" && it.browseId!="VLSE"}
    }
    private suspend fun playlistMutation(id:String,request:suspend ()->io.ktor.client.statement.HttpResponse):Result<Unit> = sessionSafeMutation {
        check(signedIn) {"Sign in to edit YouTube Music playlists"}
        require(id.startsWith("VL") && id.length>2 && id!="VLLM") {"Choose an editable playlist"}
        val revision=authRevision;val response=request();val body=response.bodyAsText()
        check(response.status.value<400 && playlistEditAccepted(body)) {"YouTube Music could not update this playlist. It may be read-only."}
        check(revision==authRevision) {"Account changed while updating playlist"}
        collectionCache.invalidate(feedKey("collection:$id"));libraryRevision++
    }
    suspend fun addPlaylistSong(id:String,videoId:String):Result<Unit> = playlistMutation(id) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        innerTube.addToPlaylist(YouTubeClient.WEB_REMIX,id,videoId)
    }
    suspend fun removePlaylistSong(id:String,card:ProviderHomeCard):Result<Unit> = playlistMutation(id) {
        val setId=card.setVideoId?:error("YouTube Music did not supply this playlist entry's ID")
        innerTube.removePlaylistSong(YouTubeClient.WEB_REMIX,id,setId,requireNotNull(card.videoId))
    }
    suspend fun artistFor(track: ProviderTrack): Result<String?> = providerResult {
        track.artistId ?: run {
            val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = track.videoId)
            check(response.status.value < 400) { "Artist is unavailable" }
            val root = json.parseToJsonElement(response.bodyAsText())
            val panel = parseQueueTracks(root).firstOrNull { it.videoId == track.videoId }
            panel?.artistId ?: discover(playerArtistLabel(track.artist),filter = "Artists").getOrThrow().cards
                .firstOrNull { lyricsNormalize(it.title) == lyricsNormalize(playerArtistLabel(track.artist)) }?.browseId
        }
    }
    suspend fun albumFor(videoId: String, known: String?): Result<String?> = providerResult {
        known ?: run {
            val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId)
            check(response.status.value<400) {"Album is unavailable"}
            parseQueueTracks(json.parseToJsonElement(response.bodyAsText())).firstOrNull {it.videoId==videoId}?.albumId
        }
    }
    suspend fun recommendations(videoId: String, token: String? = null): Result<Pair<List<ProviderTrack>,String?>> = providerResult {
        val revision=authRevision
        val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId,playlistId="RDAMVM$videoId",params="wAEB",continuation=token)
        check(response.status.value<400) {"Up next is unavailable"}
        val root=json.parseToJsonElement(response.bodyAsText())
        var tracks=parseQueueTracks(root)
        var next=catalogContinuation(root)
        if(tracks.none {it.videoId!=videoId} && next!=null && next!=token) {
            val more=innerTube.next(YouTubeClient.WEB_REMIX,videoId=videoId,playlistId="RDAMVM$videoId",params="wAEB",continuation=next)
            if(more.status.value<400) {
                val data=json.parseToJsonElement(more.bodyAsText());tracks=(tracks+parseQueueTracks(data)).distinctBy(ProviderTrack::videoId);next=catalogContinuation(data)
            }
        }
        if(tracks.none {it.videoId!=videoId}) {
            val related=relatedBrowseId(root)?.let {id->try {requestCatalog(id)}
                catch(e:CancellationException){throw e} catch(_:Exception){null}}
            tracks=(tracks+related?.cards.orEmpty().mapNotNull {it.track()}).distinctBy(ProviderTrack::videoId)
        }
        if(tracks.none {it.videoId!=videoId}) {
            // Some sessions expose a single-item radio; artist catalogue rows still come from YouTube Music.
            val artist=tracks.firstOrNull {it.videoId==videoId}?.artist
            if(!artist.isNullOrBlank()) tracks=(tracks+searchPage(playerArtistLabel(artist)).getOrThrow().tracks).distinctBy(ProviderTrack::videoId)
        }
        check(revision==authRevision) {"Account changed while loading up next"}
        tracks to next
    }
    suspend fun saveAlbum(browseId:String):Result<Unit> = providerResult {
        check(signedIn) {"Sign in to save albums on YouTube Music"}
        val revision=authRevision
        val response=innerTube.browse(YouTubeClient.WEB_REMIX,browseId=browseId,setLogin=true)
        check(response.status.value<400) {"Could not read album"}
        val root=json.parseToJsonElement(response.bodyAsText())
        val header=catalogRenderer(root,"musicResponsiveHeaderRenderer")?:catalogRenderer(root,"musicDetailHeaderRenderer")
        val playlist=if(browseId.startsWith("VL")) browseId.removePrefix("VL") else catalogValue(header,"playlistId")?:error("This album cannot be saved from its current provider response")
        val saved=innerTube.likePlaylist(YouTubeClient.WEB_REMIX,playlist)
        check(saved.status.value<400 && revision==authRevision) {"Could not save album"}
        libraryRevision++
    }
    suspend fun loadLike(videoId: String): Result<Boolean?> = providerResult {
        if (!signedIn) return@providerResult null
        val revision = authRevision
        val response = innerTube.next(YouTubeClient.WEB_REMIX,videoId = videoId)
        check(response.status.value < 400) { "Could not read like status" }
        val value = parseLikeStatus(json.parseToJsonElement(response.bodyAsText()))
        if (revision == authRevision && signedIn && value != null) songLikes[videoId] = value
        value
    }
    suspend fun setSongLike(videoId: String, liked: Boolean): Result<Unit> = providerResult {
        likeGate.withLock {
            check(signedIn) { "Sign in to like songs on YouTube Music" }
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
            val revision = authRevision
            val response = if(liked) innerTube.likeVideo(YouTubeClient.WEB_REMIX,videoId)
                else innerTube.unlikeVideo(YouTubeClient.WEB_REMIX,videoId)
            check(response.status.value < 400) { "Could not update the YouTube Music like" }
            val body = response.bodyAsText()
            check(body.isBlank() || (json.parseToJsonElement(body) as? JsonObject)?.get("error") == null) { "YouTube Music did not accept the like" }
            check(revision == authRevision && signedIn) { "Account changed while saving" }
            songLikes[videoId] = liked; libraryRevision++
        }
    }
    suspend fun liveStations(category:String="Featured"):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey(radioFeedKey(category))) {liveStationsFresh(category).getOrThrow()}
    }
    private val radioGate=kotlinx.coroutines.sync.Semaphore(3)
    private suspend fun liveStationsFresh(category:String):Result<List<ProviderHomeSection>> = providerResult {
        val shelves=coroutineScope {channelsForRadio(category).map {channel->async(Dispatchers.IO) {
            feedCache.get(feedKey("radio-channel:${channel.handle}")) {RadioShelf(loadRadioChannel(channel))}.shelf
        }}.awaitAll().filterNotNull()}
        val unique=uniqueRadioShelves(shelves)
        if(category=="Featured") check(unique.isNotEmpty()) {"No live stations could be reached. Try again when online."}
        unique
    }
    private data class RadioShelf(val shelf:ProviderHomeSection?)
    private suspend fun loadRadioChannel(channel:RadioChannel):ProviderHomeSection? {
            radioGate.acquire()
            return try {withTimeoutOrNull(18_000) {
                val response=http.get("https://www.youtube.com/@${channel.handle}/streams") {
                    header("User-Agent","Mozilla/5.0");header("Accept-Language","en-US,en;q=0.9")
                }
                if(response.status.value>=400) null else {
                    val root=youtubeInitialData(response.bodyAsText())
                    val cards=root?.let(::parseCatalogCards).orEmpty().filter {it.kind==CatalogKind.Live && it.videoId!=null}.map {it.copy(subtitle=channel.name)}
                    if(cards.isEmpty()) null else ProviderHomeSection(channel.name,cards)
                }
            }} catch(e:CancellationException) {throw e} catch(_:Exception) {null} finally {radioGate.release()}
    }

    suspend fun search(query: String): Result<List<ProviderTrack>> =
        searchPage(query).map(ProviderSearchPage::tracks)

    suspend fun lyricsFor(videoId: String, durationMs: Long): LyricsDocument? = withContext(Dispatchers.IO) {
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return@withContext null
        val revision = authRevision
        val next = innerTube.next(YouTubeClient.WEB_REMIX, videoId = videoId)
        if (next.status.value >= 400) return@withContext null
        val root = json.parseToJsonElement(next.bodyAsText())
        fun lyricsId(node: JsonElement): String? = when (node) {
            is JsonObject -> (node["browseId"] as? JsonPrimitive)?.content?.takeIf { it.startsWith("MPLY") }
                ?: node.values.firstNotNullOfOrNull(::lyricsId)
            is JsonArray -> node.firstNotNullOfOrNull(::lyricsId)
            else -> null
        }
        val browseId = lyricsId(root) ?: return@withContext null
        // Native timed lyrics are public metadata. Keep this request free of web-only
        // nullable/account fields, which make the mobile endpoint reject the body.
        // The shared HTTP client has no default account headers; InnerTube adds them
        // only to its own signed requests. Song lookup and web fallback stay signed.
        val synced = try {
            val locale = innerTube.locale
            val response = http.post("https://music.youtube.com/youtubei/v1/browse") {
                contentType(ContentType.Application.Json)
                setBody(TextContent(youtubeTimedLyricsBody(browseId, locale.hl, locale.gl).toString(), ContentType.Application.Json))
            }
            if (response.status.value >= 400 || revision != authRevision) null
            else parseYoutubeLyrics(json.parseToJsonElement(response.bodyAsText()), durationMs)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (revision != authRevision) return@withContext null
        if (synced?.timed == true) return@withContext synced
        val web = try {
            val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId, setLogin = signedIn)
            if (response.status.value >= 400 || revision != authRevision) null
            else parseYoutubeLyrics(json.parseToJsonElement(response.bodyAsText()), durationMs)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (revision != authRevision) null else listOfNotNull(synced, web).maxByOrNull { it.quality }
    }

    suspend fun homeSections(mood:String=""):Result<List<ProviderHomeSection>> = providerResult {
        feedCache.get(feedKey("home:$mood")) {
            if(mood.isBlank()) homeSectionsFresh().getOrThrow() else {
                if(homeMoods.isEmpty()) homeSections().getOrThrow()
                val tag=homeMoods.firstOrNull {it.title==mood}?:error("This mood is no longer available")
                val page=requestCatalog(tag.browseId,params=tag.params)
                page.shelves.ifEmpty {listOf(ProviderHomeSection(mood,page.cards))}
            }
        }
    }
    private suspend fun homeSectionsFresh(): Result<List<ProviderHomeSection>> = withContext(Dispatchers.IO) {
        try {
            val revision = authRevision
            val response = innerTube.browse(YouTubeClient.WEB_REMIX,
                browseId = "FEmusic_home", setLogin = signedIn)
            if (response.status.value >= 400) return@withContext Result.failure(
                IllegalStateException("YouTube Music home is unavailable (HTTP ${response.status.value})"))
            val root = json.parseToJsonElement(response.bodyAsText())
            val sections = parseHomeSections(root).toMutableList()
            var continuation=catalogContinuation(root)
            var homePages=0
            while(sections.none {it.title.contains("forgotten fav",true)} && continuation!=null && homePages<3) {
                val token=continuation;homePages++
                val more=innerTube.browse(YouTubeClient.WEB_REMIX,browseId="FEmusic_home",continuation=token,setLogin=signedIn)
                if(more.status.value>=400) break
                val moreRoot=json.parseToJsonElement(more.bodyAsText())
                sections.addAll(parseHomeSections(moreRoot).filter {s->sections.none {it.title==s.title}})
                continuation=catalogContinuation(moreRoot)?.takeUnless {it==token}
            }
            if (revision == authRevision) {cachedHome = sections;podcastEndpoint=catalogBrowseFilter(root,"Podcasts");homeMoods=parseHomeMoods(root)
                runtimeScope.launch {runCatching {
                    val extra=innerTube.browse(YouTubeClient.WEB_REMIX,browseId="FEmusic_moods_and_genres",setLogin=signedIn)
                    if(extra.status.value<400 && revision==authRevision) homeMoods=(homeMoods+parseHomeMoods(json.parseToJsonElement(extra.bodyAsText()))).distinctBy {it.title}
                }}}
            val serverMessage = findRenderer(root, "messageRenderer")?.let { message ->
                providerText(message["text"]).ifBlank { providerText(message["message"]) }
            }
            if (sections.isEmpty()) return@withContext Result.failure(
                IllegalStateException(serverMessage?.takeIf(String::isNotBlank)
                    ?: "YouTube Music returned no home recommendations"))
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException("Account changed while loading home"))
            Result.success(sections)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(
                if (error is java.net.UnknownHostException)
                    "Cannot reach music.youtube.com on this network"
                else "Could not load YouTube Music home (${error::class.simpleName})"))
        }
    }

    suspend fun recentSongs(): Result<List<ProviderTrack>> = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext Result.success(emptyList())
        try {
            val revision = authRevision
            val response = innerTube.browse(YouTubeClient.WEB_REMIX,
                browseId = "FEmusic_history", setLogin = true)
            if (response.status.value >= 400) return@withContext Result.failure(
                IllegalStateException("YouTube Music history is unavailable"))
            val tracks = parseTracks(json.parseToJsonElement(response.bodyAsText()), 30)
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException("Account changed while loading history"))
            Result.success(tracks)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(
                "Could not load listening history (${error::class.simpleName})"))
        }
    }

    suspend fun profile():Result<ProviderProfile?> = providerResult {
        feedCache.get(feedKey("profile")) {Unit to profileFresh().getOrThrow()}.second.also {accountProfile=it}
    }
    private suspend fun profileFresh(): Result<ProviderProfile?> = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext Result.success(null)
        try {
            val revision = authRevision
            val response = innerTube.accountMenu(YouTubeClient.WEB_REMIX)
            var profile = if (response.status.value < 400)
                parseAccountProfile(json.parseToJsonElement(response.bodyAsText())) else null
            if (profile == null || profile.name.isBlank() || profile.artworkUrl == null) {
                try {
                    val accounts = innerTube.accountsList(YouTubeClient.WEB_REMIX)
                    if (accounts.status.value < 400) {
                        val selected = parseAccountProfile(json.parseToJsonElement(accounts.bodyAsText()))
                        if (selected != null) profile = ProviderProfile(
                            profile?.name?.takeIf(String::isNotBlank) ?: selected.name,
                            profile?.artworkUrl ?: selected.artworkUrl)
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Keep a valid partial account profile. */ }
            }
            if (authRevision != revision || !signedIn) return@withContext Result.failure(
                IllegalStateException("Account changed while loading profile"))
            Log.i("RiffProfile", "loaded name=${!profile?.name.isNullOrBlank()} photo=${profile?.artworkUrl != null}")
            accountProfile = profile
            Result.success(profile)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Result.failure(IllegalStateException(
                "Could not load account profile (${error::class.simpleName})"))
        }
    }

    /** Read-only account library browse. This does not mutate YouTube Music likes. */
    suspend fun likedSongsPage(nextToken: String? = null): Result<ProviderSearchPage> =
        withContext(Dispatchers.IO) {
            if (!signedIn) return@withContext Result.failure(
                IllegalStateException("Sign in to view your YouTube Music liked songs"))
            val revision = authRevision
            try {
                paceSearch()
                val response = innerTube.browse(YouTubeClient.WEB_REMIX,
                    browseId = "VLLM", continuation = nextToken, setLogin = true)
                if (response.status.value >= 400) return@withContext Result.failure(
                    IllegalStateException("Account library is unavailable (HTTP ${response.status.value})"))
                val root = json.parseToJsonElement(response.bodyAsText())
                if (authRevision != revision || !signedIn) return@withContext Result.failure(
                    IllegalStateException("Account changed while loading the library"))
                val tracks = parseTracks(root, limit = 2000)
                Result.success(ProviderSearchPage(tracks, findContinuation(root)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(IllegalStateException(
                    "Could not read the account library (${error::class.simpleName})"))
            }
        }

    suspend fun collectionPage(browseId: String, nextToken: String? = null): Result<ProviderSearchPage> =
        withContext(Dispatchers.IO) {
            if (!browseId.matches(Regex("[A-Za-z0-9_-]{2,200}"))) return@withContext Result.failure(
                IllegalStateException("Collection is unavailable"))
            val revision = authRevision
            try {
                paceSearch()
                val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = browseId,
                    continuation = nextToken, setLogin = signedIn)
                if (response.status.value >= 400) return@withContext Result.failure(
                    IllegalStateException("Collection is unavailable (HTTP ${response.status.value})"))
                val root = json.parseToJsonElement(response.bodyAsText())
                if (revision != authRevision) return@withContext Result.failure(
                    IllegalStateException("Account changed while loading the collection"))
                Result.success(ProviderSearchPage(parseTracks(root, limit = 2000), findContinuation(root)))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { Result.failure(IllegalStateException(
                "Could not load collection (${error::class.simpleName})")) }
        }

    suspend fun searchPage(query: String, nextToken: String? = null): Result<ProviderSearchPage> = withContext(Dispatchers.IO) {
        try {
            val revision = authRevision
            val useLogin = signedIn
            val cleanQuery = query.trim().take(100)
            val key = cleanQuery.lowercase() + "\u0000" + nextToken.orEmpty()
            val cached = synchronized(cacheLock) { searchCache[key] }
            if (cached != null && authRevision == revision &&
                SystemClock.elapsedRealtime() - cached.first < 5 * 60_000L) {
                return@withContext Result.success(cached.second)
            }
            paceSearch()
            val response = innerTube.search(YouTubeClient.WEB_REMIX,
                query = cleanQuery,
                params = songsFilter,
                continuation = nextToken, setLogin = useLogin)
            if (response.status.value == 429) return@withContext Result.failure(
                IllegalStateException("YouTube Music search is temporarily rate limited. Try again shortly."))
            if (response.status.value >= 400) return@withContext Result.failure(
                IllegalStateException("YouTube Music search is unavailable (HTTP ${response.status.value})."))
            var root = json.parseToJsonElement(response.bodyAsText())
            var mixedShelves = 0
            var mixedRows = 0
            var mixedSongTracks = emptyList<ProviderTrack>()
            var liveChipFound = false
            var liveFilterTried = false
            if (nextToken == null && parseTracks(root).isEmpty()) {
                paceSearch()
                val mixed = innerTube.search(YouTubeClient.WEB_REMIX,
                    query = cleanQuery, setLogin = useLogin)
                if (mixed.status.value == 429) return@withContext Result.failure(
                    IllegalStateException("YouTube Music search is temporarily rate limited. Try again shortly."))
                if (mixed.status.value < 400) {
                    val mixedRoot = json.parseToJsonElement(mixed.bodyAsText())
                    mixedShelves = countKey(mixedRoot, "musicShelfRenderer")
                    mixedRows = parseTracks(mixedRoot).size
                    mixedSongTracks = parseSongShelfTracks(mixedRoot)
                    val liveFilter = findSongsFilter(mixedRoot)
                    liveChipFound = !liveFilter.isNullOrBlank()
                    if (!liveFilter.isNullOrBlank() && liveFilter != songsFilter) {
                        liveFilterTried = true
                        paceSearch()
                        val refreshed = innerTube.search(YouTubeClient.WEB_REMIX,
                            query = cleanQuery, params = liveFilter, setLogin = useLogin)
                        if (refreshed.status.value == 429) return@withContext Result.failure(
                            IllegalStateException("YouTube Music search is temporarily rate limited. Try again shortly."))
                        if (refreshed.status.value < 400)
                            root = json.parseToJsonElement(refreshed.bodyAsText())
                    }
                }
            }
            val filteredTracks = parseTracks(root)
            if (authRevision != revision) return@withContext Result.failure(
                IllegalStateException("Account changed while searching"))
            val useMixedSongs = nextToken == null && filteredTracks.isEmpty() &&
                mixedSongTracks.isNotEmpty()
            val tracks = if (useMixedSongs) mixedSongTracks else filteredTracks
            if (nextToken == null && tracks.isEmpty() && countKey(root, "musicShelfRenderer") == 0) {
                Log.i("RiffSearch", "no_song_rows filtered_shelves=0 " +
                    "mixed_shelves=$mixedShelves mixed_rows=$mixedRows " +
                    "mixed_song_rows=${mixedSongTracks.size} songs_chip=$liveChipFound " +
                    "dynamic_retry=$liveFilterTried")
                Result.failure(IllegalStateException("Song catalog unavailable on this device"))
            } else {
                val page = ProviderSearchPage(tracks,
                    if (useMixedSongs) null else findContinuation(root))
                synchronized(cacheLock) {
                    if (authRevision == revision)
                        searchCache[key] = SystemClock.elapsedRealtime() to page
                }
                Result.success(page)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException("YouTube Music search is unavailable (${error::class.simpleName})"))
        }
    }

    private data class VideoPair(val id:String?)
    suspend fun videoFor(track:ProviderTrack):Result<String?> = providerResult {
        feedCache.get(feedKey("video-pair:${track.videoId}")) {
            val response=innerTube.next(YouTubeClient.WEB_REMIX,videoId=track.videoId,playlistId="RDAMVM${track.videoId}")
            check(response.status.value<400) {"Could not check the YouTube Music video switcher"}
            VideoPair(musicVideoPair(json.parseToJsonElement(response.bodyAsText()),track))
        }.id
    }
    /** Signed URLs stay in a bounded RAM cache and never enter the durable music database. */
    suspend fun resolve(videoId:String,live:Boolean=false,excludedProfiles:Set<String> = emptySet(),video:Boolean=false,download:Boolean=false):Result<ProviderStream> {
        val key="$authRevision:$videoId:$live:$video:$download"
        if(excludedProfiles.isNotEmpty()) {synchronized(cacheLock) {streamCache.remove(key)};return resolveFresh(videoId,live,excludedProfiles,video,download)}
        val flight=synchronized(cacheLock) {
            streamCache[key]?.takeIf { reusableStream(it.expiresAtMs,System.currentTimeMillis(),live) }?.let {return Result.success(it)}
            streamFlights[key] ?: runtimeScope.async {
                val result=withTimeout(60_000) {
                    try {resolveFresh(videoId,live,emptySet(),video,download)}
                    catch(e:CancellationException) {
                        currentCoroutineContext().ensureActive()
                        playbackEvents.append("provider_session_refresh_retry")
                        delay(150);resolveFresh(videoId,live,emptySet(),video,download)
                    }
                }
                result.getOrNull()?.let {stream->synchronized(cacheLock) {streamCache[key]=stream;while(streamCache.size>32) streamCache.remove(streamCache.keys.first())}}
                result
            }.also {job->streamFlights[key]=job;job.invokeOnCompletion {synchronized(cacheLock) {if(streamFlights[key]===job) streamFlights.remove(key)}}}
        }
        return flight.await()
    }
    /** Drops the minted attestation so the next stream mints a fresh one (it can go stale after a network change). */
    suspend fun invalidateAttestation() = streamTokens.invalidateAttestation()
    fun invalidateStream(videoId:String) {synchronized(cacheLock) {streamCache.keys.filter {it.contains(":$videoId:")}.forEach {streamCache.remove(it)}}}
    private suspend fun resolveFresh(videoId: String, live: Boolean, excludedProfiles: Set<String>, video:Boolean,download:Boolean): Result<ProviderStream> = withContext(Dispatchers.IO) {
        try {
            val revision = authRevision
            require(videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) { "Invalid video ID" }
            val stream = extractor.extract(
                videoId = videoId,
                hints = ContentHints(wantVideo = video,isLive = live,maxVideoHeight=720).withStreamCapabilities(
                    allowHls = !download, allowSabr = true, allowBoundedRange = download),
                excludedClients = excludedProfiles + if(live || video) setOf("WEB_REMIX","WEB_REMIX_SABR","ANDROID_MUSIC","IOS_MUSIC") else emptySet(),
                audioQuality = if(download) AudioQuality.HIGH else AudioQuality.AUTO,
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
                val processed=if(cipherIsolated) remoteCipher.processNParameter(config.playerUrl,challengeUrl)
                    else streamCipher.processNParameter(config.playerUrl,challengeUrl)
                processed?.toHttpUrl()?.queryParameter("n")
            } else stream.audioUrl
            playbackEvents.append("manifest_ready hls=$hls")
            val videoUrl=stream.videoUrl?.takeUnless {it==stream.audioUrl}
            Result.success(ProviderStream(audioUrl, stream.headers, stream.mimeType,
                stream.sabrBootstrap,stream.profileId,videoUrl,stream.videoMimeType,stream.sabrVideoBootstrap,videoId,
                stream.itag,stream.bitrate?:0,stream.contentLengthBytes,maxOf((stream.mediaMetadata?.durationSeconds?:0)*1000,stream.sabrBootstrap?.durationMs?:0),
                minOf(stream.expiresAt?.toEpochMilliseconds()?:Long.MAX_VALUE,System.currentTimeMillis()+20*60_000),
                stream.requireBoundedRange || stream.useRangeChunks,video || live,stream.mediaMetadata?.title.orEmpty(),stream.mediaMetadata?.author.orEmpty(),stream.mediaMetadata?.channelId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            Result.failure(IllegalStateException(if (error.reason ==
                    StreamResolveException.Reason.NO_PLAYABLE_STREAM)
                "No supported audio stream is available for this track"
            else "Track unavailable: ${error.reason}"))
        } catch (error: Exception) {
            playbackEvents.append("stream_prepare_failed type=${error::class.simpleName}")
            Result.failure(IllegalStateException("Could not prepare this track (${error::class.simpleName})"))
        }
    }

    override fun close() {
        runtimeScope.cancel()
        streamTokens.dispose()
        innerTube.close()
        http.close()
    }

    private suspend fun paceSearch() {
        searchPacer.withLock {
            val waitMs = (lastSearchMs + 800L - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
            lastSearchMs = SystemClock.elapsedRealtime()
        }
    }

    private fun findSongsFilter(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val chip = node["chipCloudChipRenderer"] as? JsonObject
            val label = (chip?.get("text") as? JsonObject)?.get("runs") as? JsonArray
            val name = label?.joinToString("") { run ->
                ((run as? JsonObject)?.get("text"))?.jsonPrimitive?.content.orEmpty()
            }
            val endpoint = chip?.get("navigationEndpoint") as? JsonObject
            val search = endpoint?.get("searchEndpoint") as? JsonObject
            val params = search?.get("params")?.jsonPrimitive?.content
            if (name.equals("Songs", ignoreCase = true) && !params.isNullOrBlank()) params
            else node.values.firstNotNullOfOrNull(::findSongsFilter)
        }
        is JsonArray -> node.firstNotNullOfOrNull(::findSongsFilter)
        else -> null
    }

    private fun providerText(node: JsonElement?): String = when (node) {
        is JsonObject -> node["simpleText"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?: (node["runs"] as? JsonArray)?.joinToString("") {
                (it as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
            }.orEmpty()
        else -> node?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
    }

    private fun findRenderer(node: JsonElement, key: String): JsonObject? = when (node) {
        is JsonObject -> (node[key] as? JsonObject)
            ?: node.values.firstNotNullOfOrNull { findRenderer(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { findRenderer(it, key) }
        else -> null
    }

    private fun providerValue(node: JsonElement?, key: String): String? = when (node) {
        is JsonObject -> node[key]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        } ?: node.values.firstNotNullOfOrNull { providerValue(it, key) }
        is JsonArray -> node.firstNotNullOfOrNull { providerValue(it, key) }
        else -> null
    }

    internal fun parseHomeCard(node: JsonElement): ProviderHomeCard? {
        return parseCatalogCards(node).firstOrNull()
    }

    private fun parseHomeSections(root: JsonElement): List<ProviderHomeSection> {
        val sections = mutableListOf<ProviderHomeSection>()
        fun visit(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val shelf = listOf("musicCarouselShelfRenderer", "musicShelfRenderer",
                        "musicImmersiveCarouselShelfRenderer")
                        .firstNotNullOfOrNull { node[it] as? JsonObject }
                    if (shelf != null) {
                        val header = shelf["header"] as? JsonObject
                        val title = providerText((header?.get("musicCarouselShelfBasicHeaderRenderer")
                            as? JsonObject)?.get("title")).ifBlank {
                            providerText(shelf["title"])
                        }
                        val cards = (shelf["contents"] as? JsonArray)
                            ?.mapNotNull(::parseHomeCard)?.distinctBy {
                                it.videoId ?: it.browseId ?: it.title
                            }.orEmpty()
                        if (title.isNotBlank() && cards.isNotEmpty())
                            sections += ProviderHomeSection(title, cards)
                    }
                    node.values.forEach(::visit)
                }
                is JsonArray -> node.forEach(::visit)
                else -> Unit
            }
        }
        visit(root)
        return sections.distinctBy(ProviderHomeSection::title)
    }

    private fun parseTracks(root: JsonElement, limit: Int = 30): List<ProviderTrack> {
        return parseCatalogCards(root).mapNotNull { it.track() }.distinctBy(ProviderTrack::videoId).take(limit)
    }

    private fun parseSongShelfTracks(node: JsonElement): List<ProviderTrack> = when (node) {
        is JsonObject -> {
            val shelf = node["musicShelfRenderer"] as? JsonObject
            val title = shelf?.get("title") as? JsonObject
            val runs = title?.get("runs") as? JsonArray
            val name = runs?.joinToString("") { run ->
                (run as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
            }
            if (name.equals("Songs", ignoreCase = true) && shelf != null) parseTracks(shelf)
            else node.values.flatMap(::parseSongShelfTracks).distinctBy(ProviderTrack::videoId)
        }
        is JsonArray -> node.flatMap(::parseSongShelfTracks).distinctBy(ProviderTrack::videoId)
        else -> emptyList()
    }

    private fun countKey(node: JsonElement, key: String): Int = when (node) {
        is JsonObject -> (if (key in node) 1 else 0) + node.values.sumOf { countKey(it, key) }
        is JsonArray -> node.sumOf { countKey(it, key) }
        else -> 0
    }

    private fun findContinuation(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val continuations = node["continuations"] as? JsonArray
            val shelfToken = continuations?.firstNotNullOfOrNull { entry ->
                val data = (entry as? JsonObject)?.get("nextContinuationData") as? JsonObject
                data?.get("continuation")?.jsonPrimitive?.content
            }
            if (!shelfToken.isNullOrBlank()) shelfToken else {
                val item = node["continuationItemRenderer"] as? JsonObject
                val endpoint = item?.get("continuationEndpoint") as? JsonObject
                val command = endpoint?.get("continuationCommand") as? JsonObject
                val token = command?.get("token")?.jsonPrimitive?.content
                if (!token.isNullOrBlank()) token else node.values.firstNotNullOfOrNull(::findContinuation)
            }
        }
        is JsonArray -> node.firstNotNullOfOrNull(::findContinuation)
        else -> null
    }
}

internal const val OFFLINE_MESSAGE = "You're offline. Check your connection and try again."
internal const val TIMEOUT_MESSAGE = "YouTube Music took too long to answer. Try again."

/**
 * What a failed provider request tells the user. Network failures get plain words: the raw
 * message ("Unable to resolve host \"music.youtube.com\"...") showed on Podcasts when offline.
 */
internal fun providerErrorMessage(e: Throwable): String {
    val chain = generateSequence(e) { it.cause }.take(8).toList()
    if (chain.any { it is java.net.UnknownHostException || it is java.net.ConnectException ||
            it is java.net.NoRouteToHostException }) return OFFLINE_MESSAGE
    if (chain.any { it is java.net.SocketTimeoutException || it.javaClass.simpleName.contains("Timeout") }) return TIMEOUT_MESSAGE
    return e.message?.takeIf { !it.contains("http",true) && !it.contains("host",true) } ?: "YouTube Music could not load this content"
}
