# Q18 split: Q18A, Q18B, Q18C

Written 15 October 2026 at the owner's request, before Q18 starts. Q18 (plan section 18, gates G01-G12, and the section 19 handoff) is divided by what each piece needs:

- **Q18A**: needs the phone **and** the owner at it (to look, listen, touch, sign in or decide).
- **Q18B**: needs the phone, not the owner. The owner hands it over plugged in, unlocked, screen kept on and charged, then leaves it. Everything is driven over USB.
- **Q18C**: needs neither. Runs on this computer and the disposable emulator.

The three can run in any order and can be interleaved. Two exceptions, both stated where they apply: B1 needs C1 first, and C9 (the closing gates) runs last, after A and B, because the gates are judged on the exact final candidate.

Standing rules that apply to every sub-phase:
- Installing anything on the phone needs the owner's confirmation of the phone and the build, each time. The main app (`com.musicglass.personal`) is never uninstalled, replaced or read. Phone work uses separate packages: Riff Preview (`.preview`), the benchmark variant (`.benchmark`) and the side-by-side debug variant from C1.
- Reading the phone's diagnostics or logs needs the owner's OK. A Q18B session asks once at hand-over for that session.
- A device setting changed for a test (for example aeroplane mode, Wi-Fi, animations or TalkBack) is restored afterwards and recorded.
- Personal YouTube Music accounts are never used for checks. Live provider checks use a dedicated test account. Changes to that account need approval.
- Nothing is pushed, published or deleted without the owner's explicit decision.
- Missing hardware, accounts or network means BLOCKED with rerun steps, never an invented result.

Finding made while writing this split: the device lane (`verify.py --lane device`) installs the debug build as `com.musicglass.personal`, the main app's package. On the owner's phone that install fails on the certificate. The lane refuses to uninstall, so nothing would be lost, but the lane cannot run there. C1 fixes this before B1. The `com.musicglass.personal.test` package already on the phone is an older instrumentation APK aimed at the main app's package. It is left alone unless the owner asks.

## Q18A: phone and the owner (rough time per item in brackets)

| # | Sub-phase | What happens | Gates | Plan source |
|---|---|---|---|---|
| A1 | Player and lyrics, by eye | Q17A fixes (opening and closing morph, top of the player over a white cover, no Play video, rounded shuffle and repeat, lyrics page taps); lyrics letter pop and shimmer on a real word-timed song; held-word glow with Remove animations on; player menu on a downloaded song played from Home | G04, G06 | Q16A, Q16B, Q17A (20 min) |
| A2 | Listening | Crossfade at 1, 6 and 12 s; boost off, 200 % and 300 %; Normalize volume; on speaker, wired and Bluetooth. Pause, seek, skip, crossfade off and sleep timer during a blend; repeat-one near the end. Listen for clicks, doubled passages and level jumps | G04 | Q07, Q08A (40 min) |
| A3 | Audio focus and routes | During a blend: a real incoming call, a navigation or notification sound, unplugging headphones, Bluetooth off. Media buttons from Bluetooth and, if available, a car head unit | G04, G07 | Q07, Q15 (20 min) |
| A4 | TalkBack walk-through | UX01-UX10 with TalkBack on, recorded on screen; owner judges what is spoken. TalkBack turned off again afterwards | G06 | Q13 (40 min) |
| A5 | Keyboard walk-through | The five steps in accessibility.md with a physical or Bluetooth keyboard (only if one is available, otherwise BLOCKED) | G06 | Q13 (15 min) |
| A6 | Test account sign-in | The owner signs a dedicated test account (nothing personal on it) into Riff Preview. I never type passwords. If two test accounts exist: the two-account switch and identifier check (ACC04); otherwise it is BLOCKED | G03 | Q03, Q04, Q10, Q15 (15 min) |
| A7 | Owner decisions on the phone | Upgrade over the real rc20 main app with real history. This replaces the main app with the candidate and migrates its data, so it only happens if the owner decides to do it now (may be declined: stays BLOCKED). Privacy actions on data that may be lost (clear history, delete downloads); approved row by row or declined | G03, G10 | Q04, Q05 (30 min if done) |
| A8 | Feel checks | First song after a break and after a cold start, as the owner experiences it; the cold-start logo against the 1.2 s budget (decision pending, see C) | G05 | Q14, Q17A (10 min) |

Not possible with one phone: moving an export file between two phones (Q05A) stays BLOCKED unless a second phone is lent.

## Q18B: phone only (the owner hands it over and leaves it)

At hand-over the owner confirms the phone and each build to install, gives the OK to read diagnostics for the session, and says whether the phone may use Wi-Fi or mobile data. Sessions that need a signed-in test account come after A6.

| # | Sub-phase | What happens | Gates | Plan source |
|---|---|---|---|---|
| B1 | Device lane | `verify.py --lane device --tier R` with the side-by-side debug variant from C1: every instrumented test on the phone (Android 16 = API 36, physical). Pull and review the q16a, q16b and q17a screenshots and texts | G02, G05 | Q16, Q16A, Q16B, Q17A |
| B2 | Release candidate on the phone | Final signed Riff Preview: install, launch, smoke run driven over adb (Home, search, play a downloaded song, queue, lyrics, settings); screenshots; check version and certificate on the device | G10 | Q10, Q17 |
| B3 | Playback robustness | Rapid taps on three albums (only the last plays), pause while opening, duplicate queue entries moved and removed, force-stop while playing then reopen (queue back paused near its position); Wi-Fi off then on, and aeroplane mode for 60 s (resume without a tap; with pause pressed, nothing resumes) | G04 | Q06 (PLY03, PLY04) |
| B4 | Stream helper under stress | Stress build: attestation fault during playback (one recovery line, intent kept); kill the solver during a lookup (at most three attempts). Time the first song with the diagnostics' stream_prewarm and selected_stream_ready numbers, cold and after 10 minutes away (Q17A speed fix) | G04, G05 | Q06 (PLY05, PLY06), Q17A |
| B5 | Downloads under interruption | Start a large download; force-stop, and separately reboot, during Downloading, Checking audio and Saving artwork and lyrics; reopen and check states and the reconcile line. A reboot interrupts the phone briefly and is asked first | G03, G04 | Q09 |
| B6 | Export and import | Settings > Privacy and data: export through the system picker, check the file; import and preview; cancel an export mid-way; confirm no storage permission was asked. Picker driven over adb | G03 | Q05A |
| B7 | Performance budgets | Benchmark variant and its test APK (separate packages). Aeroplane mode, animations on, charged and cool: JourneyCheck, Macrobenchmark (cold start, player and queue actions, frames at 60 Hz and the phone's high refresh, 2,000-entry queue, local and controlled online first audio), with and without the Baseline Profile. `check_performance.py summary --tier R`; traces kept | G05 | Q14 |
| B8 | Soaks and memory | 2 h offline soak (downloaded songs); 4 h live soak if a test account and network are allowed. Watch for crashes, ANRs and sustained memory growth | G09 | Q14 |
| B9 | Battery | Three 2-hour playback runs, unplugged (adb over Wi-Fi) with the screen off, measured from batterystats. If no trustworthy discharge measurement is possible, BLOCKED | G05, G09 | Q14 |
| B10 | Live provider checks (after A6) | On the test account: search (six kinds, Load more), Home and second page, album, playlists over 100 and 2,000 songs, owned and other people's playlists, expired session, the canary's stream phase, a live loudness value, a real download. Likes, playlist edits and saves change the test account; each is asked first | G04, G07 | Q08A, Q10, Q10A |
| B11 | Security on the phone | Foreign media controller (a throwaway app) cannot edit the queue but can play and pause; content access in the sign-in browser (after A6) | G07 | Q15 |
| B12 | Restore and record | Restore every changed setting, remove the test-only packages the owner wants removed, leave Riff Preview on the final candidate, record device state | G12 | 19.8 |

## Q18C: neither (this computer and the emulator)

| # | Sub-phase | What happens | Gates | Plan source |
|---|---|---|---|---|
| C1 | Side-by-side device builds | A debug variant with its own package (for example `com.musicglass.personal.debug`) and a test APK aimed at it, so the device lane installs beside the main app; verify.py follows it. Proven on the emulator. **Needed before B1** | G12 | Q16 |
| C2 | API 33 and 36 emulators | Download the two system images (needs network; asked first), make AVDs, run the emulator lane on each with the network off. Also the Android 14 and 15 foreground-service limits for downloads | G05 | Q09, Q16 |
| C3 | Foreign controller on the emulator | Throwaway controller app on the emulator: removeMediaItem refused, play and pause allowed (the phone half is B11) | G07 | Q15 |
| C4 | Lint and static triage | Every lint warning (85) and hint (3) triaged with a reason, no blanket suppression; R8 Kotlin-metadata warnings explained | G08 | Q11, Q17 |
| C5 | Case map and statuses | Every required case of every package mapped to a test, run or BLOCKED entry; no unknown status anywhere in the ledger | G01 | Q00-Q17A |
| C6 | Clean-checkout build | Fresh clone with only the SDK and JDK, offline; the pull-request lane and the packaging lane (source archive, checksums, certificate, upgrade from rc20 on the emulator) | G02, G10 | Q16, Q17 |
| C7 | Security and secrets | Secret and test-entry scan of the source archive and the APK; recheck the two medium items; Linux dependency checksums (only possible with a Linux machine or a pushed CI run, otherwise BLOCKED) | G07 | Q15 |
| C8 | Docs and inventory | README, features, privacy, accessibility, performance, provider compatibility, dependency inventory and release notes checked against the final code; known limitations listed | G11 | Q17 |
| C9 | Closing gates (**runs last**) | Rebuild the candidate from the final commit; full collector runs, debug and release; emulator lane; compare with the Q17 package; G01-G12 each PASS, FAIL or BLOCKED with evidence; the 100-point scorecard; final-report.md completed as section 19 asks; handoff | all | 18, 19 |

## Owner decisions (no phone; any time, in chat)

These need the owner, not the phone, so they fit none of the three. They do not block A, B or C1-C8. C9 records whatever has been decided by then:
- Publishing: whether, where and when (personal use only, source only, or source plus signed APKs).
- ML Kit translation under Google's terms in a GPL-3.0 app: qualified review, or remove translation.
- The four reference images in `references/`: keep in the source archive or not.
- The jsDelivr pre-processed player script in the solver: keep or turn off.
- The 1.15 s start-up logo against the 1.2 s cold-start budget.
- Darker scrims and black text on pink buttons, chosen by arithmetic rather than a design review.
- "Remove download" also shown while a download is queued or running (Q16B): keep or narrow.

## Order I would suggest

C1 first (it unblocks B1). Then the rest of C while you are away from the phone. One sitting for A1-A6 and A8 (about 2.5 hours, can be split). A7 only if you choose. B runs whenever you can leave the phone with me: B1-B7 in one day, B8 and B9 overnight or across days. C9 last. If a fix is needed on the way (as in Q17A), it becomes its own lettered package, and the affected evidence is rerun before C9.

## Q18F: the fix package, and the tests that check each fix (added 19 October 2026)

The owner decided on 19 October 2026, while working by remote control with no phone at hand, to start the fix
package now instead of after all testing. B8's live soak, B9 and B10 move to after it and run once, on the fixed
build, rather than twice. Bugs that B8, B9 and B10 find become a second, smaller fix round.

Q18F fixes the open bugs in docs/quality/q18-bugs.md: High first (Q18-021, Q18-009, Q18-010), then Medium, then
Low. Q18-013 (the sign-in overhaul) waits for the owner's description of how the page should look and work; Q18-009
is fixed on its own before it. Every fix gets its own test in Q18F (C10 below); the phone checks follow in B13 and
A9. Each bug logged from now on names its C10, B13 and A9 check when it is logged.

### C10: fix tests on this computer (part of Q18F; JVM, Robolectric and the API 31, 33 and 36 emulators)

| Bug | Test that shows the fix |
|---|---|
| Q18-001 | The mini-player end of the morph: JVM, the flying copies sit on the mini player's own elements through the cross-fade window; PlayerFixesDeviceTest draws p = 0.005, 0.01, 0.02 and 0.035 and finds one title and one cover in each |
| Q18-002, Q18-008 | Robolectric pictures of the player and an album page over a pure white cover: no gradient or patch pixels behind the close button, heading and back arrow; the shadowed icon and text stand out from white by a recorded margin; PlayerFixesTest's shade-depth test replaced |
| Q18-003 | LyricsRepository: a word-timed source that answers after the line-timed one is still preferred; a cached line-timed result is asked again for word timing (with a limit), and the word-timed answer replaces it |
| Q18-004 | heldLyricWords with the "Dancing queen" timing from A1: "queen" is held; the median no longer includes the word itself; earlier held-word cases unchanged |
| Q18-005 | Load-control settings (back buffer) in a JVM test; an emulator test seeks back and ahead in a fixture song and in a download and records the time to playing again |
| Q18-009 | The sign-in host rule (WebBoundariesTest, SEC01): Google's country domains (google.co.in, google.com.br, google.co.uk) allowed; lookalikes (google.co.in.example.com, googlex.com, google.evil) still refused |
| Q18-010 | ProviderSessionManager: cookies of a different account in the shared store are never adopted under the committed account (a dataSyncId or authUser mismatch makes the account unverified and asks); an abandoned Change account leaves the live session and the cookie store in agreement |
| Q18-011 | A sweep over every screen's semantics: no label repeats a word ("Back to Back"); every back arrow names a destination or says "Back" |
| Q18-012 | An account change clears the held search (query, results, filter, sort); AccountSwitchDeviceTest gains a case: search under A, switch to B, B's Search is empty and B's recent searches do not gain A's query |
| Q18-013 | Written with the new design: SignInFlowTest for every stage, cancel and slow network; no state needs a hidden button to finish |
| Q18-014 | Home feed store: per owner, dropped on an account change, shown at once on start, refreshed in the background, swapped without a jump; placeholders only when nothing is stored |
| Q18-015 | No fix test before it is broken down; the timing is checked in B13 |
| Q18-016 | The pr lane's static gate passes |
| Q18-017 | networkConditions with a fake VPN whose underlying networks are gone reads offline; with a validated underlying network, online |
| Q18-018 | The queue answers added, moved or unchanged; the notice says "Already in the queue" when nothing was added |
| Q18-019 | A restored queue that is not prepared shows the saved position and the saved duration; a seek before Play moves the saved position |
| Q18-020 | RecoveryDeviceTest: the media session reports buffering (not ERROR) while waiting for a connection, PAUSED once paused, and stays paused when the connection returns |
| Q18-021 | Reconcile that requeues a hand-chosen song starts the download again (service started or MANUAL job scheduled afresh); DownloadLifecycleDeviceTest: kill during a fixture download, reopen, the song finishes without another request |
| Q18-022 | A solver that stays down reaches the player as SolverUnavailable: the "Online playback resolver unavailable" text, class solver, Retry only |
| Q18-023 | Macrobenchmark: a new androidx.benchmark or runner change; JourneyCheck runs to the end on the API 31 emulator first (as far as an emulator allows) |
| Q18-024 | Lint: the widget string, paddingEnd and int-state warnings gone; the pseudo-locale sweep reaches the widgets; the shrunk build prints no Kotlin-metadata warning |
| Q18-025 | Emulator lane on API 36, two cold boots: the network gate waits until mobile data and Wi-Fi read 0 and no default network is up |
| Q18-026 | The Android 15 daily limit, on the API 36 emulator with the shortened timeout: download_foreground_timeout logged, service stopped, the job holds the rest |
| Q18-027 | The sign-in browser's settings: allowContentAccess false |
| Q18-028 | The blocked-host event names the host's class (Google, other) without the address |
| Q18-029 | Per the decision in Q18F: the hook covers the SABR reads that happen, with an emulator fixture, or PLY05 is retired as a device case with the reason |
| Q18-030 | No fix test; the render-thread work found by the trace is changed and FrameBenchmark decides (B7b) |
| Q18-031 | Compose: the active download's phase is a polite live region keyed on the phase; a percentage change sends nothing, a phase change one announcement |
| Q18-032 | Widget update: the toggle is "Play" when paused and "Pause" when playing |
| Q18-033 | The four Settings sliders have a name and a value with its unit ("Crossfade length", "3 seconds"); a sweep finds no unnamed slider anywhere |
| Q18-034 | A fake translator that fails, one cancelled mid-way, and a model download refused offline: the timed lines, their timing and the karaoke stay; nothing is cached as translated |

C10 ends with the full pr lane, the emulator lane on API 31, 33 and 36, and a new Riff Preview candidate built for
the phone (B2).

### B13: fix checks on the phone, without the owner (after Q18F, on the new Riff Preview)

| Bug | Check |
|---|---|
| Q18-001 | screenrecord of opening and closing the player five times; frames at the mini-player end show one title and one cover |
| Q18-003 | "Delicate" (Taylor Swift) reads Word synced on the lyrics page |
| Q18-005 | Seeks back and ahead on a streamed and a downloaded song, timed from the media session: back in a download under 0.5 s, in a stream under 1.5 s |
| Q18-012 | With the dummy account only: search, sign out to signed-out use and back; nothing crosses (the two-account part is A9) |
| Q18-014 | Three cold starts recorded: real Home content when the logo ends, no placeholder rows |
| Q18-015 | First song cold and after 10 minutes away, with stream_prewarm and selected_stream_ready (as B4) |
| Q18-017 | B1's three offline tests with the owner's VPN connected (the owner connects it at hand-over) |
| Q18-018 | Add to queue on a queued song says "Already in the queue" |
| Q18-019 | B3 step 4 again: after a force-stop the player shows the saved time and length before Play |
| Q18-020 | B3 step 6 again with the media session state logged: no ERROR through wait, pause and reconnect |
| Q18-021 | B5 again: kill while Downloading and while Saving artwork and lyrics, reopen, the song finishes by itself; and the reboot run |
| Q18-022 | B4's solver kept down again: the resolver-unavailable message |
| Q18-023, Q18-030 | B7b: Macrobenchmark's full run, frames at 60 Hz (the owner sets Standard at hand-over) and 120 Hz |
| Q18-029 | PLY05 on the phone, if the hook was moved |
| Q18-031 | The A4 method: accessibility events recorded while an album downloads; one announcement per phase change, none per percentage |
| Q18-032 | Widget tree read paused and playing: "Play" and "Pause" |
| Q18-033 | Settings tree: the four sliders named with their values |
| Q18-034 | A song translated; Wi-Fi and mobile data off during the model download; Back while translating; the timed lyrics stay |
| C5 additions | Largest font size and landscape on Home, player, queue, lyrics and Settings (A11Y02; settings restored); lock-screen media controls read from the session (UX10) |

Moved here from before the fix package, on the same build: B10 (live checks, likes and edits asked first), B9
(battery), and B8's 4-hour live soak (the owner starts the streaming).

### A9: fix checks with the owner (after Q18F)

| Bug | Check |
|---|---|
| Q18-001 | Open and close the player by eye: no doubled title or see-through mini player |
| Q18-002, Q18-008 | The player over a white cover and an album page: no dark fade or patch; the x, NOW PLAYING and the back arrow readable |
| Q18-004 | "Dancing Queen" at 1:36: "queen" glows and grows |
| Q18-005 | Seek back and ahead by ear: no long silence |
| Q18-009, Q18-010, Q18-013 | Sign in through the new page with the dummy account; Change account to the owner's account and back (UX07, ACC02, ACC04 at swapped chooser positions); an abandoned change leaves Riff and Google's list in agreement |
| Q18-011, Q18-031, Q18-033 | With TalkBack: the Settings and album back buttons, one download's phases, the four sliders |
| Q18-012 | During UX07: a search under one account is not on the other's Search tab or in its recent searches |
| Q18-014, Q18-015 | A8 again: cold start feel and first song |
| Q18-027 | The profile-picture upload from B11 once on the final candidate |
| Q18-028 | If a "Google's page tried to open" message appears anywhere, the owner says what it named |
| C5 addition | Lock-screen controls by hand: play, pause, next from the lock screen (UX10) |

### New order from 19 October 2026

Q18F with C10 now (no phone needed) -> B2 (new candidate on the phone) -> B13, A9, B10, B7b, B9 and B8's live soak
(any order the phone allows) -> a second fix round for what they find, with its own C10, B13 and A9 lines -> C5
second pass, C6 leftovers, C8 -> A7 (the owner upgrades the main app) -> B12 -> C9.
