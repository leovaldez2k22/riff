package com.musicglass.desktop

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class StorageAndCacheContractTest {

    @Test
    fun databaseRecordsHistoryAndPerformsWalCheckpoint() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "riff-db-test-" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val db = DesktopDatabase(tempDir)
            val event1 = HistoryEvent(
                scope = "personal",
                eventId = "ev-1",
                mediaId = "track-1",
                title = "Cinnamon Girl",
                artist = "Lana Del Rey",
                artworkUrl = null,
                playedAt = 1000L,
                durationMs = 218000L
            )
            db.recordHistory(event1)

            val recent = db.getRecentHistory("personal")
            assertEquals(1, recent.size)
            assertEquals("track-1", recent[0].mediaId)

            val snapshot = PlaybackSnapshot(
                scope = "personal",
                currentTrackId = "track-1",
                positionMs = 12000L,
                queueTrackIds = listOf("track-1", "track-2"),
                updatedAt = 1050L
            )
            db.saveSnapshot(snapshot)
            assertEquals(12000L, db.getSnapshot("personal")?.positionMs)

            // Perform checkpoint: writes from WAL into main DB
            db.checkpoint()
            val walFile = File(tempDir, "library-state.db-wal")
            assertEquals(0L, walFile.length(), "WAL must be truncated after checkpoint")

            // Test recovery from cold files
            val coldDb = DesktopDatabase(tempDir)
            assertEquals(1, coldDb.getRecentHistory("personal").size)
            assertEquals("track-1", coldDb.getSnapshot("personal")?.currentTrackId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun databasePersistsLibraryPlaylistsAndEncoreStats() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "riff-db-full-" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val db = DesktopDatabase(tempDir)

            // Add Library Item
            val item = LibraryItem(
                mediaId = "song-101",
                scope = "work",
                title = "Starboy",
                artist = "The Weeknd",
                album = "Starboy",
                durationMs = 230000L,
                artworkUrl = "https://example.com/starboy.jpg",
                addedAtEpochMs = 1700000000L
            )
            db.addLibraryItem(item)

            val library = db.getLibraryItems("work")
            assertEquals(1, library.size)
            assertEquals("song-101", library[0].mediaId)

            // Save Playlist
            val playlist = PlaylistRecord(
                playlistId = "pl-1",
                scope = "work",
                title = "Favorites",
                trackIds = listOf("song-101", "song-102"),
                updatedAtEpochMs = 1700001000L
            )
            db.savePlaylist(playlist)
            val playlists = db.getPlaylists("work")
            assertEquals(1, playlists.size)
            assertEquals(2, playlists[0].trackIds.size)

            // Save Encore Stats
            val stats = EncoreStatsRecord(
                scope = "work",
                periodKey = "2026-10",
                totalListeningSeconds = 36000L,
                topTrackIds = listOf("song-101"),
                topArtistNames = listOf("The Weeknd"),
                updatedAtEpochMs = 1700002000L
            )
            db.saveEncoreStats(stats)
            val retrievedStats = db.getEncoreStats("work", "2026-10")
            assertNotNull(retrievedStats)
            assertEquals(36000L, retrievedStats.totalListeningSeconds)

            // Cold recovery check
            db.checkpoint()
            val coldDb = DesktopDatabase(tempDir)
            assertEquals(1, coldDb.getLibraryItems("work").size)
            assertEquals(1, coldDb.getPlaylists("work").size)
            assertEquals(36000L, coldDb.getEncoreStats("work", "2026-10")?.totalListeningSeconds)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun audioCacheWritesReadsAndEnforcesQuotas() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "riff-cache-test-" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            // 1 KB max cache for testing eviction
            val cache = DesktopAudioCache(tempDir, maxSizeBytes = 1024L)
            val data1 = ByteArray(600) { 1 }
            val complete1 = cache.writeData("track-1", 0L, data1, totalLength = 600L)
            assertTrue(complete1)
            assertTrue(cache.isCached("track-1"))

            // Verify read
            val readBytes = cache.readData("track-1", 0L, 100)
            assertNotNull(readBytes)
            assertEquals(100, readBytes.size)

            // Writing second block that exceeds 1 KB total forces eviction of oldest
            val data2 = ByteArray(600) { 2 }
            cache.writeData("track-2", 0L, data2, totalLength = 600L)

            // Track 1 should be evicted to honor 1 KB quota
            assertFalse(cache.isCached("track-1"))
            assertTrue(cache.isCached("track-2"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun audioCacheUsesSha256HashingAndTracksByteRanges() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "riff-sha256-test-" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val cache = DesktopAudioCache(tempDir, maxSizeBytes = 10L * 1024 * 1024)
            val key = "scope:spotify_yt_12345:high_aac"
            val keyHash = DesktopAudioCache.sha256Hex(key)

            // Write first 1000 bytes
            val chunk1 = ByteArray(1000) { 0x42 }
            val completed1 = cache.writeData(key, 0L, chunk1, totalLength = 2000L)
            assertFalse(completed1, "Resource is not complete yet")

            // Verify file name uses SHA-256 hash
            val targetFile = File(tempDir, "$keyHash.pcm")
            assertTrue(targetFile.exists(), "Cache file must be named using SHA-256 hex hash")
            assertEquals(1000L, targetFile.length())

            val res = cache.getResource(key)
            assertNotNull(res)
            assertEquals(1, res.ranges.size)
            assertEquals(0L, res.ranges[0].startOffset)
            assertEquals(999L, res.ranges[0].endOffset)
            assertTrue(res.hasCoverage(0L, 500))
            assertFalse(res.hasCoverage(500L, 1000))

            // Write second contiguous chunk 1000..1999 to complete the resource
            val chunk2 = ByteArray(1000) { 0x43 }
            val completed2 = cache.writeData(key, 1000L, chunk2, totalLength = 2000L)
            assertTrue(completed2, "Resource must be complete after writing all 2000 bytes")
            assertTrue(cache.isCached(key))
            assertEquals(1, cache.getResource(key)?.ranges?.size, "Contiguous ranges must be merged")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun artPipelineLoadsAndCachesDecks() {
        runBlocking {
            val tempDir = File(System.getProperty("java.io.tmpdir"), "riff-art-test-" + System.currentTimeMillis())
            tempDir.mkdirs()
            try {
                val pipeline = DesktopArtPipeline(tempDir, maxMemoryEntries = 2)
                val pixels = IntArray(36) { 0xFF123456.toInt() }

                val deck1 = pipeline.loadDeck("art-1", pixels, 6, 6)
                assertEquals("art-1", deck1.key)
                assertEquals(36, deck1.meshColors.size)
                assertSame(deck1, pipeline.getMemoryCached("art-1"))

                // Fill memory cache to test LRU eviction
                pipeline.loadDeck("art-2", pixels, 6, 6)
                pipeline.loadDeck("art-3", pixels, 6, 6)

                // art-1 should be evicted from memory
                assertNull(pipeline.getMemoryCached("art-1"))
                assertNotNull(pipeline.getMemoryCached("art-3"))

                // Loading art-1 again should reload from disk cache without failing
                val reloadedDeck1 = pipeline.loadDeck("art-1", pixels, 6, 6)
                assertEquals("art-1", reloadedDeck1.key)
                assertEquals(deck1.dominantColor, reloadedDeck1.dominantColor)
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
