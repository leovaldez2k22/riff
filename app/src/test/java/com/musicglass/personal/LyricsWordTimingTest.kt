package com.musicglass.personal

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Q18-003: line-timed lyrics are shown at once but never stop Riff finding word timing. A word-timed source that
 * answers too slowly the first time is asked once more, and a cached or downloaded line-timed result is asked again
 * for word timing at most every 12 hours; a word-timed answer replaces it.
 */
class LyricsWordTimingTest {
    private val track = LyricsTrack("yt:delicate", "Delicate", "Taylor Swift", "reputation", 20_000)
    private val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0:02.000" end="0:04.000"><span begin="2s" end="3s">Morning</span> <span begin="3s" end="4s">path</span></p><p begin="0:07.000" end="0:09.000">A new day</p></div></body></tt>"""
    private fun lrclib() = Json.parseToJsonElement("""{"id":12,"trackName":"Delicate","artistName":"Taylor Swift","duration":20,"syncedLyrics":"[00:02.00]Morning path\n[00:07.00]A new day"}""")
    private val dir: File = Files.createTempDirectory("lyrics-word").toFile()
    private val calls = mutableListOf<String>()
    private var ttmlAnswer: suspend () -> JsonElement? = { null }
    private val repo = LyricsRepository(dir, 300) { endpoint, _ ->
        synchronized(calls) { calls += endpoint }
        when {
            endpoint == "https://api.betterlyrics.org/getLyrics" -> ttmlAnswer()
            endpoint.contains("lrclib.net/api/get") -> lrclib()
            else -> null
        }
    }
    @After fun clean() { repo.close(); dir.deleteRecursively() }

    private fun lookUp() = runBlocking { synchronized(calls) { calls.clear() }; repo.lyrics(track) { null }.toList() }
    private fun ageTheCache(hours: Long) = dir.listFiles().orEmpty().forEach { it.setLastModified(System.currentTimeMillis() - hours*3600_000) }
    private fun asked() = synchronized(calls) { calls.toList() }

    @Test fun aWordTimedSourceThatAnswersAfterTheLineTimedOneIsStillPreferred() {
        var first = true
        ttmlAnswer = { if (first) { first = false; delay(2_000) }; buildJsonObject { put("ttml", ttml) } }
        val shown = lookUp()
        assertEquals("Line timing first, then the word timing replaces it", listOf(false, true), shown.map { it.wordTimed })
        assertTrue(shown.first().timed)
        assertEquals("BetterLyrics asked twice", 2, asked().count { it == "https://api.betterlyrics.org/getLyrics" })
        assertEquals("The word timing is what is cached", listOf(true), lookUp().map { it.wordTimed })
        assertEquals("A cached word-timed result asks nobody", emptyList<String>(), asked())
    }

    @Test fun aCachedLineTimedResultIsAskedAgainForWordTimingAndReplaced() {
        assertEquals(listOf(false), lookUp().map { it.wordTimed })
        assertEquals("Within 12 hours the cached line timing is all that is shown and nobody is asked",
            listOf(false) to emptyList<String>(), lookUp().map { it.wordTimed } to asked())

        ageTheCache(13)
        ttmlAnswer = { buildJsonObject { put("ttml", ttml) } }
        assertEquals("The cached lines first, then the word timing", listOf(false, true), lookUp().map { it.wordTimed })
        assertTrue("Only the word-timed sources are asked", asked().none { it.contains("lrclib") || it.contains("kugou") || it.contains("lyrics.ovh") })
        assertEquals("And the word timing is now the cached result", listOf(true) to emptyList<String>(), lookUp().map { it.wordTimed } to asked())
    }

    @Test fun aWordTimingRetryThatFindsNothingWaitsAnother12Hours() {
        lookUp(); ageTheCache(13)
        assertEquals(listOf(false), lookUp().map { it.wordTimed })
        assertTrue("The word-timed sources were asked", asked().contains("https://api.betterlyrics.org/getLyrics"))
        assertEquals("Not again straight away", emptyList<String>(), lookUp().let { asked() })
        ageTheCache(13)
        lookUp(); assertTrue("Again after 12 hours", asked().contains("https://api.betterlyrics.org/getLyrics"))
    }

    @Test fun aRetryThatCannotReachAnySourceStillShowsTheLinesAndDoesNotFail() {
        lookUp(); ageTheCache(13)
        ttmlAnswer = { throw java.io.IOException("offline") }
        assertEquals(listOf(false), lookUp().map { it.wordTimed })
        assertTrue(dir.listFiles().orEmpty().any { it.extension == "json" })
    }
}
