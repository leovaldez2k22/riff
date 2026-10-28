package com.musicglass.personal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
    /** The size an icon is drawn for on its own: Material's 24 dp grid (RoundedTransportIcons, Q18-016). */
    val icon = 24.dp
}

/** Feature 7: a long song title scrolls in the full player only, pausing at its start between passes. */
internal object RiffMarquee {
    /** The space between the end of the title and its next start. */
    val gap = 48.dp
    /** Speed: slow enough to read while it moves. */
    val velocity = 30.dp
    /** The right edge fades over this width while the title runs past it. */
    val fade = 20.dp
    /** The start of the title is read first, then it rests at the start between passes. */
    const val firstDelayMs = 2_000
    const val restMs = 2_500
}

/** Feature 22: how far the back gesture's preview moves the player and pushed pages. */
internal object RiffPredictiveBack {
    /** At the end of the gesture the player is drawn at 90 %. */
    const val playerShrink = .1f
    val playerDrop = 64.dp
    val playerCorner = 32.dp
    /** A pushed page moves this share of its width, and fades a little. */
    const val pageSlide = .15f
    const val pageFade = .2f
}

/** Feature 24: the large card for an artist or album at the top of an "All" search. */
internal object RiffTopResult {
    val artwork = 88.dp
    val title = 20.sp
}

/** Feature 16: the stats for nerds panel. */
internal object RiffNerdStats {
    /** The label column, wide enough for "Network estimate". */
    val label = 128.dp
    /** Between two rows. */
    val rowGap = 2.dp
}

/** Feature 11: the dark fade at the top and bottom of the screen, under the status bar and behind the floating bar. */
internal object RiffEdgeFade {
    /** Below the status bar, the top fade carries on this far. */
    val topExtra = 28.dp
    /** Above the navigation bar, the bottom fade reaches this far (behind the floating home, mini player and search). */
    val bottomHeight = 120.dp
    /** How dark each fade is at the very edge: "slightly dark", text under it still faintly visible. */
    const val topPeak = .72f
    const val bottomPeak = .62f
    /** With high contrast on, the status bar always gets a dark base. */
    const val highContrastPeak = .9f
}

/** Feature 8: the "See all" grid: the narrowest a card may be before a column is dropped. */
internal object RiffGrid {
    val cell = 150.dp
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
    /** The title of a settings row. */
    val rowTitle = 15.sp
}

/** Q16A: the lyrics screen's sung lines, their background vocals and the emphasis on held words. */
internal object RiffLyrics {
    val text = 29.sp
    val textLine = 38.sp
    /** A background vocal: smaller, under its line. */
    val backing = 20.sp
    val backingLine = 27.sp
    val linePad = 5.dp
    val backingGap = 4.dp
    /** How far a held word rises, how far its glow spreads, and how soft the edge of its sweep is. */
    val heldRise = 2.dp
    val heldGlow = 14.dp
    val sweepEdge = 10.dp
    const val heldScale = 1.07f
    /** Q16B: how far each letter pops up as the sweep reaches it (it stays there), and how much it swells on the way. */
    val letterRise = 1.dp
    const val letterSwell = 1.12f
    /** Q16B: how far a held word's letters shake while it is sung: a shimmer more than a wobble. */
    val heldShake = .25.dp
    /** Feature 32: the three dots of an instrumental stretch: each dot, the space between, the row and the space under it. */
    val gapDot = 13.dp
    val gapDotSpace = 5.dp
    val gapRow = 40.dp
    val gapRowBelow = 16.dp
    /** An unlit dot still shows how many are coming; the row grows in from this scale. */
    const val gapDotRest = .25f
    const val gapRestScale = .76f
    /** While the stretch plays, the dots breathe this much, once every [gapBreathMs]. */
    const val gapBreath = .06f
    const val gapBreathMs = 1_600
    /** In the last moment before singing returns, the dots shrink away so the next line lights into empty space. */
    const val gapExitMs = 300L
    /** Feature 25: the romanized line under the original, when both are shown. */
    val romanUnder = 19.sp
    val romanUnderLine = 25.sp
    const val romanUnderAlpha = .7f
    /** Feature 5: lines not being sung are very slightly blurred, more the further they are from the sung one. */
    val blurNear = 1.dp
    val blurMid = 1.75.dp
    val blurFar = 2.5.dp
    /** Feature 5: the list fades out at its top and bottom: transparent at the edge, clear from these fractions in. */
    const val fadeTop = .12f
    const val fadeBottom = .82f
    /** The glow: a lighter pink than [Pink], so the white letters stay readable on it. */
    val glow = Color(0xFFFF9BD3)
    /** Q18-037: the tint behind a line chosen for a card, reaching past the line at its sides and ends, and its corner. */
    val chosenSide = 10.dp
    val chosenEnd = 2.dp
    val chosenCorner = 16.dp
    /** F2: the share sheet's card preview corner and the space between the preview and the looks. */
    val cardCorner = 18.dp
    val sheetGap = 14.dp
}

/** Q18-013, Q18-042: the sign-in screens' glass, buttons, account rows, steps and bar (docs/quality/sign-in-design.md). */
internal object RiffSignIn {
    /** The glass of a screen's card. Buttons, rows and the footer use [corner]; the bar uses [barCorner]. */
    val glass = 28.dp
    val glassEdge = .7.dp
    val corner = 18.dp
    /** A button's least height, the reach of the pink button's glow, and its words. */
    val button = 52.dp
    val buttonGlow = 18.dp
    val buttonText = 15.sp
    /** A saved account: its coloured circle, the initial in it, the name, and the space at the row's sides. */
    val avatar = 40.dp
    val avatarText = 17.sp
    val rowTitle = 15.sp
    val rowSide = 10.dp
    /** A step of Checking: its ring, the ring's line, the tick inside it, and the space above and below. */
    val stepRing = 22.dp
    val stepRingLine = 1.5.dp
    val stepTick = 15.dp
    val stepPad = 7.dp
    /** The bar over Google's page and the accounts page: its height, the small record beside back, its corner and title. */
    val barHeight = 64.dp
    val barRecord = 44.dp
    val barSide = 6.dp
    val barCorner = 22.dp
    val barTitle = 16.sp
    /** A screen's title, a note under it, and the footer under Google's page with its button's greatest height. */
    val title = 26.sp
    val note = 13.sp
    val footer = 60.dp
    val footerButton = 44.dp
    /** Inside a card, between its items, around a card that fills the width, and below the last card. */
    val cardPad = 22.dp
    /** Welcome's card has a little less space inside than the others (as designed before Q18-042). */
    val welcomePad = 20.dp
    val cardGap = 10.dp
    val cardMargin = 10.dp
    val cardBottom = 24.dp
    /** The accounts page's search: the space inside its card and its corner; the lock beside Google's address. */
    val fieldPad = 14.dp
    val fieldCorner = 16.dp
    val lock = 14.dp
    /** Room above each screen's card for the record (recordSpot in AccountScreen.kt), and the record on Stop signing in. */
    val manageTop = 150.dp
    val welcomeTop = 130.dp
    val firstRunTop = 232.dp
    val connectedTop = 250.dp
    val stopRecord = 64.dp
    /** How far each part of Connected rises as it appears. */
    val itemRise = 18.dp
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
 * Q13: how much black goes over a picture before words or a control are set on it. Each figure is for the
 * worst picture there is, a white one, so it holds for any artwork or photograph (ContrastTest works them out).
 */
internal object RiffContrast {
    /** Ordinary text. */
    const val text = 4.5
    /** Large text (24 sp, or 19 sp bold) and anything that has to be seen to be used. */
    const val control = 3.0
    /** Under [White] words: 4.5:1 needs 0.551. */
    const val scrimForText = .58f
    /** Under [Muted] words: 4.5:1 needs 0.732. */
    const val scrimForMutedText = .74f
    /** Under a [White] icon: 3:1 needs 0.435. Icons and words over artwork now carry [RiffOverPicture]'s shadows instead. */
    const val scrimForControl = .46f
}

/**
 * Icons and words set straight over a picture, with no fade or patch under them: the owner did not want the
 * player's fade or the back arrow's patch (Q18-002, Q18-008). Each carries its own dark edge instead, which reads
 * over a white cover; accessibility.md records what this gives up against a measured backing.
 */
internal object RiffOverPicture {
    /** A tight dark copy under an icon: an edge round its strokes. */
    val iconEdge = 1.5.dp
    /** A wider, softer copy that carries the edge out over a white picture. */
    val iconHalo = 6.dp
    /** A collection's lines under its title: the subtitle and the song count and length (Q18-093). */
    val headerLine = 16.sp
    val headerLineGap = 6.dp
    const val iconEdgeAlpha = .9f
    const val iconHaloAlpha = .55f
    /** Under words: one close, deep shadow that edges each letter. */
    val textShadow = Shadow(Color.Black.copy(alpha = .9f), Offset.Zero, blurRadius = 8f)
}

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
