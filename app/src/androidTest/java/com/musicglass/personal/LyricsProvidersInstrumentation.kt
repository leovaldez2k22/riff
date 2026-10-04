package com.musicglass.personal

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

/** Real public-provider acceptance check on the phone; never bundled in the release APK. */
class LyricsProvidersInstrumentation {
    @Test fun netEaseReturnsMatchedLyricsOrReportsVerificationWithoutBlockingFallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LyricsRepository(File(context.cacheDir,"lyrics-provider-device-check"))
        try {
            val track=LyricsTrack("yt:abcdefghijk", "Yellow", "Coldplay", "Parachutes", 266773)
            val doc = try { withTimeout(40_000) { repo.netEase(track) } }
            catch (e:java.io.IOException) {
                if(!e.message.orEmpty().contains("code -462")) throw e
                InstrumentationRegistry.getInstrumentation().sendStatus(1,android.os.Bundle().apply {
                    putString("provider_limitation","NetEase requires anonymous-client verification (-462). No live matched NetEase lyrics were obtained on this phone.")
                })
                // Exercise the production hierarchy with the actual verification error injected:
                // earlier sources miss, NetEase rejects, and the next source must still be published.
                val fallback=LyricsRepository(File(context.cacheDir,"lyrics-fallback-device-check")) {url,_ ->
                    when {
                        url.contains("music.163.com") -> throw e
                        url.contains("lrclib.net/api/get") -> kotlinx.serialization.json.Json.parseToJsonElement("""{"trackName":"Yellow","artistName":"Coldplay","duration":266.773,"syncedLyrics":"[00:33.79]Look at the stars"}""")
                        else -> null
                    }
                }
                try {
                    val result=fallback.lyrics(track,force=true) {null}.toList().last()
                    assertEquals("LRCLIB",result.source);assertTrue(result.timed)
                } finally {fallback.close()}
                return@runBlocking
            }
            assertNotNull("NetEase supplied no matched lyrics",doc)
            assertEquals("NetEase Cloud Music",doc!!.source)
            assertTrue(doc.timed)
            assertTrue(doc.lines.any { it.text.contains("Look at the stars",ignoreCase=true) })
        } finally {repo.close()}
    }
    @Test fun ovhReturnsPlainLyricsWithoutAKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = LyricsRepository(File(context.cacheDir,"lyrics-provider-device-check"))
        try {
            val doc = withTimeout(15_000) { repo.ovh(LyricsTrack("", "Yellow", "Coldplay", "Parachutes", 266773)) }
            assertNotNull("Lyrics.ovh supplied no lyrics",doc)
            assertEquals("Lyrics.ovh",doc!!.source)
            assertFalse(doc.timed)
            assertTrue(doc.lines.any { it.text.contains("Look at the stars",ignoreCase=true) })
        } finally {repo.close()}
    }
}
