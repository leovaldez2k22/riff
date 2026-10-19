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
    val currentKey = provider.accountProfile?.email ?: provider.accountScopeKey()
    // The account in use is on the list too, so the welcome screen can show it as Current.
    LaunchedEffect(provider.signedIn, provider.accountProfile) {
        val profile = provider.accountProfile
        if (provider.signedIn && profile != null && saved.none { it.key == (profile.email ?: currentKey) })
            (profile.email ?: currentKey)?.let { accounts.used(it, profile.name, profile.email, System.currentTimeMillis()) }
    }

    fun closeBrowser() {
        attempt.restart()
        verification?.cancel()
        picker?.onReceiveValue(null); picker = null
        stopSheet = false
    }

    fun restart(changeAccount: Boolean, email: String? = null) {
        closeBrowser()
        generation = attempt.restart()
        changingAccount = changeAccount
        pickedEmail = email
        selectedAccountIndex = null
        choseOnGoogle = email != null
        pageLoading = true; pageShown = false; mainFrameFailed = false; onMusic = false
        stepFound = false; stepLibrary = false; checkFailed = null; ticks = 0
        counting?.cancel(); likedCount = null; playlistCount = null
        startedAt = SystemClock.elapsedRealtime()
        go(SignInPhase.Google)
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
            val switched = verified?.let {
                sessions.transition("sign_in", provider.signInScope(it)) { provider.commitSignIn(it) }
            }
            if (switched is SessionTransition.Committed && verified != null) {
                startCounting()
                stepLibrary = true
                val profile = verified.profile
                connectedName = profile?.name.orEmpty()
                connectedEmail = profile?.email
                (profile?.email ?: provider.accountScopeKey() ?: profile?.name?.takeIf { it.isNotBlank() })?.let {
                    accounts.used(it, profile?.name.orEmpty(), profile?.email, System.currentTimeMillis())
                }
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
            SignInPhase.Checking -> if (checkFailed != null) { closeBrowser(); go(SignInPhase.Welcome) }
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        SignInBackdrop(brightness.value)
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
                            verification?.cancel()
                            if (phase == SignInPhase.Checking && checkFailed == null) go(SignInPhase.Google)
                            signInSelectedAccount(url.orEmpty())?.let { selectedAccountIndex = it }
                            url?.toUri()?.host?.let { host = it }
                            navigation++; startedAt = SystemClock.elapsedRealtime()
                            pageLoading = true; pageShown = false; mainFrameFailed = false
                            onMusic = url.orEmpty().toUri().host == "music.youtube.com"
                        }
                        override fun onPageCommitVisible(view: WebView, url: String?) {
                            if (attempt.accepts(token)) pageShown = true
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (!attempt.accepts(token) || url != view.url) return
                            pageLoading = false
                            if (!mainFrameFailed) pageShown = true
                            if (googleAccountsPage(url.orEmpty())) choseOnGoogle = true
                            if (phase == SignInPhase.Google && shouldVerifyFinishedSignIn(url.orEmpty(), changingAccount, choseOnGoogle)) verify(view, token)
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
                browser.loadUrl(signInStartUrl(changingAccount, pickedEmail))
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
                        onSignOut = {
                            scope.launch {
                                val result = sessions.transition("sign_out", AccountScope.Guest) { provider.signOut(); true }
                                if (result is SessionTransition.Committed) {
                                    status = string(R.string.signed_out_on_this_phone_reconnect_whenever)
                                    go(SignInPhase.Welcome)
                                } else onNotice(string(R.string.could_not_sign_out_safely_try_again))
                            }
                        })
                    SignInPhase.Welcome -> WelcomeScreen(saved, currentKey.takeIf { provider.signedIn }, nowMs, status,
                        onClose = ::leave,
                        onPick = { account ->
                            if (provider.signedIn && account.key == currentKey && signedInNow()) go(SignInPhase.Manage)
                            else restart(changeAccount = provider.signedIn, account.email)
                        },
                        onSeeAll = { go(SignInPhase.AllAccounts) },
                        onAnother = { restart(changeAccount = provider.signedIn || saved.isNotEmpty()) })
                    SignInPhase.AllAccounts -> AllAccountsScreen(saved, currentKey.takeIf { provider.signedIn }, nowMs,
                        onBack = { go(SignInPhase.Welcome) },
                        onPick = { account ->
                            if (provider.signedIn && account.key == currentKey && signedInNow()) go(SignInPhase.Manage)
                            else restart(changeAccount = provider.signedIn, account.email)
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

internal val SIGN_IN_FOOTER_HEIGHT: Dp = 60.dp

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

@Composable internal fun SignInTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { heading() })

/** Signed in: who it is, Change account, and the account's own controls (sign out, verify, older saved songs). */
@Composable internal fun ManageAccount(provider: YouTubeMusicProvider, collections: LocalCollections, status: String?,
    onBack: () -> Unit, onChange: () -> Unit, onReconnect: () -> Unit, onVerify: () -> Unit, onImported: () -> Unit,
    onSignOut: () -> Unit) {
    val title = string(R.string.your_account)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        IconButton(hapticClick(onBack), Modifier.testTag("signin_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, string(R.string.back_to_riff), tint = White)
        }
        Spacer(Modifier.height(150.dp))
        Column(Modifier.fillMaxWidth().signInGlass().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val profile = provider.accountProfile
            SignInTitle(profile?.name?.takeIf { it.isNotBlank() } ?: string(R.string.your_youtube_music_account))
            profile?.email?.let { Text(shortEmail(it), color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Text(status ?: when (provider.sessionState) {
                SessionState.Valid -> string(R.string.connected_to_youtube_music)
                SessionState.Expired -> string(R.string.your_session_has_expired_reconnect_to_contin)
                else -> string(R.string.account_saved_on_this_phone_connection_has)
            }, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_status"))
            if (provider.sessionState == SessionState.Expired)
                SignInPinkButton(string(R.string.reconnect_account), "account_open_sign_in", onClick = onReconnect)
            else SignInPinkButton(string(R.string.change_account), "account_open_sign_in", onClick = onChange)
            if (provider.accountScope() == AccountScope.Unverified) {
                Text(string(R.string.could_not_verify_this_account_for_local),
                    color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("account_unverified"))
                SignInGlassButton(string(R.string.verify_account), "account_verify", onClick = onVerify)
            }
            Text(string(R.string.app_saved_online_tracks_stay_on_this), color = Muted, fontSize = 12.sp)
            val legacyCount = collections.legacyOnlineCount()
            if (legacyCount > 0 && provider.accountScopeKey() != null)
                SignInGlassButton(string(R.string.import_x_older_app_saved_entries, legacyCount), "account_import_legacy") {
                    if (collections.importLegacyOnlineToCurrentAccount()) onImported()
                }
            TextButton(hapticClick(onSignOut), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_sign_out")) {
                Text(string(R.string.sign_out), color = Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 1: Welcome. The three most recent accounts and See all, or a first run's "Bring your music". */
@Composable internal fun WelcomeScreen(saved: List<SavedAccount>, currentKey: String?, nowMs: Long, status: String?,
    onClose: () -> Unit, onPick: (SavedAccount) -> Unit, onSeeAll: () -> Unit, onAnother: () -> Unit) {
    val hasAccounts = saved.isNotEmpty()
    val title = string(if (hasAccounts) R.string.signin_welcome_back else R.string.signin_bring_music)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(hapticClick(onClose), Modifier.testTag("account_close")) { Icon(Icons.Rounded.Close, string(R.string.close), tint = White) }
        }
        Spacer(Modifier.height(if (hasAccounts) 130.dp else 232.dp))
        Column(Modifier.fillMaxWidth().signInGlass().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignInTitle(title)
            Text(string(if (hasAccounts) R.string.signin_pick_account else R.string.signin_bring_music_body), color = Muted, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            status?.let { Text(it, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_status")) }
            if (hasAccounts) {
                Spacer(Modifier.height(4.dp))
                saved.take(3).forEach { account -> SavedAccountRow(account, account.key == currentKey, nowMs, onPick = { onPick(account) }) }
                if (saved.size > 3) TextButton(hapticClick(onSeeAll), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("signin_see_all")) {
                    Text(string(R.string.signin_see_all, saved.size), color = Pink)
                }
            }
            Spacer(Modifier.height(6.dp))
            SignInPinkButton(string(if (hasAccounts) R.string.signin_use_another else R.string.signin_continue_google), "account_open_google", onClick = onAnother)
            TextButton(hapticClick(onClose), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_not_now")) {
                Text(string(R.string.signin_not_now), color = Muted)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 1b: every account used on this phone, with a search and "Remove from this phone". */
@Composable internal fun AllAccountsScreen(saved: List<SavedAccount>, currentKey: String?, nowMs: Long, onBack: () -> Unit,
    onPick: (SavedAccount) -> Unit, onRemove: (SavedAccount) -> Unit, onAdd: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(saved, query) { saved.filter { query.isBlank() || it.name.contains(query, true) || it.email?.contains(query, true) == true } }
    val title = string(R.string.signin_accounts)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        SignInTopBar(title, string(R.string.signin_x_used, saved.size), onBack, string(R.string.back))
        Column(Modifier.weight(1f).fillMaxWidth().padding(10.dp).signInGlass().padding(14.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(string(R.string.signin_search_accounts)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) }, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("signin_search"))
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(shown, key = { it.key }) { account ->
                    SavedAccountRow(account, account.key == currentKey, nowMs, onPick = { onPick(account) },
                        onRemove = { onRemove(account) })
                }
            }
            SignInPinkButton(string(R.string.signin_add_another), "signin_add_account", onClick = onAdd)
        }
    }
}

/** 2 and 4: the bar over Google's page, its footer, and the card when nothing loaded. */
@Composable internal fun GoogleFrame(host: String, nothingLoaded: Boolean, canContinue: Boolean, onBack: () -> Unit,
    onClose: () -> Unit, onContinue: () -> Unit, onRetry: () -> Unit, onStartOver: () -> Unit) {
    val title = string(R.string.signin_with_google)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        SignInTopBar(title, host, onBack, string(R.string.back), trailing = {
            Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(14.dp))
            IconButton(hapticClick(onClose), Modifier.testTag("account_cancel")) { Icon(Icons.Rounded.Close, string(R.string.signin_stop), tint = White) }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(nothingLoaded, enter = fadeIn(tween(900, delayMillis = 1600)) + slideInVertically(tween(1000, delayMillis = 1500)) { it / 6 },
                exit = fadeOut(tween(500)) + slideOutVertically(tween(700)) { it / 6 }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().padding(16.dp).signInGlass().padding(22.dp).testTag("account_no_load"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignInTitle(string(R.string.signin_didnt_load))
                    Text(string(R.string.signin_didnt_load_body), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    SignInPinkButton(string(R.string.try_again), "account_retry", onClick = onRetry)
                    SignInGlassButton(string(R.string.signin_start_over), "account_restart", onClick = onStartOver)
                }
            }
        }
        if (!nothingLoaded) Row(Modifier.fillMaxWidth().height(SIGN_IN_FOOTER_HEIGHT).padding(horizontal = 10.dp, vertical = 6.dp)
            .signInGlass(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canContinue) SignInPinkButton(string(R.string.continue_to_riff), "account_finish", Modifier.heightIn(max = 44.dp), onClick = onContinue)
            else {
                Text(string(R.string.signin_step_google), color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(string(R.string.signin_never_sees_password), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 3: Stop signing in? */
@Composable internal fun StopSheet(open: Boolean, reduced: Boolean, onKeep: () -> Unit, onStop: () -> Unit) {
    val dim by animateFloatAsState(if (open) .5f else 0f, if (reduced) snap() else tween(if (open) 700 else SIGN_IN_STOP_SHEET_OUT_MS / 2), label = "dim")
    if (dim > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)).clickable(onClick = onKeep))
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(open,
            enter = if (reduced) fadeIn(snap()) else slideInVertically(androidx.compose.animation.core.spring(dampingRatio = .72f, stiffness = 140f)) { it } + fadeIn(tween(500, delayMillis = 300)),
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(SIGN_IN_STOP_SHEET_OUT_MS)) { it } + fadeOut(tween(SIGN_IN_STOP_SHEET_OUT_MS))) {
            val title = string(R.string.signin_stop_title)
            Column(Modifier.fillMaxWidth().padding(10.dp).signInGlass().padding(22.dp).semantics { paneTitle = title }.testTag("account_stop_sheet"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RiffRecord(Modifier.size(64.dp))
                SignInTitle(title)
                Text(string(R.string.signin_stop_body), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                SignInPinkButton(string(R.string.signin_keep), "account_keep", onClick = onKeep)
                SignInGlassButton(string(R.string.signin_stop), "account_stop", onClick = onStop)
            }
        }
    }
}

/** 5: Checking your account; three steps tick off in turn. */
@Composable internal fun CheckingScreen(ticks: Int, failed: String?, onRetry: () -> Unit, onStartOver: () -> Unit) {
    val title = string(R.string.signin_checking)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.padding(horizontal = 20.dp), verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp).signInGlass().padding(22.dp).testTag("account_verifying"),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SignInTitle(title, Modifier.padding(bottom = 8.dp))
            SignInStepRow(string(R.string.signin_step_signed_in), done = ticks >= 1, current = ticks == 0)
            SignInStepRow(string(R.string.signin_step_found), done = ticks >= 2, current = ticks == 1)
            SignInStepRow(string(R.string.signin_step_library), done = ticks >= 3, current = ticks == 2)
            if (failed != null) {
                Text(failed, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_status"))
                SignInPinkButton(string(R.string.try_again), "account_retry", onClick = onRetry)
                SignInGlassButton(string(R.string.signin_start_over), "account_restart", onClick = onStartOver)
            }
        }
    }
}

/**
 * 6: Connected. Waits for Start listening. Q18-038: [liked] and [playlists] are the real numbers (the second value is
 * true only past the counting limit), fetched since the account was committed; "–" until they arrive.
 */
@Composable internal fun ConnectedScreen(name: String, email: String?, liked: Pair<Int, Boolean>?, playlists: Pair<Int, Boolean>?,
    reduced: Boolean, onStart: () -> Unit, onAnother: () -> Unit) {
    var shown by remember { mutableIntStateOf(if (reduced) 9 else 0) }
    LaunchedEffect(reduced) { if (!reduced) { delay(2900); for (i in 1..6) { shown = i; delay(260) } } }
    val title = if (name.isNotBlank()) string(R.string.signin_youre_in, name) else string(R.string.signin_youre_in_plain)
    @Composable fun Item(index: Int, content: @Composable () -> Unit) {
        val alpha by animateFloatAsState(if (shown >= index) 1f else 0f, if (reduced) snap() else tween(700), label = "item")
        val rise by animateDpAsState(if (shown >= index) 0.dp else 18.dp, if (reduced) snap() else tween(700), label = "item rise")
        Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; translationY = rise.toPx() }) { content() }
    }
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(250.dp))
        Item(0) {
            Column(Modifier.fillMaxWidth().signInGlass().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Item(1) { SignInTitle(title, Modifier.testTag("account_connected")) }
                Item(2) { Text(shortEmail(email), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Item(3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CountUp(liked, shown >= 3, string(R.string.signin_liked_songs), reduced)
                        CountUp(playlists, shown >= 3, string(R.string.signin_playlists), reduced)
                    }
                }
                Item(4) { SignInPinkButton(string(R.string.signin_start_listening), "account_start_listening", onClick = onStart) }
                Item(5) {
                    TextButton(hapticClick(onAnother), Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_not_you")) {
                        Text(string(R.string.signin_not_you), color = Muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A number counted up from zero, and on as more of it arrives; "+" only past the counting limit. */
@Composable internal fun CountUp(value: Pair<Int, Boolean>?, start: Boolean, label: String, reduced: Boolean) {
    val target = if (start) value?.first ?: 0 else 0
    val counted by animateIntAsState(target, if (reduced) snap() else tween(1400), label = "count")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (value == null) "–" else "$counted${if (value.second && counted == value.first) "+" else ""}",
            color = White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 12.sp)
    }
}

internal val signInDomains = listOf("youtube.com", "google.com", "googleusercontent.com", "gstatic.com") + googleCountryDomains

internal fun allowedSignInUrl(url: String): Boolean = httpsAddressOn(url, signInDomains) != null
