# M3 Acceptance Evidence — Native Audio Engine, Streaming & Playback Lifecycle

Status: Substantially Complete on Local Hardware; Automated Unit and Smoke Verification Passing.

## 1. Implemented & Integrated Deliverables

1. **Decoupled Audio Worker Architecture (`DesktopAudioBridge.kt`, `AudioWorkerMain.kt`)**:
   - Production playback rendering completely decoupled from the Compose UI frame loop (`Main.kt`).
   - Dedicated high-priority rendering thread (`RiffAudioRenderThread`) drives audio output and sample clock.
   - Dedicated analysis thread (`RiffAudioAnalysisThread`) performs off-callback spectrum analysis.
   - Clean shutdown with `InterruptedException` handling ensuring zero process leaks or stack traces.

2. **Framed Private IPC Transport (`AudioIpcTransport.kt`)**:
   - 4-byte big-endian framing with strict payload limits (1 MiB maximum) protecting against malformed frames.
   - Full command, result, and event binary serialization for `AudioCommand`, `AudioResult`, and `AudioEvent`.
   - Monotonic sequence numbers and timeline epoch validation (`timelineEpoch`).
   - Conflated feature spectrum channel dropping stale frames under load.

3. **Audio Output Backend (`DesktopAudioBackend.kt`)**:
   - Real Windows audio output backend wrapping `javax.sound.sampled.SourceDataLine` (44.1 kHz, 16-bit stereo PCM).
   - Authoritative sample rendering clock (`totalSamplesRendered`).
   - Hardware latency calculation derived from line buffer occupancy (`unrenderedBytes / (channels * 2)`).
   - High-precision headless/CI simulation fallback for hardware-less testing.

4. **Dual-Deck Gapless Mixer & DSP Processing (`DesktopDualDeckMixer.kt`, `DesktopDSP.kt`)**:
   - Dual-deck architecture: Deck A (active) and Deck B (prebuffered).
   - Sample-accurate gapless transition between consecutive tracks.
   - Equal-power crossfade blending (`cos(theta) * DeckA + sin(theta) * DeckB`).
   - 10-band TPT state-variable equalizer with bass cut.
   - Dynamic volume booster with 100ms peak limiter release preventing clipping.
   - Feedback delay line echo.

5. **Authoritative Clock & Real FFT Spectrum Analysis (`DesktopFFT.kt`)**:
   - Exact content position derived from rendered PCM frames: `(samplesRendered * 1000L) / sampleRate`.
   - Off-callback Radix-2 Cooley-Tukey FFT with Hann windowing over real PCM audio samples.
   - 256 uint16 frequency magnitude bins and peak RMS calculated from rendered audio energy.

6. **Real Audio Format Decoding & Source Preparation (`DecodedAudioTrackSource`)**:
   - Decodes local audio streams (`.wav`, `.pcm`) via Java Sound `AudioSystem.getAudioInputStream`.
   - Automatic format conversion to 44.1 kHz 16-bit signed stereo PCM.
   - Sample-accurate seeking and exact metadata duration extraction.
   - `AudioCommand.PrepareSource` pre-buffers and decodes tracks into dual-deck memory before playback handoff.

7. **Architecture Decision Record Formalization (`docs/desktop/adr-001-audio-worker-runtime.md`)**:
   - Formally accepted Kotlin/JVM Audio Worker runtime with DirectSound/WASAPI output backend.
   - Dual-mode architecture: isolated subprocess (`AudioWorkerMain`) and isolated multi-threaded in-process mixer (`DesktopDualDeckMixer`).
   - Guarantees: decoupled hardware clock, < 50ms render latency, bounded supervisor recovery.

8. **Subprocess IPC Correlation & Worker Supervision (`DesktopAudioBridge.kt`)**:
   - Monotonic request-response correlation (`requestId`) with 5.0-second timeout preventing UI deadlocks.
   - Unified stdout stream message discrimination: `AudioIpcTransport.readMessage` discriminates `AudioResult` (`0x10`) from `AudioEvent` (`0x20..0x25`).
   - Bounded worker supervision: automatically restarts crashed or dead worker subprocess up to 3 times in 10 seconds.
   - Emits observable `AudioEvent.Fault` if restart threshold is exceeded, preventing silent failure loops.

9. **Stream URL & Token Resolution (`DesktopStreamResolver.kt`)**:
   - Mutex-guarded single-flight deduplication per `sessionScope:trackId`.
   - Bounded exponential backoff with jitter on transient failures.
   - Exposes `Direct`, `HLS`, and `SABR` transport capabilities.
   - In-memory expiration verification with safety margin (`isNearExpiration()`).

## 2. Automated Checks

- **Desktop Unit Tests (`AudioEngineContractTest`)**:
  - `equalizerProcessesPcmWithoutClippingOrNan`: PASS
  - `volumeBoostAndLimiterPreventClipping`: PASS
  - `echoProducesDecayingFeedbackTail`: PASS
  - `audioBridgeHandlesCommandsAndClockAnchors`: PASS
  - `streamResolverDeduplicatesAndCaches`: PASS
  - `fftProduces256BinsAndCalculatesRms`: PASS
  - `audioIpcTransportRoundTripsCommandsAndEvents`: PASS
  - `dualDeckMixerGaplessTransitionAndSeeking`: PASS
  - Total in class: **8 tests, 0 failures**.
- **Desktop Total Test Suite**: **57 passing unit tests**.

## 3. Manual Checks & Observations

- Verified dual-deck mixer renders synthetic PCM fixtures through Windows audio output line without clicks or clipping.
- Verified off-callback FFT computes non-zero magnitude bins for 1000 Hz sine wave and zero magnitude for silence.
- Verified seeking updates timeline epoch and flushes hardware buffer cleanly.

## 4. Outstanding Hardware Gates

- **[Other hardware]** Multi-channel USB audio interfaces, external DACs, and Bluetooth A2DP latency profile calibration.
