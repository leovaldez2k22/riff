package com.musicglass.personal

import android.net.Uri
import android.os.SystemClock
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.net.toUri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

@Composable
internal fun AccountScreen(model: ShellModel, provider: YouTubeMusicProvider,
    collections: LocalCollections, playback: PlaybackGateway, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val attempt = remember { SignInAttempt() }
    var generation by remember { mutableIntStateOf(0) }
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    var changingAccount by rememberSaveable { mutableStateOf(false) }
    var selectedAccountIndex by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf(if (browserOpen) SignInStage.Loading
        else if (provider.signedIn && provider.sessionState != SessionState.Expired) SignInStage.Connected else SignInStage.Ready) }
    var message by remember { mutableStateOf(if (provider.sessionState == SessionState.Expired)
        "Your session has expired. Reconnect your YouTube Music account." else "Access your playlists, liked songs, and recommendations.") }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var navigation by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var picker by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var verification by remember { mutableStateOf<Job?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = picker
        picker = null
        callback?.onReceiveValue(uri?.let { arrayOf(it) })
    }

    fun restart(changeAccount: Boolean = changingAccount) {
        verification?.cancel()
        picker?.onReceiveValue(null); picker = null
        generation = attempt.restart()
        changingAccount = changeAccount
        selectedAccountIndex = null
        browserOpen = true
        loading = true
        startedAt = SystemClock.elapsedRealtime()
        progress = 0
        stage = SignInStage.Loading
        message = "Opening secure sign-in…"
    }

    fun verify(view: WebView, token: Int) {
        if (!attempt.accepts(token) || verification?.isActive == true) return
        if (view.url.orEmpty().toUri().host != "music.youtube.com") {
            // Leave optional setup only at the user's request. Required setup can redirect back.
            view.loadUrl(signInMusicUrl(selectedAccountIndex))
            return
        }
        val verifyNavigation = navigation
        verification = scope.launch {
            stage = SignInStage.Verifying
            message = "Connecting your account…"
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
                        "Could not confirm the selected YouTube Music account. Check the account shown on the page, or restart sign-in."))
                provider.verifySignIn(readyCandidate)
            }
            if (!attempt.accepts(token) || navigation != verifyNavigation) return@launch
            if (outcome?.isSuccess == true && provider.commitSignIn(outcome.getOrThrow())) {
                collections.bindAccountScope(provider.accountScopeKey())
                loading = false
                stage = SignInStage.Connected
                browserOpen = false
                uiHaptic(HapticCue.Confirm)
                onNotice("YouTube Music account connected")
                model.back()
            } else {
                stage = SignInStage.Failed
                message = outcome?.exceptionOrNull()?.message
                    ?: "Could not finish checking your account. Try again."
            }
        }
    }

    fun leave() {
        attempt.restart()
        verification?.cancel()
        picker?.onReceiveValue(null); picker = null
        browserOpen = false; model.back()
    }
    BackHandler {
        val current = web
        if (browserOpen && current?.canGoBack() == true && stage != SignInStage.Verifying) current.goBack()
        else leave()
    }
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
    LaunchedEffect(generation, navigation, loading, foreground, picker != null) {
        if (!loading || !foreground || picker != null) return@LaunchedEffect
        while (loading) {
            delay(250)
            val next = signInLoadingStage(SystemClock.elapsedRealtime() - startedAt)
            if (stage == SignInStage.Verifying) continue
            if (next == SignInStage.Slow) {
                stage = next
                message = "This is taking longer than expected. You can retry or go back."
            } else if (next == SignInStage.Failed) {
                loading = false
                stage = next
                message = "The sign-in page took too long to load. Check your connection and try again."
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
        Pink.copy(alpha = .12f), Ink, Ink))).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { uiHaptic(); leave() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Riff", tint = White)
            }
            Text(if (stage == SignInStage.Connected) "Your account" else "Connect YouTube Music",
                color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        val controlsModifier = if (browserOpen) Modifier.weight(.45f, fill = false) else Modifier
        Column(controlsModifier.fillMaxWidth().heightIn(max = if (browserOpen) 240.dp else 600.dp)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            if (!browserOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp))
                    Text("Riff", color = White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(if (stage == SignInStage.Connected) when (provider.sessionState) {
                SessionState.Valid -> "Connected to YouTube Music"
                SessionState.Expired -> "Your session has expired. Reconnect to continue."
                else -> "Account saved on this phone. Connection has not been verified yet."
            } else message, color = Muted, fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("account_status"))
            if (stage == SignInStage.Connected) {
                val profile = provider.accountProfile
                Row(verticalAlignment = Alignment.CenterVertically) {
                    profile?.artworkUrl?.let {
                        AsyncImage(it, "Account picture", Modifier.size(48.dp).padding(end = 8.dp))
                    }
                    Text(profile?.name?.takeIf { it.isNotBlank() } ?: "Your YouTube Music account", color = White)
                }
                Button(onClick = { restart(changeAccount = provider.sessionState != SessionState.Expired) },
                    modifier = Modifier.fillMaxWidth().testTag("account_open_sign_in")) {
                    Text(if (provider.sessionState == SessionState.Expired) "Reconnect account" else "Change account")
                }
                Text("App-saved online tracks stay on this phone and are hidden after sign-out.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                val legacyCount = collections.legacyOnlineCount()
                if (legacyCount > 0 && provider.accountScopeKey() != null) {
                    TextButton(onClick = {
                        if (collections.importLegacyOnlineToCurrentAccount()) onNotice("Older app-saved tracks imported to this account")
                    }, modifier = Modifier.testTag("account_import_legacy")) { Text("Import $legacyCount older app-saved entries") }
                }
                TextButton(onClick = {
                    if (playback.state.queue.any { it.mediaId.startsWith("yt:") }) {
                        playback.clearProviderPlayback(); model.miniVisible = false; model.sheet = ""
                    }
                    provider.signOut(); collections.bindAccountScope(null)
                    stage = SignInStage.Ready; message = "Signed out on this phone. Reconnect whenever you're ready."
                }, modifier = Modifier.testTag("account_sign_out")) { Text("Sign out", color = Muted) }
            } else if (!browserOpen) {
                Button(onClick = { uiHaptic(); restart(changeAccount = false) }, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account_open_sign_in")) {
                    Text("Continue to sign-in")
                }
                Text("You'll sign in on Google's page. Your password is not stored by Riff.",
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                SignInBrowserActions(stage == SignInStage.Verifying,
                    onContinue = { web?.let { verify(it, generation) } }, onRetry = {
                        verification?.cancel()
                        stage = SignInStage.Loading; loading = true
                        startedAt = SystemClock.elapsedRealtime()
                        message = "Retrying Google's sign-in page…"
                        web?.reload() // Preserve the browser's form/navigation context.
                    }, onRestart = { restart() })
            }
        }
        if (browserOpen) {
            val token = generation
            val browser = remember(generation) {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true // Scoped system-picker content URIs for profile images.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, value: Int) {
                            if (attempt.accepts(token)) progress = value
                        }
                        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                            params: FileChooserParams): Boolean {
                            if (!attempt.accepts(token) || !allowedSignInUrl(view.url.orEmpty())) {
                                callback.onReceiveValue(null); return true
                            }
                            picker?.onReceiveValue(null)
                            picker = callback
                            runCatching { imagePicker.launch("image/*") }.onFailure {
                                picker = null; callback.onReceiveValue(null)
                                message = "Could not open the image picker. You can continue without changing your picture."
                            }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (!attempt.accepts(token)) return
                            verification?.cancel()
                            signInSelectedAccount(url.orEmpty())?.let { selectedAccountIndex = it }
                            navigation++; startedAt = SystemClock.elapsedRealtime(); loading = true; progress = 0
                            stage = SignInStage.Loading; message = "Loading Google's sign-in page…"
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (!attempt.accepts(token) || url != view.url || stage == SignInStage.Verifying) return
                            if (stage == SignInStage.Failed) return // Keep main-frame failures visible until an explicit retry.
                            loading = false; stage = SignInStage.Waiting
                            message = if (changingAccount)
                                "Choose your account on Google's page, then tap Continue to Riff to confirm it."
                            else "Complete sign-in on Google's page. Once signed in, you can continue to Riff."
                            if (shouldVerifyFinishedSignIn(url.orEmpty(), changingAccount)) verify(view, token)
                        }
                        override fun onFormResubmission(view: WebView, dontResend: android.os.Message, resend: android.os.Message) {
                            dontResend.sendToTarget()
                            if (attempt.accepts(token)) {
                                loading = false; stage = SignInStage.Waiting
                                message = "This Google form cannot be reloaded. Use Restart sign-in to open a fresh page."
                            }
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (attempt.accepts(token) && request.isForMainFrame) {
                                loading = false; stage = SignInStage.Failed
                                message = "The sign-in page could not load. Check your connection and retry."
                            }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (attempt.accepts(token) && request.isForMainFrame) {
                                loading = false; stage = SignInStage.Failed
                                message = if (response.statusCode == 403) "Google refused this sign-in page. Retry or go back."
                                    else "Google's sign-in page is unavailable. Please retry."
                            }
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
                                PlaybackEventLog(context).append("sign_in_blocked main=${request.isForMainFrame} scheme=${request.url.scheme} host=${request.url.host}")
                                if (request.isForMainFrame) {
                                    loading = false; stage = SignInStage.Failed
                                    message = "Google's page tried to open ${request.url.host ?: request.url.scheme}. Use Continue to Riff or restart sign-in."
                                }
                            }
                            return !allowed
                        }
                    }
                }
            }
            DisposableEffect(browser) {
                web = browser
                browser.loadUrl(signInStartUrl(changingAccount))
                onDispose {
                    if (web === browser) web = null
                    picker?.onReceiveValue(null); picker = null
                    browser.stopLoading(); browser.webChromeClient = null
                    browser.webViewClient = WebViewClient(); browser.destroy()
                }
            }
            if (loading && stage != SignInStage.Verifying) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().testTag("account_loading"))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SignInWebView(browser, Modifier.fillMaxSize().testTag("account_browser"))
                if (loading || stage == SignInStage.Verifying) {
                    SignInBrowserLoading(stage == SignInStage.Verifying)
                }
            }
            TextButton(onClick = { leave() }, modifier = Modifier.fillMaxWidth().testTag("account_cancel")) { Text("Back to Riff") }
        }
    }
}

/** AndroidView's factory is called once per composition identity, so a restart needs a new key. */
@Composable
internal fun SignInWebView(browser: WebView, modifier: Modifier = Modifier) {
    key(browser) { AndroidView(factory = { browser }, modifier = modifier) }
}

/** Keep native feedback visible while WebView has not painted a useful page yet. */
@Composable
internal fun SignInBrowserLoading(verifying: Boolean) {
    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp).testTag("account_browser_placeholder"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), color = Pink)
        Spacer(Modifier.height(16.dp))
        Text(if (verifying) "Checking your account…" else "Opening Google sign-in…",
            color = White, fontSize = 14.sp)
    }
}

@Composable
internal fun SignInBrowserActions(verifying: Boolean, onContinue: () -> Unit,
    onRetry: () -> Unit, onRestart: () -> Unit) {
    if (verifying) {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("account_verifying"))
    } else {
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_finish")) {
            Text("Continue to Riff")
        }
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("account_retry")) { Text("Reload page") }
        TextButton(onClick = onRestart, modifier = Modifier.heightIn(min = 48.dp).testTag("account_restart")) { Text("Restart sign-in") }
    }
}

internal fun allowedSignInUrl(url: String): Boolean {
    val uri = url.toUri()
    val host = uri.host.orEmpty().lowercase()
    return uri.scheme == "https" && listOf("youtube.com", "google.com", "googleusercontent.com", "gstatic.com")
        .any { host == it || host.endsWith(".$it") }
}
