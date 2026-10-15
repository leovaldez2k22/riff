# Phase 10 rc3 handoff — 29 September 2026

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Status: **signed rc3 installed; M10 partially verified**. This is an installable personal release candidate, not a claim that every milestone in the implementation plan passed.

## Artifact and build

- App ID `com.musicglass.personal`; version `0.9.0-rc3`, code `4`.
- APK: `artifacts/release/youtube-music-glass-0.9.0-rc3.apk`.
- SHA-256: `5f9481be5f18105d8f42191c2481357351c98ba714a117c03918cee1050d7645`.
- `scripts/build-release-candidate.ps1` passed unit tests, release lint, release assembly, APK signature verification, and the 206-dependency license audit. `scripts/package-source.ps1` produces the corresponding GPLv3 source ZIP. The release signing key stays in ignored `.toolchain/signing/`; back up its keystore and password file together.
- Original demo music and art are labeled Demo. Real YouTube Music rows are browse-only because the provider's SABR playback path returned `RELOAD_PLAYER` before media bytes; the app does not advertise them as playable. Cloud sync, social features, Cast, AI and billing have explicit unavailable states without their required services.

## Phone matrix

| Case | Result |
|---|---|
| Fresh signed release install | Rc1 installed and cold-launched on iQOO Neo 7 Pro, Android 16, gesture navigation, after the user authorized removal of the old differently signed app. That uninstall erased the old app's local data. |
| Same-key update | Rc1 → rc2 → rc3 each installed with `adb install -r` and no uninstall. A demo favorite was set on rc1 and still exposed `Remove favorite` on rc2 and rc3. |
| Startup | Five rc3 cold `am start -W` runs reported 397–722 ms `TotalTime`. This is Android activity launch time, not a measured first-content frame. |
| Player and tabs | Mini-player tap, short upward swipe, and full-player downward swipe changed the expected UI semantics on rc1. Rc3 UI Automator reached all five main destinations, expanded queue, and timed lyrics. ADB screenshots intermittently disagreed with the UI tree. An earlier navigation sequence was interrupted when the phone entered Camera and was discarded. |
| 200% text | Rc2 Home capture showed icon-only bottom destinations, an ellipsized mini title and no nav-label collision. Full-player controls remained reachable by scrolling. A row can remain visible behind the transparent bottom chrome; visual readability needs human review. |
| RTL | A force-RTL capture was made with the global setting restored immediately. The capture did not demonstrate mirrored layout, so the RTL device gate is unverified. |
| TalkBack, reduced motion, long thermal soak | Reduced-motion preference persisted across restart and was restored to off; animation behavior itself was not traced. A five-minute bounded interaction sample ended at 240 MB PSS and 32.0°C, without an observed crash/ANR. TalkBack and a representative long thermal soak are not complete. |
| Frame behavior | Rc2 clear glass: 31/544 missed-deadline frames (5.70%), median 22 ms. Rc2 solid controls: 22/455 (4.84%), median 15 ms. Rc3 clear glass after removing 49-sample interior blur: 16/417 (3.84%), median 16 ms. These short `gfxinfo` loops are comparative evidence, not a thermal or perceptual smoothness guarantee. |

`artifacts/device-checks/M09/20260929-motion-release/` holds the captures, XML, `gfxinfo`, startup and memory outputs. See `phase-9-device-review.md` for the method and limits.

## Remaining milestone boundaries

- M3/M5 streaming alpha: real YouTube Music playback is unavailable due the documented SABR `RELOAD_PLAYER` response. The user previously requested one final attempt and then moving on; no unsupported workaround is claimed.
- M6: local original-demo offline playback was phone-tested, but rapid-copy cancellation/retry and lyric frame timing remain unverified.
- M8: self-contained features are implemented; two-device sync, collaboration, Cast receiver handoff and billing gates require services or devices absent from this project.
- M9: TalkBack traversal, a valid RTL stress test, Baseline Profile, long memory/thermal soak, and full release-like user-journey traces remain open. The measured rc3 loop still missed 3.84% of frame deadlines.
- M10: fresh install and same-key upgrade passed their narrow checks. The complete phone smoke suite, account isolation, permissions and playback listening check are not all verified on rc3, so the full gate remains open.

The APK is a usable personal demo/local-media candidate with explicit unavailable states for unsupported integrations. Public streaming-app readiness is not claimed.
