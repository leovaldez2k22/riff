package com.musicglass.personal

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Q10A: the catalog phases of the canary against the provider itself: one search and one collection page,
 * signed out. It sends real requests, so Gradle leaves it out of every test run unless -PriffCanaryOut names
 * the file to write. Run it through scripts/quality/provider_canary.py, which keeps it to once a day.
 * It passes whatever the provider answers: the answer is the result, written as classes and reason words.
 */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[36])
class ProviderCanaryLive {
    @Test fun searchAndOneCollectionPage() {
        val out = assertNotNull("Run through scripts/quality/provider_canary.py", System.getProperty("riff.canary.out")).let { File(System.getProperty("riff.canary.out")) }
        val phases = CanaryWorld(ApplicationProvider.getApplicationContext()).use { world -> runBlocking { listOf(world.search(), world.collection()) } }
        out.absoluteFile.parentFile?.mkdirs()
        out.writeText(canaryJson(phases))
    }
}
