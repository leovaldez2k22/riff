# Riff

An Android music player with YouTube Music browsing, synced lyrics, offline downloads, automatic crossfades, and customizable glass controls.

[![CI](https://github.com/leovaldez2k21/riff/actions/workflows/ci.yml/badge.svg)](https://github.com/leovaldez2k21/riff/actions/workflows/ci.yml)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84)](#build)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Riff is an independent personal project and is not affiliated with Google or YouTube. Online playback and catalog availability depend on the providers.

## Availability

The source reports **0.10.0-rc1 (version code 22)**. It is a **prepared, unpublished** release candidate: a signed APK and its source archive can be built and checked locally (see [release notes](docs/release-notes-0.10.0-rc1.md)), but nothing has been published from this repository, and publishing needs the owner's explicit decision. The last APK distributed was 0.9.0-rc20 (4 October 2026), built under the project's earlier account; the candidate is signed with the same certificate and updates it in place.

This repository continues the project under **leovaldez2k21** with its Git history. Older documents may name the earlier account.

## Features

- Browse recommendations, search, albums, playlists, artists, podcasts and live radio.
- Background playback, automatic crossfades, normalize volume, queue editing and sleep timers.
- Word-synced or line-synced lyrics with overlapping lines, background vocals and held-word emphasis, timing adjustment and on-device English translation.
- Song and collection downloads, optional Smart Downloads, storage budgets.
- Separate data for each account, for signed-out listening and for audio on the phone; Privacy and data controls; export and import to another phone.
- On-device audio, local saves, listening history and home-screen widgets.
- Glass and frosted intensity, reduced motion, high contrast, haptics, screen-reader queue actions and Data saver.
- Diagnostics of how the app last closed, shared only by you.

See the [feature guide](docs/features.md) and the [changelog](CHANGELOG.md).

## Screenshots

Screenshots from the rc20 build; the current source differs in places (lyrics, menus, settings).

<table>
  <tr>
    <td><img src="docs/images/rc20-player.png" width="250" alt="Riff now playing screen" /></td>
    <td><img src="docs/images/rc20-queue.png" width="250" alt="Queue with visible remove buttons" /></td>
    <td><img src="docs/images/rc20-data-saver.png" width="250" alt="Data saver and appearance settings" /></td>
  </tr>
</table>

## Phones

Android 12 (API 31) or later; target API 36. The release APK contains native code for 64-bit ARM (`arm64-v8a`) only. A separate `lite` build adds 32-bit ARM (`armeabi-v7a`) for older phones (`scripts/build-lite.ps1`). x86 emulators run the debug build.

## Build

Requirements: **JDK 21**, **Android SDK platform 37**, **build-tools 36.0.0**, and **Gradle 8.13** through the included wrapper (its download is checked against Gradle's published checksum). Dependencies are checked against `gradle/verification-metadata.xml`.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

On macOS or Linux, set `JAVA_HOME` and `ANDROID_HOME` and use `./gradlew`.

### Checks

One entry point runs each check lane the same way locally and in CI (Python 3.10+):

```bash
python scripts/quality/verify.py --lane pr
```

| Lane | Command | Needs |
|---|---|---|
| Pull request | `verify.py --lane pr` | Nothing installed: tests, lint, static checks, the release build and its content checks |
| Emulator | `verify.py --lane emulator --allow-install` | One emulator; its network is turned off |
| Physical device | `verify.py --lane device --allow-install --serial <id> --tier R\|L` | An authorized test phone, named explicitly |
| Benchmarks | `verify.py --lane benchmark --allow-install --serial <id> --tier R\|L` | A phone; emulator timings are refused |
| Packaging | `verify.py --lane packaging [--allow-install --previous-apk <apk>]` | A signed candidate; an emulator for the upgrade check |

Without `--allow-install` nothing touches a device. See [testing](docs/quality/testing.md) and [CI lanes](docs/quality/ci-lanes.md).

### Release candidate

Release signing credentials stay outside Git ([release signing](docs/release-signing.md)). `scripts/build-release-candidate.ps1` builds, signs and checks the certificate (`-Offline`, `-OutDir`, and `-Toolchain` to build a clean worktree with the main checkout's key and Gradle cache; `-Preview` also builds **Riff Preview**, the same release under the package `com.musicglass.personal.preview`, which installs beside an installed release without touching its data); `scripts/package-source.ps1` writes the matching source archive from the files Git tracks. CI builds an unsigned release. Debug builds use a different key and cannot update a release in place. Never commit keys, passwords, cookies or signed media URLs.

Benchmarks and the Baseline Profile generator are in `:benchmark` and run against a separate fixture-fed build (`com.musicglass.personal.benchmark`). See [performance](docs/quality/performance.md).

## Documentation

- [Features](docs/features.md), [privacy and permissions](docs/privacy.md), [security policy](SECURITY.md)
- Quality: [architecture](docs/quality/architecture.md), [testing](docs/quality/testing.md), [security review](docs/quality/security-review.md), [provider compatibility](docs/quality/provider-compatibility.md), [performance](docs/quality/performance.md), [final report](docs/quality/final-report.md)
- [Documentation index](docs/README.md), which separates current documents from historical records

## Support, privacy, and license

[Report a bug](https://github.com/leovaldez2k21/riff/issues/new/choose) once the repository is public, with the app version, Android version, phone model, reproduction steps and a diagnostics report from Settings → About → Diagnostics. Read any report before posting it publicly. For security issues, follow [SECURITY](SECURITY.md). This personal project has no guaranteed response schedule.

[Privacy and permissions](docs/privacy.md) lists what stays on the phone and every network destination. Android cloud backup and device transfer are disabled.

Riff is licensed under the **[GNU GPL v3](LICENSE)**. Distribute the corresponding source and the [third-party notices](NOTICE.md) with the app. See the [dependency license inventory](docs/dependency-license-inventory.md) and [asset provenance](licenses/placeholder-art.md).
