# Overnight phone run, 2026-10-03 23:00 → 2026-10-04 07:00

Riff 0.9.0-rc18 on the vivo I2217 (Android 16), dummy YouTube Music account, media volume held at 0 all night (`scripts/stress/night-guard.ps1`). App pinning was off, so nothing was pinned; random-input runs used the new `-NoPin` watchdog instead. Evidence: `artifacts/overnight-2026-10-03/` (screenshots, logs, `findings.txt`).

## Summary

- **rc18 is installed on the phone** (signed release, your data kept) and tagged on GitHub as `v0.9.0-rc18`.
- **Passed:** release checks, phone UI suite (5/5), 50,000 random-input events with no crash (rc16 had a native crash on one of the same seeds), 5 Doze runs, network flap and handover, 45-minute soak.
- **Six app bugs found and fixed tonight:** stacked copies of the main screen (memory up to 710 MB), Play needing two taps after a network error, no automatic resume after a network drop, "Couldn't load lyrics" on instrumentals, the raw DNS error offline, and the crash notice text being cut off.
- **Not tested:** pinning widgets (would change your home screen), voice search speech, Lite playback (needs sign-in).
- **Your phone:** every setting is back as it was, except **media volume, which is still 0**. Turn it up yourself, or ask me to run `device-state.ps1 -Action RestoreVolume` (back to 9). Bluetooth: on, as before (see the note below).


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

## 45-minute soak

✅ Continuous silent playback for 45 minutes: 0 restarts, every sample playing, PSS 252 → 242 MB (no growth), no crash or ANR. (A first attempt was invalid: the network dropped as it began and the harness's play helper couldn't press Play, see below; the app recovered on one manual tap.)

## Network auto-recovery (added tonight)

The invalid soak showed playback sitting in a network error for 55 minutes. Riff now resumes by itself: after a playback error caused by a lost connection it retries at 5, 20 and 60 s and whenever a network becomes available, at most 5 times, and only if you hadn't paused. Checked on the phone at 04:58: a track failed to load offline; the network came back at 04:59:12 and playback resumed at 04:59:14 with nobody touching it. Two things had to be fixed before it worked: the track loader dropped the original cause of the failure, and the provider reports an outage as "Track unavailable: NETWORK".

## Riff Lite

Installs beside Riff and launches in 418 ms; Home, Search and navigation work without the glass effect. Signed out, YouTube Music answers "not available in your country" and search returns no songs, so playback in Lite needs a sign-in and wasn't tested. Uninstalled afterwards.

## Final build and smoke test

Signed rc18 rebuilt at 05:06 with every fix: 212 unit tests, lint and the certificate check pass. Release 26.1 MiB, SHA-256 `666C26E26714982E88A61CC54C5E84CD96DC2CBDA378465CCBE63D4231AAC7F8`; Lite 38.4 MiB, `6BCFC4EF…`. Installed over the test build (data kept) and checked: starts in 344 ms; the instrumental now shows "Instrumental · This recording has no sung lyrics"; the crash notice reads "Stopped unexpectedly"; offline Podcasts says "Cannot reach music.youtube.com on this network". UI suite 5/5 on the same code.

## Harness fixes (no effect on the app)

`run-monkey.ps1 -NoPin` (watchdog instead of pinning), `scenarios.py` presses play with Riff's own button (never `cmd media_session dispatch play`, which once opened YouTube) and reads the screen with `adb exec-out`, `background_doze` handles track changes, `device-state.ps1` records Bluetooth.
