package com.musicglass.personal

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk = [36])
class PlaybackHttpTest {
    private val answer = Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("".toResponseBody()).build()
    }
    private fun serviceClient() = HttpClient(OkHttp) { engine { sharePlaybackConnections(); config { addInterceptor(answer) } } }
    private fun providerClient() = HttpClient(OriginalAudioEngine) { engine { config { addInterceptor(answer) } } }
    /** Closing is finished on another coroutine; this is the point after which the shutdown has happened, if it will. */
    private suspend fun closed(client: HttpClient) {
        client.close()
        withTimeout(5_000) { client.engine.coroutineContext[Job]?.join() }
        delay(300)
    }
    private suspend fun status(client: HttpClient): Int = client.get("https://www.youtube.com/").statusOf()
    private fun HttpResponse.statusOf() = status.value

    /**
     * The service closes its client in onDestroy while the process, and so the provider's client, lives on. Ktor shuts
     * down a closing client's dispatcher: if that were shared, every later request would fail with "executor rejected".
     */
    @Test fun aServiceThatClosesItsClientLeavesTheProviderWorking() = runBlocking {
        val service = serviceClient()
        val provider = providerClient()
        try {
            assertEquals(200, status(service))
            assertEquals(200, status(provider))
            closed(service)
            assertFalse(PlaybackHttp.base.dispatcher.executorService.isShutdown)
            assertEquals(200, status(provider))
            val next = serviceClient()
            try { assertEquals("a recreated service works too", 200, status(next)) } finally { next.close() }
        } finally { provider.close() }
    }

    @Test fun aProviderThatClosesItsClientLeavesTheOthersWorking() = runBlocking {
        val first = providerClient()
        val second = providerClient()
        try {
            assertEquals(200, status(first))
            closed(first)
            assertFalse(PlaybackHttp.base.dispatcher.executorService.isShutdown)
            assertEquals(200, status(second))
        } finally { second.close() }
    }
}
