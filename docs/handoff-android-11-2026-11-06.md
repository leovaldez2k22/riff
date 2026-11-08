# Handoff: Android 11 support (6 November 2026)

Goal: make Riff install and run on Android 11 (API 30). It currently needs Android 12 (API 31). The owner asked to
experiment on a branch; nothing is committed, pushed or released.

## Where it stands

- **Branch:** `android-11`, made from `origin/main` at 2409a1d (0.21.0 plus docs). Local `main` is stale (behind
  `origin/main`), so don't branch from it.
- **All changes are uncommitted** in the working tree of `C:\Volume D\Riff`. If you continue on another machine, the
  owner must commit (and push) them first; until then they exist only on this laptop.
- The untracked `docs/cleanup-list-2026-11-06.md`, `output/` and `research_notes/` are the owner's and are not part of
  this work.

## What changed (first session: 10 files, +45/-16; the second session's Cast fix is below)

| File | Change |
|---|---|
| `app/build.gradle.kts` | `minSdk` 31 → 30 (line 20, and the quality-report map near line 303) |
| `benchmark/build.gradle.kts` | `minSdk` 31 → 30 |
| `RiffGlass.kt` | New `GLASS_CAN_BLUR` (`SDK_INT >= 31`, `@ChecksSdkIntAtLeast`). `riffGlass` reads the page only when it is true; otherwise it draws its existing "nothing beneath" tinted fill. `glassEffect` returns null below 31, so `RenderEffect` (API 31) is never touched. |
| `FrostedPopup.kt` | `frostedPopupBackground` drops the backdrop below 31, so popups use their opaque tint (the Q18-046 path) and never record the page. |
| `PlatformCompatibility.kt` | New `openLinksByDefaultAction(sdk)` and `openLinkSettings(context)`: `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` (API 31) on 12+, `ACTION_APPLICATION_DETAILS_SETTINGS` (App info, which holds "Open by default") on 11. |
| `SettingsSections.kt`, `WhatsNew.kt` | Both "Open links in Riff" buttons now call the shared `openLinkSettings`. Before, on Android 11 they would have silently done nothing. |
| `PlatformCompatibilityTest.kt` | Tests for the action switch and `audioLibraryPermission(30)`. |
| `README.md` | Requirements line now says Android 11 (API 30) or later, and that the glass is a tinted fill there. |
| `docs/feasibility-…-2026-10-27.md` | Dated note under the glass section's Android 12 bullet (CLAUDE.md: update the plan when the code changes it). |

Past release notes still say "Android 12 or later" on purpose: they describe what shipped.

## What is verified

- `:app:lintRelease` with minSdk 30: **no `NewApi` findings.** The only API-level findings were the two
  `InlinedApi` uses of `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`, now fixed. Two `ObsoleteSdkInt` warnings remain
  (`SDK_INT >= 29` in `MediaKeyHold.kt`, the `mipmap-anydpi-v26` folder): harmless, left alone.
- `python scripts/quality/verify.py --lane pr`: **PASS_LOCAL**, all nine checks (script-tests, static, build,
  artifactIdentity, jvm, lint, release-content, apk, solver-seam). It ran after every code change listed above.

## What is not done

1. **Nothing has run on Android 11 yet.** The debug build was being rebuilt for minSdk 30 when the work stopped (the
   `app-debug.apk` on disk is from the morning and still says minSdk 31; it will refuse to install on API 30).
2. The device test lane has not run on API 30.
3. The owner has not tested on a real Android 11 phone. Only they can.
4. `verify.py` does not yet list API 30 among its emulator levels (the handover mentions lanes on 31, 33 and 36).
   Consider adding it once the lane passes.

## Environment set up this session

- Installed `system-images;android-30;google_apis;x86_64` into `.toolchain/sdk` (owner approved the download).
- Created the AVD `riff-glass-30` in `.toolchain/avd`, a copy of `riff-glass-31`'s settings (1080×2400, 420 dpi,
  3 GB RAM, swiftshader). It boots in about 35 s.
- **The emulator may still be running** as `emulator-5580`. Stop it with `adb -s emulator-5580 emu kill` if you don't
  need it.

Gotchas:

- `sdkmanager.bat` and `avdmanager.bat` are in `.toolchain/sdk/cmdline-tools/latest/cmdline-tools/bin/` (one folder
  deeper than usual).
- From Git Bash, those `.bat` files fail on the space in `C:\Volume D` ("'C:\Volume' is not recognized"). Run them from
  PowerShell.
- The SDK licenses are already accepted in `.toolchain/sdk/licenses`. Don't auto-accept new ones; ask the owner.
- Set `ANDROID_AVD_HOME` to `C:\Volume D\Riff\.toolchain\avd` before starting the emulator.
- A full `lintRelease` took about 11 minutes and `verify.py --lane pr` about as long; run them in the background.

## Next steps

PowerShell, from `C:\Volume D\Riff`:

```powershell
$env:JAVA_HOME = "C:\Volume D\Riff\.toolchain\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME = "C:\Volume D\Riff\.toolchain\gradle-user-home"
$env:ANDROID_HOME = "C:\Volume D\Riff\.toolchain\sdk"
$env:ANDROID_AVD_HOME = "C:\Volume D\Riff\.toolchain\avd"
```

1. If the emulator isn't running, start it:
   `& "$env:ANDROID_HOME\emulator\emulator.exe" -avd riff-glass-30 -port 5580 -no-snapshot-save -no-boot-anim`
2. Build and install: `.\gradlew.bat :app:assembleDebug`, then
   `& "$env:ANDROID_HOME\platform-tools\adb.exe" -s emulator-5580 install -r app\build\outputs\apk\debug\app-debug.apk`
3. Smoke test: launch the app, open Home, Search, a player, a popup menu, Settings → "Open links in Riff" (should open
   App info), Look and feel. Check the glass draws a tinted fill, not an unblurred copy of the page. Watch
   `adb logcat` for `FATAL EXCEPTION` and `NoSuchMethodError` / `NoClassDefFoundError`.
4. Things that differ on Android 11 without crashing, worth a look: background playback and the media notification,
   offline downloads (foreground service type `dataSync`), the on-device library permission (`READ_EXTERNAL_STORAGE`
   below 33), Cast, and output switching (the system output picker is only offered on 34+).
5. Device lane: `python scripts/quality/verify.py --lane emulator --allow-install --serial emulator-5580`. It installs a
   debug app and runs the fixture suite with the emulator's network off.
6. Fix anything found; keep the fixes version-checked so 12+ behaves as before. Rerun `verify.py --lane pr` before
   calling it done (CLAUDE.md).

## Second session, 6 November evening: first run on Android 11

The debug build (minSdk 30) was installed on `riff-glass-30` (`emulator-5580`).

**Found and fixed: Riff closed at start.** The emulator image's Play services are 20.18.17 (2020). Media3's
`Cast.initialize()` asks Play services for the `CastContext` on a background thread; that version throws
`IllegalStateException: addListener must be called on main thread` there, on a thread `castable`'s catch never sees,
so the process died. `MusicPlaybackService.castable` now makes the `CastContext` on the main thread first when
`SDK_INT < 31`, so Media3 finds it built. Android 12+ is unchanged. A real Android 11 phone with current Play services
probably never hit this, but a phone with stale Play services would have. Plan note added under Feature 19's log row.

**Smoke test after the fix (all on API 30):** starts, no crash in `logcat -b crash`; restored song plays (session
`state=3`) with the media notification posted; Home, Search (results load), Settings, Look & feel, a popup menu all
draw. The glass is the tinted fill and popups their opaque tint, never an unblurred copy of the page. Playback
dropped into buffering (`state=6`) for a moment every few seconds while a second emulator was booting; not rechecked
on a quiet machine.

**Found, not fixed (not Android 11 specific):** in Settings → Downloads & data, the "Open links in Riff" tile under
Data saver is drawn underneath the next tile: only a sliver shows, and a tap there lands on Data saver. Cause:
`BentoToggle` has a stretching `Spacer(Modifier.weight(1f))`, and in `BentoBeside`'s right-hand column it takes the
height meant for the tile below it, the same thing `SettingsBento.kt`'s comment on `BentoLink` (stacked) describes.
Nothing in it depends on the API level, so 12+ should show it too (not confirmed: the API 31 emulator was too slow
to compare). So "Open links in Riff" on Android 11 was not tapped through; its intent switch is covered by
`PlatformCompatibilityTest`.

**Device lane (`--lane emulator --serial emulator-5580`): FAIL, 63 of 64.** The one failure is
`SeekTimingDeviceTest.aDownloadStoredTheOldWayIsStoredAgainOnceAndSeeksAsFast`: repacked-file seeks moving after
0.6–1.2 s against the 1 s limit. Rerun alone twice, the *other* test in the class (an ordinary download) also went
over (up to 1.15 s), and the repacked file once passed: both sit on the 1 s line on this software-drawn API 30
emulator. The old-format file still takes up to 10 s, which the repack fixes. Read as emulator speed, not an
Android 11 fault; only a real phone settles it.

**`verify.py --lane pr` after the Cast fix: PASS_LOCAL**, all nine checks. Still nothing committed or pushed. The
`riff-glass-30` emulator has since shut down; start it again with step 1 of "Next steps".

**Signed test build (owner asked, 6 November):** `scripts\build-release-candidate.ps1 -AllowDirty -OutDir
artifacts\release-android-11` (kept apart from the published `artifacts\release`). The first run failed one JVM test,
`HistoryScreensTest.earlierSearchesAreImportedOrDeletedOnlyWhenTheUserSaysSo` ("0 searches added"); it passed alone
and in the rerun, so read as flaky. Second run: PASS_LOCAL, release certificate `e45ac43b…`, 44.4 MiB, SHA-256
`ab2726199eb1921d2d9cadcc3912dd0513abcaf2de1353a572106fc7bb15228d`. A copy is in the owner's Downloads as
`riff-0.21.0-android11.apk`. Built from uncommitted work, so not traceable to a commit; same version (0.21.0, code
35) as the published release. Not installed or run anywhere yet.

## Decisions for the owner

- **Seek timing on API 30:** keep the 1 s limit and treat the emulator result as "check on a phone", or give the API
  30 emulator its own limit. Not changed.
- **The hidden "Open links in Riff" tile:** the owner chose to leave it (6 November). Not fixed on this branch.

- **Glass on Android 11:** currently the tinted fill. Alternatives: the high-contrast "solid controls" look, or a
  software blur (days of work and slow; not recommended).
- Whether to commit the branch, open a PR, and ship Android 11 support in a release. Nothing has been pushed.
- Android 8 (API 26) was discussed and judged a much larger job (about 1–2 weeks: backup via `MediaStore`
  `RELATIVE_PATH` needs a pre-Android 10 path, exit diagnostics need API 30, plus testing three more versions). Not
  started.
