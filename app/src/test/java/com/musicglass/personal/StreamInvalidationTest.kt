package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class StreamInvalidationTest {
    @Test fun invalidationDetachesOldFlightAndLateCompletionCannotPoisonCache() = runBlocking {
        ProviderWorld(ApplicationProvider.getApplicationContext<Context>()).use { world ->
            val resolver = world.runtime.resolver as ScriptedResolver
            val oldReply = resolver.enqueue()
            val freshReply = resolver.enqueue()
            val id = "fxsong00001"
            val old = async(start = CoroutineStart.UNDISPATCHED) { world.provider.resolve(id).getOrThrow() }
            assertEquals(1, resolver.requests().size)
            world.provider.invalidateStream(id)
            val fresh = async(start = CoroutineStart.UNDISPATCHED) { world.provider.resolve(id).getOrThrow() }
            assertEquals(2, resolver.requests().size)
            val valid = fixtureStream(id).copy(url = "https://media.example/fresh")
            freshReply.complete(Result.success(valid))
            assertEquals(valid, fresh.await())
            oldReply.complete(Result.success(valid.copy(url = "https://media.example/stale")))
            old.await()
            assertEquals(valid, world.provider.resolve(id).getOrThrow())
            assertEquals(2, resolver.requests().size)
        }
    }
}
