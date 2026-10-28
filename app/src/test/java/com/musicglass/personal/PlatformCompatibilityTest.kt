package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class PlatformCompatibilityTest {
    @Test fun audioPermissionChangesAtAndroid13() {
        listOf(31,32).forEach {assertEquals("android.permission.READ_EXTERNAL_STORAGE",audioLibraryPermission(it))}
        listOf(33,36).forEach {assertEquals("android.permission.READ_MEDIA_AUDIO",audioLibraryPermission(it))}
    }
}
