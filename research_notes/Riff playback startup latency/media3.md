# Media3 startup latency research

Researched 2 November 2026. Scope: official Media3 sources and Riff's checked-out integration. No playback measurement or implementation was performed.

## Buffer thresholds and what changing them can solve

### Takeaway
Riff uses Media3 1.11.1 (`gradle/libs.versions.toml:7`), and `riffLoadControl` only changes the back buffer (`VolumeBoostProcessor.kt:99-110`). Therefore the relevant startup default is **1,000 ms of buffered media**, not the 2,500 ms often repeated in old ExoPlayer advice. A 1-second media buffer is not a mandated 1-second wall-clock delay: it can download faster or slower than real time.

### Cited findings
- Version-pinned source defines network initial/user-seek buffer 1,000 ms, rebuffer recovery 2,000 ms, and forward minimum/maximum 50,000 ms. Local initial buffer is also 1,000 ms. `shouldStartPlayback` compares playout duration against the startup/rebuffer threshold, or permits starting when its byte target is reached with time prioritization disabled. The 50-second forward minimum concerns continued loading, not waiting to begin playback. `shouldContinuePreloading` declines preloading while a player is actively loading. [Media3 1.11.1 DefaultLoadControl, constants and lines 765-791](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)

### Inferences
- Lowering startup buffering to a trial 250-500 ms may reduce the post-first-byte part of startup on slow links, but cannot remove time spent before a media source has a playable URL. Keep a more conservative rebuffer threshold and evaluate underruns before adopting. These values are proposed experiment arms, not an official recommendation or measured result.
- Riff's five-minute back buffer primarily helps backwards seeks within the active player; it does not prefetch another track or persist bytes across process death.

### Gaps
The share of Riff's actual startup spent waiting for this threshold has not been measured here. Do not label buffer tuning the root-cause fix without a tap-to-audio trace.

## Preloading APIs already available in Riff's dependency

### Takeaway
Resolve-ahead, download-ahead and prepare-ahead are separate optimizations. Media3 provides both simple next-playlist-item preloading and a manager for ranked candidates. It cannot avoid application-specific YouTube token/cipher resolution unless that work is integrated into the source/preload path.

### Cited findings
- Media3 1.11.1 has `ExoPlayer.PreloadConfiguration(targetPreloadDurationUs)` and `setPreloadConfiguration`; the default disables playlist preloading. The unit is microseconds. This is the smaller-scope candidate for a known next track. [Version-pinned ExoPlayer source, lines 184-204 and 1557-1567](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/ExoPlayer.java)
- The preload manager ranks items by distance from the current selection. Its stages can prepare a source, select tracks, load a specified time range into memory, or cache a time range to disk. An official sample loads three seconds for the next item, one for the previous item, and less work for distant candidates. Its builder should also construct players, ensuring shared components. Caching stages require a configured cache. [Create/configure preload manager](https://developer.android.com/media/media3/exoplayer/preloading-media/preloadmanager/create)
- `DefaultPreloadManager` in version 1.11.1 is in `androidx.media3.exoplayer.source.preload`, buffers using `PreloadMediaSource` and `SampleQueue`, and supplies the builder. This functionality is already in Riff's ExoPlayer dependency rather than requiring an unsupported future version. [Version-pinned manager source](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.java)
- Google distinguishes playlist preloading (useful when skipping before the normal playback buffer reaches the next item) from manager-based preloading for dynamic feeds or carousels. [Official preloading introduction](https://developer.android.com/blog/posts/elevating-media-playback-introducing-preloading-with-media3-part-1)

### Inferences
- Prefer a bounded next-track strategy before preparing every visible card. Test built-in playlist preloading with Riff's catalog source and deferred URL resolution; it may remove duplicate ad-hoc warmup code if integrated correctly.
- A known next track can have a valid URL but no cached audio bytes. Conversely, cached audio needs correct source/cache identity. Track each layer's hit rate separately.
- Preloading will not directly help a completely unpredicted first tap with a cold resolver. Do not present seamless next-track behavior as proof that cold tap startup is solved.
- Check custom SABR and Automix source/player behavior before adopting the manager; compatibility has not been established by this research.

### Gaps
No compilation or end-to-end integration trial was run. Exact retention, cancellation and resource ownership must be designed against Riff's custom media sources and dual-player Automix path.

## Network connections and persistent byte caching

### Takeaway
Sharing clients and caching bytes prevent repeated network setup and downloads; they are complementary to valid-URL caching and sample preloading.

### Cited findings
- Android recommends a single app-wide network stack and singleton `HttpEngine`, `CronetEngine` or `OkHttpClient` so sockets and resources can be pooled. Current docs recommend HttpEngine from API 34/S extension 7, with Cronet/OkHttp alternatives. Disk byte caching uses singleton `SimpleCache` and `CacheDataSource.Factory`, usually with LRU eviction for an on-the-fly cache. [Official network stacks and cache guidance](https://developer.android.com/media/media3/exoplayer/network-stacks)

### Inferences
- First measure DNS, connection establishment and TLS separately. A network-stack migration adds integration work and will not fix a token/cipher bottleneck. Shared OkHttp infrastructure may be the lower-risk path if Riff already uses it widely.
- Use a format-aware stable cache identity (for example media identity plus format/representation) rather than treating expiring signed URLs as permanent content identities. Verify byte-range consistency, download integrity, account changes and fallback formats before reusing bytes.
- Give foreground playback priority over analysis, image fetches, speculative audio and downloads; cancel work for discarded queue entries. Warm only a bounded prefix, respecting the owner's existing requirement to ask before downloads.

### Gaps
This research does not establish whether Riff currently lacks any of these mechanisms; the local-code researcher covers that audit. It also does not quantify a benefit from changing HTTP stacks.

## What first audio actually measures

### Takeaway
`docs/quality/performance.md:109-114` says its UI-frame-after-`isPlaying` endpoint is never earlier than the first sample leaving the speaker buffer. **That guarantee is unsupported and should be corrected.** Keep the metric, but label it tap-to-playing-state-visible. Add a separate audio-position-advancing metric and physical audible-onset validation where user-perceived delay matters.

### Cited findings
- Media3 defines `isPlaying` from READY, `playWhenReady=true` and no playback suppression. This is player state, not an acoustic measurement or physical-output callback. [Official player events](https://developer.android.com/media/media3/exoplayer/listening-to-player-events)
- `onAudioPositionAdvancing` reports the first audio-position increase since pause/reset and supplies an approximate derived wall-clock playout-start time. [AnalyticsListener API](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener)
- The sink-level callback's derived timestamp is only valid if the track has not underrun. [AudioSink.Listener API](https://developer.android.com/reference/androidx/media3/exoplayer/audio/AudioSink.Listener#onPositionAdvancing(long))
- Media3's audio position tracker estimates position from AudioTrack head position/timestamps and may subtract mixer/hardware latency; it contains device timestamp/latency safeguards and smoothing. This is an estimate, not a microphone measurement. [1.11.1 AudioTrackPositionTracker](https://github.com/androidx/media/blob/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioTrackPositionTracker.java)

### Inferences and recommended experiment
Riff already logs both `onIsPlayingChanged` and `onAudioPositionAdvancing` in `StartupDiagnostics.kt:198-211`, with a monotonic timestamp for the event and a wall-clock derived timestamp as its count. Use the monotonic event marks for ordinary stage comparisons; do not subtract raw `currentTimeMillis` values from `elapsedRealtime` values. If the derived start estimate is used, record a deliberate mapping between clocks.

Build a per-tap waterfall: tap -> dispatch/controller connection -> queue apply -> resolver start -> token/cipher/config completion -> playable source -> request headers -> first media byte -> decoder initialized -> READY/isPlaying -> audio position advancing. Correlate each operation and process rather than summing overlapping spans. Riff's diagnostic comments say request index is process-local and recommend one deliberate tap per run; cross-process spans need monotonic timestamps and process identity until correlation is strengthened.

Measure the same tracks across these cells: fresh process/cold resolver; warm process/cold media bytes; valid URL hit; prefetched prefix hit; downloaded local song; signed in versus signed out; Wi-Fi versus mobile/high RTT/loss; direct versus SABR fallback; speaker versus Bluetooth; Automix on versus off. Distinguish first tap, rapid skips and natural transitions. Avoid clearing the owner's own app or changing account state without authorization.

Collect distributions (p50/p95), startup failures and rebuffering in the first 30 seconds, not only successful average startup. Count cache hits, cancelled stale work, bytes prefetched, CPU and thermal state. Compare changes in matched runs with the same queue, phone and connection. Use a known audible-onset fixture or loopback/microphone method for end-to-end sound onset; a song beginning with silence confounds subjective timing. Bluetooth route latency can add audible delay after app events, so report it separately. These are proposed methodology, not measured outcomes.

### Gaps
No official source promises exact ordering between a UI frame showing `isPlaying` and physical speaker onset. The current documentation's never-earlier assertion must therefore not support the release's audible-start claim. Even the more suitable advancing callback remains approximate; route-specific acoustic timing was not measured.

## Priority adjustment from local timing evidence

The coordinator reports an existing cold-restored force-stop sample with total startup 7.16 s, resolver 6.22 s, solver RPC 4.68 s; five warm samples have median 0.669 s and an extended-idle sample is 0.561 s. These are local artifact observations, not web-source claims, and the coordinator must link the originating evidence. They make resolver/solver cold-path optimization the first priority. Buffer tuning ranks lower and should only follow a stage trace demonstrating material post-first-byte wait. One forced cold-start example cannot establish natural foreground-idle behavior or p95.
