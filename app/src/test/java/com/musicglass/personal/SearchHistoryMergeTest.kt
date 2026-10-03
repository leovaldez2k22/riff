package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Test

/** SearchHistory now loads after the screen appears; these are the rules for actions taken before then. */
class SearchHistoryMergeTest {
    private val stored = listOf("lofi beats", "Arijit Singh", "jazz cafe")

    @Test fun nothingDoneMeanwhileKeepsTheStoredList() {
        assertEquals(stored, mergeLoadedHistory(stored, emptyList(), emptySet(), cleared = false))
    }

    @Test fun searchesMadeBeforeLoadComeFirstWithoutDuplicates() {
        assertEquals(listOf("arijit singh", "new query", "lofi beats", "jazz cafe"),
            mergeLoadedHistory(stored, listOf("arijit singh", "new query"), emptySet(), cleared = false))
    }

    @Test fun entriesRemovedBeforeLoadStayRemoved() {
        assertEquals(listOf("lofi beats", "jazz cafe"), mergeLoadedHistory(stored, emptyList(), setOf("Arijit Singh"), cleared = false))
    }

    @Test fun clearBeforeLoadDropsTheStoredListButKeepsLaterSearches() {
        assertEquals(listOf("after clear"), mergeLoadedHistory(stored, listOf("after clear"), emptySet(), cleared = true))
        assertEquals(emptyList<String>(), mergeLoadedHistory(stored, emptyList(), emptySet(), cleared = true))
    }

    @Test fun mergedListIsCappedAtThirty() {
        val many = (1..30).map { "stored $it" }
        val merged = mergeLoadedHistory(many, listOf("fresh 1", "fresh 2"), emptySet(), cleared = false)
        assertEquals(30, merged.size); assertEquals("fresh 1", merged.first()); assertEquals("stored 28", merged.last())
    }
}
