package com.musicglass.personal

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp

/**
 * Feature 34.3: the player's hairline seek and volume bars. No thumb; the played part is white at 92 % on 26 %, and
 * the bar grows from [idle] to [dragged] while a finger is on it. The touch area stays 48 dp tall for TalkBack and
 * motor access (the plan's 34 dp is BitChord's; Riff keeps its own minimum).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier, onValueChangeFinished: (() -> Unit)? = null, enabled: Boolean = true,
    haptics: SliderHaptics = SliderHaptics.Seek, idle: Dp = RiffPlayer.seekIdle, dragged: Dp = RiffPlayer.seekDragged) {
    val feedback = remember(haptics) { SliderHapticTracker(haptics) }
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionPolicy.current
    val height by animateDpAsState(if (dragging || pressed) dragged else idle, GlassMotion.timed(GlassMotion.pressMs, motion), label = "thin slider")
    Slider(value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = { feedback.change(it, valueRange)?.let(::uiHaptic); onValueChange(it) },
        valueRange = valueRange, enabled = enabled, interactionSource = interaction,
        onValueChangeFinished = { onValueChangeFinished?.invoke(); feedback.finish()?.let(::uiHaptic) },
        thumb = { Spacer(Modifier.size(RiffPlayer.seekThumbSpace)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(White.copy(alpha = if (enabled) .26f else .12f), cornerRadius = radius)
                val span = (state.valueRange.endInclusive - state.valueRange.start).coerceAtLeast(.001f)
                val fraction = ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                if (fraction > 0) drawRoundRect(White.copy(alpha = if (enabled) .92f else .35f), size = Size(size.width * fraction, size.height), cornerRadius = radius)
            }
        }, modifier = modifier.heightIn(min = RiffSpace.touch))
}
