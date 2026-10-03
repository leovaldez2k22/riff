package com.musicglass.personal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FeedSnapshotStoreTest {
    private val song = ProviderHomeCard("Song A", "Artist", "https://lh3.googleusercontent.com/abc=w544-h544", videoId = "dQw4w9WgXcQ",
        kind = CatalogKind.Song, artistId = "UC123", albumId = "MPRE1", album = "Album", musicVideo = true, setVideoId = "SV1", addedAt = 42L)
    private val playlist = ProviderHomeCard("Mix", "YouTube Music", "https://i.ytimg.com/vi/x/hqdefault.jpg", browseId = "VLPL1", kind = CatalogKind.Playlist)
    private val feed = listOf(ProviderHomeSection("Quick picks", listOf(song, playlist)), ProviderHomeSection("Albums for you", listOf(playlist)))

    private fun store(now: () -> Long = { 1_000_000L }): Pair<FeedSnapshotStore, File> {
        val dir = Files.createTempDirectory("feed").toFile()
        return FeedSnapshotStore(dir, now) to dir
    }

    @Test fun roundTripKeepsEveryCardField() {
        assertEquals(FeedSnapshot(feed, 7L), decodeFeedSnapshot(encodeFeedSnapshot(feed, 7L)))
    }

    @Test fun nothingSecretOrStreamableIsWritten() {
        val leaky = song.copy(artworkUrl = "https://rr1---sn-x.googlevideo.com/videoplayback?sig=AB&pot=XY")
        val text = encodeFeedSnapshot(listOf(ProviderHomeSection("x", listOf(leaky, song.copy(artworkUrl = "http://insecure/a.jpg")))), 1L)
        for (bad in listOf("googlevideo", "sig=", "pot=", "SAPISID", "http://insecure")) assertFalse(bad, text.contains(bad))
        assertEquals(listOf(null, null), decodeFeedSnapshot(text)!!.sections[0].cards.map { it.artworkUrl })
    }

    @Test fun saveThenLoadPerAccountAndKey() {
        val (s, dir) = store()
        s.save("account-a", "home:", feed)
        assertEquals(feed, s.load("account-a", "home:")!!.sections)
        assertNull("other account", s.load("account-b", "home:"))
        assertNull("other key", s.load("account-a", "home:Chill"))
        dir.deleteRecursively()
    }

    @Test fun signOutDeletesOnlyThatAccount() {
        val (s, dir) = store()
        s.save("account-a", "home:", feed); s.save("account-b", "home:", feed)
        s.deleteScope("account-a")
        assertNull(s.load("account-a", "home:")); assertNotNull(s.load("account-b", "home:"))
        dir.deleteRecursively()
    }

    @Test fun weekOldSnapshotsAreNotShown() {
        var now = 1_000_000L
        val (s, dir) = store { now }
        s.save("a", "home:", feed)
        now += FEED_SNAPSHOT_MAX_AGE_MS; assertNotNull(s.load("a", "home:"))
        now += 1; assertNull(s.load("a", "home:"))
        dir.deleteRecursively()
    }

    @Test fun corruptOrForeignFilesAreIgnoredAndRemoved() {
        val (s, dir) = store()
        s.save("a", "home:", feed)
        val file = dir.walkTopDown().first { it.isFile }
        file.writeText("{not json")
        assertNull(s.load("a", "home:")); assertFalse(file.exists())
        assertNull(decodeFeedSnapshot("""{"version":99,"savedAt":1,"sections":[]}"""))
        assertNull("no cards", decodeFeedSnapshot(encodeFeedSnapshot(listOf(ProviderHomeSection("Empty", emptyList())), 1L)))
        dir.deleteRecursively()
    }

    @Test fun emptyAndOversizedFeedsAreNotWritten() {
        val (s, dir) = store()
        s.save("a", "home:", listOf(ProviderHomeSection("Empty", emptyList())))
        val huge = listOf(ProviderHomeSection("Huge", List(5_000) { song.copy(title = "Song $it with a long enough title") }))
        s.save("a", "home:Big", huge)
        assertTrue(dir.walkTopDown().none { it.isFile })
        dir.deleteRecursively()
    }
}
