# Desktop M4 implementation — Local database, audio cache and artwork pipeline

The owner authorized implementation on 1–2 November 2026. The desktop module remains an additive opt-in exception to the Android-only module rule (`:desktop` under `-PriffDesktop=true`); Android `:app` and `:benchmark` retain their boundaries.

## Implemented in M4

1. **Local State Database & WAL Engine (`DesktopDatabase.kt`)**:
   - Conforms to Section 5.1 and 5.3 of `reports/Riff Windows desktop architecture.md`.
   - Dual-file transactional storage: main database file (`library-state.db`) and write-ahead log (`library-state.db-wal`).
   - Append-only WAL with atomic commit records (`COMMIT`).
   - Periodic checkpointing truncates WAL and writes consolidated state into the primary file.
   - Robust crash recovery on initialization by replaying uncheckpointed WAL records.
   - Scope-isolated storage for `history_events` (Encore statistics, listening history) and `playback_snapshots` (queue recovery, resume positions).
   - Sanitized persistence: strictly excludes stream URLs, authorization cookies, and volatile tokens.

2. **LRU Audio Segment & Disk Cache (`DesktopAudioCache.kt`)**:
   - 256 MiB LRU audio disk cache managing random-access byte ranges.
   - Replaces Media3 SimpleCache semantics without leaking expiring YouTube Music stream URLs.
   - Deterministic sequence-counter LRU access tracking (`accessCounter`) with automatic quota-enforced eviction.
   - Atomic segment validation: enforces complete stream checks before marking tracks fully cached.

3. **High-Performance Artwork Pipeline (`DesktopArtPipeline.kt`)**:
   - Dual-tier caching: in-memory LRU (`ArtworkDeck`) and durable disk cache for downloaded artwork bitmaps.
   - Single-flight deduplication prevents concurrent redundant network requests for identical artwork keys.
   - Deterministic 6×6 Skia mesh extraction: calculates color gradient anchors (`meshColors`) directly from artwork pixels for real-time backdrop glass distortion.
