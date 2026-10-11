package com.musicglass.personal

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
internal fun ShellScene(model: ShellModel, compact: Boolean, showChrome: Boolean,
    playback: PlaybackGateway, collections: LocalCollections, provider: YouTubeMusicProvider,
    ambient: androidx.compose.runtime.State<AmbientPalette>,
    onNotice: (String) -> Unit,
    onCategoryBounds: (String, androidx.compose.ui.geometry.Rect?) -> Unit = { _, _ -> }) {
    DisposableEffect(playback,model) {
        (playback as? MusicPlaybackGateway)?.onLivePlay={track->
            model.liveTrack=track;model.sheet="mini"
        }
        onDispose {(playback as? MusicPlaybackGateway)?.onLivePlay=null}
    }
    LaunchedEffect(model.tab,provider.authRevision) {
        provider.checkFeedWindow()
        if(model.tab=="Library" && provider.signedIn) provider.scanLibraryPlaylists().onFailure {onNotice(it.message?:"Could not check playlists")}
    }
    val holder = rememberSaveableStateHolder()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val chromePaddingTarget = if (!showChrome) 20.dp else if (model.miniVisible) {
        if (compact) 160.dp else 154.dp
    } else if (compact) 90.dp else 84.dp
    val motion = LocalMotionPolicy.current
    val chromePadding by animateDpAsState(chromePaddingTarget,
        GlassMotion.timed(GlassMotion.selectionMs, motion), label = "chrome inset")
    val totalBottomPadding = chromePadding + navigationInset
    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(ambient)
        // Dismissing hides the expired-session banner until the account or session changes.
        var expiredBannerDismissed by remember(provider.authRevision, provider.sessionState) { mutableStateOf(false) }
        AnimatedContent(targetState = model.tab to model.route,
            transitionSpec = {
                if (motion.reduced) EnterTransition.None togetherWith ExitTransition.None
                else {
                    val detailChange = initialState.first == targetState.first
                    val direction = when {
                        !detailChange -> 0
                        targetState.second.isNotEmpty() -> 1
                        else -> -1
                    }
                    (fadeIn(GlassMotion.timed(GlassMotion.screenMs, motion)) +
                        slideInHorizontally(GlassMotion.timed(GlassMotion.screenMs, motion)) {
                            it * direction / 12
                        }) togetherWith
                        (fadeOut(GlassMotion.timed(GlassMotion.screenMs, motion)) +
                            slideOutHorizontally(GlassMotion.timed(GlassMotion.screenMs, motion)) {
                                -it * direction / 12
                            })
                }
            }, label = "scene navigation", modifier = Modifier.fillMaxSize()) { (tab, route) ->
        holder.SaveableStateProvider("$tab/$route") {
        androidx.compose.runtime.key(if(route.isEmpty()) model.rootRevisions[tab]?:0 else 0) {
        when (route) {
            "youtubeLive" -> YouTubeLiveScreen(model,playback,totalBottomPadding)
            "account" -> AccountScreen(model, provider, collections, playback, onNotice)
            "device" -> OnDeviceScreen(model, totalBottomPadding, playback)
            "accessibility" -> AccessibilityScreen(model, totalBottomPadding)
            "downloads" -> DownloadsScreen(model,totalBottomPadding,playback,onNotice)
            "settings" -> SettingsScreen(model,totalBottomPadding,provider,collections,onNotice)
            "listening_history" -> ListeningHistoryScreen(model,totalBottomPadding,provider,playback,onNotice)
            "not_interested" -> NotInterestedScreen(model,totalBottomPadding,onNotice)
            "remote_library" -> ProviderCollectionScreen(model, totalBottomPadding, provider, playback, onNotice, liked = true)
            "collection" -> CatalogDetailScreen(model,totalBottomPadding,provider,playback,onNotice)
            else -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                when (tab) {
                    "Podcasts", "Radio" -> MediaExploreScreen(model, totalBottomPadding, tab, provider, playback, onNotice)
                    "Search" -> CatalogSearchScreen(model,totalBottomPadding,playback,provider,onNotice)
                    "Library" -> AccountLibraryScreen(model,totalBottomPadding,collections,playback,provider,onNotice)
                    else -> HomeFeedScreen(model, totalBottomPadding, playback, collections, provider, onNotice, onCategoryBounds)
                }
                // W5: Home and Library are where an expired session shows first (empty Library, failing likes).
                if ((tab == "Home" || tab == "Library") && provider.sessionState == SessionState.Expired && !expiredBannerDismissed)
                    SessionExpiredBanner(onSignIn = { model.push("account") }, onDismiss = { expiredBannerDismissed = true })
            }
        }
        }
        }
        }
    }
}

@Composable
internal fun ScreenList(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listState = rememberLazyListState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    androidx.compose.runtime.CompositionLocalProvider(LocalShelfInset provides 20.dp) {LazyColumn(
        state = listState, modifier = Modifier.fillMaxSize().then(
            if (landscape) Modifier.padding(bottom = bottom) else Modifier),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            top = statusInset + 4.dp, bottom = if (landscape) 20.dp else bottom),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content
    )}
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), color = Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp)
}

@Composable
internal fun ScreenTitle(title: String, subtitle: String? = null) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(title) { focusRequester.requestFocus() }
    Column(Modifier.focusRequester(focusRequester).focusable()
        .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = White, fontSize = 31.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.6f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (action != null) Text(action, color = Pink,
            fontSize = if (largeText) 11.sp else 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = hapticClick(onAction)).padding(8.dp))
    }
}

@Composable
private fun SearchScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    playback: PlaybackGateway, provider: YouTubeMusicProvider, onNotice: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val voice = remember(context) { VoiceSearchController(context) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start { model.searchQuery = it } else voice.permissionDenied()
    }
    var rows by remember { mutableStateOf<List<ProviderTrack>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(model.searchQuery, provider.authRevision, retry) {
        rows = emptyList(); next = null; error = null; loading = false; loadingMore = false
        val query = model.searchQuery.trim()
        if (query.isNotEmpty()) {
            loading = true
            delay(450)
            provider.searchPage(query).fold(onSuccess = { rows = it.tracks; next = it.nextToken },
                onFailure = { error = it.message ?: "Search is unavailable" })
            loading = false
        }
    }
    val visible = if (model.searchSort == "Title") rows.sortedBy { it.title.lowercase() } else rows
    ScreenList(model, bottom) {
        item { ScreenTitle("Search", "YouTube Music") }
        item {
            OutlinedTextField(value = model.searchQuery, onValueChange = { model.searchQuery = it.take(160) },
                label = { Text("Songs, artists or albums") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("search_input"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    IconButton(onClick = { uiHaptic(); 
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            voice.start { model.searchQuery = it }
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Filled.Mic, contentDescription = "Voice search") }
                })
        }
        voice.message?.let { status -> item { Text(status, color = Muted, fontSize = 12.sp) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilterChip(selected = model.searchSort == "Title",
                    onClick = { uiHaptic();  model.searchSort = if (model.searchSort == "Title") "Relevance" else "Title" },
                    label = { Text("Sort: ${model.searchSort}") })
                Button(onClick = { uiHaptic();  model.push("account") }, modifier = Modifier.testTag("account_open")) {
                    Text(if (provider.signedIn) "Account" else "Sign in")
                }
            }
        }
        if (loading) item { Text("Searching\u2026", color = Muted) }
        error?.let { message -> item {
            Text(message, color = Pink)
            Button(onClick = { uiHaptic();  retry++ }) { Text("Retry") }
        } }
        if (!loading && error == null && rows.isEmpty()) item {
            Text(if (model.searchQuery.isBlank()) "Search YouTube Music to find songs." else "No songs found.",
                color = Muted, modifier = Modifier.testTag("search_empty"))
        }
        items(visible, key = { it.videoId }) { track ->
            ProviderSongRow(track, onPlay = {
                keyboard?.hide()
                scope.launch { playRealTrack(provider, playback, track, onNotice) }
            })
        }
        if (next != null) item {
            Button(onClick = { uiHaptic(); 
                val token = next ?: return@Button
                val query = model.searchQuery.trim()
                val revision = provider.authRevision
                loadingMore = true
                scope.launch {
                    val result = provider.searchPage(query, token)
                    if (model.searchQuery.trim() == query && provider.authRevision == revision && next == token) {
                        result.fold(onSuccess = { page ->
                            rows = (rows + page.tracks).distinctBy(ProviderTrack::videoId)
                            next = page.nextToken?.takeUnless { it == token }
                        }, onFailure = { onNotice(it.message ?: "Could not load more songs") })
                        loadingMore = false
                    }
                }
            }, enabled = !loadingMore, modifier = Modifier.fillMaxWidth().testTag("provider_load_more")) {
                Text(if (loadingMore) "Loading\u2026" else "Load more songs")
            }
        }
    }
}

internal suspend fun playRealTrack(provider: YouTubeMusicProvider, playback: PlaybackGateway,
    track: ProviderTrack, onNotice: (String) -> Unit,video:Boolean=false) {
    if(!video && !track.isLive) {playback.playSong(track);return}
    val revision = provider.authRevision
    kotlinx.coroutines.withTimeoutOrNull(if(track.isLive) 60_000 else 30_000) {provider.resolve(track.videoId,track.isLive,video=video || track.isLive)}.let {result->
        if(revision!=provider.authRevision) return
        result?.fold(onSuccess={playback.playProvider(track,it);if(track.isLive) (playback as? MusicPlaybackGateway)?.onLivePlay?.invoke(track)},
            onFailure={onNotice(if(track.isLive) "Live stream unavailable. Try refreshing Radio." else it.message?:"This song is unavailable")})
            ?: onNotice("Preparing playback timed out. Try again.")
    }
}

@Composable
internal fun ProviderSongRow(track: ProviderTrack, onPlay: () -> Unit,
    saved: Boolean? = null, onSave: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(12.dp))
        .clickable(onClick = hapticClick(onPlay)).testTag("provider_result_${track.videoId}"),
        verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = track.artworkUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(White.copy(alpha = .08f)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(track.title, color = White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, color = Muted, fontSize = 12.sp, maxLines = 1)
        }
        if (saved != null) IconButton(onClick = hapticClick(onSave)) {
            Icon(if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (saved) "Remove favorite" else "Add favorite", tint = Pink)
        }
    }
}

@Composable
private fun ExploreScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp, title: String) {
    ScreenList(model, bottom) {
        item { ScreenTitle(title) }
        item { Text("Find ${title.lowercase()} on YouTube Music.", color = Muted) }
        item { Button(onClick = { uiHaptic();  model.searchQuery = title.lowercase(); model.select("Search") }) {
            Text("Search ${title.lowercase()}")
        } }
    }
}

@Composable
private fun LibraryScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    collections: LocalCollections, playback: PlaybackGateway, provider: YouTubeMusicProvider,
    onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val tracks = when (model.libraryFilter) {
        "Favorites" -> collections.onlineFavorites
        "Playlists" -> collections.onlinePlaylist
        "History" -> collections.onlineHistory
        else -> (collections.onlineFavorites + collections.onlinePlaylist + collections.onlineHistory)
            .distinctBy(ProviderTrack::videoId)
    }
    ScreenList(model, bottom) {
        item { ScreenTitle("Library", "Your music") }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Favorites", "Playlists", "History").forEach { filter ->
                    FilterChip(selected = model.libraryFilter == filter,
                        onClick = { uiHaptic();  model.libraryFilter = filter }, label = { Text(filter) })
                }
            }
        }
        item { ToolLink("On-device audio") { model.push("device") } }
        item { ToolLink(if (provider.signedIn) "YouTube Music liked songs" else "Sign in to YouTube Music") {
            model.push(if (provider.signedIn) "remote_library" else "account")
        } }
        item { ToolLink("Display and accessibility") { model.push("accessibility") } }
        if (model.libraryFilter == "History") item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Save listening history", color = White, modifier = Modifier.weight(1f))
                Switch(checked = collections.historyEnabled, onCheckedChange = collections::updateHistoryPreference)
            }
        }
        if (tracks.isEmpty()) item {
            Text(if (provider.accountScope() == AccountScope.Unverified) VERIFY_TO_SAVE_NOTICE
                else if (!collections.canSave) "Sign in to save your favorite songs and playlists."
                else "No saved songs yet.", color = Muted, modifier = Modifier.testTag("library_empty"))
        }
        items(tracks, key = { it.videoId }) { track ->
            ProviderSongRow(track, onPlay = { scope.launch { playRealTrack(provider, playback, track, onNotice) } },
                saved = collections.onlineFavorites.any { it.videoId == track.videoId },
                onSave = { collections.toggleOnlineFavorite(track) })
        }
    }
}

@Composable
private fun ProviderCollectionScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit,
    liked: Boolean = false) {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<ProviderTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var next by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(model.selectedPlaylistId, provider.authRevision, retry) {
        loading = true; tracks = emptyList(); next = null; error = null
        val result = if (liked) provider.likedSongsPage() else provider.collectionPage(model.selectedPlaylistId)
        result.fold(onSuccess = { tracks = it.tracks; next = it.nextToken },
            onFailure = { error = it.message ?: "Collection is unavailable" })
        loading = false
    }
    ScreenList(model, bottom) {
        item { BackHeading("Back", model::back) }
        item { ScreenTitle(if (liked) "Liked songs" else model.selectedCollectionTitle) }
        if (loading) item { Text("Loading\u2026", color = Muted) }
        error?.let { message -> item { Text(message, color = Pink); Button(onClick = { uiHaptic();  retry++ }) { Text("Retry") } } }
        if (!loading && error == null && tracks.isEmpty()) item { Text("No songs available in this collection.", color = Muted) }
        items(tracks, key = { it.videoId }) { track ->
            ProviderSongRow(track, onPlay = { scope.launch { playRealTrack(provider, playback, track, onNotice) } })
        }
        if (next != null) item {
            Button(onClick = { uiHaptic(); 
                val token = next ?: return@Button
                val id = model.selectedPlaylistId
                val revision = provider.authRevision
                loading = true
                scope.launch {
                    val result = if (liked) provider.likedSongsPage(token) else provider.collectionPage(id, token)
                    if (revision == provider.authRevision && model.selectedPlaylistId == id && next == token) {
                        result.fold(onSuccess = {
                            tracks = (tracks + it.tracks).distinctBy(ProviderTrack::videoId)
                            next = it.nextToken?.takeUnless { value -> value == token }
                        }, onFailure = { onNotice(it.message ?: "Could not load more songs") })
                        loading = false
                    }
                }
            }, enabled = !loading) { Text("Load more songs") }
        }
    }
}

@Composable
internal fun ToolLink(title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = hapticClick(onClick))
        .background(White.copy(alpha = .06f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = White, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Muted)
    }
}


@Composable
private fun AccessibilityScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp) {
    val settings = LocalAccessibilitySettings.current
    ScreenList(model, bottom) {
        item { BackHeading("Library", model::back) }
        item { ScreenTitle("Display and accessibility", "Choices saved on this phone") }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Solid, high-contrast controls", color = White)
                    Text("Use opaque player and navigation surfaces instead of clear glass.",
                        color = Muted, fontSize = 12.sp)
                }
                Switch(checked = settings.solidControls,
                    onCheckedChange = hapticToggle(settings::updateSolidControls),
                    modifier = Modifier.semantics {
                        contentDescription = "Solid, high-contrast controls"
                    }.testTag("solid_controls"))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Reduce motion", color = White)
                    Text("Skip ambient fades and large player and selector transitions.",
                        color = Muted, fontSize = 12.sp)
                }
                Switch(checked = settings.reduceMotion,
                    onCheckedChange = hapticToggle(settings::updateReduceMotion),
                    modifier = Modifier.semantics { contentDescription = "Reduce motion" }
                        .testTag("reduce_motion"))
            }
        }
        item { Text("Swipe gestures still follow your finger. Tap controls remain available.",
            color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun OnDeviceScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    playback: PlaybackGateway) {
    val motion = LocalMotionPolicy.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember(context) { LocalAudioRepository(context.applicationContext) }
    val audioPermission = remember { audioLibraryPermission() }
    var granted by remember { mutableStateOf(context.checkSelfPermission(
        audioPermission) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<LocalAudioTrack>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasAccess = context.checkSelfPermission(audioPermission) ==
                    PackageManager.PERMISSION_GRANTED
                if (!hasAccess && granted) {
                    granted = false
                    denied = true
                    tracks = emptyList()
                } else if (hasAccess) {
                    granted = true
                    refreshKey++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, refreshKey) {
        if (!granted) { tracks = emptyList(); return@LaunchedEffect }
        if (context.checkSelfPermission(audioPermission) !=
            PackageManager.PERMISSION_GRANTED) {
            granted = false
            denied = true
            tracks = emptyList()
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        repository.list().fold(onSuccess = { tracks = it }, onFailure = {
            tracks = emptyList()
            if (it is SecurityException) {
                granted = false
                denied = true
            } else loadError = "Could not read this device's audio library."
        })
        loading = false
    }
    val index = remember(tracks) { CatalogIndex(tracks.map { track ->
        CatalogEntry("local-${track.id}", track.title, track.artist, track.album)
    }) }
    val results = remember(index, query) { index.search(query) }
    ScreenList(model, bottom) {
        item { BackHeading("Library", model::back) }
        item { ScreenTitle("On device", "Audio files indexed by Android on this phone") }
        if (!granted) {
            item {
                Text(if (denied) "Audio access was denied. You can allow it when ready."
                    else "Allow audio access to show files stored on this phone.", color = Muted)
            }
            item { Button(onClick = { uiHaptic();  permission.launch(audioPermission) },
                modifier = Modifier.testTag("request_audio_permission")) {
                Text("Allow audio access")
            } }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${tracks.size} audio ${if (tracks.size == 1) "file" else "files"}",
                        color = Muted, modifier = Modifier.weight(1f))
                    Text("Refresh", color = Pink, modifier = Modifier.clickable { uiHaptic();  refreshKey++ }
                        .padding(12.dp).testTag("device_refresh"))
                }
            }
            item { OutlinedTextField(query, onValueChange = { query = it },
                singleLine = true, label = { Text("Search on-device audio") },
                modifier = Modifier.fillMaxWidth().testTag("device_search")) }
            if (loading) item { Text("Reading audio metadata\u2026", color = Muted) }
            loadError?.let { error -> item { Text(error, color = Pink) } }
            if (!loading && loadError == null && results.isEmpty()) item {
                Text(if (tracks.isEmpty()) "No music files were found on this phone."
                    else "No audio files match this search.", color = Muted)
            }
            items(results, key = { tracks[it].id }) { position ->
                val track = tracks[position]
                Row(Modifier.fillMaxWidth().height(64.dp)
                    .then(if (motion.reduced) Modifier else Modifier.animateItem())
                    .motionClickable { playback.playLocal(track) }
                    .testTag("local_track_${track.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${track.title}",
                        tint = Pink, modifier = Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${track.artist} \u00B7 ${track.album}", color = Muted,
                            fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BackHeading(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = hapticClick(onBack), modifier = Modifier.size(48.dp).testTag("back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to $title", tint = White)
        }
        Spacer(Modifier.width(8.dp))
        Text(title, color = White, fontSize = 16.sp)
    }
}
