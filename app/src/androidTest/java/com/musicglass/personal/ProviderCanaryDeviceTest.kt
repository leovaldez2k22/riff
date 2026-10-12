package com.musicglass.personal

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Q10A: the stream phase of the provider canary. Resolving a stream needs the solver process, which exists
 * only on Android, so this phase runs here and not on the JVM. It resolves one long-lived public song with
 * the app signed out and reads the first 2048 bytes of its audio. It sends real requests, so it runs only
 * when asked for:
 *
 *   adb shell am instrument -w -r -e class com.musicglass.personal.ProviderCanaryDeviceTest -e riffCanary stream \
 *       com.musicglass.personal.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The result is one status line, riffCanary={...}, which scripts/quality/provider_canary.py reads with
 * --stream-log. The test passes whatever the provider answers. If the app is signed in it sends nothing.
 */
@RunWith(AndroidJUnit4::class)
class ProviderCanaryDeviceTest {
    @Test fun streamPhase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Runs only with -e riffCanary stream", InstrumentationRegistry.getArguments().getString("riffCanary") == "stream")
        val provider = AppContainer.get(instrumentation.targetContext).provider
        val phase = runBlocking {
            withTimeout(150_000) { StreamCanary({ provider.signedIn }, { provider.cipherIsolated }, { videoId -> provider.resolve(videoId) }).run() }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("riffCanary", phase.toJson()) })
    }
}
