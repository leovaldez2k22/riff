package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Feature 2 (owner, Q2): every send goes to a fake; every title, artist, token and id is invented. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScrobbleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var clock = 1_800_000_000_000L
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    /** Not encryption: enough to see that the stored text is not the token, and that a failed seal keeps nothing. */
    private val seal = object : TokenSeal {
        var works = true
        override fun seal(plain: String) = if (works) "sealed:" + plain.reversed() else null
        override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed()
    }
    private lateinit var outbox: ScrobbleOutbox
    private lateinit var settings: ScrobbleSettings

    @Before fun setUp() {
        outbox = ScrobbleOutbox(prefs("scrobble_outbox_test")) { clock }
        settings = ScrobbleSettings(prefs("scrobble_accounts_test"), seal)
    }

    private fun play(id: String = "p1", ms: Long = 200_000, scope: String = "owner-a", at: Long = clock, device: Boolean = false) =
        ScrobblePlay(id, scope, "Ab-_12345cd", "First Light", "Fixture Band", "Album One", ms, at, device)

    @Test fun theThresholdIsHalfTheTrackOrFourMinutesAndShortTracksNever() {
        assertNull(scrobbleThresholdMs(30_000))
        assertEquals(15_500L, scrobbleThresholdMs(31_000))
        assertEquals(100_000L, scrobbleThresholdMs(200_000))
        assertEquals(240_000L, scrobbleThresholdMs(600_000))
    }

    @Test fun onlyHeardTimeCountsAndAPlayIsHandedBackOnce() {
        val meter = ScrobbleMeter()
        meter.start(play(ms = 60_000), 0)
        var pos = 0L
        repeat(3) { pos += 5_000; assertNull(meter.heard(5_000, true, pos)) }
        // A seek forward to the end earns no more than the tick.
        pos = 39_000; assertNull(meter.heard(5_000, true, pos)); assertEquals(20_000L, meter.heardMs)
        // Paused or buffering: the clock runs, nothing is earned.
        assertNull(meter.heard(5_000, false, pos)); assertEquals(20_000L, meter.heardMs)
        // A late tick earns at most five seconds, and never more than the position moved.
        pos = 0; meter.start(play("p2", 40_000), 0)
        assertNull(meter.heard(60_000, true, 2_000)); assertEquals(3_500L, meter.heardMs)
        repeat(4) { pos = 2_000L + (it + 1) * 5_000; meter.heard(5_000, true, pos) }
        assertNull(meter.heard(5_000, true, pos + 5_000)) // 23.5 s heard: past 20 s, so handed back above
        val again = ScrobbleMeter().apply { start(play("p3", 40_000), 0) }
        val handed = (1..4).mapNotNull { again.heard(5_000, true, it * 5_000L) }
        assertEquals(listOf("p3"), handed.map { it.id })
        assertNull("Once only", again.heard(5_000, true, 25_000))
        // The boundary: exactly half of the track is enough.
        val edge = ScrobbleMeter().apply { start(play("p4", 31_000), 0) }
        assertNull(edge.heard(5_000, true, 5_000)); assertNull(edge.heard(5_000, true, 10_000)); assertNull(edge.heard(5_000, true, 15_000))
        assertNotNull(edge.heard(500, true, 15_500))
    }

    @Test fun rowsAreKeptOnceBoundedAndForSevenDays() {
        assertTrue(outbox.add(ScrobbleService.ListenBrainz, play("a")))
        assertFalse(outbox.add(ScrobbleService.ListenBrainz, play("a")))
        assertTrue(outbox.rows(ScrobbleService.LastFm).isEmpty())
        repeat(SCROBBLE_PENDING_LIMIT) { outbox.add(ScrobbleService.ListenBrainz, play("n$it")) }
        val rows = outbox.rows(ScrobbleService.ListenBrainz)
        assertEquals(SCROBBLE_PENDING_LIMIT, rows.size); assertEquals("n0", rows.first().play.id)
        outbox.clear(ScrobbleService.ListenBrainz)
        outbox.add(ScrobbleService.ListenBrainz, play("old", at = clock - SCROBBLE_KEEP_MS - 1))
        outbox.add(ScrobbleService.ListenBrainz, play("new"))
        assertEquals(listOf("new"), outbox.due(ScrobbleService.ListenBrainz).map { it.play.id })
        outbox.later(ScrobbleService.ListenBrainz, listOf("new"), 30_000)
        assertTrue(outbox.due(ScrobbleService.ListenBrainz).isEmpty())
        clock += 30_000
        assertEquals(1, outbox.due(ScrobbleService.ListenBrainz).single().attempts)
        outbox.add(ScrobbleService.LastFm, play("b", scope = "owner-b"))
        assertEquals(0, outbox.clearScope("owner-c")); assertEquals(1, outbox.clearScope("owner-b"))
        outbox.remove(ScrobbleService.ListenBrainz, listOf("new")); assertTrue(outbox.rows(ScrobbleService.ListenBrainz).isEmpty())
        assertEquals(60_000L, scrobbleBackoffMs(1)); assertEquals(6 * 60 * 60 * 1000L, scrobbleBackoffMs(30))
        assertTrue(decodePending("not json").isEmpty())
    }

    @Test fun aTokenIsKeptSealedAndOnlyWhenItCanBe() {
        val raw = context.getSharedPreferences("scrobble_accounts_test", Context.MODE_PRIVATE)
        assertFalse(settings.enabled(ScrobbleService.ListenBrainz))
        assertTrue(settings.connect(ScrobbleService.ListenBrainz, "fixture-user", "token-123"))
        assertFalse(raw.all.values.any { it.toString().contains("token-123") })
        assertEquals(ScrobbleAccount("fixture-user", "token-123"), settings.active(ScrobbleService.ListenBrainz))
        settings.markBroken(ScrobbleService.ListenBrainz); assertNull(settings.active(ScrobbleService.ListenBrainz))
        settings.disconnect(ScrobbleService.ListenBrainz); assertNull(settings.account(ScrobbleService.ListenBrainz))
        seal.works = false
        assertFalse(settings.connect(ScrobbleService.LastFm, "u", "sk")); assertNull(settings.account(ScrobbleService.LastFm))
        assertFalse(settings.nowPlaying); assertFalse(settings.phoneFiles)
    }

    private class FakeHttp(val answer: (String, String?) -> ScrobbleReply) : ScrobbleHttp {
        val sent = mutableListOf<Pair<String, String?>>()
        override suspend fun call(url: String, headers: Map<String, String>, body: String?, contentType: String?): ScrobbleReply {
            sent += url to body; return answer(url, body)
        }
    }

    @Test fun listenBrainzSendsSinglesAndImportsAndFindsTheRowItRefused() = runBlocking {
        val http = FakeHttp { _, body ->
            val o = JSONObject(body!!)
            if (o.getJSONArray("payload").toString().contains("Refused")) ScrobbleReply(400, "{}") else ScrobbleReply(200, "{\"status\":\"ok\"}") }
        val client = ListenBrainzClient(http)
        assertEquals(setOf("p1"), client.submit("t", listOf(play())).sent)
        assertEquals("single", JSONObject(http.sent.last().second!!).getString("listen_type"))
        val listen = JSONObject(http.sent.last().second!!).getJSONArray("payload").getJSONObject(0)
        assertEquals(clock / 1000, listen.getLong("listened_at"))
        assertEquals("https://music.youtube.com/watch?v=Ab-_12345cd", listen.getJSONObject("track_metadata").getJSONObject("additional_info").getString("origin_url"))
        val mixed = client.submit("t", listOf(play("a"), play("b").copy(title = "Refused"), play("c")))
        assertEquals(setOf("a", "c"), mixed.sent); assertEquals(setOf("b"), mixed.refused)
        assertEquals("import", JSONObject(http.sent[1].second!!).getString("listen_type"))
        val busy = ListenBrainzClient(FakeHttp { _, _ -> ScrobbleReply(429, "", waitS = 12) }).submit("t", listOf(play()))
        assertEquals(setOf("p1"), busy.retry); assertEquals(12_000L, busy.waitMs)
        assertTrue(ListenBrainzClient(FakeHttp { _, _ -> ScrobbleReply(401, "") }).submit("t", listOf(play())).unauthorized)
        val lost = ListenBrainzClient(FakeHttp { _, _ -> throw IOException("timeout") }).submit("t", listOf(play()))
        assertEquals(setOf("p1"), lost.retry); assertTrue(lost.sent.isEmpty())
        assertEquals("fixture", ListenBrainzClient(FakeHttp { _, _ -> ScrobbleReply(200, "{\"valid\":true,\"user_name\":\"fixture\"}") }).validate("t"))
        assertNull(ListenBrainzClient(FakeHttp { _, _ -> ScrobbleReply(401, "{\"valid\":false}") }).validate("t"))
    }

    @Test fun lastFmSignsItsCallsAndKeepsWhatItAccepted() = runBlocking {
        // Worked out apart from the app: md5("api_keykey123methodauth.getSessiontokentok456" + "sec789").
        assertEquals("8eaaa07b55f7dd1dc3ca3578f8e3b031",
            lastFmSignature(mapOf("method" to "auth.getSession", "token" to "tok456", "api_key" to "key123", "format" to "json"), "sec789"))
        val http = FakeHttp { _, _ -> ScrobbleReply(200, """{"scrobbles":{"@attr":{"accepted":1,"ignored":1},"scrobble":[
            {"ignoredMessage":{"code":"0","#text":""}},{"ignoredMessage":{"code":"1","#text":"Artist ignored"}}]}}""") }
        val client = LastFmClient("key123", "sec789", http)
        val outcome = client.submit("sk", listOf(play("a"), play("b")))
        assertEquals(setOf("a"), outcome.sent); assertEquals(setOf("b"), outcome.refused)
        val body = http.sent.single().second!!
        assertTrue(body.contains("method=track.scrobble")); assertTrue(body.contains("artist%5B1%5D=Fixture+Band")); assertTrue(body.contains("api_sig="))
        assertTrue(LastFmClient("k", "s", FakeHttp { _, _ -> ScrobbleReply(403, "{\"error\":9,\"message\":\"Invalid session key\"}") })
            .submit("sk", listOf(play())).unauthorized)
        assertEquals(5 * 60_000L, LastFmClient("k", "s", FakeHttp { _, _ -> ScrobbleReply(429, "{\"error\":29}") }).submit("sk", listOf(play())).waitMs)
        assertFalse(LastFmClient("", "", http).available)
        assertEquals("Fixture" to "sk-1", LastFmClient("k", "s", FakeHttp { _, _ -> ScrobbleReply(200, "{\"session\":{\"name\":\"Fixture\",\"key\":\"sk-1\"}}") }).session("t"))
        assertNull(LastFmClient("k", "s", FakeHttp { _, _ -> ScrobbleReply(403, "{\"error\":14}") }).session("t"))
    }

    private class FakeClient : ScrobbleClient {
        val submitted = mutableListOf<List<String>>(); val playing = mutableListOf<String>(); var answer: (List<ScrobblePlay>) -> ScrobbleOutcome = { ScrobbleOutcome(sent = it.map { p -> p.id }.toSet()) }
        override suspend fun submit(token: String, rows: List<ScrobblePlay>) = answer(rows).also { submitted += rows.map { it.id } }
        override suspend fun nowPlaying(token: String, play: ScrobblePlay) { playing += play.id }
    }

    @Test fun thePlayIsQueuedOnceHeardAndOnlyWhileHistoryIsOn() = runBlocking {
        var history = true
        val client = FakeClient(); val events = mutableListOf<String>()
        // A scope that runs nothing: the sends are made by hand below.
        val idle = CoroutineScope(Job().apply { cancel() })
        val scrobbler = Scrobbler(settings, outbox, idle, { history }, { events += it }, { clock }, mapOf(ScrobbleService.ListenBrainz to client))
        fun listen(p: ScrobblePlay?, ticks: Int) { scrobbler.restart(); var pos = 0L; repeat(ticks) { pos += 5_000; clock += 5_000; scrobbler.tick(5_000, true, pos) { p } } }
        listen(play("before", 60_000), 7)
        assertTrue("Not connected: nothing kept", outbox.rows(ScrobbleService.ListenBrainz).isEmpty())
        settings.connect(ScrobbleService.ListenBrainz, "u", "t")
        listen(play("one", 60_000), 7)
        assertEquals(listOf("one"), outbox.rows(ScrobbleService.ListenBrainz).map { it.play.id })
        assertTrue("Now playing is off by default", client.playing.isEmpty())
        scrobbler.sendDue()
        assertEquals(listOf(listOf("one")), client.submitted); assertTrue(outbox.rows(ScrobbleService.ListenBrainz).isEmpty())
        assertTrue(events.single().startsWith("scrobble_send kind=listenbrainz count=1"))
        assertEquals(events.single(), DiagnosticEvent.parse(events.single()).text)
        history = false
        listen(play("off", 60_000), 7); assertTrue(outbox.rows(ScrobbleService.ListenBrainz).isEmpty())
        history = true
        listen(play("file", 60_000, device = true), 7); assertTrue("Phone files are off by default", outbox.rows(ScrobbleService.ListenBrainz).isEmpty())
        settings.phoneFiles = true; settings.nowPlaying = true
        listen(play("file2", 60_000, device = true), 7)
        assertEquals(listOf("file2"), outbox.rows(ScrobbleService.ListenBrainz).map { it.play.id })
        // The start time is when it began to play, not when it was queued.
        assertEquals(clock - 35_000, outbox.rows(ScrobbleService.ListenBrainz).single().play.startedAtMs)
        // A token the service refuses stops the sends and keeps the row.
        client.answer = { ScrobbleOutcome(retry = it.map { p -> p.id }.toSet(), unauthorized = true) }
        scrobbler.sendDue()
        assertNull(settings.active(ScrobbleService.ListenBrainz)); assertEquals(1, outbox.rows(ScrobbleService.ListenBrainz).size)
        // Switched off: kept nothing new.
        settings.disconnect(ScrobbleService.ListenBrainz); settings.connect(ScrobbleService.ListenBrainz, "u", "t"); settings.setEnabled(ScrobbleService.ListenBrainz, false)
        listen(play("paused", 60_000), 7); assertFalse(outbox.rows(ScrobbleService.ListenBrainz).any { it.play.id == "paused" })
        // Too short to count.
        settings.setEnabled(ScrobbleService.ListenBrainz, true)
        listen(play("short", 30_000), 6); assertFalse(outbox.rows(ScrobbleService.ListenBrainz).any { it.play.id == "short" })
    }
}
