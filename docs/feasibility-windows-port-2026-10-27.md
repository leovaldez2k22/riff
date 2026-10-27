# Feasibility and plan: Riff for Windows with Compose Multiplatform

27 October 2026. A report and plan only: nothing in the app has changed.

Based on Riff's source at 9ea71f4 (0.10.0, version code 33) and the published metadata of the libraries in the project's Gradle cache (`.toolchain/gradle-user-home`). File references are to `app/src/main/java/com/musicglass/personal/` unless a path is given.

## Contents

1. [Summary](#summary)
2. [What Riff is made of today](#what-riff-is-made-of-today)
3. [Target shape of the project](#target-shape-of-the-project)
4. [Dependency map: libraries](#dependency-map-libraries)
5. [Dependency map: Android platform APIs](#dependency-map-android-platform-apis)
6. [The hard parts](#the-hard-parts)
7. [What Windows will not have](#what-windows-will-not-have)
8. [Order of work](#order-of-work)
9. [Testing and release](#testing-and-release)
10. [Risks and open questions](#risks-and-open-questions)

## Summary

**A Windows version is feasible.** Most of Riff carries over. The parts that don't are concentrated in a few places: the player, sign-in, and the system integrations.

- **The UI carries over.** Riff is all Jetpack Compose. Compose Multiplatform runs the same `androidx.compose.*` code on Windows through Skia, so screens, motion, lyrics and most of the glass need little more than moving files.
- **The YouTube Music client carries over.** InnerTubeX 0.7.2 already publishes a `desktop` (JVM) variant beside the Android one. Its JavaScript engine, quickjs-kt 1.0.14, publishes a JVM jar that ships `jni/windows_x64/libquickjs.dll`. Both were checked in the Gradle module metadata.
- **The player has to be rebuilt.** Media3/ExoPlayer is Android-only. Riff's crossfade, volume boost, normalize, SABR streaming, cache and offline sources are all written against it, in 20 files. This is the biggest piece of work.
- **Sign-in and the stream token need a browser.** On Android both use WebView. On Windows the replacement is an embedded Chromium (KCEF).
- **Some features have no Windows equivalent:** home-screen widgets, phone accounts, haptics, the system equalizer and the OEM audio effects.

| Area | Carries over | Effort | Main risk |
|---|---|---|---|
| Compose UI, navigation, lyrics, design tokens | Almost all | Small to medium | Material icons artifact; text rendering differences |
| Glass (AGSL shaders, blur) | Shader code nearly as-is | Medium | Skia runtime effects behave differently from Android `RuntimeShader` |
| YouTube catalog, library, playlists | InnerTubeX desktop variant | Small | The desktop variant has not been built against Riff yet |
| Playback, crossfade, boost, normalize | Logic only, not code | **Large** | Audio output latency and gapless timing on Windows |
| Stream tokens and cipher | Cipher yes; token runtime replaced | Medium | Attestation in an embedded Chromium |
| Sign-in and cookies | Flow logic yes; WebView replaced | Medium | Google may treat an embedded browser as unsafe and refuse it |
| Storage (SQLite, preferences, files) | Schema and queries yes | Small to medium | Migrating nothing: Windows starts empty |
| Downloads | Engine yes; remux replaced | Medium | FFmpeg packaging size |
| System integration (media keys, tray, notifications) | No | Medium | Windows media controls need a small native helper |

**Rough size:** a usable first Windows build (browse, search, sign-in, playback with crossfade, lyrics, downloads) is about **8–12 weeks** of focused work for one developer. Parity with the Android app's polish would take longer. The first two weeks are a spike that settles the three risks that could sink the plan (see [Order of work](#order-of-work)).

## What Riff is made of today

| Measure | Value |
|---|---|
| Main source | 173 Kotlin files, 25,750 lines, all in one `:app` module |
| Files with no Android or AndroidX imports at all | 44 |
| Files whose only AndroidX imports are Compose | 77 (includes the 44) |
| Files that import `android.*` | 81 |
| Files that import Media3 | 20 |
| Files that use `LocalContext` | 28 |
| Files that use `R.` (strings, drawables, fonts) | 69 |
| Strings in `res/values/strings.xml` | 886 |
| Unit tests | 148 files, 17,010 lines; 69 use Robolectric |

The Compose imports (`androidx.compose.ui`, `foundation`, `runtime`, `material`, `material3`, `animation`) don't count against the port, because Compose Multiplatform keeps the same package names.

Android components in `AndroidManifest.xml` that need a Windows counterpart:
- `MusicPlaybackService`: a `MediaSessionService`, foreground type `mediaPlayback`.
- `SolverService`: runs in its own process (`:solver`) so that a QuickJS crash doesn't take down playback.
- `OfflineDownloadService` and `OfflineDownloadJob`: foreground type `dataSync`.
- `MoodPhotoRefreshJob`.
- Six home-screen widget receivers.
- A `FileProvider`.

## Target shape of the project

AGP 9 does not let `com.android.application` and `kotlin.multiplatform` share a module, so the code is split into three modules:

```
:shared        Kotlin Multiplatform library (targets: android, desktop/jvm)
  commonMain   UI, view models, repositories, lyrics, provider logic, models
  androidMain  Media3 player, WebView, MediaStore, widgets, Android services
  desktopMain  FFmpeg player, KCEF, Windows integration
:androidApp    com.android.application: manifest, MainActivity, services, widgets, signing, build types
:desktopApp    Compose Desktop application: main(), window, tray, MSI packaging
:benchmark     unchanged, points at :androidApp
```

The Android app must keep building and shipping throughout the work. Every step below leaves `:androidApp` producing the same APK as before. Robolectric tests stay in `androidApp` (or `shared/androidUnitTest`). Tests with no Android dependency move to `commonTest` and run on both targets.

Platform seams are written as Kotlin `expect`/`actual` declarations or as interfaces handed in through `AppContainer.kt`. Riff already has the right seam for most of this:
- `PlaybackGateway` is the only way the UI reaches the player.
- `CipherBackend`, `TokenProvider` and `ProviderSession` hide the provider runtime.

## Dependency map: libraries

Each entry in `gradle/libs.versions.toml` and `app/build.gradle.kts`, and what it becomes on Windows.

| Android dependency | Used for | Windows replacement | Notes |
|---|---|---|---|
| `androidx.compose:compose-bom` 2026.06.01 (`ui`, `foundation`, `material3`, `ui-tooling-preview`) | All UI | **Compose Multiplatform** (`org.jetbrains.compose` plugin) at the release that matches Kotlin 2.4.10 | Same package names. Check that the chosen CMP release supports Kotlin 2.4 before starting. |
| `androidx.compose.material:material-icons-extended` | Icons | Copy the icons Riff actually uses into `commonMain` as `ImageVector`s | The extended icon set is no longer published for newer CMP releases. Copying only the icons in use also cuts size. |
| `androidx.activity:activity-compose` 1.13.0 | `setContent`, result launchers (file pickers, permissions) | `androidMain` only. Desktop uses `application { Window { … } }` and FileKit or AWT `FileDialog` for pickers | 5 files use `androidx.activity.result`. |
| `androidx.lifecycle` (compose, repeatOnLifecycle) | Pause and resume work with the screen | `org.jetbrains.androidx.lifecycle` (KMP), driven by window focus and minimize on desktop | |
| `androidx.core` (`content`, `net`, `graphics`, `database`) | `toUri`, `ContextCompat`, `FileProvider` | Plain Kotlin and `java.net.URI` in common code; `FileProvider` stays Android-only | Small. |
| `io.coil-kt.coil3:coil-compose` 3.3.0 | Artwork | Same library: Coil 3 is multiplatform and supports JVM desktop | `ArtworkImageLoader.kt` takes a `Context`; desktop passes a `PlatformContext` and a cache folder. |
| `io.coil-kt.coil3:coil-network-okhttp` | Image network stack | Same library; OkHttp runs on the JVM | |
| `androidx.media3:media3-exoplayer` 1.11.1 | Playback, two players for crossfade, renderers, load control | **FFmpeg decode (bytedeco `ffmpeg` with Windows x64 natives) + Riff's own mixer + `javax.sound.sampled` output (WASAPI-backed)** | See [Playback](#1-playback). |
| `androidx.media3:media3-exoplayer-hls` | Live radio streams | FFmpeg's HLS demuxer | Same FFmpeg build. |
| `androidx.media3:media3-session` | `MediaSession`, notification, Bluetooth and lock-screen controls | Windows **System Media Transport Controls** through a small native helper; Compose Desktop `Tray` | See [Media controls](#4-media-controls-and-running-in-the-background). |
| `androidx.media3` `datasource`, `database`, `extractor` | `OkHttpStreamDataSource`, `SimpleCache` (`MusicDiskCache.kt`), SABR data source | Riff's own byte source on top of OkHttp/Ktor feeding FFmpeg's custom I/O (`AVIOContext`); a plain file cache under `%LOCALAPPDATA%\Riff\cache` | The SABR segments come from InnerTubeX, not from Media3. |
| `com.github.MetrolistGroup.innertubex:innertubex` 0.7.2 | YouTube Music catalog, library, cipher, SABR, PoToken helpers | **Same library, `innertubex-desktop` variant** (JVM), published in the same release | Not downloaded yet; the first spike task resolves it and checks that the classes Riff uses are there. |
| `io.github.dokar3:quickjs-kt` 1.0.14 (via InnerTubeX) | Signature and n-parameter solving | `quickjs-kt-jvm` 1.0.14, which bundles `windows_x64/libquickjs.dll` | Run in a child process, as on Android: see [Solver](#3-the-solver-process). |
| `io.ktor:ktor-client-okhttp` 3.5.2, `content-negotiation`, `serialization-json` | All provider HTTP | Same libraries | OkHttp is JVM. |
| `kotlinx-coroutines`, `kotlinx-serialization` | Everywhere | Same libraries | Desktop needs `kotlinx-coroutines-swing` for `Dispatchers.Main`. |
| `androidx.profileinstaller`, `androidx.baselineprofile`, `:benchmark` | Startup performance on Android | `androidMain` and `:androidApp` only; nothing on desktop | The JVM has its own JIT warm-up. Consider an AppCDS archive in the installer later. |
| Test: `robolectric` 4.17, `ui-test-junit4` | Android unit and UI tests | Robolectric stays for Android. Desktop UI tests use `compose.uiTest` (`runComposeUiTest`) | |

New dependencies the desktop build adds:

| Library | Why |
|---|---|
| `dev.datlag:kcef` (KCEF, Kotlin wrapper for JCEF/Chromium) | Sign-in page, the attestation runtime, embedded YouTube video |
| `org.bytedeco:ffmpeg` with `windows-x86_64` natives only | Decode, demux and remux audio (and later video) |
| `net.java.dev.jna:jna-platform` | Windows APIs: DPAPI for encrypting cookies, `SystemParametersInfo` for reduced motion, known folders |
| `androidx.sqlite:sqlite-bundled` (KMP) | One SQLite API on both platforms; ships its own native SQLite for Windows |
| `androidx.datastore:datastore-preferences-core` (KMP) or a small properties store | Replaces `SharedPreferences` |
| `org.jaudiotagger` (optional) | Tags of the user's own music files for "On device" |

## Dependency map: Android platform APIs

Grouped by what the code does. "Files" lists where each API is used today.

### Context, resources, strings

| Android API | Files | Windows replacement |
|---|---|---|
| `Context`, `Application` (as a handle to folders, preferences and services) | About 40 files, most as a constructor argument | A `Platform` object in `commonMain`: data folder, cache folder, preferences factory, clock, `openUrl`, clipboard. Android builds it from `Context`; desktop from `%LOCALAPPDATA%\Riff`. |
| `Resources`, `R.string`, `R.plurals` (`Strings.kt`, 69 files) | Every visible string | **Compose Multiplatform resources**: `strings.xml` moves to `composeResources/values/` in the same format. `AppStrings` keeps its non-composable `string(id)`. Its existing `DefaultStrings` fallback already reads `strings.xml` without Android, so it becomes the desktop implementation. |
| `R.font` (Inter, four weights) | `DesignTokens.kt` | `composeResources/font/`, loaded with `Font(Res.font.inter_regular)` |
| `R.drawable` (cover fallbacks, liked-music art) | Artwork | `composeResources/drawable/`. The widget and launcher drawables stay Android-only. |
| `content.res.Configuration` | `ScreenParts.kt` | Window size from `LocalWindowInfo` / `BoxWithConstraints` |
| `text.format.DateUtils`, `text.Html` | `DiagnosticsScreen.kt`, `MoodPhotos.kt` | `kotlinx-datetime` formatting; a small HTML-entity decoder |

### Storage

| Android API | Files | Windows replacement |
|---|---|---|
| `SQLiteOpenHelper`, `SQLiteDatabase`, `ContentValues` | `LibraryState.kt`, `OfflineMusicStore.kt`, `LocalDataTransfer.kt` | `androidx.sqlite` `SQLiteDriver` with `BundledSQLiteDriver` on both platforms. The SQL stays; only the helper calls change. |
| `SharedPreferences` | `AudioTransitionCoordinator.kt`, `SignInDesign.kt`, `UpdateNotice.kt`, `MoodPhotos.kt`, `ProviderSessionManager.kt` and others | DataStore Preferences (KMP), or a small typed key-value store over a JSON file. One interface in common code. |
| `filesDir`, `cacheDir`, `StatFs` | Downloads, caches, `AppContainer.kt` | `%LOCALAPPDATA%\Riff\data` and `…\cache`; `java.io.File.getUsableSpace()` |
| `MediaStore`, `ContentUris`, `ContentResolver` | `LocalAudioRepository.kt` (On device), `LocalBackup.kt`, `LyricsShare.kt` | On device: scan the user's Music folder (`FOLDERID_Music`) and folders they add, reading tags with jaudiotagger. Backup and lyric cards: write to a folder the user picks. |
| `DocumentsContract`, activity result pickers | `LocalBackupScreen.kt`, `LocalDataTransferScreen.kt` | Native open and save dialogs (FileKit, or AWT `FileDialog`) |
| `androidx.core.content.FileProvider`, `shared_files.xml` | Sharing files with other apps | Not needed. Share by saving a file or copying to the clipboard. |
| `data_extraction_rules.xml` (Android backup) | | Not applicable. Riff's own export/import (`LocalDataTransfer.kt`) is the backup on Windows too. |

### Network and device state

| Android API | Files | Windows replacement |
|---|---|---|
| `ConnectivityManager`, `NetworkCapabilities` | `AppContainer.kt` (Data saver, metered networks) | Assume unmetered. Data saver becomes a manual setting. Metered detection through WinRT `NetworkInformation` can come later through the native helper. |
| `BatteryManager`, `PowerManager` | `AppContainer.kt`, `ServiceStopDiagnostics.kt` | JNA `GetSystemPowerStatus` for battery saver; drop power diagnostics |
| `net.Uri` | 14 files | `java.net.URI`, or a small `Url` value class in common code |
| `os.Handler`, `Looper` | `DeferredProviderSource.kt`, `PlaybackGateway.kt`, `GlassMotion.kt`, `AndroidTokenRuntime.kt` | Coroutines on `Dispatchers.Main` (Swing on desktop) |
| `os.SystemClock` | 6 files | `System.nanoTime()` behind Riff's existing clock seam (`dependencies.clocks`) |
| `os.Build` | Version checks | Not needed on desktop; the common code stops asking |
| `os.Trace` (`PerfTrace.kt`) | Performance traces | No-op on desktop |

### Graphics and glass

| Android API | Files | Windows replacement |
|---|---|---|
| `RuntimeShader` (AGSL), `RenderEffect` | `AgslChromeGlassEffect.kt`, `AgslNavigationLens.kt`, `ChromeGlassEffect.kt`, `FrostedPopup.kt`, `ShellChrome.kt` | Skia `RuntimeEffect.makeForShader` (SkSL) with `ImageFilter.makeRuntimeShader` and `ImageFilter.makeBlur`, turned into a Compose `RenderEffect` with `asComposeRenderEffect()`. AGSL is a dialect of SkSL, so the shader text should need only small changes (uniform types, `eval` on child shaders). |
| `graphics.Bitmap`, `Canvas`, `Paint`, `StaticLayout`, `Typeface` | `LyricsShare.kt` (lyric cards) | Draw the card with Compose's `CanvasDrawScope` into an `ImageBitmap`, using `TextMeasurer` for text, then encode to PNG with Skia. This also replaces the Android code path, which would be a good change on its own. |
| `BitmapFactory` | `MoodPhotos.kt` | `org.jetbrains.skia.Image.makeFromEncoded` |
| `view.SurfaceView`, `AndroidView` | `NativeVideo.kt` | FFmpeg video frames drawn into an `ImageBitmap` (later phase), or vlcj's callback renderer |
| `view.HapticFeedbackConstants` (`UiHaptics.kt`) | Graded haptics | No-op on desktop |

### Web content

| Android API | Files | Windows replacement |
|---|---|---|
| `webkit.WebView`, `WebViewClient`, `SslErrorHandler` | `AccountScreen.kt`, `WebBoundaries.kt` (sign-in) | **KCEF** browser in a Compose window, with the same URL allow-list (`StrictWebViewClient` rules become a CEF request handler) |
| `webkit.CookieManager` | `ProviderSessionManager.kt` | CEF's cookie manager, read once after sign-in. Cookies are kept encrypted with Windows DPAPI (`Crypt32Util.cryptProtectData`) in Riff's data folder, and the CEF profile lives under `%LOCALAPPDATA%\Riff\cef`. |
| `WebView` running attestation JavaScript with network blocked | `AndroidTokenRuntime.kt`, `AndroidStreamTokenProvider.kt` | KCEF in windowless (off-screen) mode, with a request handler that answers every request with an empty response, as the Android code does today |
| `WebView` with the YouTube embed | `InlineYouTubeVideo.kt` | KCEF browser composable, same embed URL and navigation rules |

### Audio and playback

| Android API | Files | Windows replacement |
|---|---|---|
| Media3 `ExoPlayer`, `Player`, `MediaItem`, `AudioProcessor`, renderers, `LoadControl` | `MusicPlaybackService.kt`, `AudioTransitionCoordinator.kt`, `Crossfade.kt`, `PlaybackSources.kt`, `QueueCoordinator.kt`, `PlaybackState.kt`, `PlaybackRecovery.kt`, `StreamRecovery.kt`, `WaitingSessionPlayer.kt`, `SongPresentation.kt`, `PlaybackPersistence.kt`, `PlaybackFaults.kt`, `VolumeBoostProcessor.kt` | A `DesktopPlaybackEngine` behind `PlaybackGateway`: FFmpeg decoders, a Riff mixer, a `SourceDataLine` output. See [Playback](#1-playback). |
| `media.MediaExtractor`, `MediaMuxer`, `MediaCodec`, `MediaFormat` | `OfflineDownloadEngine.kt` (`AndroidAudioAssembler`), `DownloadRepack.kt` | FFmpeg demux and remux (`-c copy` equivalent). Riff's own `WebmAudioWriter.kt` and `WebmSeekIndex.kt` are pure Kotlin and move to common code unchanged. |
| `media.audiofx.LoudnessEnhancer`, `AudioEffect` broadcast | `AudioSessionEffects.kt` | Riff's existing `PcmVolumeBoost` limiter, which is already the Android fallback. Nothing to broadcast: Windows has no per-session effect hook. |
| `AudioManager`, `AudioDeviceInfo`, `MediaRouter2`, `Settings` | `AudioOutputControls.kt` | List output devices with `AudioSystem.getMixerInfo()` and let the user pick one; the "open system output picker" button opens `ms-settings:sound` |
| `speech.SpeechRecognizer` | `VoiceSearchController.kt` | Hide voice search on desktop at first. Later: Windows speech through the native helper, or Vosk offline. |

### Background work, system UI, diagnostics

| Android API | Files | Windows replacement |
|---|---|---|
| `MediaSessionService`, foreground service, `PendingIntent` | `MusicPlaybackService.kt` | The player lives in the app process; closing the window can hide it to the tray while music plays (a setting) |
| `app.Service` in the `:solver` process, `IBinder`, `Parcel`, `ServiceConnection` | `SolverService.kt`, `RemoteCipherClient.kt`, `SolverProtocol.kt` | A child JVM process (`ProcessBuilder`), talking over stdin/stdout with the same `SolverProtocol` messages. See [Solver](#3-the-solver-process). |
| `app.job` (`JobScheduler`), `dataSync` foreground service | `OfflineDownloadService.kt`, `OfflineDownloadJob`, `MoodPhotoRefreshJob` | Coroutines in the app process while it runs (including in the tray). Smart Downloads run at app start and on a timer. |
| `appwidget.*`, `RemoteViews`, widget layouts and XML | `RiffWidgets.kt`, `res/layout/widget_*.xml`, `res/xml/widget_*_info.xml` | None. A tray menu and an optional compact always-on-top mini player window take their place. |
| `content.pm.PackageManager`, pinned shortcuts | `LibraryShortcuts.kt`, `OnDeviceScreen.kt`, `CatalogScreens.kt` | Drop the shortcuts. Taskbar jump list later, through the native helper. |
| `Intent` (share, open link, open settings) | `CardPeek.kt`, `NowPlaying.kt`, `DownloadsScreen.kt`, `DiagnosticsScreen.kt` and others | `java.awt.Desktop.browse` for links; clipboard or "Save as…" for sharing |
| `ClipData` | `LyricsShare.kt` | `LocalClipboardManager` / AWT clipboard |
| `ActivityManager.ApplicationExitInfo`, `UsageStatsManager` | `ExitDiagnostics.kt`, `ServiceStopDiagnostics.kt` | A "running" marker file written at start and cleared on clean exit, plus a default uncaught-exception handler. JVM crash logs (`hs_err_pid*.log`) go to Riff's data folder via `-XX:ErrorFile`. |
| `database.ContentObserver` on animator settings | `GlassMotion.kt` (reduced motion) | JNA `SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`, re-read when the window gains focus |
| `os.Process` | `SolverService.kt` | `ProcessHandle` |

## The hard parts

### 1. Playback

Riff's playback is more than "play a URL":
- Crossfade runs a second ExoPlayer as the outgoing "tail" (`AudioTransitionCoordinator.kt`). It keeps the two in step by nudging speed (`CrossfadeTailSynchronizer` in `Crossfade.kt`).
- Boost and normalize scale PCM in a Media3 `AudioProcessor` (`VolumeBoostProcessor.kt`).
- SABR streams arrive through a custom `DataSource` (`SabrPlaybackTransport.kt`).
- Downloads and the disk cache plug in as further data sources.

**Recommendation: own the audio pipeline on desktop instead of wrapping a media player.**

```
OkHttp / SABR / cache / file  ->  ByteSource  ->  FFmpeg demux + decode  ->  PCM (float, 48 kHz stereo)
                                                                               |
                                    deck A ---+                                v
                                    deck B ---+-->  Riff mixer: crossfade curve, normalize trim,
                                                    boost + limiter (PcmVolumeBoost), output volume
                                                                               |
                                                                               v
                                                  javax.sound.sampled SourceDataLine (WASAPI shared mode)
```

- **Crossfade gets simpler.** Both decks are mixed sample by sample in one place, so the tail needs no speed-nudging synchronizer. `CrossfadeTailSynchronizer` stays Android-only.
- **The crossfade curves, the queue, recovery and persistence rules** (`Crossfade.kt` curves, `QueueCoordinator.kt`, `PlaybackRecovery.kt`) are mostly pure logic. Where they now take Media3 types (`MediaItem`, `Player.Listener`), they need Riff's own small models (`RiffTrack`, `PlayerEvent`) in common code. Android maps those to Media3.
- **`PcmVolumeBoost`** is already plain Kotlin over `ByteBuffer` and moves to common code as-is.
- **Why not vlcj or JavaFX Media?** Neither lets Riff feed SABR bytes and mix two decks with sample-accurate control at the same time. vlcj also needs VLC installed or bundled (about 100 MB). FFmpeg through bytedeco with Windows natives only is roughly 40–60 MB.
- **Live radio (HLS)** goes through FFmpeg's HLS input on the same path.
- **Song videos** come in a later phase: decode with FFmpeg into an `ImageBitmap` at the video's frame rate.

The spike must measure two things:
- Gapless and crossfade timing through `SourceDataLine` with a buffer of about 50 ms.
- CPU use while two Opus streams decode at once.

If `SourceDataLine` adds too much latency, the fallback is a small WASAPI output through JNA or the native helper.

### 2. Sign-in and the stream token

Both rely on Android's WebView today. KCEF bundles Chromium, about 150–200 MB, downloaded on first run or packaged with the installer. KCEF covers:
- **Sign-in.** It opens `accounts.google.com` with the same handoff URLs `SignInFlow.kt` builds, using the same allow-list from `WebBoundaries.kt`. After the handoff, Riff reads the cookies and keeps them encrypted with DPAPI.
- **Attestation.** A windowless KCEF browser loads the same blank page at `https://www.youtube.com/robots.txt` with all network blocked, as `AndroidTokenRuntime.kt` does. It runs the same scripts through `executeJavaScript` with a message-router callback for the result.

**Risk:** Google blocks sign-in from some embedded browsers ("This browser or app may not be secure"). The spike must try a real sign-in in KCEF early. If it is refused, the fallback is to open the system browser, have the user sign in to music.youtube.com there, and hand the session to Riff. That flow is clumsier and needs its own design and privacy review.

### 3. The solver process

On Android, QuickJS runs in the `:solver` process so that a native crash doesn't stop the music (`SolverService.kt`). On Windows, a crash in `libquickjs.dll` would take down the whole JVM, so the same isolation is needed:
- Riff starts a second JVM from the same install: `javaw -cp … com.musicglass.personal.SolverMain`.
- `SolverProtocol` messages go over stdin/stdout with length-prefixed frames instead of `Parcel`.
- `RemoteCipherClient`'s retry, `SolverHealth` back-off and idle shutdown stay as they are, behind the same `CipherBackend` interface.
- The prepared-player cache (`PreprocessedPlayerCache`) moves to `%LOCALAPPDATA%\Riff\cache\solver-players`.

The cost is a second JVM, about 60–100 MB of memory while it runs. It already shuts down after 10 idle minutes.

### 4. Media controls and running in the background

Windows shows media in the taskbar flyout, on the lock screen and through the keyboard media keys. All of that is the **System Media Transport Controls (SMTC)**, a WinRT API. There is no maintained Java binding, so the plan adds a small native helper (`riff-smtc.dll`) in C++/WinRT, about 300 lines:
- It sets the title, artist, artwork and playback state.
- It reports play, pause, next, previous and seek back to Kotlin through JNA callbacks.

The same helper can later carry jump lists, metered-network detection and toast notifications. Until it exists, global media keys can be read with JNativeHook as a stopgap, though nothing appears in the Windows media flyout.

When the window is closed while music is playing, Riff hides to the tray: Compose Desktop's `Tray` with Play/Pause, Next and Quit. Downloads continue while Riff is in the tray. A setting lets closing the window quit instead.

### 5. Glass on Skia

Compose Desktop draws through Skia (Skiko), which supports both blur and runtime shaders. That means `Modifier.blur`, `graphicsLayer { renderEffect = … }` and the frosted surfaces should work. Differences to expect:
- **Shader source.** AGSL `uniform shader` children are sampled with `.eval()`; check each shader in `AgslChromeGlassEffect.kt` and `AgslNavigationLens.kt` for Android-only built-ins.
- **Rendering path.** Skiko renders with Direct3D 12 on Windows, with OpenGL or software as fallbacks. Glass on the software fallback (old GPUs, remote desktop) should drop to the frosted-only look Riff already uses for Android 12 and Riff Lite.
- **Window size.** A desktop window can be much larger than a phone, so blur cost grows with it. Cap blur radius and the size of the backdrop layer the way Lite does.

## What Windows will not have

At least in the first version:

- **Home-screen widgets.** There is no equivalent. A tray menu and an optional mini player window take their place.
- **Accounts used on the phone.** There is no Android account list. Sign-in is the web flow only. Riff's saved-accounts list (`SignInDesign.kt`) still works for switching.
- **System equalizer and OEM audio effects** (the `AudioEffect` session broadcast). Riff's own boost, normalize, and any future built-in equalizer work on Windows.
- **Haptics.**
- **Voice search** until a speech option is chosen.
- **Pinned shortcuts.**
- **Migration from the phone.** Riff's export and import (`LocalDataTransfer.kt`) is the way to move data. It already produces a file, so a Windows import reads the same file. The import format is checked for paths and assumptions specific to Android.

## Order of work

### Phase 0: Spike (about 2 weeks; decides go or no-go)

1. Add a throwaway `:desktopSpike` module with Compose Desktop, InnerTubeX's `desktop` variant and quickjs-kt-jvm.
2. Show Home from the real provider in a window, with no sign-in.
3. Play one song through FFmpeg → mixer → `SourceDataLine`, including a SABR stream. Crossfade between two songs and measure timing and CPU.
4. Sign in to Google in KCEF and fetch the library with the cookies.
5. Run one AGSL shader from `AgslChromeGlassEffect.kt` as SkSL.

**Stop if:** Google refuses sign-in in KCEF *and* the system-browser fallback is unacceptable, or InnerTubeX's desktop variant is missing what Riff needs and can't be added upstream.

### Phase 1: Restructure without changing the Android app (about 2 weeks)

1. Create `:shared` (KMP: android + desktop) and `:androidApp`. Move `MainActivity`, the services, widgets, manifest, build types and signing to `:androidApp`. The APK's package id, signature and behaviour are unchanged; check with the existing release smoke test and the baseline screens.
2. Move the 44 Android-free files to `commonMain`, then the Compose-only screens.
3. Introduce the `Platform` object, the string and resource move to Compose resources, the SQLite driver change and the preferences interface. Each lands as its own commit with the Android tests passing.

### Phase 2: Desktop playback (about 3 weeks)

1. Riff's own track and event models in common code; Media3 mapping in `androidMain`.
2. `DesktopPlaybackEngine`: byte sources (HTTP, SABR, cache, file), FFmpeg decode, the two-deck mixer, boost, normalize and the limiter, the output device picker.
3. Queue, recovery and persistence wired to it through `PlaybackGateway`.
4. The solver child process.

### Phase 3: Sign-in, tokens, downloads (about 2 weeks)

1. KCEF sign-in, DPAPI cookie store, account switching.
2. Off-screen attestation runtime.
3. Downloads with FFmpeg remux, Smart Downloads on a timer, storage budgets.

### Phase 4: Windows integration and polish (about 2–3 weeks)

1. `riff-smtc.dll` and the tray.
2. Window chrome: a custom title bar so the glass reaches the top, with standard Windows buttons and Snap layouts kept.
3. Keyboard shortcuts (Space, arrow keys, Ctrl+F, media keys) and mouse behaviour (hover states, right-click menus, scroll-wheel speed on lists and the lyrics).
4. A layout for wide windows: the player beside the library instead of over it, and a navigation rail instead of the bottom bar when the window is wide.
5. Reduced motion, high contrast and screen-reader check (Compose Desktop's accessibility goes through the Java Access Bridge; test with Narrator and NVDA).

## Testing and release

- **Tests.** Tests that don't need Android move to `commonTest` and run on the JVM for both targets. Robolectric tests stay Android-only. Add desktop tests for the mixer (exact sample output for a crossfade and for the limiter), the solver protocol over pipes, and the cookie store.
- **CI.** Add a `windows-latest` job that builds `:desktopApp` and runs the common and desktop tests. The Android jobs stay as they are.
- **Packaging.** Compose Desktop's `packageMsi` with a bundled JRE (jlink). Expected size is about 120 MB without Chromium, plus about 150–200 MB if KCEF is bundled rather than downloaded on first run. An MSIX package would be needed for Microsoft Store distribution and gives cleaner install and uninstall; decide before Phase 4.
- **Signing.** Unsigned MSIs trigger SmartScreen warnings, so a code-signing certificate is a release cost to plan for. It's separate from the Android key in `docs/release-signing.md`.
- **Licences.** InnerTubeX is GPL-3.0 and Riff is GPL-3.0. FFmpeg must be the LGPL build, or the GPL build, which Riff's licence allows; record which in `docs/dependency-license-inventory.md`. CEF/Chromium (BSD and others), JNA (Apache-2.0/LGPL) and the bundled JRE (GPL-2.0 with Classpath Exception) are added to the inventory and to `NOTICE.md`.
- **Publishing.** As with the APK, nothing is published without the owner's explicit decision.

## Risks and open questions

| Risk | Likelihood | Effect | What settles it |
|---|---|---|---|
| Google refuses sign-in in an embedded Chromium | Medium | Sign-in needs the system-browser flow | Spike step 4 |
| InnerTubeX `desktop` variant lacks a class Riff uses (for example SABR or the PoToken helpers) | Low to medium | Patch upstream, or keep a thin copy | Spike step 1 |
| `SourceDataLine` latency spoils gapless playback and crossfade | Medium | Needs a WASAPI output through JNA or the helper | Spike step 3 |
| AGSL shaders need real rewrites for SkSL | Low | A few days per shader | Spike step 5 |
| Compose Multiplatform release lags Kotlin 2.4.10 | Low | Hold Kotlin at the newest version CMP supports | Before Phase 1 |
| Install size (JRE + FFmpeg + Chromium) about 300 MB | High | Annoying, not blocking | Download Chromium on first run; jlink only the modules in use |
| Two code paths for playback drift apart | Medium | Bugs fixed on one platform only | Keep queue, recovery, crossfade curves and gain in common code; share tests |

**Questions for the owner:**
1. Is Windows 10 required, or only Windows 11? This affects SMTC details and the window chrome.
2. Should the Windows version be distributed through the Microsoft Store (MSIX) or only as a download (MSI)?
3. Is a second, separate sign-in on Windows acceptable? There is no way to reuse the phone's sign-in.
4. Should song videos be in the first Windows version, or come later?
