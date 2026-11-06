# Gemini execution briefs: remaining desktop update modules

Prepared 2 November 2026 from the current workspace. These are update modules, not Gradle modules or desktop milestones M1–M6.

## Start here

Give Gemini one brief at a time, in order. Suggested prompt:

> Implement the attached UPDATE brief in C:\Volume D\Riff. Read handoffs/README.md and all required sources first. Complete the code, focused tests and result report within the brief's scope. Preserve existing work. Report actual evidence and unresolved issues; do not claim a stage complete before the required checks pass. Stop at the review handoff before starting the next module.

| Order | Brief | Dependency / starting state |
|---|---|---|
| 1–2 | Existing [Module 1 brief](update-01-trust-contract-brief.md), [contract](../update-trust-contract-v1.md), [Module 2 result](update-02-result.md) | Contract revision 2 exists; verifier implementation and packaged probe reported. Historical pending/full-suite isolation caveats are superseded by the fresh UPDATE-07 result: current full suite, packaged probes, isolated smoke and PR lane passed. Preserve the original failure record. |
| 3 | [Strict authenticated payload parser](update-03-payload-parser-brief.md) | Consume the existing verifier; resolve prerequisite failures rather than assuming approval. |
| 4 | [Compatibility and durable freshness policy](update-04-policy-state-brief.md) | Reviewed parser API and fixtures. |
| 5 | [Coordinator and legacy-path removal](update-05-coordinator-brief.md) | Reviewed parser and policy. |
| 6 | [Release signer and interoperable vectors](update-06-release-signer-brief.md) | Reviewed end-to-end contract and coordinator; no production key needed. |
| 7 | [Integration review and packaged acceptance](update-07-review-acceptance-brief.md) | Modules 2–6 implemented and results available. |

## Shared execution contract

- Read root AGENTS.md and CLAUDE.md, then docs/handover-0.20.0.md. Read the normative desktop contract revision 2, especially sections 3–10 and revision-2 resolutions. Earlier M6 acceptance claims do not prove authentication: the old DesktopUpdateClient still accepts unsigned JSON.
- Inspect current source and prior result reports before editing. Paths and API names suggested in these briefs are proposals unless explicitly described as existing. Follow existing implementations rather than creating duplicates.
- This pack prepares future execution; it does not claim code is implemented or authorize starting every module automatically. Once the owner assigns a brief, perform routine work within its scope without repeatedly seeking permission.
- Owner-owned deployment values remain decisions: release version/channel, production public keys and private-key custody, sequence allocation/bootstrap, feed/artifact hosts, compatibility tables, minimum Windows build and release operations. Use explicit test configuration; do not invent production defaults. Missing production configuration must fail closed.
- Preserve the dirty/shared workspace. Inspect git status and diffs first; if Git cannot establish the working tree, diagnose root/configuration read-only and document the failure before editing tracked files. During brief preparation, sandboxed Git failed; a subsequent access-authorized check succeeded and showed extensive existing Android/desktop changes. Do not treat access restrictions as repository corruption. No reset/clean/reinitialization, forced checkout, bulk formatting, unrelated commits or overwriting another task's files.
- Work sequentially with one build/lane at a time. No subagents or parallel changes in this shared checkout unless the owner authorizes them. Codex review here means a later review handoff, not a claim that an independent reviewer already approved anything.
- No Android behavior changes, phone installation, account mutation, user-data deletion, credential access, production signing, push, publication, update download or installer execution. Ask before new dependency/tool downloads, as required by the handover. Use cached tools offline first.
- Keep private keys, cookies, signed URLs, user data and raw remote bodies out of logs/reports/Git. TEST-ONLY public RFC seeds already used by fixtures must be labelled as such.
- Maintain unforgeable-by-ordinary-callers typed boundaries: verified opaque bytes -> validated schema -> policy decision -> eligible metadata. Internal visibility alone is insufficient if constructors/copy methods let ordinary callers bypass the earlier phase.
- Use stable contract errors. Preserve cancellation. Do not return partial success or include raw exception/remote strings in diagnostics.

## Toolchain and verification

From the repository root in PowerShell, use the handover's existing toolchain (confirm these paths exist):

```powershell
$env:JAVA_HOME = Join-Path (Get-Location) '.toolchain/jdk-21.0.12.1+1'
$env:ANDROID_HOME = Join-Path (Get-Location) '.toolchain/sdk'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.toolchain/gradle-user-home'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
.\gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain
python scripts/quality/verify.py --lane pr
```

The Android PR lane is required by AGENTS.md and does not replace desktop tests. Read scripts/quality/verify.py before running; use only documented options. Missing dependencies/tools are BLOCKED checks, not passing evidence. Do not run the lane while another Gradle build/emulator is active. Record lane directory, source revision/dirty state, commands, exit codes, test counts, failures and reruns. Reuse prior evidence only if it demonstrably covers the same relevant source; no blanket carry-forward PASS.

WorkspaceLayoutTest.hostHandlesPlaybackAndClockAdvancement previously restored real user-home state (expected 0, got 1671). Isolate test storage through temporary injected roots, inspect other test constructors, preserve real data, then rerun the full suite. A filtered pass does not close the failure.

## Required result report for every module

Create its named update-NN-result.md without overwriting someone else's report. Include:

1. Task ID; READY_FOR_REVIEW, FAIL or BLOCKED; starting/ending source identity and dirty-state limitations.
2. Changed files with purpose, final public interfaces and caller migration notes.
3. Acceptance table: criterion -> test/manual evidence -> PASS/FAIL/BLOCKED.
4. Exact commands, exit codes, counts, artifact paths, failures and separate reruns.
5. Contract deviations with evidence and review needed; no silent weakening.
6. Remaining defects, owner decisions and exact next actions; next module's prerequisites.
7. Confirmation of scope boundaries, including whether any external/device/user-state actions occurred.

Implementation-ready or focused-test success is not final stage completion. Before calling a stage done, run the PR lane and relevant desktop checks. Stop after the report for review; do not auto-start the next module.

## Outside this pack

Contract section 9 explicitly reserves downloader/redirect/network-address enforcement, artifact staging and exact size/hash checking, Authenticode, installer activation, data-compatible recovery, clean Windows VM acceptance and production deployment configuration for separate briefs. Module 7 must report these as open and must not mark M6 or the whole updater production-ready. Owner-accepted Android limitations and removed features are not reopened by this pack.

## Brief preparation checks

All five briefs were checked for the seven required sections; every index link resolves locally. These checks passed on 2 November 2026. No application code was changed by brief preparation. An attempted PR lane exited before running gates because sandbox permissions prevented creating its evidence directory. An access-authorized process check then found another active verify.py/Gradle run; no competing lane was started. No fresh PR PASS or implementation-stage completion is claimed for this documentation pack.

## Current scoped sequence evidence

The seven-module authenticated-metadata sequence now has implementation and scoped self-review evidence. Final handoff: [UPDATE-07 review](update-07-review.md) and [result](update-07-result.md). This is a self-review, not independent security approval. No live production updater/installer readiness is claimed; M6 remains open for the explicit collector/download/Authenticode/activation/recovery/VM and owner-configuration gates in that result.