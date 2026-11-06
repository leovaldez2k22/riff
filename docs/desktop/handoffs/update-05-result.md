# UPDATE-05 — Authenticated coordinator and unsigned-path removal

Date: 2 November 2026. Implementer: Codex. Status: READY_FOR_REVIEW. Desktop tests, rebuilt package, offline packaged probes and required PR lane passed. Independent review has not occurred.

## Source identity and prerequisite review

Starting HEAD: e4748bf6a59aa4d56575ef1c97215f2a58b05c92, extensively dirty shared checkout with untracked desktop/docs trees. No reset, cleanup, commit, push or unrelated source replacement. Ending HEAD is unchanged. artifacts/desktop/update-coordinator/source-inventory.json contains 16 module/dependency/documentation fingerprints; they were rechecked unchanged after verification. HEAD alone cannot capture untracked work.

Read Modules 2–4 reports, contract revision 2, actual verifier/parser/policy/store APIs, diagnostic, build wiring and update usage sites. Self-review traced private verified/schema/eligible construction, complete bootstrap binding, cross-process scoped transactions and Module 2's fixed OpenSSL/JCA/package evidence. This is prerequisite self-review, not an independent security approval. The pre-existing host-test storage isolation is already in place and was retained. Full tests rerun prior verifier/parser/policy/store suites. The verifier's broad exception catch needed a cancellation correction identified by the new integration test; recorded below.

This is update module UPDATE-05, not completion of desktop milestone M6. No live feed, new dependency download, production key/configuration, user-home database access, Android behavior change, account/device operation, artifact download, installer execution or signing occurred. Packaged executable runs were offline diagnostics with guards before UI/store initialization.

## Changed files

| File | Change |
|---|---|
| desktop/src/main/kotlin/com/musicglass/desktop/DesktopUpdateClient.kt | Replaces unsigned metadata evaluation with bounded, suspending authenticated coordinator; typed results and retained-offer revalidation |
| desktop/src/main/kotlin/com/musicglass/desktop/DesktopBuildIdentity.kt | Strict bounded generated desktop identity reader; no Android/default fallback |
| desktop/build.gradle.kts | One preserved desktop version value generates both native package version and runtime resource; test identity expectation |
| desktop/src/main/kotlin/com/musicglass/desktop/Main.kt | Early offline --desktop-build-identity diagnostic; existing opaque verifier flag semantics unchanged |
| desktop/src/main/kotlin/com/musicglass/desktop/UpdateEnvelopeVerifier.kt | Read-only configured-registry predicate and cancellation rethrow before generic envelope rejection |
| desktop/src/test/kotlin/com/musicglass/desktop/DesktopUpdateClientTest.kt | 18 signed integration, boundary, identity, disabled-config and revalidation tests replace five unsigned/permissive tests |
| docs/desktop/manifest-inventory.md | Contract-v2 envelope/schema example, Windows 11 x64, actual identity/data-root behavior and open gates |
| docs/desktop/m6-implementation.md | Current metadata status; historical packaging observations explicitly labelled |
| docs/desktop/m6-acceptance.md | Removes M6 completion/authentication overclaims; preserves historical evidence and dates with correction |
| docs/desktop/capability-matrix.md | Separates metadata implementation, disabled deployment and missing collector/install gates |
| docs/desktop/sbom.md | Corrects desktop identity, observed runtime versions, unsupported JNA Platform claim and checksum/authentication claims; labels inventory limitations |

## Final interface and decision order

`DesktopUpdateClient(registry?, configuration?, store?, clock)` defaults to absent deployment configuration. Its suspending `check(ByteArray)` / `check(InputStream)` return:

- Disabled with CONFIGURATION_ERROR for missing/invalid key registry, missing deployment/store, absent/malformed runtime identity or installed-version disagreement.
- Rejected(code) preserving verifier/parser/configuration/state errors by their contract names.
- AuthenticatedIneligible(reason) preserving policy target/time/sequence/compatibility outcomes.
- UpToDate, or Eligible(offer: EligibleUpdate), only after durable policy commit.

No public String evaluation entry point, permissive version conversion, legacy manifest/offer type or unsigned fallback remains. Removed evaluateManifest, compareSemVer, DesktopUpdateManifest, old UpdateAvailable and standalone installer-hash utility (which had no external callers and did not authorize metadata). Existing window-state parseSimpleJson is untouched.

Per-client coroutine mutex serializes collection and decisions. Work runs on Dispatchers.IO. Byte arrays are capped before copying; stream collection counts at most 131073 bytes, rejects byte 131073, checks cancellation and leaves stream ownership with the caller. Only then run envelope verification -> strict payload parser -> target/signer/host and durable policy. Invalid signatures cannot produce VerifiedUpdatePayload, reach schema parsing or access freshness state. A bad signature on a deliberately malformed payload returns INVALID_SIGNATURE; the same malformed payload correctly signed returns INVALID_PAYLOAD. Store spy remains untouched in both cases.

No network collector exists in this module. Caller-supplied streams need transport timeouts/cancellable reads from the future collector; the coordinator does not impose HTTP encoding/redirect/address policy. IO exceptions become stable INVALID_ENVELOPE without exception/query text. Cancellation exceptions propagate, including direct verifier input cancellation. Parsing/clock/state cancellation boundaries remain intact.

One maximum-size envelope is retained privately for the current eligible offer. No raw envelope accessor or unbounded offer history exists. Offer identity includes reference identity plus the policy's committed scope/sequence/digest and evaluation time. `revalidate(offer)` accepts only this client's retained reference, reverifies its copied envelope, reparses and reruns the same locked policy against current state/time. Caller mutation of the original input cannot change it. A higher locally issued offer replaces the retained reference; another client's higher acceptance is detected by durable policy. Foreign-client/superseded references fail STALE_RELEASE. A revalidation success issues a new retained offer; consumers use that returned result. Recreating a client/trust registry requires a fresh check rather than reusing old references.

Eligible metadata is a point-in-time decision. No installation method, artifact capability or perpetual lease is exposed. Future activation must coordinate with the same scope's metadata decisions and revalidate at activation; the returned offer alone cannot prevent another process accepting a newer release after the transaction unlocks. Installation serialization remains a later gate.

## Desktop identity and deployment behavior

Preserved declared native package version **0.1.0**. riffDesktopVersion in desktop/build.gradle.kts supplies nativeDistributions.packageVersion, generated META-INF/riff-desktop.properties and the test expectation. Runtime reader accepts only a bounded canonical version line; malformed/missing/unreadable identity fails closed. No Android-derived 0.20.0 default survives in client code. Coordinator rejects configured installedVersion that differs from the packaged resource.

Package test reads both generated resource from the production application jar and launcher cfg's -Djpackage.app-version=0.1.0. Packaged --desktop-build-identity reports runtime 0.1.0. No release version bump was made or selected on the owner's behalf.

There were no existing application/UI update callers to migrate; usages of the old client were its tests alone. The replacement is the metadata API for future callers. Default construction remains disabled and reads neither input nor state. No keys, channel, hosts, bootstrap, Windows observations, worker/migration tables or storage root are inferred from the feed or bundled as invented deployment choices. TEST-ONLY trust exists exclusively in tests and the already explicit offline verifier diagnostic. Production jar inspection confirms no update-v1 fixtures, test signing helper or subprocess test probe is packaged.

## Documentation corrections

Inventory now describes five envelope fields and exact schema-v1 decoded fields, with MSI-only artifact syntax, minWindowsBuild >=22000 and no remote arguments/mandatory flag. The decoded example is clearly not signed/eligible metadata; fixed TEST-ONLY envelope links to the actual vector. Old portable-zip/rollbackPolicy and Windows 10 examples are superseded.

M6 documents retain their old packaging/MSI/smoke observations and 57-test evidence as historical reports from 1–2 November, with an explicit correction that the old client was unsigned and did not establish authentication. They do not turn those measurements into fresh Module 5 or clean-VM/installer evidence. SBOM is explicitly a manual inventory, not a complete audited dependency manifest; JNA 5.19.1, Skiko 0.150.2 and coroutines 1.9.0 were checked against local packaged jars, and unbundled JNA Platform was removed. Unrelated feature claims are outside this update change.

## Acceptance evidence

| Requirement | Test / evidence | Status |
|---|---|---|
| Fresh signed compatible metadata eligible only after commit | signedCompatibleFreshMetadataCommitsBeforeEligible; fullCoordinatorUsesRealTemporaryDurableStorage | PASS |
| Equal packaged version UpToDate after commit | equalPackagedVersionIsUpToDateOnlyAfterCommit | PASS |
| Unsigned/remote public-key rejection | unsignedLegacyAndEnvelopeSuppliedPublicKeysAreRejected | PASS |
| Invalid signature cannot reach parser/store | invalidSignatureOnMalformedPayloadNeverReachesParserOrStore; private constructor/control-flow trace | PASS |
| Signed malformed/future payload codes retained | authenticatedPayloadFailuresAndFutureSchemaPreserveCodes | PASS |
| Wrong target and write failure cannot offer | wrongTargetDoesNotOpenStoreAndFailedCommitDoesNotOffer | PASS |
| Incompatible metadata commits knowledge but no offer | incompatibleMetadataCommitsButNeverBecomesOffer | PASS |
| Missing deployment defaults disabled; no input/state work | defaultsAndMissingDeploymentAreDisabledWithoutReadingInput | PASS |
| Runtime/package identity and malformed identity handling | installedVersionMustMatchGeneratedNativePackageIdentity; missingMalformedOrOversizedIdentityCannotBecomeVersionZero; packaged-identity.json | PASS |
| Stream cap/ownership and copied source bytes | callerStreamIsBoundedAndRemainsCallerOwned; streamAndByteArrayUseTheSameAuthenticatedPipeline | PASS |
| Cancellation and sanitized failures | cancellationPropagatesAcrossCoordinatorAndDirectVerifier; ioFailuresExposeStableCodesWithoutRemoteText | PASS |
| Old/foreign/superseded/expired offer rejected | heldOfferIsRejectedAfterHigherSequenceAndCannotCrossClientIdentity; supersededLocalOfferCannotBeReused; heldOfferExpiresAtExactBoundary | PASS |
| No production unsigned helpers or test fixtures | usage-audit.txt; packaged-identity.json; actual no-callsite search | PASS |
| Full desktop regressions isolated | 141 tests, 15 classes, zero failures/errors/skips | PASS |
| Rebuilt standalone runtime and offline probes | full-package-build.log; packaged-results.json | PASS |
| Required repository PR lane | artifacts/quality/lanes/pr-20261102T092014Z/lane-summary.json | PASS_LOCAL, all nine gates |

## Commands and outcomes

Cached JDK/SDK/Gradle home; offline Gradle. Builds/lane sequential, no phone/emulator or live feed.

1. Focused command: `./gradlew.bat -PriffDesktop=true :desktop:test --tests '*DesktopUpdateClientTest' --offline --console=plain`. Initial run exit 1: 18 tests, one cancellation test failure. The verifier's generic exception catch swallowed CancellationException. Evidence: artifacts/desktop/update-coordinator/initial-focused-failure.log. Rethrow added; no weaker assertion or cancellation-to-error mapping.
2. Same focused command after correction: exit 0, 18 tests, zero failures/errors/skips. focused-build.log and focused-suite.json in artifacts/desktop/update-coordinator/.
3. `./gradlew.bat -PriffDesktop=true :desktop:test :desktop:createDistributable --offline --console=plain`: exit 0; 141 desktop tests, 15 classes, zero failures/errors/skips; standalone app rebuilt. Evidence full-package-build.log, full-suite.json, coordinator-tests.xml.
4. Actual packaged executable: C:/Volume D/Riff/desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe. Offline `--verify-update-fixture test-rfc8032-01 <public-key.der> <envelope.json>`: exit 0, VERIFIED_OPAQUE_PAYLOAD. Same flag with a temporary tampered copy: exit 2, INVALID_SIGNATURE. These remain opaque signature diagnostics, not coordinator eligibility or installation tests.
5. Same executable `--desktop-build-identity`: exit 0, RIFF_DESKTOP_IDENTITY 0.1.0 UPDATES_DISABLED CONFIGURATION_ERROR. Package cfg/resource match 0.1.0; production jar excludes legacy offer classes and test fixtures. Results: packaged-results.json, packaged-identity.json and diagnostic stdout/stderr files.
6. `rg -n 'evaluateManifest|compareSemVer|DesktopUpdateManifest|UpdateAvailable|parseSimpleJson' desktop/src`: only two expected matches, DesktopWindowStateManager's parseSimpleJson call and definition. All matches reviewed; no unsigned updater entry point survives. Evidence usage-audit.txt.
7. `python scripts/quality/verify.py --lane pr`: exit 0, PASS_LOCAL across all nine gates; 174 script tests passed and static checks reported zero findings. Evidence: artifacts/quality/lanes/pr-20261102T092014Z/lane-summary.json; collector build/manifest under artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-0ed34c22bb7b/lane-pr-20261102T092014Z/. This repository/Android lane does not replace the desktop checks above.

## Remaining work and review handoff

All assigned implementation verification is complete. Next action: review the coordinator, identity wiring, cancellation fix, migrated tests and corrected documentation against the contract. Source fingerprints and packaged identity/evidence are recorded; no later code changes occurred. No wire/security contract change, production trust selection, new mandatory policy or new activation behavior. Main's verifier probe was not relabelled as update eligibility; new identity diagnostic initializes neither UI nor user storage.

Next after review: Module 6 release signer/vector corpus. Production owner decisions remain actual release/channel, keys/custody, sequence/bootstrap, hosts, Windows compatibility and worker/migration tables, with explicit storage/transport integration. The coordinator requires configuration before enabling; it does not request credentials or choose those values.

Still open: HTTP collector timeouts/content encoding/identity requests and cancellation; allowlisted redirects/private-address checks; artifact staging, exact size/hash binding, Authenticode, installer activation/receipt, cross-process activation serialization, compatible-data recovery and clean Windows VM acceptance. State deletion/reinstall, bad initial clocks, trusted-signer compromise and lack of a latest-release oracle retain the contract's limitations. No M6 or production release completion claim. Module 6 has not started.