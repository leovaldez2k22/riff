package com.musicglass.personal

import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

/** Q18-065: Better Lyrics answers keyless lookups from its cache only, under the key its extension uses. */
class BetterLyricsCacheTest {
    private val track = LyricsTrack("yt:abcdefghijk","Die With A Smile","Lady Gaga & Bruno Mars","Die With A Smile",252000)
    private val ttml = Json.parseToJsonElement(
        """{"ttml":"<tt xmlns='http://www.w3.org/ns/ttml'><body><p begin='2s' end='5s'><span begin='2s' end='3s'>Ooh</span></p></body></tt>"}""")

    private fun withRepo(transport: suspend (String,Map<String,String>) -> JsonElement?,
        block: suspend (LyricsRepository) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("better-lyrics").toFile()
        val repo = LyricsRepository(dir,transport = transport)
        try { block(repo) } finally { repo.close();dir.listFiles().orEmpty().forEach {it.delete()};dir.delete() }
    }

    @Test fun aMissIsAskedOnceMoreWithTheExtensionsArtistsAndNoAlbum() {
        val asked = mutableListOf<Map<String,String>>()
        withRepo({url,params -> if (url.endsWith("api.betterlyrics.org/getLyrics")) {
            asked += params
            if (params["a"] == "Lady Gaga, Bruno Mars" && "al" !in params) ttml else throw LyricsCacheMiss()
        } else null}) {repo ->
            val docs = repo.lyrics(track) {null}.toList()
            assertEquals("Better Lyrics",docs.last().source)
            assertEquals(2,asked.size)
            assertEquals("Lady Gaga & Bruno Mars",asked[0]["a"]);assertEquals("Die With A Smile",asked[0]["al"])
        }
    }

    @Test fun aMissIsNotALyricsSourceThatCouldNotBeReached() {
        withRepo({url,_ -> if (url.contains("betterlyrics")) throw LyricsCacheMiss() else null}) {repo ->
            assertTrue(repo.lyrics(track) {null}.toList().isEmpty()) // no "Couldn't load lyrics"
        }
    }

    @Test fun theLooserQueryIsLeftOutWhenItWouldBeTheSame() {
        assertNull(betterLyricsLooserQuery(mapOf("s" to "No One Noticed","a" to "The Marías","al" to "","d" to "285")))
        assertEquals(mapOf("s" to "x","a" to "A, B, C","d" to "1"),
            betterLyricsLooserQuery(mapOf("s" to "x","a" to "A, B & C","al" to "Album","d" to "1")))
        assertEquals("Simon, Garfunkel",betterLyricsLooserQuery(mapOf("a" to "Simon & Garfunkel"))!!["a"]) // only on a miss
    }
}
