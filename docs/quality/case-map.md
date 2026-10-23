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
| ACC01 result after A->B | AccountSessionTest, DownloadOwnerSwitchTest (JVM); AccountSwitchDeviceTest.queueRequestBuiltForThePreviousAccountIsSkipped (emulator, B1) | A6: the owner's last search and results stayed on Search under the dummy account and were saved to its recent searches; Q19A A2 (21 Oct, rc6 Riff Preview): changed to the owner's account and back; the dummy account's search hidden on the owner's account and back on the dummy (owner) | PASS |  |
| ACC02 A->B->A | AccountSwitchDeviceTest.eachAccountGetsItsOwnQueueBackPaused (emulator, B1; synthetic identities) | Real A->B->A goes with UX07 | DEFERRED | A2 with UX07 (Q18-009 fixed in Q18F) |
| ACC03 cookie rotation | AccountSessionTest (same owner, no quiesce, transfers continue) | none needed | PASS | |
| ACC04 selector order | AccountScopeProviderTest (JVM, Robolectric) | Two real accounts at swapped chooser positions not tried: Change account is broken | DEFERRED | A2, two real accounts at swapped chooser positions (Q18-009 fixed in Q18F) |
| ACC05 cancelled or failed chooser | AccountScreenSessionTest (failed sign-out keeps the account) | A6: after a Change account that did not finish, Riff kept the old account while the sign-in browser's cookies had moved to the new one; Q19A A2 (21 Oct, rc6): an abandoned account change kept the old account; fine by the owner | PASS |  |
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
| PLY04 offline 60 s then online | PlaybackRecoveryTest (virtual time); RecoveryDeviceTest (emulator, B1 with VPN off) | B3 6a and 6b PASS with the VPN off. Q19B (22 Oct, Riff Debug 1880301): offline 40 s, the session BUFFERING throughout and PLAYING again on reconnect, never ERROR (Q18-020, artifacts/quality/q19/day22/b3b/q18-020-states-2.txt); while waiting the button reads Play (Q18-076, Low) | PASS | the VPN case on the phone DECLINED by the owner (22 Oct, Q18-017); Q18-076 |
| PLY05 rejected or expired stream | PlaybackRecoveryTest (AttestationExpired, 403/410 refreshed once), SabrRecoveryPolicyTest (JVM) | Retired as a device case (owner, 19 Oct 2026): ordinary playback never uses the SABR transport where the fault hook sits | PASS (JVM only) | Q18-029 closed |
| PLY06 solver death | SolverFailClosedTest, PlaybackRecoveryTest (JVM, fake solver) | B4: one crash recovers. Q19B (22 Oct, Riff Debug 1880301): solver kept down, "Online playback resolver unavailable" with Retry, class solver, exactly three attempts (Q18-022, artifacts/quality/q19/day22/b3a) | PASS | |
| PLY07 main-process death | PlaybackOrderDeviceTest process-death steps (emulator: 3,082 ms lost, bound 6,500) | B3 step 4: queue back paused, resumes from 22.2 s; shows 0:00 until Play | PASS | Q18-019 (Low, display) |
| PLY08 focus, call, noisy route | Q08 focus tests (emulator, another focus request) | A3: notification duck, real call, earbud pause and next, earbuds into the case and Bluetooth off, all during a blend. Car head unit BLOCKED (none) | PASS | car: BLOCKED, optional in the split |
| PLY09 unknown outcome mutation | ProviderMutationTest (eight unreadable answers, reconcile before retry) (JVM) | Q19B owner session (22 Oct, dummy account): like and Not interested reach YouTube; the unlike reports success but the song stays in Liked songs (Q18-080); no playlist edit possible (Q18-081) | BLOCKED | Q18-080 and Q18-081 fixed in Q19G (44bd38a); the unlike and a playlist edit on the dummy account again on rc7 |
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
| AUD01 normalization on | LoudnessNormalizationTest (20, JVM); NormalizationDeviceTest (9, emulator, B1) | A2 step 5 by ear. Q19B B7 (22 Oct, Riff Debug signed out): Normalize volume on, three streamed songs, no gain logged (artifacts/quality/q19/day22/b7/loudness.txt) | BLOCKED | Q18-077 fixed as far as code can (90822b3: each track logs its loudness); by ear and loudness_track on rc7 |
| AUD02 normalization off or missing | LoudnessNormalizationTest, NormalizationDeviceTest | none needed | PASS | |

### Downloads

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| DLD01 kill each transfer phase | DownloadEngineTest six stop points, DownloadReconcileTest each stored state (JVM) | Q19B B3b (22 Oct, Riff Debug 1880301): killed while Downloading, killed while Saving artwork and lyrics, and through a reboot; each time requeued and finished soon after Riff was opened (Q18-021, artifacts/quality/q19/day22/b3b) | PASS | |
| DLD02 bad range, 403, truncate, full disk | DownloadEngineTest; DownloadLifecycleDeviceTest truncated and wrong duration (emulator, B1) | none needed | PASS | |
| DLD03 offline bundle restart and seek | DownloadLifecycleDeviceTest (emulator; B1 offline with the VPN off) | B8: 2 h offline soak, every song from its download, no stream lookup | PASS | |
| DLD04 clear playback cache | DownloadControlsTest (JVM) | Not tapped on the phone (destructive, owner's data) | PASS | |
| DLD05 Smart Downloads policy | DownloadEngineTest (mobile, battery under 20 %, playback, switched off) (JVM) | Android 15 daily limit has no test | PASS | Q18-026 (Low, test gap) |
| DLD06 bulk duplicates, pins | DownloadEngineTest (JVM) | none needed | PASS | |

### Catalog and lyrics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| CAT01 unknown or malformed renderer | ProviderFixtureTest unknown-renderers.json, emptyAndBareAnswersParseToNothing, ProviderContractTest | Live answers in B10 | PASS | |
| CAT02 continuation loop and cap | ProviderContractTest (repeated token, cycles, page limit), CollectionTruncationUiTest | Playlists over 100 and 2,000 songs in B10; Q19B 23 Oct, Riff Preview rc6, dummy account: The Hits: '90s queued all 120 songs (YouTube: 120); THE LONGEST MIXTAPE (YouTube: 940 tracks, about 910 playable) queued 815 with no notice (Q18-095); no public playlist over 2,000 found, so the cap is checked by the JVM tests only | BLOCKED | Q18-095 fixed in Q19G (bb311b3); THE LONGEST MIXTAPE again on rc7; the 2,000 cap on a live list when one is found |
| CAT03 HTTP 200 rejected edit | ProviderMutationTest, ProviderContractTest errors inside 200 | Q19B owner session (22 Oct): the dummy account has no playlist and Riff cannot create one (Q18-081) | BLOCKED | Q18-081, then a live playlist edit on the dummy account |
| CAT04 provider native sort | ProviderCatalogTest.playlistSortsUseOnlyProviderCommands (JVM); PlaylistSortDeviceTest.nativeSortPreferencesReloadEveryTrackAndRestoreOriginalSelection is a live test in no lane | PlaylistSortDeviceTest or the same steps on the test account in B10, preference restored; Q19B 23 Oct, Riff Preview rc6: the dummy account owns no playlist (Q18-081); Liked Music shows no sort; a public playlist (The Hits: '90s) offers only Riff's own sorts (Newest and Oldest disabled, no YouTube sort) | BLOCKED | Q18-081, then the sorts on a playlist the dummy account owns, preference restored |
| LYR01 word, line, plain, none | ProviderFixtureTest.timedAndPlainLyricsAreToldApart, LyricsTest, LyricsPreloadTest, LyricsKaraokeTest | Q19B B3a (22 Oct, Riff Preview rc6): "Delicate" still read Line synced, Better Lyrics · Kugou; the owner, 23 Oct: Delicate now has word-synced lyrics (Q18-075 closed) | PASS | none |
| LYR02 translation failure or cancel | LyricsTranslationTest (7, JVM: failure, cancel, model refused offline) | Q19B B3c (22 Oct): on Riff Preview rc6 every translation fails with a raw exception (Q18-072); on Riff Debug the original lines stay while the model download waits offline, and the translation completes once online (Q18-034); Back while translating is under a second, too short to time by hand | BLOCKED | Q18-072 fixed in Q19G (bb311b3, release keep rule now a PR gate); translation on rc7 (Preview/release build); the cancel stays JVM only |

### Accessibility

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| A11Y01 TalkBack and keyboard | AccessibilitySemanticsTest, PlayerControlSemanticsTest, the three sweeps (JVM) | A4 TalkBack: UX01-06, 08-10 and widgets PASS; download phases never announced; four Settings sliders unnamed; "Back to Back". Keyboard: BLOCKED, no keyboard (A5) | BLOCKED | TalkBack DECLINED for Q19 by the owner (19 Oct 2026: no TalkBack checks); Q18-031, Q18-033, Q18-011, Q18-032 fixed in Q18F (JVM sweeps); keyboard BLOCKED, no keyboard; download announcements and slider names by ear are not rerun |
| A11Y02 200 % text, RTL, narrow, landscape | AccessibilitySweepTest, ...LargeWindowTest, ...LandscapeTest, PseudoLocaleSweepTest; q13-screens (90 pictures) | Q19B B3c (22 Oct, Riff Preview rc6, screen trees): Home, Settings, player, queue and lyrics at 200 % text and in landscape, no text past the edge, no crash. The owner by eye (22 Oct): landscape Settings reaches About, nothing cut off; the queue at 200 % is squeezed with Close queue cut off (upright) or not shown (landscape), and the player stops scrolling (artifacts/quality/q19/day22/owner) | BLOCKED | Q18-079 fixed in Q19G (46b8484); G3 on rc7 at the largest font, upright and landscape |
| A11Y03 motion and contrast | AccessibilitySemanticsTest reduced motion, ContrastTest (arithmetic) | A1 step 6: Remove animations, nothing moves, glow kept | PASS | owner decision on darker scrims open (split, owner decisions) |

### Security, release and diagnostics

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| SEC01 host lookalike, intent, TLS | WebBoundariesTest (JVM) | B11 sign-in browser; one blocked host during a sign-in that succeeded | PASS | Q18-027, Q18-028 (Low) |
| SEC02 foreign custom command | WebBoundariesTest | C3 emulator and B11 phone with test.riff.foreign: queue edits refused, transport allowed | PASS | |
| SEC03 adversarial diagnostics | DiagnosticEventTest, ExitDiagnosticsTest, ServiceStopDiagnosticsTest | Q15A ring read back; C7 sweep of repo, APK, archive | PASS | |
| REL01 candidate APK | check_release_content.py (18 checks), test_check_release_content.py | Q17: 0.10.0-rc1 signed, certificate equal to rc20, non-debuggable, no fixtures. Must be repeated on the final candidate | PASS | on the signed 0.10.0-rc2 and rc2-preview with Q18-043 and Q18-047 fixed (6bcce26): every check including the pinned certificate; again on the signed 0.10.0-rc4 and rc4-preview (C8, 20 Oct): 20 of 20 each; repeat on the final candidate (R2) |
| REL02 in-place upgrade | LegacyUpgradeDeviceTest (emulator, B1) | Q17 packaging lane: rc20 to rc1 on the emulator, data kept. Over the owner's real rc20: A7 | DEFERRED | emulator half PASS (Q19C6: rc20 with its data, then the signed rc2 over it on a fresh API 36 emulator: first-install time kept, no file lost, the retired-content migration ran, Home opens; again with the signed rc4 (C6, 20 Oct): packaging lane PASS, all 18 of rc20's files kept, Q18-054 found); over the owner's real rc20: R3 |
| REL03 evidence mismatch | test_collect_results.py (stale, mismatched, incomplete rejected), test_verify.py | Seen working: q04-debug-04/05, q07 fixture, q16a, q17a runs refused | PASS | |
| REL04 interim release R1 | none | Q05R declined by the owner on 19 October 2026: no interim release; only the final candidate is prepared | DECLINED | none |
| EXP01 export then import | LocalDataTransferTest.aCleanInstallMatchesTheSourceForEverythingIncluded; LocalDataTransferDeviceTest (emulator, B1) | B6: export through the picker, file checked, import preview. Between two phones: BLOCKED (one phone) | PASS | two-phone transfer BLOCKED |
| EXP02 bad import files | LocalDataTransferTest (corrupt, truncated, newer, oversized, mismatched), LocalDataTransferDeviceTest | none needed | PASS | |
| OPS01 canary | ProviderCanaryTest, test_provider_canary.py; two live canary runs (Q10A) | The scheduled workflow never ran: nothing pushed, by the owner's choice | PASS | workflow run BLOCKED (no push) |
| UPD01 update feed | UpdateNoticeTest (12, scripted feed) | No public release feed exists; D1 (19 Oct): personal use only, nothing published, so no feed will exist | DECLINED | none; publishing later reopens it (Q12A rerun steps) |
| UPD02 update check off | UpdateNoticeTest (zero requests) | none needed | PASS | |
| DIAG01 exit diagnostics | ExitDiagnosticsTest, DiagnosticsScreenTest; Q15A on the emulator (crash in each process, force-stop, SIGKILL) | ANR and low memory by scripted records only; Diagnostics screen not looked at on the phone | PASS | |
| PERF01 Baseline Profile | release-content check (profile packaged), Q14 baseline-prof.txt | Q19B6 (22 Oct, rc6): the Macrobenchmark lane runs now, but its cold-start test reports the app "did not open" (Q18-074). By hand, 15 cold starts each: about 1.5 s to fully drawn with the profile, 1.6 s without (artifacts/quality/q19/day22/b6-hand) | BLOCKED | Q18-074 harness fixed in Q19G (46b8484); the benchmark lane on rc7, then the Macrobenchmark comparison |

## 2. Journeys UX01-UX10 (plan Q12 and Q13)

Tests per journey are listed in `core-journeys.md`. The phone walk-through is A4 (TalkBack) plus the runs named.

| Journey | Phone evidence | Status | Open |
|---|---|---|---|
| UX01 first launch, sign-in | A4: Home read in order. A6: Change account cannot finish; the owner asked for a new sign-in page; Q19A A2 (21 Oct, rc6 Riff Preview): sign-in and account change PASS by the owner (Q18-009, 010, 012, 013, 028) | PASS |  |
| UX02 search, play, queue | A4 | PASS | Q18-018 (Low) |
| UX03 filter, sort, play | A4 | PASS | |
| UX04 download, restart offline, play | A4 follow-up and B5; Q19B B3b (22 Oct, Riff Debug): a download killed and reopened finishes (Q18-021); phase announcements need TalkBack, excluded on the phone (Q18-031); Q19B 23 Oct, Riff Preview rc6: downloaded, force-stopped, airplane mode, played from Downloads and seeked offline, then removed: PASS (artifacts/quality/q19/ux04) | BLOCKED | Q18-031 on an emulator with TalkBack (the only part left) |
| UX05 mini, full, queue, Home | A4 PASS; A1: doubled title and cover at the mini-player end of the morph; Q19A A1 (21 Oct, rc6): player opened and closed repeatedly, no doubled title, no patch (Q18-001, 055, 002, 008) PASS by the owner | PASS |  |
| UX06 lyrics, timing, translation | A4: +0.2 s dialog. Q19B (22 Oct, Riff Preview rc6): translation fails (Q18-072); Delicate line synced (Q18-075, closed by the owner 23 Oct: now word synced) | BLOCKED | Q18-072 fixed in Q19G (bb311b3); translation on rc7 |
| UX07 account switch | not run | DEFERRED | A2, by the owner (Q18-009 fixed in Q18F) |
| UX08 cache, history, download deletion | A4: wording and Cancel; real deletion is A7 | PASS | |
| UX09 network, session error | A4 (aeroplane mode); B3 | PASS | Q18-020 (Low) |
| UX10 widgets, lock screen, timer | A4: sleep timer; widgets on Riff Preview. A5 (21 Oct): lock-screen play, pause, next. Q19B owner session (22 Oct): the widget's button reads Play while paused and Pause while playing (Q18-032) | PASS | |

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
| I08 states truthful and actionable | UX09; Q19B (22 Oct, Riff Debug): the kept-down solver shows "Online playback resolver unavailable" with Retry (Q18-022) | PASS |
| I09 release has no stress or fixture entry points | REL01 release-content check | PASS on rc1, on the unsigned build of 6328a8d (Q19C1) on the signed 0.10.0-rc2 and rc2-preview (Q19C6), and on the signed rc4 and rc4-preview (C8): no test, stress, fixture or benchmark entry, file or class |
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
| Q14 | PASS locally for tooling; budgets measured only by fallback | B8 three 2-hour runs done 23 Oct (no battery budget, recorded as measured); B9 4-hour soak accepted by the owner 23 Oct; B7b after Q18-023 |
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
| Q14 retained memory growth | NOT DECIDED | B8 offline soak: no growth over 2 h (PASS); B9 4 h live (23 Oct): medians of hour 1 and the last hour both about 334 MB, the single-reading rule FAIL noted, accepted by the owner; MemoryCheck's 3-run loop is BLOCKED with Macrobenchmark (Q18-023) |
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
| F1 lyric lines not clipped; the Aurora look (Q18-037, Q18-036) | LyricsShareTest (the clip, the tint behind the line, four looks), artifacts/quality/q19/f1/aurora-card.png | B4 on the phone (signed rc2 Preview, signed out): hold picks a line with the tint, a second line added, the four looks readable (artifacts/quality/q19/b4/06-looks-strip.png). The owner names the look: A3; Q19A A3 (21 Oct, rc6): all four looks right; the owner names the fourth look Aurora, as built | PASS |  |
| F2 real counts on screen 6; Checking plays in full (Q18-038, Q18-039) | SignInCountsTest, LikedSongsCountTest, SignInDesignTest (JVM) | Real numbers need a signed-in account: A2, A3; Q19A A2 (21 Oct, rc6): the owner signed in for real; sign-in animation fine; Q18-038 and Q18-039 accepted by the owner (21 Oct). The counts were not compared number by number with YouTube | PASS |  |
| F3 lyric card share and save on a device | LyricShareDeviceTest: API 33 3 of 3; API 36 3 of 3; API 31 2 of 3 (Q18-045, a test fault); not in the lane's list (Q18-044) | B4 on the phone: Save wrote Pictures/Riff, pulled then deleted; the share sheet opened and was cancelled, but its preview was an older card (Q18-048, fixed in code in ec0f53c). LyricShareDeviceTest 3 of 3 on the phone (B1 lane) and on API 33; Q18-048 PASS (Q19A A3 by the owner; Q19B 23 Oct, a new address for each card) | PASS |  |
| F3 settings compartments | SettingsCompartmentsTest (JVM) | B1 device lane PASS on the phone; B4: all six sections open with their content (artifacts/quality/q19/b4/settings-strip.png) | PASS |  |
| F4 lint and the new surfaces | lint 0 errors (Q19C1); lint-triage.md Q19F4; security-review.md section 9 | none needed | PASS | |
| F5 plan 2 supplement (S00, S02, S03) | PlayerConfigBoundaryTest (PC01 to PC03, JVM); S00 in the ledger | S03 fetched and recorded with the owner's approval (8de0f4e); Q18-041 left as it is by the owner's decision | PASS |  |

### Sign-in screens without an account (Q19C3, SignInScreensDeviceTest)

Run on the API 33 and 36 emulators, network off, with five made-up accounts on example.test; pictures in
artifacts/quality/q19/c3 (no personal data).

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| SGN01 welcome: first run, and the three latest of five saved accounts with See all | aFirstRunSaysBringYourMusic, welcomeShowsTheThreeLatest... (API 33 6 of 6; API 36 3 of 3) | Pictures 1-welcome-first-run, 1-welcome-saved | PASS | |
| SGN02 all accounts: search and "Remove from this phone" | welcomeShowsTheThreeLatestAndAllAccountsSearchesAndRemoves: the search keeps one row, removing the latest drops it from the screen and the stored list, and only it | Q18-046 fixed: names whole, the menu readable (artifacts/quality/q19/g1/api33-pics/strip-1b.png); the test now checks no name is ellipsized | PASS |  |
| SGN03 Google's page in Riff's frame | googlesPageOpens...: the frame asks only for Google's sign-in hosts; the bar reads accounts.google.com | B5 on the phone: Google's page loads in the frame, the bar reads accounts.google.com, Step 1 of 2 and the password line in the footer. The country host comes only after the email step, and outside links were not tried without an account: A2; Q19A A2 (21 Oct, rc6): the owner signed in through Google's page in Riff's frame, fine | PASS |  |
| SGN04 "didn't load" with the network off | googlesPageOpens...: the card appears with Try again and Start over; Start over and Back return to Welcome with the browser gone | B5 on the phone in airplane mode: the card with Try again and Start over | PASS |  |
| SGN05 Back through every step | backWalksEveryStepToTheStart: All accounts, its back arrow, "didn't load" | B5 on the phone over a page that loaded: the stop sheet's Keep signing in returns to the page and Stop to Welcome. Back over a loaded page: A2; Q19B 23 Oct, Riff Preview rc6, dummy account: Change account, Use another Google account, Back on Google's first page brings Stop signing in?, Keep signing in returns to the page, Stop to Welcome back, Back to the account screen (still connected), Back to Settings. All accounts not reachable: with three saved accounts Welcome lists them all | PASS |  |
| SGN06 Remove animations: every transition a cut | withReducedMotionEveryTransitionIsACut, and withFullMotionTheSameTransitionOverlaps as its control | none needed | PASS | |
| SGN07 Checking and Connected with a real account | SignInDesignTest, SignInCountsTest (JVM) | A2, A3; Q19A A2 (21 Oct, rc6): Checking and Connected seen with a real account, fine by the owner | PASS |  |


### The owner's requests of 20 October (F6 to F10, and the swipe)

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| F6 the player's clock in hours | NowPlaying clock tests (JVM); OwnerFeaturesDeviceTest: 1:02:05 and -57:55 on a 2-hour episode, m:ss under an hour (API 31, 33, 36) | A long episode on the phone: player, mini player, notification (B11); the look (A3); Q19A A3 (21 Oct, rc6): an episode over an hour seeked past 1:00:00, hours in the player, mini player and notification (owner) | PASS |  |
| F7 pins on speed dial | SpeedDialPinsTest, LocalDataTransferTest round trip (JVM); OwnerFeaturesDeviceTest: kept by a new store, drawn first with one badge each, the 28th refused (API 31, 33, 36) | Q19B B11 (22 Oct, Riff Preview rc6): a pin drawn first with its badge on Home in airplane mode, then removed (artifacts/quality/q19/day22/b11/f7.txt); the look in A3 | PASS | |
| F8 frosted glass by default at 20 % | AccessibilitySettings default and kept choice: OwnerFeaturesDeviceTest (API 31, 33, 36); bar pictures on API 36 (artifacts/quality/q19/glass) | Q19B B11 (22 Oct): a cleared Riff Debug reads frosted glass on at 20 % (artifacts/quality/q19/day22/b11/f8.txt); B6: Home scroll 1.67 % janky frames at 20 %, 1.33 % off; the look in A3 | PASS | |
| F9 backup on this phone | LocalBackupTest (9, JVM); OwnerFeaturesDeviceTest: MediaStore write, rewrite, read, Off removes (API 31, 33, 36) | Q19B B11 (22 Oct, Riff Debug 1880301, no account): Back up now, uninstall and reinstall, the restore offer, the folder picker, the setting back (shown only after a restart), Off removed this install's files (artifacts/quality/q19/day22/b11/f9.txt); Q18-078 | BLOCKED | Q18-078; the account's waiting file after its sign-in (A2) |
| F10 Not interested reaches YouTube Music | ProviderMutationTest (3), NotInterestedUndoTest (4), JVM against recorded endpoints | Q19B owner session (22 Oct, Riff Preview rc6, dummy account): Not interested on a Home card, "Disliked on YouTube Music too", gone from Home, listed in Settings; Undo empties the list and the song is back on Home (artifacts/quality/q19/day22/owner/session.txt) | PASS | |
| F3b a swipe back always goes to the song before | PlaybackToggleTest.theButtonRestartsAfterTenSecondsButASwipeAlwaysGoesToTheSongBefore (JVM, a recording player) | Swipe at 3 s and past 10 s, and the button past 10 s, on the phone (B4); Q19B 23 Oct, Riff Preview rc6: a swipe right on the cover at 3 s (No Scrubs to Killing Me Softly) and past 10 s (Killing Me Softly to Everybody) went to the song before; Previous past 10 s restarted the song (position 0) | PASS |  |

### Bugs logged since the first pass

| Case | Tests | Device and manual | Status | Open |
|---|---|---|---|---|
| Q18-040 | see q18-bugs.md | accepted by the owner (21 Oct); the B5 capture is a screen capture, which the phone rule forbids, so covered by A2 as the Q19B plan set out; the missing email carries on in Q18-085 and Q18-088 | PASS | none (Q18-085, Q18-088) |
| Q18-041 player config never fetched | PlayerConfigBoundaryTest | Owner's choice of (a), (b) or (c); timing in B3 | DECLINED | owner's decision 19 Oct 2026: left as it is |
| Q18-042 static check | test_static_checks | none | PASS | fixed in Q19G (6bcce26) |
| Q18-043 release-content check and the share provider | check_release_content.py | none | PASS | fixed in Q19G (6bcce26) |
| Q18-044 lane omits LyricShareDeviceTest | verify.py | none | PASS | fixed in Q19G (6bcce26) |
| Q18-045 LyricShareDeviceTest on API 31 | LyricShareDeviceTest | none | PASS | fixed in Q19G (6bcce26) |
| Q18-046 Accounts screen looks | SignInScreensDeviceTest pictures | none | PASS | fixed in Q19G (6bcce26) |
| Q18-047 release-content check skips the certificate of a v2-signed APK | check_release_content.py | none | PASS | fixed in Q19G (6bcce26) |
| Q18-048 share sheet shows an older card | LyricShareDeviceTest (the next card's address differs, one file kept) | Q19A A3 (21 Oct, rc6): the preview matched a second, different card (owner); Q19B 23 Oct, Riff Preview rc6: two different cards shared one after the other went out under two addresses (riff-lyrics-<time>.png, read from the share intent), nothing sent | PASS |  |
| Q18-049 glass bars too clear over text | none yet | Home and Settings pictures (artifacts/quality/q19/b5) | DECLINED | not a bug (owner); remedy: frosted by default at 20 %, frost to the edges and bending at the rim; the look by eye in A3 |
| Q18-050 benchmark lane stops on dependency verification | verify.py --lane benchmark | Q19B6 (22 Oct): the lane built, installed and ran on the phone | PASS | fixed in 10b96ac; its test failures are Q18-073 and Q18-074 |
| Q18-051 start-up crash reading the provider's state | HomePrefetchTest.theProviderIsBuiltOnTheCallersThreadBeforeAnyScreenReadsIt (JVM) | Q19B B3a (22 Oct, Riff Preview rc6): ten cold starts, five offline, no crash (artifacts/quality/q19/day22/b3a/coldstarts.txt) | PASS | |
| Q18-052 a backup removed a waiting import's working copy | LocalBackupTest.aBackupWrittenWhileAnImportWaitsLeavesTheImportsCopyAlone; LocalDataTransferDeviceTest | Found by the C7 lane on API 33 (2 cases); after the fix the lanes pass on API 31, 33 and 36 | PASS | fixed in bb37685; on the phone with B1 |
| Q18-053 the restore after a reinstall brought nothing back | LocalBackupTest.anEmptyNewInstallNeverOutrunsTheRealBackup | The reinstall by hand on API 33 (artifacts/quality/q19/c7/reinstall2): restored with its notice, the pin on Home offline; Not now kept the offer away and later backups wrote over one file | PASS | fixed in 47bcb8b; on the phone: B11 |
| Q18-054 a restore offered after an update | LocalBackupTest.anUpdateIsNeverOfferedARestore | Found in C6 on the signed rc4 over rc20 (artifacts/quality/q19/c8/c6) | BLOCKED | fixed in code (9117a18) after rc4 was signed; C6 on the next candidate |
| Q18-067 first song slow after more than 10 minutes away | StreamStageEventTest (secondPlayerToPreload, cipher_step source) (JVM) | Q19A with the owner, A4 second try (21 Oct): about 6.7 s by the owner's count | BLOCKED | A4 on rc7 (phone) |
| Q18-068 a slow slide-up shows two titles | MorphBorderTest (JVM); PlayerFixesDeviceTest slide-up, solid controls off and on (device) | Emulator API 33, 1080 x 2400 at 420 dpi (23 Oct): 0 pixels differ off and on; before the fix 6,523 with solid controls | BLOCKED | device lane on rc7 (phone) |
| Q18-069 backup test wants an unnumbered file | OwnerFeaturesDeviceTest (device, base-name check) | B1 on the phone (21 Oct): the backup wrote a numbered file | BLOCKED | Q19G emulator lane |
| Q18-070 seeks in downloaded songs over 500 ms | SeekTimingDeviceTest (device; ready and moving apart) | Emulator API 33 (23 Oct): both tests pass | BLOCKED | device lane on rc7 (phone) |
| Q18-071 a network-off download test hung | DownloadLifecycleDeviceTest (device, Timeout rule); test_verify InstrumentCommandTest | Emulator API 33, network off (23 Oct): 6 of 6 pass with the deadlines | PASS | fixed in Q19G; no VPN test on the phone (owner) |
| Q18-072 translation fails on the release build | LyricsTranslationTest (8, JVM), check_registrars gate on the release build | Q19B B3c on Riff Preview rc6; Riff Debug translates | BLOCKED | G3 on rc7 (translate on Riff Preview) |
| Q18-073 memory growth over the journeys | MemoryCheck (Macrobenchmark; median of three readings, every row) | Emulator API 33 (23 Oct): three blocks +5.7/+2.1/+13.0 MB, native levelled; with the median reading +12 kB, PASS | BLOCKED | benchmark lane on rc7 (phone), riff.memoryBlocks 3 |
| Q18-074 two benchmark waits fail | benchmark harness (builds); benchmark lane run | Q19B6: the queue button and the app open not seen; by hand the app opens every time | BLOCKED | benchmark lane on rc7 (phone) |
| Q18-075 Delicate still line synced | LyricsWordTimingTest (JVM) | Q19B B3a on Riff Preview rc6; the owner, 23 Oct: now word synced | PASS | none (closed by the owner) |
| Q18-076 the button reads Play while waiting for a connection | PlaybackToggleTest.aSongWaitingForAConnectionShowsPause (JVM) | Q19B B3b on Riff Debug | BLOCKED | G3 on rc7 (airplane mode) |
| Q18-077 no loudness gain for signed-out streams | LoudnessNormalizationTest (JVM) | Q19B B7 on Riff Debug | BLOCKED | B13 on rc7 (signed out) |
| Q18-078 backup copies, refresh and leftovers after a reinstall | LocalBackupTest (JVM: numbered copy, Off leftovers, settings after restore) | Q19B B11 on Riff Debug | BLOCKED | G3 on rc7 (Riff Debug, F9 steps) |
| Q18-079 the queue at 200 % text | PlayerHeroHeightTest (JVM) | Q19B owner session, upright and landscape | BLOCKED | G3 on rc7 (largest font, A11Y02) |
| Q18-080 an unlike stays in Liked songs | LikedSongsCountTest (JVM: VLLM every page, read again after an unlike) | Q19B owner session, dummy account | BLOCKED | G3 on rc7 (dummy account) |
| Q18-081 no way to create a playlist | PlaylistCreateTest (6, JVM) | Q19B owner session | BLOCKED | G3 on rc7 (dummy account; then CAT03/PLY09) |
| Q18-082 lyrics searched in parallel | LyricsParallelSearchTest (12, JVM timed fakes); LyricsTest, LyricsPreloadTest, LyricsWordTimingTest, AdditionalLyricsProvidersTest | B13 on the phone after the fix | BLOCKED | B13 on rc7 (Delicate) |
| Q18-083 Save on this phone is not a download | SavedOnThisPhoneTest (JVM) | Q19B test download on Riff Preview | BLOCKED | G3 on rc7 (add and remove) |
| Q18-084 a held word at a row's end drawn over the row's start | LyricsTest.aHeldWordIsItsLettersWithoutTheSpaceAfterIt (JVM) | owner's recording, 23 Oct | BLOCKED | G3 on rc7 (Heavy, "lost") |
| Q18-085 one account listed twice after sign-out | SignInDesignTest.oneAccountSavedBeforeAndAfterItsAddressWasKnownIsOneRow (JVM) | owner's screenshot, 23 Oct | BLOCKED | G3 on rc7 (Welcome back, two accounts) |
| Q18-086 no warning before Sign out | AccountScreenSessionTest.signOutQuiescesTheAccountThenBindsGuest, cancellingTheSignOutQuestionKeepsTheAccount (JVM) | owner's request, 23 Oct | BLOCKED | G3 on rc7 (dummy account) |
| Q18-087 switching accounts always goes through Google's page | SignInDesignTest.aDirectSwitchNeedsAKnownAccountAndLandsOnlyOnIt, googlesAccountListIsReadDefensively (JVM) | owner's request, 23 Oct | BLOCKED | G3 on rc7 (switch both ways) |
| Q18-088 full email on account rows | SignInDesignTest.googlesSessionFillsInAddressesAndPositions (JVM) | owner's request, 23 Oct | BLOCKED | G3 on rc7 (full addresses) |
| Q18-089 bitrate depends on which YouTube client answered | StreamQualityTest (JVM) | audio quality investigation, 23 Oct; downloads recorded at 273, 144 and 132 kbps as Highest available | BLOCKED | B13 on rc7 (ten downloads) |
| Q18-090 Premium never passed to the extraction library | StreamQualityTest (JVM) | audio quality investigation, 23 Oct | BLOCKED | B13 on rc7 (owner's account) |
| Q18-091 format picker ignores DRC copies | OriginalAudioPolicyTest (JVM: isDrc, xtags, encoded xtags) | audio quality investigation, 23 Oct; no DRC format seen yet | BLOCKED | B13 on rc7 (capture) |
| Q18-092 16-bit boost limiter changes loud music | VolumeBoostTest, LoudnessNormalizationTest (JVM) | audio quality investigation, 23 Oct | BLOCKED | B13 on rc7 (boost on a loud song) |
| Q18-093 song count and total length on a playlist | ProviderCatalogTest.aCollectionSaysHowManySongsAndHowLongInOneLine (JVM) | owner's request, 23 Oct | BLOCKED | G3 on rc7 (a playlist and an album) |
| Q18-094 an official playlist's row says "Playlist · Playlist" | ProviderCatalogTest.aSearchRowNamesItsKindOnce (JVM) | Q19B CAT02 check on Riff Preview rc6, 23 Oct (the HTML codes first listed were the dump's escaping, not Riff) | BLOCKED | G3 on rc7 (search an official playlist) |
| Q18-095 a 940-track playlist queues 815 songs with no notice | ProviderCatalogTest.collectionSummary (JVM); loader checked against the live pages (815 of 940 listed) | Q19B CAT02 check on Riff Preview rc6, 23 Oct | BLOCKED | G3 on rc7 (open the playlist) |

## 6. Totals

First pass (Q18C5): 72 rows, PASS 42, FAIL 12, BLOCKED 3, DEFERRED 6, TODO 8, DECLINED 1 (REL04).

Second pass (Q19C5), the 72 matrix and journey rows, counted from the table cells: PASS 43 (PLY05 "PASS (JVM only)"
included, as retired in Q18-029; the first pass's 42 left it out), FAIL 1 (REL01, Q18-043), BLOCKED 21, DEFERRED 6, TODO 0,
DECLINED 1 (REL04). Section 7 adds 21 rows: PASS 4, FAIL 8, BLOCKED 9. Every FAIL has its bug and Q19G;
every BLOCKED names its rerun (B1 to B7, A1 to A5, the owner's candidate in C4, or an owner decision). G01 cannot
pass until Q19G fixes the FAIL rows and Q19B and Q19A run the BLOCKED ones.

Night of 20 October (Q19B1, B2, B4 and B5 without an account, B6, Q19G G1): the 72 rows PASS 44 (REL01 on the signed
rc2), FAIL 0, BLOCKED 21, DEFERRED 6, DECLINED 1. Section 7, 24 rows: PASS 13, FAIL 2 (Q18-049 and Q18-050, both
waiting for the owner's choice), BLOCKED 8, DECLINED 1 (Q18-041).
Same morning, after the owner's answers: section 7 is PASS 13, FAIL 0, BLOCKED 9, DECLINED 2 (Q18-041, Q18-049).

C5 on 20 October, after F6 to F10, the swipe change, C7 and the signed rc4: the 72 rows are unchanged at PASS 44 (REL01
now on the signed rc4 and rc4-preview), FAIL 0, BLOCKED 21, DEFERRED 6, DECLINED 1. Section 7, 34 rows: PASS 15 (adds
Q18-052, Q18-053), FAIL 0, BLOCKED 17 (adds F6 to F10, F3b, Q18-054; Q18-048 now waits for rc4 in B4), DECLINED 2.
Q18-054 is fixed in code after rc4 was signed, so it passes only with C6 on the next candidate.

Q19B by day, 22 October (Riff Preview rc6 and Riff Debug 1880301; evidence in artifacts/quality/q19/day22): the 72
rows PASS 47 (adds PLY04, PLY06, DLD01), FAIL 5 (AUD01 Q18-077; LYR01 Q18-075; LYR02 and UX06 Q18-072; PERF01
Q18-074), BLOCKED 13, DEFERRED 6, DECLINED 1. Invariants: I08 now PASS. Section 7, 41 rows: PASS 19 (adds F7, F8,
Q18-050, Q18-051), FAIL 7 (Q18-072 to Q18-078, new), BLOCKED 13, DECLINED 2. The BLOCKED rows wait for the owner (the
A11Y02 look, F4, Q18-032, Q18-058, B7 writes), B8 and B9, rc7, or TalkBack on an emulator (Q18-031); every FAIL goes
to the fix round after Q19B.

Q19B owner session, 22 October afternoon (Riff Preview rc6, dummy account; evidence in
artifacts/quality/q19/day22/owner): the 72 rows PASS 48 (adds UX10), FAIL 7 (adds A11Y02 Q18-079 and PLY09
Q18-080), BLOCKED 10, DEFERRED 6, DECLINED 1. Section 7, 44 rows: PASS 20 (adds F10), FAIL 10 (adds Q18-079 to
Q18-081), BLOCKED 12, DECLINED 2. Also passed by the owner: F4 slider haptics, Q18-032 widget names, Q18-058 a
second account signing in. CAT03 waits for Q18-081 (no way to make a playlist).

Q19B close, 23 October (B8 three runs, B9 accepted, UX04, the CAT02 and Q18-048 checks on Riff Preview rc6, and the
rows Q19A had already passed): the 72 rows PASS 53 (adds UX01, UX05, ACC01, ACC05, LYR01), FAIL 7 (PLY09 Q18-080,
AUD01 Q18-077, CAT02 Q18-095, LYR02 and UX06 Q18-072, A11Y02 Q18-079, PERF01 Q18-074), BLOCKED 5 (CAT03 waits for
Q18-081, CAT04 a native sort on the dummy account, A11Y01 TalkBack declined and no keyboard, UPD01 publication, UX04
TalkBack announcements on an emulator), DEFERRED 6, DECLINED 1. Section 7, 63 rows: PASS 29 (adds F1, F2, F3, F6,
SGN03, SGN07, Q18-040, Q18-048, Q18-075), FAIL 21, BLOCKED 5 (SGN05 a walk back on the phone, F9 on Riff Preview
after rc7, F3b a swipe back on the phone, Q18-054 C6 on the next candidate, Q18-071 on an emulator), TODO 6,
DECLINED 2. Every FAIL and TODO goes to Q19G.
Same afternoon, after SGN05, F3b and CAT04 on the phone: the 72 rows unchanged (CAT04 stays BLOCKED, now on
Q18-081). Section 7: PASS 31 (adds SGN05, F3b), FAIL 21, BLOCKED 3 (F9 after rc7, Q18-054 on the next candidate,
Q18-071 on an emulator), TODO 6, DECLINED 2.
Owner, 23 Oct: UPD01 recorded DECLINED under D1 (no publication). The 72 rows: PASS 53, FAIL 7, BLOCKED 4, DEFERRED 6,
DECLINED 2.

Q19G fix round, 23 October (every open Q18 bug fixed or judged, each with a test; PR lane and emulator lane
PASS_LOCAL): the 72 rows PASS 53, FAIL 0, BLOCKED 11 (adds PLY09, AUD01, CAT02, LYR02, UX06, A11Y02 and PERF01, each
waiting for its phone check on rc7), DEFERRED 6, DECLINED 2. Section 7, 63 rows: PASS 32 (adds Q18-071 on the
emulator), FAIL 0, BLOCKED 29 (the fixed bugs, on rc7), DECLINED 2. G01 waits for rc7 and the G3 phone checks.
