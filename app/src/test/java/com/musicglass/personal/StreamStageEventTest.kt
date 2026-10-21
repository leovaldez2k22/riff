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
        assertEquals("stream_stage player_responses_received ms=812",
            streamStageEvent(event("player responses received", mapOf("count" to "2", "elapsedMs" to "812"), mediaId = "abcdefghijk")))
        assertEquals("stream_stage cipher_processing_completed ms=40",
            streamStageEvent(event("cipher processing completed", mapOf("client" to "WEB_REMIX", "elapsedMs" to "40"))))
    }

    @Test fun otherMessagesAndStepsWithoutADurationAreLeftOut() {
        assertNull(streamStageEvent(event("SABR stream selected", mapOf("client" to "WEB", "profile" to "p"))))
        assertNull(streamStageEvent(event("player responses received", mapOf("count" to "2"))))
        assertNull(streamStageEvent(event("embedded config ready", mapOf("elapsedMs" to "soon"))))
    }

    /** Q18-059: the prewarm and the first song must mint for the same visitor, so a session without one gets it first. */
    @Test fun aSessionWithoutAVisitorFetchesOneBeforeThePrewarm() = kotlinx.coroutines.runBlocking {
        var fetched = 0
        assertTrue(settleVisitor(null) { fetched++; "Cgt2aXNpdG9y" }); assertTrue(settleVisitor(" ") { fetched++; null })
        assertFalse(settleVisitor("Cgt2aXNpdG9y") { fetched++; null })
        assertEquals(2, fetched)
    }
}
