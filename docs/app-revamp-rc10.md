# Riff rc10: haptics, navigation and native radio

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Version 0.9.0-rc10, version code 11. October 1, 2026.

## Changes

- Semantic Android haptics cover navigation, player/lyrics transitions, transport, category selection, settings, queue drag/swipe, slider pickup and release, and successful favorite/save/queue actions. Volume ticks follow actual rounded volume steps; glass intensity uses landmarks. Routine scrolling, typing and automatic track changes remain silent. Feedback respects Android touch settings, a saved app switch, lifecycle and accessibility behavior; Settings includes a preview. No vibration permission or forced system-setting override is used.
- Lyrics begin loading when song playback starts. A bounded shared result/job serves the lyrics page, avoiding duplicate provider requests. Retry cancels the previous generation and guards stale completion. Better Lyrics remains the first provider; fallback providers run only when it has no usable result.
- The bottom navigation selection lifts and enlarges during dragging, follows the finger between tabs, and settles with a spring on release. Keyboard and accessibility selection remain available. Double tapping Home, Podcasts, Radio or Library returns that tab to its default root and resets its scroll state; Search focuses its field and keyboard. Other tabs retain their state.
- Radio uses native Media3 YouTube HLS with attestation, manifest-path tokens and solved n challenges. The full player uses app-owned controls on black, without an embedded YouTube control panel. Detaching the video surface for the mini player or another app leaves service audio running. The previous premature embedded-renderer fallback and misleading YouTube Music error were removed. Transient live retries suppress premature error notices; an unavailable final stream shows a retry state.
- Live playback does not expose a moving seek position. The shared combined HLS stream is not requested twice as separate audio and video sources.

## Verification

The final signed release passed 73 unit tests, with zero failures, errors or skips. Android lint reported zero errors and 53 warnings. The APK signature verifies with one signer using APK Signature Scheme v2. Tests cover tab reset semantics, semantic slider feedback, shared/cancelled lyric loads and HLS path normalization. A pre-existing cancellation test now waits for transport entry instead of relying on a short delay.

Physical verification used the iQOO I2217 running Android 16. Lofi Girl deep sleep radio displayed native video and reported PLAYING in the full player, after collapsing to the mini player, after returning to Android Home, and several minutes later after a USB interruption. Actual device haptic records identify this app with TOUCH usage and normal flags. Settings disabled the haptic preview when switched off and restored it when switched on. Double-tap checks reset Home settings, the selected podcast genre and Library filter; Search focused the text field. Dragging the navigation selection to Radio selected Radio on release.

Native song playback was also checked with Numb by Linkin Park: the media session reported PLAYING and a seven-item recommendation queue. On first opening lyrics after playback, the document was already present and the captured UI showed Better Lyrics with Word synced timing. Automated tests establish shared-request behavior; provider latency still varies by song and network.

Evidence is retained on the development computer under artifacts/ux-rc10, including verification.json and screenshots. Earlier failed transport attempts remain in the chronological evidence and are superseded by the later native playback checks. Android 12 remains the minimum supported platform; rc10's new runtime was tested physically on Android 16. The rc9 report contains the earlier Android 12 emulator checks. These checks do not establish playback availability for every song, channel or device.

## References and licensing

The token runtime adapts the GPL-3.0 InnerTubeX harness; attribution appears in NOTICE.md and corresponding source accompanies the release.

- [Android haptic principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)
- [Android semantic haptic feedback](https://developer.android.com/develop/ui/views/haptics/haptic-feedback)
- [Media3 background playback](https://developer.android.com/media/media3/session/background-playback)
- [YouTube HLS path handling in yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py)

## Final installed build

- `installed_version`: `["versionCode=11 minSdk=31 targetSdk=36", "versionName=0.9.0-rc10"]`

- `final_build_radio_start`: `{"state": "PLAYING", "position_ms": -1, "description": "24/7 deep sleep music \ud83c\udf0c calm ambient to sleep & dream to, Lofi Girl, null", "queue_size": 1}`

- `final_build_minimized`: `{"state": "PLAYING", "position_ms": -1, "description": "24/7 deep sleep music \ud83c\udf0c calm ambient to sleep & dream to, Lofi Girl, null", "queue_size": 1}`

- `final_build_background`: `{"state": "PLAYING", "position_ms": -1, "description": "24/7 deep sleep music \ud83c\udf0c calm ambient to sleep & dream to, Lofi Girl, null", "queue_size": 1}`

- `final_paused_restore`: `{"before": {"state": "PAUSED", "position_ms": -1, "description": "24/7 deep sleep music \ud83c\udf0c calm ambient to sleep & dream to, Lofi Girl, null", "queue_size": 1}, "after": {"state": "NONE", "position_ms": 0, "description": "24/7 deep sleep music \ud83c\udf0c calm ambient to sleep & dream to, Lofi Girl, null", "queue_size": 1}, "mini_player_visible": true, "play_description": ""}`

- `temporary_phone_files`: `{"paths": ["/sdcard/Download/riff-rc9-debug.xml", "/sdcard/Download/riff-rc9-debug.png"], "remaining": []}`

- `phone_locked`: `{"wakefulness": ["mWakefulness=Asleep"], "keyguard": ["KeyguardServiceDelegate", "showing=true"]}`
