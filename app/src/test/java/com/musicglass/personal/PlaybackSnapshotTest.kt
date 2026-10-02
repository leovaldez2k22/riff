package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class PlaybackSnapshotTest {
    private val queue=(0..6).map {SavedQueueItem("yt:abcdefghij$it","Track $it","Artist",album="Album",albumId="MPRE_album")}
    @Test fun fourthAlbumSongKeepsPreviousAndFollowingSongsInOrder() {
        val saved=PlaybackSnapshot(queue,3,41234,true,2)
        assertEquals(saved,decodePlaybackSnapshot(encodePlaybackSnapshot(saved)))
        val restored=decodePlaybackSnapshot(encodePlaybackSnapshot(saved))!!
        assertEquals(listOf("Track 0","Track 1","Track 2"),restored.queue.take(restored.index).map {it.title})
        assertEquals("Track 3",restored.queue[restored.index].title)
        assertEquals(listOf("Track 4","Track 5","Track 6"),restored.queue.drop(restored.index+1).map {it.title})
    }
    @Test fun repeatedTrackOccurrencesAndQueueEditsSurviveRestoration() {
        val edited=listOf(queue[2],queue[1],queue[2],queue[6])
        val restored=decodePlaybackSnapshot(encodePlaybackSnapshot(PlaybackSnapshot(edited,2,12000)))!!
        assertEquals(edited,restored.queue);assertEquals(2,restored.index)
    }
    @Test fun liveBroadcastRestoresAtLiveEdge() {
        val item=queue[0].copy(live=true)
        assertEquals(0L,decodePlaybackSnapshot(encodePlaybackSnapshot(PlaybackSnapshot(listOf(item),0,12000)))!!.positionMs)
    }
    @Test fun corruptAndUntrustedLocalSessionsAreRejected() {
        assertNull(decodePlaybackSnapshot("invalid"))
        assertNull(decodePlaybackSnapshot(encodePlaybackSnapshot(PlaybackSnapshot(listOf(SavedQueueItem("local:1","x","x",localUri="https://example.com/file")),0,0))))
    }
}
