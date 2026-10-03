# Riff improvement plan: rc17 to rc19

Drafted 2026-10-03 against **Riff 0.9.0-rc16** (versionCode 17, commit `06aae55` plus four uncommitted UI files). Status: **plan only. No code has been changed.**

This plan merges two reviews of the project into one ordered piece of work:

- **Review A** (Claude, 2026-10-03): signing-key backup, no remote, playback crash isolation, main-thread stalls, APK size, the untested attestation-recovery path, sleep timer, unrun UI scenarios, sign-in fragility, housekeeping.
- **Review B** (second reviewer, 2026-10-03): playback reliability, UI stalls, *a Home feed that shows instantly from disk with skeleton cards*, APK size with post-shrink verification, *session validation with a "Sign in again" state*, sleep-timer persistence, *automated UI regression checks*.

It also takes evidence from [the overnight stress test](stress-test-2026-10-03.md) and builds on [the 2026-10-02 remediation plan](remediation-plan-2026-10-02.md). Section 11 shows how the two plans line up, so that no item is planned twice.

Every step lists the files it touches, how to do it, how to test it and what "done" means. Code facts were checked directly before writing. Line numbers refer to the working tree on 2026-10-03 and will drift as work lands, so treat them as starting points.

---

## 0. Overview

| # | Workstream | Source | Severity | Effort | Ships in |
|---|---|---|---|---|---|
| W0 | Safety net: key backup, remote, working-tree cleanup | A | **Critical** (only unrecoverable loss) | 2–3 h | before any code |
| W1 | Playback reliability: isolate the QuickJS crash, prove attestation recovery, instrument service stops | A + B | **High** | 4–6 days | rc17 |
| W2 | Main-thread stalls: process-scoped lyrics, off-thread preferences and artwork | A + B | High (≈490 ms jank) | 1–1.5 days | rc17 |
| W3 | Instant Home: on-disk feed snapshot and skeleton cards | B | Medium (perceived speed) | 2–2.5 days | rc18 |
| W4 | APK size: R8, resource shrinking, arm64-only | A + B | Medium | 1.5–2 days | rc18 |
| W5 | Session health: validation, expiry state, "Sign in again" | A + B | Medium | 2–3 days | rc18 |
| W6 | Sleep timer survives process death | A + B | Low | 0.5–1 day | rc19 |
| W7 | Automated UI regression checks and CI | A + B | Medium (prevents regressions) | 3–4 days | rc19 (started in rc17) |
| W8 | Build hygiene: retired module, Unix wrapper, changelog, formatting | A | Low | 1 day | alongside rc18 |

**Total:** about 17–23 working days for one person.

**Order:** W0, then W1 and W2 in parallel, then W7.1–W7.3 (so later work has a safety net), then W3/W4/W5, then W6 and the rest of W7. W8 can fill gaps at any point after W0.

**Releases:**
- **rc17 (reliability):** W1, W2, W7.1–W7.3.
- **rc18 (speed and size):** W3, W4, W5, W8.
- **rc19 (polish):** W6, W7.4–W7.6.

### Decisions needed before starting

These change what gets built. My recommendation is listed first.

1. **QuickJS isolation route (W1.2):** (a) fork innertubex and add a public solver seam, used through a composite build *(recommended; the existing remediation plan's P8.2 already recommends a fork)*; or (b) run the whole stream-resolution path in a second process *(larger, and it fights WebView cookie-store rules; see W1.2)*.
2. **Release CPU types (W4):** arm64-v8a only *(recommended; the phone is arm64; saves ≈52 MB of native code)*, or keep armeabi-v7a too (+≈13 MB).
3. **Remote (W0.2):** a private GitHub repository *(recommended; W7.6 CI depends on it)*, or another host.
4. **Home snapshot privacy (W3):** keep the on-disk Home snapshot per account and delete it on sign-out *(recommended)*, or keep it only while signed out.

### Rules for every workstream

- **One branch per workstream** (`fix/w1-playback-isolation` and so on), merged into `main` when its definition of done passes. `main` stays releasable.
- **Commit each fix separately**, with an imperative subject line and the reason in the body (as in the stress-test commits).
- **No behaviour change hides inside a refactor.** W4 and W8 must not change behaviour. If one is needed, it gets its own commit.
- **Concurrent agent:** another agent works in this repository (the `islandSpoof` build type and native-island instrumentation). Don't modify its files. Rebase rather than overwrite.
- **Definition of done, checked for every workstream:**
  1. `:app:testDebugUnitTest`, `:app:lintRelease` and `:app:assembleRelease` pass.
  2. The release APK installs **over the previous release** with `adb install -r`. Session, downloads, settings and the paused queue survive.
  3. The smoke checklist in section 10 passes on the phone.
  4. This plan's status line and the per-workstream checkbox in section 12 are updated.

---

## W0 — Safety net (do first)

### W0.1 Back up the signing key
**Why:** `.toolchain/signing/` holds the only copy of `musicglass-release.p12`, next to a plaintext `password.txt`. If it is lost, every installed copy of Riff must be uninstalled (losing downloads and saves) before any new build will install. `scripts/build-release-candidate.ps1` will also **generate a new key silently** if both files are missing.

**Steps:** follow phase P0 of the remediation plan exactly (P0.1 record fingerprint → P0.2 two off-machine backups and a restore drill → P0.3 DPAPI-encrypted password → P0.4 harden the script). In summary:
1. `apksigner verify --print-certs artifacts/release/riff-0.9.0-rc16.apk`. Record the SHA-256 certificate digest in a new `docs/release-signing.md`. The stress report records the certificate as `ac70…f6e1`.
2. Make a password-manager entry (attach the `.p12`, put the password in the password field), plus an AES-256 7-Zip archive on removable media with a *different* passphrase.
3. **Restore drill:** extract the archive to a temporary folder, run `keytool -list -v`, compare the digest, then delete the temporary copy.
4. Change the script so it throws `Signing key missing. Restore from backup (see docs/release-signing.md).` instead of generating a key, and so it fails the build if the certificate digest differs.

**Done when:** the restore drill passes; temporarily renaming `.toolchain/signing` makes the script fail rather than create a key.

### W0.2 Push to a private remote
1. Create a private repository. Add it as `origin`, then push `main`, `master` and the tags.
2. Make sure `.gitignore` excludes `.toolchain/`, `artifacts/`, `build/` and `*.p12` **before** the first push. Check with `git ls-files | grep -E "p12|password|artifacts/|\.toolchain"`, which must print nothing.
3. Reconcile the branches: the working branch is `master` and the configured main branch is `main`. Pick one (`main`) and delete or alias the other.

### W0.3 Clean the working tree
1. Commit or stash the four uncommitted UI files (`CatalogScreens.kt`, `GlassCategoryChip.kt`, `NowPlaying.kt`, `RiffApp.kt`). Do this first so W2 starts from a clean base.
2. After the user confirms the app works, delete `artifacts/stress/app-data-backup-2026-10-02.tar`. It contains sign-in data.
3. Add `.gitattributes` with `* text=auto eol=lf` and `*.ps1 text eol=crlf`. Git currently warns about LF→CRLF on two of the modified files.

---

## W1 — Playback reliability

### W1.1 Pin down the crash (half a day, before writing fix code)
**Evidence (stress report F1):** `SIGSEGV` at address `0x18` in `libquickjs.so` (`JS_DefineProperty` ← `build_backtrace` ← `JS_CallInternal`), on a `DefaultDispatcher` thread, called from `QuickJs.evaluate` ← innertubex `QuickJsEngine.evaluate` ← `EjsChallengeSolver.solveOnce` ← `YouTubeCipherService.processFormats`. The Java heap was full (136/136 MB). The QuickJS limits are 192 MB of memory and a 256 KB stack. It happened once in 74,479 events.

1. Write a JVM/desktop reproduction in the innertubex checkout (`.toolchain/innertubex` is a **v0.7.1** source checkout; update it to the **v0.7.2** tag that the app uses). Evaluate the current player script under a tightened stack (64 KB) and memory limit (64 MB) to trigger `build_backtrace` on stack or memory exhaustion. If it reproduces, it gives the upstream report a test case.
2. File upstream issues with the tombstone (`artifacts/stress/monkey-main/seed-404-monkey.txt`, near line 1865), one at MetrolistGroup/innertubex and one at dokar3/quickjs-kt. **Remove** any signed URLs or tokens from the attachments first.
3. Note the outcome in this plan. Isolation (W1.2) goes ahead regardless: it turns *any* future native fault in the solver into one failed track instead of a dead app.

### W1.2 Run challenge solving in a separate `:solver` process
**Goal:** a native crash in QuickJS kills only a disposable helper process. Playback, the media session, the UI and downloads keep running, and the affected track either retries once or shows the existing "unavailable" state.

**Why a library change is needed (checked in source):**
- `InnerTubeExtractor`'s public constructor takes a concrete `YouTubeCipherService` and wraps it in `DefaultExtractionCipherService`. The `ExtractionCipherService` interface (`initialize`, `preloadPlayerCode`, `prewarmEjs`, `processFormats`) is **`internal`**, so the app can't substitute it.
- `YouTubeCipherService` is a final class that creates its own `QuickJsEngine()` (also `internal`).
- Option (b), moving all of stream resolution into another process, would also move `AndroidStreamTokenProvider`'s WebView. Two processes can't share one WebView data directory (Android throws), so the solver process would have a separate cookie store and need cookies copied across on every change. That route is larger and more fragile.

**Route (a), recommended:**

1. **Fork and build innertubex from source.**
   - Fork `MetrolistGroup/innertubex` at tag `v0.7.2`. Add it with `includeBuild("../innertubex")` in `settings.gradle.kts`, using `dependencySubstitution` to replace `com.github.MetrolistGroup.innertubex:innertubex`. Keep JitPack as the fallback, behind a Gradle property, for one release.
   - This matches remediation plan P8.2, so do it once for both.
2. **Add a public seam in the fork** (keep the diff small so it can go upstream):
   ```kotlin
   // innertubex: cipher/ChallengeSolverBackend.kt (new, public)
   interface ChallengeSolverBackend {
       /** n-parameter and signature challenges for one player version, solved together. */
       suspend fun solve(playerUrl: String, playerScript: PlayerScriptRef,
                         nChallenges: List<String>, sigChallenges: List<String>): ChallengeSolution
       suspend fun prewarm(playerUrl: String, playerScript: PlayerScriptRef) {}
   }
   data class ChallengeSolution(val n: Map<String, String>, val sig: Map<String, String>)
   sealed interface PlayerScriptRef { data class Text(val js: String) : PlayerScriptRef; data class File(val path: String) : PlayerScriptRef }
   ```
   - `YouTubeCipherService` gets an optional constructor parameter `solverBackend: ChallengeSolverBackend? = null`. When it is null, behaviour is unchanged (the in-process `QuickJsEngine`).
   - When it is set, `processFormats` / `processNParameter` / `deobfuscateSignatureCipher` collect the challenges from all formats, call `solve` **once per batch**, and apply the results. Download the player script and handle caching the same way as today (`getOrDownloadPlayerCode`), but pass a `File` reference instead of the 2.6 MB text.
   - Add tests in the fork: a fake backend that returns fixed solutions gives the same URLs as the in-process engine on the existing `YouTubeCipherServiceTest` fixtures; a backend that throws gives the same failure type as today.
3. **Solver service in the app.**
   - Manifest: `<service android:name=".SolverService" android:process=":solver" android:exported="false" />`.
   - `SolverService.kt` (new): a bound service exposing an AIDL `ISolver` with one method, `solve(in Bundle request): Bundle`.
     - **Binder limit:** a transaction is capped at about 1 MB. Never send the script itself; send the absolute path of the script file in `noBackupFilesDir/solver/<sha256>.js`. Both processes share the app's user ID, so the solver can read it. Challenge lists and results are a few KB.
     - Inside the solver process, use the stock `YouTubeCipherService` (with no backend set) or `EjsChallengeSolver` directly. Keep one warm engine per player version.
     - **Idle shutdown:** call `stopSelf()` after 3 minutes without requests, to release the ≈200 MB native heap that QuickJS can use. Shut down immediately on `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` or higher when not busy.
   - `RemoteSolverBackend.kt` (new, main process): implements `ChallengeSolverBackend`.
     - It binds with `BIND_AUTO_CREATE` and caches the connection behind a `Mutex`. It calls `ISolver.solve` on `Dispatchers.IO` with a 30-second timeout (matching `EVALUATION_TIMEOUT_MS`).
     - On `DeadObjectException` / `RemoteException` or a binding death: log `solver_died` to `PlaybackEventLog`, rebind, **retry once**, then throw `StreamResolveException`. The existing skip/"unavailable" handling then takes over.
     - **Crash-loop guard:** if the solver dies 3 times within 10 minutes, mark it unhealthy for 10 minutes and fail fast. This stops a bad player script from crashing in a loop.
   - **Wiring:** in `YouTubeMusicProvider.kt` (around line 160), `YouTubeCipherService(http, configStore, solverBackend = RemoteSolverBackend(context))`. The HLS path at line 871 (`streamCipher.processNParameter`) uses the same instance, so it is covered too.
   - `Application` setup: if the app gains an `Application` subclass, skip all main-process setup (provider, image loader, WebView) when `processName` ends with `:solver`. Right now the app has no `Application` class (setup happens in `MainActivity`), so the solver process starts clean. **Don't add work to a future `Application.onCreate` without this guard.**
   - **Download path:** `OfflineDownloadEngine` resolves through the same provider, so it is covered too. Check this with a download while the solver is being killed (step 4).
4. **Tests.**
   - Unit (JVM): `RemoteSolverBackend` with a fake `ISolver` that throws `DeadObjectException` once → retries and succeeds. Throws three times → unhealthy and fails fast. A timeout → `StreamResolveException`.
   - Device (new instrumentation mode `stress_solver` in `OfflineSmokeInstrumentation`): start playback of a song that needs a challenge, then `adb shell kill -9 $(pidof com.musicglass.personal:solver)` during resolution. The main process PID must not change, `MediaSession` must stay connected, and the track must either play after the retry or show "unavailable". Repeat during a download.
   - Rerun stress seed **404** with 10,000 events, plus 4 new seeds. Expected: 0 main-process native crashes. Solver deaths are logged and recovered.
   - Memory: compare main-process PSS before and after. It should drop, because the QuickJS heap moves out. Report both processes.

**Done when:** killing `:solver` at any moment never kills playback or the UI; the five-seed monkey run has no main-process crashes; the fork's tests pass; and the upstream pull request for the seam is opened (merging it is not required).

### W1.3 Prove the `ATTESTATION_REQUIRED` recovery (stress F4)
**Problem:** the fix in `259104b` (`MusicPlaybackService.kt` around lines 419–430: `sabrRecoveryAction(...) == REFRESH_ATTESTATION` → `invalidateAttestation()`, drop the cached stream, re-prepare at the same position, once per track) has **never run on a device**. The error didn't happen again in later runs.

1. **Extract the decision logic** into a pure function in a new `SabrRecoveryPolicy.kt` (no behaviour change):
   `internal fun sabrRecoveryStep(failure: SabrFailureKind?, alreadyRefreshed: Boolean): RecoveryStep` returning `RefreshAndReprepare | GiveUp | NotSabr`.
2. **Unit tests** in `SabrRecoveryPolicyTest.kt`:
   - `ATTESTATION_REQUIRED` the first time → `RefreshAndReprepare`; the second time for the same track → `GiveUp`; a different kind → `NotSabr`.
   - Errors nested inside causes are found (walk `cause`, as the current code does).
   - `onMediaItemTransition` clears the set (line 138).
3. **Device fault injection** (stress build only, in `src/stress`): a debug broadcast `com.musicglass.personal.stress.FAIL_SABR` that makes the next SABR read throw `SabrPlaybackFailure(ATTESTATION_REQUIRED)`.
   - Script: `scenarios.py sabr_attestation`. Expected: `sabr_attestation_refresh` in the events log, playback resumes within ±2 s of the failure position, and a second injection on the same track gives the normal error.
4. Keep the existing `network_flap` scenario as the real-world check.

### W1.4 Instrument unexplained service stops (stress F5)
**Problem:** the playback service was destroyed 27 s into deep idle while audio was active. `onTaskRemoved` wasn't called, and the system log had already rotated, so the cause is unknown. It wasn't reproduced. **Don't fix blind; record the cause next time.**

1. **On every service `onCreate`:** read `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 5)`. For each new exit (track the last seen timestamp in preferences), append to `PlaybackEventLog`: `reason`, `subReason` (via reflection-free `toString()` parsing only in the stress build), `importance`, `pss`, `rss`, `description` and the timestamp. This covers process deaths: low memory, crashes, ANRs, `USER_REQUESTED`, `EXCESSIVE_RESOURCE_USAGE`, `FREEZER`.
2. **In `onDestroy`** (`MusicPlaybackService.kt` around line 510), log the reason context before releasing: `isPlaying`, `playbackState`, whether `onTaskRemoved` ran (a flag), `UsageStatsManager.appStandbyBucket`, `ActivityManager.isBackgroundRestricted`, `PowerManager.isDeviceIdleMode`, and whether the service was in the foreground. Today it only logs `service_destroy`.
3. **Detect "stopped while playing":** if `onDestroy` runs with `playWhenReady == true` and `onTaskRemoved` didn't run, log `unexpected_stop`. When the service next starts, show nothing to the user, but keep the event for the diagnostics export.
4. **Diagnostics export:** add Settings → About → "Copy playback diagnostics". It copies the last 200 `PlaybackEventLog` lines, with no tokens or URLs (the log already avoids them; add a unit test that asserts no `googlevideo.com` or `pot=` appears).
5. **Rerun** `background_doze` 5 times on battery. Capture `logcat -b events` live (`am_kill`, `am_stop_service`, `am_proc_died`) so that the system buffer can't rotate away.

**Done when:** the next unexpected stop, if one happens, comes with its cause recorded. This step doesn't promise a fix.

### W1 findings (2026-10-03, on device)

- **Current SABR song streams don't use the cipher at all.** Crashing the solver during SABR playback had no effect on the music. QuickJS runs for live radio (HLS n-challenge), fallback clients that return ciphered URLs, and prewarm. That is where the stress-test crash happened.
- **Likely cause of F5 (unexplained stop):** the new exit-reason log showed an earlier Riff process killed for `low_memory` at importance 125 (foreground service). That points to memory pressure, not a bug in the stop path. Moving QuickJS's heap (up to ~200 MB native) into `:solver`, which is released after 10 idle minutes, should help. Watch the `process_exit` lines.
- **W4 dependency:** R8 must keep `InnerTubeExtractor.cipherService` (the field `CipherBridgeInstaller` replaces): `-keepclassmembers class com.metrolist.innertubex.extraction.InnerTubeExtractor { *** cipherService; }`. `CipherBridgeTest` fails the build if it disappears, but only on the unshrunk unit-test classpath, so check the shrunk build on device.
- **Toolchain:** unit tests that load innertubex classes need JDK 21 (`.toolchain/jdk-21.0.12.1+1`); JDK 20 fails with `UnsupportedClassVersionError`.
- **Media3 retries hide single load errors:** one injected SABR failure is retried silently by ExoPlayer. Recovery runs only after 4 consecutive failures (first attempt + 3 retries).

### W1.5 Crash-aware restore (small, after W1.2)
When the main process restarts after an abnormal exit (W1.4 detects `REASON_CRASH_NATIVE` / `REASON_CRASH` from the previous run), restore the snapshot **paused**, as today, and show a one-time, dismissible notice in the mini player: "Playback stopped unexpectedly. Tap play to continue." Never auto-resume audio, because the user may no longer expect sound.

---

## W2 — Main-thread stalls

**Evidence:** activity recreation costs up to ≈490 ms on the main thread (rotation, dark mode, font size). `SearchHistory` load takes 112–340 ms per frame; `OfflineMusicStore.artwork` up to 50 ms.

### W2.1 Make `LyricsRepository` process-scoped
- **Now:** `RiffApp.kt:276` `remember(context) { LyricsRepository(context.applicationContext) }` and `:277` `DisposableEffect { onDispose { lyricsRepository.close() } }`. Every recreation builds a new OkHttp client and then evicts the old pool (stress F2 already moved eviction off the main thread). `OfflineDownloadEngine.kt:138` builds a separate instance per download.
- **Change:**
  1. Add `LyricsRepository.get(context)`: a lazily created, `@Volatile` double-checked singleton on `applicationContext`, the same pattern as `YouTubeMusicProvider.sharedInstance` / `MoodPhotos.get`.
  2. Remove the `DisposableEffect` that closes it. Keep `close()` for tests only; the process owns the instance.
  3. `OfflineDownloadEngine.kt:138` uses `LyricsRepository.get(context)`.
  4. Check `LyricsRepository` for per-screen state that was being reset by recreation (in-flight request maps, current-song preload). If any exists, key it by song ID rather than relying on a new instance.
  5. If the constructor does disk work (for example, creating the cache directory), move it to the first suspend call on `Dispatchers.IO`.
- **Test:** a unit test that `get()` returns the same instance; a device test that `config_churn` (W7.4) shows no `LyricsRepository.<init>` in a main-thread Perfetto slice after the first launch.

### W2.2 Load `SearchHistory` off the main thread
- **Now:** `SearchHistory.kt:13` reads and parses `search_history` preferences inside the `mutableStateOf(...)` initializer. `CatalogScreens.kt:76` builds it inside `remember`, so the first `getSharedPreferences` read runs on the main thread during composition.
- **Change:**
  1. Initialize `entries` to `emptyList()` and add `suspend fun load()`, which reads on `Dispatchers.IO` and then publishes on the main thread. Use a `loaded` flag so that `record()` before `load()` completes merges instead of overwriting.
  2. In `CatalogScreens.kt`, call `LaunchedEffect(history) { history.load() }`.
  3. Better: make it process-scoped like W2.1, so the list is loaded once per process.
- **Test:** `updatedSearchHistory` tests already exist. Add one for "record before load completes" → both entries kept, newest first.

### W2.3 Artwork file checks in the playback service
- **Now:** `MusicPlaybackService.kt:86` and `:365` call `offline.artwork(...)` (`File.isFile` / `length()`) on the service main thread for every metadata build. `DownloadsScreen.kt:113` already runs on IO and is fine.
- **Change:** give `OfflineMusicStore` a small in-memory `ConcurrentHashMap<String, Boolean>` of "artwork present" keyed by `scope/id`. Fill it when a download commits (`OfflineDownloadEngine.kt:124` already knows) and when `rows()` is read on IO. Invalidate entries when they are removed. The service reads only the map; if an entry is missing, it uses the provider URL and fills the map in the background.
- **Test:** unit-test the cache states (commit → present; remove → absent; unknown → provider URL used and background fill requested).

### W2.4 Startup work in `MainActivity.onCreate`
- **Now:** `removeRetiredContent(this)` (`ContentMigration.kt`) opens the `music_collections` preferences on every `onCreate`, including recreations. `StartupLogo.kt:69` reads `accessibility_display` preferences in `remember`.
- **Change:**
  1. Run `removeRetiredContent` once per process: a process-level `AtomicBoolean`, called from a background coroutine. It is idempotent and already gated by `retired_content_removed`. Data it migrates is only read later by `LocalCollections`, so add a `CompletableDeferred` that `LocalCollections` awaits before its first read of migrated keys.
  2. `StartupLogo`: pass `show = savedInstanceState == null` (already done) and **skip the preferences read entirely when `show` is false** (recreations). For the first launch, read on IO and default to "not reduced" until loaded. Or better, since this is accessibility-sensitive, read `reduce_motion` once in `MainActivity` off-thread and pass it down.
- **Test:** a Perfetto trace of a rotation shows no `SharedPreferencesImpl.awaitLoadedLocked` on the main thread.

### W2.5 Guardrail
Add `StrictMode.ThreadPolicy` with `detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog()` to the **stress** build only (`src/stress`, already there for other checks). Make `run-monkey.ps1` count `StrictMode policy violation` lines per run and fail if the count goes up from the rc17 baseline.

**W2 done when:** a `config_churn` run (rotation × 20, dark mode × 10, font scale 1.0↔1.3 × 10) has no main-thread slice over 100 ms caused by app code, and the StrictMode count of disk reads during recreation is 0.

---

## W3 — Instant Home (on-disk snapshot and skeletons)

**Now:** `feedCache` (`YouTubeMusicProvider.kt:112`, a `PageMemoryCache`) is process memory only. A cold start shows `"Loading YouTube Music…"` text (`HomeFeed.kt:134`, and the shelf placeholder at `:243`/`:279`) until the network answers. Removing the app from recent apps (`onTaskRemoved` → `discardPageMemory()`) and account changes clear it.

### W3.1 Feed snapshot store
1. **New `FeedSnapshotStore.kt`:** files at `noBackupFilesDir/feed-snapshots/<accountScope>/<key>.json`, with a version field, `savedAt` (epoch ms) and the `List<ProviderHomeSection>` serialized with the existing kotlinx.serialization `Json`.
   - Add `@Serializable` to `ProviderHomeSection`, `ProviderHomeCard` and `CatalogKind`. These are pure data, so there is no behaviour change.
   - **Keys:** `home:` (no mood) and `home:<mood>` for the last-used mood only. Leave podcasts and radio out for now, since radio is "live now" and goes stale fast.
   - **Limits:** 512 KB per file; skip the write if larger. The whole folder is capped at 2 MB, oldest deleted first.
   - **Writes:** atomic (`.tmp` then `renameTo`), on `Dispatchers.IO`, debounced to once per successful fresh fetch.
2. **What never goes into a snapshot:** stream URLs, tokens, cookies or signed artwork parameters. `ProviderHomeCard` holds only titles, IDs and artwork URLs, which are public `lh3.googleusercontent.com` / `i.ytimg.com` links. Add a unit test that fails if any serialized field contains `googlevideo.com`, `pot=`, `sig=` or `SAPISID`.
3. **Privacy and account rules (decision 4):** the scope is `accountScopeKey()` (`YouTubeMusicProvider.kt:249`, derived from `dataSyncId` + `authUser`), or `"signed-out"`. `signOut()` deletes that account's folder. `refreshAccount()` doesn't delete anything; it switches scope.

### W3.2 Stale-while-revalidate in the provider
- `homeSections(mood)` (around line 582): if the memory cache misses **and** a snapshot exists for this scope, emit the snapshot immediately (marked `stale = true`) **and** start the fresh fetch.
- The current API returns one `Result`. Add `fun homeSectionsFlow(mood): Flow<HomeFeedState>`, where `HomeFeedState = Loading | Stale(sections, savedAt) | Fresh(sections) | Failed(error, stale: sections?)`. Keep `homeSections` for other callers.
- A fresh success replaces the snapshot. A fresh failure keeps showing the stale sections with a small "Couldn't refresh" chip and a retry. It **never** replaces content with an error screen when stale content exists.
- **Age limit:** don't show snapshots older than 7 days; show skeletons instead.
- **Feed windows:** the 6 AM / 6 PM refresh (`PageMemoryCache.checkWindow`) still forces a fresh fetch. The snapshot only covers the time until that fetch returns.

### W3.3 Skeleton cards
1. **New `ShelfSkeleton.kt`:** placeholder shelves with the **same measured sizes** as the real ones (title bar plus N cards with `ShelfLayout` card dimensions), so nothing jumps when content arrives. Use 3 shelves × 4 cards.
2. **Shimmer:** a single shared `rememberInfiniteTransition` driving a gradient alpha between 0.06 and 0.12, at **one** animation clock for the whole screen. Respect reduced motion (`accessibility_display/reduce_motion` and the system animator scale): static blocks, no shimmer.
3. **Accessibility:** the skeleton container has `contentDescription = "Loading Home"` and `liveRegion = Polite`; individual blocks are hidden from accessibility services.
4. **Replace** the text placeholders at `HomeFeed.kt:134`, `:243` and `:279`. Errors with no stale content still show the existing retry state.
5. **Crossfade** from skeleton to content at the existing motion tokens (`GlassMotion.kt`), with no layout shift.

### W3.4 Tests
- **Unit:** snapshot round-trip; a corrupt file → ignored and deleted; an oversized file → not written; a different account scope → not read; sign-out → folder deleted; a 7-day-old snapshot → not shown; a fresh failure with a stale snapshot → `Failed(stale = sections)`.
- **Compose (W7.2):** `Loading` renders the skeleton with the expected test tags; `Stale` renders the real shelves plus no error; `Failed` + stale renders the shelves plus the refresh chip.
- **Device:** cold start with airplane mode on → Home shows the last shelves within the first frame after the startup logo. Cold start online → content first, then a quiet swap when fresh data arrives, with no visible jump (record a screen video and compare frames).

**Done when:** a cold start with a snapshot shows real shelves in the first frame after the logo, a cold start without one shows skeletons, and neither ever shows plain "Loading…" text.

---

## W4 — APK size

**Measured from `artifacts/release/riff-0.9.0-rc16.apk` (134,717,460 bytes ≈ 128.5 MiB), uncompressed:**

| Part | Size | Notes |
|---|---|---|
| DEX (4 files) | **61.4 MB** | No R8 (`app/build.gradle.kts:43` `isMinifyEnabled = false`). `material-icons-extended` alone is tens of MB of unshrunk classes. |
| `lib/x86_64` | 19.6 MB | Emulators only |
| `lib/x86` | 19.5 MB | Emulators only |
| `lib/arm64-v8a` | 18.3 MB | The phone: `libtranslate_jni.so` 16.4 MB, `liblanguage_id_l2c_jni.so` 1.0 MB, `libquickjs.so` 0.9 MB |
| `lib/armeabi-v7a` | 12.9 MB | Old 32-bit phones |
| `res` + `resources.arsc` | 3.4 MB | Includes four 0.4 MB font files |
| `assets`, `yt_ejs` | 0.7 MB | Includes `tflite_langid.tflite`; the `yt_ejs` solver JS **must stay** |

**Target:** ≤ 35 MB for arm64-only with R8. Estimate: DEX 8–14 MB + native 18.3 MB + resources ≈ 3 MB.

### W4.1 ABI split (half a day, no risk to behaviour)
- Add `release { ndk { abiFilters += listOf("arm64-v8a") } }` (decision 2). Keep `debug` unfiltered so emulators still work.
- Better: use `splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }`, publish only the arm64 split, and keep v7a available on request.
- Update `scripts/build-release-candidate.ps1` and `verify-device.ps1` for the new APK file names.
- **Expected saving:** ≈52 MB (x86 + x86_64 + v7a).

### W4.2 R8 and resource shrinking (1–1.5 days, risky: needs keep rules)
1. `release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }`. The `stress` build type inherits this through `initWith`, which is what we want, because stress tests should run shrunk code.
2. **New `app/proguard-rules.pro`.** Start strict, then loosen only where something breaks:
   - **kotlinx.serialization** (used by innertubex and the app's own `Json`): the official rules from kotlinx.serialization ≥1.7 are bundled as consumer rules. Verify innertubex's models keep their `$serializer` companions. If missing, add `-keep,includedescriptorclasses class com.metrolist.innertubex.**$$serializer { *; }` and `-keepclassmembers class com.metrolist.innertubex.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }`.
   - **quickjs-kt (JNI):** native code calls back into Java by name. The AAR ships a 403-byte `proguard.txt` consumer rule file; check that it covers the classes the native code calls back into, and add `-keep class com.dokar.quickjs.** { *; }` if evaluation fails after shrinking.
   - **ML Kit translate / language-id:** these ship consumer rules. Verify by translating after shrinking.
   - **Ktor + OkHttp:** consumer rules are bundled. Add `-dontwarn` only for warnings that R8 lists, one by one, never a blanket `-dontwarn **`.
   - **Media3:** consumer rules are bundled. Reflection-loaded extensions aren't used.
   - **App classes loaded by name:** the six widget receivers, services and `MainActivity` are kept by the manifest. `PlaybackSnapshot` / queue files are decoded by hand (`decodePlaybackSnapshot`), not by reflection, so check this. The new W3 `@Serializable` classes are handled by the serialization plugin.
   - **WebView token runtime** (`AndroidTokenRuntime` / `AndroidStreamTokenProvider`): it has no `@JavascriptInterface` bridge today (checked), so no rule is needed. Add `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }` if one is ever added.
   - **`yt_ejs/*.js` and `assets/`:** these are Java resources and assets, so R8 / resource shrinking leave them alone. Check that they are still present in the shrunk APK.
3. **Resource shrinking:** add `res/raw/keep.xml` with `tools:keep="@layout/widget_*,@xml/widget_*_info,@drawable/widget_*"`. Widget layouts are only referenced from XML provider info, which the shrinker usually handles, but this makes it explicit.
4. **Mapping files:** archive `app/build/outputs/mapping/release/mapping.txt` next to each release APK in `artifacts/release/`. Without it, crash stack traces from shrunk builds can't be read. Add this to the release script.
5. **Size budget:** make the release script fail if the arm64 APK is larger than **40 MB**. Print a per-part breakdown (the `unzip -l` summary used for the table above) into the release notes.

### W4.3 Verification (this is the risky part)
The full smoke checklist (section 10) on a **shrunk** build, with particular care for:
- Search, play a SABR song, play an HLS live radio station (both use the cipher / QuickJS / serialization).
- Official video playback; lyrics (all providers: Better Lyrics, LRCLIB, YouTube Music); **English translation** (ML Kit model download plus the JNI); language detection.
- Sign in (WebView token runtime); like/unlike; playlist edit; download plus offline playback; every widget design; sleep timer; voice search.
- `releaseSmoke=true` instrumentation (`OfflineSmokeInstrumentation`, `TranslationSmokeInstrumentation`) against the shrunk build. The `testBuildType` switch at `app/build.gradle.kts:12` already supports this.
- Install **over rc16** (`adb install -r`), not a fresh install: the snapshot, downloads and settings must survive.

**Done when:** the arm64 release is ≤ 40 MB (target ≤ 35 MB), the smoke checklist passes on the shrunk build, and the mapping file is archived.

---

## W5 — Session health and "Sign in again"

**Now:** `refreshAccount()` (`YouTubeMusicProvider.kt:196`) treats the session as valid when `SAPISID`, `__Secure-3PAPISID` or `__Secure-1PAPISID` cookies **exist**. It never checks that the server still accepts them. When Google expires or revokes the session, the app looks signed in but Library, likes and the personalized Home fail with generic errors.

### W5.1 Spike: capture what "expired" looks like (half a day)
On the **dummy** account:
1. Sign in on the phone. Then, from a desktop browser, open Google Account → Security → Your devices and sign that device out (or change the password).
2. Capture the responses from `innerTube.accountMenu(WEB_REMIX)` (already used by `profileFresh()` at line 662), a Library browse, and `/next`: HTTP status, `responseContext.serviceTrackingParams` / `mainAppWebResponseContext.loggedOut`, and any `"LOGIN_REQUIRED"` / `"UNAUTHENTICATED"` values.
3. Record the exact signals in this plan **before** writing W5.2. Don't guess the shape of the response.

### W5.2 Session state model
1. **New `SessionHealth.kt`:** `enum class SessionState { SignedOut, Checking, Valid, Expired, Unverified }`. `Unverified` means "offline or server error: can't tell"; it is never treated as expired.
2. A pure classifier, `classifySessionProbe(status: Int?, body: JsonObject?, networkError: Boolean): SessionState`, built from the W5.1 signals. Unit-test every captured case, using fixtures stored in `app/src/test/resources/session/` with tokens and cookies **removed**.
3. **Probe timing:** on cold start if `signedIn`; on `onResume` at most once every 6 hours; and immediately when any authenticated call returns an auth failure (reactive detection in `providerResult`). One probe at a time (a `Mutex`); results are cached for 10 minutes.
4. **Exposure:** expose `sessionState` as Compose state on the provider, next to `signedIn`.

### W5.3 Recovery UX
- **Expired:**
  - Show a persistent but dismissible banner on Home and Library: "Your YouTube Music session has expired." with the action **Sign in again**. The account screen shows the same state.
  - Account-only actions (like, add to playlist, Library) show the same message and action instead of a generic error.
- **Sign in again:** opens the existing WebView sign-in flow. After `useWebSession(...)` succeeds, run the probe. If the account is the same (`accountScopeKey()` unchanged, since it comes from `dataSyncId` + `authUser`), keep local saves, downloads and listening history. If the account is different, follow the normal account-switch path.
- **What keeps working while expired:** downloads, local saves, playback of the current queue (streams may still resolve with visitor data), search, and radio. Don't clear anything automatically.
- **Unverified:** no banner. Retry on the next probe trigger.
- **Persist rotated cookies:** call `CookieManager.flush()` after every successful authenticated response that set cookies (cheap), so Google's normal cookie rotation isn't lost when the process dies. This overlaps remediation P7. Do it here and mark P7's item done.

### W5.4 Tests
- Unit: classifier cases; probe throttling; reactive detection turning a failed Library call into `Expired`; `Unverified` never shows the banner.
- Compose: the banner renders for `Expired` with a working action; it doesn't render for `Unverified`.
- Device: repeat the W5.1 revoke on the dummy account. The banner appears within one app resume, Sign in again restores Library, and local saves are still there.

---

## W6 — Sleep timer survives process death

**Now:** `SleepTimerController` keeps the deadline only in memory. Its clock is `SystemClock.elapsedRealtime()` (`MusicPlaybackService.kt:48`), which keeps counting across process death but resets on reboot.

**When it matters (be honest about the scope):** if the process dies, audio stops anyway. Persisting helps when playback **restarts** in a new process before the deadline: from the notification, a widget, a Bluetooth play button or the app. Without persistence, the timer the user set silently disappears and music plays all night.

1. **Persist:** whenever `publish(deadline)` is called, write `{deadline, bootCount}` to `snapshotPrefs` (key `sleep_deadline`). `bootCount` comes from `Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)`. `deadline = 0` removes the key.
2. **Restore in `MusicPlaybackService.onCreate`:**
   - Different `bootCount` (rebooted) → discard.
   - `deadline > now` → re-arm with an `arm(deadline)` overload (refactor `set(minutes)` to compute the deadline, then call `arm`). The UI countdown already reads the published deadline.
   - `deadline <= now` (expired while dead) → clear it, and make sure the restored queue stays **paused** (it already restores paused). Never start playback from an expired-timer path.
3. **Volume:** if the process died mid-fade, the new player's own volume starts at 1.0, so nothing needs restoring. Don't save `originalVolume`.
4. **Tests** (extend `SleepTimerControllerTest`): `arm(future)` pauses at the deadline; `arm(past)` doesn't arm; restore with a different `bootCount` discards; re-arming at less than 15 s left starts the fade immediately from the current level.
5. **Device:** set a 5-minute timer, `adb shell am kill` the app while paused, start playback from the widget, and check that it pauses at the original deadline (±1 s).

---

## W7 — Automated UI regression checks and CI

**Now:** 27 JVM unit test classes, all logic-level (for example, `UiInteractionTest` tests `ShellModel` and the haptic trackers, not composables). Three `androidTest` instrumentations (offline smoke, translation smoke, the other agent's native-island probe). No Compose UI tests, no CI. The stress `config_churn` and `nav_leak` scenarios never ran because the phone locked.

### W7.1 Test seam for the provider (prerequisite, 1 day)
Screens reach `YouTubeMusicProvider` directly. To render them with fixed data:
1. Define a narrow `internal interface HomeFeedSource { fun homeSectionsFlow(mood: String): Flow<HomeFeedState>; ... }` (only what W3 adds), and similar for the `sessionState` from W5. **Don't** extract the whole provider; that is remediation P6.
2. Pass these through a `CompositionLocal` with the real provider as the default. Tests supply fakes.

### W7.2 JVM Compose tests with Robolectric (1 day)
1. Dependencies: `testImplementation("org.robolectric:robolectric:<latest>")` (a version that supports compileSdk 37), `testImplementation("androidx.compose.ui:ui-test-junit4")`, `debugImplementation("androidx.compose.ui:ui-test-manifest")`, and `testOptions { unitTests { isIncludeAndroidResources = true } }`.
2. **Tests (new `src/test/.../ui/`):**
   - `HomeFeedUiTest`: Loading → skeleton; Stale → shelves; Failed with and without stale content (W3).
   - `SessionBannerUiTest` (W5).
   - `ShellNavigationUiTest`: each tab renders its root; double-tap resets (complements `UiInteractionTest`); the back stack.
   - `LargeFontUiTest`: render the shell, mini player and a song row at `fontScale = 2.0` and width 360 dp. Assert that no text node is clipped (`assertIsDisplayed` plus a check of `TextLayoutResult.hasVisualOverflow` through a test semantics property) and that touch targets are ≥ 48 dp.
   - `QueueRowUiTest`: drag-handle semantics, the accessibility custom actions "Move up/Move down/Remove" (already implemented) exist and call the model.
3. Robolectric can't render AGSL / RenderEffect glass. Tests run with the "solid controls" setting forced on, which the app already supports.

### W7.3 Screenshot baselines (half a day, optional but cheap)
Use Roborazzi (`io.github.takahirom.roborazzi`) on the W7.2 tests: record baselines for Home (skeleton / content), Now Playing collapsed and expanded, the queue, and Settings, at font scales 1.0 and 1.3, in light and dark. Verify on every run; the diff images go into the build output.

### W7.4 Device UI regression instrumentation (1–1.5 days)
New `app/src/androidTest/.../UiRegressionInstrumentation.kt` using `createAndroidComposeRule<MainActivity>()` against the stress build on the phone (or the Android 12 emulator from `.toolchain/avd`):
1. **Navigation:** visit each tab and open a collection → back; double-tap resets.
2. **Player expansion:** with a fixed local queue (from a download or a seeded snapshot, so no network is needed), tap the mini player → full player; swipe down → mini; check the transitions keep the song title node.
3. **Rotation:** `activityRule.scenario.recreate()` and `UiDevice.setOrientationLeft/Natural` in the expanded player, the queue and Search with text entered. The state must survive: tab, scroll position, search text, expanded player.
4. **Font and density:** run under `adb shell settings put system font_scale 1.3` and `wm density` changes. Restore them in `@After`; the existing `device-state.ps1 -Action Restore` is the backstop.
5. **Queue gestures:** drag a row by its handle two positions down (`performTouchInput { down; moveBy; up }`), swipe to remove, undo where supported; compare with the service queue through the `MediaController`.
6. **Leak check (`nav_leak` equivalent):** loop the tabs 50 times, force a GC, assert `Debug.getMemoryInfo` Java heap growth < 8 MB and exactly one `MainActivity` alive (`WeakReference` count).

**Phone lock (from the stress harness notes):** before an unattended run, turn off "ask for PIN before unpinning", or use an unlocked test phone. Don't drive the vivo gesture bar.

### W7.5 Rerun the skipped stress scenarios
`python scripts/stress/scenarios.py config_churn` and `nav_leak`, on the rc17 build with the phone unlocked, after W2. Add the results to a new section of the stress report.

### W7.6 CI (half a day, needs W0.2)
GitHub Actions `.github/workflows/ci.yml` on pushes and pull requests:
- **JDK 17, cached Gradle:** `:app:testDebugUnitTest` (including the Robolectric and Roborazzi tests), `:app:lintRelease` and `:app:assembleRelease` **unsigned**. The keystore never goes into CI.
- Upload test reports and screenshot diffs as artifacts on failure.
- The **size budget** from W4.2 runs in CI on the unsigned release.
- **innertubex fork (W1.2):** use a git submodule or check it out as a second repository in the workflow so the composite build resolves.
- The device instrumentation (W7.4) stays local: `scripts/verify-device.ps1` gets a `-UiRegression` switch.

---

## W8 — Build hygiene (fills gaps; overlaps remediation P2/P3)

1. **Remove the retired `:core:glass` module:** drop it from `settings.gradle.kts:21` and delete `core/glass` (the README already calls it retired and not bundled). Check first that nothing in `app` depends on it: `grep -r "com.musicglass.glass" app/` must print nothing.
2. **Add the Unix `gradlew`:** run `gradle wrapper --gradle-version 8.13` from `.toolchain/gradle-8.13`, commit `gradlew` with mode 755, and check it in CI (W7.6 uses Linux).
3. **`CHANGELOG.md`:** move the version history out of `README.md` (rc4–rc16 paragraphs) into a changelog. The README keeps "what it is / build / behaviour and limits".
4. **Formatting:** 109 lines in `app/src/main` are over 200 characters, and many lines hold several statements. Do one mechanical ktlint/Spotless reformat **in a single commit with no other changes**, then enforce it with `spotlessCheck`. Do this **after** W1–W3 are merged to avoid painful rebases (remediation P3).
5. **Warnings:** compiler and lint warnings to zero, then `allWarningsAsErrors` (remediation P2).

---

## 10. Smoke checklist (every release candidate)

Run on the phone, release build, installed **over** the previous release:

- [ ] App opens; paused queue, position, downloads and settings survive the upgrade.
- [ ] Sign in (or the existing session is still valid; W5 shows the correct state).
- [ ] Search and play a song (SABR); seek; skip; previous.
- [ ] Live radio station (HLS).
- [ ] Official video for a song with one; "unavailable" message for one without.
- [ ] Lyrics with word sync; English translation (first use downloads the model).
- [ ] Download a song; play it in flight mode.
- [ ] Home shows content (or skeletons, then content) with no "Loading…" text (W3).
- [ ] Every widget: pause, play, skip.
- [ ] Sleep timer: the countdown shows; the fade and pause happen.
- [ ] Queue: drag by handle, swipe to remove, Play next.
- [ ] Rotate in the full player and in Search; switch dark mode; font scale 1.3: no jank, state kept.
- [ ] Mood photo appears on Home.
- [ ] One pass on the Android 12 emulator.

---

## 11. How this lines up with the 2026-10-02 remediation plan

| Remediation plan phase | This plan | Notes |
|---|---|---|
| P0 signing key | **W0.1** | Do exactly as written there |
| P1 version control | W0.2, W0.3 | Git exists now (18 commits); history import from source zips is optional |
| P2 build hygiene, warnings | W8.1–W8.3, W8.5 | |
| P3 formatting | W8.4 | After W1–W3 |
| P4 UI tests and CI | **W7** | W7 is more specific (rotation, fonts, queue, leaks) |
| P5 APK size | **W4** | Numbers updated from the rc16 APK |
| P6 typed navigation, file split, DI | Not in this plan | W7.1 adds only the minimum seam; P6 later |
| P7 sign-in robustness | **W5** | W5 adds detection and recovery UX; cookie flush moved here |
| P8 YouTube-change resilience, fork | **W1.2** (fork) | The fork is shared; canary script and runbook stay in P8 |
| P9 sleep timer | **W6** | |
| — | **W1.1, W1.3–W1.5, W2, W3** | New since the stress test and review B |

---

## 12. Tracking

| Workstream | Branch | Status | Release |
|---|---|---|---|
| W0 safety net | — | ◐ 2026-10-03: W0.1 done (certificate recorded, backups in Google Drive + Gmail with a restore drill, password encrypted with DPAPI, release script can't create a key silently and checks the certificate). W0.2 done: private repository https://github.com/leovaldez2k20/riff, local `master` pushes to `main` (`push.default=upstream`), history scanned for secrets before the first push. Open: W0.3 | — |
| W1.1 crash reproduction and upstream report | `fix/w1-playback` | ☐ upstream issues not filed yet (needs the user's OK to post publicly) | rc17 |
| W1.2 `:solver` process | `fix/w1-playback` | ☑ 2026-10-03 (`8b2838e`, `cb28ae7`). Java bridge instead of a fork: the fork needs Gradle 9.7/AGP 9.3. Device test 3/3 pass | rc17 |
| W1.3 attestation recovery proven | `fix/w1-playback` | ☑ 2026-10-03 (`d1c18db`): unit tests; on device, 4 injected failures → `sabr_attestation_refresh` → playback continued | rc17 |
| W1.4 stop diagnostics | `fix/w1-playback` | ◐ 2026-10-03 (`152cf62`): exit reasons and stop context logged; Settings export waits for the other agent's SettingsScreen work | rc17 |
| W1.5 crash-aware restore | `fix/w1-playback` | ☐ waits for the other agent's NowPlaying work | rc17 |
| W2 main-thread stalls | `fix/w2-stalls` | ◐ 2026-10-03 (`f17b8df`): W2.1–W2.4 code and tests done; W2.3 widened to the queue builder (`catalogItem` stat per item, 2,000 per queue). Waiting on: device measurement (rotation churn, 2,000-track queue) and the W2.5 StrictMode count in run-monkey.ps1 | rc17 |
| W7.1–W7.3 test seam, Compose tests, screenshots | `test/w7-ui` | ☐ | rc17 |
| W3 instant Home | `feat/w3-instant-home` | ☐ | rc18 |
| W4 APK size | `build/w4-size` | ☐ | rc18 |
| W5 session health | `feat/w5-session` | ☐ | rc18 |
| W8 hygiene | `chore/w8-hygiene` | ☐ | rc18 |
| W6 sleep timer | `fix/w6-sleep` | ☐ | rc19 |
| W7.4–W7.6 device UI tests, scenarios, CI | `test/w7-device` | ☐ | rc19 |

### Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| The innertubex fork drifts from upstream | Medium | Keep the seam diff small; open an upstream pull request; rebase on each innertubex release |
| Binder transaction too large | Low with file paths | Never send scripts over binder; unit-test the request size (< 64 KB) |
| R8 breaks reflection or JNI paths at runtime only | **High** without care | Strict keep rules; full shrunk smoke checklist; `releaseSmoke` instrumentation; archive the mapping |
| A stale Home snapshot shows the wrong account | Low | Per-scope folders; delete on sign-out; unit test |
| False "session expired" banners | Medium | `Unverified` state for network/server errors; classifier built from captured real responses only |
| An unattended device run stops at the lock screen | Medium | Turn off unpin-PIN or use an unlocked phone; don't drive the gesture bar |
