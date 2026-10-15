# Phase 6 lyrics, podcasts, and offline media review — 28 September 2026

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Phase 6 follows the user's requested final real-streaming attempt. Authenticated YouTube Music search returned real rows, but the selected SABR stream returned `RELOAD_PLAYER` on its first media request and again after one fresh-player-response retry. The M5 streaming gate remains open. Phase 6 does not represent YouTube Music tracks as downloadable or playable offline.

## Implemented

- Original timed demo lyrics remain seekable by line. White Static has an untimed layout, and instrumental Distant Carrier has an explicit unavailable state. Manual scrolling pauses auto-follow until “Return to current line” is tapped. Sharing lyrics is offered only for original demo words.
- Podcasts has two original demo shows and four instrumental episodes. Episode detail offers Resume and Start from beginning. Position and completion persist locally, and Podcasts shows a Continue listening entry.
- Library → Offline exposes save, cancel, retry, play, and delete for app-owned original demo audio. Copies are stored in app-private files. A saved copy is shown as ready only after a complete write; SHA-256 and length are checked again before playback. Partial files are cleaned on cancellation and app initialization. The screen shows saved count and storage size, and labels these as copies of original demos.

## Checks completed on the connected phone

- Podcasts showed both shows and their episode rows. Low Orbit began playing, was paused at about 1.6 seconds, and showed “Resume at 1s.” After force-stop/restart, Continue listening still offered the saved position.
- White Static opened the untimed lyrics screen without a visible layout failure.
- Offline Afterglow Circuit changed from Available to save to Saved · ready offline and appeared as “Offline demo” in the mini-player. The saved state and 375 KB storage count survived force-stop/restart. With airplane mode enabled, the media session reported `PLAYING` at 0 ms and again at 2,638 ms, with 12,000 ms buffered and no error. Airplane mode was restored to its original off state immediately afterward.
- Deleting that copy returned the row to Available to save, removed its Play action, reset storage to 0 KB, and left the app-private offline directory empty.
- A simulated `demo_0.part` file was placed in the app-private offline directory. After force-stop/restart, the directory was empty, confirming interrupted-copy cleanup. Low Orbit remained selected and paused after that restart.
- Distant Carrier displayed “No lyrics for this instrumental demo.” Timed Pine Echo lyrics opened with four rows; tapping its third row sought the media session to 6,000 ms. Manual scrolling revealed “Return to current line,” and tapping it resumed follow mode. The test ended with Low Orbit selected and paused, as before the Phase 6 checks.
- `:app:assembleDebug` and `:app:lintDebug` passed on the final APK, including the artwork morph correction for the “Offline demo” source. The APK was installed on the phone.

## Open verification

- The timed fixture's line seek was exact at 6,000 ms. Frame-level lyric highlight timing against playback boundaries was not measured, so the plan's approximate 100 ms target remains unverified.
- The original demo copies satisfy the permitted local-copy path. A remote-provider download path is unavailable while the YouTube Music stream transport is unsupported. Translation, remote lyrics sharing, and remote offline media remain disabled.
- Cancellation and retry have code paths and visible controls, but the bundled clips copy too quickly for a reliable manual cancellation test; do not claim those device-tested.

The M6 gate remains open pending the listed checks.
