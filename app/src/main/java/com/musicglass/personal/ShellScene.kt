package com.musicglass.personal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The collection the screen being drawn was opened for. Null outside a collection screen. */
internal val LocalCollectionRoute = androidx.compose.runtime.compositionLocalOf<Route.Collection?> { null }

@Composable
internal fun ShellScene(model: ShellModel, compact: Boolean, showChrome: Boolean,
    playback: PlaybackGateway, collections: LocalCollections, provider: YouTubeMusicProvider,
    ambient: androidx.compose.runtime.State<AmbientPalette>,
    onNotice: (String) -> Unit,
    onCategoryBounds: (String, androidx.compose.ui.geometry.Rect?) -> Unit = { _, _ -> }) {
    DisposableEffect(playback,model) {
        (playback as? MusicPlaybackGateway)?.onLivePlay={track->
            model.sheet="mini"
        }
        onDispose {(playback as? MusicPlaybackGateway)?.onLivePlay=null}
    }
    LaunchedEffect(model.tab,provider.authRevision) {
        provider.checkFeedWindow()
        if(model.tab=="Library" && provider.signedIn) provider.scanLibraryPlaylists().onFailure {onNotice(it.message?:string(R.string.could_not_check_playlists))}
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
        AmbientBackdrop(ambient, LocalPageBackdrop.current?.let { Modifier.riffBackdropSource(it) } ?: Modifier)
        // Dismissing hides the expired-session banner until the account or session changes.
        var expiredBannerDismissed by remember(provider.authRevision, provider.sessionState) { mutableStateOf(false) }
        AnimatedContent(targetState = model.screen,
            transitionSpec = {
                if (motion.reduced) EnterTransition.None togetherWith ExitTransition.None
                else {
                    // Deeper slides in from the end, back slides the other way; a change of root only fades.
                    val direction = when {
                        initialState.tab != targetState.tab -> 0
                        targetState.depth >= initialState.depth && targetState.depth > 0 -> 1
                        else -> -1
                    }
                    (fadeIn(GlassMotion.timed(GlassMotion.screenMs, motion)) +
                        slideInHorizontally(GlassMotion.timed(GlassMotion.screenMs, motion)) {
                            it * direction / 12
                        }) togetherWith
                        (fadeOut(GlassMotion.exit(GlassMotion.screenMs, motion)) +
                            slideOutHorizontally(GlassMotion.exit(GlassMotion.screenMs, motion)) {
                                -it * direction / 12
                            })
                }
            }, label = "scene navigation", modifier = Modifier.fillMaxSize()) { screen ->
        val tab = screen.tab; val route = screen.wire
        // Declared before the state holder below, so it is disposed after the holder has saved: a screen
        // that was closed, not just covered, has what it remembered dropped once it has left.
        DisposableEffect(screen.key) { onDispose { if (screen.key !in model.screenKeys) holder.removeState(screen.key) } }
        holder.SaveableStateProvider(screen.key) {
        // A screen draws the collection it was opened for, also while it slides away under the next one.
        androidx.compose.runtime.CompositionLocalProvider(LocalCollectionRoute provides screen.collection) {
        androidx.compose.runtime.key(if(route.isEmpty()) model.rootRevisions[tab]?:0 else 0) {
        // The typed route this name stands for; a collection carries its id in the model.
        when (Route.plain(route)) {
            Route.Account -> AccountScreen(model, provider, collections, playback, onNotice)
            Route.DeviceAudio -> OnDeviceScreen(model, totalBottomPadding, playback)
            Route.Downloads -> DownloadsScreen(model,totalBottomPadding,playback,onNotice)
            Route.Settings(SettingsSection.Main) -> SettingsScreen(model,totalBottomPadding,provider,collections,onNotice)
            Route.ListeningHistory -> ListeningHistoryScreen(model,totalBottomPadding,provider,playback,onNotice,onCleared=collections::refresh)
            Route.Encore -> EncoreScreen(model,totalBottomPadding,provider,playback,onNotice)
            Route.Settings(SettingsSection.NotInterested) -> NotInterestedScreen(model,totalBottomPadding,onNotice)
            Route.Settings(SettingsSection.EarlierData) -> EarlierDataScreen(model,totalBottomPadding,provider,playback,onNotice)
            Route.Settings(SettingsSection.Diagnostics) -> DiagnosticsScreen(model,totalBottomPadding,onNotice=onNotice)
            Route.Settings(SettingsSection.Equalizer) -> EqualizerScreen(model,totalBottomPadding)
            Route.Settings(SettingsSection.GlassGallery) -> GlassGalleryScreen(model,totalBottomPadding)
            Route.Settings(SettingsSection.PrivacyData) -> {
                val accessibility=LocalAccessibilitySettings.current
                PrivacyDataScreen(model,totalBottomPadding,provider,collections,playback,onNotice,onSettingsImported=accessibility::reload)
            }
            else -> if (route == "collection") CatalogDetailScreen(model,totalBottomPadding,provider,playback,onNotice)
            else if (screen.route is Route.ArtistShelf) ArtistShelfScreen(model,totalBottomPadding,screen.route,provider,playback,onNotice)
            else androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                when (tab) {
                    "Podcasts", "Radio" -> MediaExploreScreen(model, totalBottomPadding, tab, provider, playback, onNotice)
                    "Search" -> CatalogSearchScreen(model,totalBottomPadding,playback,provider,onNotice)
                    "Library" -> AccountLibraryScreen(model,totalBottomPadding,collections,playback,provider,onNotice)
                    else -> HomeFeedScreen(model, totalBottomPadding, playback, collections, provider, onNotice, onCategoryBounds)
                }
                // W5: Home and Library are where an expired session shows first (empty Library, failing likes).
                if ((tab == "Home" || tab == "Library") && provider.sessionState == SessionState.Expired && !expiredBannerDismissed)
                    SessionExpiredBanner(onSignIn = { model.push(Route.Account) }, onDismiss = { expiredBannerDismissed = true })
            }
        }
        }
        }
        }
        }
    }
}
