package com.musicglass.personal

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * W6 device check, step 1: sets the sleep timer through the real gateway -> service path and reports
 * the deadline. When an instrumentation finishes, Android force-stops the app: a real process kill in
 * which the service's onDestroy never runs, which is exactly what the saved deadline must survive.
 *
 *   adb shell am instrument -w -e minutes 2 com.musicglass.personal.test/com.musicglass.personal.SleepTimerSetInstrumentation
 *
 * Then restart the playback service, press play, and check it pauses at the reported deadline.
 */
class SleepTimerSetInstrumentation : Instrumentation() {
    private var minutes = 2
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments); minutes = arguments?.getString("minutes")?.toIntOrNull() ?: 2; start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val gateway = withContext(Dispatchers.Main) { MusicPlaybackGateway(targetContext) }
                try {
                    withTimeout(15_000) { while (!withContext(Dispatchers.Main) { gateway.state.ready }) delay(50) }
                    // A deadline restored from an earlier run may already be showing: wait for ours.
                    val before = withContext(Dispatchers.Main) { gateway.state.sleepDeadlineMs }
                    val expected = SystemClock.elapsedRealtime() + minutes * 60_000L
                    withContext(Dispatchers.Main) { gateway.setSleepTimer(minutes) }
                    val deadline = withTimeout(10_000) {
                        var d = before
                        while (d == before || d < expected - 5_000) { delay(50); d = withContext(Dispatchers.Main) { gateway.state.sleepDeadlineMs } }
                        d
                    }
                    result.putLong("deadline_elapsed_ms", deadline)
                    result.putLong("now_elapsed_ms", SystemClock.elapsedRealtime())
                    result.putLong("remaining_ms", deadline - SystemClock.elapsedRealtime())
                    delay(1_000) // let the service's SharedPreferences.apply() reach disk
                } finally {
                    withContext(Dispatchers.Main) { gateway.close() }
                }
            }
            result.putString("result", "set")
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("result", "fail"); result.putString("error", "${e.javaClass.simpleName}: ${e.message}")
            finish(0, result)
        }
    }
}
