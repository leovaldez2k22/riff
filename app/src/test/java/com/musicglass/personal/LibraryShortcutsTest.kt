package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryShortcutsTest {
    @Test fun countsReadNaturally() {
        assertEquals("None yet", songCountLabel(0))
        assertEquals("1 song", songCountLabel(1))
        assertEquals("86 files", songCountLabel(86, "file"))
    }

    @Test fun activeDownloadsComeFirst() {
        assertEquals("42 songs", DownloadSummary(42, 0, emptyList()).label)
        assertEquals("Downloading 3 \u00B7 42 ready", DownloadSummary(42, 3, emptyList()).label)
        assertEquals("Downloading 1", DownloadSummary(0, 1, emptyList()).label)
    }
}
