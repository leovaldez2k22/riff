package com.musicglass.desktop
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
@Composable internal fun TabLens(frame: () -> TabLensFrame, tabs: RiffBackdrop, page: RiffBackdrop?, barWidth: Float, barHeight: Float, density: Float) {
    val composite = rememberRiffBackdrop()
    val bar = remember { arrayOf(Offset.Zero) }
    // Read above and below the bar: what the lens stands out of it, and the page its rim bends in from outside.
    val spill = barHeight * .3f
    DisposableEffect(tabs) { tabs.readers++; onDispose { tabs.readers-- } }
    Box(Modifier.fillMaxSize().onGloballyPositioned { bar[0] = it.positionInRoot() }.drawWithCache {
        onDrawBehind {
            val f = frame()
            if (f.ghostAlpha <= .002f) return@onDrawBehind
            val origin = Offset(f.ghostX - f.width / 2f, size.height / 2f - f.height / 2f)
            val dimensions = androidx.compose.ui.geometry.Size(f.width, f.height)
            drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha = .9f * f.ghostAlpha), White.copy(alpha = .35f * f.ghostAlpha),
                White.copy(alpha = .7f * f.ghostAlpha)), start = origin, end = origin + Offset(dimensions.width, dimensions.height)),
                origin, dimensions, androidx.compose.ui.geometry.CornerRadius(f.height / 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(density))
        }
    })
    Box(Modifier.layout { measurable, constraints ->
        val f = frame()
        val placeable = measurable.measure(Constraints.fixed(f.width.toInt().coerceAtLeast(1), f.height.toInt().coerceAtLeast(1)))
        // Reported at the bar's size and placed at its corner: a child taller than its slot is centred on it by
        // Compose, which lifted the lens by half of what it stands out, so it grew upward only (phone check 30 Oct).
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
    }.graphicsLayer {
        val f = frame()
        translationX = f.centerX - f.width.toInt() / 2f; translationY = barHeight / 2f - f.height.toInt() / 2f
        alpha = (3f * f.lift).coerceIn(0f, 1f)
    }.drawWithContent {
        // The page and the tabs as one backdrop, a little taller than the bar, from where both are now.
        val origin = bar[0] - Offset(0f, spill)
        composite.origin = origin
        composite.layer.record(IntSize(barWidth.toInt().coerceAtLeast(1), (barHeight + 2 * spill).toInt())) {
            page?.let { translate(it.origin.x - origin.x, it.origin.y - origin.y) { drawLayer(it.layer) } }
            translate(tabs.origin.x - origin.x, tabs.origin.y - origin.y) { drawLayer(tabs.layer) }
        }
        drawContent()
        drawRoundRect(Brush.linearGradient(listOf(White.copy(alpha = .85f), White.copy(alpha = .25f), White.copy(alpha = .6f)),
            end = Offset(size.width, size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.4f * density))
    }.riffGlass(GlassRole.Lens, backdrop = composite))
}


