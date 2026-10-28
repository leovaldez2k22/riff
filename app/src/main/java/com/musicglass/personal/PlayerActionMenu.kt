package com.musicglass.personal

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal fun playerMenuPosition(anchor: IntRect, window: IntSize, panel: IntSize,
    margin: Int, topInset: Int, bottomInset: Int): IntOffset {
    val minY = topInset + margin
    val maxY = (window.height - bottomInset - margin - panel.height).coerceAtLeast(minY)
    val x = (anchor.right - panel.width).coerceIn(margin, (window.width - panel.width - margin).coerceAtLeast(margin))
    val above = anchor.bottom - panel.height
    val y = if (above >= minY) above else anchor.top.coerceIn(minY, maxY)
    return IntOffset(x, y.coerceIn(minY, maxY))
}

/** Reveal a full-size panel from a 40dp rounded footprint, without relaying out its rows. */
private class UnfoldShape(val progress: Float, val anchor: androidx.compose.ui.geometry.Offset) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = progress.coerceIn(0f, 1f)
        val radius = with(density) {20.dp.toPx()}.coerceAtMost(minOf(size.width, size.height) / 2)
        val x = anchor.x.coerceIn(radius, (size.width - radius).coerceAtLeast(radius))
        val y = anchor.y.coerceIn(radius, (size.height - radius).coerceAtLeast(radius))
        val rect = Rect((x-radius)*(1-p), (y-radius)*(1-p),
            (x+radius)*(1-p)+size.width*p, (y+radius)*(1-p)+size.height*p)
        val corner = radius*(1-p)+with(density) {18.dp.toPx()}*p
        return Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
    }
}

@Composable internal fun PlayerActionMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    anchorBounds: IntRect?, place: (IntRect, IntSize, IntSize, Int, Int, Int) -> IntOffset = ::playerMenuPosition,
    paneTitle: String = string(R.string.song_options), content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotionPolicy.current
    val progress = remember {Animatable(0f)}
    var visible by remember {mutableStateOf(false)}
    var origin by remember {mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)}
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    val margin = with(density) {12.dp.roundToPx()}
    val suppliedAnchor=anchorBounds
    val positioner = remember(suppliedAnchor, top, bottom, margin, place) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val anchor = suppliedAnchor ?: anchorBounds
                val offset = place(anchor, windowSize, popupContentSize, margin, top, bottom)
                origin = androidx.compose.ui.geometry.Offset(anchor.center.x-offset.x.toFloat(), anchor.center.y-offset.y.toFloat())
                return offset
            }
        }
    }
    LaunchedEffect(expanded, motion) {
        if (expanded) visible = true
        if (motion.reduced) progress.snapTo(if(expanded) 1f else 0f)
        else if (expanded) progress.animateTo(1f, GlassMotion.settle(motion))
        else progress.animateTo(0f, GlassMotion.exit(GlassMotion.selectionMs, motion))
        if (!expanded) visible = false
    }
    if (expanded || visible) {
        Popup(popupPositionProvider=positioner, onDismissRequest=onDismissRequest,
            properties=PopupProperties(focusable=true)) {
            Box(Modifier.width(with(density) {(window.width-2*margin).toDp()}.coerceIn(1.dp,290.dp))
                .heightIn(max=with(density) {(window.height-top-bottom-2*margin).coerceAtLeast(1).toDp()})
                .testTag("player_action_menu").semantics {this.paneTitle=paneTitle;testTagsAsResourceId=true}
                .graphicsLayer {
                    shape=UnfoldShape(progress.value,origin);clip=true
                    shadowElevation=16.dp.toPx()*progress.value.coerceIn(0f,1f)
                }.frostedPopupBackground()) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical=8.dp)
                    .graphicsLayer {
                        alpha=motionRamp(progress.value,.18f,.85f)
                        translationY=(1-progress.value)*4.dp.toPx()
                    },content=content)
            }
        }
    }
}
