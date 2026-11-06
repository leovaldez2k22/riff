# Riff native audio worker (Windows x64, Phase 1)

Build from the repository root with its JDK 21 present:

```
python scripts/desktop/acquire-phase1-native.py
python scripts/desktop/build-phase1-native.py
.\gradlew.bat -PriffDesktop=true :desktop:nativeAudioTest :desktop:createDistributable --offline
```

`dependencies.json` pins archive bytes and SHA-256 digests. Acquisition never accepts a new upstream digest automatically. The BtbN `latest` download URL moves; a future changed archive fails validation and needs a separately reviewed pin or the retained original archive. The verified archives remain in `.toolchain/desktop-native`. Zig 0.15.2 is a build-only dependency. This portable LLVM/MinGW toolchain replaces the proposed MSVC setup on this laptop; JNI stays compatible with the bundled JDK 21 without preview FFM flags.

The actual upstream FFmpeg build is `n8.1.3-32-g760d10603e-20261102`, LGPL version 3, with avformat62, avcodec62, avutil60 and swresample6. It is dynamically linked; unmodified DLLs can be replaced with ABI-compatible builds. Upstream sources: https://github.com/FFmpeg/FFmpeg/tree/760d10603e and build recipes: https://github.com/BtbN/FFmpeg-Builds . `licenses/FFMPEG-LGPL-3.0.txt` reproduces the supplied license; LGPLv3 incorporates GPLv3, available as the repository's `LICENSE`. The packaged preview includes this document, pins and LGPL text. This private acceptance image is not a published release: a complete corresponding-source/transitive-notice bundle remains a release gate.

JNI owns FFmpeg leases and a WASAPI shared-mode render thread. Two JVM decoder threads fill bounded one-second Float32 stereo/48kHz rings. The mix thread applies equal-power crossfade, existing desktop TPT EQ, gain and limiter. Output uses a one-second native ring and a 150ms target lead. The native thread registers MMCSS Audio, waits on WASAPI events, and makes no JVM callback. Pause/resume uses a bounded five-step per-stream volume ramp without changing the Windows master volume. Hardware clock spans exclude generated underrun silence. Analysis follows presented PCM spans on a separate worker.

FFmpeg receives canonical local paths with a `file` protocol restriction, or custom AVIO reading validated HTTP ranges / real SABR chunks. No signed URL is passed to FFmpeg or persisted in the session database. Required container and decode failures surface as failed command results; device loss stops the engine, is visible to the host, and recovery replays the durable desired queue paused. Rewind-and-decode seeking intentionally favors exact decoded-frame equality over fast indexed seeking for this first slice. Late seeks can therefore be expensive; range/progress remains off the UI and render threads.

`captureLoopback` is an explicit diagnostic call. It records the Windows default render endpoint's actual shared mix into a caller-owned bounded buffer; it is not the submitted PCM tap. Corpus material and provider captures are kept in ignored acceptance artifacts, never bundled as application tracks.


## Shared-mode packet scheduling correction (3 November 2026)

A frozen-runtime soak observed premature silence insertion. Native telemetry showed 4,320 frames still queued at the endpoint at the first insertion: available buffer capacity had been treated as a request to fill every free frame immediately. The renderer now submits the available real packet while endpoint audio remains queued, waits for producer data when needed, and primes real PCM before `IAudioClient::Start`. It does not increase the buffer or remove genuine empty-endpoint starvation reporting. This follows WASAPI's shared-mode partial-packet contract and startup sequence: https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiorenderclient-getbuffer and https://learn.microsoft.com/en-us/windows/win32/coreaudio/rendering-a-stream .

The hardware-paced regression uses a real muted WASAPI stream, maintains measured presentation headroom, then pauses the producer for 60 ms. The same test class against the frozen old and corrected DLLs reported 3,339 inserted zero frames versus zero; native/test digests and logs are in `artifacts/desktop/phase3/native-regression/comparison.json`. An earlier fixed-interval warmup was replaced because Windows sleep timing could underfeed the endpoint; the accepted reproduction paces from actual presented frames. This proves that packet-scheduling bug, not completion of the eight-hour reliability gate. Native producer/render gaps and first-underrun padding are retained for future failures.


The second instrumented long-run failure (3 Nov, 07:29:05 IST) is independent of the corrected packet-fill defect: first endpoint starvation at 07:29:05.2628 fell inside a JVM mixer Thread.sleep(2) that took 286.6483 ms. JFR's longest GC pause was 7.0036 ms; there is no matching long GC pause. The native render thread already registered for MMCSS Audio; the JVM producer did not. The real output now acquires a separate Audio/NORMAL MMCSS task on the invoking mixer thread and reverts it on that same thread at shutdown, including initialization failures. Injected output taps do not load native scheduling. No endpoint buffer or underrun acceptance bound was increased. See artifacts/desktop/phase3/soak/failure-thread-analysis.json and failure-pauses.json; the untouched binary recording is retained. A new bounded stress/pilot and eight-hour run are required before accepting this mitigation.

Windows API contract: https://learn.microsoft.com/en-us/windows/win32/procthread/multimedia-class-scheduler-service . Each task-performing thread registers independently; registering only the WASAPI render thread does not register a JVM producer.


## Resilience buffering policy (3 November, subsequent correction)

A later real endpoint probe measured 100 ms capacity; both native render and producer gaps reached about 168 ms during the third failed endurance run. The identical real-FLAC regression reproduced failure under an injected 180 ms producer delay. The output now explicitly requests 250 ms in **timer-driven shared mode**, wakes on new PCM or a bounded 5 ms playing timeout, and the mixer holds 350 ms total presented-clock headroom. Idle polling remains 20 ms. This is a deliberate latency/resilience tradeoff, not a relaxed underrun threshold. Native renderer MMCSS registration and priority failures now fail endpoint initialization instead of silently proceeding. Diagnostics expose actual endpoint capacity, padding and native queued frames; frame-to-content mapping still uses the hardware clock. Stop/Pause acts on the endpoint immediately, while seek flushes queued old PCM.

Microsoft's shared-mode event-callback contract requires zero buffer duration; explicit timer-driven capacity follows https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-initialize . Before/after regression receipts retain actual pause response and unchanged test-class hashes. This mitigation still requires a new eight-hour run and physical endpoint acceptance.
