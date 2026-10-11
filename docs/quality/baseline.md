# Q00 baseline

Status: PASS (baseline established; release compliance remains unverified). Authorized 8 October 2026 (Asia/Kolkata).
Starting revision: b99865fe84b1d83d7495bb98fbd07b6e3abb7b20; dirty checkout.
Existing work preserved: modified scripts/download_favourites.py; untracked docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md.

Scope: source/toolchain inspection, fresh JVM/lint/build checks, metadata audit and baseline evidence. No application behavior changes, installation, provider-account mutations or publication authorized in this phase.

Git status failed under the read-only sandbox; the same unmodified checkout works outside it. No repository configuration repair was necessary.
Device checks: BLOCKED because no authorized phone/USB debugging is available. Saved reports are historical until independently rerun.

Fresh results and limitations are recorded below.

## Source inventory and feature reconciliation

Source counts (Kotlin/Java, excluding generated output): main 120 files / 14,443 lines; JVM test 55 / 3,195; androidTest 17 / 2,876. Counts match the planning baseline. Test-source files include support files; they are not executed-test counts.

Statuses describe source inspection, not phone verification. Implemented = present source behavior; partially verified = present with limited local policy/parser coverage and outstanding runtime/provider/device evidence; unavailable = documented guarantee absent in current implementation; historical = prior artifact only.

| Feature-guide claim | Baseline classification | Source/test boundary and limitation |
|---|---|---|
| Search songs/albums/artists/playlists/podcasts; voice input, clear, sorting, pagination | Partially verified | RiffApp, CatalogScreens, ProviderCatalog, VoiceSearchController; ProviderCatalogTest. Live catalog/voice unavailable here. |
| Provider library, acknowledgments for likes and playlist edits | Partially verified | YouTubeMusicProvider, PlaylistEditing; FeaturePolicyTest, PlaylistSessionTest. No personal account mutations tested. |
| Multi-playlist edits, exact-entry removal, accent search/date sorting | Partially verified | PlaylistEditing, CollectionTools; CollectionToolsTest, CollectionSortMenuTest. Native provider sort needs live evidence. |
| Account-scoped saves and listening history | Partially verified; detailed-history claim unavailable | LocalCollections has scoped keys; ListeningHistory uses one history-v1.jsonl. These are different stores. Q03-Q05 must resolve ownership. |
| Device audio and contextual media permission | Partially verified | LocalAudioRepository, RiffApp, manifest; physical permissions/content URI checks pending. |
| Licensed mood photos/daily refresh/offline fallback/original covers | Partially verified | MoodPhotos, ArtworkFallback, ArtworkImageLoader, HomeFeed; external image/provenance review not a new live check. |
| Home/Podcasts/Radio feeds; twice-daily memory cache and library refresh | Partially verified | PageMemoryCache, HomeFeed, CatalogScreens, YouTubeMusicProvider; PageMemoryCacheTest. |
| Live radio/HLS, surface detachment, retry | Partially verified | RadioCatalog, YouTubeHlsManifest, YouTubeLiveScreen, MusicPlaybackService; YouTubeHlsManifestTest. Live playback blocked. |
| Six widgets and pin/resize behavior | Partially verified | RiffWidgets, SettingsScreen, widget resources; FeaturePolicyTest covers action policy, launcher checks blocked. |
| Sleep timer, deadline restart, final fade, system volume unchanged | Partially verified | SleepTimerController, MusicPlaybackService; SleepTimerControllerTest, FeaturePolicyTest. Actual output/focus timing blocked. |
| Data saver for future streams and final-queue removal | Partially verified | DataSaverSettings, OriginalAudioPolicy, MusicPlaybackService; DataSaverTest, QueueRemovalTest. |
| Output route icon and adaptive navigation lens | Partially verified | AudioOutputControls, AgslNavigationLens, PlatformCompatibility; PlatformCompatibilityTest; actual routes/API render checks blocked. |
| Recommendations versus authored repeat order; queue reorder/remove/actions | Partially verified | MusicPlaybackService, InlineQueue, CollectionTools; QueueRegressionTest, Rc16RegressionTest. |
| Official video counterpart and position-preserving switch | Partially verified | YouTubeMusicProvider, InlineYouTubeVideo; OfficialVideoPolicyTest. Provider/device playback blocked. |
| Background playback, stop on recents removal, paused restoration | Partially verified | MusicPlaybackService, PlaybackSnapshot; PlaybackSnapshotTest. Snapshot preferences are currently shared across accounts. |
| Player/lyrics transitions, capsule controls, refresh preference | Partially verified | SongPresentation, CapsuleSlider, UiRefreshRate; SongPresentationTest, RefreshRatePolicyTest. No frame-rate claim. |
| Full-collection download, delete-all and cache/download separation | Partially verified | OfflineDownloadEngine, OfflineMusicStore, DownloadsScreen; DownloadBatchTest, DownloadControlsTest, MusicCachePolicyTest. Crash/full-disk boundaries pending. |
| Permanent bundles, fresh-stream restart, remux/seek, independent assets | Partially verified | OfflineDownloadEngine, OfflineMediaSource, WebmSeekIndex; OriginalAudioEngineTest, WebmSeekIndexTest. Offline decoder proof blocked. |
| Smart Downloads eligibility, 250 cap, exclusions, pins, budgets/reserve | Partially verified | OfflineMusicStore, OfflineDownloadEngine, OfflineDownloadService; OriginalAudioPolicyTest, DownloadControlsTest. Scheduler/battery/runtime checks blocked. |
| Timed/plain lyrics, preload, provider order, +0.2s default | Partially verified | LyricsRepository, LyricsModels, LyricsPanel; LyricsTest, LyricsPreloadTest, AdditionalLyricsProvidersTest. Provider availability not asserted. |
| On-device English translation/model download | Partially verified | LyricsTranslation; TranslationSmokeInstrumentation not run; actual ML Kit metrics behavior not newly investigated. |
| Glass/frost sliders, contrast/reduced motion/haptics, double-tap navigation | Partially verified | AccessibilitySettings, GlassMotion, UiHaptics, ShellScene; ComponentUiTest, UiInteractionTest, TabHitTestTest. TalkBack/contrast/large-text evidence blocked. |
| Automatic 1-12s equal-power audio/visual crossfade and cancellations | Partially verified | Crossfade, MusicPlaybackService, SongPresentation; CrossfadeAndSeekTest; decoder/buffering/gains on device blocked. |
| Direct transport, deferred resolve/cache/coalescing; no durable streams | Partially verified | OriginalAudioEngine, DeferredProviderSource, YouTubeMusicProvider, OfflineMusicStore; OriginalAudioEngineTest, OriginalAudioPolicyTest. Full persistence/security audit belongs to Q15. |
| Separate solver guarantees playback isolation | Partially verified; unconditional guarantee unavailable | RemoteCipherClient/SolverService exist, but YouTubeMusicProvider retains in-process cipher if reflection installation fails. Q08 must fail closed. |
| Crash notification, network recovery, diagnostics, session-expiry UI | Partially verified | ServiceStopDiagnostics, StreamRecovery, PlaybackEventLog, SessionBanner; ServiceStopDiagnosticsTest, NetworkRecoveryTest, SessionHealthTest. Race/real-network coverage pending. |
| Retired prototype and older verification records | Historical | docs/* dated reports and archive/core-glass reference; no saved report counts as a fresh result. |

## Known baseline gaps and later work

- Provider scope key includes authUser selector (YouTubeMusicProvider.accountScopeKey), so chooser reordering can change ownership: Q03.
- SearchHistory uses shared search_history/queries preferences; detailed history and last_playback are also shared: Q03-Q05.
- Snapshot decoder limit is 3,000,000 characters, inconsistent with service byte guard: Q04.
- Reflection bridge failure leaves the in-process cipher configured: Q08.
- README correctly says source is newer than rc20 APK; version/code remains 0.9.0-rc20 / 21: Q01.
- Privacy documentation omits some implemented lyrics providers and links the previous repository for issues: Q17.
- CI push filter covers main/fix branches, not codex/**; portability scripts use project-local assumptions and some absolute fallback paths: Q16.
- Gradle emits Kotlin future-minimum, AGP compileSdk 37.0 compatibility, and SDK XML-tooling warnings. Record without blanket suppression or unrelated upgrades.

## Metadata audit

355 tracked paths examined. No tracked generated/cache/artifact paths or credential-file paths matched the audited categories (.env, local.properties, release-signing.properties, jks/keystore/p12/pfx, build/.gradle/.toolchain/artifacts/__pycache__). .gitignore excludes those common paths. This is a path audit, not proof that all tracked text is secret-free; full security review remains Q15. No files were deleted as cleanup.


## Reproduction

Run from the repository root in a writable session. Use configured JDK/SDK paths, or the existing local fallbacks below; do not load signing secrets for this unsigned baseline.

```powershell
$env:JAVA_HOME = Join-Path (Get-Location) '.toolchain\jdk-21.0.12.1+1'
$env:ANDROID_HOME = Join-Path (Get-Location) '.toolchain\sdk'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.toolchain\gradle-user-home'
.\gradlew.bat --version --offline --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --offline --continue --rerun-tasks --console=plain
```

The first baseline command cleared the four MUSIC_GLASS_* signing variables only inside its child PowerShell process. It did not read signing files or change the parent environment. If dependencies are absent on a different machine, the offline failure is an environment blocker; resolve trusted dependencies separately and record a distinct rerun.

No applicable AGENTS.md was found in the checked parent chain or repository sources. No repository configuration or application source was edited.

Device rerun prerequisites: explicitly authorized dedicated test device/profile/account, compatible test APK pairing and signing path, and exact APK/source hashes. Follow implementation-plan section 17; run the existing instrumentation scripts only after installation is explicitly authorized because they install APKs. API31/33/36 functional tests, live provider checks, audio continuity, TalkBack/keyboard walkthrough, physical R/L performance and soaks remain BLOCKED. Never substitute historical logs or emulator timing for physical evidence.

## Shrinker warning investigation

The fresh R8 run emits Kotlin metadata parsing warnings with the pinned Kotlin 2.4.10 / AGP 8.13.2 combination. This is an unresolved release-toolchain risk, not a JVM test failure. Official Android guidance consulted on 8 October 2026 lists R8 9.1.29 for Kotlin 2.4: https://developer.android.com/build/kotlin-support . The fresh mapping header confirms bundled R8 8.13.19 (r8-mapping-header.txt). Choose the smallest supported correction in a separately approved change; do not infer shrunk-runtime correctness from assembly success. No dependency change or suppression was made in Q00.


## Fresh execution results

Evidence directory: artifacts/quality/b99865fe-dirty/q00-20261008T042625Z/
Build: SUCCESS, exit 0, 14m 5s, 116 actionable tasks executed (rerun requested; no clean/delete performed).
Build UTC start: 2026-10-08T04:26:49.5894473Z
Build UTC end: 2026-10-08T04:40:55.7453728Z
Source remained unchanged during execution, verified against source-file-hashes.json. That file records every tracked app/build-input hash; its SHA-256 is 97C2F51F38ED7C58F7F27E6DABE883C6A68CA0261A04F47FC2FBF6FDFE21FA31.

| Check | Result | Evidence |
|---|---|---|
| JVM tests | PASS: 304 tests, 55 suites, 0 failures/errors/skips | junit-summary.json; junit/TEST-*.xml |
| Debug lint | PASS task: 0 errors, 83 warnings, 5 hints | lint-results-debug.xml/html; lint-debug-ids.json |
| Release lint | PASS task: 0 errors, 90 warnings, 5 hints | lint-results-release.xml/html; lint-release-ids.json |
| Debug assembly | PASS | baseline-build.log; apk-summary.json |
| Shrunk unsigned release assembly | PASS with unresolved R8 warnings | baseline-build.log; r8-mapping-header.txt |
| Release size | PASS baseline ceiling: 26.275 MiB / 40 MiB | apk-summary.json |
| Release APK SHA-256 | 8AA714303656318441C39AB610C5430D63708E7E5332EE92675F30D23A092849 | archived app-release-unsigned.apk |
| Release native ABI | arm64-v8a | apk-entry-audit.json |
| Merged manifest | backup=false; cleartext=false; debuggable omitted (Android default false); 21 components | release-merged-manifest.xml; merged-manifest-components.json |
| Signature / in-place upgrade | BLOCKED: unsigned baseline, no installed-app certificate/device comparison | Not claimed |
| Phone/live/performance/accessibility | BLOCKED: phone unavailable | Follow plan section 17 when authorized hardware is available |

Lint IDs are preserved below. Warning ownership is preliminary, not final acceptance triage: HardcodedText/RtlSymmetry/InlinedApi -> Q13; lifecycle/storage warnings (ApplySharedPref/Recycle/StaticFieldLeak) -> Q04/Q11/Q15; ExportedService -> Q15; build/dependency/ABI/target issues -> Q15/Q16; style/performance suggestions -> Q11/Q14. No warning was blanket-suppressed. Optional dependency-update suggestions do not authorize upgrades.

| Lint ID | Severity | Debug | Release |
|---|---|---|---|
| AndroidGradlePluginVersion | Warning | 0 | 1 |
| ApplySharedPref | Warning | 5 | 5 |
| AutoboxingStateCreation | Hint | 5 | 5 |
| ChromeOsAbiSupport | Warning | 2 | 2 |
| ConfigurationScreenWidthHeight | Warning | 2 | 2 |
| ExportedService | Warning | 1 | 1 |
| GradleDependency | Warning | 3 | 2 |
| HardcodedText | Warning | 11 | 11 |
| InlinedApi | Warning | 1 | 1 |
| ModifierParameter | Warning | 2 | 2 |
| NewerVersionAvailable | Warning | 0 | 7 |
| ObsoleteSdkInt | Warning | 1 | 1 |
| OldTargetApi | Warning | 1 | 1 |
| Recycle | Warning | 1 | 1 |
| RtlSymmetry | Warning | 1 | 1 |
| StaticFieldLeak | Warning | 2 | 2 |
| UseKtx | Warning | 40 | 40 |
| UseTomlInstead | Warning | 9 | 9 |
| VectorRaster | Warning | 1 | 1 |

Q00 acceptance: PASS. Commands and fresh outputs establish the baseline; existing work preserved; unavailable checks are explicit. This is not approval of release quality. No source fixes were made. Next proposed phase is Q01 build identity and result schema, subject to user permission. The R8 compatibility correction requires a separately approved narrowly scoped change before trusting a release candidate.

