# Riff rc11: offline downloads, startup and official videos

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Version 0.9.0-rc11, version code 12. October 1, 2026. Android 12/API 31 remains the minimum.

## Official videos

Video lookup first checks YouTube Music's paired video, then candidates classified as official music videos. YouTube metadata must match the whole recording title and an artist-owned channel, including VEVO/canonical artist identities. Artist prefixes/suffixes and presentation labels are allowed. Lyrics, audio, visualizer, karaoke, cover, fan uploads, medleys and unintended live/remix recordings are rejected. Successful lookup without a match shows “No official music video is available”; network failure is reported separately. Audio continues if lookup fails. Video renders natively with app-owned controls on black and preserves the queue/position; switching back to audio checks permanent downloads first.

## Downloads and recovery

The referenced “Assess offline music downloads” chat was reviewed. Its durable bundle approach is implemented: original audio, high-resolution artwork, serialized timed lyrics with attribution, and catalog metadata. Existing InnerTubeX supplies exact-ID highest-quality audio; NewPipe was not added because the complete direct/SABR download proof succeeded with the current integration. No search-by-title substitutes a different recording.

SQLite tracks queued/downloading/assets/complete/failed/paused states, bytes, quality, pinned manual downloads, playback history, account scope and user removals. Each account has an opaque namespace. Temporary audio is validated for contiguous bytes and full duration, remuxed without re-encoding into an indexed container, then committed with a length marker. Interrupted transfers restart safely with fresh stream resolution and bounded retries. Startup recovery requeues unfinished jobs; completed audio is retained when retrying missing assets. Invalid or incomplete files are never offered as offline audio. Better Lyrics remains first priority; permanent lyrics are read before temporary cache or network. Missing/failed artwork or lyrics are visible independently of usable audio.

Download song is available in song menus; Library and Settings open Downloads. The manager supports progress, pause, retry/keep, removal and clearing temporary playback cache. Manual work uses an Android data-sync foreground service and deferred retry job; automatic work uses constrained JobScheduler jobs. Bundles live in app-private permanent storage, with no timed expiry. Uninstalling removes them.

## Smart Downloads

The saved toggle defaults off. Membership is the newest 250 unique qualifying songs. Qualifying listening requires at least 30 seconds or half the song for shorter songs. Recency, capped repeat plays, completion and favorites rank download priority. Podcasts, live streams, songs longer than 20 minutes and explicitly removed downloads are excluded. Authored queues and listening-history settings remain respected.

Automatic downloads require unmetered Wi-Fi and at least 20% battery, pause while playback is buffering/playing, and respect battery/storage job constraints. Budgets are 2, 4, 8 or 16 GB; default 4 GB. At least 1 GB or 10% of total storage remains free. The rolling automatic selection can prune older automatic bundles; manual downloads are retained. Disabling automatic work pauses pending smart jobs and keeps completed bundles. Storage, history and source availability can result in fewer than 250 songs; quality is never reduced to meet the quota.

## Playback and cache

Song selection sends catalog metadata directly to the player. Only the selected source is resolved on demand; queue recommendations add metadata instead of eagerly extracting several tracks. Lyrics and recommendation requests begin after the player is actually ready and playing. The provider is shared across UI/service/downloads, prewarms extraction, coalesces concurrent requests and keeps at most 32 valid stream results in RAM with expiry margins. Live streams always resolve fresh. Expired URLs and playback failures invalidate stream entries. Signed URLs are never persisted into catalog/download metadata.

A 256 MB LRU Media3 disk cache uses stable account/recording/format keys for audio. Finite SABR song streams are cached even though their byte length is initially unknown. Live/video bypass the audio cache; HLS does not receive an unsupported custom MediaItem cache key. Cached audio is opportunistic and can be partial; permanent downloads are the offline guarantee. Catalog metadata and listening factors are persisted independently. Player collapse dragging is limited to the artwork when scrolling is unnecessary, leaving seek/volume controls usable.

## Verification

The signed release passed 81 unit tests with no failures, errors or skips. Android lint reported zero errors and 56 warnings (53 existed before this change). Tests cover whole-recording/official-owner matching, expiry/live cache policy, recent-250 selection, eligibility and storage reserve. Instrumentation checks download container seeking, artwork/word timing, interrupted-job recovery and preserving existing audio during asset retry. Test instrumentation is a separate APK and is not bundled into the release.

On the physical iQOO I2217/Android 16, Numb downloaded as a complete 2,930,371-byte original-quality WebM audio file in about 9.9 seconds, with saved artwork and Better Lyrics word timing. MediaExtractor successfully sought to 131.6 seconds and read samples. With Wi-Fi and mobile data disabled, after force-stopping/reopening the app, playback started in 829 ms and artwork/word-synced lyrics remained available. Network settings were restored afterward. The artist's official video ID was kXYiU_JCYtU and the native player displayed it correctly. The queue remained intact when switching audio/video. A slider drag reached 148.3 seconds without collapsing the player.

In the End's online first-start measurement was 1,355 ms and repeat start 630 ms in the tested session. These are individual device measurements, not a guarantee for every song/network. Highest quality means the best stream exposed by the successful provider/account response; no fixed bitrate, Premium tier or lossless guarantee is inferred. The Smart Downloads settings screen showed the toggle off and the 4 GB default. Recovery and foreground asset retry passed without deleting audio. A component check cached 262,144 bytes from a finite unknown-length source and reused them across two session URIs with only one upstream open; its test cache entry was removed afterward. Real streaming playback populated 1.0 MB of the persistent audio cache after the SABR cache-flag correction. The final live-radio regression reported PLAYING for Lofi Girl using native YouTube HLS after the cache changes. Android 12 compatibility is retained but this feature's physical playback tests used Android 16.

Evidence is retained on the development computer under artifacts/offline-rc11. Early failed/aborted debugging attempts remain chronological evidence and are superseded only by the corresponding later passing checks. In particular, isolated video extraction instrumentation was interrupted; official matching was subsequently tested separately and native playback verified through the actual activity. The automatic 250-song workload was not enabled on the user's phone during checks.

## References

- [Media3 caching and source customization](https://developer.android.com/media/media3/exoplayer/customization)
- [Media3 DataSpec cache flags](https://developer.android.com/reference/androidx/media3/datasource/DataSpec)
- [Media3 CacheDataSink](https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSink)
- [Android background transfer options](https://developer.android.com/develop/background-work/background-tasks/uidt)

Corresponding source and GPL notices accompany the release. Existing extractor/token-runtime attribution remains in NOTICE.md; no NewPipe dependency was introduced.

## Final handoff

The signed rc11 APK is installed and copied to `C:\Users\KIIT0001\Downloads\youtube-music-glass-0.9.0-rc11.apk`; its SHA-256 is `0ccebe632b920305f31d69ef332fc7849819b4eb68f90a60612b5869c02dbc13`. The Downloads copy matches the release artifact byte for byte. APK Signature Scheme v2 verifies with one signer.

The final official-video UI displayed Switch to audio while retaining the seven-song queue. After switching back and restarting, Numb was ready paused in the mini player with the seven-song queue. Wi-Fi and mobile data remained enabled as before testing. The exact temporary phone capture paths `/sdcard/Download/riff-rc9-debug.xml` and `/sdcard/Download/riff-rc9-debug.png` have no remaining files; the standalone `com.musicglass.personal.test` package was uninstalled. The phone was locked at handoff: wakefulness Dozing, keyguard showing=true.
