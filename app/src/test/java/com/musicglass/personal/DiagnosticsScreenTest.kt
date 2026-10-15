package com.musicglass.personal

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Q15A: Settings, About, Diagnostics as the user meets it, from synthetic records. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val notices = mutableListOf<String>()
    private val hour = 1_800_000_000_000L - 1_800_000_000_000L % HOUR_MS

    @Before fun seed() {
        val store = ExitStore.of(context)
        store.clear()
        store.addExits(listOf(ExitRecord(hour, ProcessRole.Main, "crash", 100, 22),
            ExitRecord(hour, ProcessRole.Main, "user_requested", 400, 22),
            ExitRecord(hour, ProcessRole.Solver, "low_memory", 400, 22),
            ExitRecord(hour, ProcessRole.Main, "other", 400, 22)))
        store.addCrash(CrashRecord(hour, ProcessRole.Main, listOf("IllegalStateException", "IOException"),
            listOf("PlaybackSources.resolve:12"), 3, 22))
        PlaybackEventLog(context).append("stream_resolve_start lookup=1 https://example.com/x?sig=secret")
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun tap(tag: String) { compose.waitUntil(WAIT_MS) { count(tag) > 0 }; compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) }

    @Test fun exitsReadInPlainWordsAndOnlyFailuresAreMarkedAsSuch() {
        compose.setContent { DiagnosticsScreen(ShellModel(), 0.dp) { notices += it } }
        // The exits and the errors are two list items and may arrive a frame apart.
        compose.waitUntil(WAIT_MS) { shown(string(R.string.exit_crash)) && count("diagnostics_error") > 0 }
        for (text in listOf(R.string.exit_crash, R.string.exit_user_requested, R.string.exit_low_memory, R.string.exit_other, R.string.stream_helper))
            assertTrue(string(text), shown(string(text)))
        assertEquals(2, count("diagnostics_exit_failure"))
        assertEquals("a stop from settings and a swipe-away are not failures", 2, count("diagnostics_exit_ordinary"))
        assertTrue(shown("IllegalStateException"))
        assertTrue(shown(quantity(R.plurals.n_times, 3)))
    }

    @Test fun shareSendsTheCleanedReportThroughTheSystemSheetOnly() {
        compose.setContent { DiagnosticsScreen(ShellModel(), 0.dp) { notices += it } }
        compose.waitUntil(WAIT_MS) { count("diagnostics_error") > 0 }
        assertEquals("the report row is drawn once after the errors appear", 1, count("diagnostics_share"))
        tap("diagnostics_share")
        val shadow = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        compose.waitUntil(WAIT_MS) { shadow.peekNextStartedActivity() != null }
        val chooser = shadow.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertNull("no component: the user picks where it goes", send.component)
        val report = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(report.contains("process_exit at=$hour process=main reason=crash importance=100 code=22"))
        assertTrue(report.contains("uncaught at=$hour process=main causes=IllegalStateException>IOException count=3 code=22"))
        assertTrue(report.contains("  at PlaybackSources.resolve:12"))
        assertFalse(report.contains("example.com")); assertFalse(report.contains("secret"))
    }

    @Test fun clearRemovesTheRecordsAndSaysSo() {
        compose.setContent { DiagnosticsScreen(ShellModel(), 0.dp) { notices += it } }
        compose.waitUntil(WAIT_MS) { count("diagnostics_exit_failure") > 0 }
        tap("diagnostics_clear")
        compose.waitUntil(WAIT_MS) { string(R.string.exit_records_cleared) in notices }
        compose.waitUntil(WAIT_MS) { shown(string(R.string.no_exits_recorded)) }
        assertEquals(0, count("diagnostics_error"))
        assertTrue(ExitStore.of(context).exits().isEmpty())
        assertTrue(ExitStore.of(context).crashes().isEmpty())
    }
}
