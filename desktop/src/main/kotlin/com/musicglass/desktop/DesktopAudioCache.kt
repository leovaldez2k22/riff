package com.musicglass.desktop

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Contiguous byte range within an audio resource. */
internal data class CachedByteRange(
    val startOffset: Long,
    val endOffset: Long // inclusive
) {
    val length: Long get() = (endOffset - startOffset + 1).coerceAtLeast(0L)

    fun contains(offset: Long): Boolean = offset in startOffset..endOffset
    fun overlapsOrAbuts(other: CachedByteRange): Boolean =
        !(endOffset < other.startOffset - 1 || other.endOffset < startOffset - 1)

    fun merge(other: CachedByteRange): CachedByteRange = CachedByteRange(
        minOf(startOffset, other.startOffset),
        maxOf(endOffset, other.endOffset)
    )
}

/** Metadata for an audio cache segment. */
internal data class CacheResource(
    val key: String, // scope:providerId:representation
    val keyHash: String, // SHA-256 hex string
    val contentLength: Long,
    val ranges: List<CachedByteRange>,
    val isComplete: Boolean,
    val lastAccessedAt: Long,
    val file: File
) {
    fun hasCoverage(offset: Long, length: Int): Boolean {
        if (length <= 0) return true
        val targetEnd = offset + length - 1
        return ranges.any { it.startOffset <= offset && it.endOffset >= targetEnd }
    }
}

/**
 * 256 MiB LRU audio disk cache managing collision-resistant SHA-256 filenames,
 * sparse byte range coverage, and strict byte-budget eviction.
 * Preserves Media3 SimpleCache semantics without leaking expiring URLs (Section 5.2).
 */
internal class DesktopAudioCache(
    private val cacheDir: File,
    private val maxSizeBytes: Long = 256L * 1024 * 1024 // 256 MiB
) {
    private val resources = ConcurrentHashMap<String, CacheResource>()
    private val lock = ReentrantReadWriteLock()
    private var totalSizeBytes: Long = 0L
    private var accessCounter: Long = 0L

    private val manifestFile = File(cacheDir, "cache-manifest.txt")

    init {
        cacheDir.mkdirs()
        scanExistingCache()
    }

    /** Opens or allocates an audio cache file for a key with collision-resistant SHA-256 hashing. */
    fun writeData(key: String, offset: Long, data: ByteArray, totalLength: Long): Boolean = lock.write {
        val keyHash = sha256Hex(key)
        val targetFile = File(cacheDir, "$keyHash.pcm")

        val previousFileLength = if (targetFile.exists()) targetFile.length() else 0L
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.seek(offset)
            raf.write(data)
        }

        val newFileLength = targetFile.length()
        totalSizeBytes += (newFileLength - previousFileLength)

        val newRange = CachedByteRange(offset, offset + data.size - 1)
        val existingResource = resources[key]
        val updatedRanges = mergeRanges((existingResource?.ranges ?: emptyList()) + newRange)

        val isComplete = (totalLength > 0L && newFileLength >= totalLength && updatedRanges.any { it.startOffset == 0L && it.endOffset >= totalLength - 1 })
        resources[key] = CacheResource(
            key = key,
            keyHash = keyHash,
            contentLength = newFileLength,
            ranges = updatedRanges,
            isComplete = isComplete,
            lastAccessedAt = ++accessCounter,
            file = targetFile
        )

        evictIfNecessary()
        saveManifest()
        return@write isComplete
    }

    /** Reads cached bytes if byte-range coverage is present. */
    fun readData(key: String, offset: Long, length: Int): ByteArray? = lock.write {
        val res = resources[key] ?: return@write null
        if (!res.file.exists() || !res.hasCoverage(offset, length)) return@write null

        val available = minOf(length.toLong(), res.file.length() - offset).toInt()
        if (available <= 0) return@write null

        val buffer = ByteArray(available)
        RandomAccessFile(res.file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(buffer)
        }
        resources[key] = res.copy(lastAccessedAt = ++accessCounter)
        return@write buffer
    }

    fun isCached(key: String): Boolean = lock.read {
        resources[key]?.isComplete == true
    }

    fun getResource(key: String): CacheResource? = lock.read {
        resources[key]
    }

    fun getTotalSizeBytes(): Long = lock.read { totalSizeBytes }

    private fun evictIfNecessary() {
        var evictedAny = false
        while (totalSizeBytes > maxSizeBytes && resources.isNotEmpty()) {
            val oldest = resources.values.minByOrNull { it.lastAccessedAt } ?: break
            val fileSize = oldest.file.length()
            if (oldest.file.exists()) oldest.file.delete()
            resources.remove(oldest.key)
            totalSizeBytes = maxOf(0L, totalSizeBytes - fileSize)
            evictedAny = true
        }
        if (evictedAny) saveManifest()
    }

    private fun saveManifest() {
        try {
            val sb = java.lang.StringBuilder()
            for (res in resources.values) {
                val rangesStr = res.ranges.joinToString(",") { "${it.startOffset}:${it.endOffset}" }
                sb.append("${res.key}|${res.keyHash}|${res.contentLength}|${res.isComplete}|${res.lastAccessedAt}|$rangesStr\n")
            }
            manifestFile.writeText(sb.toString(), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun scanExistingCache() {
        totalSizeBytes = 0L
        if (manifestFile.exists()) {
            try {
                manifestFile.forEachLine { line ->
                    val parts = line.split("|")
                    if (parts.size >= 6) {
                        val key = parts[0]
                        val keyHash = parts[1]
                        val contentLength = parts[2].toLongOrNull() ?: 0L
                        val isComplete = parts[3] == "true"
                        val lastAccessed = parts[4].toLongOrNull() ?: 0L
                        val ranges = if (parts[5].isBlank()) emptyList() else {
                            parts[5].split(",").mapNotNull { rStr ->
                                val bounds = rStr.split(":")
                                if (bounds.size == 2) {
                                    val start = bounds[0].toLongOrNull()
                                    val end = bounds[1].toLongOrNull()
                                    if (start != null && end != null) CachedByteRange(start, end) else null
                                } else null
                            }
                        }
                        val targetFile = File(cacheDir, "$keyHash.pcm")
                        if (targetFile.exists()) {
                            val fileLen = targetFile.length()
                            totalSizeBytes += fileLen
                            resources[key] = CacheResource(
                                key = key,
                                keyHash = keyHash,
                                contentLength = fileLen,
                                ranges = ranges,
                                isComplete = isComplete,
                                lastAccessedAt = lastAccessed,
                                file = targetFile
                            )
                            accessCounter = maxOf(accessCounter, lastAccessed)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Account for any unindexed .pcm files
        val files = cacheDir.listFiles() ?: return
        for (f in files) {
            if (f.isFile && f.name.endsWith(".pcm")) {
                val alreadyIndexed = resources.values.any { it.file.absolutePath == f.absolutePath }
                if (!alreadyIndexed) {
                    totalSizeBytes += f.length()
                }
            }
        }
    }

    fun clear() = lock.write {
        resources.clear()
        cacheDir.listFiles()?.forEach { it.delete() }
        totalSizeBytes = 0L
    }

    companion object {
        fun sha256Hex(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun mergeRanges(ranges: List<CachedByteRange>): List<CachedByteRange> {
            if (ranges.size <= 1) return ranges
            val sorted = ranges.sortedBy { it.startOffset }
            val merged = mutableListOf<CachedByteRange>()
            var current = sorted.first()

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                current = if (current.overlapsOrAbuts(next)) {
                    current.merge(next)
                } else {
                    merged.add(current)
                    next
                }
            }
            merged.add(current)
            return merged
        }
    }
}
