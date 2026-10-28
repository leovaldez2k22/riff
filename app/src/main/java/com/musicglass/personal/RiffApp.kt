package com.musicglass.personal

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal val Ink = Color.Black

internal val Raised = Color(0xFF181A22)

internal val Muted = Color(0xFFADB0BF)

internal val Pink = Color(0xFFFF5EAC)

internal val White = Color(0xFFF9F8FC)

internal fun LayoutCoordinates.unclippedBoundsInRoot(): Rect {
    val origin = positionInRoot()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RiffApp() {
    val model = rememberSaveable(saver = ShellModel.Saver) { ShellModel() }
    val context = LocalContext.current
    val provider = remember(context) { YouTubeMusicProvider.shared(context) }
    val lyricsRepository = remember(context) { LyricsRepository.get(context) }
    val collections = remember(context) {
        LocalCollections(context, provider.accountScopeKey())
    }
    val playback = remember(context) { MusicPlaybackGateway(context) }
    val accessibility = remember(context) { AccessibilitySettings(context) }
    val statusBar = remember { StatusBarRequests() }
    StatusBarAppearance(statusBar)
    val hapticView=androidx.compose.ui.platform.LocalView.current
    val hapticOwner=LocalLifecycleOwner.current
    DisposableEffect(hapticView,hapticOwner,accessibility) {
        UiHaptics.bind(hapticView,hapticOwner.lifecycle,accessibility); PerfTrace.bind(hapticView)
        onDispose {UiHaptics.unbind(hapticView); PerfTrace.unbind(hapticView)}
    }
    val motion = rememberMotionPolicy(accessibility.reduceMotion, accessibility.motionPace)
    val ambient = rememberAmbientPalette(motion)
    DisposableEffect(playback) { onDispose { playback.close() } }
    
    LaunchedEffect(provider.authRevision, provider.storageRevision) {
        model.searchOwnerIs(provider.storageScopeKey())
        collections.bindAccountScope(provider.accountScopeKey()); collections.refresh()
        // A restored account's file waits for that account; signed in now, it is added.
        if (provider.accountScopeDigests().isNotEmpty() && AppContainer.get(context).localBackup.restoreWaiting() > 0) collections.refresh()
    }
    val sessions = remember(context) { AppContainer.get(context).sessions }
    DisposableEffect(sessions, collections) {
        val registration = sessions.register(object : SessionParticipant {
            // The playback service saves and stops the outgoing queue; only the player chrome closes here.
            override suspend fun quiesce(outgoing: SessionToken) {
                if (queueStopsWithAccount(playback.state.queue.map { it.mediaId })) { model.miniVisible = false; model.sheet = "" }
            }
            override suspend fun bind(current: SessionToken) {
                collections.bindAccountScope(provider.accountScopeKey())
            }
        })
        onDispose { registration.close() }
    }
    LaunchedEffect(playback.state.mediaId, playback.state.playing) {
        collections.refresh()
        if (playback.state.playing && playback.state.source == "YouTube Music")
            collections.recordOnlinePlayed(ProviderTrack(playback.state.mediaId.removePrefix("yt:"),
                playback.state.title, playback.state.artist, playback.state.artworkUrl,
                isLive=playback.state.live,isMusic=playback.state.music))
    }
    LaunchedEffect(playback.state.playing) { PerfTrace.shown("play_toggle"); if (playback.state.playing) PerfTrace.shown("first_audio") }
    LaunchedEffect(playback.state.mediaId) {
        PerfTrace.shown("skip")
        model.miniVisible = playback.state.mediaId.isNotBlank()
        if (!model.miniVisible) model.sheet = ""
        model.mediaChanged(playback.state.mediaId, System.currentTimeMillis())
    }
    DisposableEffect(model, playback) {
        SongTaps.listener = { id -> model.songTapped(id, playback.state.mediaId, System.currentTimeMillis()) }
        onDispose { SongTaps.listener = null }
    }
    LaunchedEffect(playback.state.mediaId,playback.state.title,playback.state.artist,playback.state.durationMs,playback.state.playing) {
        val state=playback.state
        if(state.playing && !state.live && state.durationMs>0 && state.mediaId.isNotBlank()) {
            val track=LyricsTrack(state.mediaId,state.title,state.artist,state.album,state.durationMs)
            lyricsRepository.preload(track) { provider.lyricsFor(track.mediaId.removePrefix("yt:"),track.durationMs) }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    // Feature 26: removals offer Undo for a few seconds; a newer one replaces the last.
    val undoScope = rememberCoroutineScope()
    DisposableEffect(snackbar) {
        UndoNotices.listener = { message, undo -> undoScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(message, actionLabel = string(R.string.undo), duration = androidx.compose.material3.SnackbarDuration.Short)
                == androidx.compose.material3.SnackbarResult.ActionPerformed) { uiHaptic(HapticCue.Confirm); undo() }
        } }
        onDispose { UndoNotices.listener = null }
    }
    // Feature 13: a YouTube Music link opened in Riff (tapped, or shared to it).
    val pendingLink by SharedLinkInbox.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val target = pendingLink ?: return@LaunchedEffect
        SharedLinkInbox.pending.value = null
        when (target) {
            is SharedTarget.Collection -> { model.sheet = ""; model.openCollection(target.browseId, "") }
            is SharedTarget.Song -> provider.resolve(target.videoId).fold(onSuccess = { stream ->
                val track = ProviderTrack(target.videoId, stream.resolvedTitle, stream.resolvedAuthor, providerArtworkUrl(target.videoId))
                SongTaps.tapped("yt:${target.videoId}")
                playback.playProvider(track, stream, target.startMs)
            }, onFailure = { snackbar.showSnackbar(it.message ?: string(R.string.link_could_not_open)) })
        }
    }
    // Feature 27: app shortcuts and the Quick Settings tile; Resume names the song that is loaded.
    val pendingShortcut by SharedLinkInbox.shortcut.collectAsStateWithLifecycle()
    LaunchedEffect(pendingShortcut) {
        val which = pendingShortcut ?: return@LaunchedEffect
        SharedLinkInbox.shortcut.value = null
        model.sheet = ""
        when (which) {
            AppShortcut.Search -> { model.select("Search"); model.searchFocusRequest++ }
            AppShortcut.Downloads -> model.push(Route.Downloads)
            AppShortcut.ShuffleLiked -> if (!provider.signedIn) { model.select("Library"); model.resetRoot("Library"); model.libraryFilter = "Favorites" }
                else provider.likedSongsPage().fold(onSuccess = { page ->
                    if (page.tracks.isEmpty()) snackbar.showSnackbar(string(R.string.no_liked_songs))
                    else playback.playCollection(page.tracks, 0, string(R.string.liked_songs), shuffle = true)
                }, onFailure = { snackbar.showSnackbar(it.message ?: string(R.string.could_not_load_liked_songs)) })
            AppShortcut.Resume -> if (playback.state.queue.isNotEmpty()) playback.resume() else playback.restoreSavedQueue { if (it) playback.resume() }
        }
    }
    val resumeTitle = playback.state.title
    LaunchedEffect(resumeTitle) { withContext(Dispatchers.IO) { publishAppShortcuts(context, resumeTitle) } }
    val scope = rememberCoroutineScope()
    val cardPeek = remember(scope) { CardPeekController(scope) }
    // Where the player is between the mini bar and the full screen. Its frames are read through lambdas and
    // derived state below, so a drag or a settle redraws the player and not the screens under it.
    val player = remember { PlayerPresentationState() }
    val playerMorph = player.morph
    LaunchedEffect(playerMorph) { playerMorph.run() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.stopped(model.sheet.isNotEmpty())
            if (event == Lifecycle.Event.ON_START) provider.warmStreams()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.sheet.isNotEmpty(), motion) { player.sheetChanged(model.sheet.isNotEmpty(), motion) }
    LaunchedEffect(playback.state.error) {
        playback.state.error?.takeUnless {playback.state.live}?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(playback.state.notice) {
        playback.state.notice?.let { snackbar.showSnackbar(it) }
    }
    // Q12A: one notice per newer version, which the user can dismiss; its action opens the release page.
    val updates = remember(context) { AppContainer.get(context).updates }
    val updateNotice = updates.pendingNotice
    LaunchedEffect(updateNotice) {
        val release = updateNotice ?: return@LaunchedEffect
        updates.noticeShown(release)
        val result = snackbar.showSnackbar(string(R.string.riff_version_is_available, release.version.toString()),
            actionLabel = string(R.string.view_release), withDismissAction = true, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) openWebPage(context, release.page, updates.pageHosts)
    }
    val density = LocalDensity.current.density
    val compact = LocalWindowInfo.current.containerSize.width / density < 390f ||
        LocalDensity.current.fontScale >= 1.3f
    // Feature 30: a rail on wide windows, and on the widest the player as a pane beside the screens.
    val window = LocalWindowInfo.current.containerSize
    val widthClass = shellWidth(window.width / density, window.height / density)
    val railed = widthClass != ShellWidth.Compact
    val paned = widthClass == ShellWidth.Expanded && model.miniVisible
    val paneWidth = if (paned) maxOf((window.width / density * .4f).dp, RiffShell.paneLeast) else RiffShell.none
    val shellInsets = ShellInsets(if (railed) RiffShell.rail + RiffShell.railEdge * 2 else RiffShell.none, paneWidth)
    // The pane is the player: opening it full screen there would cover what the pane sits beside.
    LaunchedEffect(paned, model.sheet) { if (paned && model.sheet == "mini") model.sheet = "" }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val sceneHasChrome = !keyboardOpen && model.current != Route.Account
    // Thresholds invalidate the shell only at a visibility change, not on every morph frame.
    val showChrome by remember(sceneHasChrome, playerMorph, paned) {
        derivedStateOf { sceneHasChrome && (paned || playerMorph.position.value < .999f || player.draggingMini) }
    }
    val showPlayer by remember(model, playerMorph, paned) {
        derivedStateOf { paned || model.sheet.isNotEmpty() || playerMorph.position.value > MORPH_SHOWN }
    }
    val showMini = model.miniVisible
    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    var area by remember { mutableStateOf(IntSize.Zero) }
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    val miniElementBounds = remember { mutableStateMapOf<String, Rect>() }
    val categoryBounds = remember { mutableStateMapOf<String, Rect>() }
    val categoryChips = remember { mutableStateMapOf<String, GlassChipStyle>() }
    val backdropLayer=androidx.compose.ui.graphics.rememberGraphicsLayer()
    // Feature 1: the page under the shell's glass, recorded by the glass scene.
    val glassLayer=androidx.compose.ui.graphics.rememberGraphicsLayer()
    val glassBackdrop=remember(glassLayer) {RiffBackdrop(glassLayer)}
    val glassStyle=rememberGlassStyle(accessibility)
    // Feature 2: the screens' background, for glass inside a page.
    val pageBackdrop=rememberRiffBackdrop()
    val frostedBackdrop=remember(backdropLayer) {FrostedBackdrop(backdropLayer)}
    // Feature 22: the gesture previews the close of the player, or the way back from a pushed page.
    val backGesture = RiffBackHandler(enabled = !keyboardOpen && (model.sheet.isNotEmpty() || model.route.isNotEmpty() || model.tab != "Home"),
        reduced = motion.reduced) { model.back() }
    val pagePreview = if (model.sheet.isEmpty() && model.route.isNotEmpty()) Modifier.pageBackPreview(backGesture) else Modifier

    CompositionLocalProvider(LocalGlassBounds provides categoryBounds,LocalGlassChips provides categoryChips,
        LocalFrostedBackdrop provides frostedBackdrop,LocalRiffBackdrop provides glassBackdrop,LocalPageBackdrop provides pageBackdrop,LocalGlassStyle provides glassStyle,LocalFrostedIntensity provides if(accessibility.solidControls) -1f else accessibility.frostedIntensity,
        LocalSongCrossfade provides SongCrossfadeState.active,
        androidx.compose.foundation.LocalOverscrollFactory provides StretchOverscrollFactory,LocalAccessibilitySettings provides accessibility,LocalStatusBarRequests provides statusBar,
        LocalMotionPolicy provides motion,LocalCardPeek provides cardPeek,
        LocalOpenDownloads provides { model.sheet = ""; model.push(Route.Downloads) }) {
    RiffTheme {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalLyricsSharedScope provides this) {
        Box(Modifier.fillMaxSize().preferredFrameRate(LocalUiRefreshRate.current).background(Ink)
            .frostedBackdropSource(frostedBackdrop)
            .onSizeChanged { area = it }
            .semantics { testTagsAsResourceId = true }) {
            GlassScene(glassBackdrop) {
                Box(Modifier.fillMaxSize().then(pagePreview)) {
                ShellScene(model, compact, sceneHasChrome, playback, collections, provider,
                    ambient,
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } },
                    onCategoryBounds = { label, bounds ->
                        val old = categoryBounds[label]
                        if (bounds == null) categoryBounds.remove(label)
                        else if (old == null || kotlin.math.abs(old.left - bounds.left) > .5f ||
                            kotlin.math.abs(old.top - bounds.top) > .5f || old.size != bounds.size)
                            categoryBounds[label] = bounds
                    }, insets = shellInsets, bottomTabs = !railed, showMini = model.miniVisible && !paned)
                }
                // Feature 11: inside the glass scene, so the floating bar refracts the darkened page, not a darker band.
                EdgeFades(highContrast = accessibility.solidControls)
            }
            if(!accessibility.solidControls) VisibleGlassLabels(categoryBounds,categoryChips,model.tab,model.route,area)
            AnimatedVisibility(visible = showChrome,
                enter = fadeIn(GlassMotion.timed(GlassMotion.selectionMs, motion)) +
                    slideInVertically(GlassMotion.timed(GlassMotion.selectionMs, motion)) { it / 6 },
                exit = fadeOut(GlassMotion.exit(GlassMotion.selectionMs, motion)) +
                    slideOutVertically(GlassMotion.exit(GlassMotion.selectionMs, motion)) { it / 6 },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = shellInsets.start, end = shellInsets.end)) {
                ChromeBar(model, compact, playback, Modifier, tabs = !railed, showMini = model.miniVisible && !paned,
                    bottomInset = bottomInset / density,
                    miniMorph = { playerMorph.position.value },
                    miniDragging = player.draggingMini,
                    onMiniBounds = { miniBounds = it },
                    onMiniElementBounds = { name, bounds -> miniElementBounds[name] = bounds },
                    onMiniDrag = { delta, time -> player.dragMini(delta, time, area.height) },
                    onMiniRelease = { player.releaseMini(model.sheet.isNotEmpty(), motion, area.height) { model.sheet = "mini" } },
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } })
            }
            if (railed && showChrome) GlassRail(model, Modifier.align(Alignment.CenterStart)
                .padding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start).asPaddingValues()).padding(start = RiffShell.railEdge))
            val playerArea = if (paned) Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(paneWidth) else Modifier.fillMaxSize()
            if(model.sheet=="lyrics" || isTransitionActive) Box(playerArea.background(Ink).blockTouchesBelow())
            AnimatedVisibility(visible=model.sheet!="lyrics",
                enter=fadeIn(GlassMotion.timed(GlassMotion.playerMs,motion)) + slideInVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) { -it/14 },
                exit=fadeOut(GlassMotion.exit(GlassMotion.playerMs,motion)) + slideOutVertically(GlassMotion.exit(GlassMotion.playerMs,motion)) { -it/14 },
                modifier=playerArea.playerBackPreview(backGesture)) {
            CompositionLocalProvider(LocalLyricsVisibilityScope provides this) {
            if (showPlayer) {
                PlayerMorphHost(if (paned) {{ 1f }} else {{ playerMorph.position.value }}, model, compact, playback, collections,
                    bottomInset / density, miniBounds, miniElementBounds, player.draggingFull, provider,
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } },
                    onMorphDrag = { delta, time -> if (!paned) player.dragFull(delta, time, area.height) },
                    onMorphRelease = { velocity -> if (!paned) player.releaseFull(velocity, model.sheet.isNotEmpty(), motion, area.height) { model.sheet = "" } },
                    onArtist = {
                        val state=playback.state
                        scope.launch {
                            provider.artistFor(ProviderTrack(state.mediaId.removePrefix("yt:"),state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live)).fold(
                                onSuccess={id->if(id!=null) {model.sheet="";model.openCollection(id,playerArtistLabel(state.artist))} else snackbar.showSnackbar(string(R.string.artist_profile_is_unavailable_for_this_recor))},
                                onFailure={snackbar.showSnackbar(it.message?:string(R.string.could_not_open_artist))})
                        }
                    },
                    onAlbum = {
                        val state=playback.state
                        scope.launch {
                            provider.albumFor(state.mediaId.removePrefix("yt:"),state.albumId).fold(
                                onSuccess={id->if(id!=null) {model.sheet="";model.openCollection(id,state.album.ifBlank {string(R.string.album)})} else snackbar.showSnackbar(string(R.string.album_is_unavailable_for_this_recording))},
                                onFailure={snackbar.showSnackbar(it.message?:string(R.string.could_not_open_album))})
                        }
                    },
                    onFailureAction = { action ->
                        val state = playback.state
                        val track = ProviderTrack(state.mediaId.removePrefix("yt:"),
                            state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live,state.music)
                        when(action) {
                            // The service tries again and keeps the queue, the position and the entry.
                            FailureAction.Retry -> playback.retry()
                            FailureAction.Skip -> playback.next()
                            FailureAction.SignIn -> {model.sheet="";model.push(Route.Account)}
                            // The files of this download are unusable. Removing them lets the song stream; downloading again replaces them.
                            FailureAction.Redownload, FailureAction.RemoveDownload -> scope.launch {
                                val store=OfflineMusicStore.get(context)
                                val done=withContext(Dispatchers.IO) {runCatching {
                                    store.remove(store.currentScope,track.videoId,exclude=action==FailureAction.RemoveDownload)
                                    if(action==FailureAction.Redownload) store.enqueue(track)
                                }.isSuccess}
                                snackbar.showSnackbar(if(!done) string(R.string.could_not_change_this_download) else if(action==FailureAction.Redownload) string(R.string.downloading_again) else string(R.string.download_removed))
                                if(done) playback.retry()
                            }
                        }
                    })
            }
            }
            }
            AnimatedVisibility(visible = model.sheet == "lyrics",
                enter = fadeIn(GlassMotion.timed(GlassMotion.playerMs, motion)) + slideInVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) {it/14},
                exit = fadeOut(GlassMotion.exit(GlassMotion.playerMs, motion)) + slideOutVertically(GlassMotion.exit(GlassMotion.playerMs,motion)) {it/14},
                modifier = playerArea) {
                CompositionLocalProvider(LocalLyricsVisibilityScope provides this) {
                // Touches that no lyrics control takes stop here: before, a tap between the controls at the bottom
                // reached the home page underneath and could start one of its songs.
                Box(Modifier.fillMaxSize().playerBackPreview(backGesture).background(Color.Black).blockTouchesBelow().testTag("lyrics_screen")) {
                    LyricsPanel(playback, provider, lyricsRepository, onBack = { model.sheet = "mini" })
                }
                }
            }
            CardPeekLayer(cardPeek, model, provider, playback, onNotice = { scope.launch { snackbar.showSnackbar(it) } })
            RestoreOffer(onNotice = { scope.launch { snackbar.showSnackbar(it) } }, onRestored = { collections.refresh() })
            // Feature 2: snack bars are Menu glass over the page; a dark tint keeps them readable over bright covers.
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter)
                .padding(WindowInsets.safeDrawing.asPaddingValues()).padding(top = 12.dp)) { data ->
                androidx.compose.material3.Snackbar(data, Modifier.padding(horizontal = RiffSpace.block).riffGlass(GlassRole.Menu, RiffRadius.card),
                    shape = RoundedCornerShape(RiffRadius.card), containerColor = Color(0xFF171C27).copy(alpha = .5f),
                    contentColor = White, actionColor = Pink)
            }
        }
        }
        }
        // Feature 29: once after an update, or the first-run tips on a new install.
        WhatsNewSheet(model)
        if (model.sheet == "libraryShortcuts") {
            ModalBottomSheet(onDismissRequest = { model.sheet = "mini" },
                containerColor = Raised, scrimColor = Color.Black.copy(alpha = .55f)) {
                Column(Modifier.fillMaxWidth().preferredFrameRate(LocalUiRefreshRate.current).padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)) {
                    Text(string(R.string.library), color = White, style = RiffText.sectionHeading,
                        modifier = Modifier.padding(bottom = 12.dp))
                    listOf("All", "Favorites", "History", "Playlists").forEach { filter ->
                        Text(filter, color = White, modifier = Modifier.fillMaxWidth()
                            .clickable { uiHaptic(); 
                                model.tab = "Library"
                                model.resetRoot("Library")
                                model.libraryFilter = filter
                                model.sheet = ""
                            }.padding(vertical = 15.dp)
                            .testTag("library_shortcut_${filter.lowercase()}"))
                    }
                }
            }
        }
    }
    }
}
