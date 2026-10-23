package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q18-096: scrolling the open queue to its last song loads more songs. */
class QueueLoadMoreTest {
    @Test fun theLastSongOnScreenAsksForMore() {
        assertTrue(queueWantsMore(lastVisible = 5, upNext = 6))
        assertFalse(queueWantsMore(lastVisible = 4, upNext = 6))
        assertFalse("nothing up next: the end text, not a request", queueWantsMore(lastVisible = 0, upNext = 0))
    }

    @Test fun scrollingForMoreDoesNotWaitForTheEndOfPlayback() {
        assertTrue("paused, far from the end", radioShouldExtend(onDemand = true, ready = false, playing = false, remaining = 7, size = 7, busy = false))
        assertFalse("on its own it waits", radioShouldExtend(onDemand = false, ready = true, playing = true, remaining = 7, size = 7, busy = false))
        assertTrue(radioShouldExtend(onDemand = false, ready = true, playing = true, remaining = 4, size = 7, busy = false))
    }

    @Test fun oneFetchAtATimeAndALimit() {
        assertFalse(radioShouldExtend(onDemand = true, ready = true, playing = true, remaining = 1, size = 10, busy = true))
        assertTrue(radioShouldExtend(onDemand = true, ready = true, playing = true, remaining = 1, size = RADIO_QUEUE_LIMIT - 1, busy = false))
        assertFalse(radioShouldExtend(onDemand = true, ready = true, playing = true, remaining = 1, size = RADIO_QUEUE_LIMIT, busy = false))
        assertFalse(radioShouldExtend(onDemand = false, ready = true, playing = true, remaining = 1, size = RADIO_QUEUE_AUTO_LIMIT, busy = false))
    }
}
