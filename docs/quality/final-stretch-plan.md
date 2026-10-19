# Q19: the final stretch

Written 19 October 2026 at the owner's request: one plan for everything left before the excellence plan closes.
Nothing in it is started. Each sub-phase starts only when the owner says so, and I ask before each one, as in Q18
(unless the owner names several to run together; then exactly those run).

It replaces the "New order from 19 October 2026" at the end of docs/quality/q18-split.md. The Q18 tables it refers
to (B13 and A9 in q18-split.md, the C10 tests, the bug rows in q18-bugs.md) stay where they are and are not copied
here; this plan says when each runs and adds what is new since.

## 1. What "finished" means

The excellence plan (docs/RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md, sections 18 and 19) closes when:

1. Every gate G01 to G12 reads PASS, or BLOCKED / DECLINED with its reason, rerun steps and the owner's decision
   where one is needed. No gate reads TODO or unknown.
2. Every bug in docs/quality/q18-bugs.md is Fixed and checked on the phone, Withdrawn, or Closed by the owner;
   any left open is listed as a known limitation with the owner's agreement.
3. F1 to F5 and the Q18-013 sign-in are checked on the phone and by the owner.
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
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## 3. What is left (inventory, 19 October 2026)

### 3.1 Bugs

| Bug | State | Where it is handled |
|---|---|---|
| Q18-036 lyric card: fourth, blue-to-pink look | Open (owner's request) | Q19F1 |
| Q18-037 lyric line's first letter clipped | Open | Q19F1 |
| Q18-038 Connected shows "98+" liked songs | Open | Q19F2 |
| Q18-039 sign-in animation cut short | Open (owner's request) | Q19F2 |
| Q18-040 no email from the account menu | Open; needs a captured response | Q19B5 (capture), Q19G (fix) |
| Q18-015 about 3 s from tap to sound | Open: measure first | Q19B3 (measure), Q19G (fix if a cause is found) |
| Q18-005 seek gap on downloaded songs | Partly fixed | Q19B3 (measure), Q19G |
| Q18-023 Macrobenchmark stops after the first launch | Partly fixed (1.6.0-alpha01) | Q19B6 |
| Q18-030 Home scroll at 60 Hz just over budget | Open: needs a trace | Q19B6, Q19G |
| Q18-024 R8 metadata warnings | Partly fixed; needs an AGP upgrade | Q19D (decision), Q19F5 (if yes) |
| Q18-001 to Q18-035, the rest | Fixed in Q18F; waiting for their phone checks | Q19B3 (B13 rows), Q19A1 (A9 rows) |

### 3.2 Built but not checked on a device

- F1 song taps open the player; F2 share lyrics; F3 Previous restarts after 10 s; F4 slider haptics; F5 settings
  compartments (docs/quality/feature-requests.md).
- Q18-013, the sign-in rebuild (sign-in-design.md). The owner has already signed in once on Riff Preview (screen 6
  screenshot); that is not yet a recorded check.
- The UiRegressionInstrumentation changes for the settings compartments (compiled, never run).

### 3.3 Q18 sub-phases not yet run

B2 (signed candidate), B13 (fix checks without the owner), A9 (fix checks with the owner), B10 (live provider
checks), B7b (performance, after Q18-023), B9 (battery), B8's 4-hour live soak, C5 second pass, C6 leftovers
(certificate and upgrade checks need a signed candidate), C8 (docs), A7 (main app upgrade, deferred by the owner),
B12 (restore and record), C9 (closing gates).

### 3.4 Owner decisions still open

From q18-split.md: publishing; ML Kit translation in a GPL-3.0 app; the four reference images; the jsDelivr player
script; the 1.15 s logo against the 1.2 s budget; darker scrims and black text on pink chosen by arithmetic;
"Remove download" on queued downloads. New since: the AGP upgrade for Q18-024; whether plan 2's supplement
(S00 to S07) is in scope; the version name of the final candidate.

### 3.5 Housekeeping

- `C:\q18c6` (the Q18C6 clone) is waiting for the owner to delete it.
- Riff Preview on the phone is signed with the development key; the release-signed candidate (Q19C4) needs it
  uninstalled first, which deletes its data. Asked at that point.
- Lint now reports 114 warnings, up from the 85 triaged in Q18C4: the new code needs triage (Q19F4).

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
| D1 | Publishing: personal use only, source only, or source plus signed APKs; and whether anything is pushed | R6, G10, G12 |
| D2 | ML Kit translation under Google's terms in a GPL-3.0 app: keep (after a qualified review) or remove | R1, G07, G11 |
| D3 | The four reference images in `references/`: in the source archive or not | R2 |
| D4 | The jsDelivr pre-processed player script: keep or turn off | R1, G07 |
| D5 | The 1.15 s logo against the 1.2 s cold-start budget: accept or shorten | G05 |
| D6 | Darker scrims and black text on pink buttons (chosen by arithmetic): accept or ask for a design pass | G06 |
| D7 | "Remove download" while a download is queued or running: keep or narrow | none |
| D8 | AGP upgrade to a release that bundles R8 9.1.29 or newer (Q18-024): yes (download asked first) or leave the 179 warnings documented | Q19F5, G08 |
| D9 | Plan 2's supplement (S00 to S07): bring into scope, decline each, or decline all. Its G01 counts each as done or declined by an explicit decision | G01 |
| D10 | Final version name and code (0.10.0, or another rc) | Q19C4, R2, G10 |

### Q19F: fix round 2 (this computer)

Each fix gets its C10 test (written in the bug's row), then the full JVM suite and lint. One commit per bug.

| No. | Work | C10 |
|---|---|---|
| F1 | Q18-037: confirm the clip is the cause with a picture, then remove the clip from lyric lines and draw the chosen tint behind the line. Q18-036: the fourth card look (navy base, blue glow from the top left, pink glow from the bottom right; working name Aurora; the owner names it in A3 if they want) | LyricsShareTest: the new look's four corners against the sampled colours; a held first word's pixels reach past the line's left edge, chosen and not chosen [1 h] |
| F2 | Q18-038: real counts. First read a liked-songs response's header for a total (fixture only, no live call); if none, count through the continuations in the background, paced, cancelled on an account change. Playlists: confirm the count leaves out non-playlist cards. Q18-039: the minimum timeline for Checking and 5 to 6, real work inside it, counts fetched from the commit and counted up when they float in, failure shown at once, nothing added with Remove animations on | Count fixtures (header total; three continuation pages); the test-clock timeline tests in Q18-039's row [3 h] |
| F3 | Hardening the new features where tests are thin: an instrumented test that the lyric card's share intent carries a content:// URI readable by the receiver and nothing outside `shared_lyrics/`; Save writes one picture to Pictures/Riff and nothing on failure (emulator); the settings compartments keep each row's test tag and open state; F1 does not open the player for Play and Shuffle buttons | New device tests run in Q19C2 [2 h] |
| F4 | Lint: triage the 29 new warnings from F2, F5 and the sign-in (lint-triage.md), fix what is real, no blanket suppression. Security pass on the new surfaces: FileProvider paths, MediaStore insert, links that open outside Riff (gesture only, https only), the saved-accounts store (no cookies; email never logged: a grep of every log call in the new code and of the diagnostics ring) | lint 0 errors; security-review.md section added [2 h] |
| F5 | Only if D8 is yes: the AGP upgrade (download asked first), the R8 9.1.29+ shrinker, the 179 warnings checked gone, and the Q18-035 regression check (a cancelled download frees the engine in a debug build) | Full JVM, emulator lane on API 36, DownloadTimeLimitDeviceTest [3 h] |

Exit: JVM suite green, lint 0 errors, every commit with its test, ledger section.

### Q19C: tests, lanes and candidates (this computer and the emulators)

| No. | Work | Exit |
|---|---|---|
| C1 | The pr lane (`verify.py --lane pr`), static checks, script tests on the fixed code | 0 failures, 0 findings [30 min] |
| C2 | Emulator lanes on API 31, 33 and 36 (the disposable AVDs; network off): every instrumented test including the settings compartment changes, Q19F3's new tests, the Q18 device tests | All pass, or each failure logged as a bug [2 to 3 h, one emulator at a time] |
| C3 | Sign-in screens on the emulator without any account: welcome with seeded saved accounts, all accounts (search, remove), Google's page loads accounts.google.com in its frame, the stop sheet on Back, "didn't load" with the network off, Back through every step; with Remove animations on, every transition a cut. Screenshots kept (no account, so no personal data) | A device test per screen; screenshots in artifacts [2 h] |
| C4 | Candidates: the side-by-side debug build and its test APK (Q19B1), stress and benchmark builds (Q19B3, B6), and the release candidate built by the owner with `scripts/build-release-candidate.ps1 -Preview -Offline -AllowDirty` (version from D10). I check its certificate digest against release-signing.md and its ABI, size and version | A signed Riff Preview with a recorded digest [owner 5 min; checks 15 min] |
| C5 | Case map second pass: add F1 to F5, the sign-in screens and Q18-036 to Q18-040 as cases; every TODO row becomes PASS, FAIL, BLOCKED, DEFERRED or DECLINED as the B and A runs report | case-map.md with no TODO [1 h, then updated after B and A] |
| C6 | Clean-checkout leftovers: with the signed candidate, the certificate check and the upgrade from rc20 on the emulator (an rc20 install with fixture data, then the candidate over it: data kept, migration lines in the log) | Both PASS or BLOCKED with reason [1 h] |

### Q19B: the phone, without the owner

The owner hands the phone over unlocked, charged, on Wi-Fi, with the dummy test account available in Riff Preview.
Each step that changes the phone (install, uninstall, reboot, airplane mode, settings) is listed when asked.

| No. | Work | Exit |
|---|---|---|
| B1 | Device lane on the phone with the side-by-side debug build (Q18B1 again on the new code): every instrumented test, including the settings compartments and Q19F3's tests; screenshots pulled, nothing left on the phone | All pass or logged [1 h] |
| B2 | The release candidate (Q19C4) on the phone: uninstall the development-key Riff Preview (asked: deletes its data), install the candidate, check version and certificate on the device, smoke run over adb (Home, search, a downloaded song, queue, lyrics, settings, the sign-in welcome without signing in) | PASS with screenshots [30 min] |
| B3 | Fix checks: every row of the B13 table in q18-split.md, plus the new rows below. Includes the timing measurements for Q18-015 (first song cold and after 10 minutes away) and Q18-005 (seeks back and ahead, stream and download, timed from the media session) | Each row PASS, FAIL (logged) or BLOCKED [3 to 4 h] |
| B4 | F1 to F5 on the phone: F1 a tapped song opens the player from lists, collections, downloads, history and on-device, and Play or Shuffle does not; F2 hold timing, five-line limit, all four looks, Save (one picture in Pictures/Riff, then removed) and the share sheet opening (cancelled, nothing sent); F3 Previous before and after 10 s from the player, the notification and a widget; F4 haptic constants fired at each mark (read from the haptic events where the phone exposes them; the feel itself is A3); F5 one compartment at a time, every row reachable, sliders unchanged | Each PASS or logged [1.5 h] |
| B5 | Sign-in on the phone without credentials: welcome, all accounts, stop sheet, "didn't load" in airplane mode, the country host in the bar, outside links opening in the browser. Then Q18-040's capture: with the dummy account signed in, one account-menu response saved with names, addresses and IDs redacted before it is written, to see where (if anywhere) YouTube sends the address | Screens PASS; redacted capture kept [45 min] |
| B6 | Performance (Q18B7b): Macrobenchmark with androidx.benchmark 1.6.0-alpha01 on the phone (Q18-023: does it pass the first launch?); cold start, player and queue actions, frames at 60 Hz (owner sets Standard at hand-over) and 120 Hz, with and without the Baseline Profile; a render-thread trace of Home scrolling for Q18-030; `check_performance.py summary --tier R` | Budgets PASS or FAIL with traces; Q18-023 decided [2 h] |
| B7 | Live provider checks (Q18B10) on the dummy account: search (six kinds, Load more), Home and its second page, an album, playlists over 100 and 2,000 songs, owned and others' playlists, an expired session, the canary's stream phase, a live loudness value, a real download. Each like, playlist edit or save is asked first | Each row PASS or logged [2 h] |
| B8 | Battery (Q18B9): three 2-hour playback runs, unplugged, adb over Wi-Fi, screen off, from batterystats; BLOCKED if no trustworthy measurement | Three runs recorded [6 h, overnight] |
| B9 | The 4-hour live soak (Q18B8's second half): streaming on the dummy account, screen off, sampled every 5 minutes; the owner starts the streaming (the unattended-tap rule) | No crash, ANR or memory growth past budget [4 h, overnight] |
| B10 | Restore after each sitting: settings changed in B are put back, test files removed from the phone, the ledger says what was removed | Phone as handed over [10 min each] |

New B13 rows (add to the Q18 table when B3 runs):

| Bug or feature | Check |
|---|---|
| Q18-036 | Four looks on the share sheet; the new one matches the sampled colours on a saved picture (picture removed after) |
| Q18-037 | "Cinnamon Girl" lyrics at the sung line: screenshot shows the first letter whole |
| Q18-038 | Screen 6 count equals Riff's Liked music count for the dummy account |
| Q18-039 | screenrecord of a dummy-account sign-in: Checking at least 7.4 s, ticks in order, 5 to 6 complete, numbers count up |
| Q18-040 | The dummy account's welcome row and screen 6 after the fix |
| Q18-013 | Back through every step; the stop sheet; "didn't load" only with the network off, never on a slow page |

### Q19A: the phone with the owner

One or two sittings; about 2 hours in all.

| No. | Work | Exit |
|---|---|---|
| A1 | Every row of the A9 table in q18-split.md (by eye and by ear) | Each row PASS or logged [45 min] |
| A2 | The sign-in for real: sign in with the dummy account through the new page; Change account to the owner's account and back (UX07, ACC02, ACC04, at swapped chooser positions); an abandoned change leaves Riff and Google's list in agreement; the saved-accounts list after both; "Remove from this phone" on one row (it leaves the Google account alone). The owner types every password; no screenshot while a password is on screen | PASS or logged; DEFERRED rows in the case map closed [30 min] |
| A3 | F1 to F5 and the four new fixes by feel: haptic strengths on the three sliders, the hold-and-pop of a lyric line, the four card looks (the owner names the new one), the full sign-in animation with real numbers | Owner's verdict per item [30 min] |
| A4 | Feel checks again (Q18A8): first song after a break and after a cold start; the cold-start logo (with D5) | Owner's verdict [10 min] |
| A5 | Lock-screen controls by hand (UX10): play, pause, next | PASS [5 min] |

### Q19G: fix round 3

For what B and A find, and the measured problems if the measurements give a cause: Q18-015 (where the 3 s goes:
lookup, solver, first bytes, decoder), Q18-005's download half, Q18-030 (the render-thread work the trace shows),
Q18-040 (from B5's capture), Q18-023 if the runner still stops. Same rules as Q18F: each bug logged with its C10,
B13 and A9 checks; each fix with its C10 test; then the affected B and A rows run again on a new candidate. If B and
A find nothing and the measurements give no cause, Q19G is recorded as empty and the measurements go into
performance.md as known limitations (owner agrees).

| No. | Work |
|---|---|
| G1 | Log and fix (computer), C10 per bug, JVM, lint, emulator lane on API 36 |
| G2 | New candidate (owner signs) |
| G3 | The affected B13 and A9 rows again on the phone |

### Q19R: release and close

| No. | Work | Exit |
|---|---|---|
| R1 | Docs against the final code (Q18C8): README, features (F1 to F5, the sign-in), privacy (saved accounts already in; D2 and D4 outcomes), performance (B6 numbers, known limitations), provider compatibility, dependency inventory (benchmark alpha, AGP if upgraded), release notes for the version in D10 | Every doc dated and true to the code [2 h] |
| R2 | The final candidate from the final commit: the owner runs the release script; I check certificate, ABI, size, version, source archive and checksums; install as Riff Preview (asked) and a last smoke run | G10 evidence [owner 5 min; 30 min] |
| R3 | A7: the owner decides whether to install the candidate over the main app (rc20 with real history). If yes: a backup export first (Privacy and data > Export, kept on the computer), the upgrade, then history, queue, downloads and saved songs checked present; privacy actions only row by row as the owner approves | PASS, or DEFERRED/DECLINED by the owner [30 min] |
| R4 | Restore and record (Q18B12): settings back, test packages the owner wants removed are removed (asked, listed), Riff Preview left on the final candidate or removed as the owner says, device state recorded; testing files removed from the phone | G12 evidence [20 min] |
| R5 | Closing gates (Q18C9, last): rebuild from the final commit; full collector runs, debug and release; the emulator lane; compare with the Q17 package; G01 to G12 each PASS, FAIL or BLOCKED with evidence; the 100-point scorecard; final-report.md completed as section 19 asks | Final report and scorecard committed [3 h] |
| R6 | Handoff: what is where (APKs, source archive, evidence), what the owner still owns (`C:\q18c6`, the signing key backups, any push or publication under D1, never done by me without a yes) | A short handoff section in final-report.md [20 min] |

## 5. Order

```
Q19D (any time; D8, D9, D10 early)
   |
Q19F  ->  Q19C1, C2, C3  ->  C4 (owner signs)  ->  C6
                                  |
                 Q19B1, B2  ->  B3, B4, B5, B6, B7   (one or two hand-overs)
                                  |                   B8, B9 overnight
                               Q19A1 to A5            (with the owner; any time after B2)
                                  |
                               Q19G  (if anything was found or measured)
                                  |
                               C5 final, R1  ->  R2  ->  R3 (owner's choice)  ->  R4  ->  R5  ->  R6
```

Suggested sittings:

1. Computer, no phone: Q19F, then C1 to C3 (about one working day).
2. The owner signs the candidate (C4, five minutes), then hands over the phone: B1, B2, then B3 to B7 (one long day,
   or two halves). B8 and B9 run on the nights after.
3. With the owner: A1 to A5 (about 2 hours; can follow sitting 2 the same day).
4. Q19G if needed (computer), its candidate and its phone rows.
5. Closing: R1 to R6 (about a day; the owner is needed for R2's signing and R3's decision).

## 6. Approval points (asked when reached, each on its own)

| Where | What is asked |
|---|---|
| Before every sub-phase | Permission to start it (the Q18 rule) |
| C2 | Booting the disposable emulators (approved for the Q18F lanes; asked again for Q19) |
| F5 | The AGP download (name, source, size), only after D8 |
| B2, R2 | Uninstalling the development-key Riff Preview (deletes its data); installing the candidate |
| B4, B5 | Turning airplane mode on and off; saving one card picture and removing it |
| B5 | Saving one redacted account-menu capture |
| B7 | Each like, playlist edit, save or download on the dummy account |
| B8, B9 | Running unplugged over Wi-Fi adb overnight; the owner starts B9's streaming |
| R3 | Installing over the main app; each privacy action |
| R4 | Each package removed from the phone |
| R6 | Any push or publication (only under D1) |

## 7. Evidence and records

- Phone and emulator evidence under `artifacts/quality/q19/<sub-phase>/` (local, not committed; anything showing an
  account's address or name is marked local only).
- Each sub-phase gets a ledger section: what ran, on which build (commit and signature), results, what was removed
  from the phone.
- Bugs go into q18-bugs.md (it stays the one bug log), numbered on from Q18-041.
- The case map and feature-requests.md are updated as checks pass.

## 8. Risks

| Risk | Plan |
|---|---|
| YouTube changes its responses during the stretch (live checks, counts, email) | Captured fixtures for every parser change; B7 rows that fail on a provider change are logged as provider changes, not Riff bugs, with the capture |
| Macrobenchmark still stops on the phone (Q18-023) | PERF rows BLOCKED with the evidence; JourneyCheck and the frame numbers from B7a stand in; G05 records it |
| The release key is unavailable on the day | C4 and R2 wait; development-key builds are never used for B2, C6 or R2 evidence |
| A fix in Q19G touches playback or downloads | The emulator lane on API 31, 33 and 36 and the affected B3 rows rerun before R2 |
| Battery numbers untrustworthy (charging over USB, vendor batterystats gaps) | B8 BLOCKED with the reason and rerun steps rather than estimated |
| Time: B8 and B9 need nights | They run after B2 on any nights the phone is free; R5 waits for them or records them BLOCKED by the owner's choice |
