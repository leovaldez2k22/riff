package com.musicglass.personal

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlin.math.PI
import kotlin.math.sin

/**
 * Feature 32: an instrumental stretch, counted out by three dots that light one after another as it passes, the
 * way Apple Music and BitChord show it. The row opens when the stretch starts and closes when singing returns, so
 * a break that is not playing takes no room between the verses. The fill is read in the draw phase from
 * [position], so a running break redraws three circles, never the list.
 */
@Composable
internal fun LyricGapRow(line: LyricLine, active: Boolean, position: () -> Long, motion: MotionPolicy,
    onSeek: (() -> Unit)?, modifier: Modifier = Modifier) {
    val open by animateFloatAsState(if (active) 1f else 0f,
        if (active) GlassMotion.timed(GlassMotion.screenMs, motion) else GlassMotion.exit(GlassMotion.screenMs, motion), label = "lyric gap")
    val breathing = active && !motion.reduced
    val breath = if (breathing) rememberInfiniteTransition(label = "lyric gap breath").animateFloat(0f, 1f,
        infiniteRepeatable(tween(RiffLyrics.gapBreathMs, easing = LinearEasing), RepeatMode.Restart), label = "breath") else null
    val span = (line.endMs - line.startMs).coerceAtLeast(1L)
    fun progress() = ((position() - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    val lit by remember(line) { derivedStateOf { (progress() * LYRIC_GAP_DOTS).toInt().coerceIn(0, LYRIC_GAP_DOTS) } }
    val label = string(R.string.instrumental)
    val state = string(R.string.x_of_x, lit.coerceAtLeast(1), LYRIC_GAP_DOTS)
    Box(modifier.fillMaxWidth().height((RiffLyrics.gapRow + RiffLyrics.gapRowBelow) * open).clipToBounds(),
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.width(RiffLyrics.gapDot * LYRIC_GAP_DOTS + RiffLyrics.gapDotSpace * (LYRIC_GAP_DOTS - 1))
            .height(RiffLyrics.gapDot)
            .then(if (onSeek != null) Modifier.clickable(onClick = hapticClick(onSeek)) else Modifier)
            .semantics { contentDescription = label; stateDescription = state }
            .graphicsLayer {
                val untilEnd = line.endMs - position()
                val leaving = if (untilEnd in 0 until RiffLyrics.gapExitMs) untilEnd.toFloat() / RiffLyrics.gapExitMs else 1f
                val pulse = breath?.let { 1f + RiffLyrics.gapBreath * sin(2 * PI * it.value).toFloat() } ?: 1f
                val grow = RiffLyrics.gapRestScale + (1f - RiffLyrics.gapRestScale) * open
                scaleX = grow * pulse * (.6f + .4f * leaving); scaleY = scaleX
                transformOrigin = TransformOrigin(0f, .5f)
                alpha = open * leaving
            }
            .drawBehind {
                val radius = RiffLyrics.gapDot.toPx() / 2f
                val stride = (RiffLyrics.gapDot + RiffLyrics.gapDotSpace).toPx()
                val through = progress()
                repeat(LYRIC_GAP_DOTS) { dot ->
                    val fill = lyricGapDotFill(through, dot)
                    drawCircle(White.copy(alpha = RiffLyrics.gapDotRest + (1f - RiffLyrics.gapDotRest) * fill),
                        radius, Offset(radius + dot * stride, size.height / 2f))
                }
            })
    }
}
