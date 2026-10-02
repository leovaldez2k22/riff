package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

class TabHitTestTest {
    @Test fun tapsMapToLogicalTabsInBothDirections() {
        val width = 400f
        val search = 60f
        val gap = 8f
        assertEquals(0, TabHitTest.indexAt(12f, width, search, gap, false))
        assertEquals(3, TabHitTest.indexAt(320f, width, search, gap, false))
        assertEquals(4, TabHitTest.indexAt(375f, width, search, gap, false))
        assertEquals(0, TabHitTest.indexAt(388f, width, search, gap, true))
        assertEquals(3, TabHitTest.indexAt(80f, width, search, gap, true))
        assertEquals(4, TabHitTest.indexAt(25f, width, search, gap, true))
    }
}
