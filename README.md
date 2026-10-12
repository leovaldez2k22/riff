# Riff

An Android music player with YouTube Music browsing, synced lyrics, offline downloads, automatic crossfades, and customizable glass controls.

[![CI](https://github.com/leovaldez2k21/riff/actions/workflows/ci.yml/badge.svg)](https://github.com/leovaldez2k21/riff/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84)](#build)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Riff is an independent personal project and is not affiliated with Google or YouTube. Online playback and catalog availability depend on the providers.

This repository continues the project under **leovaldez2k21**, preserving its Git history and including the latest local changes. Historical documentation may refer to the previous account. No APK release has been published from this new repository yet; the source currently reports `0.10.0-rc1` (code 22), an unpublished quality candidate containing changes newer than the original rc20 APK. Build identity and validation evidence are described in [the quality guide](docs/quality/build-identity-and-evidence.md).

## Features

- Browse recommendations, search, albums, playlists, artists, podcasts, and live radio.
- Background playback, automatic crossfades, queue editing, and official video counterparts when available.
- Word-synced or line-synced lyrics, timing adjustments, and on-device English translation when supported.
- Individual and collection downloads, optional Smart Downloads, and configurable storage budgets.
- On-device audio, local saves, listening history, sleep timers, and home-screen widgets.
- Independent glass and frosted intensity controls, motion, contrast, haptics, and Data saver.
- Updated sign-in and account switching, playlist sorting, podcast metadata, and crossfade behavior.

See the [feature guide](docs/features.md), [latest sign-in and crossfade notes](docs/sign-in-crossfade-fixes-2026-10-06.md), and [changelog](CHANGELOG.md). Historical verification records describe the builds and changes tested at that time.

## Screenshots

Screenshots from the original rc20 build; newer source changes may differ.

<table>
  <tr>
    <td><img src="docs/images/rc20-player.png" width="250" alt="Riff now playing screen" /></td>
    <td><img src="docs/images/rc20-queue.png" width="250" alt="Queue with visible remove buttons" /></td>
    <td><img src="docs/images/rc20-data-saver.png" width="250" alt="Data saver and appearance settings" /></td>
  </tr>
</table>

## Build

Requirements: **JDK 21**, **Android SDK platform 37.0**, **build-tools 36.0.0**, and **Gradle 8.13** through the included wrapper. Minimum Android API is 31; target API is 36. Release builds target 64-bit ARM phones.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

On macOS/Linux, set `JAVA_HOME` and `ANDROID_HOME` and use `./gradlew`.

Performance benchmarks and the Baseline Profile generator are in `:benchmark`; they run against a separate fixture-fed build (`com.musicglass.personal.benchmark`) and need a physical phone for results that count. See [performance](docs/quality/performance.md).

Debug builds use a different signing key and cannot update an installed release in place. Release signing credentials stay outside Git. CI builds an unsigned release when signing credentials are absent. Never commit keys, passwords, cookies, or signed media URLs.

See [CONTRIBUTING](CONTRIBUTING.md), the [CI workflow](.github/workflows/ci.yml), [release signing](docs/release-signing.md), and the [dependency license inventory](docs/dependency-license-inventory.md).

## Support, privacy, and license

[Report a bug](https://github.com/leovaldez2k21/riff/issues/new/choose) with the app version, Android version, phone model, reproduction steps, and sanitized diagnostics. Review any diagnostics before posting them publicly.

For security issues, follow [SECURITY](SECURITY.md). This personal project has no guaranteed response schedule.

[Privacy and permissions](docs/privacy.md) explains local storage, provider requests, sign-in, translation, and optional voice search. Android cloud backup and device transfer are disabled.

Riff is licensed under **[GNU GPL v3](LICENSE)**. Distribute corresponding source and [third-party notices](NOTICE.md) with the app. See [artwork provenance](licenses/placeholder-art.md) and the dependency inventory.
