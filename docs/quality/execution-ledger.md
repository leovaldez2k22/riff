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
Status: Q03 PASS (see the Q03 section); Q04 PASS locally with open items (see the Q04 section); Q05 PASS locally with open items (see the Q05 section); Q06 PASS locally with open items (see the Q06 section); Q07 PASS locally with open items (see the Q07 section); Q08 PASS locally with open items (see the Q08 section); Q09 PASS locally with open items (see the Q09 section); Q10 PASS locally with open items (see the Q10 section); Q11 PASS locally with open items (see the Q11 section); Q10A PASS locally with open items (see the Q10A section, which follows Q11); Q12 PASS locally with open items, device walk-through BLOCKED (see the Q12 section); Q13 PASS locally with open items, TalkBack and keyboard walk-throughs BLOCKED (see the Q13 section); Q14 PASS locally for tooling, Baseline Profile and APK checks, every physical-tier budget BLOCKED and one open finding awaiting a product decision (see the Q14 section); Q15 PASS locally with open items, two of them medium (see the Q15 section); Q16 PASS locally with open items, API 33/36, device, benchmark and hosted CI BLOCKED (see the Q16 section); Q16A PASS locally and on the emulator with open items, phone and real songs not yet checked (see the Q16A section); Q17-Q18 TODO. Lettered packages Q05A and Q08A PASS locally with open items (see the Q05A and Q08A sections); Q12A PASS locally with open items, live feed BLOCKED (see the Q12A section); Q15A PASS locally with open items (see the Q15A section)
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
Commit: 18080c7 on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q09. It is inside the 9 October authorization (Q03-Q18) but the user's instruction covered Q08 and Q08A only; wait for "start Q09".

## Q09
### Q09 completion (12 October 2026)
Status: PASS locally and on the disposable emulator. A real process kill on a phone, Android 14 and 15 background limits, the SABR transfer path and a real provider download are BLOCKED or not covered (below).
Source: 4be57575ca5b794bd521c9ff04275517d4d6c232 dirty, digest prefix e5ba47bf700a (full value in the manifests). Evidence root: artifacts/quality/4be57575ca5b794bd521c9ff04275517d4d6c232-dirty-e5ba47bf700a/
User instruction on 12 October 2026: "Start q09" (standing rule: complete every aspect of the phase). Q05R, Q10A, Q12A and Q15A were not started and remain unauthorized. Q10 has not been started.

Implemented (new: DownloadState.kt, NetworkStoragePolicy.kt, BundleValidator.kt; changed: OfflineMusicStore.kt, OfflineDownloadEngine.kt, OfflineDownloadService.kt, MusicPlaybackService.kt, DownloadsScreen.kt, LibraryShortcuts.kt, AppContainer.kt):
- States: metadata, queued, resolving, transferring, validating, assets, complete, paused, failed, cancelled. "downloading" from earlier builds reads as transferring. A string no build writes is not guessed at: the row is kept, shown as "Needs downloading again" and repaired at the next start.
- Generation per row (database version 4 adds generation and failures; the older table is upgraded in place). A run takes the generation when it begins a song; every later write and the commit name it. Remove, pause, the startup check and marking a download damaged raise it, so a late result cannot write to a row it no longer holds.
- Order of a transfer: source to audio.part and flushed; rebuilt to assembled.part and checked (audio present, duration within 3 s or 5 percent of what the provider declared); flushed; renamed to audio.media; loudness written; ready.part written with the audio length, flushed and renamed to ready; row to assets; artwork and lyrics each through a .part file; row to complete. Audio readiness is the success; artwork and lyrics have their own states and cannot undo it.
- Startup check (reconcile): in-flight rows lose their unfinished files and wait again, keeping audio that was committed with its marker; an interrupted removal is finished; waiting, paused and failed rows keep their state; no row is deleted. One download_reconcile line with counts. Finished rows are checked when the Downloads screen loads and at the start of each run.
- Space: admitted against the budget (audio plus 9 MB for assets) and against the device (two copies plus 9 MB above a reserve of 1 GB or a tenth of storage, with Unlimited too). Checked again about every megabyte and before the rebuild.
- Failures: a fixed set of ten messages; nothing from a response, an address or an account is stored. Inside a run a failed transfer is tried at most twice more, each with a newly resolved stream and the refused profiles excluded. A failed song is tried by later runs at most three runs in all, then waits for the user.
- Bulk requests keep complete, running and paused songs and count each new song once. Removal and Delete downloads cancel the transfers for those rows and are limited to one account. Smart Downloads pauses only its own choices, respects removals and never removes pins. An account switch stops the run and leaves the song in hand queued under its own account.
- Scheduling: idempotent. A job that is already waiting or running with the same conditions is left alone. Before, every request, account event or settings change rescheduled the job, which stops it, and with it the transfer in progress. A refused foreground service (started from the background, or out of allowance) is caught and logged and the job carries on; onTimeout does the same.
- Labels and actions say what happens: "Paused - starts again from the beginning" (with a middle dot in the app), "Restart download", "Retry missing assets". Nothing is called Resume.
- Playing: a download is opened only if its marker matches and its first bytes are a WebM or MP4 container; otherwise it is marked failed, its marker withdrawn, and the song streams. A player failure on a download is a download failure however the song was queued: "Download incomplete or unavailable" with Redownload and Remove, no automatic retry, no provider request. Found while writing the emulator test and fixed: a song queued from the catalog and served from its download was classed as a provider failure and the same file was reopened up to the recovery budget.
- RuntimeDependencies gains the audio rebuild (AudioAssembler) and the source of a download's lyrics, so the engine can be run end to end in a JVM test.
- Docs: docs/quality/downloads.md (new), recovery.md, deterministic-harness.md, CHANGELOG.

Verification:
- Debug (q09-debug-01/manifest.json): PASS_LOCAL. 655 JVM tests / 79 suites, zero failures, errors or skips (Q08A: 583 / 75). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q08a-debug-01. APK SHA-256 94e963fda14ef3e0e7c9f6eff61e7e46a9d3b80d292d19798257ccbce1ec9ece.
- New JVM tests (72): DownloadPolicyTest (17), DownloadReconcileTest (14), DownloadEngineTest (36), DownloadSchedulingTest (5). DownloadControlsTest now also checks that clearing the playback cache leaves a finished download sound. DownloadBatchTest, AccountScopeMergeTest and LocalDataControllerTest were changed for the new signatures and state names.
- Emulator, bound (q09-debug-fixture-01/manifest.json, log q09-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 304.359 s: the 40 of Q08A plus 6 DownloadLifecycleDeviceTest. Test APK SHA-256 f0711bb17929b6829895ac89d3f26d1033b38a9e47a7a782a19b76961d949be7. riff_q02_fixture, API 31, network disabled and confirmed unreachable, signed out. The crossfade classes ran first, soon after boot.
- Emulator, not bound (q09-two-player-instrumentation-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 208.796 s, on a freshly started emulator.
- Release (q09-release-01/manifest.json): PASS_LOCAL. junit {"suites": 79, "tests": 655, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q08a-release-01. APK SHA-256 c6cfef4922d8d956f907be69abb5edd615f36e03bb9325d2d20e259802dc315a. Shrunk unsigned release 26.56 MiB (Q08A: 26.51). No device run of the release artifact.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

How a stop of the app is tested: in the JVM tests an Error is thrown from a scripted part (resolver, audio response, free-space reading, rebuild, clock, artwork response). The engine catches Exception and its own control types, not Error, so none of its handlers run, as none would if the process died. The store is then opened afresh. This reproduces the state on disk and in the database; it is not a process kill on a device.

Failed attempts before the evidence runs (none preserved as files; all were in working runs):
- DownloadReconcileTest: fixture ids were 12 characters, not 11, so every row was skipped as not a video id. Test change only.
- DownloadEngineTest, first runs: wrong expectations about profile numbering, retry counts within a run and the value a run returns with nothing to do; a removal made while the source file was open could not delete it on the build machine's file system, so that case removes and asks again from inside the resolver instead. Robolectric's image decoder accepts any bytes, so the case of artwork that is not an image is tested on the emulator.
- DownloadLifecycleDeviceTest, first emulator run: 6 tests, 2 failures. One expected the label "Download missing" where the row is failed and the label is "Download failed"; test change. The other was real: a download cut short after a valid start failed in the player with ERROR_CODE_IO_UNSPECIFIED caused by EOFException, which the service did not count as damage, so the row stayed complete. A download that ends before its own index says it should is now marked damaged. Second run: 1 failure, a wrong assertion that no player error is held while a song waits for a connection (one is, by the Q07 design). Third run: 6 of 6.
- NormalizationDeviceTest built its downloads from WAV, which the new container check refuses at play time. It now uses the same AAC-in-MP4 fixture as the download test (DownloadFixtures.kt, encoded on the emulator from the generated tones). 9 of 9 after the change.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| DLD01: stop in each phase; restart leaves each song queued or complete, never falsely complete | PASS (JVM) | Six stop points in DownloadEngineTest from resolve to the last asset, each followed by a fresh store and a second run that completes; DownloadReconcileTest for each stored state. Not a device process kill |
| DLD02: bad range, 403, truncated, full disk | PASS (JVM; emulator for truncated and wrong duration with the real muxer) | 403 mid-transfer recovers with a new stream; always refused ends failed after three attempts; wrong range, no length, wrong length, manifest, corrupt audio; full before, during, before rebuild, during rebuild, at commit. Nothing playable and no .part file is left in any of them |
| DLD03: offline restart and seek with no provider requests | PASS (emulator, network off) | A download rebuilt by Android's muxer plays, seeks to 21 s and back, moves to the next download and plays again from an empty player: no stream lookup event, no recovery event, resolver and audio request counts unchanged. The app process was not restarted in that test; the JVM test restarts the store and shows no further request |
| DLD04: clearing the playback cache keeps downloads | PASS (JVM) | DownloadControlsTest: bundle sound and row complete after the cache is cleared |
| DLD05: Smart Downloads policy changes | PASS (JVM) | Network changing to mobile mid-transfer, battery below 20 percent, playback starting, Smart Downloads switched off: the song in hand returns to the queue with no files. Requests by hand ignore these |
| DLD06: bulk duplicates and manual pins | PASS (JVM) | Duplicates in one request are fetched once; complete and running songs are kept; pins survive Smart Downloads cleanup and switching it off |
| Delete-all during assets | PASS (JVM) | The run stops at its next step; rows return to metadata; no files |
| Account switch pauses transfers | PASS (JVM, with the existing AccountSwitchDeviceTest unchanged on the emulator) | |
| Scoped deletion | PASS (JVM) | Another account's rows and files untouched |
| Idempotent scheduling; foreground-service refusal | PASS (Robolectric) | Same JobInfo object after five repeats; a refusal leaves the song queued with the job scheduled. Not run on Android 14 or 15 |
| Invalid bundle offers a way to download again | PASS (JVM; emulator) | Refused before decoding (container) and after a player failure (cut short), each once, with the row failed and the marker withdrawn; downloading again plays |
| Complete fixtures play with zero provider calls | PASS (emulator) | As DLD03 |

Decisions (mine, not the user's):
- No resume of a partial transfer. A stream address is short-lived and tied to its request, and a partial source cannot be validated; restarting with a fresh stream is what the plan's "fresh-stream restart" asks for. The labels say so.
- Two copies plus assets must fit above the reserve before a transfer starts. This is stricter than before and refuses some downloads that would previously have started and failed during the rebuild.
- Three runs of automatic retry for a failed song, then it waits for the user.
- The check before playing reads twelve bytes. Reading the whole file on every play was judged too slow; deeper damage is caught when the player fails on it.
- A player failure on a download marks it damaged only when the files fail the check, the container cannot be parsed, the file is missing or it ends early. A decoder that fails to start is not taken as proof that the file is bad.
- Space, budget and no-audio failures fail the song at once without the in-run retries.
- Pause in the notification pauses every download asked for by hand in the active account, not only the one in hand.

Known limits:
- A song that cannot finish inside one background job (about eight minutes) starts again in the next. It finishes with the app open or the foreground service allowed.
- The periodic Smart Downloads job is best effort; Android decides when it runs.
- Deeper damage in a finished download is found only when it is played.
- The row message for a download found damaged is general; it does not say which file.

Not covered by any test:
- The SABR transfer path (no fixture produces a SABR stream). Its contiguity checks are in the engine and unexercised.
- WebM/Opus through the real muxer. The emulator fixture is AAC in MP4.
- The Redownload and Remove actions end to end from the failure row (carried from Q07).
- The Downloads screen was not looked at on a screen with the new labels; the label function is tested.

BLOCKED:
- A process kill and a power loss during a transfer on a phone. Rerun: start a large download, force-stop the app (and separately reboot) during Downloading, Checking audio and Saving artwork and lyrics; reopen; expect Waiting to download or Downloaded, never Downloaded without audio, and a download_reconcile line in the copied diagnostics. Needs explicit approval for installation.
- Android 14 and 15: start a download, background the app, queue another from a notification or widget path; expect download_service_refused or download_foreground_refused and the song finishing through the job. On Android 15, the daily limit (download_foreground_timeout). Needs hardware or an emulator image at those levels; only API 31 is present in the toolchain.
- A real provider download: one song over Wi-Fi, one over mobile data with Smart Downloads set to Wi-Fi only, one with the phone nearly full. Needs the network, the provider and approval.
- The release APK on a device.
- Carried from Q04 to Q08A: as listed in the Q08 and Q08A entries.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped once before the stress build. On the emulator only: fixture downloads fixtureD001 to fixtureD007 and fixtureN001 to fixtureN012 were created in and removed from the signed-out download folder and its table, fixture tones were added to and removed from the media library, and audio and crossfade settings were set and cleared. No physical device, real account, provider request, publication, push or user-data deletion. Deleted build outputs were regenerable files under app/build.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 7c64be5 on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q10. It is inside the 9 October authorization (Q03-Q18) but the user's instruction covered Q09 only; wait for "start Q10".

## Q10
### Q10 completion (12 October 2026)
Status: PASS locally and on the disposable emulator for the deterministic contracts. Every check against YouTube Music itself is BLOCKED (below); no fixture result stands in for one.
Source: 9ef6a831524619d71a93114b6203953d015f0c91 dirty, digest prefix a93aa0f28824 (full value in the manifests). Evidence root: artifacts/quality/9ef6a831524619d71a93114b6203953d015f0c91-dirty-a93aa0f28824/
User instruction on 12 October 2026: "Start q10" (standing rule: complete every aspect of the phase). Q05R, Q10A, Q12A and Q15A were not started and remain unauthorized. Q11 has not been started.

Implemented (new: ProviderContracts.kt; changed: YouTubeMusicProvider.kt, ProviderCatalog.kt, PlaylistEditing.kt, SessionHealth.kt, OriginalAudioPolicy.kt, ImmersiveCollection.kt, NowPlaying.kt, CatalogScreens.kt):
- Interfaces: ProviderSession, ProviderCatalogReader, ProviderMutations, with the existing StreamResolver as the fourth. YouTubeMusicProvider implements them on the same pinned library (innertubex 0.7.2). No second implementation exists; call sites still hold the concrete class except addTrackToPlaylist, which takes the interfaces.
- Changes: MutationOutcome is Acknowledged, Rejected or Unknown. A playlist edit is acknowledged only by STATUS_SUCCEEDED with no error. A like or save is acknowledged only by a JSON object with responseContext and no error; before, a blank body or {} was taken as saved. HTTP 4xx other than 408 is Rejected; 408, 5xx, a timeout or a dropped connection is Unknown; a request that could not connect is Rejected and reported with the reason it never left.
- Unknown playlist edit: the whole playlist is read. Present: done. Absent: not saved, may be tried again. Unreadable, or truncated without the edit in the part read: MutationUnconfirmed, the edit is remembered for the account generation, and the next attempt at the same edit reads first and sends only if still absent. Events: provider_edit_unknown, provider_edit_settled applied=true|false.
- Unknown like: read from the watch queue, which decides; unreadable is MutationUnconfirmed. Unknown save: reported unconfirmed and safe to repeat. Sort preference: unchanged (sent once, the provider's own command and parameters, only for its own playlist).
- Batches: PlaylistBatchResult keeps completed, failures and unconfirmed per playlist; the summary counts each; a retry goes only to the failures. addTrackToPlaylist reads the playlist first and refuses a read-only one and one too large to read whole.
- Collections: 2,000 tracks and 64 pages. A token seen before, two pages in a row with no new entry, or the page limit ends the read. The list is returned with CatalogPage.truncation (Limit or RepeatedPage) instead of an error (Limit) or as if whole (repeated token). The collection screen shows the label and disables Play, Shuffle, Download all and Sort; a row tap plays from what is loaded. Search "Load more" uses the same once-only rule (PageTokens); Up next never returns the token it was asked with.
- Failures: an error object inside HTTP 200 is a failure (ProviderEnvelopeException keeps only the code and a status word). classifyProviderFailure: Offline, Timeout, Auth, RateLimited, NotFound, Refused, Provider, Unknown, each with a fixed sentence. Nothing is called a region restriction; no answer Riff reads says so.
- Parsers: fields of the wrong type are treated as missing (JsonElement.textOrNull) in ProviderCatalog.kt, the provider's own parsers and OriginalAudioPolicy.kt. Before, a string where an object was expected threw and failed the page.
- Capabilities: Capability is Available, Loading or Unavailable(why). ProviderCapabilities (browse, stream, library, like, saveCollection, editPlaylists, feedback) from session state, account scope and resolver; CollectionCapabilities (playAll, downloadAll, sort, save, removeEntries, checkMembership) from the page. The like buttons and the collection screen read them.
- Docs: docs/quality/provider-compatibility.md (new: dependency, assumptions, wording, change protocol, cache table, fixture matrix, live result, triage, limits), deterministic-harness.md, CHANGELOG.

Verification:
- Debug (q10-debug-01/manifest.json): PASS_LOCAL. 736 JVM tests / 84 suites, zero failures, errors or skips (Q09: 655 / 79). Lint 0 errors, 84 warnings, 5 hints; no issue added or removed against q09-debug-01. APK SHA-256 36b81dd1a019ba635b449880127b57cb5868c09c6953fc2eeabe9ef8bcf26f08.
- New JVM tests (81): ProviderFixtureTest (25), ProviderContractTest (24), ProviderMutationTest (18), ProviderCapabilitiesTest (11), CollectionTruncationUiTest (3). No existing test was changed.
- ProviderContractTest and ProviderMutationTest run YouTubeMusicProvider itself on the pinned library with the HTTP transport replaced (ProviderWorld in ProviderContractSupport.kt): each request the library builds is recorded and answered by the test. No request leaves the process; the session fields are synthetic.
- Fixtures: 30 JSON files and index.json in app/src/test/resources/quality/provider/, written by scripts/quality/generate_provider_fixtures.py. They are invented, not recorded. A test checks their digests and scans them for credentials, account values, signed addresses and ids that are not synthetic.
- Emulator, bound (q10-debug-fixture-01/manifest.json, log q10-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 326.546 s, the same classes as Q09; Q10 adds no emulator test. Test APK SHA-256 534ce32f6b692d39b062a1fc8034f3bf0304ac721a68ab10c4e7fea6e5383060. riff_q02_fixture, API 31, network disabled and confirmed unreachable, signed out.
- Emulator, not bound (q10-two-player-instrumentation-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 212.671 s, on a freshly started emulator.
- Release (q10-release-01/manifest.json): PASS_LOCAL. junit {"suites": 84, "tests": 736, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 84, "Hint": 5}; no issue added or removed against q09-release-01. APK SHA-256 4f8e6a35290ce8a782b7d1738af3b335402bbe875ac27a9a39633b75e5301c76. Shrunk unsigned release 26.57 MiB (Q09: 26.56). No device run of the release artifact.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only. The daemon it used kept the log open after BUILD SUCCESSFUL; the exit line was written when the daemon was stopped.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts before the evidence runs (none preserved as files; all were in working runs):
- ProviderFixtureTest, first run, 25 tests, 2 failures. One was the intended observation: a string where a text object was expected threw IllegalArgumentException in catalogText, failing the page; the parsers were then changed and the test passes. The other was the sanitation scan refusing serviceTrackingParams in a fixture; the generator was changed to leave it out.
- ProviderContractTest and ProviderMutationTest, first run, 2 failures, both in the tests: the library sends a search page token in the address, not the body, so the harness now records both; and a large-playlist case used a first page with no editable header.
- ProviderContractTest, second run, 1 failure: an HTTP 401 also sets off a session check, so the request count for that case now counts only requests for the collection. It had passed once and failed once, which is how it was found.
- CollectionTruncationUiTest, first run: one test set the screen content twice. Split into one test for each kind of truncation.
- An offline playlist edit said "It may be read-only". Found while writing the mutation tests; it now gives the offline sentence.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| 1. Separate interfaces; pinned integration kept | PASS | ProviderContracts.kt; innertubex 0.7.2 unchanged; solver seam PASS |
| 2. Fixtures for each search kind, Home shelf, album, playlist continuation, owned and read-only playlist, sort menu, account menu, video pairing, lyrics, error envelope | PASS (invented fixtures) | ProviderFixtureTest; matrix in provider-compatibility.md |
| 3. No credentials, account ids or signed URLs | PASS | noFixtureHoldsACredentialAnAccountValueOrASignedAddress |
| 4. Unknown renderers, missing and wrong-type fields, empty arrays, errors inside 200, repeated tokens, cyclic pagination | PASS (JVM) | unknown-renderers.json and emptyAndBareAnswersParseToNothing; ProviderContractTest for the envelope, the repeated token, pages going round under new tokens and the page limit |
| 5. 2,000 cap and seen tokens; truncation labelled; whole-collection actions disabled | PASS (JVM, Robolectric) | Cap, exactly 2,000, a last page running past it; CollectionTruncationUiTest for the label and the three disabled buttons. Sort being disabled is covered by ProviderCapabilitiesTest, not on screen |
| 6. Opaque sort parameters, strict allowlists, no inferred dates or votes | PASS (JVM) | Sort command and parameters sent byte for byte; a menu entry that is not a sort dropped; playlist and video id allowlists send nothing |
| 7. Acknowledgment and captured generation; unknown outcome reconciled before retry; no blind retry | PASS (JVM) | ProviderMutationTest: eight kinds of unreadable answer, each sent once; unreadable playlist then present (nothing sent) and then absent (read, then sent once); account change mid-request |
| 8. Batch keeps per-destination results; retry only the unresolved | PASS (JVM) | aBatchKeepsOneResultForEachPlaylistAndARetrySendsOnlyTheUnresolved. The picker screen itself is not driven by a test |
| 9. ProviderCapabilities drives actions | PASS for like and the collection screen | ProviderCapabilitiesTest. Other screens still check signedIn directly (below) |
| 10. Cache expiry and invalidation documented; a stale in-flight result cannot repopulate | PASS (JVM) | Table in provider-compatibility.md; aLoadInFlightWhenItsPageIsInvalidatedCannotPutItsResultBack and the existing PageMemoryCacheTest |
| 11. provider-compatibility.md maintained | PASS | New file. "Last live result: none in this package" |
| 12. Failure wording by evidence, otherwise generic | PASS (JVM) | failuresAreClassifiedByWhatWasSeenAndOtherwiseSaidToBeUnknown; no region wording |
| Dedicated-account live checks | BLOCKED | Below |

Decisions (mine, not the user's):
- Fixtures are generated from the shapes the parsers read, not captured. No network or account was available, and a capture would need an account. This is stated in the doc, the test header and the changelog.
- A like needs responseContext in the answer. This is an assumption about the provider that has not been checked live. If it is wrong the like is read back and still ends correctly, at the cost of one request.
- HTTP 4xx (except 408) on a change is taken as refused and not applied.
- A truncated collection is shown and partly usable rather than refused. Row taps play from what is loaded.
- Two pages in a row with no new entry end a collection read. A collection whose pages legitimately repeat entries for two pages running would be cut short and labelled.
- An unconfirmed edit is remembered in memory only.
- Region is not classified.

Known limits:
- The read-back trusts the provider to show a new edit at once. If it is slow, an edit can be reported as not saved and then appear; trying again would add the song twice. Not measured.
- After a restart an unconfirmed edit is not remembered. The read before every add still prevents a second copy; a remove is sent again.
- Library, search "Add to playlist", feedback and download entry points still test signedIn themselves and do not read ProviderCapabilities. Their behaviour is unchanged from Q09.
- Radio, podcast and live-station answers have no fixtures.
- "Not interested" is taken as sent on any answer below HTTP 400.

Not covered by any test:
- The playlist picker screen and the sort menu being disabled on a truncated collection, on screen.
- The provider_edit_* and provider_like_unknown events being written.
- lyricsFor with plain lyrics only, and the mobile timed-lyrics request body.

BLOCKED:
- Everything against YouTube Music, on a dedicated test account. Rerun, recording date, build and pass or fail for each in provider-compatibility.md under "Live result": (1) search, each of the six kinds, and Load more; (2) Home and its second page; (3) an album, a playlist over 100 songs, a playlist over 2,000 (label, disabled buttons); (4) an owned playlist and someone else's; (5) play a song and switch to its official video; (6) like and unlike, and whether the answer body carries responseContext; (7) on a disposable playlist: add, remove, change the sort, and how soon a read shows each; (8) save and remove an album; (9) a read and a change with the session expired. Items 6 to 8 change account data and need explicit approval as well as the account.
- The release APK on a device.
- Carried from Q04 to Q09: as listed in the Q08, Q08A and Q09 entries.

Device state: emulator shut down with adb emu kill, adb server stopped, Gradle daemons stopped before and after the stress build. Nothing new was left on the emulator by Q10; the bound classes create and remove the same fixture data as in Q09.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: ec3950a on local branch codex/riff-quality-m0 (not pushed).
Next exact action: Q11. It is inside the 9 October authorization (Q03-Q18) but the user's instruction covered Q10 only; wait for "start Q11".

## Q11
### Q11 completion (12 October 2026)
Status: PASS locally and on the disposable emulator. A pinned Gradle formatter or analyzer is BLOCKED offline, and frame measurement and anything on a phone are BLOCKED or not covered (below).
Source: e44ce24a050273ab18aa1c1035e7293563b448fc dirty (the two user files named at the end are the only uncommitted changes), digest prefix 839b54c26f45 (full value in the manifests). Evidence root: artifacts/quality/e44ce24a050273ab18aa1c1035e7293563b448fc-dirty-839b54c26f45/
User instruction on 12 October 2026: "Start q11" (standing rule: complete every aspect of the phase). Q10A comes before Q11 on the plan's critical path but is not authorized, so it was not started; Q11 does not depend on its output. Q05R, Q10A, Q12A and Q15A remain unauthorized. Q12 has not been started.

Commits (local branch codex/riff-quality-m0, not pushed), behavioural changes kept apart from moves where the two could be separated:
- 4f26ad2 checkpoint 1: typed routes, versioned shell state, shell split.
- a0445cd checkpoint 2: playback service split.
- b5e6aca checkpoint 3: provider split (bodies moved line for line by a script; no logic edits).
- 62dcaee checkpoint 4: cancellation, lifecycle collection, static checks, dead code, docs.
- e44ce24: Media3 opt-in on QueueCoordinator (lint error found by the first evidence run).

Implemented, by plan action:
- 1 Ownership: docs/quality/architecture.md records what AppContainer (process), MusicPlaybackService (players, session, playback jobs), MainActivity/RiffApp (screen-only effects) and SolverService own, and what each closes or cancels. The code already followed this after Q03-Q10; Q11 moved the remaining service and provider internals behind it and wrote it down.
- 2 Provider: YouTubeMusicProvider.kt went from 1379 lines to a 169-line forwarding facade that still implements StreamResolver, ProviderSession, ProviderCatalogReader and ProviderMutations. Parts: ProviderSessionManager (341), ProviderCatalogRepository (655), ProviderLibraryRepository (224), ProviderStreamResolver (187).
- 3 Service: MusicPlaybackService.kt went from 906 to 419 lines and keeps the Media3 session, callbacks, custom commands, lifecycle, sleep timer and history. New: QueueCoordinator, PlaybackPersistence, AudioTransitionCoordinator, PlaybackSources, PlaybackFaults. RecoveryCoordinator already existed from Q07 (PlaybackRecovery.kt) and is wired, not rewritten.
- 4 Shell: RiffApp.kt 1095 to 324 lines, ShellScene.kt 631 to 110. New: ShellNavigation.kt (model, routes, saved state), PlayerPresentation.kt (PlayerPresentationState holds the drag and morph state), ShellChrome.kt, OnDeviceScreen.kt, ScreenParts.kt. The player's position and the playback position are read through lambdas, derivedStateOf or snapshotFlow; the eight places a screen file reads one are on the reviewed list of the position rule.
- 5 Routes: sealed Route (Account, DeviceAudio, Downloads, ListeningHistory, Settings(section), Collection(id, title)); every argument read from saved state is validated; one stack per root, bounded at 16.
- 6 ShellSavedState: named and versioned ("riff-shell/2"). The positional list of earlier builds is still read. It holds no credential, stream address or playing item.
- 7 Dead code, each removed only after a reference search over app/src (main, test, androidTest), the manifest and build files found no use. Checkpoint 1: the first Search, Explore and Library screens, the liked-songs and accessibility screens behind them, YouTubeLiveScreen.kt and the collapsed queue. Checkpoint 3: collectionPage and recentSongs in the provider. Checkpoint 4: QueuePanel (InlineQueue carries the same test tags and is the queue in use), Modifier.fadingEdges (FadingEdges.kt), startManual in OfflineDownloadService.kt, LocalCollections.clearCurrentAccountOnlineData, toggleOnlineFavorite and toggleOnlinePlaylist, GlassMotion.targetFrameRate, contentMs and modalMs, and the unused locals glassBounds, titleWidth and queueSheetState. Default no-op API reviewed and kept: SessionParticipant.quiesce and bind have empty defaults, and AppContainer and three tests each implement only one of them.
- 8 Formatter and analysis: .editorconfig records the conventions the sources already follow. scripts/quality/static_checks.py (Python only) has six rules: cancellation, blocking, direction, lifecycle, position, size (700 lines). 18 entries, each naming a file, a line fragment and a reason, cover the 23 accepted sites in static_checks_reviewed.json; no rule or file can be switched off, and an entry that matches nothing is a finding. No mass reformat was done. A Gradle formatter is BLOCKED (below).
- 9 Cancellation: suspendCatching (Cancellation.kt) replaces runCatching at eleven places where the block can suspend (AppContainer pruning, ArtworkColor x2, EarlierDataScreen, LibraryHistoryScreens x2, LocalDataController.clearOwner, the moods fetch, stream prewarm, RiffWidgets x2). It rethrows when the caller is no longer active and otherwise returns the failure, which is the "retryable error only while the parent is active" rule; providerResult already did this for provider calls. PlaybackPersistence.loadNewest keeps the caller's cancellation. 14 collectAsState calls became collectAsStateWithLifecycle. The readability check for a local file moved from the main thread to the IO dispatcher inside the queue command. runBlocking remains at three reviewed places (bounded final save in the service, the SABR loader thread, the solver process).
- 10 No forwarding repositories or use cases were added. The one forwarding class is the provider facade, kept so that callers and tests did not change; architecture.md says it goes when no caller names it.
- 11 Dependency direction: the direction rule fails a file that is both a screen file and a store, client or service file. One reviewed site: AccountScreen turns cookies on for its own sign-in WebView.

Behaviour changes (all in CHANGELOG):
- Each root keeps its own open collection. Before, one was shared and switching roots could show the wrong one.
- Cancelled work stops at the places above instead of continuing to its next line.
- An unreadable local file now fails after the request has taken its queue ticket, not before.
- Screens stop collecting those 14 flows while the activity is stopped.

Verification:
- Debug (q11-debug-01/manifest.json): PASS_LOCAL. 788 JVM tests / 89 suites, zero failures, errors or skips (Q10: 736 / 84). Lint 0 errors, 81 warnings, 3 hints (Q10: 84 and 5). Against q10-debug-01, 13 warnings appear in the new files and 18 leave the files they were moved out of, all of two kinds (UseKtx, AutoboxingStateCreation); no new kind of issue. APK SHA-256 b3285bdfb4fa6989ba5207273a9bf36792194459fcba881dec8cf96a09b74c2c.
- New JVM tests (52): ShellNavigationTest (15), PlayerPresentationStateTest (6), PlaybackPersistenceTest (10), QueueCoordinatorTest (16), CancellationTest (5). QueueCoordinatorTest and PlaybackPersistenceTest run the extracted classes on a real ExoPlayer under Robolectric.
- Existing tests changed, setup only, assertions untouched: UiInteractionTest (7 lines) and CollectionTruncationUiTest (2 lines) build the shell model through the typed route API. No existing test was removed. No androidTest source was changed.
- Emulator, bound (q11-debug-fixture-01/manifest.json, log q11-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 318.869 s, the same classes as Q10. Test APK SHA-256 1dd2090911a0b2210b89668ae608ca23752ad18733feee77316973b2e745bab4. Network disabled (ping_exit=2 in the install log).
- Emulator, not bound (q11-two-player-instrumentation-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 208.85 s, on a freshly started emulator.
- Release (q11-release-01/manifest.json): PASS_LOCAL. junit {"suites": 89, "tests": 788, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 81, "Hint": 3}, the same difference against q10-release-01 as debug. APK SHA-256 b49fe939d4fe37fa5ccbab4161ca535dab9285567f15129368f6dbfc784b3f25.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 149 files, 23 reviewed sites, 0 findings. Script tests (quality-script-tests-01.txt): 47 tests OK, of which 19 are new in test_static_checks.py, including one that runs the checks on the app sources.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts:
- q11-debug-01-failed-lint (kept in the evidence root; it was taken at 62dcaee): FAIL. JVM 788 / 0 failures, lint 1 error: UnsafeOptInUsageError at QueueCoordinator.kt, where setCustomCacheKey had moved from the service without its Media3 opt-in. Unit tests and compilation do not catch this; lint does. Fixed in e44ce24 and every run above was taken after it.
- Working runs, not kept as files: recursive type inference between the service's lazy coordinators (explicit types added); QueueCoordinatorTest failed with a null binder until the solver service was declared unbindable in the test, with resolver failure then simulated by HTTP 500; the first direction rule (a file without @Composable may not import Compose UI) was wrong for UI helper files and was redefined; the first cancellation rule reported 149 sites, most of them blocking code, and was narrowed to blocks that can suspend.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| Existing behaviour assertions retained | PASS | 736 earlier JVM tests still present and passing; two changed in setup only; 46 + 15 emulator tests unchanged and passing |
| Entry points resolve | PASS | Manifest class names unchanged; debug, release and stress builds; app started and the service bound by the emulator classes; solver seam PASS in the shrunk release |
| All regression lanes pass | PASS on what this machine can run | Debug, release, emulator bound and unbound, stress compile, script tests |
| Large classes lose unrelated responsibilities | PASS | Service, provider and shell splits above; each new class has one listed responsibility in architecture.md and direct tests for queue, persistence, navigation and player presentation |
| Not file size alone | PASS | The size rule is a tripwire only; the splits are by owner and are tested as units |
| Position and drag state do not invalidate unrelated screens each frame | PASS by structure, not measured | Position rule and its reviewed list; PlayerPresentationStateTest. No recomposition count or frame timing was taken |
| Cancellation rethrown; retryable only while the parent is active | PASS (JVM) | CancellationTest; cancellation rule with 6 reviewed sites, none in a cancellable coroutine |
| No blocking main-thread Binder or network | PASS for what the audit found | Local-file check moved; blocking rule. The audit was by reading and by rule, not by StrictMode on a device |
| Pinned formatter | BLOCKED | Below |

Decisions (mine, not the user's):
- New classes stay in the root package com.musicglass.personal (ADR-Q01 keeps one module and stable entry-point names; subpackages would have turned every move into a visibility change).
- The provider facade and the four interfaces on it stay for now, so no caller or test changed in the same commit as the move.
- Collection sorting lives in ProviderLibraryRepository, since only an owned playlist can be sorted.
- The static checks are text rules in Python rather than a compiler plugin. They can miss a suspending call hidden behind a non-suspend name and can be satisfied by a reviewed entry with a poor reason; the reasons are there to be read.
- The size ceiling is 700 lines. No file is over it, so there is no size exception.
- Frame-rate proof is left to Q14, which owns performance measurement.

Known limits:
- The facade still exposes everything; nothing stops new code from taking the whole provider instead of a narrow interface.
- ProviderLibraryRepository reads through ProviderCatalogRepository (cache keys and collection reads), so the two are not independent.
- collectAsStateWithLifecycle changes when 14 screens receive updates. This was exercised by the Robolectric UI tests and the emulator classes, not by a test that stops and restarts the activity and asserts the catch-up.
- The trailer on these commits reads "Co-authored-by: Codex": the repository's commit-msg hook rewrites the trailer that was supplied. The hook was left as it is.

Not covered by any test:
- Recomposition counts or frame timing while the player is dragged.
- Process death and restore of ShellSavedState on a device (the round trip and the earlier format are covered on the JVM).
- The unreadable-local-file path of PLAY_LOCAL_AUDIO after its move to the IO dispatcher.

BLOCKED:
- A pinned Gradle formatter or analyzer (ktlint, detekt or Spotless). None is in the project's offline Gradle home and the plan's builds run --offline. Rerun: with network, choose one, pin its version and checksum in the version catalog and verification metadata, run it once as a commit that contains only formatting, then add its check task to collect_results.py. Until then .editorconfig and static_checks.py are what exists.
- Frame timing and recomposition on a phone (Q14 owns the measurement). Rerun: release build on the reference phone, drag the player and scrub the seek bar under a frame-timing capture; no screen under the player should recompose per frame.
- The release APK on a device.
- Carried from Q04 to Q10: as listed in the Q08, Q08A, Q09 and Q10 entries.

Device state: emulator shut down with adb emu kill after each run, adb server stopped, Gradle daemons stopped before and after the stress build. Nothing new was left on the emulator by Q11; the bound classes create and remove the same fixture data as in Q10.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 8d45eb3 on local branch codex/riff-quality-m0 (not pushed), after 4f26ad2, a0445cd, b5e6aca, 62dcaee and e44ce24.
Next exact action: Q12. It is inside the 9 October authorization (Q03-Q18) but the user's instruction covered Q11 only; wait for "start Q12".

## Q10A
### Q10A completion (12 October 2026)
Status: PASS locally, with two live canary results. The scheduled workflow has not run (the branch is not pushed), and the fast-track candidate procedure is written and not exercised (below).
Source: 3f83586860fd9f92d5b035ba53ff97a069ab7df5 dirty (the two user files named at the end are the only uncommitted changes), digest prefix 43e6a3762425 (full value in the manifests). Evidence root: artifacts/quality/3f83586860fd9f92d5b035ba53ff97a069ab7df5-dirty-43e6a3762425/
User instruction on 12 October 2026: "do q08a and q10a". This is the authorization for Q10A, which the 9 October authorization (Q03-Q18) did not cover. Q08A was already complete (its section above, commit 18080c7); nothing was left to implement. Its tests ran again here and in Q11 and pass: LoudnessNormalizationTest (20, JVM) and NormalizationDeviceTest (9, emulator, among the 46). Its BLOCKED items (a live loudness value, listening on a phone) are unchanged: they need a phone or an installation that has not been approved. Q05R, Q12A and Q15A remain unauthorized. Q12 has not been started.
Order: Q10A was done after Q11, although the plan puts it before. It depends only on Q10, and was written against the provider classes as Q11 left them.

Implemented (no file under app/src/main changed; the app is byte-for-byte the same code as at Q11):
- 1 Account-free canary. ProviderCanary.kt and ProviderCanaryLive.kt (test source set): the real provider class on the pinned library, signed out, with only the status number of each request noted; one search for a long-lived public song and one page of a long-lived public album. ProviderCanaryModel.kt (testSupport, shared with device tests): the classes, the classifiers, and StreamCanary, which resolves one long-lived public song and reads its first 2,048 bytes. ProviderCanaryDeviceTest.kt (androidTest) runs StreamCanary in the app. No credential, no account, no change sent. If the app is signed in the stream phase sends nothing and reports a harness fault.
- 2 Schedule. .github/workflows/provider-canary.yml: cron once a day and workflow_dispatch; no pull_request and no push trigger; contents: read; no secret. provider_canary.py refuses a second scheduled run within 20 hours. A run is 2 requests on the JVM (3 if the first album is gone) and 2 on a device (4 if the first song is unavailable).
- 3 Lanes. Search and collection run on the JVM. Stream resolution needs the solver process (ProviderStreamResolver fails closed without it), so that phase runs on an emulator or device and each record names the lane (streamLane: none, emulator, device). There is no emulator lane in CI until Q16; until then the stream phase is run by hand or from a local scheduler. This run used: JVM on the build machine, and the disposable emulator.
- 4 Classes: Healthy, ParserBreak, ResolutionBreak, Blocked, Outage, HarnessFault. 401, 403, 429, a refusal inside HTTP 200 and "every target unavailable" are Blocked; no answer, a timeout and 5xx are Outage. In one run any HarnessFault, then Outage, then Blocked hides a break in another phase. A break is flagged only when the newest result and the conclusive result before it are both breaks; results that could not tell neither count nor reset.
- 5 Flag: an error annotation, the step summary (GITHUB_STEP_SUMMARY) and exit code 1. The script has no code path that edits a file outside its state folder, runs git, or publishes.
- 6 Stored: time to the minute, trigger, lanes, library version, and per phase a class, a reason word from a fixed list of 22, milliseconds and a request count. Harness output with any other key or value is refused whole (unsanitary_output). A record is scanned again before it is written.
- 7 Runbook: "When the canary reports a break" in docs/quality/provider-compatibility.md, nine steps from reading the result to stopping before publication, with the commands.
- 8 Fast-track gate set, in step 8 of the runbook: G02, G07, G08, G10, G12, the affected G04 cases and a live check on an authorized test account; every other gate listed as carried over and not verified again; Q05R publication rules.
- 9 Each summary and each record says it satisfies no gate ("gate": "none"); the doc says so in two places.
- Gradle: ProviderCanaryLive is excluded from testDebugUnitTest unless -PriffCanaryOut is given, so no ordinary test run, and no evidence run, sends a request.

Live canary results (the only ones; history copied to canary-history-01.jsonl, device log canary-stream-instrumentation-01.log):
- 12 October 2026 06:48 UTC, manual, JVM: search Healthy (1 request, 2524 ms), collection Healthy (1 request, 1224 ms).
- 12 October 2026 06:52 UTC, manual, disposable emulator, signed out, debug build of this source before the docs were added: stream Healthy (1 resolution, 2,048 bytes read, 33829 ms including the solver's first start).
- Before those, to choose the album ids, two searches were sent from a temporary test that was then deleted. All requests in this package: 4 searches or album pages and 1 stream resolution with one 2,048-byte read. No account, no cookie.
- The emulator's Wi-Fi and data were switched on for the stream run only and off again straight after; ping_exit=2 was confirmed before it was shut down. Without -e riffCanary stream the device test is skipped (canary-stream-skipped-01.log).

Verification:
- Debug (q10a-debug-01/manifest.json): PASS_LOCAL. 801 JVM tests / 90 suites, zero failures, errors or skips (Q11: 788 / 89). Lint 0 errors, 81 warnings, 3 hints; no issue added or removed against q11-debug-01. APK SHA-256 0299d60d8f74af38150fbcb4397702a53f9c556021462528a82df790c2ade309.
- New JVM tests: ProviderCanaryTest (13), every provider answer scripted. No existing test was changed. ProviderCanaryLive is not among the 801.
- Script tests (quality-script-tests-01.txt): 65 tests OK, of which 18 are new in test_provider_canary.py.
- Emulator, bound (q10a-debug-fixture-01/manifest.json, log q10a-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 327.872 s, network off. Test APK SHA-256 162cc6cb697c286fcd4334663c091b926f2c974a0ea1effdd5de879d314f8544.
- Emulator, not bound (q10a-two-player-instrumentation-02.log, install-03.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 216.319 s, on a freshly started emulator.
- Release (q10a-release-01/manifest.json): PASS_LOCAL. junit {"suites": 90, "tests": 801, "failures": 0, "errors": 0, "skipped": 0}; lint {"Warning": 81, "Hint": 3}; no issue added or removed against q11-release-01. APK SHA-256 bcfd89b4ba3ec0654f4d390091a08cbdd929e3e95940006379237e2b93d06fd1.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 149 files, 23 reviewed sites, 0 findings.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken before this ledger entry was written; the entry is the only change after it.

Failed attempts:
- attempts/q10a-two-player-instrumentation-01.log (with attempts/install-02.log): 15 tests, 1 failure, CrossfadeDeviceTest.threeSecondCrossfadeBlendsBothRenderers, "short-fade timed out at audible handoff". The log shows the two players drifting to 118 ms apart, a corrective seek, then 160 to 270 ms apart, and the blend skipped with tail_not_aligned. It was a freshly started emulator, so the Q08 explanation (an emulator that has been running a while) does not cover it; the cause is not established. The same two classes passed 15 of 15 on the next fresh start and in Q11 earlier today. Q10A changed nothing under app/src/main. It is recorded as an unexplained intermittent failure of this emulator test, not as a pass.
- ProviderCanaryTest, first run, 13 tests, 1 failure, in the test: a body that is not JSON is read by the provider class as a page with no rows, so the canary says ParserBreak/empty, not unreadable. The expectation was corrected; the class is the same.
- ProviderCanaryTest, second run, 1 failure, in the test: the unknown-renderers fixture also holds rows the parser knows, so it is Healthy. The empty-search fixture is used for "no rows".
- The first Gradle run of the temporary discovery test finished, but the shell that started it did not return for ten minutes. The build log showed BUILD SUCCESSFUL; the result was read from the test report.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| OPS01: healthy, broken and blocked provider classified correctly | PASS (JVM, scripted) | ProviderCanaryTest: healthy; malformed and empty answers and a rejected request are ParserBreak; 401, 403, 429 and a refusal inside 200 are Blocked; no answer, timeout and 503 are Outage; the stream cases likewise |
| OPS01: a block or an outage is never reported as app breakage | PASS (JVM and script tests) | Class rules above; test_a_block_or_an_outage_is_never_reported_as_a_break_however_often_it_repeats; a block in one phase hides a break in another |
| Two-strike rule | PASS (script tests) | test_two_strikes (13 sequences) and the run-level tests |
| Sanitized output | PASS (JVM and script tests) | A result with a signed address behind it holds none of it; ten kinds of unexpected field or value refused; the store refuses an address or a long token |
| Runs at most daily, on request, never on pull requests | PASS for the script and the workflow text | The 20-hour rule is tested; the workflow file is checked by two tests. The workflow itself has never run |
| No secret needed | PASS | Both live runs used none; the workflow names none |
| Runbook executable without this conversation | PASS by review, not by a second person | Every step names a command or a file that exists; the names were checked against the tree. Nobody else has followed it |
| Canary never satisfies a gate | PASS | Stated in each summary and record and in the doc |
| The flag never edits, bumps or publishes | PASS by construction | Nothing in the script or the workflow does; the workflow has read-only permission |

Decisions (mine, not the user's):
- The targets are three very well-known songs and the albums of two of them. The album ids were found by search today; the song ids are the public video ids of those songs.
- At most two targets a phase. A stale album list is the canary's fault (targets_stale), not a break.
- Every target song unavailable is Blocked, not a break: for songs this well known a refusal of the runner is far more likely than a break that affects only them.
- An audio host answering 403 to a freshly resolved address is a ResolutionBreak. It can also be a refusal of the runner; the two-strike rule is the guard.
- Results that could not tell do not reset the count between two breaks. A provider that breaks and then blocks the runner for a day should still be flagged on the next break.
- HarnessFault exits 2 at once, with a warning and not an error: it is visible, and it is not called a break.
- The canary state is kept in the workflow cache, not committed. If the cache is lost the count starts again, which delays a flag by one run.
- The live test lives in the unit-test source set and is excluded by Gradle unless asked for, rather than in a new module (ADR-Q01).
- One live run of each lane was made in this package, by hand, to prove the harness against the provider. I read "do q10a" as covering that: it is the package's own subject, account-free and read-only.

Known limits:
- A provider that refuses a runner with an ordinary-looking empty answer is seen as ParserBreak. Two strikes and a run from another network are the guard.
- Three songs and two albums: a change to artists, long playlists, lyrics or the signed-in library is not seen.
- The stream phase is scheduled nowhere.
- Hosted runners may be blocked every day, in which case the scheduled canary reports Blocked for ever and is useful only when run locally. Not known until it runs.
- The workflow's cache and artifact steps have not run; a mistake in them would show as a missing history, that is, a delayed flag.

Not covered by any test:
- The workflow on GitHub.
- ProviderCanaryLive's own file writing, except by the one live run.
- A real break: no ParserBreak or ResolutionBreak has been seen from the provider, only scripted ones.

BLOCKED or not done:
- The scheduled workflow: needs the branch pushed, which is not authorized. Rerun: after a push, dispatch "Provider canary" by hand once, read its summary, and check that a second dispatch restores the first result from the cache (the history in the uploaded artifact has two lines).
- The fast-track candidate: needs a real break, a dedicated test account for the live check, and authorization to prepare a candidate. The procedure is step 8 of the runbook.
- Carried from Q04 to Q11: as listed in those entries.

Device state: emulator shut down with adb emu kill after each run, adb server stopped, Gradle daemons stopped before and after the stress build. The emulator's network is off. On the emulator the app made one stream resolution, signed out; nothing was written to an account because there is none. No physical device, no real account, no publication, no push, no user-data deletion.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: be72164 on local branch codex/riff-quality-m0 (not pushed), after 3f83586.
Next exact action: Q12. It is inside the 9 October authorization (Q03-Q18) but no instruction has named it; wait for "start Q12".

## Q12
### Q12 completion (12 October 2026)
Status: PASS locally with open items. The physical-device walk-through that the acceptance asks for is BLOCKED (no phone; installation on one is not authorized) and was not done. Everything else in the package was done or is listed below as not done.
Source: 6124fec6ccff3098abefa6325ec83fbf26fd8817 dirty (the two user files named at the end are the only uncommitted changes), digest prefix 9e717f3171f6 (full value in the manifests). Evidence root: artifacts/quality/6124fec6ccff3098abefa6325ec83fbf26fd8817-dirty-9e717f3171f6/
User instruction on 12 October 2026: "start q12". Q12 is inside the 9 October authorization (Q03-Q18).

Audit findings that led to changes:
- A failure, an empty result and a load in progress were each one line of text in Search, collection detail, Radio and Podcasts, Library and On device. A failed "Load more" was only a snackbar. Radio/Podcasts and On device had no way to try again.
- Nested collections shared one saved-state key ("<tab>/collection") and one AnimatedContent target, so opening an artist from a playlist and pressing Back returned to the top of the playlist, with no transition between the two. Found by reading ShellScene.kt.
- Returning to Search from a collection emptied the list and searched again after the 450 ms debounce.
- The position and volume sliders exposed only a bare range to accessibility; live radio said "Live radio" with no reason for the disabled bar.
- Confirm buttons read "Clear" and "Delete all"; a failed clear or delete closed into a snackbar.
- No shared measures: the same 20 dp edge, 12 dp radius and 14 sp body size were written out in each file.
- Rendering the Search screen inside the real theme showed dark purple text on the pink "Load more" button (Material's default onPrimary).
Audited and left as they are: the full player hides and disables its title row and its buttons while the queue is open (no invisible pressable layer); titles ellipsize in rows and in both players and there is no marquee anywhere; lyrics already tell loading, failed and none apart; the sleep timer deadline has one source (the service's session extras) that the UI reads.

Implemented:
- 1 States. ContentState.kt: ContentProblem (the sentence, one action chosen by problemKind, a polite live region, stays until retried) and ContentEmpty. ShelfSkeleton.kt: ListSkeleton, six rows of 60 dp. Used by Search, collection detail, Radio/Podcasts, Library, On device. An expired session offers Sign in and opens Account. A failed next page keeps the rows and is said beside Load more (load_more_problem). The skeleton is shown only while nothing is on screen.
- 2 Layout stability. ListSkeleton has a fixed size; ProviderSongRow and the plain collection hero now use CatalogArtwork, the same stand-in whether the address is missing or the image fails.
- 3 Long titles. Already ellipsized; the row subtitle and the back heading gained an ellipsis. The full title is in the Credits dialog of the player. No marquee exists.
- 4 Seek and volume. contentDescription "Position"/"Volume" and stateDescription "1:05 of 3:20", "40 percent", "Muted". Live: disabled, LIVE_SEEK_EXPLANATION on screen and as the state.
- 5 Navigation state. ShellScreen (root, depth, route) with a key that includes a collection's id; AnimatedContent and the saveable state holder use it; a closed screen's state is removed; LocalCollectionRoute gives a collection screen its own id and title. ShellModel.searchSnapshot keeps the last search in memory (never saved, never for another account or query). Double tap on a tab resets as before.
- 6 Dialogs and errors. Failures of Clear cache and Delete all downloads are shown inside the dialog, which stays open with "Try again" and "Close". List failures stay on screen (item 1).
- 7 Wording. "Clear cache", "Delete all downloads". No implementation detail was found in product text on the audited screens.
- 8 Downloads, Smart Downloads and cache: the existing text already separates them ("Managed by Smart Downloads", "Downloaded songs will be kept"); the cache prompt keeps that sentence. No change beyond item 7.
- 9 Ownership of artwork and text: audited, no change (see above).
- 10 Pictures. ScreenBaselineTest: nine PNGs under app/src/test/resources/quality/screens (search empty, loading, offline, error, results, results at font scale 2; mini player playing, paused, paused at font scale 2), drawn with Robolectric native graphics at 360 x 640 dp inside RiffTheme, compared with a tolerance of 0.2 percent of pixels differing by more than 8 per channel. -PriffRecordScreens records.
- Tokens. DesignTokens.kt: RiffSpace, RiffRadius, RiffType, RiffTheme, contrastRatio. Adopted in the shared parts and the new components only. static_checks.py has a new rule, measures, with static_checks_measures.json: 659 raw dp/sp in 34 screen files; a file may not gain one, a new screen file may have none, a count that is too high is a finding.
- Theme: filled buttons are Pink with Ink text.
- Mechanical, no behaviour change: RiffTheme extracted from RiffApp.kt; LyricsTimingDialog, lyricsOffsetStep and lyricsOffsetLabel extracted from LyricsPanel.kt; SeekControl made internal. They are in the same commit as the behaviour changes because they touch the same files; the commit message lists them.
- Docs: docs/quality/core-journeys.md (state rules, tokens, navigation state, journey-to-test table, picture limits, the 13-step device walk-through), architecture.md, CHANGELOG.md.

Verification:
- Debug (q12-debug-01/manifest.json): PASS_LOCAL. 843 JVM tests / 95 suites, zero failures, errors or skips (Q10A: 801 / 90). Lint 0 errors, 81 warnings, 3 hints; no issue added or removed against q10a-debug-01. APK SHA-256 9a25efea96f3a3674f94ed386243a7e00cda00c78fd539618081b0b74c911a13.
- New JVM tests, 42: ContentStateUiTest (13), NavigationStateUiTest (5), PlayerControlSemanticsTest (4), JourneyDecisionsTest (10), ScreenBaselineTest (10, nine pictures and one test of the comparison itself).
- Existing test changed: DownloadControlsTest.clearingPlaybackCacheRequiresConfirmationAndCancelKeepsTheAudio gained two assertions (button text "Clear cache", prompt says downloads are kept). Nothing was removed or loosened; it passed unchanged before the addition as it presses the button by tag.
- Script tests (quality-script-tests-01.txt): 72 tests OK, of which 7 are new (MeasuresRule).
- Emulator, bound (q12-debug-fixture-01/manifest.json, log q12-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 333.086 s, network off (ping_exit=2). Test APK SHA-256 a4665a873aa4e448c2d38038538b1196d194694076e59fec16dd63ddc0d3aa25. The built app APK matched the manifest hash before install.
- Emulator, not bound (q12-two-player-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 211.423 s, on a freshly started emulator, first attempt.
- Release (q12-release-01/manifest.json): PASS_LOCAL. 843 tests / 95 suites, zero failures; lint 81 warnings, 3 hints; no issue added or removed against q10a-release-01. APK SHA-256 e77b8802af721ec1c4f909df1a85413e2c934e37a6666ecb79ae9be34b7d6841.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 151 files, 23 reviewed sites, 659 raw measures in 34 screen files, 0 findings.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The pictures were compared, not recorded, in both collector runs (the property is not passed), so the nine PNGs were reproduced three times on this machine after recording.
- The evidence was taken after commit 6124fec and before this ledger entry was written; the entry is the only change after it.

Failed attempts (during development, before the evidence runs; no evidence run failed):
- q12_states.py, the first edit script, failed: the file tool strips trailing spaces and the source has them after "uiHaptic(); ". The patterns were corrected and it was applied once.
- First compile after the navigation edit failed: a replace-all turned the new line's own fallback into a self-reference in ImmersiveCollection.kt. Corrected by hand.
- NavigationStateUiTest, runs 1 to 4: (a) LocalAccessibilitySettings was not provided; (b) rows below the hero are not composed, so waiting for row 0 never ended; (c) two tests waited for a second provider request that never came. With a diagnostic the request did come. The cause as far as I established it: in a test of the whole ShellScene the Compose test scheduler did not pick up an answer that finished on another thread unless some coroutine was waiting on the test clock. The test now keeps a 100 ms heartbeat coroutine in its content, and says so in a comment. I did not find why the bare-screen tests (ContentStateUiTest) do not need it. This is a property of the test harness; on a device the dispatchers are real.
- PlayerControlSemanticsTest: I asserted that a disabled slider has no set-progress action. Material's slider keeps the action and marks the node disabled. The assertion was removed; "not enabled" is asserted.
- static_checks.py edit: a shell here-document turned doubled backslashes into single ones, which broke a regex and two string literals. Repaired from a script file; the 26 static-check tests and the full 72 pass.
- ScreenBaselineTest, first run: captureToImage waits for a frame that never comes while the test clock is stopped. The view is drawn into a bitmap directly instead.
- ScreenBaselineTest, first pictures: recorded outside the app theme, so the field text was nearly invisible. That was the test, not the app. RiffTheme was extracted and the pictures recorded again; looking at them showed the purple-on-pink button, which was then changed and the pictures recorded a third time.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| Every UX journey has decision-level Compose or JVM tests | PASS, with the gaps listed under "Not covered" | Table in docs/quality/core-journeys.md; every test class named there exists and ran in q12-debug-01 |
| Physical-device walk-through of every journey | BLOCKED | No phone; not attempted; steps in core-journeys.md |
| No new flow promises unsupported behaviour | PASS by review | New text: the three state components, "Live radio can't be rewound", "Clear cache", "Delete all downloads", two failure sentences. Each describes what the code does |
| An exception never becomes an empty list | PASS for Search and collection detail (tested); by reading for Radio/Podcasts, Library, On device | ContentStateUiTest; the same pattern in the other three, not screen-tested |
| Fixed skeleton size, no jump on image failure | PASS (JVM) | whileSearchingAPlaceholderOfFixedSize...; aRowWithoutArtworkIsTheSameSizeAsOneWithIt |
| Seek and volume expose value; live explained | PASS (JVM) | PlayerControlSemanticsTest |
| Tab and nested state preserved; reset only when asked | PASS (JVM) for nested collections and Search | NavigationStateUiTest |
| Destructive wording and truthful failure | PASS for wording (JVM); the in-dialog failure text is not tested | DownloadControlsTest; see "Not covered" |
| Screenshot baselines | PASS on this machine | ScreenBaselineTest, nine pictures |
| Tokens centralized | PARTIAL | Shared parts only; 659 raw measures remain, held by the ratchet |

Decisions (mine, not the user's):
- Tokens were adopted only in shared parts and new code, and the rest held by a ratchet, rather than rewriting 659 measures in 34 screens with no device to look at.
- The search snapshot is in memory only and is one search deep.
- When the stack is cut at its depth bound (16), keys shift by one; a screen that deep may lose its scroll position. Accepted.
- The pictures use the mini player for playing and paused, not the full player, which needs hosts that only exist inside the app root.
- The tolerance (0.2 percent, 8 per channel) is a guess that passed three times here; it has not met another operating system.
- Black text on pink for filled buttons is a visible change I made from the contrast figures and one picture, not from a design decision by the user. It is one line in RiffTheme if it is not wanted.
- An expired session's "Sign in" opens the Account screen rather than starting sign-in directly.

Not covered by any test:
- The nested-collection defect was established by reading the old code. The new test was not run against the old code, so it is not shown to fail there.
- Radio/Podcasts, Library (signed in) and On device with the new state components at screen level; Home and Explore states.
- The in-dialog failure of Clear cache and Delete all downloads (no seam to make them fail).
- Session expiry at screen level (a 401 starts a session check that the test would have to script); the decision is tested on the component.
- Focus returning to the invoking control when a dialog closes; transport not being obscured; both need a device and TalkBack.
- The selected row matching the playing track on Search; the lock screen; the widget's state as published from a real player (the rendering of a given state is tested).
- The full player, Home, Library and collection screens in pictures.
- The pictures on any system other than this Windows machine.

BLOCKED or not done:
- Device walk-through (13 steps in docs/quality/core-journeys.md). Rerun: connect a phone with a test account that holds nothing personal, get approval to install the debug build, follow the steps, record device, Android version and build identity.
- Converting the remaining raw measures to tokens: not done, by decision above.
- Carried from Q04 to Q10A: as listed in those entries.

Device state: emulator shut down with adb emu kill after each run, adb server stopped, Gradle daemons stopped before and after the stress build. The emulator's network stayed off throughout. No physical device, no real account, no provider request: every provider answer in the new tests is scripted, and artwork loading is replaced in the picture tests.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 2d4b94d on local branch codex/riff-quality-m0 (not pushed), after 6124fec.
Next exact action: Q13. It is inside the 9 October authorization (Q03-Q18) but no instruction has named it; wait for "start Q13".

## Q13
### Q13 completion (12 October 2026)
Status: PASS locally with open items. The TalkBack walk-through of UX01-UX10 and the keyboard walk-through that the acceptance asks for are BLOCKED (no phone; installation on one is not authorized) and were not done. No accessibility audit or certification was done and none is claimed. Everything else in the package was done or is listed below as not done.
Source: df087b4269222c1e567b3bad80b244b7f7a76d14 dirty (the two user files named at the end are the only uncommitted changes), digest prefix 8b75e6ac1cce (full value in the manifests). Evidence root: artifacts/quality/df087b4269222c1e567b3bad80b244b7f7a76d14-dirty-8b75e6ac1cce/
Commits: a523a8c (behaviour: queue actions, contrast over artwork, touch targets), 161ab9c (text moved to string resources, pseudo-locale sweep, strings static check), df087b4 (lint warnings the two had added). The behaviour change and the mechanical move of text are separate commits.
User instruction on 12 October 2026: "start q13". Q13 is inside the 9 October authorization (Q03-Q18).

Findings that led to changes (each found by the new sweep or the contrast arithmetic, not by a person with a phone):
- On a 360 x 640 dp screen at large text the player's controls were squeezed below 48 dp; the control column now keeps its height and the player scrolls.
- 48% of a bright artwork colour was too light for Pink text (errors, the playing mark); faint 55% white text on a magenta tone was 4.32:1.
- White text and icons were drawn straight over artwork or a photograph in five places (player close button and heading, back heading over a hero, a collection's and Downloads' title block, speed-dial titles, Home over a mood photograph). Over a white picture none of them could be read.
- The queue could only be reordered by dragging; a row said nothing about its position; the drag handle was a second, unlabelled stop.
- The like button changed its name with its state instead of keeping one name and saying the state.
- Four haptic constants exist only from API 34; below that the view ignored them, so the threshold cue did nothing on API 31-33.
- RemoteCipherClient.onServiceConnected assumed a non-null binder (found when a refused bind landed in an unrelated test).
- After the text was moved to resources, the pseudo-locale pass found five texts still written in code ("3 of 6", "1:05 of 3:20", the lyrics timing steps and value, the On device tile); a reading of what was left found six more sentences.

Implemented, by plan item:
- 1 Text. app/src/main/res/values/strings.xml holds 733 strings and 44 plurals, English only. No translation was written. Strings.kt: string(), quantity(), AppStrings, and RiffApplication, a new Application class named in the manifest, which installs the application's resources before anything else runs. 54 source files were rewritten by a script (723 literals, 16 constants turned into getters, 41 hand-made plurals turned into plurals resources) and about 40 sites by hand. Enums that carry a label (OutputKind, CollectionSort, ProblemKind, DownloadFailure, QueueRowAction, FailureAction, CatalogKind, CollectionTruncation, ProviderFault, WidgetPreset) hold the resource id and read the text when asked. Words the code compares (tabs, filters, menu actions, 64 of them) stay literals; KeyLabels.kt maps each to its text and keyLabel() is called where one is shown. Debug builds include the en-XA and ar-XB pseudo-locales. app/build.gradle.kts puts res/values on the JVM test classpath so tests without Android read the same file.
- 2 Touch targets. Fixed where the sweep found them; the drag handle is 48 dp.
- 3 Contrast. RiffContrast in DesignTokens.kt (targets 4.5 and 3.0; scrims 0.58 under white text, 0.74 under muted text, 0.46 under a white icon; tone under words 0.9); readableArtworkTone darkens until Pink reaches 4.5:1; scrimPatch, scrimLabel and wordsOverPicture modifiers; speed-dial and mood scrims deepened. Method and figures are in docs/quality/accessibility.md.
- 4 Labels and state. Queue row position and state; like button; tabs; seek and volume were done in Q12.
- 5 Queue without dragging. Move up, Move down, Play next and Remove as custom accessibility actions on each row, offered only where they change something.
- 6, 7 Text sizes, windows, direction. AccessibilitySweepTest and two siblings: 15 screens x font scale 1.0, 1.3, 1.6, 2.0 x LTR and RTL on 360 x 640 dp, 640 x 360 dp and 840 x 900 dp. PseudoLocaleSweepTest: the same screens with every string a third longer and marked.
- 8 Reduced motion. Already honoured the app setting and the system animator scale; now tested.
- 9 Haptics. hapticConstant() with a fallback for each API 34 constant; gating by app setting, system setting and foreground tested.
- 10, 11 TalkBack focus and keyboard. Enter and Space on a tab and a filter chip are tested. The rest needs a device (below).
- Static check. scripts/quality/static_checks.py has a new rule, strings: a quoted literal with a word in it handed straight to Text, a content or state description, a notification title or text, or a notice is a finding. One reviewed entry (the provider's name on a History chip).
- Docs: docs/quality/accessibility.md (new), architecture.md, CHANGELOG.md.

Verification:
- Debug (q13-debug-01/manifest.json): PASS_LOCAL. 927 JVM tests / 102 suites, zero failures, errors or skips (Q12: 843 / 95). Lint 0 errors, 80 warnings, 3 hints; against q12-debug-01 nothing added and one removed (InlinedApi in UiHaptics.kt), lint-diff-debug-01.txt. APK SHA-256 df19b7f33a9a684468e29108d5ab7a21c7e2a4049425eacae310beba98077f76.
- New JVM tests, 84: AccessibilitySweepTest, AccessibilitySweepLandscapeTest and AccessibilitySweepLargeWindowTest (15 each), PseudoLocaleSweepTest (15), AccessibilitySemanticsTest (12), ContrastTest (8), StringResourcesTest (4).
- No existing test was changed, removed or loosened in any of the three commits. The 843 earlier tests assert on English text in several hundred places and passed unchanged after the move to resources; that is the main evidence that the move changed no wording.
- StringResourcesTest holds the text read from the file by plain JVM tests to what Android returns, for every string and plural.
- Pictures (q13-screens/, 90 PNG files, copied from app/build/reports/q13-screens after the debug run): each swept screen at 100% and 200% text in both directions, and with the longer text. I looked at two of them (the player at 200% right-to-left, Settings with the longer text). They are not compared with a baseline.
- Script tests (quality-script-tests-01.txt): 77 tests OK, of which 5 are new (StringsRule).
- Emulator, bound (q13-debug-fixture-01/manifest.json, log q13-instrumentation-04.log, install-02.log): PASS_LOCAL, all five gates PASS. 46 tests, 311.589 s, network off (ping_exit=2). Test APK SHA-256 e86d1a856afbd2f831bded1ea202832e9447bf349a817e31b5dd996ee0ec463c. The built app APK matched the manifest hash before install. This was the fourth attempt; see "Failed evidence attempts".
- Emulator, not bound (q13-two-player-01.log, install-03.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 211.152 s, on a freshly started emulator, first attempt.
- Release (q13-release-01/manifest.json): PASS_LOCAL. 927 tests / 102 suites, zero failures; lint 80 warnings, 3 hints; against q12-release-01 nothing added, one removed (lint-diff-release-01.txt). APK SHA-256 5223dd389ea2a9e25c4dc73a74b1029405c013e758dee4ee24e1e9082dd380d1.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 153 files, 24 reviewed sites, 658 raw measures in 34 screen files, 0 findings.
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken after commit df087b4 and before this ledger entry was written; the entry is the only change after it.

Failed evidence attempts (logs kept under attempts/):
- q13-instrumentation-01-failed.log: 46 run, 1 failed. DownloadLifecycleDeviceTest.aDownloadThatBeginsRightButCannotBePlayedFailsAsADownloadAndIsNotRetried timed out after 30 s waiting for the failure.
- q13-instrumentation-02-failed.log and -03-failed.log (fresh emulator): 46 run, 1 failed each. CrossfadeFixtureDeviceTest.aOneSecondBlendIsGradualAndPutsTheLevelBack: "The two decoders did not align in four attempts". The download test passed in both.
- What I established about the first: run alone it failed five times in a row with the Q13 build. With the Q12 app and test APKs from the Q12 evidence folder, on the same emulator session, it passed once and failed once in the same way. Polling the media session showed the truncated file playing at normal speed to 14.46 s of 14.59 s buffered and then standing there in the ready state; the error the test waits for came 11 s later in the run that passed and not within 30 s in the others. So it fails with the Q12 build too and is not introduced by Q13. I did not find why the stall varies. The machine had 2.4 GB of 16 GB free with other programs of the user's running; I stopped the Gradle daemon before the fourth attempt, which passed. That is one pass after the change, not proof the daemon was the cause.
- Neither test was changed. Both depend on audio timing on an emulator started with -no-audio; the first has a 30 s limit that playback of the 15 s file plus the stall can exceed. This is an open weakness of those two tests, not a result about the app.
- An earlier debug collector run on 161ab9c (artifacts/quality/161ab9cad11e620423c509dd3a1fafc5e169103d-dirty-98ecbcc29324/q13-debug-01) was PASS_LOCAL but showed five lint warnings more than Q12 (InlinedApi x3 from the haptic fallback, PluralsCandidate x2 from two new strings). They were fixed in df087b4 and the collector was run again; that folder is superseded and is not evidence for this entry.

Failed attempts during development:
- The sweep harness: 96 parameterized cases ran out of memory (now one test per screen that loops); legacy graphics measured text wrongly, so chips looked 35 dp wide (native graphics now); a live flip of direction never went idle on Library (the screen is rebuilt per direction, as it is in the app when the language changes); a text field inside a dialog never goes idle under Robolectric, so the sleep timer dialog is not in the sweep.
- ContrastTest: my first model of the ambient backdrop was wrong and was replaced by a grid of points; a colour is kept in 8 bits a channel, so shares are compared with a tolerance of 0.01.
- BackHeading gained a parameter in a position that broke positional callers; moved to the end.
- The generated strings.xml had the attribute and the text swapped in 702 entries (a wrong argument order in my script); repaired by a second script and parsed to check.
- I read a stale log from an earlier run with the same name ("96 tests completed, 35 failed") as the result of a run that was still going. The run had in fact failed to compile, for a different reason. Logs are now removed before a run starts.
- A shell here-document turned doubled backslashes into single ones in two test files and one real newline into a string literal, again. Repaired from script files.
- captureToImage never returns under Robolectric (as in Q12); the view is drawn into a bitmap directly.
- I rewrote static_checks_reviewed.json through a JSON dump, which reformatted every entry; restored from a copy and added the one entry as text.
- One compound shell command was refused by a safety check of the tool (it contained no deletion). I ran the parts separately without the part that was flagged.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| TalkBack UX01-UX10 recording or checklist | BLOCKED | No phone; not attempted; ten steps in docs/quality/accessibility.md |
| Keyboard walk-through | BLOCKED | Not attempted; five steps in docs/quality/accessibility.md. Enter and Space on a tab and a chip are tested on the JVM |
| Automated semantics | PASS (JVM) | AccessibilitySemanticsTest, PlayerControlSemanticsTest, the three sweeps |
| Large-text and RTL screenshots | PASS on this machine, as pictures to look at | q13-screens/, 90 files; no baseline comparison |
| Strings externalized, pseudo-localized expansion, no fabricated translations | PASS (JVM) with the limits below | StringResourcesTest, PseudoLocaleSweepTest, the strings static rule |
| Touch targets 48 dp or non-overlapping equivalent, measured | PASS (JVM) for the 15 swept screens | the three sweeps |
| Contrast 4.5:1 and 3:1 over bright, dark and busy pictures; method and colours recorded | PASS by arithmetic | ContrastTest; docs/quality/accessibility.md |
| Queue: Move up, Move down, Play next without drag | PASS (JVM) | AccessibilitySemanticsTest |
| Font scales, 360 dp, landscape, large window | PASS (JVM) | the three sweeps |
| Keyboard open | NOT DONE | Needs a device |
| Reduced motion; haptics | PASS (JVM) | AccessibilitySemanticsTest |
| Routine progress never announced continuously | NOT VERIFIED | Needs TalkBack; no test asserts it |

Decisions (mine, not the user's):
- The scrims and the darker tone are visible changes made from arithmetic, not from a design decision by the user or from looking at a phone. Screens with bright artwork are darker than before. Each is one constant in RiffContrast.
- Words the code compares stay English literals in the code and are translated only where shown, rather than changing every comparison to an enum.
- A new Application class (RiffApplication) exists only to install the resources early. It runs in the solver process too.
- The fallback that reads strings.xml from the classpath (DefaultStrings in Strings.kt) is compiled into the app although only JVM tests reach it.
- Radio station names and genres, provider names and lyrics provider names are treated as names and are not in strings.xml.
- Two counts in one sentence ("1 saved; 2 could not be updated.") stay one string, marked for lint, rather than two plurals joined in code.
- The sleep timer dialog is left out of the sweep rather than restructured to suit the test.

Not covered by any test:
- Anything a screen reader says or the order it says it in; focus after a dialog or the player closes; announcements of progress.
- Tab order, Back and Escape, focus traps, the on-screen keyboard.
- The glass bars and chips over moving content with High-contrast controls off.
- Home, Lyrics, Privacy and data, Export and import, History, Radio and Podcasts in the sweep and in the pseudo-locale pass. Their text was moved by the same script and is held by the static rule and their own English tests.
- A real right-to-left language or any real translation; en-XA and ar-XB on a device.
- Sentences still assembled from parts in an English order (listed in accessibility.md).
- The widgets and the notification in another language.
- The pictures on any system other than this Windows machine.

BLOCKED or not done:
- TalkBack and keyboard walk-throughs (docs/quality/accessibility.md). Rerun: connect a phone or use an emulator with TalkBack and a keyboard, get approval to install the debug build, sign in with a test account that holds nothing personal, follow the steps with a screen recording, record device, Android version and build identity.
- The two timing-dependent device tests named above were not made robust; that is outside this package and is recorded here so it is not lost.
- Carried from Q04 to Q12: as listed in those entries.

Device state: emulator shut down with adb emu kill after each run, adb server stopped, Gradle daemons stopped before and after the stress build. The emulator's network stayed off throughout. During the investigation the Q12 app and test APKs were installed on the disposable emulator; it was then restarted and the Q13 APKs installed again before the bound run (install-02.log). No physical device, no real account, no provider request.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 08cea9d on local branch codex/riff-quality-m0 (not pushed), after a523a8c, 161ab9c and df087b4.
Next exact action: Q14. It is inside the 9 October authorization (Q03-Q18) but no instruction has named it; wait for "start Q14".

## Q14
### Q14 completion (12 October 2026)
Status: PASS locally for the tooling, the shipped Baseline Profile, the release APK check and the main-thread check on API 31, with open items. Every timing, frame, battery and soak budget on Tier R and Tier L is BLOCKED (no phone; installation on one is not authorized) and none was measured. No budget credit is claimed for any of them. One budget (cold start, Tier R) is expected to fail as the app stands and waits for a product decision (finding 2). Nothing here is a claim that the app is fast.
Source: 91fe78b0979b3b172aa3f85e7d6cd306ae232c29 dirty (the two user files named at the end are the only uncommitted changes), digest prefix c75e38b47e46 (full value in the manifests). Evidence root: artifacts/quality/91fe78b0979b3b172aa3f85e7d6cd306ae232c29-dirty-c75e38b47e46/
Commits: 43599f1 (behaviour: the crash fix and its test), 543a48c (benchmark module, fixture-fed benchmark build types, trace marks), b150e30 (Baseline Profile, check_performance.py), 91fe78b (documents). The one behavioural change is its own commit.
User instruction on 12 October 2026: "start q14". Q14 is inside the 9 October authorization (Q03-Q18).

Findings:
- 1, fixed. Opening the full player ended the app on Android 12 and 13: the output row called MediaRoute2Info.getType, which exists from API 34, and on API 31 and 32 the call was always made (NoSuchMethodError). Found by the player journey on the API 31 emulator. routeDeviceType in AudioOutputControls.kt asks only from API 34; AudioRouteTypeTest (4 tests). Lint's NewApi check had not reported the call.
- 2, open, needs a decision by the user. The startup logo runs 1,150 ms and the first composition waits for its first 740 ms, so the shell cannot be usable sooner than about 1.15 s after the first frame; the Tier R budget is 1.2 s from process start. This is arithmetic from StartupLogo.kt, not a measurement. Choices: shorten or drop the logo on a cold start; compose the shell under the logo; or a product-level waiver that redefines the budget. Nothing was changed and no credit is taken. I cannot waive it.
- 3, recorded. 24 reads of preference files on the main thread during a cold start. They are outside the "warmed journey" budget and inside the cold-start number.

Implemented, by plan item:
- Benchmark module. :benchmark (com.android.test, namespace com.musicglass.personal.macrobenchmark): StartupBenchmark (20 cold launches, with and without the profile), FrameBenchmark (Home scroll with and without the profile, collection scroll, player and queue; 5 warmed loops), ActionBenchmark (six player and queue actions x 30, queue of 2,000 x 10, first audio from a local bundle x 20, first audio at 100 ms and 5 Mbit/s x 20, unthrottled x 20), BaselineProfileGenerator, and three untimed checks: JourneyCheck, MainThreadAudit, MemoryCheck. Two custom metrics: SectionMetric (duration of a named app section) and MissedDeadlineMetric (share of frames the frame timeline marks late).
- Versions, checked against developer.android.com on 12 October 2026: androidx.benchmark 1.5.0 (library and baselineprofile plugin), UI Automator 2.4.0, androidx.profileinstaller 1.4.1. No other dependency was changed.
- The measured build. benchmarkRelease (shrunk, not debuggable, profileable) and nonMinifiedRelease, both with package id com.musicglass.personal.benchmark and the source set app/src/benchmarkFeed: provider answers from the invented fixture pages, one generated tone for every song, generated artwork, a synthetic session, all from 127.0.0.1 in the app's own process. None of it is in debug, release or Lite builds; check_performance.py apk fails a release APK that holds any of it.
- Trace marks. PerfTrace.kt and eleven sections (player and queue open and close, play toggle, skip, queue prepare with IO and main phases apart, first audio); reportFullyDrawn when Home shows its shelves and the logo has gone. AppContainer.runtimeFor is the one seam the benchmark builds replace.
- Rule 8, Baseline Profile. app/src/release/generated/baselineProfiles/baseline-prof.txt, 31,341 rules, committed as source, collected by BaselineProfileGenerator from nonMinifiedRelease on the API 31 emulator (31,406 collected; 65 rules for the feed's own classes dropped).
- scripts/quality/check_performance.py: apk (size, profile packaged, feed absent, profile covers the journey classes), summary (n, p50, p95, budget, verdict; an emulator's rows read NOT EVIDENCE, too few samples INSUFFICIENT), adopt. 21 tests.
- Docs: docs/quality/performance.md (new: protocol, budgets, findings, rules checked against the code, BLOCKED table with commands), architecture.md, CHANGELOG.md, README.md.
- Rules 1-7: checked against the code and tabled in performance.md. No optimization was made on a guess. Not audited: effect caching and narrow state reads, release of offscreen video and tail players (they need frame and memory profiles from a phone). A Performance Auto/Full/Reduced setting was not added; nothing measured asks for it yet.

Verification:
- Debug (q14-debug-01/manifest.json): PASS_LOCAL. 931 JVM tests / 103 suites, zero failures, errors or skips (Q13: 927 / 102; the four new ones are AudioRouteTypeTest). Lint 0 errors, 81 warnings, 3 hints; against q13-debug-01 one added and none removed (lint-diff-debug-01.txt): GradleDependency on the existing androidx.test.ext:junit line, which I did not touch. It appears because the online resolution below put newer version lists in the Gradle cache. Not upgraded (no unrelated dependency upgrades). APK SHA-256 562a7ccbb82569b554165024215fb079d63a9372d9fcb2fa2db0929f72e756a0.
- Emulator, bound (q14-debug-fixture-01/manifest.json, log q14-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 318.662 s, first attempt, network off (ping_exit=2). Test APK SHA-256 e407b4b05a04e54c2d26a2fdeb1a0ce9a1c9f11ccfb57c8c4af01b4a5322dac2. The built app APK matched the manifest hash before install.
- Emulator, not bound (q14-two-player-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 211.355 s, on a freshly started emulator, first attempt.
- Benchmark variants built from the committed source (benchmark-apks-01.sha256): benchmarkRelease app e5fa06f3a9c483cbb798e9075c23a624c5b00e0aef986bb6ad1b6ca25972a6e5, nonMinifiedRelease app d798e6fcd83d8828bc1817810068b91cff61901da071132068598fc81907e378, benchmark test APK c604853577018acf8312f8abbd519c77f6136f11568b3e199058a80f3a90f900.
- JourneyCheck (q14-journey-check-01.log): 6 tests OK, 177.623 s. Every step the benchmarks take, untimed, on API 31.
- MainThreadAudit (q14-main-thread-audit-01.log, nonMinifiedRelease): OK, 36.077 s, zero violations after the warm marker. The whole log (q14-main-thread-audit-01-strictmode.log) holds 35 reports from the start and the first pass: 27 disk reads, 4 disk writes, 2 custom, 1 leaked closable, 1 SQLite object leak; none is a network violation. Evidence for API 31 behaviour, not for speed.
- MemoryCheck (q14-memory-check-01.log): OK. PSS 119,767 kB before and 123,642 kB after ten journeys (3,875 kB; 20,480 kB allowed); over three blocks 119,767, 123,642, 137,617, 126,799 kB. The reading is noisier than the allowance (an earlier foreground reading grew 24.3 MB); it is not a verdict on the budget, which the soaks decide.
- Release (q14-release-01/manifest.json): PASS_LOCAL. 931 tests / 103 suites, zero failures, errors or skips; lint 81 warnings, 3 hints; against q13-release-01 the same one added, none removed (lint-diff-release-01.txt). APK SHA-256 7abed3399f0ab848ebe153af23ec990da61078a72e3e52dd8ddf1c351d4951c3.
- Release APK (release-apk-check-01.txt): all five checks ok. 28,194,806 bytes = 26.89 MiB; assets/dexopt/baseline.prof 11,410 bytes and baseline.profm 1,637 bytes packaged; no feed class, provider, config or fixture page; the committed profile has rules for all journey classes.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 154 files, 24 reviewed sites, 658 raw measures in 34 screen files, 0 findings.
- Script tests (quality-script-tests-01.txt): 98 tests OK, of which 21 are new (check_performance).
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- The evidence was taken after commit 91fe78b and before this ledger entry was written; the entry is the only change after it.

What never ran:
- StartupBenchmark, FrameBenchmark and ActionBenchmark. The API 31 emulator image's system tracer (traced_probes) dies with SIGILL when tracing starts, and Macrobenchmark stops with "did not detect tracing on after 5000 ms". So SectionMetric, MissedDeadlineMetric and the summary parser are compiled and unit-tested but have never met a real trace or a real result file. The first phone run is their first real test.
- The profile comparison (with and without, by compilation mode), for the same reason.
- Emulator timings read from the system log during development (first frame 0.55-0.80 s, fully drawn 2.2-2.6 s over six starts) are recorded in performance.md as not evidence.

Departures from the rules, each recorded because it happened:
- One online Gradle resolution, during development, to fetch androidx.benchmark 1.5.0, the baselineprofile plugin, UI Automator 2.4.0 and their dependencies into the project's Gradle cache. Every evidence run was --offline. AGP's test-runner artifacts (com.android.tools.utp 31.13.2) were not fetched, so connectedAndroidTest and generateReleaseBaselineProfile cannot run offline here.
- The Baseline Profile was therefore collected by starting the generator with am instrument, pulling the file with adb, and writing it into place with check_performance.py adopt, which applies the same filter as the plugin's. The collection is the official BaselineProfileRule; the orchestration is mine. It took adb root on the disposable emulator (API 31 needs it), 820 s.
- The benchmark variants (com.musicglass.personal.benchmark) and the benchmark test APK were installed on the disposable emulator only. That is the same emulator practice as earlier packages under a new package id; nothing was installed on a phone.

Failed attempts during development:
- The .benchmark id suffix set in the build type block was overwritten when the plugin copied release; it is set in finalizeDsl. The test module's namespace collided with the app id and was renamed.
- The invented playlist showed as truncated with Play disabled until continuation tokens were mapped to the fixture pages.
- Journeys: the play state is on a child node; a scroll by object went stale and became a swipe; play after a scroll failed and play now comes first; a race between Library and an already open playlist; the Downloads heading matched a Library tile during the transition.
- Downloads in the fed build: the runtime's HTTP client refused loopback; a WAV was refused at commit because a bundle must be MP4 or WebM, so the tone is encoded to AAC in MP4 on the device; cached pages leaked from one run to the next until every run started from a cleared app; a seeded cookie was lost when the process was stopped at once, until it was flushed.
- The offline build resolved desktop variants for the tested APK configuration; made non-transitive.
- A shell here-document halved backslashes again, in check_performance.py, its test and two regular expressions in MemoryCheck. Repaired from script files.
- MemoryCheck's first form read PSS in the foreground and failed its own allowance on noise; it now reads after ten seconds in the background.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| :benchmark module, release-equivalent nondebuggable variant, distinct package, fixture injection absent from production | PASS locally | builds; release-apk-check-01.txt; JourneyCheck |
| Versions pinned and checked against official documents | PASS | performance.md |
| Cold start p95 1.2 s R / 2.0 s L | BLOCKED; expected to fail on R (finding 2) | none |
| Player and queue actions p95 150 / 250 ms | BLOCKED | none |
| Missed-deadline frames, 60 Hz and high refresh | BLOCKED | none |
| 2,000-entry queue p95 2 / 4 s, IO and main apart | BLOCKED | none; the phases are marked apart in the code |
| Local bundle first audio p95 500 / 800 ms | BLOCKED | none |
| Controlled online first audio p95 3 / 5 s | BLOCKED | none |
| Release APK at most 40 MiB | PASS | release-apk-check-01.txt |
| No app-owned network on the main thread | PASS on API 31 emulator | MainThreadAudit and its log |
| No avoidable main-thread disk in a warmed journey | PASS on API 31 emulator | MainThreadAudit |
| Retained memory growth | NOT DECIDED | MemoryCheck passes but is too noisy to count; soaks BLOCKED |
| Battery, three 2-hour runs | BLOCKED | none |
| Soaks, 4 h live and 2 h offline | BLOCKED | none; the live soak also needs an account |
| API matrix 31 / 33 / 36 | API 31 function only; 33 and 36 BLOCKED | emulator runs above |
| Baseline Profile generated from :benchmark, committed, packaged | PASS | baseline-prof.txt; release-apk-check-01.txt |
| Profile with/without comparison | BLOCKED | no device that can trace |
| No sustained-120 fps claim from display scheduling | PASS (no such claim is made anywhere) | performance.md |
| Optimization rules 1-7 | Partly: two not audited (above) | performance.md |

Decisions (mine, not the user's):
- A second Gradle module. It is the plan's own requirement and the one exception to the single-module decision; it holds tests only.
- The fixture feed lives in an app source set that only the two benchmark build types compile, and the app gained one replaceable function (AppContainer.runtimeFor) and PerfTrace calls in production code. A PerfTrace call is one check when nothing is tracing.
- "First usable shell" is defined as Home showing its shelves with the startup logo gone. A looser definition would pass more easily; I did not choose it.
- first_audio ends when the player reports playing and a frame shows it, which is slightly later than the first sample and never earlier.
- The online first-audio benchmark uses 20 starts, like the local one; the plan gives the network but no count.
- profileinstaller is now a direct dependency of the app (it was already there transitively).
- The androidx.test.ext:junit line that lint now flags was left alone.

Not covered by any test:
- Any number on a phone. Any trace-based metric on any device.
- API 33 and API 36 in any form.
- Whether the Baseline Profile helps, and whether it is still current after later changes (the apk check only holds the journey classes in it).
- The Lite flavour's benchmark build types (they compile with no feed and were not run).
- The notification's and mood photograph's requests in the fed build; the benchmarks must be run in aeroplane mode so none leaves the phone.

BLOCKED, with rerun steps (the commands are in docs/quality/performance.md, "BLOCKED, and how to unblock each"):
- Tier R and Tier L budgets: connect the phone, get approval to install com.musicglass.personal.benchmark and the benchmark test APK (they cannot replace or read the main app), aeroplane mode, animations on, charged and cool; run JourneyCheck, then the Macrobenchmark rule, pull the result JSON and traces, run check_performance.py summary --tier R or L with the refresh rate in force; keep model, API level, fingerprint and revision.
- API matrix: a device or an emulator image for 33 and 36 whose tracer works.
- Profile comparison: StartupBenchmark and FrameBenchmark.homeScroll with and without, on any device that can trace.
- Battery and soaks: Tier R phone, a trustworthy discharge measurement, and for the live soak an account that this work may use. Not scripted. They stay BLOCKED rather than pass on a meaningless run.
- Carried from Q04 to Q13: as listed in those entries, including the two timing-dependent device tests (both passed first time here).

Device state: emulator shut down with adb emu kill after the runs, Gradle daemons stopped before and after the stress build. The emulator's network stayed off throughout. The disposable emulator holds the debug app, its test APK, the benchmark variant and the benchmark test APK; adbd was restarted as root on it once, for the profile collection. No physical device, no real account, no provider request.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: 4029b5a on local branch codex/riff-quality-m0 (not pushed), after 43599f1, 543a48c, b150e30 and 91fe78b.
Next exact action: Q15. It is inside the 9 October authorization (Q03-Q18) but no instruction has named it; wait for "start Q15". Finding 2 needs the user's decision whenever it suits; it does not hold Q15 up.

## Q15
### Q15 completion (13 October 2026)
Status: PASS locally with open items. SEC01, SEC02 and SEC03 pass as JVM tests and the release-content check passes on the built APK. No high-severity issue is known to be open; two medium items are open (dependency verification is not enforced on CI; a foreign media controller was not tried on a device). This was one review of the code by the same hand that wrote the changes. It is not an audit, a penetration test or a certification, and nothing was tried on a phone.
Source: 5aa172cb377e714519b2ea3c0df8b3618739df18 dirty (the two user files named at the end are the only uncommitted changes), digest prefix 83f32ef6db48 (full value in the manifests). Evidence root: artifacts/quality/5aa172cb377e714519b2ea3c0df8b3618739df18-dirty-83f32ef6db48/
Commits: 4312497 (diagnostic ring reduction), 70e1949 (behaviour: media controllers, photo links, browser rules), 93136d8 (release content check, wrapper checksum, pinned actions, dependency checksums), 5aa172c (documents).
User instruction on 13 October 2026: "Start q15". Q15 is inside the 9 October authorization (Q03-Q18).

Findings that led to changes:
- Medium, fixed. Any app that connected a media controller to the playback service was given Media3's default player commands in full, which include removing, moving and clearing queue entries. onConnect recognised the app itself by package name alone. Another app now gets transport and reading without COMMAND_CHANGE_MEDIA_ITEMS, COMMAND_SET_MEDIA_ITEM and COMMAND_SET_PLAYLIST_METADATA, and the app is itself only with its own package name and user id. The custom commands were already refused to others.
- Medium, fixed. The photo credit dialog on Home handed the photograph's page and licence addresses to ACTION_VIEW as the photo service sent them (and as stored in a file). They could name any scheme. They now open only as a browsable HTTPS page, and a licence link that is not one is not shown.
- Low, fixed. The diagnostic ring held media server host names, cookie names (not values), the first eight characters of a queue entry identifier and the system's free-text description of a process exit. None is kept now.
- Low, fixed. The live video player wrote its video identifier into an address without checking its form.
- Lint: UnsafeOptInUsageError on my own new function, found by lint during the work and fixed before the commits.

Implemented, by plan item:
- 1, 2 Typed events. DiagnosticEvent.kt: an event is a name and key=value fields; a field is kept only if its key is one of 96 listed and its value has the shape that key allows (number, true/false, or a short word, error-code name or class name; up to five class names joined by > for an error chain). Everything else becomes <redacted>; a text without a proper name is stored as rejected_event. Bounds: 1,000 characters read, 400 kept, 24 fields, 48 characters a value, 200 lines. PlaybackEventLog reduces every line before storing it and before logcat; a ring from an older version is reduced once on the first write. playbackDiagnosticsReport is the export scrubber: every line reduced again, the heading cut to one plain line. hostClass and schemeClass reduce a host and a scheme to a few words. The three direct logcat calls go through diagnosticLogcat.
- 3 Tests. DiagnosticEventTest, 12 tests (below).
- 4 Browsers. WebBoundaries.kt: httpsAddressOn and httpsAddress (HTTPS only; no user-info, no port but 443, no backslash or control character, plain host, at most 4,096 characters); StrictWebViewClient with a final onReceivedSslError that cancels, used by all three browsers; isVideoId; openWebPage. allowedSignInUrl uses the one rule. File access was already off in all three and there is no JavaScript bridge. Content access stays on in the sign-in browser (open item).
- 5 Media session. Above. The service stays exported; the manifest says why.
- 6 Local content. localAudioUri is the one rule (already applied in two places, now one function). A revoked permission already gave a recoverable failure; reviewed, not changed.
- 7 Release content. scripts/quality/check_release_content.py reads the built APK with aapt2: backup and transfer excluded, cleartext off and no override, not debuggable, test-only or shell-profileable, exported components exactly the reviewed four, no exported or granting provider, permissions exactly the reviewed twelve, nothing from test, stress, fixture or benchmark builds, no key file, and the pinned certificate when signed. 17 tests. Added as a CI step.
- 8 Signing. Unchanged: key outside Git and CI; build-release-candidate.ps1 still checks the pinned certificate. No general file export exists; the copied report has fixed content.
- 9 Lint. The Cursor warning (Recycle), three StaticFieldLeak warnings and ExportedService each have an explanation where they are. Nothing is suppressed.
- 10 Wrapper and dependencies. distributionSha256Sum for gradle-8.13-bin.zip, read from services.gradle.org and gradle.org/release-checksums (they agree); the committed wrapper jar hashes to the value the same pages give. gradle/verification-metadata.xml: SHA-256 for 616 components, generated from this machine's cache; strict locally, lenient on CI (open item). Dependency locking was not added.
- 11 CI. Six actions pinned to the commits their releases pointed to on 13 October; contents: read on both workflows; no secret is used. The workflows were not run (nothing was pushed).
- 12 docs/quality/security-review.md (new): boundaries, attack cases and results, open items by severity, what was not looked at. architecture.md, CHANGELOG.md.

Verification:
- Debug (q15-debug-01/manifest.json): PASS_LOCAL. 952 JVM tests / 105 suites, zero failures, errors or skips (Q14: 931 / 103). Lint 0 errors, 80 warnings, 3 hints; against q14-debug-01 two added (UseKtx in WebBoundaries.kt, twice: a style hint to use toUri) and three removed (UseKtx in HomeFeed.kt twice and MusicPlaybackService.kt once), lint-diff-debug-01.txt. APK SHA-256 ee239902726ccdd6d44785af411bc1dd4a5f8dfffae0684b55819fa0b4a88eae.
- New JVM tests, 21: DiagnosticEventTest (12) and WebBoundariesTest (9). What they hold is tabled in security-review.md under "Attack cases and results".
- Existing tests: no JVM test was changed. One device test was: PlaybackOrderDeviceTest reads "stream_resolve_start lookup=" where it read "entry=", because the field it counted distinct values of was replaced. What it asserts is the same.
- Emulator, bound (q15-debug-fixture-01/manifest.json, log q15-instrumentation-01.log, install-01.log): PASS_LOCAL, all five gates PASS. 46 tests, 307.108 s, first attempt, network off (ping_exit=2). Test APK SHA-256 6639d02cfda6931952f515bdbcd16bf3bcda6e3e7287081f478b2b00a299a906. The built app APK matched the manifest hash before install.
- The ring on the emulator after those 46 tests (device-ring-scan-01.txt): 200 lines, none with an address, an entry identifier, cookie names or a description, none outside the grammar, and none holding <redacted>. So on these journeys the reduction removed nothing the code meant to write.
- Emulator, not bound (q15-two-player-01.log, install-02.log): CrossfadeDeviceTest and SongFadeDeviceTest, 15 tests, OK, 213.839 s, fresh emulator, first attempt.
- Shrunk build on the emulator (q15-journey-check-01.log, benchmark-apks-01.sha256): the Q14 JourneyCheck against benchmarkRelease built from this source, 6 tests OK, 158.582 s. Regression only: the journeys open the full player and the queue through the app's own controller.
- Release (q15-release-01/manifest.json): PASS_LOCAL. 952 tests / 105 suites, zero failures, errors or skips; lint 80 warnings, 3 hints; against q14-release-01 the same two added and three removed (lint-diff-release-01.txt). APK SHA-256 6533d649f081b6293c05ed3bf02d2f6bfaeb07054e2d26217e523d0e3c10cc97.
- Release content (release-content-01.txt): exit 0. 18 checks ok; the certificate line reads "not checked: the APK is unsigned", which is not a pass for the certificate.
- The check against a build that must fail (release-content-benchmark-01.txt): the same script on the benchmarkRelease APK exits 2 with 7 checks failed (network security override, shell-profileable, a fifth exported component, an exported provider, feed entries in the manifest, fixture files, a feed class). The check can fail.
- Release APK size and profile (release-apk-check-01.txt): all five checks ok. 28,194,802 bytes = 26.89 MiB; baseline.prof 11,390 bytes and baseline.profm 1,650 bytes packaged. The profile was not regenerated for the new classes; the journey classes it must cover are still covered.
- Shrunk release, solver seam (solver-seam-release-01.txt): PASS, unchanged.
- Static checks (static-checks-01.txt): 156 files, 24 reviewed sites, 658 raw measures in 34 screen files, 0 findings.
- Script tests (quality-script-tests-01.txt): 115 tests OK, of which 17 are new (check_release_content).
- Stress (stress-build-01.log): :app:assembleStress :app:assembleStressAndroidTest -PriffTestBuildType=stress, exit 0. Compilation only.
- Every Gradle run above was --offline and ran with dependency verification strict.
- The evidence was taken after commit 5aa172c and before this ledger entry was written; the entry is the only change after it.

Failed attempts and one unexplained failure:
- AccessibilitySweepLargeWindowTest.downloads failed once, in a development run, with IndexOutOfBoundsException "Index 3, size 3" inside Compose's lazy list while measuring. It passed in the full run before it, in the debug collector run and in the release collector run, and the Q15 changes do not touch that screen. I did not find the cause. It may be a race in the test's setup or a real race in the Downloads list; it is recorded here so it is not lost.
- My first run of the new tests: 4 of 42 failed, all mine. A chain of two class names was longer than the single-value limit (the limit now applies to each name); the source scan took a logcat tag for an event name; one test encoded a space as + and so tested a legitimate address; one looked for ".proceed(" and found OkHttp's.
- check_release_content.py: a relative tool path failed on Windows; the tool's output is not valid in the console code page; "assets/quality/" matched the build identity file, which belongs in a release.
- Generating the dependency checksums took five runs: I included testReleaseUnitTest, which this project does not support (180 Compose tests fail there for want of a debug manifest; the collector has never run it); then the one-off sweep failure above stopped a run; then lint stopped one on my own opt-in error.
- A shell here-document halved the backslashes of a regular expression in a test again. Repaired with the edit tool.

Acceptance:
| Item | State | Evidence |
|---|---|---|
| SEC01 Host lookalike, intent, TLS: safe rejection, no arbitrary execution | PASS (JVM) | WebBoundariesTest |
| SEC02 Foreign custom command denied, legitimate transport usable | PASS (JVM) for the rule and its wiring; not tried with a second app on a device | WebBoundariesTest |
| SEC03 Adversarial diagnostics: no credentials, URLs or IDs stored or exported | PASS (JVM); the ring read back from the emulator agrees | DiagnosticEventTest; device-ring-scan-01.txt |
| Release-content checks | PASS on the unsigned release APK; certificate not checked | release-content-01.txt |
| No unresolved high-severity issue | None known | security-review.md, "Open, by severity" |
| No secret in stored or exported event fixtures | PASS | the planted values in DiagnosticEventTest are invented |
| Wrapper checksum from the official release | PASS | gradle-wrapper.properties; security-review.md |
| Dependency verification or locking where supported | PARTIAL: recorded and strict locally, lenient on CI, not provenance | gradle/verification-metadata.xml |
| CI actions pinned to reviewed commits, least privilege, no secrets to PR code | Pinned and read-only; the actions' code was not read; workflows not run | .github/workflows |
| security-review.md | Done | docs/quality/security-review.md |

Decisions (mine, not the user's):
- The reduction is on shape and a list of keys, not a list of known secrets. Events stay written as text at their call sites and are parsed at the one place they are stored, rather than every call site being rewritten to build an object.
- A field that is refused keeps its key and loses its value. The key of a refused field is itself text from the event, limited to a short plain form.
- Three player commands are withheld from other apps. I chose them from Media3's list; the user did not. If Android Auto or an assistant turns out to need one, that is one array in WebBoundaries.kt.
- Photo pages may be on any HTTPS host: the photo services link to many sites. Only the scheme and form are held.
- Content access was left on in the sign-in browser rather than changed without a device to try it on.
- Dependency verification is strict locally and lenient on CI, rather than left out until Linux checksums exist, or strict everywhere and CI broken.
- The release-content check is a CI step now, although CI is Q16's package: it is one line and the check is this package's.

Not covered by any test:
- A second app as media controller on a device; Bluetooth, the lock screen, Android Auto and Assistant after the three commands were withheld.
- The three browsers on a device: a real certificate error, a real hostile page, the picker.
- A signed APK's certificate through the new script (the release script's own check is the existing one).
- The workflows.
- Whether the checksums in verification-metadata.xml are the publishers'.
- Everything under "Not looked at in this package" in security-review.md: overlays on the sign-in screen, the clipboard, known vulnerabilities in dependencies, the provider library's own network code.

BLOCKED or not done, with rerun steps:
- Foreign controller on a device: needs approval to install a second, throwaway controller app on the emulator or a phone; connect a MediaController to the service, try removeMediaItem and play/pause, confirm the first does nothing and the second works; then pair Bluetooth or a car head unit on a phone and confirm transport.
- Content access in the sign-in browser: needs a test account on a device; steps in security-review.md section 2.
- Linux checksums: needs a Linux build with network access (a pushed CI run or a local Linux machine); steps in security-review.md, "Dependency verification".
- Carried from Q04 to Q14: as listed in those entries. Q14's physical budgets and the startup-logo decision are unchanged.

Network use in this package: one read of gradle.org and services.gradle.org for the two checksums and of GitHub's public API and release pages for six tags, through a web-fetch agent. Nothing was downloaded into the build and no Gradle run was online.
Device state: emulator shut down with adb emu kill after each run, adb server stopped, Gradle daemons stopped before and after the stress build. The emulator's network stayed off throughout. It holds the debug app, its test APK, the benchmark variant and the benchmark test APK. No physical device, no real account, no provider request.
Not part of this package and left untouched: scripts/download_favourites.py and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Commit: c368a37 on local branch codex/riff-quality-m0 (not pushed), after 4312497, 70e1949, 93136d8 and 5aa172c.
Next exact action: Q15A (exit-reason diagnostics) is next in the plan's order and is one of the packages that is not authorized; it needs explicit authorization. Q16 is inside the 9 October authorization; wait for "start Q16" or for authorization of Q15A.

## Q12A
### Q12A completion (14 October 2026)
Status: PASS locally with open items; the live feed is BLOCKED. UPD01 and UPD02 pass as JVM tests with a scripted feed, clock and network. No code path downloads or installs. There is no public release feed, so the check is built in but has no address: this build shows no setting and sends nothing. The notice and the About rows were not seen on a device.
Source: a374ffa6695285335a6229763ffcb08b4fd63390 dirty (the two user files named at the end are the only uncommitted changes), digest prefix a7fd258b6340 (full value in the manifests). The debug run started before the commit, from e7de619 with the same files and the same digest. Evidence roots: artifacts/quality/e7de61944b34fe2298f3fe64b1c4e8195396b425-dirty-a7fd258b6340/ (debug) and artifacts/quality/a374ffa6695285335a6229763ffcb08b4fd63390-dirty-a7fd258b6340/ (release).
Commit: a374ffa (code, tests, documents).
User instruction on 14 October 2026: "start q12a, then a15a then complete q16". This is the authorization for Q12A, which the 9 October authorization (Q03-Q18) did not cover. "a15a" is read as Q15A (there is no package A15A).

Where releases are published (plan action 1):
- README: "No APK release has been published from this new repository yet."
- github.com/leovaldez2k21/riff and github.com/leovaldez2k20/riff both answer 404 to unauthenticated requests for the page and for api.github.com/repos/.../releases. Neither is public. The authenticated gh account on this machine cannot see either.
- build-release-candidate.ps1 and package-source.ps1 build and package; neither publishes.
So no endpoint was invented. The feed address and the allowed page hosts are Gradle properties (riffUpdateFeed, riffUpdatePages, the latter defaulting to github.com). Without a feed the checker reports NotConfigured, and the Settings rows are not drawn.

Implemented, by plan item:
- 1 Feed. BuildConfig.UPDATE_FEED and UPDATE_PAGES from the properties. The format read is GitHub's release list, or its single latest release. docs/quality/update-notice.md says what to set once releases are public.
- 2 Version. ReleaseVersion parses 0.10.0 and 0.10.0-rc1 (a candidate ranks below its release) and the installed name with the Lite suffix. Only a strictly newer release is offered. A debug, dirty or local-unknown build (isReleaseBuild) is never called current: it says it is a local build and names the newest release.
- 3 Schedule. MainActivity.onResume -> AppContainer.checkForUpdatesIfDue, on the IO scope. At most once a day, by the stored date and, within a process, by the monotonic clock. A stored date in the future counts as due once. Skipped on a metered network while Data saver is on. Check for updates now in About asks at once.
- 4 Setting. Check for updates in Settings, About, on by default. Off hides the manual check too and sends nothing. docs/privacy.md says so.
- 5 Request. One GET of the feed address with Accept and User-Agent: Riff-update-check, nothing else; the provider client and its cookies are not used.
- 6 Notice. One snackbar with View and a close button per newer version (noticeShown records it), and the About row "Riff X is available" while it is newer. View and the row open the page through openWebPage with the page hosts (Q15). No system notification, no download, no install request.
- 7 Bounds. 256 KiB read at most. Malformed, oversized, 403/429, other errors, unreachable, offline and a redirect to plain HTTP are silent; the attempt time is stored before asking, so a failure waits a day like a success. The manual check says what happened. 404 is read as no release published.
- 8 Seams. The checker takes its preferences, feed, clocks, network, Data saver reading, HTTP client and dispatcher as arguments.

Verification:
- Debug (q12a-debug-01/manifest.json): PASS_LOCAL. 964 JVM tests / 106 suites, zero failures, errors or skips (Q15: 952 / 105). Lint 0 errors, 84 warnings, 3 hints. Against q15-debug-01, four added (UseKtx in UpdateNotice.kt: SharedPreferences.edit to the KTX form; a style suggestion, the same as the 40 UseKtx already triaged) and none removed. APK SHA-256 9f94ac8134eb4e1bf9003dc4e0ae756df4de06aaead7b4454d6c39261a9ba903.
- Release (q12a-release-01/manifest.json): PASS_LOCAL. 964 tests / 106 suites, zero failures, errors or skips; lint 0 errors, 84 warnings, 3 hints; against q15-release-01 the same four UseKtx added in UpdateNotice.kt and none removed. APK SHA-256 ee478e6105d7f95b0cc8a66b6a17f6d61a04b8f1c4f5b1769b6bfbca7dbd22b0 (unsigned).
- New JVM tests, 12: UpdateNoticeTest. Version order and refused forms. Newer, same and older answers. A prerelease, which counts only for a prerelease install, and a draft. A local build. Failures: malformed, a non-list value, oversized, 403, 429, 500, unreachable, offline, a redirect to plain HTTP. Page lookalikes in the feed and on the phone. One notice per version across a restart. Off and no feed send nothing (UPD02). The daily throttle under date changes and failures. The request has exactly two headers. The source has no installer, download or activity start, the manifest no install permission.
- StringResourcesTest passes with the twelve new strings.
- Static checks: 157 files, 0 findings.
- No emulator run for this package: with no feed nothing appears or is sent, and the fixture suite does not touch the change. Q15A's emulator run includes these changes.

Decisions:
- An empty feed address rather than BLOCKED with no code. The plan says to mark the package BLOCKED rather than invent an endpoint; no endpoint was invented, and the code and its tests are ready for the day a feed exists. The live part is BLOCKED.
- Off also hides the manual check, so off is zero requests without exceptions.
- A prerelease counts for a prerelease install, because every build so far is a candidate (0.10.0-rc1).

BLOCKED or not done, with rerun steps:
- Live feed (UPD01 against a real feed): publish a release on a public repository, set riffUpdateFeed and riffUpdatePages in gradle.properties, build, and check that a build one version lower shows the notice and that View opens the release page. Needs the user to publish (not authorized here).
- The notice and the About rows on a device: build with a feed, install on the emulator (approved practice) and look; not done because no feed exists.

Network use in this package: unauthenticated reads of github.com and api.github.com for the two repository paths (404). No Gradle run was online.
Device state: no device or emulator used for this package.
Not part of this package and not committed with it: scripts/download_favourites.py (user change) and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Next exact action: Q15A, authorized by the same instruction.

## Q15A
### Q15A completion (14 October 2026)
Status: PASS locally with open items. DIAG01 passes on the fixture emulator for a crash in each process, a stop from Android settings and a SIGKILL; ANR and low-memory exits are covered by scripted records only. SEC03 passes with the new records and the report included. The screen was checked under Robolectric, not looked at on a device.
Source: 2a7056c dirty; the evidence was built from d3bcb98 with the Q15A files uncommitted, the same files later committed in 2a7056c, digest prefix 3f46088455e5 (full value in the manifests). The other uncommitted files at the time were the Q16 scripts, workflows and documents (outside the source digest) and the two user files named at the end. Evidence root: artifacts/quality/d3bcb9809d8e7960447ddc991ae6cf55a0bedd27-dirty-3f46088455e5/.
Commit: 2a7056c (code, tests, documents). The CHANGELOG and architecture.md were staged without their Q16 lines.
Authorization: the user's instruction of 14 October 2026, "start q12a, then a15a then complete q16"; "a15a" is read as Q15A.

Implemented, by plan action:
- 1 Exits. RiffApplication.onCreate starts a thread in the main process only that reads getHistoricalProcessExitReasons for the package and records those newer than the last seen (exit_records preferences: seen_at, last_version_code). The version filed is the one that ran: after an update, the first start files exits under the previous code.
- 2 Fields. process (main, solver, other), reason as a word (unknown future codes become reason_<n>), importance, the start of the hour, version code. The description and the trace stream are never read.
- 3 Errors. CrashRecorder, installed in every process: exception class names (up to five with causes) and up to six frames in the app's package from the deepest cause that has any, never a message. It then calls the previous handler with the same thread and exception. On a plain JVM with no previous handler (unit tests) it prints the error as the JVM would; an earlier draft ended the process there, which ended the Gradle test JVM, see below.
- 4 Store. no_backup/diagnostics: exits.log (last 12, main process only), crash-main.log and crash-solver.log (each written only by that process, the last 4 kinds, a repeat raises its count). 8 KiB cap, temporary file and rename, exact-shape parsing. Apart from the ring.
- 5 Screen. Settings, About, Diagnostics (replaces the Copy playback diagnostics row, which moved inside): Last exits in words, failures marked, a stop from settings, by the user, an update or a swipe-away (other) neutral; Errors with class, process, count and hour.
- 6 Report. Share diagnostics report (ACTION_SEND through the chooser, no component), Copy playback diagnostics (same text), Clear exit records. Fixed content: identity line, build identity, exits, errors with frames, the ring; every line through DiagnosticEvent.parse. No network endpoint.
- 7 Soak. scripts/stress/scenarios.py reads the files with run-as before and after each scenario and writes exitRecords into result.json (informational). That file also carries Q16's SDK lookup and is committed with Q16. No stress run was made.

Verification:
- Debug (q15a-debug-05/manifest.json): PASS_LOCAL. 975 JVM tests / 108 suites, zero failures, errors or skips (Q12A: 964 / 106). Lint 0 errors, 85 warnings, 3 hints; against q12a-debug-01 one added (UseKtx, ExitDiagnostics.kt:172, SharedPreferences.edit to the KTX form; style, as the others) and none removed. APK SHA-256 3c54d97580015e33dbc4176e0dbe84a07d4c4de79a433bd19e5af45251e31e5c.
- Release (q15a-release-01/manifest.json): PASS_LOCAL. 975 / 108, zero failures; lint 85 warnings, 3 hints, the same one UseKtx added against q12a-release-01. APK SHA-256 2133299a2ddd19308b7edbbefcff1d4cdcfedba412feab7957aadb96fe06d0bd (unsigned).
- Release content (release-content-01.txt): every check ok; certificate n/a (unsigned).
- Emulator, bound (q15a-debug-fixture-02/manifest.json, log q15a-instrumentation-03.log, install-02.log): PASS_LOCAL, all five gates PASS. 46 tests, third attempt, network off (ping_exit=2). Test APK SHA-256 8656ed5ace492f1d7924e0114f72c2d6225a812dcf8ea5c459d2804532454e02. The installed app APK matched the manifest hash. riff_q02_fixture, API 31, sdk_gphone64_x86_64.
- DIAG01 (q15a-diag01-01.txt, -02.txt, -03.txt), on the same emulator and install, after the fixture run:
  - am crash by package crashed the solver: exits.log got process=solver reason=crash importance=100 and crash-solver.log "uncaught process=solver causes=RemoteServiceException count=1 frames=" (no app frames: the exception is thrown from framework code).
  - am force-stop: process=main reason=user_requested, shown neutral.
  - am crash by the main pid: process=main reason=crash and crash-main.log causes=RemoteServiceException.
  - SIGKILL of the main process (kill -9 as emulator root; run-as kill was refused, see below): process=main reason=signaled.
  - Each was read at the next start, as designed. The two earlier lines (main 125, solver 300, user_requested) are the instrumentation runs ending. No record line matched http, cookie, SAPISID, @ or title=.
  - adbd was returned to non-root afterwards.
- New JVM tests, 11: ExitDiagnosticsTest (8) and DiagnosticsScreenTest (3), as listed in docs/quality/exit-diagnostics.md, plus a case for a recorder with no previous handler.
- Static checks (static-checks-01.txt): 159 files, 24 reviewed sites, 658 raw measures in 34 screen files, 0 findings. Quality script tests (quality-script-tests-01.txt): 138, OK (this count includes Q16's test_verify.py, uncommitted at the time).

Failed evidence attempts (kept):
- q15a-debug-01: the Gradle test JVM ended with exit value 10. The recorder's no-handler branch called exitProcess, and some test let an exception escape a background thread. Fixed as described in action 3, with a test.
- q15a-debug-02: DiagnosticsScreenTest found two Share rows. The Errors item was added to the lazy list only when there were errors, which moved the Report item for a frame. Fixed: every item has a key and the Errors item is always present, drawing nothing when there are none; the tests wait for both sections.
- q15a-debug-03: ServiceStopDiagnosticsTest (Q15, unchanged until then) failed because the planted value "abc" occurs in this source digest (38802dababc9...). The test now looks for planted values below the identity line. A test fault, not an app fault.
- q15a-debug-04: a test edit of mine put a real newline into a string literal (shell quoting); compile error, repaired with the editor.
- attempts/q15a-instrumentation-01-failed.log: 46 run, 1 failed, DownloadLifecycleDeviceTest.aDownloadThatBeginsRightButCannotBePlayedFailsAsADownloadAndIsNotRetried timed out (the Q13 weakness), while the release collector was building.
- attempts/q15a-instrumentation-02-failed.log: 46 run, 2 failed, CrossfadeFixtureDeviceTest "The two decoders did not align in four attempts" (the other Q13 weakness), Gradle stopped, 3.5 GB free. Attempt 03 on a cold-started emulator passed. Neither test was changed.
- attempts/q15a-debug-fixture-01-misbound: bound with the instrumentation start time as the run start, so the collector called the JVM and lint reports stale. Rebound as fixture-02 with the debug run's start time, as Q15 did.

Open items:
- ANR, low memory, native crash and the other failure reasons were not produced on a device; scripted records only.
- The Diagnostics screen was not looked at on a device or with TalkBack.
- run-as cannot signal the app's process on this image (SELinux); the SIGKILL check needs a rootable emulator.
- Action 7 was not exercised: no stress run.

Network use in this package: none. All Gradle runs were offline.
Device state: only the disposable fixture emulator (emulator-5580, network off). It was restarted once (cold) and left running for Q16. No physical device was attached.
Not part of this package and not committed with it: scripts/download_favourites.py (user change), docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md, and the Q16 files.
Next exact action: Q16, authorized by the 9 October authorization and the same instruction.

## Q16
### Q16 completion (14 October 2026)
Status: PASS locally with open items. The pull-request lane passes offline with strict dependency verification; the emulator lane passes on API 31; the packaging lane is BLOCKED for the certificate and the upgrade, as it must be without a signed candidate. API 33 and 36, the physical-device and benchmark lanes, and any hosted CI run are BLOCKED or not run (see below).
Source: evidence taken on b40d596 dirty with the Q16 files uncommitted, digest prefix 9ca2bdfd2482 (gradle/verification-metadata.xml is in the digest and was changed before the runs; full value in the manifests). Committed as b3d6c77, which differs from the evidence state only in scripts/quality/test_verify.py (outside the digest; see below). Evidence: artifacts/quality/lanes/pr-20261014T112341Z, emulator-20261014T113650Z, packaging-20261014T114357Z, q16-guards*, q16-script-tests-02.txt.
Commit: b3d6c77 (scripts, workflows, metadata, documents).
Authorization: the 9 October authorization (Q03-Q18) and the user's instruction of 14 October 2026, "start q12a, then a15a then complete q16".

Implemented, by plan item:
- Lanes. scripts/quality/verify.py (verify.ps1 calls it) with five lanes and a fixed list of required gates each: pr (script tests, static checks, JVM and Compose tests, release lint, an unsigned shrunk release with identity, release content including the merged manifest, the 40 MiB ceiling and Baseline Profile, solver seam), emulator (fixture suite, network off inside the emulator), device (an authorized phone named by --serial only), benchmark (physical R or L only; an emulator is refused), packaging (source archive check, release identity and certificate, SHA256SUMS, an in-place upgrade from --previous-apk with install -r, never an uninstall).
- Parameters: --checks, --serial, --tier, --mode fixture|live (a label only; no credentials are supplied), --evidence-dir, --allow-install, --previous-apk, --offline, --tools.
- Device choice: exactly one emulator may be chosen automatically for the emulator lane; a phone never is; several devices need --serial. A per-device lock (named by a hash) stops two lanes sharing a device.
- Results: lane-summary.json and .md. A required gate missing is FAIL, one that cannot run is BLOCKED with its reason, nothing defaults to zero. Exit 0, 2 BLOCKED, 1 FAIL. Devices are named by tier, API and a cleaned model, never by serial.
- Instrumentation: through collect_results.py, which reads cases one by one (INSTRUMENTATION_CODE -1 alone is not success; a crash, abort, missing case or truncated log fails), compares the two APKs' build identities before installing, and records both hashes.
- Read-only and install modes: without --allow-install no lane touches a device; the first output line says READ-ONLY or INSTALLS ON A DEVICE.
- Tools: SDK from ANDROID_HOME, ANDROID_SDK_ROOT, sdk.dir in local.properties, then .toolchain/sdk; JDK and Gradle home likewise; paths from the script's location. scripts/quality/Tools.ps1 does the same for the PowerShell scripts, now used by check-layout-modes, check-shell, build-lite, build-release-candidate, verify-device, run-ui-regression and the stress scripts; restore-app-data.sh and scenarios.py too.
- download_favourites.py: the personal adb path, phone serial and work folder replaced by --serial, --adb and --work-dir. Only that header change was committed; the user's own uncommitted edit further down the file was left as it was and is not in the commit.
- CI: ci.yml runs the pr lane (also on codex/**), cancels superseded runs, uploads only summaries and manifests (14 days). emulator.yml (new) runs the emulator lane on API 31, 33 and 36 after a push to main or codex/** and on request, actions pinned by commit, 7-day retention, no secret. provider-canary.yml and ci.yml no longer set dependency verification lenient.
- Dependency metadata: aapt2 8.13.2-14304508 Linux and macOS jars added with SHA-256 values computed from Google Maven downloads, each matching the SHA-1 published beside it; the Windows jar fetched the same way matched the recorded value.
- Soak: scenarios.py records exit records per scenario (Q15A action 7).

Verification:
- pr lane, offline (pr-20261014T112341Z): PASS_LOCAL, all nine gates PASS. 975 JVM tests / 108 suites, zero failures; lint 85 warnings, 3 hints (as Q15A). Release APK SHA-256 227f23db493760d066315578dd41db276814f94cb81076b555cc853aa988f2e2 (unsigned). The build resolved everything under strict verification with the new metadata.
- emulator lane, API 31 (emulator-20261014T113650Z): PASS_LOCAL, all four gates PASS. 46 tests, first attempt; pairing compared identities (revision b40d596, digest 9ca2bdfd...); network off (ping exit 2). App APK e41650d6449025ef82681cd530a4f8c6bcd8d1d3c45ce824f219170a137e847f, test APK d96ed1786ee4a2783ea00781c3e240ddbc1c24ae78b8abe7f7b282bfdcc9cf01. The serial appears in neither summary.
- packaging lane (packaging-20261014T114357Z): BLOCKED. source-archive PASS (574 tracked files, none forbidden, state dirty so not an exact candidate), checksums PASS, release-identity BLOCKED (unsigned), upgrade BLOCKED (no --allow-install or --previous-apk).
- Guards (q16-guards-*.txt): the emulator lane without --allow-install exits 2 with every gate BLOCKED and touches nothing; the device lane without --serial exits 2 ("a phone is never chosen automatically"); the benchmark lane pointed at the emulator exits 1 ("physical device only").
- Script tests: 138, OK, inside the pr lane, and again after the change below (q16-script-tests-02.txt). test_verify.py adds 23, among them a missing gate is FAIL, an incomplete instrumentation log is rejected, a device needs --serial, nothing installs without permission, the summary holds no serial, the SDK lookup order, and no supported script holds a personal path.
- After the lane runs, test_verify.py's list of personal-path markers was changed so that the repository does not carry a user name or a phone serial as a marker (it now looks for home-folder and workspace patterns). Outside the source digest; rerun as above.
- All eleven .ps1 files changed or added parse without errors (PowerShell parser, after the commit).

BLOCKED or not done, with rerun steps:
- Emulator lane on API 33 and 36: those system images are not installed. sdkmanager "system-images;android-33;google_apis;x86_64" "system-images;android-36;google_apis;x86_64" (network), create one AVD for each, start it with its network off, then python scripts/quality/verify.py --lane emulator --allow-install --serial <emulator>. Or push the branch and read emulator.yml (pushing needs the user's approval).
- Hosted CI: neither workflow has run; nothing was pushed. The first ci.yml run is the check that the Linux build needs no other unlisted artifact. Q15's open medium item (dependency verification not enforced on CI) is fixed in the workflows and metadata and closes with that first strict hosted run.
- Physical-device and benchmark lanes: need an authorized test phone (R and L tiers) and approval to install on it.
- Packaging certificate and upgrade: need a candidate signed with the release key and the last distributed APK (Q05R, not authorized).
- A clean-checkout run: the lanes ran in this working tree (state dirty because of the two user files). A fresh clone with only the SDK and JDK was not tried.

Network use in this package: Google Maven downloads of the three aapt2 jars, to compute their checksums; no Gradle run was online.
Device state: only the disposable fixture emulator (emulator-5580, API 31, network off); the emulator lane reinstalled its debug app and test APK. No physical device was attached.
Not part of this package and not committed with it: scripts/download_favourites.py's remaining user change and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Next exact action: Q17 (current documentation and packaging), inside the 9 October authorization; not started in this session.

## Q16A

### Q16A completion (14 October 2026)
Status: PASS locally and on the disposable emulator, with open items. Overlapping lines, background vocals and the held-word pop and glow pass as JVM tests, as Robolectric drawings and on the API 31 fixture emulator with a synthetic word-timed song. Nothing was seen on a phone, no real song was played (the emulator has no network), and reduced motion was checked under Robolectric only (below).
Source: evidence taken on 8500538 dirty with the Q16A files uncommitted, digest prefix 585746c18d43 (full value in the manifests). Committed as dc9fef3, which differs from the evidence state only in docs/quality/ci-lanes.md (one sentence; outside the digest). Evidence: artifacts/quality/8500538f03bb30e0ee7bab626b8cf3d5a6bb9245-dirty-585746c18d43/q16a-debug-05, q16a-release-02; artifacts/quality/lanes/emulator-20261014T135656Z (with q16a/: three device PNGs, the two number files, the logcat lines and a 22 s recording).
Commit: dc9fef3 (code, tests, lane list, documents).
Authorization: the user's instruction of 14 October 2026 ("integrate this into the current plan and execute this before the next phase starts ... do this before q17") and their four answers: background vocals included, white sweep with a pink glow, only held words grow and rise, emulator now and their phone later. The reference was the user's screen recording of an Apple Music lyrics view (the word "true" in the first line); it was watched, not stored in the repository.

Implemented, by plan action:
1. Active set: activeLyricLines returns every line whose window holds the position (a line's window runs to the later of its end and its background vocal's end), scanning back at most 24 lines. activeLyricLine is now the last of that set. The view scrolls to the earliest active line, else the latest started one. Line-synced lyrics without end times behave as before (tested).
2. Background vocals: TTML spans with ttm:role="x-bg" go to LyricBacking (text, start, end, word timing relative to its own text), drawn as a smaller line (20 sp, 27 sp line height) under the main one and lit on its own timing; the timing is no longer clipped to the main line. A line holding only a background vocal is kept. The cache and offline lyrics files store "backing" only when present, so a line without one is written exactly as before and older files still read. The cache key moved to hierarchy-v3, so lyrics cached before are fetched once more (CHANGELOG).
3. Held words: syllables that touch with no space are one word. Held when at least 1600 ms, or at least 900 ms with a per-letter time at least 1.5 times the line's upper median. Word-synced lyrics only.
4. Drawing (LyricsKaraoke.kt): the sweep is white. While a held word on one display line is sung it grows to 1.07 around its own centre and rises 2 dp over 220 ms, with a pink glow (RiffLyrics.glow) over its lit letters and a soft 10 dp sweep edge (right-to-left aware); it eases back over 380 ms after its end. The rest of the line is drawn around it and does not move. A held word broken across two display lines gets nothing. Reduced motion (Remove animations, or animator duration scale off): glow only. Sizes, rise, glow and edge are tokens in DesignTokens.kt (RiffLyrics).
5. Per-frame work: the position is read only while drawing. Held words are measured once per text layout (remember keyed by layout, words and style), not per frame.
6. Sources: documented in docs/features.md; nothing is invented for line-synced or plain lyrics.

Verification:
- q16a-debug-05: PASS_LOCAL. 985 JVM tests / 109 suites, zero failures (Q16: 975 / 108; +7 in LyricsTest, +3 in the new LyricsKaraokeTest). Lint 85 warnings, 3 hints. App APK 28542104216cb36f458c1ae8677e7322275247c387124964b14c9bf5feb63bb0.
- q16a-release-02: PASS_LOCAL. 985 / 109, zero failures; lint 85 warnings, 3 hints, nothing added or removed against q15a-release-01. Release APK 745e6d8423d9c03642a27ee4dce663d6752ed458210fe39186c53cd70676b544, 28,263,130 bytes (unsigned).
- JVM (LyricsTest): two overlapping lines lit together; the earlier line stays lit after the later one ends; the scan is bounded; x-bg parsing with its own timing past the line end and a line holding only a background vocal; cache round trip with and without backing and reading an older file; syllable grouping; the held-word thresholds on "Find your dreams come true", a uniformly slow line and the 1600 ms edge; the rise and glow curves.
- Robolectric with native graphics (LyricsKaraokeTest): no pink before the held word (the sweep is white), more than 200 pink pixels while it is held, none after it settles, and its lit letters higher than when settled; under reduced motion the glow stays and the letters do not move; the background line is present, below the main line, lit on its own timing, and the line says it is current.
- Emulator lane, API 31 (emulator-20261014T135656Z): PASS_LOCAL, 48 tests (the 46 before plus LyricsKaraokeDeviceTest's two), network off (ping exit 2). App APK as debug-05; test APK dc6a0d7358c8e6894d6eda64c80ab3361c6980af6e8c325a7ec74b1125412404. On the device's own renderer: pink pixels before=0, held=770, after=0, and two lines current at once. Real-time playback: 337 position updates, median 50 ms, p95 66 ms between them (test-loop updates on an emulator, not frame timings, and no budget evidence).
- The recording and frames were compared by eye with the user's reference: the held word lifts, grows and glows while the next line is already lit, and the background vocal sits under its line.
- Static checks: 160 files, 0 findings; LyricsPanel's raw-measure budget lowered from 49 to 46. Script tests: 138, OK.

Attempts kept, not counted:
- Digest e992545a72e0 (before the device test was final): a lyrics device run hidden by an emulator "System UI isn't responding" dialog, one with a pink threshold set from the JVM renderer (the device glow is paler), one whose clock started before the screen was up, and a fixture binding moved there when an androidTest edit changed the digest. Folder: artifacts/quality/8500538f...-dirty-e992545a72e0/attempts.
- Digest 92f7ed4fb305: q16a-debug-02 and q16a-release-01 passed. Emulator lane emulator-20261014T130538Z failed on the known CrossfadeFixtureDeviceTest "decoders did not align" (Q13 weakness). The rerun after a cold emulator start, emulator-20261014T131456Z, ran OK (48 tests) but the lane failed it: the lyrics test reported its numbers with sendStatus, and the parser reads every status as a test case ("Incomplete instrumentation case"). The parser was right; the test now writes to logcat and a file, and docs/quality/ci-lanes.md says so. That edit changed the digest, so everything was run again.
- Digest 585746c18d43: q16a-debug-03 failed on DiagnosticsScreenTest.shareSendsTheCleanedReportThroughTheSystemSheetOnly (10 s wait for the share sheet) while the emulator was running beside the build; the class passed three times alone and in debug-05. A timing weakness of a Q15A test under load, recorded as an open item. q16a-debug-04 failed because Gradle reused debug-03's APK and lint report, which predate the run; those outputs were removed and debug-05 rebuilt them.

Open items, with rerun steps:
- On a phone: the user will lend a phone for a full USB pass over the plan. Then: python scripts/quality/verify.py --lane device --allow-install --serial <phone> --tier R|L, pull files/q16a-*.png and q16a-*.txt from the app's external files folder, and play a song with TTML word timing and background vocals. Installing needs the user's confirmation of the phone and the build.
- Real songs: no provider was reached (emulator network off). Which songs get TTML with x-bg depends on the providers; check on the phone.
- Reduced motion on a device: covered by Robolectric only; on the phone, turn on Remove animations and confirm the held word glows without growing.
- API 33 and 36 emulator images are not installed (as Q16).
- DiagnosticsScreenTest's share wait is timing-sensitive under load (above); not changed here because it is Q15A code and passes alone.

Network use in this package: none.
Device state: only the disposable fixture emulator (emulator-5580, API 31, network off); the lane reinstalled its debug app and test APK; the recording was removed from the emulator after it was pulled. No physical device was attached.
Not part of this package and not committed with it: scripts/download_favourites.py's remaining user change and docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md.
Next exact action: Q17 (current documentation and packaging), inside the 9 October authorization; not started.

## Plan v1.1 additions (11 October 2026)
Plan revised to v1.1; see plan section 22. No application code changed by the revision.
New packages. Status TODO and implementation not yet authorized unless the line says otherwise (the 9 October authorization covers Q03-Q18 only):
- Q05A local export/import. Dependencies: Q03, Q04, Q05. Authorized by the user on 11 October 2026 ("start Q05A"). Status: PASS locally with open items (see the Q05A section).
- Q05R interim release R1. Dependencies: Q00-Q05 PASS.
- Q08A loudness normalization. Dependencies: Q08. Authorized by the user on 11 October 2026 ("start q08 followed by q08a"). Status: PASS locally with open items (see the Q08A section).
- Q10A provider canary and response runbook. Dependencies: Q10. Authorized by the user on 12 October 2026 ("do q08a and q10a"). Status: PASS locally with open items (see the Q10A section).
- Q12A update notice. Dependencies: Q01, Q12. Authorized by the user on 14 October 2026 ("start q12a, then a15a then complete q16"). Status: PASS locally with open items, live feed BLOCKED (see the Q12A section).
- Q15A exit-reason diagnostics. Dependencies: Q15. Authorized by the user on 14 October 2026 (same instruction). Status: PASS locally with open items (see the Q15A section).
- Q16A lyrics: simultaneous lines, held-word emphasis, background vocals (plan section 22, added 14 October 2026 at the user's request). Dependencies: Q12, Q13. Authorized by the user on 14 October 2026 ("integrate this into the current plan and execute this before the next phase starts ... do this before q17"), with these choices: background vocals included, white sweep with a pink glow, only held words grow and rise, emulator check now and the user's phone later. Status: PASS locally and on the emulator with open items (see the Q16A section).
Changed inside existing packages: Q14 now includes a Baseline Profile (optimization rule 8).
New contract rule 16: commit each package locally on its branch before starting the next. Q00-Q02 work is still uncommitted on main; committing it is the next step before Q03 and has not been done. Pushing remains unauthorized.
