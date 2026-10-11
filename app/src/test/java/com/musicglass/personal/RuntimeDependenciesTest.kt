package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class RuntimeDependenciesTest {
    private fun runtime(clock:FakeClock,resolver:ScriptedResolver)=RuntimeDependencies(
        networkConditions={NetworkConditions(false,false,false)},
        storageCapacity={StorageCapacity(0,1_000_000)},
        batteryPercent={15},
        httpCalls=OkHttpClient.Builder().addInterceptor {throw IOException("Unexpected outbound fixture request")}.build(),
        clocks=clock,dispatchers=AppDispatchers(Dispatchers.Unconfined,Dispatchers.Unconfined),
        providerTransport={OriginalAudioEngine.create {config {addInterceptor {throw IOException("Unexpected provider HTTP request")}}}},
        resolver=resolver,startProviderBackgroundWork=false)

    @Test fun providerCacheUsesInjectedClockAndResolver()=runBlocking {
        withTimeout(5000) {
            val clock=FakeClock();val resolver=ScriptedResolver()
            val context=ApplicationProvider.getApplicationContext<Context>()
            val container=AppContainer(context,runtime(clock,resolver))
            assertNull(container.existingProvider())
            val provider=container.provider
            assertSame(provider,container.provider)
            assertSame(provider,container.existingProvider())
            try {
                resolver.enqueue().complete(Result.success(fixtureStream(expiresAt=clock.wall+120_000)))
                val first=provider.resolve("fixtureA001").getOrThrow()
                assertEquals(first,provider.resolve("fixtureA001").getOrThrow())
                assertEquals(1,resolver.requests().size)
                clock.advance(61_000)
                resolver.enqueue().complete(Result.success(fixtureStream(expiresAt=clock.wall+120_000)))
                assertTrue(provider.resolve("fixtureA001").isSuccess)
                assertEquals(2,resolver.requests().size)
            } finally {provider.close()}
        }
    }

    @Test fun failedTransportCreationReleasesDownloadGate()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val dependencies=runtime(FakeClock(),ScriptedResolver()).copy(wait={})
        assertTrue(runCatching {
            OfflineDownloadEngine.run(context,false,dependencies.copy(providerTransport={throw IOException("Synthetic engine failure")})) {}
        }.exceptionOrNull() is IOException)
        assertTrue(OfflineDownloadEngine.run(context,false,dependencies) {})
    }

    @Test fun offlineBatteryAndStorageConditionsAreInjected() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val store=OfflineMusicStore.get(context);val dependencies=runtime(FakeClock(),ScriptedResolver())
        assertFalse(OfflineDownloadEngine.automaticConditionsAllowed(context,store,dependencies))
        assertEquals(0L,dependencies.storageCapacity(context.cacheDir).availableBytes)
        assertFalse(OfflineDownloadEngine.automaticConditionsAllowed(context,store,dependencies.copy(
            batteryPercent={100},networkConditions={NetworkConditions(true,true,false)})))
        assertTrue(OfflineDownloadEngine.automaticConditionsAllowed(context,store,dependencies.copy(
            batteryPercent={100},networkConditions={NetworkConditions(true,true,true)})))
    }
}
