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
        // The typed route this name stands for; a collection carries its id in the model.
        when (Route.plain(route)) {
            Route.Account -> AccountScreen(model, provider, collections, playback, onNotice)
            Route.DeviceAudio -> OnDeviceScreen(model, totalBottomPadding, playback)
            Route.Downloads -> DownloadsScreen(model,totalBottomPadding,playback,onNotice)
            Route.Settings(SettingsSection.Main) -> SettingsScreen(model,totalBottomPadding,provider,collections,onNotice)
            Route.ListeningHistory -> ListeningHistoryScreen(model,totalBottomPadding,provider,playback,onNotice,onCleared=collections::refresh)
            Route.Settings(SettingsSection.NotInterested) -> NotInterestedScreen(model,totalBottomPadding,onNotice)
            Route.Settings(SettingsSection.EarlierData) -> EarlierDataScreen(model,totalBottomPadding,provider,playback,onNotice)
            Route.Settings(SettingsSection.PrivacyData) -> {
                val accessibility=LocalAccessibilitySettings.current
                PrivacyDataScreen(model,totalBottomPadding,provider,collections,playback,onNotice,onSettingsImported=accessibility::reload)
            }
            else -> if (route == "collection") CatalogDetailScreen(model,totalBottomPadding,provider,playback,onNotice)
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
