package com.musicglass.personal

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Rc16RegressionTest {
    private val track = LyricsTrack("yt:abcdefghijk", "Morning Path", "Paper Birds", "", 20_000)
    private fun timedRows(rows: String) = Json.parseToJsonElement("""{"contents":{"elementRenderer":{"newElement":{"type":{"componentType":{"model":{"timedLyricsModel":{"lyricsData":{"timedLyricsData":[$rows],"sourceMessage":"Source: LyricFind"}}}}}}}}}""")
    private val rows = """{"lyricLine":"Morning path","cueRange":{"startTimeMilliseconds":"2000","endTimeMilliseconds":"4000"}},{"lyricLine":{"runs":[{"text":"A new day"}]},"cueRange":{"startTimeMilliseconds":7000,"endTimeMilliseconds":9000}}"""
    @Test fun nativeLyricsBodyContainsOnlyTheRequiredPublicMetadataAndLocale() {
        val body = youtubeTimedLyricsBody("MPLYt123", "en", "IN")
        assertEquals(setOf("browseId", "context"), body.keys)
        assertEquals("MPLYt123", body["browseId"]!!.jsonPrimitive.content)
        assertEquals(setOf("client"), body["context"]!!.jsonObject.keys)
        val client = body["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals(setOf("clientName", "clientVersion", "hl", "gl"), client.keys)
        assertEquals("ANDROID_MUSIC", client["clientName"]!!.jsonPrimitive.content)
        assertEquals("en", client["hl"]!!.jsonPrimitive.content); assertEquals("IN", client["gl"]!!.jsonPrimitive.content)
        assertTrue(client.values.none { it == JsonNull })
    }
    @Test fun nativeTimedLyricsKeepRealCueRangesAndFollowSeeks() {
        val doc = parseYoutubeLyrics(timedRows(rows), 20_000)!!
        assertTrue(doc.timed); assertFalse(doc.wordTimed)
        assertEquals(listOf("Morning path", "A new day"), doc.lines.map { it.text })
        assertEquals(listOf(2000L, 7000L), doc.lines.map { it.startMs })
        assertEquals(listOf(4000L, 9000L), doc.lines.map { it.endMs })
        assertEquals(1, activeLyricLine(doc.lines, 8000))
        assertEquals(0, activeLyricLine(doc.lines, 2500))
        assertEquals(-1, activeLyricLine(doc.lines, 6000))
    }
    @Test fun nativeUnstampedLyricsRemainReadableAndNeverInventTiming() {
        val doc = parseYoutubeLyrics(timedRows("""{"lyricLine":"Morning path"},{"lyricLine":"A new day"}"""), 20_000)!!
        assertFalse(doc.timed); assertEquals(2, doc.lines.size)
    }
    @Test fun mixedInvalidCueNeverHidesUntimedVerses() {
        val doc = parseYoutubeLyrics(timedRows(rows + """,{"lyricLine":"Final verse","cueRange":{"startTimeMilliseconds":"invalid"}}"""), 20_000)!!
        assertFalse(doc.timed); assertEquals("Final verse", doc.lines.last().text)
        assertNull(parseYoutubeLyrics(timedRows("""{"lyricLine":null,"cueRange":{"startTimeMilliseconds":-1}}"""), 20_000))
    }
    @Test fun nativeTimedRowsWinOverDescriptionAndDuplicateLegacyWrappers() {
        val root = buildJsonObject {
            put("mobile", timedRows(rows))
            put("legacy", Json.parseToJsonElement("""{"lyricLineRenderer":{"lyricLine":"Morning path","cueRange":{"startTimeMilliseconds":"2000","endTimeMilliseconds":"4000"}}}"""))
            put("plain", Json.parseToJsonElement("""{"musicDescriptionShelfRenderer":{"description":{"simpleText":"Plain text"}}}"""))
        }
        assertEquals(2, parseYoutubeLyrics(root, 20_000)!!.lines.size)
    }
    @Test fun oldPlainCacheUpgradesToNativeTiming() = runBlocking {
        val dir = Files.createTempDirectory("rc16-lyrics").toFile()
        var synced = false
        var requests = 0
        var youtubeCalls = 0
        val repo = LyricsRepository(dir) { _, _ -> requests++; null }
        try {
            suspend fun youtube(): LyricsDocument? {
                youtubeCalls++
                return if (synced) parseYoutubeLyrics(timedRows(rows), 20_000)
                    else LyricsDocument("YouTube Music", "https://music.youtube.com", listOf(LyricLine(-1,-1,"Morning path")))
            }
            assertFalse(repo.lyrics(track) { youtube() }.toList().last().timed)
            synced = true
            val updated = repo.lyrics(track) { youtube() }.toList()
            assertFalse(updated.first().timed); assertTrue(updated.last().timed)
            assertEquals(2, youtubeCalls); assertTrue(requests >= 6)
            assertTrue(decodeLyricsDocument(Json.parseToJsonElement(dir.listFiles()!!.single().readText()).jsonObject).timed)
        } finally { repo.close(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete() }
    }
    @Test fun lrclibKeepsPriorityOverEqualQualityYoutubeTiming() = runBlocking {
        val dir = Files.createTempDirectory("rc16-priority").toFile()
        val repo = LyricsRepository(dir) { endpoint, _ -> if (endpoint.endsWith("/api/get"))
            Json.parseToJsonElement("""{"trackName":"Morning Path","artistName":"Paper Birds","duration":20,"syncedLyrics":"[00:03.00]Morning path"}""") else null }
        try {
            val docs = repo.lyrics(track) { delay(40); parseYoutubeLyrics(timedRows(rows),20_000) }.toList()
            assertEquals("LRCLIB", docs.last().source); assertEquals(3000L,docs.last().lines.first().startMs)
        } finally { repo.close(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete() }
    }
    private fun song(id: String) = ProviderHomeCard(id, "Artist", null, videoId = id)
    private fun collection(id: String, kind: CatalogKind) = ProviderHomeCard(id, "Collection", null, browseId = id, kind = kind)
    @Test fun listeningHistoryCannotTurnPodcastEpisodesOrRadioIntoSongCards() {
        assertEquals(CatalogKind.Episode, ProviderTrack("podcast0001", "Episode", "Show", isMusic=false).homeCard().kind)
        assertEquals(CatalogKind.Live, ProviderTrack("live0000001", "Radio", "Channel", isLive=true).homeCard().kind)
        val song=ProviderTrack("song0000001", "Song", "Artist", album="Album",artistId="artist",albumId="album").homeCard()
        assertEquals(CatalogKind.Song, song.kind);assertEquals("Album",song.album);assertEquals("artist",song.artistId)
    }
    @Test fun speedDialIsThreePagesOfListenAgainSongs() {
        val listen = (1..40).map { song("listen$it") }
        val shelves = listOf(ProviderHomeSection("Listen again", listen + collection("list", CatalogKind.Playlist)),
            ProviderHomeSection("Quick picks", (1..35).map { song("new$it") }))
        val actual = homeSpeedDialCandidates(listOf(song("recent")), shelves)
        assertEquals(listen.take(SPEED_DIAL_SIZE), actual)
    }
    @Test fun shortListenAgainShelfComesFirstAndIsToppedUpWithSongsOnly() {
        val listen = (1..20).map { song("listen$it") }
        val podcasts = listOf(collection("podcast", CatalogKind.Podcast), song("episode").copy(kind=CatalogKind.Episode))
        val shelves = listOf(ProviderHomeSection("Listen again", listen),
            ProviderHomeSection("Quick picks", (1..35).map { song("new$it") } + collection("list", CatalogKind.Playlist) + podcasts))
        val actual = homeSpeedDialCandidates(listOf(song("recent"), listen.first()), shelves)
        assertEquals(27, actual.size); assertEquals(listen, actual.take(20)); assertEquals("recent", actual[20].key())
        assertTrue(actual.all { it.kind == CatalogKind.Song })
        assertEquals(actual.size, actual.map { it.key() }.toSet().size)
    }
    @Test fun smallSpeedDialKeepsAvailableSongsWithoutDuplicatesOrPodcastPadding() {
        val actual = homeSpeedDialCandidates(listOf(song("one")),listOf(ProviderHomeSection("Listen again", listOf(song("one"),song("two"))),
            ProviderHomeSection("Other",listOf(song("three"),song("one"),collection("pod",CatalogKind.Podcast)))))
        assertEquals(listOf("one","two","three"),actual.map {it.key()})
    }
    @Test fun speedDialDropsPodcastsThatLookLikeSongs() {
        val recent = listOf(song("one"), ProviderTrack("podcast0001", "Episode", "Show", isMusic=false).homeCard())
        val shelves = listOf(ProviderHomeSection("Quick picks", listOf(song("two"), song("pod").copy(subtitle="Podcast · Host"),
            collection("show", CatalogKind.Playlist).copy(subtitle="Podcast"))),
            ProviderHomeSection("Podcasts for you", listOf(song("three"))))
        assertEquals(listOf("one","two"), homeSpeedDialCandidates(recent, shelves).map { it.key() })
        val episode = Json.parseToJsonElement("""{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Ep 1",
            "navigationEndpoint":{"watchEndpoint":{"videoId":"episode0001","watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}}}}}]}}}]}}""")
        assertEquals(CatalogKind.Episode, parseCatalogCards(episode).single().kind)
    }
    @Test fun speedDialIncludesRecentSongsEvenWhenThereIsNoListenAgainShelf() {
        assertEquals(listOf("one","two"),homeSpeedDialCandidates(listOf(song("one")),listOf(ProviderHomeSection("Quick picks",listOf(song("one"),song("two"))))).map {it.key()})
    }
    @Test fun largeListenAgainShelfIsCappedWithoutItsSeparateHomeSection() {
        val listen=(1..70).map {song("listen$it")}
        val shelves=listOf(ProviderHomeSection("Listen again",listen.take(2)+listen),ProviderHomeSection("Quick picks",listOf(song("fresh"))))
        val actual=homeSpeedDialCandidates(emptyList(),shelves)
        assertEquals(listen.take(SPEED_DIAL_SIZE),actual)
        assertEquals(listOf("Quick picks"),homeDiscoveryShelves(shelves).map {it.title})
    }

}
