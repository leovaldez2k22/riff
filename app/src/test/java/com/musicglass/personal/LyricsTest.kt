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
    @Test fun betterLyricsLineTimingKeepsPriorityWhenFallbacksHaveTheSameTimingTier() = runBlocking {
        val dir=Files.createTempDirectory("lyrics-priority").toFile()
        val calls=mutableListOf<String>()
        val repo=LyricsRepository(dir) {endpoint,_->calls+=endpoint
            when {endpoint=="https://api.betterlyrics.org/getLyrics"->buildJsonObject {put("ttml","<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='2s' end='5s'>Morning path</p></div></body></tt>")}
                endpoint.contains("betterlyrics")->null
                else->lrclib()}
        }
        try {val docs=repo.lyrics(track) {null}.toList()
            assertTrue(docs.single().source.startsWith("Better Lyrics"));assertTrue(docs.single().timed)
            assertTrue(calls.any {it.contains("lrclib")});assertTrue(calls.any {it.contains("music.163.com")})
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
    // Q16A: two voices at once, background vocals, held words.
    private val overlap = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="6s">Lead line</p><p begin="3s" end="4s">Answer</p><p begin="8s" end="9s">Later</p></div></body></tt>"""
    @Test fun overlappingLinesAreLitTogetherAndTheEarlierStaysLitWhenTheLaterEndsFirst() {
        val lines = parseTtml(overlap,20_000)!!.lines
        assertEquals(listOf(0),activeLyricLines(lines,2000))
        assertEquals(listOf(0,1),activeLyricLines(lines,3500))
        // The answer ended first: the lead line is still sung and stays lit (before Q16A nothing was lit here).
        assertEquals(listOf(0),activeLyricLines(lines,4500))
        assertEquals(0,activeLyricLine(lines,4500))
        assertEquals(1,activeLyricLine(lines,3500))
        assertEquals(emptyList<Int>(),activeLyricLines(lines,7000))
        assertEquals(listOf(2),activeLyricLines(lines,8000))
        assertEquals(emptyList<Int>(),activeLyricLines(lines,0))
        // A line-synced source has no overlap: one line at a time, as before.
        val lrc = parseLrc("[00:01.00]One\n[00:03.00]Two",20_000,"test","https://example.com")!!.lines
        assertEquals(listOf(1),activeLyricLines(lrc,3500))
    }
    @Test fun theOverlapScanIsBounded() {
        val long = LyricLine(0,100_000,"Held all song")
        val lines = listOf(long) + (1..LYRIC_OVERLAP_SCAN + 5).map { LyricLine(it * 1000L,it * 1000L + 500,"Line $it") }
        assertTrue(0 in activeLyricLines(lines,3200))
        assertFalse("lines further back than the scan are not looked at",0 in activeLyricLines(lines,(LYRIC_OVERLAP_SCAN + 2) * 1000L + 200))
    }
    private val backing = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s" end="3s"><span begin="1s" end="2s">Soft</span> <span begin="2s" end="3s">light</span> <span ttm:role="x-bg"><span begin="2.5s" end="3.2s">(echo</span> <span begin="3.2s" end="4.6s">echo)</span></span></p><p begin="6s" end="7s">Next</p></body></tt>"""
    @Test fun backgroundVocalsAreTheirOwnLineWithTheirOwnTiming() {
        val doc = parseTtml(backing,20_000)!!
        val line = doc.lines[0]
        assertEquals("Soft light",line.text)
        assertEquals(2,line.parts.size)
        val vocal = line.backing!!
        assertEquals("(echo echo)",vocal.text)
        assertEquals(2500L,vocal.startMs)
        // Its timing is kept past the main line's end (it used to be cut 0.5 s after it).
        assertEquals(4600L,vocal.endMs)
        assertEquals(listOf(LyricPart(2500,3200,0,5),LyricPart(3200,4600,6,11)),vocal.parts)
        assertEquals(4600L,line.untilMs)
        assertEquals(listOf(0),activeLyricLines(doc.lines,4000))
        assertNull(doc.lines[1].backing)
        // A line with nothing but a background vocal is kept.
        val only = parseTtml("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s" end="2s"><span ttm:role="x-bg" begin="1s" end="2s">(ooh)</span></p></body></tt>""",20_000)!!
        assertEquals("",only.lines.single().text)
        assertEquals("(ooh)",only.lines.single().backing!!.text)
        assertEquals(listOf(0),activeLyricLines(only.lines,1500))
    }
    @Test fun cacheKeepsBackgroundVocalsAndReadsFilesWrittenBefore() {
        val doc = parseTtml(backing,20_000)!!
        assertEquals(doc,decodeLyricsDocument(encodeLyricsDocument(doc)))
        assertFalse("a line without one is written as before","backing" in encodeLyricsDocument(doc)["lines"]!!.jsonArray[1].jsonObject)
        val before = Json.parseToJsonElement("""{"source":"test","url":"https://example.com","instrumental":false,"lines":[{"start":1000,"end":2000,"text":"Old","parts":[{"start":1000,"end":2000,"from":0,"to":3}]}]}""").jsonObject
        assertEquals(LyricLine(1000,2000,"Old",listOf(LyricPart(1000,2000,0,3))),decodeLyricsDocument(before).lines.single())
    }
    @Test fun syllablesWithNothingBetweenThemAreOneWord() {
        val text = "won der ful lyrics"
        // "won","der","ful" are timed apart but written apart too; "lyr"+"ics" touch and are one word.
        val parts = listOf(LyricPart(0,100,0,3),LyricPart(100,200,4,7),LyricPart(200,300,8,11),LyricPart(300,400,12,15),LyricPart(400,1500,15,18))
        val words = lyricWords(text,parts)
        assertEquals(4,words.size)
        assertEquals(LyricWord(12,18,300,1500),words.last())
    }
    @Test fun heldWordsAreLongAgainstTheirLineNotJustLong() {
        // "Find your dreams come true": the last word is held about four times as long per letter.
        val text = "Find your dreams come true"
        val parts = listOf(LyricPart(0,300,0,4),LyricPart(300,600,5,9),LyricPart(600,1000,10,16),LyricPart(1000,1300,17,21),LyricPart(1300,2500,22,26))
        assertEquals(listOf(LyricWord(22,26,1300,2500)),heldLyricWords(text,parts))
        // Sung slowly throughout: no word stands out, and none is long enough on its own.
        val slow = listOf(LyricPart(0,1000,0,4),LyricPart(1000,2000,5,9),LyricPart(2000,3200,10,16),LyricPart(3200,4200,17,21),LyricPart(4200,5300,22,26))
        assertEquals(emptyList<LyricWord>(),heldLyricWords(text,slow))
        // Very long is held whatever the line does.
        assertEquals(1,heldLyricWords("Ooh",listOf(LyricPart(0,LONG_HELD_WORD_MS,0,3))).size)
        assertEquals(0,heldLyricWords("Ooh",listOf(LyricPart(0,LONG_HELD_WORD_MS - 1,0,3))).size)
        // Line-synced lyrics have no words to hold.
        assertEquals(emptyList<LyricWord>(),heldLyricWords("Line only",emptyList()))
    }
    @Test fun aHeldWordGrowsWhileSungAndSettlesAfter() {
        val word = LyricWord(0,4,1000,2200)
        assertEquals(0f,heldEmphasis(999,word),0f)
        assertEquals(1f,heldEmphasis(1000 + HELD_RISE_MS,word),0f)
        assertEquals(1f,heldEmphasis(2100,word),0f)
        assertEquals(0f,heldEmphasis(2200 + HELD_RELEASE_MS,word),0f)
        assertTrue(heldEmphasis(2200 + HELD_RELEASE_MS / 2,word) in 0.1f..0.9f)
        assertEquals(0f,heldGlow(999,word),0f)
        assertTrue("the glow grows with the sweep",heldGlow(2100,word) > heldGlow(1300,word))
        assertEquals(0f,heldGlow(2200 + HELD_RELEASE_MS,word),0f)
    }
}
