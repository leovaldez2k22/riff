package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SearchBestSongTest {
    private fun song(id: String, title: String, by: String) = ProviderHomeCard(title, by, null, videoId = id)
    private fun artist(name: String) = ProviderHomeCard(name, "Artist", null, browseId = "UC$name", kind = CatalogKind.Artist)
    private val stars = song("ats", "All the Stars", "Kendrick Lamar & SZA")
    private val cover = song("cover", "All the Stars (cover)", "Someone")

    @Test fun theSongMissingFromAllGoesFirst() {
        val all = listOf(cover, song("x", "Stars", "Other"))
        assertEquals(listOf("ats", "cover", "x"), searchWithBestSong(all, listOf(stars), "all the stars").map { it.videoId })
    }

    @Test fun aSongFurtherDownMovesUpAndIsNotShownTwice() {
        val all = listOf(cover, stars)
        assertEquals(listOf("ats", "cover"), searchWithBestSong(all, listOf(stars), "all the stars").map { it.videoId })
    }

    @Test fun theArtistInTheQueryCounts() {
        val song = song("abba", "Slipping Through My Fingers", "ABBA")
        assertEquals("abba", searchWithBestSong(listOf(artist("ABBA")), listOf(song), "slipping through my fingers abba").first().videoId)
    }

    @Test fun anArtistThatIsExactlyTheQueryKeepsTheTop() {
        val all = listOf(artist("ABBA"), song("w", "Waterloo", "ABBA"))
        val merged = searchWithBestSong(all, listOf(song("d", "Dancing Queen ABBA", "ABBA")), "abba")
        assertEquals(listOf("ABBA", "Dancing Queen ABBA", "Waterloo"), merged.map { it.title })
    }

    @Test fun aSongWithoutTheWordsChangesNothing() {
        val all = listOf(cover)
        assertSame(all, searchWithBestSong(all, listOf(song("n", "Something Else", "Other")), "all the stars"))
    }

    @Test fun aSongAlreadyFirstChangesNothing() {
        val all = listOf(stars, cover)
        assertSame(all, searchWithBestSong(all, listOf(stars), "all the stars"))
    }
}
