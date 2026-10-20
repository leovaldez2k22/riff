package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * Q18-014: Home's fresh feed is asked for when the app opens, not once the logo hands over. Nothing is stored:
 * the owner chose on 3 Oct 2026 that Home shows only a fresh feed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomePrefetchTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val paths = Collections.synchronizedList(mutableListOf<String>())

    private fun runtime(io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) = RuntimeDependencies(
        networkConditions = { NetworkConditions(true, true, true) },
        storageCapacity = { StorageCapacity(0, 1_000_000) },
        batteryPercent = { 80 },
        httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
        clocks = FakeClock(), dispatchers = AppDispatchers(io, Dispatchers.Unconfined),
        providerTransport = { OriginalAudioEngine.create { config { addInterceptor { chain ->
            paths += chain.request().url.encodedPath; throw IOException("fixture: no network") } } } },
        resolver = ScriptedResolver(), storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)

    @Test fun homeIsAskedForBeforeAnyScreenIsBuilt() {
        val container = AppContainer(context, runtime())
        container.prefetchHome()
        val deadline = System.currentTimeMillis() + 10_000
        while (paths.none { it.endsWith("/browse") } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("A browse request went out: $paths", paths.any { it.endsWith("/browse") })
        assertNull("A failed prefetch stores nothing; Home asks again and shows why", container.provider.cachedFeed("home:"))
    }

    @Test fun theProviderIsBuiltOnTheCallersThreadBeforeAnyScreenReadsIt() {
        // Q18-051: an IO thread that has not run yet; the provider must already exist when prefetchHome returns.
        val parked = Collections.synchronizedList(mutableListOf<Runnable>())
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { parked += block }
        }
        val container = AppContainer(context, runtime(io))
        assertNull(container.existingProvider())
        container.prefetchHome()
        assertNotNull("Built on the caller's thread", container.existingProvider())
        assertTrue("The request itself waits for IO", paths.isEmpty())
        container.existingProvider()?.close()
    }

    @Test fun theActivityStartsItAsItOpensAndNoHomeSnapshotIsWritten() {
        val activity = File("src/main/java/com/musicglass/personal/MainActivity.kt").readText()
        val onCreate = activity.substringAfter("override fun onCreate").substringBefore("setContent")
        assertTrue("Before the logo and the app are composed", onCreate.contains("prefetchHome()"))
        val catalog = File("src/main/java/com/musicglass/personal/ProviderCatalogRepository.kt").readText()
        assertFalse("Home is not written to disk (owner's choice, 3 Oct 2026)", Regex("""feed-snapshots"[^\n]*(writeText|outputStream)""").containsMatchIn(catalog))
    }
}
