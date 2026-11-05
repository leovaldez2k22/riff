package com.musicglass.personal

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class StartupDiagnosticsTest {
    private fun lines() = ShadowLog.getLogsForTag("RiffStartup").map { it.msg }

    @Test fun disabledDiagnosticsEmitNothingAndStillExecuteTheOperation() {
        ShadowLog.clear()
        val previous = StartupDiagnostics.enabled
        try {
            StartupDiagnostics.enabled = false
            StartupDiagnostics.request(StartupDiagnostics.Stage.MiniTap)
            assertEquals(42, StartupDiagnostics.measure(StartupDiagnostics.Stage.SolverRpc, StartupDiagnostics.Kind.Solver) { 42 })
            assertTrue(lines().isEmpty())
        } finally { StartupDiagnostics.enabled = previous }
    }

    @Test fun failedSpanEndsWithFailureAndRetainsTheOriginalException() {
        ShadowLog.clear()
        val error = IllegalStateException("private invented payload")
        try {
            StartupDiagnostics.measure(StartupDiagnostics.Stage.SolverRpc, StartupDiagnostics.Kind.Solver) { throw error }
            fail("expected original failure")
        } catch (actual: IllegalStateException) { assertSame(error, actual) }
        assertTrue(lines().last().contains("result=failed"))
        assertFalse(lines().joinToString().contains("payload"))
    }

    @Test fun stateRecordsSafeNumericFieldsWithoutMediaMetadataOrIdentifiers() {
        ShadowLog.clear()
        val item = MediaItem.Builder().setMediaId("private_track_identifier")
            .setUri("https://example.com/private?token=secret").build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getPlaybackState" -> Player.STATE_READY
                "getBufferedPosition" -> 1234L
                "getTotalBufferedDuration" -> 1000L
                "getCurrentPosition" -> 234L
                "getMediaItemCount" -> 2
                "getCurrentMediaItemIndex", "getPlaybackSuppressionReason" -> 0
                "getPlayWhenReady" -> false
                else -> error("Unexpected player access: ${method.name}")
            }
        } as Player
        StartupDiagnostics.request(StartupDiagnostics.Stage.MiniTap)
        SystemClock.sleep(1)
        StartupDiagnostics.state(StartupDiagnostics.Stage.EntryState, player)
        val line = lines().last()
        assertTrue(line, line.contains("buffered_ms=1234"))
        assertTrue(line, line.contains("queue_index=0"))
        assertTrue(line, line.contains("source=direct"))
        assertFalse(line, line.contains("<redacted>"))
        assertFalse(line, line.contains("private"))
        assertFalse(line, line.contains("secret"))
        assertTrue(line.length <= DiagnosticEvent.MAX_TEXT)
        assertTrue(line, Regex("pid=\\d+ epoch=\\d+").containsMatchIn(line))
    }
}
