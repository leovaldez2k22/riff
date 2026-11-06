package com.musicglass.personal

import java.io.File

/** Enough of a song's start to play while its address is looked up: about 28 s at 144 kbps, longer than a lookup. */
internal const val CACHED_START_BYTES = 512L * 1024
/** Never resolves, by RFC 2606, so a stand-in address cannot reach a server by mistake. */
internal const val CACHED_STREAM_HOST = "cached-stream.invalid"
private const val CACHED_STREAM_ENTRIES = 512

/** What is needed to play a song's bytes from the disk cache. Holds no address, header or token. */
internal data class CachedStreamEntry(val cacheKey: String, val itag: Int, val mimeType: String?, val bitrate: Int, val contentLength: Long?,
    val durationMs: Long, val loudnessDb: Double?, val sourceVideoId: String?, val sabr: Boolean)

internal enum class CachedPart(val word: String) { Whole("whole"), Start("start") }

/**
 * How much of [entry] can be played from disk without looking the song up first. [knownLength] is what the cache learned of
 * the length, or a non-positive number. A SABR song needs all its bytes, since continuing it needs a session a lookup makes.
 */
internal fun cachedPart(entry: CachedStreamEntry, knownLength: Long, isCached: (position: Long, length: Long) -> Boolean): CachedPart? {
    val declared = entry.contentLength?.takeIf { it > 0 }
    val length = declared ?: knownLength.takeIf { it > 0 } ?: return null
    if (isCached(0, length)) return CachedPart.Whole
    // Without a declared length, the address layer could not check that a later lookup is for these same bytes.
    if (entry.sabr || declared == null) return null
    return if (isCached(0, minOf(length, CACHED_START_BYTES))) CachedPart.Start else null
}

/**
 * Whether the bytes around [positionMs] are cached, so the player can reach it without the network. Seek points were about 10 s
 * apart on the phone, and it reads forward from the one before the position. Bytes are placed by time, so the margin is generous.
 */
internal fun cachedAround(positionMs: Long, durationMs: Long, length: Long, isCached: (position: Long, length: Long) -> Boolean): Boolean {
    if (durationMs <= 0 || length <= 0) return false
    val from = (positionMs - CACHED_AROUND_MS).coerceAtLeast(0).coerceAtMost(durationMs) * length / durationMs
    val to = (positionMs + CACHED_AROUND_MS).coerceAtLeast(0).coerceAtMost(durationMs) * length / durationMs
    return to > from && isCached(from, to - from)
}
private const val CACHED_AROUND_MS = 15_000L

/** The entry for a stream just looked up, or null when its bytes are not kept or its address could not continue them. */
internal fun cachedStreamEntry(stream: ProviderStream, cacheKey: String?): CachedStreamEntry? {
    if (cacheKey == null || stream.itag <= 0 || stream.includesVideo || stream.videoUrl != null || stream.boundedRange) return null
    return CachedStreamEntry(cacheKey, stream.itag, stream.mimeType, stream.bitrate, stream.contentLength, stream.durationMs,
        stream.loudnessDb, stream.sourceVideoId, stream.sabrBootstrap != null)
}

/**
 * A stream for bytes on disk. Its address is already out of date, so the address layer looks up a real one the moment a
 * read goes past the cached bytes, and checks that it is for the same bytes (`sameStreamRepresentation`).
 */
internal fun cachedStreamStandIn(entry: CachedStreamEntry, videoId: String) = ProviderStream(
    url = if (entry.sabr) "sabr://$CACHED_STREAM_HOST/$videoId" else "https://$CACHED_STREAM_HOST/$videoId",
    headers = emptyMap(), mimeType = entry.mimeType, sourceVideoId = entry.sourceVideoId, itag = entry.itag, bitrate = entry.bitrate,
    contentLength = entry.contentLength, durationMs = entry.durationMs, expiresAtMs = 1, loudnessDb = entry.loudnessDb, cachedKey = entry.cacheKey)

/**
 * Which cached bytes belong to a song, so that it can start before any lookup. A cache key includes the encoding, which only a
 * lookup used to tell. Kept beside the cache and cleared with it. Holds what the cache's own keys already show: an entry for
 * another account or quality is simply not found. Thread-safe; call it off the main thread.
 */
internal class CachedStreamIndex(private val file: File, private val keep: Int = CACHED_STREAM_ENTRIES) {
    private var entries: LinkedHashMap<String, CachedStreamEntry>? = null

    @Synchronized fun get(scope: String, videoId: String, quality: String): CachedStreamEntry? = loaded()[key(scope, videoId, quality)]

    @Synchronized fun put(scope: String, videoId: String, quality: String, entry: CachedStreamEntry) {
        val all = loaded(); val key = key(scope, videoId, quality)
        if (all[key] == entry) return
        all.remove(key); all[key] = entry
        while (all.size > keep) all.remove(all.keys.first())
        save(all)
    }

    @Synchronized fun remove(scope: String, videoId: String, quality: String) {
        val all = loaded()
        if (all.remove(key(scope, videoId, quality)) != null) save(all)
    }

    @Synchronized fun clear() { entries = linkedMapOf(); file.delete() }

    private fun key(scope: String, videoId: String, quality: String) = "$scope|$videoId|$quality"

    private fun loaded() = entries ?: linkedMapOf<String, CachedStreamEntry>().also { all ->
        runCatching { if (file.isFile) file.forEachLine { line -> parse(line)?.let { (key, entry) -> all[key] = entry } } }
        entries = all
    }

    private fun save(all: Map<String, CachedStreamEntry>) {
        // Losing this file only costs a lookup, so a failed write is not a fault.
        runCatching {
            val written = File(file.path + ".tmp")
            written.writeText(all.entries.joinToString("\n") { (key, e) -> listOf(key, e.cacheKey, e.itag, e.mimeType.orEmpty(), e.bitrate,
                e.contentLength ?: "", e.durationMs, e.loudnessDb ?: "", e.sourceVideoId.orEmpty(), e.sabr).joinToString("\t") })
            if (!written.renameTo(file)) { file.delete(); written.renameTo(file) }
        }
    }

    private fun parse(line: String): Pair<String, CachedStreamEntry>? {
        val f = line.split('\t')
        if (f.size != 10) return null
        val itag = f[2].toIntOrNull() ?: return null
        val bitrate = f[4].toIntOrNull() ?: return null
        val duration = f[6].toLongOrNull() ?: return null
        return f[0] to CachedStreamEntry(f[1], itag, f[3].ifEmpty { null }, bitrate, f[5].toLongOrNull(), duration, f[7].toDoubleOrNull(),
            f[8].ifEmpty { null }, f[9] == "true")
    }
}
