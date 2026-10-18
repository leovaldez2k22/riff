package com.musicglass.personal

import android.app.ActivityManager
import android.app.Instrumentation
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Device check for the :solver process (W1.2). Runs in the app's own process, so surviving each
 * solver crash is itself part of the check.
 *
 *   adb shell am instrument -w com.musicglass.personal.debug.test/com.musicglass.personal.SolverIsolationInstrumentation
 *   (test APK of a debug build made with -PriffSideBySide=true, as the lanes make it; otherwise com.musicglass.personal.test)
 *
 * Crashes the solver four times; afterwards force-stop the app so the provider's own client
 * forgets those crashes.
 */
class SolverIsolationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun solverPid(): Int? = targetContext.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == "${targetContext.packageName}:solver" }?.pid

    private fun crashSolver(): Int {
        val pid = checkNotNull(solverPid()) { "No solver process to crash" }
        Process.sendSignal(pid, 11) // SIGSEGV, as in the stress-test crash
        val until = SystemClock.elapsedRealtime() + 5_000
        while (solverPid() == pid && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
        check(solverPid() != pid) { "Solver $pid survived SIGSEGV" }
        return pid
    }

    override fun onStart() {
        val result = Bundle()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val http = HttpClient(OriginalAudioEngine) { install(HttpTimeout) { connectTimeoutMillis = 12_000; requestTimeoutMillis = 30_000 } }
        fun step(name: String) { android.util.Log.i("SolverTest", name); events += "step:$name" }
        try {
            // A real player script URL, as the provider would get it.
            val innerTube = InnerTube(http)
            val parser = YtConfigParserImpl(http, innerTube, RemotePlayerConfigStore(http, AppPlayerConfigRepository(targetContext, "player_config_test")))
            step("player_url")
            val playerUrl = runBlocking { parser.fetchEmbeddedConfig("dQw4w9WgXcQ", useLoginCookies = false).playerUrl }
            val client = RemoteCipherClient(targetContext, onEvent = { events += it }, idleMs = 120_000)
            fun challengeUrl(n: String) = "https://manifest.googlevideo.com/videoplayback?n=$n"
            fun solved(url: String?, n: String) = url != null && "n=$n" !in url && "n=" in url

            step("cold_solve")
            // 1. A real challenge is solved in the separate process.
            var start = SystemClock.elapsedRealtime()
            val first = runBlocking { client.processNParameter(playerUrl, challengeUrl("q7Hx3kLm2Pn9Rt5w")) }
            result.putLong("cold_solve_ms", SystemClock.elapsedRealtime() - start)
            check(solved(first, "q7Hx3kLm2Pn9Rt5w")) { "Solver did not transform n" }
            val firstPid = checkNotNull(solverPid())
            check(firstPid != Process.myPid()) { "Solver ran in the main process" }
            result.putInt("solver_pid_1", firstPid)

            step("crash_between")
            // 2. Crash between requests: the next request starts a new solver.
            crashSolver()
            start = SystemClock.elapsedRealtime()
            val second = runBlocking { client.processNParameter(playerUrl, challengeUrl("Zp4Rw8Lc1Vb6Nm3q")) }
            result.putLong("restart_solve_ms", SystemClock.elapsedRealtime() - start)
            check(solved(second, "Zp4Rw8Lc1Vb6Nm3q")) { "Restarted solver did not transform n" }
            val secondPid = checkNotNull(solverPid())
            check(secondPid != firstPid) { "Solver was not restarted" }

            step("crash_during")
            // 3. Crash during a request. A fresh client (fresh crash count) and a crashed warm solver force
            //    a cold start (~6 s: player script download + parse); crash it mid-way. The client retries once.
            val coldClient = RemoteCipherClient(targetContext, onEvent = { events += "b:$it" }, idleMs = 120_000)
            crashSolver()
            val third = runBlocking {
                val call = async(kotlinx.coroutines.Dispatchers.IO) { coldClient.processNParameter(playerUrl, challengeUrl("Hk2Jd9Fs7Ga4Lp1x")) }
                val until = SystemClock.elapsedRealtime() + 10_000
                while (!events.contains("b:solver_connected") && SystemClock.elapsedRealtime() < until) delay(20)
                delay(1_500)
                if (call.isCompleted) result.putString("mid_call_note", "finished before the crash") else crashSolver()
                call.await()
            }
            check(solved(third, "Hk2Jd9Fs7Ga4Lp1x")) { "Retry after a mid-request crash failed" }
            result.putBoolean("mid_call_retry", events.any { it.startsWith("b:solver_call_lost") })

            step("crash_loop")
            // 4. Crash-loop guard: coldClient has seen 1 crash; two more within 10 minutes pause the solver.
            crashSolver()
            check(solved(runBlocking { coldClient.processNParameter(playerUrl, challengeUrl("Wq3Er6Ty9Ui2Op5a")) }, "Wq3Er6Ty9Ui2Op5a")) {
                "Solver did not recover after the second crash"
            }
            crashSolver()
            start = SystemClock.elapsedRealtime()
            val paused = runCatching { runBlocking { coldClient.processNParameter(playerUrl, challengeUrl("Mn5Bv2Cx8Zl3Kj7h")) } }
            result.putLong("paused_fail_ms", SystemClock.elapsedRealtime() - start)
            check(paused.exceptionOrNull() is IOException) { "Expected a fast IOException while paused, got $paused" }
            check(SystemClock.elapsedRealtime() - start < 2_000) { "Paused solver did not fail fast" }

            result.putInt("solver_deaths_a", events.count { it == "solver_died" })
            result.putInt("solver_deaths_b", events.count { it == "b:solver_died" })
            result.putString("events", events.joinToString(","))
            result.putString("result", "pass")
            finish(Activity_RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "fail")
            result.putString("error", "${e.javaClass.simpleName}: ${e.message}")
            result.putString("events", events.joinToString(","))
            finish(Activity_RESULT_FAILED, result)
        } finally {
            http.close()
        }
    }

    private companion object {
        const val Activity_RESULT_OK = -1
        const val Activity_RESULT_FAILED = 0
    }
}
