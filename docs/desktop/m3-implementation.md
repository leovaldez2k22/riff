# Desktop M3 implementation — Native audio engine and stream transport/proxy

The owner authorized implementation on 1–2 November 2026. The desktop module remains an additive opt-in exception to the Android-only module rule (`:desktop` under `-PriffDesktop=true`); Android `:app` and `:benchmark` retain their boundaries.

## Implemented in M3

1. **Typed Command and Event Protocol (`AudioContract.kt`)**:
   - Conforms to Section 4.4 of `reports/Riff Windows desktop architecture.md`.
   - Commands: `Handshake`, `LoadQueue`, `SetPlaying`, `Seek`, `SetVolume`, `SetPlaybackRate`, `SetDSPConfig`, `PrepareSource`, `Shutdown`.
   - Events: `PlaybackStateChanged`, `ClockAnchor`, `TrackTransition`, `BufferingState`, `FeatureSpectrumFrame`, `Fault`.
   - Versioned envelope with request IDs, timeline epochs, and monotonically increasing sequence counters.

2. **Core Audio DSP Port (`DesktopDSP.kt`)**:
   - `DesktopEqualizer`: 10-band TPT state-variable filter over 16-bit PCM matching Android equations.
   - `DesktopVolumeBoost`: 1.0f–3.0f booster with track normalization trim and dynamic peak limiter (100ms release) to prevent digital clipping.
   - `DesktopEcho`: Automix feedback delay line (`ECHO_FEEDBACK = 0.55f`) with smooth trailing decay.

3. **Stream Resolver & Session Management (`DesktopStreamResolver.kt`)**:
   - Deduplicated single-flight resolution over Mutex.
   - Memory-only URL and token cache with expiration (expiring URLs and cookies never enter durable metadata or diagnostics).
   - Dynamic cache invalidation on stream error or scope reset.

4. **Desktop Audio Bridge (`DesktopAudioBridge.kt`)**:
   - Authoritative `ClockAnchor` emission mapping content positions to monotonic system timestamps at 5–10Hz and discontinuities.
   - FFT feature analysis synthesizing 256 uint16 bins for visualizers.
   - Seamless clock stepping for UI lyric synchronization.
