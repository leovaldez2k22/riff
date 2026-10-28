package com.musicglass.personal

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.net.toUri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Q18-013: the sign-in, rebuilt to the agreed design (docs/quality/sign-in-design.md). One record travels through
 * every screen; Google's own page sits unchanged inside Riff's glass frame; Riff finishes by itself once Google hands
 * back, and only says Google's page didn't load when nothing loaded.
 */
@Composable
internal fun AccountScreen(model: ShellModel, provider: YouTubeMusicProvider,
    collections: LocalCollections, playback: PlaybackGateway, onNotice: (String) -> Unit,
    sessions: SessionCoordinator = AppContainer.get(LocalContext.current).sessions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val attempt = remember { SignInAttempt() }
    val accounts = remember(context) { SavedAccounts(context) }
    val reduced = LocalMotionPolicy.current.reduced
    var generation by remember { mutableIntStateOf(0) }
    fun signedInNow() = provider.signedIn && provider.sessionState != SessionState.Expired
    var phase by rememberSaveable { mutableStateOf(if (provider.signedIn) SignInPhase.Manage else SignInPhase.Welcome) }
    // A restored Google page cannot come back after the process is gone; start from the account screens instead.
    if (phase in setOf(SignInPhase.Google, SignInPhase.NoLoad, SignInPhase.Checking) && generation == 0)
        phase = if (provider.signedIn) SignInPhase.Manage else SignInPhase.Welcome
    var moveMs by remember { mutableIntStateOf(1200) }
    fun go(next: SignInPhase) { if (next != phase) { moveMs = if (reduced) 0 else signInTransitionMs(phase, next); phase = next } }
    var stopSheet by remember { mutableStateOf(false) }
    var changingAccount by rememberSaveable { mutableStateOf(false) }
    var pickedEmail by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAccountIndex by rememberSaveable { mutableStateOf<String?>(null) }
    // Q18-087: switching to a saved account without Google's page; the scope the switch must land on.
    var directScope by rememberSaveable { mutableStateOf<String?>(null) }
    // Q18-110: the scope of the account picked from the list, kept through the fallback to Google's page.
    var pickedScope by rememberSaveable { mutableStateOf<String?>(null) }
    // Q18-110: what the direct attempt saw, for the log: the position asked for and the page's own.
    var lastPageIndex by remember { mutableStateOf<String?>(null) }
    // Q18-110: Google's own account page was drawn in this attempt, so what is reached is the owner's own choice.
    var sawGooglePage by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (provider.sessionState == SessionState.Expired)
        string(R.string.your_session_has_expired_reconnect_your_yout) else null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var pageLoading by remember { mutableStateOf(false) }
    var pageShown by remember { mutableStateOf(false) }
    var mainFrameFailed by remember { mutableStateOf(false) }
    var choseOnGoogle by remember { mutableStateOf(false) }
    var onMusic by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("accounts.google.com") }
    var navigation by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var picker by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var verification by remember { mutableStateOf<Job?>(null) }
    // Checking: which steps are done, and the message if it could not finish.
    var stepFound by remember { mutableStateOf(false) }
    var stepLibrary by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf<String?>(null) }
    // Q18-039: the ticks shown, which follow the real steps at Checking's own pace.
    var ticks by remember { mutableIntStateOf(0) }
    // Q18-038: the real numbers, fetched from the moment the account is committed; null until they arrive.
    var likedCount by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var playlistCount by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var counting by remember { mutableStateOf<Job?>(null) }
    var connectedName by remember { mutableStateOf("") }
    var connectedEmail by remember { mutableStateOf<String?>(null) }
    val browserOpen = phase in setOf(SignInPhase.Google, SignInPhase.NoLoad, SignInPhase.Checking)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = picker
        picker = null
        callback?.onReceiveValue(uri?.let { arrayOf(it) })
    }
    val nowMs = remember(phase, accounts.revision) { System.currentTimeMillis() }
    val saved = remember(accounts.revision) { accounts.all() }
    val currentScope = (provider.accountScope() as? AccountScope.Provider)?.opaqueId
    val currentEmail = provider.accountProfile?.email
    val currentKey = saved.firstOrNull { (currentScope != null && it.scope == currentScope) || it.key == provider.accountScopeKey() ||
        (currentEmail != null && it.email.equals(currentEmail, ignoreCase = true)) }?.key ?: currentEmail ?: provider.accountScopeKey()
    // The account in use is on the list too, so the welcome screen can show it as Current; a row saved for it
    // under another key is merged into it (Q18-085), and Google's session fills in addresses and positions.
    LaunchedEffect(provider.signedIn, provider.accountProfile) {
        val profile = provider.accountProfile
        if (!provider.signedIn || profile == null) return@LaunchedEffect
        (profile.email ?: currentScope ?: provider.accountScopeKey())?.let {
            accounts.used(it, profile.name, profile.email, System.currentTimeMillis(), currentScope, aliases = setOfNotNull(provider.accountScopeKey()))
        }
        accounts.learn(provider.sessionAccounts())
    }

    fun closeBrowser() {
        attempt.restart()
        verification?.cancel()
        picker?.onReceiveValue(null); picker = null
        stopSheet = false
    }

    /**
     * Opens Google's page, or with [direct] (a saved account Google's session still holds, Q18-087) goes straight to
     * that account's YouTube Music behind the checking screen. A direct switch commits only if it lands on that
     * account; anything else falls back to Google's page.
     */
    fun restart(changeAccount: Boolean, email: String? = null, direct: SavedAccount? = null, keepPick: Boolean = false) {
        closeBrowser()
        generation = attempt.restart()
        changingAccount = changeAccount
        pickedEmail = email
        sawGooglePage = false
        if (!keepPick) pickedScope = direct?.scope
        lastPageIndex = null
        val straight = directSwitchAccount(direct, provider.signedIn)
        directScope = straight?.scope
        selectedAccountIndex = straight?.index
        choseOnGoogle = email != null || straight != null
        pageLoading = true; pageShown = false; mainFrameFailed = false; onMusic = false
        stepFound = false; stepLibrary = false; checkFailed = null; ticks = 0
        counting?.cancel(); likedCount = null; playlistCount = null
        startedAt = SystemClock.elapsedRealtime()
        go(if (straight != null) SignInPhase.Checking else SignInPhase.Google)
    }

    /** Q18-087: the direct switch did not land on the picked account; Google's page, with that account's address. */
    fun directFailed() {
        if (directScope == null) return
        // Q18-110: the position asked for and the page's own, numbers only, and which of the three ways it missed.
        val asked = selectedAccountIndex?.takeIf { it.all { c -> c in '0'..'9' } }
        val page = lastPageIndex?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        val reason = if (page == null) "no_page" else if (page != asked) "index_mismatch" else "other_account"
        PlaybackEventLog(context).append("account_switch_direct_fallback reason=$reason" +
            (asked?.let { " asked=$it" } ?: "") + (page?.let { " page=$it" } ?: ""))
        restart(changingAccount, pickedEmail, direct = null, keepPick = true)
    }

    /** Q18-110: the check reached an account other than the one picked: nothing is committed, Google's chooser opens. */
    fun reachedAnotherAccount() {
        PlaybackEventLog(context).append("account_pick_mismatch")
        restart(changeAccount = true)
    }

    /** Q18-038: counts Liked music and the playlists while the animation plays; an account change drops them. */
    fun startCounting() {
        counting?.cancel(); likedCount = null; playlistCount = null
        counting = scope.launch {
            launch { provider.likedSongsCount { so -> likedCount = so to false }.onSuccess { likedCount = it.total to !it.complete } }
            launch { provider.scanLibraryPlaylists().onSuccess { playlistCount = it.size to (it.size >= LIBRARY_PLAYLIST_SCAN_LIMIT) } }
        }
    }

    fun leave() { closeBrowser(); counting?.cancel(); model.back() }

    fun verify(view: WebView, token: Int) {
        if (!attempt.accepts(token) || verification?.isActive == true) return
        if (view.url.orEmpty().toUri().host != "music.youtube.com") {
            // Leave optional setup only at the user's request. Required setup can redirect back.
            view.loadUrl(signInMusicUrl(selectedAccountIndex))
            return
        }
        val verifyNavigation = navigation
        stepFound = false; stepLibrary = false; checkFailed = null; ticks = 0
        go(SignInPhase.Checking)
        verification = scope.launch {
            // Q18-039: Checking plays to its full length around the real work below.
            val timeline = async {
                signInCheckingTimeline(reduced, SystemClock::elapsedRealtime,
                    done = { step -> when (step) { 0 -> true; 1 -> stepFound; else -> stepLibrary } },
                    failed = { checkFailed != null }, onTick = { ticks = it })
            }
            val outcome = withTimeoutOrNull(SIGN_IN_VERIFY_MS) {
                // Google's document can finish before its account config is initialized.
                var candidate: WebSignInCandidate? = null
                val configDeadline = SystemClock.elapsedRealtime() + 6_000
                while (candidate == null && SystemClock.elapsedRealtime() < configDeadline) {
                    val data = suspendCancellableCoroutine<JSONObject?> { continuation ->
                        view.evaluateJavascript("(function(){var c=(window.ytcfg&&window.ytcfg.data_)||(window.yt&&window.yt.config_)||{};return JSON.stringify({v:c.VISITOR_DATA||null,d:c.DATASYNC_ID||null,a:c.SESSION_INDEX==null?null:String(c.SESSION_INDEX)});})()") { result ->
                            if (continuation.isActive) continuation.resume(runCatching {
                                JSONObject(JSONArray("[$result]").getString(0))
                            }.getOrNull())
                        }
                    }
                    if (!attempt.accepts(token) || navigation != verifyNavigation) return@withTimeoutOrNull null
                    val pageIndex = data?.optString("a")
                    if (directScope != null) lastPageIndex = pageIndex
                    if (signInAccountMatches(selectedAccountIndex, pageIndex)) {
                        candidate = provider.signInCandidate(data?.optString("v"), data?.optString("d"), pageIndex)
                    }
                    if (candidate == null) delay(200)
                }
                val readyCandidate = candidate
                    ?: return@withTimeoutOrNull Result.failure<VerifiedSignIn>(IllegalStateException(
                        string(R.string.could_not_confirm_the_selected_youtube_music)))
                stepFound = true
                provider.verifySignIn(readyCandidate)
            }
            if (!attempt.accepts(token) || navigation != verifyNavigation) return@launch
            // The coordinator stops the outgoing account's playback and transfers before the commit.
            val verified = outcome?.getOrNull()
            // Q18-087: a direct switch that reached another account, or none, commits nothing and goes to Google's page.
            if (directScope != null && (verified == null || !directSwitchLanded(directScope, provider.signInScope(verified)))) {
                timeline.cancel(); directFailed(); return@launch
            }
            // Q18-110: whatever the route, an account picked from the list is committed only if it is the one reached.
            if (verified != null && !sawGooglePage && !pickedAccountReached(pickedScope, pickedEmail, provider.signInScope(verified), verified.profile?.email)) {
                timeline.cancel(); pickedScope = null; reachedAnotherAccount(); return@launch
            }
            val switched = verified?.let {
                sessions.transition("sign_in", provider.signInScope(it)) { provider.commitSignIn(it) }
            }
            if (switched is SessionTransition.Committed && verified != null) {
                directScope = null
                startCounting()
                stepLibrary = true
                val profile = verified.profile
                connectedName = profile?.name.orEmpty()
                connectedEmail = profile?.email ?: profile?.handle
                val committedScope = (provider.accountScope() as? AccountScope.Provider)?.opaqueId
                (profile?.email ?: committedScope ?: provider.accountScopeKey() ?: profile?.name?.takeIf { it.isNotBlank() })?.let {
                    accounts.used(it, profile?.name.orEmpty(), profile?.email, System.currentTimeMillis(), committedScope,
                        verified.candidate.accountIndex, setOfNotNull(provider.accountScopeKey()))
                }
                scope.launch { accounts.learn(provider.sessionAccounts()) }
                status = null
                // The last step's tick and the full ring are seen before the record moves on.
                if (!timeline.await()) return@launch
                uiHaptic(HapticCue.Confirm)
                closeBrowser()
                go(SignInPhase.Connected)
            } else {
                checkFailed = if (switched is SessionTransition.Failed)
                    string(R.string.could_not_switch_accounts_safely_nothing_was)
                else outcome?.exceptionOrNull()?.message ?: string(R.string.could_not_finish_checking_your_account_try)
                timeline.cancel()
            }
        }
    }

    fun back() {
        when (phase) {
            SignInPhase.Manage -> leave()
            SignInPhase.Welcome -> if (provider.signedIn) go(SignInPhase.Manage) else leave()
            SignInPhase.AllAccounts -> go(SignInPhase.Welcome)
            SignInPhase.Google -> {
                val current = web
                when {
                    stopSheet -> stopSheet = false
                    current?.canGoBack() == true -> current.goBack()
                    else -> stopSheet = true
                }
            }
            SignInPhase.NoLoad -> { closeBrowser(); go(SignInPhase.Welcome) }
            SignInPhase.Checking -> if (checkFailed != null || directScope != null) { closeBrowser(); directScope = null; go(SignInPhase.Welcome) }
            SignInPhase.Connected -> leave()
        }
    }
    BackHandler { back() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = event == Lifecycle.Event.ON_RESUME || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) { web?.onResume(); startedAt = SystemClock.elapsedRealtime() }
            if (event == Lifecycle.Event.ON_STOP) web?.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            attempt.restart()
            verification?.cancel()
            picker?.onReceiveValue(null)
        }
    }
    // Q18-087: a direct switch whose page never reaches the check in time goes to Google's page.
    LaunchedEffect(generation, directScope) {
        if (directScope == null) return@LaunchedEffect
        delay(SIGN_IN_DIRECT_MS)
        if (directScope != null && verification?.isActive != true) directFailed()
    }
    // Q18-013 point 3: no banner while a page is working; only when nothing of this navigation ever showed.
    LaunchedEffect(generation, navigation, phase, foreground, picker != null) {
        if (phase != SignInPhase.Google || !foreground || picker != null) return@LaunchedEffect
        while (isActive && !pageShown && !mainFrameFailed) {
            delay(250)
            if (signInNothingLoaded(SystemClock.elapsedRealtime() - startedAt, pageShown, false)) break
        }
        if (!pageShown || mainFrameFailed) { pageLoading = false; stopSheet = false; go(SignInPhase.NoLoad) }
    }

    // The 5 to 6 moment: the record grows and glows, a pink aura swells and melts, the room brightens.
    val grow = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val aura = remember { Animatable(0f) }
    val brightness = remember { Animatable(.8f) }
    LaunchedEffect(phase) {
        if (phase != SignInPhase.Connected) { grow.snapTo(1f); glow.snapTo(0f); aura.snapTo(0f); brightness.snapTo(.8f); return@LaunchedEffect }
        if (reduced) { brightness.snapTo(1.1f); glow.snapTo(.25f); return@LaunchedEffect }
        delay(1100) // the steps card sinks and the record slows to a stop
        launch { glow.animateTo(1f, tween(900)) }
        grow.animateTo(1.4f, tween(1000, easing = FastOutSlowInEasing))
        launch { aura.animateTo(1f, tween(1600)) }
        launch { brightness.animateTo(1.1f, tween(1600)) }
        delay(350)
        launch { glow.animateTo(.25f, tween(1200)) }
        grow.animateTo(1f, tween(1000, easing = FastOutSlowInEasing))
    }

    // Feature 2: the sign-in cards are glass over this backdrop.
    val signInBackdrop = rememberRiffBackdrop()
    CompositionLocalProvider(LocalSignInBackdrop provides signInBackdrop) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        SignInBackdrop(brightness.value, Modifier.riffBackdropSource(signInBackdrop))
        val width = maxWidth
        if (browserOpen) key(generation) {
            val token = generation
            val browser = remember(generation) {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false // The picker's result reaches the page through onShowFileChooser; checked in Q18B11 (Q18-027).
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams): Boolean {
                            if (!attempt.accepts(token) || !allowedSignInUrl(view.url.orEmpty())) {
                                callback.onReceiveValue(null); return true
                            }
                            picker?.onReceiveValue(null)
                            picker = callback
                            runCatching { imagePicker.launch("image/*") }.onFailure {
                                picker = null; callback.onReceiveValue(null)
                                onNotice(string(R.string.could_not_open_the_image_picker_you))
                            }
                            return true
                        }
                    }
                    webViewClient = object : StrictWebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (!attempt.accepts(token)) return
                            // Q18-058, the same store page reached without a navigation Riff could intercept.
                            if (signInStorePage(url.orEmpty())) {
                                view.stopLoading(); PlaybackEventLog(context).append("sign_in_store_page")
                                view.loadUrl(signInMusicUrl(selectedAccountIndex)); return
                            }
                            // Q18-087: Google asking to sign in means its session no longer holds the picked account.
                            if (directScope != null && googleAccountsPage(url.orEmpty())) { view.stopLoading(); directFailed(); return }
                            verification?.cancel()
                            if (phase == SignInPhase.Checking && checkFailed == null && directScope == null) go(SignInPhase.Google)
                            signInSelectedAccount(url.orEmpty())?.let { selectedAccountIndex = it }
                            url?.toUri()?.host?.let { host = it }
                            navigation++; startedAt = SystemClock.elapsedRealtime()
                            pageLoading = true; pageShown = false; mainFrameFailed = false
                            onMusic = url.orEmpty().toUri().host == "music.youtube.com"
                        }
                        override fun onPageCommitVisible(view: WebView, url: String?) {
                            if (attempt.accepts(token)) { pageShown = true; if (googleAccountsPage(url.orEmpty())) sawGooglePage = true }
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (!attempt.accepts(token) || url != view.url) return
                            pageLoading = false
                            if (!mainFrameFailed) pageShown = true
                            if (googleAccountsPage(url.orEmpty())) choseOnGoogle = true
                            if ((phase == SignInPhase.Google || directScope != null) &&
                                shouldVerifyFinishedSignIn(url.orEmpty(), changingAccount, choseOnGoogle)) verify(view, token)
                        }
                        override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
                            dontResend.sendToTarget()
                            if (attempt.accepts(token)) { pageLoading = false; onNotice(string(R.string.this_google_form_cannot_be_reloaded_use)) }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (attempt.accepts(token) && request.isForMainFrame) { pageLoading = false; mainFrameFailed = true }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            // Google's setup can use Android app links after Skip. Keep their
                            // validated HTTPS destination in this browser rather than launching an app.
                            val fallback = signInWebFallback(url)
                            if (fallback != null) {
                                if (request.isForMainFrame && attempt.accepts(token)) view.loadUrl(fallback)
                                return true
                            }
                            if (!request.isForMainFrame && url == "about:blank") return false
                            // Q18-058: Google can show its Play Store page for YouTube Music after the password. The
                            // sign-in is done by then; go on to YouTube Music, where Riff checks it as usual.
                            if (request.isForMainFrame && signInStorePage(url)) {
                                if (attempt.accepts(token)) {
                                    PlaybackEventLog(context).append("sign_in_store_page")
                                    view.loadUrl(signInMusicUrl(selectedAccountIndex))
                                }
                                return true
                            }
                            val allowed = allowedSignInUrl(url)
                            if (!allowed && attempt.accepts(token)) {
                                PlaybackEventLog(context).append("sign_in_outside main=${request.isForMainFrame} scheme=${schemeClass(request.url.scheme)} host=${hostClass(request.url.host)}")
                                // Q18-013 point 2: a link elsewhere (help, privacy) opens outside Riff; the sign-in stays put.
                                if (request.isForMainFrame && request.hasGesture() && signInOutsideLink(url)) runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                            return !allowed
                        }
                    }
                }
            }
            DisposableEffect(browser) {
                web = browser
                browser.loadUrl(if (directScope != null) signInMusicUrl(selectedAccountIndex) else signInStartUrl(changingAccount, pickedEmail))
                onDispose {
                    if (web === browser) web = null
                    picker?.onReceiveValue(null); picker = null
                    browser.stopLoading(); browser.webChromeClient = null
                    browser.webViewClient = WebViewClient(); browser.destroy()
                }
            }
            // Google's page, unchanged, in Riff's glass frame. It rises in, sinks away, and blurs under the stop sheet.
            val shown = phase == SignInPhase.Google
            val pageAlpha by animateFloatAsState(if (shown && pageShown) 1f else 0f,
                if (reduced) snap() else tween((moveMs * .45f).toInt(), delayMillis = if (shown) (moveMs * .45f).toInt() else 0), label = "page")
            val lift by animateDpAsState(if (shown) 0.dp else 60.dp, if (reduced) snap() else tween((moveMs * .6f).toInt()), label = "page lift")
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = SIGN_IN_BAR_HEIGHT + 14.dp, bottom = SIGN_IN_FOOTER_HEIGHT + 8.dp, start = 10.dp, end = 10.dp)
                .graphicsLayer { alpha = pageAlpha; translationY = lift.toPx() }
                .then(if (stopSheet) Modifier.blur(12.dp) else Modifier)
                .clip(RoundedCornerShape(22.dp))) {
                key(browser) { AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize().testTag("account_browser")) }
            }
        }

        // Each screen's glass, buttons and words. Google's page and its "didn't load" card share one bar.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val group = if (phase == SignInPhase.NoLoad) SignInPhase.Google else phase
            AnimatedContent(group, transitionSpec = {
                val d = moveMs
                if (d == 0) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(tween((d * .45f).toInt(), delayMillis = (d * .45f).toInt())) +
                    slideInVertically(tween((d * .55f).toInt(), delayMillis = (d * .4f).toInt())) { it / 8 }) togetherWith
                    (fadeOut(tween((d * .35f).toInt())) + slideOutVertically(tween((d * .4f).toInt())) { it / 10 })
            }, label = "sign-in screen") { shownPhase ->
                when (shownPhase) {
                    SignInPhase.Manage -> ManageAccount(provider, collections, status, onBack = ::back,
                        onChange = { status = null; go(SignInPhase.Welcome) },
                        onReconnect = { restart(changeAccount = false, provider.accountProfile?.email) },
                        onVerify = { restart(changeAccount = false) },
                        onImported = { onNotice(string(R.string.older_app_saved_tracks_imported_to_this)) },
                        // Q18-086: signing out clears Google's whole web session, so it asks first.
                        onSignOut = { confirmSignOut = true })
                    SignInPhase.Welcome -> WelcomeScreen(saved, currentKey.takeIf { provider.signedIn }, nowMs, status,
                        onClose = ::leave,
                        onPick = { account ->
                            if (provider.signedIn && account.key == currentKey && signedInNow()) go(SignInPhase.Manage)
                            else restart(changeAccount = provider.signedIn, account.email, account)
                        },
                        onSeeAll = { go(SignInPhase.AllAccounts) },
                        onAnother = { restart(changeAccount = provider.signedIn || saved.isNotEmpty()) })
                    SignInPhase.AllAccounts -> AllAccountsScreen(saved, currentKey.takeIf { provider.signedIn }, nowMs,
                        onBack = { go(SignInPhase.Welcome) },
                        onPick = { account ->
                            if (provider.signedIn && account.key == currentKey && signedInNow()) go(SignInPhase.Manage)
                            else restart(changeAccount = provider.signedIn, account.email, account)
                        },
                        onRemove = { accounts.remove(it.key) },
                        onAdd = { restart(changeAccount = true) })
                    SignInPhase.Google -> GoogleFrame(host, phase == SignInPhase.NoLoad, onMusic && !pageLoading,
                        onBack = ::back, onClose = { stopSheet = true },
                        onContinue = { web?.let { verify(it, generation) } },
                        onRetry = {
                            mainFrameFailed = false; pageShown = false; pageLoading = true
                            startedAt = SystemClock.elapsedRealtime()
                            go(SignInPhase.Google)
                            web?.reload() ?: restart(changingAccount, pickedEmail)
                        },
                        onStartOver = { closeBrowser(); go(SignInPhase.Welcome) })
                    SignInPhase.Checking -> CheckingScreen(ticks, checkFailed,
                        onRetry = { checkFailed = null; web?.let { verify(it, generation) } ?: restart(changingAccount, pickedEmail) },
                        onStartOver = { closeBrowser(); go(SignInPhase.Welcome) })
                    SignInPhase.Connected -> ConnectedScreen(connectedName, connectedEmail, likedCount, playlistCount, reduced,
                        onStart = {
                            uiHaptic(HapticCue.Confirm)
                            onNotice(string(R.string.youtube_music_account_connected))
                            model.resetRoot("Home"); model.select("Home")
                        },
                        onAnother = { restart(changeAccount = true) })
                    SignInPhase.NoLoad -> Unit
                }
            }
        }

        // The record: one, moving between screens. In the bar on Google's page, in the middle while checking.
        val spot = recordSpot(phase, saved.isNotEmpty(), width, maxHeight)
        val travel = if (reduced) snap<Dp>() else tween(moveMs.coerceAtLeast(1) * 7 / 10, delayMillis = moveMs / 10, easing = FastOutSlowInEasing)
        val x by animateDpAsState(spot.x, travel, label = "record x")
        val y by animateDpAsState(spot.y, travel, label = "record y")
        val size by animateDpAsState(spot.size, travel, label = "record size")
        val grey by animateFloatAsState(if (phase == SignInPhase.NoLoad) 1f else 0f, if (reduced) snap() else tween(1400, delayMillis = 900), label = "record grey")
        val ringTarget = if (phase != SignInPhase.Checking) 0f else maxOf(ticks, 1) / SIGN_IN_CHECKING_STEPS.toFloat()
        val ring by animateFloatAsState(ringTarget, if (reduced) snap() else tween(900), label = "record ring")
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            RiffRecord(Modifier.offset { IntOffset((x - size / 2).roundToPx(), (y - size / 2).roundToPx()) }.size(size)
                .graphicsLayer { scaleX = grow.value; scaleY = grow.value },
                spinning = (phase == SignInPhase.Google && pageLoading) || (phase == SignInPhase.Checking && checkFailed == null),
                ring = if (phase == SignInPhase.Checking) ring else null, grey = grey,
                glow = glow.value, aura = aura.value, noSignal = phase == SignInPhase.NoLoad)
        }

        // 3: Stop signing in? A glass sheet over the dimmed page.
        if (phase == SignInPhase.Google) StopSheet(stopSheet, reduced, onKeep = { stopSheet = false },
            onStop = { closeBrowser(); go(SignInPhase.Welcome) })
    }
    }

    // Q18-086: Google's web session holds every account signed in through Riff; signing out ends all of them.
    if (confirmSignOut) FrostedAlertDialog(onDismissRequest = { confirmSignOut = false },
        title = { Text(string(R.string.sign_out_all_title)) },
        text = { Text(string(R.string.sign_out_all_body), modifier = Modifier.testTag("sign_out_prompt")) },
        confirmButton = {
            TextButton(onClick = {
                confirmSignOut = false
                scope.launch {
                    val result = sessions.transition("sign_out", AccountScope.Guest) { provider.signOut(); true }
                    if (result is SessionTransition.Committed) {
                        accounts.clear()
                        status = string(R.string.signed_out_on_this_phone_reconnect_whenever)
                        go(SignInPhase.Welcome)
                    } else onNotice(string(R.string.could_not_sign_out_safely_try_again))
                }
            }, modifier = Modifier.testTag("confirm_sign_out")) { Text(string(R.string.sign_out), color = Pink) }
        },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(string(R.string.cancel)) } })
}

internal val SIGN_IN_FOOTER_HEIGHT: Dp = RiffSignIn.footer

internal data class RecordSpot(val x: Dp, val y: Dp, val size: Dp)

/** Where the record sits on each screen, its centre in the safe area. The bar's record is beside the back button. */
internal fun recordSpot(phase: SignInPhase, hasAccounts: Boolean, width: Dp, height: Dp): RecordSpot = when (phase) {
    SignInPhase.Manage -> RecordSpot(width / 2, 110.dp, 150.dp)
    SignInPhase.Welcome -> if (hasAccounts) RecordSpot(width / 2, 104.dp, 132.dp) else RecordSpot(width / 2, 160.dp, 190.dp)
    SignInPhase.AllAccounts, SignInPhase.Google -> RecordSpot(6.dp + 48.dp + SIGN_IN_BAR_RECORD / 2, SIGN_IN_BAR_HEIGHT / 2, SIGN_IN_BAR_RECORD + 8.dp)
    SignInPhase.NoLoad -> RecordSpot(width / 2, height * .3f, 170.dp)
    SignInPhase.Checking -> RecordSpot(width / 2, height * .3f, 210.dp)
    SignInPhase.Connected -> RecordSpot(width / 2, 138.dp, 170.dp)
}

internal val signInDomains = listOf("youtube.com", "google.com", "googleusercontent.com", "gstatic.com") + googleCountryDomains

internal fun allowedSignInUrl(url: String): Boolean = httpsAddressOn(url, signInDomains) != null
