package com.musicglass.personal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q18-059: the next song's stream is looked up while this one plays, so a skip does not wait for it. */
class LookAheadTest {
    @Test fun theNextCatalogSongIsLookedUpWhileASongPlays() {
        assertTrue(lookAheadWanted(true, "yt:aaaaaaaaaaa", "catalog", "yt:bbbbbbbbbbb", false))
    }

    @Test fun nothingIsLookedUpWhilePausedOrForWhatNeedsNoLookup() {
        assertFalse(lookAheadWanted(false, "yt:aaaaaaaaaaa", "catalog", "yt:bbbbbbbbbbb", false)) // paused or buffering
        assertFalse(lookAheadWanted(true, "yt:aaaaaaaaaaa", "file", "yt:bbbbbbbbbbb", false)) // a download
        assertFalse(lookAheadWanted(true, "yt:aaaaaaaaaaa", "https", "yt:bbbbbbbbbbb", false)) // already resolved
        assertFalse(lookAheadWanted(true, "yt:aaaaaaaaaaa", "catalog", "yt:bbbbbbbbbbb", true)) // live: its address does not keep
        assertFalse(lookAheadWanted(true, "yt:aaaaaaaaaaa", "catalog", "yt:aaaaaaaaaaa", false)) // the same song again
        assertFalse(lookAheadWanted(true, "yt:aaaaaaaaaaa", null, "local:42", false)) // a file on the phone
    }

    @Test fun theLookupWaitsForTheStartedSongFirst() {
        assertTrue(LOOK_AHEAD_DELAY_MS in 1_000L..10_000L)
    }
}
