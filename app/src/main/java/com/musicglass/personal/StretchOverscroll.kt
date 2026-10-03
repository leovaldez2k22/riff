package com.musicglass.personal

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal object StretchOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = StretchOverscroll()
    override fun hashCode(): Int = 0x57e7c4
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * Edge stretch drawn as a layer scale anchored at the pulled edge. The platform EdgeEffect stretches at draw time,
 * which layout positions never see, so the glass chips (placed from those positions) stayed put while the page
 * stretched around them. A layer transform is reflected in localToRoot, so they follow it.
 */
internal class StretchOverscroll : OverscrollEffect {
    /** Accumulated pull in px per axis; positive means the start edge (top/left) is pulled away from it. */
    private var pull by mutableStateOf(Offset.Zero)
    private var settling by mutableStateOf(false)
    private var contentSize = IntSize.Zero

    override val isInProgress: Boolean get() = pull != Offset.Zero || settling

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        // A finger reversing direction first gives back the stretch before it scrolls the content again.
        val relax = Offset(relaxed(pull.x, delta.x), relaxed(pull.y, delta.y))
        pull += relax
        val remaining = delta - relax
        val consumed = performScroll(remaining)
        val leftover = remaining - consumed
        if (source != NestedScrollSource.UserInput || leftover == Offset.Zero) return relax + consumed
        val limit = Offset(contentSize.width.toFloat(), contentSize.height.toFloat())
        pull = Offset((pull.x + leftover.x * RESISTANCE).coerceIn(-limit.x, limit.x),
            (pull.y + leftover.y * RESISTANCE).coerceIn(-limit.y, limit.y))
        return delta
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        if (pull != Offset.Zero) {
            // Released mid-stretch: spring back, and let any fling away from the edge scroll the content meanwhile.
            val away = Velocity(if (velocity.x.sign == -pull.x.sign) velocity.x else 0f,
                if (velocity.y.sign == -pull.y.sign) velocity.y else 0f)
            coroutineScope {
                launch { settle(Velocity.Zero) }
                performFling(away)
            }
        } else {
            val left = performFling(velocity)
            // A fling that reaches the edge bounces briefly instead of stopping dead.
            if (left != Velocity.Zero) settle(Velocity(left.x * BOUNCE, left.y * BOUNCE))
        }
    }

    private suspend fun settle(kick: Velocity) {
        settling = true
        try {
            coroutineScope {
                launch { animate(pull.x, 0f, kick.x, SPRING) { v, _ -> pull = pull.copy(x = v) } }
                launch { animate(pull.y, 0f, kick.y, SPRING) { v, _ -> pull = pull.copy(y = v) } }
            }
        } finally {
            settling = false
        }
    }

    private fun relaxed(current: Float, delta: Float): Float =
        if (current == 0f || delta == 0f || delta.sign == current.sign) 0f
        else if (abs(delta) >= abs(current)) -current else delta

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            contentSize = IntSize(placeable.width, placeable.height)
            return layout(placeable.width, placeable.height) {
                // State is read in the layer block, so a stretch frame only updates the layer, never remeasures.
                placeable.placeWithLayer(0, 0) {
                    val p = pull
                    scaleX = 1f + MAX_STRETCH * (abs(p.x) / contentSize.width.coerceAtLeast(1).toFloat()).coerceAtMost(1f)
                    scaleY = 1f + MAX_STRETCH * (abs(p.y) / contentSize.height.coerceAtLeast(1).toFloat()).coerceAtMost(1f)
                    transformOrigin = TransformOrigin(if (p.x >= 0f) 0f else 1f, if (p.y >= 0f) 0f else 1f)
                }
            }
        }
    }

    private companion object {
        const val RESISTANCE = .5f
        const val MAX_STRETCH = .4f
        const val BOUNCE = .06f
        val SPRING = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}
