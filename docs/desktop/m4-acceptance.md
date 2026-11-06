# M4 Acceptance Evidence — Local Database, Audio Cache & Artwork Pipeline

Status: Substantially Complete on Local Hardware; Automated Unit and Storage Verification Passing.

## 1. Implemented & Integrated Deliverables

1. **Transactional Database Engine with Schema Migrations (`DesktopDatabase.kt`)**:
   - Versioned schema migrations (`schemaVersion = 2`) with automatic migration on startup.
   - Account-scoped persistence for `LibraryItem`, `PlaylistRecord`, `HistoryEvent`, `EncoreStatsRecord`, and `PlaybackSnapshot`.
   - Write-Ahead Logging (WAL) with transactional commit markers and checkpointing into `library-state.db`.
   - Cold crash recovery verified from WAL logs across process restarts.

2. **Cryptographic Audio Cache with Persistent Manifest (`DesktopAudioCache.kt`)**:
   - Collision-resistant file naming using SHA-256 hex hashes (`$keyHash.pcm`).
   - Sparse contiguous byte-range coverage tracking (`CachedByteRange`) supporting partial segment reads, merging, and completeness validation.
   - Strict 256 MiB LRU disk cache quota with automatic eviction of least-recently-accessed audio segments.
   - Persistent restart recovery manifest (`cache-manifest.txt`): preserves all byte ranges, completion status, and LRU timestamps across application restarts.

3. **Dual-Tier Byte-Bounded Artwork Pipeline (`DesktopArtPipeline.kt`)**:
   - Memory cache bounded by decoded bitmap byte size (`width * height * 4` bytes, 32 MiB budget) and entry limits.
   - Disk cache bounded by 64 MiB byte budget with SHA-256 keyed serialization (`$keyHash.art`).
   - Single-flight mutex deduplication per key preventing redundant decodes.
   - Deterministic 5-cluster k-means palette extraction and 6×6 Skia mesh derivation.

4. **Desktop Host Database Integration (`DesktopHost.kt`)**:
   - Resumes last playback track and timeline position from `PlaybackSnapshot` on startup.
   - Records `HistoryEvent` transactionally when tracks begin playing.
   - Persists state and performs WAL checkpoints on application close / quit.

## 2. Automated Checks

- **Desktop Unit Tests (`StorageAndCacheContractTest`, `LargeLibraryBenchmarkTest`)**:
  - `databaseRecordsHistoryAndPerformsWalCheckpoint`: PASS
  - `databasePersistsLibraryPlaylistsAndEncoreStats`: PASS
  - `audioCacheWritesReadsAndEnforcesQuotas`: PASS
  - `audioCacheUsesSha256HashingAndTracksByteRanges`: PASS
  - `artPipelineLoadsAndCachesDecks`: PASS
  - `benchmarkLargeLibraryScaleAndColdRecovery`: PASS (50,000 items bulk insertion, query execution < 50ms, WAL checkpointing, cold recovery)
- **Desktop Total Test Suite**: **57 passing unit tests, 0 failures, 0 skipped**.

## 3. Manual Checks & Observations

- Verified SHA-256 hashed filenames generated in cache directory during audio caching.
- Verified contiguous range merging: writing 0..999 and 1000..1999 merges into single 0..1999 byte range.
- Verified derived color decks survive memory eviction by reloading cached mesh from disk.

## 4. Outstanding Hardware Gates

- **[Other hardware]** 50,000-item library stress testing on external slow HDD/USB drives.
