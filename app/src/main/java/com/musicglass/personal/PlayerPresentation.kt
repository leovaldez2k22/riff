package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Where the player is between the mini bar and the full screen, and the two drags that move it: up from the
 * mini bar and down from the full player. Screen-only state, owned by the composition that shows the player.
 * The service knows nothing of it.
 */
internal class PlayerPresentationState(val morph: PlayerMorphController = PlayerMorphController(), private val haptic: (HapticCue) -> Unit = { uiHaptic(it) }) {
    var draggingMini by mutableStateOf(false)
        private set
    var draggingFull by mutableStateOf(false)
        private set
    /** Set when a release has already started the settle that the sheet change would otherwise start. */
    private var pendingGestureTarget: Boolean? = null
    private val miniDrag = MorphDragTracker()
    private val fullDrag = MorphDragTracker()
    private fun range(areaHeight: Int) = areaHeight.coerceAtLeast(1) * .55f

    /** The app went to the background: any drag ends and the player rests where the sheet says, without animation. */
    fun stopped(open: Boolean) {
        miniDrag.reset(); fullDrag.reset(); draggingMini = false; draggingFull = false
        morph.settle(open, MotionPolicy(true, 0f))
    }
    /** The sheet opened or closed for a reason other than a release handled here. */
    fun sheetChanged(open: Boolean, motion: MotionPolicy) {
        if (pendingGestureTarget == open) pendingGestureTarget = null else morph.settle(open, motion)
    }
    fun dragMini(delta: Float, time: Long, areaHeight: Int) {
        if (!miniDrag.active) { miniDrag.target = morph.position.value; miniDrag.active = true; draggingMini = true }
        miniDrag.update(delta, time, range(areaHeight)); morph.dragTo(miniDrag.target)
    }
    fun dragFull(delta: Float, time: Long, areaHeight: Int) {
        if (!fullDrag.active) { fullDrag.target = morph.position.value; fullDrag.active = true; draggingFull = true }
        fullDrag.update(delta, time, range(areaHeight)); morph.dragTo(fullDrag.target)
    }
    /** Ends a drag from the mini bar. [open] is called, before the settle starts, when the player is to end open. */
    fun releaseMini(sheetOpen: Boolean, motion: MotionPolicy, areaHeight: Int, open: () -> Unit) {
        val target = miniDrag.target; val velocity = miniDrag.velocity
        miniDrag.reset(); draggingMini = false
        settle(target, velocity, sheetOpen, motion, areaHeight) { opening -> if (opening) open() }
    }
    /** Ends a drag from the full player; a real fling's own velocity is used. [close] is called when the player is to end closed. */
    fun releaseFull(flingVelocity: Float, sheetOpen: Boolean, motion: MotionPolicy, areaHeight: Int, close: () -> Unit) {
        val velocity = if (kotlin.math.abs(flingVelocity) > 650f) flingVelocity else fullDrag.velocity
        val target = fullDrag.target
        fullDrag.reset(); draggingFull = false
        settle(target, velocity, sheetOpen, motion, areaHeight) { opening -> if (!opening) close() }
    }
    private fun settle(target: Float, velocity: Float, sheetOpen: Boolean, motion: MotionPolicy, areaHeight: Int, sheet: (Boolean) -> Unit) {
        val opening = playerReleaseOpens(target, velocity)
        if (sheetOpen != opening) { haptic(HapticCue.DragEnd); pendingGestureTarget = opening }
        sheet(opening)
        morph.settle(opening, motion, direct = true, fromDrag = target, releaseVelocity = velocity / range(areaHeight))
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

@Composable
internal fun MiniPlayerContents(playback: PlaybackGateway, onOpen: () -> Unit,
    actionsAlpha: Float = 1f, artworkAlpha: Float = 1f,
    onElementBounds: (String, Rect) -> Unit = { _, _ -> }) {
    val state = playback.state
    val songTransition=rememberSongPresentationTransition(state)
    val context = LocalContext.current
    // W1.5: after a crash the queue comes back paused; say why once, until the user plays.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CrashNotice.load(context) } }
    LaunchedEffect(state.playing) { if (state.playing) withContext(Dispatchers.IO) { CrashNotice.clear(context) } }
    val crashNotice = CrashNotice.pending
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        songTransition.SongContentFade(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
            .onGloballyPositioned { onElementBounds("art", it.unclippedBoundsInRoot()) }) {song->
        AsyncImage(model = song.artworkUrl
            ?: providerArtworkUrl(song.mediaId.removePrefix("yt:")),
            contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { alpha = artworkAlpha }
                .background(Brush.linearGradient(
                    listOf(Color(0xFF655080), Color(0xFF315B75)))))
        }
        songTransition.SongContentFade(Modifier.weight(1f).clickable(onClick = hapticClick(onOpen))
            .padding(start = 12.dp).semantics { contentDescription = string(R.string.open_player_x, state.title) }) {
            song->Column {
            Text(song.title, color = White, fontSize = miniTitleSize(LocalDensity.current.fontScale),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.onGloballyPositioned {
                    if(song.mediaId==state.mediaId) onElementBounds("title", it.unclippedBoundsInRoot()) })
            if (crashNotice)
                // The mini player fits about 20 characters here (checked on a 1080 px phone); the
                // full sentence goes to TalkBack.
                Text(string(R.string.stopped_unexpectedly), color = Pink,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("mini_crash_notice")
                        .semantics { liveRegion = LiveRegionMode.Polite
                            contentDescription = string(R.string.playback_stopped_unexpectedly_tap_play_to_co) })
            else if (LocalDensity.current.fontScale < 1.8f)
                Text(playerArtistLabel(song.artist), color = Muted, fontSize = MINI_ARTIST_SIZE,
                    maxLines = 1, modifier = Modifier.onGloballyPositioned {
                        if(song.mediaId==state.mediaId) onElementBounds("artist", it.unclippedBoundsInRoot()) })
            }
        }
        if (crashNotice)
            IconButton(onClick = hapticClick { CrashNotice.clear(context) },
                modifier = Modifier.size(48.dp).testTag("mini_crash_notice_dismiss")) {
                Icon(Icons.Rounded.Close, contentDescription = string(R.string.dismiss_notice), tint = Muted)
            }
        IconButton(onClick = hapticClick(playback::toggle),
            modifier = Modifier.size(48.dp).graphicsLayer { alpha = actionsAlpha }
                .testTag("mini_play")) {
            Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.playing) string(R.string.pause) else string(R.string.play), tint = White,
                modifier = Modifier.onGloballyPositioned {
                    onElementBounds("play", it.unclippedBoundsInRoot()) })
        }
        IconButton(onClick = hapticClick(playback::next), enabled = state.queue.size > 1,
            modifier = Modifier.size(48.dp).graphicsLayer { alpha = actionsAlpha }
                .testTag("mini_next")) {
            Icon(Icons.Rounded.FastForward, contentDescription = string(R.string.next_track), tint = White,
                modifier = Modifier.onGloballyPositioned {
                    onElementBounds("next", it.unclippedBoundsInRoot()) })
        }
    }
}

/** Progress after which the flying morph elements hand over to the real player controls. */
private const val morphHandoffStart = .96f

/**
 * The mini-player end of the morph: over p 0 to this the flying copies fade in over the real mini player, and
 * nothing moves. They used to travel from the first moment, while the mini player faded out where it was, and the
 * two showed a few dp apart: two titles, a faint second cover (Q18-001).
 */
internal const val MORPH_MINI_HANDOFF_END = .02f

/** How far the surface has grown from the mini player at [p]: not at all until the mini player has handed over. */
internal fun morphGeometry(p: Float): Float = ((p - MORPH_MINI_HANDOFF_END) / (1f - MORPH_MINI_HANDOFF_END)).coerceIn(0f, 1f)

/** How far the flying copies have travelled at [p]: from the mini player's hand-over to the player's. */
internal fun morphTravel(p: Float): Float = ((p - MORPH_MINI_HANDOFF_END) / (morphHandoffStart - MORPH_MINI_HANDOFF_END)).coerceIn(0f, 1f)

/** Above this the player is drawn at all (the morph host is composed). */
internal const val MORPH_SHOWN = .001f

/**
 * How much of each flying copy shows at [p]; the real element shows the rest ([morphHandoff]). Q18-055: at the mini
 * player's end they swap in one frame, never fade: the copies are drawn exactly like the mini player, and two
 * identical layers fading over each other still thicken every edge.
 */
internal fun morphCopyAlpha(p: Float): Float = (if (p > MORPH_SHOWN) 1f else 0f) * (1f - morphHandoff(p))

/** How much of the real mini player shows at [p]: all of it at rest, none once its copies are drawn. */
internal fun miniPlayerAlpha(p: Float): Float = if (p > MORPH_SHOWN) 0f else 1f

/** The mini player's title and artist sizes; the morph's copies start in exactly these (Q18-055). */
internal fun miniTitleSize(fontScale: Float) = if (fontScale >= 1.6f) 12.sp else 14.sp
internal val MINI_ARTIST_SIZE = 11.sp

/**
 * Q18-055 (Q18-001 again): how far the flying title and artist have turned from the mini player's look into the
 * player's, at travel [t]. They start as exact copies of the mini player's text (its size, weight and width), so
 * over the hand-over they lie on it pixel for pixel; the player's larger bold look takes over once they are moving.
 */
internal fun morphRestyle(t: Float): Float = motionRamp(t, .1f, .45f)

/** How much of the real title, artwork and play buttons shows at [p]. */
internal fun morphHandoff(p: Float): Float = motionRamp(p, morphHandoffStart, 1f)

/** One surface occupies the mini-player bounds and animates into the full player bounds. */
@Composable
internal fun PlayerMorphHost(progress: () -> Float, model: ShellModel, compact: Boolean,
    playback: PlaybackGateway, collections: LocalCollections, bottomInset: Float,
    miniBounds: Rect?, miniElementBounds: Map<String, Rect>, fullDragging: Boolean, provider: YouTubeMusicProvider,
    onFailureAction: (FailureAction) -> Unit, onMorphDrag: (Float, Long) -> Unit,
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
        val g = morphGeometry(p)
        val x = miniX * (1f - g)
        val y = miniY + (fullY - miniY) * g
        val w = miniWidth + (maxWidth - miniWidth) * g
        val h = miniHeight + (maxHeight - fullY - miniHeight) * g
        val movingAlpha = morphCopyAlpha(p)
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
        val shape = RoundedCornerShape(32.dp * (1f - g))
        Box(Modifier.offset(x = x, y = y).width(w).height(h)
            .clip(shape)
            .background(if (solidControls) Color(0xFF11131A) else Color.Black.copy(alpha = p))
            // As deep as the mini player's own border, which it replaces in the same frame (Q18-055).
            .border(1.dp, White.copy(alpha = (.42f * (1f - p) + .10f * p) * (1f - p)), shape)) {
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
                        onClose = { PerfTrace.action("player_close"); model.sheet = "" },
                        onSwipeDrag = onMorphDrag,
                        onSwipeRelease = onMorphRelease,
                        onQueue = { PerfTrace.action(if(model.sheet=="queue") "queue_close" else "queue_open"); model.sheet = if(model.sheet=="queue") "mini" else "queue" },
                        queueOpen=model.sheet=="queue",onQueueClose={PerfTrace.action("queue_close");model.sheet="mini"},
                        onLibrary = { model.sheet = "libraryShortcuts" },
                        onLyrics = { model.sheet = "lyrics" },onArtist=onArtist,onAlbum=onAlbum,
                        onFailureAction = onFailureAction,
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
                // The flying copies arrive before the hand-over starts and then fade in place. They used to
                // travel until the very end, so during the hand-over each copy and the real element it hands
                // to were both visible a few dp apart: two titles, two play buttons (seen on a phone).
                val t = morphTravel(p)
                val restyle = morphRestyle(t)
                val (miniArtX, miniArtY) = miniPosition("art", 12.dp, 11.dp)
                val artX = miniArtX * (1f - t)
                val artY = miniArtY * (1f - t)
                val heroHeight = fullHeight / 2f
                val artSize = 40.dp + (fullWidth - 40.dp) * t
                val artHeight = 40.dp + (heroHeight - 40.dp) * t
                val state = playback.state
                val artUrl = state.artworkUrl ?: providerArtworkUrl(state.mediaId.removePrefix("yt:"))
                // Q18-055: the mini player's cover as it draws it, then the player's, as for the title below.
                Box(Modifier.offset(x = artX, y = artY).width(artSize).height(artHeight)
                    .clip(RoundedCornerShape(10.dp * (1f - t)))
                    .graphicsLayer { alpha = movingAlpha * (1f - restyle) }) {
                    AsyncImage(model = artUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.offset(x = artX, y = artY).width(artSize).height(artHeight)
                    .clip(RoundedCornerShape(10.dp * (1f - t)))
                    .graphicsLayer {
                        alpha = movingAlpha * restyle
                    }) {
                    PlayerArtwork(artUrl, Modifier.fillMaxSize(), blend = t)
                }

                val (miniTitleX, miniTitleY) = miniPosition("title", 64.dp, 10.dp)
                val (fullTitleX, fullTitleY) = fullPosition("title", 0.dp,
                    20.dp, heroHeight + 8.dp)
                val (miniArtistX, miniArtistY) = miniPosition("artist", 64.dp, 31.dp)
                val (fullArtistX, fullArtistY) = fullPosition("artist", 0.dp,
                    20.dp, heroHeight + 38.dp)
                val titleBaseY = miniTitleY + (fullTitleY - miniTitleY) * t
                val artistBaseY = miniArtistY + (fullArtistY - miniArtistY) * t
                val artworkClearance = (artHeight + 6.dp - titleBaseY).coerceAtLeast(0.dp) *
                    ((t - .05f) / .15f).coerceIn(0f, 1f)
                val fullTextWidth = { name: String -> fullElementBounds[name]?.let { with(measure) { it.width.toDp() } }
                    ?: (fullWidth - 120.dp) }
                val miniTextWidth = { name: String, fallback: androidx.compose.ui.unit.Dp ->
                    miniElementBounds[name]?.let { with(measure) { it.width.toDp() } } ?: fallback }
                val miniTitle = miniTitleSize(measure.fontScale)
                // The mini player's own title and artist, drawn as it draws them, growing towards the player's size.
                Text(state.title, color = White, fontSize = miniTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = miniTitleX + (fullTitleX - miniTitleX) * t,
                        y = titleBaseY + artworkClearance)
                        .width(miniTextWidth("title", miniWidth - 160.dp)).graphicsLayer {
                            alpha = movingAlpha * (1f - restyle)
                            scaleX = 1f + (22f / miniTitle.value - 1f) * t; scaleY = scaleX
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }.testTag("morph_mini_title"))
                if (measure.fontScale < 1.8f) Text(playerArtistLabel(state.artist), color = Muted,
                    fontSize = MINI_ARTIST_SIZE, maxLines = 1,
                    modifier = Modifier.offset(
                        x = miniArtistX + (fullArtistX - miniArtistX) * t,
                        y = artistBaseY + artworkClearance)
                        .width(miniTextWidth("artist", miniWidth - 160.dp)).graphicsLayer {
                            alpha = movingAlpha * (1f - restyle)
                            scaleX = 1f + (13f / MINI_ARTIST_SIZE.value - 1f) * t; scaleY = scaleX
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        })
                // The player's title and artist, shrunk to the mini player's size, take over once moving.
                Text(state.title, color = White,
                    fontSize = 22.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = miniTitleX + (fullTitleX - miniTitleX) * t,
                        y = titleBaseY + artworkClearance)
                        .width(fullTextWidth("title")).graphicsLayer {
                            alpha = movingAlpha * restyle
                            val start=miniTitle.value/22f
                            scaleX=start+(1f-start)*t;scaleY=scaleX
                            transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
                        })
                Text(playerArtistLabel(state.artist), color = Muted,
                    fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.offset(
                        x = miniArtistX + (fullArtistX - miniArtistX) * t,
                        y = artistBaseY + artworkClearance)
                        .width(fullTextWidth("artist")).graphicsLayer {
                            scaleX=11f/13f+(2f/13f)*t;scaleY=scaleX
                            transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0f,0f)
                            alpha = if (measure.fontScale >= 1.8f && !miniElementBounds.containsKey("artist"))
                                movingAlpha * t else movingAlpha * restyle
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
                        x = miniPlayX + (fullPlayX - miniPlayX) * t,
                        y = miniPlayY + (fullPlayY - miniPlayY) * t)
                        .size(24.dp + 28.dp * t)
                        .graphicsLayer { alpha = movingAlpha })
                Icon(Icons.Rounded.FastForward, contentDescription = null, tint = White,
                    modifier = Modifier.offset(
                        x = miniNextX + (fullNextX - miniNextX) * t,
                        y = miniNextY + (fullNextY - miniNextY) * t)
                        .size(24.dp + 14.dp * t)
                        .graphicsLayer {
                            alpha = movingAlpha * if (state.queue.size > 1) 1f else .36f
                        })
            }
        }
    }
}
