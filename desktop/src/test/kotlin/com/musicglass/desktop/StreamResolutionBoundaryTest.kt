package com.musicglass.desktop

import java.io.IOException
import kotlinx.coroutines.*
import kotlin.test.*

class StreamResolutionBoundaryTest {
    private val now = 1_000_000L
    private fun handle(r: StreamResolutionRequest, url: String = "https://media.example.test/audio") =
        StreamHandle(r.trackId, url, "audio/ogg", now + 120_000, r.transport, r.sessionScope)

    @Test fun rejectsManufacturedUrisWithoutRetry() = runBlocking<Unit> {
        var calls = 0
        val resolver = DesktopStreamResolver(StreamResolverBackend { calls++; handle(it, "riff://stream/account/song") }, nowMs = { now })
        assertTrue(resolver.resolveStream("song").isFailure)
        assertEquals(1, calls)
        assertNull(resolver.getCachedHandle("anonymous", "song"))
    }

    @Test fun cancellationPropagatesWithoutRetry() = runBlocking<Unit> {
        var calls = 0
        val resolver = DesktopStreamResolver(StreamResolverBackend { calls++; throw CancellationException("cancelled") })
        assertFailsWith<CancellationException> { resolver.resolveStream("song") }
        assertEquals(1, calls)
    }

    @Test fun invalidationFencesInFlightReply() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val resolver = DesktopStreamResolver(StreamResolverBackend { entered.complete(Unit); release.await(); handle(it) }, nowMs = { now })
        val pending = async { resolver.resolveStream("song", "account") }
        entered.await()
        resolver.invalidate(sessionScope = "account")
        release.complete(Unit)
        assertIs<StreamResolutionInvalidatedException>(pending.await().exceptionOrNull())
        assertNull(resolver.getCachedHandle("account", "song"))
    }

    @Test fun concurrentRequestsShareOneActualResolution() = runBlocking<Unit> {
        var calls = 0
        val resolver = DesktopStreamResolver(StreamResolverBackend { calls++; delay(10); handle(it) }, nowMs = { now })
        val replies = coroutineScope { List(16) { async { resolver.resolveStream("song").getOrThrow() } }.awaitAll() }
        assertEquals(1, calls)
        replies.forEach { assertSame(replies.first(), it) }
    }

    @Test fun structuredKeysSeparateColonCollisionsAndTransport() = runBlocking<Unit> {
        var calls = 0
        val resolver = DesktopStreamResolver(StreamResolverBackend { calls++; handle(it) }, nowMs = { now })
        resolver.resolveStream("c", "a:b").getOrThrow()
        resolver.resolveStream("b:c", "a").getOrThrow()
        resolver.resolveStream("c", "a:b", StreamTransportCapability.HLS).getOrThrow()
        assertEquals(3, calls)
        resolver.invalidate(sessionScope = "a")
        assertNotNull(resolver.getCachedHandle("a:b", "c"))
        assertNull(resolver.getCachedHandle("a", "b:c"))
    }

    @Test fun onlyIoFailuresRetryAndRetryCountIsBounded() = runBlocking<Unit> {
        var calls = 0
        val resolver = DesktopStreamResolver(StreamResolverBackend { calls++; throw IOException("offline") })
        assertIs<IOException>(resolver.resolveStream("song").exceptionOrNull())
        assertEquals(3, calls)
    }

    @Test fun mismatchedOrExpiredIdentityIsNeverCached() = runBlocking<Unit> {
        for (bad in listOf<(StreamResolutionRequest) -> StreamHandle>(
            { handle(it).copy(sessionScope = "other") },
            { handle(it).copy(expiresAtEpochMs = now) },
            { handle(it).copy(streamUrl = "https://user:secret@media.example.test/audio") }
        )) {
            val resolver = DesktopStreamResolver(StreamResolverBackend { bad(it) }, nowMs = { now })
            assertTrue(resolver.resolveStream("song").isFailure)
            assertNull(resolver.getCachedHandle("anonymous", "song"))
        }
    }

    @Test fun cacheHasFiniteCapacity() = runBlocking<Unit> {
        val resolver = DesktopStreamResolver(StreamResolverBackend { handle(it) }, nowMs = { now })
        repeat(257) { resolver.resolveStream("song-$it").getOrThrow() }
        assertNull(resolver.getCachedHandle("anonymous", "song-0"))
        assertNotNull(resolver.getCachedHandle("anonymous", "song-256"))
    }
}