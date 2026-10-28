package com.musicglass.personal

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 14, section 14.2: the tree Android Auto browses, its pages, and what a choice plays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CarLibraryTest {
    private fun track(n: Int) = ProviderTrack("song%07d".format(n), "Song $n", "Artist $n")
    private fun card(n: Int) = ProviderHomeCard("Song $n", "Artist $n", null, videoId = "song%07d".format(n))
    private val album = ProviderHomeCard("An album", "Artist", null, browseId = "MPREalbum1", kind = CatalogKind.Album)
    private val podcast = ProviderHomeCard("A show", "Host", null, browseId = "MPSPshow1", kind = CatalogKind.Podcast)

    private class Fake : CarSource {
        override var signedIn = true
        var connected = true
        var homeCalls = 0
        var sections: List<ProviderHomeSection> = emptyList()
        var downloaded: List<ProviderTrack> = emptyList()
        var found: List<ProviderTrack> = emptyList()
        var heard: List<ProviderTrack> = emptyList()
        var songs: Map<String, List<ProviderTrack>> = emptyMap()
        override fun online() = connected
        override suspend fun home(): List<ProviderHomeSection> { homeCalls++; return sections }
        override suspend fun library(kind: String): List<ProviderHomeCard> = emptyList()
        override suspend fun liked(): List<ProviderTrack> = emptyList()
        override suspend fun collection(browseId: String) = songs[browseId]
        override suspend fun downloadedSongs() = downloaded
        override suspend fun recent(limit: Int) = heard.take(limit)
        override suspend fun search(query: String) = found
    }

    private var now = 0L
    private val fake = Fake()
    private val car = CarLibrary(fake) { now }
    private fun ids(parent: String, page: Int = 0, size: Int = 100) = runBlocking { car.children(parent, page, size) }!!.map { it.mediaId }

    @Test fun theRootHasFourTabs() {
        assertEquals(listOf(CAR_HOME, CAR_LIBRARY, CAR_DOWNLOADS, CAR_RECENT), ids(CAR_ROOT))
        assertTrue(runBlocking { car.children(CAR_ROOT, 0, 10) }!!.all { it.mediaMetadata.isBrowsable == true })
        assertNull("not in the tree", runBlocking { car.children("somewhere", 0, 10) })
    }

    @Test fun homeShelvesOpenAsFoldersAndLeavePodcastsToThePhone() {
        fake.sections = listOf(ProviderHomeSection("Quick picks", (1..3).map(::card)), ProviderHomeSection("Shows", listOf(podcast)),
            ProviderHomeSection("Albums", listOf(album)))
        val shelves = runBlocking { car.children(CAR_HOME, 0, 10) }!!
        assertEquals(listOf("Quick picks", "Albums"), shelves.map { it.mediaMetadata.title.toString() })
        val picks = runBlocking { car.children(shelves[0].mediaId, 0, 10) }!!
        assertEquals(3, picks.size); assertTrue(picks.all { it.mediaMetadata.isPlayable == true })
        val albums = runBlocking { car.children(shelves[1].mediaId, 0, 10) }!!
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, albums.single().mediaMetadata.mediaType)
    }

    @Test fun pagesFollowTheCarsSizeAndResultsAreKeptFiveMinutes() {
        fake.sections = listOf(ProviderHomeSection("Many", (1..25).map(::card)))
        val shelf = ids(CAR_HOME).single()
        assertEquals(10, ids(shelf, 0, 10).size); assertEquals(5, ids(shelf, 2, 10).size); assertEquals(0, ids(shelf, 3, 10).size)
        val calls = fake.homeCalls
        ids(CAR_HOME); assertEquals(calls, fake.homeCalls)
        now += 5 * 60_000L; ids(CAR_HOME); assertEquals(calls + 1, fake.homeCalls)
    }

    @Test fun aSongPlaysItsFolderFromThatSong() {
        fake.songs = mapOf("MPREalbum1" to (1..4).map(::track))
        val songs = runBlocking { car.children("riff:c/MPREalbum1", 0, 10) }!!
        val queue = runBlocking { car.queueFor(listOf(MediaItem.Builder().setMediaId(songs[2].mediaId).build()), 0) }!!
        assertEquals(4, queue.tracks.size); assertEquals(2, queue.index)
        assertNull("only Riff's own ids", runBlocking { car.queueFor(listOf(MediaItem.Builder().setMediaId("yt:song0000001").build()), 0) })
    }

    @Test fun voiceSearchPlaysSongsAndAnEmptyRequestPlaysWhatWasHeard() {
        fake.found = (1..3).map(::track); fake.heard = listOf(track(9))
        fun spoken(q: String) = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(q).build()).build()
        assertEquals(3, runBlocking { car.queueFor(listOf(spoken("queen")), 0) }!!.tracks.size)
        assertEquals("song0000009", runBlocking { car.queueFor(listOf(spoken("")), 0) }!!.tracks.single().videoId)
        assertEquals(3, runBlocking { car.search("queen") })
    }

    @Test fun signedOutAndOfflineStillShowTheTabs() {
        fake.signedIn = false; fake.connected = false; fake.downloaded = listOf(track(5))
        val library = runBlocking { car.children(CAR_LIBRARY, 0, 10) }!!.single()
        assertFalse(library.mediaMetadata.isPlayable == true || library.mediaMetadata.isBrowsable == true)
        assertEquals("offline, Home shows Downloads", listOf("song0000005"), runBlocking { car.queueFor(listOf(
            runBlocking { car.children(CAR_HOME, 0, 10) }!!.single()), 0) }!!.tracks.map { it.videoId })
    }
}
