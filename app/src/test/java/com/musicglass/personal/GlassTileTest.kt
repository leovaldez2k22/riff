package com.musicglass.personal

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassTileTest {
    @Test fun chipsStayPillsAndTilesKeepTheirCorner() {
        val chip = Rect(0f, 0f, 200f, 90f)
        assertEquals(45f, glassCornerRadius(chip, null), 0f)
        assertEquals(45f, glassCornerRadius(chip, 0f), 0f)
        val tile = Rect(0f, 0f, 480f, 160f)
        assertEquals(44f, glassCornerRadius(tile, 44f), 0f)
        assertEquals(80f, glassCornerRadius(tile, 500f), 0f) // Never past a pill.
    }
}
