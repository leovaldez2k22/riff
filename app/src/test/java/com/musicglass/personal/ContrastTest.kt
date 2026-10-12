package com.musicglass.personal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.*
import org.junit.Test

/**
 * Q13: words and controls set over artwork, a photograph or a colour taken from artwork stay readable whatever
 * the picture is. The method is arithmetic, not a screenshot: the colour that ends up behind the words is
 * worked out from the layers the screen draws, for a white picture (the worst there is), a black one and a
 * set of loud ones, and its WCAG 2 contrast with the words is held to 4.5:1 for ordinary text and 3:1 for
 * large text and essential controls. This is not a certification; docs/quality/accessibility.md says what
 * it does and does not cover.
 */
class ContrastTest {
    private val white = Color.White
    /** Bright, dark and busy: what a cover or a photograph can put behind the words. */
    private val pictures = listOf(white, Color.Black, Color(0xFFFFFF00), Color(0xFF00FFFF), Color(0xFF00FF00), Color(0xFFFF00FF),
        Color(0xFFFF0000), Color(0xFF0000FF), Color(0xFF808080), Color(0xFFFFC0CB), Color(0xFFFFF8E1), Color(0xFF283444))

    private fun reads(words: Color, behind: Color) = contrastRatio(words.compositeOver(behind), behind)
    private fun atLeast(needed: Double, words: Color, behind: Color, what: String) =
        assertTrue("$what: ${"%.2f".format(reads(words, behind))} on ${behind.value.toString(16).take(8)}, needs $needed", reads(words, behind) >= needed)
    private fun black(alpha: Float) = Color.Black.copy(alpha = alpha)
    /** The black a vertical gradient has reached at [fraction] of its height. */
    private fun at(stops: Array<Pair<Float, Color>>, fraction: Float): Color {
        val after = stops.indexOfFirst { it.first >= fraction }.coerceAtLeast(1)
        val (a, from) = stops[after - 1]; val (b, to) = stops[after]
        val share = ((fraction - a) / (b - a)).coerceIn(0f, 1f)
        return Color(0f, 0f, 0f, from.alpha + (to.alpha - from.alpha) * share)
    }

    @Test fun aToneTakenFromArtworkIsDarkEnoughToReadOn() {
        pictures.forEach { picture ->
            val tone = picture.readableArtworkTone()
            atLeast(RiffContrast.text, Pink, tone, "an error or the playing mark")
            atLeast(RiffContrast.text, Muted, tone, "an artist's name")
            atLeast(RiffContrast.text, White, tone, "a title")
            // The faintest words the queue, a collection and Downloads set on a tone, and their faintest icon.
            atLeast(RiffContrast.text, White.copy(alpha = .6f), tone, "a row's second line")
            atLeast(RiffContrast.control, White.copy(alpha = .45f), tone, "the drag handle")
        }
    }

    @Test fun aToneIsOnlyDarkenedWhereItHasToBe() {
        fun share(of: Color) = of.readableArtworkTone().let { maxOf(it.red, it.green, it.blue) / maxOf(of.red, of.green, of.blue) }
        // A colour is kept in 8 bits a channel, so the share is only that exact.
        assertEquals("a dark cover keeps its 48%", .48f, share(Color(0xFF283444)), .01f)
        assertEquals("so does a loud but dark one", .48f, share(Color(0xFF0000FF)), .01f)
        assertTrue("pure red is just too light at 48% for pink words", share(Color(0xFFFF0000)) in .40f..<.48f)
        assertTrue("a white cover gives a darker tone than 48%", share(white) < .30f)
        assertTrue("and one that is still a tone, not black", share(white) > .15f)
        assertEquals(Color.Black, Color.Black.readableArtworkTone())
    }

    @Test fun theScrimsAreDeepEnoughOverAWhitePicture() {
        atLeast(RiffContrast.text, White, black(RiffContrast.scrimForText).compositeOver(white), "white words")
        atLeast(RiffContrast.text, Muted, black(RiffContrast.scrimForMutedText).compositeOver(white), "muted words")
        atLeast(RiffContrast.control, White, black(RiffContrast.scrimForControl).compositeOver(white), "a white icon")
        // Each is close to the least that will do: a shallower one fails, so a later change cannot quietly thin it.
        assertTrue(reads(White, black(RiffContrast.scrimForText - .05f).compositeOver(white)) < RiffContrast.text)
        assertTrue(reads(Muted, black(RiffContrast.scrimForMutedText - .05f).compositeOver(white)) < RiffContrast.text)
        assertTrue(reads(White, black(RiffContrast.scrimForControl - .05f).compositeOver(white)) < RiffContrast.control)
    }

    @Test fun wordsSetOverACoverReadOnItsTone() {
        // A collection's and Downloads' title block: the cover, then its tone at 90%, then the words.
        pictures.forEach { cover ->
            val behind = cover.readableArtworkTone().copy(alpha = RiffContrast.toneUnderWords).compositeOver(white)
            atLeast(RiffContrast.control, White, behind, "the title (28 sp)")
            atLeast(RiffContrast.text, White.copy(alpha = .8f), behind, "the artist")
            atLeast(RiffContrast.text, White.copy(alpha = .76f), behind, "the description")
        }
    }

    @Test fun aSpeedDialTitleReadsOverAWhiteCover() {
        // The title is 11 sp with 8 dp below it: its top edge is about 22 dp up a 42 dp scrim.
        val top = 1f - 22f / SPEED_DIAL_SCRIM_DP
        for (fraction in listOf(top, (top + 1f) / 2f, 1f - 8f / SPEED_DIAL_SCRIM_DP))
            atLeast(RiffContrast.text, White, at(speedDialScrim, fraction).compositeOver(white), "a tile title at $fraction")
    }

    @Test fun homeReadsOverAnyPhotograph() {
        for (fraction in listOf(0f, .15f, .3f, .6f, 1f))
            pictures.forEach { photo ->
                val behind = at(moodScrim, fraction).compositeOver(photo)
                atLeast(RiffContrast.text, Muted, behind, "a shelf's second line at $fraction")
                atLeast(RiffContrast.text, White, behind, "a heading at $fraction")
            }
    }

    /** AmbientBackdrop's layers at one point of a [width] x [height] screen, as it draws them. */
    private fun ambient(palette: AmbientPalette, x: Float, y: Float, width: Float, height: Float): Color {
        fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)
        fun glow(colour: Color, alpha: Float, cx: Float, cy: Float, radius: Float): Color {
            val away = kotlin.math.hypot(x * width - cx * width, y * height - cy * height) / (radius * height)
            return colour.copy(alpha = alpha * (1f - away).coerceIn(0f, 1f))
        }
        val top = palette.top.copy(alpha = .74f); val middle = palette.middle.copy(alpha = .42f)
        val wash = when { y <= .18f -> mix(top, middle, y / .18f); y <= .53f -> mix(middle, middle.copy(alpha = 0f), (y - .18f) / .35f); else -> Color.Transparent }
        return listOf(wash, glow(palette.accent, .62f, .08f, .10f, .36f), glow(palette.middle, .58f, .92f, .03f, .36f), glow(palette.top, .36f, .55f, .22f, .31f))
            .fold(Color.Black) { behind, layer -> layer.compositeOver(behind) }
    }

    @Test fun everyAmbientPaletteIsDarkEnoughEverywhere() {
        // A phone upright and on its side, at every twentieth of the way across and down.
        for ((width, height) in listOf(360f to 800f, 800f to 360f)) ambientPalettes.forEach { palette ->
            for (column in 0..20) for (row in 0..20) {
                val behind = ambient(palette, column / 20f, row / 20f, width, height)
                atLeast(RiffContrast.text, Muted, behind, "muted words"); atLeast(RiffContrast.text, White, behind, "white words")
                atLeast(RiffContrast.control, Pink, behind, "a chosen mark")
            }
        }
    }

    @Test fun theSolidFallbackReadsWithoutAnyPicture() {
        // High-contrast controls: the bars and chips are these colours with nothing showing through.
        for (surface in listOf(Ink, Raised, Color(0xFF1D2633))) {
            atLeast(RiffContrast.text, White, surface, "white words"); atLeast(RiffContrast.text, Muted, surface, "muted words")
            atLeast(RiffContrast.text, Pink, surface, "the chosen one")
        }
    }
}
