package com.musicglass.personal

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class ServiceStopDiagnosticsTest {
    private fun exit(at: Long, reason: Int = ApplicationExitInfo.REASON_LOW_MEMORY, process: String = "com.musicglass.personal",
                     description: String? = null) =
        ProcessExit(at, reason, status = 0, importance = 125, pssKb = 150_000, rssKb = 210_000, processName = process, description = description)

    @Test fun onlyExitsAfterTheLastSeenOneAreReportedOldestFirst() {
        val exits = listOf(exit(300), exit(100), exit(200))
        assertEquals(listOf(200L, 300L), newProcessExits(exits, lastSeen = 100).map { it.timestamp })
        assertTrue(newProcessExits(exits, lastSeen = 300).isEmpty())
    }

    @Test fun lineNamesTheReasonAndProcess() {
        val line = describeExit(exit(42, ApplicationExitInfo.REASON_CRASH_NATIVE, "com.musicglass.personal:solver"))
        assertTrue(line, line.startsWith("process_exit at=42 process=solver reason=crash_native "))
        assertTrue(describeExit(exit(1)).contains("process=main reason=low_memory"))
        assertTrue(describeExit(exit(1, reason = 99)).contains("reason=reason_99"))
    }

    @Test fun descriptionIsSingleLineShortAndUrlFree() {
        val line = describeExit(exit(1, description = "killed\nby  system https://rr1---sn.googlevideo.com/videoplayback?sig=abc&pot=x " + "y".repeat(200)))
        assertFalse(line.contains('\n'))
        assertFalse(line.contains("googlevideo")); assertFalse(line.contains("pot=")); assertFalse(line.contains("sig="))
        assertTrue(line.contains("desc=\"killed by system <url>"))
        assertTrue(line.substringAfter("desc=\"").length <= 81)
        assertFalse(describeExit(exit(1, description = "  ")).contains("desc="))
    }

    @Test fun abnormalExitsAreCrashesAndAnrsOnly() {
        assertTrue(isAbnormalExit(ApplicationExitInfo.REASON_CRASH))
        assertTrue(isAbnormalExit(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(isAbnormalExit(ApplicationExitInfo.REASON_ANR))
        for (reason in listOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_PACKAGE_UPDATED))
            assertFalse(exitReasonName(reason), isAbnormalExit(reason))
    }

    @Test fun unexpectedStopMeansStillWantingSoundWithoutSwipeAway() {
        assertTrue(isUnexpectedStop(playWhenReady = true, taskRemoved = false))
        assertFalse(isUnexpectedStop(playWhenReady = true, taskRemoved = true))
        assertFalse(isUnexpectedStop(playWhenReady = false, taskRemoved = false))
    }

    @Test fun crashNoticeIsForRecentMainProcessCrashesOnly() {
        val now = 10 * CRASH_NOTICE_WINDOW_MS
        val pkg = "com.musicglass.personal"
        val recentCrash = exit(now - 60_000, ApplicationExitInfo.REASON_CRASH_NATIVE)
        assertEquals(recentCrash, crashNoticeExit(listOf(exit(now - 1000), recentCrash), pkg, now))
        // The solver crashing never stops the music.
        assertNull(crashNoticeExit(listOf(exit(now - 1000, ApplicationExitInfo.REASON_CRASH_NATIVE, "$pkg:solver")), pkg, now))
        // A crash from days ago, read on the first start after an update, is old news.
        assertNull(crashNoticeExit(listOf(exit(now - CRASH_NOTICE_WINDOW_MS - 1, ApplicationExitInfo.REASON_CRASH)), pkg, now))
        assertNull(crashNoticeExit(listOf(exit(now - 1000, ApplicationExitInfo.REASON_LOW_MEMORY)), pkg, now))
    }

    @Test fun diagnosticsReportHasNoUrlsOrTokens() {
        val lines = listOf("1 service_create", "2 http_rejected code=403 host=rr1---sn.googlevideo.com kind=segment",
            "3 oops https://rr1---sn.googlevideo.com/videoplayback?sig=abc&pot=xyz", "4 leaked pot=MnQx sig=AB12 SAPISID=zz __Secure-3PSIDTS=qq")
        val report = playbackDiagnosticsReport(lines, "0.9.0-rc18", 36, "vivo I2217")
        assertTrue(report, report.startsWith("Riff 0.9.0-rc18, Android SDK 36, vivo I2217\nBuild revision="))
        for (secret in listOf("videoplayback", "abc", "xyz", "MnQx", "AB12", "=zz", "=qq")) assertFalse(secret, report.contains(secret))
        assertTrue(report.contains("pot=<redacted>")); assertTrue(report.contains("1 service_create"))
        assertTrue(report.contains(BuildConfig.SOURCE_REVISION)); assertTrue(report.contains("\n4 playback events"))
        assertEquals(EVENT_LOG_LINES + 3, playbackDiagnosticsReport(List(500) { "$it e" }, "v", 36, "d").lines().size)
    }
}
