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
}
