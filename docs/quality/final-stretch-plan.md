# Q19: the final stretch

Written 19 October 2026 at the owner's request: one plan for everything left before the excellence plan closes.
**Revised 20 October 2026 (evening)** at the owner's request, adding tests for the five features built on 20 October
(F6 to F10) and the bugs found since (Q18-048 to Q18-051), and marking what has already run. The revision changes no
rule; it adds rows, a status for each row, one decision (D11), and rc4 in place of rc3.

Each sub-phase starts only when the owner says so, and I ask before each one, as in Q18 (unless the owner names
several to run together; then exactly those run).

It replaces the "New order from 19 October 2026" at the end of docs/quality/q18-split.md. The Q18 tables it refers
to (B13 and A9 in q18-split.md, the C10 tests, the bug rows in q18-bugs.md) stay where they are and are not copied
here; this plan says when each runs and adds what is new since.

Status words used in the tables: **Done** (ran, result in the ledger), **Partly done** (what is missing is named),
**Redo on rc4** (passed on an earlier build; repeated because rc4 changes the code under it), **Open** (not started).

## 1. What "finished" means

The excellence plan (docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md, sections 18 and 19) closes when:

1. Every gate G01 to G12 reads PASS, or BLOCKED / DECLINED with its reason, rerun steps and the owner's decision
   where one is needed. No gate reads TODO or unknown.
2. Every bug in docs/quality/q18-bugs.md is Fixed and checked on the phone, Withdrawn, or Closed by the owner;
   any left open is listed as a known limitation with the owner's agreement.
3. F1 to F10 and the Q18-013 sign-in are checked on the phone and by the owner.
4. The final candidate is built from the final commit, signed with the release key, installed as Riff Preview,
   and (if the owner chooses, A7) installed over the main app.
5. docs/quality/final-report.md, the 100-point scorecard and the ledger are complete; the phone and this computer
   are left as the owner wants them.

## 2. Rules that carry over (unchanged)

- The 9 October authorization: device installs, account changes, publishing, pushing and deleting data each need
  the owner's explicit yes. Installing a Riff Preview the owner has asked for counts as asked.
- Never touch the main app (com.musicglass.personal) until A7; never uninstall it.
- No typing passwords; no screenshots of sign-in pages with a password visible; destructive buttons are not tapped
  without asking. The signing password is never read or printed: the owner runs the signing script.
- Never reset or clean the working tree, never `git stash`, never `git add -A`, never push, never `--no-verify`.
  `scripts/download_favourites.py` (owner's change) and `docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md` stay untouched
  and uncommitted.
- No cookies, passwords, keys, signed URLs, raw account IDs, names or email addresses in logs, commits or evidence.
- Downloads (system images, dependencies, AGP) are asked first, with name, source and size.
- Missing hardware or access means BLOCKED with rerun steps. Results are never invented.
- Bugs found while testing are logged in q18-bugs.md with their C10, B13 and A9 checks and fixed in the next fix
  round, not mid-test (the Q18 rule).
- After phone testing, the files testing put on the phone are removed (only those), and the ledger says what went.
  From 20 October this includes backup files a test made in `Documents/Riff Backup (preview)`; the backup a real
  install keeps is never removed by testing.
- No TalkBack checks (owner, 18 October).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## 3. What is left (inventory, 20 October 2026, evening)

### 3.1 Bugs

| Bug | State | Where it is handled |
|---|---|---|
| Q18-036 lyric card: fourth (Aurora) look | Fixed in Q19F1; looks checked on the phone (B4) | A3 (the owner names it) |
| Q18-037 lyric line's first letter clipped | Fixed in Q19F1 | B3 |
| Q18-038 Connected shows "98+" liked songs | Fixed in Q19F2 | B3 (needs the dummy account) |
| Q18-039 sign-in animation cut short | Fixed in Q19F2 | B3, A3 |
| Q18-040 no email from the account menu | Open; needs a captured response | B5 (capture), Q19G (fix) |
| Q18-015 about 3 s from tap to sound | Open: measure first | B3 (measure), Q19G (fix if a cause is found) |
| Q18-005 seek gap on downloaded songs | Partly fixed | B3 (measure), Q19G |
| Q18-023 Macrobenchmark stops after the first launch | Not fixed by 1.6.0-alpha01 (B6, 20 Oct); the lane now stops a silent run as BLOCKED | B6 again with the Q18-050 lane; else a known limitation |
| Q18-030 Home scroll render-thread misses | Trace read: the glass's full-screen offscreen layer and glyph uploads. Frosted glass is now the default and adds a blur | B6 (frosted on and off), Q19G |
| Q18-024 R8 metadata warnings | Known limitation (D8) | R1, R5 (G08) |
| Q18-041 config host refused | Closed by the owner (Q19D) | none |
| Q18-042 to Q18-047 | Fixed in Q19G G1 and passed on the emulators and phone | none, beyond the rc4 lanes |
| Q18-048 share sheet showed an older card | Fixed in code; LyricShareDeviceTest checks it | B3 row |
| Q18-049 glass let words show through | Not a bug (owner); frosted glass by default at 20 %, bending at the rim (d6a9ace), accepted by the owner on the emulator picture | B3 row, A3 |
| Q18-050 benchmark lane needed untrusted artifacts | Fixed by option (b) (10b96ac); not yet run on the phone | B6 |
| Q18-051 start-up crash (snapshot read) | Fixed (e900710); seen once on the emulator | B3 row (cold starts) |
| Q18-001 to Q18-035, the rest | Fixed in Q18F; waiting for their phone checks | B3 (B13 rows), A1 (A9 rows) |

### 3.2 Built but not checked on a device

- F1 song taps open the player; F2 share lyrics (partly checked in B4); F3 Previous restarts after 10 s; F4 slider
  haptics; F5 settings compartments (checked in B4) (docs/quality/feature-requests.md).
- Q18-013, the sign-in rebuild (sign-in-design.md): the screens without an account checked in B5; the real sign-in is A2.
- **New on 20 October** (owner's requests; none has run on a device):
  - **F6** the player's clock counts hours (1:43:39, not 103:39). JVM test only.
  - **F7** pin to speed dial: songs, episodes, podcasts, albums, playlists, artists; stored on the phone, loaded with
    Home, first in pin order with a badge, up to 27; carried in the local data file. JVM tests only.
  - **F8** frosted glass by default at 20 %, covering the glass to its edge and bending at the rim (Q18-049's remedy).
    Checked on the API 36 emulator by picture.
  - **F9** backup on this phone: one file per owner in `Documents/Riff Backup` (per build), a mirror, restored after a
    reinstall through the folder picker, another account's file waiting for its sign-in; not encrypted. JVM tests
    (LocalBackupTest) with a folder on disk and a wiped app; the picker, MediaStore and a real reinstall untested.
  - **F10** Not interested reaches YouTube Music: its own feedback where offered, otherwise a dislike; Undo removes
    the dislike on the same account only. JVM tests against recorded endpoints; no live call made.

### 3.3 Q18 sub-phases not yet run

B13 (fix checks without the owner), A9 (fix checks with the owner), B10 (live provider checks), B9 (battery), B8's
4-hour live soak, C8 (docs), A7 (main app upgrade, deferred by the owner), B12 (restore and record), C9 (closing
gates). Run since the first version of this plan: B2 on rc2, C5 second pass, C6 leftovers, B7b in part (B6).

### 3.4 Owner decisions still open

D1 to D10 are decided (section 4). D11 (the backup on by default, also for the main app) decided 20 October. Also left with the owner: the Q18-030 fix, if B6 shows the frosted blur costs frames.

### 3.5 Housekeeping

- `C:\q18c6` (the Q18C6 clone) and `C:\c` (two empty wrapper stubs from Q19C2) are waiting for the owner to delete them.
- `..\Riff-rc3` removed on 20 October at the owner's word (rc4 replaces it).
- rc3 was never signed and is not used: it predates F9 and F10.
- The API 36 emulator (riff_q18_api36) crashed three times on 20 October (exit 139). API 33 is the stable lane;
  API 36 is retried for C2 and recorded BLOCKED with the crash if it keeps failing.
- Static checks report one finding outside new code (AudioSessionEffects.kt:45, cancellation); fixed or reviewed in
  Q19G before rc4.

## 4. The phases

Five lettered groups, as in Q18, plus a decisions list. Letters say who has to be there:

| Group | Who | What |
|---|---|---|
| Q19D | Owner, in chat (no phone) | Decisions; any time, but some unblock later steps |
| Q19F | Neither (this computer) | Fix round 2: the four new bugs and the computer-side leftovers |
| Q19C | Neither (this computer and emulators) | Tests, lanes, candidates, case map |
| Q19B | Phone only (owner hands it over) | Device lane, candidate, fix checks, features, performance, soaks, battery, live checks |
| Q19A | Phone and owner | Checks only the owner can make: by eye, by ear, by feel, the real sign-in |
| Q19G | Neither, then phone | Fix round 3 for what B and A find, and the measured fixes |
| Q19R | All | Docs, final candidate, main-app upgrade, restore, closing gates, report |

Rough times are in brackets.

### Q19D: owner decisions (in chat)

| No. | Decision | Blocks |
|---|---|---|
| D1 | Publishing. **Decided 19 Oct 2026:** personal use only; nothing is pushed or published. R6 records no publication; G10 and G12 note it. Publishing later reopens D2 and D3 | R6, G10, G12 |
| D2 | ML Kit translation in a GPL-3.0 app. **Decided 19 Oct 2026:** keep (personal use, no distribution). If Riff is ever published, remove it or get a qualified licence review first | R1, G07, G11 |
| D3 | The four reference images. **Decided 19 Oct 2026:** out of the source archive (done in G1) | R2 |
| D4 | The jsDelivr player script. **Decided:** keep the fallback; it runs only in the sandboxed :solver process and stays disclosed in privacy.md | R1, G07 |
| D5 | The 1.15 s logo. **Decided 19 Oct 2026:** accept for now; judged by eye in A4, shortened in Q19G if it feels long | G05 |
| D6 | Darker scrims, black text on pink. **Decided 19 Oct 2026:** accept; no design pass | G06 |
| D7 | "Remove download" while queued or running. **Decided 19 Oct 2026:** keep | none |
| D8 | AGP upgrade for Q18-024. **Decided 19 Oct 2026: no.** The warnings stay as a known limitation with the reason | G08 |
| D9 | Plan 2's supplement. **Decided 19 Oct 2026, package by package:** S00, S02, S03 (fingerprints and archive), S04 (as the D1 record) in scope; S01, S05, S06, S07 declined and named in the final report | Q19F5, G01 |
| D10 | Version names. **Decided 19 Oct 2026:** test candidates 0.10.0-rc2 and on, each with a higher code; the final candidate is 0.10.0 with a code above every earlier build; the main app's code checked lower before A7. **Applied 20 Oct:** rc3 (code 24) prepared but superseded; the next test candidate is **0.10.0-rc4, code 25** | C4, C8, R2, G10 |
| D11 | The backup on this phone (F9) by default. When the main app is upgraded in A7, it starts writing the owner's real history, searches and settings, unencrypted, to `Documents/Riff Backup`. **Decided 20 Oct 2026: keep the backup on by default**, for every install including the upgraded main app. No code change | R3 |

### Q19F: fix round 2 (this computer) — Done 19 October 2026

| No. | Work | C10 | Status |
|---|---|---|---|
| F1 | Q18-037 clip removed; Q18-036 the fourth (Aurora) card look | LyricsShareTest | Done (6c31a74) |
| F2 | Q18-038 real counts; Q18-039 the sign-in timeline | Count fixtures; timeline tests | Done (f463a14) |
| F3 | LyricShareDeviceTest; settings compartments test | Device tests run in C2 | Done (9713908) |
| F4 | Lint triage of the new warnings; security review section 9 | lint 0 errors | Done |
| F5 | Plan 2 supplement work (D9): S00, S02 (PC01 to PC03), S03 (hashes; source archive with the owner's approval), S04 recorded with D1 | Ledger entries S00 to S07 | Done |

### Q19C: tests, lanes and candidates (this computer and the emulators)

| No. | Work | Exit | Status |
|---|---|---|---|
| C1 | The pr lane (`verify.py --lane pr`), static checks, script tests | 0 failures, 0 findings [30 min] | Done on rc2; **done on rc4** (aa4fde0, every check PASS, 20 Oct) |
| C2 | Emulator lanes on API 31, 33 and 36 (disposable AVDs; network off): every instrumented test, including C7's new tests | All pass, or each failure logged [2 to 3 h] | **Done with C7's tests** (3c94f48, 20 Oct): API 33 65 of 65, API 31 65 of 65, API 36 66 of 66 |
| C3 | Sign-in screens on the emulator without an account (SignInScreensDeviceTest) | A device test per screen; screenshots [2 h] | Done (6 of 6 on API 33 and 36) |
| C4 | rc2 candidates, signed by the owner; certificate, ABI, size, version checked | A signed Riff Preview with a recorded digest | Done on rc2; replaced by C8 |
| C5 | Case map second pass: no TODO; add rows as runs report | case-map.md with no TODO | Done; **add F6 to F10 and Q18-048 to Q18-051** (C7) |
| C6 | Upgrade from rc20 on the emulator; packaging lane | Both PASS or BLOCKED | Done on rc2 (PASS_LOCAL). **Redo on rc4** (upgrade only: rc20 with fixture data, then rc4 over it; a new install's backup does not run over the upgraded data's restore offer) |
| C7 | **New: device tests for F6 to F10**, written on this computer and run in C2: **(a)** F6 a fixture episode over an hour shows hours in the player clock and seek bar, under an hour stays m:ss; **(b)** F7 pin a song, an episode and an album from the long-press menu; force-stop; Home shows them first in pin order with the badge; the 28th pin is refused with its notice; unpin; **(c)** F8 Settings shows frosted at 20 % on a fresh install, a chosen value survives a restart; pictures of the bar over Home and Settings, liquid and frosted; **(d)** F9 on the emulator, fixture data for the phone, signed-out and a fixture account: Back up now writes three files in `Documents/Riff Backup (debug)`; uninstall and reinstall; the restore offer shows on first start; the folder picker is driven by UiAutomator to the folder and "Use this folder"; the data is back and the account's file waits; Not now leaves the offer gone for good; turning the backup off removes the files; nothing is left in Documents afterwards; **(e)** F10 has no device part: there is no fixture server on the device, and the JVM tests already drive the real repository against recorded endpoints (`like/dislike`, `like/removelike`, nothing for another account); the live check is B7. Tests (a) to (d) are OwnerFeaturesDeviceTest in the fixture lane, except the reinstall and the picker, which are a scripted adb run on the emulator (an instrumented test cannot uninstall its own app) | Each test passes on API 33 and 31 (and 36 unless BLOCKED by its crash) [half a day] | **Done** (20 Oct): OwnerFeaturesDeviceTest; the reinstall by hand on API 33; found Q18-052 and Q18-053, both fixed |
| C8 | **New: rc4.** Version 0.10.0-rc4, code 25, from the commit after C7 and Q19G's computer fixes; a clean worktree (as for rc3); side-by-side debug and test APKs, benchmark builds; the owner runs the release script; I check certificate, ABI, size, version and the release-content check | A signed rc4 Preview with a recorded digest [owner 5 min; checks 15 min] | Prepared (1ffce77, ..\Riff-rc4, side-by-side builds in artifacts/quality/q19/c8; re-cut before signing to take the swipe change); waiting for the owner's signing |

### Q19B: the phone, without the owner

The owner hands the phone over unlocked, charged, on Wi-Fi, with the dummy test account available in Riff Preview.
Each step that changes the phone (install, uninstall, reboot, airplane mode, settings) is listed when asked.

| No. | Work | Exit | Status |
|---|---|---|---|
| B1 | Device lane on the phone with the side-by-side debug build: every instrumented test, now including C7's (F9's reinstall test runs on the debug build, not on Riff Preview) | All pass or logged [1 h] | Done on 6bcce26 (54 of 54). **Redo on rc4** |
| B2 | The signed candidate on the phone: uninstall the old Riff Preview (asked: deletes its data), install rc4 Preview, check version and certificate on the device, smoke run (Home, search, a downloaded song, queue, lyrics, settings, the sign-in welcome) | PASS with screenshots [30 min] | Done on rc2. **Redo on rc4** |
| B3 | Fix checks: every row of the B13 table in q18-split.md, plus the new rows below; the timing measurements for Q18-015 and Q18-005; A11Y02 (largest font, landscape) | Each row PASS, FAIL (logged) or BLOCKED [3 to 4 h] | Open (needs the dummy account) |
| B4 | F1 to F5 on the phone | Each PASS or logged [1.5 h] | Partly done (F2 and F5 without an account, 20 Oct). Open: F1, F3, F4, and F2's share preview again after Q18-048 |
| B5 | Sign-in on the phone without credentials, then Q18-040's redacted capture with the dummy account | Screens PASS; capture kept [45 min] | Partly done (screens without an account, 20 Oct). Open: the capture |
| B6 | Performance: the benchmark lane on the phone with Q18-050's fix (Q18-023: does it run, or BLOCKED after 15 silent minutes); cold start with and without the Baseline Profile; **Home scroll with frosted glass at 20 % (the new default) and with it off**, 120 Hz and 60 Hz if the owner sets Standard; a trace of each for Q18-030; `check_performance.py summary --tier R` | Budgets PASS or FAIL with traces; Q18-023 decided; whether the frosted default costs frames [2 h] | Partly done (fallback cold start and liquid-glass scroll, 20 Oct). Open: the lane, the frosted measurement |
| B7 | Live provider checks on the dummy account (search, Home, albums, large playlists, expired session, canary stream phase, loudness, a real download), **plus F10 live**: Not interested on one song from search (no YouTube token): the notice says disliked; the song reads as disliked in YouTube Music; Undo: the dislike is gone; and on a Home card that carries YouTube's own token: the notice says YouTube Music will suggest it less. Each like, dislike, playlist edit, save or feedback is asked first | Each row PASS or logged [2 h 15 min] | Open |
| B8 | Battery: three 2-hour playback runs, unplugged, from batterystats; BLOCKED if no trustworthy measurement | Three runs recorded [6 h, overnight] | Open |
| B9 | The 4-hour live soak on the dummy account; the owner starts the streaming | No crash, ANR or memory growth past budget [4 h, overnight] | Open |
| B10 | Restore after each sitting: settings put back, test files removed (from rc4 on, also `Documents/Riff Backup (debug)`, which the debug build writes when it leaves the screen during the lane), the ledger says what was removed | Phone as handed over [10 min each] | Done after the 20 Oct night; again after every sitting |
| B11 | **New: F6 to F10 on the phone, on the signed rc4 Preview.** **F6:** a podcast episode over an hour, seek past 1:00:00; the player, mini player and notification read hours. **F7:** pin a song, an episode, a podcast and a playlist; close Riff from recents and reopen: the pins load first, with Home offline too (airplane mode, asked); unpin all. **F8:** a fresh install's Settings reads frosted 20 %; pictures of the bar and mini player over Home, Settings and the player, for A3. **F9:** with the dummy account and a signed-out play: Back up now; `Documents/Riff Backup (preview)` holds three files (listed with adb, names only); uninstall and reinstall Riff Preview (asked: deletes its data); the restore offer; the picker opens at the folder; one tap restores; history, searches, pins and settings are back; the dummy account's file waits until it signs in (A2 or a sign-in the owner does), then its data is back; the backup files are then removed with the backup's own Off switch, and the ledger lists them. **F10:** covered in B7 | Each PASS or logged [1.5 h] | Open |

New B13 rows (add to the Q18 table when B3 runs):

| Bug or feature | Check |
|---|---|
| Q18-036 | Four looks on the share sheet; the new one matches the sampled colours on a saved picture (picture removed after) |
| Q18-037 | "Cinnamon Girl" lyrics at the sung line: screenshot shows the first letter whole |
| Q18-038 | Screen 6 count equals Riff's Liked music count for the dummy account |
| Q18-039 | screenrecord of a dummy-account sign-in: Checking at least 7.4 s, ticks in order, 5 to 6 complete, numbers count up |
| Q18-040 | The dummy account's welcome row and screen 6 after the fix |
| Q18-013 | Back through every step; the stop sheet; "didn't load" only with the network off, never on a slow page |
| Q18-048 | Share two different cards in a row: the share sheet's preview is the second card each time (cancelled; nothing sent) |
| Q18-049 | Fresh install: the bar and mini player are frosted to their edges with the rim bend; words beneath are not readable through them at rest |
| Q18-051 | Ten cold starts from the launcher, five of them with the network off: no crash (logcat checked for the snapshot error) |

### Q19A: the phone with the owner

One or two sittings; about 2 hours 15 minutes in all.

| No. | Work | Exit | Status |
|---|---|---|---|
| A1 | Every row of the A9 table in q18-split.md (by eye and by ear) | Each row PASS or logged [45 min] | Open |
| A2 | The sign-in for real with the dummy account; Change account to the owner's and back; an abandoned change; the saved-accounts list; "Remove from this phone". The owner types every password. **Also, after B11's reinstall:** the dummy account signing in restores its waiting backup file (the notice and its history checked) | PASS or logged [35 min] | Open |
| A3 | By feel and by eye: F1 to F5, the four card looks (the owner names Aurora), the full sign-in animation with real numbers, **frosted glass at 20 % on the phone (F8, Q18-049), the pin badge and pinned cards on Home (F7), the hours clock on a long episode (F6)** | Owner's verdict per item [40 min] | Open |
| A4 | Feel checks again: first song after a break and after a cold start; the cold-start logo (D5) | Owner's verdict [10 min] | Open |
| A5 | Lock-screen controls by hand: play, pause, next | PASS [5 min] | Open |

### Q19G: fix round 3

For what B and A find, and the measured problems if the measurements give a cause: Q18-015, Q18-005's download
half, Q18-030 (the glass's offscreen layer, and the frosted blur if B6 shows it costs frames), Q18-040 (from B5's
capture), Q18-023 if the runner still stops. Same rules as Q18F: each bug logged with its C10, B13 and A9 checks;
each fix with its C10 test; then the affected B and A rows run again on a new candidate. If B and A find nothing and
the measurements give no cause, Q19G is recorded as empty and the measurements go into performance.md as known
limitations (owner agrees).

| No. | Work | Status |
|---|---|---|
| G1 | Log and fix (computer), C10 per bug, JVM, lint, emulator lanes; D3 | Done for Q18-042 to Q18-047 and D3 (20 Oct). Continues for what B and A find; also AudioSessionEffects.kt:45 before rc4 |
| G2 | New candidate (owner signs) | rc3 prepared, superseded; **rc4 is C8** (and rc5 and on if later fixes need one) |
| G3 | The affected B13 and A9 rows again on the phone | Open |

### Q19R: release and close

| No. | Work | Exit | Status |
|---|---|---|---|
| R1 | Docs against the final code: README, features (F1 to F10, the sign-in), privacy (saved accounts; D2, D4; **the backup and its D11 default; Not interested sending a dislike**), performance (B6 numbers, the frosted default's cost), provider compatibility (the dislike), dependency inventory, release notes | Every doc dated and true to the code [2 h] | Open (F9 and F10 docs written 20 Oct; checked again here) |
| R2 | The final candidate (0.10.0) from the final commit: the owner runs the release script; I check certificate, ABI, size, version, source archive and checksums; install as Riff Preview (asked) and a last smoke run | G10 evidence [owner 5 min; 30 min] | Open |
| R3 | A7: the owner decides whether to install the candidate over the main app (rc20 with real history). If yes: an export first (kept on the computer), the upgrade, then history, queue, downloads, saved songs and pins checked present; **the backup on (D11), its first write checked (files listed by name only; contents never opened)**; privacy actions only row by row as the owner approves | PASS, or DEFERRED/DECLINED by the owner [35 min] | Open |
| R4 | Restore and record: settings back, test packages removed as the owner says (asked, listed), Riff Preview kept or removed, device state recorded; testing files removed, **including any `Documents/Riff Backup (preview)` and `(debug)` files testing made** | G12 evidence [20 min] | Open |
| R5 | Closing gates: rebuild from the final commit; full collector runs; the emulator lane; G01 to G12 each PASS, FAIL or BLOCKED with evidence; the 100-point scorecard; final-report.md | Final report and scorecard committed [3 h] | Open |
| R6 | Handoff: what is where, what the owner still owns (`C:\q18c6`, `C:\c`, the signing key backups; no push or publication under D1) | A short handoff section in final-report.md [20 min] | Open |

## 5. Order

```
Q19D (D1 to D11 decided)
   |
Q19F (done)  ->  C1 to C6 (done on rc2)
   |
C7 (new device tests)  ->  G1 leftovers  ->  C1, C2 on that commit  ->  C8 rc4 (owner signs)  ->  C6 on rc4
                                                  |
                 B1, B2 on rc4  ->  B3, B4, B5, B6, B7, B11   (one or two hand-overs)
                                                  |            B8, B9 overnight
                                          A1 to A5            (with the owner; any time after B2; A2 after B11)
                                                  |
                                          Q19G  (if anything was found or measured; then rc5 and its rows)
                                                  |
                                     C5 final, R1  ->  R2  ->  R3 (owner's choice)  ->  R4  ->  R5  ->  R6
```

Suggested sittings:

1. Computer, no phone: C7, G1's leftovers, C1 and C2 on that commit, rc4 prepared (about a day).
2. The owner signs rc4 (C8, five minutes), then hands over the phone: B1, B2, then B3 to B7 and B11 (one long day,
   or two halves). B8 and B9 run on the nights after.
3. With the owner: A1 to A5 (about 2 hours 15 minutes; can follow sitting 2 the same day).
4. Q19G if needed (computer), its candidate and its phone rows.
5. Closing: R1 to R6 (about a day; the owner is needed for R2's signing and the R3 decision).

## 6. Approval points (asked when reached, each on its own)

| Where | What is asked |
|---|---|
| Before every sub-phase | Permission to start it (the Q18 rule) |
| C2, C7 | Booting the disposable emulators |
| C8, R2 | The owner runs the release script |
| B2, B11, R2 | Uninstalling Riff Preview (deletes its data); installing the candidate |
| B4, B5, B11 | Turning airplane mode on and off; saving one card picture and removing it |
| B5 | Saving one redacted account-menu capture |
| B7 | Each like, dislike, Not interested, Undo, playlist edit, save or download on the dummy account |
| B8, B9 | Running unplugged over Wi-Fi adb overnight; the owner starts B9's streaming |
| B11, R4 | Removing the backup files testing made in Documents (by the backup's Off switch, listed by name) |
| R3 | Installing over the main app; each privacy action |
| R4 | Each package removed from the phone |
| R6 | Any push or publication (only under D1) |

## 7. Evidence and records

- Phone and emulator evidence under `artifacts/quality/q19/<sub-phase>/` (local, not committed; anything showing an
  account's address or name is marked local only). Backup files are listed by name; their contents are never copied
  off the phone or the emulator except fixture data.
- Each sub-phase gets a ledger section: what ran, on which build (commit and signature), results, what was removed
  from the phone.
- Bugs go into q18-bugs.md (it stays the one bug log), numbered on from Q18-052.
- The case map and feature-requests.md are updated as checks pass; F6 to F10 get case rows in C7.

## 8. Risks

| Risk | Plan |
|---|---|
| YouTube changes its responses during the stretch (live checks, counts, email, feedback tokens) | Captured fixtures for every parser change; B7 rows that fail on a provider change are logged as provider changes, not Riff bugs, with the capture |
| A dislike sent by F10 lands on the wrong account, or Undo misses it | Only the dummy account is used in B7; the account scope is checked before Undo; A2 checks no dislike crossed to the owner's account |
| The backup writes real history unencrypted once the main app is upgraded | Accepted by the owner (D11, on by default); privacy.md says so; R3 checks the files exist by name only |
| The folder picker differs on the owner's phone (vendor Files app) | B11 records the steps the phone's picker needs; a picker that cannot reach Documents is a bug for Q19G |
| Macrobenchmark still stops on the phone (Q18-023) | PERF rows BLOCKED with the evidence; the cold-start fallback and frame numbers stand in; G05 records it |
| Frosted glass by default costs frames on Home | B6 measures it on and off; the owner chooses in Q19G (a lighter blur, or liquid by default) |
| The API 36 emulator keeps crashing | C2 and C7 run on API 31 and 33; API 36 BLOCKED with the crash and rerun steps |
| The release key is unavailable on the day | C8 and R2 wait; development-key builds are never used for B2, C6 or R2 evidence |
| A fix in Q19G touches playback, downloads or the backup | The emulator lanes and the affected B3 and B11 rows rerun before R2 |
| Battery numbers untrustworthy | B8 BLOCKED with the reason and rerun steps rather than estimated |
| Time: B8 and B9 need nights | They run after B2 on any nights the phone is free; R5 waits for them or records them BLOCKED by the owner's choice |
