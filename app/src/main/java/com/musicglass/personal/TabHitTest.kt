package com.musicglass.personal

/** Converts a physical touch coordinate to the logical tab order in either layout direction. */
internal object TabHitTest {
    fun indexAt(x: Float, width: Float, searchWidth: Float, gap: Float,
        rightToLeft: Boolean): Int {
        val mainWidth = (width - searchWidth - gap).coerceAtLeast(0f)
        if (mainWidth <= 0f) return 4
        val logicalX = if (rightToLeft) width - x else x
        if (logicalX >= mainWidth + gap / 2f) return 4
        return ((logicalX.coerceIn(0f, mainWidth - .001f) / mainWidth) * 4f)
            .toInt().coerceIn(0, 3)
    }
}
