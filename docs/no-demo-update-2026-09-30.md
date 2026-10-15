# Demo-content removal — 2026-09-30

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Version 0.9.0-rc4 (versionCode 5) removes all app-bundled demo content. Installed successfully over the existing release on the iQOO I2217 (Android 16) with `adb install -r`, using the existing signing key.

## Removed

- Ten original audio files and the generated song/album artwork; silent prototype videos and fixture art.
- Fixture catalog and demo search/source filters, albums, artists, saved mixes, collections, history, podcast episodes, radio/video fixtures, lyrics and credits.
- Default demo playback queue, offline demo commands and stores, generated catalog benchmarks, prototype gallery/lab routes, and the app's core/glass dependency.
- All fallback demo recommendations, artificial repeated speed-dial songs, and hypothetical demo menu actions.

The playback service and gateway now serve only real YouTube Music streams and on-device audio. The player starts without a media item. Home consumes real provider shelves, saved real favorites/history, and genuine provider artwork. Search results play through the provider; Home collection cards browse the actual provider collection. Empty/error states contain no sample media.

## Upgrade behavior

The first launch migrates only real provider saves and the history preference from the former mixed collection preferences. It clears retired demo preferences, podcast progress, saved demo playback state, and the app-private offline_demos folder. Provider session/cookies and accessibility preferences are preserved. Restoring old navigation drops retired routes and never fabricates playback.

## Validation

- Final `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease`: BUILD SUCCESSFUL.
- Ten tests passed: artwork handling, gesture release, navigation upgrade parsing, and tab hit testing. Lint: zero errors, 38 warnings and four hints.
- APK v2 signature verified. Static ZIP/DEX audit found zero retired resource entries, demo app classes or catalog strings. The Coil network fetcher remains included. Audit: `artifacts/device-checks/no-demo-20260930/apk-audit.json`.
- Same-key release installed with app data preserved; package reports versionCode 5 and versionName 0.9.0-rc4.
- Phone Home initially showed real provider music/albums with no preloaded mini-player. Search displayed real Party music results without a demo source or demo rows.
- Library contained actual account/library entry points, with no generated albums, artists, saved mixes or demo offline route. On this session the provider account had no usable account scope for app-owned saves, so migration of an existing populated real favorites list was not independently verified on-device.
- Little Dark Age opened the actual MGMT collection (She Works Out Too Much, Little Dark Age, When You Die, Me and Michael, etc.).
- Actual Tenno audio played; later capture shows it paused at 73,680 ms with 180,041 ms buffered, one real item, and no playback error. Playback left paused.
- Player artwork inspected visually: genuine high-resolution Tenno art. Podcasts and Radio contained search entry points and no fixtures.

Phone screenshots and semantic snapshots: `artifacts/device-checks/no-demo-20260930/`. Build artifacts: `artifacts/release/youtube-music-glass-0.9.0-rc4.apk` and corresponding source archive. APK SHA-256: `0ff58932ce769fd4248e32acdc44e8e354c8051975d878a3e1a4e36565140656`.

Historical release artifacts and prototype documentation remain outside the application and are not bundled as app content. Real provider availability remains dependent on the session and selected media.