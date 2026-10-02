package com.musicglass.personal
import org.junit.Assert.*
import org.junit.Test
class ArtworkPaletteTest {
    @Test fun gradientPopulationWinsOverASmallFlatPatch() {
        val purple=(0..999).map {val v=it%40;((115+v) shl 16)or((35+v/3) shl 8)or(125+v)}
        val pixels=(purple+List(250){0x997755}).toIntArray()
        val p=dominantArtworkRgb(pixels)
        assertTrue((p and 255)>((p shr 8)and 255));assertTrue(((p shr 16)and 255)>((p shr 8)and 255))
    }
    @Test fun emptyArtworkHasStableFallback() {assertEquals(0x283444,dominantArtworkRgb(intArrayOf()))}
}
