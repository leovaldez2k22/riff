package com.musicglass.personal

import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Q18-059: the extractor's timed steps reach the diagnostics log as a fixed name and milliseconds, nothing else. */
class StreamStageEventTest {
    private fun event(message: String, details: Map<String, String>, mediaId: String? = null) =
        InnerTubeLogEvent(InnerTubeLogLevel.DEBUG, "InnerTubeExtractor", message, mediaId, details)

    @Test fun aTimedStepKeepsOnlyItsNameAndDuration() {
        assertEquals("stream_stage stage=player_responses_received ms=812",
            streamStageEvent(event("player responses received", mapOf("count" to "2", "elapsedMs" to "812"), mediaId = "abcdefghijk")))
        assertEquals("stream_stage stage=cipher_processing_completed ms=40",
            streamStageEvent(event("cipher processing completed", mapOf("client" to "WEB_REMIX", "elapsedMs" to "40"))))
    }

    @Test fun otherMessagesAndStepsWithoutADurationAreLeftOut() {
        assertNull(streamStageEvent(event("SABR stream selected", mapOf("client" to "WEB", "profile" to "p"))))
        assertNull(streamStageEvent(event("player responses received", mapOf("count" to "2"))))
        assertNull(streamStageEvent(event("embedded config ready", mapOf("elapsedMs" to "soon"))))
    }

    /** On the phone the names came out as <redacted>: the diagnostics ring keeps only listed key=value fields. */
    @Test fun theStepNameSurvivesTheDiagnosticsFilter() {
        for (stage in listOf("prewarm completed", "embedded config ready", "watch page config ready", "player responses received",
                "cipher processing completed", "cipher fallback completed")) {
            val line = checkNotNull(streamStageEvent(event(stage, mapOf("elapsedMs" to "9"))))
            assertEquals(line, DiagnosticEvent.parse(line).text)
        }
    }

    /** Q18-059: the cipher's sub-steps keep their name, milliseconds, count and flags; the player id is dropped. */
    @Test fun aCipherStepKeepsItsTimingAndNotThePlayer() {
        val evaluate = checkNotNull(cipherStepEvent("EJS evaluate done preprocessed=true requests=2 elapsed=1412ms player=9f1c2a7b"))
        assertEquals("cipher_step stage=ejs_evaluate ms=1412 count=2 preprocessed=true", evaluate)
        assertEquals(evaluate, DiagnosticEvent.parse(evaluate).text)
        val start = checkNotNull(cipherStepEvent("processFormats start formats=18 cipherFormats=0 nFormats=18 cachedSolver=false player=9f1c2a7b"))
        assertEquals("cipher_step stage=process_start count=18 cached=false", start)
        assertEquals(start, DiagnosticEvent.parse(start).text)
        // Q18-067: whether the player was ready from the start-up preload or prepared for this song.
        val ready = checkNotNull(cipherStepEvent("player JS ready source=downloaded size=2614032 elapsed=2210ms player=9f1c2a7b"))
        assertEquals("cipher_step stage=player_ready ms=2210 source=downloaded", ready)
        assertEquals(ready, DiagnosticEvent.parse(ready).text)
        assertEquals("cipher_step stage=player_ready ms=3 source=in_flight", cipherStepEvent("player JS ready source=in-flight size=1 elapsed=3ms player=9f1c2a7b"))
        assertEquals("cipher_step stage=player_ready ms=3", cipherStepEvent("player JS ready source=https://x size=1 elapsed=3ms player=9f1c2a7b"))
        assertNull(cipherStepEvent("EJS solve failed player=9f1c2a7b type=IllegalStateException"))
        assertNull(cipherStepEvent("rejecting format with unresolved n itag=251 player=9f1c2a7b"))
    }

    /** Q18-067: the signed-out player is prepared too, only when the first lookup would need it and it is another one. */
    @Test fun theSignedOutPlayerIsPreparedOnlyWhenTheFirstLookupNeedsADifferentOne() {
        assertEquals("/s/player/aaa/base.js", secondPlayerToPreload(true, false, "/s/player/aaa/base.js", "/s/player/bbb/base.js"))
        assertNull("the same player", secondPlayerToPreload(true, false, "/s/player/aaa/base.js", "/s/player/aaa/base.js"))
        assertNull("signed out: the library prepared it", secondPlayerToPreload(false, false, "/s/player/aaa/base.js", null))
        assertNull("Premium audio starts signed in", secondPlayerToPreload(true, true, "/s/player/aaa/base.js", "/s/player/bbb/base.js"))
        assertNull("no signed-in config: the library prepared the signed-out one", secondPlayerToPreload(true, false, "/s/player/aaa/base.js", null))
        assertNull(secondPlayerToPreload(true, false, null, "/s/player/bbb/base.js"))
    }

    /** Q18-059: the prewarm and the first song must mint for the same visitor, so a session without one gets it first. */
    @Test fun aSessionWithoutAVisitorFetchesOneBeforeThePrewarm() = kotlinx.coroutines.runBlocking {
        var fetched = 0
        assertTrue(settleVisitor(null) { fetched++; "Cgt2aXNpdG9y" }); assertTrue(settleVisitor(" ") { fetched++; null })
        assertFalse(settleVisitor("Cgt2aXNpdG9y") { fetched++; null })
        assertEquals(2, fetched)
    }
}
