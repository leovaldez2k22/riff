package com.musicglass.personal

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/*
 * Feature 22: the back gesture previews where it goes. The full player and lyrics shrink toward the mini player;
 * a pushed page slides toward the edge it leaves by. Under reduced motion there is no preview, only the back itself.
 * The measures are RiffPredictiveBack in DesignTokens.kt.
 */

/** What the open player or page looks like part-way through a back gesture. */
internal data class BackPreview(val scale: Float, val dropFraction: Float, val slideFraction: Float, val alpha: Float)

internal fun backPreview(progress: Float, player: Boolean): BackPreview {
    val p = progress.coerceIn(0f, 1f)
    return if (player) BackPreview(1f - RiffPredictiveBack.playerShrink * p, p, 0f, 1f)
    else BackPreview(1f, 0f, RiffPredictiveBack.pageSlide * p, 1f - RiffPredictiveBack.pageFade * p)
}

/** The gesture's progress, 0 when no back gesture is under way. */
internal class PredictiveBackState {
    val progress = Animatable(0f)
    /** Set from the gesture's start: a swipe from the right edge slides the page left. */
    var fromRight = false
}

/**
 * Collects the system's back events. A finished gesture calls [onBack]; a cancelled one settles back to the start.
 * With [reduced] motion nothing moves while the finger does.
 */
@Composable
internal fun RiffBackHandler(enabled: Boolean, reduced: Boolean, onBack: () -> Unit): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val scope = rememberCoroutineScope()
    val motion = androidx.compose.runtime.rememberUpdatedState(LocalMotionPolicy.current)
    PredictiveBackHandler(enabled) { events: Flow<BackEventCompat> ->
        try {
            events.collect { event ->
                state.fromRight = event.swipeEdge == BackEventCompat.EDGE_RIGHT
                if (!reduced) state.progress.snapTo(event.progress)
            }
            onBack()
            state.progress.snapTo(0f)
        } catch (cancelled: CancellationException) {
            scope.launch { state.progress.animateTo(0f, GlassMotion.settle(motion.value)) }
            throw cancelled
        }
    }
    return state
}

/** The open player or lyrics, previewing its close. */
internal fun Modifier.playerBackPreview(state: PredictiveBackState): Modifier = graphicsLayer {
    val preview = backPreview(state.progress.value, player = true)
    if (preview.scale == 1f) return@graphicsLayer
    scaleX = preview.scale; scaleY = preview.scale
    translationY = preview.dropFraction * RiffPredictiveBack.playerDrop.toPx()
    shape = RoundedCornerShape(RiffPredictiveBack.playerCorner * preview.dropFraction)
    clip = true
}

/** A pushed page, previewing the way back to the page under it. */
internal fun Modifier.pageBackPreview(state: PredictiveBackState): Modifier = graphicsLayer {
    val preview = backPreview(state.progress.value, player = false)
    if (preview.slideFraction == 0f) return@graphicsLayer
    translationX = size.width * preview.slideFraction * (if (state.fromRight) -1f else 1f)
    alpha = preview.alpha
}
