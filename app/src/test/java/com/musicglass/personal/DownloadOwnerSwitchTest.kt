package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** An account switch in the middle of a transfer; synthetic session fields and no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class DownloadOwnerSwitchTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val processField=AppContainer::class.java.getDeclaredField("process").apply {isAccessible=true}

    private fun session(sync:String?) {
        val edit=context.getSharedPreferences("provider_session",Context.MODE_PRIVATE).edit().clear()
        CookieManager.getInstance().removeAllCookies(null)
        if(sync==null) edit.putBoolean("signed_out",true).commit()
        else {edit.putString("sync",sync).putString("account","0").commit();CookieManager.getInstance().setCookie("https://music.youtube.com","SAPISID=fixture")}
    }

    @Test fun switchDuringATransferStopsItAndLeavesTheSongQueuedForItsOwner()=runBlocking {
        withTimeout(20_000) {
            val previous=processField.get(null)
            session("fixture-sync-A")
            lateinit var container:AppContainer
            var resolves=0
            // The switch lands while the engine is waiting for the stream, as a slow resolve would allow.
            val resolver=object:StreamResolver {
                override suspend fun resolve(request:StreamRequest):Result<ProviderStream> {
                    resolves++
                    val result=container.sessions.transition("fixture_switch",providerScope(VerifiedAccountIdentity("fixture-sync-B"))) {
                        session("fixture-sync-B");container.provider.refreshAccount()
                    }
                    check(result is SessionTransition.Committed)
                    return Result.success(fixtureStream(request.videoId))
                }
            }
            val runtime=RuntimeDependencies(
                networkConditions={NetworkConditions(false,false,false)},
                storageCapacity={StorageCapacity(1_000_000_000,2_000_000_000)},
                batteryPercent={80},
                httpCalls=OkHttpClient.Builder().addInterceptor {throw IOException("Unexpected outbound fixture request")}.build(),
                clocks=FakeClock(),dispatchers=AppDispatchers(Dispatchers.Unconfined,Dispatchers.Unconfined),
                providerTransport={OriginalAudioEngine.create {config {addInterceptor {throw IOException("Unexpected provider HTTP request")}}}},
                resolver=resolver,storedScopeKeys={emptySet()},startProviderBackgroundWork=false,wait={})
            container=AppContainer(context,runtime)
            processField.set(null,container)
            try {
                val store=OfflineMusicStore.get(context)
                val ownerA=store.currentScope
                assertEquals(providerScope(VerifiedAccountIdentity("fixture-sync-A")).opaqueId,ownerA)
                store.enqueue(ProviderTrack("fixtureA001","Song","Artist"),scope=ownerA)

                runCatching {OfflineDownloadEngine.run(context,false,runtime) {}}

                assertEquals(1,resolves)
                val ownerB=store.currentScope
                assertNotEquals(ownerA,ownerB)
                assertEquals("queued",store.song(ownerA,"fixtureA001")?.state)
                assertNull(store.audio(ownerA,"fixtureA001"))
                assertNull("A's song must not appear under B",store.song(ownerB,"fixtureA001"))
                assertNull(store.audio(ownerB,"fixtureA001"))
            } finally {
                container.provider.close();processField.set(null,previous);session(null)
            }
        }
    }
}
