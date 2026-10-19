# Case map (Q18C5, gate G01)

First pass, 19 October 2026, before the fix package. Every required case of the plan is mapped to its tests,
its run on a device or its BLOCKED entry, with one status from the list below. Sources: the plan's acceptance
matrix (section 16, 62 cases), the UX01-UX10 journeys (section Q12), the invariants (section 3), and each
package's acceptance in `execution-ledger.md`. Bugs are in `q18-bugs.md`. The second pass, after the fix package,
updates every row the fixes touch and is the input to C9.

Status words, and only these:

- **PASS**: the expected result is shown by the tests and runs named, on the stated build.
- **FAIL**: a check ran and the expected result did not hold. The bug named holds the fix; the case is rerun
  after the fix package.
- **BLOCKED**: the evidence cannot be produced here (hardware, account, network or publication missing). The
  rerun is named.
- **DEFERRED**: the owner chose to run it after the fix package.
- **TODO**: can be run, is scheduled in Q18 (B2, B8, B9, B10, C9) and has not run yet.
- **DECLINED**: the owner decided the case or package is not done at all (recorded with the date).

A row's status is its weakest part, in the order FAIL, BLOCKED, DEFERRED, TODO, PASS. DECLINED rows are counted apart. "JVM" means JVM or
Robolectric tests in the pull-request lane (1006 tests at C6, 0 failures); "emulator" means the fixture lane on
API 31, 33 and 36 (51 tests each, C2); "phone" means the owner's I2217, Android 16, Riff Preview 7f7ab8e unless
stated; "B1" means the same 51 instrumented tests on that phone (Riff Debug, 51 of 51).

All evidence below was taken on builds before the fix package. Code changes invalidate the affected rows (plan
section 18); C9 reruns them on the final candidate.

## 1. Acceptance matrix (plan section 16)

### Account and scope

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| ACC01 result after A->B | AccountSessionTest, DownloadOwnerSwitchTest (JVM); AccountSwitchDeviceTest.queueRequestBuiltForThePreviousAccountIsSkipped (emulator, B1) | A6: the owner's last search and results stayed on Search under the dummy account and were saved to its recent searches | FAIL | Q18-012 |
| ACC02 A->B->A | AccountSwitchDeviceTest.eachAccountGetsItsOwnQueueBackPaused (emulator, B1; synthetic identities) | Real A->B->A goes with UX07 | DEFERRED | UX07 after Q18-009 |
| ACC03 cookie rotation | AccountSessionTest (same owner, no quiesce, transfers continue) | none needed | PASS | |
| ACC04 selector order | AccountScopeProviderTest (JVM, Robolectric) | Two real accounts at swapped chooser positions not tried: Change account is broken | DEFERRED | Q18-009, then the Q03 rerun steps |
| ACC05 cancelled or failed chooser | AccountScreenSessionTest (failed sign-out keeps the account) | A6: after a Change account that did not finish, Riff kept the old account while the sign-in browser's cookies had moved to the new one | FAIL | Q18-009, Q18-010 |
| ACC06 missing stable ID | AccountSessionTest, AccountScreenSessionTest, HistoryScreensTest.unverifiedAccount... (JVM) | none needed | PASS | |
| ACC07 local vs mixed on switch | ScopedPlaybackSnapshotsTest; AccountSwitchDeviceTest.onDeviceQueueKeepsItsPlaceAcrossAnAccountSwitch (emulator, B1) | none needed | PASS | |

### Data, deletion and migration

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| DAT01 clear while write queued | LocalDataControllerTest, LibraryStateTest (tickets taken before a clear refused, also after reopening); LocalDataDeviceTest (emulator, B1) | Clearing real history on the phone is part of A7 | DEFERRED | A7 privacy rows |
| DAT02 delete during transfer or assets | OfflineMusicStore discardUnlisted cases, DownloadEngineTest delete-all during assets (JVM) | Deleting real downloads is part of A7 | DEFERRED | A7 privacy rows |
| DAT03 kill migration at checkpoints | LegacyStateMigrationTest (death at every checkpoint, rollback, restart); LegacyUpgradeDeviceTest (emulator, B1) | none needed | PASS | |
| DAT04 ambiguous legacy data | LegacyStateMigrationTest (quarantine, explicit import only), HistoryScreensTest (Earlier data) | none needed | PASS | |
| DAT05 retention and time change | LibraryStateTest.retentionRemovesOldPlaysPerOwnerAndKeepsFutureDatedOnes; LocalDataControllerTest.retentionIsAppliedAtMostOnceADayWhenTheAppRuns, aClockSetBackwardsDoesNotPostponeRetention | none needed | PASS | |
| DAT06 full disk, migration and snapshot | LegacyStateMigrationTest full disk then retry; LocalDataTransferTest injected failures; DownloadEngineTest full disk at five points (all JVM, injected) | No real full disk produced; accepted as injected | PASS | |
| DAT07 clear disabled history | LocalDataControllerTest.turningHistoryOffStopsNewWritesAtOnceAndKeepsWhatIsThere; LocalDataDeviceTest (emulator, B1) | none needed | PASS | |

### Playback and queue

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| PLY01 pause before resolution | PlaybackStateTest pause while reading; PlaybackOrderDeviceTest (emulator, B1) | B3 step 2: paused 0.3 s after choosing, nothing in 20 s | PASS | |
| PLY02 rapid skips, replacement | PlaybackStateTest, QueueCoordinatorTest; PlaybackOrderDeviceTest (emulator, B1) | B3 step 1: three tiles in 0.66 s, only the last plays | PASS | |
| PLY03 Wi-Fi/mobile handover | PlaybackRecoveryTest (JVM) | B3 step 5: Wi-Fi off 33 s on mobile data, continuous | PASS | |
| PLY04 offline 60 s then online | PlaybackRecoveryTest (virtual time); RecoveryDeviceTest (emulator, B1 with VPN off) | B3 6a and 6b PASS with the VPN off. With a VPN on, Riff believes it is online and the wait is not taken; the session reports ERROR while waiting | FAIL | Q18-017; Q18-020 (Low) |
| PLY05 rejected or expired stream | PlaybackRecoveryTest (AttestationExpired, 403/410 refreshed once), SabrRecoveryPolicyTest (JVM) | Not reachable on a device in this build: ordinary playback never uses the SABR transport where the fault hook sits | BLOCKED | Q18-029 (make the hook reachable or replace the device case) |
| PLY06 solver death | SolverFailClosedTest, PlaybackRecoveryTest (JVM, fake solver) | B4: one crash recovers; kept down, exactly three attempts, but the message and class are wrong | FAIL | Q18-022 |
| PLY07 main-process death | PlaybackOrderDeviceTest process-death steps (emulator: 3,082 ms lost, bound 6,500) | B3 step 4: queue back paused, resumes from 22.2 s; shows 0:00 until Play | PASS | Q18-019 (Low, display) |
| PLY08 focus, call, noisy route | Q08 focus tests (emulator, another focus request) | A3: notification duck, real call, earbud pause and next, earbuds into the case and Bluetooth off, all during a blend. Car head unit BLOCKED (none) | PASS | car: BLOCKED, optional in the split |
| PLY09 unknown outcome mutation | ProviderMutationTest (eight unreadable answers, reconcile before retry) (JVM) | Live edit on the test account is in B10 (asked first) | TODO | B10 |
| QUE01 authored duplicates | PlaybackStateTest, QueueRegressionTest; PlaybackOrderDeviceTest (emulator, B1) | B3 step 3: menus cannot make two copies; Add to queue says "Added" when it adds nothing | PASS | Q18-018 (Low) |
| QUE02 filter or sort then play | CollectionToolsTest.alphabeticalSortingAndFilteringDoNotLoseNativeQueuePosition, CollectionSortMenuTest, QueueRegressionTest | A4 UX03 | PASS | |
| QUE03 remove final item | PlaybackStateTest (only entry: empty paused queue); emulator through the gateway | Widgets after an emptied queue not looked at | PASS | |
| QUE04 2,000-entry UTF-8 snapshot | PlaybackSnapshotTest (6 MiB ceiling, round trip), LegacyStateMigrationTest 2,000 tracks; PlaybackOrderDeviceTest 2,000 entries (emulator, B1) | Timing of a 2,000-entry queue is B7b | PASS | timing in PERF rows |

### Crossfade and audio

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| XFD01 blend 1/6/12 s | CrossfadeOwnerTest (JVM); CrossfadeFixtureDeviceTest (emulator, B1) | A2 steps 1, 2 and 6 on speaker and Bluetooth; wired BLOCKED (none) | PASS | wired: BLOCKED |
| XFD02 buffer, late start, early end | CrossfadeOwnerTest; CrossfadeFixtureDeviceTest two-player cases | none needed beyond A2 | PASS | |
| XFD03 pause, seek, skip, off, switch | CrossfadeFixtureDeviceTest (created and released counts equal) | A2 step 3; A3 steps 2-5 (fading-out song released in 9-54 ms) | PASS | |
| XFD04 boost and sleep | VolumeBoostTest, CrossfadeOwnerTest; CrossfadeFixtureDeviceTest boost at maximum, sleep timer in a blend | A2 steps 3 and 4 (200 and 300 %) | PASS | |
| AUD01 normalization on | LoudnessNormalizationTest (20, JVM); NormalizationDeviceTest (9, emulator, B1) | A2 step 5 by ear. A live loudness value from the provider not read | TODO | B10 (live loudness value) |
| AUD02 normalization off or missing | LoudnessNormalizationTest, NormalizationDeviceTest | none needed | PASS | |

### Downloads

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| DLD01 kill each transfer phase | DownloadEngineTest six stop points, DownloadReconcileTest each stored state (JVM) | B5: state right after a kill while Downloading and while Saving artwork, never falsely complete; but the song is not resumed. Reboot run PASS once Riff is opened | FAIL | Q18-021 (High) |
| DLD02 bad range, 403, truncate, full disk | DownloadEngineTest; DownloadLifecycleDeviceTest truncated and wrong duration (emulator, B1) | none needed | PASS | |
| DLD03 offline bundle restart and seek | DownloadLifecycleDeviceTest (emulator; B1 offline with the VPN off) | B8: 2 h offline soak, every song from its download, no stream lookup | PASS | |
| DLD04 clear playback cache | DownloadControlsTest (JVM) | Not tapped on the phone (destructive, owner's data) | PASS | |
| DLD05 Smart Downloads policy | DownloadEngineTest (mobile, battery under 20 %, playback, switched off) (JVM) | Android 15 daily limit has no test | PASS | Q18-026 (Low, test gap) |
| DLD06 bulk duplicates, pins | DownloadEngineTest (JVM) | none needed | PASS | |

### Catalog and lyrics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| CAT01 unknown or malformed renderer | ProviderFixtureTest unknown-renderers.json, emptyAndBareAnswersParseToNothing, ProviderContractTest | Live answers in B10 | PASS | |
| CAT02 continuation loop and cap | ProviderContractTest (repeated token, cycles, page limit), CollectionTruncationUiTest | Playlists over 100 and 2,000 songs in B10 | TODO | B10 |
| CAT03 HTTP 200 rejected edit | ProviderMutationTest, ProviderContractTest errors inside 200 | Live edit in B10 (asked first) | TODO | B10 |
| CAT04 provider native sort | ProviderCatalogTest.playlistSortsUseOnlyProviderCommands (JVM); PlaylistSortDeviceTest.nativeSortPreferencesReloadEveryTrackAndRestoreOriginalSelection is a live test in no lane | PlaylistSortDeviceTest or the same steps on the test account in B10, preference restored | TODO | B10 |
| LYR01 word, line, plain, none | ProviderFixtureTest.timedAndPlainLyricsAreToldApart, LyricsTest, LyricsPreloadTest, LyricsKaraokeTest | A1 steps 4-6: labels honest, but some songs get line timing although word timing exists | FAIL | Q18-003; Q18-004 (Low) |
| LYR02 translation failure or cancel | **none**: no test fails or cancels a translation; TranslationSmokeInstrumentation exists but is not in any lane | not tried | FAIL | Q18-034 (new, test gap) |

### Accessibility

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| A11Y01 TalkBack and keyboard | AccessibilitySemanticsTest, PlayerControlSemanticsTest, the three sweeps (JVM) | A4 TalkBack: UX01-06, 08-10 and widgets PASS; download phases never announced; four Settings sliders unnamed; "Back to Back". Keyboard: BLOCKED, no keyboard (A5) | FAIL | Q18-031, Q18-033; Q18-011, Q18-032 (Low); keyboard BLOCKED |
| A11Y02 200 % text, RTL, narrow, landscape | AccessibilitySweepTest, ...LargeWindowTest, ...LandscapeTest, PseudoLocaleSweepTest; q13-screens (90 pictures) | Not looked at on the phone: no sub-phase of the split covers it | TODO | add to the post-fix phone session (font size largest, landscape) |
| A11Y03 motion and contrast | AccessibilitySemanticsTest reduced motion, ContrastTest (arithmetic) | A1 step 6: Remove animations, nothing moves, glow kept | PASS | owner decision on darker scrims open (split, owner decisions) |

### Security, release and diagnostics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| SEC01 host lookalike, intent, TLS | WebBoundariesTest (JVM) | B11 sign-in browser; one blocked host during a sign-in that succeeded | PASS | Q18-027, Q18-028 (Low) |
| SEC02 foreign custom command | WebBoundariesTest | C3 emulator and B11 phone with test.riff.foreign: queue edits refused, transport allowed | PASS | |
| SEC03 adversarial diagnostics | DiagnosticEventTest, ExitDiagnosticsTest, ServiceStopDiagnosticsTest | Q15A ring read back; C7 sweep of repo, APK, archive | PASS | |
| REL01 candidate APK | check_release_content.py (18 checks), test_check_release_content.py | Q17: 0.10.0-rc1 signed, certificate equal to rc20, non-debuggable, no fixtures. Must be repeated on the final candidate | TODO | B2, C9 (C6 release-identity BLOCKED on an unsigned clone) |
| REL02 in-place upgrade | LegacyUpgradeDeviceTest (emulator, B1) | Q17 packaging lane: rc20 to rc1 on the emulator, data kept. Over the owner's real rc20: A7 | DEFERRED | A7, by the owner, after all fixes and tests |
| REL03 evidence mismatch | test_collect_results.py (stale, mismatched, incomplete rejected), test_verify.py | Seen working: q04-debug-04/05, q07 fixture, q16a, q17a runs refused | PASS | |
| REL04 interim release R1 | none | Q05R declined by the owner on 19 October 2026: no interim release; only the final candidate is prepared | DECLINED | none |
| EXP01 export then import | LocalDataTransferTest.aCleanInstallMatchesTheSourceForEverythingIncluded; LocalDataTransferDeviceTest (emulator, B1) | B6: export through the picker, file checked, import preview. Between two phones: BLOCKED (one phone) | PASS | two-phone transfer BLOCKED |
| EXP02 bad import files | LocalDataTransferTest (corrupt, truncated, newer, oversized, mismatched), LocalDataTransferDeviceTest | none needed | PASS | |
| OPS01 canary | ProviderCanaryTest, test_provider_canary.py; two live canary runs (Q10A) | The scheduled workflow never ran: nothing pushed, by the owner's choice | PASS | workflow run BLOCKED (no push) |
| UPD01 update feed | UpdateNoticeTest (12, scripted feed) | No public release feed exists | BLOCKED | publication decision; Q12A rerun steps |
| UPD02 update check off | UpdateNoticeTest (zero requests) | none needed | PASS | |
| DIAG01 exit diagnostics | ExitDiagnosticsTest, DiagnosticsScreenTest; Q15A on the emulator (crash in each process, force-stop, SIGKILL) | ANR and low memory by scripted records only; Diagnostics screen not looked at on the phone | PASS | |
| PERF01 Baseline Profile | release-content check (profile packaged), Q14 baseline-prof.txt | B7 fallback: 1457 against 1503 ms median Fully drawn with and without; Macrobenchmark's own comparison hangs | BLOCKED | Q18-023, then B7b |

## 2. Journeys UX01-UX10 (plan Q12 and Q13)

Tests per journey are listed in `core-journeys.md`. The phone walk-through is A4 (TalkBack) plus the runs named.

| Journey | Phone evidence | Status | Open |
|---|---|---|---|
| UX01 first launch, sign-in | A4: Home read in order. A6: Change account cannot finish; the owner asked for a new sign-in page | FAIL | Q18-009, Q18-013 (High); Q18-014 placeholders |
| UX02 search, play, queue | A4 | PASS | Q18-018 (Low) |
| UX03 filter, sort, play | A4 | PASS | |
| UX04 download, restart offline, play | A4 follow-up and B5 | FAIL | Q18-021 (resume), Q18-031 (announcements) |
| UX05 mini, full, queue, Home | A4 PASS; A1: doubled title and cover at the mini-player end of the morph | FAIL | Q18-001; Q18-002, Q18-008 (owner's change requests) |
| UX06 lyrics, timing, translation | A4: +0.2 s dialog. Translation cancel: no test | FAIL | Q18-034, Q18-003 |
| UX07 account switch | not run | DEFERRED | by the owner, after Q18-009 |
| UX08 cache, history, download deletion | A4: wording and Cancel; real deletion is A7 | PASS | |
| UX09 network, session error | A4 (aeroplane mode); B3 | PASS | Q18-020 (Low) |
| UX10 widgets, lock screen, timer | A4: sleep timer; widgets on Riff Preview. Lock-screen controls not checked | TODO | lock screen in B2's smoke run; Q18-032 (Low) |

## 3. Invariants (plan section 3)

| ID | Shown by | Status |
|---|---|---|
| I01 no expiring streams, cookies or tokens stored | LocalDataTransferTest file scan, ProviderFixtureTest.noFixtureHolds..., DiagnosticEventTest; B6 export file read | PASS |
| I02 account operations capture scope and generation | AccountSessionTest, OrderedCommands tests | PASS |
| I03 stale operations cannot publish to a new account | ACC01 | FAIL (Q18-012) |
| I04 pause wins; restart never autoplays | PLY01, PLY04, PLY07, PlaybackRecoveryTest | PASS (Q18-017 is about detecting offline, not autoplay) |
| I05 authored order and repeats survive | QUE01, QUE02 | PASS |
| I06 cache clearing keeps downloads; deletion in owned paths | DLD04; OfflineMusicStore.deleteScope canonical parent tests | PASS |
| I07 one crossfade gain owner | XFD04, AUD01 | PASS |
| I08 states truthful and actionable | UX09; B4 shows the wrong message when the solver stays down | FAIL (Q18-022) |
| I09 release has no stress or fixture entry points | REL01 release-content check | PASS on rc1; TODO on the final candidate |
| I10 ambiguous legacy data preserved | DAT04 | PASS |
| I11 complete offline bundles need no provider | DLD03, B8 | PASS |
| I12 local deletion never changes the provider | Q05 by construction (no provider dependency); Q05A import has no network client | PASS (by construction) |

## 4. Packages (G01 "every package complete")

| Package | Ledger status | Open before G01 can pass |
|---|---|---|
| Q00, Q01, Q02 | PASS | none |
| Q03 | PASS; physical two-account check open | ACC04 (deferred) |
| Q04, Q05 | PASS locally; phone upgrade and privacy actions | A7 (deferred) |
| Q05A | PASS locally; picker done in B6 | two-phone transfer BLOCKED |
| Q05R | DECLINED by the owner, 19 October 2026 | none; the final report lists it as declined |
| Q06, Q07 | PASS locally; phone checks done in B3 and B4 | Q18-017, Q18-022 |
| Q08, Q08A | PASS locally; listening done in A2 and A3 | live loudness value (B10) |
| Q09 | PASS locally; phone kill done in B5 | Q18-021, Q18-026 |
| Q10, Q10A | PASS locally; live checks open | B10 |
| Q11 | PASS locally; pinned formatter BLOCKED offline | formatter stays BLOCKED (no network for a pinned tool) |
| Q12, Q13 | PASS locally; walk-throughs done in A4 | the FAIL rows above; keyboard BLOCKED |
| Q12A | PASS locally; live feed BLOCKED | publication decision |
| Q14 | PASS locally for tooling; budgets measured only by fallback | B7b after Q18-023; B9 battery; B8 live soak |
| Q15, Q15A | PASS locally; C7 and B11 done | none beyond the bugs |
| Q16 | PASS locally; C2, C6 done | Linux checksums BLOCKED (owner: no push); hosted CI not run |
| Q16A, Q16B, Q17A | PASS locally; checked on the phone in A1 and B1 | Q18-001, Q18-003, Q18-004 |
| Q17 | PASS; rc1 prepared, not published | C8 docs check on the final code |
| Q18 | IN_PROGRESS | the rest of the split |

## 5. Labels in the ledger outside the status words

Each is read as follows; none is counted as PASS.

| Where | Label | Read as |
|---|---|---|
| Q03 slice 1 table | PARTIAL, NOT VERIFIED | superseded by the Q03 completion table (ACC07 PASS there) |
| Q08A provider field | PARTLY | PASS for the fixture; the live value is TODO in B10 (AUD01) |
| Q12 tokens centralized | PARTIAL | open limitation: 659 raw measures held by the static-check ratchet; for C8's known limitations |
| Q13 keyboard open | NOT DONE | PASS on the phone for the sleep timer (A4 UX10); Search not checked; part of A11Y01 (FAIL for its other reasons) |
| Q13 progress never announced continuously | NOT VERIFIED | PASS in A4 follow-up ("not continuously"); "announced once" FAIL, Q18-031 |
| Q14 retained memory growth | NOT DECIDED | B8 offline soak: no growth over 2 h (PASS); MemoryCheck's 3-run loop is BLOCKED with Macrobenchmark (Q18-023) |
| Q14 optimization rules | Partly | two rules not audited; for C8's known limitations |
| Q15 no unresolved high-severity issue | None known | at Q15; now Q18-009, Q18-010, Q18-021 and Q18-013 are High and open, so G07 waits for the fix package |
| Q15 dependency verification | PARTIAL | strict locally (C6); hosted strict run BLOCKED (no push) |
| Q15 security-review.md | Done | PASS |
| Q18A1, A2, A4 | done | each step's own PASS, FAIL or BLOCKED stands; "done" means the sub-phase ran |
| Q18A3, A6, A8, B-entries | no Status line | each step states its own result; summarised above |

## 6. Totals

Matrix and journeys, 72 rows: PASS 42, FAIL 12, BLOCKED 3, DEFERRED 6, TODO 8, DECLINED 1 (REL04). No row is unmapped: LYR02 is
FAIL with Q18-034 because it has no test. G01 cannot pass until the FAIL rows are fixed and rerun, the TODO rows
run.
