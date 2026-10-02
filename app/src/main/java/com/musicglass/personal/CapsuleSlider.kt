package com.musicglass.personal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A continuous rounded capsule: the filled end is the handle, with no floating thumb or gap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CapsuleSlider(value: Float, onValueChange: (Float)->Unit,
    valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    onValueChangeFinished: (()->Unit)? = null, enabled: Boolean = true, haptics: SliderHaptics = SliderHaptics.Seek, steps: Int = 0) {
    val feedback=androidx.compose.runtime.remember(haptics) {SliderHapticTracker(haptics)}
    Slider(value=value.coerceIn(valueRange.start,valueRange.endInclusive),onValueChange={feedback.change(it,valueRange)?.let(::uiHaptic);onValueChange(it)},
        valueRange=valueRange,steps=steps,onValueChangeFinished={onValueChangeFinished?.invoke();feedback.finish()?.let(::uiHaptic)},enabled=enabled,
        thumb={ Spacer(Modifier.size(20.dp)) },track={ state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val radius=CornerRadius(size.height/2,size.height/2)
                drawRoundRect(White.copy(alpha=if(enabled) .26f else .12f),cornerRadius=radius)
                val span=(state.valueRange.endInclusive-state.valueRange.start).coerceAtLeast(.001f)
                val fraction=((state.value-state.valueRange.start)/span).coerceIn(0f,1f)
                if(fraction>0) drawRoundRect(White.copy(alpha=if(enabled) .9f else .35f),size=Size(size.width*fraction,size.height),cornerRadius=radius)
            }
        },modifier=modifier.heightIn(min=48.dp))
}
