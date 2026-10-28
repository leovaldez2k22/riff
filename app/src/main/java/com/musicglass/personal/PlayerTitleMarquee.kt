package com.musicglass.personal

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Feature 7: the full player's title scrolls when it is longer than its row, and nowhere else. The mini player,
 * lists and the queue keep the "…". [basicMarquee] only moves text that does not fit, so a short title stays
 * still; [overflowing] decides only whether the right edge fades.
 */
internal fun Modifier.playerTitleMarquee(enabled: Boolean, overflowing: Boolean): Modifier =
    if (!enabled) this else this
        .then(if (overflowing) Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = RiffMarquee.fade.toPx().coerceAtMost(size.width / 4f)
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                    startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
            } else Modifier)
        .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = RiffMarquee.firstDelayMs,
            repeatDelayMillis = RiffMarquee.restMs, spacing = MarqueeSpacing(RiffMarquee.gap), velocity = RiffMarquee.velocity)

/**
 * Whether the title would scroll: the full player's own rules. Large text keeps two lines with "…" (more
 * readable than a moving line at that size); reduced motion keeps the "…"; and it only runs once the player
 * is fully open, not during the morph, a drag or with the queue over it.
 */
internal fun playerTitleScrolls(reducedMotion: Boolean, fontScale: Float, fullyOpen: Boolean, queueOpen: Boolean, dragging: Boolean) =
    !reducedMotion && fontScale < 1.5f && fullyOpen && !queueOpen && !dragging
