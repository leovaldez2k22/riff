# What can be deleted after Q19R

Nothing here has been deleted. Each item waits for the owner's word, and only after Q19R (final report written, handoff note done, final candidate signed and its evidence kept).
Sizes are given only where measured; the machine was too slow to total the rest in the time allowed.

## 1. Stray folders made by my own mistakes (safe now)

| Path | What it is |
|---|---|
| `C:\c` (about 759 MB) | A Gradle home created by two emulator-lane runs where `MSYS_NO_PATHCONV=1` broke GRADLE_USER_HOME. Nothing uses it. |
| `C:\q18c6` | Scratch folder of the Q18 C6 packaging run. |

## 2. Release-candidate worktrees (delete after the final candidate is signed and checked)

Each is a full checkout with its own build output. The signed APKs of an rc live inside its worktree, so keep a copy of any APK you still want before removing it.

| Worktree | Commit | Note |
|---|---|---|
| `..\Riff-rc4` to `..\Riff-rc9` | detached | Superseded by rc10. |
| `..\Riff-rc10` | 8adb2d1 | Delete after the 0.10.0 final candidate is built and installed; keep until then (it holds the signed rc10). |
| `Riff\.claude\worktrees\youthful-wright-c57334` | 7f7ab8e | Old session worktree (branch `claude/youthful-wright-c57334`). |

Remove with `git worktree remove <path>`, then `git worktree prune`.

## 3. Old branches (check each is merged or abandoned first)

`codex/downloads-crossfade-improvements`, `codex/fix-audio-continuity`, `codex/rc20-lyrics-playback`, `feat/rc18-prep`, `fix/overnight-rc18`, `claude/youthful-wright-c57334`. Keep `main` and `codex/riff-quality-m0` (the Q18/Q19 work) until it is merged.

## 4. Test evidence in `Riff\artifacts\` (git-ignored)

Keep until `docs/quality/final-report.md` is accepted, because it cites some of them:
`artifacts\quality\lanes` (57 lane runs; keep only the final PR, emulator, device, benchmark and memory runs), `artifacts\quality\q19\rc9`, `rc10` and the final candidate's folder.

Deletable after the report:
- Per-night and per-bug folders in `artifacts\quality\q19`: `a1`, `b0`, `b1`, `b1-run2`, `b2`, `b4`, `b5`, `b6`, `b8`, `c1` to `c8`, `day22`, `f1`, `fix22`, `frost`, `g1`, `g2`, `glass`, `night21` to `night23`, `owner-bugs-23oct`, `pins`, `pr-1585045`, `rc6`, `rc7`, `rc8`, `ux04`, plus `c1.log`, `g1-build.log`, `g1-build2.log`.
- About 90 hash-named folders in `artifacts\quality` (`<commit>-dirty-<id>` and `-clean-`, `canary`, `local-unknown`): build and lane outputs of intermediate commits.
- `artifacts\quality\q02-work`, `q03-work`, `q04-work`, `q18-owner-20261019`, `q18a`, `q18b`, `q18c`.
- Older work folders: `android12-compat`, `animation-120hz`, `animation-rc13`, `app-revamp`, `branding`, `bugfix-rc9`, `bugfix-rc16`, `crossfade-verification`, `design-revamp`, `features-rc14`, `features-rc15`, `features-rc17`, `lite`, `lyrics-upgrade`, `native-island-20261002`, `offline-rc11`, `overnight-2026-10-03`, `playback-fix-2026-10-05`, `settings-rc12`, `sign-in-crossfade`, `stress`, `ux-rc10`, `q18-phone-1021b`, `phase7-*.png`.
- Earlier cleanup lists: `deletable-files-2026-10-05.*`, `redundant-files-2026-10-05*`.

Do NOT delete: `artifacts\release` (signed APKs and checksums of rc20 and every shipped build), `artifacts\phone-backup`, `artifacts\github-*` (record of the credit and author clean-up) until you have decided they are no longer needed.

## 5. Toolchain scratch in `Riff\.toolchain`

Large but needed while you build: `jdk-21...`, `sdk`, `gradle-8.13`, `gradle-user-home`, `signing`. Delete only if you will never build again.

Safe to delete once Q19R is done:
- Emulator images: `avd`, `q02-avd`, `q18-avd`, `q19-avd` (the Q18/Q19 emulator lanes recreate `q18-avd`; `perfetto-v50.1`, the Android SDK images they use stay in `sdk`).
- Screenshots and XML dumps: `*.png`, `*.xml`, `*.mp4`, `video-*-frames`, `surface-fallback-frames`.
- One-off scripts and data: `*.py` (audit, fix, ui, phone-check), `rc20-device-test.ps1`, `rc20-*.json/.xml/.png`, `megalobiz-*`, `netease-*.json`, `ProviderProbe.java`, `bounded-scroll-*.txt`, `release-scroll-*.txt`, `window.xml`, `__pycache__`, `folder-cleanup-2026-10-02.*`.
- `perf-release-signed.apk` (+ `.idsig`), `rc20-release-verification`, `innertubex` and `innertubex-0.7.2-src` (only needed to read the library source).

## 6. Beside the repo in `..\scratch`

`agp9-build.log`, `agp9-build2.log`, `agp9-build3.log`, `riff-copy.log`, `models.txt`, `project_details.md`, `innertubex-riff` (a copy of the library work). The other folders (`Codex`, `GATE_EC_2027_1`, `RISC-V`, `Zombie Highway`, `kyber_project`, `2026-09-*`) are not Riff; I did not look inside them, so that is your call.

## 7. On the phone (only with your OK, per R4)

- Riff Debug (`com.musicglass.personal.debug`), once the suites are done and you no longer need it. Never the app labelled "Riff".
- Riff Preview (`.preview`) rc9/rc10, after the final candidate replaces it or you choose to go back to your own account on Riff.
- Files my tests left in the phone's storage (the cleanup list in R4 names them); I delete only what testing created.
- Dummy-account data in Riff Preview, if you want it gone: clear app data or uninstall Preview.

## 8. Never delete

`docs/quality/` (ledger, bugs, proposed-bugs, final report), `docs/release-signing.md`, your signing key and its backup, `artifacts\release`, `Riff` the app on the phone, `scripts\download_favourites.py` (your uncommitted edit), `docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md` and `.commandcode/` (not mine).
