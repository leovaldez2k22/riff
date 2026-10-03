package com.musicglass.personal

import com.metrolist.innertubex.InnerTubeHttpException
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Fixtures mirror the shapes captured on device on 2026-10-03 (SessionProbeInstrumentation), reduced
 * to the fields that matter. No real account data.
 */
class SessionHealthTest {
    private val validText = """{"responseContext":{},"trackingParams":"x","actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Someone"}]}}}}}}}]}"""
    private val unauthenticatedText = """{"error":{"code":401,"message":"Request is missing required authentication credential.","status":"UNAUTHENTICATED"}}"""
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test fun validSessionHasAnAccountHeader() {
        assertEquals(SessionState.Valid, classifySessionProbe(200, json(validText), validText, networkError = false))
    }

    @Test fun missingScrambledOrRevokedCookiesAre401Unauthenticated() {
        assertEquals(SessionState.Expired, classifySessionProbe(401, json(unauthenticatedText), unauthenticatedText, false))
        // Either signal alone is enough.
        assertEquals(SessionState.Expired, classifySessionProbe(401, null, "", false))
        assertEquals(SessionState.Expired, classifySessionProbe(200, json(unauthenticatedText), unauthenticatedText, false))
    }

    @Test fun anythingElseIsUnverifiedNeverExpired() {
        assertEquals(SessionState.Unverified, classifySessionProbe(null, null, null, networkError = true))
        assertEquals(SessionState.Unverified, classifySessionProbe(403, null, "bot check", false))
        assertEquals(SessionState.Unverified, classifySessionProbe(503, null, "", false))
        val noHeader = """{"responseContext":{},"actions":[]}"""
        assertEquals(SessionState.Unverified, classifySessionProbe(200, json(noHeader), noHeader, false))
    }

    @Test fun authFailureIsA401AnywhereInTheCauseChain() {
        val unauthorized = InnerTubeHttpException("browse", HttpStatusCode.Unauthorized)
        assertTrue(isAuthFailure(unauthorized))
        assertTrue(isAuthFailure(IOException("wrapped", RuntimeException(unauthorized))))
        assertFalse(isAuthFailure(InnerTubeHttpException("browse", HttpStatusCode.Forbidden)))
        assertFalse(isAuthFailure(InnerTubeHttpException("browse", HttpStatusCode.InternalServerError)))
        assertFalse(isAuthFailure(IOException("offline")))
        assertFalse(isAuthFailure(null))
    }

    @Test fun resumeChecksAreSixHoursApartForcedChecksThirtySeconds() {
        var now = 0L
        val schedule = SessionCheckSchedule({ now })
        assertTrue("first check is always due", schedule.due(forced = false))
        schedule.checked()
        now += 60_000; assertFalse(schedule.due(forced = false)); assertTrue(schedule.due(forced = true))
        now = 6 * 60 * 60_000L - 1; assertFalse(schedule.due(false))
        now += 1; assertTrue(schedule.due(false))
        schedule.checked(); now += 10_000; assertFalse("401 storm is rate-limited", schedule.due(forced = true))
        schedule.reset(); assertTrue("account change checks at once", schedule.due(false))
    }

    @Test fun earlyForcedCheckIsPostponedNotDropped() {
        var now = 0L
        val schedule = SessionCheckSchedule({ now })
        schedule.checked()
        now = 12_000
        assertFalse(schedule.due(forced = true))
        assertEquals(18_000L, schedule.waitMs(forced = true))
        now = 30_000; assertEquals(0L, schedule.waitMs(forced = true)); assertTrue(schedule.due(forced = true))
    }
}
