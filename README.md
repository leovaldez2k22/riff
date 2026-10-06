# Riff

An Android music player with YouTube Music browsing, synced lyrics, offline downloads, automatic crossfades, and customizable glass controls.

[![CI](https://github.com/leovaldez2k20/riff/actions/workflows/ci.yml/badge.svg)](https://github.com/leovaldez2k20/riff/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/leovaldez2k20/riff?include_prereleases&label=release)](https://github.com/leovaldez2k20/riff/releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84)](#install)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**[Download rc20 APK](https://github.com/leovaldez2k20/riff/releases/download/v0.9.0-rc20/riff-0.9.0-rc20.apk)** · [Release notes](https://github.com/leovaldez2k20/riff/releases/tag/v0.9.0-rc20) · [All releases](https://github.com/leovaldez2k20/riff/releases) · [Report a bug](https://github.com/leovaldez2k20/riff/issues/new/choose)

Riff is an independent personal project and is not affiliated with Google or YouTube. The current version is a **release candidate**. Online playback and catalog availability depend on the providers.

## Latest development update — 6 October 2026

The changes below are merged into `main` and were tested on an Android 16 phone. **The published rc20 APK above is from 4 October and does not contain these updates.** The tested local update still reports `0.9.0-rc20` (version code 21); a new public APK release has not been published.

| Area | Current behavior on main |
|---|---|
| Downloads | **Delete all downloads** replaces the Downloads settings shortcut, asks for confirmation, and cancels pending transfers. Playlist and album pages offer **Download all songs** after the full collection loads, preserving completed downloads and deduplicating repeats. |
| Storage and cache | Budgets extend beyond 16 GB through **24, 32, 64, 128 and 256 GB**, with **Unlimited** last. Device free-space reserves still apply. **Clear playback cache** asks for confirmation and retains downloaded songs. |
| Crossfade audio | Automatic queue handoffs blend both songs with smooth equal-power gains. Startup and buffering hold progress; delayed updates and an early outgoing end cannot abruptly restore volume. Both players use matching volume-boost processing. |
| Crossfade visuals | Artwork, song title and artist fade for the **entire actual crossfade**, following live audio progress in the player, mini player and queue header. Manual song changes and crossfade-off playback replace them immediately; reduced motion is respected. |
| Search and queue | A cross icon clears search text. Tapping the current-song thumbnail in the queue returns to the expanded player. |
| Lyrics and menus | Lyrics timing defaults and resets to **+0.2 seconds**. Timing controls, three-dot menus, song long-press menus, sorting and language menus use frosted backgrounds. The translation metrics sentence has been removed from the lyrics screen. |
| Appearance | **Frosted glass intensity** has its own slider, separate from Glass intensity. High-contrast controls retain opaque backgrounds. |
| Playback continuity | Direct online audio and separate cache identities address the earlier short playback interruption while retaining the selected format and bitrate. |

**Verified:** 273 JVM/Robolectric/Compose tests across 52 suites, 17 distinct phone instrumentation tests, zero lint errors, and a signed-release queue-navigation check. [GitHub CI passed for the implementation](https://github.com/leovaldez2k20/riff/actions/runs/37412870176). Device tests measured playback, gain progression and visual behavior; they do not establish subjective loudness equivalence between differently mastered songs.

See [the complete update notes](docs/update-2026-10-06.md), [crossfade and UI verification](docs/downloads-crossfade-verification-2026-10-06.md), [playback continuity verification](docs/playback-fix-2026-10-05.md), and [the changelog](CHANGELOG.md).

## Install

The rc20 APK supports **Android 12 or newer on 64-bit ARM (arm64-v8a) phones**. Android 13+ supports liquid refraction; Android 12 uses translucent controls and frosted blur.

1. Download `riff-0.9.0-rc20.apk` from the release's **Assets** section or the direct link above.
2. Open the APK on your phone. If Android asks, allow installation from the browser or file manager you used.
3. Install it. Existing Riff releases using the same signing certificate update in place, retaining app data. Uninstalling deletes app-private downloads, local saves, and settings.
4. Open Riff. Sign in through its YouTube Music screen to access your provider library. Local audio access is requested only when opening On device.

This APK uses the existing release signing key. The stable application ID is `com.musicglass.personal`. Older Lite builds have a separate package and are not replaced by this APK; rc20 currently publishes the standard ARM64 build.

| Release asset | Purpose |
|---|---|
| [Signed APK](https://github.com/leovaldez2k20/riff/releases/download/v0.9.0-rc20/riff-0.9.0-rc20.apk) | Installable app, about 26.2 MiB |
| [Corresponding source](https://github.com/leovaldez2k20/riff/releases/download/v0.9.0-rc20/riff-0.9.0-rc20-source.zip) | Source, build scripts, licenses, and documentation |
| [SHA256SUMS.txt](https://github.com/leovaldez2k20/riff/releases/download/v0.9.0-rc20/SHA256SUMS.txt) | SHA-256 checksums for the APK and source archive |

The release also includes `LICENSE` and `NOTICE.md`. GitHub's automatically generated source archives refer to the release tag. To check a download in PowerShell, use `Get-FileHash .\riff-0.9.0-rc20.apk -Algorithm SHA256` and compare it with `SHA256SUMS.txt`. The public signing certificate fingerprint is in [release signing](docs/release-signing.md).

## What Riff does on main

- Browse recommendations, search, albums, playlists, artists, podcasts, and live radio.
- Play audio in the background, blend automatic queue changes with crossfade, view an exact official video counterpart when available, and control the queue with Play next, reorder, and remove actions.
- Read word-synced or line-synced lyrics, adjust timing from a +0.2-second default, and use on-device English translation when supported.
- Download individual songs or whole playlists/albums, including artwork and available lyrics; optional Smart Downloads use a configurable budget up to Unlimited and network preferences.
- Access on-device audio, local saves, listening history, sleep timers, and home-screen widgets.
- Customize glass and frosted intensity independently, motion, contrast, haptics, and Data saver.

Read the [detailed feature and behavior guide](docs/features.md) for network, cache, queue, download, and account behavior.

## Screenshots

Actual screenshots of the published 4 October rc20 build. They predate the latest download, menu and appearance changes. Album artwork belongs to its respective owners; screenshots illustrate the app interface.

<table>
  <tr>
    <td><img src="docs/images/rc20-player.png" width="250" alt="Riff now playing screen" /></td>
    <td><img src="docs/images/rc20-queue.png" width="250" alt="Queue with visible remove buttons" /></td>
    <td><img src="docs/images/rc20-data-saver.png" width="250" alt="Data saver and appearance settings" /></td>
  </tr>
  <tr><td>Now playing</td><td>Queue controls</td><td>Data saver</td></tr>
</table>

## Published rc20 APK — 4 October 2026

These changes and checks describe the APK currently available in Releases. The newer work on `main` is described above.

- Replaces rejected streams during recovery, invalidates stale prefetched streams, and tries a bounded set of alternate profiles while preserving queue position and user pauses.
- Adds a visible remove button to every queue row, including removal of the final song.
- Adds persistent **Settings → Data usage → Data saver** for lower-bitrate audio and up to 360p video on newly resolved streams. Downloads keep their selected quality.
- Adds NetEase Cloud Music and Lyrics.ovh to the lyrics hierarchy.

Lyrics priority: **Better Lyrics TTML → QQ → NetEase → Kugou → LRCLIB → YouTube Music → Lyrics.ovh**. Timing quality takes precedence: word > line > plain; provider order breaks ties. NetEase may require verification and then falls through to later providers. Matched NetEase lyrics were not verified live on the test phone. Lyrics.ovh provides plain text. Megalobiz and Spicy Lyrics are excluded from this release.

Validation: **253 unit/Compose tests**, **eight phone tests**, a **12-song playback run** including Data saver, and **six-song playback on the signed release** passed. Release lint has zero errors; existing warnings remain. The signed release also recovered automatically from a real SABR attestation failure. See [the rc20 verification record](docs/app-revamp-rc20.md) and [CHANGELOG](CHANGELOG.md).

## Build and contribute

Requirements: **JDK 21**, **Android SDK platform 37.0**, **build-tools 36.0.0**, and **Gradle 8.13** through the included wrapper. Minimum Android API is 31; target API is 36. JDK 21 is needed by the InnerTubeX classes used in tests.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

On macOS/Linux, set `JAVA_HOME` and `ANDROID_HOME` and use `./gradlew`. The Unix wrapper must be executable (`chmod +x gradlew` if needed).

Debug builds use a different signing key and cannot update an installed release in place. `scripts/build-release-candidate.ps1` signs releases using the maintainer's ignored local keystore and checks its public certificate; without signing credentials, CI builds an unsigned release. Never commit keys, passwords, cookies, or signed media URLs. `scripts/package-source.ps1` packages corresponding source.

See [CONTRIBUTING](CONTRIBUTING.md), the [CI workflow](.github/workflows/ci.yml), and [dependency license inventory](docs/dependency-license-inventory.md).

## Support, privacy, and license

Use [GitHub Issues](https://github.com/leovaldez2k20/riff/issues/new/choose) for bugs and feature requests. Include the app version, Android version, phone model, reproduction steps, and sanitized playback diagnostics when relevant. **Settings → About → Copy playback diagnostics** provides a bounded event log; review anything you post publicly.

For security issues, use [private vulnerability reporting](https://github.com/leovaldez2k20/riff/security/advisories/new). See [SECURITY](SECURITY.md). This personal project has no guaranteed response schedule.

[Privacy and permissions](docs/privacy.md) explains local storage, provider requests, sign-in, translation, and optional voice search. Android cloud backup and device transfer are disabled. App-local saves and downloads are account-scoped and do not provide cross-device sync.

Riff is licensed under **[GNU GPL v3](LICENSE)**. Distribute corresponding source and [third-party notices](NOTICE.md) with the app. See [artwork provenance](licenses/placeholder-art.md), the dependency inventory, and the licenses included in the source archive. Historical implementation reports remain in [docs](docs/).
