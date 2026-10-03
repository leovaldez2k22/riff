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

*(in progress)*
