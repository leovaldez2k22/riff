package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class PlatformCompatibilityTest {
    @Test fun audioPermissionChangesAtAndroid13() {
        listOf(31,32).forEach {assertEquals("android.permission.READ_EXTERNAL_STORAGE",audioLibraryPermission(it))}
        listOf(33,36).forEach {assertEquals("android.permission.READ_MEDIA_AUDIO",audioLibraryPermission(it))}
        assertEquals("android.permission.READ_EXTERNAL_STORAGE",audioLibraryPermission(30))
    }

    @Test fun openLinksSettingsFallsBackToAppInfoOnAndroid11() {
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS",openLinksByDefaultAction(30))
        listOf(31,36).forEach {assertEquals("android.settings.APP_OPEN_BY_DEFAULT_SETTINGS",openLinksByDefaultAction(it))}
    }
}
