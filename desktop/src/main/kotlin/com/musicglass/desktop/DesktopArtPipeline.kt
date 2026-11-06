package com.musicglass.desktop

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Decoded artwork and derived color deck model. */
internal data class ArtworkDeck(
    val key: String,
    val dominantColor: Int,
    val meshColors: IntArray,
    val width: Int,
    val height: Int
) {
    val estimatedMemoryBytes: Long get() = (width.toLong() * height.toLong() * 4L) + 256L

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArtworkDeck) return false
        return key == other.key &&
               dominantColor == other.dominantColor &&
               meshColors.contentEquals(other.meshColors)
    }
    override fun hashCode(): Int = key.hashCode()
}

/**
 * Dual-tier memory/disk LRU artwork pipeline with single-flight prefetching,
 * byte-bounded memory/disk budgets, and deterministic color deck derivation (Section 5.2).
 */
internal class DesktopArtPipeline(
    private val diskCacheDir: File,
    private val maxMemoryBytes: Long = 32L * 1024 * 1024, // 32 MiB
    private val maxDiskBytes: Long = 64L * 1024 * 1024,    // 64 MiB
    private val maxMemoryEntries: Int = 100
) {
    private val memoryCache = LinkedHashMap<String, ArtworkDeck>(maxMemoryEntries, 0.75f, true)
    private var currentMemoryBytes = 0L
    private var currentDiskBytes = 0L
    private val inFlightMutexes = ConcurrentHashMap<String, Mutex>()

    init {
        diskCacheDir.mkdirs()
        scanDiskCache()
    }

    /**
     * Retrieves or derives an artwork deck for a given track key.
     * Guaranteed single-flight per key to prevent redundant concurrent decodes.
     * Checks memory cache -> disk cache -> deterministic computation.
     */
    suspend fun loadDeck(key: String, rawPixels: IntArray, width: Int, height: Int): ArtworkDeck {
        synchronized(memoryCache) {
            val cached = memoryCache[key]
            if (cached != null) return cached
        }

        val mutex = inFlightMutexes.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            // Re-check memory under lock
            synchronized(memoryCache) {
                val cached = memoryCache[key]
                if (cached != null) return@withLock cached
            }

            // Check disk cache
            val diskDeck = readFromDisk(key)
            if (diskDeck != null) {
                putInMemory(diskDeck)
                return@withLock diskDeck
            }

            // Derive deterministic palette and 6x6 mesh
            val dominant = dominantArtworkRgb(rawPixels)
            val mesh = deckMesh(meshColors(rawPixels,width,height))

            val deck = ArtworkDeck(
                key = key,
                dominantColor = dominant,
                meshColors = mesh,
                width = width,
                height = height
            )

            putInMemory(deck)
            writeToDisk(deck)

            inFlightMutexes.remove(key)
            deck
        }
    }

    fun getMemoryCached(key: String): ArtworkDeck? = synchronized(memoryCache) {
        memoryCache[key]
    }

    private fun putInMemory(deck: ArtworkDeck) = synchronized(memoryCache) {
        val entryBytes = deck.estimatedMemoryBytes
        while ((currentMemoryBytes + entryBytes > maxMemoryBytes || memoryCache.size >= maxMemoryEntries) && memoryCache.isNotEmpty()) {
            val eldestKey = memoryCache.keys.firstOrNull() ?: break
            val removed = memoryCache.remove(eldestKey)
            if (removed != null) {
                currentMemoryBytes = maxOf(0L, currentMemoryBytes - removed.estimatedMemoryBytes)
            }
        }
        memoryCache[deck.key] = deck
        currentMemoryBytes += entryBytes
    }

    private fun writeToDisk(deck: ArtworkDeck) {
        try {
            val keyHash = sha256Hex(deck.key)
            val file = File(diskCacheDir, "$keyHash.art")
            val prevLen = if (file.exists()) file.length() else 0L

            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeUTF(deck.key)
                out.writeInt(deck.dominantColor)
                out.writeInt(deck.width)
                out.writeInt(deck.height)
                out.writeInt(deck.meshColors.size)
                for (c in deck.meshColors) out.writeInt(c)
            }

            currentDiskBytes += (file.length() - prevLen)
            evictDiskIfNecessary()
        } catch (_: Exception) {}
    }

    private fun readFromDisk(key: String): ArtworkDeck? {
        val keyHash = sha256Hex(key)
        val file = File(diskCacheDir, "$keyHash.art")
        if (!file.exists()) return null

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val readKey = input.readUTF()
                val dominant = input.readInt()
                val w = input.readInt()
                val h = input.readInt()
                val meshCount = input.readInt()
                val mesh = IntArray(meshCount)
                for (i in 0 until meshCount) mesh[i] = input.readInt()

                // Update file timestamp for LRU
                file.setLastModified(System.currentTimeMillis())
                ArtworkDeck(readKey, dominant, mesh, w, h)
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun evictDiskIfNecessary() {
        val files = diskCacheDir.listFiles { _, name -> name.endsWith(".art") } ?: return
        if (currentDiskBytes <= maxDiskBytes) return

        val sortedFiles = files.sortedBy { it.lastModified() }
        for (f in sortedFiles) {
            if (currentDiskBytes <= maxDiskBytes) break
            val len = f.length()
            if (f.delete()) {
                currentDiskBytes = maxOf(0L, currentDiskBytes - len)
            }
        }
    }

    private fun scanDiskCache() {
        val files = diskCacheDir.listFiles { _, name -> name.endsWith(".art") } ?: return
        for (f in files) {
            currentDiskBytes += f.length()
        }
    }

    fun clear() {
        synchronized(memoryCache) {
            memoryCache.clear()
            currentMemoryBytes = 0L
        }
        diskCacheDir.listFiles()?.forEach { it.delete() }
        currentDiskBytes = 0L
        inFlightMutexes.clear()
    }

    companion object {
        private fun sha256Hex(input: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
