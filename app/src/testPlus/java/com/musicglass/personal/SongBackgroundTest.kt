package com.musicglass.personal

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 17 (Riff+, Q17). Every answer here is invented; nothing reaches the network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SongBackgroundTest {
    @get:Rule val folder = TemporaryFolder()

    private val artistA = "11111111-1111-1111-1111-111111111111"
    private val artistB = "22222222-2222-2222-2222-222222222222"
    private fun recording(id: String, title: String, artistId: String, artist: String, date: String, score: Int = 100) =
        """{"id":"$id","score":$score,"title":"$title","first-release-date":"$date","artist-credit":[{"name":"$artist","artist":{"id":"$artistId","name":"$artist"}}],
           "releases":[{"title":"Album of $title","date":"$date"}]}"""

    /** Answers by what the address asks for; every request is recorded. */
    private class Fake(val routes: Map<String, String>) {
        val asked = mutableListOf<HttpUrl>()
        val get: suspend (HttpUrl) -> String? = { url -> asked += url; routes.entries.firstOrNull { (k, _) -> url.toString().contains(k) }?.value }
    }

    private val happy = mapOf(
        "ws/2/recording?" to """{"recordings":[${recording("r-2", "Glass River", artistA, "The Fixtures", "2019-05-01")},${recording("r-1", "Glass River", artistA, "The Fixtures", "2018-03-02")},
            ${recording("r-9", "Glass River", artistB, "Someone Else", "2001-01-01", 60)}]}""",
        "ws/2/recording/r-1" to """{"relations":[{"type":"performance","work":{"id":"w-1","title":"Glass River"}}]}""",
        "ws/2/work/w-1" to """{"relations":[{"type":"composer","artist":{"name":"Ann Writer"}},{"type":"lyricist","artist":{"name":"Bo Words"}},
            {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q100"}}]}""",
        "ws/2/artist/$artistA" to """{"name":"The Fixtures","disambiguation":"test band","type":"Group","country":"GB","life-span":{"begin":"2010"},
            "relations":[{"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q200"}}]}""",
        "ids=Q100" to """{"entities":{"Q100":{"sitelinks":{"enwiki":{"title":"Glass River (song)"}}}}}""",
        "ids=Q200" to """{"entities":{"Q200":{"sitelinks":{"frwiki":{"title":"The Fixtures"},"enwiki":{"title":"The Fixtures"}}}}}""",
        "en.wikipedia.org/api/rest_v1/page/summary/Glass_River_(song)" to """{"type":"standard","title":"Glass River (song)","extract":"A <b>song</b>  about\n rivers.",
            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Glass_River_(song)"}}}""",
        "fr.wikipedia.org/api/rest_v1/page/summary/The_Fixtures" to """{"type":"standard","title":"The Fixtures","extract":"Un groupe.",
            "content_urls":{"desktop":{"page":"https://evil.example/phish"}}}""")

    @Test fun aSongIsIdentifiedThroughItsRecordingWorkAndArtist() = runBlocking {
        val fake = Fake(happy); var turns = 0
        val found = BackgroundLookup(fake.get, { turns++ }, "fr").find("Glass River (Official Video)", "The Fixtures & A Guest")
        assertEquals(BackgroundStatus.Found, found.status)
        val song = found.song!!
        assertEquals("2018-03-02", song.released); assertEquals("Album of Glass River", song.album)
        assertEquals(listOf("Ann Writer", "Bo Words"), song.writers)
        assertEquals("No French article: English, labelled", true, song.article!!.fallback)
        assertEquals("Plain text, whitespace folded; markup is shown as text, never rendered", "A <b>song</b> about rivers.", song.article!!.extract)
        val artist = found.artist!!
        assertEquals("test band", artist.disambiguation); assertEquals("2010", artist.begin)
        assertEquals(false, artist.article!!.fallback); assertEquals("fr", artist.article!!.lang)
        assertEquals("A link off Wikipedia is replaced by the article's own address", "https://fr.wikipedia.org/wiki/The_Fixtures", artist.article!!.url)
        assertEquals("Every MusicBrainz request waited its turn", fake.asked.count { it.host == "musicbrainz.org" }, turns)
        assertTrue(fake.asked.all { it.isHttps && (it.host.endsWith("wikipedia.org") || it.host == "musicbrainz.org" || it.host == "www.wikidata.org") })
        assertTrue("Only title and artist go out", fake.asked.first().queryParameter("query")!!.contains("\"Glass River\"") &&
            fake.asked.first().queryParameter("query")!!.contains("\"The Fixtures\""))
    }

    @Test fun namesakesAreNotGuessed() = runBlocking {
        val two = mapOf("ws/2/recording?" to """{"recordings":[${recording("r-1", "Same", artistA, "Twin", "2000")},${recording("r-2", "Same", artistB, "Twin", "2001")}]}""")
        assertEquals(BackgroundStatus.Ambiguous, BackgroundLookup(Fake(two).get, {}, "en").find("Same", "Twin").status)
        val artists = mapOf("ws/2/recording?" to """{"recordings":[]}""",
            "ws/2/artist?" to """{"artists":[{"id":"$artistA","name":"Twin","score":100},{"id":"$artistB","name":"Twin","score":98}]}""")
        assertEquals(BackgroundStatus.Ambiguous, BackgroundLookup(Fake(artists).get, {}, "en").find("Unknown song", "Twin").status)
    }

    @Test fun nothingFoundOfflineAndDisambiguationPages() = runBlocking {
        assertEquals(BackgroundStatus.NotFound, BackgroundLookup(Fake(mapOf("ws/2/recording?" to """{"recordings":[]}""", "ws/2/artist?" to """{"artists":[]}""")).get, {}, "en")
            .find("Nothing", "Nobody").status)
        assertEquals(BackgroundStatus.Unavailable, BackgroundLookup(Fake(emptyMap()).get, {}, "en").find("Any", "One").status)
        val disambiguation = BackgroundLookup(Fake(mapOf("ids=Q1" to """{"entities":{"Q1":{"sitelinks":{"enwiki":{"title":"Glass"}}}}}""",
            "summary/Glass" to """{"type":"disambiguation","title":"Glass","extract":"Glass may refer to"}""")).get, {}, "en")
        assertNull(disambiguation.article("Q1"))
        assertNull("Not a Wikidata item", wikidataItem(kotlinx.serialization.json.Json.parseToJsonElement("""{"relations":[{"type":"wikidata","url":{"resource":"https://x/../Q1;drop"}}]}""") as kotlinx.serialization.json.JsonObject))
    }

    @Test fun titlesLoseWhatYouTubeAdds() {
        assertEquals("Glass River", backgroundTitle("Glass River (feat. Someone)"))
        assertEquals("Glass River", backgroundTitle("Glass River [Official Music Video]"))
        assertEquals("Glass River", backgroundTitle("Glass River - Remastered 2011"))
        assertEquals("(Untitled)", backgroundTitle("(Untitled)"))
    }

    @Test fun theCacheKeepsAnswersButNotFailures() {
        var now = 1_000_000_000_000L
        val cache = BackgroundCache(folder.root, { now })
        val value = SongBackground(BackgroundStatus.Found, SongFacts("T", "A", "2018", null, listOf("W"), "https://musicbrainz.org/recording/x",
            BackgroundArticle("T", "Text", "https://en.wikipedia.org/wiki/T", "en", true)), null)
        cache.put("k", value); assertEquals(value, cache.get("k"))
        cache.put("u", SongBackground(BackgroundStatus.Unavailable)); assertNull(cache.get("u"))
        folder.root.listFiles()!!.forEach { it.setLastModified(now - 31L * 86_400_000) }
        assertNull("Older than 30 days", cache.get("k"))
    }

    @Test fun onlyTheAllowedHostsAreFetched() = runBlocking {
        val get = backgroundGet(okhttp3.Call.Factory { error("must not be called") })
        assertNull(get(okhttp3.HttpUrl.Builder().scheme("https").host("evil.example").build()))
        assertNull(get(okhttp3.HttpUrl.Builder().scheme("http").host("musicbrainz.org").build()))
    }
}
