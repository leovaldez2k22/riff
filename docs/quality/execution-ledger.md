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
Status: Q03 PASS (see the Q03 section); Q04 PASS locally with open items (see the Q04 section); Q05 PASS locally with open items (see the Q05 section); Q06 PASS locally with open items (see the Q06 section); Q07 PASS locally with open items (see the Q07 section); Q08 PASS locally with open items (see the Q08 section); Q09-Q18 TODO. Lettered packages Q05A and Q08A PASS locally with open items (see the Q05A and Q08A sections)
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

## Q07
### Q07 completion (11 October 2026)
Status: PASS locally and on the disposable emulator. Physical-phone and live-provider checks are BLOCKED (below).
Source: 69d7b931f6c38aa01a164740a0a1b65fc883110e dirty, digest e16f428c57d8d5a88eb8f1816949c34d2a568ff9f96a29e38d7166e2af5a9137. Evidence root: artifacts/quality/69d7b931f6c38aa01a164740a0a1b65fc883110e-dirty-e16f428c57d8/
User instruction on 11 October 2026: start Q07 (standing rule: complete every aspect of the phase). The lettered v1.1 packages were not started and remain unauthorized.

Implemented (PlaybackRecovery.kt new; MusicPlaybackService.kt, PlaybackGateway.kt, PlaybackState.kt, PlaybackProtocol.kt, NowPlaying.kt, RiffApp.kt, StreamRecovery.kt, RemoteCipherClient.kt, YouTubeMusicProvider.kt, DeferredProviderSource.kt; NetworkRecovery.kt removed):
- classifyFault sorts every playback failure into one of ten classes: Offline, Timeout, RejectedStream, AttestationExpired, SessionExpired, RateLimited, ProviderUnavailable, SolverUnavailable, UnsupportedMedia, LocalFileMissing. A classified fault holds the class, the source, an HTTP status, a Retry-After value and a profile; no exception text.
- RecoveryEpisode is the one budget per selected entry: 3 automatic attempts, delays 0, 1 s and 3 s with up to 250 ms injected jitter, 60 s of active time. Attestation is refreshed once (not for a profile without a token), a 403 or 410 stream is refreshed once, after that the rejected profile is excluded. Retry-After is honoured up to 60 s and not counted as active time; longer stops with Retry.
- RecoveryCoordinator runs one episode at a time. Offline waits without limit for a validated connection that stays 500 ms. A pause holds it; play afterwards starts over. Another entry, a replaced or emptied queue ends it and a late lookup is dropped. A failure reported while it waits or tries starts nothing. A recovery that lasts less than 10 s continues the same episode. A player that never answers and a lookup that hangs both end at the 60 s limit.
- Session expiry, unsupported media and missing files stop at once. Session expiry offers Sign in; signing in starts nothing.
- Recommendation entries may be passed over at most 3 times in a row; entries the user queued stop with Retry and Skip.
- The service's three retry paths (recoverProviderStream, retryLiveProfile, network recovery with its own timers and callback) and StreamRecoveryBudget are removed. The service implements RecoveryHost and publishes recovery, failure and recoveryHeld in the session extras. A RETRY_PLAYBACK command retries the selected entry in place.
- The app no longer derives a failure from the player's own error. Failure texts follow plan section 20.6, with actions (Retry, Skip, Sign in, Redownload, Remove). "Connection lost; waiting to reconnect" is a notice, not a failure.
- Solver failures have their own exception type. The provider's generic lookup failure keeps its cause so it can be classified; the cause is not shown or logged.
- Docs: docs/quality/recovery.md (new), docs/quality/playback-state.md, CHANGELOG.

Verification:
- Debug (q07-debug-01/manifest.json): PASS_LOCAL. 520 JVM tests / 71 suites, zero failures, errors or skips (Q06: 488 / 71). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q06-debug-02. APK SHA-256 ddffc5454889f2563e519fb351ef356bef071fa668230b8452d1787c0d5f7914.
- New JVM tests: PlaybackRecoveryTest (36, virtual time), three in ComponentUiTest. Removed with their code: NetworkRecoveryTest (3) and the four budget tests of StreamRecoveryTest; their cases are covered in PlaybackRecoveryTest.
- Emulator (q07-debug-fixture-02/manifest.json, log q07-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 16 tests, 85.969 s: the 14 of Q06 plus two RecoveryDeviceTest. Test APK SHA-256 53e5e0a21e7aea8d0b91c4c51aa49adda7c9d9fbeccdecb334d26c1050cffc43. riff_q02_fixture, API 31, network disabled and confirmed unreachable, synthetic entries, fixture audio only. The service's recovery events of the run are in q07-recovery-events-01.txt (13 lines).
- Release (q07-release-01/manifest.json): PASS_LOCAL. junit {"suites": 71, "tests": 520, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q06-release-01. APK SHA-256 b139a2123c43d83de23e1a3a8c3a0f99057cefc6402c3ab9b3f357ba59ae2940. Shrunk unsigned release 26.43 MiB. No device run of the release artifact.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only. The stress fault injection (SabrFaultInjection) still enters through the player error and is now classified as AttestationExpired; it was not run.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts, preserved:
- q07-debug-fixture-01/manifest.json: FAIL on the artifactIdentity gate, "Signed APK requires apksigner certificate verification". The binding step was run from a shell without the toolchain variables, so apksigner was not found. Same APKs and same log bound again with the variables set as q07-debug-fixture-02: PASS. No test was rerun.
- First run of PlaybackRecoveryTest: 1 of 36 failed. The test helper passed only the outer exception instead of the cause chain the service passes. Helper corrected; the classifier was not changed.
- First compile: a missing import. A scripted edit of StreamRecovery.kt did not match because the file has Windows line endings; the file was rewritten instead.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| Every classification | PASS | PlaybackRecoveryTest: each class by its own signal, and the order of the rules |
| No retry storm | PASS (JVM, virtual time) | Exhaustion after 3 attempts at 0, 1 and 4 s, then nothing for 10 minutes; all causes share the 3 attempts; a stream that becomes ready and fails again within 10 s stays in the same episode; the 60 s limit for an unanswered attempt and a hung lookup |
| No simultaneous duplicated recovery | PASS | Seven further failure reports during one attempt: one attempt, one event |
| Connectivity flaps | PASS | Six up/down cycles of 300 ms start nothing; a rise that drops inside 500 ms does not count; a connection lost during a delay costs no attempt; an unreported change is noticed by the 15 s check |
| Pause, skip, switch during retry | PASS | JVM: pause while waiting, during a delay, and one the coordinator was not told about; skip during a delay. Emulator, real service: pause ends the wait and nothing resumes in 3 s; play waits again for the same entry; next ends recovery for the entry left |
| Obsolete completion | PASS | A lookup that completes after the entry was left does not reach the player; the host reporting the entry gone clears the episode |
| Missing bundle | PASS | Download source: storage failure, no attempt, no skip; partial downloads are still never played as downloads (OfflineMusicStore.audio requires the ready marker, unchanged). Emulator: a library file deleted under a queued entry stops as "File unavailable on this device" with 0 attempts |
| No unwanted playback | PASS (JVM and emulator) | Nothing resumes after a pause; a failure while paused is held; an expired session and a later sign-in start nothing; restored queues stay paused (Q06, unchanged) |
| Session expiry offers Sign in | PASS (JVM) | Classification, stop, action list and the action row. The path from the button to the Account screen is one line and was not exercised |
| Retry-After | PASS (JVM) | 45 s honoured twice without using active time; 60 s honoured; over 60 s stops with Retry. The header is read from Media3's InvalidResponseCodeException; no real server answer was seen |
| Recommendation skip limit; authored collections stop | PASS (JVM) | Three skipped, the fourth stops; reset only after 10 s of playback; no skip when paused; an authored entry stops with Retry and Skip |
| Events carry class, attempt, timing only | PASS | Every event matches a fixed key=value form; no profile, entry or address |
| Offline 60 s then online resumes if intent remained (PLY04) | PASS (JVM, virtual time); emulator covers the offline half only | An hour offline uses no attempt, then the attempt is made 500 ms after the connection returns. On the emulator the network stays off, so the resume itself was not observed there |
| Wi-Fi/mobile handover keeps position and intent (PLY03) | BLOCKED | Needs a phone |
| Rejected or expired stream, solver death against the provider (PLY05, PLY06) | BLOCKED | Needs a connection to the provider |

Decisions (mine, not the user's):
- With a validated connection a network error is Timeout and is retried on the back-off; without one it is Offline and waits. The plan names both classes but not how to tell them apart.
- A delay asked by the provider is not counted in the 60 s of an episode, like the offline wait. Otherwise a 60 s Retry-After could never be honoured.
- An episode ends after 10 s of playback, not at the first ready state. The plan does not say; ending it at ready would let a stream that starts and is rejected loop without limit.
- The time the player takes to answer an attempt counts as active time, and an attempt it never answers ends the episode at 60 s. Playback that then starts anyway clears the failure.
- Any pause holds recovery, including the sleep timer and loss of audio focus, not only a button.
- Play after a hold or a stop is a retry with a full budget, also from the notification, a headset or a widget. Before, play did nothing for a failed entry outside the app.
- Retry now keeps the queue. The old Retry replaced the queue with the one song.
- Only entries added by recommendations are passed over, and only while the service that added them runs. A restored queue is treated as queued by the user.
- A failure while paused is not shown unless it is one that is never retried.
- An age-restricted item is UnsupportedMedia. It does not offer Sign in.
- Without a connection a 403 or an attestation failure is treated as Offline.
- Redownload and Remove delete the unusable download files at the user's tap, then try the entry again.
- The old failure texts were replaced by those of plan section 20.6. PlaybackFailureKind lost ProviderTrack, LiveStream and Player.
- The network callback is registered for the life of the service instead of only while recovering.
- SabrRecoveryPolicy.sabrAttestationRetryId is unused by the app and was left in place with its tests.

Known limits:
- A VPN or captive portal that reports itself validated counts as online.
- A provider failure with no status and no known message is retried three times even if the item will never play.
- The Redownload, Remove and Sign in actions were not exercised end to end.
- On the emulator, provider entries only ever wait; no online retry of a provider stream was observed outside the JVM tests.

BLOCKED:
- PLY03 and PLY04 on a physical phone. Needs an authorized device and explicit approval for installation. Rerun: install the debug APK; play a provider song on Wi-Fi, walk out of range onto mobile data and confirm playback continues near the same position; turn on airplane mode for 60 s while playing, confirm "Connection lost; waiting to reconnect", turn it off and confirm playback resumes at the same position without a tap; repeat and press pause during the wait, then confirm nothing resumes when the connection returns. Collect the recovery lines of the playback event log.
- PLY05 and PLY06 against the provider. Needs a connection to the provider and a test account. Rerun: stress build, send the attestation fault through StressDiagnostics while a song plays and confirm one "recovery class=attestation attempt=1 action=attestation" line and uninterrupted intent; kill the solver process during a lookup and confirm at most three attempts and "Online playback resolver unavailable" if it stays down.
- Carried from Q04 to Q06: upgrade over a real earlier install; privacy actions on a phone; the Q06 phone checks; phone-only checks compiled only; NativeIslandInstrumentation not ported.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once before the stress build. Data created and removed was synthetic fixture data on the emulator only. No physical device, real account, provider request, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 5ed2429 on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q08, when the user says to start it.

## Q05A
### Q05A completion (11 October 2026)
Status: PASS locally and on the disposable emulator. The system document picker and a transfer between two phones are BLOCKED (below).
Source: e593d02cdf4dcf74434dda7c7c74994f1fbd31be dirty, digest prefix 5021be823261 (full value in the manifests). Evidence root: artifacts/quality/e593d02cdf4dcf74434dda7c7c74994f1fbd31be-dirty-5021be823261/
User instruction on 11 October 2026: start Q05A (standing rule: complete every aspect of the phase). This authorizes Q05A only. Q05R, Q08A, Q10A, Q12A and Q15A were not started and remain unauthorized.

Implemented (LocalDataTransfer.kt and LocalDataTransferScreen.kt new; PrivacyDataScreen.kt, AppContainer.kt, ShellScene.kt, LocalDataController.kt, LocalCollections.kt, SearchHistory.kt, OfflineMusicStore.kt, NotInterested.kt, AccountSession.kt, YouTubeMusicProvider.kt, AccessibilitySettings.kt, Crossfade.kt, LibraryHistoryScreens.kt):
- Settings, Privacy and data has a section "Move to another phone" with Export local data and Import local data. Both use the system document picker. No storage permission is requested and the manifest is unchanged.
- One file, JSON Lines, schema number 1: a header, one section per owner (device, signed-out listening, each account), an end mark. Limits: 16,384 characters per line, 128 MiB per file, 100,000 plays and 2,000 of every other kind per section.
- Contents: device settings (13 keys, only those the user changed), items marked Not interested, tracks saved in the app, recently played, searches, listening history, and downloads as identifier, name, artist and album. The user chooses the owners and sees counts. Other accounts on the phone are offered but not preselected.
- Not in the file: cookies, tokens, account identifiers or names, storage keys, stream addresses, audio, image bytes, diagnostics, the saved queue, and the three download settings. An account's section is named by the opaque digest of its identity. Artwork is written only as a plain address on a provider image host.
- The export step states, before the picker opens, that the file is not encrypted, holds listening history and searches, and can be read by anyone who has it.
- Export is assembled in private storage and copied to the chosen document only when complete.
- Import copies the document to private storage up to the limit, reads it once for a preview, and reads it again to store. A file with no header, a higher schema number, no end mark or over the limit is refused whole. A record that cannot be read is skipped and counted.
- Ownership: device and signed-out sections go to the same owner. An account's section goes to the account signed in now when the digest matches, also under an older storage key. Any other account's section is left out unless the user ticks it and confirms a second prompt; then it is added to the account signed in now. Without a verified account it cannot be imported. The destination is decided again when the import runs.
- Import adds only. History is one transaction, deduplicated by event identifier, never beyond what the owner may keep, and without plays older than the history period. Saved tracks, searches, downloads and hidden items are one write each, with what is already there kept first. Downloads become rows with a name and nothing else. Nothing is queued, played or sent to the provider.
- Cancel is honoured until the history transaction commits. A failure there changes nothing. A later kind that cannot be stored is named and the others are still stored; the same file can be imported again.
- Runs on the IO dispatcher with progress, and shares the lock of the clear and delete actions.
- Events: local_data action=export|import_open|import with counts and fixed words only.
- Docs: docs/quality/local-export-import.md (new), docs/quality/privacy-controls.md, docs/privacy.md, CHANGELOG.

Also changed, found while doing this:
- PrivacyDataScreen (Q05) caught the cancellation of a superseded reading and showed it as empty counts. It could make the list lose a row for a moment. The cancellation is now passed on.
- History replays an entry for audio on the phone only when the file's title matches as well as its number. After an import from another phone the number alone could name a different file.

Verification:
- Debug (q05a-debug-01/manifest.json): PASS_LOCAL. 547 JVM tests / 72 suites, zero failures, errors or skips (Q07: 520 / 71). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q07-debug-01. APK SHA-256 d52fa119c9f7c4ce9996fd95c90e49a89eeabe470637cc6c2c38e63749be4da5.
- New JVM tests: LocalDataTransferTest (22), five in PrivacyDataScreenTest.
- Emulator (q05a-debug-fixture-01/manifest.json, log q05a-instrumentation-02.log, install-01.log): PASS_LOCAL, all five gates PASS. 18 tests, 114.435 s: the 16 of Q07 plus two LocalDataTransferDeviceTest. Test APK SHA-256 f2a0c5f0e3e08b3168522e8d5a5b28b4691d2e69511c8c119bf415c2417fa1aa. riff_q02_fixture, API 31, network disabled and confirmed unreachable, synthetic identities.
- Release (q05a-release-01/manifest.json): PASS_LOCAL. junit {"suites": 72, "tests": 547, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q07-release-01. APK SHA-256 2b871fc1aafc36dd6eb0cc836bb5111e913f361e7cb06cdad2f78d23d4bf0d81. Shrunk unsigned release 26.51 MiB (Q07: 26.43). No device run of the release artifact.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts, preserved or recorded:
- q05a-instrumentation-01.log: 18 tests, 1 failure. RecoveryDeviceTest.aFileThatHasGoneIsAStorageFailureThatIsNotRetried (a Q07 test) timed out waiting for the fixture file to start playing. It was the first test after a cold boot of the emulator; q05a-logcat-01-excerpt.txt shows another app of the image in blocking garbage collection during those seconds. Run again on the same install without any change: 18 of 18 (q05a-instrumentation-02.log). The cause is inferred from the log, not proven. The failed run is not bound to a manifest.
- First run of LocalDataTransferTest: 4 of 22 failed. Three were a defect: a section that is left out was counted once per record instead of once. Fixed in the code. One was the test's own seed, which put the storage key in a search text. Fixed in the test.
- First run of the new screen tests: 1 failed. The test expected the export dialog to close when the user backs out of the picker; it stays open so the user can choose again or cancel. Test corrected.
- One run of PrivacyDataScreenTest.earlierDataIsLinkedWhileSomethingIsWaiting failed with an index error in the list. Cause: the cancelled reading described above. It failed once in seven runs before the fix and passed in the three full runs after it (one local, the debug and the release evidence runs). Three runs do not prove a rare failure gone.
- First lint run before evidence: 1 error (a literal byte-order mark in a source file) and 1 new warning (a cursor closed by use). Both corrected before the evidence runs.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| EXP01: export then import on a clean install restores settings, collections and history | PASS (JVM, clean stores); PASS on the emulator with the data deleted first, not a reinstall | LocalDataTransferTest.aCleanInstallMatchesTheSourceForEverythingIncluded; LocalDataTransferDeviceTest.exportThenImportAfterTheDataIsGoneRestoresItAndStartsNothing |
| EXP01: no credentials or streams in the file (I01) | PASS | The file is scanned for session values, cookie and token words, stream hosts and parameters, storage keys, and any address that is not a plain image-host address |
| EXP02: mismatched account is never silently adopted | PASS (JVM, Compose, emulator) | Left out by default; added only with the tick and the second prompt; not offered without a verified account; decided again at import |
| EXP02: corrupt, truncated, newer and oversized files are rejected safely, existing data unchanged | PASS (JVM, emulator) | Refused whole with the reason; a file cut at every 97th byte; 25 kinds of unreadable record skipped one by one. Oversized is tested with a lowered limit and an endless stream; a real 128 MiB file was not used |
| Duplicate import | PASS | The same file twice adds nothing; state identical |
| Cancel mid-import | PASS | Cancel when every play is written but not committed: nothing changed. Cancel after the commit: the import finishes |
| Full disk | PASS (injected failures) | While copying, while writing history, in a later kind, and during export. No real full disk was produced |
| Import never deletes | PASS | Limits never push out what is there; a shorter history period is not applied; a later prune removes nothing |
| Imported downloads are names only; nothing plays or reaches the provider (I12) | PASS (JVM, emulator) | Rows in state metadata, no files, download count 0; on the emulator the player stays empty and no queue is saved; the import code has no player and no network client |
| Progress and cancellation off the main thread | PASS (JVM) for cancellation; progress is shown but its display was not asserted | |
| System picker, no storage permission | BLOCKED for the picker itself | The manifest has no new permission. The picker is replaced in tests |

Decisions (mine, not the user's):
- JSON Lines, 128 MiB, 16,384 characters per line. The plan asks for one versioned file with a named schema and a size ceiling and leaves the rest open.
- Items marked Not interested are included in the device section. The plan's list does not name them; without them a restored phone suggests again what the user hid.
- A download carries its name, artist and album next to the identifier. The plan says durable IDs only; an identifier alone would give a row without a name.
- Automatic downloads, download on mobile data and the download budget are not exported or imported.
- Only the account signed in now can match a section. A section of another account known to this phone is treated as another account's.
- A history period from the file is applied only when it is not shorter than the one in force.
- Plays older than the history period in force are not imported, and never more than the owner may keep.
- The second prompt adapts the Q04 wording to all data instead of repeating it word for word.
- Other accounts are not preselected for export.
- The saved queue is not exported.
- A document that received no complete export is removed again by the app.
- A track record in a signed-out section is refused, because signed-out listening saves no tracks.
- NotInterestedStore's constructor and LocalDataController's lock became internal so that tests and the import can use them.

Known limits:
- The file is not encrypted and not signed.
- Imported download names are not shown anywhere and nothing is downloaded.
- The account digest is the same on every phone; someone who knows an account's identifier can check a section against it.
- A large import holds the history database's write lock while it reads the file.
- Leaving the screen during an export stops it and leaves the empty document.

BLOCKED:
- The system document picker end to end. Needs a person at a device, or UI automation of the picker, and for a phone explicit approval for installation. Rerun: install the debug APK; Settings, Privacy and data, Export local data; confirm the warning text, choose a folder, confirm the notice and that the file exists and opens as text; Import local data, pick the file, confirm the preview and the summary; start an export and cancel it in the progress dialog, confirm no file remains; confirm Android asked for no storage permission.
- A file moved between two physical phones, with History replay of an entry for audio on the phone. Needs two authorized devices.
- Carried from Q04 to Q07: upgrade over a real earlier install; privacy actions on a phone; the Q06 and Q07 phone and provider checks; phone-only checks compiled only; NativeIslandInstrumentation not ported.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once before the stress build. Data created and removed was synthetic fixture data on the emulator only. No physical device, real account, provider request, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 559a808 on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q08, when the user says to start it.

## Q08
### Q08 completion (12 October 2026)
Status: PASS locally and on the disposable emulator. Listening, ducking, calls, headphone and Bluetooth routes, streamed songs and the solver against the provider are BLOCKED on a phone (below).
Source: e882a56b29d6e388d454a91367289941b3cbf34f dirty, digest prefix b16332428914 (full value in the manifests). Evidence root: artifacts/quality/e882a56b29d6e388d454a91367289941b3cbf34f-dirty-b16332428914/
User instruction on 11 October 2026: "start q08 followed by q08a" (standing rule: complete every aspect of the phase). On 12 October 2026 the user added "Run next phase" while Q08 was being verified; I read it as go on to Q08A after Q08, which was already the instruction. Q05R, Q10A, Q12A and Q15A were not started and remain unauthorized.

Implemented (Crossfade.kt, YouTubeMusicProvider.kt, AppContainer.kt, CipherBridgeInstaller.java comment, proguard-rules.pro comment):
- CrossfadeOwner: the one holder of the player's level during a blend. It holds the outgoing and incoming media IDs, progress, length and the level to put back. One blend at a time. The level is handed back exactly once; a later release, or the cleanup of a blend that was already ended from outside, gets nothing and writes nothing. A blend that never reached the handoff holds nothing. The equal-power curve, the envelope and the alignment code are unchanged and now run through the owner.
- The level is read when the blend takes it, not when preparation began (up to about 40 s earlier).
- Before Q08 the blend's cleanup wrote the level a second time after cancel() had restored it, and wrote it even when the blend had never taken it. Both are gone.
- Repeat-one is now asked again at every step up to the handoff. Before, turning it on after preparation had begun (within the fade length plus 12 s of the end) still blended into the next song. Found by the new device test.
- Both gains are clamped to 0..1. Before, the outgoing gain at the very end was about -4e-8 from rounding.
- A copy of the outgoing song that cannot be built is a skipped blend with a log line. Before, the exception left the blend's coroutine unhandled.
- Every skipped or cancelled blend logs a reason; the copy logs crossfade_tail_created and crossfade_tail_released.
- Solver, ADR-Q05: if the bridge to the solver process cannot be installed, online stream resolution is refused with SolverUnavailable. Before, the in-process cipher was used silently, for stream extraction and for the HLS n-parameter. The extractor still has to be built with one of the library's own ciphers; that object is never called when the bridge is missing. The prewarm is skipped as well.
- The reflection seam is still in use. innertubex 0.7.2 has no public way to supply a cipher. Nothing here claims otherwise.
- scripts/quality/check_solver_seam.py checks a shrunk release's mapping: the field kept its name and type, the bridge code is present, the service kept its name. Five tests of its own.
- Docs: docs/quality/crossfade-and-audio.md (new), CHANGELOG.

Not changed, verified as they are: the envelope (never compresses, 50 ms per update), alignment, the sleep timer, the booster and its limiter, Media3's focus and noisy-route handling, the visuals (full player, mini player and queue all draw from the same blend; reduced motion swaps at once), RemoteCipherClient's retry and crash-loop guard.

Verification:
- Debug (q08-debug-02/manifest.json): PASS_LOCAL. 563 JVM tests / 74 suites, zero failures, errors or skips (Q05A: 547 / 72). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q05a-debug-01. APK SHA-256 a4a0f155cc820196e656cac99eee9bd55b7517a26667149c65454ba65c838621.
- New JVM tests: CrossfadeOwnerTest (11), SolverFailClosedTest (4), one in VolumeBoostTest, test_check_solver_seam.py (5, Python, not part of the Gradle count).
- Emulator, bound (q08-debug-fixture-01/manifest.json, log q08-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 31 tests, 248.046 s: the 18 of Q05A plus 13 CrossfadeFixtureDeviceTest (real service, second player and effects). Test APK SHA-256 9920bc2bdad20273650d2edae23b33217895488dfb58d89bc17c44c3878ed3e9. riff_q02_fixture, API 31, network disabled and confirmed unreachable, signed out or synthetic identities. In this run no blend was given up (q08-device-notes-01.txt): 1 s blend 985 ms declared, 6 s 5992 ms, 12 s 11977 ms, 3 s with boost at 300 percent 2967 ms; each took its length within 12 ms as far as it was followed.
- Emulator, not bound (q08-two-player-instrumentation-01.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK. Same install, same APKs. These were phone-only until now.
- Release (q08-release-01/manifest.json): PASS_LOCAL. junit {"suites": 74, "tests": 563, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q05a-release-01. APK SHA-256 8945c7a34a84bd7bd7fe912033d3de7f413fe94bd3d313a1d576eece92d3ce43. Shrunk unsigned release 26.51 MiB (Q05A: 26.51). No device run of the release artifact.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS. R8 renames the extractor class and merges the bridge into another class; the field cipherService keeps its name and type.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

The emulator is not a timing reference for two decoders:
- The service starts a blend only when the two players agree on their position within 80 ms for 200 ms, and gives the blend up safely if they do not before the song ends. On this emulator (no audio output) the two audio clocks drift against each other by up to about 6 percent, so a blend is sometimes given up. It got worse the longer the emulator had been running.
- CrossfadeFixtureDeviceTest therefore plays the queue again, up to four times, when a blend was given up, after checking what a given-up blend must leave: the level unchanged, no copy left, the next song playing alone, a logged reason. A run in which every attempt is given up fails.
- CrossfadeDeviceTest (two bare players) has no such retry and stays outside the bound run.
- Blend lengths are measured against the progress read with the first sample, because the first read comes a moment after the blend began.

Failed attempts, preserved or recorded (attempts/ in the evidence root unless another place is named):
- q08-xfd-01.log: 13 tests, 2 failures. Repeat-one blended into the next song: the defect fixed above. A 12 s blend ran 16.1 s; it was the first test after the app started, no stall was logged, and it did not recur (11.9 s in every later run). Cause inferred: updates more than 50 ms apart extend a blend by design. Not proven.
- q08-xfd-03.log and q08-xfd-04.log: CrossfadeDeviceTest's 1 s scenario, first test of the run, failed its fixed step bound (0.08 per read). The copy had played to its end before the envelope finished. The test now scales the bound with the time between reads and exempts a copy that has ended. Test change only.
- q08-xfd-04.log: my test measured a 3 s blend as 2802 ms because it started timing late. Measurement corrected as described above.
- q08-xfd-05.log (9 of 28 failed) and q08-xfd-06.log (8 of 28): blends given up on an emulator that had run for more than 40 minutes. q08-xfd-07.log after a restart: 1 of 28.
- Evidence root e882a56b29d6e388d454a91367289941b3cbf34f-dirty-699e30b98e22 holds the first evidence attempt: q08-debug-01 (PASS_LOCAL, superseded because a test changed afterwards), q08-crossfade-instrumentation-01.log (28 tests, 3 failures, all CrossfadeDeviceTest scenarios whose blend was given up or timed out) and q08-instrumentation-01.log (31 tests, 1 failure: a 6 s blend declared 5648 ms, which my test refused; the overlap may be shorter than the setting when alignment finishes late, and the test now accepts that). Nothing from that root is bound.
- q08-bound-try-01.log: 31 tests, 1 failure, a blend given up; this led to the retry. q08-bound-try-02.log: 13 of 13 with four blends given up and checked.
- First run of CrossfadeOwnerTest: 4 of 11 failed on the negative gain described above. Fixed in the code.
- SolverFailClosedTest.aSolverThatKeepsDying failed once in a run with other classes (4 solver requests instead of 3) and did not fail in 40 repeats or four further runs. Robolectric hands the test's fake binder to every client in the process, so a provider left by an earlier test can reach it. The fake now scripts and counts only the request under test. The cause is inferred, not proven; the one failure's output was not kept.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| XFD01: 1, 6 and 12 s blends are gradual, valid, equal power | PASS (JVM; emulator for level and progress) | CrossfadeOwnerTest: gains finite and in range, squared sum within 0.03 of one, progress never decreasing, length within max(150 ms, 5 percent). CrossfadeFixtureDeviceTest: level follows the curve, never ahead of the clock |
| XFD02: buffering, late start, early end | PASS (JVM; emulator two-player test for buffering before the blend and an ended or restarted copy) | Held levels, no jump on resume, never shortened; an ended copy stays silent and the incoming song does not jump. The early-end exception to equal power is stated in the doc and the code |
| XFD03: pause, seek, skip or select, off, account switch cancel and release the copy | PASS (emulator, real service) | Level back exactly, blend gone from the screen state, created and released counts equal, nothing written afterwards |
| XFD04: boost and sleep: no conflicting owners, finite output | PASS (JVM; emulator for boost at maximum and sleep timer during a blend) | Generated tones at off, 200 and 300 percent stay in 16-bit range, sign kept, limiter monotonic. Sleep timer fades from the level the blend handed back and restores it |
| Repeat-one, short tracks, sleep timer never start a blend | PASS (JVM; emulator for repeat-one) | |
| Cancellation restores the level exactly once | PASS (JVM, emulator) | |
| Visuals follow the live blend; reduced motion swaps at once | PASS (JVM, Compose) | SongPresentationTest, unchanged. Progress shown is the owner's value. Not looked at on a screen |
| Focus loss and a short interruption | PASS (emulator, another focus request from the test) | Media3 pauses or holds; the blend ends; the level is back; playback returns after the short one |
| Ducking, a real call, unplugging headphones, Bluetooth | BLOCKED | Needs a phone |
| Pinned cipher dependency contract | PASS (JVM) | CipherBridgeTest, SolverFailClosedTest.thePinnedLibraryAcceptsTheBridge |
| Shrunk release keeps the seam | PASS (mapping of the release that was built) | Not run on a device |
| Bridge failure becomes SolverUnavailable | PASS (JVM) | Typed cause, classified SolverUnavailable, zero HTTP requests, event logged |
| Solver death leaves audio intact, bounded reconnect | PASS (JVM, with a fake solver) for the bound and the typed failure; BLOCKED for a real solver crash during playback | One retry per request; three deaths pause the solver. SolverIsolationInstrumentation needs the network and the provider |
| No leaked copies | PASS (emulator) | Created and released counts equal in every test |

Decisions (mine, not the user's):
- When a blend still holds the level at its own cleanup, the level is put back even if the sleep timer is set. The service always ends the blend before it sets the timer, so this path is not reached there; if it were, the timer adopts the restored level on its next step. The old code left the level where the blend had it.
- Fail closed for every online resolution when the bridge is missing, including streams that would not have needed a challenge.
- The emulator test retries a blend that was given up, four times at most. Without that, a bound run depends on the emulator's audio clocks.
- CrossfadeDeviceTest's status messages became log lines, because the collector reads a status with code 0 as a finished test.
- No limiter was added for the sum of two songs in a blend, and the booster's wording in Settings was left alone. Both are described in the doc.

Known limits:
- During a blend, Media3 ducks the main player only. Whether the copy is lowered depends on Android. Not heard on a device.
- Two full-scale songs can add up to 1.41 times full scale in the middle of a blend. Riff does not limit that.
- Updates more than 50 ms apart extend a blend. If the copy reaches the end of its audio first, it ends there.
- The equal-power fade does not even out songs mastered at different levels. That is Q08A.

BLOCKED:
- Listening on a phone, with crossfade at 1, 6 and 12 s, boost off, 200 and 300 percent, on speaker, wired and Bluetooth. Needs explicit approval for installation. Rerun: install the debug APK; play an album of local or downloaded songs; let three changes blend at each length; pause, seek, skip and turn crossfade off during a blend; set a sleep timer during a blend; turn repeat-one on five seconds before a song ends. Expected: a smooth change, no click, no doubled passage, no jump in level, the full level afterwards, and no blend with repeat-one.
- Focus on a phone: receive a call, trigger a navigation prompt or notification sound, unplug headphones and switch Bluetooth off, each during a blend. Expected: Android's usual pause, hold or duck; the next song alone afterwards at the level from before.
- Streamed songs and the solver: CrossfadeServiceDeviceTest's streamed cases and SolverIsolationInstrumentation need the network, the provider and, for the phone tests, the user's saved queue.
- The release APK on a device.
- Carried from Q04 to Q07 and Q05A: upgrade over a real earlier install; privacy actions on a phone; the Q06 and Q07 phone and provider checks; the system document picker; phone-only checks compiled only (AudioContinuity, CrossfadeService, OfflineSmoke, PlaybackAcceptance); NativeIslandInstrumentation not ported.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once before the stress build. Fixture tones were added to and removed from the emulator's media library; crossfade and boost settings were set and cleared on the emulator only. No physical device, real account, provider request, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build only.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: fde01db on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q08A.

## Q08A
### Q08A completion (12 October 2026)
Status: PASS locally and on the disposable emulator. The provider's loudness value on a live response, and listening on a phone, are BLOCKED (below).
Source: d158dbe110730ee150cb74832b103978f8968fe9 dirty, digest prefix 061178bee5ae (full value in the manifests). Evidence root: artifacts/quality/d158dbe110730ee150cb74832b103978f8968fe9-dirty-061178bee5ae/
User instruction on 11 October 2026: "start q08 followed by q08a" (standing rule: complete every aspect of the phase). On 12 October 2026 the user added "Run next phase", read as go on to Q08A after Q08. Q05R, Q10A, Q12A and Q15A were not started and remain unauthorized. Q09 has not been started.

Established first (action 1): before this package Riff had no loudness normalization. Nothing in the source read a loudness value, a ReplayGain tag or measured audio. The only scaling of samples was the volume booster.

Implemented (LoudnessNormalization.kt new; VolumeBoostProcessor.kt, AudioBoostSettings.kt, MusicPlaybackService.kt, YouTubeMusicProvider.kt, OfflineDownloadEngine.kt, OfflineMusicStore.kt, SettingsScreen.kt, LocalDataTransfer.kt):
- Source of the value: innertubex 0.7.2 ExtractedStream.loudnessDb, which the library fills from the player response (playerConfig.audioConfig.loudnessDb). ProviderStream carries it as loudnessDb, null when absent or invalid. No audio is analysed on the device and no tags are read.
- Rule (normalizationGain): off, missing, not finite or outside -60..60 dB means unchanged. Zero or negative means unchanged: nothing is ever turned up. Positive x means x dB down, at most 12 dB (gain never below 0.2512).
- One point in the chain: PcmVolumeBoost, the stage that already held the booster, now multiplies booster gain (1 to 3) by the track's gain (0.25 to 1). Below 1 it is plain multiplication with no limiter; above 1 the booster's soft knee limits the product as before. With both at 1 the stage still passes samples through bit for bit.
- The player's level is never written by normalization. It stays with the crossfade owner and, after a blend, the sleep timer.
- Each player has its own stage. The outgoing copy in a blend is given the outgoing song's gain when it is loaded and keeps it; the main player takes the incoming song's gain.
- A track starts at its gain (the stage resets to it on flush). A change of the setting or of the booster mid-track ramps over the stage's existing 30 ms.
- The service learns a value per queue entry when the entry's stream or download is opened, and keeps at most 2048. It logs normalize gain_db=... when the main player's gain changes and normalize_outgoing gain_db=... for the copy.
- Downloads: the value is written to a file named loudness in the bundle's folder before the bundle is marked ready. It holds one number. A bundle without the file, or with a damaged one, plays unchanged; nothing is written into existing bundles.
- Setting: Normalize volume in Settings between the booster and Crossfade, off by default (key normalize in the audio_boost preferences). Label: "Turns down louder songs from YouTube Music when it supplies loudness data".
- Export and import (Q05A) include the switch: 14 device settings travel, where the Q05A entry says 13.
- Docs: docs/quality/crossfade-and-audio.md (new section and test rows), docs/quality/local-export-import.md, CHANGELOG.

Verification:
- Debug (q08a-debug-01/manifest.json): PASS_LOCAL. 583 JVM tests / 75 suites, zero failures, errors or skips (Q08: 563 / 74). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q08-debug-02. APK SHA-256 4904e473a49f3b759f1d69f50091eccb76735c94bf9e698b483ff0c691cb5a6a.
- New JVM tests: LoudnessNormalizationTest (20). LocalDataTransferTest now seeds the new switch and expects 7 changed settings where it expected 6.
- Emulator, bound (q08a-debug-fixture-01/manifest.json, log q08a-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 40 tests, 263.248 s: the 31 of Q08 plus 9 NormalizationDeviceTest (real service; downloads built in the test from generated tones with a scripted value file). Test APK SHA-256 7631402f232754546f15ff9fc965f4c9ba47b4893bb4422daf79f4376aaf2f1a. riff_q02_fixture, API 31, network disabled and confirmed unreachable, signed out. The crossfade classes ran first, soon after boot.
- In the first run of NormalizationDeviceTest (before the evidence run, same sources apart from docs) the log showed the blend case as intended: copy created, normalize_outgoing gain_db=-6.0, crossfade_start, normalize gain_db=-3.0, copy released.
- Emulator, not bound (q08a-two-player-instrumentation-02.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, on a freshly started emulator.
- Release (q08a-release-01/manifest.json): PASS_LOCAL. junit {"suites": 75, "tests": 583, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q08-release-01. APK SHA-256 84120641a0eb4a53f01523623c3d1cb7ef2dda87e22c656504c0645cf6230796. Shrunk unsigned release 26.51 MiB (Q08: 26.51). No device run of the release artifact.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged from Q08.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts, preserved or recorded:
- attempts/q08a-two-player-instrumentation-01.log: 15 tests, 3 failures in CrossfadeDeviceTest (two fades shortened, one timed out at the handoff). It ran after the 263 s bound run on the same emulator session; the two players' positions were 100 to 230 ms apart throughout. Same class of failure as recorded in Q08 for an emulator that has been running a while. The rerun after a restart passed 15 of 15. CrossfadeDeviceTest uses two bare players and no normalization. The cause is inferred from the Q08 findings, not proven again.
- First compile of LoudnessNormalizationTest failed on argument types in six assertions. Test change only.
- First run: 4 failures. Three were LocalDataTransferTest's expected counts (6 settings, now 7). One was real: BundleLoudness.write did not replace an earlier value where a rename does not overwrite (seen on the build machine's file system; Android's does overwrite). The earlier value is now removed first when the rename fails.
- An edit script failed once with OSError 22 while writing MusicPlaybackService.kt; the file was checked intact and the edit applied again.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| AUD01: normalization on, tracks of different loudness: bounded attenuation, no clipping | PASS (JVM; emulator for the gain the service applies) | Generated tones at three levels with scripted values: 6 dB and 3 dB down within 2 of the expected peak, quieter tone bit exact. Clamp at 12 dB. Every combination of four booster settings, ten values and three signals stays in 16-bit range, keeps its sign and is never louder than without normalization. Emulator: -6.0, -3.0, -12.0 and 0.0 as scripted, per song |
| AUD01: crossfade ownership intact | PASS (JVM; emulator) | The player's level equals the user's level before and after a blend between a 6 dB and a 3 dB song; the copy had -6.0 and never -3.0; copy released. Normalization never writes the level |
| AUD01: boost ownership intact | PASS (JVM; emulator with the booster at 300 percent) | Booster changes do not change the track's gain and the other way round |
| AUD01: sleep ownership intact | PASS by construction and by the unchanged Q08 tests | The sleep fade writes the player's level, which normalization does not touch. CrossfadeFixtureDeviceTest's sleep-timer cases pass unchanged. No test runs the sleep fade with normalization on |
| AUD02: off or value missing: unchanged gain, no failure | PASS (JVM; emulator) | Bit-exact output; no normalize event for a download without a value, with a damaged value, with the setting off, or for on-device audio; no player error |
| XFD04 still passes | PASS | VolumeBoostTest and CrossfadeFixtureDeviceTest unchanged and passing |
| Finite bounded samples in every combination | PASS (JVM) | As AUD01 |
| No step at track start or on a toggle | PASS (JVM) for the stage; see the limit below for gapless changes | First sample already at the gain; a toggle changes each sample by no more than the 30 ms ramp allows |
| Offline bundle with and without the value | PASS (JVM; emulator) | |
| Default off; honest label | PASS (JVM for the default; label as quoted above) | The row was not looked at on a screen |
| Provider field proven with a fixture | PARTLY | The fixture is written by hand to the pinned library's model and decoded with the library's own serializer. It proves which field the library reads. It is not a recorded response. A live value is BLOCKED |

Decisions (mine, not the user's):
- Reference and range: the provider's own zero, 12 dB of attenuation at most. The plan asks for a documented clamp and names no number.
- loudnessDb, not perceptualLoudnessDb. The first is relative to the provider's reference, which is what "reduce toward the reference" needs.
- The gain is set when the player reports the song change, not at the exact sample where the next song begins. Doing it at the sample needs the audio sink's stream boundaries; I judged that too fragile for this package.
- A download's value file is written only when the provider gave a value, and is not removed when a later download of the same song has none.
- The value file is not part of Export local data. Downloads travel as a list to fetch again, and fetching stores the value.

Known limits:
- Gapless change without crossfade: audio already buffered when the change is reported was scaled with the previous song's gain. The first fraction of a second of the new song can be at the old gain, then a 30 ms ramp. Not measured on a phone.
- The value is not checked against the audio. Whether normalized songs sound equally loud has not been heard.
- Songs quieter than the reference stay quieter. That is the attenuation-only rule.
- Downloads made before this package play unchanged until downloaded again.
- Audio from the phone's library is never normalized. No tag-based normalization exists or is claimed.
- With the platform LoudnessEnhancer doing the booster's part, the order is Riff's track gain first, then the platform effect and its limiter. Not heard on a device.

BLOCKED:
- A live provider value. Needs the network and the provider. Rerun: on a build with a connection, play five streamed songs with Normalize volume on and read normalize gain_db in the copied diagnostics; a song with no line was at or below the reference or had no value. Download one, go offline, play it, expect the same line.
- Listening on a phone: a loud and a quiet song back to back with the setting on and off; toggling mid-song; with crossfade at 6 s; with the booster at 300 percent; a gapless album without crossfade for the limit above. Needs explicit approval for installation.
- The release APK on a device.
- Carried from Q04 to Q08 and Q05A: as listed in the Q08 entry.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once before the stress build. On the emulator only: fixture downloads fixtureN001 to fixtureN012 were created in and removed from the signed-out download folder, fixture tones were added to and removed from the media library, and audio and crossfade settings were set and cleared. No physical device, real account, provider request, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Next exact action: Q09. It is inside the 9 October authorization (Q03-Q18) but the user's instruction covered Q08 and Q08A only; wait for "start Q09".

## Plan v1.1 additions (11 October 2026)
Plan revised to v1.1; see plan section 22. No application code changed by the revision.
New packages. Status TODO and implementation not yet authorized unless the line says otherwise (the 9 October authorization covers Q03-Q18 only):
- Q05A local export/import. Dependencies: Q03, Q04, Q05. Authorized by the user on 11 October 2026 ("start Q05A"). Status: PASS locally with open items (see the Q05A section).
- Q05R interim release R1. Dependencies: Q00-Q05 PASS.
- Q08A loudness normalization. Dependencies: Q08. Authorized by the user on 11 October 2026 ("start q08 followed by q08a"). Status: PASS locally with open items (see the Q08A section).
- Q10A provider canary and response runbook. Dependencies: Q10.
- Q12A update notice. Dependencies: Q01, Q12.
- Q15A exit-reason diagnostics. Dependencies: Q15.
Changed inside existing packages: Q14 now includes a Baseline Profile (optimization rule 8).
New contract rule 16: commit each package locally on its branch before starting the next. Q00-Q02 work is still uncommitted on main; committing it is the next step before Q03 and has not been done. Pushing remains unauthorized.
