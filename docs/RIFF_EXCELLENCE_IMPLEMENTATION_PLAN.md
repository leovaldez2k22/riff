# Riff excellence implementation plan

Version: 1.1. Date: 7 October 2026 (Asia/Kolkata); revised 11 October 2026, see section 22.
Status: implementation specification. Proposed changes and targets are not completed results.
Project: Riff; application ID: com.musicglass.personal.
Original workspace: C:/Users/KIIT0001/.gemini/antigravity/scratch/App.

## 1. Objective and executor instructions

Improve the existing Riff Android application to an exceptionally reliable, maintainable, accessible and polished release while preserving its current identity and features. This document defines decisions, work packages, acceptance criteria and handoff requirements so a different AI can execute without the original conversation.

A subjective 10/10 cannot be guaranteed. Success means every mandatory gate in section 18 passes with evidence tied to the exact source and release artifact. A high test count, attractive screenshot or successful build alone is insufficient.

### 1.1 Execution contract

1. Implement only after the human authorizes implementation. Creating this plan does not itself authorize application changes, device installation, account mutations, publication or data deletion.
2. Read applicable AGENTS.md files and inspect current source before editing. Paths below are relative to project root. Production Kotlin/Java currently lives under app/src/main/java/com/musicglass/personal/.
3. Current source and new evidence govern present behavior. Older plans are historical context. Do not reinstate Samples, demo feeds, obsolete minimum API settings or superseded visual rules.
4. Preserve all uncommitted work. Obtain a successful working-tree status before editing. Git status failed during planning with "this operation must be run in a work tree"; do not assume cleanliness. Diagnose configuration/root read-only. Never reset, clean, delete .git or recreate the repository to repair this.
5. Reuse the checkout unless isolation is needed. New branch naming default: codex/riff-quality-<phase>. Never overwrite an existing branch or create user-visible chats/delegate work without authorization.
6. Execute packages in dependency order. Each package requires code, meaningful tests, relevant documentation and evidence before PASS.
7. Separate behavioral changes from mechanical formatting/package moves. Preserve reviewability through coherent changes.
8. Use existing toolchain and Gradle wrapper. Verify compatibility using official documentation when selecting new tooling. Do not perform unrelated dependency upgrades.
9. Deterministic development tests require no personal credentials. Live-provider checks run separately using explicitly authorized test accounts.
10. Never mutate personal provider data for testing. Disposable account resources require authorization; temporary provider preferences must be restored in finally blocks.
11. Never log/commit/export cookies, passwords, keys, signed URLs, raw account IDs, unredacted provider bodies or device backups.
12. Never uninstall the user's main app to solve a signing mismatch. Use an authorized test package/device or existing signing process.
13. Missing tools/hardware/credentials produce BLOCKED gates with exact rerun instructions. Continue independent work. Never invent results or silently waive requirements.
14. Ordinary choices are resolved below. Record technically necessary deviations in ADRs with evidence and smallest alternative. Ask only for consequential choices that this plan does not resolve.
15. Maintain docs/quality/execution-ledger.md. Resume from it after handoff/compaction; do not restart completed work.
16. Commit each package locally on its branch as coherent commits once its ledger entry records PASS, and before starting the next package. Work in progress that spans a package boundary is committed as a labeled checkpoint rather than left only in the working tree. Record the commit in the ledger. Pushing, opening pull requests and publication still require explicit authorization. Never commit ignored evidence, credentials or signing material.

### 1.2 Ledger

Each package records: ID; TODO/IN_PROGRESS/PASS/FAIL/BLOCKED; dependencies; starting revision; changed paths; commands; test and artifact paths; each acceptance result; limitations; next exact action. Record failures and reruns separately. PARTIAL is not PASS.

## 2. Inspected baseline and source map

Rebaseline in Q00. These observations describe the folder inspected on 7 October, not a guaranteed later checkout.

| Item | Inspected state |
|---|---|
| Product | Native Android music app; unofficial YouTube Music integration; local audio |
| Modules | One :app module |
| Production source | 120 Kotlin/Java files; approximately 14,443 lines |
| JVM tests | 55 source files; approximately 3,195 lines |
| Device tests | 17 source files; approximately 2,876 lines |
| Saved JVM results | 304 tests / 55 suites; zero failures, errors or skips |
| Saved release lint | Zero errors; 90 warnings; five hints |
| Version | 0.9.0-rc20 / code 21; multiple local implementations share identity |
| Android | min API 31; target API 36; compile API 37 |
| Toolchain | Gradle 8.13; documented JDK 21; bytecode target 17 |
| Stack | Compose, Material 3, Media3, Coil, Ktor/OkHttp, InnerTubeX, ML Kit |
| Physical evidence | Mainly iQOO I2217 / Android 16 |
| Local release size | Approximately 26.3 MiB; existing ceiling 40 MiB |

Saved reports were inspected, not freshly run during planning. Device artifacts contain failed attempts and later successful reruns. A later playback/crossfade run reports 23 passing tests; a later playlist-sort run reports one passing test. Neither certifies the whole current app.

| Responsibility | Current files |
|---|---|
| Shell/startup | MainActivity.kt, RiffApp.kt, ShellScene.kt, PlayerMorphController.kt |
| Player/queue UI | NowPlaying.kt, InlineQueue.kt, SongPresentation.kt, PlayerArtwork.kt |
| Playback boundary | PlaybackGateway.kt, PlaybackProtocol.kt, MusicPlaybackService.kt, PlaybackSnapshot.kt |
| Audio | Crossfade.kt, SleepTimerController.kt, AudioSessionEffects.kt, VolumeBoostProcessor.kt |
| Session/provider | YouTubeMusicProvider.kt, AccountScreen.kt, SignInFlow.kt, SessionHealth.kt, CookieRotation.kt |
| Catalog/edits | ProviderCatalog.kt, CatalogScreens.kt, PlaylistEditing.kt, CollectionTools.kt |
| Solver/streams | RemoteCipherClient.kt, SolverService.kt, SolverProtocol.kt, CipherBridgeInstaller.java, AndroidStreamTokenProvider.kt, SabrPlaybackTransport.kt |
| Downloads | OfflineMusicStore.kt, OfflineDownloadEngine.kt, OfflineDownloadService.kt, DownloadsScreen.kt, OfflineMediaSource.kt |
| Local data | ListeningHistory.kt, SearchHistory.kt, LocalCollections.kt, LibraryHistoryScreens.kt, ContentMigration.kt |
| Lyrics | LyricsRepository.kt, LyricsModels.kt, LyricsPanel.kt, AdditionalLyricsProviders.kt, LyricsTranslation.kt |
| Appearance/accessibility | AccessibilitySettings.kt, GlassMotion.kt, FrostedPopup.kt, ChromeGlassEffect.kt, AgslChromeGlassEffect.kt, UiHaptics.kt |
| Diagnostics | PlaybackEventLog.kt, ServiceStopDiagnostics.kt, stress-only StressDiagnostics.kt |

Follow symbols if files move. Do not create a second implementation because the original path changed.

### 2.1 Main issues to resolve

- Detailed history is a shared listening-history/history-v1.jsonl file, not account-filtered.
- Playback snapshots use shared last_playback preferences while downloads/local collections are scoped.
- Large provider, service and shell files combine unrelated responsibilities.
- Cipher integration reflects into dependency internals; fallback can put solving back in the main process.
- Dense formatting and positional saved-state lists increase maintenance risk.
- Release identity, README counts and some dependency inventory claims have drifted.
- Tooling includes machine-specific paths; physical low-end/accessibility coverage is incomplete.

## 3. Preserve scope and enforce invariants

Preserve Home, Podcasts, Radio, Library and detached Search; real catalog feeds; accounts/provider library/playlist edits; local audio; background playback; queues/authored repeats; official video pairing; crossfade/boost/sleep timer; lyrics/translation; downloads/Smart Downloads; widgets; Data saver; glass/frost sliders; contrast/reduced motion/haptics.

Keep app name, application ID, signing certificate and upgrade path. Keep minimum API 31. Do not change compile/target settings without a verified narrowly scoped requirement.

Exclude backend/social/subscription/AI playlist/recognition/cloud-sync/casting expansion and wholesale visual redesign. Do not introduce Room, DataStore, Hilt or production modules solely for fashion. Existing SQLite/preferences are acceptable behind clear ownership boundaries.

The section 22 additions are in scope and do not relax these exclusions: the update notice and provider canary use no project backend; local export/import is a user-initiated file, not cloud sync; loudness normalization is an audio-settings option, default off.

Required invariants:

| ID | Invariant |
|---|---|
| I01 | Durable metadata contains no expiring streams, cookies or tokens |
| I02 | Account operations capture scope and generation |
| I03 | Stale operations cannot publish to a new account or recreate deleted data |
| I04 | Pause/stop/skip overrides recovery; restart never autoplays |
| I05 | Authored order/repeats survive filtering, sorting and serialization |
| I06 | Cache clearing preserves downloads; deletion stays in owned paths |
| I07 | One crossfade gain owner; sleep-timer ownership respected |
| I08 | Loading/unavailable/auth/offline states are truthful and actionable |
| I09 | Release contains no stress/fixture entry points |
| I10 | Ambiguous legacy data is preserved for explicit recovery, never silently adopted |
| I11 | Complete offline bundles play without provider/session requests |
| I12 | Local deletion never changes provider playlists/favorites |

## 4. Fixed architecture and product decisions

### ADR-Q01: Incremental architecture

Keep :app. Introduce responsibility packages and constructor-injected collaborators through a small process-owned AppContainer. Preserve public Android entry-point class names using wrappers if implementation moves. Update manifest/widget/R8/test references together when necessary.

Target packages beneath com.musicglass.personal: app, account, catalog, playback, downloads, lyrics, storage, settings, ui, diagnostics. Keep stable Android entry wrappers in the original package.

Extract only where a class owns behavior/lifecycle/persistence or a real test boundary. One source of truth owns active account, queue, sleep deadline and crossfade. Mechanical moves never share a change with database migration.

### ADR-Q02: Identity and data ownership

Typed AccountScope variants:
- Guest: anonymous provider history/search/queues.
- DeviceLocal: on-device audio history and pure local queues.
- Provider(scopeId): authenticated provider history/search/queues/downloads/local saves.

Provider scope v2 hashes a versioned domain prefix plus the normalized verified stable provider account/sync identifier using SHA-256. Exclude positional authUser selector from identity; retain it for request routing. Persist only opaque digests. Different verified channel identities remain separate.

Create verified legacy-key aliases. Never infer ownership from name/avatar/email-like labels or the currently signed-in account. Alias migration requires proof that the old derivation belongs to the verified identity. Missing stable ID leaves account persistence unavailable with a clear unverified state; it must not merge into Guest.

Account chooser reordering must not change ownership. If identifier stability cannot be established using fixtures and a two-account device check, mark scope migration BLOCKED and retain old data.

Mixed provider/local queues belong to their explicit provider or Guest creator context. Pure local queues belong to DeviceLocal. Local-track history always belongs to DeviceLocal; present it separately from account history.

### ADR-Q03: Persistence

Add library-state.db using SQLiteOpenHelper, WAL, parameterized SQL and transactions for detailed history, snapshots, scope registry and migration records. Keep offline-music.db and local collection preferences. Do not rewrite all persistence at once.

Boundaries: HistoryRepository, PlaybackSnapshotRepository, AccountScopeRegistry, LocalDataController. IO dispatchers own DB/serialization work; UI observes immutable state/flows.

### ADR-Q04: Account transition

Opening/canceling chooser does not switch active identity. A verified switch is serialized:
1. Capture outgoing scope; advance transition generation to invalidate stale work.
2. Pause provider/mixed playback, cancel crossfade/recovery, persist its paused queue under captured owner. Pure DeviceLocal playback may continue.
3. Cancel outgoing transfers/mutations at safe boundaries; pause queued work, retain completed files.
4. Commit incoming verified session; invalidate account caches/projections.
5. Bind incoming repositories and load its queue paused. If local playback continued, leave incoming queue available for explicit restoration rather than replacing local audio.
6. Schedule eligible incoming work; never autoplay.
7. Emit a sanitized transition event.

Sign-out uses the same policy to Guest and existing cookie removal. Preserve hidden old-account files until explicit deletion. On persistence failure pause safely, retain recoverable state and show an error; never assign old data to the new account.

### ADR-Q05: Solver safety

Media3 remains the player. Keep separate solver process. Bridge initialization failure must leave local/downloaded/ongoing audio functional and produce typed provider-resolution failure. Release must not silently execute crash-prone solving in the main process.

Inspect pinned dependency for a public injection seam. If absent, retain the contract-tested pinned reflection seam with fail-closed initialization, diagnostics and actual shrunk-release checks. A minimal upstream seam is a follow-up, not a mandate for a large fork. Do not claim reflection removed while it remains.

### ADR-Q06: Privacy controls

Settings -> Privacy and data:
Save listening history; History retention; Clear account listening history; Clear device listening history; Clear search history; Delete downloads; Delete local data for this account; Delete unassigned legacy data when present.

Retention: 30/90/365 days or Keep until cleared; default Keep until cleared for upgrades/new installs. Safety ceiling 100,000 history records per scope, disclosed in settings before enforcement. Search cap: 30 queries per scope. Appearance/audio/history-enable preferences remain device-wide and labeled.


## 5. Execution order and common completion requirements

Critical path:
Q00 baseline -> Q01 traceability -> Q02 test seams -> Q03 account identity -> Q04 migration -> Q05 controls -> Q05A local export/import -> Q05R interim release -> Q06 playback state -> Q07 recovery -> Q08 audio/solver -> Q08A loudness normalization -> Q09 downloads -> Q10 provider contracts -> Q10A provider canary/response -> Q11 architecture -> Q12 UX -> Q12A update notice -> Q13 accessibility -> Q14 performance -> Q15 security -> Q15A exit diagnostics -> Q16 CI -> Q17 docs -> Q18 release gates.

Lettered packages are specified in section 22. Q05A does not block Q05R; Q05R does not block Q06.

Q10 fixture preparation can start after Q02. Q13 resources and Q16 portability can begin after Q01 if they do not conflict with active files. Dependency order does not authorize leaving earlier work incomplete.

Each package finishes with code, targeted tests, affected integration tests, relevant documentation, criterion-by-criterion evidence and ledger update. Full offline regression runs occur at milestone boundaries and after broad refactoring. Do not repeat unrelated expensive checks after a trivial change.

## 6. Q00-Q02: Baseline and trustworthy testing

### Q00: Establish baseline

Targets: build configuration, source, current reports; new docs/quality/baseline.md.

Actions:
1. Resolve repository root and instructions; record revision and clean/dirty state. Preserve existing changes.
2. Compare code with docs/features.md. Mark each feature implemented, partially verified, unavailable or historical. Preserve implemented behavior.
3. Verify JDK 21, wrapper and SDK. Use configured paths and project-local fallback; never dump environment values.
4. Run current JVM suite, debug/release lint and assembly if execution is writable. Classify environment failures separately from source failures.
5. Record actual counts, skips, lint IDs, APK size, merged-manifest components and reports.
6. Audit tracked-path metadata/.gitignore for generated/sensitive artifacts without printing sensitive contents. Do not delete caches/artifacts as housekeeping.
7. Record baseline known failures and smallest fixes as isolated changes.

Acceptance: reproducible baseline commands and either valid results or explicit environment blockers; no lost user work; old artifacts never attributed to a new revision.

### Q01: Build identity and result schema

Targets: app/build.gradle.kts, PlaybackEventLog.kt, About UI, release/device scripts, CI.

Actions:
1. Add build metadata: version name/code, source commit, variant, dirty/local status. About shows short build ID; diagnostics show full revision. No Git -> local-unknown, never fake clean.
2. Default first quality candidate is 0.10.0-rc1 unless already used at execution. Code is one greater than highest known distributed code. Subsequent distributed candidates increment code and rc suffix; app ID/certificate remain unchanged.
3. Avoid compiled timestamps that defeat reproducibility. Evidence records timestamps separately. Do not promise byte-identical reproducibility before testing it.
4. Evidence root: artifacts/quality/<revision-or-local-id>/<run-id>/.
5. manifest.json fields: schemaVersion, revision, dirty flag, version/code, variant, toolchain, APK hash, certificate hash if signed, start/end UTC, device tier/API/model, fixture/live mode, commands, test/report paths and gate results. Public copies omit serials/account IDs.
6. Implement scripts/quality/collect-results.ps1 with a portable Python core if useful. Parse JUnit XML/instrumentation result structures. Reject process-crash/truncated/incomplete runs. Filenames such as final/accepted never determine success.
7. Keep failed runs and successful reruns with distinct IDs and explanation of intervening changes.

Acceptance: binaries are distinguishable; exact APK/source/results link together; incomplete logs cannot yield PASS.

### Q02: Deterministic test seams

Targets: AppContainer and meaningful constructor boundaries; test source sets.

Actions:
1. Inject wall/monotonic clocks, dispatchers, connectivity, storage capacity, provider transport and resolver. Keep production Android defaults.
2. Create FakeCatalog, FakeSession and ScriptedResolver with delayed/out-of-order responses, offline, 403/429/5xx, auth expiry, malformed payloads and expired streams.
3. Use bounded local HTTP fixture server for decoder tests. Test-only cleartext allowance must not enter production.
4. Generate original audio: distinct 30-second tones A/B, a 5-minute track, 5-second short track, stereo fixture and silence. Include deterministic timing metadata, provenance and test-only packaging.
5. Audit PlaybackGateway default no-op methods. Required production commands become abstract or explicitly return unsupported capability results; fakes implement deliberately.
6. Test controls/diagnostics are app-UID restricted and test-build-only. No arbitrary file/network execution.
7. Policy tests use fake time. Actual Media3 decoder tests use real monotonic time and bounded tolerances.

Acceptance: critical state/migration/playback tests require neither personal account nor public-provider availability.

## 7. Q03-Q05: Ownership, migration and user control

### Q03: Account/session coordinator

Targets: YouTubeMusicProvider, SignInFlow, AccountScreen, RiffApp, gateway/service and downloads.

Create AccountScope, VerifiedAccountIdentity, SessionToken(scope,generation), ScopeAliasRegistry and SessionCoordinator. SessionToken is an ownership marker, not an authentication credential. Active session is StateFlow.

Actions:
1. Implement ADR-Q02/Q04. Same-identity cookie rotation leaves ownership unchanged; chooser order does not change it.
2. Capture token before async operations; validate before UI publication, DB commit, queue apply and cache insertion.
3. Centralize switch/sign-out sequencing; remove screen-level duplicate cleanup once owned centrally.
4. Include scope/generation in account request coalescing, stream cache and projection keys.
5. Never hold UI/DB locks through network work. Serialize transitions; keep critical sections short.
6. Persist only verified aliases. Resolve bundle ownership through aliases rather than moving large trees unnecessarily.
7. Conflict rules: validated complete bundle beats queued metadata; manual pin wins; history deduplicates event IDs; newest valid paused snapshot wins. Never infer that all legacy roots belong to one identity.
8. Make account transition failures explicit; preserve a recoverable paused state.

Tests: A->B->A; A->Guest; failed/canceled chooser; missing identity; selector reorder; cookie rotation; delayed A result after B; switch during transfer/asset completion; pure local and mixed playback.

Acceptance: no cross-account flashes/writes; correct scoped paused restoration; unavailable identity never adopts data.

### Q04: History/snapshot persistence and safe migration

Create library-state.db version 1:

| Table | Required fields/indexes |
|---|---|
| history_events | scope, event_id, media_id, title, artist, artwork nullable, played_at, duration_ms nullable, music; PK(scope,event_id); index(scope,played_at DESC,event_id) |
| playback_snapshots | scope PK, payload_version, payload, updated_at |
| scope_generations | scope PK, history_epoch, snapshot_epoch, deletion_epoch |
| migration_runs | migration_id PK, source_fingerprint, state, imported_count, rejected_count, completed_at nullable |
| scope_aliases | opaque legacy_scope PK, canonical_scope, verification_version |

Use parameterized SQL/transactions. History page size 100 with keyset cursor (timestamp,event_id), stable descending ordering. Escape LIKE wildcards so user %/_ search is literal; retain accent-insensitive matching using normalized searchable fields.

Migration rules:
1. Shared history-v1.jsonl is ambiguous. Inventory/quarantine in app-private unassigned storage, never automatically import provider events into current account.
2. Valid unequivocal local events may migrate to DeviceLocal. Provider events stay unassigned.
3. Privacy offers explicit local import into current verified account, explaining mixed-account possibility; user confirmation is required to execute import. Offer delete-unassigned separately.
4. Old shared playback snapshot is quarantined and can be explicitly restored paused into a confirmed context. No first-upgrade autoplay or silent adoption.
5. Account-scoped collections/downloads retain data through verified aliases. No blanket reassignment.
6. Legacy event IDs derive deterministically from source fingerprint + line ordinal; identical repeated plays remain distinct, reruns do not duplicate.
7. Bound source/record size, skip malformed lines independently and record only counts/error classes. Unreadable source stays recoverable.
8. Insert data + migration marker in one transaction. Validate ownership/counts before retiring source. Simulate death before/after each checkpoint.
9. Read snapshot payload v1 for one compatibility release; write named/versioned v2 through repository. Validate ID types, safe content URIs, metadata bounds, 2,000-item cap, position/index/repeat; live position restores at zero.
10. Reconcile existing 6,000,000-byte service guard versus 3,000,000-character decoder guard. Introduce one shared 6 MiB UTF-8 byte ceiling for IPC/persistence plus field bounds. Preflight before sending; test multibyte metadata and 2,000 items.
11. Persist durable IDs only; resolve on explicit playback.

Tests: empty/corrupt/truncated/multibyte sources; repeated identical plays; mixed accounts; idempotent rerun; interrupted migration; transaction rollback; full disk; scoped alias conflict; 2,000-track snapshot; authored repeats; unknown payload version.

Acceptance: no ambiguous adoption or silent loss; restart-safe idempotency; paused restoration; no main-thread migration.

### Q05: Deletion and retention controls

Targets: SettingsScreen, History screen, SearchHistory and storage/download controllers.

Actions:
1. Implement ADR-Q06 with scope labels and clear counts. Account-history clear excludes DeviceLocal unless separately selected.
2. Confirmations explain scope, permanence and provider independence. Cancel changes nothing. Success updates UI; failure offers truthful Retry.
3. Save history off prevents new writes immediately, leaving existing records accessible until explicit clear.
4. Delete local account data includes scoped history/search/snapshot/local saves/downloads; preserves device settings and real MediaStore audio files. Sign-out remains separate.
5. Pause active provider scope before its deletion. Serialize clear/delete with writes.
6. History writer captures historyEpoch. Clear increments it transactionally; queued pre-clear writes are rejected. Fresh post-clear playback may record only with a fresh epoch. Apply corresponding protections to snapshots/download deletion.
7. Prune retention on IO after recording and once daily when app work executes. Inject wall time; no exact-scheduler-time promise.
8. Canonical-path-check recursive file removal against the expected app-owned root. Late transfer/artwork callbacks cannot recreate removed data.
9. Report partial deletion explicitly; do not claim success when filesystem removal failed.

Acceptance: cancel inert; clear cannot be reversed by queued work; scope-specific tests pass; no remote mutations; history deletion never requires Android Clear storage.

## 8. Q06-Q08: Playback and audio

### Q06: Typed state and command ordering

Targets: PlaybackGateway, service, protocol, queue/snapshot logic.

Define PlaybackStatus: Idle, Resolving, Buffering, Playing, Paused, Recovering, Ended, Failed.
PlaybackState includes status, desiredPlayWhenReady, queueRevision, selected queueItemId, owner scope, position/duration, typed error and recovery reason. Continuous position updates are separate from broad metadata state.

Actions:
1. Every queue entry has unique ID; video ID is not position identity. Preserve authored duplicate recordings.
2. Structural edits increment queue revision; user intents increment command sequence. Async apply validates scope, revision, entry and command sequence.
3. Main thread owns Media3 calls; IO owns JSON/database/file work. Serialize queue apply so an older slow request cannot overwrite the newest.
4. Pause during resolution/recovery clears autoplay intent. Skip cancels obsolete work.
5. Remove current -> following survivor, otherwise preceding; remove final -> empty paused state. Preserve explicit repeat/shuffle behavior.
6. Keep stop-on-removal-from-recents and paused process-restart restoration.
7. Retain approximate 5-second routine snapshot cadence plus meaningful lifecycle/queue saves; last-sequence-wins.
8. Lifecycle saves need actual bounded durable IO handoff/transaction. Do not claim SharedPreferences.apply is a synchronous durability guarantee.
9. Do not resolve every queue stream before first playback.

Acceptance: race tests prove latest intent wins; selected metadata matches entry; restored position within 1 second of last durable checkpoint; unexpected-kill loss bounded by cadence plus measured scheduling tolerance.

### Q07: Unified recovery coordinator

Extract RecoveryCoordinator/RecoveryPolicy to replace independently multiplying stream/network/live retries.

Typed classifications: Offline, Timeout, RejectedStream, AttestationExpired, SessionExpired, RateLimited, ProviderUnavailable, SolverUnavailable, UnsupportedMedia, LocalFileMissing.

Policy:
- Offline waits for validated connectivity stable for 500ms; retains position/intent. Pause while waiting prevents resume.
- Rejected/attestation failures invalidate relevant stream/attestation and exclude rejected profiles.
- At most three automatic resolution attempts per selected entry per episode; delays 1s then 3s, bounded injected jitter; active online attempt episode <=60s. Offline wait is separate.
- Retry-After honored up to 60s; longer waits become an actionable retry state.
- Session expiry stops automatic authenticated retries and offers Sign in again. Successful sign-in alone does not autoplay.
- Missing local bundle/file is storage failure with optional redownload; partial cache never advertised as a download.
- Automatic recommendation queues may skip at most three consecutive unavailable entries. Authored collections stop with Retry/Skip.
- All retry paths share one episode budget. Events carry class/attempt/timing, never raw provider exception text.

Tests: every classification; exhaustion; connectivity flaps; pause/skip/switch during retry; obsolete completion; missing bundle; no simultaneous duplicated recovery.

Acceptance: no retry storm/unwanted playback; deterministic faults pass; physical handover/drop checks preserve intent and position.

### Q08: Crossfade, volume, focus and solver

Preserve current equal-power design and visual alignment. Do not rewrite DSP unnecessarily.

Actions:
1. One owner holds outgoing/incoming entry IDs, progress, duration and cancellation; tail player/effects always released.
2. Cover 1/6/12s, short tracks, delayed startup, each renderer buffering, early outgoing end, skip/select/pause/seek/off, repeat-one, sleep timer, account switch, focus/noisy-route changes.
3. Test-only sampled gains finite/in range; squared normalized gains sum near one while both participate, absolute tolerance 0.03. Explicitly specify early-end exceptions.
4. Progress never decreases. Nonbuffering fixture duration error <=max(150ms,5%). Buffering may extend wall time, never compress fade.
5. Cancellation restores owned volume exactly once without overriding sleep fade.
6. Full/mini/queue visuals follow live blend. Reduced motion swaps visuals immediately while audio may still blend.
7. Test boost off/default/max with generated PCM for finite bounded samples; document clipping/limiting. Never claim equal-power fade normalizes different mastering.
8. Contract-test pinned cipher dependency plus shrunk release. Bridge failure becomes SolverUnavailable; injected solver death leaves existing audio intact with bounded reconnect.
9. Follow Android/Media3 contracts for focus, ducking, calls, headphone unplug and Bluetooth route changes; verify on device.

Acceptance: all targeted/device cases pass; no leaked tails, gain jumps or solver crash propagation to playback process.


## 9. Q09-Q10: Download and provider reliability

### Q09: Download lifecycle and integrity

Targets: OfflineMusicStore, OfflineDownloadEngine, OfflineDownloadService, OfflineMediaSource and DownloadsScreen.
Extract DownloadCoordinator, BundleValidator and NetworkStoragePolicy where they own meaningful behavior.

States: Metadata, Queued, Resolving, Transferring, Validating, Assets, Complete, Paused, Failed, Cancelled. Decode existing DB strings strictly with safe unknown handling; migrate without removing existing rows.

Actions:
1. Each transfer captures scope, session generation and per-item deletion generation. Pause/cancel/remove invalidates only relevant work. Validate before writes/commit and late asset updates.
2. Audio readiness is core success; artwork/lyrics have separate statuses. Missing assets do not invalidate sound audio; failed asset writes never count as success.
3. Temporary source -> validated remux -> synchronized output -> same-directory atomic rename -> atomic ready marker -> DB complete. Implement startup reconciliation for every crash boundary.
4. Existing ready marker and DB compatibility remains readable. Never treat metadata-only rows as playable.
5. Reserve in-flight bytes including source + assembled output + assets. Recheck free space while transferring and before remux. Unlimited removes configured app budget, not device reserve.
6. Keep current fresh-stream restart strategy unless resume safety is independently proved. Never append duplicate SABR initialization.
7. Bulk download uses full loaded collection, deduplicates IDs and preserves completed bundles. Incomplete pagination disables promises of complete collection download.
8. Delete-all cancels current and pending scoped transfers. Smart Downloads respects user exclusions so it does not immediately recreate removed songs.
9. Account switches pause outgoing transfers without deleting completed bundles. Reactivation requires active matching scope and allowed conditions.
10. Job/service startup, timeout and repeated scheduling are idempotent. Validate OS foreground-service restrictions on supported APIs. Periodic six-hour work is best effort.
11. Existing manual pins survive disabling automatic work or rolling smart selection. Pause and retry labels describe whether transfer restarts or resumes.
12. Network/battery/storage policy changes are checked during active automatic work. Manual behavior remains documented and independently tested.
13. Bundle validation uses format/duration/length consistency and complete-marker state; integrity failures produce redownload action, not decoder crash loops.

Acceptance tests: kill in each phase; 403 mid-transfer; wrong range; missing/wrong length; corrupt container; full disk during transfer/remux/commit; delete-all during assets; account switch; duplicate bulk; offline restart/seek; mobile policy change; Smart Downloads disabled; manual pin retention; scoped deletion. Complete fixtures play with zero provider calls.

### Q10: Provider contracts and capabilities

Targets: provider/catalog/session/library/stream interfaces and parser fixtures.

Actions:
1. Separate interfaces for catalog, session, mutations and resolver. Keep pinned integration until a focused change has evidence.
2. Sanitized minimal fixtures cover each search kind, Home shelf, album, playlist continuation, owned/read-only playlist, sort menu, account menu, official-video pairing, lyrics and error envelope.
3. Strip credentials/account IDs/signed URLs while preserving parser semantics. Do not store personal complete responses.
4. Test unknown renderers, missing/wrong fields, empty arrays, nested HTTP-200 errors, repeated tokens and cyclic pagination.
5. Enforce 2,000-track cap and seen-token set. Truncation is labeled; full-collection actions disabled until genuinely complete.
6. Preserve provider opaque sort params and strict mutation allowlists. Do not infer unsupported dates/votes. Cover provider sort preferences separately from local display sort.
7. Mutations require explicit acknowledgment and captured generation. Unknown outcome is reconciled before retry; never blindly retry a non-idempotent edit.
8. Batch edits retain per-destination success/failure and retry only unresolved selections.
9. ProviderCapabilities drives actions: available, unavailable-with-reason, or loading. Unsupported video/lyrics/editing cannot break independent audio/local features.
10. Document cache expiry/invalidation for session changes, edits and rejected streams. Stale in-flight results cannot repopulate invalidated generations.
11. Maintain docs/quality/provider-compatibility.md: exact dependency, assumptions, fixture matrix, last live result and outage triage.
12. Failure UX differentiates region/unavailable/auth/network/provider faults when evidence supports classification; otherwise use honest generic provider-unavailable wording.

Acceptance: deterministic contracts pass; dedicated-account checks cover browse/search/collection/playback/video if available and acknowledged disposable edits. Provider outage blocks live gate only; it never converts fixture results to live verification.

## 10. Q11-Q12: Architecture and experience

### Q11: Responsibility extraction and readability

Perform after scoped behavior/state tests exist. Keep Android entry wrappers stable.

Actions:
1. AppContainer owns process-scoped repositories/clients. Service owns playback jobs/players; activity owns screen-only effects. Document ownership and close/cancel rules.
2. Split provider into SessionManager, CatalogRepository, ProviderLibraryRepository and StreamResolver with a temporary migration facade.
3. Split service into QueueCoordinator, RecoveryCoordinator, PlaybackPersistence and AudioTransitionCoordinator. Service retains Media3 callback/lifecycle wiring.
4. Split RiffApp/ShellScene into typed navigation, screen host and player presentation. Position/drag state must not invalidate unrelated screens every frame.
5. Typed routes: Home/Podcasts/Radio/Library/Search roots plus Account, DeviceAudio, RemoteLibrary(kind), Settings(section), Downloads, Collection(id,title), Artist(id) as actually supported. Do not add routes absent from product. Validate route arguments and bound saved stacks.
6. Replace positional saved list with named/versioned ShellSavedState. Read old state for compatibility, discard invalid routes safely, preserve collection trail/tab state. Never restore credentials or raw streams.
7. Review default no-op APIs and dead prototypes using reference search; remove only after proving unused in production/test/build packaging.
8. Add compatible pinned formatter and light static analysis. Mechanical formatting is separate. No blanket suppression to force green.
9. Audit cancellation: rethrow parent cancellation; session-refresh cancellation becomes explicit retryable error only when parent remains active. Lifecycle-aware flow collection; no blocking main-thread Binder/network.
10. Avoid generic repositories/use cases that merely forward calls. Extract real decisions and side effects.
11. Add dependency direction check where practical: domain policies do not import UI; UI does not directly write database files or mint provider tokens.

Acceptance: existing assertions retained; entry points resolve; all regression lanes pass; unrelated responsibilities removed from large classes. File-size reduction alone does not establish architectural success.

### Q12: Core journey polish

Preserve artwork-first identity and controls. Centralize typography/spacing/radius/contrast/motion tokens. Use fixed fixture content for visual comparisons.

| Journey | Required result |
|---|---|
| UX01 First launch/sign-in | Useful signed-out state; optional local audio; cancellation/retry; no endless spinner |
| UX02 Search/play/queue | Clear query, accurate selected row and loading state, stable Back behavior |
| UX03 Filter/sort collection/play | Correct authored queue position and repeated-entry identity |
| UX04 Download/restart offline/play | Truthful phases, complete readiness, seek, no extraction |
| UX05 Mini/full/queue/thumbnail/full/Home | Consistent gestures/Back and artwork ownership |
| UX06 Lyrics/timing/translation | +0.2s default/reset retained; plain/timed/none clear; cancellation safe |
| UX07 Account switch | No cross-account flash; outgoing provider audio pauses |
| UX08 Cache/history/download deletion | Clear scope/consequence; cancel inert; success/failure truthful |
| UX09 Network/session error | Context-appropriate action; preserved intent/cached content |
| UX10 Widgets/lock screen/timer | Actual selected-track state; service/UI deadline agreement |

Actions:
1. Distinct loading/empty/error/offline/permission/session-expired states. An exception does not automatically become an empty list.
2. Fixed skeleton dimensions and stable artwork fallback; no layout jumping on image failure.
3. Long titles ellipsize in constrained rows; full title accessible in expanded detail. No endless marquee by default.
4. Seek/volume expose current value and adjustable semantics; seeking unavailable for live content with explanation.
5. Preserve tab scroll/state, reset only for established double-tap/explicit navigation. Nested Back returns correct collection.
6. Dialog dismissal restores invoking focus; persistent errors remain visible beyond transient snackbar. Essential transport not obscured.
7. Confirm actions consistently; destructive button wording names operation. Avoid implementation details in product text.
8. Distinguish manual downloads from playback cache and automatic downloads without requiring users to understand transport.
9. Artwork/text transition has one ownership path. Queue/player swaps cannot leave duplicated visible labels or invisible clickable layers.
10. Use screenshot baselines for empty/loading/offline/error/playing/paused/large-text states with synthetic metadata, not changing live art.

Acceptance: all UX journeys have decision-level Compose tests plus physical-device walkthrough. No new flow promises unsupported behavior.

## 11. Q13: Accessibility and localization

Targets: all screens/components, resources/widgets/dialogs, motion/glass policy.

Actions:
1. Externalize strings/plurals/formats/accessibility descriptions. Default English plus pseudo-localized expansion and RTL checks; no fabricated production translations.
2. Essential touch targets >=48dp or non-overlapping equivalent expansion. Measure hit regions, not icon dimensions.
3. Contrast targets: ordinary text 4.5:1, large text/essential controls 3:1. Test bright/dark/busy artwork fixtures; apply reliable scrims/solid fallback. Record method/colors; do not claim formal certification from screenshots.
4. Icon labels describe purpose. Favorite exposes state; tabs expose selection; queue exposes position/action; sliders expose value/range. Decorative duplicates excluded.
5. Queue reorder supports Move up/Move down/Play next in addition to drag/swipe.
6. Font scales 1.0/1.3/1.6/2.0, 360dp narrow portrait, landscape, keyboard open, large windows. Controls reachable, dialogs scroll when necessary, labels not covering actions.
7. RTL respects logical navigation/layout with media controls verified intentionally. Fix accidental left/right padding assumptions.
8. Reduced motion honors system animator settings and app preference. Suppress lens/morph spatial motion; preserve functionality/audible crossfade.
9. Haptics honor system/in-app preferences and API-safe constants. Add old-API fallback for threshold cues.
10. TalkBack focus predictable during expansion, queue changes, dialogs/errors. Routine progress never announces continuously.
11. Keyboard Enter/Space, Tab/Shift-Tab and Back/Escape work where platform appropriate; no focus traps.

Acceptance: TalkBack UX01-UX10 recording/checklist, keyboard walkthrough, automated semantics and large-text/RTL screenshots. Missing physical assistive-tech check is BLOCKED.

## 12. Q14: Performance and resources

Add :benchmark test module using compatible official Android Macrobenchmark tooling. Target a nondebuggable release-equivalent benchmark variant with distinct test package and fixture injection absent from production. Pin versions after checking official compatibility; do not guess latest releases.

Tiers:
- R: iQOO I2217 or comparable physical ARM64 device at fixed 60Hz and supported high refresh.
- L: physical Android 12+ device with <=4GB RAM and 60Hz.
- Functional API matrix: 31,33,36; optional API37 runtime smoke if available. Minimum API31 mandatory.
Emulator functional checks do not satisfy physical battery/performance gates.

Baseline before optimization; proposed release budgets:

| Metric | R | L | Protocol |
|---|---|---|---|
| Cold first usable shell p95 | <=1.2s | <=2.0s | 20 cold Macrobenchmark launches; fixture feed |
| Player/queue action to responsive visible state p95 | <=150ms | <=250ms | 30 fixture actions; animation duration separate |
| 60Hz missed-deadline frames | <=2% | <=5% | 5 identical warmed loops; deadline-aware metrics |
| High-refresh missed deadlines | <=3% | n/a | recorded refresh/fixture; no claim from refresh request alone |
| 2,000-entry queue prep p95 | <=2s | <=4s | 10 runs; IO/main phases separated |
| Complete local bundle first audio p95 | <=500ms | <=800ms | 20 starts; documented audio-ready proxy |
| Controlled online first audio p95 | <=3s | <=5s | fixture server 100ms latency/5Mbps |
| Standard release APK | <=40MiB | <=40MiB | exact ABI/build reported |
| App-owned main-thread network | zero | zero | runtime attribution/source |
| Avoidable warmed-journey main-thread disk | zero | zero | framework noise separately justified |
| Retained memory growth | <=max(10%,20MiB) | same | stabilized baseline; 30min loop+5min idle; 3 runs |

Battery: three two-hour screen-off local-playback runs, fixed route/volume, battery/temperature/network recorded. Compare default candidate with baseline, downloads/crossfade disabled for base measurement. Median drain regression <=10% relative only when measurement resolution is sufficient. Otherwise BLOCKED for measurement inadequacy, not a meaningless pass.

Soaks: four-hour live-provider playback and two-hour offline playback, zero app crashes/ANRs, no unexplained sustained growth. Provider outage classified separately. Full live run required for live gate.

Optimization rules:
1. Profile FrameTimeline/Perfetto, recomposition, shader/offscreen work, decoding, SQLite and retained players before changing.
2. Cache effect construction; read animated values in narrow layer/draw phases; bound image resolution/cache by tier.
3. Release offscreen video/tail players/translators/recognizers; process clients intentionally bounded. No high-refresh request in background.
4. Lite remains reduced-motion/solid/smaller-artwork by default; verify separately.
5. If adaptive rendering needed, expose Performance Auto/Full/Reduced. Auto chooses a stable capability tier; no oscillating per-frame fallback and no silent override of explicit user choice.
6. Report live-provider startup separately from controlled transport timings. Never hide provider latency inside an offline benchmark.
7. Any failed budget requires optimization or explicit product-level waiver. AI cannot self-waive and award full credit.
8. Ship a Baseline Profile. Generate it from the :benchmark module with the official Baseline Profile tooling, pinned after checking compatibility with the existing AGP/Gradle versions. Cover cold start to usable shell, mini/full player expansion, queue open and one collection scroll, using fixture content only. Commit the generated profile as source and regenerate it when those journeys change materially. Verify the release APK actually packages the profile, and report startup and frame metrics with and without it using Macrobenchmark compilation modes. Generation needs a suitable emulator or device; without one, mark profile generation BLOCKED with rerun steps and do not claim its benefit. The 40 MiB ceiling still applies.

Acceptance: distributions/traces/environment/build identity for both physical tiers; no sustained-120fps claim from display scheduling alone.

## 13. Q15: Security, diagnostics and privacy

Targets: event logging, WebView/session, service boundaries, storage, release build.

Actions:
1. Typed DiagnosticEvent with allowlisted fields; sanitize before persistence/logcat, plus export scrubber. Keep 200-event ring and field-length bounds.
2. Low-cardinality phase/reason/attempt fields only. Exclude song titles, opaque scope IDs, credentials and raw exception messages by default.
3. Property/negative tests for URLs, cookie/auth headers, signatures/token fields, line breaks, malformed Unicode and oversized values.
4. WebView host/scheme allowlists; file access disabled; approved picker access only; no arbitrary JS bridges. TLS errors never proceed. Intent fallback validates HTTPS destination and never executes arbitrary intent.
5. Media session exported access reviewed contextually. App-only custom commands reject foreign UID/package; ordinary legitimate transport remains available. Do not blindly unexport service to silence lint.
6. Validate local content URIs and paths. Revoked MediaStore permission produces recoverable error.
7. Inspect merged manifest/APK for backup/transfer disabled, production cleartext disabled, nondebuggable release, no stress/fixture diagnostics.
8. Signing key outside Git/CI; preserve certificate pin/check. No general arbitrary-file diagnostic export; the fixed-content report in Q15A and the user data file in Q05A are the only exports.
9. Review lint evidence: Cursor use and application-context singleton warnings may be false positives. Local explanation only; no blanket suppression.
10. Add wrapper checksum from official release and reviewed dependency verification/locking where supported. Hash collection alone is not provenance.
11. Pin CI actions to reviewed commits; least-privilege workflow permissions; do not expose secrets to untrusted PR code.
12. Produce docs/quality/security-review.md with boundaries, attack cases, results and unresolved limitations. This is engineering evidence, not certification.

Acceptance: SEC tests and release-content checks pass; no unresolved high-severity issue; no secret in stored/exported event fixtures.


## 14. Q16: CI and portable tooling

Existing build commands from project root (Unix: replace .\gradlew.bat with ./gradlew):

~~~powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --console=plain
.\gradlew.bat :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress --console=plain
~~~

These are builds, not device-install permission. Existing run-ui-regression.ps1 installs APKs; build-release-candidate.ps1 uses local signing and verifies certificate. Preserve protections.

Required lanes:
1. Deterministic PR: format/static analysis, JVM/Compose, release lint, unsigned shrunk release, merged-manifest check, 40MiB ceiling.
2. Emulator: fixture smoke/migrations/account transitions/UI on API31/33/36, no personal Google credentials.
3. Explicit physical/live lane: authorized test device/account; credentials local; sanitized evidence only.
4. Benchmarks: separately invoked physical R/L runs; hosted emulator timings cannot substitute.
5. Packaging/upgrade: source archive contents, signature/identity, exact-artifact manifest and legacy-fixture upgrade.

Tooling:
- scripts/quality/verify.ps1 parameters: selected checks, serial, variant, evidence directory, fixture/live mode, explicit installation opt-in. Auto-select only exactly one authorized device; multiple require serial.
- Resolve SDK from environment/local properties then documented local fallback; paths from script root.
- Parameterize/archive download_favourites.py hardcoded user/device paths; no supported tooling with personal absolute paths.
- Check exit codes AND complete structured test results. INSTRUMENTATION_CODE:-1 alone is not success; process-crash result invalidates run.
- Upload sanitized summaries on success/failure; short bounded raw-artifact retention. Never upload device app backups, signing files or account exports.
- Include codex/** branch checks or require PR checks consistently; cancel superseded builds; never overlap mutable device/account tests.
- Result schema missing required gate -> FAIL/BLOCKED, never default zero.
- Read-only verification and mutation/install modes separated visibly.
- Record exact test APK/app APK hashes and build pairing; mismatched instrumentation pairing fails preflight.

Acceptance: clean-checkout deterministic lane works on Windows/Linux where SDK/dependencies exist; no personal paths/secrets needed; incomplete instrumentation rejected.

## 15. Q17: Current documentation and packaging

Update:
- README: actual available release, current features, API/ABI, prerequisites and commands.
- docs/features.md: implemented behavior/limits/capabilities.
- docs/privacy.md: account/Guest/DeviceLocal, legacy quarantine/import, deletion/retention, SDK metrics, update check request, exit-reason records, export file contents.
- docs/quality/architecture.md: final package/ownership/lifetime diagram.
- docs/quality/testing.md: lanes, fixtures, commands, hardware and reruns.
- docs/quality/performance.md: protocols, distributions, budgets/limits.
- docs/quality/provider-compatibility.md and security-review.md.
- baseline.md, execution-ledger.md, ADRs and final-report.md.
- dependency-license-inventory.md/CSV: regenerate final graph, remove absent-demo/generator claims, asset/code provenance. Metadata inventory is not legal certification.
- CHANGELOG/release notes: user-visible improvements, migration behavior, limitations.

Keep historical plans/reports labeled historical. Add a pointer to the current plan rather than rewriting old results.

Package candidate APK, matching source, license/notices, SHA256SUMS, build metadata, sanitized validation summary; retain matching R8 mapping privately. Exclude toolchains/caches/backups/credentials. Source includes new quality scripts and necessary build files. Check archive contents and build extracted source in separate temporary directory when resources permit.

Acceptance: documented behavior matches code; archive corresponds to candidate; upgrade certificate matches; availability language distinguishes prepared from published. Publication requires explicit authorization.

## 16. Required acceptance matrix

Map each case to a named executable test or precise manual protocol in ledger. Assertions target behavior/invariants, not duplicated implementation expressions.

| ID | Scenario | Expected result |
|---|---|---|
| ACC01 | A result completes after A->B | No B UI/state/write mutation |
| ACC02 | A->B->A | Only A records/downloads/paused queue restored |
| ACC03 | Cookie rotation same identity | Same scope, no duplicate migration |
| ACC04 | Selector order changes | Stable canonical identity |
| ACC05 | Canceled/failed chooser | Original session/playback unchanged |
| ACC06 | Missing stable ID | No adoption; clear unverified state |
| ACC07 | Pure local vs mixed playback on switch | Local continues; mixed/provider pauses |
| DAT01 | Clear while write queued | Pre-clear write rejected |
| DAT02 | Delete during transfer/assets | No late resurrection; partial failure truthful |
| DAT03 | Kill migration at checkpoints | Restart-safe, idempotent, source preserved |
| DAT04 | Ambiguous legacy data | Quarantined pending explicit action |
| DAT05 | Retention/time change | Correct scoped cutoff, future-time anomaly safe |
| DAT06 | Full disk migration/snapshot | Recoverable prior data; accurate error |
| DAT07 | Clear disabled history | Existing data removed only explicitly |
| PLY01 | Pause before resolution completes | No autoplay |
| PLY02 | Rapid skips/queue replacement | Newest command wins |
| PLY03 | Wi-Fi/mobile handover | Position/intent retained |
| PLY04 | Offline60s then online | Resume only if intent remained play |
| PLY05 | Rejected/expired stream | Bounded fresh resolve; actionable exhaustion |
| PLY06 | Solver death | Existing audio survives; bounded reconnect |
| PLY07 | Main-process death | Correct owner queue restored paused |
| PLY08 | Focus/call/noisy route | Platform behavior; user pause wins |
| PLY09 | Unknown outcome mutation | Reconcile before potentially duplicate retry |
| QUE01 | Authored duplicate songs | Unique entries survive move/remove/restore |
| QUE02 | Filter/sort then play | Correct original position/order |
| QUE03 | Remove final item | Empty paused UI/session/widgets |
| QUE04 | 2,000-entry UTF-8 snapshot | Shared bounds; valid roundtrip or preflight rejection |
| XFD01 | Blend1/6/12s | Gradual valid equal-power gain/progress |
| XFD02 | Buffer/start delay/early end | No compressed/backward fade or restore jump |
| XFD03 | Pause/seek/skip/off/switch | Cancel correctly; release tail |
| XFD04 | Boost/sleep | No conflicting gain owners; finite output |
| DLD01 | Kill each transfer commit phase | Reconcile; no false complete |
| DLD02 | Bad range/403/truncate/full disk | Bounded retry; cleanup; truthful status |
| DLD03 | Offline bundle restart/seek | No provider requests |
| DLD04 | Clear playback cache | Bundles retained |
| DLD05 | Smart policy changes | Network/battery/storage/playback honored |
| DLD06 | Bulk duplicates/manual pins | Completed files/pins preserved |
| CAT01 | Unknown/malformed renderer | Typed safe error/partial state |
| CAT02 | Continuation loop/cap | Bounded stop; incomplete label |
| CAT03 | HTTP200 rejected edit | No false success |
| CAT04 | Provider native sort | Full collection/selection correct; test preference restored |
| LYR01 | Word/line/plain/none | Honest labels and timing hierarchy |
| LYR02 | Translation failure/cancel | Original timed lyrics usable |
| A11Y01 | TalkBack/keyboard | Named actions, sensible focus, no gesture-only core action |
| A11Y02 | 200%text/RTL/narrow/landscape | Essential content/actions reachable |
| A11Y03 | Motion/contrast settings | Consistent rendering and functionality |
| SEC01 | Host lookalike/intent/TLS | Safe rejection; no arbitrary execution |
| SEC02 | Foreign custom command | Denied; legitimate media transport usable |
| SEC03 | Adversarial diagnostics | No credentials/URLs/IDs stored/exported |
| REL01 | Candidate APK | Correct cert; nondebuggable; no fixtures/stress |
| REL02 | In-place old-data upgrade | Settings/downloads retained; safe migration |
| REL03 | Evidence mismatch | Gate rejects hash/revision mismatch |
| REL04 | Interim release R1 | Required subset passes on exact artifact; unverified gates disclosed |
| EXP01 | Export then import on clean install | Settings/collections/history restored; no credentials or streams in file |
| EXP02 | Import mismatched/corrupt/newer/oversized file | No silent adoption; rejected safely; existing data unchanged |
| AUD01 | Normalization on, tracks of different loudness | Bounded attenuation applied; no clipping; crossfade/boost/sleep ownership intact |
| AUD02 | Normalization off or value missing | Unchanged gain; no failure |
| OPS01 | Canary with healthy/broken/blocked provider | Correct classification; block or outage never reported as app breakage |
| UPD01 | Newer/same/older/malformed/unreachable feed | Notice only for newer; silent on failure; no download or install |
| UPD02 | Update check disabled | Zero requests |
| DIAG01 | Crash/ANR/low-memory exit then relaunch | Sanitized reason recorded; no messages, titles, URLs or IDs |
| PERF01 | Release APK with Baseline Profile | Profile packaged; measured with/without comparison recorded |

Test naming: <Subsystem><Behavior>Test for JVM; <Subsystem>AcceptanceInstrumentation for device. Extend existing meaningful suites where suitable. Do not delete failing assertions to make refactoring pass.

### 16.1 Fixture detail and test expectations

Account fixture A/B have distinct stable IDs but may exchange selector positions; C lacks stable identity. Raw fixture IDs are synthetic and never copied from live accounts. Delayed A jobs are released only after B activation to prove rejection.

Queue fixtures:
- authored [A,B,A,C], selected second A, then filter A and play second occurrence;
- 2,000 entries with Unicode titles/accents and bounded long metadata;
- recommendation queue with audio/video equivalent plus distinct remix;
- pure local and mixed queues with revoked MediaStore permission.

Download fixtures:
- complete audio; truncated last segment; wrong Content-Range; changed stream length; expired URL; missing art; failed lyrics; missing readiness marker; marker with wrong length; DB complete but missing audio; audio ready but DB assets.
For each startup reconciliation, specify whether to retain ready audio, restart transfer, remove only temp data or expose retry. Never delete validated audio to resolve a metadata mismatch without cause.

History fixtures:
- 0/1/101/100,001 rows; identical timestamps; repeated identical plays; literal percent/underscore; accents; malformed lines; future date; obsolete scope alias.
Retention test freezes time and proves exact boundary handling: delete played_at < cutoff, preserve equality. Safety cap keeps newest100,000 deterministically by timestamp/event ID. Pagination must not duplicate/skip ties.

State tests use reproducible seeded ordering. Assert at the public repository/gateway boundary plus persisted state after restart. Stress runs retain seed/build ID and failing sequence.

## 17. Device protocol, milestones and interruption rules

### 17.1 Device safety and evidence

Use dedicated test device/profile/account. User's primary data is never a fixture. Backups are private, authorized and uncommitted. Do not bypass lock screens or enter credentials without scope authorization.

Preflight record: app/test APK hashes, revision/variant, API/model, refresh, orientation/font scale, network/output route, battery/temperature and fixture/live mode. Clock changes recorded.

Sequence:
1. Install authorized distinct test package or use authorized compatible signing path.
2. Generate/migrate fixtures; run ACC/DAT/QUE.
3. Run PLY/XFD/DLD; then UX01-UX10.
4. Run focused accessibility configurations.
5. Warm/stabilize then performance; do not include install/model downloads in UI metrics.
6. Offline/provider soaks separately.
7. Restore font/rotation/network/battery simulation/pinning/refresh/provider test preferences in finally.
8. If restoration fails, immediately report exact remaining changes.
9. Regular release smoke uses exact signed artifact. Stress tests supplement, never prove shrinker behavior.

Live sample: at least12 varied available tracks, two authored collections including continuations/repeats, one podcast and one available live stream; official video if supported; failed item; network handover. External unavailability is recorded, not passed. Disposable edits require authorization and acknowledgment.

### 17.2 Milestones

| Milestone | Packages | Required deliverable |
|---|---|---|
| M0 | Q00-Q02 | Baseline/build identity/deterministic harness |
| M1 | Q03-Q05, Q05A | Scope isolation/migration/privacy controls/local export |
| R1 | Q05R | Interim release of M1 on a reduced, disclosed gate set |
| M2 | Q06-Q08, Q08A | Playback/recovery/audio/solver evidence/loudness option |
| M3 | Q09-Q10, Q10A | Download integrity/provider contracts/canary and response runbook |
| M4 | Q11-Q13, Q12A | Architecture/UX/accessibility/update notice |
| M5 | Q14-Q16, Q15A | Physical budgets/Baseline Profile/security/exit diagnostics/CI |
| M6 | Q17-Q18 | Exact-artifact candidate/final gates |

No deadline is promised. Estimate packages after baseline and first slice. Do not truncate verification to fit guessed schedule.

Stop risky action if migration misattributes/destroys data, test needs personal account mutation, certificate differs, installation requires uninstalling main app, secret appears, device settings cannot be restored, or checkout identity is unknown. Preserve state, report and continue independent work. Routine already-authorized reversible steps do not need repeated confirmation.

## 18. Q18: Release gates and scorecard

Run on exact candidate. Code/dependency/build changes invalidate affected evidence. Docs-only corrections do not require audio reruns, but packaged source/hash identity must remain accurate.

| Gate | Mandatory criterion |
|---|---|
| G01 | Every package complete; required cases mapped; no unknown statuses |
| G02 | Deterministic suite passes; zero unexpected errors/failures/skips |
| G03 | Scope/delete/migration/durability interruptions/full-disk cases pass |
| G04 | Controlled playback/recovery/audio/download checks pass |
| G05 | API31/33/36 functional; R/L physical budgets verified |
| G06 | TalkBack/keyboard/large text/reduced motion evidence |
| G07 | No unresolved high-severity security issue or secret/test entry leak |
| G08 | Lint zero errors; every warning triaged, no blanket suppression |
| G09 | Soaks no crash/ANR/unexplained sustained memory growth |
| G10 | Unique version, correct signature/ABI/size/source/upgrade |
| G11 | Current docs/inventory and disclosed limitations |
| G12 | Device state restored; evidence complete; no unauthorized publication/mutation |

Intentional platform skip needs rationale and alternate evidence; required coverage absent -> BLOCKED. External failure never becomes a successful local feature test. A waiver must be an explicit human product decision and clearly prevents claiming full compliance with this plan.

Scorecard100 points: correctness/account/data20; playback/recovery/download20; UX/visual15; accessibility/localization10; performance10; architecture10; security/privacy5; testing/release5; docs/onboarding5.

Full credit requires linked evidence. Scores cannot offset failed privacy/data/crash/signature/secret gates. Describe meeting this quality standard, never universal perfection/provider guarantees.

## 19. Required final handoff

Human summary plus docs/quality/final-report.md:
1. Revision/dirty state/version/code/APK hash/certificate/source hash.
2. Completed packages and changed subsystem summary.
3. Every invariant/gate PASS/FAIL/BLOCKED with evidence.
4. Counts/skips/lint triage/devices/performance distributions.
5. Migration, retained legacy data, deletion and retention semantics.
6. Known provider/reflection/hardware limitations and recovery instructions.
7. Remaining work and exact next steps, never obscured by numeric score.
8. Restored device/account test state or exact remaining changes.
9. Upgrade/install procedure and prepared-versus-published distinction.

### Copyable executor prompt

Read docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md completely and applicable repository instructions. Implement its packages in dependency order, preserving current work and product invariants. Start at Q00 and update docs/quality/execution-ledger.md. Use fixed decisions/defaults for routine choices. Do not present saved reports as fresh runs. Do not publish, delete personal data, mutate personal provider accounts or install over the user's main app without authorization. Complete independent work when prerequisites are absent; mark affected gates BLOCKED with exact rerun steps. Continue until all authorized implementation/verification is finished, then deliver section19 final report.


## 20. Detailed contracts and edge-case decisions

This section resolves implementation details that should not be left to ad hoc screen logic. If a statement here adds precision to an earlier package, use this more specific rule. Do not use it to weaken a mandatory gate.

### 20.1 Ownership token and commit barriers

Suggested Kotlin contract shapes (adapt names/packages without changing semantics):

~~~kotlin
sealed interface AccountScope {
    data object Guest : AccountScope
    data object DeviceLocal : AccountScope
    data class Provider(val opaqueId: String) : AccountScope
}
data class SessionToken(val scope: AccountScope, val generation: Long)
data class WriteToken(
    val scope: AccountScope,
    val sessionGeneration: Long,
    val historyEpoch: Long,
    val deletionEpoch: Long,
)
data class HistoryCursor(val playedAt: Long, val eventId: String)
sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>
    data class Rejected(val reason: RejectionReason) : OperationResult<Nothing>
    data class Failure(val error: AppError) : OperationResult<Nothing>
}
~~~

Rejection reasons include StaleSession, ClearedData, Cancelled, InvalidInput and UnsupportedCapability. Rejected stale work is not a visible provider error for the new account.

Session generation is process-local monotonic state; persistence/deletion epochs are durable. On restart issue a new process/session identity so an old IPC callback cannot be mistaken for current work. If a generation is compared across processes, include a random process/session nonce and compare the full token, not a reset integer alone.

Repository commit guard must run within the same transaction/serialized critical section as the write or deletion. A check before dispatching IO is insufficient. UI completion must revalidate session before publication even when a DB operation succeeded for an outgoing scope. Do not leak outgoing success notices into the new account.

Local history uses DeviceLocal tokens independent of provider auth refresh. Turning global history off invalidates pending recording work across all scopes; turning it back on issues fresh recording epochs. Clear-history affects only selected scope.

Per-item download generation allows deleting one song without invalidating unrelated transfers. Delete-all increments scope deletion epoch and invalidates every item under it. Re-enqueue after explicit user action creates a fresh item generation; canceled callbacks cannot attach assets to that new attempt.

### 20.2 History API and recording semantics

HistoryRepository exposes record(event,token), page(scope,cursor,query,limit), clear(scope), setRetention(policy), prune(scope,now) and observeRevision(scope). Return typed outcomes; no silent runCatching success.

History event ID is generated once per logical play, not on every persistence retry. Existing approximate five-second listening qualification remains the initial rule. Repeating the same queue entry after completion starts a new logical play. Brief pause/resume of the same play does not create another event. A deliberate replay after completion does. Test repeat-one and seek-to-start explicitly.

History enable setting is device-wide; retention may remain device-wide for this program but pruning is applied independently per scope. Display that distinction. Provider activity does not send history records remotely unless already explicitly part of provider playback; this new history repository adds no remote endpoint.

Rows at the same timestamp page by event ID without duplication. Live broadcasts are excluded from song-history qualification per existing policy unless an explicit current supported history feature requires them; preserve established podcast/music flags.

Future timestamps: do not delete them merely because clock changed. Clamp new locally recorded wall timestamps to validated nonnegative values; ordering tolerates future legacy values and reports a sanitized anomaly count. Never use wall-clock elapsed time for crossfade or retry duration.

The 100,000-row cap is a safety policy for new storage, disclosed in UI/docs. If a legacy import exceeds the cap, the import confirmation states how many newest rows will be retained; keep original legacy source recoverable until explicit deletion. Do not silently truncate an existing legacy archive because the setting appeared.

### 20.3 Snapshot API and lifecycle

Snapshot repository exposes save(scope,snapshot,writeSequence,token), load(scope), clear(scope) and observeAvailability(scope). Scope lives in the row/key and validated context; payload IDs cannot select arbitrary filesystem roots.

Routine writes are conflated last-sequence-wins. Final lifecycle save gets a bounded awaited IO transaction when lifecycle permits; if Android terminates first, restoration uses last durable checkpoint. Do not block the main thread indefinitely to pretend process-death durability is absolute.

If queue is empty, clear only its owner snapshot. Guest empty queue never erases an old Provider snapshot. Deleted account snapshot cannot be rewritten by an already queued lifecycle callback.

Bad payload handling: preserve one private bounded corrupt-copy record for diagnosis/recovery, emit sanitized reason, show safe empty paused state. Do not repeatedly parse an unbounded corrupt payload every launch. Unknown newer schema must not be overwritten silently by an older binary.

### 20.4 Migration checkpoints and rollback

Use named checkpoints: Discovered, SourceFrozen, Parsed, TransactionStarted, Committed, Verified, Archived. Inject termination before/after each.

Freeze legacy input under the old writer's lock or copy to a private same-filesystem immutable staging file before fingerprinting. Route new events into the new repository only after ownership is known. If migration fails, retain source and safe in-memory state; do not continue writing into an ambiguously owned old file.

Migration fingerprint includes format version, byte length and SHA-256 of frozen input. Exclude credentials from diagnostic reporting; store only necessary hashes/counts privately. A changed source is a new migration input requiring deduplication by deterministic source/event mapping, not permission to duplicate all old rows.

Cross-database scope alias migration is not one atomic SQLite transaction. Use an idempotent migration journal:
1. Register verified target/legacy alias pending.
2. Make canonical reads able to find retained legacy bundles.
3. Copy/merge metadata transactionally in each store, with conflict rules.
4. Verify canonical count/file references.
5. Mark alias active; keep legacy roots read-compatible until no metadata references remain.
6. Remove old duplicate metadata only after verification; do not delete sound bundle files as cleanup.

Rollback policy: avoid installing older APK over a newly migrated production data store. Prefer a forward fix. Keep schema additions backward-readable where practical, but never promise arbitrary downgrade. Test rollback of an uncommitted migration transaction and restart recovery; separately document binary downgrade as unsupported if true. Backups contain private data and are local-only, protected and deleted only under an explicit retention policy.

### 20.5 Download startup reconciliation table

| Files/DB | Required reconciliation |
|---|---|
| DB Complete, valid audio/marker | Keep ready, inspect assets independently |
| DB Complete, audio missing/corrupt | Mark retryable failure; no decoder retry loop |
| Valid audio/marker, DB Transferring/Assets | Validate then promote audio readiness; retain asset status |
| Audio present, marker absent | Validate container/length/duration before writing marker; otherwise retry |
| Marker wrong length | Treat as invalid; preserve recoverable bytes until validator decides |
| Only partial source/output | Restart safely; remove owned temp files only |
| Assets complete, audio invalid | Keep metadata; never report playable complete |
| Deleted/tombstoned row, late files | Remove only owned stale-attempt files; never resurrect row |
| Another account's valid bundle | Leave untouched and inaccessible to wrong scope |

Atomic marker writes use temp+rename in the same directory. A ready marker is integrity metadata, not a security signature. A matching length alone does not prove an arbitrary media file is healthy.

### 20.6 Error vocabulary and user actions

| Error | Product message intent | Action |
|---|---|---|
| Offline waiting | Connection lost; waiting to reconnect | Pause/Cancel waiting |
| Retry exhausted | Could not play this item | Retry / Skip |
| Session expired | Sign in again to access account content | Sign in |
| Unverified identity | Could not verify account for local saves | Retry verification; retain old account data |
| Missing local file | File unavailable on this device | Choose another / grant access if relevant |
| Invalid offline bundle | Download incomplete or unavailable | Redownload / Remove |
| Storage insufficient | Not enough free storage | Manage downloads / Retry |
| Solver unavailable | Online playback resolver unavailable | Retry; offline audio usable |
| Unsupported video | No verified official video available | Continue audio |
| Mutation uncertain | Could not confirm change | Refresh/reconcile before retry |

Use localized strings; messages must not include raw network exceptions. Action labels may vary grammatically but semantics remain fixed.

## 21. Operational handoff templates and first-session checklist

### 21.1 First implementation session

Execute in this order:
1. Read this plan and applicable instructions.
2. Read ledger if present; do not assume TODO from an old copy.
3. Verify repository/working tree safely.
4. Inspect build configuration, README, feature/privacy docs and all touched subsystem tests.
5. Create baseline document and ledger before behavioral changes.
6. Run Q00 checks and record actual output.
7. Implement Q01 build identity before collecting new device evidence.
8. Add Q02 seams minimally; prove one account-race test and one fixture playback test.
9. Proceed to Q03 scope coordinator; do not start widespread package moves yet.
10. End each work period with a resumable ledger entry naming next exact file/test/action.

### 21.2 Ledger row example

~~~text
Package: Q04
Status: IN_PROGRESS
Starting revision: <actual revision or local-unknown>
Dependencies: Q00 PASS, Q01 PASS, Q02 PASS, Q03 PASS
Completed: schema creation; history pagination; malformed-line tests
Pending: interruption checkpoints; verified aliases; old snapshot quarantine UI
Last command: <actual command>
Result: <actual result/report path>
Next action: add test for death after Committed before Archived, then rerun migration suite
Blockers: <exact hardware/credential/tool prerequisite or none>
~~~

The placeholders illustrate fields; replace every one with actual values in execution records.

### 21.3 Evidence validity rules

A report records revision, dirty state, artifact hash, test scope and timestamps. Changing a fixture assertion creates a new test result; it does not retroactively repair the earlier run. Retrying flaky tests until green without root-cause classification is not acceptance.

Harness defect classification needs evidence such as wrong package/activity pairing, test deadlock or fixture startup failure. Production defect classification needs reproducible app behavior. If uncertain, label Unresolved and block affected gate. Never classify all timeouts as harness problems.

Any final summary must distinguish:
- freshly executed checks;
- existing historical evidence;
- deterministic fixture success;
- live-provider/device success;
- unavailable or failed checks.

### 21.4 Completion quality

The final reviewer must be able to answer from artifacts alone: what source produced this APK; what device/config tested it; which data migrations ran; what happens on account switch; how failures recover; what was not verified; whether secrets/test code are excluded; and whether the installed release matches the tested artifact.

If those answers require private chat history, the handoff is incomplete.

## 22. Version 1.1 additions: post-release resilience

Added 11 October 2026. Version 1.0 specified correctness and evidence up to the release candidate; these packages cover what happens after users have the app, plus an earlier release point. Each follows the section 5 completion requirements and the section 1.1 contract. Where this section is more specific than an earlier one, use this section; it never weakens a mandatory gate. The 9 October authorization for Q03-Q18 does not cover these packages: obtain approval before implementing them.

Also changed in v1.1 outside this section: contract rule 16 (commit per package), the section 3 scope note, the section 5 critical path, Q14 optimization rule 8 (Baseline Profile), Q15 action 8, the Q17 privacy line, new acceptance rows in section 16 and the milestone table.

### Q05A: Local data export and import

Depends on Q03-Q05. Targets: Settings -> Privacy and data, LocalDataController, the Q04 repositories.

Reason: backup and device transfer stay disabled (Q15), so changing phones currently loses settings, local collections and history.

Actions:
1. Add Export local data and Import local data. Use the system document picker for both; request no storage permission and write nowhere else.
2. One versioned file with a named schema. Contents: device-wide settings, local collections, search history, detailed history, and the download list as durable IDs only. Let the user choose which scopes to include, with counts.
3. Never include cookies, tokens, stream URLs, audio, artwork bytes or diagnostics (I01). Provider-scoped sections carry only the opaque scope digest.
4. The file is unencrypted. State before export that it contains listening history and is readable by anyone holding it.
5. Import validates with the same bounds as Q04: field limits, 2,000-item collections, 100,000 history rows per scope, a total size ceiling. Reject unknown newer schema versions; skip malformed records independently and report counts.
6. Ownership on import: DeviceLocal and Guest sections import directly. A Provider section imports automatically only into the matching verified scope. A non-matching section is never silently adopted; offer explicit import into the current verified account with the same confirmation wording as the Q04 legacy import, or leave it unimported.
7. Import merges and never deletes. History deduplicates by event ID; collections follow Q03 conflict rules. One transaction per store; failure leaves existing data unchanged.
8. Imported download IDs become Metadata rows, not automatic transfers. Import never autoplays and never mutates provider data (I12).
9. Run on IO with progress and cancellation; cancel before commit changes nothing.

Tests: round trip on a clean install; mismatched scope; corrupt, truncated, oversized and newer-version files; duplicate import; cancel mid-import; full disk; file scanned for credential/URL patterns.

Acceptance: EXP01 and EXP02 pass; a restored install matches the source for included data; nothing in the file violates I01.

### Q05R: Interim release R1

Depends on Q00-Q05 PASS. Q05A is included if PASS but does not block.

Reason: the cross-account history and snapshot leak is the main user-facing defect, and several final gates need hardware that may stay unavailable. Do not hold the fix until M6.

Rules:
1. Build an exact candidate under the Q01 numbering rule. Same application ID and certificate.
2. Required on the exact artifact: G02, G03, G07, G08, G10 and G12; REL01-REL04; ACC, DAT and QUE cases; existing playback, crossfade and download suites as regression checks.
3. REL02 in-place upgrade from legacy fixtures on API 31, 33 and 36 emulators is mandatory. Add a physical-device upgrade when an authorized device exists. Never test the upgrade on the user's main install without authorization.
4. G05 physical budgets, G06 assistive-technology evidence and G09 soaks are not required for R1. Release notes must list each as not verified, with README and privacy text updated for the changed behavior.
5. R1 is not compliance with this plan and must not be described as such. The M6 candidate reruns every gate; R1 evidence is not reused for it.
6. If any required item is FAIL or BLOCKED, R1 is not prepared. No waiver by the executor.
7. Preparing the artifact is not publication. Publication, pushing and installation each need explicit authorization.

Acceptance: REL04; prepared artifact, matching source, hashes, sanitized evidence summary and limitation list.

### Q08A: Loudness normalization

Depends on Q08. Targets: stream resolution metadata, the audio processing chain, audio settings, download bundle metadata.

Reason: Q08 states that the equal-power fade does not even out different mastering. This is the audible gap.

Actions:
1. First establish from source whether any normalization already exists. If it does, verify and document it against the rules below instead of adding a second mechanism.
2. Use the provider-supplied per-track loudness value where the pinned integration exposes it. Prove the field with a sanitized fixture before relying on it. Do not analyze audio on device.
3. Add Normalize volume to audio settings, default off for upgrades and new installs.
4. Attenuation only: reduce louder tracks toward the reference, never apply positive gain. Clamp to a documented range. Missing or invalid value means unchanged gain.
5. Apply it as a static per-track gain at a single point in the processing chain. It composes with, and never replaces, the crossfade owner (I07), volume boost and sleep fade. During a crossfade each player keeps its own static gain. No gain step at track start or when the setting toggles mid-track; ramp briefly.
6. Persist the value with download metadata so offline bundles normalize without provider requests (I11). It is durable, non-expiring metadata (I01). Existing bundles without it play unchanged.
7. Local device audio is unchanged in this package. Do not claim tag-based normalization.
8. Label the setting honestly: it evens out provider tracks when loudness data is available.

Tests: generated tones at different levels with scripted loudness values; clamp bounds; missing value; toggle mid-track; crossfade between differently normalized tracks; boost at maximum; offline bundle with and without the stored value.

Acceptance: AUD01 and AUD02; XFD04 still passes; finite bounded samples in every combination.

### Q10A: Provider canary and breakage response

Depends on Q10. Targets: scripts/quality, CI, docs/quality/provider-compatibility.md.

Reason: for an unofficial integration the most likely field failure is a provider-side change. Fixtures prove the parser against yesterday's responses; they cannot detect today's break.

Actions:
1. Add an account-free canary: one search, one collection page and one stream resolution that fetches only the first bytes of audio, against a small list of long-lived public tracks. No credentials, no personal account, no mutations.
2. Run it at most once a day on a schedule and on manual request, never on pull requests. Keep the request volume minimal.
3. Prefer a JVM harness over the pinned integration. If stream resolution needs the Android solver, run that step on the Q16 emulator lane or as a locally scheduled run, and record which was used.
4. Classify results: Healthy, ParserBreak, ResolutionBreak, Blocked/RateLimited, Outage, HarnessFault. Hosted runners are often blocked by the provider; a block or outage is never reported as app breakage. Require two consecutive break results before flagging.
5. A confirmed break raises a visible flag through the existing CI summary. It never edits code, bumps dependencies or publishes on its own.
6. Store sanitized results only: classification, phase, timing, dependency version. No response bodies or signed URLs.
7. Write the response runbook in provider-compatibility.md: triage by classification, capture and sanitize a new fixture, bump or patch the pinned dependency in an isolated change, rerun contract tests and the Q08 shrunk-release solver check, then prepare a fast-track candidate.
8. Fast-track candidate gate set: G02, G07, G08, G10, G12, the affected G04 cases and a live check on an authorized test account. Other gates are listed as carried over from the last full candidate, not re-verified. Same publication rules as Q05R.
9. Canary results never satisfy the Q10 live gate or any Q18 gate.

Tests: classifier against scripted healthy, malformed, rejected, blocked and unreachable responses; two-strike rule; sanitized output scan.

Acceptance: OPS01; runbook executable by someone without this conversation; no secret needed to run the canary.

### Q12A: Update notice

Depends on Q01 and Q12. Targets: About/Settings UI, a small UpdateChecker behind AppContainer.

Reason: a sideloaded app has no store to announce fixes, including Q10A fast-track fixes.

Actions:
1. Confirm from README and release tooling where releases are actually published. Use that public release feed over HTTPS. If no public feed exists, mark this package BLOCKED rather than inventing an endpoint.
2. Compare the feed's version against the Q01 build identity. Notify only for a strictly newer release. Local or dirty builds show their identity and never claim to be current.
3. Automatic check at most once per 24 hours while the app is in use, plus Check for updates in About. Skip the automatic check on metered networks when Data saver is on.
4. Setting Check for updates, default on, disclosed in docs/privacy.md. Off means zero requests (UPD02).
5. The request is unauthenticated and carries no account, device or install identifier, and no personal data in the URL.
6. Notify only: a row in About and one dismissible in-app notice per version. No system notification, no APK download, no install request. The action opens the release page in the browser after validating an HTTPS allowlisted host (Q15 action 4).
7. Bound response size and parse strictly. Failure, offline, rate limit and malformed data are silent with no retry storm; the manual check shows a truthful result.
8. Use the injected clock and transport so tests are deterministic.

Tests: newer, same, older, prerelease, malformed, oversized and unreachable feed; disabled setting; 24-hour throttle under clock changes; dismissed version not shown again; host lookalike rejected.

Acceptance: UPD01 and UPD02; no request when disabled; no code path downloads or installs.

### Q15A: Exit-reason diagnostics

Depends on Q15. Targets: PlaybackEventLog and diagnostics UI, application startup, the solver process.

Reason: the 200-event ring records what the app did, not why a process died. Crashes, ANRs and low-memory kills in the field are currently invisible.

Actions:
1. On startup, off the main thread, read the platform's historical process exit reasons (available at min API 31) for the main and solver processes. Record entries newer than the last one seen.
2. Record allowlisted fields only: process role, reason class, importance at exit, coarse time, build ID. Drop the free-text description and do not persist platform trace contents.
3. Add an uncaught-exception recorder that stores exception class names and app-package stack frames only, never messages, then delegates to the previous handler. It must not swallow the crash or delay process death noticeably.
4. Keep a small bounded store, separate from the event ring so a crash loop cannot evict it. Subject to the Q15 sanitizer and negative tests.
5. Show Last exits in diagnostics with plain-language reasons. A user-requested stop or swipe-away is not shown as a failure.
6. Add Share diagnostics report: a fixed-content sanitized text report (build identity, exit records, event ring) through the system share sheet, run through the Q15 export scrubber. User-initiated only. Nothing is sent automatically and no network endpoint is added.
7. Feed exit counts into Q14 soak evidence where the lane can read them.

Tests: scripted exit records for each reason class; exception with a message containing a URL, cookie and title; crash-loop bound; handler delegation; report scan for credentials, URLs, titles and scope IDs.

Acceptance: DIAG01; SEC03 still passes with the new records and report included.

### 22.1 Gate and ledger effects

G01 counts the lettered packages. Q05R and the Q10A fast track are reduced, disclosed gate sets for specific artifacts; neither changes what Q18 requires of the M6 candidate. Add each lettered package to the ledger as TODO with its dependencies, and record its authorization separately from Q03-Q18.

