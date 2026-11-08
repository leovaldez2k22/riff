# Other players and playback startup

## What BitChord actually does

### Takeaway
BitChord combines persistent audio caching with modest queue read-ahead and short startup buffering. Its extractor code also contains explicit safeguards against duplicated resolution and expensive JavaScript contention. These are architectural examples, not evidence that BitChord is faster than Riff on the owner's phone.

### Cited Findings
- AudioCache wraps the resolving upstream, so a cached audio read can bypass URL resolution. The cache key distinguishes the media ID and rendition rather than using expiring CDN URLs. Its queue read-ahead waits eight seconds, fetches the immediate next track's first 1 MiB and then its remainder, and warms one further URL with 500 ms spacing. Queue changes cancel obsolete work; saved downloads are excluded. This is verified in executable code, not just comments. The limited depth and delayed start protect foreground playback from competing network/CPU work. The source comments contain developer-specific contention timings, which are not independently reproduced here. [AudioCache source, constants and prefetchQueue](https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/java/com/music/bitchord/playback/AudioCache.kt)
- PlaybackService sets a 500 ms startup buffer, 2,000 ms rebuffer threshold, 8 MiB target, 15-minute maximum forward duration and 30-second back buffer. Its media source uses AudioCache outside resolution. For optional module sources, it races module lookup against YouTube and can begin with the first usable source; a later quality upgrade uses a separate audition player to prove the replacement before swapping. Failed substitutes can be refused for that track. The resolution outer ceiling is 120 seconds: this is a worst-case timeout, not its typical startup. Comments referencing old Media3 defaults are not applicable to Riff's current dependency version. [PlaybackService source, farBufferingLoadControl, resolveWithModulePriority and constants](https://raw.githubusercontent.com/kushagrasinghx/BitChord/main/app/src/main/java/com/music/bitchord/playback/PlaybackService.kt)
- A browser-indexed StreamResolver snapshot coalesces work per videoId using an in-flight deferred map. Completion of the walk itself removes the entry, so cancellation of one caller cannot expose a still-running walk as absent. It preserves NewPipe player-JS caches, guards that shared state with a mutex, memoizes the signature timestamp for the process, and serializes extractor fallback work. It first attempts InnerTubeX and falls back to NewPipe. Comments explain asking only for audio streams avoids unnecessary transformations of video formats' n parameters; the posted timings are developer observations, not our benchmarks. IMPORTANT: this blob is an older indexed main snapshot (last-month crawl); its raw current path returned 404, so do not treat it as commit-pinned current implementation. [Indexed StreamResolver source](https://github.com/kushagrasinghx/BitChord/blob/main/app/src/main/java/com/music/bitchord/data/innertube/StreamResolver.kt)
- A similarly older PlayerClient snapshot includes unciphered-client paths as well as ciphered fallback clients; comments say results vary by network. It explicitly cautions that datacentre checks may differ from a real handset network. Treat client versions and eligibility as historical implementation evidence requiring fresh on-device validation. [Indexed PlayerClient source](https://github.com/kushagrasinghx/BitChord/blob/main/app/src/main/java/com/music/bitchord/data/innertube/PlayerClient.kt)

### Inferences
- The most transferable ideas are to serve cached audio before resolving, coalesce duplicate foreground/background requests, and protect foreground extraction from speculative queue work.
- A 500 ms media buffer does not promise 500 ms tap-to-sound; resolver, network, extraction and decoder costs precede it.
- Racing independent optional sources may reduce silence, but Riff should not blindly race CPU-heavy JS solvers. That may worsen contention.

### Gaps
- No same-device, same-song, cache-controlled latency measurements were conducted.
- No commit SHA could be established from the accessible web snapshots; raw main files are mutable and cached pages have differing crawl dates.
- BitChord's current raw StreamResolver moved or disappeared; the older blob supplies evidence about design history only. Current InnerTubeX internals and complete cipher lifetime were not independently inspected.

## What Metrolist does

### Takeaway
Metrolist is a useful current reference for expiry-aware URL reuse, exclusion of failing stream clients, and bounded initial-stall recovery. Its startup threshold is slightly shorter than Riff's present Media3 default, but those fractions of buffered media cannot explain away multi-second extraction.

### Cited Findings
- MusicService configures min/max 50,000/50,000 ms, startup 750 ms and rebuffer 2,000 ms. Its resolver checks download/player byte caches and its URL cache before extracting. It records the stream client, refreshes rejected URLs, tracks failed clients, and guards retries against a changed item, index, position or play state. It has an initial buffering watchdog and a three-retry per-song limit, with one-second retry delay. Startup preferences are batch-read to avoid repeated blocking preference reads. These are executable behaviors plus a source comment explaining the preference change. No broad explicit queue preloader was verified in this file. [MusicService source](https://raw.githubusercontent.com/MetrolistGroup/Metrolist/main/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt)
- StreamUrlCache is a synchronized LRU with 500 entries. Each entry retains URL, request headers, client and range policy. Expiry uses an absolute timestamp computed from the response's expiresInSeconds. Invalidation advances a generation; insertion checks the expected generation, so an earlier resolve cannot repopulate an entry invalidated during that resolve. Expired entries are removed. [StreamUrlCache source](https://raw.githubusercontent.com/MetrolistGroup/Metrolist/main/app/src/main/kotlin/com/metrolist/music/playback/StreamUrlCache.kt)
- InnerTubeXPlayer exposes prewarm, holds a reusable extraction bundle keyed by transport generation, and persists player-config JSON, timestamp, source URL and ETag in preferences. It excludes recently failed stream clients for five minutes. It explicitly disables HLS/SABR capabilities for this engine and rejects a SABR result. A persistent player-config cache is different from persisting executable solver/runtime state. [InnerTubeXPlayer source](https://raw.githubusercontent.com/MetrolistGroup/Metrolist/main/app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt)

### Inferences
- Cache invalidation needs generation checking and account/quality/network context; caching only a URL string is insufficient for dependable playback.
- Excluding incompatible protocols is preferable to obtaining a URL the downstream engine cannot consume. Riff supports SABR, so Metrolist's protocol decision should not be copied as a blanket restriction.
- A 750 ms threshold may be worth a controlled Riff experiment after resolver work; it is a smaller possible gain than moving seconds of resolution off the critical path.

### Gaps
- No empirical cross-app measurement; inspected raw main snapshots are mutable.
- prewarm exists as an API; its exact call timing and internal complete behavior were not verified.

## Other lineage and practical lesson

### Takeaway
InnerTune shows the common two-layer cache architecture, while its source also illustrates why expiry logic should be audited rather than transplanted.

### Cited Findings
- InnerTune's inspected dev service checks a download cache, player cache and URL map before a fresh YouTube player request. It reuses stored format itag where available and bounds stream reads by chunk length. Its map stores expiresInSeconds times 1000 yet compares that value with the epoch clock using a less-than condition. Those visible expressions are reason to audit expiry semantics carefully, not a template for correct expiry. [InnerTune MusicService source](https://raw.githubusercontent.com/z-huang/InnerTune/dev/app/src/main/java/com/zionhuang/music/playback/MusicService.kt)

### Inferences
- Independent implementation from behavior/specifications is appropriate; no third-party source code was copied into Riff.
- Investigate first unresolved selection, process-cold resume, warm selection, next-track transition and cache replay separately: these designs optimize different cases.

### Gaps
- Spotify and Apple Music internals are not open-source evidence here; no unsupported claims about their private streaming implementation.
- GitHub connector returned a 403 account-suspended error. Public web primary sources were used as the fallback. This does not affect the local Riff audit, but limits commit pinning.
