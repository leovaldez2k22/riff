package com.musicglass.personal

import android.graphics.RenderEffect
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

internal val Ink = Color.Black
internal val Raised = Color(0xFF181A22)
internal val Muted = Color(0xFFADB0BF)
internal val Pink = Color(0xFFFF5EAC)
internal val White = Color(0xFFF9F8FC)

internal fun LayoutCoordinates.unclippedBoundsInRoot(): Rect {
    val origin = positionInRoot()
    return Rect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
}

internal class ShellModel {
    var tab by mutableStateOf("Home")
    var stacks by mutableStateOf(mapOf<String, List<String>>())
    var miniVisible by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var homeMood by mutableStateOf("")
    var podcastGenre by mutableStateOf("For you")
    var radioCategory by mutableStateOf("Featured")
    var libraryFilter by mutableStateOf("All")
    var selectedPlaylistId by mutableStateOf("")
    var selectedCollectionTitle by mutableStateOf("Collection")
    var searchSort by mutableStateOf("Relevance")
    var searchFilter by mutableStateOf("All")
    var sheet by mutableStateOf("")

    val route: String get() = stacks[tab]?.lastOrNull().orEmpty()
    var liveTrack:ProviderTrack? by mutableStateOf(null)
    var searchFocusRequest by mutableStateOf(0)
    private var lastTapName=""
    private var lastTapMs: Long?=null
    var rootRevisions by mutableStateOf(mapOf<String,Int>())
        private set
    fun select(name: String) { tab=name }
    fun selectFromTap(name: String, nowMs: Long): Boolean {
        val previous=lastTapMs
        val reset=lastTapName==name && previous!=null && nowMs-previous in 0..350
        if(reset) {
            stacks=stacks+(name to emptyList())
            collectionTrail=collectionTrail.filterNot {it.first==name}
            rootRevisions=rootRevisions+(name to ((rootRevisions[name]?:0)+1))
            if(name=="Library") libraryFilter="All"
            if(name=="Home") homeMood=""
            if(name=="Podcasts") podcastGenre="For you"
            if(name=="Radio") radioCategory="Featured"
            if(name=="Search") searchFocusRequest++
            lastTapMs=null;lastTapName=""
        } else {lastTapMs=nowMs;lastTapName=name}
        tab=name
        return reset
    }
    fun push(route: String) { stacks = stacks + (tab to (stacks[tab].orEmpty() + route)) }
    private var collectionTrail: List<Triple<String,String,String>> = emptyList()
    fun openCollection(id: String,title: String) {
        if(route=="collection") collectionTrail=collectionTrail+Triple(tab,selectedPlaylistId,selectedCollectionTitle)
        selectedPlaylistId=id;selectedCollectionTitle=title;push("collection")
    }
    fun back() {
        val stack = stacks[tab].orEmpty()
        when {
            sheet == "queue" || sheet == "queueCollapsed" || sheet == "lyrics" ||
                sheet == "libraryShortcuts" -> sheet = "mini"
            sheet.isNotEmpty() -> sheet = ""
            stack.isNotEmpty() -> {
                if(route=="collection") collectionTrail.lastOrNull()?.takeIf {it.first==tab}?.let {
                    selectedPlaylistId=it.second;selectedCollectionTitle=it.third;collectionTrail=collectionTrail.dropLast(1)
                }
                stacks = stacks + (tab to stack.dropLast(1))
            }
            tab != "Home" -> tab = "Home"
        }
    }

    companion object {
        val Saver = Saver<ShellModel, List<String>>(
            save = { model ->
                listOf(model.tab, model.miniVisible.toString(), model.searchQuery,
                    model.libraryFilter, "",
                    "", model.selectedPlaylistId,
                    "YouTube Music", "Songs", model.searchSort,
                    *listOf("Home", "Podcasts", "Radio", "Search", "Library").map {
                        model.stacks[it].orEmpty().joinToString(",")
                    }.toTypedArray(), model.selectedCollectionTitle, "rc7", model.searchFilter,
                    model.collectionTrail.joinToString("\u001e") {listOf(it.first,it.second,it.third).joinToString("\u001f")})
            },
            restore = { values ->
                ShellModel().apply {
                    val expanded = values.size >= 14
                    val tabs = listOf("Home", "Podcasts", "Radio", "Search", "Library")
                    tab = values.getOrNull(0).let { if (it == "Samples") "Podcasts" else it }
                        .takeIf { it in tabs } ?: "Home"
                    miniVisible = false
                    searchQuery = values.getOrNull(2).orEmpty().take(160)
                    libraryFilter = values.getOrNull(3)
                        ?.takeIf { it in if(values.getOrNull(16)=="rc7") listOf("All","Songs","Favorites","Playlists","Albums","Artists","Podcasts","History","On device") else listOf("All", "Favorites", "Playlists", "History", "On device") } ?: "All"
                    if (expanded) {
                        selectedPlaylistId = values.getOrNull(6).orEmpty().take(200)
                        selectedCollectionTitle = values.getOrNull(15)?.take(180) ?: "Collection"
                        if(values.getOrNull(16)=="rc7") {
                            searchFilter=values.getOrNull(17)?.takeIf {it in listOf("All","Songs","Albums","Artists","Playlists","Podcasts")}?:"All"
                            collectionTrail=values.getOrNull(18).orEmpty().split("\u001e").mapNotNull {
                                val row=it.split("\u001f");if(row.size==3) Triple(row[0],row[1],row[2]) else null
                            }.take(8)
                        }
                        searchSort = values.getOrNull(9)
                            ?.takeIf { it in listOf("Relevance", "Title") } ?: "Relevance"
                    }
                    val savedTabs = if (values.size >= 15) tabs
                        else listOf("Home", "Podcasts", "Search", "Library")
                    val validRoutes = setOf("account", "device", "remote_library", "accessibility", "collection", "settings", "downloads")
                    stacks = savedTabs
                        .mapIndexed { index, tabName ->
                            tabName to values.getOrNull(index + if (expanded) 10 else 5).orEmpty()
                                .split(',').filter { it in validRoutes }.take(8)
                        }.toMap()
                }
            }
        )
    }
}

private class MorphDragTracker {
    var active = false
    var target = 0f
    var velocity = 0f
    private val times = LongArray(6)
    private val progress = FloatArray(6)
    private var samples = 0
    fun update(delta: Float, time: Long, range: Float) {
        target = (target - delta / range).coerceIn(0f, 1f)
        val slot = samples % times.size
        times[slot] = time
        progress[slot] = target
        samples++
        val oldest = (samples - times.size).coerceAtLeast(0)
        val firstSlot = oldest % times.size
        val elapsed = time - times[firstSlot]
        if (elapsed > 0L) velocity = (target - progress[firstSlot]) *
            range * 1000f / elapsed
    }
    fun reset() { active = false; velocity = 0f; samples = 0 }
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
    val hapticView=androidx.compose.ui.platform.LocalView.current
    val hapticOwner=LocalLifecycleOwner.current
    DisposableEffect(hapticView,hapticOwner,accessibility) {
        UiHaptics.bind(hapticView,hapticOwner.lifecycle,accessibility)
        onDispose {UiHaptics.unbind(hapticView)}
    }
    val motion = rememberMotionPolicy(accessibility.reduceMotion)
    val ambient = rememberAmbientPalette(motion)
    DisposableEffect(playback) { onDispose { playback.close() } }
    
    LaunchedEffect(provider.authRevision) {
        collections.bindAccountScope(provider.accountScopeKey())
    }
    LaunchedEffect(playback.state.mediaId, playback.state.playing) {
        collections.refresh()
        if (playback.state.playing && playback.state.source == "YouTube Music")
            collections.recordOnlinePlayed(ProviderTrack(playback.state.mediaId.removePrefix("yt:"),
                playback.state.title, playback.state.artist, playback.state.artworkUrl,
                isLive=playback.state.live,isMusic=playback.state.music))
    }
    LaunchedEffect(playback.state.mediaId) {
        model.miniVisible = playback.state.mediaId.isNotBlank()
        if (!model.miniVisible) model.sheet = ""
    }
    LaunchedEffect(playback.state.mediaId,playback.state.title,playback.state.artist,playback.state.durationMs,playback.state.playing) {
        val state=playback.state
        if(state.playing && !state.live && state.durationMs>0 && state.mediaId.isNotBlank()) {
            val track=LyricsTrack(state.mediaId,state.title,state.artist,state.album,state.durationMs)
            lyricsRepository.preload(track) { provider.lyricsFor(track.mediaId.removePrefix("yt:"),track.durationMs) }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val queueSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val playerMorph = remember { PlayerMorphController() }
    var draggingMini by remember { mutableStateOf(false) }
    var draggingFull by remember { mutableStateOf(false) }
    var pendingGestureTarget by remember { mutableStateOf<Boolean?>(null) }
    val miniDrag = remember { MorphDragTracker() }
    val fullDrag = remember { MorphDragTracker() }
    LaunchedEffect(playerMorph) { playerMorph.run() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playerMorph) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                miniDrag.reset()
                fullDrag.reset()
                draggingMini = false
                draggingFull = false
                playerMorph.settle(model.sheet.isNotEmpty(), MotionPolicy(true, 0f))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.sheet.isNotEmpty(), motion) {
        val open = model.sheet.isNotEmpty()
        if (pendingGestureTarget == open) pendingGestureTarget = null
        else playerMorph.settle(open, motion)
    }
    LaunchedEffect(playback.state.error) {
        playback.state.error?.takeUnless {playback.state.live}?.let { snackbar.showSnackbar(it) }
    }
    val density = LocalDensity.current.density
    val compact = LocalWindowInfo.current.containerSize.width / density < 390f ||
        LocalDensity.current.fontScale >= 1.3f
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val sceneHasChrome = !keyboardOpen && model.route !in setOf("account","youtubeLive")
    // Thresholds invalidate the shell only at a visibility change, not on every morph frame.
    val showChrome by remember(sceneHasChrome, playerMorph) {
        derivedStateOf { sceneHasChrome && (playerMorph.position.value < .999f || draggingMini) }
    }
    val showPlayer by remember(model, playerMorph) {
        derivedStateOf { model.sheet.isNotEmpty() || playerMorph.position.value > .001f }
    }
    val showMini = model.miniVisible
    val bottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    var area by remember { mutableStateOf(IntSize.Zero) }
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    val miniElementBounds = remember { mutableStateMapOf<String, Rect>() }
    val categoryBounds = remember { mutableStateMapOf<String, Rect>() }
    val categoryChips = remember { mutableStateMapOf<String, GlassChipStyle>() }
    BackHandler(enabled = !keyboardOpen && (model.sheet.isNotEmpty() || model.route.isNotEmpty() || model.tab != "Home")) {
        model.back()
    }

    CompositionLocalProvider(LocalGlassBounds provides categoryBounds,LocalGlassChips provides categoryChips,
        androidx.compose.foundation.LocalOverscrollFactory provides StretchOverscrollFactory,LocalAccessibilitySettings provides accessibility,
        LocalMotionPolicy provides motion) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Pink, background = Ink, surface = Raised),
        typography = GlassTypography) {
        SharedTransitionLayout {
        CompositionLocalProvider(LocalLyricsSharedScope provides this) {
        Box(Modifier.fillMaxSize().preferredFrameRate(LocalUiRefreshRate.current).background(Ink)
            .onSizeChanged { area = it }
            .semantics { testTagsAsResourceId = true }) {
            GlassScene(categoryBounds,area,density,compact,showMini,sceneHasChrome,bottomInset,
                model.tab,model.route,accessibility) {
                ShellScene(model, compact, sceneHasChrome, playback, collections, provider,
                    ambient,
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } },
                    onCategoryBounds = { label, bounds ->
                        val old = categoryBounds[label]
                        if (bounds == null) categoryBounds.remove(label)
                        else if (old == null || kotlin.math.abs(old.left - bounds.left) > .5f ||
                            kotlin.math.abs(old.top - bounds.top) > .5f || old.size != bounds.size)
                            categoryBounds[label] = bounds
                    })
            }
            if(!accessibility.solidControls) VisibleGlassLabels(categoryBounds,categoryChips,model.tab,model.route,area)
            AnimatedVisibility(visible = showChrome,
                enter = fadeIn(GlassMotion.timed(GlassMotion.selectionMs, motion)) +
                    slideInVertically(GlassMotion.timed(GlassMotion.selectionMs, motion)) { it / 6 },
                exit = fadeOut(GlassMotion.timed(GlassMotion.selectionMs, motion)) +
                    slideOutVertically(GlassMotion.timed(GlassMotion.selectionMs, motion)) { it / 6 },
                modifier = Modifier.align(Alignment.BottomCenter)) {
                ChromeBar(model, compact, playback, Modifier,
                    bottomInset = bottomInset / density,
                    miniMorph = { playerMorph.position.value },
                    miniDragging = draggingMini,
                    onMiniBounds = { miniBounds = it },
                    onMiniElementBounds = { name, bounds -> miniElementBounds[name] = bounds },
                    onMiniDrag = { delta, time ->
                        if (!miniDrag.active) {
                            miniDrag.target = playerMorph.position.value
                            miniDrag.active = true
                            draggingMini = true
                        }
                        miniDrag.update(delta, time, area.height.coerceAtLeast(1) * .55f)
                        playerMorph.dragTo(miniDrag.target)
                    },
                    onMiniRelease = {
                        val target = miniDrag.target
                        val releaseVelocity = miniDrag.velocity
                        val opening = playerReleaseOpens(target, releaseVelocity)
                        miniDrag.reset()
                        draggingMini = false
                        if (model.sheet.isNotEmpty() != opening) {
                            uiHaptic(HapticCue.DragEnd)
                            pendingGestureTarget = opening
                        }
                        if (opening) model.sheet = "mini"
                        playerMorph.settle(opening, motion, direct = true, fromDrag = target,
                            releaseVelocity = releaseVelocity / (area.height.coerceAtLeast(1) * .55f))
                    },
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } })
            }
            if(model.sheet=="lyrics" || isTransitionActive) Box(Modifier.fillMaxSize().background(Ink))
            AnimatedVisibility(visible=model.sheet!="lyrics",
                enter=fadeIn(GlassMotion.timed(GlassMotion.playerMs,motion)) + slideInVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) { -it/14 },
                exit=fadeOut(GlassMotion.timed(GlassMotion.playerMs,motion)) + slideOutVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) { -it/14 },
                modifier=Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLyricsVisibilityScope provides this) {
            if (showPlayer) {
                PlayerMorphHost({ playerMorph.position.value }, model, compact, playback, collections,
                    bottomInset / density, miniBounds, miniElementBounds, draggingFull, provider,
                    onNotice = { scope.launch { snackbar.showSnackbar(it) } },
                    onMorphDrag = { delta, time ->
                        if (!fullDrag.active) {
                            fullDrag.target = playerMorph.position.value
                            fullDrag.active = true
                            draggingFull = true
                        }
                        fullDrag.update(delta, time, area.height.coerceAtLeast(1) * .55f)
                        playerMorph.dragTo(fullDrag.target)
                    },
                    onMorphRelease = { velocity ->
                        val settledVelocity = if (kotlin.math.abs(velocity) > 650f) velocity
                            else fullDrag.velocity
                        val target = fullDrag.target
                        val releaseVelocity = settledVelocity
                        val opening = playerReleaseOpens(target, settledVelocity)
                        fullDrag.reset()
                        draggingFull = false
                        if (model.sheet.isNotEmpty() != opening) {
                            uiHaptic(HapticCue.DragEnd)
                            pendingGestureTarget = opening
                        }
                        if (!opening) model.sheet = ""
                        playerMorph.settle(opening, motion, direct = true, fromDrag = target,
                            releaseVelocity = releaseVelocity / (area.height.coerceAtLeast(1) * .55f))
                    },
                    onArtist = {
                        val state=playback.state
                        scope.launch {
                            provider.artistFor(ProviderTrack(state.mediaId.removePrefix("yt:"),state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live)).fold(
                                onSuccess={id->if(id!=null) {model.sheet="";model.openCollection(id,playerArtistLabel(state.artist))} else snackbar.showSnackbar("Artist profile is unavailable for this recording")},
                                onFailure={snackbar.showSnackbar(it.message?:"Could not open artist")})
                        }
                    },
                    onAlbum = {
                        val state=playback.state
                        scope.launch {
                            provider.albumFor(state.mediaId.removePrefix("yt:"),state.albumId).fold(
                                onSuccess={id->if(id!=null) {model.sheet="";model.openCollection(id,state.album.ifBlank {"Album"})} else snackbar.showSnackbar("Album is unavailable for this recording")},
                                onFailure={snackbar.showSnackbar(it.message?:"Could not open album")})
                        }
                    },
                    onRetry = {
                        val state = playback.state
                        scope.launch {
                            val track = ProviderTrack(state.mediaId.removePrefix("yt:"),
                                state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live)
                            if(!state.live) {playback.restoreAudio(track,playback.positionMs);return@launch}
                            provider.resolve(track.videoId,state.live).fold(
                                onSuccess = { playback.playProvider(track, it, playback.positionMs) },
                                onFailure = { snackbar.showSnackbar(it.message ?: "Retry failed") })
                        }
                    })
            }
            }
            }
            AnimatedVisibility(visible = model.sheet == "queueCollapsed",
                enter = fadeIn(GlassMotion.timed(GlassMotion.modalMs, motion)) +
                    slideInVertically(GlassMotion.timed(GlassMotion.modalMs, motion)) { it / 5 },
                exit = fadeOut(GlassMotion.timed(GlassMotion.contentMs, motion)) +
                    slideOutVertically(GlassMotion.timed(GlassMotion.contentMs, motion)) { it / 5 },
                modifier = Modifier.align(Alignment.BottomCenter)) {
                CollapsedQueue(playback, onExpand = { model.sheet = "queue" },
                    onClose = { model.sheet = "mini" },
                    modifier = Modifier.padding(horizontal = 16.dp)
                        .padding(bottom = (bottomInset / density + 10f).dp))
            }
            AnimatedVisibility(visible = model.sheet == "lyrics",
                enter = fadeIn(GlassMotion.timed(GlassMotion.playerMs, motion)) + slideInVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) {it/14},
                exit = fadeOut(GlassMotion.timed(GlassMotion.playerMs, motion)) + slideOutVertically(GlassMotion.timed(GlassMotion.playerMs,motion)) {it/14},
                modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalLyricsVisibilityScope provides this) {
                Box(Modifier.fillMaxSize().background(Color.Black).testTag("lyrics_screen")) {
                    LyricsPanel(playback, provider, lyricsRepository, onBack = { model.sheet = "mini" })
                }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter)
                .padding(WindowInsets.safeDrawing.asPaddingValues()).padding(top = 12.dp))
        }
        }
        }
        if (model.sheet == "libraryShortcuts") {
            ModalBottomSheet(onDismissRequest = { model.sheet = "mini" },
                containerColor = Raised, scrimColor = Color.Black.copy(alpha = .55f)) {
                Column(Modifier.fillMaxWidth().preferredFrameRate(LocalUiRefreshRate.current).padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)) {
                    Text("Library", color = White, fontSize = 23.sp,
                        modifier = Modifier.padding(bottom = 12.dp))
                    listOf("All", "Favorites", "History", "Playlists").forEach { filter ->
                        Text(filter, color = White, modifier = Modifier.fillMaxWidth()
                            .clickable { uiHaptic(); 
                                model.tab = "Library"
                                model.stacks = model.stacks + ("Library" to emptyList())
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

@Composable
private fun ChromeBar(model: ShellModel, compact: Boolean, playback: PlaybackGateway, modifier: Modifier,
    bottomInset: Float, miniMorph: () -> Float, miniDragging: Boolean,
    onMiniDrag: (Float, Long) -> Unit,
    onMiniRelease: () -> Unit, onMiniBounds: (Rect) -> Unit,
    onMiniElementBounds: (String, Rect) -> Unit,
    onNotice: (String) -> Unit) {
    val accessibility = LocalAccessibilitySettings.current
    val miniProgress = miniMorph()
    val navHeight = if (compact) 64.dp else 58.dp
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)
        .padding(bottom = (bottomInset + 10f).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.miniVisible) {
            Surface(shape = RoundedCornerShape(32.dp),
                color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                border = BorderStroke(1.dp, Color.White.copy(
                    alpha = if (accessibility.solidControls) .72f else .42f)),
                modifier = Modifier.fillMaxWidth().height(62.dp).testTag("mini_player")
                    .onGloballyPositioned { if(miniProgress<.001f) onMiniBounds(it.boundsInRoot()) }
                    .graphicsLayer { alpha = 1f - motionRamp(miniProgress, 0f, .02f) }
                    .pointerInput(model) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                onMiniDrag(amount, change.uptimeMillis)
                                change.consume()
                            }, onDragEnd = onMiniRelease,
                            onDragCancel = onMiniRelease)
                    }) {
                MiniPlayerContents(playback, onOpen = { model.sheet = "mini" },
                    onElementBounds = {name,bounds->if(miniProgress<.001f) onMiniElementBounds(name,bounds)})
            }
        }
        Box(Modifier.graphicsLayer {
            alpha = 1f - motionRamp(miniProgress, 0f, .5f)
        }) { LiquidTabBar(model, compact, navHeight) }
    }
}

@Composable
private fun MiniPlayerContents(playback: PlaybackGateway, onOpen: () -> Unit,
    actionsAlpha: Float = 1f, artworkAlpha: Float = 1f,
    onElementBounds: (String, Rect) -> Unit = { _, _ -> }) {
    val state = playback.state
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val context = LocalContext.current
    // W1.5: after a crash the queue comes back paused; say why once, until the user plays.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CrashNotice.load(context) } }
    LaunchedEffect(state.playing) { if (state.playing) withContext(Dispatchers.IO) { CrashNotice.clear(context) } }
    val crashNotice = CrashNotice.pending
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        AsyncImage(model = state.artworkUrl
            ?: providerArtworkUrl(state.mediaId.removePrefix("yt:")),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .graphicsLayer { alpha = artworkAlpha }
                .background(Brush.linearGradient(
                    listOf(Color(0xFF655080), Color(0xFF315B75)))))
        Column(Modifier.weight(1f).clickable(onClick = hapticClick(onOpen))
            .padding(start = 12.dp).semantics { contentDescription = "Open player: ${state.title}" }) {
            Text(state.title, color = White, fontSize = if (largeText) 12.sp else 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.onGloballyPositioned {
                    onElementBounds("title", it.unclippedBoundsInRoot()) })
            if (crashNotice)
                Text("Playback stopped unexpectedly. Tap play to continue.", color = Pink,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("mini_crash_notice")
                        .semantics { liveRegion = LiveRegionMode.Polite })
            else if (LocalDensity.current.fontScale < 1.8f)
                Text(playerArtistLabel(state.artist), color = Muted, fontSize = 11.sp,
                    maxLines = 1, modifier = Modifier.onGloballyPositioned {
                        onElementBounds("artist", it.unclippedBoundsInRoot()) })
        }
        if (crashNotice)
            IconButton(onClick = hapticClick { CrashNotice.clear(context) },
                modifier = Modifier.size(48.dp).testTag("mini_crash_notice_dismiss")) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss notice", tint = Muted)
            }
        IconButton(onClick = hapticClick(playback::toggle),
            modifier = Modifier.size(48.dp).graphicsLayer { alpha = actionsAlpha }
                .testTag("mini_play")) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playing) "Pause" else "Play", tint = White,
                modifier = Modifier.onGloballyPositioned {
                    onElementBounds("play", it.unclippedBoundsInRoot()) })
        }
        IconButton(onClick = hapticClick(playback::next), enabled = state.queue.size > 1,
            modifier = Modifier.size(48.dp).graphicsLayer { alpha = actionsAlpha }
                .testTag("mini_next")) {
            Icon(Icons.Rounded.FastForward, contentDescription = "Next track", tint = White,
                modifier = Modifier.onGloballyPositioned {
                    onElementBounds("next", it.unclippedBoundsInRoot()) })
        }
    }
}

@Composable
private fun CollapsedQueue(playback: PlaybackGateway, onExpand: () -> Unit,
    onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state = playback.state
    val current = state.index
    val next = state.queue.getOrNull(current + 1)?.title ?: "End of queue"
    Surface(shape = RoundedCornerShape(27.dp), color = Color(0xFF11121A),
        border = BorderStroke(1.dp, White.copy(alpha = .32f)),
        modifier = modifier.fillMaxWidth().height(104.dp).testTag("queue_collapsed")
            .pointerInput(onExpand) {
                var pull = 0f
                detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                    pull += amount
                    change.consume()
                }, onDragEnd = {
                    if (pull < -36.dp.toPx()) onExpand()
                    pull = 0f
                }, onDragCancel = { pull = 0f })
            }) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = hapticClick(onExpand))) {
                Text("UP NEXT \u00B7 ${state.queue.size} TRACKS", color = Pink,
                    fontSize = 11.sp, maxLines = 1)
                Text(state.title, color = White, fontSize = 16.sp, maxLines = 1)
                Text("Next: $next", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = hapticClick(onExpand), modifier = Modifier.testTag("queue_expand")) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Expand queue", tint = White)
            }
            IconButton(onClick = hapticClick(onClose), modifier = Modifier.testTag("queue_collapse_close")) {
                Icon(Icons.Rounded.Close, contentDescription = "Close queue", tint = Muted)
            }
        }
    }
}

/** Progress after which the flying morph elements hand over to the real player controls. */
private const val morphHandoffStart = .96f

/** One surface occupies the mini-player bounds and animates into the full player bounds. */
@Composable
private fun PlayerMorphHost(progress: () -> Float, model: ShellModel, compact: Boolean,
    playback: PlaybackGateway, collections: LocalCollections, bottomInset: Float,
    miniBounds: Rect?, miniElementBounds: Map<String, Rect>, fullDragging: Boolean, provider: YouTubeMusicProvider,
    onRetry: () -> Unit, onMorphDrag: (Float, Long) -> Unit,
    onNotice: (String) -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit,
    onMorphRelease: (Float) -> Unit) {
    val p = progress().coerceIn(0f, 1f)
    val panelTransport=remember(progress) {{motionRamp(progress(),.68f,1f)}}
    // Flying copies and the real elements swap over a short window once they coincide,
    // so the two are never visible side by side.
    val panelHandoff=remember(progress) {{motionRamp(progress(),morphHandoffStart,1f)}}
    val solidControls = LocalAccessibilitySettings.current.solidControls
    val measure = LocalDensity.current
    val fullElementBounds = remember { mutableStateMapOf<String, Rect>() }
    val panelCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("player_morph")) {
        val fullWidth = maxWidth
        val fullHeight = maxHeight
        val miniX = miniBounds?.let { with(measure) { it.left.toDp() } } ?: 16.dp
        val miniY = miniBounds?.let { with(measure) { it.top.toDp() } } ?:
            maxHeight - (bottomInset + 10f).dp -
                (if (compact) 64.dp else 58.dp) - 8.dp - 62.dp
        val miniWidth = miniBounds?.let { with(measure) { it.width.toDp() } } ?: maxWidth - 32.dp
        val miniHeight = miniBounds?.let { with(measure) { it.height.toDp() } } ?: 62.dp
        val fullY = 0.dp
        val x = miniX * (1f - p)
        val y = miniY + (fullY - miniY) * p
        val w = miniWidth + (maxWidth - miniWidth) * p
        val h = miniHeight + (maxHeight - fullY - miniHeight) * p
        val handoff = motionRamp(p, morphHandoffStart, 1f)
        val movingAlpha = motionRamp(p, 0f, .02f) * (1f - handoff)
        val miniOrigin = miniBounds ?: Rect(0f, 0f, 0f, 0f)
        fun miniPosition(name: String, fallbackX: androidx.compose.ui.unit.Dp,
            fallbackY: androidx.compose.ui.unit.Dp): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
            val bounds = miniElementBounds[name] ?: return fallbackX to fallbackY
            return with(measure) { (bounds.left - miniOrigin.left).toDp() to
                (bounds.top - miniOrigin.top).toDp() }
        }
        fun fullPosition(name: String, inset: androidx.compose.ui.unit.Dp,
            fallbackX: androidx.compose.ui.unit.Dp,
            fallbackY: androidx.compose.ui.unit.Dp): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
            val bounds = fullElementBounds[name] ?: return fallbackX to fallbackY
            return with(measure) { bounds.left.toDp() + inset to bounds.top.toDp() + inset }
        }
        // Measured against the panel's own coordinates in the same layout pass, so the
        // landing spots never lag the animation the way root bounds minus progress() did.
        val reportFullBounds=remember(panelCoordinates) {
            { name:String,coordinates:LayoutCoordinates ->
                            val panel=panelCoordinates[0]
                            if(panel!=null && panel.isAttached && coordinates.isAttached) {
                            val local = panel.localBoundingBoxOf(coordinates, clipBounds = false)
                            val previous = fullElementBounds[name]
                            if (previous == null ||
                                kotlin.math.abs(previous.left - local.left) > 1f ||
                                kotlin.math.abs(previous.top - local.top) > 1f ||
                                kotlin.math.abs(previous.width - local.width) > 1f ||
                                kotlin.math.abs(previous.height - local.height) > 1f)
                                fullElementBounds[name] = local
                            }
            }
        }
        val shape = RoundedCornerShape(32.dp * (1f - p))
        Box(Modifier.offset(x = x, y = y).width(w).height(h)
            .clip(shape)
            .background(if (solidControls) Color(0xFF11131A) else Color.Black.copy(alpha = p))
            .border(1.dp, White.copy(alpha = (.42f * (1f - p) + .10f * p) *
                (p * 12f).coerceAtMost(1f)*(1f-p)), shape)) {
            if (p > .001f) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredWidth(fullWidth).requiredHeight(fullHeight - fullY)
                    .onPlaced { panelCoordinates[0] = it }
                    .then(if (p < .999f) Modifier.clearAndSetSemantics { } else Modifier)
                    .graphicsLayer {
                    alpha = motionRamp(p, .08f, .63f)
                }) {
                    NowPlayingPanel(playback, collections, provider,
                        transportAlpha = panelTransport,
                        artworkAlpha = panelHandoff,
                        sharedContentAlpha = panelHandoff,
                        dragging = fullDragging,
                        onMorphElementBounds = reportFullBounds,
                        interactive = p >= .999f,
                        onClose = { model.sheet = "" },
                        onSwipeDrag = onMorphDrag,
                        onSwipeRelease = onMorphRelease,
                        onQueue = { model.sheet = if(model.sheet=="queue") "mini" else "queue" },
                        queueOpen=model.sheet=="queue",onQueueClose={model.sheet="mini"},
                        onLibrary = { model.sheet = "libraryShortcuts" },
                        onLyrics = { model.sheet = "lyrics" },onArtist=onArtist,onAlbum=onAlbum,
                        onRetry = onRetry,
                        onNotice = onNotice)
                    if (p < .999f) {
                        Box(Modifier.fillMaxSize()
                            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null) { }
                            .pointerInput(onMorphDrag, onMorphRelease) {
                                var lastTime = 0L
                                var velocity = 0f
                                detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                                    if (lastTime > 0L) velocity = -amount * 1000f /
                                        (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                                    lastTime = change.uptimeMillis
                                    onMorphDrag(amount, change.uptimeMillis)
                                    change.consume()
                                }, onDragEnd = {
                                    onMorphRelease(velocity)
                                    lastTime = 0L
                                    velocity = 0f
                                }, onDragCancel = {
                                    onMorphRelease(0f)
                                    lastTime = 0L
                                    velocity = 0f
                                })
                            })
                    }
                }
            }
            if (p > 0f && p < 1f) {
                val artX = 12.dp * (1f - p)
                val artY = 11.dp * (1f - p)
                val heroHeight = fullHeight / 2f
                val artSize = 40.dp + (fullWidth - 40.dp) * p
                val artHeight = 40.dp + (heroHeight - 40.dp) * p
                val state = playback.state
                Box(Modifier.offset(x = artX, y = artY).width(artSize).height(artHeight)
                    .clip(RoundedCornerShape(10.dp * (1f - p)))
                    .graphicsLayer {
                        alpha = 1f - handoff
                    }) {
                    PlayerArtwork(state.artworkUrl ?: providerArtworkUrl(state.mediaId.removePrefix("yt:")),
                        Modifier.fillMaxSize(), blend = p)
                }

                val (miniTitleX, miniTitleY) = miniPosition("title", 64.dp, 10.dp)
                val (fullTitleX, fullTitleY) = fullPosition("title", 0.dp,
                    20.dp, heroHeight + 8.dp)
                val (miniArtistX, miniArtistY) = miniPosition("artist", 64.dp, 31.dp)
                val (fullArtistX, fullArtistY) = fullPosition("artist", 0.dp,
                    20.dp, heroHeight + 38.dp)
                val titleBaseY = miniTitleY + (fullTitleY - miniTitleY) * p
                val artistBaseY = miniArtistY + (fullArtistY - miniArtistY) * p
                val artworkClearance = (artHeight + 6.dp - titleBaseY).coerceAtLeast(0.dp) *
                    ((p - .05f) / .15f).coerceIn(0f, 1f)
                val fullTextWidth = { name: String -> fullElementBounds[name]?.let { with(measure) { it.width.toDp() } }
                    ?: (fullWidth - 120.dp) }
                val titleWidth = (miniWidth - 168.dp).coerceAtLeast(48.dp) * (1f - p) +
                    (fullWidth - 120.dp).coerceAtLeast(48.dp) * p
                Text(state.title, color = White,
                    fontSize = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = miniTitleX + (fullTitleX - miniTitleX) * p,
                        y = titleBaseY + artworkClearance)
                        .width(fullTextWidth("title")).graphicsLayer {
                            alpha = movingAlpha
                            val start=if(measure.fontScale>=1.6f) 12f/22f else 14f/22f
                            scaleX=start+(1f-start)*p;scaleY=scaleX
                            transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
                        })
                Text(playerArtistLabel(state.artist), color = Muted,
                    fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = miniArtistX + (fullArtistX - miniArtistX) * p,
                        y = artistBaseY + artworkClearance)
                        .width(fullTextWidth("artist")).graphicsLayer {
                            scaleX=11f/13f+(2f/13f)*p;scaleY=scaleX
                            transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
                            alpha = if (measure.fontScale >= 1.8f && !miniElementBounds.containsKey("artist"))
                                movingAlpha * p else movingAlpha
                        })
                val controlHeight = fullHeight - (bottomInset + 10f).dp - heroHeight
                val groupGap = ((controlHeight - 284.dp) / 3f).coerceAtLeast(0.dp)
                val targetCenterY = heroHeight + 148.dp + groupGap * 2f
                val miniIconY = (miniHeight - 24.dp) / 2f
                val (miniPlayX, miniPlayY) = miniPosition("play", miniWidth - 84.dp, miniIconY)
                val (miniNextX, miniNextY) = miniPosition("next", miniWidth - 36.dp, miniIconY)
                val (fullPlayX, fullPlayY) = fullPosition("play", 6.dp,
                    fullWidth / 2 - 26.dp, targetCenterY - 26.dp)
                val (fullNextX, fullNextY) = fullPosition("next", 8.dp,
                    fullWidth * .70f - 19.dp, targetCenterY - 19.dp)
                val playIcon = if (playback.state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                Icon(playIcon, contentDescription = null, tint = White,
                    modifier = Modifier.offset(
                        x = miniPlayX + (fullPlayX - miniPlayX) * p,
                        y = miniPlayY + (fullPlayY - miniPlayY) * p)
                        .size(24.dp + 28.dp * p)
                        .graphicsLayer { alpha = movingAlpha })
                Icon(Icons.Rounded.FastForward, contentDescription = null, tint = White,
                    modifier = Modifier.offset(
                        x = miniNextX + (fullNextX - miniNextX) * p,
                        y = miniNextY + (fullNextY - miniNextY) * p)
                        .size(24.dp + 14.dp * p)
                        .graphicsLayer {
                            alpha = movingAlpha * if (state.queue.size > 1) 1f else .36f
                        })
            }
        }
    }
}

@Composable
internal fun LiquidTabBar(model: ShellModel, compact: Boolean, height: androidx.compose.ui.unit.Dp) {
    val names = listOf("Home", "Podcasts", "Radio", "Library", "Search")
    val accessibility = LocalAccessibilitySettings.current
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val largeText = LocalDensity.current.fontScale >= 1.6f
    var fingerX by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).pointerInput(compact, rightToLeft) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fingerX = down.position.x
            var releasedX = down.position.x
            var lastHover=TabHitTest.indexAt(releasedX,size.width.toFloat(),height.value*density,8f*density,rightToLeft)
            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                releasedX = change.position.x
                fingerX = releasedX
                val nextHover=TabHitTest.indexAt(releasedX,size.width.toFloat(),height.value*density,8f*density,rightToLeft)
                if(change.pressed && nextHover!=lastHover) {uiHaptic(HapticCue.Selection);lastHover=nextHover}
                if (!change.pressed) break
            } while (true)
            val width = size.width.toFloat()
            val searchWidth = height.value * density
            val gap = 8f * density
            val index = TabHitTest.indexAt(releasedX, width, searchWidth, gap,
                rightToLeft)
            val reset=model.selectFromTap(names[index], android.os.SystemClock.uptimeMillis())
            uiHaptic(if(reset) HapticCue.Confirm else HapticCue.Selection)
            fingerX = null
        }
    }) {
        val navTotalWidth=maxWidth
        val mainWidth = maxWidth - height - 8.dp
        val slot = mainWidth / 4
        val hovered = fingerX?.let { px ->
            TabHitTest.indexAt(px, maxWidth.value * density, height.value * density,
                8f * density, rightToLeft)
        }
        val active = hovered ?: names.indexOf(model.tab).coerceAtLeast(0)
        val motion = LocalMotionPolicy.current
        val targetWidth=if(active==4) height.value else slot.value
        val logicalFinger=fingerX?.let {if(rightToLeft) maxWidth.value-it/density else it/density}
        val targetCenter=logicalFinger?.coerceIn(targetWidth/2f,maxWidth.value-targetWidth/2f)
            ?:if(active==4) mainWidth.value+8f+height.value/2f else slot.value*(active+.5f)
        val center=animateFloatAsState(targetCenter,if(motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio=.82f,stiffness=if(fingerX!=null) 1200f else 600f),label="liquid pill position")
        val width=animateFloatAsState(targetWidth,GlassMotion.settle(motion),label="liquid pill width")
        val pressState=animateFloatAsState(if(fingerX!=null) 1f else 0f,GlassMotion.settle(motion),label="liquid contact")
        fun geometry():NavLens {
            val cx=if(rightToLeft) maxWidth.value-center.value else center.value
            return navigationLens(cx*density,width.value*density,height.value*density,pressState.value,
                (targetCenter-center.value)*density,accessibility.glassIntensity,motion.reduced || accessibility.solidControls)
        }
        val refraction=rememberNavigationRefraction()
        Row(Modifier.fillMaxSize().graphicsLayer {
            val lens=geometry()
            renderEffect=if(lens.gain>0f && refraction!=null) refraction(lens,size.width,size.height) else null
        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(height / 2),
                color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                border = BorderStroke(.65.dp, Color.White.copy(alpha =
                    if (accessibility.solidControls) .72f else .4f)),
                modifier = Modifier.width(mainWidth).height(height).testTag("navigation")) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    names.take(4).forEach { name ->
                        NavItem(name, names[active] == name, !largeText,
                            Modifier.weight(1f).graphicsLayer {
                                if(android.os.Build.VERSION.SDK_INT<33) {val lens=geometry();val itemCenter=slot.value*density*(names.indexOf(name)+.5f)
                                    val physical=if(rightToLeft) navTotalWidth.value*density-itemCenter else itemCenter
                                    val contact=(1f-kotlin.math.abs(physical-lens.centerX)/lens.radiusX.coerceAtLeast(1f)).coerceIn(0f,1f)
                                    scaleX=1f+lens.gain*contact;scaleY=1f+lens.gain*contact}
                            }) { uiHaptic(HapticCue.Selection); model.selectFromTap(name,android.os.SystemClock.uptimeMillis()) }
                    }
                }
            }
            run {
                Surface(shape = CircleShape,
                    color = if (accessibility.solidControls) Color(0xFF11131A) else Color.Transparent,
                    border = BorderStroke(.65.dp, Color.White.copy(alpha =
                        if (accessibility.solidControls) .72f else .4f)),
                    modifier = Modifier.size(height).testTag("nav_search")) {
                    NavItem("Search", active == 4, false, Modifier.fillMaxSize().graphicsLayer {
                        if(android.os.Build.VERSION.SDK_INT<33) {val lens=geometry();val logical=mainWidth.value+8f+height.value/2f;val physical=(if(rightToLeft) navTotalWidth.value-logical else logical)*density
                            val contact=(1f-kotlin.math.abs(physical-lens.centerX)/lens.radiusX.coerceAtLeast(1f)).coerceIn(0f,1f);scaleX=1f+lens.gain*contact;scaleY=1f+lens.gain*contact}
                    }) {
                        uiHaptic(HapticCue.Selection); model.selectFromTap("Search",android.os.SystemClock.uptimeMillis())
                    }
                }
            }
        }
        LiquidSelector(::geometry,density,accessibility.solidControls,fingerX!=null)

    }
}

@Composable private fun LiquidSelector(geometry:()->NavLens,density:Float,solid:Boolean,pressed:Boolean) {
    Box(Modifier.fillMaxSize().drawWithCache {
        onDrawBehind {
            val lens=geometry()
            val left=lens.centerX-lens.radiusX;val top=lens.centerY-lens.radiusY
            val dimensions=androidx.compose.ui.geometry.Size(lens.radiusX*2,lens.radiusY*2)
            val origin=androidx.compose.ui.geometry.Offset(left,top)
            val radius=androidx.compose.ui.geometry.CornerRadius(lens.radiusY)
            drawRoundRect(Brush.verticalGradient(if(solid) listOf(Pink.copy(alpha=.10f),Pink.copy(alpha=.10f))
                else if(pressed) listOf(White.copy(alpha=.11f),Pink.copy(alpha=.04f),Color.Transparent)
                else listOf(Color.Transparent,Color.Transparent),startY=top,endY=top+dimensions.height),origin,dimensions,radius)
            drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha=.82f),Pink.copy(alpha=.56f),White.copy(alpha=.25f)),start=origin,end=origin+androidx.compose.ui.geometry.Offset(dimensions.width,dimensions.height)),origin,dimensions,radius,style=androidx.compose.ui.graphics.drawscope.Stroke(density))
        }
    })
}

@Composable
private fun NavItem(name: String, selected: Boolean, showLabel: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val icon = when (name) {
        "Home" -> Icons.Rounded.Home
        "Podcasts" -> Icons.Rounded.Podcasts
        "Radio" -> Icons.Rounded.Radio
        "Library" -> Icons.Rounded.LibraryMusic
        else -> Icons.Rounded.Search
    }
    Column(modifier.fillMaxSize().onKeyEvent {event ->
        if(event.type==KeyEventType.KeyUp && event.key in listOf(Key.Enter,Key.NumPadEnter,Key.Spacebar)) {onClick();true} else false
    }.focusable().testTag("tab_${name.lowercase()}")
        .semantics {
            contentDescription = "$name tab${if (selected) ", selected" else ""}"
            this.selected = selected
            role = Role.Tab
            onClick { onClick(); true }
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, contentDescription = null, tint = if (selected) Pink else White,
            modifier = Modifier.size(23.dp))
        if (showLabel) Text(name, color = if (selected) Pink else if(LocalAccessibilitySettings.current.solidControls) White else Muted, fontSize = 10.sp, maxLines = 1)
    }
}

/** Snapshot geometry reads stay inside layer updates; scrolling does not recompose the shell. */
@Composable private fun GlassScene(bounds:Map<String,Rect>,area:IntSize,density:Float,compact:Boolean,
    showMini:Boolean,chrome:Boolean,inset:Int,tab:String,route:String,settings:AccessibilitySettings,content:@Composable ()->Unit) {
    val solid=settings.solidControls;val frosted=settings.frostedGlass;val intensity=settings.glassIntensity
    // The cache itself is not snapshot state; mutating it inside a layer never schedules composition.
    val cache=remember {GlassEffectCache()}
    Box(Modifier.fillMaxSize().imePadding().testTag("scene").graphicsLayer {
        val categories=visibleGlassChips(bounds,tab,route,area.width,area.height).map {it.second}
        val key=listOf(area,density,compact,showMini,chrome,inset,solid,frosted,intensity,categories)
        if(cache.key!=key) {
            cache.key=key
            cache.effect=if(chrome && !solid) chromeGlassEffect(area.width,area.height,density,showMini,compact,
                false,inset,categories,frosted,intensity)?.asComposeRenderEffect() else null
        }
        renderEffect=cache.effect
    },content={content()})
}
private class GlassEffectCache {
    var key:List<Any?>?=null
    var effect:androidx.compose.ui.graphics.RenderEffect?=null
}
@Composable private fun VisibleGlassLabels(bounds:Map<String,Rect>,styles:Map<String,GlassChipStyle>,tab:String,route:String,area:IntSize) {
    GlassCategoryLabels(visibleGlassChips(bounds,tab,route,area.width,area.height),styles)
}
