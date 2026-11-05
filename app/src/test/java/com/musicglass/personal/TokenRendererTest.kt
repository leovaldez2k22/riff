package com.musicglass.personal

import android.content.Context
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Robolectric's WebView never calls a script back, which is what a dead or stuck renderer does. */
@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class TokenRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val killed = object : RenderProcessGoneDetail() {
        override fun didCrash() = false
        override fun rendererPriorityAtExit() = 0
    }

    private fun turnUntil(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            // A paused looper's clock only moves when told to, and the runtime's delays and deadlines run on it.
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(30))
            if (System.nanoTime() > end) fail("Timed out: $what")
            Thread.sleep(5)
        }
    }
    /** Starts a script and lets the page finish, so that the script is left waiting on the renderer. */
    private fun evaluating(runtime: AndroidTokenRuntime): Pair<Deferred<String>, WebView> {
        val call = scope.async { runtime.evaluate("'ready'") }
        turnUntil("the WebView is made") { runtime.viewForTest != null }
        val view = runtime.viewForTest!!
        shadowOf(view).webViewClient.onPageFinished(view, "https://www.youtube.com/robots.txt")
        turnUntil("the page script is sent") { shadowOf(view).lastEvaluatedJavascript != null }
        return call to view
    }
    private fun failure(call: Deferred<String>): Throwable {
        turnUntil("the script fails") { call.isCompleted }
        return call.getCompletionExceptionOrNull()!!
    }

    @Test fun aGoneRendererIsHandledAndFailsTheWaitingScriptAtOnce() {
        val runtime = AndroidTokenRuntime(context, scriptDeadlineMs = 60_000)
        try {
            val (call, view) = evaluating(runtime)
            assertTrue(runtime.alive)
            // false here is what lets Android kill the whole process, and playback with it.
            assertTrue(shadowOf(view).webViewClient.onRenderProcessGone(view, killed))
            assertTrue(failure(call) is IllegalStateException)
            assertFalse(runtime.alive)
            assertNull(runtime.viewForTest)
        } finally { runtime.close(); scope.cancel() }
    }

    @Test fun aRendererThatDoesNotAnswerFailsAfterTheDeadline() {
        val runtime = AndroidTokenRuntime(context, scriptDeadlineMs = 50)
        try {
            val (call, _) = evaluating(runtime)
            val error = failure(call)
            // A plain failure, not a cancellation, so the lookup records it and the provider starts over.
            assertTrue(error is IllegalStateException)
            assertTrue("a stuck renderer may recover", runtime.alive)
        } finally { runtime.close(); scope.cancel() }
    }

    @Test fun aGoneRuntimeRefusesNewScripts() {
        val runtime = AndroidTokenRuntime(context, scriptDeadlineMs = 60_000)
        try {
            val (call, view) = evaluating(runtime)
            shadowOf(view).webViewClient.onRenderProcessGone(view, killed)
            failure(call)
            assertTrue(failure(scope.async { runtime.evaluate("'again'") }) is IllegalStateException)
        } finally { runtime.close(); scope.cancel() }
    }
}
