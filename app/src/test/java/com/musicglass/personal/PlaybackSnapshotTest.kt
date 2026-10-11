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
    @Test fun writesTheNamedSecondVersionAndStillReadsTheFirst() {
        val saved=PlaybackSnapshot(queue,3,41234,true,2)
        val text=encodePlaybackSnapshot(saved)
        assertTrue(text.contains("\"format\":\"riff.playback-snapshot\"")&&text.contains("\"version\":2"))
        val first=text.replace("\"format\":\"riff.playback-snapshot\",","").replace("\"version\":2","\"version\":1")
        assertEquals(SnapshotDecode.Ok(saved),decodePlaybackSnapshotDetailed(first))
    }
    @Test fun newerAndUnknownVersionsAreToldApartFromDamage() {
        val text=encodePlaybackSnapshot(PlaybackSnapshot(queue,0,0))
        assertEquals(SnapshotDecode.Newer(3),decodePlaybackSnapshotDetailed(text.replace("\"version\":2","\"version\":3")))
        assertNull(decodePlaybackSnapshot(text.replace("\"version\":2","\"version\":3")))
        assertEquals(SnapshotDecode.Invalid("version"),decodePlaybackSnapshotDetailed(text.replace("\"version\":2","\"version\":0")))
        assertEquals(SnapshotDecode.Invalid("version"),decodePlaybackSnapshotDetailed(text.replace("riff.playback-snapshot","someone.else")))
        assertEquals(SnapshotDecode.Invalid("json"),decodePlaybackSnapshotDetailed("{not json"))
        assertEquals(SnapshotDecode.Invalid("empty"),decodePlaybackSnapshotDetailed("{\"format\":\"riff.playback-snapshot\",\"version\":2,\"queue\":[]}"))
    }
    @Test fun sizeIsMeasuredInBytesNotCharacters() {
        assertEquals(1,utf8Size("a"));assertEquals(2,utf8Size("\u00e9"));assertEquals(3,utf8Size("\u65e5"));assertEquals(4,utf8Size("\uD83C\uDFB5"))
        // Under the ceiling in characters, over it in bytes.
        val wide="\u65e5".repeat(MAX_SNAPSHOT_BYTES/3+1)
        assertTrue(wide.length<MAX_SNAPSHOT_BYTES&&utf8Size(wide)>MAX_SNAPSHOT_BYTES)
        assertEquals(SnapshotDecode.Invalid("size"),decodePlaybackSnapshotDetailed("{\"version\":1,\"note\":\"$wide\",\"queue\":[]}"))
    }
    @Test fun twoThousandSongsRoundTripAndALongerQueueKeepsTheFirstTwoThousand() {
        val full=PlaybackSnapshot((0 until MAX_SNAPSHOT_ITEMS).map {SavedQueueItem("yt:fixture%04d".format(it),"Track $it","Artist")},1999,5)
        assertEquals(full,decodePlaybackSnapshot(encodePlaybackSnapshotBounded(full)!!))
        val over=full.copy(queue=full.queue+SavedQueueItem("yt:fixture2000","One more","Artist"))
        assertEquals(full,decodePlaybackSnapshot(encodePlaybackSnapshotBounded(over)!!))
    }
    @Test fun unsafeIdentifiersInAStoredQueueAreRejected() {
        fun stored(item:SavedQueueItem)=decodePlaybackSnapshotDetailed(encodePlaybackSnapshot(PlaybackSnapshot(listOf(item),0,0)))
        assertEquals(SnapshotDecode.Invalid("item"),stored(SavedQueueItem("local:1","x","x",localUri="content://media/external/audio/media/2")))
        assertEquals(SnapshotDecode.Invalid("item"),stored(SavedQueueItem("local:1","x","x",localUri="content://com.other.provider/audio/1")))
        // A malformed browse identifier is dropped; the song itself is still restorable.
        assertNull((stored(queue[0].copy(albumId="../../etc",artistId="a b")) as SnapshotDecode.Ok).snapshot.queue.single().let {it.albumId?:it.artistId})
        assertTrue(stored(SavedQueueItem("local:1","x","x",localUri="content://media/external/audio/media/1")) is SnapshotDecode.Ok)
    }
}
