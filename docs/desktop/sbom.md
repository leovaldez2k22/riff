# Software Bill of Materials (SBOM) — Riff Desktop

## Release Metadata
- **Package Name**: Riff Desktop Preview
- **Version**: 0.1.0 desktop (generated runtime identity matches native package; Android version is separate)
- **Target OS**: Windows 11 x64 initially
- **Generated**: November 2026
- **Architecture**: Modular Compose Multiplatform JVM with isolated Audio Worker runtime

---

This is a manually maintained inventory, not a complete generated/audited SBOM. Runtime versions below were checked against the local packaged jars on 2 November 2026. Authentication/installation claims are limited as described below.

## Direct Dependencies and Component Inventory

| Component Name | Version | Supplier / Origin | License | Purpose | Pinned Hash / Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **OpenJDK JRE** | 21.0.12.1+1 | Eclipse Adoptium / Temurin | GPLv2 with Classpath Exception | Embedded runtime environment for packaged `.exe` and `.msi` | Verified toolchain JDK 21 |
| **Kotlin Compiler** | 2.4.10 | JetBrains | Apache-2.0 | Language compiler and runtime library | `gradle/verification-metadata.xml` |
| **Compose Multiplatform** | 1.12.2 | JetBrains | Apache-2.0 | Desktop UI toolkit and state management | JetBrains Compose Multiplatform plugin |
| **Skiko** | 0.150.2 | JetBrains / Google | BSD-3-Clause | Hardware-accelerated Skia rendering engine | Bundled with Compose Desktop runtime |
| **Kotlinx Coroutines** | 1.11.0 | JetBrains | Apache-2.0 | Asynchronous IO, clock flows, IPC streaming | `kotlinx-coroutines-core-jvm` |
| **Java Native Access (JNA)** | 5.19.1 | JNA Contributors | Apache-2.0 / LGPL-2.1 | Win32 interop (DwmSetWindowAttribute, SetThreadExecutionState) | `net.java.dev.jna:jna:5.19.1` |
| **Inter Variable Font** | Android-identical bundled binary | Rasmus Andersson | SIL Open Font License 1.1 | Optical typography and interface rendering | `app/src/main/res/font/inter_variable.ttf` (SHA-256 validated) |

---

## Internal Modules and Engines

| Module / Component | Language | Scope | Functionality |
| :--- | :--- | :--- | :--- |
| **`:desktop:main`** | Kotlin 2.4.10 | Host UI | Glass layout, titlebar, navigation sidebar, stages, dock, inspectors |
| **`AudioWorkerMain`** | Kotlin 2.4.10 | Subprocess | Required packaged JVM worker; native FFmpeg/WASAPI JNI bridge, no production in-process fallback |
| **`DesktopDualDeckMixer`** | Kotlin 2.4.10 | Core Audio | Bounded Float32 stereo/48kHz dual-deck equal-power crossfade, desktop EQ/gain/limiter; Automix parity pending |
| **`DesktopAudioCache`** | Kotlin 2.4.10 | Cache | 256 MiB LRU audio disk cache with persistent manifest and SHA-256 keys |
| **`DesktopSessionRepository`** | Kotlin / SQLDelight | Storage | Actual SQLite WAL/FULL durable checkpoint and bounded receipts; full relational library migration pending |
| **`DesktopUpdateClient`**| Kotlin 2.4.10 | Security | Ed25519-authenticated metadata coordinator with durable freshness/compatibility; production defaults disabled |
| **`WindowsChrome`** | Kotlin / JNA | OS Window | Native Win32 `WM_NCHITTEST` borderless caption and resizing integration |
| **`WindowsSMTC`** | Kotlin 2.4.10 | OS Media | Adapter component only; actual WinRT SMTC media-flyout publication pending |
| **`WindowsTray`** | Kotlin / AWT | System Tray | Taskbar notification area icon with Explorer restart recovery |
| **`DiscordPresence`** | Kotlin 2.4.10 | Integration | Opt-in Discord Rich Presence via IPC named pipes |

---

## Cryptographic and Security Controls

- Ed25519 verifies exact domain/length-prefixed metadata payloads using explicitly configured local trust. No TEST-ONLY key is bundled as production trust.
- Strict schema parsing precedes target/signer/host checks and atomic scoped sequence/digest/version/time policy. SHA-256 identifies metadata; checksums alone do not authenticate a publisher.
- Runtime installed identity is generated from desktop package version 0.1.0. Missing deployment configuration disables checks; no Android-derived default remains.
- Revalidation rejects superseded/expired offers. Installation activation still needs serialization against later accepted metadata.
- No collector, artifact downloader/staging, Authenticode, installer execution or recovery acceptance is claimed. M6 is incomplete. See [UPDATE-05](handoffs/update-05-result.md).

## Phase 1 runtime additions

SQLDelight 2.1.0 / Xerial SQLite JDBC 3.51.3.0, Ktor 3.5.2 and InnerTubeX desktop 0.7.2 now belong to the real runtime graph (Ktor and InnerTubeX superseded on 6 November; see "Provider version" below). Native FFmpeg is `n8.1.3-32-g760d10603e-20261102`, with avformat62, avcodec62, avutil60 and swresample6, LGPL-3.0. Archive SHA-256/size pins are in `native/riff-audio/dependencies.json`; build and source details are in `native/riff-audio/README.md`. Zig 0.15.2 is build-only and not distributed. This inventory remains manually maintained and does not close the Phase 5 corresponding-source/transitive-license gate.


## Phase 2/3 additions

`:desktop-data` now owns real scoped relational SQLite/FTS, library scanning metadata, playlists, history, catalog, artwork and lyric document records; the old custom-file database remains test-only. ICU4J 78.3 supplies offline mechanical lyric transliteration, under Unicode License v3 plus retained upstream historical notices in `licenses/ICU4J-78.3-LICENSE.txt`. The approved codec vectors are test-only and unshipped, pinned by `native/riff-audio/codec-test-inputs.json`; the complete original HE-AAC v2 package is retained outside the distribution. These entries do not close the public-release transitive-source/license gate.


## SoundTouch Float32 tempo engine (3 November 2026)

SoundTouch 2.4.1, Olli Parviainen and contributors, LGPL-2.1-or-later, official source https://www.surina.net/soundtouch/soundtouch-2.4.1.tar.gz . SHA-256: e07abf20ce8f95850c280132e1f61ad400fc1f4011b7fac698a503de6aab6733 (607,365 bytes). Unmodified source archive: `native/riff-audio/vendor/soundtouch-2.4.1.tar.gz`; full license: `licenses/SOUNDTOUCH-LGPL-2.1.txt`. The bridge compiles the pinned Float32 source into riff_audio.dll; no extra platform DLL is introduced. Native source pins live in `native/riff-audio/dependencies.json`, not Maven verification metadata. The build rechecks the archive and extracts only regular required header/library-source entries. The unused Linux example symlink is never extracted. Source/license are copied into the distribution's native license directory. The archive contains no audio test media.

## Provider version (6 November 2026, desktop PLAN.md Task 1.7)

`:desktop:dependencyInsight` shows desktop resolving **InnerTubeX 0.11.2** (`innertubex-desktop-0.11.2.jar`), not 0.7.2. It moved on 5 November, when Android's playback commit `93814ac` raised the shared catalog entry `innertubex`; nothing on the desktop side noticed. The owner chose to keep 0.11.2. Desktop now has its own entry, `desktop-innertubex` in `gradle/libs.versions.toml`, and `:desktop:verifyProviderJvm` fails unless both the provider probe and `:desktop`'s `runtimeClasspath` resolve exactly the version that entry names.

The provider's runtime graph on 0.11.2 (`desktop/build/reports/provider-jvm.txt`) adds or changes:

| Component | Version | License (from its POM) | Note |
| :--- | :--- | :--- | :--- |
| InnerTubeX desktop | 0.11.2 | GPL-3.0 | Was 0.7.2. |
| QuackJS JVM | 0.1.2 | GPL-3.0-only | New with 0.11.2; replaces QuickJS. The jar holds no native library (no DLL). |
| Ktor client (core, CIO, websockets, SSE and the rest) | 3.6.0 | Apache-2.0 | Was 3.5.2; `:desktop` also declares 3.6.0 directly. |

Live provider evidence recorded before this date ran on 0.7.2 (every frozen soak runtime lists `innertubex-desktop-0.7.2`); see the capability matrix for what that leaves unproven on 0.11.2.
