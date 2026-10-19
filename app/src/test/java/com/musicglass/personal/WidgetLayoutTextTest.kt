package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Q18-024: the widgets take their words from the string resources, so translations reach them, and pad both sides alike. */
class WidgetLayoutTextTest {
    private val layouts = File("src/main/res/layout").listFiles { f -> f.name.startsWith("widget_") }!!.sortedBy { it.name }

    @Test fun noWidgetWritesItsOwnWords() {
        assertEquals(6, layouts.size)
        for (layout in layouts) {
            val written = Regex("""android:text="([^"@][^"]*)"""").findAll(layout.readText()).map { it.groupValues[1] }.toList()
            assertEquals("${layout.name} writes text instead of a @string", emptyList<String>(), written)
        }
    }

    @Test fun aPaddingAtOneSideHasOneAtTheOther() {
        for (layout in layouts) {
            val text = layout.readText()
            assertEquals(layout.name, Regex("paddingStart=").findAll(text).count(), Regex("paddingEnd=").findAll(text).count())
        }
    }
}
