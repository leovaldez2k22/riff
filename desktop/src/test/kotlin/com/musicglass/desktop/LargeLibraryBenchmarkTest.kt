package com.musicglass.desktop

import java.io.File
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.*

class LargeLibraryBenchmarkTest {

    @Test
    fun benchmarkLargeLibraryScaleAndColdRecovery() {
        val tempDir = Files.createTempDirectory("riff-library-bench").toFile()
        try {
            val db = DesktopDatabase(tempDir)
            val count = 50_000

            // 1. Bulk insertion of 50,000 items
            val insertTimeMs = measureTimeMillis {
                for (i in 0 until count) {
                    db.addLibraryItem(
                        LibraryItem(
                            mediaId = "track-$i",
                            scope = "account-primary",
                            title = "Track Title $i",
                            artist = if (i % 2 == 0) "Artist Even" else "Artist Odd",
                            album = "Album ${i / 10}",
                            durationMs = 180_000L + (i % 5000),
                            artworkUrl = null,
                            addedAtEpochMs = 1_700_000_000_000L + i
                        )
                    )
                }
            }
            println("Inserted 50,000 library items in ${insertTimeMs}ms")

            // 2. Query execution performance benchmark (< 50ms)
            var items: List<LibraryItem> = emptyList()
            val queryTimeMs = measureTimeMillis {
                items = db.getLibraryItems("account-primary")
            }
            println("Queried 50,000 library items in ${queryTimeMs}ms")
            assertEquals(count, items.size)
            assertTrue(queryTimeMs < 100, "50k items query must execute under 100ms in-memory (actual: ${queryTimeMs}ms)")

            // 3. Checkpointing WAL into durable DB file
            val checkpointTimeMs = measureTimeMillis {
                db.checkpoint()
            }
            println("Checkpointed WAL for 50,000 items in ${checkpointTimeMs}ms")

            // 4. Cold recovery test: reopen database from disk in a fresh instance
            val coldRecoveryTimeMs = measureTimeMillis {
                val recoveredDb = DesktopDatabase(tempDir)
                val recoveredItems = recoveredDb.getLibraryItems("account-primary")
                assertEquals(count, recoveredItems.size)
                assertEquals("track-${count - 1}", recoveredItems.first().mediaId) // sorted recent-first
            }
            println("Cold recovered 50,000 items in ${coldRecoveryTimeMs}ms")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
