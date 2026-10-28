package com.musicglass.personal

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

/**
 * Feature 5: how much a lyric line is blurred: none for the line being sung, a little for the next one or two, a
 * touch more beyond. Off while the listener scrolls on their own (they are reading), for untimed lyrics, under
 * reduced motion, while choosing lines to share, and when the setting is off.
 */
internal fun lyricLineBlur(index: Int, focusIndex: Int, active: Boolean, enabled: Boolean): Dp {
    if (!enabled || active || focusIndex < 0) return Dp(0f)
    return when (abs(index - focusIndex)) {
        0 -> Dp(0f)
        1 -> RiffLyrics.blurNear
        2 -> RiffLyrics.blurMid
        else -> RiffLyrics.blurFar
    }
}

/** Whether lines are blurred at all right now. */
internal fun lyricsBlurOn(setting: Boolean, reducedMotion: Boolean, timed: Boolean, following: Boolean, sharing: Boolean) =
    setting && !reducedMotion && timed && following && !sharing

/** Feature 5: the lyrics list fades out at its top and bottom edges instead of being cut. */
internal fun Modifier.lyricsEdgeFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, RiffLyrics.fadeTop to Color.Black,
            RiffLyrics.fadeBottom to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
