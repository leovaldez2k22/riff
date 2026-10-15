# Riff overnight stress test — 2026-10-02 23:00 to 2026-10-03 06:00

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Unattended USB stress and bug-fix pass on a vivo I2217 (Android 16, 7.5 GB RAM, 120 Hz), signed in to a dummy YouTube Music account. The phone was kept silent (media, system and notification volume held at 0 by an on-phone guard; ring and alarm untouched) and awake on charge.

## Summary

| | |
|---|---|
| Random input | 8 seeds × 10,000 events (74,479 injected): **1 native crash**, 0 Java crashes, 0 ANRs |
| Targeted scenarios | 10 run (8 pass outright, network flap fixed, Doze stopped once of 2); 2 UI scenarios not run (phone locked) |
| App bugs fixed | **3** (one high-severity playback failure, two main-thread stalls) |
| Open issues | 1 high (native crash in a dependency), 1 unexplained one-off service stop, performance notes |
| Code | 15 commits on top of baseline `bbe1236` (new git repository created for this run) |

## Findings

### F1 — Native crash in QuickJS during YouTube challenge solving (high, open)

Seed 404, event 4,479. `SIGSEGV` (null pointer, address `0x18`) on a `DefaultDispatcher` worker. The whole app process dies, including playback.

- Native: `libquickjs.so` `JS_DefineProperty` ← `JS_DefinePropertyValue` ← `build_backtrace` ← `JS_CallInternal` (deeply recursive).
- Java: `com.dokar.quickjs.QuickJs.evaluate` ← innertubex `cipher.QuickJsEngine.evaluate` ← `EjsChallengeSolver.solveOnce` ← `YouTubeCipherService.processFormats`.
- Context: Java heap full (136/136 MB) with 64 MB of large objects just allocated (the ~2.6 MB player script); QuickJS memory limit 192 MB, stack limit 256 KB. innertubex already serialises engine access with a mutex, so this is not concurrent use. It looks like an allocation or stack-limit failure while QuickJS builds an error backtrace.
- Frequency: once in 74,479 events.
- Versions: innertubex 0.7.2, quickjs-kt 1.0.14. quickjs-kt 1.0.15 only changes Unicode string conversion, so upgrading is not a known fix.
- Recommendation: report upstream (innertubex and quickjs-kt) with the tombstone; consider running challenge solving in a separate process so a native crash cannot take playback down.
- Evidence: `artifacts/stress/monkey-main/seed-404-monkey.txt` (tombstone near line 1865), `seed-404-logcat.txt`.

### F4 — Playback stayed broken after network drops (high, fixed `259104b`)

In the network-flap scenario, after the first reconnect YouTube's SABR stream answered `ATTESTATION_REQUIRED`. ExoPlayer failed with "SABR playback failed". Pressing play re-resolved the stream (same `VISIONOS_SABR__nopo` profile) and failed again within 4 seconds. Two later scenarios could not start playback at all. A freshly started process played immediately with a newly minted token, so the stale state was inside the app, not a server-side block.

Cause: Riff never applied innertubex's SABR recovery (`sabrRecoveryAction`: refresh attestation, then exclude the profile). `SabrAudioDataSource` also replaced the library error with a bare `IOException`, which dropped the failure kind. `AndroidStreamTokenProvider.invalidateAttestation()` existed but had no caller.

Fix: the data source keeps the failure kind (`SabrPlaybackFailure`; the message still excludes the library text, which can contain signed URLs). On `ATTESTATION_REQUIRED`, the playback service invalidates the minted attestation and the cached stream once per track, then re-prepares at the same position.

Verification: after the fix, three network-flap runs and one Wi-Fi → mobile → Wi-Fi handover run all recovered playback. **None of them re-triggered `ATTESTATION_REQUIRED` (1 in 4 runs overall), so the new recovery branch has not yet run on the device.** It is narrowly scoped: it only runs for a SABR failure that innertubex itself labelled `ATTESTATION_REQUIRED`, and only once per track.

Evidence: `artifacts/stress/scenarios/network_flap-before-fix/logcat.txt`.

### F3 — Queue requests decoded on the main thread (medium, fixed `2613c81`, verified)

The playback service read and decoded the queue-request file (up to ~6 MB for 2,000 tracks) inside `onCustomCommand` on the main thread: 323 StrictMode reports in one 10,000-event run, at up to 77 ms per tap. Decoding now runs on a single-lane IO dispatcher, so successive requests keep their order; the queue is still applied on the main thread.

Device verification (new `stress_queue` instrumentation, real gateway → service path): 40-track queue applied in 188 ms, 2,000-track queue in 3.8 s, three back-to-back requests resolved to the newest one and stayed there, Play next inserted after the current track, and **0** main-thread queue reads.

### F2 — Lyrics connections closed on the main thread (low, fixed `7323dd2`)

`LyricsRepository.close()` runs from `onDispose` on every activity recreation (rotation, dark mode, font size). `ConnectionPool.evictAll()` writes TLS close notifications, which StrictMode flagged as network I/O on the main thread (68 ms). Eviction now runs on a background thread.

### F5 — Playback service stopped once during Doze (unexplained, open)

In the first Doze run (battery unplugged in software, app removed from the doze whitelist, deep idle forced), the playback service was destroyed 27 seconds in while audio was active. `onTaskRemoved` was not called and the process stayed alive. The service was a proper `mediaPlayback` foreground service. The system log buffer that would show who stopped it had already rotated. A second run with that buffer captured played for the full 10 minutes in deep idle without a stop. Worth watching on battery; not reproduced.

## Scenario results

All on the final stress build unless noted. "Locked" means the phone was on its secure lock screen (see harness notes).

| Scenario | Result |
|---|---|
| rapid_skip: 150 next + 50 previous at 250 ms | Pass |
| play_pause_storm: 200 toggles at 100 ms | Pass |
| Restore after process kill, paused | Pass: same song, position +44 ms, restored paused |
| Restore after process kill, playing | Pass: same song, position +1.2 s, restored paused |
| network_flap: 8 Wi-Fi/data drops + 60 s airplane mode | Failed before F4 fix; passed 3/3 after |
| network_handover: 3 × Wi-Fi → mobile → Wi-Fi | Pass: playing throughout |
| trim_memory_background: 7 trim levels while backgrounded | Pass: kept playing |
| background_doze: 10 min deep idle on simulated battery | 1 stop (F5), then 20/20 samples playing |
| stress_queue (instrumentation) | Pass, see F3 |
| playback_soak: 75 min | Pass on substance: 26 track plays, 0 player errors, 0 crashes; PSS 199 → ~150 MB, no growth. The harness check flagged "not playing" samples because the queue was an 11-track collection, which ends without automatic Up next by design. |
| config_churn (rotation, dark mode, font, density) | Not run: needs the UI, phone locked |
| nav_leak (tab loop, heap trend) | Not run: needs the UI, phone locked |

## Performance notes (not changed)

- Activity recreation costs up to ~490 ms on the main thread: `LyricsRepository` construction (new OkHttp client per activity), `StartupLogo` disk read and the preferences directory check in `ContentMigration`. Recreations happen on rotation, dark mode and font changes. Making `LyricsRepository` process-scoped would remove most of this; it was not changed overnight because the UI could not be tested.
- `SearchHistory` loads preferences on the main thread (112–340 ms frame).
- `OfflineMusicStore.artwork` reads from disk on the main thread (up to 50 ms).
- Memory under random input peaked at ~545 MB PSS, but after a forced GC there was 1 activity and a 32 MB Java heap: no activity leak. Most memory is native (player, WebView, image decoding) and system.
- `AndroidTokenRuntime` creates its headless WebView with the application context; StrictMode flags it, but it is intentional and harmless.

## Harness notes and incidents

- **First ANR was caused by the harness.** StrictMode `detectActivityLeaks()` forces a blocking `System.gc()` on every activity destroy; with rotation events and a large heap it froze the main thread. Removed (`ba1354a`), seed rerun clean.
- **The phone locked at 00:48:51.** Random input swiped vivo's bottom gesture bar while the app was pinned; on vivo that gesture exits screen pinning, and Android's lock-on-unpin then showed the secure lock screen. The screen itself never slept (the guard and stay-awake worked). No unlock attempts were registered. The phone was not unlocked; from then on, scenarios drove the playback service directly, and UI scenarios were skipped. For future runs, turn off "ask for PIN before unpinning" or use an unlocked test phone.
- vivo ignores `cmd media_session volume --set`; `cmd audio set-volume` works. `monkey --monitor-native-crashes` reports old (August) tombstones as new on this phone.
- Random input changed app settings (Smart Downloads off, solid controls on, glass intensity, search history, local saves, listening history). These were restored from the pre-test backup (see below). Account-side changes on the dummy YouTube Music account (likes, playlist additions) may also have happened and were not reverted.

## State of the phone at handover

- **App:** release build with this night's fixes installed over the stress build at 03:39, keeping app data: `artifacts/stress/final/riff-0.9.0-rc16-stressfix.apk`, Riff 0.9.0-rc16 (version code 17), same signing certificate (`ac70…f6e1`), not debuggable, no stress or preview code. SHA-256 `29081104878C34872052931F36ED3C28CFB00B2BCEDFC73EA91F72B63DC67953`. Unit tests and release lint pass. It started cleanly with no crash.
- **Rollback:** the exact app that was installed before the run is saved as `artifacts/stress/original-rc16-installed.apk` and installs over this one with `adb install -r`, keeping data.
- **App data:** settings, search history, local saves, listening history, offline catalog database and last-played session restored from the 22:55 backup (checksums matched). The last-played session was already empty in that backup, so the player opens with no song loaded. Sign-in tokens were left as they were. The backup itself (`artifacts/stress/app-data-backup-2026-10-02.tar`, contains sign-in data) is still on the PC; delete it once the app is confirmed working.
- **Phone settings:** every recorded setting matches the 22:41 baseline (stay-awake 15, rotation, font, density, dark mode, timeout, Wi-Fi, mobile data, airplane mode, doze whitelist, standby bucket). Temporary files removed, night guard stopped, screen pinning off.
- **Volume is still muted on purpose:** media 0 (was 12), system 0 (was 11), notifications 0 (was 0). Ring and alarm were never touched. To put them back: `scripts/stress/device-state.ps1 -Action RestoreVolume`, or use the volume keys.
- **Lock screen:** the phone has been on its lock screen since 00:48 and was not unlocked.
- **Test package:** `com.musicglass.personal.test` (present before the run) was updated with the new `stress_queue` mode.

## How to reproduce

- Random input: `scripts/stress/run-monkey.ps1 -Seeds 404 -Events 10000` (stress build installed; app is pinned during the run).
- Scenarios: `python scripts/stress/scenarios.py <name>`; queue check: `adb shell am instrument -w -e mode stress_queue com.musicglass.personal.test/com.musicglass.personal.OfflineSmokeInstrumentation`.
- Phone settings: `scripts/stress/device-state.ps1 -Action Save|Prepare|Restore|RestoreVolume`; guard: `scripts/stress/night-guard.ps1 [-Stop]`.

Logs, tombstones and bug reports are under `artifacts/stress/` (not in git).
