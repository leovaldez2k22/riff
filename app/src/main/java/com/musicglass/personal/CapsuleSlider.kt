package com.musicglass.personal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A continuous rounded capsule with a white thumb that turns to glass while held (Feature 2). [label] and
 * [spokenValue] are what a screen reader says ("Crossfade length", "3 seconds") instead of an unnamed
 * percentage (Q18-033); a caller that names the slider itself leaves both null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CapsuleSlider(value: Float, onValueChange: (Float)->Unit,
    valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    onValueChangeFinished: (()->Unit)? = null, enabled: Boolean = true, haptics: SliderHaptics = SliderHaptics.Seek, steps: Int = 0,
    label: String? = null, spokenValue: String? = null) {
    val feedback=androidx.compose.runtime.remember(haptics) {SliderHapticTracker(haptics)}
    val interaction=androidx.compose.runtime.remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    // Feature 2: the thumb is glass while held, bending the track recorded here.
    val track=rememberRiffBackdrop()
    Slider(value=value.coerceIn(valueRange.start,valueRange.endInclusive),onValueChange={feedback.change(it,valueRange)?.let(::uiHaptic);onValueChange(it)},
        valueRange=valueRange,steps=steps,interactionSource=interaction,onValueChangeFinished={onValueChangeFinished?.invoke();feedback.finish()?.let(::uiHaptic)},enabled=enabled,
        thumb={ GlassThumb(dragged || pressed, track) },track={ state ->
            Canvas(Modifier.fillMaxWidth().height(6.dp).riffBackdropSource(track)) {
                val radius=CornerRadius(size.height/2,size.height/2)
                drawRoundRect(White.copy(alpha=if(enabled) .26f else .12f),cornerRadius=radius)
                val span=(state.valueRange.endInclusive-state.valueRange.start).coerceAtLeast(.001f)
                val fraction=((state.value-state.valueRange.start)/span).coerceIn(0f,1f)
                if(fraction>0) drawRoundRect(White.copy(alpha=if(enabled) .9f else .35f),size=Size(size.width*fraction,size.height),cornerRadius=radius)
            }
        },modifier=modifier.heightIn(min=48.dp).then(if(label==null && spokenValue==null) Modifier else Modifier.semantics {
            label?.let {contentDescription=it};spokenValue?.let {stateDescription=it}
        }))
}
