# Changelog

Newest first. Release candidates before 0.9.0-rc16 were recorded only in the README; their text is kept here unchanged. Detailed implementation and verification records are in `docs/`.

## Unreleased (improvement plan W1–W8, not yet merged into main)

Work from [the 2026-10-03 improvement plan](docs/improvement-plan-2026-10-03.md), on branches `fix/w1-playback` through `fix/w8-hygiene`. Each branch builds on the previous one.

- **Playback reliability (W1):** QuickJS challenge solving runs in a separate `:solver` process, so a native crash no longer stops playback. SABR attestation recovery is tested and was verified on a device. The app records why the playback service stops.
- **Smoother rotation, dark mode and font changes (W2):** no main-thread disk reads during recreation, and MainActivity handles configuration changes in place (frames skipped in the churn test: 703 → 68).
- **Instant Home groundwork (W3):** the last Home feed is saved per account, and there are skeleton shelves. Not yet visible: the Home screen hookup is pending.
- **Smaller app (W4):** 128.5 → 26.1 MiB (R8, resource shrinking, arm64-only release).
- **Session expiry (W5):** detects when YouTube Music no longer accepts the sign-in, shows "Session expired · Sign in again", and gives a clear message where account features fail.
- **Sleep timer (W6):** keeps its deadline when Android kills the app.
- **Tests and CI (W7):** Robolectric Compose UI tests, GitHub Actions CI with an unsigned release build and a 40 MiB size budget.
- **Build hygiene (W8):** removed the retired `:core:glass` module (tag `archive/core-glass`), added the Unix `gradlew`, and moved this history out of the README.

## 0.9.0-rc16

Fixes native YouTube Music timed-lyrics requests/parsing and mixes a limited selection of Listen again songs into song-first Speed dial.

Details: [docs/app-revamp-rc16.md](docs/app-revamp-rc16.md)

## 0.9.0-rc15

Adds daily licensed mood photographs, 20 designed minimal cover fallbacks, compact playlist/download artwork and artist rows, a visible sleep countdown with a final 15-second fade, route-aware output icons, locally refracted navigation labels, separate Speed dial/Listen again shelves, exact YouTube Music Song/Video pairing and a much larger live-radio catalogue.

Details: [docs/app-revamp-rc15.md](docs/app-revamp-rc15.md)

## 0.9.0-rc14

Adds process-memory feeds with local 6 AM/6 PM refresh, provider-curated Home moods, fresh Library playlist membership with cached details, real playlist editing and multi-playlist saves, collection search/sorting, six widget designs, a sleep timer, immediate queue-handle dragging, 90 Hz support and stable high-contrast navigation.

Details: [docs/app-revamp-rc14.md](docs/app-revamp-rc14.md)

## 0.9.0-rc13

Reduces startup, transition, scrolling, press and palette animation work; fixes seeking in existing downloads; selects original audio before format ranking; orders Downloads newest first; and repairs broken UI punctuation.

Details: [docs/app-revamp-rc13.md](docs/app-revamp-rc13.md)

## 0.9.0-rc12

Introduces grouped Settings, a global haptic switch, optional mobile data for Smart Downloads, a 1–16 GB storage slider, and an album-style Downloads collection headed by the most recently downloaded song’s artwork.

Details: [docs/app-revamp-rc12.md](docs/app-revamp-rc12.md)

## 0.9.0-rc11

Adds verified official song videos, permanent highest-quality audio/artwork/timed-lyrics downloads, a Downloads manager, faster deferred playback, a bounded audio cache, and optional Smart Downloads for up to 250 recent songs.

Details: [docs/app-revamp-rc11.md](docs/app-revamp-rc11.md)

## 0.9.0-rc10

Adds semantic touch haptics with a saved switch, shared background lyrics preloading, a floating navigation selection pill, double-tap tab reset, and authenticated native YouTube live playback that continues when minimized and backgrounded.

Details: [docs/app-revamp-rc10.md](docs/app-revamp-rc10.md)

## 0.9.0-rc9

Adds Android 12 support, complete ordered playlist queues, matching album/playlist pages, smaller compact song rows, persistent search history, on-device English lyrics translation, in-player live/video playback with black video backgrounds, clear frosted category labels, aligned search pills, edge-to-edge scrolling shelves, cinematic startup, and duplicate/queue-scroll fixes.

Details: [docs/app-revamp-rc9.md](docs/app-revamp-rc9.md)

## 0.9.0-rc8

Adds immersive album/artist pages, ordered album queues, the animated inline queue, dominant artwork colors, hardware-blurred frosted glass and intensity settings, YouTube live broadcasts and native song video, podcast genres, Better Lyrics priority, and paused restoration of the last song, position and queue.

Details: [docs/app-revamp-rc8.md](docs/app-revamp-rc8.md)

## 0.9.0-rc7

Adds artist and album discovery, account library browsing with pagination, YouTube Music likes, automatic recommendation queues, live channel radio, Settings, rounded player icons, capsule sliders, and shared player/lyrics transitions.

Details: [docs/app-revamp-rc7.md](docs/app-revamp-rc7.md)

## 0.9.0-rc6

Adds real multi-provider lyrics, word/syllable highlighting when supplied, line-synced fallback, automatic following, lyric tap-to-seek, timing adjustment, and an offline lyrics cache.

## 0.9.0-rc5

Includes player artwork blending, a slim volume track, clean artist labels, real account names/photos, tighter top spacing and liquid-glass categories.

## 0.9.0-rc4

Removed the complete demo catalog, generated audio and artwork, demo search, demo albums and playlists, demo podcast/radio content, demo lyrics, and the prototype gallery/lab routes.

Details: [docs/no-demo-update-2026-09-30.md](docs/no-demo-update-2026-09-30.md)
