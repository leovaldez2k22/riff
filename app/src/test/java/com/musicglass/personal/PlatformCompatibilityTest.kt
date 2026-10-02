package com.musicglass.personal

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PlatformCompatibilityTest {
    @Test fun audioPermissionChangesAtAndroid13() {
        listOf(31,32).forEach {assertEquals("android.permission.READ_EXTERNAL_STORAGE",audioLibraryPermission(it))}
        listOf(33,36).forEach {assertEquals("android.permission.READ_MEDIA_AUDIO",audioLibraryPermission(it))}
    }
    @Test fun glassMaskKeepsChromeBoundsAndLimitsCategorySlots() {
        val categories=List(10) {Rect(it*10f,0f,it*10f+8,8f)}
        val shapes=chromeGlassShapes(1080,2400,2.75f,true,false,false,0,categories)
        assertEquals(11,shapes.size)
        assertEquals(44f,shapes[0].left,0f)
        assertEquals(1036f,shapes[1].right,0f)
        assertEquals(62f*2.75f,shapes[2].height,0f)
        assertEquals(8f*2.75f,shapes[0].top-shapes[2].bottom,0f)
        assertEquals(categories.take(8),shapes.drop(3))
    }
    @Test fun noMiniPlayerLeavesItsMaskOffscreen() {
        val shapes=chromeGlassShapes(1080,2400,2.75f,false,true,true,50,emptyList())
        assertTrue(shapes[2].right<0)
        assertEquals(46f*2.75f,shapes[0].height,0f)
    }
}
