# Final report

Riff 0.10.0, version code 33. Written 24 October 2026 in Q19R (R5 and R6); replaces the Q17 draft. It answers plan section 19. The detailed record of every package, run and rerun is the [execution ledger](execution-ledger.md), the cases are in the [case map](case-map.md) and the bugs in the [bug log](q18-bugs.md). This page summarizes them and adds the closing runs of R5.

## Verdict

**Complete. The owner declared the Riff excellence plan fully complete on 24 October 2026**, after reading the gates below and dropping the last open items (Q18-108 and P1). The gates are recorded as they ran, so this is the owner's acceptance of the result, not a claim that every gate passed. The final candidate is built, signed, installed as the owner's main Riff and checked on the phone and on the emulators. Ten of the twelve gates pass; G05 fails on the 60 Hz frame budget, which the owner chose to leave (Q18-030), and G06 is declined and blocked (no TalkBack walk-through by the owner's decision, no keyboard). Nothing has been pushed or published (D1).

## 1. Identity

| | |
|---|---|
| Version | 0.10.0, version code 33 (`app/build.gradle.kts`) |
| Signed from | a9b1299, clean (the owner ran `scripts/build-release-candidate.ps1`; unit tests ran first, 1,225 of 1,225 on the second run) |
| Final commit | the last commit of this report; after a9b1299 only documents changed (`git diff a9b1299 HEAD` outside docs, CHANGELOG, README and NOTICE is empty) |
| Content digest | 2c37caa7158b, the same in the signed APK (Settings → About) and in R5's rebuild from bc69f02 |
| riff-0.10.0.apk | SHA-256 7bd89214b37e9c92168799532c028a04527aea5f65cd90f7878152c5dcdec258, 9,499,189 bytes, arm64-v8a, minSdk 31, targetSdk 36 |
| riff-0.10.0-preview.apk | SHA-256 8d958d43df746c6a02069933357aaa89701c3349e38aaf7bbc588c828623945b |
| Source archive | riff-0.10.0-source.zip, SHA-256 051535e83d1c9be0bf8332f809b66cfcad6fa33ba3db1e075fee10330304130d (Git-tracked files at a9b1299) |
| Certificate | SHA-256 ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1, the same as every release since 0.9.0-rc4 |
| Where | `..\Riff-0.10.0\artifacts\release` and `Riff\artifacts\release\0.10.0` (with SHA256SUMS.txt, mapping and dependencies); a copy of the APK in the owner's Downloads |

Against the Q17 package (0.10.0-rc1, code 22): the APK fell from 28,263,130 to 9,499,189 bytes, mostly from removing lyrics translation and ML Kit (Q18-101); 993 JVM tests then, 1,225 now; the emulator lane grew from 49 to 69 device tests.

## 2. What was done

Every package of plan 1 (Q00 to Q18) and the lettered additions ran; Q19 (the final stretch) closed them on the phone. In short, by subsystem (the user-facing list is the [changelog](../../CHANGELOG.md)):

- **Accounts:** account scope v2 with ownership tokens; serialized switching that saves and restores each account's queue; the rebuilt sign-in (Q18-013) with saved accounts and direct switching, committing only the account reached (Q18-110).
- **Data:** history, searches, queues, saved tracks, downloads and pins per owner; the Room database; Privacy and data; export and import; the backup on this phone (F9, on by default, D11); retention; Earlier data for what older builds shared.
- **Playback:** one recovery owner and budget; truthful states; Song quality; normalize volume; prewarming and OkHttp streams; the stream helper in its own process with a fail-closed solver.
- **Downloads:** restart and validation after a kill, power loss or full disk; Riff's own WebM; Android 15's daily limit; a 2 GB cap on the space kept free.
- **Catalog and lyrics:** provider contracts and fixtures, a daily canary script, playlist create, rename and delete, Not interested; lyrics sources ranked in parallel, overlapping and background lines, held words, lyric cards.
- **Interface:** design tokens, frosted glass by default, settings compartments, slider haptics, the hours clock, speed dial pins, rc20's artwork colours (the owner's choice).
- **Quality tooling:** build identity and evidence manifests, the deterministic harness, one lane entry point (`verify.py`), static checks, a release-content check, a Baseline Profile and a Macrobenchmark module, exit diagnostics.

Declined or not done, by the owner's decisions: Q05R (no interim release), lyrics translation (removed), TalkBack checks, the update feed (D1), Plan 2's S05 to S07 (D9). S01 was declined in D9 and then done anyway as Q18-024 (AGP 9.4.1, R8 metadata warnings 179 to 0).

## 3. Gates

Status words as in the case map. Evidence paths under `artifacts/` are local and not committed.

| Gate | Criterion | Status | Evidence |
|---|---|---|---|
| G01 | Every package complete; cases mapped; no unknown status | PASS | Case map section 8: the 72 matrix and journey rows PASS 67, BLOCKED 2 (A11Y01: TalkBack declined, no keyboard; ACC04: two accounts at swapped chooser positions never arranged), DECLINED 3; the 77 later rows PASS 65, FAIL 3, BLOCKED 4, DECLINED 5; no TODO or DEFERRED. Every package has a ledger status; Q05R DECLINED |
| G02 | Deterministic suite passes, no unexpected failures or skips | PASS | R5 PR lane on bc69f02 (`..\Riff-final\artifacts\quality\lanes\pr-20261024T151040Z`): 1,225 JVM tests, 0 failures, 0 errors, 0 skipped; script tests 164 OK; static checks 0 findings. Known intermittent: HistoryScreensTest.earlierSearchesAreImportedOrDeletedOnlyWhenTheUserSaysSo failed once in each of two signing runs and passed alone and on rerun (not logged, owner's decision) |
| G03 | Scope, deletion, migration, durability, interruption, full disk | PASS | ACC and DAT rows (JVM, emulator, phone); full disk by injection (DAT06); migration killed at every checkpoint (DAT03); upgrade over rc20 with data on the emulator (C6). Clearing real data on the phone not run: the owner wiped rc20's data in R3 |
| G04 | Controlled playback, recovery, audio and download checks | PASS | R5 emulator lanes on bc69f02, network off: API 33 69 of 69, API 31 69 of 69 (`..\Riff-final\artifacts\quality\lanes\emulator-20261024T153150Z`, `-155407Z`), no crossfade flake; D1 and D2 on the phone on rc10, 63 of 63; PLY, XFD, AUD and DLD rows; every playback and download bug confirmed on the phone or closed by the owner |
| G05 | API 31, 33 and 36 functional; tier R and L budgets | FAIL | Functional: R5 emulator lanes API 31 69 of 69, API 33 69 of 69, API 36 69 of 70 (`emulator-20261024T161001Z`; the one failure is a test tapping a row scrolled off the 720 x 1280 screen, same in three reruns; proposed as P1 and dropped by the owner, 24 Oct). Tier R on the I2217 (rc10, performance.md): cold start p95 1,788 ms, 2,000-entry queue 369 ms, local first audio 294 ms, online first audio 263 ms, memory over journeys, all PASS; frames at 60 Hz 8.0 / 7.0 / 17.1 % against 2 % FAIL (Q18-030, left by the owner); six action timings MISSING (Q18-097). Tier L BLOCKED: no such phone |
| G06 | TalkBack, keyboard, large text, reduced motion | DECLINED / BLOCKED | Large text and landscape PASS (A11Y02, Q18-079 on rc7); reduced motion PASS (A11Y03, SGN06); TalkBack DECLINED by the owner (19 Oct, Q18-031 closed 24 Oct); keyboard BLOCKED (none). JVM semantics sweeps pass |
| G07 | No unresolved high-severity security issue, no secret or test entry leak | PASS | security-review.md: no high-severity issue open, two medium (the jsDelivr solver script, D4; CI dependency verification unproven on a hosted run); release-content check 20 of 20 on both signed APKs and on R5's rebuild; no fixture, stress or benchmark entry in the release (I09) |
| G08 | Lint zero errors, every warning triaged, no blanket suppression | PASS | R5 lint: 0 errors, 93 warnings, 3 hints, each rule with a disposition (lint-triage.md, R5 section); no baseline or severity override |
| G09 | Soaks without crash, ANR or unexplained memory growth | PASS | 2 h offline (19 Oct) no growth; 4 h live (B9, 23 Oct) 240 min, 67 songs, one process, no crash, medians of hour 1 and the last hour both about 334 MB (the single-reading rule's FAIL accepted by the owner); MemoryCheck three blocks on rc10 PASS (210.0 → 221.8 MB over 30 journeys) |
| G10 | Unique version, signature, ABI, size, source, upgrade | PASS | Code 33 above every earlier build (rc20 was 21); certificate as above; arm64-v8a; 9.06 MiB under the 40 MiB cap; source archive with hash; installed over rc20 on the phone with `adb install -r` (R3) and over rc11 as Riff Preview (R2) |
| G11 | Current docs, inventory, disclosed limitations | PASS | R1 (d0cd9f9): README, features, privacy, release notes, changelog (corrected in bc69f02), performance, provider compatibility, architecture, testing, dependency inventory (210), NOTICE; limitations in release-notes-0.10.0.md and below |
| G12 | Device state restored, evidence complete, no unauthorized publication or mutation | PASS | R4: only com.musicglass.personal (0.10.0) left on the phone, every test app and testing file removed and listed in the ledger; no push, no publication; each write on the dummy account was approved by the owner; the owner's main app was cleared only on their instruction |

## 4. Counts and measurements

- **JVM:** 1,225 tests in 148 suites, 0 failures, 0 skipped (R5).
- **Device tests:** R5 emulator lanes on the final code: API 31 69 of 69, API 33 69 of 69, API 36 69 of 70 (P1, a test fault, dropped by the owner); the phone's device lane on rc10, 63 of 63 (61 with the network on, the two network-off cases with it off).
- **Lint:** 0 errors, 93 warnings, 3 hints, all triaged. Static checks: 173 files, 0 findings, 621 raw measures held by the ratchet.
- **Devices:** vivo I2217, Android 16 (tier R); emulators API 31 (riff_q02_fixture), API 33 (riff_q18_api33), API 36 (riff_q18_api36), network off.
- **Performance:** the table in section 3, G05, and [performance.md](performance.md). Battery: three 2-hour runs, about 3 % an hour streaming or from downloads and 2.5 % idle; no budget, recorded as measured.

## 5. Data: migration, legacy, deletion and retention

- Updating from rc20 moves on-device plays automatically; YouTube Music plays, searches and the old queue were shared by every account, so they wait in Earlier data until imported or deleted. Migration is resumable at every checkpoint.
- Deletion is per owner and asks first; it never changes the YouTube Music account. Clear history, searches, downloads, or everything for the account in use. Retention: 30, 90 or 365 days, or until cleared, applied at most once a day.
- The backup on this phone is on by default (D11): one unencrypted file per owner in `Documents/Riff Backup`, written when Riff leaves the screen (at most every 10 minutes) and at once after a deletion or restore. It survives uninstalling.
- The owner's phone started fresh: in R3 they chose to clear rc20's data with no export, so no legacy data remains there.

## 6. Known limitations

- A song tapped within about 3 s of a cold start takes 6 to 8 s to sound (Q18-098, Q18-109, dropped); a streamed song takes about 3 s from tap to sound, most of it the library's cipher (Q18-015, Q18-059).
- Scrolling misses the 2 % frame budget at 60 Hz (Q18-030); the benchmark's action timings are not measured (Q18-097).
- Song quality: Medium chooses the same format as High on Wi-Fi; left as the library chooses (Q18-108, dropped by the owner).
- Not exercisable on this phone: the DRC filter (no compressed copy seen, Q18-091) and the boost fallback (the phone always has LoudnessEnhancer, Q18-092); both rest on JVM tests.
- No TalkBack or keyboard walk-through; the screen-reader features are tested in code only.
- On a pale cover, pink and grey words on the artwork colour fall below 4.5:1 (rc20's colours, the owner's choice).
- The pinned YouTube library may fetch a pre-processed player script from jsDelivr and run it in the sandboxed stream helper (D4, disclosed in privacy.md).
- Provider: everything rests on YouTube Music's private API; a change on their side can break browsing or playback until Riff or the library is updated. The canary script notices such changes when run; it is not scheduled (nothing pushed).
- The update notice is built in but inactive: no release feed exists.
- Emulator flake: CrossfadeFixtureDeviceTest can report "The two decoders did not align" on an emulator that has run for hours; a cold boot clears it.

Recovery: if playback stops working after a YouTube change, Settings → About → Diagnostics shows the recorded errors and can share a cleaned report; never uninstall Riff to get past an install error (it deletes downloads and settings), and keep the backup on.

## 7. What is left, and the next steps

Nothing is left open. If any of these matters later, start here:

1. Q18-108, dropped: Medium and Low could choose lower formats (Medium about 160 kbps, Low about 70 kbps); then StreamQualityTest per choice.
2. Q18-030 and Q18-097, if smoother scrolling matters later: the glass's offscreen layer and glyph uploads are the measured causes (performance.md).
3. Q18-098 and Q18-109, if the first tap after a cold start matters later: the second player must wait for the first.
4. Publishing, if ever: reopens D1 (and D2's licence question no longer applies, since ML Kit is gone); needs a release feed for the update notice and a hosted CI run.
5. Two accounts at swapped chooser positions (ACC04) and the backup's waiting account file (F9) on the phone, when convenient.

## 8. The phone and this computer

The phone (vivo I2217) after R4: one Riff package, com.musicglass.personal 0.10.0 (code 33), signed out, its backup in `Documents/Riff Backup`. Removed: Riff Preview, Riff Debug and its test app, Riff Benchmark and the macrobenchmark runner, the test backup folders, a test export, two screen dumps, a test APK in the recycle bin, the removed apps' folders, and the outdated rc10 APK copied to the computer's Downloads. Left: Shizuku's files and an empty dalvik-cache folder in /data/local/tmp (no sign testing made them). The owner signs in again.

## 9. Installing and upgrading

- Install `riff-0.10.0.apk` on Android 12 or later (arm64), allowing installs from the app that opens it. Check it against SHA256SUMS.txt and the certificate above (`apksigner verify --print-certs`).
- It installs over any Riff signed with the same certificate (0.9.0-rc4 and later) and keeps that install's data. A lower version cannot be installed over it.
- **Prepared, not published.** The APK and source archive exist on this computer and the owner may share them privately; nothing is on a store, a release page or a remote repository.

## 10. Scorecard (100 points)

Scores do not offset a failed gate; G05 and G06 stand as written above.

| Area | Points | Score | Why not full |
|---|---|---|---|
| Correctness, account, data | 20 | 18 | ACC04 not arranged on the phone; clearing real data never exercised there (the owner wiped rc20) |
| Playback, recovery, downloads | 20 | 16 | First song after a cold start 6 to 8 s and about 3 s per streamed song (dropped or in the library); Song quality's Medium matches High on Wi-Fi (Q18-108, dropped) |
| UX and visual | 15 | 13 | Contrast on pale covers traded for rc20's colours; frame misses show while scrolling |
| Accessibility and localization | 10 | 6 | No TalkBack or keyboard walk-through; English only (pseudo-locales tested) |
| Performance | 10 | 5 | Four tier R budgets pass; frames fail, action timings missing, no tier L |
| Architecture | 10 | 7 | The provider facade stays (ADR-Q01 deviation); 621 raw measures held by the ratchet; no pinned formatter |
| Security and privacy | 5 | 4 | jsDelivr script (medium, accepted); hosted dependency verification never run |
| Testing and release | 5 | 4 | No hosted CI run; one intermittent JVM test and the emulator crossfade flake |
| Docs and onboarding | 5 | 5 | |
| **Total** | **100** | **78** | |

The score describes this plan's standard on the evidence above, not a guarantee about YouTube Music or every phone.

## 11. Handoff (R6)

**What is where**

| What | Where |
|---|---|
| Source, history, every document | `Riff` (branch `codex/riff-quality-m0`), local only. The remotes still name the suspended accounts; the working GitHub account is leovaldez2k22. Nothing is pushed |
| The final candidate | `Riff\artifacts\release\0.10.0` and `..\Riff-0.10.0\artifacts\release` (APKs, SHA256SUMS.txt, mapping, dependency list, source archive); a copy of the APK in the owner's Downloads |
| R5's rebuild and lanes | `..\Riff-final` (bc69f02): `artifacts\quality\lanes\pr-20261024T151040Z` and the emulator lanes |
| Earlier candidates | `Riff\artifacts\release` (rc20 and earlier, rc1 to rc6) and the worktrees `..\Riff-rc4` to `..\Riff-rc11` |
| Evidence of Q18 and Q19 | `Riff\artifacts\quality` (local, git-ignored), cited in the ledger |
| Your uncommitted work, left alone | `scripts/download_favourites.py` (your change), `docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md`, `.commandcode/` |

**What the owner still owns**

- **The signing key.** `.toolchain\signing\musicglass-release.p12` with its DPAPI password file, and the two encrypted backups (Google Drive and Gmail, 3 October 2026) whose passphrase lives in your password manager and on paper. Every future update must be signed with it; if it is lost, no installed Riff can be updated. Run the restore drill in [release-signing.md](../release-signing.md) about once a year.
- **`C:\c` and `C:\q18c6`.** Stray folders from my runs (a misplaced Gradle home, 760 MB; a Q18 C6 repo copy, 664 MB). Nothing uses them; delete them when you like.
- **Clean-up after this report:** [deletable-after-q19r.md](deletable-after-q19r.md) lists the worktrees, evidence folders, emulator images and branches that can go, each waiting for your word.
- **Decisions left:** none. Section 7 lists what could be picked up later.

**Not done, by decision:** no push, no publication, no release feed, no store listing (D1). Sharing the APK privately is your call.
