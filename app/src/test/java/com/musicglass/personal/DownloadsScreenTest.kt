package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The Downloads list and the thread its contents are written on. createComposeRule runs effects on an unconfined
 * dispatcher, so a LaunchedEffect that returns from withContext(Dispatchers.IO) carries on on the IO thread. The
 * list once wrote what it had loaded from there while the main thread was measuring it, and the lazy list saw two
 * different numbers of items in one measure: "Index 3, size 3" (Q15, q17a-release-01, q17a-release-04).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class DownloadsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun whatTheListLoadedIsWrittenOnTheMainThread() {
        shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, SolverService::class.java))
        val main = Looper.getMainLooper().thread
        val elsewhere = Collections.synchronizedList(mutableListOf<String>())
        // Only this screen's own writes: coroutines left over from other classes in the same JVM write too.
        val writes = Snapshot.registerGlobalWriteObserver {
            if (Thread.currentThread() != main && Throwable().stackTrace.any { it.className.startsWith("com.musicglass.personal.DownloadsScreenKt") })
                elsewhere += "write on ${Thread.currentThread().name}"
        }
        try {
            compose.setContent { DownloadsScreen(ShellModel(), 0.dp, RecordingGateway().gateway) {} }
            val empty = string(R.string.download_a_song_from_its_menu_or)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(empty).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.waitForIdle()
        } finally { writes.dispose() }
        assertEquals(emptyList<String>(), elsewhere.distinct())
    }
}
