package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/**
 * Q10A (OPS01): what the canary calls a healthy, malformed, rejected, blocked and unreachable provider. Every
 * answer here is scripted; nothing leaves the process. The provider class and the pinned library are the real ones.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[36])
class ProviderCanaryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var world: CanaryWorld? = null
    @After fun close() { world?.close() }
    private fun world(script: (String) -> CanaryReply) = CanaryWorld(context, script).also { world = it }
    private fun fixture(name: String) = CanaryReply(providerFixture(name))
    private fun healthy(endpoint: String) = if (endpoint == "search") fixture("search-songs.json") else fixture("album.json")
    private fun CanaryPhase.said() = "${classification.name}/$reason"

    @Test fun aHealthyProviderIsHealthyInBothCatalogPhasesWithOneRequestEach() = runBlocking {
        val w = world(::healthy)
        val search = w.search(); val collection = w.collection()
        assertEquals("Healthy/ok", search.said()); assertEquals(1, search.requests)
        assertEquals("Healthy/ok", collection.said()); assertEquals(1, collection.requests)
        assertFalse("the canary has no account", w.provider.signedIn)
    }

    @Test fun anAnswerThatArrivesAndCannotBeUsedIsAParserBreak() = runBlocking {
        assertEquals("not JSON: the parser finds nothing in it", "ParserBreak/empty", world { CanaryReply("<html>") }.search().said()); world?.close()
        assertEquals("a failure with an answer and no known cause", CanaryClass.ParserBreak to "unreadable",
            classifyCatalogPhase("YouTube Music could not load this content", 0, listOf(CanaryExchange(200))))
        assertEquals("an answer with no rows for a song everyone has", "ParserBreak/empty", world { fixture("search-empty.json") }.search().said()); world?.close()
        assertEquals("an empty object", "ParserBreak/empty", world { CanaryReply("{}") }.collection().said()); world?.close()
        assertEquals("a request the provider no longer accepts", "ParserBreak/rejected", world { CanaryReply("{}", 400) }.search().said())
    }

    @Test fun aRefusalALimitOrASignInDemandIsABlockAndNeverABreak() = runBlocking {
        for ((status, reason) in listOf(403 to "refused", 429 to "rate_limited", 401 to "sign_in_required")) {
            val w = world { CanaryReply("{}", status) }
            assertEquals("search $status", "Blocked/$reason", w.search().said())
            assertEquals("collection $status", "Blocked/$reason", w.collection().said())
            w.close()
        }
        assertEquals("refused inside HTTP 200", "Blocked/refused", world { fixture("error-envelope.json") }.search().said())
    }

    @Test fun noAnswerOrAServerErrorIsAnOutageAndNeverABreak() = runBlocking {
        assertEquals("Outage/unreachable", world { CanaryReply("", failure = UnknownHostException("scripted")) }.search().said()); world?.close()
        assertEquals("Outage/unreachable", world { CanaryReply("", failure = IOException("scripted reset")) }.collection().said()); world?.close()
        assertEquals("Outage/timeout", world { CanaryReply("", failure = java.net.SocketTimeoutException("scripted")) }.search().said()); world?.close()
        assertEquals("Outage/server_error", world { CanaryReply("{}", 503) }.search().said())
    }

    @Test fun aCollectionThatNoLongerExistsIsPassedOverAndAStaleListIsTheCanarysOwnFault() = runBlocking {
        var asked = 0
        val w = world { if (asked++ == 0) fixture("error-not-found.json") else fixture("album.json") }
        val phase = w.collection()
        assertEquals("Healthy/ok", phase.said()); assertEquals("the second target was asked for", 2, phase.requests); w.close()
        val stale = world { fixture("error-not-found.json") }.collection()
        assertEquals("HarnessFault/targets_stale", stale.said())
        assertEquals("no more than two targets are tried", CANARY_TARGET_TRIES, stale.requests)
    }

    @Test fun aSearchEndpointThatIsGoneIsABreak() = runBlocking {
        assertEquals("ParserBreak/not_found", world { CanaryReply("{}", 404) }.search().said())
    }

    // ---- the stream phase, with the resolver and the audio host scripted

    private fun stream(url: String = "http://127.0.0.1:9/never-asked") = ProviderStream(url, emptyMap(), "audio/webm")
    private fun streamCanary(signedIn: Boolean = false, solver: Boolean = true, bytes: (ProviderStream) -> Pair<CanaryExchange, Int> = { CanaryExchange(206) to CANARY_FIRST_BYTES },
        resolve: suspend (String) -> Result<ProviderStream>) = StreamCanary({ signedIn }, { solver }, resolve, bytes)
    private fun failed(message: String) = Result.failure<ProviderStream>(IllegalStateException(message))

    @Test fun aStreamThatResolvesAndGivesItsFirstBytesIsHealthy() = runBlocking {
        val asked = mutableListOf<String>()
        val phase = streamCanary { asked += it; Result.success(stream()) }.run()
        assertEquals("Healthy/ok", phase.said())
        assertEquals("one song only", listOf(CANARY_TRACKS[0]), asked); assertEquals("one resolution and one read", 2, phase.requests)
    }

    @Test fun theFirstBytesAreReallyOnlyTheFirstBytes() {
        val audio = ByteArray(64 * 1024) { (it % 251).toByte() }
        FixtureHttpServer(mapOf("/audio" to FixtureHttpServer.Reply(audio)), maxRequests = 2).use { server ->
            val (answer, received) = fetchFirstBytes(stream("${server.baseUrl}/audio"))
            assertEquals(206, answer.status); assertEquals(CANARY_FIRST_BYTES, received)
            val (missing, none) = fetchFirstBytes(stream("${server.baseUrl}/gone"))
            assertEquals("ResolutionBreak/no_bytes", classifyFirstBytes(missing, none).let { "${it.first.name}/${it.second}" })
        }
        val (down, nothing) = fetchFirstBytes(stream("http://127.0.0.1:9/closed"))
        assertTrue(down.unreachable); assertEquals(0, nothing)
    }

    @Test fun aStreamThatCannotBeResolvedOrIsRefusedByTheAudioHostIsAResolutionBreak() = runBlocking {
        assertEquals("ResolutionBreak/no_stream", streamCanary { failed("No supported audio stream is available for this track") }.run().said())
        assertEquals("ResolutionBreak/no_stream", streamCanary { failed("Could not prepare this track (IllegalArgumentException)") }.run().said())
        assertEquals("ResolutionBreak/no_stream", streamCanary { failed("Track unavailable: UNKNOWN") }.run().said())
        assertEquals("ResolutionBreak/stream_refused", streamCanary(bytes = { CanaryExchange(403) to 0 }) { Result.success(stream()) }.run().said())
        assertEquals("ResolutionBreak/no_bytes", streamCanary(bytes = { CanaryExchange(200) to 0 }) { Result.success(stream()) }.run().said())
    }

    @Test fun aNetworkFailureALimitOrEverySongUnavailableIsNotABreak() = runBlocking {
        assertEquals("Outage/unreachable", streamCanary { failed("Could not prepare this track (UnknownHostException)") }.run().said())
        assertEquals("Outage/unreachable", streamCanary { failed("Track unavailable: NETWORK") }.run().said())
        assertEquals("Outage/timeout", streamCanary { failed("Could not prepare this track (SocketTimeoutException)") }.run().said())
        assertEquals("Outage/unreachable", streamCanary(bytes = { CanaryExchange(unreachable = true) to 0 }) { Result.success(stream()) }.run().said())
        assertEquals("Outage/server_error", streamCanary(bytes = { CanaryExchange(502) to 0 }) { Result.success(stream()) }.run().said())
        assertEquals("Blocked/rate_limited", streamCanary(bytes = { CanaryExchange(429) to 0 }) { Result.success(stream()) }.run().said())
        val asked = mutableListOf<String>()
        assertEquals("Blocked/all_targets_unavailable", streamCanary { asked += it; failed("Track unavailable: UNAVAILABLE") }.run().said())
        assertEquals("the next song is tried, and no more than two", CANARY_TRACKS.take(CANARY_TARGET_TRIES), asked)
    }

    @Test fun oneUnavailableSongIsPassedOver() = runBlocking {
        var n = 0
        assertEquals("Healthy/ok", streamCanary { if (n++ == 0) failed("Track unavailable: AGE_RESTRICTED") else Result.success(stream()) }.run().said())
    }

    @Test fun aSignedInAppAMissingSolverOrAnErrorInTheCanaryIsAHarnessFaultAndSendsNothing() = runBlocking {
        var asked = 0
        assertEquals("HarnessFault/signed_in", streamCanary(signedIn = true) { asked++; Result.success(stream()) }.run().said())
        assertEquals("HarnessFault/solver_unavailable", streamCanary(solver = false) { asked++; Result.success(stream()) }.run().said())
        assertEquals("nothing was asked of the provider", 0, asked)
        assertEquals("HarnessFault/solver_unavailable", streamCanary { failed("Online playback resolver unavailable") }.run().said())
        assertEquals("HarnessFault/harness_exception", streamCanary { error("scripted fault in the canary") }.run().said())
    }

    @Test fun aResultHoldsAClassAReasonWordATimeAndACountAndNothingElse() = runBlocking {
        val w = world(::healthy)
        val stream = streamCanary { Result.success(stream("https://rr1---sn-fixture.googlevideo.com/videoplayback?sig=SECRET&n=abc")) }.run()
        val json = canaryJson(listOf(w.search(), w.collection(), stream))
        assertTrue(json, Regex("""\{"schema":1,"phases":\[(\{"phase":"[a-z]+","classification":"[A-Za-z]+","reason":"[a-z_]+","millis":\d+,"requests":\d+\},?){3}]}""").matches(json))
        for (secret in listOf("http", "googlevideo", "SECRET", "sig=", CANARY_TRACKS[0], CANARY_COLLECTIONS[0], "Rick")) assertFalse(secret, json.contains(secret))
    }
}
