package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/**
 * SEC03: nothing that may not be kept reaches the stored ring, logcat or the report, whatever is handed to
 * the log. The adversarial values below are invented; none is a real credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticEventTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun reduce(text: String) = DiagnosticEvent.parse(text).text

    /** Invented secrets and content, each with the part that must never survive. */
    private val planted = listOf(
        "https://rr4---sn-abc.googlevideo.com/videoplayback?expire=1760000000&sig=AOq0QJ8wRQIhAKf&pot=MnQxTokenValue" to "googlevideo",
        "http://127.0.0.1:8080/secret/path?key=AIzaSyFakeKeyFakeKeyFakeKeyFakeKey00" to "AIzaSy",
        "Cookie: SAPISID=abcdEFGH1234/xyz; __Secure-3PSID=g.a000zzzz" to "abcdEFGH1234",
        "Authorization: SAPISIDHASH 1760000000_0a1b2c3d4e5f60718293a4b5c6d7e8f901234567" to "0a1b2c3d4e5f",
        "authorization=Bearer ya29.a0AfakeFAKEfake-token_value" to "ya29",
        "sig=AOq0QJ8wRQIhAKfakeSignature n=Zx9_fakeNParam lsig=AG3C_xAwRgIhfake" to "AOq0QJ8w",
        "title=Never Gonna Give You Up artist=Rick Astley" to "Astley",
        "video=dQw4w9WgXcQ playlist=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf" to "dQw4w9WgXcQ",
        "scope=acct_9f8e7d6c5b4a39281706f5e4d3c2b1a0 owner_id=UCuAXFkgsw1L7xaCfnd5JJOw" to "9f8e7d6c5b4a",
        "message=java.io.IOException: unexpected end of stream on https://music.youtube.com/youtubei/v1/player" to "youtubei",
        "path=/data/user/0/com.musicglass.personal/files/offline/acct/track.m4a" to "offline/acct",
        "email=someone@example.com" to "example.com",
    )

    @Test fun aWellFormedEventIsKeptAsItWasWritten() {
        for (line in listOf(
            "service_create", "play_when_ready=true reason=1", "playback_state=3",
            "player_error=ERROR_CODE_IO_BAD_HTTP_STATUS causes=ExoPlaybackException>InvalidResponseCodeException",
            "http_rejected code=403 host=googlevideo kind=segment",
            "recovery class=network attempt=2 action=reprepare delay_ms=500 hold_ms=0 active_ms=1200",
            "stream_resolved profile=WEB_REMIX transport=SABR po_token=true",
            "token_minted stage=visitor+video elapsedMs=412 count=2", "normalize gain_db=-3.5",
            "crossfade_sync elapsed=120 remaining=4000 delta=-12 speed=1.02 playing=true",
            "solver_bridge_unavailable NoSuchFieldException",
            "process_exit at=1760000000000 process=solver reason=crash_native status=11 importance=100 pss_kb=0 rss_kb=0",
            "account_transition reason=sign_out result=committed owner_changed=true from=account to=guest",
        )) assertEquals(line, reduce(line))
    }

    @Test fun plantedSecretsAndContentNeverSurviveInAnyPosition() {
        for ((secret, marker) in planted) {
            for (line in listOf(secret, "oops $secret", "http_rejected code=403 $secret kind=segment",
                "stream_prepare_failed type=$secret", "download_failed phase=fetch kind=$secret", "reason=$secret")) {
                val kept = reduce(line)
                assertFalse("$line -> $kept", kept.contains(marker))
                assertFalse(kept, kept.contains("://")); assertFalse(kept, kept.contains("?"))
            }
        }
    }

    @Test fun aValueMustHaveTheShapeItsKeyAllows() {
        assertEquals("http_rejected code=<redacted> host=other", reduce("http_rejected code=403;DROP host=other"))
        assertEquals("manifest_ready hls=<redacted>", reduce("manifest_ready hls=yes"))
        // A key nobody listed keeps its name and loses its value, even when the value looks harmless.
        assertEquals("x token=<redacted>", reduce("x token=1"))
        // A word that is mostly digits is an identifier, not a phase or a reason.
        assertEquals("x reason=<redacted>", reduce("x reason=a3f9c2d1e8b7"))
        assertEquals("x kind=<redacted>", reduce("x kind=AbC123dEf45"))
        assertEquals("x type=IOException", reduce("x type=IOException"))
        // The error chain takes five class names and no more.
        assertEquals("x causes=<redacted>", reduce("x causes=Aa>Bb>Cc>Dd>Ee>Ff"))
    }

    @Test fun anEventWithoutAProperNameKeepsNothing() {
        for (line in listOf("", "   ", "\n", "Never Gonna Give You Up", "https://example.com/a", "9lives reason=x", "name-with-dash", "unknown_key_first=1"))
            assertEquals(line, DiagnosticEvent.REJECTED, reduce(line))
    }

    @Test fun lineBreaksControlCharactersAndMalformedUnicodeCannotForgeOrBreakALine() {
        val forged = reduce("download_failed phase=fetch\n1760000000000 service_create\r\nplayer_error=ok")
        assertFalse(forged.contains('\n')); assertFalse(forged.contains('\r'))
        // The forged second line is not a line: its time and its name are bare words here, and bare words go.
        assertEquals("download_failed phase=fetch <redacted> player_error=ok", forged)
        val odd = "x reason=a\u0000b kind=\uD83D type=\uDC00\uD83D‮evil​ phase=café \t stage=ok\u0007"
        val kept = reduce(odd)
        assertTrue(kept, kept.all { it in ' '..'~' })
        assertFalse(kept.contains("evil"))
        // A lone surrogate, a bidirectional override and an accented letter all become spaces: the value is cut there.
        assertTrue(kept, kept.startsWith("x reason=a "))
    }

    @Test fun oversizedInputIsBoundedInEveryDimension() {
        val long = "x reason=" + "a".repeat(100_000)
        assertEquals("x reason=<redacted>", reduce(long))
        assertEquals("x kind=<redacted>", reduce("x kind=" + "A" + "b".repeat(DiagnosticEvent.MAX_VALUE)))
        val many = "x " + (1..5_000).joinToString(" ") { "count=$it" }
        val kept = reduce(many)
        assertTrue(kept.length <= DiagnosticEvent.MAX_TEXT)
        assertTrue(kept.split(' ').size <= DiagnosticEvent.MAX_FIELDS + 2)
        assertTrue(kept.endsWith(DiagnosticEvent.REDACTED))
        assertEquals(DiagnosticEvent.REJECTED, reduce("x".repeat(2_000_000)))
    }

    /** Whatever goes in, what comes out is a name and fields of the allowed shapes, or the rejection word. */
    @Test fun randomInputAlwaysReducesToTheAllowedGrammar() {
        val random = Random(20261012)
        val alphabet = "abcXYZ019 _=-+>./:?&%@;\"'\\\n\r\t\u0000é😀‮<>"
        val field = Regex("[A-Za-z_][A-Za-z0-9_-]{0,31}=(<redacted>|[A-Za-z0-9_+.>-]{1,${DiagnosticEvent.MAX_VALUE * 5 + 4}})")
        val bare = Regex("[A-Z][A-Za-z0-9]{0,40}(Exception|Error)")
        repeat(20_000) {
            val text = buildString { repeat(random.nextInt(0, 160)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val seeded = if (it % 3 == 0) "crossfade_skipped $text" else if (it % 3 == 1) "x reason=$text kind=$text" else text
            val kept = reduce(seeded)
            assertTrue(kept.length <= DiagnosticEvent.MAX_TEXT)
            if (kept == DiagnosticEvent.REJECTED) return@repeat
            val tokens = kept.split(' ')
            assertTrue(kept, Regex("[a-z][a-z0-9_]{0,47}").matches(tokens[0]) || field.matches(tokens[0]))
            for (token in tokens.drop(1)) {
                assertTrue("$seeded -> $kept", token == DiagnosticEvent.REDACTED || bare.matches(token) || field.matches(token))
                val key = token.substringBefore('=', "")
                if (key.isNotEmpty() && !token.endsWith("=<redacted>")) assertTrue(token, key in DiagnosticEvent.keys)
            }
        }
    }

    @Test fun hostsAndSchemesAreReducedToAFewWords() {
        assertEquals("googlevideo", hostClass("rr4---sn-abc.googlevideo.com"))
        assertEquals("youtube", hostClass("music.youtube.com")); assertEquals("youtube", hostClass("YouTube.com."))
        assertEquals("google_images", hostClass("i.ytimg.com")); assertEquals("google", hostClass("accounts.google.com"))
        assertEquals("other", hostClass("accounts.google.com.attacker.example")); assertEquals("other", hostClass("notgooglevideo.com"))
        assertEquals("none", hostClass(null)); assertEquals("none", hostClass(""))
        assertEquals("intent", schemeClass("INTENT")); assertEquals("other", schemeClass("myapp-9f8e7d")); assertEquals("none", schemeClass(null))
        for (host in listOf("a.b.c", null, "x".repeat(500))) assertEquals(reduce("e host=${hostClass(host)}"), "e host=${hostClass(host)}")
    }

    @Test fun theStoredRingHoldsNoPlantedValueAndTheReportNeitherAndLogcatNeither() {
        context.getSharedPreferences("playback_events", Context.MODE_PRIVATE).edit().clear().commit()
        val log = PlaybackEventLog(context)
        org.robolectric.shadows.ShadowLog.clear()
        for ((secret, _) in planted) { log.append("stream_prepare_failed type=$secret"); log.append(secret); log.append("x\n$secret") }
        log.append("service_create")
        val stored = context.getSharedPreferences("playback_events", Context.MODE_PRIVATE).all.values.joinToString("\n")
        val report = playbackDiagnosticsReport(log.recent(), "0.10.0\nforged line", 36, "maker‮model https://example.com/x")
        val logcat = org.robolectric.shadows.ShadowLog.getLogsForTag("RiffPlayback").joinToString("\n") { it.msg }
        for (text in listOf(stored, report.lines().drop(2).joinToString("\n"), logcat)) {
            for ((_, marker) in planted) assertFalse(marker, text.contains(marker))
            assertFalse(text.contains("://"))
        }
        assertTrue(stored.contains("service_create")); assertTrue(logcat.contains("service_create"))
        // The heading stays one line of plain characters.
        assertEquals(planted.size * 3 + 1 + 3, report.lines().size)
        assertTrue(report.lines()[0], report.lines()[0].all { it in ' '..'~' })
    }

    @Test fun linesStoredByAnOlderVersionAreReducedOnTheFirstWriteAndInTheReport() {
        val prefs = context.getSharedPreferences("playback_events", Context.MODE_PRIVATE)
        val old = listOf("1 service_create", "2 process_exit at=5 process=main reason=crash status=0 importance=100 pss_kb=1 rss_kb=2 desc=\"killed by https://example.com/a?sig=zz\"",
            "3 stream_resolve_start entry=0a1b2c3d", "4 cookies_rotated names=SIDCC,__Secure-1PSIDTS", "not a line at all sig=zz", "5 http_rejected code=403 host=rr1---sn-xyz.googlevideo.com kind=segment")
        prefs.edit().clear().putString("recent", old.joinToString("\n")).commit()
        // Before anything is written, the report already reduces what is there.
        val before = playbackDiagnosticsReport(PlaybackEventLog(context).recent(), "v", 36, "d")
        for (gone in listOf("example.com", "0a1b2c3d", "SIDCC", "rr1---sn", "zz")) assertFalse(gone, before.contains(gone))
        PlaybackEventLog(context).append("service_create")
        val stored = prefs.getString("recent", "").orEmpty()
        for (gone in listOf("example.com", "0a1b2c3d", "SIDCC", "rr1---sn", "zz")) assertFalse(gone, stored.contains(gone))
        assertTrue(stored.lines().first() == "1 service_create")
        assertTrue(stored.contains("2 process_exit at=5 process=main reason=crash status=0 importance=100 pss_kb=1 rss_kb=2 desc=<redacted>"))
        assertTrue(stored.contains("0 rejected_event"))
        assertEquals(old.size + 1, stored.lines().size)
    }

    @Test fun theRingKeepsItsNewestTwoHundredLines() {
        val log = PlaybackEventLog(context)
        context.getSharedPreferences("playback_events", Context.MODE_PRIVATE).edit().clear().commit()
        repeat(EVENT_LOG_LINES + 50) { log.append("recovery_skip count=$it") }
        val lines = log.recent()
        assertEquals(EVENT_LOG_LINES, lines.size)
        assertTrue(lines.first().endsWith("count=50")); assertTrue(lines.last().endsWith("count=${EVENT_LOG_LINES + 49}"))
    }

    /**
     * Every event the code writes uses a proper name and listed keys. A text in the code that this test does
     * not understand is a failure too, so a new way of writing events has to be looked at.
     */
    @Test fun everyEventWrittenInTheCodeUsesAListedKey() {
        val sources = File("src/main/java/com/musicglass/personal").listFiles { f -> f.extension == "kt" }!!
        assertTrue(sources.size > 100)
        val call = Regex("(?:\\.append|\\bevent|\\blog|\\bonEvent|diagnosticLogcat)\\s*\\(")
        val literal = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        val pair = Regex("(?:^|\\s)([A-Za-z_][A-Za-z0-9_-]*)=")
        val problems = mutableListOf<String>(); var events = 0
        for (file in sources) {
            if (file.name in setOf("DiagnosticEvent.kt", "PlaybackEventLog.kt")) continue
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!call.containsMatchIn(line) || line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                val after = line.substring(call.find(line)!!.range.last + 1)
                // A text that is appended to a text builder is not an event.
                if (Regex("(text|out|line|where|sb|builder)\\.append\\(").containsMatchIn(line)) return@forEachIndexed
                // A call that continues on the next lines: the texts of all of them belong to it.
                val block = (listOf(after) + lines.drop(index + 1).takeWhile { it.trimStart().startsWith("\"") || it.trimStart().startsWith("is ") || it.trimStart().startsWith("\"") }.take(6)).joinToString(" ")
                // The first text of a logcat call is its tag.
                val logcat = "diagnosticLogcat" in call.find(line)!!.value
                val texts = literal.findAll(block).map { it.groupValues[1] }.toList().drop(if (logcat) 1 else 0)
                if (texts.isEmpty()) return@forEachIndexed
                val head = texts.first().substringBefore(' ').substringBefore('=').substringBefore('$')
                if (after.trimStart().startsWith("\"") && !logcat || logcat && texts.isNotEmpty()) {
                    events++
                    if (!Regex("[a-z][a-z0-9_]{0,47}").matches(head)) problems += "${file.name}:${index + 1} name '$head'"
                }
                for (text in texts) for (match in pair.findAll(text)) {
                    val key = match.groupValues[1]
                    if (key !in DiagnosticEvent.keys && !(key == "desc" && file.name == "ServiceStopDiagnostics.kt"))
                        problems += "${file.name}:${index + 1} key '$key'"
                }
            }
        }
        assertTrue("events found: $events", events > 80)
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
