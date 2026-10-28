package com.musicglass.personal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature 4: lrc.red, searched by title and artist, read from its TTML. Answers as recorded on 4 Oct 2026. */
class LrcRedLyricsTest {
    private val bohemian = Json.parseToJsonElement("""{"hits":[
        {"isrc":"GBUM71100986","title":"Bohemian Rhapsody (Operatic Section / 2011 A Cappella Mix)","artist":"Queen","duration":64.969},
        {"isrc":"GBUM71029604","title":"Bohemian Rhapsody","artist":"Queen","album":"A Night at the Opera","duration":356.52},
        {"isrc":"GBCEG0100046","title":"Bohemian Rhapsody (Live)","artist":"Queen","duration":352.04}],"next":"p24"}""")
    private fun track(title: String, artist: String, ms: Long) = LyricsTrack("yt:abcdefghijk", title, artist, "", ms)

    @Test fun theStudioRecordingIsChosenNotTheMixOrTheLiveCut() {
        assertEquals("GBUM71029604", lrcRedBestIsrc(bohemian, track("Bohemian Rhapsody", "Queen", 356_000)))
    }

    @Test fun aLengthTooFarOffOrAnotherArtistIsNoMatch() {
        assertNull(lrcRedBestIsrc(bohemian, track("Bohemian Rhapsody", "Queen", 300_000)))
        assertNull(lrcRedBestIsrc(bohemian, track("Bohemian Rhapsody", "Somebody Else", 356_000)))
    }

    @Test fun oneOfSeveralCreditedArtistsIsEnough() {
        val tumHiHo = Json.parseToJsonElement("""{"hits":[{"isrc":"INS181303031","title":"Tum Hi Ho","artist":"Mithoon, Arijit Singh","duration":261.974}]}""")
        assertEquals("INS181303031", lrcRedBestIsrc(tumHiHo, track("Tum Hi Ho", "Arijit Singh", 262_000)))
    }

    @Test fun onlyAWellFormedIsrcBecomesAPath() {
        assertEquals("https://lrc.red/s/GBUM71029604.ttml", lrcRedDocumentUrl(" gbum71029604 "))
        assertNull(lrcRedDocumentUrl("../search.json?q=x"))
        assertNull(lrcRedDocumentUrl(""))
        assertNull(lrcRedDocumentUrl(null))
    }

    @Test fun theTtmlIsReadWordTimed() = runBlocking {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="2s" end="4s"><span begin="2s" end="3s">Is</span> <span begin="3s" end="4s">this</span></p></div></body></tt>"""
        val asked = mutableListOf<Pair<String, Boolean>>()
        val doc = lrcRedLyrics(track("Bohemian Rhapsody", "Queen", 356_000), "Bohemian Rhapsody", "Queen") { url, _, raw ->
            asked += url to raw
            val answer: JsonElement? = if (url.endsWith("search.json")) bohemian else JsonPrimitive(ttml)
            answer
        }!!
        assertEquals(LRC_RED_SOURCE, doc.source)
        assertEquals("https://lrc.red/s/GBUM71029604.ttml", doc.sourceUrl)
        assertTrue(doc.wordTimed)
        assertEquals(listOf("https://lrc.red/search.json" to false, "https://lrc.red/s/GBUM71029604.ttml" to true), asked)
    }

    @Test fun itRanksBehindBetterLyricsAndAheadOfNetEase() {
        assertTrue(lyricsProviderPriority("Better Lyrics · QQ") < lyricsProviderPriority(LRC_RED_SOURCE))
        assertTrue(lyricsProviderPriority(LRC_RED_SOURCE) < lyricsProviderPriority("NetEase Cloud Music"))
    }
}
