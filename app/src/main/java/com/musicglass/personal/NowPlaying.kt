package com.musicglass.personal

import android.content.Intent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.unit.round
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/** 2:13, or 1:43:39 from an hour up. */
internal fun playerClock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    val hours = seconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, seconds % 3600 / 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, active: Boolean = false,
    enabled: Boolean = true, diameter: androidx.compose.ui.unit.Dp = 50.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp,
    /** Feature 34: shuffle and repeat are white at 50 % when off, full white with a dot under when on. */
    toggle: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionPolicy.current
    val scale = animateFloatAsState(if (pressed) .95f else 1f,
        GlassMotion.timed(GlassMotion.pressMs, motion), label = "$label press")
    Box(modifier.size(diameter)
        .clickable(interactionSource = interaction, indication = null,
            enabled = enabled, onClick = hapticClick(onClick)), contentAlignment = Alignment.Center) {
        Crossfade(targetState = icon,
            animationSpec = GlassMotion.timed(GlassMotion.selectionMs, motion),
            label = "$label icon") { current ->
            Icon(current, contentDescription = label,
                tint = White,
                modifier = Modifier.size(iconSize).graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value
                    alpha = if (!enabled) .35f else if (toggle && !active) .5f else 1f
                })
        }
        if (toggle) ActiveDot(active && enabled, Modifier.align(Alignment.BottomCenter))
    }
}

/** Why the position bar does not move for a live stream: there is nothing behind or ahead to move to. */
internal val LIVE_SEEK_EXPLANATION: String get() = string(R.string.live_radio_can_t_be_rewound)

/** Position ticks only recompose this small subtree; the thumb follows a drag exactly. */
@Composable
internal fun SeekControl(playback: PlaybackGateway, mediaId: String, durationMs: Long) {
    var dragging by remember(mediaId) { mutableStateOf(false) }
    var preview by remember(mediaId) { mutableFloatStateOf(0f) }
    val live=playback.state.live
    val duration = if(live) 1L else durationMs.coerceAtLeast(1L)
    val position = if(live) 0f else if (dragging) preview else playback.positionMs
        .coerceIn(0L, duration).toFloat()
    Column {
        ThinSlider(value=position.coerceIn(0f,duration.toFloat()),
            onValueChange={dragging=true;preview=it},onValueChangeFinished={
                if(dragging) playback.seekTo(preview.toLong());dragging=false
            },valueRange=0f..duration.toFloat(),enabled=durationMs>0 && !playback.state.live,
            // Said as a time, not as a percentage; a live stream says why it cannot be moved.
            modifier=Modifier.fillMaxWidth().testTag("playback_seek").semantics {
                contentDescription=string(R.string.position)
                stateDescription=if(live) LIVE_SEEK_EXPLANATION else string(R.string.position_x_of_x, playerClock(position.toLong()), playerClock(duration))
            })
        // Feature 34: times at 55 % in figures of one width, the quality streams ask for between them.
        val timeStyle = TextStyle(fontSize = RiffPlayer.time, fontFeatureSettings = "tnum")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if(playback.state.live) string(R.string.live) else playerClock(position.toLong()), color = if(playback.state.live) Pink else White.copy(alpha = .55f), style = timeStyle)
            if(!live) PlayerQualityLabel(mediaId)
            Text(if(playback.state.live) LIVE_SEEK_EXPLANATION else "−${playerClock((duration - position.toLong()).coerceAtLeast(0))}",
                color = White.copy(alpha = .55f), style = timeStyle)
        }
    }
}

@Composable
internal fun NowPlayingPanel(playback: PlaybackGateway, collections: LocalCollections, provider: YouTubeMusicProvider,
    transportAlpha: () -> Float = {1f}, interactive: Boolean = true, queueOpen:Boolean=false, onQueueClose:()->Unit={},
    artworkAlpha: () -> Float = {1f},
    sharedContentAlpha: () -> Float = {1f}, dragging: Boolean = false,
    onMorphElementBounds: (String, androidx.compose.ui.layout.LayoutCoordinates) -> Unit = { _, _ -> },
    onClose: () -> Unit,
    onSwipeDrag: (Float, Long) -> Unit, onSwipeRelease: (Float) -> Unit,
    onQueue: () -> Unit, onLibrary: () -> Unit, onEqualizer: () -> Unit = {},
    onLyrics: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit, onFailureAction: (FailureAction) -> Unit,
    onNotice: (String) -> Unit) {
    val state = playback.state
    val songTransition=rememberSongPresentationTransition(state)
    val artworkColor=rememberArtworkColorState(state.artworkUrl)
    val context = LocalContext.current
    val motion = LocalMotionPolicy.current
    val hasQueue = state.queue.size > 1
    val scope=rememberCoroutineScope()
    val videoId=state.mediaId.removePrefix("yt:")
    val favorite=provider.songLikes[videoId] == true
    var likeBusy by remember(state.mediaId,provider.authRevision) { mutableStateOf(false) }
    LaunchedEffect(state.mediaId,provider.authRevision) {
        if(state.source=="YouTube Music" && provider.signedIn) {
            likeBusy=true;provider.loadLike(videoId);likeBusy=false
        }
    }
    var playlistTrack by remember {mutableStateOf<ProviderTrack?>(null)}
    playlistTrack?.let {PlaylistPicker(it,{playlistTrack=null},onNotice)}
    var sleepTimerVisible by remember {mutableStateOf(false)}
    if(sleepTimerVisible) SleepTimerDialog(playback) {sleepTimerVisible=false}
    var speedVisible by remember {mutableStateOf(false)}
    if(speedVisible) PlaybackSpeedDialog(playback) {speedVisible=false}
    var nerdStatsVisible by remember {mutableStateOf(false)}
    if(nerdStatsVisible) NerdStatsDialog(playback,onNotice) {nerdStatsVisible=false}
    var moreExpanded by remember(state.mediaId) { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf<androidx.compose.ui.unit.IntRect?>(null) }
    var creditsVisible by remember(state.mediaId) { mutableStateOf(false) }
    fun providerTrack() = ProviderTrack(state.mediaId.removePrefix("yt:"),
        state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live,state.music)
    val pins = remember(context) { SpeedDialPins.get(context) }
    // Q18-083: Riff's own list on this phone, apart from the YouTube like: added and removed here, never sent anywhere.
    val keptOnPhone = collections.onlineFavorites.any { it.videoId == videoId }
    fun keepOnPhone(keep: Boolean) {
        collections.setOnlineFavorite(providerTrack(), keep)
        uiHaptic(HapticCue.Confirm)
        onNotice(string(if (keep) R.string.added_to_saved_on_this_phone else R.string.removed_from_saved_on_this_phone))
    }
    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    val seekNudge = remember { SeekNudge() }
    val density = LocalDensity.current.density
    val viewportHeight = LocalWindowInfo.current.containerSize.height / density
    val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = navigationInset + 10.dp
    val heroHeight = (viewportHeight / 2f).dp
    val fontScale = LocalDensity.current.fontScale
    val controlHeight = (viewportHeight.dp - navigationInset -
        heroHeight - 10.dp).coerceAtLeast((330f * fontScale.coerceIn(1f, 2f)).dp)
    val allowScroll = viewportHeight < 730f || fontScale >= 1.3f
    val scrollState = rememberScrollState()
    // Q18-079: the open queue locks the player's scrolling, so it opens at the top, wherever Open queue was pressed.
    LaunchedEffect(queueOpen) { if (queueOpen) scrollState.scrollTo(0) }
    // Feature 33: how light the cover is under the status bar sets the status icons' colour and the close and
    // heading ink. The video, live radio and the open queue are on dark, so they ask for nothing.
    val pictureLuminance = rememberTopLuminance(state.artworkUrl.takeUnless { state.video || state.live || queueOpen },
        LocalWindowInfo.current.containerSize.width.toFloat(), heroHeight.value * density, (statusInset + RiffSpace.touch).value * density)
    val lightPicture = rememberLightPicture(pictureLuminance) == true
    ReportStatusBar("player", if (interactive) lightPicture else null, STATUS_PRIORITY_PLAYER)
    val swipeDrag by rememberUpdatedState(onSwipeDrag)
    val swipeRelease by rememberUpdatedState(onSwipeRelease)
    val swipeConnection = remember(scrollState) {
        object : NestedScrollConnection {
            private var pullDown = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput &&
                    (scrollState.value == 0 && available.y > 0f || pullDown > 0f)) {
                    val next = (pullDown + available.y).coerceAtLeast(0f)
                    val consumed = next - pullDown
                    pullDown = next
                    swipeDrag(consumed, android.os.SystemClock.uptimeMillis())
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullDown > 0f) swipeRelease(-available.y)
                pullDown = 0f
                return Velocity.Zero
            }
        }
    }
    val preferences = LocalAccessibilitySettings.current
    // Feature 34.2: the cover's own colours, blurred, under the whole panel; the video and live radio stay on black.
    Box(Modifier.fillMaxWidth().fillMaxHeight()) {
    if(!state.video && !state.live) PlayerBackdrop(state.artworkUrl, artworkColor.value, Modifier.matchParentSize())
    Column(Modifier.fillMaxWidth().fillMaxHeight().preferredFrameRate(LocalUiRefreshRate.current)
        .drawBehind {if(state.video || state.live) drawRect(Color.Black)}
        .then(if(queueOpen) Modifier else Modifier.nestedScroll(swipeConnection))
        .verticalScroll(scrollState, enabled = (interactive || dragging) && allowScroll && !queueOpen)
        .padding(bottom = bottomSpace),
        verticalArrangement = Arrangement.Top) {
        Box(Modifier.fillMaxWidth().height(playerHeroHeight(viewportHeight, bottomSpace, queueOpen))
            .then(if ((interactive || dragging) && !allowScroll && !queueOpen) Modifier.pointerInput(state.mediaId,queueOpen) {
            var pull = 0f
            var lastTime = 0L
            var velocity = 0f
            detectVerticalDragGestures(onVerticalDrag = { change, amount ->
                val next = (pull + amount).coerceAtLeast(0f)
                val consumed = next - pull
                pull = next
                if (consumed != 0f) {
                    if (lastTime > 0L) velocity = -consumed * 1000f /
                        (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    lastTime = change.uptimeMillis
                    swipeDrag(consumed, change.uptimeMillis)
                    change.consume()
                }
            }, onDragEnd = {
                if (pull > 0f) swipeRelease(velocity)
                pull = 0f
                lastTime = 0L
                velocity = 0f
            }, onDragCancel = {
                if (pull > 0f) swipeRelease(0f)
                pull = 0f
                lastTime = 0L
                velocity = 0f
            })
        } else Modifier)) {
            SharedTransitionLayout {
            AnimatedContent(queueOpen,transitionSpec={fadeIn(GlassMotion.timed(GlassMotion.playerMs,motion)) togetherWith fadeOut(GlassMotion.exit(GlassMotion.playerMs,motion))},label="queue artwork morph",modifier=Modifier.fillMaxSize()) {open->
            val queueVisibility=this
            if(open) {
                InlineQueue(playback,provider,onQueueClose,onLike={
                    if(!provider.capabilities.like.available) onNotice(provider.capabilities.like.reason?:CHECKING_ACCOUNT_NOTICE) else scope.launch {
                        provider.setSongLike(videoId,!favorite).fold(onSuccess={uiHaptic(HapticCue.Confirm);unlikedOffPhone(collections,providerTrack(),!favorite)},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:string(R.string.could_not_save_like))})
                    }
                },onMore={moreExpanded=true},onMoreBounds={moreAnchor=it},artworkModifier=Modifier.sharedBounds(rememberSharedContentState("queue-art"),this))
            } else Box(Modifier.fillMaxSize()) {
            songTransition.SongContentFade(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artworkAlpha() }) { song ->
                val itemId=song.mediaId;val itemArtwork=song.artworkUrl;val renderer=song.renderer
                Box(Modifier.fillMaxSize().pointerInput(itemId, hasQueue) {
                    detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                        dragX += amount
                        change.consume()
                    }, onDragEnd = {
                        if (hasQueue && dragX < -65.dp.toPx()) {playback.next();uiHaptic(HapticCue.Selection)}
                        if (hasQueue && dragX > 65.dp.toPx()) {playback.previousSong();uiHaptic(HapticCue.Selection)}
                        dragX = 0f
                    }, onDragCancel = { dragX = 0f })
                }.doubleTapSeek(!state.live && renderer != 2 && itemId == state.mediaId, seekNudge, playback, state.durationMs)) {
                    if(renderer==2) InlineYouTubeVideo(playback,videoId,Modifier.fillMaxSize()) else if(renderer==1) NativeSongVideo(playback,Modifier.fillMaxSize()) else PlayerArtwork(itemArtwork ?: providerArtworkUrl(itemId.removePrefix("yt:")),
                        Modifier.fillMaxSize().sharedBounds(rememberSharedContentState("queue-art"),queueVisibility))
                    SeekNudgeOverlay(seekNudge)
                }
            }
            PlayerStatsLine(playback, preferences.playerStats && interactive && !queueOpen,
                Modifier.align(Alignment.BottomCenter).padding(bottom = RiffSpace.item))
            // No fade over the artwork (Q18-002, the owner's choice): the close button and the heading carry their own shadows.
            CompositionLocalProvider(LocalLightPicture provides lightPicture) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .padding(top = statusInset + 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(onClose),enabled=!queueOpen, modifier = Modifier.testTag("player_close")) {
                    OverPictureIcon(Icons.Rounded.Close, string(R.string.close_player))
                }
                Box(Modifier.weight(1f)) {
                    Text(string(R.string.now_playing), color = overPictureInk(lightPicture), fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1,
                        style = TextStyle(shadow = overPictureShadow(lightPicture)))
                }
            }
            }
        }
            }
            }
            }
        // Where the page scrolls, the controls take the room they need: at a fixed height on a short screen the
        // last rows were squeezed under the ones above them (Q13).
        Column(Modifier.fillMaxWidth().then(if(allowScroll) Modifier.heightIn(min = controlHeight) else Modifier.height(controlHeight))
            .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().graphicsLayer {alpha=if(queueOpen) 0f else 1f}.then(if(queueOpen) Modifier.clearAndSetSemantics {} else Modifier), verticalAlignment = Alignment.CenterVertically) {
            songTransition.SongContentFade(Modifier.weight(1f)) {song->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val titleStyle = TextStyle(fontSize = RiffPlayer.title, fontWeight = FontWeight.Bold)
                val scrollTitle = playerTitleScrolls(motion.reduced, fontScale, interactive, queueOpen, dragging)
                var titleRowWidth by remember { mutableIntStateOf(0) }
                val titleMeasurer = rememberTextMeasurer()
                val titleOverflows = scrollTitle && titleRowWidth > 0 && remember(song.title, titleMeasurer) {
                    titleMeasurer.measure(song.title, titleStyle, maxLines = 1, softWrap = false).size.width }.let { it > titleRowWidth }
                Text(song.title, color = White, style = titleStyle,
                    maxLines = if (scrollTitle) 1 else if (fontScale >= 1.5f) 2 else 1,
                    overflow = if (scrollTitle) TextOverflow.Clip else TextOverflow.Ellipsis, softWrap = !scrollTitle,
                    modifier = Modifier.lyricsSharedBounds("song-title:${song.mediaId}").graphicsLayer { alpha = sharedContentAlpha() }
                        .onGloballyPositioned {
                            if(song.mediaId==state.mediaId) onMorphElementBounds("title", it) }
                        .onSizeChanged { titleRowWidth = it.width }
                        .playerTitleMarquee(scrollTitle, titleOverflows)
                        .then(if (scrollTitle) Modifier.testTag("player_title_marquee") else Modifier))
                Text(playerArtistLabel(song.artist), color = White.copy(alpha = .55f), fontSize = RiffPlayer.artist,
                    maxLines = if (fontScale >= 1.5f) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.lyricsSharedBounds("song-artist:${song.mediaId}")
                        .then(if(state.source=="YouTube Music" && !queueOpen && song.mediaId==state.mediaId) Modifier.clickable(onClick = hapticClick(onArtist)).testTag("player_artist") else Modifier)
                        .graphicsLayer { alpha = sharedContentAlpha() }
                        .onGloballyPositioned {
                            if(song.mediaId==state.mediaId) onMorphElementBounds("artist", it) })
            }
            }
            IconButton(onClick = {
                if(!provider.capabilities.like.available) onNotice(provider.capabilities.like.reason?:CHECKING_ACCOUNT_NOTICE)
                else { likeBusy=true;scope.launch {
                    provider.setSongLike(videoId,!favorite).fold(onSuccess={
                        uiHaptic(HapticCue.Confirm)
                        unlikedOffPhone(collections,providerTrack(),!favorite)
                        onNotice(if(!favorite) string(R.string.liked_on_youtube_music) else string(R.string.removed_from_youtube_music_liked_songs))
                    },onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:string(R.string.could_not_save_like))});likeBusy=false
                } }
            }, enabled = state.source == "YouTube Music" && !likeBusy && !queueOpen, modifier = Modifier.size(RiffPlayer.circle)
                .clip(CircleShape).background(White.copy(alpha = .18f))
                .testTag("playback_favorite").semantics {
                    contentDescription = string(R.string.like_on_youtube_music); stateDescription = if(favorite) string(R.string.liked) else string(R.string.not_liked)
                }) {
                Crossfade(targetState = favorite,
                    animationSpec = GlassMotion.timed(GlassMotion.selectionMs, motion),
                    label = "favorite state") { saved ->
                    Icon(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = White, modifier = Modifier.size(RiffPlayer.circleIcon))
                }
            }
            Spacer(Modifier.width(RiffSpace.item))
            Box {
                IconButton(onClick = { uiHaptic();  moreExpanded = true },
                    enabled=!queueOpen,modifier = Modifier.size(RiffPlayer.circle).clip(CircleShape).background(White.copy(alpha = .18f))
                        .testTag("player_more").onGloballyPositioned {if(!queueOpen) {val r=it.boundsInWindow();moreAnchor=androidx.compose.ui.unit.IntRect(r.topLeft.round(),r.bottomRight.round())}}) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = string(R.string.more_song_options),
                        tint = White, modifier = Modifier.size(RiffPlayer.circleIcon))
                }
                PlayerActionMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }, anchorBounds=moreAnchor) {
                    if(state.source=="YouTube Music") DownloadSongAction(providerTrack(),onNotice,onDismiss={moreExpanded=false})
                    val options = if (state.source == "YouTube Music")
                        listOf("View credits", "Add to playlist", "Share song", "View album", "View artist", "Favorite", "Play next", "Add to queue", if(keptOnPhone) "Remove from Saved on this phone" else "Save on this phone", "Library shortcuts", "Sleep timer", "Playback speed", "Equalizer", "Stats for nerds", if(providerTrack().videoId in pins) "Unpin from speed dial" else "Pin to speed dial") + (if(hasQueue) listOf("Remove from queue") else emptyList()) + (if(state.live) emptyList() else listOf("Not interested"))
                        else listOf("Library shortcuts", "View credits", "Sleep timer", "Playback speed", "Equalizer", "Stats for nerds")
                    options.forEach { option ->
                        SongActionItem(if(option=="Sleep timer" && state.sleepDeadlineMs>0) string(R.string.sleep_timer_x_min, sleepMinutesRemaining(state.sleepDeadlineMs,android.os.SystemClock.elapsedRealtime())) else option, onClick = { 
                            moreExpanded = false
                            when (option) {
                                "Favorite" -> if(!provider.capabilities.like.available) onNotice(provider.capabilities.like.reason?:CHECKING_ACCOUNT_NOTICE) else scope.launch {
                                    provider.setSongLike(videoId,!favorite).fold(onSuccess={uiHaptic(HapticCue.Confirm);unlikedOffPhone(collections,providerTrack(),!favorite)},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:string(R.string.could_not_save_like))})
                                }
                                "Remove from queue" -> playback.removeQueueItemWithUndo(state.index)
                                "Not interested" -> scope.launch {markNotInterested(context,provider,playback,NotInterestedEntry(videoId,state.title,state.artist,state.artworkUrl),null,onNotice)}
                                "View artist" -> onArtist()
                                "View album" -> onAlbum()
                                "Play next", "Add to queue" -> scope.launch {
                                    provider.resolve(videoId,state.live).fold(onSuccess={playback.enqueueProvider(providerTrack(),it,option=="Play next");onNotice(string(R.string.added_to_queue))},onFailure={onNotice(it.message?:string(R.string.could_not_add_to_queue))})
                                }
                                "Save on this phone" -> if (collectionsCanSave(collections)) keepOnPhone(true)
                                    else onNotice(saveUnavailableNotice(provider.accountScope()))
                                "Remove from Saved on this phone" -> keepOnPhone(false)
                                "Add to playlist" -> playlistTrack=providerTrack()
                                "Sleep timer" -> sleepTimerVisible=true
                                "Playback speed" -> speedVisible=true
                                "Stats for nerds" -> nerdStatsVisible=true
                                "Equalizer" -> onEqualizer()
                                "Library shortcuts" -> onLibrary()
                                "Share song" -> share("${state.title} \u00B7 ${state.artist}\nhttps://music.youtube.com/watch?v=${providerTrack().videoId}")
                                "View credits" -> creditsVisible = true
                                "Pin to speed dial", "Unpin from speed dial" -> toggleSpeedDialPin(context, providerTrack().homeCard(), onNotice)
                            }
                        })
                    }
                }
            }
        }
        if(preferences.playerLyricLine && !state.live && !state.video && fontScale < 1.5f)
            PlayerLyricLine(playback, provider, visible = interactive && !queueOpen, onLyrics = onLyrics)
        SeekControl(playback, state.mediaId, state.durationMs)
        Row(Modifier.fillMaxWidth().graphicsLayer { alpha = transportAlpha() },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(RoundedTransportIcons.Shuffle,
                if (state.shuffle) string(R.string.shuffle_on) else string(R.string.shuffle_off), playback::toggleShuffle,
                modifier = Modifier.testTag("playback_shuffle"), active = state.shuffle,
                enabled = hasQueue, diameter = RiffPlayer.toggle, iconSize = RiffPlayer.toggleIcon, toggle = true)
            TransportButton(Icons.Rounded.FastRewind, string(R.string.previous_track), playback::previous,
                // Q18-063: Previous starts a song on its own again, so it is never greyed while a song is loaded.
                modifier = Modifier.testTag("playback_previous"), enabled = state.mediaId.isNotEmpty(),
                diameter = RiffPlayer.skip, iconSize = RiffPlayer.skipIcon)
            TransportButton(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) string(R.string.pause) else string(R.string.play), playback::toggle,
                modifier = Modifier.testTag("playback_toggle")
                    .graphicsLayer { alpha = sharedContentAlpha() }
                    .onGloballyPositioned {
                        onMorphElementBounds("play", it) },
                diameter = RiffPlayer.play, iconSize = RiffPlayer.playIcon)
            TransportButton(Icons.Rounded.FastForward, string(R.string.next_track), playback::next,
                modifier = Modifier.testTag("playback_next")
                    .graphicsLayer { alpha = sharedContentAlpha() }
                    .onGloballyPositioned {
                        onMorphElementBounds("next", it) }, enabled = hasQueue,
                diameter = RiffPlayer.skip, iconSize = RiffPlayer.skipIcon)
            TransportButton(if (state.repeat == 1) RoundedTransportIcons.RepeatOne else RoundedTransportIcons.Repeat,
                when (state.repeat) { 1 -> string(R.string.repeat_one); 2 -> string(R.string.repeat_all); else -> string(R.string.repeat_off) },
                playback::cycleRepeat, modifier = Modifier.testTag("playback_repeat"),
                active = state.repeat != 0, diameter = RiffPlayer.toggle, iconSize = RiffPlayer.toggleIcon, toggle = true)
        }
        AudioOutputControls(onLyrics = onLyrics, onQueue = onQueue)
        if(state.sleepDeadlineMs>0) SleepTimerCountdown(state.sleepDeadlineMs) {uiHaptic();sleepTimerVisible=true}
        PlaybackProblem(state.notice, state.error, state.playback.failure, onFailureAction)
        }
    }
    }
    if (creditsVisible) AlertDialog(onDismissRequest = { creditsVisible = false },
        title = { Text(string(R.string.credits), color = White) },
        text = {
            Text(string(R.string.x_x_full_contributor_credits_are_unavailable, state.title, state.artist),
                color = White)
        },
        confirmButton = { TextButton(onClick = { uiHaptic();  creditsVisible = false }) {
            Text(string(R.string.done), color = Pink)
        } },
        containerColor = Raised)
}

private fun collectionsCanSave(collections: LocalCollections) = collections.canSave

/**
 * What recovery tells the user (Q07): a notice while it waits for a connection, or a failure with
 * what can be done about it. Waiting is not a failure: Pause ends it, and nothing else needs doing.
 */
@Composable
internal fun PlaybackProblem(notice: String?, error: String?, failure: PlaybackFailure?, onAction: (FailureAction) -> Unit) {
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        notice?.let { Text(it, color = White.copy(alpha = .78f), modifier = Modifier.testTag("playback_notice")) }
        error?.let {
            Text(it, color = Pink, modifier = Modifier.testTag("playback_error"))
            Row { failure?.actions().orEmpty().forEach { action ->
                TextButton(onClick = hapticClick { onAction(action) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("playback_${action.name.lowercase()}")) {
                    Text(action.label, color = White) }
            } }
        }
    }
}

/**
 * Q18-083: a like is YouTube's alone and no longer adds the song to Riff's list on this phone; an unlike also takes
 * it off that list, so a song the owner no longer likes is not left under Liked songs.
 */
internal fun unlikedOffPhone(collections: LocalCollections, track: ProviderTrack, liked: Boolean) {
    if (!liked) collections.setOnlineFavorite(track, false)
}

/**
 * The player's top area: half the window for the artwork; the whole window above the navigation bar for the open
 * queue (Q18-079), which at the largest text needed more than half for its heading alone.
 */
internal fun playerHeroHeight(viewportHeightDp: Float, bottomSpace: Dp, queueOpen: Boolean): Dp {
    val half = (viewportHeightDp / 2f).dp
    return if (queueOpen) (viewportHeightDp.dp - bottomSpace).coerceAtLeast(half) else half
}
