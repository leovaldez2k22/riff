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
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import coil3.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private fun clock(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun TransportButton(icon: ImageVector, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, active: Boolean = false,
    enabled: Boolean = true, diameter: androidx.compose.ui.unit.Dp = 50.dp,
    iconSize: androidx.compose.ui.unit.Dp = 28.dp) {
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
                tint = if (active) Pink else White,
                modifier = Modifier.size(iconSize).graphicsLayer {
                    scaleX = scale.value; scaleY = scale.value
                    alpha = if (enabled) 1f else .36f
                })
        }
    }
}

/** Position ticks only recompose this small subtree; the thumb follows a drag exactly. */
@Composable
private fun SeekControl(playback: PlaybackGateway, mediaId: String, durationMs: Long) {
    var dragging by remember(mediaId) { mutableStateOf(false) }
    var preview by remember(mediaId) { mutableFloatStateOf(0f) }
    val live=playback.state.live
    val duration = if(live) 1L else durationMs.coerceAtLeast(1L)
    val position = if(live) 0f else if (dragging) preview else playback.positionMs
        .coerceIn(0L, duration).toFloat()
    Column {
        CapsuleSlider(value=position.coerceIn(0f,duration.toFloat()),
            onValueChange={dragging=true;preview=it},onValueChangeFinished={
                if(dragging) playback.seekTo(preview.toLong());dragging=false
            },valueRange=0f..duration.toFloat(),enabled=durationMs>0 && !playback.state.live,
            modifier=Modifier.fillMaxWidth().testTag("playback_seek"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if(playback.state.live) "LIVE" else clock(position.toLong()), color = if(playback.state.live) Pink else Muted, fontSize = 12.sp)
            Text(if(playback.state.live) "Live radio" else "−${clock((duration - position.toLong()).coerceAtLeast(0))}",
                color = Muted, fontSize = 12.sp)
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
    onQueue: () -> Unit, onLibrary: () -> Unit,
    onLyrics: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit, onRetry: () -> Unit,
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
    var videoBusy by remember(state.mediaId) {mutableStateOf(false)}
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
    var moreExpanded by remember(state.mediaId) { mutableStateOf(false) }
    var moreAnchor by remember { mutableStateOf<androidx.compose.ui.unit.IntRect?>(null) }
    var creditsVisible by remember(state.mediaId) { mutableStateOf(false) }
    fun providerTrack() = ProviderTrack(state.mediaId.removePrefix("yt:"),
        state.title,state.artist,state.artworkUrl,state.artistId,state.albumId,state.album,state.live,state.music)
    fun addToLibrary() {
        collections.setOnlineFavorite(providerTrack(),true)
        uiHaptic(HapticCue.Confirm)
        onNotice("Saved on this phone")
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
    val heroHeight = (viewportHeight / 2f).dp
    val fontScale = LocalDensity.current.fontScale
    val controlHeight = (viewportHeight.dp - navigationInset -
        heroHeight - 10.dp).coerceAtLeast((330f * fontScale.coerceIn(1f, 2f)).dp)
    val allowScroll = viewportHeight < 730f || fontScale >= 1.3f
    val scrollState = rememberScrollState()
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
    Column(Modifier.fillMaxWidth().fillMaxHeight().preferredFrameRate(LocalUiRefreshRate.current)
        .drawBehind {drawRect(if(state.video || state.live) Brush.verticalGradient(listOf(Color.Black,Color.Black)) else playerBackground(artworkColor.value))}
        .then(if(queueOpen) Modifier else Modifier.nestedScroll(swipeConnection))
        .verticalScroll(scrollState, enabled = (interactive || dragging) && allowScroll && !queueOpen)
        .padding(bottom = navigationInset + 10.dp),
        verticalArrangement = Arrangement.Top) {
        Box(Modifier.fillMaxWidth().height(heroHeight)
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
            AnimatedContent(queueOpen,transitionSpec={fadeIn(GlassMotion.timed(GlassMotion.playerMs,motion)) togetherWith fadeOut(GlassMotion.timed(GlassMotion.playerMs,motion))},label="queue artwork morph",modifier=Modifier.fillMaxSize()) {open->
            val queueVisibility=this
            if(open) {
                InlineQueue(playback,provider,onQueueClose,onLike={
                    if(!provider.signedIn) onNotice("Sign in to like songs on YouTube Music") else scope.launch {
                        provider.setSongLike(videoId,!favorite).fold(onSuccess={uiHaptic(HapticCue.Confirm);collections.setOnlineFavorite(providerTrack(),!favorite)},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:"Could not save like")})
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
                        if (hasQueue && dragX > 65.dp.toPx()) {playback.previous();uiHaptic(HapticCue.Selection)}
                        dragX = 0f
                    }, onDragCancel = { dragX = 0f })
                }.doubleTapSeek(!state.live && renderer != 2 && itemId == state.mediaId, seekNudge, playback, state.durationMs)) {
                    if(renderer==2) InlineYouTubeVideo(playback,videoId,Modifier.fillMaxSize()) else if(renderer==1) NativeSongVideo(playback,Modifier.fillMaxSize()) else PlayerArtwork(itemArtwork ?: providerArtworkUrl(itemId.removePrefix("yt:")),
                        Modifier.fillMaxSize().sharedBounds(rememberSharedContentState("queue-art"),queueVisibility))
                    SeekNudgeOverlay(seekNudge)
                }
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .16f), .36f to Color.Transparent)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .padding(top = statusInset + 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = hapticClick(onClose),enabled=!queueOpen, modifier = Modifier.testTag("player_close")) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close player", tint = White)
                }
                Text("NOW PLAYING", color = White, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                    modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
            }
            }
            }
        Column(Modifier.fillMaxWidth().height(controlHeight).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth().graphicsLayer {alpha=if(queueOpen) 0f else 1f}.then(if(queueOpen) Modifier.clearAndSetSemantics {} else Modifier), verticalAlignment = Alignment.CenterVertically) {
            songTransition.SongContentFade(Modifier.weight(1f)) {song->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(song.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = if (fontScale >= 1.5f) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.lyricsSharedBounds("song-title:${song.mediaId}").graphicsLayer { alpha = sharedContentAlpha() }
                        .onGloballyPositioned {
                            if(song.mediaId==state.mediaId) onMorphElementBounds("title", it) })
                Text(playerArtistLabel(song.artist), color = Muted, fontSize = 13.sp,
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
                if(!provider.signedIn) onNotice("Sign in from Settings to like songs on YouTube Music")
                else { likeBusy=true;scope.launch {
                    provider.setSongLike(videoId,!favorite).fold(onSuccess={
                        uiHaptic(HapticCue.Confirm)
                        collections.setOnlineFavorite(providerTrack(),!favorite)
                        onNotice(if(!favorite) "Liked on YouTube Music" else "Removed from YouTube Music liked songs")
                    },onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:"Could not save like")});likeBusy=false
                } }
            }, enabled = state.source == "YouTube Music" && !likeBusy && !queueOpen, modifier = Modifier.size(40.dp)
                .testTag("playback_favorite").semantics {
                    contentDescription = if(favorite) "Remove YouTube Music like" else "Like on YouTube Music"
                }) {
                Crossfade(targetState = favorite,
                    animationSpec = GlassMotion.timed(GlassMotion.selectionMs, motion),
                    label = "favorite state") { saved ->
                    Icon(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = White, modifier = Modifier.size(21.dp))
                }
            }
            Spacer(Modifier.width(7.dp))
            Box {
                IconButton(onClick = { uiHaptic();  moreExpanded = true },
                    enabled=!queueOpen,modifier = Modifier.size(40.dp)
                        .testTag("player_more").onGloballyPositioned {if(!queueOpen) {val r=it.boundsInWindow();moreAnchor=androidx.compose.ui.unit.IntRect(r.topLeft.round(),r.bottomRight.round())}}) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "More song options",
                        tint = White, modifier = Modifier.size(21.dp))
                }
                PlayerActionMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }, anchorBounds=moreAnchor) {
                    if(state.source=="YouTube Music") DownloadSongAction(providerTrack(),onNotice,onDismiss={moreExpanded=false})
                    val options = if (state.source == "YouTube Music")
                        listOf("View credits", "Add to playlist", "Share song", "View album", "View artist", "Favorite", "Play next", "Add to queue", "Save on this phone", "Library shortcuts", "Sleep timer") + (if(hasQueue) listOf("Remove from queue") else emptyList()) + (if(state.live) emptyList() else listOf("Not interested"))
                        else listOf("Library shortcuts", "View credits", "Sleep timer")
                    options.forEach { option ->
                        SongActionItem(if(option=="Sleep timer" && state.sleepDeadlineMs>0) "Sleep timer · ${sleepMinutesRemaining(state.sleepDeadlineMs,android.os.SystemClock.elapsedRealtime())} min" else option, onClick = { 
                            moreExpanded = false
                            when (option) {
                                "Favorite" -> if(!provider.signedIn) onNotice("Sign in to like songs on YouTube Music") else scope.launch {
                                    provider.setSongLike(videoId,!favorite).fold(onSuccess={uiHaptic(HapticCue.Confirm);collections.setOnlineFavorite(providerTrack(),!favorite)},onFailure={uiHaptic(HapticCue.Reject);onNotice(it.message?:"Could not save like")})
                                }
                                "Remove from queue" -> playback.removeQueueItem(state.index)
                                "Not interested" -> scope.launch {markNotInterested(context,provider,playback,NotInterestedEntry(videoId,state.title,state.artist,state.artworkUrl),null,onNotice)}
                                "View artist" -> onArtist()
                                "View album" -> onAlbum()
                                "Play next", "Add to queue" -> scope.launch {
                                    provider.resolve(videoId,state.live).fold(onSuccess={playback.enqueueProvider(providerTrack(),it,option=="Play next");onNotice("Added to queue")},onFailure={onNotice(it.message?:"Could not add to queue")})
                                }
                                "Save on this phone" -> if (collectionsCanSave(collections)) addToLibrary()
                                    else onNotice(saveUnavailableNotice(provider.accountScope()))
                                "Add to playlist" -> playlistTrack=providerTrack()
                                "Sleep timer" -> sleepTimerVisible=true
                                "Library shortcuts" -> onLibrary()
                                "Share song" -> share("${state.title} \u00B7 ${state.artist}\nhttps://music.youtube.com/watch?v=${providerTrack().videoId}")
                                "View credits" -> creditsVisible = true
                            }
                        })
                    }
                }
            }
        }
        SeekControl(playback, state.mediaId, state.durationMs)
        Row(Modifier.fillMaxWidth().graphicsLayer { alpha = transportAlpha() },
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Icons.Rounded.Shuffle,
                "Shuffle ${if (state.shuffle) "on" else "off"}", playback::toggleShuffle,
                modifier = Modifier.testTag("playback_shuffle"), active = state.shuffle,
                enabled = hasQueue, diameter = 42.dp, iconSize = 22.dp)
            TransportButton(Icons.Rounded.FastRewind, "Previous track", playback::previous,
                modifier = Modifier.testTag("playback_previous"), enabled = hasQueue,
                diameter = 54.dp, iconSize = 38.dp)
            TransportButton(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.playing) "Pause" else "Play", playback::toggle,
                modifier = Modifier.testTag("playback_toggle")
                    .graphicsLayer { alpha = sharedContentAlpha() }
                    .onGloballyPositioned {
                        onMorphElementBounds("play", it) },
                diameter = 64.dp, iconSize = 52.dp)
            TransportButton(Icons.Rounded.FastForward, "Next track", playback::next,
                modifier = Modifier.testTag("playback_next")
                    .graphicsLayer { alpha = sharedContentAlpha() }
                    .onGloballyPositioned {
                        onMorphElementBounds("next", it) }, enabled = hasQueue,
                diameter = 54.dp, iconSize = 38.dp)
            TransportButton(if (state.repeat == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Repeat ${when (state.repeat) { 1 -> "one"; 2 -> "all"; else -> "off" }}",
                playback::cycleRepeat, modifier = Modifier.testTag("playback_repeat"),
                active = state.repeat != 0, diameter = 42.dp, iconSize = 22.dp)
        }
        AudioOutputControls(onLyrics = onLyrics, onQueue = onQueue)
        if(state.sleepDeadlineMs>0) SleepTimerCountdown(state.sleepDeadlineMs) {uiHaptic();sleepTimerVisible=true}
        if(state.source=="YouTube Music" && !state.live) TextButton(enabled=!videoBusy,onClick={ uiHaptic();videoBusy=true;scope.launch {
            // The entry, so that a second copy of the same song is not mistaken for this one.
            val requested=state.playback.queueItemId to state.mediaId
            try {
                if(state.video) {playback.restoreAudio(providerTrack(),playback.currentPositionMs());return@launch}
                val lookup=kotlinx.coroutines.withTimeoutOrNull(25_000) {provider.videoFor(providerTrack())}
                if(lookup==null || lookup.isFailure) {onNotice("Could not check official videos. Check your connection and retry.");return@launch}
                val target=lookup.getOrNull()
                if(playback.state.let {it.playback.queueItemId to it.mediaId}!=requested) return@launch
                if(target==null) {onNotice("No official music video is available");return@launch}
                val result=kotlinx.coroutines.withTimeoutOrNull(35_000) {provider.resolve(target,video=true)}
                if(playback.state.let {it.playback.queueItemId to it.mediaId}!=requested) return@launch
                if(result==null) onNotice("Official video unavailable. Try again later.") else result.fold(onSuccess={stream->
                    if(!pairedVideoTitleAllowed(providerTrack(),stream.resolvedTitle) || stream.videoUrl==null && stream.sabrVideoBootstrap==null && stream.mimeType?.contains("mpegurl",true)!=true) onNotice("No official music video is available")
                    else playback.replaceProvider(providerTrack(),stream,playback.currentPositionMs())
                },onFailure={onNotice("Official video unavailable. Try again later.")})
            } catch(e:kotlinx.coroutines.CancellationException) {throw e}
            finally {videoBusy=false}
        }},modifier=Modifier.testTag("player_video")) {Text(if(videoBusy) "Preparing video\u2026" else if(state.video) "Switch to audio" else "Play video",color=White,fontSize=13.sp)}
        state.error?.let { error ->
            Text(error, color = Pink)
            if (state.source == "YouTube Music") Text("Retry track", color = White,
                modifier = Modifier.clickable(onClick = hapticClick(onRetry)).padding(vertical = 8.dp)
                    .testTag("playback_retry"))
        }
        }
    }
    if (creditsVisible) AlertDialog(onDismissRequest = { creditsVisible = false },
        title = { Text("Credits", color = White) },
        text = {
            Text("${state.title}\n${state.artist}" +
                "\nFull contributor credits are unavailable for this source.",
                color = White)
        },
        confirmButton = { TextButton(onClick = { uiHaptic();  creditsVisible = false }) {
            Text("Done", color = Pink)
        } },
        containerColor = Raised)
}

@Composable
internal fun QueuePanel(playback: PlaybackGateway, onBack: () -> Unit) {
    val motion = LocalMotionPolicy.current
    val state = playback.state
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)
        .preferredFrameRate(LocalUiRefreshRate.current).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticClick(onBack), modifier = Modifier.testTag("queue_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to player", tint = White)
            }
            Text("Up next", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text(state.queueMessage.ifBlank { if(state.queue.size>1) "Tap to play. Reorder with the arrows." else "${state.queue.size} song in queue." }, color = Muted, fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)
            .testTag("queue_list"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(state.queue, key = { index,item -> "${item.mediaId}:$index" }) { index, item ->
                Row(Modifier.fillMaxWidth().height(72.dp).clickable { uiHaptic();  playback.seekQueueItem(index) }.then(if (motion.reduced) Modifier
                    else Modifier.animateItem())
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (index == state.index) White.copy(alpha = .1f)
                        else Color.Transparent), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.width(28.dp))
                    AsyncImage(item.artworkUrl,null,contentScale=ContentScale.Crop,modifier=Modifier.size(42.dp).clip(RoundedCornerShape(9.dp)).padding(end=6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playerArtistLabel(item.artist), color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    if (state.queue.size > 1) {
                        IconButton(onClick = { uiHaptic();  playback.moveQueueItem(index, index - 1) },
                            enabled = index > 0, modifier = Modifier.size(42.dp)
                                .testTag("queue_up_$index")) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move ${item.title} up",
                                tint = White)
                        }
                        IconButton(onClick = { uiHaptic();  playback.moveQueueItem(index, index + 1) },
                            enabled = index < state.queue.lastIndex, modifier = Modifier.size(42.dp)
                                .testTag("queue_down_$index")) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move ${item.title} down",
                                tint = White)
                        }
                        IconButton(onClick = { uiHaptic();  playback.removeQueueItem(index) },
                            enabled = state.queue.size > 1, modifier = Modifier.size(42.dp)
                                .testTag("queue_remove_$index")) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${item.title}",
                                tint = Muted)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp))
    }
}


private fun collectionsCanSave(collections: LocalCollections) = collections.canSave
