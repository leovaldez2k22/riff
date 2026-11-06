# Riff Desktop: Technical Implementation Plan

Written 5 November 2026, against the uncommitted working tree on `codex/phase1-walking-skeleton` (HEAD `93814ac`). This is a plan only. Nothing in it is implemented, and its versions, timings and API choices are proposals until a task's verification step passes.

## How to use this plan

- Work the tasks in order. Each is meant to fit one coding turn. Tick its box only when its verification step has really passed.
- If the code or a measurement disagrees with this plan, update the plan in the same change and say why. Line numbers describe the tree on 5 November and will move.
- Before a feature task, read that feature's section in `docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md`. For Automix, also read `docs/automix-engine-report-2026-10-28.md`.

### How this relates to the existing documents

| Document | Role now |
|---|---|
| `reports/Riff Windows production completion 2026-11-02.md` (and the PDF of it) | Still the detailed spec for Phase 4 and Phase 5 of that report. Its P4 and P5 items map to Phases 4 and 11 here. Its label rule stands: Observed, Previously tested, Proposed, Production accepted |
| `docs/desktop/phase-3-status.md` | Still the evidence ledger. Tasks that change evidence update it |
| This file | Sets the order and scope of all remaining desktop work, and adds the product work the earlier report left to a parity register |

### Rules every task inherits

These come from `CLAUDE.md` and `AGENTS.md`.

1. **Screen hold.** Don't launch, activate or automate a desktop window until the owner says screen tests can start. Offscreen rendering (Task 5.1) doesn't open a window. Steps marked **Owner screen check** wait for the owner.
2. **Ask first** before anything that commits, pushes, publishes or writes to the owner's YouTube Music account. Never enter the owner's passwords.
3. **New network destinations and dependencies** need the owner's approval, then `NOTICE.md`, `docs/privacy.md` and `docs/desktop/sbom.md` updates. Pin and verify each new artifact.
4. **Android stays as it is.** Nothing here rewrites `:app`, except Phase 10, and only if the owner approves it.
5. **Long lanes run with the machine alone**, as the owner's rule requires.
6. **No fake success.** A control that can't do its job is hidden or says so. No synthetic audio, fixture data or invented IDs reach a release path.

### Shorthand

| Short form | Meaning |
|---|---|
| `$D` | `desktop/src/main/kotlin/com/musicglass/desktop` |
| `$T` | `desktop/src/test/kotlin/com/musicglass/desktop` |
| `$A` | `app/src/main/java/com/musicglass/personal` |
| `$DD` | `desktop-data/src/main/kotlin/com/musicglass/data` |
| **Gradle setup** | Set `JAVA_HOME` to `.toolchain\jdk-21*` and `GRADLE_USER_HOME` to `.toolchain\gradle-user-home` first, as `scripts/quality/verify.py` does. The system JDK is 20 and won't build desktop |
| **Unit** | `.\gradlew.bat -PriffDesktop=true :desktop:test :desktop-data:test --offline --continue` |
| **Native** | `.\gradlew.bat -PriffDesktop=true :desktop:nativeBackgroundTest --offline` (real decoders and WASAPI, no window) |
| **PR lane** | `python scripts/quality/verify.py --lane pr` |

### Owner decisions this plan depends on

The work stops at each of these and asks. The recommendation is what the plan assumes.

| # | Decision | Needed by | Recommendation |
|---|---|---|---|
| D1 | Commit the desktop tree to a branch | Task 1.1 | Yes, staging desktop paths only |
| D2 | Where evidence in `artifacts/` is backed up | Task 1.2 | Hash manifest in git, files on an external drive |
| D3 | Push a desktop CI job to GitHub | Task 1.4 | Yes, once the lane is stable |
| D4 | Extra lyrics sources (Better Lyrics, QQ, Kugou, NetEase, YouTube Music). Only LRCLIB and lrc.red are approved on desktop so far | Task 8.2 | Same set as Android |
| D5 | Default for closing the window: quit, or hide to tray | Task 4.10 | Quit, with a setting for tray |
| D6 | Discord presence: real application ID, or remove it | Task 4.13 | Remove until an ID exists |
| D7 | How desktop signs in to YouTube Music | Task 9.1 | Decided from the Task 9.1 spike |
| D8 | Whether desktop may write to the account (likes, playlists) | Task 9.5 | Only after read-only works, with a test account if possible |
| D9 | Cast, voice search and video on desktop | Task 6.17 | Out of the first release, recorded as exceptions |
| D10 | Shared module touching `:app` | Task 10.2 | Yes, once the Android work in progress settles |
| D11 | Product name, install identity, channel, hosts, signing custody | Task 11.1 | Name "Riff"; the rest is the owner's |

---

# 1. Target Architecture & Dependency Specification

## 1.1 Final directory and package structure

**Moves happen late on purpose.** Existing files stay where they are until Task 10.6, so the paths in this plan stay valid. New files go straight into their target package.

Key: **K** keep as is, **R** refactor, **S** split, **D** delete, **M** move out of main, **N** new.

```text
settings.gradle.kts                     R  desktop lane include; :riff-core (Phase 10)
PLAN.md                                 N  this file
riff-core/                              N  Phase 10: pure Kotlin shared with :app, no Android or Compose types
  QueueOrder, lyric parsing and rules, transition maths, EQ curve
desktop-data/                           K  SQLDelight store: migrations, FTS5, Encore, downloads, caches
  LegacyDesktopStoreReader.kt           K  until old stores age out
native/
  riff-audio/riff_audio.cpp             R  FFmpeg, WASAPI, SoundTouch (seek index in Task 2.15)
  riff-windows/riff_windows.cpp         N  SMTC facade, kept apart from audio
desktop/
  build.gradle.kts                      R  section 1.2
  src/acceptance/kotlin/.../            M  every *Acceptance, *Soak and *Probe file from main
  src/preview/kotlin/...                K  GlassGallery, ArtworkDeckFixture
  src/test/kotlin/...                   R  DesktopDatabase.kt and DesktopAudioBackend.kt (prototypes) D
  src/main/kotlin/com/musicglass/desktop/
    Main.kt                             S  entry point only; window code goes to app/, chrome to os/
    app/
      RiffWindowHost.kt                 N  windows, modes, minimum sizes
      ShortcutRegistry.kt               N  one keyboard table
    host/
      DesktopHost.kt                    S  actor shell, about 200 lines
      HostSnapshot.kt                   N  durable state only, no clock
      PlaybackClock.kt                  N  clock flow
      QueuePlanner.kt                   N  pure queue diff
      PlaybackController.kt             N  playback commands
      LibraryCommands.kt                N
      SettingsStore.kt                  N  all settings persisted
      NavigationController.kt           N  real back stack
      DesktopSessionRepository.kt       R  moved; position row apart from queue
      DesktopLibraryController.kt       K  moved
      DesktopDownloads.kt               K  moved
      AccountSessionCoordinator.kt      K  moved
      DesktopAuthenticationCoordinator  R  moved; Phase 9
    audio/
      AudioContract.kt                  R  protocol 2.8, then 2.9
      AudioIpcTransport.kt              R  new frames
      AudioWorkerMain.kt                R  dispatch, no runBlocking
      DesktopAudioBridge.kt             R  moved
      DesktopAudioRuntime.kt            R  legacy branches gone
      DesktopDualDeckMixer.kt           S  mixer loop plus QueueWindow and BlendController
      BufferedAudioDeck.kt, NativeAudio.kt, TempoDecoder.kt, DesktopNormalization.kt   K  moved
      CanonicalEqualizerCurve.kt, CanonicalFloatEqualizer.kt, FloatDesktopDsp.kt       K  moved
      CachedByteSource.kt, DesktopAudioCache.kt, PersistentAudioBlocks.kt              K  moved
      DesktopStreamResolver.kt, DesktopProvider.kt, DurableAudioSource.kt              R  moved
      AudioFailures.kt, DesktopSleepTimer.kt, automix/* (runtime files)                K  moved
      DesktopDSP.kt, DesktopFFT.kt, DesktopEqPresets.kt                                D  after callers are gone
    lyrics/
      LyricModels.kt, LyricsKaraoke.kt  K  moved (riff-core in Phase 10)
      LyricsPresentationClock.kt        R  display lead
      LyricsSourceLadder.kt             N  Android's tiers
      DesktopLyricsLookup.kt            R  becomes one ladder entry
      LrcRedLyrics.kt, SyncedLyricsRepository.kt, DesktopLyricsRomanize.kt   R  moved
    catalog/
      DesktopCatalog.kt, DesktopCatalogParser.kt, DesktopSearchSuggestions.kt, DesktopSharedLinks.kt  R  moved
      ArtworkRepository.kt, ArtworkBitmap.kt, ArtworkMath.kt, ArtworkPalette.kt, TrackArtwork.kt      K  moved
      HomeFeedPolicy.kt, ProviderLibrary.kt                    N  Phases 6 and 9
    os/
      WindowsChrome.kt, WindowsFileIdentity.kt, WindowsNetworkCost.kt, DesktopWindowStateManager.kt   K  moved
      PowerManagement.kt, WindowsTray.kt, DesktopOSCoordinator.kt, GlobalShortcuts.kt                 R  moved
      WindowsMediaSession.kt            N  replaces WindowsSMTC.kt (D)
      SingleInstance.kt                 N
      OSIntegrationContract.kt          R  interfaces match real behaviour
      DiscordPresence.kt                D or R, by D6
    update/
      Update*.kt, DesktopUpdateClient.kt   R  moved; live only in Phase 11
    ui/
      theme/  CanonicalTokens (generated), RiffTheme.kt, RiffText.kt, Motion.kt, RiffIcons.kt
      glass/  DesktopGlass.kt, GlassControls.kt, GlassSegmented.kt, TabLens.kt, NavigationLens.kt, AmbientBackdrop.kt
      kit/    RiffInteraction.kt, RiffScrollbar.kt, RiffTooltip.kt, RiffContextMenu.kt, RiffFocusRing.kt, RiffList.kt
      shell/  NavigationSidebar.kt, DesktopDock.kt, InspectorPane.kt, MiniPlayer.kt, WorkspaceDivider.kt, WorkspaceLayout.kt, TitleBar.kt
      screens/ HomeStage, LibraryStage, CollectionStage, ArtistStage, SearchStage, PodcastsStage, RadioStage,
               NowPlayingStage, SettingsStage (bento), DownloadsStage, DiagnosticsStage, CatalogBrowserStage
      encore/ DesktopEncore.kt, DesktopEncoreStories.kt, DesktopEncoreGenres.kt, DesktopEncoreExport.kt
      lyrics/ LyricsPanel.kt (from InspectorPane), LyricsTiming.kt, LyricsShare.kt
    WorkspaceStages.kt                  S  emptied by Task 6.1, then D
    RiffDesign.kt                       D  Task 5.2
    DesktopArtPipeline.kt               D  Task 3.9, once ArtworkDeck has moved
```

**Moved out of main in Task 3.8 (M):** `CanonicalEqualizerAcceptance`, `CodecLifecycleAcceptance`, `DownloadPlaybackAcceptance`, `EncorePlaybackAcceptance`, `EpisodeSpeedAcceptance`, `GenreLookupAcceptance`, `PhaseOneAcceptance`, `PhaseThreeAcceptance`, `PhaseThreeSoak`, `PhaseTwoAcceptance`, `PhaseTwoCatalogProbe`, `PhaseTwoHistoryAcceptance`, `PlaybackIntentAcceptance`, `PlaybackSpeedAcceptance`, `RemotePlaybackEnduranceAcceptance`, `RemoteRestartAcceptance`, `RemoteStreamDecodeAcceptance`, `UpdateManifestValidationMain`, `UpdateVerificationProbe`, `automix/DesktopAutomixAnalysisAcceptance`.

## 1.2 JVM and desktop library stack

Android APIs map to these. Everything marked "in place" is already in the build, so this plan adds very little.

| Need | Android | Desktop (locked) | Status |
|---|---|---|---|
| UI | Jetpack Compose | Compose Multiplatform 1.12.2 with Skia | In place |
| Decode | Media3 | FFmpeg 62/60 through JNI `riff_audio.dll` | In place |
| Output | AudioTrack | WASAPI shared mode, Float32 48 kHz, MMCSS | In place |
| Pipeline | Media3 AudioProcessors | `DesktopDualDeckMixer` in a separate worker JVM | In place |
| Tempo | Media3 Sonic | SoundTouch 2.4.1 | In place |
| EQ | `EqualizerProcessor` | `CanonicalFloatEqualizer` (TPT) | In place |
| FFT | None in the player | **Removed.** No screen uses it. Automix has its own analysis | Task 3.9 |
| Beat model | ONNX Runtime Android | `onnxruntime` 1.31.1 JVM, same model file | In place |
| Database | Room and DataStore | SQLDelight 2.1.0, sqlite-jdbc 3.51.3.0 | In place |
| Disk cache | Media3 SimpleCache | `DesktopAudioCache` with SHA blocks | In place |
| Network | OkHttp, Ktor OkHttp | Ktor CIO 3.6.0 | In place |
| Provider | InnerTubeX | `libs.innertubex`, a desktop artifact | In place, see below |
| Unicode | ICU | icu4j 78.3 | In place |
| Win32 | n/a | JNA 5.19.1 | In place |
| Media session | MediaSession | WinRT SMTC through new `riff_windows.dll` | **New**, Task 4.2 decides the build route |
| Offscreen UI tests | Compose test | `compose.desktop.uiTestJUnit4` | **New, test only** |
| Icons | material-icons-extended | Ported vector subset, no dependency | **New source**, Task 5.5 |

**Version note to resolve in Task 1.7.** The docs say InnerTubeX 0.7.2, but `gradle/libs.versions.toml:36` now says 0.11.2. Because desktop shares that catalog entry, an Android upgrade changes desktop's provider too.

### Changes to `desktop/build.gradle.kts`

```kotlin
// Task 1.5: tokens generated from Android, so they cannot drift
val generateCanonicalTokens = tasks.register("generateCanonicalTokens") { /* section 2.1 */ }
kotlin.sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("generated/riffTokens")) }

// Task 3.4: offscreen Compose tests, no visible window
dependencies { testImplementation(compose.desktop.uiTestJUnit4) }

// Task 3.8: harnesses leave the shipped jar
val acceptance by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
// every JavaExec task, and stageSoakRuntime: classpath = acceptance.runtimeClasspath

// Task 4.3: second native library
val buildWindowsNative = tasks.register<Exec>("buildWindowsNative") { /* scripts/desktop/build-windows-native.py */ }
// createDistributable: dependsOn(buildWindowsNative); also copy riff_windows.dll

// Task 11.1, once D11 is decided
nativeDistributions { packageName = "Riff" }   // and every literal "Riff Desktop Preview" at lines 148-161
```

`upgradeUuid` must not change. `settings.gradle.kts` keeps the `-PriffDesktop=true` gate, so Android builds stay untouched.

## 1.3 Concurrency and state contracts

### Threads and dispatchers

| Owner | Thread or dispatcher | May do | Must never do |
|---|---|---|---|
| UI | Compose main (`Dispatchers.Main`, AWT EDT) | Compose, tray AWT objects | File, database, IPC, hashing |
| Host actor | `RiffController`, one thread, exposed as `hostDispatcher` | Apply commands, publish state, short database reads | `runBlocking`, waiting on the worker |
| Audio operations | `RiffAudioOperations`, one coroutine with a conflated channel | Awaited IPC, descriptor checks | Touching state except through the actor |
| Background | `Dispatchers.IO.limitedParallelism(4)` | Provider, lyrics, artwork, scans | Holding the actor |
| Session writer | `RiffSessionWriter`, one thread | SQLite session commits | Blocking the actor |
| WinRT owner | `RiffMediaSession`, one COM MTA thread | All SMTC calls | Touching Kotlin state directly; it only posts primitive events |
| Worker: reader | Main thread of the worker JVM | Decoding commands, dispatch | Long work |
| Worker: long ops | `RiffLongAudioOperation` | Opening decoders, seeks | Touching mixer fields except by `control {}` |
| Worker: mixer | `RiffFloatMixer` | Mixing, deck swap | Allocation per block, file or network IO, opening a decoder |
| Worker: decoders | One per `BufferedAudioDeck` | FFmpeg, network | Touching the mixer |

Priorities: mixer thread uses MMCSS, decoders are normal, analysis is below normal. A slow analysis can't starve audio.

### State pipelines

```text
Mixer ──ClockAnchor 10 Hz (latest-wins)──► DesktopAudioBridge.clockAnchor
      ──TrackTransition, Fault, Playback (reliable, ordered)──► SharedFlow(replay=0, extraBuffer=64)
                         │
                         ▼
DesktopHost actor ── reduces commands and events
   ├─ state: StateFlow<HostSnapshot>        changes only on real state changes
   ├─ clock: StateFlow<PlaybackClock>       anchor rate; screens read it at leaf level only
   ├─ notices: SharedFlow<Notice>           one-shot messages
   └─ session writes ──► RiffSessionWriter (conflated)

UI screens ── state.map { slice }.distinctUntilChanged() per screen
Lyrics     ── clock + LyricsDocument ─► line index derived in a frame loop, only while visible
OS session ── clock sampled at 4 Hz + state slice ─► WindowsMediaSession.publish
```

```kotlin
internal data class PlaybackClock(
    val epoch: Long, val entryId: String?, val positionMs: Long, val anchorNanos: Long,
    val rate: Float, val playing: Boolean, val secondaryEntryId: String?, val secondaryPositionMs: Long
)
internal fun PlaybackClock.positionAt(nowNanos: Long): Long

internal sealed interface HostEvent { /* worker events after the epoch check */ }
internal fun interface HostCommandSink { fun dispatch(command: DesktopCommand) }   // never blocks
```

### Invariants

1. **Clock ticks never touch `HostSnapshot`.** Task 3.1 enforces this and Task 3.4 tests it.
2. **Worker is truth for what's audible; the host is truth for what's wanted.** The UI shows wanted state as pending until the anchor confirms it.
3. **Every worker message carries `epoch`.** The host drops anything older. A queue patch doesn't change the epoch, but a seek or reload does.
4. **Queue patches carry `queueRevision`.** The worker applies only the newest and echoes it in anchors.
5. **The mixer thread doesn't block or allocate per block.** Opening a deck happens on the long-operations thread, and the result arrives through `control {}`.

### Lifecycle

```text
App start: single-instance check → DesktopHost (database, session restore) → worker handshake → window
Worker loss: fault → actor checkpoints → restart (at most 3 in 60 s) → reload at last heard position → stays paused unless device recovery
Window hidden: worker, host and SMTC keep running; the window peer stays alive
Quit: stop intents → final session write → SMTC revoke → worker Shutdown with a timeout → tray removed → exit
```

---

# 2. Design System & Desktop Adaptation Spec

## 2.1 Theme and token mapping

**One source of truth: Android's `$A/DesignTokens.kt`.**

- **Tokens.** Task 1.5 generates `CanonicalTokens.kt` at build time from Android's file, changing only the package line. Android's token edits then reach desktop without anyone porting them, and the copy can't fail by drifting.
- **Desktop-only values** live in `RiffDesktop` (`ui/theme/RiffTheme.kt`): hover alphas, cursor rules, scrollbar sizes, row heights. Nothing there overrides an Android value.
- **Colour.** `RiffDesign.kt` goes away. Screens read `RiffColors` from the tokens, and literal colours fail a lint test (Task 5.17).
- **Type.** Inter variable (`app/src/main/res/font/inter_variable.ttf`, copied at build time). `RiffText` takes a style from the tokens and always sets `maxLines` and `overflow`. It replaces `Label()` and its size `when`.
- **Shapes.** Token corners, using the same shapes as Android. Literal `RoundedCornerShape(n.dp)` fails the same lint test, except inside `ui/kit`.
- **Motion.** Springs and durations come from the tokens and `$A/GlassMotion.kt`. Reduced motion swaps them for short fades, as on Android.
- **Dynamic colour.** The same chain as Android:

```text
Artwork bytes → ArtworkRepository (off the UI thread) → dominant colour + 36-int mesh (SongItem.deckMesh)
  → AmbientBackdrop mesh at the shell root → riffBackdropSource → glass surfaces sample it
  → backdropScrimAlpha + backdropIsWhite (ported from Android's 5 November fix) keep text readable
```

Palette results are cached per artwork key. Track changes crossfade the mesh with the player's token duration. The dominant-colour code matches Android only if the output does, so Task 5.6 compares results for the same images.

## 2.2 Responsive layout architecture

Keep `WorkspaceLayoutCalculator`. It's the right model and already tested.

```text
┌───────────────────────────────────────────────────────────────┐
│ Title bar 44 dp: drag area, search field, min/max/close        │
├──────────┬──┬──────────────────────────────┬──┬───────────────┤
│ Sidebar  │▕ │ Main stage (max 1280 centred)│▕ │ Inspector     │
│ 224 dp   │▕ │ routes, own scroll, scrollbar│▕ │ Queue/Lyrics  │
│ 180-320  │▕ │                              │▕ │ 360 (280-480) │
├──────────┴──┴──────────────────────────────┴──┴───────────────┤
│ Dock 88 dp: track, transport, seek, volume, inspector toggles  │
└───────────────────────────────────────────────────────────────┘
       One AmbientBackdrop behind everything; panels are glass
```

| Window width | Navigation | Inspector | Main stage |
|---|---|---|---|
| Under 1024 dp | 80 dp rail, icons with tooltips | Overlay from the right, closes with Esc | Full width |
| 1024 to 1439 | Sidebar | Docked if the stage keeps at least 640 dp, otherwise overlay | Fluid |
| 1440 and up | Sidebar | Docked | Centred, max 1280 dp |
| Up to 1920 cap | Same | Same | Stage stays capped, backdrop fills |

- **Minimum sizes:** Main 800×600, already set at `Main.kt:181`. Mini modes keep their current values.
- **Panel widths** persist per window mode. Double-click on a divider resets it.
- **Focused player** takes the whole stage: backdrop full-bleed, art fills the height, with lyrics beside it at 1024 dp and up, and below it when narrower.
- **Title bar:** keep `WindowsChrome` for hit-testing, Snap and DWM corners. Close turns red on hover, as Windows does.

## 2.3 Desktop interaction patterns

All of these come from `ui/kit` so screens can't drift apart. Android's look stays and desktop behaviour is layered on top.

| Pattern | Rule | Where |
|---|---|---|
| Hover | `Modifier.riffHover()`: white at 6% on list rows, 10% on icon buttons, token duration, none under reduced motion | Rows, cards, chips, sidebar, dock, caption buttons |
| Cursor | Hand on anything clickable, resize on dividers, text beam in fields | `riffClickable` sets it |
| Row targets | 40 dp rows and 32 dp icon buttons under the mouse. Clickable area stays at least 32 dp | Lists, dock |
| Scrollbars | `RiffScrollbar`: 8 dp overlay, 4 dp thumb that grows on hover, fades after 1 s idle, always draggable | Every scrolling surface |
| Tooltips | 500 ms delay. Shows name and shortcut. Required on every icon-only control | Rail, dock, title bar, row actions |
| Context menu | `RiffContextMenu`: frosted, the same items as Android's `SongActionMenu`. Right-click and Shift+F10 | Song, album, artist, playlist rows, cards, queue, now-playing art |
| Selection | Click selects, Ctrl+click toggles, Shift+click extends, Ctrl+A all, Enter plays | Library, playlist and queue lists |
| Drag | Rows drag to queue or playlist. Files drop from Explorer anywhere | Lists, shell |
| Wheel | Scrolls lists with 3 lines per notch. Over volume: 2% per notch. Over seek bar: 5 s per notch | Lists, dock |
| Double-click | Row plays. Divider resets. Title bar maximises. The player's double-tap seek becomes left/right arrow keys, kept for art clicks too | Lists, chrome, player |
| Focus | Tab order follows the visual order, `RiffFocusRing` shows only for keyboard use | All |

### Keyboard shortcuts (`ShortcutRegistry`, one table, listed in Settings)

| Keys | Action | Keys | Action |
|---|---|---|---|
| Space | Play or pause, unless typing | Ctrl+L | Like |
| Ctrl+→ / Ctrl+← | Next / previous | Ctrl+K | Search |
| ← / → | Seek 5 s (Shift: 30 s) | Ctrl+O | Open file |
| Ctrl+↑ / Ctrl+↓ | Volume ±5% | Ctrl+M | Mini player |
| Ctrl+Shift+M | Mute | Ctrl+, | Settings |
| Ctrl+S | Shuffle | Ctrl+Q | Quit |
| Ctrl+R | Cycle repeat | Alt+← / Alt+→ | Back / forward |
| Ctrl+J | Toggle queue | Esc | Close overlay, then back |
| Ctrl+Shift+L | Toggle lyrics | F1 | Shortcuts list |

Media keys belong to SMTC (Task 4.5), not `RegisterHotKey`. Optional global shortcuts are off by default and user-chosen (Task 4.8).

---
# 3. Granular, Task-by-Task Execution Plan

Unless a task says otherwise, it also runs **Unit** before closing. The phases in the audit were 0 to 8; here they start at 1, so `Task 1.1` is the first task.

## Phase 1: Safety net

*Done when:* desktop is under version control, the desktop suite is green and part of a lane, and no status file claims something untrue.

- **[x] Task 1.1: Commit the desktop tree to its own branch**
  - **Goal:** Stop 447 files living only on one disk (needs D1).
  - **Files to Create / Modify / Delete:** Stage `desktop/`, `desktop-data/`, `docs/desktop/`, `native/`, `reports/`, `scripts/desktop/`, `scripts/quality/desktop_smoke.py`, `settings.gradle.kts`, the three untracked licence files, `PLAN.md`. Create branch `desktop/foundation` from the current HEAD.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Don't stage Android's uncommitted bug-batch files. Before committing, scan the staged set for secrets, `.toolchain`, build output and corpus audio, and check `native/riff-audio/vendor` licences. Report the size. Don't push.
  - **Verification Step:** `git log -- desktop` shows the commit, `git status` still lists Android's files as modified, and `.\gradlew.bat -PriffDesktop=true :desktop:compileKotlin --offline` still passes.
  - **Done 6 November 2026:** commit `a010fd9` on `desktop/foundation`, 475 files, not pushed. Android's changes were already committed in 0.21.0, so there were none to leave out. `NOTICE.md`, `AGENTS.md`, the desktop note in the feature plan and `scripts/quality/test_desktop_smoke.py` were added too. Gradle must use the project's `.toolchain/jdk-21*` and `.toolchain/gradle-user-home` (as `verify.py` does); with those, compile passes offline and **Unit** gives 258 desktop tests with 1 failure (`TokenParityContractTest`, fixed by Task 1.5) and 41 data tests passing.

- **[x] Task 1.2: Evidence manifest**
  - **Goal:** Make the ignored evidence in `artifacts/` recoverable and verifiable (needs D2).
  - **Files to Create / Modify / Delete:** Create `scripts/desktop/evidence-manifest.py`, `docs/desktop/evidence-manifest.json`.
  - **Key Classes, Interfaces & Signatures:** `python scripts/desktop/evidence-manifest.py {write|check}`.
  - **Implementation Details:** Record path, size and SHA-256 for `result.json`, receipts and logs under `artifacts/desktop`, skipping corpus audio and runtime copies. No personal paths in the JSON.
  - **Verification Step:** `write` then `check` passes. Changing a copied test file makes `check` fail.
  - **Done 6 November 2026:** 576 files (30.1 MB) recorded. A soak `runtime-*` folder keeps its `evidence/`, where the eight-hour results are; only its `lib`, `classes` and `native` copies are skipped. `check` passed on the real tree and failed on an edited copy. 7 new script tests; all 184 script tests pass. The script finds the main checkout through git, so it works from a worktree. Where to keep a copy of the files themselves is still D2.

- **[x] Task 1.3: Add a desktop lane to the quality script**
  - **Goal:** Make desktop breakage visible.
  - **Files to Create / Modify / Delete:** Modify `scripts/quality/verify.py`, `docs/quality/`. Create `scripts/quality/test_desktop_lane.py`.
  - **Key Classes, Interfaces & Signatures:** New lane `--lane desktop`: compile, **Unit**, `:desktop:verifyProviderJvm`. Results follow the existing receipt format.
  - **Implementation Details:** The `pr` lane stays Android-only at first. Skip nothing silently. If a test is excluded, the receipt lists it, including `actualDualDeckPipelineReachesWindowsLoopback`. The lane opens no window.
  - **Verification Step:** `python scripts/quality/verify.py --lane desktop` fails today on the token test, and passes after Task 1.5.
  - **Done 6 November 2026:** gates `compile`, `unit` (read case by case from the JUnit XML, which is cleared first) and `provider-jvm`. The summary lists the six excluded tests from `DESKTOP_EXCLUDED_TESTS` and any test that skipped itself. `test_desktop_lane.py` (10 tests) fails if that table and the build files disagree. All 194 script tests pass. In a worktree, `verify.py` now uses the main checkout's `.toolchain`. The real lane failed as expected: compile and provider-jvm PASS, and **Unit** has 299 tests with `TokenParityContractTest.canonicalObjectsRemainVerbatim` failing. The first run found 4 more failures that Task 1.1 had caused. The signed update vectors in `desktop/src/test/resources/update-v1` are stored with LF, but a fresh Windows checkout (`core.autocrlf=true`) writes them with CRLF, which breaks their signatures and digests. `.gitattributes` now marks them `-text`, and they pass. The second run found two different failures, `WorkspaceLayoutTest.hostHandlesPlaybackAndClockAdvancement` (position 0, expected the duration) and `LibraryIntegrationTest.fullText100kBenchmarkHasSeparateColdAndWarmObservations` (warm FTS p95 24 ms, budget 10 ms). Both passed twice when run alone, so they fail only under load. They can still fail the lane after Task 1.5, so run it with the machine alone. Making them deterministic is new work: ask the owner.

- **[ ] Task 1.4: Desktop CI job**
  - **Goal:** Run the lane away from this laptop (needs D3; nothing is pushed without approval).
  - **Files to Create / Modify / Delete:** Create `.github/workflows/desktop.yml`.
  - **Key Classes, Interfaces & Signatures:** Job `desktop-unit` on `windows-latest`, JDK 21.
  - **Implementation Details:** Unit tests only, no native corpus. Native-dependent tests stay in the local lane. Pin actions the way the existing workflows do.
  - **Verification Step:** Validate the YAML locally. After the owner's approval, the first remote run is green.
  - **Local part done 6 November 2026:** `.github/workflows/desktop.yml` with job `desktop-unit` (`windows-latest`, Temurin 21, the same pinned action commits as `ci.yml`, read-only token, no secret). It runs `verify.py --lane desktop` when desktop, Gradle, `scripts/quality` or workflow files change. The Android SDK is not installed because the desktop build doesn't need it (checked locally with `ANDROID_HOME` unset). The unit tests use no local corpus or `.toolchain` file. The YAML parses with SnakeYAML 2.6 from the project's Gradle cache (no actionlint here). 4 workflow tests in `test_desktop_lane.py`; all 198 script tests pass. **Still open:** pushing, which needs the owner's yes (D3). The first remote run will be red until Task 1.5 fixes the token test. The two load-sensitive tests from Task 1.3, especially the 10 ms FTS budget, may also fail on a shared hosted runner. If they do, the owner decides whether to make them deterministic or move them out of `test`.
  - **Pushed 6 November 2026** (owner's yes): `desktop/foundation` to `origin`. First run [42226617620](https://github.com/leovaldez2k22/riff/actions/runs/42226617620) was red in configuration, before any test ran. Dependency verification refused `junit-bom-5.10.2.module` and `kotlinx-coroutines-bom-1.8.0.pom` on the root `classpath`. Neither was in `verification-metadata.xml`; a local build never asks for them, probably because `setup-gradle`'s init script adds them on the runner. Both were checked against Maven Central's SHA-1 and added, with verification left on. `provider-jvm` passed on the runner.

- **[x] Task 1.5: Generate tokens from Android**
  - **Goal:** Fix today's failing test and remove token drift for good.
  - **Files to Create / Modify / Delete:** Modify `desktop/build.gradle.kts`, `$T/RenderingContractTest.kt`. Delete hand-copied token content from `$D/CanonicalTokens.kt`, keeping only desktop-specific extras in `$D/CanonicalTokensDesktop.kt`.
  - **Key Classes, Interfaces & Signatures:** Gradle task `generateCanonicalTokens`, output `build/generated/riffTokens/com/musicglass/desktop/CanonicalTokens.kt`.
  - **Implementation Details:** Copy the part of `$A/DesignTokens.kt` the parity test already treats as canonical, rewriting only the package line. Fail the build if the markers are missing or the file imports `android.*`. Replace the verbatim test with one checking the generated file has every token name.
  - **Verification Step:** **Unit** is fully green, and the 8 missing tokens (`RiffQueue.*`, `RiffSleep.*`) resolve. Adding a token to a scratch copy of the source regenerates the output.
  - **Done 6 November 2026:** `generateCanonicalTokens` in `desktop/build.gradle.kts` copies the canonical part verbatim, changing only the package line. That part runs from `RiffSpace` up to `RiffTheme`'s doc comment, and from `RiffContrast` to the end. The output is a source directory of `main`. Imports the copied part doesn't use are dropped: Android's file imports Material 3 for `RiffTheme`, which desktop doesn't have. The build fails if a marker is missing or out of order, or if the source imports `android.*`. The hand copy `CanonicalTokens.kt` is deleted. It held no desktop-only values, so `CanonicalTokensDesktop.kt` was not created; desktop extras belong in `RiffDesktop` (section 2.1). `canonicalObjectsRemainVerbatim` became `generatedTokensHaveEveryAndroidToken`, which compares every object, function and `val` name and the verbatim text. A second test, `theNewestTokensResolve`, uses `RiffQueue` and `RiffSleep` directly. With `-PriffTokenSource=<copy>`, a scratch copy with an added `val` regenerated the output, and copies with a renamed marker or an `android.os` import failed the build. `verify.py --lane desktop`: PASS_LOCAL, 300 tests, 0 failed, 0 skipped.

- **[ ] Task 1.6: Honest soak status**
  - **Goal:** A dead soak can never read as RUNNING again.
  - **Files to Create / Modify / Delete:** Modify `$D/PhaseThreeSoak.kt`. Create `scripts/desktop/soak-status.py`. Modify `docs/desktop/phase-3-status.md`.
  - **Key Classes, Interfaces & Signatures:** `result.json` gains `heartbeatEpochMs`, `pid`, `processStartMs`. Status values: `RUNNING`, `STALE`, `PASS`, `FAIL`.
  - **Implementation Details:** Heartbeat every 30 s. The script reports `STALE` when the PID and start time don't match or the heartbeat is over 2 minutes old. Record the 4 November run as stopped, cause undetermined.
  - **Verification Step:** Run the script on the existing evidence: it reports `STALE`. Add a unit test with fixture JSON for each status.

- **[ ] Task 1.7: Settle the provider version**
  - **Goal:** Stop Android upgrades changing desktop's provider unnoticed.
  - **Files to Create / Modify / Delete:** Modify `desktop/build.gradle.kts`, `gradle/libs.versions.toml` (only if a separate entry is needed), `docs/desktop/capability-matrix.md`, `docs/desktop/sbom.md`.
  - **Key Classes, Interfaces & Signatures:** Catalog entry `innertubex-desktop` if versions split.
  - **Implementation Details:** Run `dependencyInsight` to see what desktop really resolves. Compare to the evidence from the 0.7.2 runs. Re-run `verifyProviderJvm`, and note that earlier provider evidence applies only to the version it ran on.
  - **Verification Step:** `:desktop:verifyProviderJvm --offline` passes, and the docs state the resolved version.

## Phase 2: Playback hot path

*Done when:* add, remove, reorder, shuffle, repeat and crossfade changes cause no output reset and no epoch change, and Next doesn't hash anything.

- **[ ] Task 2.1: Write the failing queue-edit tests first**
  - **Goal:** Pin the behaviour before changing it.
  - **Files to Create / Modify / Delete:** Create `$T/QueuePatchContractTest.kt`. Modify `$T/TestAudioRuntime.kt`.
  - **Key Classes, Interfaces & Signatures:** A counting `PcmOutput` fake with `resetCount`, `setPlayingCalls`.
  - **Implementation Details:** Cover: repeat toggle, crossfade toggle, add at end, add next, remove later entry, reorder later entries, duplicate occurrences, 5,000-entry queue. Assert no reset, same epoch, position still advancing. Add `@Ignore` with this task's ID until 2.5 lands, so the suite stays green. No test may use a generated-tone source on a release path.
  - **Verification Step:** Temporarily un-ignored, every test fails against today's code for the expected reason.

- **[ ] Task 2.2: Stop hashing files on each sync**
  - **Goal:** Next and queue edits stop re-reading every local file.
  - **Files to Create / Modify / Delete:** Modify `$D/DurableAudioSource.kt`, `$D/DesktopHost.kt:361`. Create `$T/DurableAudioSourceTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `describeAudioSource(key: String, prior: DurableAudioSource? = null): DurableAudioSource`.
  - **Implementation Details:** If `prior` is `Local` with the same canonical path, size and modified time, reuse its `sha256`. Otherwise hash, off the actor. Keep the "file changed since import" error for real changes. Full verification stays at import and session restore. Provider sources are unchanged.
  - **Verification Step:** Test with a counting stream: 100 syncs read the file once. Touching the file forces a re-hash. Changing the size raises the existing error.

- **[ ] Task 2.3: Protocol 2.8 commands**
  - **Goal:** Define incremental commands on the wire, with no behaviour yet.
  - **Files to Create / Modify / Delete:** Modify `$D/AudioContract.kt`, `$D/AudioIpcTransport.kt`, `$T/PhaseOneProtocolSafetyTest.kt`.
  - **Key Classes, Interfaces & Signatures:**
    ```kotlin
    data class PatchQueue(override val requestId: Long, val revision: Long, val entryIds: List<String>, val currentEntryId: String) : AudioCommand
    data class SelectEntry(override val requestId: Long, val revision: Long, val entryId: String, val positionMs: Long = 0) : AudioCommand
    data class SetRepeatMode(override val requestId: Long, val mode: RepeatMode) : AudioCommand
    data class SetCrossfade(override val requestId: Long, val enabled: Boolean, val durationMs: Int = 2000) : AudioCommand
    // ClockAnchor gains: val queueRevision: Long = 0
    ```
  - **Implementation Details:** `BUILD_ID` becomes `riff-audio/2.8`. Enforce the existing frame bounds (5,000 entries, 256-byte IDs or the current limit). Reject unknown tags. Any change here means any previous soak no longer covers the current protocol, so note that in the status doc.
  - **Verification Step:** Round-trip tests for each command, oversized frames rejected, and a mismatched handshake refused.

- **[ ] Task 2.4: Live repeat and crossfade**
  - **Goal:** The two simplest toggles no longer reload.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt`, `$D/DesktopAudioRuntime.kt`, `$T/QueuePatchContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun setRepeatMode(mode: RepeatMode)`, `fun setCrossfade(enabled: Boolean, durationMs: Int)`.
  - **Implementation Details:** Both run through `control {}`. Repeat changes `nextIndex()`, so it shares the "next changed" path Task 2.5 builds; until then this task handles only the case where the next entry is unchanged and otherwise opens it off-thread. During a started blend, store the value and apply at handoff, since the plan is immutable until then.
  - **Verification Step:** Those test cases un-ignored and passing.

- **[ ] Task 2.5: `patchQueue` in the mixer**
  - **Goal:** Change the queue around playing audio.
  - **Files to Create / Modify / Delete:** Create `$D/audio/QueueWindow.kt`. Modify `$D/DesktopDualDeckMixer.kt`, `$D/DesktopAudioRuntime.kt`, `$T/QueuePatchContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:**
    ```kotlin
    internal class QueueWindow { val entries: List<String>; val index: Int; val revision: Long
        fun next(repeat: RepeatMode): String?; fun applied(ids: List<String>, current: String, revision: Long): QueueWindow }
    fun patchQueue(revision: Long, ids: List<String>, current: String)
    ```
  - **Implementation Details:** Algorithm in section 4, Risk 1. Key points: compare the wanted next entry with `deckB`'s entry ID. If they differ and no blend has started, invalidate the blend, close `deckB`, and open the new one on the long-operations thread. Set `nextOpening` so the end-of-track branch at line 370 waits instead of stopping. Ignore older revisions.
  - **Verification Step:** All `QueuePatchContractTest` cases pass, plus a race test patching 1,000 times during playback with a slow-opening decoder.

- **[ ] Task 2.6: Handoff by identity, opening off-thread**
  - **Goal:** Make handoff correct after patches and keep decoder creation off the mixer thread.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt` (lines 376-379), `$T/AudioEngineContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `private var deckBEntry: String?`, `private fun requestNextDeck(id: String)`.
  - **Implementation Details:** At handoff set the index from `deckBEntry`, not `nextIndex()`. Replace `deckB = deck(next)` (line 378) with `requestNextDeck`. Result arrives by `commands.offer`; a stale result is closed. If the track ends while the next deck is still opening, enter the existing buffering state instead of stopping.
  - **Verification Step:** Tests: next opens slowly at the boundary and produces buffering, then continues; a removed next never plays; **Native** passes.

- **[ ] Task 2.7: Removing the entry that is fading in**
  - **Goal:** Define the hardest patch case.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt`, `$T/QueuePatchContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `private fun abortBlend(reason: String)`, built on `rejectNext` without its fault event.
  - **Implementation Details:** If the incoming entry goes away mid-blend, ramp incoming down and outgoing back over 240 frames using the existing `recoveryGain`. Other patches during a blend are stored and applied at handoff. If the current entry is removed, the host sends `SelectEntry` instead.
  - **Verification Step:** PCM test: no sample jump above a set threshold at the abort point, and the outgoing level returns to 1.0.

- **[ ] Task 2.8: `SelectEntry` for Next, Previous and picks**
  - **Goal:** Next is fast and reuses the prepared deck.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt`, `$D/DesktopAudioRuntime.kt`, `$D/AudioWorkerMain.kt`, `$T/AudioEngineContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun selectEntry(revision: Long, id: String, positionMs: Long)`.
  - **Implementation Details:** If `id` is `deckBEntry`, swap it in with a short ramp and a new epoch, then reset output so nothing old is heard. Otherwise open it on `longOperations`, leaving the old audio playing until it's ready. Add to the worker's long-operation dispatch at `AudioWorkerMain.kt`, replacing any earlier select.
  - **Verification Step:** Timing test with fixture decoders: prepared Next reaches first new audio within a stated bound, recorded not assumed. **Native** passes.

- **[ ] Task 2.9: Pure queue planner in the host**
  - **Goal:** Decide the cheapest command from old and new state.
  - **Files to Create / Modify / Delete:** Create `$D/host/QueuePlanner.kt`, `$T/QueuePlannerTest.kt`.
  - **Key Classes, Interfaces & Signatures:**
    ```kotlin
    internal sealed interface AudioPlan { data object None; data class Patch(val ids: List<String>, val current: String)
        data class Select(val id: String, val positionMs: Long); data class Reload(val reason: String) }
    internal fun planAudio(old: QueueView, new: QueueView, sourcesChanged: Boolean): AudioPlan
    ```
  - **Implementation Details:** No side effects and no Compose types. `Reload` only for: cold start, worker restart, a changed source of the current entry.
  - **Verification Step:** Table tests for each user action, including duplicate songs and empty queues.

- **[ ] Task 2.10: Lazy source preparation**
  - **Goal:** Stop sending one IPC round per queue entry.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopHost.kt`, `$D/AudioContract.kt`, `$D/AudioIpcTransport.kt`, `$D/DesktopDualDeckMixer.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class PrepareSources(requestId, val sources: List<PreparedSource>)`, with `const val PREPARE_WINDOW = 3`.
  - **Implementation Details:** Prepare current plus the next few. The mixer asks when it needs one it lacks through a new reliable event `NeedSource(entryId)`, and buffers meanwhile. Vocal hints still only go to the current pair.
  - **Verification Step:** Test: a 5,000-entry load sends at most 3 sources, and reaching entry 4 fetches on demand with no skipped entry.

- **[ ] Task 2.11: Route host actions through the planner**
  - **Goal:** The user-visible fix.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopHost.kt` (lines 347-404, 556, 570, 576, and the add, remove, reorder, undo, shuffle branches), `$T/PhaseOneControllerRegressionTest.kt`, `$T/AsyncControllerContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `private fun applyQueue(old: HostSnapshot, new: HostSnapshot)`.
  - **Implementation Details:** Keep `syncAudioQueue` for `Reload` only. Patches don't set `audioOperationPending`, so anchors keep flowing. Ignore anchors whose `queueRevision` is older only for queue-derived fields. Actions no longer call `CancelPendingOperation`, which stops playback at `mixer:172`.
  - **Verification Step:** Un-ignore every remaining 2.1 test. Run **Native** and `:desktop:playbackIntentAcceptance`.

- **[ ] Task 2.12: Per-track speed without a reload**
  - **Goal:** Changing speed doesn't restart the queue.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt`, `$D/TempoDecoder.kt`, `$D/DesktopHost.kt:576`, `$T/PlaybackSpeedContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class SetEntryRate(requestId, entryId, rate)`, `fun setEntryRate(id: String, rate: Float)`.
  - **Implementation Details:** Tempo is fixed per decoder today, and `playbackRate` refuses changes while playing. Open a new tempo deck at the current content position off-thread, then swap it in with a 20 ms crossfade. Clock anchors must carry the new `rateUnits` from the swap frame.
  - **Verification Step:** `:desktop:playbackSpeedAcceptance` passes, and a test shows content position continuous across the swap within a stated tolerance.

- **[ ] Task 2.13: Separate position saves from queue saves**
  - **Goal:** End the full-queue durable write each second.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopSessionRepository.kt`, `desktop/src/main/sqldelight/` (new migration), `$D/DesktopHost.kt:335`. Create `$T/SessionWriteRateTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun saveQueue(revision: Long, snapshot: JsonObject)`, `fun savePosition(revision: Long, entryId: String, positionMs: Long)`.
  - **Implementation Details:** Queue row only when its revision changes. Position row at most every 5 s while playing, plus at pause, track change and exit, on `RiffSessionWriter`, conflated. Keep crash recovery at last saved position, and keep old sessions readable.
  - **Verification Step:** Process-death test restores within 5 s of position. Count: 60 s of playback with a 5,000-entry queue writes the queue zero times.

- **[ ] Task 2.14: Remove the query from `enqueue`**
  - **Goal:** No SQLite read for each clock anchor.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopHost.kt:338`, `$T/PhaseTwoIntegrationTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `private fun refreshSavedState()`.
  - **Implementation Details:** Call it on track change and after library changes only.
  - **Verification Step:** Counting repository test: 100 anchors, zero `isSaved` calls. Save state still updates after Like.

- **[ ] Task 2.15: Measure and fix cold seek**
  - **Goal:** Replace the 48-second cold seek on a remote stream.
  - **Files to Create / Modify / Delete:** Modify `native/riff-audio/riff_audio.cpp`, `$D/NativeAudio.kt`, `$D/DesktopProvider.kt`, `$D/CachedByteSource.kt`. Create `$D/audio/WebmSeekIndex.kt` from `$A/WebmSeekIndex.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun seekToFrame(handle: Long, frame: Long): Long`, using demuxer seeking and decoding forward only from the nearest cue.
  - **Implementation Details:** First read the native seek path and record which branch is used, since this audit didn't read `riff_audio.cpp` in full. Keep the local-seek accuracy tests. Handle a stream with no cues by falling back to today's method and saying so in diagnostics. If it takes more than one turn, split it at the measurement.
  - **Verification Step:** `:desktop:remoteRestartAcceptance` shows the before and after times in its log, and codec seek tests pass. No specific target is promised until measured.

- **[ ] Task 2.16: Phase exit run**
  - **Goal:** Prove the whole path, without a window.
  - **Files to Create / Modify / Delete:** Modify `docs/desktop/phase-3-status.md`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Run with the machine alone. Include a 30-minute soak that makes a queue edit every 10 s, using `$D/PhaseThreeSoak.kt` with a new option.
  - **Verification Step:** **Native**, `:desktop:phaseThreeAcceptance` and the soak all pass, with 0 unexplained underruns. **Owner listening check:** repeat, reorder and add during playback sound clean.

## Phase 3: State and structure

*Done when:* clock ticks don't recompose the shell, `handle()` is gone, all settings persist, and no blocking calls remain on the actor.

- **[ ] Task 3.1: `PlaybackClock` flow**
  - **Goal:** Split the clock from state.
  - **Files to Create / Modify / Delete:** Create `$D/host/PlaybackClock.kt`. Modify `$D/DesktopHost.kt:280-294`.
  - **Key Classes, Interfaces & Signatures:** `val clock: StateFlow<PlaybackClock>` (section 1.3).
  - **Implementation Details:** Anchors update `clock`. `HostSnapshot` changes only when the current entry, secondary entry or sleep plan changes. Keep `playbackPositionMs` as a checkpoint for saves, updated at most every 5 s.
  - **Verification Step:** Test: 100 anchors in one track emit zero snapshots.

- **[ ] Task 3.2: Move position readers to the clock**
  - **Goal:** Only the leaf that shows time recomposes.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDock.kt`, `$D/MiniPlayer.kt`, `$D/InspectorPane.kt`, `$D/WorkspaceStages.kt`, `$D/LyricsPresentationClock.kt`, `$D/QueueInspectorProjection.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun rememberPlaybackPosition(clock: StateFlow<PlaybackClock>): State<Long>`.
  - **Implementation Details:** Seek bars read in the draw phase by a lambda, not as a parameter, so a tick doesn't recompose them.
  - **Verification Step:** Compiles with `playbackPositionMs` no longer read from UI; a grep test enforces it.

- **[ ] Task 3.3: Stable catalog**
  - **Goal:** Stop rebuilding on recomposition.
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt:347`, `$D/DesktopCatalog.kt`.
  - **Key Classes, Interfaces & Signatures:** `remember(tracks, playlists) { DesktopCatalog(...) }`; better, derive it on `Dispatchers.Default` and expose `StateFlow<DesktopCatalog>`.
  - **Implementation Details:** Replace the O(artists×albums) filter with a map. Don't run grouping on the UI thread. Switch to `compositionLocalOf` if only some readers need it.
  - **Verification Step:** Count constructor calls over 100 clock ticks: at most 1.

- **[ ] Task 3.4: Offscreen recomposition tests**
  - **Goal:** Guard Invariant 1 without a window.
  - **Files to Create / Modify / Delete:** Modify `desktop/build.gradle.kts`. Create `$T/ShellRecompositionTest.kt`.
  - **Key Classes, Interfaces & Signatures:** A test-only `SideEffect` counter around the shell regions, with `runComposeUiTest`.
  - **Implementation Details:** Offscreen means no visible window, so this respects the hold. Confirm that on this machine before relying on it.
  - **Verification Step:** With a fake host ticking 100 anchors: shell, sidebar, stage and inspector count zero extra recompositions, and only the dock time leaf counts more. Confirm no window appears by checking `Window.getWindows()` stays empty.

- **[ ] Task 3.5: Settings store**
  - **Goal:** Every setting survives a restart.
  - **Files to Create / Modify / Delete:** Create `$D/host/SettingsStore.kt`, `$T/SettingsStoreTest.kt`. Modify `$D/DesktopHost.kt:791-807`.
  - **Key Classes, Interfaces & Signatures:** `@Serializable data class DesktopSettings(val glassQuality: GlassQuality, val reducedMotion: Boolean, val closeToTray: Boolean, /* ... */)`, `suspend fun update(transform: (DesktopSettings) -> DesktopSettings)`.
  - **Implementation Details:** Atomic file write in `DesktopPaths`, unknown fields ignored, a version field. Import the fields `persist()` writes today. Remove the mood filter from settings for now; Task 6.4 brings it back working.
  - **Verification Step:** Round trip, corrupt-file fallback with a notice, and old session import.

- **[ ] Task 3.6: Split `handle()`**
  - **Goal:** Replace the 290-line branch.
  - **Files to Create / Modify / Delete:** Create `$D/host/PlaybackController.kt`, `$D/host/LibraryCommands.kt`, `$D/host/HostSnapshot.kt`. Modify `$D/DesktopHost.kt`.
  - **Key Classes, Interfaces & Signatures:** `internal class PlaybackController(private val host: HostContext) { fun handle(c: DesktopCommand.Playback) }`, with a `sealed interface` per command group.
  - **Implementation Details:** Behaviour-preserving only. Move code in small commits, one command group each. Keep the actor's single thread. Reformat `;`-chained statements in what you touch.
  - **Verification Step:** **Unit** unchanged in count and green, and `DesktopHost.kt` under 300 lines.

- **[ ] Task 3.7: No blocking on the actor**
  - **Goal:** A slow worker can't freeze the app.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopHost.kt` (lines 260, 535, 569 and others), `$D/DesktopAudioBridge.kt`, `$D/AudioWorkerMain.kt:41`, `$D/DesktopProvider.kt:123`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun send(command: AudioCommand): AudioResult`; `fun post(command: AudioCommand)` for fire-and-forget volume.
  - **Implementation Details:** Replace `DiscardOldestPolicy` executor with a conflated coroutine channel. Volume is latest-wins. Remove `runBlocking` from production paths, and the host's tickets stay as they are.
  - **Verification Step:** Test with a worker fake that sleeps 30 s: `dispatch` returns immediately and UI state still updates. A grep test finds no `runBlocking` in main outside `acceptance`.

- **[ ] Task 3.8: Move harnesses out of main**
  - **Goal:** Ship only the product.
  - **Files to Create / Modify / Delete:** Move the files listed in section 1.1 to `desktop/src/acceptance/kotlin/com/musicglass/desktop/`. Modify `desktop/build.gradle.kts`, `$D/Main.kt:48-57`, `scripts/desktop/verify-phase1-packaged.py`, `scripts/desktop/audit-phase1-package.py`.
  - **Key Classes, Interfaces & Signatures:** `-PriffAcceptanceImage=true` builds a test image with a `RiffAcceptance` launcher.
  - **Implementation Details:** Packaged acceptance needs a packaged entry point today, so it gets a test image instead of flags in `main()`. Every `JavaExec` task uses the new classpath.
  - **Verification Step:** A release image's jar has no `*Acceptance` classes (audit script checks it), and the test image still passes `verify-phase1-packaged.py`.

- **[ ] Task 3.9: Delete dead code**
  - **Goal:** Remove what's unreachable.
  - **Files to Create / Modify / Delete:** Delete `$D/DesktopFFT.kt`, `$D/DesktopDSP.kt`, `$D/DesktopEqPresets.kt`, the `DesktopArtPipeline` class (keep `ArtworkDeck`). Modify `$D/DesktopDualDeckMixer.kt`, `$D/DesktopAudioRuntime.kt`, `$D/AudioContract.kt`, `$D/AudioIpcTransport.kt`, `$D/AudioWorkerMain.kt`, `$D/DesktopAudioBridge.kt`, `$D/WorkspaceStages.kt`, `$D/WorkspaceDivider.kt`, `$T/FloatProductionDspTest.kt`.
  - **Key Classes, Interfaces & Signatures:** Protocol 2.9 removes `SetDSPConfig`, `SetPlaybackRate`, `FeatureSpectrumFrame`.
  - **Implementation Details:** Confirm each symbol's callers first, because `DesktopEqPresets` and `SetDSPConfig` still have references. Move or drop tests. `FloatDesktopDsp` keeps its gain and limiter. Remove `EncoreStatCard`, `isHovered`, the `RiffPostDspAnalysis` thread and comments that claim conformance to a section. Removing the FFT also removes a 20 ms polling thread.
  - **Verification Step:** **Unit** and **Native** pass, and the equalizer acceptance still matches its reference within 1.5e-5.

- **[ ] Task 3.10: Replace prototype-backed tests**
  - **Goal:** Tests exercise production code.
  - **Files to Create / Modify / Delete:** Delete `$T/DesktopDatabase.kt`, `$T/DesktopAudioBackend.kt`. Modify `$T/LargeLibraryBenchmarkTest.kt`, `$T/StorageAndCacheContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** Use `SqliteLibraryRepository`, `DesktopAudioCache`.
  - **Implementation Details:** Keep any assertion that still means something. Report any that fail on the real store rather than weakening them.
  - **Verification Step:** **Unit** green, with the prototypes gone.

- **[ ] Task 3.11: Navigation controller**
  - **Goal:** Real back and forward.
  - **Files to Create / Modify / Delete:** Create `$D/host/NavigationController.kt`, `$T/NavigationControllerTest.kt`. Modify `$D/DesktopHost.kt:480,642,682`, `$D/Main.kt:245-302,470,531`.
  - **Key Classes, Interfaces & Signatures:** `data class NavEntry(val route: WorkspaceRoute, val args: RouteArgs, val scrollKey: String)`, `fun push`, `fun back(): Boolean`, `fun forward(): Boolean`.
  - **Implementation Details:** Bounded at 64 entries. Drill-downs push, sidebar picks reset. Mini mode saves and restores the stack. This also fixes the tray-restore bug at `DesktopHost.kt:480`: restore only returns from Mini when actually in Mini. Scroll position returns on back.
  - **Verification Step:** Unit tests, including "ShowMain in Main mode changes nothing".

## Phase 4: OS truth

*Done when:* the Windows media overlay and media keys control Riff, nothing swallows keys, and there's one instance.

- **[ ] Task 4.1: Remove the media-key hotkey now**
  - **Goal:** Stop likely swallowing Play/Pause system-wide.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopOSCoordinator.kt:57-65`, `$T/WindowsIntegrationContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** None new.
  - **Implementation Details:** This is an inference from the Win32 contract, not an observed failure, so record it that way. The change is harmless either way, because nothing handles the message.
  - **Verification Step:** Test asserts no registration of `VK_MEDIA_*`. **Owner check:** Play/Pause reaches another app while Riff runs.

- **[ ] Task 4.2: WinRT build spike**
  - **Goal:** Prove how to build the SMTC bridge. The native build uses `zig c++ -target x86_64-windows-gnu`, and the earlier report assumed C++/WinRT, which may not compile there.
  - **Files to Create / Modify / Delete:** Create `native/riff-windows/spike.cpp`, `docs/desktop/adr-002-smtc-bridge.md`.
  - **Key Classes, Interfaces & Signatures:** A console probe calling `ISystemMediaTransportControlsInterop::GetForWindow` on a hidden message-only window.
  - **Implementation Details:** Try in order: (a) C++/WinRT headers from the Windows SDK under zig; (b) raw ABI interfaces from the mingw `windows.media.h`, with `RoGetActivationFactory`; (c) MSVC for this one DLL (needs a toolchain download approval). Record what works. A hidden message-only window isn't a screen-test activity, but if it can't bind, use a real top-level hidden window and note it.
  - **Verification Step:** The ADR names the chosen route, with the compile command and the probe's output showing an SMTC object was obtained.

- **[ ] Task 4.3: `riff_windows.dll` publishing**
  - **Goal:** A real session with title and state.
  - **Files to Create / Modify / Delete:** Create `native/riff-windows/riff_windows.cpp`, `scripts/desktop/build-windows-native.py`. Modify `desktop/build.gradle.kts`, `native/riff-audio/dependencies.json` (or a sibling file).
  - **Key Classes, Interfaces & Signatures:** C ABI: `rw_session_create(hwnd, callback)`, `rw_session_publish(h, title, artist, album, durationUs, positionUs, playing, canSeek)`, `rw_session_set_art(h, path, generation)`, `rw_session_close(h)`, `rw_handle_count()`.
  - **Implementation Details:** Follows the sequence in the 2 November report, section on the SMTC contract: one COM MTA thread, HWND validated against this process, revokers. Callback passes primitives only.
  - **Verification Step:** Native test publishes and reads back with `GlobalSystemMediaTransportControlsSessionManager` from a separate test process. **Owner screen check:** Win+A overlay.

- **[ ] Task 4.4: `WindowsMediaSession` in Kotlin**
  - **Goal:** Replace the stub.
  - **Files to Create / Modify / Delete:** Create `$D/os/WindowsMediaSession.kt`. Delete `$D/WindowsSMTC.kt`. Modify `$D/OSIntegrationContract.kt`, `$T/WindowsIntegrationContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** The report's `data class WindowsMediaSnapshot(...)`, `interface WindowsMediaSession { suspend fun publish(snapshot); val requests: Flow<PlaybackIntent> }`.
  - **Implementation Details:** JNA on the `RiffMediaSession` thread. A failure is a capability error, and never a fake Playing state. Tests use a fake native layer but must not call a "contract" pass real delivery: mark them as component tests.
  - **Verification Step:** **Unit**, plus the Task 4.3 external observer now driven through Kotlin.

- **[ ] Task 4.5: Route buttons into the host**
  - **Goal:** Media keys and the overlay control playback.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopOSCoordinator.kt` (rewrite lines 29-48), `$D/Main.kt`, `$D/DesktopHost.kt`.
  - **Key Classes, Interfaces & Signatures:** `class DesktopOSCoordinator(sink: HostCommandSink, session: WindowsMediaSession, state: StateFlow<HostSnapshot>, clock: StateFlow<PlaybackClock>)`.
  - **Implementation Details:** Remove every local `setPlaying(!isPlaying)`. Requests become commands, and the state published comes from the host only. Handle seek requests.
  - **Verification Step:** Fake-session test: each button produces the right command and no local state changes. **Owner check:** media keys, headset buttons, overlay.

- **[ ] Task 4.6: Timeline and artwork**
  - **Goal:** A complete overlay.
  - **Files to Create / Modify / Delete:** Modify `$D/os/WindowsMediaSession.kt`, `native/riff-windows/riff_windows.cpp`, `$D/ArtworkRepository.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun exportForSession(key: String): File?`.
  - **Implementation Details:** Position at 4 Hz plus discontinuities. Artwork is a bounded local file, and SMTC never fetches a provider URL. A generation number drops stale art.
  - **Verification Step:** Observer test reads the timeline and thumbnail, and a fast track change never shows old art.

- **[ ] Task 4.7: `WM_APPCOMMAND` fallback**
  - **Goal:** Handle commands sent to the focused window.
  - **Files to Create / Modify / Delete:** Modify `$D/WindowsChrome.kt` (window proc), `$D/os/WindowsMediaSession.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun onAppCommand(cmd: Int): Boolean`.
  - **Implementation Details:** The window proc only queues events. Dedupe against SMTC with a short per-command window, because one key can arrive by both routes. Test on real keyboards before keeping it; delete it if SMTC alone suffices.
  - **Verification Step:** Unit test of the dedupe. **Owner check:** one press, one action.

- **[ ] Task 4.8: Optional global shortcuts**
  - **Goal:** Make hotkeys work, or not exist.
  - **Files to Create / Modify / Delete:** Modify `$D/GlobalShortcuts.kt`, `$D/host/SettingsStore.kt`.
  - **Key Classes, Interfaces & Signatures:** `class WindowsShortcutThread : AutoCloseable { fun register(s: GlobalShortcut): RegisterResult }`.
  - **Implementation Details:** A dedicated thread with a message pump and `RegisterHotKey`. Check the return value and report conflicts to Settings. Off by default, no media keys.
  - **Verification Step:** Native test posts a hotkey to itself. Conflict test.

- **[ ] Task 4.9: Tray repairs**
  - **Goal:** Tray survives Explorer and looks like Riff.
  - **Files to Create / Modify / Delete:** Modify `$D/WindowsTray.kt`, `$D/DesktopOSCoordinator.kt`.
  - **Key Classes, Interfaces & Signatures:** `TaskbarCreated` handled from a message-only window.
  - **Implementation Details:** Remove the cyan and violet icon (lines 123-127) and use the launcher art. AWT objects stay on the EDT. Show current track and correct Play or Pause. A `PopupMenu` stays until the design phase offers better.
  - **Verification Step:** Unit test for routing. **Owner screen check:** restart Explorer, tray returns.

- **[ ] Task 4.10: Close to tray**
  - **Goal:** The window-close choice (needs D5).
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt:165`, `$D/host/SettingsStore.kt`, `$D/WorkspaceStages.kt` (Settings).
  - **Key Classes, Interfaces & Signatures:** `DesktopSettings.closeToTray: Boolean`.
  - **Implementation Details:** Hide keeps the HWND, so the SMTC binding survives. Quit always really quits.
  - **Verification Step:** State test. **Owner screen check.**

- **[ ] Task 4.11: Single instance**
  - **Goal:** A second launch hands off its files and links.
  - **Files to Create / Modify / Delete:** Create `$D/os/SingleInstance.kt`, `$T/SingleInstanceTest.kt`. Modify `$D/Main.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun acquire(): Result<Primary | Secondary>`, `fun forward(args: List<String>): Boolean`.
  - **Implementation Details:** User-scoped mutex plus named pipe with a length-bounded, validated message. Handle a stale pipe and the start-up race. The worker doesn't take part. Data dir overrides get their own scope, or tests would collide.
  - **Verification Step:** Parallel-launch test with Unicode paths using separate JVMs and no window.

- **[ ] Task 4.12: Power request audit**
  - **Goal:** Awake state always matches playback.
  - **Files to Create / Modify / Delete:** Modify `$D/PowerManagement.kt`, `$D/DesktopOSCoordinator.kt`.
  - **Key Classes, Interfaces & Signatures:** The report's `PlaybackPowerRequest`.
  - **Implementation Details:** I didn't read this file in full, so read it first. Clear on pause, stop and worker crash. Keep the display free to sleep.
  - **Verification Step:** `powercfg /requests` during a native playback test, then cleared after a killed worker.

- **[ ] Task 4.13: Discord decision**
  - **Goal:** Remove the placeholder ID (needs D6).
  - **Files to Create / Modify / Delete:** Delete `$D/DiscordPresence.kt`, or modify it with a real ID. Modify `$D/DesktopOSCoordinator.kt`.
  - **Key Classes, Interfaces & Signatures:** None if removed.
  - **Implementation Details:** Never ship `1198765432109876543`. Update the capability matrix.
  - **Verification Step:** A grep test confirms the ID is gone.

- **[ ] Task 4.14: Remove preview identity from the UI**
  - **Goal:** The app stops calling itself a feasibility build.
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt:168`, `$D/WorkspaceStages.kt:943-945`, `$D/DesktopBuildIdentity.kt`.
  - **Key Classes, Interfaces & Signatures:** `DesktopBuildIdentity.displayName`.
  - **Implementation Details:** Title and About read one value. Package name itself waits for D11, at Task 11.1. Say "Windows 11 x64" only as a fact on the About page.
  - **Verification Step:** A grep test finds no "Milestone", "Feasibility" or "Preview" in main.

## Phase 5: Design shell

*Done when:* offscreen renders show glass over artwork colour on every panel, every interaction in section 2.3 exists, and lint tests block literal styling. Final acceptance waits for the owner's screen check.

- **[ ] Task 5.1: Offscreen render gallery**
  - **Goal:** See the UI without opening a window, so design work can continue under the hold.
  - **Files to Create / Modify / Delete:** Create `$T/ui/OffscreenGallery.kt`, `scripts/desktop/render-gallery.py`.
  - **Key Classes, Interfaces & Signatures:** `fun renderScreen(name: String, size: IntSize, scale: Float, content: @Composable () -> Unit): File`, built on `ImageComposeScene`.
  - **Implementation Details:** Output PNGs to `artifacts/desktop/gallery/<date>/` at 1280×800, 1920×1080 and 800×600, scale 1 and 1.5. Fixture data stays in test sources only. Check the SkSL glass shader works under the raster surface. If it doesn't, note it and find another offscreen path before relying on these images.
  - **Verification Step:** The script writes PNGs with no window opened. The owner can review them whenever they like.

- **[ ] Task 5.2: Theme layer**
  - **Goal:** One way to read design values.
  - **Files to Create / Modify / Delete:** Create `$D/ui/theme/RiffTheme.kt`. Delete `$D/RiffDesign.kt`. Modify its callers.
  - **Key Classes, Interfaces & Signatures:** `object RiffDesktop { val hoverRow; val hoverIcon; val scrollbarWidth; val desktopRowHeight; ... }`, `@Composable fun RiffTheme(reducedMotion: Boolean, content: @Composable () -> Unit)`.
  - **Implementation Details:** Map old `RiffDesign` values to tokens. Any value without a token gets one in `RiffDesktop` with a comment saying why.
  - **Verification Step:** **Unit**, and gallery images match before and after.

- **[ ] Task 5.3: `RiffText`**
  - **Goal:** Text never wraps by accident.
  - **Files to Create / Modify / Delete:** Create `$D/ui/theme/RiffText.kt`, `$T/ui/RiffTextTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun RiffText(text: String, style: RiffTextStyle, maxLines: Int = 1, overflow: TextOverflow = TextOverflow.Ellipsis, color: Color = Color.Unspecified, modifier: Modifier = Modifier)`.
  - **Implementation Details:** No size parameter. Styles come from Android's token names. No `font` parameter: remove the 53 threaded `font: FontFamily` parameters through the theme as files are migrated.
  - **Verification Step:** Offscreen test: a 300-character title stays one line in a row.

- **[ ] Task 5.4: Migrate `Label` to `RiffText`**
  - **Goal:** Replace 241 calls.
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt:760`, `$D/WorkspaceStages.kt`, `$D/InspectorPane.kt`, `$D/DesktopDock.kt`, `$D/MiniPlayer.kt`, `$D/NavigationSidebar.kt`, `$D/LibraryTools.kt`, `$D/CatalogBrowserStage.kt`, `$D/DesktopEncore.kt`, `$D/DesktopEncoreStories.kt`, `$D/CanonicalEqualizerPanel.kt`, `$D/PlaybackSpeedControls.kt`. Delete `Label`.
  - **Key Classes, Interfaces & Signatures:** None new.
  - **Implementation Details:** Mechanical, one file per commit. If this exceeds a turn, stop at a file boundary and continue next turn. Pick the style that matches the Android counterpart, not just the old size.
  - **Verification Step:** No `Label(` remains, and gallery images show no clipped or wrapped rows.

- **[ ] Task 5.5: Icons**
  - **Goal:** Replace emoji and placeholder shapes.
  - **Files to Create / Modify / Delete:** Create `$D/ui/theme/RiffIcons.kt` from `$A/RoundedTransportIcons.kt` and `$A/SettingIcons.kt`. Modify `$D/WorkspaceStages.kt:733,744,754`, `$D/DesktopDock.kt:144-145`, `$D/NavigationSidebar.kt:51-58`. Update `NOTICE.md`.
  - **Key Classes, Interfaces & Signatures:** `object RiffIcons { val Play: ImageVector; val Pause; val Next; val Previous; val Shuffle; val Repeat; val RepeatOne; ... }`.
  - **Implementation Details:** Copy vector data, keep licence notices, and add no icon dependency. The logo comes from Android's launcher art (`ic_launcher.xml`) as a vector.
  - **Verification Step:** No emoji in main (a grep test on those code points), and gallery images show the icons.

- **[ ] Task 5.6: Shell-level backdrop**
  - **Goal:** Put artwork colour behind everything.
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt`, `$D/AmbientBackdrop.kt`, `$D/ArtworkPalette.kt`. Port `backdropScrimAlpha`, `backdropIsWhite` from `$A/PlayerBackdrop.kt` into `$D/AmbientBackdrop.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun ShellBackdrop(track: () -> SongItem, quality: GlassQuality, modifier: Modifier)`.
  - **Implementation Details:** One backdrop at the root under `riffBackdropSource`. Crossfade on track change. Mesh calculation stays off the UI thread. Compare palette output with Android's on 10 shared images, and align them if different.
  - **Verification Step:** Gallery images for dark, bright and white covers, and a palette parity test. Measure frame cost offscreen; final numbers wait for the owner's screen check.

- **[ ] Task 5.7: Glass on the sidebar and dock**
  - **Goal:** The panels sample the backdrop.
  - **Files to Create / Modify / Delete:** Modify `$D/NavigationSidebar.kt:38-39`, `$D/DesktopDock.kt:66-67`, `$D/DesktopGlass.kt`.
  - **Key Classes, Interfaces & Signatures:** `Modifier.riffGlassPanel(quality: GlassQuality, shape: Shape)`.
  - **Implementation Details:** Remove the opaque `background`. Quality settings fall back to a tinted solid. Check text contrast at least 4.5:1 over white covers using the scrim.
  - **Verification Step:** Gallery images at each glass quality, and a contrast test on rendered pixels.

- **[ ] Task 5.8: Glass on the inspector, mini player and focused player**
  - **Goal:** Finish the panels.
  - **Files to Create / Modify / Delete:** Modify `$D/InspectorPane.kt:81`, `$D/MiniPlayer.kt:53-54`, `$D/WorkspaceStages.kt:668-775`.
  - **Key Classes, Interfaces & Signatures:** Same modifier.
  - **Implementation Details:** The mini window has its own backdrop instance. Focused player uses the full-bleed version from section 2.2.
  - **Verification Step:** Gallery images, including mini sizes.

- **[ ] Task 5.9: Hover, cursor and click primitives**
  - **Goal:** Desktop behaviour in one place.
  - **Files to Create / Modify / Delete:** Create `$D/ui/kit/RiffInteraction.kt`, `$T/ui/RiffInteractionTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun Modifier.riffHover(kind: HoverKind): Modifier`, `fun Modifier.riffClickable(role: Role, label: String, onDoubleClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier`.
  - **Implementation Details:** Sets hand cursor, semantics and hover state. Hover shouldn't recompose the row, so draw it through a draw modifier.
  - **Verification Step:** `runComposeUiTest` with mouse-enter input, counting no recomposition of the row content.

- **[ ] Task 5.10: Scrollbars**
  - **Goal:** Every scrolling surface shows position.
  - **Files to Create / Modify / Delete:** Create `$D/ui/kit/RiffScrollbar.kt`, `$D/ui/kit/RiffList.kt`. Modify `$D/WorkspaceStages.kt`, `$D/InspectorPane.kt`, `$D/CatalogBrowserStage.kt`, `$D/LibraryTools.kt`, `$D/DesktopEncore.kt`, `$D/NavigationSidebar.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun RiffLazyColumn(state: LazyListState, ...)`, wrapping `VerticalScrollbar` styled per section 2.3.
  - **Implementation Details:** Use `RiffLazyColumn` everywhere. Under reduced motion, the bar doesn't fade.
  - **Verification Step:** A grep test: no bare `LazyColumn` or `verticalScroll` outside `ui/kit`. Offscreen drag test moves the list.

- **[ ] Task 5.11: Tooltips**
  - **Goal:** Explain icon-only controls.
  - **Files to Create / Modify / Delete:** Create `$D/ui/kit/RiffTooltip.kt`. Modify `$D/DesktopDock.kt`, `$D/NavigationSidebar.kt`, `$D/Main.kt:724-757`, `$D/InspectorPane.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun RiffTooltip(text: String, shortcut: String? = null, content: @Composable () -> Unit)`.
  - **Implementation Details:** Frosted, as in `$A/FrostedPopup.kt`. Screen-reader name stays on the control.
  - **Verification Step:** Offscreen hover test after the delay, and gallery image.

- **[ ] Task 5.12: Context menus**
  - **Goal:** Right-click everywhere that has actions.
  - **Files to Create / Modify / Delete:** Create `$D/ui/kit/RiffContextMenu.kt`, `$D/ui/SongActions.kt`. Modify `$D/WorkspaceStages.kt`, `$D/CatalogBrowserStage.kt`, `$D/InspectorPane.kt:360`, `$D/DesktopDock.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class MenuAction(val id: String, val label: String, val icon: ImageVector?, val shortcut: String?, val enabled: Boolean, val run: () -> Unit)`, `fun songActions(song: SongItem, context: ActionContext): List<MenuAction>`.
  - **Implementation Details:** Actions follow `$A/SongActionMenu.kt` as of today, which removed Play next. Hide actions that don't work yet, rather than showing them disabled. Shift+F10 and the Menu key open it. Keyboard navigation inside it.
  - **Verification Step:** Offscreen right-click test, a semantics test, and a test that each action reaches a host command.

- **[ ] Task 5.13: Title bar and caption buttons**
  - **Goal:** Native-feeling chrome.
  - **Files to Create / Modify / Delete:** Create `$D/ui/shell/TitleBar.kt`. Modify `$D/Main.kt:724-757`, `$D/WindowsChrome.kt` (only if hit regions change).
  - **Key Classes, Interfaces & Signatures:** `@Composable fun TitleBar(state: TitleBarState, onCaption: (CaptionAction) -> Unit)`.
  - **Implementation Details:** Hover colours per Windows conventions, red close. Keep maximize-button hit-test code for Snap layouts, which `RenderingContractTest` checks.
  - **Verification Step:** Hit-test tests stay green. **Owner screen check:** Snap hover, Win+Z.

- **[ ] Task 5.14: Divider repair**
  - **Goal:** Panel resizing works.
  - **Files to Create / Modify / Delete:** Modify `$D/Main.kt:331`, `$D/WorkspaceDivider.kt:40,78,81`, `$T/WorkspaceLayoutTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun Modifier.dividerDrag(state: DividerState)`.
  - **Implementation Details:** Add both widths to the `remember` keys. Use a stable `pointerInput` key so the gesture doesn't restart mid-drag. Convert pixels with `LocalDensity`. Persist at drag end, not per pixel.
  - **Verification Step:** Offscreen drag test at scale 1 and 1.5: width changes by the right dp.

- **[ ] Task 5.15: Shortcut registry**
  - **Goal:** Every shortcut in section 2.3.
  - **Files to Create / Modify / Delete:** Create `$D/app/ShortcutRegistry.kt`, `$T/ShortcutRegistryTest.kt`. Modify `$D/Main.kt:245-302`.
  - **Key Classes, Interfaces & Signatures:** `data class Shortcut(val id: String, val keys: KeyCombo, val label: String, val whenTyping: Boolean = false, val run: (HostCommandSink) -> Unit)`.
  - **Implementation Details:** Text fields swallow Space and arrows. Includes the Esc fix for Discover. Settings shows this same table, and F1 opens it.
  - **Verification Step:** Tests for every row, including "typing in search doesn't pause".

- **[ ] Task 5.16: Wheel, motion and route transitions**
  - **Goal:** Finish desktop feel.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDock.kt:392`, `$D/Main.kt:470`, `$D/Motion.kt`, `$D/InspectorPane.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun RouteHost(entry: NavEntry, content: @Composable (NavEntry) -> Unit)`.
  - **Implementation Details:** Seek by wheel, volume per notch. `AnimatedContent` with token motion and shared transition between dock art and player where feasible. Reduced motion swaps it for a fade. Port `$A/EdgeFade.kt` for list edges.
  - **Verification Step:** Offscreen test at frames across a transition, with reduced motion on and off.

- **[ ] Task 5.17: Design lint**
  - **Goal:** Keep the shell consistent.
  - **Files to Create / Modify / Delete:** Create `$T/DesignLintTest.kt`.
  - **Key Classes, Interfaces & Signatures:** Source-scanning test with an explicit, shrinking allowlist.
  - **Implementation Details:** Flag `Color(0x`, `RoundedCornerShape(<n>.dp)`, `.alpha(<literal>)` and raw sizes outside `ui/theme` and `ui/kit`. Start with today's counts, requiring they can only go down. They reach zero by the end of Phase 6.
  - **Verification Step:** Adding a literal to a test copy fails the test.

## Phase 6: Screens and everyday parity

*Done when:* each Android screen in the parity register below works with real data, or has a recorded owner exception.

- **[ ] Task 6.1: Split `WorkspaceStages.kt`**
  - **Goal:** One file per screen, no behaviour change.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/HomeStage.kt`, `LibraryStage.kt`, `CollectionStage.kt`, `ArtistStage.kt`, `SearchStage.kt`, `PodcastsStage.kt`, `RadioStage.kt`, `NowPlayingStage.kt`, `SettingsStage.kt`, each under `$D/ui/screens/`. Delete `$D/WorkspaceStages.kt`.
  - **Key Classes, Interfaces & Signatures:** Each stage takes its state slice and `HostCommandSink`, not the whole snapshot.
  - **Implementation Details:** Mechanical first, then narrow the parameters. This is the point where the design lint counts drop in each file you touch.
  - **Verification Step:** Gallery images unchanged, and **Unit** green.

- **[ ] Task 6.2: Queue semantics from today's Android batch**
  - **Goal:** Desktop's queue behaves like Android's.
  - **Files to Create / Modify / Delete:** Create `$D/host/QueueOrder.kt` from `$A/QueueOrder.kt`, `$T/QueueOrderTest.kt`. Modify `$D/host/PlaybackController.kt`, `$D/InspectorPane.kt`.
  - **Key Classes, Interfaces & Signatures:** Follow the signatures in `$A/QueueOrder.kt`.
  - **Implementation Details:** See `docs/quality/owner-bug-batch-2026-11-05.md`: shuffle once with a reshuffle button, Add to queue means next after what you added before, Play next gone. Port tests from `app/src/test`. Moved to `:riff-core` in Phase 10.
  - **Verification Step:** Same test table as Android passes.

- **[ ] Task 6.3: Home feed**
  - **Goal:** Replace the placeholder Home.
  - **Files to Create / Modify / Delete:** Create `$D/catalog/HomeFeedPolicy.kt` from `$A/HomeFeedPolicy.kt`. Modify `$D/ui/screens/HomeStage.kt`, `$D/DesktopProvider.kt`, `$D/DesktopCatalogParser.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun DesktopProvider.homeFeed(continuation: String?): HomePage`, with cards as in `$A/HomeFeed.kt`.
  - **Implementation Details:** Shelves with cards, rows and "see all". Wider screens show more columns rather than larger cards. Cached through `CatalogCacheRepository`, so offline shows the last feed with a banner. Remove the marketing copy. Anonymous feed until Phase 9.
  - **Verification Step:** Parser test on recorded responses, a live `phaseTwoCatalogProbe` run, and gallery image.

- **[ ] Task 6.4: Moods and Speed dial**
  - **Goal:** Finish Home.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/HomeMoods.kt`, `$D/ui/screens/SpeedDial.kt`. Port from `$A/Moods.kt`, `$A/MoodPhotos.kt`, `$A/MoodBackdrop.kt`, `$A/SpeedDialPins.kt`. Modify `$D/host/SettingsStore.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class SpeedDialPin(...)`.
  - **Implementation Details:** Copy `$A/MoodPhotoPolicy.kt` rules. Mood filter from Settings returns, working and persisted.
  - **Verification Step:** Pin persistence test, policy tests from Android, and gallery image.

- **[ ] Task 6.5: Now Playing**
  - **Goal:** Full player stage.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/NowPlayingStage.kt`. Port `$A/PlayerTitleMarquee.kt`, `$A/PlayerArtwork.kt`, relevant parts of `$A/DoubleTapSeek.kt`, `$A/PlayerActionMenu.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun NowPlayingStage(track, clock, actions, layout: PlayerLayout)`.
  - **Implementation Details:** Layout per section 2.2. Art scales with height, no 360 dp cap, using a high-resolution artwork request with a `ArtworkRepository` size key. Title marquee only in the player.
  - **Verification Step:** Gallery at all sizes, and back from player returns to the previous route.

- **[ ] Task 6.6: Card deck in the player**
  - **Goal:** Android's previous and next card peek.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/PlayerDeck.kt` from `$A/CardPeek.kt`, `$A/PlayerDeckParts.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun PlayerDeck(prev: SongItem?, current: SongItem, next: SongItem?, onNext: () -> Unit, onPrev: () -> Unit)`.
  - **Implementation Details:** Swipe becomes click-drag and horizontal wheel. Clicking a peeking card plays it.
  - **Verification Step:** Offscreen drag test, reduced-motion variant.

- **[ ] Task 6.7: Library table**
  - **Goal:** A desktop library.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/LibraryStage.kt`, `$D/DesktopLibraryController.kt`, `$DD/SqliteLibraryRepository.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun pagedTracks(scope, sort: TrackSort, offset: Int, limit: Int)`, `data class SelectionState(val anchor: String?, val ids: Set<String>)`.
  - **Implementation Details:** Virtualised, no 500 limit, no `forEach` inside an item. Sortable columns: title, artist, album, duration, date added; columns drop as the width shrinks. Selection per section 2.3. Context menu acts on the whole selection.
  - **Verification Step:** 100k-row benchmark on the production store scrolls by data pages under the existing target. Selection tests.

- **[ ] Task 6.8: Album and artist grids, collection pages**
  - **Goal:** Replace the squeezed `Row` and the dead chips.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/LibraryStage.kt`, `CollectionStage.kt`, `ArtistStage.kt` (under `$D/ui/screens/`), `$D/DesktopCatalog.kt:13`. Port from `$A/ImmersiveCollection.kt`, `$A/ArtistShelfScreen.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun RiffAdaptiveGrid(items, minCell = 160.dp, ...)`.
  - **Implementation Details:** Chips show names, not IDs, and they navigate. Immersive header adapts: on wide screens, art left, tracks right. "See all" on artist pages per Feature 8.
  - **Verification Step:** Gallery images, navigation tests, and the album pages load from provider and local sources.

- **[ ] Task 6.9: Discover, search and history**
  - **Goal:** Search parity.
  - **Files to Create / Modify / Delete:** Modify `$D/NavigationSidebar.kt`, `$D/CatalogBrowserStage.kt`, `$D/ui/screens/SearchStage.kt`, `$D/DesktopSearchSuggestions.kt`. Create `$D/catalog/SearchHistoryStore.kt`. Port `searchWithBestSong` from `$A/ProviderCatalogRepository.kt`, and `$A/SearchHistory.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun searchAll(query: String): SearchResults`.
  - **Implementation Details:** Discover joins the sidebar. Results use rows and cards with actions, not text `DataAction`. Local plus provider results labelled. Ctrl+K focuses the title-bar field. Suggestions go with arrow keys.
  - **Verification Step:** Parser tests, the "best song first" table from Android, and keyboard tests.

- **[ ] Task 6.10: Podcasts and Radio**
  - **Goal:** Real tabs.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/PodcastsStage.kt`, `RadioStage.kt` (under `$D/ui/screens/`), `$D/DesktopProvider.kt`. Port `$A/RadioCatalog.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun radioStations(page)`, `suspend fun podcastShows(page)`.
  - **Implementation Details:** Follow Android's sources and ask before adding a new network destination. Episode progress and speed come from existing code. Podcast items needing an account say so until Phase 9.
  - **Verification Step:** Parser tests, a live probe, and an episode plays through `:desktop:episodeSpeedAcceptance`.

- **[ ] Task 6.11: Radio continuation**
  - **Goal:** Queue doesn't simply end.
  - **Files to Create / Modify / Delete:** Modify `$D/host/PlaybackController.kt`, `$D/DesktopHost.kt:773`, `$D/DesktopProvider.kt`. Port from `$A/QueueCoordinator.kt:78-89`.
  - **Key Classes, Interfaces & Signatures:** `fun radioSeed(entry: QueueEntry): String?`, `suspend fun DesktopProvider.radio(seed: String, continuation: String?): RadioPage`.
  - **Implementation Details:** Patches the queue with `PatchQueue`, so it never interrupts. Radio songs join at the end, as on Android. Local-only tracks have no seed, so it simply ends. Duplicate filter as on Android.
  - **Verification Step:** Unit tests with a fake provider, and a live run.

- **[ ] Task 6.12: Downloads screen**
  - **Goal:** Manage offline music.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/DownloadsStage.kt`. Modify `$D/DesktopDownloads.kt`, `$D/NavigationSidebar.kt`. Reference `$A/DownloadsScreen.kt`, `$A/OnDeviceScreen.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class DownloadRow(...)`.
  - **Implementation Details:** Existing engine already has resume, quota and Undo. Show real progress, and let the user pick the folder. Fix `isDownloaded` never passed true in rows.
  - **Verification Step:** `:desktop:downloadPlaybackAcceptance` passes, and the row state test.

- **[ ] Task 6.13: Encore parity**
  - **Goal:** Bring Encore up to Android's 5 November state.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopEncore.kt`, `$D/DesktopEncoreStories.kt`, `$DD/encore/EncoreRepository.kt`. Create `$D/ui/encore/EncoreArtistPhotos.kt` from `$A/EncoreArtistPhotos.kt`. Update `docs/privacy.md`.
  - **Key Classes, Interfaces & Signatures:** Same as Android's.
  - **Implementation Details:** Merge artists by name in top lists, add photos with the signed-out lookup, and use a top-song cover as fallback. Check the screen's privacy text stays true. Compare with Android's `EncoreDetail`, `EncoreRecap`, `EncoreWeekly`, `EncoreCharts`, and port what's missing, split per file if needed.
  - **Verification Step:** The ABBA duplicate test, `:desktop-data:encoreLoadAcceptance` stays within its target, and gallery images.

- **[ ] Task 6.14: Settings Bento**
  - **Goal:** Android's settings structure.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/SettingsStage.kt`, `$D/CanonicalEqualizerPanel.kt`. Port `$A/SettingsBento.kt`, `$A/SettingsSections.kt`, `$A/SettingsSound.kt`, `$A/AccessibilitySettings.kt`, `$A/DataSaverSettings.kt`, `$A/SleepTimer.kt`.
  - **Key Classes, Interfaces & Signatures:** `sealed interface SettingsSection`.
  - **Implementation Details:** Two-pane on wide windows: sections left, detail right. Each control changes real behaviour, or it isn't shown. Include the shortcuts list.
  - **Verification Step:** A test per setting that it reaches the host and persists.

- **[ ] Task 6.15: Playlist editing and undo notices**
  - **Goal:** Collection tools.
  - **Files to Create / Modify / Delete:** Create `$D/ui/UndoNotices.kt`. Modify `$D/LibraryTools.kt`, `$DD/SqliteLibraryRepository.kt`. Port `$A/PlaylistEditing.kt`, `$A/PlaylistSortRequest.kt`, `$A/UndoNotices.kt`, `$A/CollectionTools.kt`.
  - **Key Classes, Interfaces & Signatures:** `fun reorder(playlistId, from, to)`, `fun sort(playlistId, sort)`.
  - **Implementation Details:** Local playlists only. Account playlists wait for D8. Undo restores duplicates in their exact positions.
  - **Verification Step:** Store tests, including duplicate occurrences.

- **[ ] Task 6.16: Diagnostics and What's New**
  - **Goal:** Support tools.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/DiagnosticsStage.kt`, `$D/ui/WhatsNew.kt`. Port from `$A/DiagnosticsScreen.kt`, `$A/NerdStats.kt`, `$A/WhatsNew.kt`, `$A/PrivacyDataScreen.kt`.
  - **Key Classes, Interfaces & Signatures:** Reads `AudioCommand.QueryDiagnostics`.
  - **Implementation Details:** Show underruns, device, codec and blend reason. Log export strips personal paths. What's New reads `CHANGELOG.md` content bundled at build time.
  - **Verification Step:** Redaction test, and gallery image.

- **[ ] Task 6.17: Parity register**
  - **Goal:** Nothing missing goes unnoticed (needs D9).
  - **Files to Create / Modify / Delete:** Create `docs/desktop/parity-register.md`. Create `scripts/desktop/parity-register.py`.
  - **Key Classes, Interfaces & Signatures:** One row per file in `$A`: `ported | adapted | not applicable | owner exception | to do`.
  - **Implementation Details:** Start from the table below. The script fails if an Android file has no row, so new Android features are noticed. Dropped by the owner already: animated covers. Check each of the 33 features in the feasibility plan too.
  - **Verification Step:** The script passes with zero `to do` rows before Phase 11.

**Starting parity register**

| Android | Desktop plan |
|---|---|
| `Widgets`, `EncoreWidget`, `PlaybackTile`, `AppShortcuts`, `StatusBarTone`, `UiHaptics`, `StretchOverscroll`, `PredictiveBack`, `TabHitTest`, `ShellRail` phone parts | Not applicable (Jump List for shortcuts: optional, Task 11.2) |
| `CarBrowse`, `CarLibrary` (Android Auto) | Not applicable |
| `CastPicker`, `CastSupport`, `VoiceSearchController`, `NativeVideo`, `InlineYouTubeVideo`, `MusicVideoPairing`, `OfficialVideoPolicy` | D9 |
| `Solver*`, `RemoteCipherClient`, `CipherBridgeInstaller`, `AndroidStreamTokenProvider`, `Sabr*`, `OkHttp*`, `Chunked*` | Android's transport. Desktop's provider stack replaces them |
| `LocalBackup`, `LocalDataTransfer`, `EarlierDataScreen`, `LegacyStateMigration` | Task 9.7 and 9.8 |
| `LyricsShare`, `LyricsVersionCheck`, `LyricsTransition`, `LyricGapDots`, `LyricsFocus` | Phase 8 |
| `SilenceWatch`, `AudioOutputControls`, `AudioBoostSettings`, `OriginalAudioEngine` | Phase 7 |
| `AccountScreen`, `SignIn*`, `ProviderLibraryRepository`, `NotInterested` | Phase 9 |
| `UpdateNotice` | Task 11.5 |

## Phase 7: Audio features

*Done when:* Smart and Automix are selectable and audible, and the loopback gap is resolved or explained.

- **[ ] Task 7.1: Blend over IPC**
  - **Goal:** Make `configureBlend` reachable.
  - **Files to Create / Modify / Delete:** Modify `$D/AudioContract.kt`, `$D/AudioIpcTransport.kt`, `$D/DesktopAudioRuntime.kt`, `$D/AudioWorkerMain.kt`, `$T/PhaseOneProtocolSafetyTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class SetBlend(requestId, val mode: BlendMode, val durationMs: Int)`.
  - **Implementation Details:** `configureBlend` creates the analysis owner, which can be slow, so run it off the reader thread. Replaces `SetCrossfade`'s duration use. Protocol minor bump.
  - **Verification Step:** Round trip, and a worker-level test selects Automix and shows `blendMode` in diagnostics.

- **[ ] Task 7.2: Host state and hints**
  - **Goal:** Host owns the choice.
  - **Files to Create / Modify / Delete:** Modify `$D/host/SettingsStore.kt`, `$D/host/PlaybackController.kt`, `$D/DesktopHost.kt:367-376`.
  - **Key Classes, Interfaces & Signatures:** `DesktopSettings.blendMode`, `blendLengthMs`.
  - **Implementation Details:** Send on handshake and on change, without a reload. Vocal hints update for the current pair when lyrics arrive, by `PrepareSources`.
  - **Verification Step:** Unit test: changing mode sends one command and no `LoadQueue`.

- **[ ] Task 7.3: Sound settings UI**
  - **Goal:** The tile from Android.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/SettingsStage.kt`. Reference `$A/SettingsSound.kt:118-127`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun TransitionTile(mode, length, onMode, onLength)`.
  - **Implementation Details:** Same labels and ranges as Android. Remove the "final two seconds" text.
  - **Verification Step:** Gallery image, and a state test.

- **[ ] Task 7.4: When the display switches tracks**
  - **Goal:** Resolve the known gap: the engine presents the outgoing entry until its tail ends, while Android switches at the incoming start.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt:307-326`, `$D/DesktopHost.kt:280-294`, `$D/DesktopLibraryController.kt`, `$T/TransitionFactsContractTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `ClockAnchor.presentedEntryId`, `ClockAnchor.audibleEntries`.
  - **Implementation Details:** See section 4, Risk 2. Read the automix report first. History credit must not double count. UI and lyrics follow the presented entry, and session restore stays correct mid-blend.
  - **Verification Step:** Recorded test: the switch happens at the plan's incoming start within one anchor, and Encore has one play per song.

- **[ ] Task 7.5: Allocation-free render path**
  - **Goal:** Protect real-time audio during blends.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopDualDeckMixer.kt:327-449`, `$D/automix/DesktopTransitionRenderer.kt`.
  - **Key Classes, Interfaces & Signatures:** `Span` becomes a preallocated ring.
  - **Implementation Details:** Replace `spans.addLast(Span(...))` and per-block allocations. JFR on `:desktop:automixRecordedTest` finds the rest. Target: zero allocation per block in the mixer loop while mixing, except on a track change.
  - **Verification Step:** JFR allocation events on `RiffFloatMixer` near zero, and recorded tests unchanged.

- **[ ] Task 7.6: Loopback correlation**
  - **Goal:** Explain 0.41 and 0.30 against the 0.65 threshold.
  - **Files to Create / Modify / Delete:** Modify `$T/NativeAudioIntegrationTest.kt`, `desktop/build.gradle.kts:306`, `docs/desktop/phase-3-status.md`, and the renderer only if it's the cause.
  - **Key Classes, Interfaces & Signatures:** `fun alignedCorrelation(reference: FloatArray, captured: FloatArray, maxLagFrames: Int): CorrelationResult`.
  - **Implementation Details:** First establish which: the capture alignment, device processing such as enhancements, the test's reference signal, or the blend. Compare to a plain non-blend pass on the same device. Don't lower the threshold to pass. Remove the exclusion when it's truly green.
  - **Verification Step:** With the machine alone: the excluded test passes, or the status doc records the measured cause and the owner decides.

- **[ ] Task 7.7: Automix acceptance**
  - **Goal:** Real songs, real transitions.
  - **Files to Create / Modify / Delete:** Modify `docs/desktop/phase-3-status.md`. Use `$D/automix/DesktopAutomixAnalysisAcceptance.kt`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Compare plans for the same pairs with Android's output (`$T/androidreference`). Report anything that doesn't match, and fix or justify it.
  - **Verification Step:** `:desktop:automixRecordedTest` passes. **Owner listening check** on chosen pairs.

- **[ ] Task 7.8: Skip end silence**
  - **Goal:** Feature 9.
  - **Files to Create / Modify / Delete:** Create `$D/audio/SilenceWatch.kt` from `$A/SilenceWatch.kt`. Modify `$D/DesktopDualDeckMixer.kt`, `$D/ui/screens/SettingsStage.kt`.
  - **Key Classes, Interfaces & Signatures:** Follow Android's.
  - **Implementation Details:** Read Feature 9's guards. Works on post-decode frames in the mixer thread with no allocation. Don't skip for classical or podcasts.
  - **Verification Step:** PCM fixtures from the existing corpus, and Android's cases ported.

- **[ ] Task 7.9: Output device choice**
  - **Goal:** Pick a device.
  - **Files to Create / Modify / Delete:** Modify `native/riff-audio/riff_audio.cpp`, `$D/NativeAudio.kt`, `$D/AudioContract.kt`, `$D/ui/screens/SettingsStage.kt`. Reference `$A/AudioOutputControls.kt`.
  - **Key Classes, Interfaces & Signatures:** `data class SetOutputDevice(requestId, val endpointId: String?)`, `fun listEndpoints(): List<AudioEndpoint>`.
  - **Implementation Details:** Null means follow the Windows default. Use the existing device-lost recovery. Exclusive mode stays disabled, as the earlier report requires.
  - **Verification Step:** Native test lists endpoints. **Owner check:** switch devices during playback.

- **[ ] Task 7.10: Remaining sound settings**
  - **Goal:** Finish parity.
  - **Files to Create / Modify / Delete:** Modify `$D/ui/screens/SettingsStage.kt`, `$D/DesktopStreamResolver.kt`, `$D/WindowsNetworkCost.kt`. Reference `$A/AudioBoostSettings.kt`, `$A/DataSaverSettings.kt`, `$A/OriginalAudioPolicy.kt`, `$A/MusicCachePolicy.kt`.
  - **Key Classes, Interfaces & Signatures:** `DesktopSettings.streamQuality`, `cacheSizeBytes`.
  - **Implementation Details:** Quality and metered behaviour use real network cost. Decide "original audio" by reading the policy, and record it in the register.
  - **Verification Step:** Unit tests, with quality reflected in `diagnostics`.

## Phase 8: Lyrics

*Done when:* sources and accuracy rules match Android, follow behaves properly, and the panel matches reference image R04.

- **[ ] Task 8.1: Fix follow, reload and frame loop**
  - **Goal:** Clear the known bugs first.
  - **Files to Create / Modify / Delete:** Modify `$D/InspectorPane.kt:572,574,586,610,630`, `$D/DesktopHost.kt:690`, `$D/LyricsPresentationClock.kt`. Create `$T/ui/LyricsFollowTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `class LyricsFollowState { val following: Boolean; fun onUserScroll(); fun onLineTap(); fun onResume() }`.
  - **Implementation Details:** Only a real scroll breaks follow, and it returns after Android's idle time. Tap seeks and keeps follow. Opening the view uses the cached state, and only an explicit retry forces a reload. The frame loop runs only when visible and playing. Centre the active line. Add `lyricDisplayLeadMs`.
  - **Verification Step:** Offscreen tests for each case, with `withFrameNanos` counted while paused.

- **[ ] Task 8.2: Source ladder**
  - **Goal:** Same sources as Android (needs D4).
  - **Files to Create / Modify / Delete:** Create `$D/lyrics/LyricsSourceLadder.kt`. Modify `$D/DesktopLyricsLookup.kt:26-66`, `$D/SyncedLyricsRepository.kt`, `docs/privacy.md`, `NOTICE.md`. Reference `$A/LyricsRepository.kt:123-246,432`, `$A/AdditionalLyricsProviders.kt`, `$A/YouTubeLyricsRequest.kt`.
  - **Key Classes, Interfaces & Signatures:** `interface LyricsSource { val id: String; val tier: Int; val checked: Boolean; suspend fun find(q: LyricsQuery): LyricsCandidate? }`, `fun LyricsLadder.observe(q: LyricsQuery): Flow<LyricsLoadState>`.
  - **Implementation Details:** Tiers with timeouts in parallel, as on Android. Show the first acceptable answer, and upgrade if a finer one arrives. Allowlist each host. Add only approved destinations. Use Ktor CIO.
  - **Verification Step:** Fake-source timing tests from Android. A live check on a small song list.

- **[ ] Task 8.3: Accuracy rules**
  - **Goal:** Today's Android fixes.
  - **Files to Create / Modify / Delete:** Modify `$D/LyricModels.kt`, `$D/lyrics/LyricsSourceLadder.kt`, `$D/LyricsKaraoke.kt`. Reference `$A/LyricsModels.kt`, `$A/LyricsVersionCheck.kt`.
  - **Key Classes, Interfaces & Signatures:** `lyricsIsTitleLine`, the 70% word-share rule from `docs/quality/owner-bug-batch-2026-11-05.md`.
  - **Implementation Details:** Applies only to titles naming a guest. Unchecked sources lose to checked ones if they disagree. Saved copies lose title lines on read. Recheck a saved unchecked answer once in 12 hours.
  - **Verification Step:** Android's tests ported, including "Ribs" and the remix case.

- **[ ] Task 8.4: Lyrics panel rendering**
  - **Goal:** Match Android's look.
  - **Files to Create / Modify / Delete:** Create `$D/ui/lyrics/LyricsPanel.kt`. Modify `$D/InspectorPane.kt`. Port `$A/LyricsPanel.kt:158-184`, `$A/LyricsFocus.kt`, `$A/LyricGapDots.kt`, `$A/LyricsTransition.kt`, `$A/PlayerLyricLine.kt`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun LyricsPanel(document, clock, follow, onSeek, surface: LyricsSurface)`.
  - **Implementation Details:** Blur on non-active lines with quality fallback. Uses the 22 unused `RiffLyrics` tokens. Word-level karaoke draws in the draw phase, so ticks don't recompose. `LyricsSurface` covers inspector, player and mini.
  - **Verification Step:** Gallery images, and a recomposition test: 10 s of karaoke recomposes only on line change.

- **[ ] Task 8.5: Timing tools**
  - **Goal:** Offset and version controls.
  - **Files to Create / Modify / Delete:** Create `$D/ui/lyrics/LyricsTiming.kt`. Modify `$DD/LyricsCacheRepository.kt`, `$D/InspectorPane.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun setOffset(documentId: String, offsetMs: Long)`.
  - **Implementation Details:** A proper dialog instead of text buttons, showing sync level and source, with "finding finer timing" state. Alt+[ and Alt+] nudge by 0.1 s.
  - **Verification Step:** Offset persists and shifts the active line by the exact amount in a test.

- **[ ] Task 8.6: Lyrics share**
  - **Goal:** Share as an image.
  - **Files to Create / Modify / Delete:** Create `$D/ui/lyrics/LyricsShare.kt` from `$A/LyricsShare.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun renderShareImage(lines, track, style): File`.
  - **Implementation Details:** Rendered with `ImageComposeScene`. Actions: copy to clipboard and save as. There's no Android share sheet.
  - **Verification Step:** Offscreen render and clipboard test.

- **[ ] Task 8.7: Romanisation and full-stage lyrics**
  - **Goal:** Finish.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopLyricsRomanize.kt`, `$D/ui/screens/NowPlayingStage.kt`, `$D/MiniPlayer.kt`. Reference `$A/LyricsRomanize.kt`.
  - **Key Classes, Interfaces & Signatures:** Existing `LyricsRomanMode`.
  - **Implementation Details:** Compare results with Android's on shared lines, since icu4j may differ. Lyrics beside art on wide windows.
  - **Verification Step:** Android's cases pass, with gallery images at each size.

## Phase 9: Account and data

*Done when:* the owner can see their library on desktop, and move their data from Android. Each task that touches the account asks first.

- **[ ] Task 9.1: Sign-in spike**
  - **Goal:** Find a sign-in route that works without an embedded Android WebView (needs D7).
  - **Files to Create / Modify / Delete:** Create `docs/desktop/adr-003-account-session.md`. Read `$A/SignInFlow.kt`, `$A/ProviderSessionManager.kt`, `$A/CookieRotation.kt`, `$D/DesktopAuthenticationCoordinator.kt`.
  - **Key Classes, Interfaces & Signatures:** None; a decision record.
  - **Implementation Details:** Compare: (a) system browser plus loopback OAuth for what the official API allows; (b) a Windows WebView2 sign-in window, using the same cookie session as Android; (c) moving an existing session from the phone by a user-run export. State for each: what it can reach, the risk to the account, and terms concerns. No real sign-in runs in this task, and no passwords are entered, ever.
  - **Verification Step:** The owner reads the ADR and picks a route.

- **[ ] Task 9.2: Session store**
  - **Goal:** Hold the chosen session safely.
  - **Files to Create / Modify / Delete:** Modify `$D/AccountSessionCoordinator.kt`, `$D/DesktopAuthenticationCoordinator.kt`. Create `$T/AccountSessionTest.kt`.
  - **Key Classes, Interfaces & Signatures:** `sealed interface AccountState { SignedOut; Restoring; SignedIn(val profile: AccountProfile); Expired }`.
  - **Implementation Details:** Reuse the DPAPI vault. Tokens never reach logs, the worker's command line, or JSON receipts. Scoped data switches at sign-in and sign-out, with stale results dropped by token check, which exists today.
  - **Verification Step:** Unit tests with fake tokens, and a log-scan test for token patterns.

- **[ ] Task 9.3: Sign-in UI**
  - **Goal:** The flow the owner chose.
  - **Files to Create / Modify / Delete:** Create `$D/ui/screens/SignInStage.kt`, `$D/ui/screens/AccountStage.kt`. Port `$A/SignInDesign.kt`, `$A/SignInScreens.kt`, `$A/AccountScreen.kt`. Modify `$D/LibraryTools.kt:101`, `$D/DesktopHost.kt:673`.
  - **Key Classes, Interfaces & Signatures:** `@Composable fun SignInStage(state: AccountState, ...)`. Uses the 37 unused `RiffSignIn` tokens.
  - **Implementation Details:** Depends on Task 9.1. The owner signs in; Claude doesn't type credentials.
  - **Verification Step:** Offscreen gallery. **Owner check:** the owner signs in.

- **[ ] Task 9.4: Read-only library**
  - **Goal:** See the account's music.
  - **Files to Create / Modify / Delete:** Create `$D/catalog/ProviderLibrary.kt` from `$A/ProviderLibraryRepository.kt`. Modify `$D/DesktopProvider.kt`, `$D/DesktopLibraryController.kt`, `$D/ui/screens/LibraryStage.kt`, `$D/ui/screens/HomeStage.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun playlists()`, `albums()`, `artists()`, `likedSongs()`, all paged.
  - **Implementation Details:** Read-only. Cache in the account's scope. Signed-out use still works. Podcasts and Home upgrade to signed-in responses.
  - **Verification Step:** Parser tests. **Owner check:** library matches the phone.

- **[ ] Task 9.5: Account writes**
  - **Goal:** Likes and playlists sync (needs D8).
  - **Files to Create / Modify / Delete:** Modify `$D/catalog/ProviderLibrary.kt`, `$D/host/LibraryCommands.kt`, `$D/LibraryTools.kt`. Reference `$A/NotInterested.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun setLiked(id: String, liked: Boolean): WriteResult`.
  - **Implementation Details:** Don't run against the owner's account without an explicit go for that specific write. Show optimistic state, and roll back on failure with a notice. Keep local Like for signed-out use. Search history stays off the account unless the user searched there.
  - **Verification Step:** Fake-provider tests. A single owner-approved like and unlike.

- **[ ] Task 9.6: Stream access when signed in**
  - **Goal:** Account-only content plays.
  - **Files to Create / Modify / Delete:** Modify `$D/DesktopStreamResolver.kt`, `$D/AudioWorkerMain.kt`, `$D/DurableAudioSource.kt:29-33`.
  - **Key Classes, Interfaces & Signatures:** `DurableAudioSource.Provider.scope` allows account scopes; `sourceFromJson` rejects anything but `anonymous` today.
  - **Implementation Details:** Pass the session to the worker over the private pipe, not by arguments. Expiry triggers a controlled refresh. If this can't work from a worker, say so before changing the process design.
  - **Verification Step:** Restart recovery with an account source. **Owner check.**

- **[ ] Task 9.7: Import Android backup**
  - **Goal:** Bring data over from the phone.
  - **Files to Create / Modify / Delete:** Create `$DD/AndroidBackupImporter.kt`, `desktop-data/src/test/kotlin/com/musicglass/data/AndroidBackupImporterTest.kt`, `$D/ui/screens/DataTransferStage.kt`. Reference `$A/LocalBackup.kt`, `$A/LocalDataTransfer.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun preview(file: File): ImportPreview`, `suspend fun import(preview: ImportPreview, onProgress: (Float) -> Unit): ImportResult`.
  - **Implementation Details:** Strict bounded parsing. Temp database, integrity check, then swap, with a backup kept. Encore history, playlists, likes, settings. Don't bring account tokens. Keep Encore IDs compatible, and map scopes explicitly.
  - **Verification Step:** Backup produced by the Android debug build on the owner's phone imports with matching counts. A corrupted file changes nothing.

- **[ ] Task 9.8: Export**
  - **Goal:** Data can leave and go back.
  - **Files to Create / Modify / Delete:** Create `$DD/DesktopBackupExporter.kt`. Modify `$D/ui/screens/DataTransferStage.kt`.
  - **Key Classes, Interfaces & Signatures:** `suspend fun export(destination: File): ExportResult`.
  - **Implementation Details:** Writes the same format as Android, if the format allows, so it can be restored there. If not, record that limit.
  - **Verification Step:** Round trip on desktop. Restored on an Android debug build, if the owner agrees.

## Phase 10: Stop the drift

*Done when:* shared behaviour comes from one source, and old files are in their target packages.

- **[ ] Task 10.1: Source drift report**
  - **Goal:** See hand-ported code that has changed.
  - **Files to Create / Modify / Delete:** Create `scripts/desktop/drift-report.py`, `docs/desktop/ported-files.json`.
  - **Key Classes, Interfaces & Signatures:** A map of desktop file to Android file with the commit it was ported from.
  - **Implementation Details:** Add it to the desktop lane as a warning. Doesn't change code.
  - **Verification Step:** Reports the current drift, and a changed Android file appears.

- **[ ] Task 10.2: Create `:riff-core`**
  - **Goal:** A pure Kotlin module both apps can use (needs D10).
  - **Files to Create / Modify / Delete:** Create `riff-core/build.gradle.kts`, `riff-core/src/main/kotlin/com/musicglass/core/QueueOrder.kt`. Modify `settings.gradle.kts`, `desktop/build.gradle.kts`, `app/build.gradle.kts`.
  - **Key Classes, Interfaces & Signatures:** `plugins { kotlin("jvm") }`, with no Android or Compose. This avoids KMP because nothing shared needs Android types.
  - **Implementation Details:** Start with only `QueueOrder`, proving it through Android's build. `:app`'s behaviour must not change. Check Android lint and R8 still pass.
  - **Verification Step:** **PR lane** and **Unit** both pass, with the APK behaving the same in `:app` unit tests.

- **[ ] Task 10.3: Move lyric logic**
  - **Goal:** One parser and rule set.
  - **Files to Create / Modify / Delete:** Move `$A/LyricsModels.kt` and `$D/LyricModels.kt` content to `riff-core/src/main/kotlin/com/musicglass/core/lyrics/`, with non-pure parts staying behind.
  - **Key Classes, Interfaces & Signatures:** Same as today's, in the new package.
  - **Implementation Details:** Only pure parts. The small differences between 0.98-similar files are real decisions, so review each one, rather than picking a side blindly.
  - **Verification Step:** Both apps' lyric tests pass with no change to expected results.

- **[ ] Task 10.4: Move transition and EQ maths**
  - **Goal:** One audio plan.
  - **Files to Create / Modify / Delete:** Move `$D/automix/SmartTransition.kt`, `$D/automix/BeatTracker.kt`, `$D/automix/BlendPolicy.kt`, `$D/CanonicalEqualizerCurve.kt`, and Android's counterparts (`$A/SmartTransition.kt`, `$A/BeatTracker.kt`, `$A/EqualizerCurve.kt`) to `riff-core`.
  - **Key Classes, Interfaces & Signatures:** Same signatures.
  - **Implementation Details:** Verify allocation behaviour doesn't change on the mixer path.
  - **Verification Step:** Android's and desktop's reference tests pass. Equalizer acceptance within 1.5e-5.

- **[ ] Task 10.5: Move Encore rollup rules**
  - **Goal:** The same statistics on both.
  - **Files to Create / Modify / Delete:** Move pure parts of `$DD/EncoreRollup.kt`, `$A/EncoreRepository.kt` to `riff-core`.
  - **Key Classes, Interfaces & Signatures:** Period, ranking and merge functions.
  - **Implementation Details:** The repositories are only 0.65 similar, so plan this one by reading both. If it's too big for a turn, stop after the ranking rules.
  - **Verification Step:** One fixture gives identical top lists from both apps' tests.

- **[ ] Task 10.6: Move files to target packages**
  - **Goal:** Reach the tree in section 1.1.
  - **Files to Create / Modify / Delete:** Move every file marked "moved" in section 1.1. Modify `desktop/build.gradle.kts` for main class names.
  - **Key Classes, Interfaces & Signatures:** `mainClass = "com.musicglass.desktop.app.MainKt"`, and the worker's new name in the packaged `RiffAudioWorker.cfg` step.
  - **Implementation Details:** Mechanical only, in one commit per package, using `git mv`. Update packaged-launcher strings, ProGuard or SBOM references, and scripts that name classes.
  - **Verification Step:** **Unit**, **Native** and `verify-phase1-packaged.py` all pass.

## Phase 11: Release

*Done when:* the owner has what they need to publish. Claude doesn't publish. Details are in the 2 November report's Phase 5.

- **[ ] Task 11.1: Identity**
  - **Goal:** Final name, version and paths (needs D11).
  - **Files to Create / Modify / Delete:** Modify `desktop/build.gradle.kts:41,62,148-161`, `$D/DesktopSessionRepository.kt:10-11`, `$D/DesktopBuildIdentity.kt`, `scripts/desktop/verify-phase1-packaged.py`, `scripts/desktop/audit-phase1-package.py`.
  - **Key Classes, Interfaces & Signatures:** `riffDesktopVersion`.
  - **Implementation Details:** Keep `upgradeUuid`. Move data roots to `%LOCALAPPDATA%\Riff\<channel>\` and migrate existing data without deleting anything unrelated. Add a real `.ico`.
  - **Verification Step:** Packaged image runs with the new identity, and an old data directory migrates in a test.

- **[ ] Task 11.2: Windows integration at install**
  - **Goal:** File opening and shortcuts.
  - **Files to Create / Modify / Delete:** Modify `desktop/build.gradle.kts`, `$D/os/SingleInstance.kt`, `scripts/desktop/`.
  - **Key Classes, Interfaces & Signatures:** ProgID per the report's proposal, confirmed at Task 11.1.
  - **Implementation Details:** Register only accepted codecs, and never take over defaults silently. Optional Jump List. Check `.exe` and `.msi`.
  - **Verification Step:** On a clean VM, "Open with Riff" reaches the running instance.

- **[ ] Task 11.3: Native provenance and SBOM**
  - **Goal:** P5-01.
  - **Files to Create / Modify / Delete:** Modify `native/riff-audio/dependencies.json`, `docs/desktop/sbom.md`, `NOTICE.md`, `licenses/`, `scripts/desktop/audit-phase1-package.py`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Include `riff_windows.dll`. Check the runtime needs of each DLL on a machine with no developer tools. LGPL obligations for FFmpeg and SoundTouch stay met.
  - **Verification Step:** Audit script passes on the packaged image.

- **[ ] Task 11.4: Update transport**
  - **Goal:** P5-02 and P5-03.
  - **Files to Create / Modify / Delete:** Create `$D/update/UpdateHttpCollector.kt`, `$D/update/BoundedArtifactDownloader.kt`, `$D/update/VerifiedStagingStore.kt`, `$D/update/WindowsAuthenticodeVerifier.kt`. Modify `$D/DesktopUpdateClient.kt`, `$D/UpdateEligibilityPolicy.kt`.
  - **Key Classes, Interfaces & Signatures:** As specified in the report.
  - **Implementation Details:** Use a test server and test keys only. The existing offline verifier stays authoritative. Split across turns: collector, then downloader, then staging, then Authenticode.
  - **Verification Step:** Tests for redirects, size limits, bad hash, cancel, disk full.

- **[ ] Task 11.5: Install helper and rollback**
  - **Goal:** P5-04 and P5-05.
  - **Files to Create / Modify / Delete:** Create the helper under `desktop/src/updateHelper/`, `$D/update/CompatibleDataGenerationStore.kt`, `$D/ui/UpdateNotice.kt` (from `$A/UpdateNotice.kt`).
  - **Key Classes, Interfaces & Signatures:** As specified in the report, including Appendix A's activation fence and rollback receipt.
  - **Implementation Details:** This is several turns, and it must split at the report's numbered steps. Test interrupted install, reboot and candidate crash on a VM.
  - **Verification Step:** Test-key update and rollback on a clean VM. Updating stays disabled by default until Task 11.9.

- **[ ] Task 11.6: Accessibility pass**
  - **Goal:** Report P4-07.
  - **Files to Create / Modify / Delete:** Modify UI files as findings require. Modify `docs/desktop/phase-3-night-acceptance.md` or its successor.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Automated semantics checks first, porting `AccessibilitySemanticsTest`. Keyboard-only journey, contrast, reduced motion, then NVDA.
  - **Verification Step:** Offscreen semantics tests. **Owner screen check:** full NVDA journey.

- **[ ] Task 11.7: Screen acceptance**
  - **Goal:** Clear UI-01 to UI-17, once the owner lifts the hold.
  - **Files to Create / Modify / Delete:** Modify `docs/desktop/phase-3-night-acceptance.md`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Includes the reference images in `references/manifest.md` and matched Android captures. Physical displays and mixed DPI stay HELD if the equipment isn't available, never waived by inference.
  - **Verification Step:** Each item has evidence or a recorded hold.

- **[ ] Task 11.8: Final soak**
  - **Goal:** Eight hours on the final protocol.
  - **Files to Create / Modify / Delete:** Modify `docs/desktop/phase-3-status.md`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Run after the last audio change. Use the fixed status from Task 1.6, and disable sleep and Windows Update restarts during the run if the owner agrees, because the last run stopped around an update event. Any later audio change requires a re-run. Include queue edits and blends.
  - **Verification Step:** `PASS`, with 0 unexplained underruns and flat heap and handle trends. The earlier pass was 28,862 s on protocol 2.5.

- **[ ] Task 11.9: Release candidate**
  - **Goal:** Everything the owner needs to publish.
  - **Files to Create / Modify / Delete:** Create `docs/desktop/release-checklist.md`. Modify `README.md`, `CHANGELOG.md`, `docs/desktop/capability-matrix.md`, `docs/privacy.md`.
  - **Key Classes, Interfaces & Signatures:** None.
  - **Implementation Details:** Signing, hosts and keys are the owner's (D11). Clean VM install as a standard user, non-ASCII profile, offline first run, uninstall with a data choice.
  - **Verification Step:** Parity register has no `to do`, every lane passes, and the checklist is complete. The owner decides whether to publish.

---

# 4. Critical Risk Mitigation

These three could cause a dead end. All approaches are proposals until their tasks measure them.

## Risk 1: Changing the queue around live audio (Tasks 2.5 to 2.8)

**Why it's hard.** Three threads touch queue state: host patches, the mixer loop, and deck opening. The mixer assumes `nextIndex()` is what `deckB` holds, and that assumption becomes false the moment the queue can change.

**Approach: identity, single owner, no blocking.**

```text
State owned only by the mixer thread:
  window: QueueWindow        deckA, deckAEntry        deckB, deckBEntry
  nextOpening: String?       pendingPatch: Patch?     blendStarted

patchQueue(rev, ids, current), run inside control {}:
  if rev <= window.revision: return                       // stale
  if blendStarted:
      if deckBEntry not in ids: abortBlend()              // ramp over 240 frames
      else: pendingPatch = Patch(rev, ids, current); return
  window = window.applied(ids, current, rev)
  want = window.next(repeat)
  if want == deckBEntry: return                           // common case, nothing audible changes
  invalidateBlend(); close deckB; deckB = null; deckBEntry = null
  if want != null: requestNextDeck(want)

requestNextDeck(id), off the mixer thread:
  nextOpening = id; open and prime the decoder
  commands.offer { if nextOpening == id: install, analysePair(); else close it }

End of primary track:
  if deckB != null: hand off; index comes from deckBEntry; apply pendingPatch
  else if nextOpening != null: enter buffering, no stop
  else: finish draining and stop                          // genuine end of queue
```

**Why it works.** Every change runs on one thread between blocks, so there's no lock in the audio path. Output is never reset, and the epoch doesn't change, so the clock stays continuous. `queueId`s are unique, so duplicate songs are safe. Opening a deck can't stall audio.

**Early warning and fallback.** Task 2.1's tests come first, and Task 2.5's race test runs a slow decoder through 1,000 patches. If the mixer can't support this, the fallback is to keep `LoadQueue` while keeping the current deck alive across it: a smaller change that still removes the audible gap.

## Risk 2: Automix transitions and handoff on the JVM (Tasks 7.4 to 7.7)

**Why it's hard.** There are three separate problems. GC pauses can threaten real-time audio. The display must switch at the incoming start, while the engine holds the outgoing entry until its tail ends. And real loopback correlation currently fails at 0.41 and 0.30 against 0.65.

**Approach.**

1. **Keep what already protects real time.** A separate worker JVM means UI garbage can't pause audio. Decoders feed SPSC rings, so the mixer never waits on IO. The plan's 350 ms of output headroom absorbs small pauses. Task 7.5 removes per-block allocation, confirmed by JFR.
2. **Plan once, then never change it.** Analysis runs below normal priority on a separate leased file, and its plan becomes immutable once the blend starts. If analysis isn't ready at the boundary, the fallback timing already in `selectedBlend` runs. Blends never wait for the model.
3. **Separate "audible" from "presented".** Each `Span` already records primary and secondary entries per block, so handoff can be decided by frames:

   ```text
   presentedFrame = output.presentedFrames()
   presentedEntry = if span.secondaryId != null and presentedFrame >= plan.presentAtFrame
                    then span.secondaryId else span.id
   ```

   `plan.presentAtFrame` is the mixer frame of the incoming start. Because this reads hardware-presented frames, the UI changes when the listener hears it, not when it was mixed. Encore credit follows the presented entry and is counted once per occurrence and cycle. Session restore saves both positions, which already works.
4. **Diagnose loopback before touching the renderer.** Compare a plain single-deck pass with the failing case on the same device, with lag search and any Windows audio enhancements noted. If plain playback also scores low, the test or device is the cause and not the blend. The threshold stays where it is either way.

**Fallback.** If Automix fails acceptance on real pairs, ship Crossfade and Smart only, with Automix hidden and the reason recorded for the owner. Plain crossfade is already working and must never regress.

## Risk 3: Frame-accurate lyrics without burning frames (Tasks 3.1, 3.2, 8.1, 8.4)

**Why it's hard.** Anchors arrive at 10 Hz over IPC with jitter, while karaoke needs to look smooth at 60 to 120 Hz. Doing this badly recomposes the app each frame, as it effectively does today at 10 Hz.

**Approach: interpolate on the UI side, snap only when needed.**

```text
On each anchor (hardware-derived):
  target(t) = anchor.position + (t - anchor.nanos) * rate - outputLatency
On each frame, only while the lyrics are visible and playing:
  shown = shown + (now - last) * rate
  error = target(now) - shown
  if |error| > 250 ms or epoch changed: shown = target(now)      // seek, device change
  else: shown += clamp(error * 0.1, ±2 ms per frame)             // gentle slew, never jumps back
lineIndex = binary search of line starts at shown + displayLead + userOffset
```

- `LyricsPresentationClock` already does the anchor part with the 250 ms clamp. This adds slew and lead.
- **Recomposition happens only when `lineIndex` changes** (`derivedStateOf`). Word progress is read in the draw phase, so each frame costs drawing and not composition.
- **Scroll is animated toward the centred line once per line change,** not driven per frame.
- **Blur uses a cached layer per line,** since recalculating per frame is expensive. Lower glass qualities skip it.
- **Pause or hidden means no frame loop.** Resuming restarts from the anchor.
- **A rate change resets from the swap anchor,** because Task 2.12 gives it the new rate at that frame.

**How we'll know.** Offscreen, a recorded anchor stream with injected jitter must keep `shown` within the stated error and never move backwards except on a seek. The real offset against calibrated hardware markers is a later measurement, because nothing can be claimed about perceived sync without that.

**Also watch, outside the three above: account sign-in.** Android relies on a WebView session, which desktop lacks, and this is a product and policy question more than an algorithmic one. Task 9.1 addresses it as a spike so the rest of the app doesn't depend on its answer.

---

# 5. Kickoff Protocol

**We start with Task 1.1: commit the desktop tree to its own branch.**

**Files I need from you:** none. Everything is on this disk.

**What your "Go" authorises:** creating branch `desktop/foundation` and committing only the desktop paths listed in Task 1.1, leaving Android's uncommitted bug-batch changes as they are. It doesn't authorise a push.

**I'm waiting for your "Go" before writing any code or making that commit.**
