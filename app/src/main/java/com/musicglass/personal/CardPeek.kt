package com.musicglass.personal

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How much a held card shrinks before its options open, and where it settles while they are open. */
internal const val PEEK_SHRINK = .08f
internal const val PEEK_SETTLED = .4f
/** The dim stays clear for a quick tap and builds through the rest of the hold. */
internal fun peekDimAlpha(progress: Float) = .5f * ((progress - .3f) / .7f).coerceIn(0f, 1f)

/** Below the held card when it fits, otherwise above it, aligned with its leading edge. */
internal fun peekMenuPosition(anchor: IntRect, window: IntSize, panel: IntSize, margin: Int, topInset: Int, bottomInset: Int): IntOffset {
    val minY = topInset + margin
    val maxY = (window.height - bottomInset - margin - panel.height).coerceAtLeast(minY)
    val x = anchor.left.coerceIn(margin, (window.width - panel.width - margin).coerceAtLeast(margin))
    val below = anchor.bottom + margin
    val above = anchor.top - margin - panel.height
    val y = when {
        below <= maxY -> below
        above >= minY -> above
        window.height - anchor.bottom >= anchor.top -> maxY
        else -> minY
    }
    return IntOffset(x, y.coerceIn(minY, maxY))
}

/** One held card at a time: its shrink, the page dim around it and its options menu. */
internal class CardPeekController(private val scope: CoroutineScope) {
    var card by mutableStateOf<ProviderHomeCard?>(null); private set
    var bounds by mutableStateOf(Rect.Zero); private set
    var corner by mutableFloatStateOf(0f); private set
    var menuOpen by mutableStateOf(false); private set
    val shrink = Animatable(0f)
    val dim = Animatable(0f)
    private var opener: (() -> Unit)? = null
    private var job: Job? = null

    fun scaleOf(key: String) = if (card?.key() == key) 1f - PEEK_SHRINK * shrink.value else 1f

    fun press(card: ProviderHomeCard, bounds: Rect, corner: Float, holdMs: Int, reduced: Boolean) {
        if (menuOpen) return
        this.card = card; this.bounds = bounds; this.corner = corner
        animate(reduced, 1f, 1f, holdMs)
    }

    fun release(card: ProviderHomeCard) {
        if (!menuOpen && this.card?.key() == card.key()) settle(false)
    }

    fun open(card: ProviderHomeCard, bounds: Rect, corner: Float, reduced: Boolean, open: () -> Unit) {
        this.card = card; this.bounds = bounds; this.corner = corner; opener = open; menuOpen = true
        job?.cancel()
        job = scope.launch {
            if (reduced) { shrink.snapTo(PEEK_SETTLED); dim.snapTo(1f); return@launch }
            launch { dim.animateTo(1f, tween(120)) }
            shrink.animateTo(PEEK_SETTLED, spring(dampingRatio = .55f, stiffness = 700f))
        }
    }

    fun openCard() { val open = opener; dismiss(); open?.invoke() }

    fun dismiss(reduced: Boolean = false) { menuOpen = false; settle(reduced) }

    private fun settle(reduced: Boolean) { opener = null; animate(reduced, 0f, 0f, 200) }

    private fun animate(reduced: Boolean, toShrink: Float, toDim: Float, ms: Int) {
        job?.cancel()
        job = scope.launch {
            if (reduced) { shrink.snapTo(toShrink); dim.snapTo(toDim) }
            else coroutineScope {
                launch { shrink.animateTo(toShrink, if (toShrink > 0f) tween(ms, easing = FastOutSlowInEasing) else spring(dampingRatio = .7f, stiffness = 500f)) }
                launch { dim.animateTo(toDim, tween(ms, easing = LinearEasing)) }
            }
            if (toShrink == 0f && !menuOpen) card = null
        }
    }
}

internal val LocalCardPeek = staticCompositionLocalOf<CardPeekController?> { null }

/**
 * Tap opens the card. Holding it shrinks the card and dims the page around it, then opens its options.
 * [corner] is the card's corner radius, so the undimmed cut-out matches its shape.
 */
@Composable internal fun Modifier.cardPeek(card: ProviderHomeCard, corner: Dp, onOpen: () -> Unit): Modifier {
    val peek = LocalCardPeek.current
    val source = remember { MutableInteractionSource() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val holdMs = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    val reduced = LocalMotionPolicy.current.reduced
    val current by rememberUpdatedState(card)
    LaunchedEffect(source, peek) {
        if (peek == null) return@LaunchedEffect
        source.interactions.collect { interaction ->
            when (interaction) {
                // Clickable reports a press only after the touch-slop delay, so a scroll never starts the dim.
                is PressInteraction.Press -> peek.press(current, bounds, cornerPx, (holdMs - 60).coerceAtLeast(120), reduced)
                is PressInteraction.Release, is PressInteraction.Cancel -> peek.release(current)
            }
        }
    }
    return this.onGloballyPositioned { bounds = it.boundsInWindow() }
        .graphicsLayer { val scale = peek?.scaleOf(card.key()) ?: 1f; scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(corner))
        .combinedClickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current,
            onLongClickLabel = if (card.track() != null) "Song options" else "Options",
            onLongClick = if (peek == null) null else ({ peek.open(card, bounds, cornerPx, reduced, onOpen) }),
            onClick = { uiHaptic(); onOpen() })
}

/** The page dim with the held card cut out, and the held card's options. Draws above the page, takes no touches. */
@Composable internal fun CardPeekLayer(peek: CardPeekController, model: ShellModel, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlistTrack by remember { mutableStateOf<ProviderTrack?>(null) }
    playlistTrack?.let { PlaylistPicker(it, { playlistTrack = null }, onNotice) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val card = peek.card
    if (card != null) Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }.testTag("card_peek_dim")) {
        val alpha = peekDimAlpha(peek.dim.value)
        if (alpha <= 0f) return@Canvas
        val scale = peek.scaleOf(card.key())
        val b = peek.bounds.translate(-origin)
        val hole = Rect(b.center.x - b.width * scale / 2, b.center.y - b.height * scale / 2,
            b.center.x + b.width * scale / 2, b.center.y + b.height * scale / 2)
        val path = Path().apply { addRoundRect(RoundRect(hole, CornerRadius(peek.corner * scale))) }
        clipPath(path, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = alpha)) }
    }
    fun act(block: suspend () -> Unit) { peek.dismiss(); scope.launch { block() } }
    fun share(text: String) = context.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    PlayerActionMenu(peek.menuOpen, { peek.dismiss() }, peek.bounds.roundToIntRect(), place = ::peekMenuPosition,
        paneTitle = if (card?.track() != null) "Song options" else "Options") {
        val held = card ?: return@PlayerActionMenu
        val track = held.track()
        if (track != null) {
            SongActionItem("Play next") { act { enqueueRealTrack(provider, playback, held, true, onNotice) } }
            SongActionItem("Add to queue") { act { enqueueRealTrack(provider, playback, held, false, onNotice) } }
            if (track.isMusic && !track.isLive) {
                SongActionItem("Add to playlist") { peek.dismiss(); uiHaptic(); playlistTrack = track }
                SongActionItem("Download song") { act { requestSongDownload(context, track, onNotice) } }
            }
            held.artistId?.let { id -> SongActionItem("View artist") { peek.dismiss(); model.openCollection(id, playerArtistLabel(held.subtitle)) } }
            held.albumId?.let { id -> SongActionItem("View album") { peek.dismiss(); model.openCollection(id, held.album.ifBlank { "Album" }) } }
            SongActionItem("Share song") { peek.dismiss(); share("${held.title} · ${held.subtitle}\nhttps://music.youtube.com/watch?v=${track.videoId}") }
        } else {
            SongActionItem("Open") { peek.openCard() }
            held.browseId?.let { id -> SongActionItem("Share") { peek.dismiss(); share("${held.title}\n${collectionLink(id)}") } }
        }
        if (track?.isLive != true && (track != null || held.notInterestedToken != null))
            SongActionItem("Not interested") { act { markNotInterested(context, provider, playback, held.key(), held.notInterestedToken, track != null, onNotice) } }
    }
}

internal fun collectionLink(browseId: String) = when {
    browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
    browseId.startsWith("UC") -> "https://music.youtube.com/channel/$browseId"
    else -> "https://music.youtube.com/browse/$browseId"
}
