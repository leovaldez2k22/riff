package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Enough stops that the ramp does not band across a near-flat colour. */
internal const val EDGE_FADE_STOPS = 12

/**
 * Feature 11: twelve stops on an ease-out curve, from [peak] black at the edge to nothing. smoothstep keeps the
 * far end flat, so the fade has no visible line where it stops.
 */
internal fun edgeFadeStops(peak: Float): Array<Pair<Float, Color>> = Array(EDGE_FADE_STOPS) { i ->
    val x = i / (EDGE_FADE_STOPS - 1f)
    val left = 1f - x
    x to Color.Black.copy(alpha = peak * left * left * (3f - 2f * left))
}

/**
 * Feature 11: the page fades to slightly dark under the status bar and behind the floating bar at the bottom.
 * Drawing only: it takes no touches and says nothing to a screen reader.
 */
@Composable
internal fun EdgeFades(highContrast: Boolean) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + RiffEdgeFade.topExtra
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + RiffEdgeFade.bottomHeight
    val topBrush = remember(highContrast) { Brush.verticalGradient(*edgeFadeStops(if (highContrast) RiffEdgeFade.highContrastPeak else RiffEdgeFade.topPeak)) }
    val bottomStops = remember { edgeFadeStops(RiffEdgeFade.bottomPeak) }
    val bottomBrush = remember(bottomStops) { Brush.verticalGradient(*bottomStops.map { (x, c) -> (1f - x) to c }.reversed().toTypedArray()) }
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }.testTag("edge_fades")) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(top).background(topBrush))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom).background(bottomBrush))
    }
}
