package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 27: each shortcut's action routes to its target; Resume appears only with a song loaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppShortcutsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun shortcutIntentsRoute() {
        AppShortcut.entries.forEach { assertEquals(it, appShortcutOf(Intent(it.action))) }
        assertNull(appShortcutOf(Intent(Intent.ACTION_MAIN)))
        assertNull(appShortcutOf(null))
    }

    @Test fun deliveredIntentsReachTheInbox() {
        SharedLinkInbox.shortcut.value = null
        deliverIntent(Intent(AppShortcut.Downloads.action))
        assertEquals(AppShortcut.Downloads, SharedLinkInbox.shortcut.value)
        SharedLinkInbox.shortcut.value = null
    }

    @Test fun resumeIsOfferedOnlyWithASong() {
        val manager = context.getSystemService(ShortcutManager::class.java)
        publishAppShortcuts(context, null)
        assertEquals(listOf("search", "shuffleliked", "downloads"), manager.dynamicShortcuts.sortedBy { it.rank }.map { it.id })
        publishAppShortcuts(context, "Help!")
        assertEquals("resume", manager.dynamicShortcuts.minBy { it.rank }.id)
    }

    @Test fun theTileIsActiveOnlyWhilePlaying() {
        assertEquals(Tile.STATE_ACTIVE, tileState(queued = true, playing = true))
        assertEquals(Tile.STATE_INACTIVE, tileState(queued = true, playing = false))
        assertEquals(Tile.STATE_INACTIVE, tileState(queued = false, playing = false))
    }
}
