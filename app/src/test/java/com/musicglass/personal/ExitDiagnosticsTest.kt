package com.musicglass.personal

import android.app.ApplicationExitInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Q15A: DIAG01 with scripted exits and exceptions, and SEC03 over the new records and the shared report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExitDiagnosticsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val pkg = "com.musicglass.personal"
    private lateinit var directory: File
    private lateinit var store: ExitStore
    private val hour = 1_800_000_000_000L - 1_800_000_000_000L % HOUR_MS

    @Before fun clean() {
        directory = File(context.cacheDir, "exit-test-${System.nanoTime()}")
        store = ExitStore(directory)
        context.getSharedPreferences("exit_records", Context.MODE_PRIVATE).edit().clear().commit()
        ExitStore.of(context).clear()
    }

    /** A secret-looking message: an address, a cookie, a song title and an account scope. */
    private val planted = "GET https://rr3---sn.googlevideo.com/videoplayback?sig=ABCDEF&expire=1 Cookie: SAPISID=s3cr3t; " +
        "title=Bohemian Rhapsody scope=acct-7f3a9c2e1b account=someone@example.com"

    @Test fun everyReasonClassIsRecordedWithItsWordAndPlainText() {
        val reasons = mapOf(
            ApplicationExitInfo.REASON_CRASH to ("crash" to true), ApplicationExitInfo.REASON_CRASH_NATIVE to ("crash_native" to true),
            ApplicationExitInfo.REASON_ANR to ("anr" to true), ApplicationExitInfo.REASON_LOW_MEMORY to ("low_memory" to true),
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE to ("excessive_resource_usage" to true),
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE to ("init_failure" to true), ApplicationExitInfo.REASON_DEPENDENCY_DIED to ("dependency_died" to true),
            ApplicationExitInfo.REASON_USER_REQUESTED to ("user_requested" to false), ApplicationExitInfo.REASON_USER_STOPPED to ("user_stopped" to false),
            ApplicationExitInfo.REASON_EXIT_SELF to ("exit_self" to false), ApplicationExitInfo.REASON_OTHER to ("other" to false),
            ApplicationExitInfo.REASON_SIGNALED to ("signaled" to false), ApplicationExitInfo.REASON_PACKAGE_UPDATED to ("package_updated" to false),
            ApplicationExitInfo.REASON_UNKNOWN to ("unknown" to false), 99 to ("reason_99" to false))
        val exits = reasons.keys.mapIndexed { i, reason -> PlatformExit(hour + i * 1000L + 7, reason, 100, pkg) }
        val records = reduceExits(exits, 0L, pkg, 22)
        assertEquals(reasons.size, records.size)
        records.zip(reasons.values).forEach { (record, expected) ->
            assertEquals(expected.first, record.reason)
            assertEquals(record.reason, expected.second, record.failure)
            assertEquals("the hour only", hour, record.hour)
            assertEquals(record.line, DiagnosticEvent.parse(record.line).text)
            assertEquals(record, parseExitRecord(record.line))
            assertTrue(exitReasonText(record.reason).isNotBlank())
        }
        // A stop the user asked for, a swipe from recent apps and an update read as ordinary, not as failures.
        assertNotEquals(exitReasonText("crash"), exitReasonText("user_requested"))
        assertEquals(exitReasonText("other"), exitReasonText("reason_99"))
    }

    @Test fun onlyNewExitsOfEachProcessAreRecordedWithTheVersionThatRan() {
        val exits = listOf(PlatformExit(hour + 10, ApplicationExitInfo.REASON_CRASH, 100, pkg),
            PlatformExit(hour + 20, ApplicationExitInfo.REASON_LOW_MEMORY, 400, "$pkg:solver"),
            PlatformExit(hour + 30, ApplicationExitInfo.REASON_ANR, 100, "com.other.app"),
            PlatformExit(hour + 5, ApplicationExitInfo.REASON_CRASH, 100, pkg))
        val records = reduceExits(exits, hour + 5, pkg, 21)
        assertEquals(listOf(ProcessRole.Main, ProcessRole.Solver, ProcessRole.Other), records.map { it.role })
        assertEquals(listOf(100, 400, 100), records.map { it.importance })
        assertTrue(records.all { it.versionCode == 21 })

        var answer = exits
        recordProcessExits(context) { answer }
        assertEquals(4, ExitStore.of(context).exits().size)
        recordProcessExits(context) { answer }
        assertEquals("a second start adds nothing", 4, ExitStore.of(context).exits().size)
        answer = exits + PlatformExit(hour + 40, ApplicationExitInfo.REASON_CRASH, 100, pkg)
        recordProcessExits(context) { answer }
        assertEquals(5, ExitStore.of(context).exits().size)
        // A platform that refuses leaves the store as it was.
        recordProcessExits(context) { throw SecurityException("refused") }
        assertEquals(5, ExitStore.of(context).exits().size)
    }

    @Test fun anExceptionKeepsClassNamesAndAppFramesNeverItsMessage() {
        val root = IOException(planted)
        val error = IllegalStateException(planted, RuntimeException("Cookie: SID=abc", root))
        root.stackTrace = arrayOf(StackTraceElement("okhttp3.internal.Http", "send", "Http.kt", 10),
            StackTraceElement("$pkg.PlaybackSources\$resolve\$2", "invokeSuspend", "PlaybackSources.kt", 211),
            StackTraceElement("$pkg.MusicPlaybackService", "play", "MusicPlaybackService.kt", 90),
            StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 102))
        val crash = reduceCrash(error, ProcessRole.Main, hour + 1234, 22, pkg)
        assertEquals(listOf("IllegalStateException", "RuntimeException", "IOException"), crash.causes)
        assertEquals(listOf("PlaybackSources\$resolve\$2.invokeSuspend:211", "MusicPlaybackService.play:90"), crash.frames)
        assertEquals(hour, crash.hour)
        store.addCrash(crash)
        val text = directory.listFiles()!!.joinToString("\n") { it.readText() }
        assertSecretFree(text)
        assertEquals(listOf(crash), store.crashes())
        // Frames from outside the app, and an exception with no app frame at all, keep no frame.
        assertEquals(emptyList<String>(), reduceCrash(IOException(planted), ProcessRole.Solver, 0, 22, pkg).frames.filter { !it.startsWith("ExitDiagnosticsTest") })
    }

    @Test fun theRecorderHandsTheCrashOnUnchanged() {
        val seen = mutableListOf<Pair<Thread, Throwable>>()
        val previous = Thread.UncaughtExceptionHandler { t, e -> seen += t to e }
        val recorder = CrashRecorder(store, ProcessRole.Main, pkg, 22, { hour }, previous)
        val error = IllegalStateException(planted)
        val thread = Thread("worker")
        recorder.uncaughtException(thread, error)
        assertEquals(listOf(thread to error), seen)
        assertSame("the exception is not wrapped or changed", error, seen.single().second)
        assertEquals(planted, error.message)
        assertEquals(1, store.crashes().size)
        // A store that cannot write still hands the crash on.
        val broken = File(context.cacheDir, "not-a-directory-${System.nanoTime()}").apply { writeText("x") }
        CrashRecorder(ExitStore(File(broken, "inside")), ProcessRole.Main, pkg, 22, { hour }, previous).uncaughtException(thread, error)
        assertEquals(2, seen.size)
        // With no handler before it (a plain JVM) the error is recorded and the process is left running.
        CrashRecorder(store, ProcessRole.Main, pkg, 22, { hour }, null).uncaughtException(thread, IllegalArgumentException("x"))
        assertTrue(store.crashes().any { it.causes == listOf("IllegalArgumentException") })
        // Recording is quick: a hundred crashes, written one by one, take well under the time a process has to die.
        val started = System.nanoTime()
        repeat(100) { recorder.uncaughtException(thread, IllegalArgumentException("x$it")) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test fun aCrashLoopCannotGrowTheStoreOrPushOutExits() {
        store.addExits(listOf(ExitRecord(hour, ProcessRole.Main, "low_memory", 400, 22)))
        val recorder = CrashRecorder(store, ProcessRole.Main, pkg, 22, { hour }, Thread.UncaughtExceptionHandler { _, _ -> })
        repeat(500) { recorder.uncaughtException(Thread.currentThread(), IllegalStateException("same")) }
        val same = store.crashes().single()
        assertEquals(500, same.count)
        // Different crashes: only the last few kinds are kept.
        repeat(50) { i -> store.addCrash(CrashRecord(hour, ProcessRole.Main, listOf("Error$i"), emptyList(), 1, 22)) }
        assertEquals(MAX_CRASH_RECORDS, store.crashes().size)
        assertEquals(listOf("low_memory"), store.exits().map { it.reason })
        repeat(100) { store.addExits(listOf(ExitRecord(hour, ProcessRole.Solver, "crash", 100, 22))) }
        assertEquals(MAX_EXIT_RECORDS, store.exits().size)
        assertTrue(directory.listFiles()!!.all { it.length() <= MAX_RECORD_FILE_BYTES })
        // The event ring is a different store: filling it touches neither.
        val ring = PlaybackEventLog(context)
        repeat(EVENT_LOG_LINES + 50) { ring.append("crash_notice_raised") }
        assertEquals(MAX_EXIT_RECORDS, store.exits().size)
    }

    @Test fun storedLinesOfAnyOtherShapeAreDropped() {
        directory.mkdirs()
        File(directory, "exits.log").writeText(listOf(
            "process_exit at=1 process=main reason=crash importance=100 code=22",
            "process_exit at=1 process=main reason=crash importance=100 code=22 desc=\"$planted\"",
            "process_exit at=1 process=main reason=https://evil.example importance=100 code=22",
            planted).joinToString("\n"))
        File(directory, "crash-main.log").writeText(listOf(
            "uncaught at=1 process=main causes=IllegalStateException count=1 code=22 frames=Player.play:1",
            "uncaught at=1 process=main causes=IllegalStateException count=1 code=22 frames=https://evil.example/x:1",
            "uncaught at=1 process=main causes=Bohemian Rhapsody count=1 code=22 frames=",
            "uncaught at=1 process=main causes=A>B>C>D>E>F count=1 code=22 frames=").joinToString("\n"))
        assertEquals(1, store.exits().size)
        assertEquals(1, store.crashes().size)
        // An oversized file reads as empty.
        File(directory, "exits.log").writeText("x".repeat(MAX_RECORD_FILE_BYTES + 1))
        assertEquals(0, store.exits().size)
    }

    @Test fun theSharedReportHoldsNoCredentialAddressTitleOrScope() {
        val ring = PlaybackEventLog(context)
        ring.append("stream_resolve_start lookup=3 $planted")
        ring.append(planted)
        val exits = listOf(ExitRecord(hour, ProcessRole.Main, "crash", 100, 22), ExitRecord(hour, ProcessRole.Solver, "low_memory", 400, 22))
        val error = IllegalStateException(planted, IOException(planted))
        val crashes = listOf(reduceCrash(error, ProcessRole.Main, hour, 22, pkg))
        val report = diagnosticsReport(exits, crashes, ring.recent(), "0.10.0\nforged", 36, "maker model")
        assertSecretFree(report)
        assertTrue(report.contains("process_exit at=$hour process=main reason=crash importance=100 code=22"))
        assertTrue(report.contains("uncaught at=$hour process=main causes=IllegalStateException>IOException count=1 code=22"))
        assertTrue(report.contains("2 process exits"))
        assertTrue(report.contains("1 errors"))
        assertEquals("the heading is one line", "Riff 0.10.0 forged", report.lines().first().substringBefore(","))
    }

    @Test fun theStoreIsOutsideBackupAndTheRing() {
        val path = ExitStore.of(context).let { it.addExits(listOf(ExitRecord(hour, ProcessRole.Main, "crash", 100, 22))); context.noBackupFilesDir.resolve("diagnostics/exits.log") }
        assertTrue(path.isFile)
        assertFalse(context.getSharedPreferences("playback_events", Context.MODE_PRIVATE).getString("recent", "").orEmpty().contains("code=22"))
    }

    private fun assertSecretFree(text: String) {
        // A key name may stay (its value is redacted, as in Q15's ring); no value may.
        assertFalse(text, Regex("SAPISID=(?!<redacted>)").containsMatchIn(text))
        for (secret in listOf("http", "googlevideo", "sig=", "Cookie", "s3cr3t", "SID=abc", "Bohemian", "Rhapsody",
                "acct-7f3a9c2e1b", "someone", "@example", "evil"))
            assertFalse("$secret in:\n$text", text.contains(secret, ignoreCase = true))
    }
}
