# UPDATE-04 — Compatibility and durable freshness policy

Date: 2 November 2026. Implementer: Codex. Status: READY_FOR_REVIEW. Implementation, desktop tests and required PR lane passed. Independent review has not occurred.

## Identity, prerequisite review and scope

Starting HEAD: `e4748bf6a59aa4d56575ef1c97215f2a58b05c92`. This is the existing extensively dirty shared checkout, including untracked desktop/documentation trees. No commit/reset/cleanup or unrelated source replacement. Ending HEAD is unchanged. Module-owned hashes are in artifacts/desktop/update-policy/source-inventory.json; HEAD alone cannot identify these files.

Before implementation, read the Module 3 report and traced its private manifest/version constructors, strict schema/version checks, digest retention and immutable signer context against contract revision 2. No blocking parser defect was identified in this self-review; it is not independent approval or a security certification. The production parser, version and envelope-verifier hashes still match the Module 3 evidence. The final complete desktop suite reruns all their tests. Only the test signing helper was extended with an optional test key ID for bound-ID rotation fixtures; existing default behavior and fixed vector files remain unchanged.

This is update module UPDATE-04, not desktop milestone M4. No UI, Android behavior, real user database, feed/network request, production key/configuration, installer, download or account/device operation was added. No new dependencies were downloaded. Production still uses the old unsigned client until Module 5; this module is not a production-secure updater or M6 completion claim.

## Files and API

All Kotlin paths below are under `desktop/src/{main,test}/kotlin/com/musicglass/desktop/`.

| File | Purpose |
|---|---|
| main/UpdateEligibilityPolicy.kt | Explicit immutable local configuration, restricted eligible metadata, target/compatibility/time/freshness evaluation and revalidation |
| main/UpdateFreshnessStore.kt | Bounded checksummed state, scoped cross-process transaction lock, forced temporary write and Windows durable publication |
| test/UpdatePolicyFixture.kt | Signed schema-valid metadata, fixed clocks and isolated in-memory transaction fixtures |
| test/UpdateEligibilityPolicyTest.kt | 22 deterministic policy tests |
| test/UpdateFreshnessStoreTest.kt | 14 isolated file/concurrency/failure tests |
| test/UpdateFreshnessProcessProbe.kt | Test-classpath-only subprocess entry point for locking and interrupted publication |
| test/SignedUpdatePayloadFixture.kt | Optional test key ID; signs the ID in the existing preimage, defaults unchanged |

`UpdateEligibilityPolicy.evaluate(manifest, config, store, clock)` is suspending and accepts only `ValidatedUpdateManifest`. Results are `EligibleUpdate`, `UpToDate`, `AuthenticatedIneligible(reason)` or `Rejected(code)`. Configuration/state failures are Rejected; authenticated policy ineligibility carries a stable reason. No partial offer or remote exception message escapes. `EligibleUpdate` has a private constructor, final fields and no copy bypass; its companion factory executes the complete policy transaction. Offers retain the validated manifest, scope and effective evaluation time. State is accepted metadata knowledge, not an installation receipt.

`UpdateLocalConfiguration` requires scope, installed strict version, observed Windows/build, supported worker protocol set, installed database schema, explicit supported source-to-target schema pairs, artifact host set and bootstrap sequence/version/optional digest. Collections are defensive unmodifiable snapshots. Contract v1 product is com.musicglass.desktop, target windows-x64, channels preview/stable. Invalid local values return CONFIGURATION_ERROR; missing host/protocol/migration configuration is never silently defaulted. Different syntactically valid remote identities reach policy and return WRONG_TARGET. Artifact hosts compare case-insensitively without changing URL path/query bytes. Actual deployment values remain owner-owned; test values are not production choices.

`UpdateEligibilityPolicy.revalidate(offer, config, store, clock)` performs a fresh complete evaluation. It can reject an old offer after newer metadata or expiry. It does not hold an installation lease after returning. Module 5 and the later activation coordinator must serialize activation against newer checks and revalidate at the activation boundary; returning an offer does not authorize an installer to run.

`UpdateFreshnessStore.transaction(scope, action)` provides a locked read plus commit callback for the entire policy decision. `FileUpdateFreshnessStore(root)` takes an explicit owned app-data root, opens no default user-home directory and moves all filesystem work to Dispatchers.IO. Tests inject only TemporaryFolder roots. Module 5 must inject one canonical persistent app-data root shared by every supported app instance; for example a dedicated freshness subdirectory of the installed application's local app-data directory. No real app-data path was opened in this module.

## Transaction order and normalization

1. Validate trusted local configuration, strict installed/bootstrap versions and bootstrap bounds.
2. Check product/channel/target, authenticated signer permissions and configured host membership before accessing freshness state.
3. Acquire the exact scope's decision lock; read and strictly validate state; reconcile complete bootstrap/persisted bindings in memory.
4. Capture wall clock once under the lock. Reject >300-second backward movement; use max(now,persisted evaluation time). Enforce publication <= effectiveNow+300 and expiry > effectiveNow without grace.
5. Enforce sequence floor, same-sequence digest/version binding and independent highest-version floor. Lower sequence is STALE_RELEASE; changed bound digest is RELEASE_CONFLICT. With an unbound bootstrap, lower same-sequence version is ROLLBACK_REJECTED and higher is RELEASE_CONFLICT.
6. Commit the normalized accepted binding, maximum version floor and nondecreasing evaluation time atomically. Rejected remote inputs never write normalization. This one-commit ordering satisfies both normalization-before-eligibility and no state mutation for wrong/expired/stale/rollback metadata. Failed publication exposes no offer, UpToDate or compatibility result.
7. Under the same lock, apply installed minimum, observed Windows/build, worker protocol and explicit database migration-path compatibility. Valid matching incompatible releases still advance metadata knowledge. Equal installed/target version returns UpToDate after commit; newer compatible metadata becomes EligibleUpdate.

| Bootstrap B versus stored S | Effective sequence/version/digest binding | Independent floors |
|---|---|---|
| S absent | Entire B binding, optional digest | max(installed,B.version); time 0 |
| B.sequence > S.sequence | Entire B binding; discard S digest for the lower sequence | max(installed,B.version,S.highestVersion); preserve S time |
| B.sequence < S.sequence | Entire S binding | Same maxima and S time |
| Equal sequence, different versions | STATE_UNAVAILABLE; no write | No reset |
| Equal versions, two known different digests | STATE_UNAVAILABLE; no write | No reset |
| Equal versions, one known digest | Bind that digest | Same maxima and S time |
| Equal versions, both digests absent | Remain unbound until authenticated matching-version payload | Same maxima and S time |

Example S=10/A/1.0.0 plus B=20/B/2.0.0 accepts sequence 20/digest B; digest A is never carried into sequence 20. A higher independent S version floor remains binding even if B has the higher sequence. Installed version is always included independently.

## State format, locking and crash behavior

One state and one permanent lock file per SHA-256 of UTF-8 appId + NUL + channel + NUL + target, independent of key ID. All supported readers/writers acquire the same stable lock file; it must never be removed while instances may exist. FileChannel.tryLock serializes threads, store objects and processes; overlapping same-JVM locks wait just like external contention. Lock waiting is cancellable, uses a monotonic timeout (10 seconds by default) and fails STATE_UNAVAILABLE on timeout. Callback evaluation and publication remain under that lock. Cancellation remains cancellation.

State format is binary v1: 8-byte RIFFUPD1 magic, length-prefixed ASCII app/channel/target, signed-long storage of validated positive sequence, strict sequence-bound version, exact 0/1 digest-present flag and optional lowercase hex digest, strict highest version, validated nonnegative UTC evaluation seconds, then SHA-256 over the preceding bytes. At most 1024 bytes are read, plus one byte to detect overflow. Each string length is checked before reading, exact scope/magic/EOF/field ranges are required, highest version cannot be below the bound version. Checksum detects accidental corruption, not malicious local rewriting. Absence alone bootstraps; empty/truncated/corrupt/inaccessible state fails closed without reset or deletion.

Publication creates a generated same-directory pending file, writes the complete record, calls FileChannel.force(true), checks cancellation, then calls MoveFileExW(REPLACE_EXISTING | WRITE_THROUGH) without COPY_ALLOWED. Supported production storage is a fixed local Windows NTFS volume; publisher rejects other OS/filesystem/drive types rather than provide a weaker fallback. Same-directory NTFS replacement is the filesystem atomic-rename assumption; WRITE_THROUGH requests completion on disk. API reference: [Microsoft MoveFileExW](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-movefileexw). No copy/delete or delete-old-then-rename fallback exists. Pending-file cleanup preserves a primary failure/cancellation. Existing state is never proactively removed.

Before replacement, interruption leaves the prior state and possibly an ignored pending file. After successful replacement but before return, higher knowledge may be durable even if the caller receives no offer; conservative next checks retain it. The subprocess test kills a writer after force and before replacement, then verifies exact prior state recovery and lock release. Atomic rename plus OS/storage flush semantics are assumptions, not a simulation of every hardware power-loss mode. A storage device/controller ignoring flushes, local user-account tampering, directory/lock removal and unsupported filesystems are outside this guarantee. No power-cut, clean-VM or network-filesystem acceptance is claimed.

Manual failure trace: read/format/lock errors return STATE_UNAVAILABLE before callback; create/write/force failures cannot reach publication; replace failure returns STATE_UNAVAILABLE and no result; post-publication failure may leave newer state but no offer. Compatibility runs only after successful commit. Cancellation before commit or before replace prevents publication; cancellation at/after the indivisible replace boundary can leave committed state and propagates instead of returning an offer. No lower-floor recovery is implemented.

## Acceptance evidence

| Cases/criterion | Tests/manual trace | Result |
|---|---|---|
| V15 context/host/signer permissions; V27 key overlap | productChannelTargetSignerPermissionsAndHostAreScoped; signerRotationSharesFreshnessScopeAndPermissionsStillApply | PASS |
| V16 capabilities/minimum installed version; incompatible releases commit | incompatibilityCommitsMetadataKnowledgeBeforeRejection | PASS |
| V17 malformed installed version/config/bootstrap | invalidLocalConfigurationFailsWithoutOpeningStore | PASS |
| V18 version floors; V19 stale sequence | installedVersionIsAnIndependentFloor; higherSequenceCannotLowerAcceptedVersion; storedAboveBootstrapRetainsEntireStoredBinding | PASS |
| V20 equivocation; V21 idempotence | changedSameSequencePayloadConflictsAndIdenticalChecksAreIdempotent | PASS |
| V22 publication/expiry; V24 clock rollback | exactTimeBoundariesUseEffectiveTimeWithoutSleeping; clockIsCapturedOnceAndNegativeTimeFailsClosed | PASS |
| V23 lifetime schema prerequisite | Existing UpdatePayloadParserTest.timestampLifetimeIsSchemaOnlyNotCurrentClock rerun in full suite | PASS |
| V24 state corruption/empty/truncation/limits/read/write denial/replacement | File-store format, read-lock, readonly-target and failed-publication tests | PASS |
| V28 concurrent ordering and stale-offer revalidation | concurrentChecksAndOldOfferRevalidationCannotRegressKnowledge; concurrentStoreInstancesSerializeReadEvaluateAndCommit; separateProcessesShareTheWholeTransactionLock; separateProcessCannotAcquireLockWhileParentTransactionIsOpen | PASS for metadata; later activation serialization remains open |
| V33 complete B/S merge and independent floors | absent/below/equal/above bootstrap tests; independentVersionAndTimeFloorsSurviveBootstrapReplacement | PASS |
| V34 local equal-sequence conflicts | equalSequenceLocalVersionsOrKnownDigestsCannotConflict | PASS |
| V35 bootstrap without digest | unboundBootstrapBindsOnlyItsDeclaredVersion; equalSequenceMergeUsesTheOneKnownDigestOrLeavesUnbound | PASS |
| Failed writes expose no eligible/up-to-date/incompatible outcome | failedCommitCannotExposeOfferOrUpToDateOrIncompatibleResult; failedAtomicReplacementPreservesPreviousRecordAndDoesNotOffer | PASS |
| Interruption, cancellation, off-UI IO | processTerminationBeforeReplacementPreservesDurableState; cancellation/lock-wait tests; ioRunsOutsideCallingUiThread | PASS |
| Envelope/parser regressions; no user-home fixtures | Complete desktop suite, 128 tests across 15 classes, zero failures/errors/skips | PASS |
| Required repository PR lane | artifacts/quality/lanes/pr-20261102T074531Z/lane-summary.json | PASS_LOCAL, all nine gates |

Tests sign metadata using the public TEST-ONLY RFC seed and real verifier/parser. All policy rejection cases start with authenticated schema-valid metadata. Tests use fixed clocks without waiting for wall-clock changes. Process tests synchronize using temporary barrier files; waits only coordinate owned subprocesses and locks, not clock policy.

Subprocess invocation is generated by the store test using the same cached JDK and test-runtime classpath:
`java.exe -cp <test runtime classpath> com.musicglass.desktop.UpdateFreshnessProcessProbe <temporary-root> <ready-file> <release-file> <sequence> <mode>`.
Modes hold a transaction, write normally, pause immediately before publication, or require a lock timeout while a parent holds the transaction open. Tests spawn sequence 20 then 10 against one root, terminate an owned sequence-20 writer before publication over durable sequence 10, and require a separate process to time out while its parent still owns the lock. The explicit contention test prevents process-startup timing from being mistaken for proof of cross-process exclusion. The probe exists only in test classes and is not wired to application Main.

## Commands, failures and reruns

Cached JDK 21/SDK/Gradle user-home as in handoff; Gradle offline; builds run sequentially.

1. Initial focused command below: compileTestKotlin failed (exit 1); two expression-body JUnit methods inferred internal return types. Added explicit runBlocking<Unit>. Production compiled in that attempt. No test was claimed passing from the failed compile.
2. Focused rerun: exit 0, 32 tests (21 policy, 11 store), no failures/errors/skips. `artifacts/desktop/update-policy/focused-build.log`.
3. Added signer-rotation and self-consistent corrupt-format/read-denial cases: focused exit 0, 35 tests. Initial full desktop run exit 0, 127 tests.
4. Final contract review tightened local product identity to the contract's fixed appId. Reran focused and full commands sequentially after that change: both exit 0 at that point; 35 focused (22 policy + 13 store), 127 total, all zero failures/errors/skips. Log paths (now containing final reruns from step 6): `artifacts/desktop/update-policy/focused-final-build.log`, `full-build.log`; structured counts: `focused-suite.json`, `full-suite.json` in that directory.
5. `python scripts/quality/verify.py --lane pr`: exit 0, PASS_LOCAL, all nine gates PASS; 174 script tests, static checks zero findings. Evidence: `artifacts/quality/lanes/pr-20261102T074531Z/lane-summary.json`; collector manifest/build log under `artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-0ed34c22bb7b/lane-pr-20261102T074531Z/`.
6. Final evidence review strengthened cross-process contention with an explicit parent-held-lock/child-timeout test. After that test-only change, repeated focused and full desktop suites: both exit 0; **36 focused tests (22 policy + 14 store), 128 total tests across 15 classes; zero failures/errors/skips**. No production or repository/Android lane input changed after PR. Recomputed collector source digest matches the passing lane exactly; `artifacts/desktop/update-policy/pr-source-check.json` records it. Existing PR evidence is reused only for its unchanged repository/Android inputs, not as desktop-test evidence.
7. Final artifacts in `artifacts/desktop/update-policy/`: source-inventory.json (13 source/dependency/contract fingerprints), focused-suite.json, full-suite.json, focused-final-build.log, full-build.log, policy-tests.xml and store-tests.xml. No later production-code changes occurred.

Focused command: `./gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdateEligibilityPolicyTest' --tests '*UpdateFreshnessStoreTest' --offline --console=plain`.
Full command: `./gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain`.

## Remaining work and boundaries

All assigned implementation checks passed. Next action is review of the immutable policy boundary, state reconciliation/publication and evidence before Module 5. No contract changes or policy weakening. The normalization ordering above makes explicit how section 8 is implemented: reconcile in memory and atomically commit only a response passing precommit acceptance checks. Scope/product/target rules follow v1 rather than invent production variant identities.

Module 5 must wire verified bytes -> validated manifest -> this policy using explicit local configuration, fixed owned root and clock; remove the unsigned decision entry point; preserve typed errors/cancellation; arrange activation revalidation/serialization. Actual installed/package version discrepancy (package 0.1.0 versus old client 0.20.0), production bootstrap/sequence/key/hosts, Windows build acquisition and worker/migration tables remain owner/integration decisions. Observed existing database CURRENT_SCHEMA_VERSION is 2 and host protocolMajor is 1; neither was copied into a production updater default.

Storage is not tamper-proof. Deleting state/reinstalling can reset knowledge to bundled floors; incorrect initial time is not reliably detectable; no latest-release oracle or defense against response withholding exists. Trusted-signer compromise and bad signed incompatible releases can deny updates until a higher-sequence/nondecreasing correction or independently authenticated manual recovery. Downloader, redirects/private-address checks, artifact integrity, Authenticode, installer activation, installation receipts and safe data recovery remain separate work. Stop for review; Module 5 has not started.