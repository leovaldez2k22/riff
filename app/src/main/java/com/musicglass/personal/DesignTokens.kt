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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
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

/** Feature 34: the redesigned player (sizes from BitChord's, 34.3). */
/** Feature 2: the glass controls' sizes, after iOS's. */
internal object RiffGlassControl {
    /** A slider's thumb at rest; held it grows by [held]. */
    val thumb = DpSize(30.dp, 20.dp)
    const val held = .3f
    val switchTrack = DpSize(51.dp, 31.dp)
    val switchThumb = 27.dp
    val switchInset = 2.dp
    /** How much wider the switch's thumb is halfway across. */
    const val stretch = .25f
    val galleryHeight = 520.dp
    val galleryBar = 64.dp
    val galleryLens = 96.dp
    val galleryCard = 110.dp
}

/** Feature 2: popups' corner when the caller doesn't give one (the song menus'). */
internal object RiffGlassPopup {
    val corner = RiffMenu.corner
}

/**
 * Every pop-out (menus and dialogs) after the owner's reference picture (31 Oct): one panel of neutral grey glass with
 * large corners and a hairline rim, rows led by their icon, regular type, hairlines between groups only. The look only:
 * what each menu offers is unchanged. The reference is set in SF Pro, which Riff does not bundle; Inter is the nearest.
 */
internal object RiffMenu {
    val corner = 28.dp
    val minWidth = 230.dp
    val maxWidth = 290.dp
    val row = 48.dp
    /** The row's icon, and from the panel's edge to it and from it to the words. */
    val icon = 24.dp
    val edge = 20.dp
    val iconGap = 16.dp
    /** Hairlines between groups stop this far from the panel's sides. */
    val dividerInset = 16.dp
    val dividerColor = Color.White.copy(alpha = .12f)
    val text = TextStyle(fontFamily = InterFamily, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-.3).sp)
    /** A line under a row's words (a sort option it cannot offer, say). */
    val detail = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = (-.15).sp)
    val title = TextStyle(fontFamily = InterDisplayFamily, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.35).sp)
    /** The glass's tint, a neutral grey rather than the app's blue-black, and its rim. */
    val tint = Color(0xFF2A2C31)
    val tintAlpha = .72f
    val rim = Color.White.copy(alpha = .10f)
    val rimWidth = 1.dp
}

internal object RiffPlayer {
    /** Two steps smaller at the owner's request (phone checks 29 Oct, item 9): 26 and 22 sp read too big. */
    val title = 20.sp
    val titleTracking = (-.35).sp
    val artist = 17.sp
    /** The like and more circles, and their icons. */
    val circle = 44.dp
    val circleIcon = 20.dp
    val lyricLine = 17.sp
    val lyricDot = 9.dp
    val seekIdle = 7.dp
    val seekDragged = 12.dp
    val volumeIdle = 6.dp
    val volumeDragged = 10.dp
    /** Room for Material's slider to measure a thumb that isn't drawn. */
    val seekThumbSpace = 12.dp
    val time = 12.sp
    val statsLine = 11.sp
    val qualityText = 12.5.sp
    val qualityIcon = 14.dp
    val toggle = 42.dp
    val toggleIcon = 22.dp
    val skip = 56.dp
    val skipIcon = 46.dp
    val play = 64.dp
    val playIcon = 52.dp
    val activeDot = 4.dp
    val volumeIcon = 20.dp
    val bottomIcon = 24.dp
    val pillHeight = 44.dp
    val pillWidth = 64.dp
    val deviceName = 11.sp
}

/** Feature 16: the stats for nerds panel. */
/** Feature 10: the equalizer's tone pad, faders and curve. */
internal object RiffEqualizer {
    val edge = .6.dp
    val tab = 40.dp
    /** A grid dot's radius and the puck's. */
    val dot = 2.5.dp
    val puck = 11.dp
    val readout = 15.sp
    val faders = 220.dp
    val track = 4.dp
    val knob = 18.dp
    val label = 11.sp
    val curve = 88.dp
}

/** Feature 12: Encore's page, charts and stories. */
/** Feature 30: the shell on a wide window. */
internal object RiffShell {
    /** The rail's width, and the room it leaves at the start of the screens with [railEdge] on each side. */
    val rail = 80.dp
    val railItem = 60.dp
    val railEdge = 12.dp
    /** Below this height a wide window (a phone on its side) keeps the bottom bar. */
    val railLeastHeight = 480.dp
    /** The player pane is 40 % of the window, and never narrower than this. */
    val paneLeast = 360.dp
    /** Room under the screens for the mini player alone, and for nothing. */
    val miniOnly = 84.dp
    val bare = 20.dp
    val none = 0.dp
}

internal object RiffEncore {
    /** The period control's tabs. */
    val tab = 40.dp
    val headline = 44.sp
    val chart = 168.dp
    val barGap = 3.dp
    val barCorner = 4.dp
    val clock = 232.dp
    /** The clock's empty middle, as a share of its radius. */
    const val clockHole = .42f
    val cellGap = 2.dp
    val cellCorner = 3.dp
    val cellLabel = 10.sp
    val rankArt = 48.dp
    val rank = 15.sp
    val ring = 68.dp
    val ringStroke = 3.dp
    val ringGap = 4.dp
    val donut = 196.dp
    val donutStroke = 30.dp
    val storyBar = 3.dp
    val storyBarGap = 4.dp
    val storyNumber = 72.sp
    val storyTitle = 34.sp
    val storyBody = 19.sp
    val storyArt = 248.dp
    val storyRowArt = 72.dp
    val storyShare = 52.dp
}

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

/** The settings pages (owner, 30 Oct): the opening page's tiles and the sections' bento of glass tiles. */
internal object RiffSettings {
    /** The least height of a tile; a tall one is two of these and the gap. */
    val tile = 78.dp
    val tilePad = 14.dp
    val tileGap = 10.dp
    val hairline = .6.dp
    /** A row's icon in a settings list. */
    val rowIcon = 21.dp
    /** The round badge an icon sits in, and the icon. */
    val badge = 34.dp
    val badgeIcon = 19.dp
    /** The quick switches across the top of the opening page. */
    val quick = 70.dp
    val quickIcon = 22.dp
    val search = 44.dp
    val avatar = 46.dp
    /** The pictures inside the big tiles: the blend, the booster's dial, the glass sample, the storage ring. */
    val wave = 76.dp
    val waveStroke = 2.5.dp
    val beatTick = 7.dp
    val stripe = 16.dp
    val glassPill = 58.dp
    val dial = 96.dp
    val dialStroke = 9.dp
    val glassSample = 118.dp
    val ring = 104.dp
    val eqBar = 5.dp
    val eqHeight = 44.dp
    val lyricLine = 20.sp
    val lyricOther = 15.sp
    val lyricDot = 8.dp
    /** How far a tile rises as it comes in. */
    val enterRise = 18.dp
    val title = 16.sp
    val big = 26.sp
}

internal object RiffRadius {
    val artwork = 12.dp
    val card = 14.dp
    val hero = 24.dp
    /** Fields and pills: half their height or more. */
    val pill = 28.dp
}

/**
 * Feature 3: the type scale, after Apple's. Display sizes (20 sp and up) take Inter Display; the rest Inter Text.
 * Tracking tightens as the size grows, as SF's does.
 */
internal object RiffText {
    /** Home, Library, Settings and every page's own title. */
    val largeTitle = TextStyle(fontFamily = InterDisplayFamily, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.9).sp)
    val sectionHeading = TextStyle(fontFamily = InterDisplayFamily, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
    /** A headline that is not a page title: a sheet's, a message's. */
    val headline = TextStyle(fontFamily = InterDisplayFamily, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp)
    val rowTitle = TextStyle(fontFamily = InterFamily, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp)
    val body = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, letterSpacing = (-.2).sp)
    val subtitle = TextStyle(fontFamily = InterFamily, fontSize = 13.sp, letterSpacing = (-.05).sp)
    /** A Settings row, as in the owner's screenshot: larger and regular, its help under it at 55 %. */
    val settingsRow = TextStyle(fontFamily = InterFamily, fontSize = 19.sp, letterSpacing = (-.45).sp)
    val settingsHelp = TextStyle(fontFamily = InterFamily, fontSize = 15.sp, letterSpacing = (-.2).sp)
    /** Times and counts: tabular figures, so a running clock doesn't jitter. */
    const val TABULAR = "tnum"
}

internal object RiffType {
    val screenTitle = 34.sp
    val screenTitleLine = 40.sp
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
    val tracking = (-.6).sp
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
