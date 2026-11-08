package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Feature 22 (owner, Q22): picks are acted on in the order they were tapped. */
class SongSelectionTest {
    private fun t(id: String, live: Boolean = false) = ProviderTrack(id, "Song $id", "Artist", isLive = live)

    @Test fun picksKeepTapOrderAndTwoRowsOfOneSongAreTwoPicks() {
        val s = SongSelection()
        s.toggle("row:1", t("songBBBBBBB")); s.toggle("row:3", t("songDDDDDDD")); s.toggle("row:2", t("songCCCCCCC"))
        assertEquals(listOf("songBBBBBBB", "songDDDDDDD", "songCCCCCCC"), s.tracks.map { it.videoId })
        s.toggle("row:7", t("songBBBBBBB"))
        assertEquals(4, s.count)
        assertTrue("row:7" in s && "row:1" in s)
        s.toggle("row:3", t("songDDDDDDD"))
        assertEquals(listOf("songBBBBBBB", "songCCCCCCC", "songBBBBBBB"), s.tracks.map { it.videoId })
        s.clear(); assertFalse(s.active)
    }

    @Test fun playNextLeavesOutQueuedSongsRepeatsAndLiveStreams() {
        val plan = playNextPlan(listOf(t("songBBBBBBB"), t("songDDDDDDD"), t("songBBBBBBB"), t("songAAAAAAA"), t("liveLLLLLLL", live = true)),
            listOf("yt:songAAAAAAA", "local:7"))
        assertEquals(listOf("songBBBBBBB", "songDDDDDDD"), plan.tracks.map { it.videoId })
        assertEquals(3, plan.skipped)
        assertEquals(PlayNextPlan(emptyList(), 0), playNextPlan(emptyList(), emptyList()))
    }
}
