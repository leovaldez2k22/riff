package com.musicglass.personal

import kotlin.math.pow
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Feature 34.2: the player's lower half is built from the cover itself, so it always matches the sleeve. A 6 × 6
 * mesh of the cover's colours (the row at the seam kept, the rest flipped and turned sideways so it never reads as a
 * mirror image), a blurred 128 px copy over it, and a dark gradient for white text. Both are made once per cover on a
 * worker thread and cached; nothing is blurred live.
 */
internal const val BACKDROP_GRID = 6
internal const val BACKDROP_SAMPLE_PX = 128
internal const val BACKDROP_VIBRANCE = 1.12f
internal const val BACKDROP_FLOOR = .045f
/** Where the sharp art ends, as a share of the panel's height. */
internal const val BACKDROP_SEAM = .5f
private const val BACKDROP_BLUR_RADIUS = 6
private const val BACKDROP_BLUR_PASSES = 3
/** The blurred copy's opacity over the mesh. */
private const val BACKDROP_BLUR_ALPHA = .6f
/** How far down the panel below the seam the blurred copy takes to come in fully. */
private const val BACKDROP_BLUR_FADE = .35f
/** A backdrop this light or darker gets no scrim, and from [BACKDROP_SCRIM_FULL] on all the scrim it needs. */
internal const val BACKDROP_SCRIM_FROM = .35f
internal const val BACKDROP_SCRIM_FULL = .6f
internal const val BACKDROP_INK_LIGHT = .10f
/** The player's own darkening on a white cover is this share of what the queue's needs: the owner found it a little too dark (6 Nov). */
internal const val BACKDROP_DECK_SCRIM_SHARE = .75f
internal const val BACKDROP_WHITE_LIGHT = .70f
internal const val BACKDROP_WHITE_COLOUR = .16f
/** Where the player's scrim starts and where it is whole, as shares of the panel: a long, soft way down to the title. */
private const val BACKDROP_SCRIM_TOP = .26f
private const val BACKDROP_SCRIM_BOTTOM = .54f

/** The average colour of each cell of a [grid] × [grid] split of [pixels] ([width] × [height]), row by row. */
internal fun meshColors(pixels: IntArray, width: Int, height: Int, grid: Int = BACKDROP_GRID): IntArray {
    val out = IntArray(grid * grid)
    for (row in 0 until grid) for (column in 0 until grid) {
        val x0 = column * width / grid; val x1 = ((column + 1) * width / grid).coerceAtLeast(x0 + 1)
        val y0 = row * height / grid; val y1 = ((row + 1) * height / grid).coerceAtLeast(y0 + 1)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0 until y1.coerceAtMost(height)) for (x in x0 until x1.coerceAtMost(width)) {
            val p = pixels[y * width + x]; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
        }
        n = n.coerceAtLeast(1)
        out[row * grid + column] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
    return out
}

/**
 * The deck's layout of the mesh: its first row is the cover's last (against the seam); the rows above that, flipped
 * so the ones nearest the seam come first, are then turned sideways (rows become columns). The cover's own colours,
 * in its own proportions, without a mirrored face or horizon.
 */
internal fun deckMesh(mesh: IntArray, grid: Int = BACKDROP_GRID): IntArray {
    val out = IntArray(grid * grid)
    for (column in 0 until grid) out[column] = mesh[(grid - 1) * grid + column]
    // The cover's rows grid-2 downTo 0, flipped; then turned: deck cell (row, column) takes flipped cell (column, row).
    val rest = grid - 1
    for (row in 1 until grid) for (column in 0 until grid) {
        val flippedRow = column * rest / grid
        val sourceRow = grid - 2 - flippedRow
        val sourceColumn = (row - 1) * grid / rest
        out[row * grid + column] = mesh[sourceRow.coerceIn(0, grid - 1) * grid + sourceColumn.coerceIn(0, grid - 1)]
    }
    return out
}

/** Slightly more saturated, and never quite black, so a near-black cover still has depth. */
internal fun deckTone(argb: Int): Int {
    val r = (argb shr 16 and 0xFF) / 255f; val g = (argb shr 8 and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
    val mean = (r + g + b) / 3f
    fun channel(c: Float) = (mean + (c - mean) * BACKDROP_VIBRANCE).coerceIn(BACKDROP_FLOOR, 1f)
    return (0xFF shl 24) or ((channel(r) * 255).toInt() shl 16) or ((channel(g) * 255).toInt() shl 8) or (channel(b) * 255).toInt()
}

/** Three box-blur passes over a small copy, horizontally then vertically each time. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int = BACKDROP_BLUR_RADIUS, passes: Int = BACKDROP_BLUR_PASSES): IntArray {
    var source = pixels.copyOf(); var target = IntArray(pixels.size)
    fun pass(horizontal: Boolean) {
        val lines = if (horizontal) height else width; val length = if (horizontal) width else height
        for (line in 0 until lines) for (i in 0 until length) {
            var r = 0; var g = 0; var b = 0; var n = 0
            for (k in (i - radius).coerceAtLeast(0)..(i + radius).coerceAtMost(length - 1)) {
                val p = source[if (horizontal) line * width + k else k * width + line]
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
            }
            target[if (horizontal) line * width + i else i * width + line] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        val swap = source; source = target; target = swap
    }
    repeat(passes) { pass(true); pass(false) }
    return source
}

/** [deckScrim] darkens from the title down, [panelScrim] the whole panel behind the open queue. */
internal class PlayerBackdropImages(val mesh: ImageBitmap, val blurred: ImageBitmap, val deckScrim: Float = 0f, val panelScrim: Float = 0f)

/** How much light a colour sends, 0 to 1 (WCAG's relative luminance). */
internal fun backdropLight(argb: Int): Float {
    fun linear(c: Int) = (c / 255f).let { if (it <= .04045f) it / 12.92f else ((it + .055f) / 1.055f).pow(2.4f) }
    return .2126f * linear(argb shr 16 and 0xFF) + .7152f * linear(argb shr 8 and 0xFF) + .0722f * linear(argb and 0xFF)
}

/**
 * How dark a layer white words need over a backdrop this light: nothing on an ordinary cover, and on a white one enough
 * to bring it down to [BACKDROP_INK_LIGHT], where the faint artist name and buttons read too (5 Nov, the owner's choice
 * over a shadow). The ramp keeps a cover just past the start from jumping to dark.
 */
internal fun backdropScrimAlpha(light: Float): Float {
    if (light <= BACKDROP_SCRIM_FROM) return 0f
    val needed = 1f - (BACKDROP_INK_LIGHT / light).coerceAtMost(1f).pow(1f / 2.2f)
    val t = ((light - BACKDROP_SCRIM_FROM) / (BACKDROP_SCRIM_FULL - BACKDROP_SCRIM_FROM)).coerceIn(0f, 1f)
    return needed * t * t * (3f - 2f * t)
}

/**
 * The colour behind the title counts as white or off-white when it is both very light and nearly without colour. BRAT's
 * green and ordinary light covers are not, and keep the player as it was (owner, 5 Nov: off-white is included to be safe).
 */
internal fun backdropIsWhite(cells: List<Int>): Boolean {
    if (cells.isEmpty()) return false
    val r = cells.sumOf { it shr 16 and 0xFF } / cells.size; val g = cells.sumOf { it shr 8 and 0xFF } / cells.size; val b = cells.sumOf { it and 0xFF } / cells.size
    val top = maxOf(r, g, b); val bottom = minOf(r, g, b)
    return backdropLight((r shl 16) or (g shl 8) or b) >= BACKDROP_WHITE_LIGHT && top > 0 && (top - bottom).toFloat() / top <= BACKDROP_WHITE_COLOUR
}

/** The scrims for a [grid] × [grid] mesh: the player's only on a white lower half, the queue's from its lightest cell. */
internal fun backdropScrims(deck: IntArray, grid: Int = BACKDROP_GRID): Pair<Float, Float> {
    val lower = deck.drop(grid * (grid / 2))
    return (if (backdropIsWhite(lower)) backdropScrimAlpha(lower.maxOf(::backdropLight)) * BACKDROP_DECK_SCRIM_SHARE else 0f) to backdropScrimAlpha(deck.maxOf(::backdropLight))
}

private val backdropCache = android.util.LruCache<String, PlayerBackdropImages>(12)

private fun backdropImages(bitmap: Bitmap): PlayerBackdropImages {
    val small = Bitmap.createScaledBitmap(bitmap, BACKDROP_SAMPLE_PX, BACKDROP_SAMPLE_PX, true)
    val pixels = IntArray(BACKDROP_SAMPLE_PX * BACKDROP_SAMPLE_PX)
    small.getPixels(pixels, 0, BACKDROP_SAMPLE_PX, 0, 0, BACKDROP_SAMPLE_PX, BACKDROP_SAMPLE_PX)
    val deck = deckMesh(meshColors(pixels, BACKDROP_SAMPLE_PX, BACKDROP_SAMPLE_PX)).map(::deckTone).toIntArray()
    val mesh = Bitmap.createBitmap(deck, BACKDROP_GRID, BACKDROP_GRID, Bitmap.Config.ARGB_8888)
    val blurred = Bitmap.createBitmap(boxBlur(pixels, BACKDROP_SAMPLE_PX, BACKDROP_SAMPLE_PX), BACKDROP_SAMPLE_PX, BACKDROP_SAMPLE_PX, Bitmap.Config.ARGB_8888)
    val (deckScrim, panelScrim) = backdropScrims(deck)
    return PlayerBackdropImages(mesh.asImageBitmap(), blurred.asImageBitmap(), deckScrim, panelScrim)
}

/** The cover's backdrop, made once and cached by address; null until it is ready or when the cover can't load. */
@Composable
internal fun rememberPlayerBackdrop(url: String?): PlayerBackdropImages? {
    val context = LocalContext.current
    val images by produceState(url?.let(backdropCache::get), url) {
        // A new song keeps the producer's old value (the last song's images) until its own are ready, so the old cover
        // cross-fades into the new one. Checking that old value instead of the cache left the bottom of the player on
        // the first cover (phone checks 29 Oct, bug 2).
        url?.let(backdropCache::get)?.let { value = it; return@produceState }
        if (url.isNullOrBlank()) { value = null; return@produceState }
        val request = ImageRequest.Builder(context).data(url).size(BACKDROP_SAMPLE_PX * 2).allowHardware(false).build()
        val bitmap = suspendCatching { SingletonImageLoader.get(context).execute(request).image?.toBitmap() }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { backdropImages(bitmap) }.getOrNull() }?.also { backdropCache.put(url, it) }
    }
    return images
}

/**
 * The player's background: [fallback] (the cover's tone) until the backdrop is ready, then the mesh, the blurred
 * cover and the legibility gradient, cross-faded when the song changes. It also paints itself into [glass] for the
 * player's glass to read. Not through [riffBackdropSource], and recorded with the standalone [GraphicsLayer.record]:
 * recorded from inside its own drawing, the canvas stopped redrawing, stayed on its first frame (the flat tone), and the
 * art faded into nothing.
 */
@Composable
internal fun PlayerBackdrop(url: String?, fallback: Color, modifier: Modifier = Modifier, glass: RiffBackdrop? = null, queueShown: () -> Float = { 0f }) {
    val images = rememberPlayerBackdrop(url)
    val motion = LocalMotionPolicy.current
    var shown by remember { mutableStateOf(images) }
    var leaving by remember { mutableStateOf<PlayerBackdropImages?>(null) }
    val arrival = remember { Animatable(1f) }
    LaunchedEffect(images) {
        if (images === shown) return@LaunchedEffect
        leaving = shown; shown = images
        arrival.snapTo(0f)
        arrival.animateTo(1f, GlassMotion.timed(GlassMotion.ambientMs, motion))
        leaving = null
    }
    Canvas(modifier.then(if (glass == null) Modifier else Modifier.onGloballyPositioned { glass.origin = it.positionInRoot() })) {
        val progress = arrival.value
        val incoming = shown
        val outgoing = leaving
        val queue = queueShown()
        val paint: DrawScope.() -> Unit = {
            drawRect(fallback.readableArtworkTone())
            // The new cover fades in over the old one; with no new cover the old one fades out.
            outgoing?.let { drawPlayerBackdrop(it, if (incoming == null) 1f - progress else 1f, queue) }
            incoming?.let { drawPlayerBackdrop(it, progress, queue) }
        }
        paint()
        if (glass != null && glass.readers > 0) glass.layer.record(this, layoutDirection, size.toIntSize()) { paint() }
    }
}

private fun DrawScope.drawPlayerBackdrop(images: PlayerBackdropImages, alpha: Float, queue: Float) {
    if (alpha <= 0f) return
    val seam = size.height * BACKDROP_SEAM
    val deck = IntSize(size.width.toInt(), (size.height - seam).toInt().coerceAtLeast(1))
    // The mesh fills the whole panel, so the art's faded lower edge melts into it.
    drawImage(images.mesh, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = alpha, filterQuality = FilterQuality.Low)
    // The blurred cover comes in gradually below the seam, so its top edge never draws a line across the page.
    drawContext.canvas.saveLayer(Rect(0f, seam, size.width, size.height), Paint())
    drawImage(images.blurred, dstOffset = IntOffset(0, seam.toInt()), dstSize = deck, alpha = BACKDROP_BLUR_ALPHA * alpha,
        filterQuality = FilterQuality.Low)
    drawRect(Brush.verticalGradient(0f to Color.Transparent, BACKDROP_BLUR_FADE to Color.Black, startY = seam, endY = size.height),
        topLeft = Offset(0f, seam), blendMode = BlendMode.DstIn)
    drawContext.canvas.restore()
    drawRect(Brush.verticalGradient(0f to Color.Transparent, .28f to Color.Black.copy(alpha = .4f * alpha), 1f to Color.Black.copy(alpha = .4f * alpha),
        startY = seam, endY = size.height), topLeft = Offset(0f, seam))
    // Under the art, which stays as it is: only what it fades into gets darker.
    val deckScrim = images.deckScrim * (1f - queue) * alpha
    if (deckScrim > 0f) {
        val top = size.height * BACKDROP_SCRIM_TOP
        fun dark(share: Float) = Color.Black.copy(alpha = deckScrim * share)
        // Eased at both ends, so no edge shows where it starts or finishes.
        drawRect(Brush.verticalGradient(0f to Color.Transparent, .2f to dark(.07f), .4f to dark(.28f), .6f to dark(.58f), .8f to dark(.86f), 1f to dark(1f),
            startY = top, endY = size.height * BACKDROP_SCRIM_BOTTOM), topLeft = Offset(0f, top))
    }
    val panelScrim = images.panelScrim * queue * alpha
    if (panelScrim > 0f) drawRect(Color.Black.copy(alpha = panelScrim))
}
