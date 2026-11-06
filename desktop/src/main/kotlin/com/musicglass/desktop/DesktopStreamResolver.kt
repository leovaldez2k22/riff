package com.musicglass.desktop

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A transport is enabled only by a backend that actually resolves it. */
internal enum class StreamTransportCapability { Direct, HLS, SABR }

/** Short-lived credentials/URLs stay in memory; this value is not a playback checkpoint. */
internal data class StreamHandle(
    val trackId: String,
    val streamUrl: String,
    val mimeType: String,
    val expiresAtEpochMs: Long,
    val transport: StreamTransportCapability = StreamTransportCapability.Direct,
    val sessionScope: String = "anonymous",
    val bitRateBps: Int = 0
) {
    fun isNearExpiration(safetyMarginMs: Long = 60_000L): Boolean =
        expiresAtEpochMs <= System.currentTimeMillis() + safetyMarginMs
}

internal data class StreamResolutionRequest(
    val trackId: String,
    val sessionScope: String,
    val transport: StreamTransportCapability
)

/** Required dependency: production may not substitute a URI formatter for a provider. */
internal fun interface StreamResolverBackend {
    suspend fun resolve(request: StreamResolutionRequest): StreamHandle
}

internal class StreamResolutionInvalidatedException : IllegalStateException("Stream session changed during resolution")

/** Bounded account/transport-scoped cache around an explicitly supplied resolver backend. */
internal class DesktopStreamResolver(
    private val backend: StreamResolverBackend,
    private val maxRetries: Int = 3,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    init { require(maxRetries in 1..3) }

    private val guard = Any()
    private val cache = LinkedHashMap<StreamResolutionRequest, StreamHandle>(32, .75f, true)
    private val stripes = Array(32) { Mutex() }
    private var generation = 0L

    private fun fresh(handle: StreamHandle): Boolean {
        val now = nowMs()
        return handle.expiresAtEpochMs > now && handle.expiresAtEpochMs - now > 60_000L
    }

    suspend fun resolveStream(
        trackId: String,
        sessionScope: String = "anonymous",
        preferredTransport: StreamTransportCapability = StreamTransportCapability.Direct
    ): Result<StreamHandle> {
        if (trackId.isBlank() || sessionScope.isBlank()) {
            return Result.failure(IllegalArgumentException("Track and account scope are required"))
        }
        val request = StreamResolutionRequest(trackId, sessionScope, preferredTransport)
        val startedGeneration = synchronized(guard) { generation }
        val stripe = stripes[(request.hashCode() and Int.MAX_VALUE) % stripes.size]
        return stripe.withLock {
            synchronized(guard) {
                if (generation != startedGeneration) return@withLock Result.failure(StreamResolutionInvalidatedException())
                cache[request]?.takeIf(::fresh)?.let { return@withLock Result.success(it) }
            }
            var lastFailure: IOException? = null
            repeat(maxRetries) { attempt ->
                try {
                    val handle = backend.resolve(request)
                    validate(request, handle)
                    return@withLock synchronized(guard) {
                        if (generation != startedGeneration) Result.failure(StreamResolutionInvalidatedException())
                        else {
                            cache[request] = handle
                            while (cache.size > 256) cache.remove(cache.keys.first())
                            Result.success(handle)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val invalidated = synchronized(guard) { generation != startedGeneration }
                    if (invalidated) return@withLock Result.failure(StreamResolutionInvalidatedException())
                    if (failure !is IOException) return@withLock Result.failure(failure)
                    lastFailure = failure
                    if (attempt + 1 < maxRetries) delay(100L * (1L shl attempt))
                }
            }
            Result.failure<StreamHandle>(checkNotNull(lastFailure))
        }
    }

    private fun validate(request: StreamResolutionRequest, handle: StreamHandle) {
        require(handle.trackId == request.trackId && handle.sessionScope == request.sessionScope &&
            handle.transport == request.transport) { "Resolved source identity does not match request" }
        val uri = java.net.URI(handle.streamUrl)
        require(uri.scheme?.lowercase() in setOf("https", "http", "file")) { "Unsupported resolved source scheme" }
        if (uri.scheme.lowercase() == "file") {
            require(java.nio.file.Files.isRegularFile(java.nio.file.Paths.get(uri))) { "Local source is unavailable" }
        } else {
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "Invalid media URL" }
        }
        require(handle.mimeType.isNotBlank() && handle.bitRateBps >= 0) { "Invalid stream metadata" }
        require(fresh(handle)) { "Resolved source is expired or too near expiration" }
    }

    fun invalidate(sessionScope: String? = null, trackId: String? = null) = synchronized(guard) {
        // Conservatively fence every in-flight call; none may republish pre-invalidation credentials.
        generation++
        cache.keys.removeAll { (sessionScope == null || it.sessionScope == sessionScope) &&
            (trackId == null || it.trackId == trackId) }
    }

    fun getCachedHandle(
        sessionScope: String, trackId: String,
        transport: StreamTransportCapability = StreamTransportCapability.Direct
    ): StreamHandle? = synchronized(guard) {
        val key = StreamResolutionRequest(trackId, sessionScope, transport)
        cache[key]?.takeIf(::fresh).also { if (it == null) cache.remove(key) }
    }
}