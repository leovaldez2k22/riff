# Android playback startup and warm-resume audit

4 November 2026. Scope: Android `:app`; existing diagnostic and desktop changes in the working tree are preserved. This records the requested audit → reference research → hypothesis verification → implementation sequence. Code defects are distinguished from measured causes of the owner's particular device failure. No screen acceptance, account changes, installation, push or publication was performed.

## 1. Pipeline traced before changes

Paths below are relative to `app/src/main/java/com/musicglass/personal/`. Original line numbers refer to the working tree at the start of this investigation, which already contained uncommitted startup diagnostics; they are not claims about HEAD. Current entry points are listed in section 4.

| Stage | Original location | Observed behavior |
|---|---|---|
| Song selection | `ScreenParts.kt:95–107`, `playRealTrack`; `PlaybackGateway.kt:353–400`, `sendCatalogQueue` | Ordinary songs immediately dispatch lightweight catalog metadata. Queue JSON encoding runs on an IO lane; no stream extraction precedes dispatch. Explicit video/live selection still resolves before dispatch. |
| Queue application | `QueueCoordinator.kt:115`, `catalogItem`; `:206`, catalog queue application | `yt:` media ID and `catalog://` URI identify entries. Set items, prepare and set play intent without waiting for extraction. |
| Session callbacks | `MusicPlaybackService.kt:326–332` | Own-controller add/set callbacks delegate to Media3. Car requests translate library IDs into catalog entries; these callbacks do not extract signed stream URLs. |
| Lazy resolution | `PlaybackSources.kt:73–107`; `DeferredProviderSource.kt:23–43` | `CompositeMediaSource` launches cancellable resolution during preparation, then selects file, progressive, SABR or HLS child source. This was already lazy, though it was not a `ResolvingDataSource`. Following sources could compete for token/cipher work before selected audio started. |
| Extraction/cache | `ProviderStreamResolver.kt:160–181`, `resolve`; `:200–252`, extractor; `MusicCachePolicy.kt:3` | RAM cache, max 32 entries, keyed by auth revision, track, mode, download/quality and stream revision. Single-flight extraction. TTL capped at 20 minutes with a 60-second safety margin; live results not reused. URLs are not durably persisted. |
| Invalidation | `ProviderStreamResolver.kt:189` | Removed completed cache entries only. A matching in-flight request remained joinable and could write its old result back after invalidation. |
| Byte sources | `PlaybackSources.kt:120–133`; `SabrPlaybackTransport.kt:46`; `OkHttpStreamDataSource.kt:38` | `CacheDataSource → DefaultDataSource → ProviderMediaDataSource → custom OkHttpStreamDataSource`, or SABR adapter/Ktor. HLS/live/video bypass the song byte cache. Local content uses the local source. No per-open URL age validation for retained direct sources. |
| Byte cache/ranges | `MusicDiskCache.kt:11–31`; `QueueCoordinator.kt:133–139`; `OkHttpStreamDataSource.kt:47` | Process singleton 256 MiB LRU `SimpleCache`; owner/track/encoding cache keys. HTTP Range reflects requested offset and length; no fixed-size CDN chunk scheduling. Extractor formats requiring bounded-range playback are explicitly refused. Caching does not by itself prove CDN throttling is prevented. |
| Next track | `PlaybackSources.kt:53–70` | Already pre-resolved next catalog song after playback began, but waited a fixed three seconds. This is URL warming, distinct from Media3's own source/audio preparation. |
| Player/buffering | `MusicPlaybackService.kt:212–225`; `VolumeBoostProcessor.kt:109` | Shared main/tail load-control helper customized only a five-minute compressed back buffer. Pinned Media3 is **1.11.1**: network min/max 50,000/50,000 ms, initial/rebuffer 1,000/2,000 ms, local initial/rebuffer 1,000/1,000 ms. These are buffered media durations, not fixed delays after tapping. |
| HTTP ownership | `PlaybackSources.kt:40`; `OkHttpStreamDataSource.kt:128`; `ProviderSessionManager.kt:119` | One warm OkHttp media client per service, reused across tracks and seeks, with 8-second connection/read limits. Provider Ktor and SABR clients were separate. No custom application DNS cache. |
| DSP/output | `VolumeBoostProcessor.kt:89–99`; `AudioTransitionCoordinator.kt:276–324` | DefaultAudioSink with silence watcher, EQ and boost; tail has EQ/echo/boost. Offload disabled deliberately for PCM processing; no visualizer startup gate found. EQ flush reconfigures rate/channels (`EqualizerProcessor.kt:133`), echo flush includes song position (`EchoProcessor.kt:90`), boost flush resets gain/limiter (`VolumeBoostProcessor.kt:76`). Effects follow audio-session changes and release with the service. No DSP lockup demonstrated. |
| Service lifecycle | `MusicPlaybackService.kt:180`, `:426–435`, `:552–586` | Service owns player/session. Recreated service restores saved queue/index/position; requested play intent is honored after restoration. Task removal intentionally stops playback. Destruction cancels recovery/scope, releases effects/player/session and unregisters connectivity. Manifest does not put playback in a separate process. Service destruction is possible without Activity recreation, but OS process death also destroys the Activity's controller. |
| Controller lifecycle | `PlaybackGateway.kt:121–162`; `RiffApp.kt:215–232` | A single immutable future, no `onDisconnected`, no `isConnected` check, no foreground rebinding. Foreground only warmed the provider. A disconnected non-null controller could remain the dispatch target. One pending-command slot could overwrite an earlier action. |
| Error/idle handling | `MusicPlaybackService.kt:274–284`, `:538–550`; `PlaybackGateway.kt:511`; `PlaybackRecovery.kt:145–171` | Error listener already invalidates streams and invokes a bounded coordinator. App Play already prepares IDLE and seeks from ENDED. Recovery preserves intent and position. Missing `prepare()` is not the ordinary app-button defect. Notification/headset transport did not explicitly route through the service retry entry point. |
| Catalog recovery | `QueueCoordinator.kt:321–354` | `scheme == catalog` returned through `prepare()` before attestation invalidation or excluded-profile resolution. A correctly chosen recovery action could therefore never be executed for the principal lazy-source path. |
| Solver lifecycle | `RemoteCipherClient.kt:66–83`, `:159–166` | Ten-minute unbind timer was rearmed only after successful RPC. New requests neither cancelled it on entry nor held an active-use lease. Timer could unbind a live operation. This matches an independently recorded defect in the 1 November investigation. |
| Focus/wake | `MusicPlaybackService.kt:217–224` | Music/media audio attributes with automatic audio focus and noisy-route handling already enabled. Network wake mode was absent. Absence alone does not establish the reported warm-resume failure. |

## 2. Reference architecture research

[BitChord v1.9](https://github.com/kushagrasinghx/BitChord/releases/tag/v1.9) describes on-demand addon selection, one stream and no mid-song quality swap. This supports lazy, single-representation selection; it does **not** establish a universal YouTube extraction latency guarantee. No BitChord code was copied.

[InnerTune's MusicService](https://raw.githubusercontent.com/z-huang/InnerTune/dev/app/src/main/java/com/zionhuang/music/playback/MusicService.kt), lines 568–653, puts `ResolvingDataSource` around cache factories, resolves using media ID/cache key, uses OkHttp and limits request subranges. Its displayed TTL comparison/storage is not a safe pattern to copy verbatim. Riff needs explicit absolute expiry, a safety margin and invalidation that also separates old flights. Riff also chooses between direct, HLS and SABR, so a progressive-only resolver cannot replace the source-selection layer wholesale.

[Media3 1.11.1 DefaultLoadControl](https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java), lines 60–100, disproves the supplied 2,500/5,000-ms default assumption for this checkout. The selected 500/1,500-ms thresholds are a modest reduction, not an explanation for historical multi-second extraction.

[Android background playback guidance](https://developer.android.com/media/media3/session/background-playback) places player/session ownership in the service and requires resource release on destruction. Foreground demotion and OS reclamation must be observed independently; retaining a UI object does not prove that its controller is connected.

## 3. Hypothesis verdicts and why earlier fixes were insufficient

| Hypothesis | Verdict | Evidence and implication |
|---|---|---|
| A: pre-play extraction | **Absent for ordinary songs; present for explicit video/live** | Song selection already queues catalog items. Moving that same extraction to another coroutine would not address it. Explicit video/live switching remains a separate path, unchanged here. |
| B: default buffers/cold per-track HTTP | **Partial, with corrected premise; not the main cost** | Defaults were uncustomized but already 1,000/2,000 ms (read from the 1.11.1 AAR); media HTTP already reused connections across tracks. Section 6: on the phone a fresh address reaches `playing` in about 0.3 s, so these thresholds can recover little of a 7 s wait. |
| C: app Play never prepares IDLE | **Not present as stated** | App and widget paths already prepare. Service retry routing is now also explicit for session transport. A timeout alone does not prove an expired URL or stale AudioTrack. |
| D: stale URL/cache | **Confirmed exposure and invalidation race** | Cache TTL checks existed only at lookup, not retained-source opens. Invalidating completed entries did not detach old flights. Prior invalidation and manual retry fixes did not close these paths. |
| E: stale controller | **Confirmed missing lifecycle handling** | Permanent future/null-only dispatch remains stale after disconnection. Provider warm-up does not restore IPC. Actual OS service reclamation on the owner's failing run still needs device evidence. |
| F: DSP/sink lockup | **Unconfirmed** | Flush and session-reattachment paths exist; no failing native-output trace supports rebuilding the sink. No speculative sink reset was added. One limit: `classifyFault` counts `ERROR_CODE_AUDIO_TRACK_*` as an unplayable song, so there is no automatic retry and a recommendation may be skipped; pressing Play does recover. Change it only if such an error appears in a trace. |

Additional confirmed defects: catalog recovery skipped attestation/profile actions; solver idle timer could release an active binding. Previous recovery budgets and solver death retries operated after these errors, rather than preventing or correctly reconstructing their source.

## 4. Implemented architecture

- **Source layer:** keep asynchronous `DeferredProviderSource` for transport/container selection. `SourcePreparationGate.kt:7` delays speculative resolution until the selected entry plays, while a skip promotes its new entry immediately. `PlaybackSources.kt:57` warms the actual shuffled next item without the former fixed delay. Cancellation follows the service/source lifetimes.
- **Direct byte layer:** `PlaybackSources.kt:131` wires `CacheDataSource → DefaultDataSource → ResolvingDataSource → OkHttpStreamDataSource` for direct audio. SABR/HLS retain their specialized sources. `StreamUrlDataSource.kt:36` checks TTL on each upstream open, so cached spans need no URL refresh. Expiry is the minimum of URL `expire`, provider expiry and the 20-minute cap (`:16`), with the existing 60-second margin.
- **Representation safety:** URL renewal retains position, length and cache key. Changed itag/MIME/bitrate/content length/source identity/transport raises `StreamRepresentationChanged`; the service rebuilds the source and restores playback position. It never stitches new-format bytes into an existing extractor/cache representation. Live and video are excluded from this progressive wrapper.
- **Rejection recovery:** on direct audio only, `StreamLoadErrorPolicy` immediately hands 403/404/410/416 to the service instead of spending about 6 s on Media3's three loader retries (1, 2 and 3 s). HLS, live and video keep Media3's retries, since a live segment can 404 for a moment. `RecoveryEpisode.next` permits one signed-URL refresh per episode; a second rejection excludes that profile, within the shared three attempts and 60 s. `QueueCoordinator.reload` now executes attestation refresh/excluded-profile selection for catalog items too. Existing generation/item-identity checks protect skip, reorder, account switch and pause.
- **Single-flight correctness:** `ProviderStreamResolver.kt:160` registers lazy flights before execution, including synchronous-completion fixtures. Publishing requires current flight identity and revision. `invalidateStream` detaches matching flights; their late completions cannot repopulate cache or remove a newer flight. Existing callers are not indiscriminately cancelled.
- **Connections:** `PlaybackGateway.kt` now owns a replaceable controller future, verifies `isConnected` before dispatch, handles disconnect, and re-verifies on `RiffApp.kt:225` foreground. Background disconnection stays dormant. Completion identity/closed guards prevent late attachment; listeners/ticker/futures are released. Pending commands retain order, queue-generation and latest play intent are rechecked at actual dispatch, and unsent request files are cleaned on close.
- **Session Play:** `WaitingSessionPlayer.kt:16` routes Play through the service's existing `retryPlayback`; notification/headset and app commands therefore share recovery and IDLE preparation. No `play()` retry loop is added.
- **HTTP ownership:** process-owned `PlaybackHttp` supplies the base for provider, SABR and media clients. Derived clients preserve separate headers/interceptors; the media client is reused across service recreation. What is shared is the connection pool; each Ktor client keeps its own dispatcher, because closing a client shuts its dispatcher down (section 6). OkHttp/system DNS remains in use, without inventing a custom unbounded DNS cache. A shared pool does not guarantee that unrelated CDN/API hosts can reuse the same connection.
- **Player:** `VolumeBoostProcessor.kt:109` explicitly uses min/max 30,000/60,000 ms and startup/rebuffer 500/1,500 ms; existing back buffer remains. `MusicPlaybackService.kt:216` enables `C.WAKE_MODE_NETWORK` with manifest wake-lock permission. Automatic audio focus remains enabled. Media3 owns wake acquisition/release; this does not keep a paused app alive indefinitely.
- **Solver binding:** `RemoteCipherClient.kt:69` counts active operations under the connection mutex, cancels idle release at entry, releases the lease in a non-cancellable finally block, and arms idle release only after the last operation ends. Timer generations reject obsolete releases (`:169`). Existing bounded dead-binder recovery remains.

## 5. Verification and remaining acceptance

New tests exercise URL renewal at nonzero byte offsets, cache/header preservation, encoding-change refusal, expiry parsing, late invalidated-flight completion, selected-source priority/cancellation, service disconnection/reconnection, closing during connection, ordered play/pause, and catalog recovery with profile exclusion. Existing stream-replacement tests cover pause/skip/position/order. Existing recovery tests now distinguish a single rejected-URL refresh from the general transient-failure budget.

Initial targeted run: 78 tests, 75 passed. Two connection fixtures used `Dispatchers.Unconfined`, which does not support the gateway's existing `limitedParallelism`; corrected to Android dispatchers. One old test expected three 403 attempts; it was first rewritten to stop after one refresh, which section 6 found to be a regression, so its original contract is back. Final results are in section 6.

Device acceptance remains held by the owner's screen-test instruction. A JVM pass cannot establish audible startup latency, a 30-minute OS idle/reclamation sequence, focus/route behavior, battery cost or physical AudioTrack recovery. Once authorized, compare signed-in direct and signed-out SABR starts; pause/background for 30+ minutes; resume and select/skip; repeat with expired/rejected URL, network loss, service recreation, pause during recovery, and queue/account changes. Correlate the existing startup spans with actual output progression. No measured latency improvement or universal absence of races/leaks is claimed without that evidence.

## 6. Second audit and phone attribution (4 November, later)

A second session audited the uncommitted work above before relying on it. The tree compiled and its tests passed, so nothing was half-written; the corrections below are about behavior. Most of the implementation stood up and is kept: the controller lifecycle, flight identity, solver lease, catalog recovery, selected-first gate, session Play routing and the direct-source `ResolvingDataSource`.

### Where the wait actually is

The 1 November evidence holds a sample this document had not used: `artifacts/idle-baseline-20261101T091433Z/samples/cold-restored-20261101T104059Z` (signed in, 19-song queue, a deliberate force-stop, not a natural idle). Times are from the mini-player tap.

| Span | Time | Share of 7,161 ms |
|---|---|---|
| Extractor config, player fetch and token bootstrap | 0 to 1,597 ms | 22 % |
| **Cipher stage of the song's lookup** | **1,597 to 6,278 ms (4,681 ms)** | **65 %** |
| Address to decoder ready to `playing` | 6,278 to 6,564 ms | 4 % |
| `playing` to the native output estimate | 6,564 to 7,161 ms | 8 % |

Every surviving-player sample took 561 to 823 ms from tap to output, with no lookup at all. So the owner's 6 to 8 s looks like the branch where **a lookup has to run**, and in that branch the cipher dominates. One sample and a forced stop are not a p95 and not proof for natural idle, but they outweigh the assumptions this investigation started from.

- **Buffer thresholds and the HTTP stack cannot fix this.** About 0.3 s separates the address from `playing`. The 500/1,500 ms thresholds are harmless and small.
- **Warm-up overlaps the lookup.** The warm-up began 3.6 s before the tap and ran 16 s. One of its solver calls ran from -3,331 to 4,479 ms; the lookup's own call ended 1.8 s after that. This fits `SolverGate`'s documented limit (a lookup waits for the one preload already inside) but is an inference: this capture has no solver-side span for the lookup.
- **Idle release has a price.** After the 19-minute idle sample, the recreated solver spent about 5.9 s and 6.1 s on two cipher RPCs. Releasing it after ten minutes frees up to about 200 MB of native memory, and a lookup after that pays to load it again. Keeping it longer trades memory for latency, which is the owner's decision. It is not changed here.

### Corrections to the first pass

| Problem found | Consequence | Now |
|---|---|---|
| A second 403/404/410/416 ended the episode instead of excluding the profile, and the old test had been rewritten to match | Regression. A profile the CDN refuses stopped playback, where the phone's 31 October run showed recovery moving to another client | Refresh once, then exclude the profile; the original test is back and covers all four codes |
| `StreamLoadErrorPolicy` applied to every non-catalog source | Regression for HLS/live radio: a momentary segment 404 became fatal | Direct audio only (`isDirectAudio`, tested) |
| A command waiting on a connection that failed stayed queued | Play pressed during a failure, already reported to the user, could start much later on the next foreground | Dropped on failure, unsent request files deleted, tested |
| Every Ktor client took `preconfigured = PlaybackHttp.base`, and Ktor only makes a new `Dispatcher` when nothing is preconfigured, so they all shared one that closing any client shuts down | Regression, and of the very kind being fixed: `onDestroy` closes the service's client, so after the service was recreated in a surviving process every provider request failed with `executor rejected`. The tests that passed at the start of this audit did not exercise it; the 87 JVM failures of the first lane run did | Ktor clients share the connection pool but each owns its dispatcher (`sharePlaybackConnections`). `PlaybackHttpTest` failed with that exact error before the fix |

### Gaps closed

- **WebView renderer death.** `AndroidTokenRuntime` keeps an off-screen WebView in the playback process and did not override `onRenderProcessGone`. Android documents that an unhandled renderer exit kills the host app, including when the system ended the renderer to reclaim memory. The handler now returns true, and the next lookup logs `token_renderer_gone` and makes a new attestation. How often this has happened on the owner's phone is unknown; the event and the existing process-exit records will show it.
- **Scripts that never answer.** A dead or stuck renderer never calls back, and only the 30 to 60 s lookup deadlines ended the wait while the token mutex was held. Each script now has a 15 s limit and fails at once if the renderer goes. The 3 s in the candidate patch was not adopted without measuring heavy scripts on the phone.
- **Dead sockets after sleep.** OkHttp's keep-alive clock (`System.nanoTime`) stops in deep sleep and a GET is not probed before reuse, so a silently dropped connection can wait out the 8 s read limit. After two minutes without a new request, counted across sleep, idle connections are dropped. A connection carrying a song is not idle and stays. This fits the 6 to 8 s symptom but is **not measured**; the HTTP spans will show it.
- **Failed warm-ups.** A warm-up that failed, as when the network is not back yet on waking, still counted as warm for five minutes. It now tries again on the next return.

### Not changed, and why

- `StrictWebViewClient`'s other users (sign-in, inline video) have the same missing handler. They are visible and need their composable to remove the view, so they are separate work.
- The 20-minute cap in `streamUrlExpiry` makes a seek after a long pause extract again although a signed URL usually lasts longer. Rejection recovery now fails fast, so this could be relaxed; there is no evidence yet either way.
- Explicit video/live selection still resolves before dispatch (hypothesis A), as section 3 says.

### Verification

`python scripts/quality/verify.py --lane pr`, run `pr-20261104T184249Z`: **PASS_LOCAL**, with all nine gates passing. That is 1,525 JVM tests with no failures, errors or skips, 177 script tests, and static checks with 0 findings over 234 files. This is on the uncommitted working tree.

The run before it, `pr-20261104T180933Z`, failed: 87 JVM tests, of which 21 failed outright with `executor rejected` and the rest followed from them, which is how the shared-dispatcher regression above came to light. It also found one unreviewed `runBlocking`, now reviewed: it runs on the loader thread and is bounded to 30 s.

Not established by any of this: audible latency, a natural 30-minute idle, OS reclamation, focus and route behavior, or battery cost. Screen acceptance is on hold at the owner's instruction, and these need the phone. The first device check should repeat the cold-restored and idle-immediate captures in `idle-resume-diagnostics.md`, compare them with the sample above, and read the solver-side spans to settle the warm-up inference.

## 7. Phone regression and chunked reads (5 November)

The owner tried the work above on the phone: slower to start, and pausing and restarting every few seconds. The passing lane in section 6 could not have seen this, because no test downloads real audio at real speed.

**A control that proved nothing.** An APK from 1 November, installed over the same data, buffered quickly and did not stall. That run did not empty the audio cache, so it may have read from disk. It does not show that this work caused the stalls, and it should not be cited.

**What BitChord does.** It reads a stream as bounded ranges (`ChunkedDataSource`, revision b690dc1). Its comments report 15.5 kB/s for an open-ended request and 5.7 MB/s for a 2 MiB range on one track. Those are its developer's figures; they were not reproduced here. Riff's `ChunkedStreamDataSource` adapts the design: at most 1 MiB, or 512 KiB for `ANDROID_VR` and `TVHTML5_SIMPLY`, above the address resolver and below the disk cache.

**Measurement.** Riff Debug on the owner's phone (I2217), Wi-Fi. Both builds are the same tree with and without the one wiring line and class. Each run: install, force-stop, delete `cache/audio-cache-v1`, open the app, tap the same song ("Beat It") on Home, sample `dumpsys media_session` for 60 s. Run in the order shown, the first alone and the other five together:

| Run | Chunked | Tap to playing | Stalls in 60 s | Least audio ahead while playing, after 10 s | Audio ahead at the end | Outcome |
|---|---|---|---|---|---|---|
| 1 | no | 8.0 s | 5 | 0.3 s | 52.6 s | Played |
| 2 | yes | 13.4 s | 1 | 2.4 s | 0 | Error at 19.98 s |
| 3 | no | 6.6 s | 3 | 0.1 s | 17.6 s | Played |
| 4 | yes | 4.9 s | 0 | 30.5 s | 77.1 s | Played |
| 5 | no | 14.2 s | 1 | 2.4 s | 0 | Error at 19.98 s |
| 6 | yes | 6.3 s | 0 | 30.5 s | 77.1 s | Played |

Whole-phone Wi-Fi receive is left out as a speed figure: it includes the home screen's loading, and it falls once the buffer is full. In the chunked runs about 60 s of audio was ahead by the first sample after playing began, at about 7 s. Without chunking, runs 1 and 3 stayed within about 2 s of the playhead for roughly the first 40 s.

What this shows: in the runs that played normally, chunking removed the stalls (0 and 0 against 5 and 3). It is two runs each, on one song, one network and one evening. It does not show how the 1 November and 0.20.0 builds behave, since neither was measured with an empty cache, so whether this work caused the stalls or only exposed them is still open.

**Not fixed: errors at 19.98 s, in both builds (runs 2 and 5).** The event log for run 5 reads: the direct address gets a 403 from googlevideo about 0.4 s after it is ready, twice; recovery leaves that client out and falls back to SABR; SABR delivers its first 341,337 bytes, then every further response is `ATTESTATION_REQUIRED`, with `po_token` false and later true; `recovery_stop class=attestation`. Those bytes are the 19.98 s. The events of run 2 show the same stop reason. This is most likely the known signed-out limit (owner's call on 31 Oct: note it, don't chase it; `docs/handover-0.20.0.md`, "Signed-out songs don't play"), which ended one of six runs then with the same 403, SABR and `ATTESTATION_REQUIRED` sequence. A song may use SABR only when `session.signedIn` is false (`streamHints`), so this log can only come from a signed-out session, and the handover left Riff Debug signed out. The session state was not read directly, because the phone had been unplugged by then. Why the direct address is refused on some runs and not others is not known; the log redacts the client profile.

**So all six runs were probably signed out, which limits the table.** The signed-in repeat follows. Starting time did not improve either: `selected_stream_ready` was 3.8 to 5.0 s in these cold starts, so the lookup is still most of the wait.

**Signed-in repeat (5 November).** The owner signed in to Riff Debug; each run checked the signed-in Home greeting before tapping, and the session log reads `session_state=valid` where it was kept. Same APKs and method, with one difference: the song is "Love in the Rain" from the owner's Home, because "Beat It" was not on the signed-in Home, and a search was avoided in case it reached the account's history. So this table is not comparable song for song with the one above. Pause times are from the event log (`playback_state=2` to `3`):

| Run | Chunked | Tap to playing | Pauses | Time paused | Least audio ahead while playing, after 10 s | `selected_stream_ready` | Outcome |
|---|---|---|---|---|---|---|---|
| 1 | yes | 7.7 s | 0 | 0 | 31.1 s | 6.3 s | Played |
| 2 | no | 6.1 s | 2 | 3.8 s | 1.3 s | 4.8 s | Played |
| 3 | no | 7.5 s | 2 | 4.4 s | 0.5 s | 6.4 s | Played |
| 4 | yes | 7.8 s | 0 | 0 | 31.1 s | 6.4 s | Played |
| 5 | yes | 6.3 s | 0 | 0 | 30.8 s | 5.1 s | Played |
| 6 | no | 7.5 s | 2 | 7.7 s | 0.5 s | 6.3 s | Played |

All six used a direct address (itag 251). No 403, SABR or `ATTESTATION_REQUIRED` appeared in any of them, which fits the 20 s failure being signed-out only, though six runs on one song cannot rule it out. The final samples of run 3 are missing from `dumpsys` (none after about 44 s), so its lowest-ahead figure covers less of the minute; its pauses come from the log, which continued.

What this shows: signed-in playback does stall without chunking, always twice, between about 22 and 35 s, and with chunking it did not stall in three of three runs. Chunking made no difference to starting time (means 7.3 s with, 7.0 s without; samples are about 0.8 s apart). It is one song, one network and three runs each. The unchunked build still holds the rest of this work, so this does not show whether that work caused the stalls or whether 0.20.0 has them too.

**Where the starting time goes, in these signed-in cold starts.** The tap came about 4 s after the process started. Playing began about 0.8 s after `selected_stream_ready`, in both builds, so the audio download is not the wait. Before that, the player request waited for the token (`token_minted` about 3 s, finishing 1.4 to 4.2 s after the tap), then `cipher_processing_completed` took a further 2.2 to 4.3 s. The next song's lookup, with no token to wait for, took 2.1 to 4.7 s, of which cipher was 1.5 to 3.3 s. `stream_resolved` reports `po_token=false` for these streams, so whether the wait for the token is needed at all is a question for the next step. Not measured: starts in a process that has been running for a while.

Not done: BitChord's other differences (reading the cache before looking up a stream, asking for streams that require bounded ranges, caching the next song's bytes ahead). Asking for bounded-range streams may bear on the refused addresses, but that is untested.

Verification: `python scripts/quality/verify.py --lane pr` on the chunked tree, 5 November: **PASS_LOCAL**, all nine gates passing, 1,535 JVM tests with no failures, errors or skips. As in section 6, this says nothing about playback on a phone; the tables above do. A rerun after the signed-in repeat failed once on `SolverGateTest.aPreloadWaitingForItsTurnStepsAsideForALookup`: the test released the first preload before recording "lookup start", so the two could swap. The test now records first, and the lane passed again, with the same counts.

## 8. Cause of the pauses, new library, disk cache first, idle (5 November)

The owner reported that 0.20.0 has no pauses. So this was measured, on an empty audio cache, signed in, on the phone's one network and the song "Love in the Rain" unless stated. The earlier impression may come from songs already on disk; that is an inference.

**The pauses are not new.** Three runs each, without chunking:

| Build | Pauses | Time paused | Tap to playing |
|---|---|---|---|
| 0.20.0 | 0, 1, 2 | 0, 2.0, 2.9 s | 12.2, 8.0, 6.4 s |
| Current tree | 3, 2, 3 | 10.3, 2.2, 9.5 s | 7.5, 6.0, 5.8 s |

Putting back each suspect alone (the older buffer sizes, the older HTTP client, the older direct-address path) gave noisy results: 2 and 1 pauses, with one run failing to start; 0 and 3; 2 and 0. None explains it. The current tree may be somewhat worse than 0.20.0, but three runs each can't separate that. With chunking there were no pauses in any run on any build, with at least about 30 s ahead. Nothing was reverted.

**InnerTubeX 0.11.2**, alternating with 0.7.2 on the same tree with chunking:

| | 0.11.2 | 0.7.2 |
|---|---|---|
| Tap to playing | 1.9, 1.3 s | 15.5, 10.9, 6.5 s |
| Next song's lookup | 154 to 198 ms | 2.5 to 3.5 s |
| `:solver` memory | 102 to 114 MB | 173 to 194 MB |

The first two 0.7.2 figures may be inflated by alternating versions on the same data. A third 0.11.2 run was refused by a false "not signed in" from the test's own check, later replaced.

**Everything together, empty cache:** 2.2, 2.3 and 2.2 s on three more songs ("Pal Pal", "Woman", "Ala Bolelo"), 2.2 and 1.2 s on "Love in the Rain", no pauses. A paused restored song gets its address in 373 to 511 ms (`stream_ready_on_return`).

**Disk cache first:** partly cached replay, 1.2 and 1.3 s, no pauses, about 31 s ahead (`stream_from_cache part=start`). Fully cached, Play on the restored song, 1.9 s in one run (`part=whole`); no address lookup is logged for it, and the sampling is about 0.3 to 0.7 s coarse. That run's low "ahead" figure of 1.7 s is the song reaching its end.

**Idle, phone asleep on USB:**

| Situation | Play to playing | Afterwards |
|---|---|---|
| Paused 32 min, process kept, audio buffered | 0.4 s | No stalls to the song's end; next song at once |
| Paused about 16 min, solver released, seek 20 s past the buffer | 0.9 s | No stalls, read to the end on the existing address |
| Killed with `kill -9`, service restarted, queue restored, key after 4 to 14 s | 1.4, 1.8, 1.4 s | Same position; plays |
| Started again with `am start` after the session had gone, then a key | 2.4 s | Same position; plays |
| Key about 2 to 4 s after a kill, session absent or queue not yet restored | Nothing | One key lost at 4 s; a later key worked. Not repeated reliably: the next 4 s try worked |
| **Android's own low-memory kill at 13:42, `reason=LOW_MEMORY`, importance 125, 280 MB** | Nothing | Not restarted at least 3.5 min later; no session, so no notification or key can reach Riff. Opening the app restores the song |

The last row is Android's own decision, on a phone the owner was using, about a minute after the test's `kill -9` restart; whether those kills made it likelier is not known. It is the idle death that remains: Riff registers no media button receiver (`mediaButtonReceiver=null`) and has no `onPlaybackResumption`, so a key cannot bring back a process that is gone, and is lost during the seconds a restart takes. Not fixed; the owner decides.

In the same logs, a return prewarm that began with the phone waking at 13:15 reported 373 s, because the phone slept during it. It ended 3 s after Play and delayed nothing.

**Is `:solver` still needed?** Not removed. Lookups at 12:48:37 (before `solver_connected`) and 13:40:03 (after `solver_idle_release`, with no reconnection) finished without it, so normal direct songs did not call it. 0.11.2 no longer writes the messages Riff parses into `cipher_step`, so the absence of those lines proves nothing, and those stages are simply not reported now. Fallback paths still call it (`processFormats`, reading 0.11.2's source). Its crash reason is weaker, since QuackJS is Kotlin and not native code; its memory reason stands. Its preload still costs about 100 MB and 7 to 8 s of background work per start.

Not tested: address expiry over 6 h (unit-tested only), signed-out playback on 0.11.2, SABR, age-restricted songs, a player script QuackJS can't parse, Cast with a cached stand-in (by reading: Cast looks up its own address), battery, doze without USB, and any idle over 32 minutes. Loudness read differently for the same song after the upgrade (-3.1 dB to 3.9 dB, with normalization off in these tests); unexplained, and worth checking before a release.

**What the PR lane found in the upgrade.** Three JVM tests failed on 0.11.2, and two were real changes. (1) The library now ends a request with a `CancellationException` when the account changes, without cancelling the caller; Home, the profile, Liked songs' count and search passed that on as if their caller had been cancelled, so they ended with no result. They now return "account changed" unless the caller really was cancelled. (2) The library throws on a failed status and retries a 429 only with a short `Retry-After`, so Riff's own three tries for a collection page stopped applying. Riff now still tries a 429 three times there, as in 0.20.0. Other reads that checked for a 429 in a returned response now get the exception instead; their tests pass, and they were not read one by one. (3) Reads of the player-config table now refresh in the background, so `PlayerConfigBoundaryTest` waits for that request. These fixes are after the phone tests above; they were not played on the phone.

One change from the plan: sign-out leaves the cached-song list in place. Entries are account-scoped and useless to another account, the same as the audio keys.

Verification: `python scripts/quality/verify.py --lane pr`, 5 November, `pr-20261105T085122Z`: **PASS_LOCAL**, all nine gates, 1,550 JVM tests with no failures or skips. The desktop module compiles against 0.11.2 (`:desktop:compileKotlin`); `:desktop:verifyProviderJvm` and its acceptance runs were not run.

## 9. Album songs playing the video's audio, and Play after a kill (5 November)

**The owner's report.** Every song on BRAT had a long pause at its start, and "360" showed 4:24 in Riff Debug against 2:13 in the owner's installed 0.20.0.

**Cause.** An album page lists a song by its music video's id when it has one. 15 of BRAT's 15 rows are `MUSIC_VIDEO_TYPE_OMV`, and so were all the rows of the After Hours, Thriller, Future Nostalgia and Random Access Memories pages checked, 10 of 12 on one Bollywood soundtrack and 4 of 17 on Abbey Road. The row states the album's length (2:14), while the id is the 264 s video.

**Two first guesses were wrong.** It is not the stream client: signed in on the phone, every client that answered returned the same 264 s, 4,176,330-byte file for `WJW-VvmRKsE`, WEB_REMIX included. It is not the library upgrade either: 0.20.0's code, in the Debug slot, played the same 264,321 ms from the same album page. So the bug is older than the upgrade. How the owner's installed 0.20.0 reached the 2:13 track was not found; it was not read, since that app is not debuggable. Its filled star suggests the audio track's id, because `next` puts the like on that id.

**Fix.** Each row's "Song credits" link is `MPTC` plus the audio track's id, and needs no further request. A music-video row on a page whose browse id starts `MPRE` now plays that id. Rows with no such link, and lists that are not albums, keep theirs: a video in a playlist is meant as that video. For all 15 BRAT rows, public `player` answers gave the same title, an ATV type and the listed length within 1 s, and the ids agreed with the album playlist's own list (`VLOLAK5uy...`). The credits-link ids matched that list in order on all but one row of the six other albums checked.

| Check, signed in on the phone, fixed build | Result |
|---|---|
| "360" from BRAT's Play button, empty audio cache, app just opened | 133,821 ms (2:13); about 4.4 s to sound, by position |
| Lookup of three audio-track ids with the fast client | 0.24 to 0.45 s, DIRECT, itag 251, the listed lengths |
| Tracks 8 to 15 by skipping | Each within 1 s of the album's length |
| Six skips in a row, next song prepared | About 1 s, by position; one reading per skip, so rough |

The owner has not yet heard the fixed build. Radio and up-next queues are not changed and can still hold music-video ids; nor are search, Home and artist rows. Songs downloaded earlier under a video's id should not show as downloaded on the album page now (by reading, not tested).

**Play after a kill.** Media3's `MediaButtonReceiver` is now in the manifest. A key that finds no queue holds the foreground with a plain notification (`MediaKeyHold`) until the saved queue is loaded, then Media3's own notification replaces it. With the process dead:

| Key sent to Riff Debug's receiver | Result |
|---|---|
| Process dead, saved queue | Restored and playing; about 4.5 s to sound, by position; notification, foreground, no crash |
| Killed while paused, key 25 s later, after Android had restarted it | 2.5 s reading, same position |
| Process dead, saved queue moved aside | No notification, not foreground, no crash |

These keys were sent directly to the receiver, with `cmd deviceidle tempwhitelist` for 15 s standing in for the exemption Android gives a real media key. The whole route through Android was **not** shown to work. After a kill `dumpsys` named Riff Debug as the last media button receiver with no button session, yet a real key at that moment appears to have gone to ReVanced YouTube, whose stopped session then became active and played about 34 s. A second key, sent without the check, paused it. Neither was intended. Android's choice among sessions decides this, so with another media app's session present Play may reach that app. Any real-key test now requires that no other media app has a session.

Not tested: a real headset or Bluetooth key after Android's own low-memory kill, signed-out album playback (the laptop's signed-out requests were refused for both kinds of id), and a key after a kill while playing.

Verification: `python scripts/quality/verify.py --lane pr`, 5 November, `pr-20261105T113817Z`: **PASS_LOCAL**, all nine gates, 1,552 JVM tests with no failures or skips.

## 10. Timing Riff's own steps, and Play on a restored song (6 November)

The owner's request was to time the steps from tap to first sound and cut the slow ones. Timings are from the Debug build on one phone and one network, by script, with `RiffStartup` stages. The time from the tap to the queue starting wasn't measured, so each figure starts at the queue.

| Start | To audio advancing | Where the time goes |
|---|---|---|
| Song not on the phone | 1.73 to 1.87 s | Riff's own steps about 34 ms. Lookup 310 to 355 ms, DNS about 190 ms, TCP and TLS about 380 ms, server wait about 365 ms, first byte to playing about 115 ms |
| Song on the phone, row tapped | 340 to 450 ms | |
| Paused song, same process | 256 to 268 ms | About 230 ms is the audio output starting; Riff adds no fade |
| Restored after a force-stop, Play on the mini player | 540 to 1,450 ms | See below |

**Riff's own steps were not the slow part of a new song.** That time is network, which this work didn't touch.

**The restored song was the one wait inside Riff's control.** Its time rose and fell with the saved position: 54 ms at 20.1 s, 450 at 24.4, 817 at 28.4, 289 at 32.1, 561 at 36.2, 868 at 39.9, measured from creating the decoder to setting up the `AudioTrack`. That is about 80 to 100 ms for each second past a seek point, with seek points about 10 s apart, and no network in the gap. The player reads forward from the last seek point to the saved position.

Theories rejected: the signature solver still warming up (a 25 s wait before Play was no faster than 6 s), and a fade in Riff (there is none).

**The change.** When the app is on screen and a restored song is paused, `PlaybackSources.readySelected` now prepares it, so that reading happens before Play. The first rule asked for the whole song on the phone, and it never fired in practice, because a song paused part-way is only partly cached. The rule now (`preparesOnReturn`, `cachedAround`):

- the bytes 15 s either side of the saved position are on the phone, or all of the song is;
- a song only partly on the phone also needs a validated network, because a failed read would start recovery on a song nobody asked to play;
- more than 60 s of the song is left, because the player's buffer target is 60 s. With less left it would go on to load the next song.

**Result:** eight runs at saved positions from 24 s to 55 s on a 2:43 song, restarted each time: **292 to 333 ms**, flat, where the same kind of start took 540 to 1,450 ms.

Also checked: the player stays `PAUSED`, its `AudioTrack` is paused, and there were no player errors. 35 s after opening, with the whole song on the phone, there was no lookup for the next song and no media request. It reached ready about 2 s after the app opened, so a Play pressed sooner still waits.

**Costs, stated plainly:**

- Opening the app with a restored song now shows the media notification and starts the foreground service, as pausing a song already does. The player holds its decoder as it does in any pause.
- A song only partly on the phone tops up its buffer while paused, so opening the app can use data without Play being pressed. The buffer overshot the 60 s target: `buffered_ms` showed the whole 2:43 song loaded from 24 s. That is an estimated 1 to 2 MB, not measured directly, once per song, since the bytes are then on the phone. Those five runs were on Wi-Fi with no metered-network check in the rule.

Songs with 60 s or less left, and songs without the bytes around the position, start as before.

Not tested: a real tap by hand, the release build, Play pressed within the first 2 s, being offline with a partly cached song (the rule skips it; only the unit test covers that), and a call or another app playing during the prepare (no audio focus is requested until Play, by reading the code).

Verification: `python scripts/quality/verify.py --lane pr`, `pr-20261105T190026Z` (00:30 on 6 November, local time): **PASS_LOCAL**, all nine gates, 1,586 JVM tests with no failures. The first run that day failed lint: a new top-level function had separated `PlaybackSources` from its `@OptIn(UnstableApi)` annotation.

Re-check on the final Debug build, 6 November, a 2:51 song from 58 s to 85 s, eight restarts: the song was prepared every time (`PAUSED` on opening, `playing` 63 to 72 ms after the tap). Sound took 724 to 1,483 ms, because the output was a Bluetooth A2DP device this time, not the phone speaker: the extra time is between `playing` and the audio position advancing, which is the Bluetooth link starting. The 292 to 333 ms above is for the phone speaker. Two of the eight runs left too little in the log to time.
