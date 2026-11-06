@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.musicglass.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import androidx.compose.foundation.window.WindowDraggableArea
import java.awt.Dimension
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--desktop-build-identity"))) {
        val version = DesktopBuildIdentity.version()
        println("RIFF_DESKTOP_IDENTITY ${version ?: "CONFIGURATION_ERROR"} UPDATES_DISABLED CONFIGURATION_ERROR")
        kotlin.system.exitProcess(if (version == null) 2 else 0)
    }
    if (args.firstOrNull() == "--verify-update-fixture") { UpdateVerificationProbe.run(args); return }
    if (args.firstOrNull() == "--phase1-acceptance") {
        try { PhaseOneAcceptance.run(args) }
        catch(t:Throwable) {
            val root=java.io.File(args.getOrElse(1){"."})
            if(root.path.contains("phase1",ignoreCase=true)) { root.mkdirs();root.resolve("acceptance-failure.txt").writeText(t.stackTraceToString()) }
            kotlin.system.exitProcess(1)
        }
        return
    }
    val dataArgument=args.indexOf("--data-dir")
    if(dataArgument>=0)System.setProperty("riff.data.dir",java.io.File(args.getOrElse(dataArgument+1){error("Missing data directory")}).canonicalPath)
    try {
        application {
            val host = remember { DesktopHost() }
            val hostSnapshot by host.state.collectAsState()
            LaunchedEffect(host){val link=args.firstOrNull{parseYouTubeLink(it)!=null};if(link!=null){host.state.first{it.initialized};host.dispatch(DesktopCommand.OpenYouTubeLink(link))}}
            LaunchedEffect(hostSnapshot.running) { if(!hostSnapshot.running) exitApplication() }
            val windowStateManager = remember { DesktopWindowStateManager() }
            val restoredState = remember { windowStateManager.loadState() }

            var savedMainWindowSize by remember {
                mutableStateOf(DpSize(restoredState.mainWidth.dp, restoredState.mainHeight.dp))
            }

            val initialPosition = remember {
                if (restoredState.mainX != null && restoredState.mainY != null) {
                    WindowPosition(restoredState.mainX.dp, restoredState.mainY.dp)
                } else WindowPosition.PlatformDefault
            }

            var savedMainWindowPosition by remember { mutableStateOf(initialPosition) }
            var savedMainWindowPlacement by remember { mutableStateOf(if(restoredState.isMaximized)WindowPlacement.Maximized else WindowPlacement.Floating) }
            var appliedWindowMode by remember { mutableStateOf<WindowMode?>(null) }

            val windowState = rememberWindowState(
                placement = if (restoredState.isMaximized) WindowPlacement.Maximized else WindowPlacement.Floating,
                position = initialPosition,
                size = DpSize(restoredState.mainWidth.dp, restoredState.mainHeight.dp)
            )

            val saveWindowState: () -> Unit = {
                val size = windowState.size
                val pos = if(hostSnapshot.windowMode==WindowMode.Main)windowState.position else savedMainWindowPosition
                val x = if (pos is WindowPosition.Absolute) pos.x.value else null
                val y = if (pos is WindowPosition.Absolute) pos.y.value else null
                val isMax = (if(hostSnapshot.windowMode==WindowMode.Main)windowState.placement else savedMainWindowPlacement) == WindowPlacement.Maximized
                windowStateManager.saveState(
                    WindowGeometryRecord(
                        mainWidth = if (hostSnapshot.windowMode == WindowMode.Main) size.width.value else savedMainWindowSize.width.value,
                        mainHeight = if (hostSnapshot.windowMode == WindowMode.Main) size.height.value else savedMainWindowSize.height.value,
                        mainX = x,
                        mainY = y,
                        isMaximized = isMax,
                        isAlwaysOnTop = hostSnapshot.isAlwaysOnTop,
                        lastMode = hostSnapshot.windowMode,
                        sidebarWidthDp = hostSnapshot.sidebarWidthDp,
                        inspectorWidthDp = hostSnapshot.inspectorWidthDp
                    )
                )
            }

            val quit: () -> Unit = {
                saveWindowState()
                host.dispatch(DesktopCommand.Quit)
            }

            LaunchedEffect(Unit) {
                host.dispatch(DesktopCommand.SetSidebarWidth(restoredState.sidebarWidthDp))
                host.dispatch(DesktopCommand.SetInspectorWidth(restoredState.inspectorWidthDp))
                host.dispatch(DesktopCommand.SetAlwaysOnTop(restoredState.isAlwaysOnTop))
                if (restoredState.lastMode != WindowMode.Main) {
                    host.dispatch(DesktopCommand.SetWindowMode(restoredState.lastMode))
                }
            }

            // Desktop OS Integrations (SMTC, Tray, Power Management, Media Keys, Discord Presence)
            val osCoordinator = remember {
                DesktopOSCoordinator(
                    onRestoreRequested = {
                        windowState.isMinimized = false
                        host.dispatch(DesktopCommand.ShowMain)
                    },
                    onTogglePlayPauseRequested = { host.dispatch(DesktopCommand.TogglePlayPause) },
                    onNextRequested = { host.dispatch(DesktopCommand.NextTrack) },
                    onPreviousRequested = { host.dispatch(DesktopCommand.PreviousTrack) },
                    onQuitRequested = { quit() }
                )
            }

            DisposableEffect(Unit) {
                onDispose {
                    osCoordinator.close()
                }
            }

            LaunchedEffect(hostSnapshot.isPlaying) {
                osCoordinator.setPlaybackState(hostSnapshot.isPlaying)
            }

            LaunchedEffect(hostSnapshot.currentTrack) {
                osCoordinator.onTrackChanged(
                    title = hostSnapshot.currentTrack.title,
                    artist = hostSnapshot.currentTrack.artist,
                    durationMs = hostSnapshot.currentTrack.durationMs
                )
            }

            LaunchedEffect(hostSnapshot.playbackPositionMs, hostSnapshot.currentTrack.durationMs) {
                osCoordinator.updateTimeline(
                    hostSnapshot.playbackPositionMs,
                    hostSnapshot.currentTrack.durationMs
                )
            }

            val windowKeyHandler=remember{java.util.concurrent.atomic.AtomicReference<(KeyEvent)->Boolean>({false})}
            Window(
                onCloseRequest = quit,
                onKeyEvent = {windowKeyHandler.get().invoke(it)},
                state = windowState,
                title = "Riff Desktop Preview",
                alwaysOnTop = hostSnapshot.isAlwaysOnTop,
                undecorated = true
            ) {
                LaunchedEffect(hostSnapshot.windowMode) {
                    val mode=hostSnapshot.windowMode
                    if(appliedWindowMode==WindowMode.Main && mode!=WindowMode.Main){
                        savedMainWindowSize=windowState.size
                        savedMainWindowPosition=windowState.position
                        savedMainWindowPlacement=windowState.placement
                    }
                    // One ordered effect: clear AWT's old minimum before requesting the smaller frame.
                    window.minimumSize=when(mode){
                        WindowMode.Main->Dimension(800,600)
                        WindowMode.MiniCompact->Dimension(360,160)
                        WindowMode.MiniExpanded->Dimension(360,460)
                    }
                    // Increasing the AWT minimum posts a resize to that minimum. Let Compose
                    // consume it before restoring the saved main size; otherwise its delayed
                    // component event overwrites our requested geometry (observed 1280x800 -> 800x600).
                    if(mode==WindowMode.Main && appliedWindowMode!=null)withFrameNanos { }
                    if(mode==WindowMode.Main){
                        windowState.size=savedMainWindowSize
                        windowState.position=savedMainWindowPosition
                        windowState.placement=savedMainWindowPlacement
                    }else{
                        windowState.placement=WindowPlacement.Floating
                        withFrameNanos { }
                        windowState.size=if(mode==WindowMode.MiniCompact)DpSize(360.dp,160.dp)else DpSize(360.dp,460.dp)
                    }
                    appliedWindowMode=mode
                }

                // Native Win32 custom chrome integration
                val captionBounds = remember { java.util.concurrent.atomic.AtomicReference(java.awt.Rectangle()) }
                val maximizeBounds = remember { java.util.concurrent.atomic.AtomicReference(java.awt.Rectangle()) }
                var nativeChrome by remember { mutableStateOf<WindowsChrome?>(null) }

                DisposableEffect(window) {
                    var disposed = false
                    var chrome: WindowsChrome? = null
                    javax.swing.SwingUtilities.invokeLater {
                        if (!disposed && window.isDisplayable && System.getProperty("os.name").startsWith("Windows")) {
                            chrome = WindowsChrome(window).also {
                                it.maximizeBounds = maximizeBounds.get()
                                it.captionBounds = captionBounds.get()
                            }
                            nativeChrome = chrome
                        }
                    }
                    onDispose { disposed = true; chrome?.close(); nativeChrome = null }
                }

                // Automated Smoke Test Diagnostic
                LaunchedEffect(Unit) {
                    if ("--smoke-test" in args) {
                        val chrome = if (System.getProperty("os.name").startsWith("Windows")) {
                            kotlinx.coroutines.withTimeout(10_000) { snapshotFlow { nativeChrome }.filterNotNull().first() }
                        } else null
                        withFrameNanos { }
                        if (chrome != null) {
                            check(chrome.verifyInstalled()) { "Native frame hook/style did not survive AWT initialization" }
                        }
                        println("RIFF_FIRST_COMPOSE_FRAME wallTimeMs=${System.currentTimeMillis()} uiScale=${window.graphicsConfiguration.defaultTransform.scaleX} heapBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
                        kotlinx.coroutines.delay(1000)
                        quit()
                    }
                }

                LaunchedEffect(nativeChrome) { withFrameNanos { }; nativeChrome?.refreshChildren() }

                // Playback clock is authoritatively driven by DesktopAudioBridge and DesktopDualDeckMixer,
                // completely decoupled from the Compose UI frame loop (Section 4.1).

                val font = remember { interFamily(14f) }
                val searchFocusRequester = remember { FocusRequester() }
                var searchFocusGeneration by remember { mutableIntStateOf(0) }
                SideEffect{windowKeyHandler.set({ keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when {
                                        keyEvent.key == Key.Spacebar -> {
                                            if (hostSnapshot.currentRoute != WorkspaceRoute.Search) {
                                                host.dispatch(DesktopCommand.TogglePlayPause)
                                                true
                                            } else false
                                        }
                                        keyEvent.isCtrlPressed && keyEvent.key == Key.O -> {
                                            java.awt.EventQueue.invokeLater {
                                                val picker=java.awt.FileDialog(window,"Import audio",java.awt.FileDialog.LOAD)
                                                try {
                                                    picker.isMultipleMode=true;picker.isVisible=true
                                                    picker.files.forEach{host.dispatch(DesktopCommand.AddFileToQueue(it))}
                                                } finally {picker.dispose()}
                                            }
                                            true
                                        }
                                        keyEvent.isCtrlPressed && keyEvent.key == Key.K -> {
                                            host.dispatch(DesktopCommand.SetRoute(WorkspaceRoute.Search))
                                            searchFocusGeneration++
                                            true
                                        }
                                        keyEvent.isCtrlPressed && keyEvent.key == Key.M -> {
                                            val nextMode = if (hostSnapshot.windowMode == WindowMode.Main) {
                                                WindowMode.MiniCompact
                                            } else {
                                                WindowMode.Main
                                            }
                                            host.dispatch(DesktopCommand.SetWindowMode(nextMode))
                                            true
                                        }
                                        keyEvent.isCtrlPressed && keyEvent.key == Key.Q -> {
                                            quit()
                                            true
                                        }
                                        keyEvent.key == Key.Escape -> {
                                            if (hostSnapshot.notificationMessage != null) {
                                                host.dispatch(DesktopCommand.DismissNotification())
                                                true
                                            } else if (hostSnapshot.inspectorTab != InspectorTab.None) {
                                                host.dispatch(DesktopCommand.ToggleInspector(hostSnapshot.inspectorTab))
                                                true
                                            } else if (hostSnapshot.currentRoute in listOf(
                                                    WorkspaceRoute.AlbumDetail,
                                                    WorkspaceRoute.ArtistDetail,
                                                    WorkspaceRoute.PlaylistDetail,
                                                    WorkspaceRoute.FocusedPlayer
                                                )) {
                                                host.dispatch(DesktopCommand.NavigateBack)
                                                true
                                            } else false
                                        }
                                        else -> false
                                    }
                                } else false
                            })}


                DisposableEffect(window) {
                    val dropTarget = object : java.awt.dnd.DropTarget() {
                        @Synchronized
                        override fun drop(evt: java.awt.dnd.DropTargetDropEvent) {
                            try {
                                evt.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                                val transferable = evt.transferable
                                if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                                    @Suppress("UNCHECKED_CAST")
                                    val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as? List<java.io.File>
                                    files?.forEach { file ->
                                        host.dispatch(DesktopCommand.AddFileToQueue(file))
                                    }
                                }
                                evt.dropComplete(true)
                            } catch (_: Exception) {
                                evt.dropComplete(false)
                            }
                        }
                    }
                    window.dropTarget = dropTarget
                    onDispose {
                        window.dropTarget = null
                    }
                }

                val constraints = remember(windowState.size.width, windowState.size.height, hostSnapshot.inspectorTab) {
                    WorkspaceLayoutCalculator.calculate(
                        windowWidth = windowState.size.width,
                        windowHeight = windowState.size.height,
                        desiredInspectorTab = hostSnapshot.inspectorTab,
                        userSidebarWidth = hostSnapshot.sidebarWidthDp.dp,
                        userInspectorWidth = hostSnapshot.inspectorWidthDp.dp
                    )
                }

                val globalBackdrop = rememberRiffBackdrop()

                CompositionLocalProvider(
                    LocalDesktopHost provides host,
                    LocalDesktopWindow provides window,
                    LocalArtworkRepository provides host.artworkRepository,
                    LocalDesktopCatalog provides DesktopCatalog(hostSnapshot.libraryTracks,hostSnapshot.searchResults,hostSnapshot.libraryPlaylists,hostSnapshot.collectionTracks,hostSnapshot.collectionKind,hostSnapshot.collectionId),
                    LocalRiffBackdrop provides globalBackdrop,
                    LocalPageBackdrop provides globalBackdrop,
                    LocalMotionPolicy provides MotionPolicy(hostSnapshot.reducedMotion || windowState.isMinimized, 1f),
                    LocalGlassStyle provides GlassStyle(
                        intensity = 1.25f,
                        frosted = true,
                        frostedIntensity = 0.25f,
                        quality = hostSnapshot.glassQuality
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(RiffDesign.ink)

                    ) {
                        // Custom Desktop Titlebar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(constraints.titlebarHeight)
                                .background(RiffDesign.raised),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WindowDraggableArea(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .onGloballyPositioned { coordinates ->
                                        val b = coordinates.boundsInWindow()
                                        val rect = java.awt.Rectangle(b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
                                        captionBounds.set(rect)
                                        nativeChrome?.captionBounds = rect
                                    }
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(start = RiffDesign.edge),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    val titleLabel = if (hostSnapshot.windowMode == WindowMode.Main) {
                                        "RIFF  /  ${hostSnapshot.currentRoute.label.uppercase()}"
                                    } else {
                                        "RIFF  /  MINI-PLAYER"
                                    }
                                    Label(titleLabel, font, RiffDesign.muted, size = 13)
                                }
                            }

                            if (hostSnapshot.windowMode != WindowMode.Main) {
                                Action("Restore Main", font) {
                                    host.dispatch(DesktopCommand.SetWindowMode(WindowMode.Main))
                                }
                            }

                            Action("Minimize", font) { windowState.isMinimized = true }

                            if (hostSnapshot.windowMode == WindowMode.Main) {
                                Action(
                                    text = if (windowState.placement == WindowPlacement.Maximized) "Restore" else "Maximize",
                                    font = font,
                                    modifier = Modifier.onGloballyPositioned { coordinates ->
                                        val b = coordinates.boundsInWindow()
                                        val rect = java.awt.Rectangle(b.left.toInt(), b.top.toInt(), b.width.toInt(), b.height.toInt())
                                        maximizeBounds.set(rect)
                                        nativeChrome?.maximizeBounds = rect
                                    }
                                ) {
                                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized) {
                                        WindowPlacement.Floating
                                    } else {
                                        WindowPlacement.Maximized
                                    }
                                }
                            }

                            Action("Close", font, action = quit)
                        }

                        // Main Workspace View vs Mini-Player Mode
                        if (hostSnapshot.windowMode == WindowMode.Main) {
                            // Workspace Middle Area (Sidebar + Divider + Stage + Divider + Inspector)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                // Left Navigation Rail / Sidebar
                                NavigationSidebar(
                                    currentRoute = hostSnapshot.currentRoute,
                                    isCompactRail = constraints.isCompactRail,
                                    font = font,
                                    onNavigate = { route -> host.dispatch(DesktopCommand.SetRoute(route)) },
                                    modifier = Modifier.width(constraints.sidebarWidth)
                                )

                                // Draggable Sidebar Divider (visible when not in compact rail mode)
                                if (!constraints.isCompactRail) {
                                    WorkspaceDivider(
                                        label = "Sidebar",
                                        currentWidth = constraints.sidebarWidth,
                                        minWidth = WorkspaceLayoutCalculator.SIDEBAR_MIN_WIDTH.dp,
                                        maxWidth = WorkspaceLayoutCalculator.SIDEBAR_MAX_WIDTH.dp,
                                        defaultWidth = WorkspaceLayoutCalculator.SIDEBAR_DEFAULT_WIDTH.dp,
                                        isLeftAnchor = true,
                                        onWidthChange = { host.dispatch(DesktopCommand.SetSidebarWidth(it.value)) }
                                    )
                                }

                                // Center Main Stage
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    val stageModifier = if (constraints.mainContentMaxWidth != androidx.compose.ui.unit.Dp.Unspecified) {
                                        Modifier.widthIn(max = constraints.mainContentMaxWidth)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }

                                    Box(modifier = stageModifier.fillMaxHeight()) {
                                        when (hostSnapshot.currentRoute) {
                                            WorkspaceRoute.Home -> HomeStage(
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onOpenAlbum = { host.dispatch(DesktopCommand.OpenAlbumDetail(it)) },
                                                onOpenArtist = { host.dispatch(DesktopCommand.OpenArtistDetail(it)) }
                                            )
                                            WorkspaceRoute.Search -> SearchStage(
                                                focusRequestToken = searchFocusGeneration,
                                                query = hostSnapshot.searchQuery,
                                                searchFocusRequester = searchFocusRequester,
                                                font = font,
                                                onQueryChange = { host.dispatch(DesktopCommand.SetSearchQuery(it)) },
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onOpenAlbum = { host.dispatch(DesktopCommand.OpenAlbumDetail(it)) }
                                            )
                                            WorkspaceRoute.Discover -> CatalogBrowserStage(font)
                                            WorkspaceRoute.Library -> LibraryStage(
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onOpenAlbum = { host.dispatch(DesktopCommand.OpenAlbumDetail(it)) },
                                                onOpenArtist = { host.dispatch(DesktopCommand.OpenArtistDetail(it)) },
                                                onOpenPlaylist = { host.dispatch(DesktopCommand.OpenPlaylistDetail(it)) }
                                            )
                                            WorkspaceRoute.Podcasts -> PodcastsStage(
                                                font = font
                                            )
                                            WorkspaceRoute.Radio -> RadioStage(
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) }
                                            )
                                            WorkspaceRoute.AlbumDetail -> AlbumDetailStage(
                                                albumId = hostSnapshot.activeAlbumId ?: "",
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onPlayCollection = { list, idx -> host.dispatch(DesktopCommand.PlayCollection(list, idx)) },
                                                onBack = { host.dispatch(DesktopCommand.NavigateBack) }
                                            )
                                            WorkspaceRoute.ArtistDetail -> ArtistDetailStage(
                                                artistId = hostSnapshot.activeArtistId ?: "",
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onBack = { host.dispatch(DesktopCommand.NavigateBack) }
                                            )
                                            WorkspaceRoute.PlaylistDetail -> PlaylistDetailStage(
                                                playlistId = hostSnapshot.activePlaylistId ?: "",
                                                font = font,
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onPlayCollection = { list, idx -> host.dispatch(DesktopCommand.PlayCollection(list, idx)) },
                                                onBack = { host.dispatch(DesktopCommand.NavigateBack) }
                                            )
                                            WorkspaceRoute.FocusedPlayer -> FocusedPlayerStage(
                                                currentTrack = hostSnapshot.currentTrack,
                                                isPlaying = hostSnapshot.playWhenReady,
                                                isBuffering = hostSnapshot.isBuffering,
                                                positionMs = hostSnapshot.playbackPositionMs,
                                                font = font,
                                                onTogglePlay = { host.dispatch(DesktopCommand.TogglePlayPause) },
                                                onNext = { host.dispatch(DesktopCommand.NextTrack) },
                                                onPrevious = { host.dispatch(DesktopCommand.PreviousTrack) },
                                                onSeek = { host.dispatch(DesktopCommand.SeekTo(it)) },
                                                onBack = { host.dispatch(DesktopCommand.SetRoute(WorkspaceRoute.Home)) }
                                            )
                                            WorkspaceRoute.Settings -> SettingsStage(
                                                glassQuality = hostSnapshot.glassQuality,
                                                reducedMotion = hostSnapshot.reducedMotion,
                                                eqPreset = hostSnapshot.eqPreset,
                                                volumeBoost = hostSnapshot.volumeBoost,
                                                crossfadeEnabled = hostSnapshot.crossfadeEnabled,
                                                moodFilter = hostSnapshot.moodFilter,
                                                font = font,
                                                onSetGlassQuality = { host.dispatch(DesktopCommand.SetGlassQuality(it)) },
                                                onSetReducedMotion = { host.dispatch(DesktopCommand.SetReducedMotion(it)) },
                                                onSetEqPreset = { host.dispatch(DesktopCommand.SetEqPreset(it)) },
                                                onSetVolumeBoost = { host.dispatch(DesktopCommand.SetVolumeBoost(it)) },
                                                onSetCrossfade = {host.dispatch(DesktopCommand.SetCrossfadeEnabled(it))},
                                                onSetMoodFilter = { host.dispatch(DesktopCommand.SetMoodFilter(it)) }
                                            )
                                            WorkspaceRoute.Encore -> EncoreStage(
                                                font = font
                                            )
                                        }
                                    }

                                    // Floating glass overlay sheet for narrow widths when inspector cannot dock
                                    if (constraints.isNarrowInspectorOverlay) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .width(360.dp)
                                                .fillMaxHeight()
                                                .background(RiffDesign.raised)
                                                .border(1.dp, Color.White.copy(alpha = 0.12f))
                                        ) {
                                            InspectorPane(
                                                activeTab = hostSnapshot.inspectorTab,
                                                currentTrack = hostSnapshot.currentTrack,
                                                queue = hostSnapshot.queue,
                                                queueEntries = hostSnapshot.queueEntries,
                                                currentEntryId = hostSnapshot.currentEntryId,
                                                queueRevision = hostSnapshot.queueRevision,
                                                positionMs = hostSnapshot.playbackPositionMs,
                                                font = font,
                                                lastRemovedItem = hostSnapshot.lastRemovedQueueItem,
                                                onSelectTab = { host.dispatch(DesktopCommand.ToggleInspector(it)) },
                                                onClose = { host.dispatch(DesktopCommand.ToggleInspector(hostSnapshot.inspectorTab)) },
                                                onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                                onPlayQueueEntry = { id,rev -> host.dispatch(DesktopCommand.PlayQueueEntry(id,rev)) },
                                                onReorder = { from, to -> host.dispatch(DesktopCommand.ReorderQueue(from, to)) },
                                                onReorderEntry = { id, up, rev -> host.dispatch(DesktopCommand.ReorderQueueByEntryId(id, up, rev)) },
                                                onMoveEntryToPosition = { id, targetIdx, rev -> host.dispatch(DesktopCommand.MoveQueueEntryToPosition(id, targetIdx, rev)) },
                                                onRemove = { host.dispatch(DesktopCommand.RemoveFromQueue(it)) },
                                                onRemoveEntry = { host.dispatch(DesktopCommand.RemoveQueueEntry(it)) },
                                                onUndoRemove = { host.dispatch(DesktopCommand.UndoQueueAction) },
                                                onOpenAlbum = { host.dispatch(DesktopCommand.OpenAlbumDetail(it)) },
                                                onOpenArtist = { host.dispatch(DesktopCommand.OpenArtistDetail(it)) },
                                                onSeek = { host.dispatch(DesktopCommand.SeekTo(it)) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }

                                // Draggable Inspector Divider & Docked Inspector Pane
                                if (constraints.isInspectorVisible) {
                                    WorkspaceDivider(
                                        label = "Inspector",
                                        currentWidth = constraints.inspectorWidth,
                                        minWidth = WorkspaceLayoutCalculator.INSPECTOR_MIN_WIDTH.dp,
                                        maxWidth = WorkspaceLayoutCalculator.INSPECTOR_MAX_WIDTH.dp,
                                        defaultWidth = WorkspaceLayoutCalculator.INSPECTOR_DEFAULT_WIDTH.dp,
                                        isLeftAnchor = false,
                                        onWidthChange = { host.dispatch(DesktopCommand.SetInspectorWidth(it.value)) }
                                    )

                                    InspectorPane(
                                        activeTab = hostSnapshot.inspectorTab,
                                        currentTrack = hostSnapshot.currentTrack,
                                        queue = hostSnapshot.queue,
                                        queueEntries = hostSnapshot.queueEntries,
                                        currentEntryId = hostSnapshot.currentEntryId,
                                        queueRevision = hostSnapshot.queueRevision,
                                        positionMs = hostSnapshot.playbackPositionMs,
                                        font = font,
                                        lastRemovedItem = hostSnapshot.lastRemovedQueueItem,
                                        onSelectTab = { host.dispatch(DesktopCommand.ToggleInspector(it)) },
                                        onClose = { host.dispatch(DesktopCommand.ToggleInspector(hostSnapshot.inspectorTab)) },
                                        onPlayTrack = { host.dispatch(DesktopCommand.PlayTrack(it)) },
                                        onPlayQueueEntry = { id,rev -> host.dispatch(DesktopCommand.PlayQueueEntry(id,rev)) },
                                        onReorder = { from, to -> host.dispatch(DesktopCommand.ReorderQueue(from, to)) },
                                        onReorderEntry = { id, up, rev -> host.dispatch(DesktopCommand.ReorderQueueByEntryId(id, up, rev)) },
                                        onMoveEntryToPosition = { id, targetIdx, rev -> host.dispatch(DesktopCommand.MoveQueueEntryToPosition(id, targetIdx, rev)) },
                                        onRemove = { host.dispatch(DesktopCommand.RemoveFromQueue(it)) },
                                        onRemoveEntry = { host.dispatch(DesktopCommand.RemoveQueueEntry(it)) },
                                        onUndoRemove = { host.dispatch(DesktopCommand.UndoQueueAction) },
                                        onOpenAlbum = { host.dispatch(DesktopCommand.OpenAlbumDetail(it)) },
                                        onOpenArtist = { host.dispatch(DesktopCommand.OpenArtistDetail(it)) },
                                        onSeek = { host.dispatch(DesktopCommand.SeekTo(it)) },
                                        modifier = Modifier.width(constraints.inspectorWidth)
                                    )
                                }
                            }

                            // Bottom Playback Dock (88dp)
                            DesktopDock(
                                currentTrack = hostSnapshot.currentTrack,
                                isPlaying = hostSnapshot.playWhenReady,
                                                isBuffering = hostSnapshot.isBuffering,
                                positionMs = hostSnapshot.playbackPositionMs,
                                volume = hostSnapshot.volume,
                                isShuffle = hostSnapshot.isShuffle,
                                repeatMode = hostSnapshot.repeatMode,
                                inspectorTab = hostSnapshot.inspectorTab,
                                font = font,
                                onTogglePlay = { host.dispatch(DesktopCommand.TogglePlayPause) },
                                onNext = { host.dispatch(DesktopCommand.NextTrack) },
                                onPrevious = { host.dispatch(DesktopCommand.PreviousTrack) },
                                onSeek = { host.dispatch(DesktopCommand.SeekTo(it)) },
                                onVolumeChange = { host.dispatch(DesktopCommand.SetVolume(it)) },
                                onToggleShuffle = { host.dispatch(DesktopCommand.ToggleShuffle) },
                                onToggleRepeat = { host.dispatch(DesktopCommand.ToggleRepeat) },
                                onToggleInspector = { host.dispatch(DesktopCommand.ToggleInspector(it)) },
                                onOpenMiniPlayer = { host.dispatch(DesktopCommand.SetWindowMode(WindowMode.MiniCompact)) },
                                onOpenFocusedPlayer = { host.dispatch(DesktopCommand.OpenFocusedPlayer) }
                            )
                        } else {
                            // Mini-Player Mode
                            MiniPlayerContent(
                                currentTrack = hostSnapshot.currentTrack,
                                isPlaying = hostSnapshot.playWhenReady,
                                                isBuffering = hostSnapshot.isBuffering,
                                positionMs = hostSnapshot.playbackPositionMs,
                                volume = hostSnapshot.volume,
                                isExpanded = hostSnapshot.windowMode == WindowMode.MiniExpanded,
                                isAlwaysOnTop = hostSnapshot.isAlwaysOnTop,
                                font = font,
                                onTogglePlay = { host.dispatch(DesktopCommand.TogglePlayPause) },
                                onNext = { host.dispatch(DesktopCommand.NextTrack) },
                                onPrevious = { host.dispatch(DesktopCommand.PreviousTrack) },
                                onSeek = { host.dispatch(DesktopCommand.SeekTo(it)) },
                                onVolumeChange = { host.dispatch(DesktopCommand.SetVolume(it)) },
                                onToggleAlwaysOnTop = { host.dispatch(DesktopCommand.SetAlwaysOnTop(!hostSnapshot.isAlwaysOnTop)) },
                                onToggleExpand = {
                                    val nextMode = if (hostSnapshot.windowMode == WindowMode.MiniExpanded) {
                                        WindowMode.MiniCompact
                                    } else {
                                        WindowMode.MiniExpanded
                                    }
                                    host.dispatch(DesktopCommand.SetWindowMode(nextMode))
                                },
                                onRestoreMain = { host.dispatch(DesktopCommand.SetWindowMode(WindowMode.Main)) }
                            )
                        }

                        // Floating notification toast banner
                        hostSnapshot.notificationMessage?.let { msg ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF262A36).copy(alpha = 0.95f))
                                        .border(1.dp, RiffDesign.pink.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Label(msg, font, Color.White, size = 13)
                                    if(hostSnapshot.pendingDownloadRemoval!=null)DataAction("Undo",font){host.dispatch(DesktopCommand.UndoDownloadRemoval)}
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .clickable { host.dispatch(DesktopCommand.DismissNotification()) }
                                            .padding(2.dp)
                                    ) {
                                        Label("✕", font, RiffDesign.muted, size = 12)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } finally {
        GlassShaderProgram.close()
    }
}

@Composable
private fun Action(text: String, font: FontFamily, modifier: Modifier = Modifier, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(1.dp, if (focused) RiffDesign.pink else Color.Transparent)
            .semantics { contentDescription = text }
            .clickable(role = Role.Button, onClickLabel = text, onClick = action),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(16.dp)) {
            val ink = Color.White
            val stroke = 1.5.dp.toPx()
            when (text) {
                "Minimize" -> drawLine(ink, Offset(0f, size.height * 0.65f), Offset(size.width, size.height * 0.65f), stroke)
                "Maximize" -> drawRect(ink, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), style = Stroke(stroke))
                "Restore" -> {
                    drawRect(ink, Offset(size.width * 0.22f, 0f), Size(size.width * 0.78f, size.height * 0.78f), style = Stroke(stroke))
                    drawRect(RiffDesign.raised, Offset(0f, size.height * 0.22f), Size(size.width * 0.78f, size.height * 0.78f))
                    drawRect(ink, Offset(0f, size.height * 0.22f), Size(size.width * 0.78f, size.height * 0.78f), style = Stroke(stroke))
                }
                "Restore Main" -> {
                    drawRect(ink, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), style = Stroke(stroke))
                    drawLine(ink, Offset(size.width * 0.3f, size.height * 0.5f), Offset(size.width * 0.7f, size.height * 0.5f), stroke)
                }
                "Close" -> {
                    drawLine(ink, Offset.Zero, Offset(size.width, size.height), stroke)
                    drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), stroke)
                }
            }
        }
    }
}

@Composable
internal fun Label(text: String, font: FontFamily, color: Color = Color.White, size: Int = 15) {
    val style = when (size) {
        34 -> RiffText.largeTitle
        22 -> RiffText.sectionHeading
        20 -> RiffMenu.title
        17 -> RiffText.rowTitle
        13 -> RiffText.subtitle
        else -> RiffText.body.copy(fontFamily = if (size >= 20) InterDisplayFamily else font, fontSize = size.sp)
    }
    BasicText(text, style = style.copy(color = color))
}

private fun interFamily(opticalSize: Float) = FontFamily(listOf(300, 400, 500, 600, 700, 800).map { weight ->
    Font("fonts/inter_variable.ttf", FontWeight(weight), variationSettings = FontVariation.Settings(
        FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)))
})

internal val InterDisplayFamily by lazy { interFamily(32f) }
internal val White = Color.White
internal val InterFamily by lazy { interFamily(14f) }
internal val Pink = RiffDesign.pink
internal val Ink = RiffDesign.ink
internal val Raised = RiffDesign.raised
internal val Muted = RiffDesign.muted
