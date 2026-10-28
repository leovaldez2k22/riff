package com.musicglass.personal

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * Feature 33: the status bar's clock and icons turn dark over a light picture and back to white over Riff's dark
 * screens. Riff's own controls over the same picture (close, "NOW PLAYING") follow the same decision through
 * [LocalLightPicture]. Screens inside the shell (album, artist and playlist headers, Home's photo) sit under Feature
 * 11's top fade, which darkens the strip under the status bar enough for white icons on any picture, so the
 * full-screen player, drawn over that fade, is the one place that asks.
 */

/** Above this luminance black contrasts better than white (the WCAG crossover is 0.179); below [LIGHT_ICONS_BELOW] white again. */
internal const val DARK_ICONS_ABOVE = .21f
internal const val LIGHT_ICONS_BELOW = .15f
/** A scroll or a crossfade passing the threshold does not flash the icons. */
internal const val STATUS_BAR_SETTLE_MS = 120L
/** The strip sampled is this many pixels of a small copy of the picture. */
internal const val STATUS_STRIP_SAMPLE_W = 32
internal const val STATUS_STRIP_SAMPLE_H = 6

/** sRGB to linear, then the relative luminance WCAG uses. */
internal fun relativeLuminance(argb: Int): Float {
    fun channel(c: Int): Float { val s = c / 255f; return if (s <= .04045f) s / 12.92f else ((s + .055f) / 1.055f).pow(2.4f) }
    return .2126f * channel((argb shr 16) and 255) + .7152f * channel((argb shr 8) and 255) + .0722f * channel(argb and 255)
}

/** The 75th percentile of the strip's luminance, so a bright sky over a dark band counts as bright. */
internal fun stripLuminance(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0f
    val sorted = pixels.map(::relativeLuminance).sorted()
    return sorted[((sorted.size - 1) * .75f).toInt()]
}

/**
 * With a scrim of [scrimAlpha] black drawn over the picture, how light the strip still is. Android blends in
 * sRGB, so the scrim scales the encoded value and the luminance falls with its 2.2 power.
 */
internal fun underScrim(luminance: Float, scrimAlpha: Float): Float = luminance * (1f - scrimAlpha.coerceIn(0f, 1f)).pow(2.2f)

/** Dark icons above the upper threshold, light below the lower, unchanged between them. */
internal fun statusBarDarkIcons(luminance: Float, darkNow: Boolean): Boolean = when {
    luminance > DARK_ICONS_ABOVE -> true
    luminance < LIGHT_ICONS_BELOW -> false
    else -> darkNow
}

/**
 * The rows of a [imageWidth] × [imageHeight] picture that land under the top [stripPx] of a [viewWidth] ×
 * [viewHeight] box drawn with ContentScale.Crop: the picture is scaled to cover the box and centred.
 */
internal fun croppedTopRows(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float, stripPx: Float): IntRange {
    if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return 0 until 0
    val scale = maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val visibleHeight = viewHeight / scale
    val top = (imageHeight - visibleHeight) / 2f
    val bottom = top + stripPx.coerceAtMost(viewHeight) / scale
    return top.toInt().coerceIn(0, imageHeight - 1)..bottom.toInt().coerceIn(0, imageHeight - 1)
}

internal const val STATUS_PRIORITY_PLAYER = 4
internal const val STATUS_PRIORITY_SHEET = 3
internal const val STATUS_PRIORITY_HEADER = 2

/** One screen's or overlay's wish for the status bar. Higher [priority] wins: player 4, sheets 3, headers 2, Home 1. */
internal data class StatusBarRequest(val lightBackground: Boolean, val priority: Int)

internal class StatusBarRequests {
    private val requests = mutableStateMapOf<String, StatusBarRequest>()
    fun set(key: String, request: StatusBarRequest) { if (requests[key] != request) requests[key] = request }
    fun remove(key: String) { requests.remove(key) }
    /** Whether the winning request is over a light background; false (Riff's dark screens) when nobody asks. */
    val lightBackground: Boolean get() = requests.values.maxByOrNull { it.priority }?.lightBackground ?: false
}

internal val LocalStatusBarRequests = compositionLocalOf { StatusBarRequests() }

/** Whether the picture under Riff's own over-picture controls is light; they then use dark ink. */
internal val LocalLightPicture = compositionLocalOf { false }

/** Over-picture words and icons: white over dark pictures, near-black over light ones. */
internal fun overPictureInk(light: Boolean): Color = if (light) Color(0xE61C1C1E) else White

/** The shadow under over-picture words: deep and dark over a dark picture, soft and light over a light one. */
internal fun overPictureShadow(light: Boolean): androidx.compose.ui.graphics.Shadow =
    if (light) androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = .35f), androidx.compose.ui.geometry.Offset.Zero, blurRadius = 6f)
    else RiffOverPicture.textShadow

/** Asks for the status bar while this composable is shown; removed when it leaves. Null asks for nothing. */
@Composable
internal fun ReportStatusBar(key: String, lightBackground: Boolean?, priority: Int) {
    val requests = LocalStatusBarRequests.current
    DisposableEffect(requests, key) { onDispose { requests.remove(key) } }
    if (lightBackground == null) requests.remove(key) else requests.set(key, StatusBarRequest(lightBackground, priority))
}

/** Applies the winning request to the window, settled for [STATUS_BAR_SETTLE_MS] so passing changes don't flash. */
@Composable
internal fun StatusBarAppearance(requests: StatusBarRequests) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(requests, view) {
        snapshotFlow { requests.lightBackground }.collectLatest { light ->
            delay(STATUS_BAR_SETTLE_MS)
            val window = (view.context as? Activity)?.window ?: return@collectLatest
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = light
        }
    }
}

/**
 * How light the top [stripPx] of [url] is when drawn with ContentScale.Crop in a [viewWidth] × [viewHeight] box,
 * after a scrim of [scrimAlpha]. Read once per picture from a small software copy (Coil caches it); null until known.
 */
@Composable
internal fun rememberTopLuminance(url: String?, viewWidth: Float, viewHeight: Float, stripPx: Float, scrimAlpha: Float = 0f): Float? {
    val context = LocalContext.current
    val luminance by produceState<Float?>(null, url, viewWidth.toInt(), viewHeight.toInt(), stripPx.toInt()) {
        value = null
        if (url.isNullOrBlank() || viewWidth <= 0f || viewHeight <= 0f) return@produceState
        val request = ImageRequest.Builder(context).data(url).size(STATUS_STRIP_SAMPLE_W * 4).allowHardware(false).build()
        val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@produceState
        value = withContext(Dispatchers.Default) { sampleTopStrip(bitmap, viewWidth, viewHeight, stripPx) }
    }
    return luminance?.let { underScrim(it, scrimAlpha) }
}

private fun sampleTopStrip(bitmap: Bitmap, viewWidth: Float, viewHeight: Float, stripPx: Float): Float {
    val rows = croppedTopRows(bitmap.width, bitmap.height, viewWidth, viewHeight, stripPx)
    if (rows.isEmpty()) return 0f
    val scale = maxOf(viewWidth / bitmap.width, viewHeight / bitmap.height)
    val visibleWidth = (viewWidth / scale).toInt().coerceIn(1, bitmap.width)
    val left = (bitmap.width - visibleWidth) / 2
    val strip = Bitmap.createBitmap(bitmap, left, rows.first, visibleWidth, (rows.last - rows.first + 1).coerceAtLeast(1))
    val small = Bitmap.createScaledBitmap(strip, STATUS_STRIP_SAMPLE_W, STATUS_STRIP_SAMPLE_H, true)
    val pixels = IntArray(STATUS_STRIP_SAMPLE_W * STATUS_STRIP_SAMPLE_H)
    small.getPixels(pixels, 0, STATUS_STRIP_SAMPLE_W, 0, 0, STATUS_STRIP_SAMPLE_W, STATUS_STRIP_SAMPLE_H)
    return stripLuminance(pixels)
}

/** Feature 33: keeps a dark/light decision with hysteresis as [luminance] arrives; null until known. */
@Composable
internal fun rememberLightPicture(luminance: Float?): Boolean? {
    val last = remember { BooleanArray(1) }
    val dark = luminance?.let { statusBarDarkIcons(it, last[0]) }
    SideEffect { if (dark != null) last[0] = dark }
    return dark
}
