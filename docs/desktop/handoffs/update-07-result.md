# UPDATE-07 — Integration and packaged acceptance result

Date: 2 November 2026. Implementer/review preparer: Codex. Status: READY_FOR_REVIEW. Scoped integration/self-review and local acceptance completed; all required checks passed. This is a self-review of prior implementation, not an independent security assessment or approval on another reviewer's behalf.

## Source identity and changes

Starting HEAD e4748bf6a59aa4d56575ef1c97215f2a58b05c92; existing dirty shared checkout with untracked desktop/docs. No reset/cleanup/commit/push or unrelated Android modification. Ending HEAD is unchanged. artifacts/desktop/update-acceptance/source-inventory.json fingerprints 26 relevant source/dependency/contract files; package-inspection.json identifies the rebuilt application jar, and module6-baseline-comparison.json records the single changed prior metadata production file. Review all findings and V01–V36 in [update-07-review.md](update-07-review.md).

Changed production symbol: UpdateEnvelopeVerifier.kt / EnvelopeReader.read moves unknown/duplicate name rejection ahead of the already-full-member guard. Same exact fields, values, signature preimage and resource limits; no accepted-format or trust change. Added one verifier regression test. No parser/policy/state/signer design change.

Changed test tooling: scripts/quality/desktop_smoke.py adds --isolate-storage, per-run temporary JVM/user/app-data roots, protection against inherited JVM overrides, positive runs and finite/nonempty scales. scripts/quality/test_desktop_smoke.py adds three regressions. Current M6 acceptance/implementation/capability/inventory docs link the scoped audit and preserve separate production gates; historical evidence remains labelled. No UI redesign or actual persistence default changed.

## Fresh verification

| Check | Command / evidence | Result |
|---|---|---|
| Pre-fix envelope error reproduction | Filtered trailingUnknownAndDuplicateMembersKeepEnvelopeErrorCategory; envelope-pre-fix.log/XML | Expected test failure reproduced; actual SIZE_LIMIT versus expected INVALID_ENVELOPE |
| Smoke helper regressions | python -m unittest discover -s scripts/quality -p test_desktop_smoke.py -v | 3 passed |
| Zero-run executable guard | desktop_smoke.py --exe not-launched.exe --runs 0 --output .../zero-runs-must-not-exist.json | Exit 2, no output/launch |
| Immutable vector | node desktop/src/test/resources/update-v1/generate.cjs, inventory equality | Exit 0; original bytes unchanged |
| Full desktop and distribution | ./gradlew.bat -PriffDesktop=true :desktop:test :desktop:createDistributable --offline --console=plain | Exit 0, 146 tests/16 classes, zero failures/errors/skips; package rebuilt |
| Actual Node signer tests | node --test scripts/desktop/sign-update-manifest.test.cjs | Exit 0, 12 passed, no failures/skips |
| Fresh complete-manifest signing | Actual CLI with public TEST-ONLY temporary DER, explicit local JDK/app and output | Exit 0, exact bytes equal coordinator valid corpus |
| Packaged signature/error matrix | Actual .exe --verify-update-fixture; packaged-results.json | Valid 0; tampered/wrong key 2 INVALID_SIGNATURE; missing provider 2 CONFIGURATION_ERROR; appended publicKey/unsigned 2 INVALID_ENVELOPE |
| Packaged identity | .exe --desktop-build-identity | Exit 0, 0.1.0, default updates disabled marker; configuration behavior source-tested |
| Runtime/resource inspection | package-inspection.json | Java 21.0.12.1, jdk.crypto.ec/JAB, launcher/resource 0.1.0, no corpus/test helper/private DER/seed material |
| Isolated UI smoke | python scripts/quality/desktop_smoke.py --exe 'desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe' --runs 3 --scales 1.0,1.25 --isolate-storage --output artifacts/desktop/update-acceptance/smoke.json | Exit 0, 6/6 launches, one marker each, isolated files observed |
| Repository PR lane | python scripts/quality/verify.py --lane pr; artifacts/quality/lanes/pr-20261102T105132Z | Exit 0, PASS_LOCAL across all nine gates; 177 script tests, zero static findings |

All standalone tests/builds/probes/smoke completed sequentially before the PR lane. No competing build/emulator was started. Cached JDK/SDK/Gradle home, offline dependency resolution. Source tests use injected clocks and temporary/memory stores; subprocess locking/crash tests terminate only owned test children. No phone/account/feed/production key or installer operation, dependency download, installation/uninstallation or publication occurred.

Package path: C:/Volume D/Riff/desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe. Exact argv, exit codes, output and duration for packaged cases are recorded in packaged-results.json. Missing-provider case overrides security providers only in its own isolated child process; no host/global security configuration changed. Opaque verification remains separate from full metadata policy; no test trust installed as production configuration.

Smoke --help was inspected before invocation and the new --isolate-storage option documented by help. Original tool could not isolate; fixed before launching. Six runs wrote database/window/GPU cache into separate owned temporary roots and cleaned them. Observed first Compose marker 1.850–2.414 s; sampled working set 254.7–269.1 MiB. Not physical monitor-DPI, GPU-presented frame, 120Hz, tray-RAM, audio or clean-VM acceptance.

## Evidence inventory

Under artifacts/desktop/update-acceptance/: envelope-pre-fix.log/XML, envelope-final.xml, full-package-build.log, full-suite.json, node-tests.log, anchor-reproduction.log, smoke-help.txt, smoke-harness-tests.log, zero-runs-guard.log, signer-run.json, signed-envelope.json, packaged-results.json, package-inspection.json, smoke-run.log, smoke.json and usage-audit.txt. source-inventory.json and final-summary.json record final source/check identity. Repository lane evidence: artifacts/quality/lanes/pr-20261102T105132Z/lane-summary.json; collector manifest/build under artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-0ed34c22bb7b/lane-pr-20261102T105132Z/. The Android/repository lane does not substitute for desktop/package/smoke checks. Reproducing the signer needs a new output filename (no clobber); use the public TEST-ONLY reconstruction commands in scripts/desktop/README.md with the new acceptance output path. Private fixture file was outside Git and removed, and is never a production credential.

No unsigned production evaluation helper remains. parseSimpleJson matches are window-state persistence only. test- matches are registry guard/explicit local diagnostic and test tooling. Raw signing validation returns status/digest, not manifest/offer. Review maps every V01–V36 to evidence, with V28 activation, V29 artifact integrity and V30 redirects/address enforcement explicitly open.

## Metadata verdict versus desktop M6 gates

Metadata verdict: scoped self-review and offline metadata acceptance complete, READY_FOR_REVIEW; no unresolved P0/P1 metadata finding identified. All required local gates passed, with no waived failure/skip. All three demonstrated P2 integration/harness findings fixed and retested. External review/approval is not represented as completed.

| Gate | State after this module |
|---|---|
| Offline signed metadata: bounds/crypto/schema/target/compatibility/freshness | Implemented; fresh source and packaged evidence above |
| Production update configuration and release authority | Disabled/unset: owner keys/custody, channel/hosts, version/bootstrap/sequence ledger, capabilities |
| HTTP collector | Unimplemented: deadline/cancellation/encoding/identity request rules |
| Artifact downloading/staging | Unimplemented: generated safe staging names, host/redirect/IP policy, exact signed size/hash before use |
| Windows Authenticode | Unimplemented/unverified: pinned publisher identity, chain/timestamp/revocation policy, explicit offline/error behavior |
| Installer activation and install receipt | Unimplemented: same-scope serialization/revalidation with newer metadata and expiry; no execute path |
| Compatible-data recovery | Unimplemented: migration capability proof, compatible backup/restore, no freshness-floor lowering |
| Clean Windows VM | Not run; later authorized clean environment/install and upgrade acceptance required |
| Live deployment/end-to-end update | Not run; no production feed/trust or installer chain to accept |
| Physical display/phone gates | Outside update pack; earlier owner hold unchanged |

M6 remains incomplete; no production-ready updater claim. Local metadata state is not tamper-proof; removal/reinstall can reset to bootstrap. Initial clocks, forward/broken time, trusted-signer compromise, incompatible publisher releases and response withholding retain documented limits. NTFS atomicity/OS flush semantics are assumptions, not all-hardware power-cut proof.

## Concrete next briefs and owner decisions

1. Collector: bounded identity-encoded transport, deadlines/cancellation, stable errors, explicit host policy, no artifact access before metadata eligibility.
2. Downloader/stager: every redirect/address check and hop cap; safe owned staging; exact bytes/size/hash; failure/cancellation cleanup; no execution.
3. Authenticode policy: owner-approved publisher/certificate identity, timestamp and chain/revocation/offline behavior with positive/negative Windows tests.
4. Activation/recovery: installation lease or equivalent cross-process serialization, immediate policy revalidation, bundled worker/schema capability verification, confirmation receipt and migration-safe backup/restore. Never lower freshness to recover automatically.
5. Clean-VM/release acceptance: missing-toolchain first run, install/upgrade/uninstall and crash/recovery, only in a later authorized environment; explicit owner approval for production signing/publishing.

Owner must choose actual release/channel, key custody and rotation/rescue authority, sequence allocation/expiry refresh, hosting/redirect allowlists, compatibility tables, Authenticode identity and recovery policy. Nothing here makes those choices or starts these future briefs. No further implementation is started by this handoff. Final source fingerprints were rechecked after validation; no later production-code change occurred. The seven-module metadata pack does not include those deployment/install gates.