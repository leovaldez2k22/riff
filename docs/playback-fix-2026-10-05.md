# Audio interruption fix — 5 October 2026

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

The connected I2217 phone's rc20 logs showed `VISIONOS_SABR__nopo` delivering its
first audio segment, followed by `ATTESTATION_REQUIRED` with zero further media
bytes. Playback briefly started, stopped, and recovered. The recovery first
invalidated attestation but selected the same tokenless bootstrap again; only
the subsequent profile exclusion selected a working stream.

Ordinary online audio now requests direct transport instead of SABR or HLS. Video,
live streams, and downloads retain their existing transport capabilities. A
tokenless SABR profile encountering an attestation failure is excluded immediately;
profiles with a token retain their one attestation refresh.

The audio cache key now distinguishes SABR fragments from direct files and uses
a new namespace. Older entries could mix two different byte layouts for the
same recording, format, and bitrate. Provider playback commands also retain the
format ID and bitrate, so different quality settings cannot share one cache key.
Existing downloads are unaffected. An intermediate HLS build was discarded after
the owner reported a temporary quality drop during phone testing; direct audio
keeps the selected format and bitrate throughout the stream.

## Verification

- All 255 unit/Compose tests passed, with zero failures or errors.
- Debug APK and signed release builds passed. Release lint: zero errors, 90 warnings, five hints.
- Release signing certificate matches `docs/release-signing.md`.
- `AudioContinuityInstrumentation` exercises real provider streams through the
  media service, bypassing downloaded files. It checks forty seconds of continuous
  playback per track, seeks to sixty seconds and checks another ten seconds,
  then moves the activity to the background and checks fifteen more seconds. It fails on a
  player error, buffering interruption, provider recovery, or a backward position
  jump exceeding one second or insufficient total advancement. It waits for a
  second of stable playback before measuring; the small position tolerance
  accommodates MediaController's extrapolation between IPC updates.
- Phone acceptance passed on the same-certificate stress build:

  | Track | Format | Bitrate | Startup | Uninterrupted position before seek |
  | --- | --- | --- | --- | --- |
  | Want To Be Free | WEB_REMIX direct WebM, itag 774 | 282,209 bps | 17.031 s | 41.352 s |
  | Numb | WEB_REMIX direct WebM, itag 774 | 264,082 bps | 3.587 s | 41.405 s |
  | Blinding Lights | WEB_REMIX direct WebM, itag 774 | 268,798 bps | 3.391 s | 41.424 s |

  All three seeks and the additional background check passed. The original
  seven-item listening queue was restored paused. Evidence is saved in
  `artifacts/playback-fix-2026-10-05/phone-direct-continuity.txt`.
- Installed the final signed release in place, retaining the signed-in account
  and queue. Confirmed the installed release is not debuggable. Normal restored-
  queue playback selected `WEB_REMIX__po` direct audio after 14.330 seconds of
  cold preparation, then started without a recovery error.
- The actual release continued playing on the phone's home screen: seven
  samples advanced from 95.418 to 127.010 seconds, all `PLAYING` with no error or
  backward jump. Evidence is saved in
  `artifacts/playback-fix-2026-10-05/signed-release-continuity.json`.

Early harness attempts incorrectly classified sub-second startup clock
corrections as restarts; that assertion was corrected. A subsequent attempt
passed its first track but was interrupted by a new catalog song-selection
command. Neither interrupted attempt is counted as full acceptance.

An initial direct-audio harness launched no activity. The phone froze its cached
process before streaming preparation completed (`do_freezer_trap` on the test
and worker threads). The corrected harness starts the activity before preparing
audio, then tests background playback once the media service is foreground.

The installable test update is saved at
`artifacts/playback-fix-2026-10-05/riff-0.9.0-rc20-playback-fix.apk`, alongside its
checksum. Its SHA-256 is
`031721806a9d57b41dbf2f74cced696eb1dd0fe352d163b45f7ba327ad6f5711`.
This is a local rc20 hotfix build; the public GitHub release has not been changed.
