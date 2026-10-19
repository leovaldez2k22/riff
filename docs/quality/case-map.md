# Case map (Q18C5, gate G01)

First pass, 19 October 2026, before the fix package. Every required case of the plan is mapped to its tests,
its run on a device or its BLOCKED entry, with one status from the list below. Sources: the plan's acceptance
matrix (section 16, 62 cases), the UX01-UX10 journeys (section Q12), the invariants (section 3), and each
package's acceptance in `execution-ledger.md`. Bugs are in `q18-bugs.md`. The second pass, after the fix package,
updates every row the fixes touch and is the input to C9.

Second pass, 19 October 2026 (Q19C5), on the source at 6328a8d after Q18F and Q19F: every row the fixes touched is
updated, every TODO is resolved, and the new cases are in section 7. A row whose bug is fixed but whose device check has
not run is BLOCKED (the phone and the accounts are not attached in Q19C), never PASS. "Q19C2" means the emulator lane on
API 31, 33 and 36 with the network off (51 tests each).

Status words, and only these:

- **PASS**: the expected result is shown by the tests and runs named, on the stated build.
- **FAIL**: a check ran and the expected result did not hold. The bug named holds the fix; the case is rerun
  after the fix package.
- **BLOCKED**: the evidence cannot be produced here (hardware, account, network or publication missing). The
  rerun is named.
- **DEFERRED**: the owner chose to run it after the fix package.
- **TODO**: can be run, is scheduled in Q18 (B2, B8, B9, B10, C9) and has not run yet. Since the second pass (Q19C5) no row is TODO: a case that waits for the phone or an account is
  BLOCKED with the Q19 sub-phase that reruns it.
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
| ACC01 result after A->B | AccountSessionTest, DownloadOwnerSwitchTest (JVM); AccountSwitchDeviceTest.queueRequestBuiltForThePreviousAccountIsSkipped (emulator, B1) | A6: the owner's last search and results stayed on Search under the dummy account and were saved to its recent searches | BLOCKED | Q18-012 fixed in Q18F (ShellNavigationTest, JVM); the real A->B check is A2 (phone, two accounts) |
| ACC02 A->B->A | AccountSwitchDeviceTest.eachAccountGetsItsOwnQueueBackPaused (emulator, B1; synthetic identities) | Real A->B->A goes with UX07 | DEFERRED | A2 with UX07 (Q18-009 fixed in Q18F) |
| ACC03 cookie rotation | AccountSessionTest (same owner, no quiesce, transfers continue) | none needed | PASS | |
| ACC04 selector order | AccountScopeProviderTest (JVM, Robolectric) | Two real accounts at swapped chooser positions not tried: Change account is broken | DEFERRED | A2, two real accounts at swapped chooser positions (Q18-009 fixed in Q18F) |
| ACC05 cancelled or failed chooser | AccountScreenSessionTest (failed sign-out keeps the account) | A6: after a Change account that did not finish, Riff kept the old account while the sign-in browser's cookies had moved to the new one | BLOCKED | Q18-009, Q18-010 fixed in Q18F (WebBoundariesTest, AccountScopeProviderTest, JVM); an abandoned change on the phone is A2 |
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
| PLY04 offline 60 s then online | PlaybackRecoveryTest (virtual time); RecoveryDeviceTest (emulator, B1 with VPN off) | B3 6a and 6b PASS with the VPN off. With a VPN on, Riff believes it is online and the wait is not taken; the session reports ERROR while waiting | BLOCKED | Q18-017, Q18-020 fixed in Q18F (NetworkConditionsTest, WaitingSessionPlayerTest, JVM); RecoveryDeviceTest PASS on API 31, 33 and 36 (Q19C2); with a VPN on the phone: B3 |
| PLY05 rejected or expired stream | PlaybackRecoveryTest (AttestationExpired, 403/410 refreshed once), SabrRecoveryPolicyTest (JVM) | Retired as a device case (owner, 19 Oct 2026): ordinary playback never uses the SABR transport where the fault hook sits | PASS (JVM only) | Q18-029 closed |
| PLY06 solver death | SolverFailClosedTest, PlaybackRecoveryTest (JVM, fake solver) | B4: one crash recovers; kept down, exactly three attempts, but the message and class are wrong | BLOCKED | Q18-022 fixed in Q18F (SolverFailClosedTest, JVM); the kept-down solver on the phone: B3 |
| PLY07 main-process death | PlaybackOrderDeviceTest process-death steps (emulator: 3,082 ms lost, bound 6,500) | B3 step 4: queue back paused, resumes from 22.2 s; shows 0:00 until Play | PASS | Q18-019 (Low, display) |
| PLY08 focus, call, noisy route | Q08 focus tests (emulator, another focus request) | A3: notification duck, real call, earbud pause and next, earbuds into the case and Bluetooth off, all during a blend. Car head unit BLOCKED (none) | PASS | car: BLOCKED, optional in the split |
| PLY09 unknown outcome mutation | ProviderMutationTest (eight unreadable answers, reconcile before retry) (JVM) | Live edit on the test account is in B10 (asked first) | BLOCKED | needs the dummy account: B7, each edit asked first |
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
| AUD01 normalization on | LoudnessNormalizationTest (20, JVM); NormalizationDeviceTest (9, emulator, B1) | A2 step 5 by ear. A live loudness value from the provider not read | BLOCKED | the live loudness value needs the dummy account: B7 |
| AUD02 normalization off or missing | LoudnessNormalizationTest, NormalizationDeviceTest | none needed | PASS | |

### Downloads

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| DLD01 kill each transfer phase | DownloadEngineTest six stop points, DownloadReconcileTest each stored state (JVM) | B5: state right after a kill while Downloading and while Saving artwork, never falsely complete; but the song is not resumed. Reboot run PASS once Riff is opened | BLOCKED | Q18-021 fixed in Q18F (DownloadSchedulingTest, JVM); the kill-and-resume on the phone: B3 |
| DLD02 bad range, 403, truncate, full disk | DownloadEngineTest; DownloadLifecycleDeviceTest truncated and wrong duration (emulator, B1) | none needed | PASS | |
| DLD03 offline bundle restart and seek | DownloadLifecycleDeviceTest (emulator; B1 offline with the VPN off) | B8: 2 h offline soak, every song from its download, no stream lookup | PASS | |
| DLD04 clear playback cache | DownloadControlsTest (JVM) | Not tapped on the phone (destructive, owner's data) | PASS | |
| DLD05 Smart Downloads policy | DownloadEngineTest (mobile, battery under 20 %, playback, switched off) (JVM) | Android 15 daily limit has no test | PASS | Q18-026 (Low, test gap) |
| DLD06 bulk duplicates, pins | DownloadEngineTest (JVM) | none needed | PASS | |

### Catalog and lyrics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| CAT01 unknown or malformed renderer | ProviderFixtureTest unknown-renderers.json, emptyAndBareAnswersParseToNothing, ProviderContractTest | Live answers in B10 | PASS | |
| CAT02 continuation loop and cap | ProviderContractTest (repeated token, cycles, page limit), CollectionTruncationUiTest | Playlists over 100 and 2,000 songs in B10 | BLOCKED | playlists over 100 and 2,000 songs need the dummy account: B7 |
| CAT03 HTTP 200 rejected edit | ProviderMutationTest, ProviderContractTest errors inside 200 | Live edit in B10 (asked first) | BLOCKED | a live edit needs the dummy account: B7, asked first |
| CAT04 provider native sort | ProviderCatalogTest.playlistSortsUseOnlyProviderCommands (JVM); PlaylistSortDeviceTest.nativeSortPreferencesReloadEveryTrackAndRestoreOriginalSelection is a live test in no lane | PlaylistSortDeviceTest or the same steps on the test account in B10, preference restored | BLOCKED | PlaylistSortDeviceTest or the same steps on the dummy account: B7, preference restored |
| LYR01 word, line, plain, none | ProviderFixtureTest.timedAndPlainLyricsAreToldApart, LyricsTest, LyricsPreloadTest, LyricsKaraokeTest | A1 steps 4-6: labels honest, but some songs get line timing although word timing exists | BLOCKED | Q18-003, Q18-004 fixed in Q18F (LyricsWordTimingTest, LyricsTest, JVM); the songs from A1 on the phone: B3 and A1 |
| LYR02 translation failure or cancel | **none**: no test fails or cancels a translation; TranslationSmokeInstrumentation exists but is not in any lane | not tried | BLOCKED | Q18-034 fixed in Q18F (LyricsTranslationTest, 7, JVM); TranslationSmokeInstrumentation needs the network: B3 |

### Accessibility

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| A11Y01 TalkBack and keyboard | AccessibilitySemanticsTest, PlayerControlSemanticsTest, the three sweeps (JVM) | A4 TalkBack: UX01-06, 08-10 and widgets PASS; download phases never announced; four Settings sliders unnamed; "Back to Back". Keyboard: BLOCKED, no keyboard (A5) | BLOCKED | TalkBack DECLINED for Q19 by the owner (19 Oct 2026: no TalkBack checks); Q18-031, Q18-033, Q18-011, Q18-032 fixed in Q18F (JVM sweeps); keyboard BLOCKED, no keyboard; download announcements and slider names by ear are not rerun |
| A11Y02 200 % text, RTL, narrow, landscape | AccessibilitySweepTest, ...LargeWindowTest, ...LandscapeTest, PseudoLocaleSweepTest; q13-screens (90 pictures) | Not looked at on the phone: no sub-phase of the split covers it | BLOCKED | the sweeps pass (JVM); on the phone, font size largest and landscape: B3 (added there, 19 Oct 2026) |
| A11Y03 motion and contrast | AccessibilitySemanticsTest reduced motion, ContrastTest (arithmetic) | A1 step 6: Remove animations, nothing moves, glow kept | PASS | owner decision on darker scrims open (split, owner decisions) |

### Security, release and diagnostics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| SEC01 host lookalike, intent, TLS | WebBoundariesTest (JVM) | B11 sign-in browser; one blocked host during a sign-in that succeeded | PASS | Q18-027, Q18-028 (Low) |
| SEC02 foreign custom command | WebBoundariesTest | C3 emulator and B11 phone with test.riff.foreign: queue edits refused, transport allowed | PASS | |
| SEC03 adversarial diagnostics | DiagnosticEventTest, ExitDiagnosticsTest, ServiceStopDiagnosticsTest | Q15A ring read back; C7 sweep of repo, APK, archive | PASS | |
| REL01 candidate APK | check_release_content.py (18 checks), test_check_release_content.py | Q17: 0.10.0-rc1 signed, certificate equal to rc20, non-debuggable, no fixtures. Must be repeated on the final candidate | FAIL | Q18-043 (the share provider) on the unsigned build and on the signed 0.10.0-rc2; Q18-047: the check never compares a v2-signed APK's certificate. The signed rc2 and rc2-preview carry the pinned certificate (apksigner and the release script, Q19C4); version 23, arm64-v8a, not debuggable |
| REL02 in-place upgrade | LegacyUpgradeDeviceTest (emulator, B1) | Q17 packaging lane: rc20 to rc1 on the emulator, data kept. Over the owner's real rc20: A7 | DEFERRED | emulator half PASS (Q19C6: rc20 with its data, then the signed rc2 over it on a fresh API 36 emulator: first-install time kept, no file lost, the retired-content migration ran, Home opens); over the owner's real rc20: R3 |
| REL03 evidence mismatch | test_collect_results.py (stale, mismatched, incomplete rejected), test_verify.py | Seen working: q04-debug-04/05, q07 fixture, q16a, q17a runs refused | PASS | |
| REL04 interim release R1 | none | Q05R declined by the owner on 19 October 2026: no interim release; only the final candidate is prepared | DECLINED | none |
| EXP01 export then import | LocalDataTransferTest.aCleanInstallMatchesTheSourceForEverythingIncluded; LocalDataTransferDeviceTest (emulator, B1) | B6: export through the picker, file checked, import preview. Between two phones: BLOCKED (one phone) | PASS | two-phone transfer BLOCKED |
| EXP02 bad import files | LocalDataTransferTest (corrupt, truncated, newer, oversized, mismatched), LocalDataTransferDeviceTest | none needed | PASS | |
| OPS01 canary | ProviderCanaryTest, test_provider_canary.py; two live canary runs (Q10A) | The scheduled workflow never ran: nothing pushed, by the owner's choice | PASS | workflow run BLOCKED (no push) |
| UPD01 update feed | UpdateNoticeTest (12, scripted feed) | No public release feed exists | BLOCKED | publication decision; Q12A rerun steps |
| UPD02 update check off | UpdateNoticeTest (zero requests) | none needed | PASS | |
| DIAG01 exit diagnostics | ExitDiagnosticsTest, DiagnosticsScreenTest; Q15A on the emulator (crash in each process, force-stop, SIGKILL) | ANR and low memory by scripted records only; Diagnostics screen not looked at on the phone | PASS | |
| PERF01 Baseline Profile | release-content check (profile packaged), Q14 baseline-prof.txt | B7 fallback: 1457 against 1503 ms median Fully drawn with and without; Macrobenchmark's own comparison hangs | BLOCKED | Q18-023 partly fixed; Macrobenchmark with 1.6.0-alpha01 on the phone: B6 |

## 2. Journeys UX01-UX10 (plan Q12 and Q13)

Tests per journey are listed in `core-journeys.md`. The phone walk-through is A4 (TalkBack) plus the runs named.

| Journey | Phone evidence | Status | Open |
|---|---|---|---|
| UX01 first launch, sign-in | A4: Home read in order. A6: Change account cannot finish; the owner asked for a new sign-in page | BLOCKED | Q18-009 fixed and Q18-013 built in Q18F; the screens without an account PASS on API 33 and 36 (Q19C3, section 7) except Q18-046 (Low, looks); signing in for real: A2 |
| UX02 search, play, queue | A4 | PASS | Q18-018 (Low) |
| UX03 filter, sort, play | A4 | PASS | |
| UX04 download, restart offline, play | A4 follow-up and B5 | BLOCKED | Q18-021, Q18-031 fixed in Q18F (JVM); download, restart offline and play on the phone: B3 |
| UX05 mini, full, queue, Home | A4 PASS; A1: doubled title and cover at the mini-player end of the morph | BLOCKED | Q18-001, Q18-002, Q18-008 fixed in Q18F (PlayerFixesTest, JVM); the morph by eye: A1 |
| UX06 lyrics, timing, translation | A4: +0.2 s dialog. Translation cancel: no test | BLOCKED | Q18-034, Q18-003 fixed in Q18F (JVM); lyrics and translation on the phone: B3 and A1 |
| UX07 account switch | not run | DEFERRED | A2, by the owner (Q18-009 fixed in Q18F) |
| UX08 cache, history, download deletion | A4: wording and Cancel; real deletion is A7 | PASS | |
| UX09 network, session error | A4 (aeroplane mode); B3 | PASS | Q18-020 (Low) |
| UX10 widgets, lock screen, timer | A4: sleep timer; widgets on Riff Preview. Lock-screen controls not checked | BLOCKED | lock-screen controls by hand: A5; Q18-032 fixed in Q18F |

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
| I09 release has no stress or fixture entry points | REL01 release-content check | PASS on rc1, on the unsigned build of 6328a8d (Q19C1) and on the signed 0.10.0-rc2 and rc2-preview (Q19C6): no test, stress, fixture or benchmark entry, file or class |
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
| Q08A provider field | PARTLY | PASS for the fixture; the live value is BLOCKED until B7 (AUD01) |
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

## 7. Cases added in the second pass (Q19C5)

### Q19F work (F1 to F5)

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| F1 lyric lines not clipped; the Aurora look (Q18-037, Q18-036) | LyricsShareTest (the clip, the tint behind the line, four looks), artifacts/quality/q19/f1/aurora-card.png | The hold-and-pop and the four looks by eye: B4, A3 (the owner names the look) | BLOCKED | B4, A3 |
| F2 real counts on screen 6; Checking plays in full (Q18-038, Q18-039) | SignInCountsTest, LikedSongsCountTest, SignInDesignTest (JVM) | Real numbers need a signed-in account: A2, A3 | BLOCKED | A2, A3 |
| F3 lyric card share and save on a device | LyricShareDeviceTest: API 33 3 of 3; API 36 3 of 3; API 31 2 of 3 (Q18-045, a test fault); not in the lane's list (Q18-044) | The share sheet opening and cancelled, one picture saved then removed: B4 | FAIL | Q18-044, Q18-045 (Q19G) |
| F3 settings compartments | SettingsCompartmentsTest (JVM) | On the phone with the device lane: B1 | BLOCKED | B1 |
| F4 lint and the new surfaces | lint 0 errors (Q19C1); lint-triage.md Q19F4; security-review.md section 9 | none needed | PASS | |
| F5 plan 2 supplement (S00, S02, S03) | PlayerConfigBoundaryTest (PC01 to PC03, JVM); S00 in the ledger | S03 needs a GitHub fetch the owner has not approved; Q18-041 needs the owner's choice | BLOCKED | S03 fetch approval; Q18-041 |

### Sign-in screens without an account (Q19C3, SignInScreensDeviceTest)

Run on the API 33 and 36 emulators, network off, with five made-up accounts on example.test; pictures in
artifacts/quality/q19/c3 (no personal data).

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| SGN01 welcome: first run, and the three latest of five saved accounts with See all | aFirstRunSaysBringYourMusic, welcomeShowsTheThreeLatest... (API 33 6 of 6; API 36 3 of 3) | Pictures 1-welcome-first-run, 1-welcome-saved | PASS | |
| SGN02 all accounts: search and "Remove from this phone" | welcomeShowsTheThreeLatestAndAllAccountsSearchesAndRemoves: the search keeps one row, removing the latest drops it from the screen and the stored list, and only it | The menu is too clear to read and names are cut short | FAIL | Q18-046 (Low, Q19G) |
| SGN03 Google's page in Riff's frame | googlesPageOpens...: the frame asks only for Google's sign-in hosts; the bar reads accounts.google.com | A page that loads, the country host in the bar, outside links: B5 (the network is off here) | BLOCKED | B5 |
| SGN04 "didn't load" with the network off | googlesPageOpens...: the card appears with Try again and Start over; Start over and Back return to Welcome with the browser gone | Picture 4-didnt-load | PASS | |
| SGN05 Back through every step | backWalksEveryStepToTheStart: All accounts, its back arrow, "didn't load" | The stop sheet on Back over a page that loaded: B5 | BLOCKED | B5 |
| SGN06 Remove animations: every transition a cut | withReducedMotionEveryTransitionIsACut, and withFullMotionTheSameTransitionOverlaps as its control | none needed | PASS | |
| SGN07 Checking and Connected with a real account | SignInDesignTest, SignInCountsTest (JVM) | A2, A3 | BLOCKED | A2, A3 |

### Bugs logged since the first pass

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| Q18-040 | see q18-bugs.md | One redacted account-menu capture: B5 (asked) | BLOCKED | B5 |
| Q18-041 player config never fetched | PlayerConfigBoundaryTest | Owner's choice of (a), (b) or (c); timing in B3 | BLOCKED | owner decision |
| Q18-042 static check | test_static_checks | none | FAIL | Q19G |
| Q18-043 release-content check and the share provider | check_release_content.py | none | FAIL | Q19G |
| Q18-044 lane omits LyricShareDeviceTest | verify.py | none | FAIL | Q19G |
| Q18-045 LyricShareDeviceTest on API 31 | LyricShareDeviceTest | none | FAIL | Q19G |
| Q18-046 Accounts screen looks | SignInScreensDeviceTest pictures | none | FAIL | Q19G |
| Q18-047 release-content check skips the certificate of a v2-signed APK | check_release_content.py | none | FAIL | Q19G |

## 6. Totals

First pass (Q18C5): 72 rows, PASS 42, FAIL 12, BLOCKED 3, DEFERRED 6, TODO 8, DECLINED 1 (REL04).

Second pass (Q19C5), the 72 matrix and journey rows, counted from the table cells: PASS 43 (PLY05 "PASS (JVM only)"
included, as retired in Q18-029; the first pass's 42 left it out), FAIL 1 (REL01, Q18-043), BLOCKED 21, DEFERRED 6, TODO 0,
DECLINED 1 (REL04). Section 7 adds 21 rows: PASS 4, FAIL 8, BLOCKED 9. Every FAIL has its bug and Q19G;
every BLOCKED names its rerun (B1 to B7, A1 to A5, the owner's candidate in C4, or an owner decision). G01 cannot
pass until Q19G fixes the FAIL rows and Q19B and Q19A run the BLOCKED ones.
