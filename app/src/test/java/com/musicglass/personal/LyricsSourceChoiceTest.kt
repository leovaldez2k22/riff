package com.musicglass.personal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Feature 25 (owner, Q25): "Source for this song". The chosen source is asked alone, its validated answer is shown
 * whatever its timing and kept out of the saved lyrics; a miss or no connection falls back to Automatic. Fakes only.
 */
class LyricsSourceChoiceTest {
    private val track = LyricsTrack("yt:delicate00", "Delicate", "Taylor Swift", "reputation", 20_000)
    private val dir: File = Files.createTempDirectory("lyrics-choice").toFile()
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val repos = mutableListOf<LyricsRepository>()
    @After fun clean() { repos.forEach { it.close() }; dir.deleteRecursively() }

    private val wordTtml = buildJsonObject { put("ttml", """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="4s"><span begin="2s" end="3s">Morning</span> <span begin="3s" end="4s">path</span></p></div></body></tt>""") }
    private val lrclibPlain = Json.parseToJsonElement("""{"id":12,"trackName":"Delicate","artistName":"Taylor Swift","duration":20,"plainLyrics":"Morning path, plain"}""")
    private fun name(url: String) = when {
        url.endsWith("api.betterlyrics.org/getLyrics") -> "bl"
        url.contains("lrclib") -> "lrclib"
        url.contains("lyrics.ovh") -> "ovh"
        else -> "other"
    }
    private fun repository(answers: Map<String, () -> JsonElement?>) = LyricsRepository(dir, 2_000, 50, 2_000) { url, _ ->
        name(url).let { asked += it; answers[it]?.invoke() }
    }.also { repos += it }
    private fun StateFlow<LyricsLoadState>.settled(after: Long) = runBlocking { withTimeout(10_000) { first { it.generation > after && !it.loading } } }

    @Test fun aChosenPlainSourceIsAskedAloneAndShownOverSavedWordTiming() {
        val repo = repository(mapOf("bl" to { wordTtml }, "lrclib" to { lrclibPlain }))
        val flow = repo.observe(track) { null }
        val first = flow.settled(0)
        assertEquals("Better Lyrics", first.document!!.source)
        asked.clear()
        repo.choose(track, "LRCLIB") { null }
        val chosen = flow.settled(first.generation)
        assertEquals("LRCLIB", chosen.document!!.source); assertFalse(chosen.document!!.timed)
        assertEquals("LRCLIB", chosen.choice); assertNull(chosen.fallback)
        assertEquals("Only the chosen source was asked", setOf("lrclib"), asked.toSet())
        // Its answer never replaced the saved, automatic best.
        val saved = runBlocking { repository(emptyMap()).lyrics(track) { null }.toList() }
        assertEquals("Better Lyrics", saved.first().source)
        // Automatic again: the saved lyrics, no new request.
        asked.clear()
        repo.choose(track, null) { null }
        val automatic = flow.settled(chosen.generation)
        assertEquals("Better Lyrics", automatic.document!!.source); assertNull(automatic.choice)
        assertTrue(asked.isEmpty())
    }

    @Test fun aMissFallsBackToAutomaticAndIsNotOfferedAgain() {
        val repo = repository(mapOf("bl" to { wordTtml }))
        val flow = repo.observe(track) { null }
        val first = flow.settled(0)
        repo.choose(track, "Lyrics.ovh") { null }
        val after = flow.settled(first.generation)
        assertEquals(LyricsFallback.Miss, after.fallback)
        assertEquals("Better Lyrics", after.document!!.source)
        assertEquals(setOf("Lyrics.ovh"), after.missed)
    }

    @Test fun noConnectionIsTemporaryAndStillShowsLyrics() {
        val repo = repository(mapOf("bl" to { wordTtml }, "lrclib" to { throw IOException("offline") }))
        val flow = repo.observe(track) { null }
        val first = flow.settled(0)
        repo.choose(track, "LRCLIB") { null }
        val after = flow.settled(first.generation)
        assertEquals(LyricsFallback.Unreachable, after.fallback)
        assertTrue(after.missed.isEmpty())
        assertEquals("Better Lyrics", after.document!!.source)
    }

    @Test fun aQuickSecondChoiceWinsOverTheFirst() {
        val repo = repository(mapOf("bl" to { Thread.sleep(300); wordTtml }, "lrclib" to { lrclibPlain }))
        val flow = repo.observe(track) { null }
        val first = flow.settled(0)
        repo.choose(track, "Better Lyrics") { null }
        repo.choose(track, "LRCLIB") { null }
        val after = flow.settled(first.generation + 1)
        assertEquals("LRCLIB", after.choice); assertEquals("LRCLIB", after.document!!.source)
    }

    @Test fun thePickerOffersYouTubeMusicOnlyForItsSongs() {
        assertTrue("YouTube Music" in lyricsSourceChoices(track))
        assertFalse("YouTube Music" in lyricsSourceChoices(track.copy(mediaId = "local:1")))
        assertEquals("Better Lyrics", lyricsSourceChoices(track).first())
    }
}
