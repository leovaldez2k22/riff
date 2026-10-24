package com.musicglass.personal

import androidx.core.content.edit
import android.webkit.CookieManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.content.TextContent
import io.ktor.client.request.setBody
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val COOKIE_IDENTITY = "cookie_identity"

/**
 * Who is signed in, and the one HTTP client that speaks for them. WebView owns the cookies; app-private
 * preferences keep the page session fields that are not cookies. Sign-in, sign-out, the check that Google
 * still accepts the session, and the opaque key this account's local data is filed under all live here.
 * The catalog, library and stream parts send their requests through [innerTube] and wrap them in
 * [providerResult]; none of them reads a cookie or builds an account header.
 */
internal class ProviderSessionManager(context: Context, internal val dependencies: RuntimeDependencies) : ProviderSession {
    internal val appContext = context.applicationContext
    /** Background work for the provider as a whole. Cancelled by [close]. */
    internal val scope = CoroutineScope(SupervisorJob() + dependencies.dispatchers.io)
    private val runtimeScope get() = scope
    private val resetListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    /** Runs [listener] whenever the session is replaced or ends, before the new generation is announced. */
    fun onReset(listener: () -> Unit) { resetListeners += listener }
    internal val playbackEvents = PlaybackEventLog(context)
    private val sessionPrefs = context.applicationContext.getSharedPreferences(
        "provider_session", Context.MODE_PRIVATE)
    override var signedIn by mutableStateOf(false)
        private set
    /** Whether Google still accepts the saved sign-in (W5); signedIn only means cookies exist. */
    override var sessionState by mutableStateOf(SessionState.SignedOut)
        private set
    private val sessionChecks=SessionCheckSchedule(dependencies.clocks::monotonicMillis)
    private val sessionProbeGate=Mutex()
    /** On resume at most every 6 hours; [forced] right after a 401 (at most every 30 s). */
    private var deferredSessionCheck:kotlinx.coroutines.Job?=null
    fun checkSessionIfDue()=checkSessionIfDue(false)
    override fun checkSessionIfDue(forced:Boolean) {
        if(!signedIn) {sessionState=SessionState.SignedOut;return}
        if(sessionChecks.due(forced)) {runtimeScope.launch {probeSession()};return}
        // A 401 right after a check must not be dropped: run it once the 30 s gap has passed.
        if(forced && deferredSessionCheck?.isActive!=true)
            deferredSessionCheck=runtimeScope.launch {dependencies.wait(sessionChecks.waitMs(forced=true));probeSession()}
    }
    private suspend fun probeSession() = sessionProbeGate.withLock {
        if(!signedIn) {sessionState=SessionState.SignedOut;return@withLock}
        if(detached) return@withLock
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
        check(BuildConfig.DEBUG) {"Session fault injection is limited to test builds"}
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
        internal set
    var authRevision by mutableIntStateOf(0)
        private set
    private var visitorData: String? = sessionPrefs.getString("visitor", null)
    private var dataSyncId: String? = sessionPrefs.getString("sync", null)
    private var authUser: String = sessionPrefs.getString("account", "0").orEmpty()
    private val scopeRegistry = AccountScopeRegistry(PreferenceScopeAliasStore(context))
    @Volatile private var resolvedStorage: Pair<String, ScopeStorage>? = null
    private val sessionNonce = java.util.UUID.randomUUID().toString()
    private val sessionFlow = MutableStateFlow(SessionToken(AccountScope.Guest, 0, sessionNonce))
    /** Active owner and generation. Capture before async work; validate before publishing or saving. */
    override val session: StateFlow<SessionToken> get() = sessionFlow
    private fun publishSession() { sessionFlow.value = SessionToken(accountScope(), authRevision.toLong(), sessionNonce) }
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val providerEngine = dependencies.providerTransport().also { (it as? OriginalAudioTransport)?.onEvent = playbackEvents::append }
    internal val http: HttpClient = HttpClient(providerEngine) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 12_000
            requestTimeoutMillis = 30_000
        }
        install(cookieRotationPlugin(enabled = { signedIn && !simulatedExpiry && !detached }, persist = ::persistRotatedCookies))
    }
    internal val innerTube: InnerTube = InnerTube(http)
    @Volatile private var simulatedExpiry = false
    /**
     * The sign-in browser writes to the same cookie store as the live session. When the store no longer holds
     * the cookies the committed account was signed in with (another account signed in there and the change
     * was not finished), Riff stops sending them: nothing reaches the other account under this one's name,
     * this account's local data stays, and the session asks to sign in again (Q18-010).
     */
    @Volatile internal var detached = false
        private set
    private val cookieLock = Any()
    private fun detachFromChangedCookies() {
        detached = true
        innerTube.replaceSession(cookie = null, visitorData = visitorData, dataSyncId = dataSyncId, authUser = authUser, useLoginForBrowse = false)
        if (sessionState != SessionState.Expired) playbackEvents.append("session_cookies_changed")
        sessionState = SessionState.Expired
    }
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
        val committed = sessionPrefs.getString(COOKIE_IDENTITY, null)
        if (committed != null && cookieIdentityDigest(current) != committed) { detachFromChangedCookies(); return@synchronized }
        if (signedIn && !simulatedExpiry) innerTube.replaceSession(cookie = current, visitorData = visitorData,
            dataSyncId = dataSyncId, authUser = authUser, useLoginForBrowse = true)
        playbackEvents.append("cookies_rotated count=${changed.size}")
    }
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
        refreshAccount()
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
        // A session from before this check is trusted once, as it is; later changes are not.
        val committed = sessionPrefs.getString(COOKIE_IDENTITY, null)
            ?: cookieIdentityDigest(cookies)?.takeIf { valid }?.also { sessionPrefs.edit { putString(COOKIE_IDENTITY, it) } }
        val changed = valid && committed != null && cookieIdentityDigest(cookies) != committed
        detached = false
        innerTube.replaceSession(cookie = cookies.takeIf { valid }, visitorData = visitorData,
            dataSyncId = dataSyncId, authUser = authUser, useLoginForBrowse = valid)
        signedIn = valid
        accountProfile = null
        // Everything read for the previous owner is dropped by the part that holds it.
        resetListeners.forEach { it() }
        authRevision++;publishSession()
        sessionChecks.reset();sessionState=if(valid) SessionState.Checking else SessionState.SignedOut
        if(changed) {detachFromChangedCookies();return false}
        if(valid && dependencies.startProviderBackgroundWork) runtimeScope.launch {dependencies.wait(1500);probeSession()}
        if(valid && dependencies.startProviderBackgroundWork) runtimeScope.launch {runCatching {reconcileAccountStorage()}}
        return valid
    }

    fun useWebSession(visitor: String?, syncId: String?, accountIndex: String?): Boolean {
        visitorData = visitor?.takeIf { it.isNotBlank() && it != "null" }
        dataSyncId = syncId?.substringBefore("||")?.takeIf { it.isNotBlank() && it != "null" }
        authUser = accountIndex?.filter(Char::isDigit)?.ifBlank { "0" } ?: "0"
        val identity = cookieIdentityDigest(CookieManager.getInstance().getCookie("https://music.youtube.com"))
        sessionPrefs.edit().putString("visitor", visitorData)
            .putString("sync", dataSyncId).putString("account", authUser).putString(COOKIE_IDENTITY, identity)
            .remove("signed_out").apply()
        CookieManager.getInstance().flush()
        return refreshAccount()
    }

    /** Capture on the UI thread; checking a candidate never replaces the committed provider session. */
    fun signInCandidate(visitor: String?, syncId: String?, accountIndex: String?): WebSignInCandidate? {
        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com") ?: return null
        if (signInCookieIdentity(cookies).isEmpty()) return null
        val index = accountIndex?.takeIf { it.isNotBlank() && it.all(Char::isDigit) } ?: return null
        // Never connect without the identity used to isolate this account's local collections.
        val sync = signInSyncId(syncId) ?: return null
        return WebSignInCandidate(cookies, visitor?.takeUnless { it.isBlank() || it == "null" },
            sync, index, authRevision)
    }

    suspend fun verifySignIn(candidate: WebSignInCandidate): Result<VerifiedSignIn> = withContext(dependencies.dispatchers.io) {
        try {
            val isolated = InnerTube(http)
            isolated.replaceSession(candidate.cookies, candidate.visitor, candidate.syncId,
                candidate.accountIndex, useLoginForBrowse = true)
            val response = isolated.accountMenu(YouTubeClient.WEB_REMIX)
            val text = response.bodyAsText()
            val body = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            when (classifySessionProbe(response.status.value, body, text, networkError = false)) {
                SessionState.Valid -> Result.success(VerifiedSignIn(candidate, body?.let(::parseAccountProfile)))
                SessionState.Expired -> Result.failure(IllegalStateException(string(R.string.google_has_not_completed_sign_in_continue)))
                else -> Result.failure(IllegalStateException(string(R.string.could_not_verify_your_account_check_your)))
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            Result.failure(IllegalStateException(string(R.string.could_not_verify_your_account_check_your)))
        }
    }

    /** Reject stale verification after sign-out, account changes, or changed browser credentials. */
    fun commitSignIn(verified: VerifiedSignIn): Boolean {
        val candidate = verified.candidate
        val current = CookieManager.getInstance().getCookie("https://music.youtube.com")
        if (authRevision != candidate.revision ||
            signInCookieIdentity(current) != signInCookieIdentity(candidate.cookies)) return false
        if (!useWebSession(candidate.visitor, candidate.syncId, candidate.accountIndex)) return false
        sessionState = SessionState.Valid
        accountProfile = verified.profile
        return true
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
        // Everything read for the previous owner is dropped by the part that holds it.
        resetListeners.forEach { it() }
        authRevision++;publishSession()
        sessionChecks.reset();sessionState=SessionState.SignedOut
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
        }
    }

    /**
     * Q18-085, Q18-087, Q18-088: the accounts Google's web session holds, with their addresses and positions; empty
     * when Google does not say. Google's cookies go only to Google's account host; no cookie or raw id is kept or logged.
     */
    suspend fun sessionAccounts(): List<SessionAccount> {
        if (!signedIn) return emptyList()
        // Q18-100: every way this list comes back empty is logged, as counts and a reason only, so the phone shows which.
        val cookies = CookieManager.getInstance().getCookie(GOOGLE_ACCOUNTS_ORIGIN)
            ?: return emptyList<SessionAccount>().also { playbackEvents.append("session_accounts cookie=false") }
        return withContext(dependencies.dispatchers.io) {
            try {
                val response = requestGoogleSessionAccounts(http, cookies)
                if (response.status.value >= 400) emptyList<SessionAccount>().also {
                    playbackEvents.append("session_accounts cookie=true status=${response.status.value} reason=http_error")
                } else {
                    val body = response.bodyAsText()
                    val entries = parseGoogleSessionAccounts(body)
                    playbackEvents.append("session_accounts cookie=true status=${response.status.value} ${googleSessionAccountsShape(body, entries)}")
                    entries.map { entry ->
                        SessionAccount(entry.email, entry.index, entry.stableId?.let { providerScope(VerifiedAccountIdentity(it)).opaqueId }, entry.signedIn)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                playbackEvents.append("session_accounts cookie=true reason=request_failed failure=${error.javaClass.simpleName.ifBlank { "Exception" }}")
                emptyList()
            }
        }
    }

    /** Signed in without a stable identifier is Unverified, never Guest. */
    override fun accountScope(): AccountScope = if (!signedIn) AccountScope.Guest
        else verifiedAccountIdentity(dataSyncId)?.let(::providerScope) ?: AccountScope.Unverified

    /** The owner a verified sign-in would become, before it is committed. */
    fun signInScope(verified: VerifiedSignIn): AccountScope =
        verifiedAccountIdentity(verified.candidate.syncId)?.let(::providerScope) ?: AccountScope.Unverified

    /** Stable, opaque local namespace; raw account identifiers never become preference keys. */
    fun accountScopeKey(): String? {
        if (!signedIn) return null
        val sync = dataSyncId ?: return null
        val identity = verifiedAccountIdentity(sync) ?: return null
        resolvedStorage?.takeIf { it.first == sync }?.let { return it.second.primaryKey }
        // After the first launch this is a preference read; stored data is only inspected in the background.
        val storage = scopeRegistry.remembered(identity)
            ?: scopeRegistry.resolve(identity, sync, authUser, dependencies.storedScopeKeys(appContext))
        resolvedStorage = sync to storage
        return storage.primaryKey
    }

    /**
     * Every opaque key this verified account's data can be filed under: the digest of its identity,
     * its storage key and its older keys. Empty when signed out or unverified.
     */
    fun accountScopeDigests(): Set<String> {
        val primary = accountScopeKey() ?: return emptySet()
        val storage = resolvedStorage?.second ?: return setOf(primary)
        return setOf(storage.scope.opaqueId, storage.primaryKey) + storage.retainedAliases
    }

    /** Bumped when background reconciliation changed what this account's stores hold. */
    var storageRevision by mutableIntStateOf(0)
        private set

    /**
     * Finds this account's other verified keys and folds their data into the primary one. Call off
     * the main thread. Returns the download rows that could not be merged and stay under their alias.
     */
    internal fun reconcileAccountStorage(): Int {
        if (!signedIn) return 0
        val sync = dataSyncId ?: return 0
        val identity = verifiedAccountIdentity(sync) ?: return 0
        val storage = scopeRegistry.resolve(identity, sync, authUser, dependencies.storedScopeKeys(appContext))
        resolvedStorage = sync to storage
        if (storage.retainedAliases.isEmpty()) return 0
        var remaining = 0; var conflicts = 0
        val journal = AppContainer.get(appContext).aliasJournal
        storage.retainedAliases.forEach { alias ->
            // Recorded before any store is touched, so an interrupted merge is visible and can be repeated.
            // A key already recorded for another account is left exactly where it is.
            if (!journal.begin(alias, storage.primaryKey)) { conflicts++; return@forEach }
            mergeCollectionScope(appContext, alias, storage.primaryKey)
            val left = OfflineMusicStore.get(appContext).mergeScope(alias, storage.primaryKey)
            journal.finish(alias, storage.primaryKey, merged = left == 0)
            remaining += left
        }
        playbackEvents.append("account_scope aliases=${storage.retainedAliases.size} unmerged_rows=$remaining conflicts=$conflicts")
        storageRevision++
        return remaining
    }

    /** For stores that also keep signed-out data. An unverified session gets a key stores refuse to write. */
    override fun storageScopeKey(): String = when (accountScope()) {
        AccountScope.Guest -> GUEST_SCOPE_KEY
        is AccountScope.Provider -> accountScopeKey() ?: UNVERIFIED_SCOPE_KEY
        else -> UNVERIFIED_SCOPE_KEY
    }

    internal suspend fun <T> providerResult(block: suspend () -> T): Result<T> {
        val caller=currentCoroutineContext()
        return try {withContext(dependencies.dispatchers.io) {Result.success(block())}}
        catch(e:CancellationException) {
            caller.ensureActive()
            Result.failure(IllegalStateException(string(R.string.youtube_music_refreshed_its_session_please_r)))
        } catch(e:Exception) {
            // A 401 on a signed-in request means the session likely expired: confirm it and say so.
            if(signedIn && isAuthFailure(e)) {checkSessionIfDue(forced=true);return Result.failure(IllegalStateException(SESSION_EXPIRED_MESSAGE))}
            // Not a failure of the change: its outcome is not known. Callers tell the two apart by type.
            if(e is MutationUnconfirmed) return Result.failure(e)
            Result.failure(IllegalStateException(providerErrorMessage(e)))
        }
    }

    fun close() {
        scope.cancel()
        innerTube.close()
        http.close()
        providerEngine.close()
    }
}
