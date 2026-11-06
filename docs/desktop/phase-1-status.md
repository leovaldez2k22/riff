# Phase 1 — real playback walking skeleton

Updated 2 November 2026, branch `codex/phase1-walking-skeleton`.

**Engineering implementation is in place. Automated acceptance has passed across the actual packaged controller/worker/native path. Interactive Windows acceptance is still blocked by the laptop's lock screen; Phase 1 is not labelled 100% accepted while that gate is open.** This is no longer the earlier resolver-only increment.

The current app imports and decodes real compressed audio, resolves a genuine public catalog track and its SABR media, plays through Float32 dual-deck DSP to WASAPI, supports real seek/queue transitions, and restores durable sessions from real SQLite. No fixture catalog, synthesized source, fake progress/FFT or silent output backend is selected by production.

## Evidence and task closure

| Plan task | Actual implementation / verification |
|---|---|
| P1-01 remove false-success defaults | FixtureTracks, generated audio backend, old custom database and gallery are test/preview-only. Empty production state is honest. Packaged JAR audit rejects the old fallback/fixture classes and bundled acceptance audio. |
| P1-02 authoritative controller | DesktopHost owns a serialized actor. Stable UUID queue occurrences preserve duplicates; desired playback and effective output/buffering are separate. Pause/quit consume acknowledged hardware-position details. Invalid imports preserve existing effective playback; clearing the last occurrence acknowledges a stopped empty engine. Rejected commands and persistence failures are visible. |
| P1-03 durable session | SQLDelight 2.1.0 + SQLite JDBC 3.51.3.0, actual SQLite file header, WAL/FULL, transactional checkpoint/receipts with bounded retention, schema/version checks. Local SHA-256/size/mtime and stable anonymous provider IDs replace expiring URLs. Legacy originals are backed up without overwrite; malformed/fixture/ambiguous queues are rejected rather than invented into tracks. |
| P1-04 actual worker | Required RiffAudioWorker.exe uses the bundled runtime and private bounded IPC, exact protocol 2.1 handshake, command correlation, strict framing and explicit data-root forwarding. At most three recovery attempts per 60 seconds replay the desired queue paused. Actual worker termination, recovery and real resume pass. Missing worker/native DLL fails without a development-JDK/in-process/silent substitute. |
| P1-05 real codec decoding | JNI FFmpeg `n8.1.3-32-g760d10603e-20261102`, pinned archive SHA-256, Float32 stereo/48kHz. Native codec IDs establish MP3/FLAC/AAC, independently of filename extensions. Actual provider WebM/Opus also passes the packaged journey. Local/custom AVIO boundaries restrict demuxers and nested IO. |
| P1-06 actual DSP/output/analysis | Bounded decoder/native rings, two real decks, equal-power crossfade, desktop TPT EQ, gain smoothing, 32000/32768 limiter ceiling and presentation-aligned FFT. Native shared WASAPI MTA/MMCSS output uses hardware clock spans that exclude underrun silence. Gapless events derive from observed starvation/underrun state rather than a hardcoded success flag. Independent endpoint loopback matches decoded source content at correlation 0.9653. |
| P1-07 genuine import | File drop and Ctrl+O route to real worker probing. Tags/duration come from the actual decoder, with explicit unknown metadata where absent. Corrupt/non-audio input fails. No 180000ms fallback establishes pretend media. |
| P1-08 genuine provider/cache | Anonymous read-only InnertubeX 0.7.2 catalog/resolver and actual SABR acquisition. Public YouTube search returns playable video items when Music returns only profiles/collections. Validated 206 blocks and bounded response/time budgets feed custom AVIO; missing sparse ranges never read as zeros. Controlled TLS and actual provider are tested separately. |
| P1-09 seek/queue/restart | Reopen/decode-forward seeking matches reference decoded frames. Real Next/Previous, duplicate reorder, shuffle permutation/order, repeat, volume, EQ/boost and crossfade settings are wired. Real dual-deck positions/settings restore paused after restart. Kill-at-acknowledged-checkpoint preserves exact queue, 2345ms position and volume. |
| P1-10 acceptance | 166 desktop component/contract tests, six real native tests, packaged local/provider/DSP/crash journeys, negative package gates, isolated Compose startup and all nine repository PR gates pass. Interactive pointer/keyboard/view acceptance remains blocked by locked Windows, as detailed below. |

Exact artifacts are under `artifacts/desktop/phase1/` (local ignored evidence, not app assets):

- `accepted-tests.log`; `desktop/build/test-results/test/` and `desktop/build/test-results/nativeAudioTest/`: 166 + 6 tests, zero failures/errors/skips.
- `accepted-packaged.log` and `final-journey/*-acceptance.json`: actual packaged import/play/pause/seek/Next/Previous, duplicate queue identity, real DSP controls, both-deck restart, worker termination/recovery, abrupt app termination, real provider full track, post-60-second seek and automatic transition.
- `loopback-evidence.json`: actual Windows render-endpoint capture, 48kHz, finite/non-clipped signal and correlation to the genuine local decoded recording, with encoded-source SHA-256. This is independent capture, not the submitted PCM buffer.
- `negative-package-evidence.json`: missing worker exits unsuccessfully in about 7 seconds; missing native shim in about 3 seconds. Neither produces a false success receipt. The missing-DLL launcher-dialog failure was found and fixed with explicit linkage-error propagation.
- `package-audit.json`: packaged native digests/imports, no development JVM path, no production fixture classes or bundled corpus.
- `startup-smoke.json`: isolated real Compose frame and native chrome startup at simulated 1.0x / 1.5x scales, exit 0, one frame marker per run. Simulation is not physical mixed-DPI acceptance.
- `preservation-audit.json`: all six captured visual identity files and all 38 audited Android source anchors are unchanged.
- Required PR lane: `artifacts/quality/lanes/pr-20261102T170731Z/lane-summary.json`, PASS_LOCAL across all nine gates. No concurrent Gradle build or emulator ran during this lane.

## Measured limits and deliberate differences

The real UI startup diagnostic measured about 2.31s / 2.22s to the Compose-frame marker and peak process-tree working sets of about 347 / 344 MiB (empty isolated session). Peak tree private memory was about 762 / 755 MiB. These are warm-cache startup observations, not tray-idle or GPU presentation benchmarks. A separate controller/worker playback sample is in `playing-memory.json`; that acceptance CLI did not include the Compose UI. No under-100MiB claim is made.

The portable approved Zig/MinGW build replaces the proposed MSVC installation on this laptop. The actual FFmpeg LGPLv3 shared build and hashes are documented in `native/riff-audio/README.md` / `dependencies.json`; notices are bundled with the private image. Complete native transitive notices/corresponding-source release compliance remains Phase 5, not a claim made by this local preview.

The Float32 chain preserves existing desktop TPT transfer equations; it is not the Android seven-manual/three-dynamic EQ layout. Every visible desktop preset now has real coefficients, and the themed crossfade control reaches the worker. Full Android EQ/Automix parity remains later work. Seek favors exact decoded-frame equality over indexed seek speed. HTTP servers ignoring Range have an 8MiB whole-object cap; ranged objects and SABR caches have 128MiB per-source bounds. Decoder caches are ephemeral; persistent offline/library/artwork cache integration is later scope.

Full relational library/playlist/history/lyrics schema and FTS, authenticated account/DPAPI flows, synced lyrics, actual WinRT SMTC publication, hardened tray/power/Discord integration, clean-machine installer/Authenticode and live updater/rollback remain production Phases 2–5. Their component existence is not marked complete here. See `capability-matrix.md`.

## One external Phase 1 acceptance gate

Windows was locked when the real Riff window was selected for computer-use verification. The capture showed the lock screen, so no authentication input was attempted. Unlock was requested while independent build/native/package/PR work continued.

After the owner unlocks Windows, the remaining interactive run is: Ctrl+O import genuine MP3/FLAC/M4A → play/pause and seek through the dock → reorder duplicate queue occurrences → Ctrl+K real search and select → Ctrl+M Mini-Player and restore → quit/relaunch paused at the saved session. Check visible buffering/error feedback and the themed DSP settings. Backend equivalents already pass through the production packaged path; they do not stand in for this UI input/view gate.

Existing M1 matched Android visual captures and physical mixed-DPI/high-refresh checks remain separately on hold under the owner's earlier instruction. They are not relabelled as passing or made prerequisites for the core audio journey.

No account writes, phone changes, production update activation, publishing, pushing or commit was performed. Existing shared-tree Android changes are preserved. The owner's instruction to pursue a whole requested phase, rather than stop at a component increment, is recorded in AGENTS.md.
