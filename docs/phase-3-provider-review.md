# Phase 3 provider and playback review

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

## Decision

The user approved GNU GPL version 3 for this personal app. InnerTubeX 0.7.1 is pinned as the
unofficial YouTube Music adapter. It uses private InnerTube endpoints and can break when the
service changes. The app remains an independent GPL-3.0 project. See [NOTICE.md](../NOTICE.md).

## Implemented paths

- **Local demo:** four original 12-second PCM loops generated in this repository. A single
  Media3 `MediaSessionService` owns playback. It handles Android audio focus, headphone removal,
  notification/lock-screen metadata, repeat, shuffle, seek, and persistent position/queue settings.
  Favorites, a saved demo mix, and history are app-owned local data.
- **YouTube Music experiment:** anonymous WEB_REMIX search requests song rows and maps any
  returned video IDs. The adapter can extract direct audio and pass a short-lived signed URL to
  the same service, but no provider playback has been verified on the test phone.
  The service checks its HTTPS host and limits forwarded request headers. It never logs URLs,
  cookies, or token values. HLS, SABR, and bounded-range streams are rejected with a visible
  error until their transport can be implemented and tested.

## Deliberate limits

- No YouTube account login or credential storage is present yet. This excludes private library,
  uploads, account playlists, entitlement-only tracks, and token-expiry recovery.
- Provider playback currently accepts one resolved track at a time. The demo path owns the
  deterministic four-track queue. Provider queue/next and durable signed-URL refresh remain open.
- The app does not silently substitute demo audio for a failed YouTube Music request.
- The M3 streaming-alpha gate remains open until supported tracks play on device, interruption
  and service recovery work, and a 30-minute provider background session is checked.

## Device check, 27 September 2026

- `:app:assembleDebug` and `:app:lintDebug` passed. The resulting APK was installed on the
  iQOO I2217 running Android 16 through USB debugging.
- The demo Media3 session played with the screen off and exposed track metadata to Android.
  A longer run was interrupted by another app requesting audio focus, so the 30-minute gate
  was not established. Service recovery and interruption policy still need controlled checks.
- Search queries for known public music returned HTTP 200 from anonymous WEB_REMIX, but its
  response on this phone contained artist/playlist rows without playable song IDs. A second
  request using the documented Songs filter returned no song shelf. The app now reports
  “Song catalog unavailable on this device” instead of presenting an empty result as a
  successful catalog search. Stream resolution and playback remain unverified.
- Demo matches and online song search are now explicitly labeled in the Search screen.
  Online queries do not imply that those tracks exist in the four-track demo collection.
- The floating bar selected tabs by tap and press-slide. On Home, a scroll reduced its tab
  bounds from 160 to 132 device pixels; a tap expanded them again. The page gradient is visible
  behind the status icons, and the glass surfaces use no black overlay tint. See
  [the screen capture](../artifacts/device-checks/M03/current/m03-collapse.png) and the
  [expanded](../artifacts/device-checks/M03/current/m03-home-expand.xml) and
  [collapsed](../artifacts/device-checks/M03/current/m03-home-collapse.xml) UI hierarchies.

**Gate status:** Phase 3 is a playback prototype. M3 remains open because provider playback,
the 30-minute background run, and service recovery have not passed on device.

## Build and source

InnerTubeX: https://github.com/MetrolistGroup/innertubex/tree/v0.7.1

Android Media3 background playback guidance: https://developer.android.com/media/media3/session/background-playback

The app compiles with SDK 37.0, Kotlin 2.4.10, and Android Gradle Plugin 8.13.2. The target SDK
remains 36. InnerTubeX is fetched from JitPack as a pinned dependency and its GPL notice is in
the project root.
