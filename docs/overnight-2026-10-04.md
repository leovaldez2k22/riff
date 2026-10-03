# Overnight phone run, 2026-10-03 23:00 → 2026-10-04 07:00

Riff 0.9.0-rc18 on the vivo I2217 (Android 16), dummy YouTube Music account, media volume held at 0 all night (`scripts/stress/night-guard.ps1`). App pinning was off, so nothing was pinned; random-input runs used the new `-NoPin` watchdog instead. Evidence: `artifacts/overnight-2026-10-03/` (screenshots, logs, `findings.txt`).

*This report is filled in as the night goes; the final summary is at the top in the morning.*

## Release checks (signed, shrunk rc18 over rc17; data kept)

| Check | Result |
|---|---|
| Install over rc17 | ✅ data and sign-in kept; session check `valid` |
| Song playback (SABR) | ✅ stream ready in 240–290 ms; Up next continues |
| Lyrics | ✅ line-synced (YouTube Music) and word-synced (Better Lyrics) · ❌→fixed: an instrumental said "Couldn't load lyrics, check your connection" |
| Translation | ✅ Malayalam isn't supported by ML Kit; the app asks for the source language, as designed |
| Official video | ✅ Numb plays natively after ~5 s buffering; "No official music video" for a track without one |
| Live radio (HLS + challenge in `:solver`) | ✅ |
| Sleep timer (5 min) | ✅ paused exactly at the deadline (23:20:23 → 23:25:23) |
| Download + offline playback | ✅ 2.9 MB download; plays with Wi-Fi and mobile data off, word-synced lyrics from the download |
| Offline screens | ❌→fixed: Podcasts showed the raw DNS error; now "You're offline. Check your connection and try again." Recovers without restart once online |
| Copy playback diagnostics | ✅ "Playback diagnostics copied" (clipboard can't be read over adb; redaction is unit-tested) |
| Crash notice (W1.5) | ✅ after a forced crash: queue restored paused, no auto-play, notice shown, dismiss works and stays dismissed · ❌→fixed: text was cut off; now "Stopped unexpectedly" (TalkBack reads the full sentence) |
| Voice search | ◐ opens the system microphone prompt (dismissed; nothing granted). Speech not tested |
| Widgets | — not pinned: that would add a widget to the owner's home screen |
| Cookie rotation (W5) | — no `cookies_rotated` events seen yet |

## Phone UI suite (W7.4)

5/5 pass on the stress build after three test-side fixes (waiting for the restored queue; the tab bar's "selected" label is stale under the paused test clock; clean-up taps replaced by the Back key). 50 tab loops: Java heap 22.0 → 22.5 MB.

## Monkey, 5 seeds × 10,000 events (no pinning)

| Seed | rc16 (2026-10-02) | rc18 tonight | Peak PSS |
|---|---|---|---|
| 101 | clean | clean | 516 MB |
| 202 | clean | clean | 546 MB |
| 303 | clean | clean | 580 MB |
| 404 | **native QuickJS crash** at event 4,479 | clean | 615 MB |
| 505 | clean | clean | 710 MB |

No crashes, ANRs or native crashes in 50,000 events.

**Bug found and fixed: stacked main screens.** Peak memory rose with every seed. After a forced GC, 19 activities were alive and Riff's task held 40 copies of MainActivity. A launch whose intent didn't match the task's root (task opened from the notification, a widget or another app; then the launcher icon) added a full copy of the UI, about 15 MB of graphics each. MainActivity is now `singleTask`. Rechecked: 1 activity, 463 MB after GC; UI suite still 5/5.

## Doze (W1.4), 5 runs

Each run: playing silently, Home pressed, Riff removed from the battery-optimisation allowlist, battery "unplugged", deep idle forced for 10 minutes, state sampled every 30 s.

| Run | Queue | Result |
|---|---|---|
| 1 | Up next (songs) | ✅ 20/20 samples playing, 4 tracks played in deep idle (next streams prefetched, ready in 1 ms) |
| 2 | Up next (songs) | ✅ 20/20 playing |
| 3 | "Liked Music", 11 songs | invalid: the playlist reached its last song and stopped, which is correct |
| 3b, 4, 5 | 24/7 live radio | ✅ 20/20 playing each |

No unexpected service stops and no process deaths. Android killed several other apps for low memory during the runs, never Riff. Runs 1–2 were first reported as failures by the harness, because it compared positions across track changes; fixed.

## Network

| Scenario | First run | After fix |
|---|---|---|
| Flap: 8 × Wi-Fi + data off 15 s / on 20 s, then 60 s airplane mode | ❌ a track change fell in an outage → load error (expected), and Riff's Play button then needed **two** taps | ✅ recovers and plays |
| Handover: 3 × Wi-Fi → mobile → Wi-Fi while playing | ❌ couldn't start (same Play bug, plus a harness retry that paused a buffering player) | ✅ playing through all 6 samples |

**Bug fixed: Play after an error.** After a load error the player is idle but keeps `playWhenReady`, so the toggle "paused" on the first tap. An idle player now always prepares and plays (gateway and service). Not fixed, follow-up: the player doesn't retry by itself when the network comes back; one tap on Play is needed.

## Bluetooth note

At 02:27 Riff's output was a pair of Bluetooth headphones ("Noise Airwave Max XR"). The logs show Bluetooth was already on before any random input, and the headphones connected by themselves at 00:43. Volume was 0 throughout. I wrongly switched Bluetooth off at 02:28 and back on at 02:34. `device-state.ps1` now records and restores Bluetooth.

## Cookie rotation (W5 follow-up)

No `cookies_rotated` events in about 4 hours of use: YouTube doesn't send the rotating session cookies on the requests Riff makes (browsers refresh them through a separate Google endpoint). The new code is harmless but has no effect so far; a real fix would need that endpoint.

## Final build

Signed rc18 rebuilt at 03:07 with all fixes: unit tests (209), lint and the certificate check pass; release 26.1 MiB (SHA-256 `F23E6974…`), Lite 38.4 MiB. CI green on `main`.
