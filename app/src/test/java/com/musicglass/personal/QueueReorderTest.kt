package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Feature 18: the queue takes a new order only if it is still the queue the order was made from. */
class QueueReorderTest {
    private val now = listOf("a", "b", "now", "c", "d", "e")

    @Test fun anOrderOfTheSongsToComeIsTaken() {
        assertEquals(listOf("a", "b", "now", "e", "c", "d"), checkedReorder(now, now, listOf("a", "b", "now", "e", "c", "d"), 2, emptySet()))
    }

    @Test fun aChangedQueueOrOtherSongsAreRefused() {
        assertNull("queue changed", checkedReorder(now + "f", now, now.reversed(), 2, emptySet()))
        assertNull("other songs", checkedReorder(now, now, listOf("a", "b", "now", "c", "d", "x"), 2, emptySet()))
        assertNull("a song twice", checkedReorder(now, now, listOf("a", "b", "now", "c", "c", "e"), 2, emptySet()))
        assertNull("blank entries", checkedReorder(listOf("a", ""), listOf("a", ""), listOf("a", ""), 0, emptySet()))
    }

    @Test fun nothingUpToTheSongPlayingMovesAndPinnedSongsStay() {
        assertNull("history moved", checkedReorder(now, now, listOf("b", "a", "now", "c", "d", "e"), 2, emptySet()))
        assertNull("song playing moved", checkedReorder(now, now, listOf("a", "b", "c", "now", "d", "e"), 2, emptySet()))
        assertNull("pinned moved", checkedReorder(now, now, listOf("a", "b", "now", "d", "c", "e"), 2, setOf("c")))
        assertEquals(listOf("a", "b", "now", "c", "e", "d"), checkedReorder(now, now, listOf("a", "b", "now", "c", "e", "d"), 2, setOf("c")))
    }
}
