# UPDATE-07 — Integration review and packaged acceptance: Gemini execution brief

Date: 2 November 2026. Implementer/review preparer: Gemini. Final reviewer: Codex after handoff.
Prerequisite: Modules 2–6 implemented with result reports. Read [shared instructions](README.md).

## Objective

Prepare an evidence-backed review of the complete authenticated-metadata pipeline in source and the bundled Windows runtime. Close demonstrated integration defects and report exact remaining gates. Do not claim an independent security assessment by reviewing your own implementation.

## Current state

Read contract revision 2, UPDATE-01 review/resolutions, all module results and current production/test source. Inspect desktop/build.gradle.kts, Main.kt, UpdateVerificationProbe.kt, UpdateEnvelopeVerifier.kt, parser/policy/store/signer/coordinator, desktop_smoke.py, m6-acceptance.md, m6-implementation.md, capability-matrix.md, manifest-inventory.md and sbom.md.

Starting evidence has limits: Module 2's targeted verifier tests and packaged opaque probe passed; its PR lane was pending in the report; full desktop suite had a WorkspaceLayoutTest real-storage isolation failure. Earlier M6 docs overclaimed authentication based on the unsigned client. Verify later corrections/results instead of carrying those claims forward.

Owned scope: docs/desktop/handoffs/update-07-review.md, update-07-result.md, accurate current desktop acceptance/capability/inventory claims and narrowly necessary integration regressions/fixes. Report substantial algorithm/design changes as follow-up defects requiring contract review.

## Implementation approach

1. Establish source identity, working-tree ownership and actual Module 2–6 evidence. Reproduce relevant failures; never discard earlier failures because focused tests pass. Review current diff against contract, not only completion reports.
2. Trace every update entry/constructor/result route. Confirm exact-byte signature precedes JSON payload interpretation, target checks precede state mutation, durable state precedes offers and no legacy fallback/test-key bypass exists. Check defensive copies and cancellation/error handling.
3. Audit limits, decoded duplicate handling, future-schema dispatch, URI interpretation and stable error precedence. Inspect all contract matrix V01–V36 cases; distinguish implemented metadata cases from downloader-only gates.
4. Review transaction durability/concurrency across supported instances, bootstrap merge, missing/corrupt state, clock bounds, same-sequence conflicts, version floors and delayed-offer invalidation. Use isolated temporary storage and subprocesses where required.
5. Reproduce immutable vectors and sign a complete test manifest offline. Verify valid/tampered/wrong-key cases with the packaged executable and its trimmed runtime. Add a test-only end-to-end diagnostic only if needed; it must not install test trust into production, contact feeds or initialize real user storage.
6. Resolve test isolation failures by temporary injected fixtures, not real data cleanup. Run the full desktop suite, package build and required PR lane with no competing build/emulator.
7. Inspect production distribution for required Ed25519 provider, correct version metadata and absence of test-only private material/configuration. Missing production deployment configuration must disable update offers. Packaging alone does not verify Authenticode.
8. Read desktop_smoke.py help/options before invocation. Run its documented offline smoke against the packaged app if it can use isolated storage; otherwise report the isolation blocker. Record runtime/version, command, source, duration, output and limitations.
9. Write findings with severity, exact file/symbol, trigger, impact, reproduction and required correction. Fix clear in-scope integration regressions with focused tests, then rerun affected checks. Do not silently waive a failing gate or perform unrelated redesign.
10. Update current M6 claims to distinguish authenticated metadata readiness, live deployment and actual installer safety. Produce a next-work list for separate downloader/redirect/private-address enforcement, exact artifact integrity, Authenticode publisher/timestamp policy, installer activation, schema-safe recovery and clean Windows VM checks.

## Scope boundaries

No real feed/account/device interactions, production signing, installation/uninstallation, user-data deletion, publishing or pushing. No claim of production readiness while deployment/signing/installer gates are missing. Clean VM/live-feed/Authenticode acceptance requires a later authorized environment; mark blocked/unimplemented accurately.

## Acceptance criteria

- Review report maps V01–V36 to actual evidence or explicitly open later gates; no unknown coverage status.
- Source and packaged runtime both pass valid signature; tampered payload/wrong key fail without a partial offer. Full signed-manifest path produces expected parser/policy decisions offline.
- Full desktop suite has no unexplained failure/skip and uses no real user database. PR lane passes on the relevant source; earlier failure/pending reports have accurate superseding evidence.
- Packaged identity/version and crypto modules match configuration; test keys/seeds/fixtures do not leak into production trust/resources.
- Concurrency/crash/clock/rotation/bootstrap evidence covers contract policy, with limits stated honestly.
- No unresolved high-severity metadata-pipeline defect is presented as ready. If one remains, report FAIL/BLOCKED with precise next action.
- Final docs separate metadata verification from downloader/install/Authenticode/recovery/production configuration; M6 remains open for unfulfilled gates.

## Verification commands

```powershell
node desktop/src/test/resources/update-v1/generate.cjs
node --test scripts/desktop/sign-update-manifest.test.cjs
.\gradlew.bat -PriffDesktop=true :desktop:test :desktop:createDistributable --offline --console=plain
python scripts/quality/verify.py --lane pr
python scripts/quality/desktop_smoke.py --help
rg -n 'evaluateManifest|compareSemVer|parseSimpleJson|test-|UpdateAvailable|EligibleUpdate' desktop/src/main scripts/desktop
```

Adjust only newly created test paths to their actual reviewed names. Record exact documented smoke/probe commands after inspecting tool help and the probe implementation. Run probes on valid, altered and wrong-key TEST-ONLY envelopes; no installer execution. Record each exit code and report/test-count details.

## Handoff requirements

Create update-07-review.md with findings and contract/test coverage. Create update-07-result.md with shared report fields, final metadata verdict, exact packaged/full-suite/PR evidence and separate M6 gate table. Include a concise owner-decision list and concrete future brief scopes. Hand the complete diff and evidence to Codex; do not mark Codex review passed on Codex's behalf or start publication.
