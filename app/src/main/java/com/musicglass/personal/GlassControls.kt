package com.musicglass.personal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.times
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Feature 2: a slider's thumb. [rest] draws it as a white pill while no finger is on it (the settings sliders); without
 * it the thumb only appears while held (the player's hairlines, Feature 34). Held, it grows 1.3× into clear glass that
 * magnifies the [track] under it. High contrast keeps the white pill.
 */
@Composable internal fun GlassThumb(held: Boolean, track: RiffBackdrop, size: DpSize = RiffGlassControl.thumb, rest: Boolean = true) {
    val motion = LocalMotionPolicy.current
    val solid = LocalGlassStyle.current.quality == GlassQuality.Solid
    val lift by animateFloatAsState(if (held && !solid) 1f else 0f, GlassMotion.settle(motion), label = "glass thumb")
    // The slider lays out a thumb of a fixed size; the held thumb overflows it from the centre, so the track never moves.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (rest || lift > .01f) Box(Modifier.wrapContentSize(unbounded = true).requiredSize((1f + RiffGlassControl.held * lift) * size)
            .then(if (lift > .01f) Modifier.riffGlass(GlassRole.Slider, zoom = { 1f + .5f * lift }, backdrop = track) else Modifier)
            .drawBehind {
                val pill = (if (rest) 1f - lift else 0f).coerceIn(0f, 1f)
                if (pill > 0f) drawRoundRect(White.copy(alpha = pill), cornerRadius = CornerRadius(this.size.height / 2))
            })
    }
}

/**
 * Feature 2: a switch with a glass thumb. Only drawing: the row around it is what toggles and what a screen reader
 * hears. The track turns Riff's pink when on; the thumb is white at rest and, while it travels, lifts into clear glass
 * that stretches up to 1.25× wide halfway across.
 */
@Composable internal fun GlassSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val motion = LocalMotionPolicy.current
    val solid = LocalGlassStyle.current.quality == GlassQuality.Solid
    val position by animateFloatAsState(if (checked) 1f else 0f, GlassMotion.settle(motion), label = "glass switch")
    val backdrop = rememberRiffBackdrop()
    val track = RiffGlassControl.switchTrack
    Box(modifier.size(track)) {
        Box(Modifier.matchParentSize().riffBackdropSource(backdrop).drawBehind {
            drawRoundRect(lerp(White.copy(alpha = .16f), Pink, position), cornerRadius = CornerRadius(size.height / 2))
        })
        val travel = 1f - abs(2f * position - 1f)
        val moving = travel > .02f && !solid
        Box(Modifier.layout { measurable, constraints ->
            val inset = RiffGlassControl.switchInset.roundToPx()
            val height = RiffGlassControl.switchThumb.roundToPx()
            val width = (height * (1f + RiffGlassControl.stretch * travel)).roundToInt()
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((inset + position * (constraints.maxWidth - 2 * inset - width)).roundToInt(), inset)
            }
        }.then(if (moving) Modifier.riffGlass(GlassRole.Slider, zoom = { 1.2f }, backdrop = backdrop) else Modifier)
            .drawBehind {
                val pill = if (moving) 1f - travel else 1f
                drawRoundRect(White.copy(alpha = pill), cornerRadius = CornerRadius(size.height / 2))
            })
    }
}

/**
 * Feature 12: a glass segmented control, the equalizer's mode switch made general: a glass track and a glass pill
 * that slides to the chosen segment. [onLongPress] is offered per segment (Encore opens a period's story that way).
 */
@Composable internal fun GlassSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, tag: String,
    onLongPress: ((Int) -> Unit)? = null) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(RiffRadius.pill)
    val motion = LocalMotionPolicy.current
    val words = rememberRiffBackdrop()
    val position by animateFloatAsState(selected.toFloat(), GlassMotion.settle(motion), label = "segment")
    Box(Modifier.fillMaxWidth().riffGlass(GlassRole.Card, RiffRadius.pill, backdrop = LocalPageBackdrop.current).padding(RiffSpace.tight)) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().riffBackdropSource(words)) {
            labels.forEachIndexed { index, label ->
                val chosen = index == selected
                Box(Modifier.weight(1f).heightIn(min = RiffEncore.tab).clip(shape)
                    .combinedClickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = { if (!chosen) { uiHaptic(HapticCue.Selection); onSelect(index) } },
                        onLongClick = onLongPress?.let { press -> { uiHaptic(HapticCue.Confirm); press(index) } })
                    .semantics { this.selected = chosen }.testTag("${tag}_$index"), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Text(label, color = if (chosen) White else Muted, fontSize = RiffType.body,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        val count = labels.size.coerceAtLeast(1)
        Box(Modifier.matchParentSize().layout { measurable, constraints ->
            val width = constraints.maxWidth / count
            val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place((position * width).roundToInt(), 0) }
        }.riffGlass(GlassRole.Button, RiffRadius.pill, backdrop = words).background(White.copy(alpha = .12f), shape))
    }
}
