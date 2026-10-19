package com.musicglass.personal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay

// Q18-042: the screens of the sign-in flow, moved out of AccountScreen.kt, which runs the flow and the web page.

@Composable internal fun SignInTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = White, fontSize = RiffSignIn.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { heading() })

/** Signed in: who it is, Change account, and the account's own controls (sign out, verify, older saved songs). */
@Composable internal fun ManageAccount(provider: YouTubeMusicProvider, collections: LocalCollections, status: String?,
    onBack: () -> Unit, onChange: () -> Unit, onReconnect: () -> Unit, onVerify: () -> Unit, onImported: () -> Unit,
    onSignOut: () -> Unit) {
    val title = string(R.string.your_account)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = RiffSpace.screenEdge)) {
        IconButton(hapticClick(onBack), Modifier.testTag("signin_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, string(R.string.back_to_riff), tint = White)
        }
        Spacer(Modifier.height(RiffSignIn.manageTop))
        Column(Modifier.fillMaxWidth().signInGlass().padding(RiffSignIn.cardPad), verticalArrangement = Arrangement.spacedBy(RiffSignIn.cardGap)) {
            val profile = provider.accountProfile
            SignInTitle(profile?.name?.takeIf { it.isNotBlank() } ?: string(R.string.your_youtube_music_account))
            profile?.email?.let { Text(shortEmail(it), color = Muted, fontSize = RiffSignIn.note, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            Text(status ?: when (provider.sessionState) {
                SessionState.Valid -> string(R.string.connected_to_youtube_music)
                SessionState.Expired -> string(R.string.your_session_has_expired_reconnect_to_contin)
                else -> string(R.string.account_saved_on_this_phone_connection_has)
            }, color = Muted, fontSize = RiffType.body, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_status"))
            if (provider.sessionState == SessionState.Expired)
                SignInPinkButton(string(R.string.reconnect_account), "account_open_sign_in", onClick = onReconnect)
            else SignInPinkButton(string(R.string.change_account), "account_open_sign_in", onClick = onChange)
            if (provider.accountScope() == AccountScope.Unverified) {
                Text(string(R.string.could_not_verify_this_account_for_local),
                    color = Muted, fontSize = RiffType.detail, modifier = Modifier.testTag("account_unverified"))
                SignInGlassButton(string(R.string.verify_account), "account_verify", onClick = onVerify)
            }
            Text(string(R.string.app_saved_online_tracks_stay_on_this), color = Muted, fontSize = RiffType.detail)
            val legacyCount = collections.legacyOnlineCount()
            if (legacyCount > 0 && provider.accountScopeKey() != null)
                SignInGlassButton(string(R.string.import_x_older_app_saved_entries, legacyCount), "account_import_legacy") {
                    if (collections.importLegacyOnlineToCurrentAccount()) onImported()
                }
            TextButton(hapticClick(onSignOut), Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).testTag("account_sign_out")) {
                Text(string(R.string.sign_out), color = Muted)
            }
        }
        Spacer(Modifier.height(RiffSignIn.cardBottom))
    }
}

/** 1: Welcome. The three most recent accounts and See all, or a first run's "Bring your music". */
@Composable internal fun WelcomeScreen(saved: List<SavedAccount>, currentKey: String?, nowMs: Long, status: String?,
    onClose: () -> Unit, onPick: (SavedAccount) -> Unit, onSeeAll: () -> Unit, onAnother: () -> Unit) {
    val hasAccounts = saved.isNotEmpty()
    val title = string(if (hasAccounts) R.string.signin_welcome_back else R.string.signin_bring_music)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = RiffSpace.screenEdge)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(hapticClick(onClose), Modifier.testTag("account_close")) { Icon(Icons.Rounded.Close, string(R.string.close), tint = White) }
        }
        Spacer(Modifier.height(if (hasAccounts) RiffSignIn.welcomeTop else RiffSignIn.firstRunTop))
        Column(Modifier.fillMaxWidth().signInGlass().padding(RiffSignIn.welcomePad), verticalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
            SignInTitle(title)
            Text(string(if (hasAccounts) R.string.signin_pick_account else R.string.signin_bring_music_body), color = Muted, fontSize = RiffType.body,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            status?.let { Text(it, color = Muted, fontSize = RiffSignIn.note, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("account_status")) }
            if (hasAccounts) {
                Spacer(Modifier.height(RiffSpace.tight))
                saved.take(3).forEach { account -> SavedAccountRow(account, account.key == currentKey, nowMs, onPick = { onPick(account) }) }
                if (saved.size > 3) TextButton(hapticClick(onSeeAll), Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).testTag("signin_see_all")) {
                    Text(string(R.string.signin_see_all, saved.size), color = Pink)
                }
            }
            Spacer(Modifier.height(RiffSignIn.barSide))
            SignInPinkButton(string(if (hasAccounts) R.string.signin_use_another else R.string.signin_continue_google), "account_open_google", onClick = onAnother)
            TextButton(hapticClick(onClose), Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).testTag("account_not_now")) {
                Text(string(R.string.signin_not_now), color = Muted)
            }
        }
        Spacer(Modifier.height(RiffSignIn.cardBottom))
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
        Column(Modifier.weight(1f).fillMaxWidth().padding(RiffSignIn.cardMargin).signInGlass().padding(RiffSignIn.fieldPad)) {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(string(R.string.signin_search_accounts)) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Muted) }, shape = RoundedCornerShape(RiffSignIn.fieldCorner),
                modifier = Modifier.fillMaxWidth().testTag("signin_search"))
            LazyColumn(Modifier.weight(1f).padding(top = RiffSpace.item)) {
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
            Icon(Icons.Rounded.Lock, null, tint = Muted, modifier = Modifier.size(RiffSignIn.lock))
            IconButton(hapticClick(onClose), Modifier.testTag("account_cancel")) { Icon(Icons.Rounded.Close, string(R.string.signin_stop), tint = White) }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(nothingLoaded, enter = fadeIn(tween(900, delayMillis = 1600)) + slideInVertically(tween(1000, delayMillis = 1500)) { it / 6 },
                exit = fadeOut(tween(500)) + slideOutVertically(tween(700)) { it / 6 }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.fillMaxWidth().padding(RiffSpace.block).signInGlass().padding(RiffSignIn.cardPad).testTag("account_no_load"),
                    verticalArrangement = Arrangement.spacedBy(RiffSignIn.cardGap)) {
                    SignInTitle(string(R.string.signin_didnt_load))
                    Text(string(R.string.signin_didnt_load_body), color = Muted, fontSize = RiffType.body, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                    SignInPinkButton(string(R.string.try_again), "account_retry", onClick = onRetry)
                    SignInGlassButton(string(R.string.signin_start_over), "account_restart", onClick = onStartOver)
                }
            }
        }
        if (!nothingLoaded) Row(Modifier.fillMaxWidth().height(SIGN_IN_FOOTER_HEIGHT).padding(horizontal = RiffSignIn.cardMargin, vertical = RiffSignIn.barSide)
            .signInGlass(RoundedCornerShape(RiffSignIn.corner)).padding(horizontal = RiffSignIn.fieldPad), verticalAlignment = Alignment.CenterVertically) {
            if (canContinue) SignInPinkButton(string(R.string.continue_to_riff), "account_finish", Modifier.heightIn(max = RiffSignIn.footerButton), onClick = onContinue)
            else {
                Text(string(R.string.signin_step_google), color = White, fontSize = RiffSignIn.note, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(string(R.string.signin_never_sees_password), color = Muted, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Column(Modifier.fillMaxWidth().padding(RiffSignIn.cardMargin).signInGlass().padding(RiffSignIn.cardPad).semantics { paneTitle = title }.testTag("account_stop_sheet"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(RiffSignIn.cardGap)) {
                RiffRecord(Modifier.size(RiffSignIn.stopRecord))
                SignInTitle(title)
                Text(string(R.string.signin_stop_body), color = Muted, fontSize = RiffType.body, textAlign = TextAlign.Center)
                SignInPinkButton(string(R.string.signin_keep), "account_keep", onClick = onKeep)
                SignInGlassButton(string(R.string.signin_stop), "account_stop", onClick = onStop)
            }
        }
    }
}

/** 5: Checking your account; three steps tick off in turn. */
@Composable internal fun CheckingScreen(ticks: Int, failed: String?, onRetry: () -> Unit, onStartOver: () -> Unit) {
    val title = string(R.string.signin_checking)
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.padding(horizontal = RiffSpace.screenEdge), verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.fillMaxWidth().padding(bottom = RiffSignIn.cardBottom).signInGlass().padding(RiffSignIn.cardPad).testTag("account_verifying"),
            verticalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
            SignInTitle(title, Modifier.padding(bottom = RiffSpace.item))
            SignInStepRow(string(R.string.signin_step_signed_in), done = ticks >= 1, current = ticks == 0)
            SignInStepRow(string(R.string.signin_step_found), done = ticks >= 2, current = ticks == 1)
            SignInStepRow(string(R.string.signin_step_library), done = ticks >= 3, current = ticks == 2)
            if (failed != null) {
                Text(failed, color = Muted, fontSize = RiffType.body, modifier = Modifier.padding(vertical = RiffSpace.item)
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
        val rise by animateDpAsState(if (shown >= index) Dp(0f) else RiffSignIn.itemRise, if (reduced) snap() else tween(700), label = "item rise")
        Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha; translationY = rise.toPx() }) { content() }
    }
    Column(Modifier.fillMaxSize().semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(horizontal = RiffSpace.screenEdge)) {
        Spacer(Modifier.height(RiffSignIn.connectedTop))
        Item(0) {
            Column(Modifier.fillMaxWidth().signInGlass().padding(RiffSignIn.cardPad), verticalArrangement = Arrangement.spacedBy(RiffSignIn.cardGap)) {
                Item(1) { SignInTitle(title, Modifier.testTag("account_connected")) }
                Item(2) { Text(shortEmail(email), color = Muted, fontSize = RiffType.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Item(3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CountUp(liked, shown >= 3, string(R.string.signin_liked_songs), reduced)
                        CountUp(playlists, shown >= 3, string(R.string.signin_playlists), reduced)
                    }
                }
                Item(4) { SignInPinkButton(string(R.string.signin_start_listening), "account_start_listening", onClick = onStart) }
                Item(5) {
                    TextButton(hapticClick(onAnother), Modifier.fillMaxWidth().heightIn(min = RiffSpace.touch).testTag("account_not_you")) {
                        Text(string(R.string.signin_not_you), color = Muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(RiffSignIn.cardBottom))
    }
}

/** A number counted up from zero, and on as more of it arrives; "+" only past the counting limit. */
@Composable internal fun CountUp(value: Pair<Int, Boolean>?, start: Boolean, label: String, reduced: Boolean) {
    val target = if (start) value?.first ?: 0 else 0
    val counted by animateIntAsState(target, if (reduced) snap() else tween(1400), label = "count")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (value == null) "–" else "$counted${if (value.second && counted == value.first) "+" else ""}",
            color = White, fontSize = RiffSignIn.title, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = RiffType.detail)
    }
}
