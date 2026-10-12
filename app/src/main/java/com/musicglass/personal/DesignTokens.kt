package com.musicglass.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Q12: the measures screens are built from, in one place. Colours are Ink, Raised, Muted, Pink and White
 * (RiffApp.kt); durations and easings are GlassMotion. A screen takes a measure from here by what it is
 * for, so two rows that should look alike cannot drift apart. scripts/quality/static_checks.py counts the
 * raw measures still written in each screen file and fails when a file gains one.
 */
internal object RiffSpace {
    /** From the edge of the screen to content. */
    val screenEdge = 20.dp
    /** Between a row's artwork and its text. */
    val rowGap = 12.dp
    /** Between items of a list. */
    val item = 8.dp
    /** Between a title and the line under it. */
    val tight = 4.dp
    /** Inside a card or a message, and between unrelated blocks. */
    val block = 16.dp
    /** The least height and width of anything that can be pressed. */
    val touch = 48.dp
    /** A list row with artwork: one line of title and one of detail. */
    val listRow = 60.dp
    val listArtwork = 48.dp
}

internal object RiffRadius {
    val artwork = 12.dp
    val card = 14.dp
    val hero = 24.dp
    /** Fields and pills: half their height or more. */
    val pill = 28.dp
}

internal object RiffType {
    val screenTitle = 31.sp
    val screenTitleLine = 34.sp
    /** A message that stands in for content: nothing here, something failed. */
    val message = 16.sp
    val body = 14.sp
    val detail = 12.sp
}

/**
 * The one theme of the app: dark, on [Ink], with [Pink] for what can be pressed. Every screen and every test
 * picture is drawn inside it. A filled button is [Pink] with [Ink] words on it: Material's own dark purple
 * was hard to read there.
 */
@Composable
internal fun RiffTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = darkColorScheme(primary = Pink, onPrimary = Ink, background = Ink, surface = Raised), typography = GlassTypography, content = content)

/**
 * Contrast of [foreground] on [background] as WCAG 2 defines it, from 1 to 21. Both colours opaque.
 * Ordinary text needs 4.5 and large text or an essential control 3 (DesignTokensTest).
 */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    fun channel(value: Float): Double = if (value <= 0.04045f) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    fun luminance(color: Color) = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    val a = luminance(foreground); val b = luminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
