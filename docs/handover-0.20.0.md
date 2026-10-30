# Handover: finishing 0.20.0 on another laptop (30 October 2026)

Written when the owner moved the release work to another laptop, mid-way through. A new Claude session should read this
first, then `CLAUDE.md`. The branch is `feat/next-features`, at the commit that added this file, and it is pushed to
`origin`. `main` was last fast-forwarded to the branch at 5262220 (the README refresh). It is behind by the 0.20.0
commits.

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
   1. Run the model only in Automix mode. Smart and Crossfade don't use the beat grid.
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
- **PR lane:** passed before 61b8922. Not run since the model went into the release.
- **Device lane** (47ca757): 64 run, 0 failed, 45 skipped (Riff Debug signed in; see above).
- **Benchmark lane:** stopped by the owner after 4 tests on 18593cc.
  - Passed: `queueOfTwoThousand`, `firstAudioLocalBundle`, `firstAudioUnthrottled`.
  - **Failed:** `playerAndQueueActions` with `"player_queue_bottom" did not appear`. The tag still exists
    (`AudioOutputControls.kt`). Suspect `reveal()` in `benchmark/.../Journeys.kt`: it swipes up 70 % → 30 % of the
    screen to bring the button into view, which in the redesigned player may do something else, or the player hadn't
    finished opening. Not investigated further.
- **A signed 0.20.0 was built at 8e87588** (`artifacts/release/riff-0.20.0.apk`, 10.3 MiB). It is stale: it has no
  slide-up fix and no model. Rebuild.

## Still to do, in order

1. Set up the laptop (next section).
2. ~~Ask the owner the open questions.~~ Done 31 Oct (see above); the "What's new" change is committed.
3. Look into `playerAndQueueActions`; fix the journey or the app.
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
- **Run lanes with the machine alone.** A second Gradle build or an emulator beside the PR lane makes timing tests fail.
- **Ask the owner first** for downloads, anything that publishes or pushes beyond the agreed release, writes to their
  YouTube Music account, and changes to the phone's system settings. Never enter the owner's passwords.
- **ONNX Runtime 1.31.1 telemetry:** keep its `TelemetryInitializer` provider removed (main manifest) and keep the
  `setTelemetry(false)` call in `OrtBeatLogits`. A model run is stopped after 30 s (stalls of minutes were seen).
- **Don't bundle:** SF Pro, BitChord's AGPL code, or open-unmix weights.
- **Commits** end with the `Co-Authored-By` line the session's instructions give.

## Phone state when work stopped

- Riff Debug (0.20.0-debug, built from 47ca757) is installed and signed in to the owner's account. Its test APK and
  the benchmark apps are installed too. The owner's own Riff (signed with the old key) is untouched.
- Thermal status 1–2 during the benchmarks.
