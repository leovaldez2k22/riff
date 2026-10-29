package com.musicglass.personal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 1: the glass's numbers per role, the settings over them, and the quality levels. */
class RiffGlassTest {
    @Test fun theStartingValuesAreThePlans() {
        val bar = glassSpec(GlassRole.Bar, GlassStyle())
        assertEquals(2f, bar.blur); assertEquals(20f, bar.rim); assertEquals(36f, bar.bend); assertEquals(1f, bar.dispersion)
        val lens = glassSpec(GlassRole.Lens, GlassStyle())
        // Phone checks 29 Oct (bug 8): the tab bar's lens magnifies without bending, so its labels no longer curl.
        assertEquals(0f, lens.blur); assertEquals(0f, lens.bend); assertEquals(Color.Transparent, lens.tint)
        val menu = glassSpec(GlassRole.Menu, GlassStyle())
        assertEquals(20f, menu.blur); assertEquals(0f, menu.depth); assertEquals(.25f, menu.tint.alpha, .01f)
    }

    @Test fun intensityBendsMoreOrLess() {
        assertEquals(18f, glassSpec(GlassRole.Bar, GlassStyle(intensity = .5f)).bend)
        assertEquals(54f, glassSpec(GlassRole.Bar, GlassStyle(intensity = 1.5f)).bend)
        assertEquals("never past 1.5", 54f, glassSpec(GlassRole.Bar, GlassStyle(intensity = 9f)).bend)
    }

    @Test fun frostedBlursAndTintsButNotTheLens() {
        val frosted = glassSpec(GlassRole.Bar, GlassStyle(frosted = true))
        assertEquals(16f, frosted.blur); assertEquals(.16f, frosted.tint.alpha, .01f)
        assertEquals(0f, glassSpec(GlassRole.Lens, GlassStyle(frosted = true)).blur)
    }

    @Test fun qualityLevels() {
        assertEquals(0f, glassSpec(GlassRole.Bar, GlassStyle(quality = GlassQuality.Reduced)).dispersion)
        assertTrue(glassSpec(GlassRole.Bar, GlassStyle(quality = GlassQuality.Reduced)).lens)
        val frost = glassSpec(GlassRole.Bar, GlassStyle(quality = GlassQuality.Frost))
        assertFalse(frost.lens); assertTrue(frost.blur >= 12f)
        assertEquals("the drag lens stays clear", 0f, glassSpec(GlassRole.Lens, GlassStyle(quality = GlassQuality.Frost)).blur)
        assertEquals(GlassQuality.Solid, glassQuality(36, solid = true, powerSave = false, thermalHeadroom = null))
        assertEquals(GlassQuality.Frost, glassQuality(31, solid = false, powerSave = false, thermalHeadroom = null))
        assertEquals(GlassQuality.Frost, glassQuality(36, solid = false, powerSave = false, thermalHeadroom = 1f))
        assertEquals("warm, not throttling", GlassQuality.Reduced, glassQuality(36, solid = false, powerSave = false, thermalHeadroom = .95f))
        assertEquals(GlassQuality.Reduced, glassQuality(36, solid = false, powerSave = true, thermalHeadroom = .3f))
        assertEquals(GlassQuality.Full, glassQuality(36, solid = false, powerSave = false, thermalHeadroom = .3f))
    }

    @Test fun theGlassReadsFarEnoughForItsBlur() {
        assertEquals(2f, glassReach(0f))
        // Android's sigma for a 20 px radius is 12.05 px: three of them, and two more pixels.
        assertEquals(39f, glassReach(20f))
    }
}
