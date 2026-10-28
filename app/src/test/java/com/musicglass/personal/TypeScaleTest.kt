package com.musicglass.personal

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 3: Inter's variable font and the type scale built on it. */
class TypeScaleTest {
    /** The variable axes of a TrueType font: tag to (min, max). */
    private fun axes(file: File): Map<String, Pair<Float, Float>> {
        val b = ByteBuffer.wrap(file.readBytes())
        val tables = (0 until b.getShort(4).toInt()).associate { i ->
            String(ByteArray(4) { b.get(12 + 16 * i + it) }) to b.getInt(12 + 16 * i + 8)
        }
        val fvar = tables.getValue("fvar")
        val offset = fvar + b.getShort(fvar + 4).toInt(); val count = b.getShort(fvar + 8).toInt(); val size = b.getShort(fvar + 10).toInt()
        return (0 until count).associate { i ->
            val p = offset + i * size
            String(ByteArray(4) { b.get(p + it) }) to (b.getInt(p + 4) / 65536f to b.getInt(p + 12) / 65536f)
        }
    }

    @Test fun theBundledFontHasTheTextAndDisplaySizes() {
        val axes = axes(File("src/main/res/font/inter_variable.ttf"))
        assertEquals(14f to 32f, axes["opsz"])
        val weight = axes.getValue("wght"); assertTrue(weight.first <= 300f && weight.second >= 800f)
    }

    @Test fun displaySizesUseInterDisplayAndTightenTheirTracking() {
        val styles = listOf(RiffText.largeTitle, RiffText.sectionHeading, RiffText.headline, RiffText.rowTitle, RiffText.body,
            RiffText.subtitle, RiffText.settingsRow, RiffText.settingsHelp)
        styles.forEach { style ->
            val display = style.fontSize.value >= 20f
            assertEquals("${style.fontSize}", if (display) InterDisplayFamily else InterFamily, style.fontFamily)
            assertTrue("${style.fontSize} is tracked in", style.letterSpacing.value < 0f)
        }
        assertEquals(-.9f, RiffText.largeTitle.letterSpacing.value)
        assertEquals(34f, RiffText.largeTitle.fontSize.value)
    }
}
