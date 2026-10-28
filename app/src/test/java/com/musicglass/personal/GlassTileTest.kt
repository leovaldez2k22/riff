package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassTileTest {
    @Test fun chipsStayPillsAndTilesKeepTheirCorner() {
        assertEquals(45f, glassRadius(200f, 90f, null), 0f)
        assertEquals(45f, glassRadius(200f, 90f, 0f), 0f)
        assertEquals(44f, glassRadius(480f, 160f, 44f), 0f)
        assertEquals(80f, glassRadius(480f, 160f, 500f), 0f) // Never past a pill.
    }
}
