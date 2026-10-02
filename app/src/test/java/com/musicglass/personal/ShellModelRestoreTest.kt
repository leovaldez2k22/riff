package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShellModelRestoreTest {
    @Test fun emptyAndMalformedSavedNavigationStartWithoutInventedPlayback() {
        val empty = ShellModel.Saver.restore(emptyList())!!
        assertEquals("Home", empty.tab)
        assertFalse(empty.miniVisible)
        val malformed = ShellModel.Saver.restore(listOf("Unknown", "true", "query", "Unknown", "999"))!!
        assertEquals("Home", malformed.tab)
        assertEquals("All", malformed.libraryFilter)
        assertFalse(malformed.miniVisible)
    }

    @Test fun upgradingDropsRetiredRoutesAndPreservesRealSearchAndAccountNavigation() {
        val old = ShellModel.Saver.restore(listOf("Samples", "true", "Tum Hi Ho", "Albums", "0",
            "album,gallery,account", "podcast_show", "", "device"))!!
        assertEquals("Podcasts", old.tab)
        assertEquals("Tum Hi Ho", old.searchQuery)
        assertEquals("All", old.libraryFilter)
        assertEquals(listOf("account"), old.stacks["Home"])
        assertEquals(emptyList<String>(), old.stacks["Podcasts"])
        assertEquals(listOf("device"), old.stacks["Library"])
        assertFalse(old.miniVisible)
    }
}