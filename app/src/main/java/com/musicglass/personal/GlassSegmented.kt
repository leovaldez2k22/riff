package com.musicglass.personal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Feature 12, a glass segmented control, moved by the tab bar's own pill (owner, 30 Oct: "anywhere such a sliding menu
 * is"): a glass track; at rest a glass pill in the chosen segment with the same gap all round; a drag or a held
 * finger lifts it into the clear lens that stands out of the track, follows the finger, stretches with speed and
 * squashes at the ends; let go, the segment under it is chosen and the lens glides home. A tap chooses at once and
 * the pill slides there. [onLongPress] is offered per segment (Encore opens a period's story that way); a held finger
 * then does that instead of lifting the lens.
 */
@Composable internal fun GlassSegmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, tag: String,
    onLongPress: ((Int) -> Unit)? = null, tags: List<String>? = null, modifier: Modifier = Modifier) {
    val motion = LocalMotionPolicy.current
    val solid = LocalAccessibilitySettings.current.solidControls
    val still = motion.reduced || solid
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current.density
    val count = labels.size.coerceAtLeast(1)
    val lens = remember(count, onLongPress != null) { TabLensMotion(count, search = false, liftOnHold = onLongPress == null) }
    var tick by remember { mutableLongStateOf(0L) }
    var lensShown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val frames = remember { arrayOfNulls<Job>(1) }
    fun animate() {
        if (frames[0]?.isActive == true) return
        frames[0] = scope.launch {
            var last = withFrameNanos { it }
            while (lens.moving) {
                withFrameNanos { now -> lens.step(android.os.SystemClock.uptimeMillis(), (now - last) / 1e9f); last = now }
                val shown = lens.frame().lift > .003f
                if (shown != lensShown) lensShown = shown
                tick++
            }
        }
    }
    val chosen by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onSelect)
    val press by rememberUpdatedState(onLongPress)
    val words = rememberRiffBackdrop()
    BoxWithConstraints(modifier.fillMaxWidth().height(RiffEncore.tab + RiffSpace.tight * 2)
        .riffGlass(GlassRole.Card, RiffRadius.pill, backdrop = LocalPageBackdrop.current)
        .pointerInput(count, rightToLeft) {
            awaitEachGesture {
                val width = size.width.toFloat()
                fun logical(x: Float) = if (rightToLeft) width - x else x
                fun indexAt(x: Float) = (logical(x) / (width / count)).toInt().coerceIn(0, count - 1)
                val down = awaitFirstDown(requireUnconsumed = false)
                lens.down(logical(down.position.x), down.uptimeMillis); animate()
                val longAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                var releasedX = down.position.x
                var lastHover = -1
                var held = false
                var scrolled = false
                while (true) {
                    val waiting = press != null && !held && !lens.lifted
                    val event = if (waiting) withTimeoutOrNull((longAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(1L)) { awaitPointerEvent() }
                        else awaitPointerEvent()
                    if (event == null) {
                        // Held still: the long press, and no lens.
                        held = true; lens.cancel(); animate()
                        uiHaptic(HapticCue.Confirm); press?.invoke(indexAt(releasedX))
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    releasedX = change.position.x
                    if (!change.pressed) break
                    if (held) continue
                    // A vertical drag is the page scrolling: the control lets it go.
                    if (!lens.lifted && abs(change.position.y - down.position.y) > viewConfiguration.touchSlop &&
                        abs(change.position.y - down.position.y) > abs(change.position.x - down.position.x)) { scrolled = true; break }
                    lens.move(logical(releasedX), viewConfiguration.touchSlop)
                    if (lens.lifted) {
                        change.consume()
                        // A tick each time the lens comes over another segment.
                        val hover = lens.hovered()
                        if (lastHover >= 0 && hover != lastHover) uiHaptic(HapticCue.Selection)
                        lastHover = hover
                    }
                }
                if (held) return@awaitEachGesture
                if (scrolled) { lens.cancel(); animate(); return@awaitEachGesture }
                val index = if (lens.lifted) lens.up() else { lens.cancel(); indexAt(releasedX) }
                animate()
                if (index != chosen) { uiHaptic(HapticCue.Selection); pick(index) }
            }
        }) {
        val barWidth = constraints.maxWidth.toFloat()
        val barHeight = constraints.maxHeight.toFloat()
        SideEffect {
            lens.layout(barWidth / count, barHeight, 0f, 0f, selected.coerceIn(0, count - 1), still)
            if (lens.moving) animate()
        }
        fun frame(): TabLensFrame {
            tick
            val f = lens.frame()
            return if (rightToLeft) f.copy(centerX = barWidth - f.centerX, ghostX = barWidth - f.ghostX) else f
        }
        Row(Modifier.fillMaxSize().riffBackdropSource(words)) {
            labels.forEachIndexed { index, label ->
                val on = index == selected
                Box(Modifier.weight(1f).fillMaxHeight().testTag(tags?.getOrNull(index) ?: "${tag}_$index").semantics {
                    role = Role.Tab; this.selected = on
                    onClick { if (!on) { uiHaptic(HapticCue.Selection); pick(index) }; true }
                    press?.let { long -> onLongClick { long(index); true } }
                }, contentAlignment = Alignment.Center) {
                    Text(label, color = if (on) White else Muted, fontSize = RiffType.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        // At rest, real glass over the words, with the tab bar pill's rim; it fades as it lifts into the lens.
        Box(Modifier.layout { measurable, constraints ->
            val f = frame()
            val placeable = measurable.measure(Constraints.fixed(f.width.toInt().coerceAtLeast(1), f.height.toInt().coerceAtLeast(1)))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        }.graphicsLayer {
            val f = frame()
            translationX = f.centerX - f.width.toInt() / 2f; translationY = barHeight / 2f - f.height.toInt() / 2f
            alpha = (1f - f.lift).coerceIn(0f, 1f)
        }.riffGlass(GlassRole.Button, RiffRadius.pill, backdrop = words).drawWithCache {
            val rim = Brush.verticalGradient(listOf(White.copy(alpha = .34f), White.copy(alpha = .08f)))
            val radius = CornerRadius(size.height / 2f)
            onDrawBehind {
                drawRoundRect(if (solid) Pink.copy(alpha = .10f) else White.copy(alpha = .12f), cornerRadius = radius)
                drawRoundRect(rim, cornerRadius = radius, style = Stroke(density))
            }
        })
        if (lensShown && !still) TabLens(::frame, words, LocalPageBackdrop.current, barWidth, barHeight, density)
    }
}
