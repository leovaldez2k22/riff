# What can be deleted after Q19R

Nothing here has been deleted. Each item waits for the owner's word. **Reviewed again on 24 October 2026, after Q19R closed**: every claim below was checked on disk that day, and the earlier version's mistakes are corrected (see the end).

Sizes measured 24 Oct (du): `Riff\artifacts` 28 GB, `Riff\.toolchain` 56 GB (including the JDK, SDK and Gradle you keep), `Riff\app\build` 1.4 GB, `C:\c` 761 MB, `C:\q18c6` 664 MB; worktrees `Riff-rc4` 1.5 GB, `Riff-rc5` 37 MB, `Riff-rc6` 2.1 GB, `Riff-rc7` 2.5 GB, `Riff-rc8` 1.0 GB, `Riff-rc9` 818 MB, `Riff-rc10` 820 MB (the newer ones were not measured). The C: drive had 21 GB free.

## 1. Safe to delete now

| Path | What it is | Checked |
|---|---|---|
| `C:\c` (761 MB) | A stray Gradle home from two lane runs where `MSYS_NO_PATHCONV=1` broke GRADLE_USER_HOME | Holds only a copy of `...\Riff\.toolchain\gradle-user-home`; nothing refers to it |
| `C:\q18c6` (664 MB) | The fresh clone used by the Q18 C6 packaging run | A clone of the local Riff repo at 0549229, which is in Riff's history; no changes, no stash, no unpushed commits; its lane output is not cited by any document |
| `..\Riff-rc4`, `..\Riff-rc5`, `..\Riff-rc6` | Old candidate worktrees | No edits; rc4's and rc6's signed APKs are in `Riff\artifacts\release`; rc5 was never signed |
| `Riff\.claude\worktrees\youthful-wright-c57334` | An old session worktree (7f7ab8e) | No edits; its branch is merged into `main` and `codex/riff-quality-m0` |
| Branches `codex/downloads-crossfade-improvements`, `codex/fix-audio-continuity`, `codex/rc20-lyrics-playback`, `feat/rc18-prep`, `fix/overnight-rc18`, `claude/youthful-wright-c57334` | Old branches | All six are merged into both `main` and `codex/riff-quality-m0`, so deleting them loses no commits. The `previous-account` remote and its branches are your call |
| `..\scratch\agp9-build.log`, `agp9-build2.log`, `agp9-build3.log`, `riff-copy.log` | My logs from the AGP upgrade and a copy | Nothing refers to them |
| `..\scratch\innertubex-riff` | A clone of the InnerTubeX library at its v0.7.2 release commit | No changes; Riff builds from the published 0.7.2 artifact (its hashes are recorded in the dependency inventory), not from this folder |

Remove worktrees with `git worktree remove <path>`, then `git worktree prune`; branches with `git branch -d <name>`.

## 2. Safe now that their evidence is copied (done 24 Oct, the owner's "do all these")

| Path | What was done | Checked |
|---|---|---|
| `..\Riff-rc7`, `..\Riff-rc8`, `..\Riff-rc9`, `..\Riff-rc10`, `..\Riff-rc11` | Their signed release files copied to `Riff\artifacts\release\0.10.0-rc7` to `0.10.0-rc11`; their `artifacts\quality` (lane runs and manifests, including rc10's benchmark run behind performance.md and Q18-030, 073, 074, 097) copied to `Riff\artifacts\quality` | `sha256sum -c` passes in each release folder; every evidence file compared byte for byte, none differs; no edits in any worktree |
| `..\Riff-final` | Its lane runs and the `bc69f02…-clean-2c37caa7158b` manifest folder copied to `Riff\artifacts\quality`; final-report.md, lint-triage.md and proposed-bugs.md now cite the copies | 202 files, none differs; no edits |
| `..\Riff-0.10.0` | Nothing needed: its signed files and source archive were already in `Riff\artifacts\release\0.10.0` | No edits. Keep only if you want the exact checkout of 0.10.0 at hand; the source archive rebuilds it too |

Not kept from any worktree: build outputs under `app\build` (unsigned intermediate APKs and test results), which nothing cites any more.

## 3. Test evidence in `Riff\artifacts\` (git-ignored)

The ledger (`execution-ledger.md`) cites most of these folders as its evidence: 32 hash-named folders, `q02-work`, `q03-work`, `q18a`, `q18b`, and almost every `q19\*` folder (`day22` 14 times, `rc8` 11, `rc7` 10, `g1` 7, among others). Deleting them leaves those links dead. Choose one: (a) accept dead links, (b) keep every cited folder, or (c) zip the cited folders into one archive first. To list what the ledger cites:
`grep -oE 'artifacts[\\/][A-Za-z0-9_./\\-]+' docs/quality/execution-ledger.md | sort -u`

Deletable, subject to that choice:
- Per-night and per-bug folders in `artifacts\quality\q19`: `a1`, `b0`, `b1`, `b1-run2`, `b2`, `b4`, `b5`, `b6`, `b8`, `c1` to `c8`, `day22`, `f1`, `fix22`, `frost`, `g1`, `g2`, `glass`, `night21` to `night23`, `owner-bugs-23oct`, `pins`, `pr-1585045`, `rc6`, `rc7`, `rc8`, `rc9`, `rc10`, `ux04`, `fix-round-20261024`, plus `c1.log`, `g1-build.log`, `g1-build2.log`.
- Earlier runs in `artifacts\quality\lanes`. **Keep** the final ones the docs cite: `pr-20261024T151040Z`, `emulator-20261024T153150Z`, `-155407Z`, `-161001Z` (R5) and `benchmark-20261024T102331Z`, `device-20261024T085600Z` (rc10, D1 to D4), with their manifest folders `bc69f02…-clean-2c37caa7158b` and `8adb2d1…-clean-15edbd20a549`.
- About 90 hash-named folders in `artifacts\quality` (`<commit>-dirty-<id>` and `-clean-`, `canary`, `local-unknown`).
- `artifacts\quality\q02-work`, `q03-work`, `q04-work`, `q18-owner-20261019`, `q18a`, `q18b`, `q18c`.
- Older work folders: `android12-compat`, `animation-120hz`, `animation-rc13`, `app-revamp`, `branding`, `bugfix-rc9`, `bugfix-rc16`, `crossfade-verification`, `design-revamp`, `features-rc14`, `features-rc15`, `features-rc17`, `lite`, `lyrics-upgrade`, `native-island-20261002`, `offline-rc11`, `overnight-2026-10-03`, `playback-fix-2026-10-05`, `settings-rc12`, `sign-in-crossfade`, `stress`, `ux-rc10`, `q18-phone-1021b`, `phase7-*.png`.
- Earlier cleanup lists: `deletable-files-2026-10-05.*`, `redundant-files-2026-10-05*`.

Do NOT delete: `artifacts\release` (every signed build, including `0.10.0`), `artifacts\phone-backup`, `artifacts\github-*` (the record of the credit and author clean-up) until you have decided they are no longer needed.

## 4. Toolchain scratch in `Riff\.toolchain`

Keep while you build: `jdk-21.0.12.1+1`, `sdk`, `gradle-user-home` (holds the Gradle 9.8.2 the build uses), `gradle-8.13` (still named by `scripts/build-lite.ps1` and `scripts/verify-device.ps1`), `signing` (**the release key**).

Deletable (no script, build file or setting refers to them):
- Emulator images `avd`, `q02-avd`, `q18-avd`, `q19-avd`. **No script recreates them**: the lanes only use an emulator that is already running. Delete them only if you will not run the emulator lanes again; the system images stay in `sdk`, so an emulator can be made again with `avdmanager`.
- Screenshots and dumps: `*.png`, `*.xml`, `*.mp4`, `*.html`, `video-*-frames`, `surface-fallback-frames`.
- One-off scripts and data: `*.py`, `rc20-device-test.ps1`, `rc20-*.json`, `megalobiz-*`, `netease-*.json`, `ProviderProbe.java`, `bounded-scroll-*.txt`, `release-scroll-*.txt`, `window.xml`, `__pycache__`, `folder-cleanup-2026-10-02.*`.
- `perf-release-signed.apk` (+ `.idsig`), `rc20-release-verification`, `perfetto-v50.1` (used by hand for traces), `innertubex` and `innertubex-0.7.2-src` (the library's source, read for the reviews; the S03 archive's hash is recorded in the dependency inventory, so it can be fetched and checked again).

## 5. Not mine: leave them, or decide yourself

In `..\scratch`: `project_details.md` (**your post-quantum cryptography / Kyber project notes, 28 Aug**), `models.txt` (an empty file from 2 Sep), and the folders `Codex`, `GATE_EC_2027_1`, `RISC-V`, `Zombie Highway`, `kyber_project` and `2026-09-*`. None is Riff and I did not create them.

## 6. On the phone

Done in R4 (24 Oct, owner: "remove everything that is not the original riff app"); see the ledger. Nothing left to remove.

## 7. Never delete

`docs/quality/` (ledger, bugs, proposed bugs, final report), `docs/release-signing.md`, `.toolchain\signing` and the key's two backups, `artifacts\release`, the app labelled "Riff" on the phone, `scripts\download_favourites.py`, `docs/RIFF_EXCELLENCE_PLAN_2_SUPPLEMENT.md` and `.commandcode/` (not mine).

## Corrections in this review

- `project_details.md` and `models.txt` were listed as deletable Riff leftovers; they are not Riff (section 5).
- The emulator lanes do not recreate `q18-avd`, as the earlier version said.
- `..\Riff-rc10` and `..\Riff-final` hold evidence that documents cite, and rc7 to rc11's signed APKs live only in their worktrees; the earlier version did not say so for all of them.
- `claude/youthful-wright-c57334` is merged into `main` too, not only into `codex/riff-quality-m0`.
- Backslashes in paths had been turned into control characters (`Riff\artifacts` read as `Riffrtifacts`) in this file and in final-report.md, performance.md, proposed-bugs.md and q18-bugs.md; all repaired (9529cd3).
