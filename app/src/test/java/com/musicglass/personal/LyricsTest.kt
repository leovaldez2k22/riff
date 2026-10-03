package com.musicglass.personal

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    private val track = LyricsTrack("yt:test","Morning Path (Official Audio)","Paper Birds","New Day",20_000)
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0:02.000" end="0:04.000"><span begin="2s" end="3s">Morning</span> <span begin="3s" end="4s">path</span></p><p begin="0:07.000" end="0:09.000">A new day</p></div></body></tt>"""
    @Test fun ttmlPreservesSpacesAndAbsoluteWordTiming() {
        val doc = parseTtml(ttml,20_000)!!
        assertEquals("Morning path",doc.lines[0].text)
        assertEquals(LyricPart(2000,3000,0,7),doc.lines[0].parts[0])
        assertEquals(LyricPart(3000,4000,8,12),doc.lines[0].parts[1])
        assertTrue(doc.wordTimed)
    }
    @Test fun ttmlHandlesNestedWordsAndBackgroundVocals() {
        val doc = parseTtml("""<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1s" end="4s"><span><span begin="1s" end="2s">Soft</span> <span begin="2s" end="3s">light</span></span> <span begin="3s" dur="1s">(echo)</span></p></body></tt>""",10_000)!!
        assertEquals("Soft light (echo)",doc.lines.single().text)
        assertEquals(3,doc.lines.single().parts.size)
        assertEquals(4000L,doc.lines.single().parts.last().endMs)
    }
    @Test fun externalEntitiesAndMalformedXmlAreRejected() {
        assertNull(parseTtml("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///secret'>]><tt>&x;</tt>",20_000))
        assertNull(parseTtml("<tt><p>",20_000))
    }
    @Test fun timestampsHandleHoursMinutesOffsetsAndPrecision() {
        assertEquals(3_661_234L,lyricsTimeMs("01:01:01.234"))
        assertEquals(18_234L,lyricsTimeMs("00:18.234"))
        assertEquals(500L,lyricsTimeMs("500ms"))
        assertNull(lyricsTimeMs("NaN")); assertNull(lyricsTimeMs("-3s"))
    }
    @Test fun lrcSortsRepeatedStampsAppliesOffsetAndRetainsInstrumentalGaps() {
        val doc = parseLrc("[offset:-100]\n[00:07.00]New day\n[00:02.00][00:12.00]Morning path\n[00:05.00]",20_000,"test","https://example.com")!!
        assertEquals(listOf(1900L,4900L,6900L,11900L),doc.lines.map { it.startMs })
        assertEquals("Morning path",doc.lines[3].text)
        assertFalse(doc.wordTimed)
        assertEquals(-1,activeLyricLine(doc.lines,5000))
    }
    @Test fun enhancedLrcKeepsSuppliedTimingAndDoesNotInventTheFinalWordEnd() {
        val doc = parseLrc("[00:02.00]<00:02.00>Morning <00:03.00>path<00:04.00>",20_000,"test","https://example.com")!!
        assertEquals("Morning path",doc.lines.single().text)
        assertEquals(2,doc.lines.single().parts.size)
        assertEquals(4000L,doc.lines.single().parts.last().endMs)
        val noEnd = parseLrc("[00:02.00]<00:02.00>Morning <00:03.00>path",20_000,"test","https://example.com")!!
        assertEquals(1,noEnd.lines.single().parts.size)
    }
    @Test fun qrcDecodesXmlAttributesWithoutLosingRowsOrSpaces() {
        val raw = """<QrcInfos><LyricInfo><Lyric_1 LyricContent="[offset:100]
[2000,2000]Morning (2000,1000)path &amp; light(3000,1000)
[7000,2000]New (7000,1000)day(8000,1000)"/></LyricInfo></QrcInfos>"""
        val doc = parseQrc(raw,20_000)!!
        assertEquals(2,doc.lines.size)
        assertEquals("Morning path & light",doc.lines[0].text)
        assertEquals(2100L,doc.lines[0].parts[0].startMs)
        assertEquals(4100L,doc.lines[0].parts.last().endMs)
    }
    @Test fun qrcDoesNotTreatOpeningCreditsAsSungLyrics() {
        val doc = parseQrc("[1000,1000]Lyrics by: Test(1000,1000)\n[3000,1000]Morning(3000,1000)\n[6000,1000]Hello(6000,1000)",10_000,"Morning")!!
        assertEquals("Hello",doc.lines.single().text)
    }
    @Test fun seeksGapsAndEndpointsUseTheActualTimestamps() {
        val doc = parseTtml(ttml,20_000)!!
        assertEquals(-1,activeLyricLine(doc.lines,0))
        assertEquals(0,activeLyricLine(doc.lines,2000))
        assertEquals(-1,activeLyricLine(doc.lines,4000))
        assertEquals(1,activeLyricLine(doc.lines,8000))
        assertEquals(0,activeLyricLine(doc.lines,2500))
        assertEquals(-1,activeLyricLine(doc.lines,20_000))
    }
    @Test fun reopeningDuringSilenceRetainsTheLastSungLineForFollowing() {
        val doc = parseTtml(ttml,20_000)!!
        assertEquals(-1,activeLyricLine(doc.lines,5000))
        assertEquals(0,startedLyricLine(doc.lines,5000))
        assertEquals(-1,startedLyricLine(doc.lines,1000))
        assertEquals(1,startedLyricLine(doc.lines,15_000))
    }
    @Test fun wrongArtistAndRecordingDurationAreRejected() {
        assertTrue(lyricsMatchScore(track,"Morning Path","Paper Birds",20_000) > .9)
        assertEquals(0.0,lyricsMatchScore(track,"Morning Path","Other Singer",20_000),0.0)
        assertEquals(0.0,lyricsMatchScore(track,"Morning Path","Paper Birds Orchestra",20_000),0.0)
        assertEquals(0.0,lyricsMatchScore(track,"Morning Path (Live)","Paper Birds",20_000),0.0)
        assertEquals(0.0,lyricsMatchScore(track,"Morning Path","Paper Birds",30_000),0.0)
    }
    @Test fun youtubeParsesTimedLinesAndPlainFallback() {
        val timed = Json.parseToJsonElement("""{"lyricsData":{"lyrics":[{"lyricLineRenderer":{"lyricLine":"Morning path","cueRange":{"startTimeMilliseconds":"2000","endTimeMilliseconds":"4000"}}}]}}""")
        assertEquals(2000L,parseYoutubeLyrics(timed,20_000)!!.lines.single().startMs)
        val plain = Json.parseToJsonElement("""{"musicDescriptionShelfRenderer":{"description":{"runs":[{"text":"Morning path\nA new day"}]}}}""")
        assertFalse(parseYoutubeLyrics(plain,20_000)!!.timed)
    }
    @Test fun cacheRoundTripPreservesWordOffsetsAndTiming() {
        val doc = parseTtml(ttml,20_000)!!
        assertEquals(doc,decodeLyricsDocument(encodeLyricsDocument(doc)))
    }
    private fun lrclib() = Json.parseToJsonElement("""{"id":12,"trackName":"Morning Path","artistName":"Paper Birds","duration":20,"syncedLyrics":"[00:02.00]Morning path\n[00:07.00]A new day"}""")
    @Test fun betterLyricsWinsWithoutContactingOtherProvidersAndCachesOffline() = runBlocking {
        val dir = Files.createTempDirectory("lyrics-test").toFile()
        var calls = 0
        val repo = LyricsRepository(dir) { endpoint,_ ->
            calls++
            when {
                endpoint.endsWith("/api/get") -> lrclib()
                endpoint.endsWith("/getLyrics") -> { delay(30); buildJsonObject { put("ttml",ttml) } }
                else -> null
            }
        }
        try {
            val updates = repo.lyrics(track) { null }.toList()
            assertEquals(listOf(true),updates.map { it.wordTimed })
            assertEquals(1,calls)
            val count = calls
            val cached = repo.lyrics(track) { error("Cached lookup should not contact YouTube") }.toList()
            assertTrue(cached.single().wordTimed)
            assertEquals(count,calls)
        } finally { repo.close(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete() }
    }
    @Test fun betterLyricsLineTimingHasPriorityOverEveryFallback() = runBlocking {
        val dir=Files.createTempDirectory("lyrics-priority").toFile()
        val calls=mutableListOf<String>()
        val repo=LyricsRepository(dir) {endpoint,_->calls+=endpoint
            when {endpoint=="https://api.betterlyrics.org/getLyrics"->buildJsonObject {put("ttml","<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='2s' end='5s'>Morning path</p></div></body></tt>")}
                endpoint.contains("betterlyrics")->null
                else->error("Fallback must not be requested")}
        }
        try {val docs=repo.lyrics(track) {error("YouTube must not be requested")}.toList()
            assertTrue(docs.single().source.startsWith("Better Lyrics"));assertTrue(docs.single().timed)
            assertTrue(calls.all {it.contains("betterlyrics")})
        } finally {repo.close();dir.listFiles().orEmpty().forEach {it.delete()};dir.delete()}
    }
    @Test fun providerFailureDoesNotPreventTheIndependentFallback() = runBlocking {
        val dir = Files.createTempDirectory("lyrics-test").toFile()
        val repo = LyricsRepository(dir) { endpoint,_ ->
            if (endpoint.contains("betterlyrics")) throw java.io.IOException("offline") else lrclib()
        }
        try { assertTrue(repo.lyrics(track) { null }.toList().single().timed) }
        finally { repo.close(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete() }
    }
    @Test fun changingTrackCancelsRequestsRatherThanPublishingStaleLyrics() = runBlocking {
        val dir = Files.createTempDirectory("lyrics-test").toFile()
        var canceled = 0
        val started=CompletableDeferred<Unit>()
        val repo = LyricsRepository(dir) { _,_ ->
            started.complete(Unit)
            try { delay(10_000); lrclib() } finally { canceled++ }
        }
        try {
            val job = launch { repo.lyrics(track) { null }.toList() }
            withTimeout(3000) {started.await()}; job.cancelAndJoin()
            assertEquals(1,canceled)
            assertTrue(dir.listFiles().orEmpty().isEmpty())
        } finally { repo.close(); dir.listFiles().orEmpty().forEach { it.delete() }; dir.delete() }
    }

    @Test fun oneUnreachableSourceIsNotAConnectionFailure() {
        // Instrumental: some sources answered "nothing", one timed out -> "No lyrics found".
        assertFalse(lyricsUnreachable(errors = 1, answered = 2))
        assertTrue(lyricsUnreachable(errors = 3, answered = 0))
        assertFalse(lyricsUnreachable(errors = 0, answered = 0))
    }
}
