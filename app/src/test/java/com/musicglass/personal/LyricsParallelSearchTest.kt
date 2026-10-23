package com.musicglass.personal

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Q18-082 (owner's request, 22 Oct 2026): the lyrics sources are asked together. Better Lyrics, QQ and NetEase first;
 * the line-timed sources a moment later and only while no word timing has come; answers ranked by timing, then by
 * source; a source that can no longer give anything better is cancelled. Timed fakes; nothing leaves the machine.
 */
class LyricsParallelSearchTest {
    private val track = LyricsTrack("yt:delicate00", "Delicate", "Taylor Swift", "reputation", 20_000)
    private val dir: File = Files.createTempDirectory("lyrics-parallel").toFile()
    private val asked: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
    private val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val repos = mutableListOf<LyricsRepository>()
    @After fun clean() { repos.forEach { it.close() }; dir.deleteRecursively() }

    private val wordTtml = buildJsonObject { put("ttml", """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="4s"><span begin="2s" end="3s">Morning</span> <span begin="3s" end="4s">path</span></p></div></body></tt>""") }
    private val lineTtml = buildJsonObject { put("ttml", """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="4s">Morning path</p></div></body></tt>""") }
    private val lrclib = Json.parseToJsonElement("""{"id":12,"trackName":"Delicate","artistName":"Taylor Swift","duration":20,"syncedLyrics":"[00:02.00]Morning path"}""")
    /** Word timing as NetEase gives it (yrc), from its two calls: the search, then the song's lyrics. */
    private val neteaseSearch = Json.parseToJsonElement("""{"code":200,"result":{"songs":[{"id":42,"name":"Delicate","duration":20000,"artists":[{"name":"Taylor Swift"}]}]}}""")
    private val neteaseWords = Json.parseToJsonElement("""{"code":200,"yrc":{"lyric":"[2000,2000](2000,1000,0)Morning (3000,1000,0)path"}}""")

    private fun source(url: String) = when {
        url.endsWith("api.betterlyrics.org/getLyrics") -> "bl"
        url.contains("/qq/") -> "qq"
        url.contains("/kugou/") -> "kugou"
        url.contains("music.163.com/api/search") -> "netease"
        url.contains("music.163.com") -> "netease-lyric"
        url.contains("lrclib") -> "lrclib"
        url.contains("lyrics.ovh") -> "ovh"
        else -> url
    }
    private fun search(limitMs: Long = 2_000, staggerMs: Long = 1_000, swapMs: Long = 2_000,
        answers: Map<String, suspend () -> JsonElement?>): Pair<List<LyricsDocument>, Long> = runBlocking {
        val t0 = System.nanoTime()
        val r = LyricsRepository(dir, limitMs, staggerMs, swapMs) { url, _ ->
            val name = source(url); asked += name to (System.nanoTime() - t0)/1_000_000
            try { answers[name]?.invoke() } catch (e: CancellationException) { cancelled += name; throw e }
        }.also { repos += it }
        val docs = r.lyrics(track) { null }.toList()
        docs to (System.nanoTime() - t0)/1_000_000
    }
    private fun names() = synchronized(asked) { asked.map { it.first } }

    @Test fun aSlowHigherRankedSourceBeatsAFastLowerOne() {
        val (docs, _) = search(answers = mapOf("bl" to { delay(300); wordTtml }, "netease" to { neteaseSearch }, "netease-lyric" to { neteaseWords }))
        assertTrue(docs.first().wordTimed)
        assertEquals("NetEase first, then Better Lyrics' word timing in its place", listOf("NetEase Cloud Music", "Better Lyrics"), docs.map { it.source })
    }

    @Test fun theSameTierSwapsOnlySoonAfterItWasShown() {
        val (docs, _) = search(swapMs = 100, answers = mapOf("bl" to { delay(500); wordTtml }, "netease" to { neteaseSearch }, "netease-lyric" to { neteaseWords }))
        assertEquals("Shown for longer than the window: NetEase stays", listOf("NetEase Cloud Music"), docs.map { it.source })
    }

    @Test fun aBetterTierAlwaysReplacesWhatIsShown() {
        val (docs, _) = search(swapMs = 0, answers = mapOf("bl" to { lineTtml }, "netease" to { delay(300); neteaseSearch }, "netease-lyric" to { neteaseWords }))
        assertEquals(listOf(false, true), docs.map { it.wordTimed })
        assertEquals("NetEase Cloud Music", docs.last().source)
    }

    @Test fun wordTimingFromBetterLyricsCancelsTheRest() {
        val (docs, ms) = search(limitMs = 10_000, answers = mapOf("bl" to { delay(100); wordTtml }, "qq" to { delay(8_000); null }, "netease" to { delay(8_000); null }))
        assertEquals(listOf("Better Lyrics"), docs.map { it.source })
        assertTrue("took $ms ms", ms < 3_000)
        assertEquals(setOf("qq", "netease"), cancelled.toSet())
        assertTrue(names().none { it in setOf("kugou", "lrclib", "ovh") })
    }

    @Test fun wordTimingFromBelowBetterLyricsWaitsOnlyForBetterLyrics() {
        val (docs, ms) = search(limitMs = 10_000, swapMs = 1_000, answers = mapOf("bl" to { delay(400); null }, "qq" to { delay(8_000); null },
            "netease" to { neteaseSearch }, "netease-lyric" to { neteaseWords }))
        assertEquals(listOf("NetEase Cloud Music"), docs.map { it.source })
        assertTrue("Better Lyrics was let finish", "bl" !in cancelled)
        assertTrue("QQ could only swap in within the window; then it is cancelled", "qq" in cancelled)
        assertTrue("took $ms ms", ms < 3_000)
    }

    @Test fun theLineTimedSourcesStartAMomentLaterWhileNoWordTimingHasCome() {
        search(staggerMs = 150, answers = mapOf("bl" to { delay(700); null }, "qq" to { delay(700); null }, "netease" to { delay(700); null }, "lrclib" to { lrclib }))
        val lrclibAt = synchronized(asked) { asked.first { it.first == "lrclib" }.second }
        assertTrue("LRCLIB asked at $lrclibAt ms, not after the slow three", lrclibAt in 100..650)
        assertTrue("and after the three started", names().indexOf("lrclib") > names().indexOf("netease"))
    }

    @Test fun earlyWordTimingMeansTheLineTimedSourcesAreNeverAsked() {
        search(staggerMs = 500, answers = mapOf("bl" to { delay(100); wordTtml }, "qq" to { delay(5_000); null }, "netease" to { delay(5_000); null }))
        assertTrue(names().none { it in setOf("kugou", "lrclib", "ovh") })
    }

    @Test fun aWordTimedSourceThatRanOutOfTimeGetsASecondTurnAfterTheOthers() {
        var first = true
        val (docs, _) = search(limitMs = 300, staggerMs = 50, answers = mapOf(
            "bl" to { if (first) { first = false; delay(2_000) }; wordTtml }, "lrclib" to { lrclib }))
        assertEquals(listOf(false, true), docs.map { it.wordTimed })
        assertEquals(2, names().count { it == "bl" })
        assertTrue("its second turn comes after LRCLIB", names().lastIndexOf("bl") > names().indexOf("lrclib"))
    }

    @Test fun plainTextIsAskedLastAndOnlyWhenNothingSyncedWasFound() {
        val (docs, _) = search(staggerMs = 50, answers = mapOf("ovh" to { buildJsonObject { put("lyrics", "Morning path") } }))
        assertEquals("Lyrics.ovh", docs.single().source)
        assertEquals("ovh", names().last())
        asked.clear(); dir.listFiles().orEmpty().forEach { it.delete() }
        search(staggerMs = 50, answers = mapOf("lrclib" to { lrclib }, "ovh" to { buildJsonObject { put("lyrics", "Morning path") } }))
        assertFalse("ovh" in names())
    }

    @Test fun theRankingRule() {
        val word = LyricsDocument("Better Lyrics · QQ", "", listOf(LyricLine(0, 1000, "a", parts = listOf(LyricPart(0, 1000, 0, 1)))))
        val line = LyricsDocument("Better Lyrics", "", listOf(LyricLine(0, 1000, "a")))
        assertTrue(lyricsOutranks(word, line, 60_000, 2_000))
        assertFalse(lyricsOutranks(line, word, 0, 2_000))
        assertTrue(lyricsOutranks(word.copy(source = "Better Lyrics"), word, 1_000, 2_000))
        assertFalse(lyricsOutranks(word.copy(source = "Better Lyrics"), word, 3_000, 2_000))
        assertFalse(lyricsOutranks(word.copy(source = "NetEase Cloud Music"), word, 0, 2_000))
    }

    // ---- (7) one queue per Better Lyrics endpoint, a shared 429 pause

    @Test fun eachBetterLyricsEndpointHasItsOwnQueue() = runBlocking {
        assertNotEquals(lyricsQueueKey("api.betterlyrics.org", "/getLyrics"), lyricsQueueKey("api.betterlyrics.org", "/qq/getLyrics"))
        assertEquals(lyricsQueueKey("lrclib.net", "/api/get"), lyricsQueueKey("lrclib.net", "/api/search"))
        val gates = LyricsGates(spacingMs = 0)
        val inside = java.util.concurrent.atomic.AtomicInteger(); var most = 0
        suspend fun call(path: String) = gates.run("api.betterlyrics.org", lyricsQueueKey("api.betterlyrics.org", path)) {
            most = maxOf(most, inside.incrementAndGet()); delay(200); inside.decrementAndGet()
        }
        listOf("/getLyrics", "/qq/getLyrics", "/kugou/getLyrics").map { launch { call(it) } }.joinAll()
        assertEquals("three endpoints, three at once", 3, most)
        most = 0
        listOf("/getLyrics", "/getLyrics").map { launch { call(it) } }.joinAll()
        assertEquals("one endpoint, one at a time", 1, most)
    }

    @Test fun a429FromAnyEndpointPausesThemAllAndFewLeftMeansOneAtATime() = runBlocking {
        var now = 0L
        val gates = LyricsGates(spacingMs = 0) { now }
        val bl = lyricsQueueKey("api.betterlyrics.org", "/getLyrics"); val qq = lyricsQueueKey("api.betterlyrics.org", "/qq/getLyrics")
        gates.answered("api.betterlyrics.org", qq, 429, 20_000, null)
        assertEquals(20_000, gates.waitMs("api.betterlyrics.org", bl))
        assertTrue(gates.waitMs("lrclib.net", "lrclib.net") <= 0)
        now = 20_000; assertTrue(gates.waitMs("api.betterlyrics.org", bl) <= 0)
        gates.answered("api.betterlyrics.org", bl, 200, 0, 3)
        val inside = java.util.concurrent.atomic.AtomicInteger(); var most = 0
        listOf(bl, qq).map { q -> launch { gates.run("api.betterlyrics.org", q) { most = maxOf(most, inside.incrementAndGet()); delay(100); inside.decrementAndGet() } } }.joinAll()
        assertEquals("few requests left: one at a time", 1, most)
        gates.answered("api.betterlyrics.org", bl, 200, 0, 50)
        most = 0
        listOf(bl, qq).map { q -> launch { gates.run("api.betterlyrics.org", q) { most = maxOf(most, inside.incrementAndGet()); delay(100); inside.decrementAndGet() } } }.joinAll()
        assertEquals(2, most)
    }
}
