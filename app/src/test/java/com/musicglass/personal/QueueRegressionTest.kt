package com.musicglass.personal
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
class QueueRegressionTest {
    @Test fun officialVideoAndAudioShareIdentityButDistinctRemixesDoNot() {
        assertEquals(queueRecordingKey("Song (Official Video)","Singer"),queueRecordingKey("Song [Official Audio]","Singer"))
        assertNotEquals(queueRecordingKey("Song (Remix)","Singer"),queueRecordingKey("Song","Singer"))
        assertNotEquals(queueRecordingKey("Song","Singer"),queueRecordingKey("Song","Another Singer"))
        val same=listOf(ProviderTrack("video000001","Song (Official Video)","Singer"),ProviderTrack("video000002","Song [Official Audio]","Singer"))
        assertEquals(queueRecordingKey("lovely","Billie Eilish"),queueRecordingKey("lovely (Official Video)","Billie Eilish & Khalid"))
        assertEquals(1,queueCandidates(same,emptySet()).size)
        assertTrue(queueCandidates(same,emptySet(),setOf(queueRecordingKey("Song","Singer"))).isEmpty())
    }
    @Test fun continuationTracksPreserveAuthoredOrderAndRepeats() {
        val root=Json.parseToJsonElement("""{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[
          {"playlistPanelVideoRenderer":{"videoId":"video000001","title":{"simpleText":"One"}}},
          {"playlistPanelVideoRenderer":{"videoId":"video000002","title":{"simpleText":"Two"}}},
          {"playlistPanelVideoRenderer":{"videoId":"video000001","title":{"simpleText":"One"}}}
        ]}}}""")
        assertEquals(listOf("video000001","video000002","video000001"),catalogPage(root).albumTracks.map {it.videoId})
    }
    @Test fun largePlaylistSnapshotKeepsSelectedFourthTrackAndEntireOrder() {
        val tracks=(0 until 750).map {SavedQueueItem("yt:video%06d".format(it),"Track $it","Singer")}
        val saved=PlaybackSnapshot(tracks,3,42000,collectionTitle="Playlist")
        val restored=decodePlaybackSnapshot(encodePlaybackSnapshot(saved))!!
        assertEquals(saved,restored);assertEquals("Track 3",restored.queue[restored.index].title)
        assertEquals(listOf("Track 0","Track 1","Track 2"),restored.queue.take(restored.index).map {it.title})
    }
    @Test fun searchHistoryNormalizesDeduplicatesAndMovesLatestToFront() {
        assertEquals(listOf("New Query","First"),updatedSearchHistory(listOf("First","new query"),"  New   Query  "))
        assertEquals(listOf("First"),updatedSearchHistory(listOf("First"),"   "))
        assertEquals(30,updatedSearchHistory((1..30).map {"Query $it"},"Newest").size)
    }
    @Test fun oldRadioDuplicatesAreRemovedWhileCurrentVideoAndPositionArePreserved() {
        val queue=listOf(SavedQueueItem("yt:video000001","Song","Singer"),SavedQueueItem("yt:video000002","Other","Singer"),SavedQueueItem("yt:video000003","Song (Official Video)","Singer"))
        val radio=PlaybackSnapshot(queue,2,96425,radioSeed="video000001")
        val result=decodePlaybackSnapshot(encodePlaybackSnapshot(radio))!!
        assertEquals(listOf("yt:video000002","yt:video000003"),result.queue.map {it.mediaId})
        assertEquals(1,result.index);assertEquals(96425L,result.positionMs)
        assertEquals(3,decodePlaybackSnapshot(encodePlaybackSnapshot(radio.copy(radioSeed=null)))!!.queue.size)
    }
    @Test fun collectionPaginationIgnoresRadioAndRecommendationsOutsideTheTrackShelf() {
        val root=Json.parseToJsonElement("""{"header":{"nextRadioContinuationData":{"continuation":"radio-token"}},"contents":{"musicPlaylistShelfRenderer":{"contents":[],"continuations":[{"nextContinuationData":{"continuation":"playlist-token"}}]}}}""")
        assertEquals("playlist-token",catalogPage(root).collectionNext)
        val finished=Json.parseToJsonElement("""{"header":{"nextRadioContinuationData":{"continuation":"radio-token"}},"contents":{"musicPlaylistShelfRenderer":{"contents":[]}}}""")
        assertNull(catalogPage(finished).collectionNext)
    }
    @Test fun modernAppendContinuationLoadsTracksAndItsOwnNextPage() {
        val root=Json.parseToJsonElement("""{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"targetId":"music-playlist-shelf","continuationItems":[
          {"playlistPanelVideoRenderer":{"videoId":"video000101","title":{"simpleText":"One"}}},
          {"playlistPanelVideoRenderer":{"videoId":"video000102","title":{"simpleText":"Two"}}},
          {"playlistPanelVideoRenderer":{"videoId":"video000101","title":{"simpleText":"One"}}},
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"playlist-page-3"}}}}
        ]}}]}""")
        val page=catalogPage(root)
        assertEquals(listOf("video000101","video000102","video000101"),page.albumTracks.map {it.videoId})
        assertEquals("playlist-page-3",page.collectionNext)
    }
}
