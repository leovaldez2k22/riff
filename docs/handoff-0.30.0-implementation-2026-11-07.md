# Riff 0.30.0 implementation handoff — 8 November 2026

**Update, 03:20 IST: resumed by the owner (feature phase 1 excluded).** Since the stop below: fresh PR lane `pr-20261107T202634Z` PASSED (all nine gates, both editions, source `6d86f2c8154b…`); four signed candidates in `stage9-candidate` verified against it, both signed solver seams pass, source ZIP regenerated (SHA-256 `7fce9004…`); probe rebuilt as `stage9-probe-signed/edition-switch-probe.apk`. Resume steps 1–4 are done. Step 4 first hit three emulator (qemu, `-gpu software`) crashes on two fresh AVDs (`stage9-emulator-owner.json`, `stage9-api36-fresh-emulator-owner.json`; both stopped, neither usable as a fresh trial host; evidence `artifacts/quality/stage9-seed-crash-diagnosis/`). On the owner's choice of one emulator change, a third fresh AVD started with `-Gpu host` (`stage9-api36-hostgpu-emulator-owner.json`) passed: **`packaging-20261108T012804Z` PASS_LOCAL**, 0.21.0 → Plus → Riff → Plus with all fixture data byte-identical and firstInstallTime unchanged. Use `-Gpu host` for signed packaging trials on this laptop. On the owner's request both emulator lanes were then re-run on current source on that AVD: Riff `emulator-20261108T024147Z` and Plus `emulator-20261108T025819Z` PASS_LOCAL, 73/73 tests each, source `6d86f2c8154b…`. Phone (I2217, tier R, airplane mode), side by side as `.debug` so the owner's real Riff 0.21.0 stayed untouched: Riff `device-20261108T033654Z` and Plus `device-20261108T034532Z` PASS_LOCAL, 66/66 each. The owner declined the in-place 0.21.0 chain on the phone; it rests on the emulator packaging trial. The owner reviewed and accepted the stage 8 documents unchanged, so stage 8 is complete. Benchmarks ran once per edition on the phone (Riff `benchmark-20261108T042938Z`, Plus `benchmark-20261108T053141Z`): actions, queue and first audio pass; frames miss as in 0.20.0; Plus cold start p95 2,011 ms, Riff's skewed by three ~33 s stalls; MemoryCheck failed on a harness wait in both. The owner accepted both runs as is (8 November); no rerun. The owner then listened to Riff+ and Riff in the side-by-side debug app and found nothing wrong. **Stage 9 is complete; split verification is done.** Next: feature phase 1, only on the owner's go-ahead. Superseded step 5 note: the explicit phone/listening/benchmark decision. All 24 feature phases, including phase 1, wait (the owner excluded phase 1 from this resume). No emulator or task-owned client is running. No commit, push, tag, publication, phone or real-account action occurred. Run PowerShell commands that call git under `$ErrorActionPreference='Stop'` with `GIT_CONFIG_PARAMETERS="'core.safecrlf=false'"`; `pwsh` is not installed.

**Earlier: STOPPED at the owner's request, 01:50 IST.** The text below is that stop's record; its resume sequence steps 1–3 are now complete.

## Latest owner direction

Android 11 / API 30 is out of scope. **Do not restart its emulator, seek investigation or codec experiment.** The experiment was reverted byte-for-byte to the pre-experiment renderer. App, benchmark, embedded build identity, packaging probe and current documentation now use Android 12 / API 31 minimum. Historical API 30 failures remain evidence, outside current release acceptance.

The owner previously accepted all section 3 recommendations and authorized continuous implementation through ready stages with their required checks. Credentials, model hosting, physical device/account actions and publication retain separate owner decisions. This stop instruction supersedes continuous execution until resumed.

## Workspace and status

- Workspace: `C:/Volume D/Riff`; PowerShell. Branch `android-11` is a historical name; do not rename it.
- HEAD: `2409a1d02b74f4d3d27e77e631bc8fe5ec79ec28`; dirty working tree includes owner changes. Preserve them and the existing index. New implementation files were marked intent-to-add only to include them in source enumeration; nothing was committed.
- Current application/build-input digest: `6d86f2c8154b718884d0df8b73be266e4aa4ce692fb3b78fbe295b97036aa814`.
- Source: unreleased 0.30.0 / code 36, min 31, target 36, compile 37. Published release remains 0.21.0 / code 35.
- Toolchain: JDK 21.0.12.1, Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.10, workspace `.toolchain` SDK/cache/signing.
- Split stages 0–7 complete. Stage 8 complete (technical checks passed; owner accepted the documents on 8 November). Stage 9 complete (8 November). All 24 subsequent feature phases remain unstarted and wait for the owner's go-ahead. Stage 10 publication waits for all features and owner authorization.

Read [the implementation plan](feature-implementation-plan-0.30.0-2026-11-07.md), [split plan](riff-plus-split-plan-2026-11-07.md), root AGENTS/CLAUDE and their latest logs. Before calling any stage complete, run `python scripts/quality/verify.py --lane pr`. Earlier historical log entries are superseded by later owner decisions.

## Exact stop checkpoint

Preserved evidence: `artifacts/quality/stage9-owner-stop-20261108-0150/`.

- `checkpoint.json`: source identity, per-edition counts, artifact hashes and session exits.
- `riff-junit/`, `plus-junit/`: actual XML reports copied before any future clean.
- `lint-results-riffRelease.xml`: completed Riff lint report.
- `git-status.txt`: working-tree snapshot; preserve the index as found.
- `previous-handoff.md`: complete preceding handoff, including detailed history and emulator/tooling recovery lessons.
- Copies of clean, PR, source-package and probe logs.

Current full PR lane **interrupted, not passed**: `artifacts/quality/lanes/pr-20261107T193221Z`. Host session **43760 exited 1 on owner cancellation**. Wrapper log `artifacts/quality/stage9-supported-floor-pr.log`. A successful app clean preceded this run (`stage9-supported-floor-clean.log`).

| Check | Result at stop |
|---|---|
| Tooling tests | 208 passed |
| Static checks | 253 files, 49 reviewed sites, 543 measures, zero findings |
| Riff unit/Compose | 1,594 tests / 212 suites, zero failures/errors/skips |
| Riff release | Lint zero errors/fatals; APK content, size/profile and solver checks passed |
| Plus unit/Compose | 1,629 tests / 218 suites, zero failures/errors/skips |
| Plus release | Compilation progressed; lint/report/APK/solver completion not established |
| Whole two-edition lane | Incomplete; no final passing lane summary |

Riff release manifest is retained in the lane. Collector root: `artifacts/quality/2409a1d02b74f4d3d27e77e631bc8fe5ec79ec28-dirty-6d86f2c8154b/`, with `lane-riff-pr-20261107T193221Z` / Plus counterpart. Do not convert this interrupted attempt into a pass or use it to satisfy the final source gate.

## Newly completed artifacts

**Source ZIP**: `artifacts/quality/stage9-candidate/riff-0.30.0-source.zip`, 16,086,045 bytes. SHA-256 `d0ff6cbb5bd0fdf1dab8754a077d972b6b65a8f52f6a2e5c69846d3e8da4a277`. Packaging session 34153 exited 0; verification confirmed every current APK fingerprint input. It predates this handoff update; regenerate the final archive after documentation is reconciled. Candidate folder currently has source ZIP/checksum only; no current-source signed app candidates/previews have been built.

**Signed test-only probe**: `artifacts/quality/stage9-probe/edition-switch-probe.apk`, 2,470,433 bytes. SHA-256 `a5d24bbebe09b98dc21e11418235b2700d7efe20b11179787edf04a785309ed5`. Session 58498 finished 0 before cancellation reached it. Log `stage9-probe-build.log`. Built from the current Riff unsigned release identity (digest 6d86f2c8154b…), current Java source and minimum API 31. It has **not been installed or exercised**. Validate its source pairing and certificate through packaging preflight before use; rebuild if source changes. Old stage7 probes are stale.

## Stage 9 code already applied

`EditionJourneysDeviceTest.kt` adds real-Android stored-Automix preservation/fallback, model/session presence, mode changes, Plus EQ interaction and Riff Sound fallback, main/tail effects and preference restoration. It is registered in the fixture lane. Actual ONNX initialization is eager, not a stub. AndroidJUnitRunner may reorder classes.

`EditionSwitchProbe.java` now uses the real audio duration and a valid 64-second local-tail, 100-ms-bin cache fixture. The former four-second duration could legitimately trigger reanalysis and create a false preservation failure. Synthetic data proves persistence only, not analysis accuracy. Production audio behavior was not changed by this correction.

The API-30-only codec change in `VolumeBoostProcessor.kt` is absent; exact restoration was verified against `artifacts/quality/stage9-before-api30-codec-queueing-VolumeBoostProcessor.kt`. Do not reapply it or weaken seek timing assertions.

## Valid prior evidence and its limits

- Stage 8 full PR: `artifacts/quality/lanes/pr-20261107T170655Z`, all nine gates PASS_LOCAL; 208 tooling tests, 1,594 Riff / 1,629 Plus tests, zero failures/errors/skips. Digest `fa3f470e693a407e7fcbfab351880f41505d6b10ee8785361e04c5853e348c15`. `compiled-release-cards.json` verifies actual flavor-specific version-36 cards. This is older source, not the final stage 9 gate.
- Both full API 36 runtime lanes passed all four gates, **73 tests each**, zero failures/errors/skips: `emulator-riff-stage9-api36-20261107-2351` and `emulator-plus-stage9-api36-20261108-0004`, under `artifacts/quality/lanes/`. Combined proof `artifacts/quality/stage9-api36-runtime-summary.json`. Source `f0ae993d13c4509677aa30ba390dcbcf03cddf753925d20e801297037648bf7f` predates the minSdk change. Renderer is restored to that tested implementation. Neither ARM64 release execution nor phone timing/listening is inferred from x86 debug tests.
- Stage 7 signed baseline: `artifacts/quality/stage7-candidate/`, release + preview for both editions, matching source ZIP and verification reports. Digest `1e2e3c72b65d31b694be83f8944f3c17b7e579cc0b782135bf58ecbe2adab829`; min 30, therefore historical rather than current candidates. All certificate/package/payload/solver checks passed. Keep these bundles and mappings.
- Published upgrade input: `artifacts/release/riff-0.21.0.apk`, SHA-256 `101075ac4aa0ba1e58c76f8b5c1e9df64f651d9b88e99c0645487deac15ee4fe`, code 35, min 31. Verification `stage7-previous-release-verification.json`.

Pinned signing certificate SHA-256: `e45ac43b9d1c658c03ace002abb4c482cc3d63214a5f8509c801e404c91ff798`. Existing `.toolchain/signing/musicglass-release.p12` and password file are present. Use established scripts to load secrets internally; never print secrets or create a new key.

## Resume sequence — only after the owner resumes

1. Read this handoff, inspect live status/processes and recompute source identity. Preserve owner work. No old session listed here is active.
2. Run a **fresh full dual-edition PR lane** on the current source, with no emulator alongside it. Retain the interrupted lane. Do not change app sources during the gate. Use workspace JDK/SDK/cache, one Gradle worker, parallel false and in-process Kotlin. Environment used previously:

```powershell
$env:JAVA_HOME = (Get-ChildItem .toolchain -Directory -Filter 'jdk-*' | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $PWD '.toolchain/sdk'
$env:GRADLE_USER_HOME = Join-Path $PWD '.toolchain/gradle-user-home'
$env:GRADLE_OPTS = ($env:GRADLE_OPTS + ' -Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false -Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process').Trim()
[Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_kotlin.compiler.execution.strategy','in-process','Process')
python -X utf8 scripts/quality/verify.py --lane pr
```

3. After it passes, build current signed releases/previews using `scripts/build-release-candidate.ps1 -AllowDirty -SkipChecks -Preview -OutDir artifacts/quality/stage9-candidate` (default clean). SkipChecks reuses the completed PR and intentionally leaves collect-only duplicate-check reports BLOCKED; never relabel those. Verify all four against the **new passing** lane with `artifacts/quality/verify-stage9-signed-build.py --pr <new-lane> --candidates artifacts/quality/stage9-candidate`. This helper expects minimum API 31, pinned signer, exact package/version/flavor/source, payload and actual compiled release cards. Run both signed mapping solver checks and regenerate matching source ZIP/checksums.
4. Validate/rebuild the current probe as necessary. Run the packaging lane on the owned supported API 36 emulator with explicit serial, `--allow-install`, current probe and the signed 0.21.0 `--previous-apk`. Prove **0.21.0 → Plus → Riff → Plus** preserves downloads, queue, preferences, analysis, Encore/history/widget and firstInstallTime. Preflight must find no existing production Riff; never uninstall to get past a failure. Current-source signed packaging has not run.
5. Reconcile supported-target runtime evidence and obtain the remaining owner document review and explicit phone/listening/benchmark decision with concrete evidence. Do not claim emulator proof satisfies physical listening or benchmark requirements. All 24 new feature phases still wait for split verification.
6. Continue the approved feature plan after prerequisite gates. Publication/push/tag/account writes remain separately authorized.

## Owned emulator and process safety

Both task-owned emulators are stopped (`launched:false` ownership records). No emulator process was present at handoff.

- Supported API 36: `riff030-stage9-api36-ffd9c0d78968`; record `artifacts/quality/stage9-emulator-owner.json`. Previous PID 9112 exited. Last serial `emulator-5590`; verify live identity before reuse. It held debug fixture data only; no production Riff package had been installed at the prior check.
- Historical API 30: record `artifacts/quality/stage9-api30-emulator-owner.json`. **Do not restart.**
- Helper `artifacts/quality/manage-stage9-emulator.ps1 -Action Start|Stop -Api 36 -OwnerFile artifacts/quality/stage9-emulator-owner.json` verifies ownership. Always verify exact console AVD name before mutation/shutdown. Do not wipe or control existing unrelated AVDs, kill shared daemons or stop global ADB.
- Toolchain AVDS live in `.toolchain/avd/riff030-trials`, Android user data in `.toolchain/android-user/riff030-stage9`. The helper handles SDK nesting/path-with-spaces recovery. See `previous-handoff.md` for detailed golden paths; do not repeat old SDK/AVD failures.
- If stopped during native instrumentation in a later run, cancel the host client **and** shut down only the verified owned VM; host cancellation alone can leave guest tests running.

No task is running in the background. All further implementation and verification are paused by owner instruction.
