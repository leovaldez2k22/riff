package com.musicglass.personal

import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AdditionalLyricsProvidersTest {
    private val track = LyricsTrack("yt:abcdefghijk","Morning Path","Paper Birds","",20000)
    private fun json(raw: String) = Json.parseToJsonElement(raw)
    private val search = """{"code":200,"result":{"songs":[{"id":42,"name":"Morning Path","duration":20000,"artists":[{"name":"Paper Birds"}]}]}}"""
    private val line = """{"lrc":{"lyric":"[00:02.00]Morning path\n[00:07.00]A new day"}}"""
    private val yrc = """{"yrc":{"lyric":"[2000,3000](2000,1000,0)Morning (3000,2000,0)path"}}"""
    private suspend fun withRepo(block: suspend (LyricsRepository,MutableList<String>) -> Unit,
        transport: suspend (String,Map<String,String>) -> JsonElement?) {
        val dir = Files.createTempDirectory("new-lyrics").toFile()
        val calls = mutableListOf<String>()
        val repo = LyricsRepository(dir) { url,params -> calls += url; transport(url,params) }
        try { block(repo,calls) } finally { repo.close();dir.listFiles().orEmpty().forEach {it.delete()};dir.delete() }
    }
    @Test fun netEaseSearchRequiresArtistAndRecordingDuration() {
        val row = json(search).jsonObject["result"]!!.jsonObject["songs"]!!.jsonArray.single().jsonObject
        assertTrue(netEaseMatchScore(row,track)>0)
        assertEquals(0.0,netEaseMatchScore(row,track.copy(artist="Other Artist")),0.0)
        assertEquals(0.0,netEaseMatchScore(row,track.copy(durationMs=24000)),0.0)
        assertEquals(0.0,netEaseMatchScore(row,track.copy(title="Morning Path (Live)")),0.0)
    }
    @Test fun yrcPreservesSuppliedWordSpansAndRejectsInvalidOnes() {
        val doc = netEaseLyrics(json(yrc).jsonObject,20000,"42")!!
        assertTrue(doc.wordTimed)
        assertEquals("Morning path",doc.lines.single().text)
        assertEquals(listOf(LyricPart(2000,3000,0,8),LyricPart(3000,5000,8,12)),doc.lines.single().parts)
        assertNull(parseNetEaseYrc("[9999999999999999999999,1](0,1,0)Bad",20000,"url"))
        val malformed = parseNetEaseYrc("[2000,3000](1000,9000,0)Bad",20000,"url")!!
        assertFalse(malformed.wordTimed)
    }
    @Test fun netEaseLrcAndAttributionArePreserved() {
        val doc = netEaseLyrics(json(line).jsonObject,20000,"42")!!
        assertEquals("NetEase Cloud Music",doc.source);assertEquals("https://music.163.com/song?id=42",doc.sourceUrl)
        assertTrue(doc.timed);assertFalse(doc.wordTimed)
    }
    @Test fun rateLimitAndVerificationResponsesAreFailures() {
        try {checkNetEaseResponse(json("""{"code":-462}""").jsonObject);fail("must reject verification response")}
        catch (_:IOException) { }
        checkNetEaseResponse(json("""{"code":200}""").jsonObject)
    }
    @Test fun netEaseWordTimingUpgradesBetterLyricsLinesAndStopsLowerProviders() = runBlocking {
        withRepo({repo,calls ->
            val docs = repo.lyrics(track) {error("Lower provider must not run")}.toList()
            assertEquals(listOf("Better Lyrics","NetEase Cloud Music"),docs.map {it.source})
            assertTrue(docs.last().wordTimed)
            assertFalse(calls.any {it.contains("lrclib") || it.contains("megalobiz") || it.contains("lyrics.ovh")})
        }) {url,_ -> when {
            url=="https://api.betterlyrics.org/getLyrics" -> json("""{"ttml":"<tt xmlns='http://www.w3.org/ns/ttml'><body><p begin='2s' end='5s'>Morning path</p></body></tt>"}""")
            url.endsWith("/api/search/get") -> json(search)
            url.endsWith("/api/song/lyric") -> json(yrc)
            else -> null
        } }
    }
    @Test fun plainResultDoesNotPreventLaterSyncedYouTubeLyrics() = runBlocking {
        withRepo({repo,_ ->
            val docs=repo.lyrics(track) {LyricsDocument("YouTube Music","https://music.youtube.com",listOf(LyricLine(2000,5000,"Morning path")))}.toList()
            assertEquals(listOf("LRCLIB","YouTube Music"),docs.map {it.source})
            assertTrue(docs.last().timed)
        }) {url,_ -> if(url.endsWith("/api/get")) json("""{"trackName":"Morning Path","artistName":"Paper Birds","duration":20,"plainLyrics":"Morning path"}""") else null }
    }
    @Test fun ovhIsTheFinalFallbackAndEscapesPathSegments() = runBlocking {
        withRepo({repo,calls ->
            val special=track.copy(title="Morning/Path?",artist="Paper & Birds")
            val docs=repo.lyrics(special) {null}.toList()
            assertEquals("Lyrics.ovh",docs.single().source);assertFalse(docs.single().timed)
            assertEquals("https://api.lyrics.ovh/v1/Paper%20&%20Birds/Morning%2FPath%3F",calls.last())
        }) {url,_ -> if(url.contains("api.lyrics.ovh")) json("""{"lyrics":"Morning path\nA new day"}""") else null }
    }
    @Test fun providerFailureAndNoMatchReachOvh() = runBlocking {
        withRepo({repo,_ ->
            assertEquals("Lyrics.ovh",repo.lyrics(track) {null}.toList().last().source)
        }) {url,_ -> when {
            url.contains("music.163.com") -> json("""{"code":-462}""")
            url.contains("api.lyrics.ovh") -> json("""{"lyrics":"Plain fallback"}""")
            else -> null
        } }
    }
    @Test fun plainCacheIsShownImmediatelyAndDoesNotHideNewSyncedLyrics() = runBlocking {
        var upgraded=false
        withRepo({repo,calls ->
            assertFalse(repo.lyrics(track) {null}.toList().last().timed)
            val count=calls.size
            upgraded=true
            val updates=repo.lyrics(track) {null}.toList()
            assertFalse(updates.first().timed);assertTrue(updates.last().wordTimed);assertTrue(calls.size>count)
            assertTrue(repo.lyrics(track,force=true) {null}.toList().last().wordTimed)
        }) {url,_ -> when {
            url.endsWith("/api/search/get") && upgraded -> json(search)
            url.endsWith("/api/song/lyric") && upgraded -> json(yrc)
            url.contains("api.lyrics.ovh") -> json("""{"lyrics":"Plain fallback"}""")
            else -> null
        } }
    }
}
