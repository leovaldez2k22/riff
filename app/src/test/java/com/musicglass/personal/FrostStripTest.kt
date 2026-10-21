package com.musicglass.personal

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q18-030: frosted glass is blurred only in strips around the glass, on a copy at half size. */
class FrostStripTest {
    private val density = 2.75f

    @Test fun theBottomChromeAndTheChipsEachGetAStripGrownByTheMargin() {
        val chips = listOf(Rect(40f, 300f, 300f, 400f), Rect(320f, 300f, 600f, 400f))
        val shapes = chromeGlassShapes(1080, 2400, density, true, false, false, 0, chips)
        val bands = frostBands(shapes, 1080, 2400, 50f)
        assertEquals(2, bands.size)
        val bottom = bands[0]
        assertEquals(kotlin.math.floor(shapes[2].top - 50f), bottom.top, 0f) // the mini player is the highest
        assertEquals(2400f, bottom.bottom, 0f) // clipped to the screen
        assertEquals(0f, bottom.left, 0f)
        assertEquals(Rect(0f, 250f, 650f, 450f), bands[1])
        for (band in bands) for (edge in listOf(band.left, band.top, band.right, band.bottom)) assertEquals(edge, kotlin.math.floor(edge), 0f)
        // The strips together are far less than the screen: the reason for them.
        assertTrue(bands.sumOf { (it.width * it.height).toDouble() } < .3 * 1080 * 2400)
    }

    @Test fun stripsThatTouchBecomeOne() {
        val shapes = chromeGlassShapes(1080, 2400, density, true, false, false, 0, listOf(Rect(40f, 1900f, 300f, 1980f)))
        val bands = frostBands(shapes, 1080, 2400, 60f)
        assertEquals(1, bands.size)
        assertEquals(kotlin.math.floor(1900f - 60f), bands[0].top, 0f)
    }

    @Test fun anOffscreenMiniPlayerAndChipsOutOfViewAddNothing() {
        val shapes = chromeGlassShapes(1080, 2400, density, false, false, false, 0, listOf(Rect(40f, -400f, 300f, -300f)))
        val bands = frostBands(shapes, 1080, 2400, 40f)
        assertEquals(1, bands.size)
        assertEquals(kotlin.math.floor(shapes[0].top - 40f), bands[0].top, 0f)
        assertTrue(frostBands(shapes.map { Rect(-1000f, -1000f, -999f, -999f) }, 1080, 2400, 40f).isEmpty())
    }

    @Test fun theMarginCoversTheRimsBendAndThreeSigmasOfTheBlur() {
        val bend = 3f * density * 1f * FROST_BEND
        val sigma = .57735f * frostBlurRadius(density, .2f) + .5f
        assertEquals(bend + 3f * sigma + 2f, frostMargin(density, 1f, .2f), .001f)
        assertTrue(frostMargin(density, 1.5f, 1.5f) > frostMargin(density, 1f, .2f))
    }

    @Test fun theHalfSizeBlurKeepsTheSameSoftness() {
        for (radius in listOf(12.1f, 60.5f, 90.75f)) {
            val full = .57735f * radius + .5f
            val half = .57735f * halfSizeBlurRadius(radius) + .5f
            assertEquals(full / FROST_SCALE, half, .001f)
        }
        assertTrue(halfSizeBlurRadius(.1f) > 0f) // Android refuses a blur of zero
    }
}
