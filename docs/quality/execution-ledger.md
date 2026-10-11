# Excellence execution ledger

## Authorization
User requires permission before every phase. Q00 authorized on 8 October 2026. Q01 authorized next; Q02 explicitly authorized after Q01. Q03-Q18 remain TODO and unauthorized. No phone/USB debugging available; continue independent work and retain device gates as BLOCKED.
Update 9 October 2026: user authorized Q02 completion and all remaining phases Q03-Q18 in sequence without per-phase approval. This does not authorize device installation, account mutations, publication, pushing or data deletion; those still require explicit approval.

## Q00
Status: PASS
Dependencies: none
Starting revision: b99865fe84b1d83d7495bb98fbd07b6e3abb7b20 (dirty)
Changed paths: docs/quality/baseline.md; docs/quality/execution-ledger.md
Preserved pre-existing paths: scripts/download_favourites.py; docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md
Completed: repository/instruction inspection; successful elevated Git status; local toolchain discovery.
Next action: ask user permission for Q01; after approval inspect app/build.gradle.kts, PlaybackEventLog.kt, About UI and evidence scripts. Do not begin Q01 without approval.
Blockers: physical/device/live checks unavailable; sandbox requires escalation for builds and documentation writes.

## Q03-Q18
Status: Q03 PASS (see the Q03 section); Q04 PASS locally with open items (see the Q04 section); Q05 PASS locally with open items (see the Q05 section); Q06 PASS locally with open items (see the Q06 section); Q07-Q18 TODO
Authorization: granted 9 October 2026 for Q03-Q18 in dependency order (see Authorization section).


### Q00 completion evidence
Evidence: artifacts/quality/b99865fe-dirty/q00-20261008T042625Z/q00-summary.json and baseline-build.log.
Commands: Gradle wrapper --version; :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease --offline --continue --rerun-tasks --console=plain.
Result: exit 0; 304 tests / 55 suites; zero failures/errors/skips; debug lint 0 errors/83 warnings/5 hints; release lint 0 errors/90 warnings/5 hints; both APK assemblies successful.
Release: unsigned arm64-v8a; 26.275 MiB; SHA-256 8AA714303656318441C39AB610C5430D63708E7E5332EE92675F30D23A092849.
Acceptance: reproducible fresh baseline PASS; existing user work preserved PASS; historical evidence not attributed to new run PASS; device blockers disclosed PASS.
Changed paths: docs/quality/baseline.md and docs/quality/execution-ledger.md only; ignored evidence under artifacts/quality/b99865fe-dirty/q00-20261008T042625Z/.
Known unresolved baseline risks: shared detailed history/search/snapshot; selector-dependent account key; solver in-process fallback; R8 Kotlin metadata warnings; lint warnings await full triage. See baseline.md for owners.
Q00 source fixes: none. No installation, account mutation, publication or device setting changes occurred.
Next exact action: obtain Q01 approval; inspect build metadata integration and highest known distributed version code before editing.

## Q01
Status: PASS
Authorization: user said start next phase on 8 October 2026; Q02 also authorized after this phase.
Dependencies: Q00 PASS
Starting revision: b99865fe84b1d83d7495bb98fbd07b6e3abb7b20 (dirty)
Scope: deterministic build/source identity, 0.10.0-rc1/code 22, About/diagnostics, fail-closed evidence schema/collector and integrations.
Preserve: scripts/download_favourites.py; implementation plan; Q00 records.
Next action: implement build identity and result collection; run parser negative tests and real builds.
Limitations: no device; Q00 R8 warnings remain unresolved and are not silently waived.
Q01 attempt 1: generateDebugBuildConfig/generateDebugBuildIdentityAssets FAILED during Gradle script compilation: java extension shadowed package-qualified MessageDigest; nullable AGP buildConfigFields; map inference cascades. Fixed import, explicit map type and required enabled BuildConfig property; no test assertion weakened.

### Q01 completion
Status: PASS (traceability package; device/release compliance not claimed).
Fresh checks: 307 JVM tests in 56 suites, zero failures/errors/skips; debug and release lint zero errors; both assemblies and embedded identity/hash checks PASS_LOCAL.
Debug evidence: artifacts/quality/b99865fe84b1d83d7495bb98fbd07b6e3abb7b20-dirty-2fcc6006e11a/q01-debug-01/manifest.json
Release evidence: artifacts/quality/b99865fe84b1d83d7495bb98fbd07b6e3abb7b20-dirty-2fcc6006e11a/q01-release-01/manifest.json
Collector tests: 23 passed; artifacts/quality/b99865fe84b1d83d7495bb98fbd07b6e3abb7b20-dirty-2fcc6006e11a/q01-collector-tests-01/unittest.log
No-Git Gradle smoke: PASS; artifacts/quality/local-unknown/q01-git-unavailable-01/build-identity.json
PowerShell parse and inspect-only wrapper PASS; no installation/signing/device script executed.
Debug APK SHA-256: 2a8b79d737c641833834d3541b35383ad65fe916a53c910227bb8f858a172a71
Release unsigned APK SHA-256: ef5f0851c66c80558466d6a36b3696c6711a24b609e46739f1b5019bead6a8c3
Acceptance: distinguishable binaries PASS; APK/source/reports linked PASS; incomplete/crashed/stale/mismatched evidence rejected PASS.
Changed paths: app/build.gradle.kts; BuildIdentity.kt/test; PlaybackEventLog.kt; SettingsScreen.kt; strings.xml; ServiceStopDiagnosticsTest.kt; scripts/quality/*; release/device/UI scripts; CI; README/CHANGELOG and quality docs.
Known blockers: no physical phone; no live/provider/upgrade tests; R8 metadata warnings retained.
Next exact action: Q02, already authorized; constructor seams, deterministic fakes/HTTP/audio fixtures and gateway audit. Q03 remains unauthorized.

## Q02
Status: PASS (see Q02 completion, 11 October 2026)
Authorization: user explicitly allowed the next phase after Q01 on 8 October 2026.
Dependencies: Q00 PASS, Q01 PASS
Starting revision: b99865fe84b1d83d7495bb98fbd07b6e3abb7b20 (dirty; includes preserved Q00/Q01 work).
Next action: inject runtime boundaries into existing provider/download/playback code; add test-only fakes and original audio; prove cache account race and fixture playback on disposable emulator if available.
Limitations: physical phone unavailable; no personal provider accounts or data are test fixtures.

### Q02 execution notes (verification in progress)
Implemented: AppContainer/runtime constructor seams; provider/cache/download dispatch/time/transport/resolver injection; strict PlaybackGateway commands; private debug preview activities; same-UID stress controls; fake policy clock/session/catalog/resolver; bounded loopback/range server; original test-only PCM fixtures and provenance; decoder instrumentation; artifact packaging audit.
Attempt compile-test-01 FAILED: injected dependency parameter after progress broke existing trailing-lambda download callers. Corrected parameter order; preserved caller behavior.
Attempt compile-test-02 FAILED: pre-existing instrumentation references to verification activities were not wired for debug. Added private activities/debug-only source wiring; no production activity added.
Attempt compile-test-03 PASS: 316 tests / 58 suites; zero failures/errors/skips; debug and paired instrumentation APKs built.
First fixture playback run FAILED: preparation exceeded unchanged 10-second deadline while release/stress compilation and R8 ran concurrently. Preserved fixture-playback-01.log and fixture-process-01.log. ART logs show repeated 100-460 ms class verification, no reported media error. Collector rejected failed structured output as intended.
Second fixture playback run PASS on the identical installed APKs after build became idle: same assertions/deadlines, 7.824 s total. No personal account or public-provider request involved. This supports a host-contention harness cause; final-source decoder run still required.
First final-build-01 PASS: debug/release lint and release assembly; stress instrumentation compilation. Known R8/Kotlin metadata warnings remain.
First packaging-audit-01 PASS: audio metadata/hashes; paired debug/test identity; fixture asset/code exclusion from app APKs; release manifest cleartext denied and no fixture config/activities.
Follow-up fixes under Q02: constructor runtime must govern container provider and automatic download planner; temporary providers and injected SABR engines close; engine initialization failure cannot retain download gate; fixture server rejects invalid config before binding and closes unfinished clients on shutdown. Added regression coverage.
Evidence staging: artifacts/quality/q02-work/. Final manifests will supersede provisional source identities after all code is frozen. Historical failures remain preserved.
Next action: finish fresh unit/lint/APK checks on final source; execute fixture playback only when build is idle; collect exact paired-APK evidence and packaging audit; shut down only the disposable emulator. Q03 remains unauthorized.

### Q02 completion (11 October 2026)
Status: PASS (deterministic harness package; device, live-provider and release compliance not claimed).
Source: b99865fe84b1d83d7495bb98fbd07b6e3abb7b20 dirty, digest bac26f301045e2fcf18455620e238433d3f0c2fe3a52978b1ba35d2694e7d661. The APKs left by final-build-02 carried digest 523607595eb0 and did not match this source, so every check below was rerun; no source file was edited in this session and the digest was identical before and after the runs.
Evidence root: artifacts/quality/b99865fe84b1d83d7495bb98fbd07b6e3abb7b20-dirty-bac26f301045/
Debug build (final-build-03.log): :app:testDebugUnitTest --rerun :app:lintDebug --rerun :app:assembleDebug :app:assembleDebugAndroidTest --offline --continue; exit 0 in 21m 41s. 318 JVM tests / 58 suites, zero failures/errors/skips. Debug lint 0 errors, 84 warnings, 5 hints.
Fixture playback (fixture-playback-03.log): PASS on first attempt against the final-source APKs with the build idle; 1 test, 2.523 s, unchanged assertions and deadlines. Disposable emulator riff_q02_fixture, API 31, sdk_gphone64_x86_64. No media error or fatal exception in fixture-logcat-03.log. No personal account or public-provider request.
Paired evidence: q02-debug-fixture-03/manifest.json PASS_LOCAL; gates build, artifactIdentity, jvm, lint, instrumentation all PASS. Debug APK SHA-256 bcf4b5d19274c6ecb6ea86758439d4541da91826afabc8768e3a7829c6090be4; test APK SHA-256 ce618f22d72769a6c8196d210a858f35bb5352e59cb5e5ae1be5c6932045665f; identical embedded identity.
Release (q02-release-01/manifest.json): PASS_LOCAL; 318 tests; release lint 0 errors, 91 warnings, 5 hints; shrunk unsigned release assembled, 26.291 MiB, SHA-256 838aa9029efd726ed3d954c7f63d93341dc7fab9e0c08c83b46647c9257ee476. Instrumentation gate BLOCKED for release (no device run of the release artifact).
Packaging audit (packaging-audit-02.json): PASS on those exact three APKs: six audio fixtures with matching metadata/hashes only in the test APK; no fixture code or assets in debug/release app APKs; release cleartext denied with no fixture config or test activities; debug cleartext limited to 127.0.0.1 and localhost.
Stress (stress-build-04.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress; exit 0. Compilation only; stress instrumentation was not run.
Collector self-tests: 23 passed.
Failed attempts in this session, all operator invocation errors with no source, assertion or deadline change: q02-debug-fixture-01 (start timestamp with seven fractional digits rejected by the collector; empty run directory preserved); q02-debug-fixture-02 (ANDROID_HOME unset, so the debug signature could not be verified; FAIL manifest preserved); stress-build-03.log (wrapper not found from the shell used). Each was rerun under a new ID.
Lint delta versus Q01: one new warning in both variants, StaticFieldLeak at AppContainer.kt:99. Preliminary triage: false positive, the container retains only applicationContext, the same pattern as the existing OfflineMusicStore and MoodPhotos warnings. Not suppressed; final triage stays with Q15/G08.
Acceptance: critical fixture state and playback tests need no personal account or public provider PASS. First-session proof of one account-race test (PageMemoryCache generation boundary, JVM) and one fixture playback test (Media3 on emulator) PASS. Migration tests do not exist yet; they belong to Q04 and will use this harness.
Known limits: R8/Kotlin metadata warnings persist (172 lines in the release log) and remain an unresolved Q00 toolchain risk. Emulator success does not satisfy physical audio, performance, live-provider, accessibility or upgrade gates. The account-race test is a prerequisite for Q03, not proof of a session coordinator.
Device state: emulator shut down with adb emu kill and adb server stopped; no emulator or adb process remains. The debug and test APKs remain installed only inside the disposable AVD image. No physical device, account, publication or data deletion was involved.
Commit: done on 11 October 2026 after the user's go-ahead, on local branch codex/riff-quality-m0 (not pushed): 4392f78 plan v1.1; f477a6a Q00; 1c08a29 Q01; 2644fbc Q02. Q01 was separated from Q02 at file level (plus one hunk of app/build.gradle.kts) and was not built on its own. scripts/download_favourites.py keeps its pre-existing uncommitted user change. The repository commit-msg hook rewrote the co-author trailer on all four commits. Committing changed the revision but not the source digest (still bac26f301045), so the Q02 evidence above remains bound to the same source content under its earlier dirty identity.

## Q03
Status: PASS for everything that can be verified without a phone or test accounts (see Q03 completion, 11 October 2026). Two-account physical check BLOCKED.
Authorization: Q03-Q18 authorized 9 October 2026; user said to start Q03 on 11 October 2026.
Dependencies: Q00 PASS, Q01 PASS, Q02 PASS
Starting revision: 2644fbc3d606d827b44dd5fe182a281078d2ff75 (dirty only for scripts/download_favourites.py)

### Q03 findings before editing
- The scope key was SHA-256 of sync identifier plus chooser position, so the same account at a different position got a different key (ACC04 defect).
- A signed-in session with no sync identifier fell back to the "guest" download scope, merging into Guest (ACC06 defect).
- The provider already rejects stale in-flight work with authRevision in roughly sixty places, and keys the feed, collection and stream caches by it. That barrier is kept as the single generation source.
- An account change did not stop the previous account's provider queue; only sign-out cleared it, from screen code.
- An interrupted transfer already re-queues itself when the active scope changes; completed files are kept.

### Q03 slice 1 (implemented, verification below)
New AccountSession.kt: AccountScope (Guest, DeviceLocal, Provider, Unverified), VerifiedAccountIdentity, scope v2 derivation without the chooser position, legacy key derivation, SessionToken with a per-process nonce, ScopeAliasStore/PreferenceScopeAliasStore, AccountScopeRegistry, SessionParticipant, SessionCoordinator.
Decisions within the plan's ADRs:
- Data is not moved. A legacy key is accepted as an alias only when re-deriving it from the verified identifier reproduces it and it already owns data. The storage key is chosen once and persisted, so a later reorder cannot move it. An account with no existing data uses its canonical v2 key.
- If one account has data under several legacy keys, the current position's key stays primary and the others are retained untouched and counted in a sanitized event. Merging them is pending.
- The provider stays the single source of the active session and publishes it as StateFlow<SessionToken>, generation equal to authRevision. The coordinator serializes sign-in commit and sign-out around the provider and rejects every token while a transition runs.
- A fourth scope, Unverified, represents a signed-in session without a stable identifier. It has no storage key; the download store refuses writes for it and the Account screen says so.
Changed paths: AccountSession.kt (new); YouTubeMusicProvider.kt (session flow, accountScope, alias-based accountScopeKey, storageScopeKey); OfflineMusicStore.kt (currentScope, scopes(), unverified write guards); AppContainer.kt (sessions coordinator, downloads participant, injectable storedScopeKeys); AccountScreen.kt (sign-in commit and sign-out through the coordinator, unverified notice); RiffApp.kt (playback and collections participant); AccountSessionTest.kt and AccountScopeProviderTest.kt (new).
Behavior changes a user can see: switching to a different account, or signing in from signed-out, now stops a provider or mixed queue, as sign-out already did. On-device-only playback continues. The stopped queue is not restored yet.
Targeted run (artifacts/quality/q03-work/targeted-01.log): exit 0; AccountSessionTest and AccountScopeProviderTest, 22 tests, zero failures/errors/skips.
Full run q03-debug-01 (source digest 84d9225ff551): PASS_LOCAL, 340 tests / 60 suites, but debug lint gained one UseKtx warning in AccountSession.kt. Fixed by using the KTX preferences editor already used elsewhere; no test changed. Run preserved.
Full run q03-debug-02 on the committed slice (artifacts/quality/2644fbc3d606d827b44dd5fe182a281078d2ff75-dirty-1a823ba39d6f/q03-debug-02/manifest.json, source digest 1a823ba39d6f): PASS_LOCAL; 340 JVM tests / 60 suites, zero failures/errors/skips; debug lint 0 errors, 84 warnings, 5 hints, identical to Q02; debug APK SHA-256 a504db2e54eedebb5264b28036dc5179b58f24e632649bae9ea464fcad4eb254.
Not run for this slice: release lint and shrunk release build, stress compilation, fixture playback on the emulator, and any device or live-provider check. They are due before Q03 can be marked PASS.

### Q03 acceptance after slice 1 (superseded by Q03 completion)
| Case | State | Evidence or gap |
|---|---|---|
| ACC01 delayed A result after B | PASS at coordinator level (JVM) | AccountSessionTest; existing DeterministicHarnessTest covers the cache boundary |
| ACC02 A->B->A | PARTIAL | Owner and stale-token rejection tested; scoped paused-queue restoration needs the Q04 snapshot store |
| ACC03 cookie rotation, same identity | PASS (JVM) | Same owner, no quiesce, no new alias write |
| ACC04 selector reorder | PASS (JVM + Robolectric provider test) | Two-account physical check BLOCKED: no device or test accounts |
| ACC05 canceled/failed chooser | PARTIAL | Declined commit keeps the owner (JVM); chooser cancel never reaches the coordinator by construction; no UI test yet |
| ACC06 missing stable ID | PASS (JVM + Robolectric) for scope and keys | UI: Account screen notice only; download entry points still fail silently |
| ACC07 pure local vs mixed on switch | NOT VERIFIED | Implemented in the RiffApp participant; needs a Compose or device test |

### Q03 remaining work after slice 1 (all items closed or blocked in Q03 completion)
1. Capture and validate SessionToken in the download engine and in MusicPlaybackService queue application; today they rely on scope-string and queue-generation checks.
2. Scoped paused-queue persistence and restoration on switch (ADR-Q04 steps 2 and 5). Depends on the Q04 snapshot repository; until then the queue is cleared.
3. Merge or multi-key reads for retained aliases, following the Q03 conflict rules.
4. Truthful Unverified state at download and save entry points, with Retry verification.
5. Tests: switch during transfer and during asset completion; mixed versus pure local playback; failed transition UI; Compose test for the Account screen flows.
6. Resolve the scope key off the main thread; the first lookup currently runs one small query where it is called.
7. Two-account device check for identifier stability: BLOCKED until an authorized device and two test accounts exist. Old data is retained in place regardless.
Next exact action: read the full-suite result in artifacts/quality/<source-id>/q03-debug-01/manifest.json, fix any regression, commit slice 1, then start item 1 in OfflineDownloadEngine.kt and MusicPlaybackService.kt.

### Q03 completion (11 October 2026)
Status: PASS locally and on the disposable emulator. One acceptance item is BLOCKED on hardware and accounts (below). Device, live-provider and release compliance are not claimed.
Source: c25701df6d4d81c395a4c5d51c7b32881a0d3d19 dirty, digest be13252c1aea3fd69f774613a8cc556f02f88ed63658881fff356c529d747649. Evidence root: artifacts/quality/c25701df6d4d81c395a4c5d51c7b32881a0d3d19-dirty-be13252c1aea/
User instruction on 11 October 2026: finish Q03, and treat "start a phase" as "complete every aspect of the phase" from now on.

Implemented after slice 1:
- Playback service is a session participant. On an owner change it cancels any crossfade, pauses, saves the provider or mixed queue under the outgoing owner with a confirmed write, then stops and clears it; a failed save abandons the switch. On bind it loads the incoming owner's queue paused when the player is empty. Pure on-device queues are left playing.
- ScopedPlaybackSnapshots.kt: one paused queue per owner in the playback_snapshots preferences. The pre-scope shared snapshot (last_playback/snapshot) is no longer read or written and is not deleted; Q04 decides its recovery. This is the minimal store Q03 needed; Q04 replaces the storage and keeps the contract.
- Stale work: queue and provider-stream commands carry the session generation and nonce; the service answers a stale one with SessionError.INFO_CANCELLED and the gateway reports nothing. Radio continuation checks the token before adding tracks. The download engine captures the owner and stops when it changes; a request that fails because of the switch re-queues the song instead of marking it failed.
- Alias merge: a background pass folds other verified keys of the same account into the primary key (OfflineMusicStore.mergeScope, mergeCollectionScope). Folders move, nothing is copied, validated audio is never deleted, pin wins, totals add, the pass is restartable. Two complete copies, or a song the primary key is transferring, stay where they are and are counted in a sanitized event.
- Scope lookup: after the first launch the storage key comes from preferences alone; stored data is inspected only by the background pass.
- Unverified state: download and save entry points say the account could not be verified; the Account screen offers Verify account. The Account screen takes the coordinator as a parameter so it can be tested.
- Docs: docs/quality/account-scope.md; CHANGELOG entries including the one visible regression (the queue saved by an earlier build is not restored on the first launch after updating).

Verification:
- Debug (artifacts/quality/c25701df6d4d81c395a4c5d51c7b32881a0d3d19-dirty-be13252c1aea/q03-debug-06/manifest.json): PASS_LOCAL. 367 JVM tests / 64 suites, zero failures, errors or skips. Debug lint 0 errors, 84 warnings, 5 hints; no issue added or removed against the slice-1 run. Debug APK SHA-256 03b6f670523b24b1d4cbde7d9978dae07fc8d0da7a19f4fdfdaf1348e0a2fc13.
- Emulator (artifacts/quality/c25701df6d4d81c395a4c5d51c7b32881a0d3d19-dirty-be13252c1aea/q03-debug-fixture-01/manifest.json, log q03-instrumentation-02.log): PASS_LOCAL with the instrumentation gate PASS. 4 tests, 17.55 s, zero failures: FixturePlaybackTest plus the three AccountSwitchDeviceTest cases. Test APK SHA-256 f5c96f94b77fed9c0b5f2fffcda8ee1f197567666180d0bc6c001b46b93da76e. Disposable emulator riff_q02_fixture, API 31, sdk_gphone64_x86_64, Wi-Fi and mobile data disabled and confirmed unreachable before the run. Session fields were synthetic; no account, cookie or provider request was involved.
- Release (artifacts/quality/c25701df6d4d81c395a4c5d51c7b32881a0d3d19-dirty-be13252c1aea/q03-release-01/manifest.json): PASS_LOCAL. 367 tests; release lint 0 errors, 91 warnings, 5 hints, identical to Q02; shrunk unsigned release assembled, 26.307 MiB, SHA-256 138afe8448b202f9aded76df58080cd473ca1996d7acdd7aad9aeecdef97956c. Instrumentation gate BLOCKED for release (no device run of the release artifact).
- Stress (artifacts/quality/c25701df6d4d81c395a4c5d51c7b32881a0d3d19-dirty-be13252c1aea/stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.

Failed attempts, all preserved:
- Account tests, first targeted run: ScopedPlaybackSnapshotsTest used an on-device fixture item without a media URI, which the decoder correctly rejects. Test data fixed; no production change.
- DownloadOwnerSwitchTest first run FAILED and found a real defect: a download request that failed because of the switch was marked failed with a storage/connection message. Fixed in OfflineDownloadEngine.kt (re-queue under its owner).
- q03-debug-03 (digest a424d726ee24) FAIL: lint WrongConstant, SessionResult.RESULT_INFO_SKIPPED is not an allowed result code; plus two new warnings (ApplySharedPref, UseKtx). Fixed: SessionError.INFO_CANCELLED; KTX editor; the deliberate confirmed write carries a documented suppression. The emulator had passed the same tests on that source (artifacts/quality/q03-work/account-switch-01.log).
- q03-debug-04 and q03-debug-05 FAIL: collector freshness rule. A lint report and then a debug APK left by intermediate Gradle runs in this session were up to date and so not rewritten inside the run. Those build outputs were cleared and the run repeated as q03-debug-06. No source change between 04 and 06.

Acceptance:
| Case | State | Evidence |
|---|---|---|
| ACC01 delayed A result after B | PASS | AccountSessionTest (token rejected); AccountSwitchDeviceTest.queueRequestBuiltForThePreviousAccountIsSkipped on the real service; DownloadOwnerSwitchTest |
| ACC02 A->B->A | PASS (emulator, synthetic identities) | AccountSwitchDeviceTest.eachAccountGetsItsOwnQueueBackPaused: A's queue and index return paused, B's likewise, sign-out leaves an empty Guest player |
| ACC03 cookie rotation, same identity | PASS (JVM) | Same owner, no quiesce; owns() stays true across the refresh so transfers continue |
| ACC04 selector reorder | PASS (JVM + Robolectric) | Physical two-account check BLOCKED, see below |
| ACC05 canceled or failed chooser | PASS (JVM + Compose) | Declined commit keeps the owner; AccountScreenSessionTest shows a failed sign-out keeps the account and tells the user. Chooser cancel never reaches the coordinator by construction; the WebView chooser itself is not automated |
| ACC06 missing stable ID | PASS (JVM + Robolectric + Compose) | Unverified scope, no storage key, truthful notices, Verify account action |
| ACC07 pure local vs mixed on switch | PASS | Owner rules in ScopedPlaybackSnapshotsTest; AccountSwitchDeviceTest.onDeviceQueueKeepsItsPlaceAcrossAnAccountSwitch on the real service with a MediaStore fixture file |

BLOCKED: two real accounts on a physical phone. Needed to confirm that the provider's stable identifier is the same for one account at different chooser positions, and to exercise the real sign-in screens. Requires an authorized device, two test accounts and explicit approval for installation. Rerun: install the debug APK built from this source on the test device; sign in to account 1, save a track and download a song; add account 2 and switch; reorder or re-add so account 1 changes chooser position; switch back and confirm account 1's saved track, download and paused queue are present and account 2's are not; copy diagnostics and check for account_transition and account_scope events with no identifiers. Until then old data is retained in place and never reassigned on unproven identity.

Known limits:
- A same-account cookie refresh that lands between a tap and the service handling it drops that one queue request silently (the token generation changes). The window is milliseconds and the tap can be repeated. Kept because the provider already rejects in-flight work on the same generation.
- Emulator runs used synthetic session fields set directly, not the sign-in WebView.
- The engine-level switch test covers the resolve phase; an interruption during asset completion relies on the same checkState path and the store's state guards and has no dedicated test.
- R8/Kotlin metadata warnings persist from Q00.
Device state: emulator shut down with adb emu kill and adb server stopped. The debug and test APKs remain only inside the disposable AVD image. No physical device, real account, publication, push or user-data deletion was involved. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py (pre-existing user change) and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md (untracked file that appeared during this session; not created by this work).
Commit: e5eeb35 on local branch codex/riff-quality-m0 (not pushed), following c25701d for slice 1. The commit changed the revision, not the source digest, so the evidence above stays bound to the same source content under its dirty identity. The repository commit-msg hook rewrote the co-author trailer.
Next exact action: Q04 (durable state and migrations), when the user says to start it. It replaces the preference-backed snapshot storage, quarantines the legacy shared snapshot, and moves detailed history and search state into owner-scoped tables.

## Q04
### Q04 completion (11 October 2026)
Status: PASS locally and on the disposable emulator. Two items are BLOCKED on a physical phone (below).
Source: a7c009a8f010c3e2b23ddfd724af8eb6b281d83e dirty, digest ebdb804727bc118eb9f59a28d8ff82391513bb0adf2ab006477a3431ef423266. Evidence root: artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/
User instruction on 11 October 2026: start Q04 (standing rule: complete every aspect of the phase).

Implemented: library-state.db v1 (history_events, playback_snapshots, scope_generations, migration_runs, scope_aliases); HistoryRepository, PlaybackSnapshotRepository, ScopeAliasJournal (LibraryState.kt); LegacyStateMigration.kt; snapshot payload v2 with v1 read and one 6 MiB UTF-8 ceiling (PlaybackSnapshot.kt); one history event per logical play; History screen per owner with paging and search; Earlier data screen (EarlierDataScreen.kt) for explicit import, restore and delete; RESTORE_SAVED_QUEUE service command. Docs: docs/quality/state-and-migration.md, account-scope.md, CHANGELOG.

Verification:
- Debug (artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/q04-debug-03/manifest.json): PASS_LOCAL. 427 JVM tests / 67 suites, zero failures, errors or skips. Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q03-debug-06. APK SHA-256 96ac9a1fcb9a34003f4ed1eb96dfcd49416d3cd10eaa43f78d0528b1d19090d7.
- Emulator (artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/q04-debug-fixture-01/manifest.json, log q04-instrumentation-03.log): PASS_LOCAL, instrumentation gate PASS. 6 tests, 15.647 s: FixturePlaybackTest, three AccountSwitchDeviceTest cases, two LegacyUpgradeDeviceTest cases. Test APK SHA-256 d3ebb58be7f79b41aed523b25cd9de751e2882dc543ff05766bbbab3ad1574b1. riff_q02_fixture, API 31, network disabled and confirmed unreachable, synthetic identities.
- In-place upgrade over the Q03 build on the same emulator (artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/upgrade-over-q03-01.log, install-01.log): the history file the Q03 build had written (one on-device fixture play) was moved to unassigned storage and imported into device history; marker archived; WAL; schema 1. Taken on digest 65c21719cceb, before the final test-only edit.
- Release (artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/q04-release-01/manifest.json): PASS_LOCAL. junit {"suites": 67, "tests": 427, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; APK SHA-256 5f2161632579bd8f14d43641355499d9c8dde3c011b34bd6a1992489e0fe9eb9. Against q03-release-01 (91 warnings): no source issue added or removed; the difference is in dependency-version notices only (seven NewerVersionAvailable and one AndroidGradlePluginVersion gone, one GradleDependency added), which depend on lint's cached version data, not on this change. Shrunk unsigned release 26.37 MiB. No device run of the release artifact.
- Stress (artifacts/quality/a7c009a8f010c3e2b23ddfd724af8eb6b281d83e-dirty-ebdb804727bc/stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0, run after the commit on the same source content. Compilation only.

Failed attempts, all preserved:
- q04-debug-01 (digest dbb66d7b557b): PASS_LOCAL but 13 new lint warnings (Recycle on query().use, UseKtx). Fixed: KTX transaction; documented suppressions where commit() results are needed and for the Recycle false positive.
- q04-debug-02 (digest 65c21719cceb): PASS_LOCAL, superseded by a test-only edit.
- q04-instrumentation-01.log: 1 of 6 failed. Test isolation: the second case reused a legacy file with identical bytes, which the migration correctly recognises as already imported. The test file now differs per run. q04-instrumentation-02 and -03: 6 of 6.
- HistoryScreensTest was flaky: work resumed from Dispatchers.IO onto the Compose test dispatcher off the main thread. The History, Earlier data and Settings lookups now use the injectable IO dispatcher. No failures since.
- First test runs had wrong test data in four cases (history encoder truncates titles; record prunes immediately; text bounds keep a queue under the byte ceiling; a bad album id is dropped, not rejected). Tests corrected; no production change.
- A failed edit script truncated PlaybackSnapshotTest.kt to zero bytes; restored from git and the additions reapplied.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| No ambiguous adoption or silent loss | PASS | LegacyStateMigrationTest (mixed accounts, quarantine, explicit import and restore only); HistoryScreensTest; LegacyUpgradeDeviceTest |
| Restart-safe idempotency | PASS | Death at every checkpoint then restart; rollback inside the transaction; full disk then retry; identical and longer source copies |
| Paused restoration | PASS (emulator) | LegacyUpgradeDeviceTest: restored queue is paused at its saved index; nothing loads without the user's choice |
| No main-thread migration | PASS (JVM) | theContainerMigratesOnItsBackgroundDispatcherNotOnTheCaller |
| Listed tests: empty, corrupt, truncated, multibyte, repeated plays, mixed accounts, alias conflict, 2,000 tracks, authored repeats, unknown payload version | PASS | LegacyStateMigrationTest, LibraryStateTest, PlaybackSnapshotTest |

Decisions (mine, not the user's):
- Explicit import is offered to the active owner including signed-out use, not only a verified account as plan rule 3 words it. Unverified is refused.
- Search history stays where it is; it is listed under Q05 targets.
- Retention and clear-history exist in the repository and are tested; they have no screen yet (Q05).
- account_scopes preferences remain the lookup for an account's primary key; scope_aliases is the merge journal.
- A queue longer than 2,000 items still keeps its first 2,000 (behaviour before Q04). Only the byte ceiling refuses.

BLOCKED:
- Upgrade over a real earlier install with real history on a physical phone. Needs an authorized device and explicit approval for installation. Rerun: install this debug APK over the existing install, open History and Settings, Library, Earlier data, and confirm on-device plays moved, provider plays wait unassigned, the old queue is offered and loads paused.
- Phone-only checks (AudioContinuity, CrossfadeService, OfflineSmoke, PlaybackAcceptance) now read the saved queue through PhoneSavedQueue.kt and are compiled only. NativeIslandInstrumentation is not ported and refuses to run.

Device state: emulator shut down with adb emu kill, adb server stopped. No physical device, real account, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 3028fdc on local branch codex/riff-quality-m0 (not pushed). The repository commit-msg hook rewrote the co-author trailer.
Next exact action: Q05, when the user says to start it.

## Q05
### Q05 completion (11 October 2026)
Status: PASS locally and on the disposable emulator. One item is BLOCKED on a physical phone (below).
Source: 036bde585ff67ac54dc4f26affa1e45b2f207d6d dirty, digest e042a1713a704fcb38a8aa86f02d8612c043e820cf6e4269602e538d0fa7eb02. Evidence root: artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-e042a1713a70/
User instruction on 11 October 2026: start Q05 (standing rule: complete every aspect of the phase). Q05A and Q05R are not part of this and remain unauthorized.

Implemented: Settings, Privacy and data screen (PrivacyDataScreen.kt) with Save listening history, History retention (30/90/365 days or Keep until cleared), Clear account listening history, Clear device listening history, Clear search history, Delete downloads, Delete local data for this account, and the Earlier data link; LocalDataController.kt (one action at a time, off the main thread, results Done / Partial / Failed, retention applied on change and at most once a day at start, injected wall time); Clear on the History screen; recent searches per owner, 30 each, unverified kept in memory only (SearchHistory.kt); the search list shared by earlier builds treated as unassigned data with import and delete in Earlier data; HistoryRepository clearCounted, scopes, countOlderThan, pruneAll; OfflineMusicStore removeAllCounted, resetListening, deleteScope (rows first, canonical parent check), discardUnlisted so a late transfer cannot list a removed song again; delete-account-data stops and empties the owner's online queue before deleting. Docs: docs/quality/privacy-controls.md, state-and-migration.md, account-scope.md, CHANGELOG.

Verification:
- Debug (artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-e042a1713a70/q05-debug-02/manifest.json): PASS_LOCAL. 470 JVM tests / 70 suites, zero failures, errors or skips (Q04: 427 / 67). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q04-debug-03. APK SHA-256 d30c0a7cc9dc42a3ec747b0acee47e070573405c138b9eb3d00818f3c35dabf1.
- Emulator (artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-e042a1713a70/q05-debug-fixture-01/manifest.json, log q05-instrumentation-01.log, install-01.log): PASS_LOCAL, instrumentation gate PASS. 8 tests, 47.672 s: FixturePlaybackTest, three AccountSwitchDeviceTest cases, two LegacyUpgradeDeviceTest cases, two LocalDataDeviceTest cases. Test APK SHA-256 251fc209d1c67d91c09b8d46bdf98597a02a57ce5c4c72677b0900630fea7bf4. riff_q02_fixture, API 31, network disabled and confirmed unreachable, synthetic identities, fixture audio only.
- Release (artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-e042a1713a70/q05-release-01/manifest.json): PASS_LOCAL. junit {"suites": 70, "tests": 470, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q04-release-01. APK SHA-256 efe4bb1cd5f3763f6640576b3cb1e99bd1f5670a34ab2434bc25f103c96bb33d. Shrunk unsigned release 26.42 MiB (Q04: 26.37). No device run of the release artifact.
- Stress (artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-e042a1713a70/stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts, preserved:
- q05-debug-01 (artifacts/quality/036bde585ff67ac54dc4f26affa1e45b2f207d6d-dirty-1552067ce322/): PASS_LOCAL but one new lint warning (UseKtx in LocalCollections.kt, where the result of commit() is reported). Suppressed with a comment, as in LegacyStateMigration.kt. q05-debug-02 has no new issue.
- PrivacyDataScreenTest first failed to compile (a fake passed through an anonymous object; missing Rule import). Test-only fix.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| Cancel is inert | PASS | PrivacyDataScreenTest (every prompt cancelled: counts unchanged, no notice); HistoryScreensTest clear then cancel; earlier-search import cancelled |
| A clear cannot be reversed by queued work | PASS | LocalDataControllerTest and LibraryStateTest (tickets taken before a clear are refused, also after reopening the database); the store discards the files of a song that is no longer listed and never those of a listed one (the download engine's three call sites are not exercised end to end by a test); LocalDataDeviceTest on the real service: a play in progress is not written back, and a deleted queue is not saved back by the periodic save or restorable afterwards |
| Scope-specific tests | PASS | Account, signed-out, device and unverified cases in LocalDataControllerTest, SearchHistoryOwnerTest, PrivacyDataScreenTest; other owners' rows and files checked after every action |
| No remote mutations | PASS by construction, not by live observation | LocalDataController has no provider or network dependency; emulator run with the network off. No live account was used, so nothing was observed against the real service |
| History deletion never requires Android Clear storage | PASS (emulator) | LocalDataDeviceTest: clear, then the next play is recorded in the same process without restart |
| History off stops new writes immediately | PASS (emulator) | LocalDataDeviceTest: 8 s of playback with saving off records nothing; turning it on records the next play |
| Failure offers a truthful Retry; partial deletion reported | PASS (JVM) | PrivacyDataScreenTest with a failing controller; LocalDataControllerTest partial results name what remains |

Decisions (mine, not the user's):
- Recent searches stay in preferences, keyed per owner, rather than moving into library-state.db.
- The search list shared by earlier builds is shown to no owner; it is imported or deleted from Earlier data.
- Clearing an account's listening history also clears its recently played list and resets the play counts kept with its downloads.
- A retention period that would remove plays asks first with a count; one that removes nothing applies directly.
- Clear on the History screen acts only on the list shown (account or device).
- The history toggle and the Earlier data row moved from Settings, Library to the Privacy and data screen.

Known limits:
- Data under an older, not yet merged key of the same account is not covered by Delete local data.
- Not interested and podcast progress are device-wide and are not part of these actions.
- Retention has no alarm; it runs when the app runs.
- SettingsScreen keeps an unused collections parameter.

BLOCKED:
- These actions on a physical phone with real downloads and a real account. Needs an authorized device and explicit approval for installation and for deleting data. Rerun: install the debug APK, open Settings, Privacy and data, check each row's owner label and count, cancel each prompt and confirm nothing changed, then on data that may be lost clear history and delete downloads and confirm other accounts' data, device settings and the phone's own audio files remain.
- Carried from Q04: upgrade over a real earlier install; phone-only checks compiled only; NativeIslandInstrumentation not ported.

Device state: emulator shut down with adb emu kill, adb server stopped. Data cleared or deleted was synthetic fixture data on the emulator only. No physical device, real account, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: e795e41 on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q06, when the user says to start it.

## Q06
### Q06 completion (11 October 2026)
Status: PASS locally and on the disposable emulator. Physical-phone and live-provider checks are BLOCKED (below).
Source: 79aa078e245068058a5757738c3e124dbbc30382 dirty, digest 21d6ed8990ebfb31719f95e73c26701982a88cc0bcdf45e8381bd1dd0e49f658. Evidence root: artifacts/quality/79aa078e245068058a5757738c3e124dbbc30382-dirty-21d6ed8990eb/
User instruction on 11 October 2026: start Q06 (standing rule: complete every aspect of the phase). Q05A and Q05R were not started and remain unauthorized; the plan says they do not block Q06.

Implemented (PlaybackState.kt, MusicPlaybackService.kt, PlaybackGateway.kt, NowPlaying.kt):
- PlaybackStatus (Idle, Resolving, Buffering, Playing, Paused, Recovering, Ended, Failed) and PlaybackState (status, desiredPlayWhenReady, queueRevision, queueItemId, owner, durationMs, typed failure, recovery reason), published as state.playback; position stays separate. Typed PlaybackFailure replaces the gateway's error strings; the wording shown is unchanged.
- Every queue entry carries its own identifier; replacements (recovered stream, live fallback, audio/video swap) keep it. Live fallback and the video swap check the entry, not the video id or a stored index.
- PlaybackCommandOrder and OrderedCommands: queue commands are ticketed on arrival and applied one at a time in that order; a later replacement cancels earlier commands before or after their file is read; additions keep their order; a pause made after a command arrived loads the queue silent; a command that throws is answered as failed. Open collection, play stream, play on-device audio, restore saved queue, add to queue and stream swap all go through it.
- Queue revision raised on every playlist change; revision, owner, the entry being looked up and the recovery reason published in the session extras with the sleep-timer deadline.
- App side: queue edits act on the entry by identifier and do nothing when it is gone; requests are written on one lane so they are sent in order; additions no longer cancel one another; pause while a request is being written clears its autoplay; a stream swap names the entry it was asked for.
- Removal: current entry removed with nothing after it selects the one before, paused; the only entry removed leaves an empty paused queue; same rule for removing every copy of a song with shuffle off.
- Moving to another entry now also cancels a pending live fallback.
- Docs: docs/quality/playback-state.md, CHANGELOG.

Verification:
- Debug (artifacts/quality/79aa078e245068058a5757738c3e124dbbc30382-dirty-21d6ed8990eb/q06-debug-02/manifest.json): PASS_LOCAL. 488 JVM tests / 71 suites, zero failures, errors or skips (Q05: 470 / 70). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q05-debug-02. APK SHA-256 8cbefba824948fec57aa4675e8262f544bc2aecfc2b259ac584d6a458009f37a.
- Emulator (artifacts/quality/79aa078e245068058a5757738c3e124dbbc30382-dirty-21d6ed8990eb/q06-debug-fixture-01/manifest.json, log q06-instrumentation-01.log, install-01.log): PASS_LOCAL, instrumentation gate PASS. 14 tests, 51.483 s: FixturePlaybackTest, three AccountSwitchDeviceTest, two LegacyUpgradeDeviceTest, two LocalDataDeviceTest, six PlaybackOrderDeviceTest. Test APK SHA-256 23d2447a45d1c1bf78be2c92705b5ec6dafc54106d3e5e0e1bb008320a8f2656. riff_q02_fixture, API 31, network disabled and confirmed unreachable, synthetic entries, fixture audio only.
- Process death on the same install (q06-process-death-step1-01.log, q06-process-death-step2-01.log, q06-process-death-measurements-01.txt; each log validates as 1 test, 0 failures): step 1 played on-device fixture audio for 17 s, the stored position trailed the player by at most 4,997 ms over 5 checkpoints, and the run ended with audio playing; the app process was gone afterwards (pidof exit 1; step 2 ran in a different pid). Step 2: position at exit 17,124 ms, stored 14,042 ms, restored 14,042 ms, paused, still paused after 2.5 s; 3,082 ms lost. Bound allowed by the test: 5,000 ms cadence plus 1,500 ms.
- Release (artifacts/quality/79aa078e245068058a5757738c3e124dbbc30382-dirty-21d6ed8990eb/q06-release-01/manifest.json): PASS_LOCAL. junit {"suites": 71, "tests": 488, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q05-release-01. APK SHA-256 233a7c376b268a36c6d29c9231cb55c73649ccf27da453b63fa16bcee251d705. Shrunk unsigned release 26.42 MiB. No device run of the release artifact.
- Stress (stress-build-03.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts, preserved:
- stress-build-01.log and stress-build-02.log: the Gradle and Kotlin daemons died, "insufficient memory for the Java Runtime Environment" (hs_err_pid6772.log, hs_err_pid7004.log, replay_pid6772.log moved from the repository root into the evidence root). The host was out of memory after the release build; no compile error. Third attempt after stopping the daemons passed.
- artifacts/quality/79aa078e245068058a5757738c3e124dbbc30382-dirty-ce8d7d4f1577/ (q06-debug-01, q06-instrumentation-01.log 14 of 14): superseded. The kill test reported its measurements as an extra instrumentation status, which the strict log validator reads as an incomplete case. It now writes them to logcat. Test-only change; the run was repeated on the new digest.
- First full JVM run: 487 of 488. A command whose preparation threw escaped as an uncaught coroutine exception and was reported against an unrelated later test. That was a real defect in the new lane (it would have stopped the service); OrderedCommands now answers such a command as failed, with a test.
- PlaybackStateTest: one case sent two replacements without letting the first run and so saw "cancelled" where it expected "failed". Test corrected.
- A shell here-document with apostrophes failed before running, so a rename it contained was not applied; the compiler caught it.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| Race tests prove latest intent wins | PASS | PlaybackStateTest: slow older request cannot overwrite the newest, rapid replacements, additions in order, addition for a replaced queue dropped, pause while reading. PlaybackOrderDeviceTest on the real service: three requests sent together (2,000, 3 and 2 entries) leave only the last and the others answer cancelled; a 2,000-entry request followed by a newer one; pause sent right after a play request leaves it silent |
| Selected metadata matches entry | PASS | PlaybackOrderDeviceTest through the app's own gateway: two copies of one video have different identifiers, the selected identifier and title are the second copy's and follow it through move and remove; PlaybackStateTest on a real player |
| Restored position within 1 second of last durable checkpoint | PASS (emulator) | Process death step 2: restored 14,042 ms, stored 14,042 ms |
| Unexpected-kill loss bounded by cadence plus measured scheduling tolerance | PASS (emulator) | 3,082 ms lost; worst trail while playing 4,997 ms; bound 6,500 ms |
| Unique entries; authored duplicates preserved (QUE01) | PASS | Move, remove and restore of duplicate entries, JVM and emulator |
| Remove current: following, otherwise preceding; final leaves empty paused; repeat and shuffle kept | PASS | PlaybackStateTest on a real player (last current, middle current, repeat all, only entry) and the rule table; emulator through the gateway |
| Pause during resolution or recovery clears autoplay; skip cancels obsolete work | PASS for the queue-opening path; existing behaviour for recovery | Tests above. Recovery already read the user's play/pause choice at the moment it applies (replaceFailedStream, live fallback, network retry); not changed and not retested here beyond the existing StreamRecoveryTest and NetworkRecoveryTest |
| Stop on removal from recents; paused restoration after restart | PASS | Unchanged code; ServiceStopDiagnosticsTest; process death step 2 |
| About 5 s cadence, last sequence wins, bounded lifecycle save | PASS | Unchanged from Q04; cadence measured in step 1 (5 checkpoints in 17 s) |
| Not every queue stream resolved before first playback | PASS (emulator) | 30-entry queue opened paused: no lookup; after play: lookups for 1 or 2 distinct entries only |

Decisions (mine, not the user's):
- Removing the playing entry when nothing follows selects the one before it and pauses. The plan says which entry is selected, not whether it plays; starting an earlier song from its beginning seemed wrong.
- Screens keep reading the older fields (playing, wantsPlayback, error). They are filled from the same refresh as the typed state. Moving screens to status is left to Q11 and Q12.
- Entry identifiers are not stored in the saved queue; a restored queue gets new ones. The saved-queue format is unchanged.
- An addition to the queue is dropped when a replacement of the queue was requested after it.
- The owner in the typed state is the opaque storage key, published only inside the app's own process.
- A diagnostics line was added per stream lookup (stream_resolve_start with the first 8 characters of the random entry identifier).

Known limits:
- Queue edits are checked by entry in the app, then sent as the standard index command; a change by the service in the milliseconds between is not excluded.
- With shuffle on, removing every copy of a song leaves the next entry to the player.
- While waiting for the network the failure text is still shown, as before; the status says Recovering. Q07 replaces that path.
- With the network off, Playing was observed only for on-device audio; provider entries go from Resolving to Recovering or Failed.

BLOCKED:
- The same checks on a physical phone and with real provider streams (rapid taps on real albums, pause while opening, queue edits while radio adds songs, kill while a provider queue plays). Needs an authorized device, explicit approval for installation, and a test account. Rerun: install the debug APK; tap three albums quickly and confirm only the last plays; tap an album and pause at once and confirm it stays silent; queue the same song twice and move and remove one copy; play, force-stop from Settings, reopen and confirm the queue is back paused within about 6 s of where it was.
- Carried from Q04 and Q05: upgrade over a real earlier install; privacy actions on a phone; phone-only checks compiled only; NativeIslandInstrumentation not ported.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once to free memory. Data created and removed was synthetic fixture data on the emulator only. No physical device, real account, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 2128b2a on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q07, when the user says to start it.

## Plan v1.1 additions (11 October 2026)
Plan revised to v1.1; see plan section 22. No application code changed by the revision.
New packages, all Status: TODO, implementation not yet authorized (the 9 October authorization covers Q03-Q18 only):
- Q05A local export/import. Dependencies: Q03, Q04, Q05.
- Q05R interim release R1. Dependencies: Q00-Q05 PASS.
- Q08A loudness normalization. Dependencies: Q08.
- Q10A provider canary and response runbook. Dependencies: Q10.
- Q12A update notice. Dependencies: Q01, Q12.
- Q15A exit-reason diagnostics. Dependencies: Q15.
Changed inside existing packages: Q14 now includes a Baseline Profile (optimization rule 8).
New contract rule 16: commit each package locally on its branch before starting the next. Q00-Q02 work is still uncommitted on main; committing it is the next step before Q03 and has not been done. Pushing remains unauthorized.
