# Handover: finishing 0.20.0 on another laptop (30 October 2026)

Written when the owner moved the release work to another laptop, mid-way through. A new Claude session should read this
first, then `CLAUDE.md`. The branch is `feat/next-features`, at the commit that added this file, and it is pushed to
`origin`. `main` was last fast-forwarded to the branch at 5262220 (the README refresh). It is behind by the 0.20.0
commits. On 31 October the new laptop added 13 commits after 63f6dbf (the pushed handover); none of them is pushed yet.

## What the owner decided (all on 30 October)

- **Version:** 0.20.0, version code 34 ("revamping the app by a lot").
- **Publish it on GitHub.**
  - The repository `github.com/leovaldez2k22/riff` is public.
  - Claude installs the GitHub CLI, the owner signs it in (`gh auth login`), Claude prepares a **draft** release, and
    **the owner clicks Publish**. Nothing is published without that click.
- **Update check on.** The release feed is this repository's GitHub releases (in `gradle.properties`).
- **A new signing key.**
  - The old key's password could no longer be decrypted, and there was no backup to restore.
  - The new key is backed up by the owner (WinRAR `.rar`, in Google Drive and Gmail).
  - Consequence: **0.20.0 does not install over 0.10.0 or rc20.** Users uninstall first. Riff's backup in
    Documents/Riff Backup survives the uninstall. See `docs/release-signing.md`.
- **Beat This! small0 and ONNX Runtime (as published) in the release and Riff Preview,** not only in debug. Don't wait
  for a reduced runtime. The APK grows from about 11 MB to about 50 MB, and the ceiling is now 64 MiB.
- **The reduced ("cut-down") ONNX Runtime build: not now.** Only when the owner asks.
- **Approved:** clearing Riff Debug's data (`adb shell pm clear com.musicglass.personal.debug`) so the device lane's 45
  account tests can run. They skip themselves while Riff Debug is signed in to the owner's account. Not done yet.
- **Approved by listening:** the blends, the font and the redesigned player.
- **The owner's concern:** the app felt laggy in Riff Debug. The benchmarks and the owner's own check in Riff Preview
  must settle this before publishing.

## Open questions for the owner (asked again on 31 October)

1. **Reduce the beat model's load on the phone?** Deferred on 31 Oct: the owner is not sure yet how demanding Automix
   really is and wants to test it first, possibly after 0.20.0 is final. Don't make these changes until they ask. The
   four proposals:
   1. Run the model only in Automix mode. Smart and Crossfade don't use the beat grid. **Done 31 Oct, at the owner's
      request:** Smart, the silence skip and download analysis outside Automix use stage 2's light tracker. Their
      analyses are stored as version 1 (`AnalysisStore.write(..., beatModel = false)`), so Automix never takes their
      beats for the model's: it reads them as missing and analyses again with the model. A model analysis serves Smart
      too. Switching to Automix drops the in-memory analyses read without the model.
   2. Stop analysing at download time (`analyseDownload` runs it on every download, back to back). Analyse just ahead
      of playback instead: the current song's end and the next song's start. Optionally, analyse a batch of downloads
      only while charging.
   3. At Android's moderate thermal status or above, or in power saver, use stage 2's light tracker for that song.
   4. Run on one thread (`OrtBeatLogits`: `setIntraOpNumThreads(2)`) at background priority.
   - Each window takes 3–7 s of CPU on the owner's phone today.
   - Earlier on 30 Oct, heavy model runs pushed the phone to thermal status 3, which cut the glass to plain frost.
2. **"What's new" for 0.20.0.** Answered on 31 Oct: yes. Every 0.20.0 install is a fresh install (new key), so a fresh
   install of version code 34 now shows the release's cards with the two tips after them (`NEW_KEY_RELEASE` in
   `WhatsNew.kt`). Other fresh installs still get the tips alone.

## Done

| Commit | What |
|---|---|
| 61b8922 | Version 0.20.0 (34); update feed on; Encore's user agent names leovaldez2k22 |
| 8e87588, 73a4213, 55c207e, 85a136e | The new signing key and its records; the release script's zero-password bug fixed (PowerShell 5.1 has no `RandomNumberGenerator::Fill`); the backup script uses WinRAR when 7-Zip is missing, and the project's own JDK |
| 47ca757 | Device-lane bug fixed: sliding the mini player up drew a bright outline over its glass, and with solid controls an opaque fill. `PlayerFixesDeviceTest` passes on the phone |
| 18593cc | Model and runtime in the release (see the plan's row "20 Automix, stage 4"); the benchmarks' clean start closes the release "What's new" sheet (from code 34 a cleared install counts as an update) |

Lanes so far:
- **PR lane:** PASS_LOCAL on 07dac97 (31 Oct, after the owner's 31 Oct fixes), all nine gates.
- **Device lane** (47ca757): 64 run, 0 failed, 45 skipped (Riff Debug signed in; see above).
- **Benchmark lane:** stopped by the owner after 4 tests on 18593cc.
  - Passed: `queueOfTwoThousand`, `firstAudioLocalBundle`, `firstAudioUnthrottled`.
  - **Failed, then fixed (31 Oct):** `playerAndQueueActions` with `"player_queue_bottom" did not appear`. Seen in
    screenshots every second on the phone: `closePlayer()` returned when the navigation showed, while the player was
    still sliding away; the next `openPlayer()` tapped the mini player during the slide (lost) and found the closing
    player's Play button, so it went on with the player shut. `closePlayer()` now waits for `playback_toggle` to go.
    Also: the benchmark app's clean start could not find the restore offer's Not now (a fresh install finds the
    earlier backup folder): `FrostedAlertDialog` now names its tags for UI Automator. The journey passes 3 of 3 and
    `ActionBenchmark#playerAndQueueActions` completes.
  - **First action timings (31 Oct, thermal 2–3, p95, budget 150 ms):** player open 18.6, close 19.3, queue open
    51.2, close 30.8, play/pause 40.5: PASS. Skip read 4,845 ms, but that was the measurement: the fixture playlist
    had four songs, so Next on the last one changed nothing (a playlist gets no recommendations after it) and its
    section stayed open until the next Next closed it. Now `next()` times a skip only when there is a next entry,
    and the benchmark's playlist has 12 songs. Rerun (thermal 1), all 30 samples each, p95: player open 18.4, close
    18.9, queue open 72.1, close 40.0, play/pause 46.5, **skip 77.0 ms**: all PASS. Not a cause of the owner's lag.
    Offered, not done: greying out Next on a queue's last song.
  - The benchmark apps and Riff Debug were reinstalled with this laptop's debug key (owner's choice, 31 Oct).
- **A signed 0.20.0 was built at 8e87588** (`artifacts/release/riff-0.20.0.apk`, 10.3 MiB). It is stale: it has no
  slide-up fix and no model. Rebuild.

## Bugs the owner found on 31 October

1. **The Lyrics timing dialog's glass did not follow the lyrics.** Fixed and seen on the phone (31 Oct): with
   a probe drawn on the dialog, inside a dialog Compose's `LocalView` is the app's own view, so
   `frostedPopupBackground` took the dialog for part of the app window and never listened to the app's frames (the
   29 Oct fix, bug 11, had the same check). Now it always listens, and each app frame moves a state its drawing reads,
   so the dialog draws again with the page. Paused with nothing changing, it stays still (17 to 18 draws in 5 s).
   Riff Debug is now signed with this laptop's debug key: the owner chose to uninstall the old one (its sign-in and
   data went with it) rather than copy the old laptop's key.
2. **Volume spikes in Automix: the outgoing song jumps.** Fixed in code, not yet heard on the phone: the handover
   from the main player's copy to the tail's was one step (`main.volume = 0f; tail.volume = base`), and each write
   lands on its own player's thread, so both copies could play for a moment (+6 dB), likelier under the beat model's
   CPU load. Now six linear steps over about 0.1 s (`handoffLevels` in `Crossfade.kt`). Ask the owner to listen.
3. **Every pop-out in the owner's reference look.** Done: `RiffMenu` tokens in `DesignTokens.kt`; the shared
   frosted background, `SongActionMenu`/`SongActionItem`, `PlayerActionMenu` and `FrostedAlertDialog`; every plain
   `AlertDialog` moved onto `FrostedAlertDialog`. Content unchanged: the reference's Like/Dislike/Share row, its
   groups and its second lines would change what menus offer, so they were not copied. SF Pro is not bundled; Inter.
   Bottom sheets (What's new, Cast picker) were left as they are. Not yet seen on the phone.

## Automix research report: the seven quick fixes (31 October)

The owner added `docs/Riff-AutoMix-Research-Report-2026-10-31.pdf` and asked for its Phase 1 (section 7.1), all seven
items. All are in code with unit tests (`AutomixPhaseOneTest`); none is heard or measured on the phone yet, and none of
the report's "done when" measures (the 50-song set, the 30-pair listening test) has been run.

1. **Bass hole and sag** (`deckLevel`, `aboveBassSection`): each player plays at the louder of its body (the
   equal-power fade) and its bass (the swap, in amplitude); a low shelf and a new high shelf at 150 Hz cut the other
   part down. The low end stays within 3 dB through the swap (it fell about 10 dB before).
2. **Phrase-anchored start** (`phrasePhase`): the four-bar phase is scored from bar-to-bar changes of the bass and
   the level, with the outro's start and the last downbeat as anchors; failing a clear phase, counted back from the
   outro. Differs from the report: no chroma or novelty (Riff computes neither yet), so the weights are 0.6 bass and
   0.4 level; only four-bar phrases. Needs downbeat confidence 0.5; below it, the nearest downbeat as before.
3. **Swap on the downbeat** (`swapOnDownbeat`): on the outgoing song's downbeat nearest the blend's middle, one beat
   long; hard when both songs' downbeat confidence is 0.7 or more. Automix only; Smart keeps 1 s at the midpoint.
4. **Lyrics as a vocal prior** (`vocalSpans`, `avoidVocals`): more than one beat of both singing moves the start to
   another downbeat, then later (shorter). Lyrics come from a download's own, or those the lyrics panel loaded in
   this session (`LyricsRepository.heard`); nothing is fetched for Automix, so the next song's lyrics are often
   unknown. Lyrics that don't fit the sound by 1 s are ignored. Smart and Automix both.
5. **Echo-out** (`echoOut`, `EchoProcessor` on the tail player): folded tempo gap over 8 %, both grids sure. One-beat
   delay, feedback 0.55, 200 Hz high-pass and 6 kHz low-pass in the loop; the send rises over the bar before the cut,
   the song stops at the cut and the next one starts on its downbeat there; 3 s of ring. The stage works in song time
   from Media3's `positionOffsetUs`, so the cut is sample-placed. Differs from the report: the cut is the phrase
   downbeat nearest where stage 1's blend would cross (not the last one), avoiding a sung line.
6. **Predictive loudness trim** (`loudnessTrim`): from both envelopes plus normalization gains, −3 to +1.5 dB,
   smoothed a second either way. A player's volume can't pass 1, so at full volume only the downward trim applies.
7. **64 s tail for songs on the phone** (`ANALYSIS_LOCAL_TAIL_MS`): streams keep 45 s. Stored 45 s tails of local
   songs are read again once. This is about 40 % more decoding and beat-model time on those songs, which touches the
   owner's open question on the model's load: measure it in the benchmark lane with the rest.

The event log has one `automix_plan` line per blend (on beat, phrase, swap, echo, vocal beats, trim range) for the
owner's listening checks.

## Still to do, in order

1. Set up the laptop (next section).
2. ~~Ask the owner the open questions.~~ Done 31 Oct (see above); the "What's new" change is committed.
3. Look into `playerAndQueueActions`; fix the journey or the app.
   - New on 31 Oct, for the owner on the phone: listen to Automix (the seven fixes and the volume handover above);
     look at the pop-outs; watch a widget's progress through a song, signed in; songs play signed in (checked);
     signed out they don't (see "Signed-out songs don't play").
4. PR lane: `python scripts/quality/verify.py --lane pr`.
5. Clear Riff Debug (approved), then the device lane:
   `python scripts/quality/verify.py --lane device --allow-install --serial 10BE1D18C0000E5 --tier R`.
6. Benchmark lane: `... --lane benchmark --allow-install --serial 10BE1D18C0000E5 --tier R`.
   - Read the results against `docs/quality/performance.md`.
   - Measure the cost of the model too: CPU and thermal status while Riff analyses songs with Automix on.
7. Build and sign:
   - Run `scripts\build-release-candidate.ps1 -Preview` (a clean tree is required).
   - Check that the certificate is `e45ac43b…` and that `release-content` passes.
   - Regenerate the licence audit: the script writes `artifacts/release/dependencies.txt`, then run
     `scripts/audit_dependency_licenses.py`. Update `docs/dependency-license-inventory.md` with ONNX Runtime's count.
8. Install **Riff Preview** (`com.musicglass.personal.preview`) on the phone for the owner's lag check.
   - Check the package name with aapt2 first.
   - Never install over `com.musicglass.personal`.
   - Wait for the owner's verdict, and fix any lag first.
9. Emulator lane on API 31, 33 and 36. Packaging lane.
   - The upgrade check against 0.10.0 is expected to fail, since the key is new. Record that, and don't uninstall to
     get past it.
10. Release notes:
    - Write `docs/release-notes-0.20.0.md` from the changelog's Unreleased section, then turn that section into 0.20.0.
    - Say: uninstall first (new key, after a backup); Cast and Android Auto are untested; the APK is about 50 MB; the
      Automix beat model; the update check.
    - Update the README's Availability section once published.
11. GitHub:
    - Install the GitHub CLI (ask the owner before the download, naming file and size). The owner runs `gh auth login`.
    - Merge the branch into `main` (fast-forward) and push.
    - Tag `v0.20.0`.
    - `gh release create v0.20.0 --draft` with the notes, the APK and the source archive (`scripts/package-source.ps1`).
    - The owner clicks Publish.

## Also done on 31 October (afternoon)

All passed the PR lane and are committed; the phone checks are noted for each.

- **Glass defaults** (e266fdd): glass at 125 % and frosted glass at 25 % on a phone where they were never set
  (`GLASS_INTENSITY_DEFAULT`, `FROSTED_INTENSITY_DEFAULT`); a choice made in Settings is kept.
- **Widgets in the glass design** (1424fd1, ff6b0ef), the seven existing ones redesigned, none added (owner):
  - A widget cannot blur what is under it, so the glass is the cover, shrunk and grown back and darkened
    (`glassBackdrop`), under a sheen and a hairline rim, clipped by `clipToOutline`.
  - Compact and Wide are pills with a round cover; Artwork is the cover with a glass bar; Player and Large have Like,
    Previous, Play, Next and Shuffle; Large shows elapsed and total time and the next two songs (a tap plays one);
    Circle is a round cover in a progress ring; Encore has the last seven days as bars.
  - Like and Shuffle use the lock screen's actions (`pressMediaButton`); Like writes to the owner's YouTube Music
    account like the lock screen's does. It is unseen, keeping its place, when the song cannot be liked.
  - Progress: sent at seeks and song changes, and every 10 s while playing with the screen on, as partial updates
    without the cover. A live station has no length, so its bar stays empty (its replay window was showing as full).
  - Seen on the phone: all six player widgets, their clipping and blur, the cover and Pause following a station, and
    Pause from a widget. Not seen: the bar and elapsed time moving through a song, Like and Shuffle, and Encore (not
    placed). Off-screen renders cannot show the clip (corners look square there); the launcher does.
- **Radio starts again** (0b8c083). A tap on a station did nothing. The live manifest's n challenge took 56–58 s:
  the library's `processNParameter` tries its old solver before EJS, and on YouTube's player `11a45ffb` (no faraday
  config on GitHub) that ran about 56 s, against a 60 s deadline. Songs were fine: `processFormats` goes to EJS
  directly. The challenge is now sent as a format's address (`hlsChallengeFormat`) through `processFormats`, with
  `processNParameter` as the fallback. Measured on the phone: solved in 2.1 s, playing about 7 s after the tap.
  `manifest_challenge_solved ms=` in the event log records the time. The owner's own Riff keeps the bug until 0.20.0.
  - How it was found: the solver's own log (`solver_events`) passes only known steps; a temporary `Log.w` of every
    library message showed the 56 s gap after "GitHub preprocessed-player missing config". This vivo drops `Log.d`.
- **Signed-out songs don't play (found 31 Oct, deferred by the owner):** signed out, every song resolves
  (`transport=DIRECT po_token=false`, cipher done by EJS in about 1.5 s) and then googlevideo answers 403 on the
  audio (`http_rejected code=403 kind=segment`); after the retries and the other clients, `recovery_stop`. Signed in,
  songs play (checked on Riff Debug: "You Right", no 403); the owner's own Riff plays too. Signed-out playback worked
  on 22 Oct (case map, Q19B B7) and nothing changed on 31 Oct touches song resolution, so likely a YouTube change
  (signed-out clients wanting a proof-of-origin token; also new player `11a45ffb`). Fix it or say so in the release
  notes.

## Setting up the new laptop

- **Windows only.** `.toolchain` holds Windows builds of the JDK (21.0.12.1), the Android SDK and Gradle. Every shell
  sets:
  `JAVA_HOME=<repo>/.toolchain/jdk-21.0.12.1+1`, `ANDROID_HOME=<repo>/.toolchain/sdk`,
  `GRADLE_USER_HOME=<repo>/.toolchain/gradle-user-home`.
- **Git Bash and adb:** set `MSYS_NO_PATHCONV=1` only for adb commands, never for `./gradlew`; with it, Gradle misses
  the SDK. adb is `.toolchain/sdk/platform-tools/adb.exe`.
- **Signing password.** `.toolchain/signing/password.dpapi` only opens for the Windows user that made it, so it will not
  open on this laptop.
  1. The owner extracts `password.txt` from their `riff-signing-key-20261030.rar` into `.toolchain/signing/` and
     moves `password.dpapi` aside. Claude never handles the password.
  2. Check that the key opens: `scripts\backup-signing-key.ps1 -VerifyDir .toolchain\signing`.
  3. Re-encrypt it for this user:
     `ConvertTo-SecureString (Get-Content password.txt -Raw).Trim() -AsPlainText -Force | ConvertFrom-SecureString`
     into `password.dpapi`.
  4. Check it again, then delete `password.txt`.
- **The phone** (vivo I2217, serial `10BE1D18C0000E5`, tier R): the owner allows USB debugging for the new laptop.
- **GitHub:** the owner signs in to git (push) and to the GitHub CLI there.
- **Python 3.10+** for the lanes. WinRAR or 7-Zip for the key backup.

## Rules carried over (from `CLAUDE.md` and the previous session's notes)

- **Installing on the owner's phone:**
  - Only `com.musicglass.personal.debug` (debug built with `-PriffSideBySide=true`), the benchmark apps and Riff
    Preview. Never anything over the owner's Riff.
  - Check every APK's package with aapt2 first.
  - Rebuild the debug APK fresh before installing it. The lanes do not refresh `app-debug.apk`.
  - A plain `assembleDebug` has the package `com.musicglass.personal`, the owner's own app: it must never be
    installed. Seen 31 Oct; the aapt2 check caught it.
- **Run lanes with the machine alone.** A second Gradle build or an emulator beside the PR lane makes timing tests fail.
- **Ask the owner first** for downloads, anything that publishes or pushes beyond the agreed release, writes to their
  YouTube Music account, and changes to the phone's system settings. Never enter the owner's passwords.
- **ONNX Runtime 1.31.1 telemetry:** keep its `TelemetryInitializer` provider removed (main manifest) and keep the
  `setTelemetry(false)` call in `OrtBeatLogits`. A model run is stopped after 30 s (stalls of minutes were seen).
- **Don't bundle:** SF Pro, BitChord's AGPL code, or open-unmix weights.
- **Commits** end with the `Co-Authored-By` line the session's instructions give.

## Phone state when work stopped

- Riff Debug (0.20.0-debug, built from ff6b0ef's code on 31 Oct) is installed and signed in to the owner's account
  again (31 Oct). Its six player widgets are on the launcher's pages. The benchmark apps are installed too. The
  owner's own Riff (signed with the old key) is untouched.
- Thermal status 1–2 during the benchmarks.
