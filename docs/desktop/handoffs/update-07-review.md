# UPDATE-07 — Integration review

Date: 2 November 2026. Reviewer/preparer: Codex, also author of preceding implementation. **Scoped self-review, not an independent security assessment or approval on another reviewer's behalf.** Final scoped verdict: READY_FOR_REVIEW. All required local checks passed; external/independent approval remains unclaimed. See update-07-result.md.

Source baseline HEAD e4748bf6a59aa4d56575ef1c97215f2a58b05c92, dirty/shared with untracked desktop sources. Compared current symbols against contract revision 2 and prior module fingerprint/evidence reports; a clean commit diff cannot identify the untracked implementation. Final source inventory/package hash accompany result. Existing unrelated Android changes preserved.

## Findings and corrections

| ID | Severity | Exact location / trigger | Impact | Correction and evidence |
|---|---|---|---|---|
| R7-01 | P2, closed | UpdateEnvelopeVerifier.kt, EnvelopeReader.read (currently around lines 175–185): append publicKey or duplicate keyId after the five valid fields | Always rejected, but returned SIZE_LIMIT rather than the specified structural INVALID_ENVELOPE; result changed with field order | Read bounded name and reject unknown/duplicate before member guard; no sixth value can be inserted. New trailingUnknownAndDuplicateMembersKeepEnvelopeErrorCategory reproduced pre-fix failure, then full suite and packaged appended-public-key case passed. No format/acceptance relaxation. |
| R7-02 | P2, closed | scripts/quality/desktop_smoke.py, former run() JVM environment | Automated packaged smoke could restore/checkpoint real user-home database/window state, invalidating isolation and affecting later tests | --isolate-storage creates separate owned temporary Java home/USERPROFILE/LOCALAPPDATA/APPDATA per run; removes inherited JVM override flags. Three harness regressions passed; six real smoke runs wrote only under isolated roots and cleaned them. No real state was read/deleted for acceptance. |
| R7-03 | P2, closed | scripts/quality/desktop_smoke.py, main() runs/scales and all(empty) gate | --runs 0 could return success without a launch; malformed scale lists could produce unusable evidence | Positive run count and nonempty positive finite scales required by argparse. CLI --runs 0 now exits 2, creates no result file and launches nothing. Unit tests cover zero/negative/nonfinite/empty cases. |

No unresolved P0/P1 metadata-pipeline defect identified within the stated threat model. This does not certify absence of vulnerabilities or authorize live deployment/installation. Fresh verification and remaining external gates are in the result report.

## Trust-boundary trace

1. DesktopUpdateClient defaults disabled without explicit local keys/configuration/storage and matching generated desktop identity. No current UI/runtime caller or legacy unsigned fallback was found. Byte/stream collection is bounded and off UI thread; stream ownership stays with caller. HTTP timeouts/encoding remain an absent collector responsibility.
2. VerifiedUpdatePayload private construction follows bounded five-field outer parse, known trusted key, canonical Base64 and Ed25519 over exact domain/length-prefixed bytes. Payload is not JSON-interpreted for update decisions before verification. Returned bytes and trust permission sets are defensive/immutable. Unsupported outer format is rejected without negotiation. Cancellation propagates.
3. ValidatedUpdateManifest has a private constructor and no copy bypass. One bounded structural JSON reader handles strict UTF-8, decoded duplicate keys, scalars/bytes/raw tokens/depth/integers and future-schema dispatch; v1 exact fields/types/ranges follow only for version 1. URI is interpreted by java.net.URI, without DNS or network; only syntax/consistency here, host allowlist in policy. Notes remain bounded plain text, never rendered as HTML here.
4. validateForSigning(ByteArray) returns only a nullable error/status and cannot return a manifest/offer. Its private read path exits on success before construction when no authenticated payload exists. Standalone validation class does not invoke graphical Main, registry, state or network. This local maintainer status API is not an unsigned update-decision path.
5. Policy checks trusted configuration, app/channel/target, signer permissions and host before freshness storage. File scope is app/channel/target, independent of key. Entire read/reconciliation/time/floor/commit/compatibility decision is under a stable cross-process lock. Bootstrap sequence/digest/version move together; independent highest-version and time floors never decrease.
6. Read state distinguishes absent from corrupt/empty/inaccessible, is bounded/checksummed/exact-scope and never resets corruption. Atomic publication forces a same-directory temporary record then uses local NTFS write-through replacement without copy/delete fallback. Required failures return STATE_UNAVAILABLE; no offer escapes failed commit. Local tampering/deletion, unsupported storage and flush-ignoring hardware are outside guarantees.
7. Time captured once under lock; effectiveNow=max(now,persisted time), >300s rollback fails, +300s publication tolerance and strict expiry. Sequence conflicts/version rollback reject without writes; valid matching incompatible metadata commits high-water knowledge before INCOMPATIBLE. Metadata knowledge is not install receipt.
8. EligibleUpdate private factory runs full policy; coordinator retains at most one copied envelope/reference. Revalidation re-verifies/reparses/reevaluates state/time and rejects old/foreign/superseded/expired references. It is not a perpetual install lease: future activation must serialize against checks, including across processes.
9. Node maintainer CLI uses the same schema status checker before key access; independent preimage assembly and vetted OpenSSL Ed25519; canonical DER/key algorithm and optional expected public key. No secrets in arguments/env/log output, only private-key file path. Atomic no-clobber hard-link publication and failure injection tested. No URL fetch or actual release signing.
10. Production distribution contains the syntax-only helper and explicit opaque test diagnostic, but no test trust installed globally. Diagnostic test key ID is locally supplied, prefix-constrained and scoped to that invocation; verified opaque payload is not an eligible result. JAR resources/classes and both known private test DER/seed forms are scanned absent. Normal client remains disabled.

## V01–V36 coverage map

PASS means named source tests rerun in the complete suite, supplemented where stated by packaged diagnostics; it is not independent certification. PARTIAL explicitly separates later gates; OPEN means intentionally unimplemented outside metadata scope. No case has an unknown status.

| Case | Current evidence | Status |
|---|---|---|
| V01 | DesktopUpdateClientTest.signedCompatibleFreshMetadataCommitsBeforeEligible; fullCoordinatorUsesRealTemporaryDurableStorage; corpus valid; packaged valid signature | PASS metadata |
| V02 | UpdateEnvelopeVerifierTest.alteredPayloadOrSignatureFails; corpus payload-tamper; packaged tampered | PASS |
| V03 | Same source test, wrongValidPublicKeyFailsSignature, corpus signature-tamper; packaged wrong-key with distinct valid DER | PASS |
| V04 | keyIdIsAuthenticatedEvenForAliasOfSameKey; corpus key-id-tamper | PASS |
| V05 | unknownInactiveAndInvalidLocalKeysFailClosed | PASS |
| V06 | supportedFormatDoesNotNegotiate | PASS |
| V07 | unsignedLegacyAndEnvelopeSuppliedPublicKeysAreRejected; new trailingUnknownAndDuplicateMembersKeepEnvelopeErrorCategory; packaged unsigned/extra-public-key | PASS after R7-01 |
| V08 | canonicalBase64AndSignatureLengthAreMandatory | PASS |
| V09 | That signature-length test; publicKeyConfigurationHasCanonicalBase64AndOwnsDer; invalid registry tests | PASS |
| V10 | duplicateEscapedEquivalentAndUnknownFieldsFail plus new trailing duplicate regression | PASS |
| V11 | UpdatePayloadParserTest.duplicateDecodedNamesFailAtEveryObjectLevel; signed duplicate corpus | PASS |
| V12 | invalidJsonUnicodeAndTypesFail; signedMalformedUnicodeBOMAndTrailingDataFail; signed UTF-8/BOM/surrogate corpus | PASS |
| V13 | Envelope/payload body boundary tests, raw/decoded scalar/string/number/depth/property tests; exact/over body/notes corpus | PASS |
| V14 | unknownMissingNullAndWrongTypesFail; hashesAreExactlyLowercaseHex; artifactCardinalityAndHashTypesAreExact | PASS |
| V15 | productChannelTargetSignerPermissionsAndHostAreScoped; wrong product/host corpus; coordinator target spy | PASS |
| V16 | incompatibilityCommitsMetadataKnowledgeBeforeRejection; incompatible worker corpus; coordinator incompatible commits test | PASS |
| V17 | versionGrammarAndComparisonHaveNoCoercionOrOverflow; invalidLocalConfigurationFailsWithoutOpeningStore; runtime identity tests | PASS |
| V18 | installedVersionIsAnIndependentFloor; higherSequenceCannotLowerAcceptedVersion; rollback corpus | PASS |
| V19 | storedAboveBootstrapRetainsEntireStoredBinding; stale corpus; heldOfferIsRejectedAfterHigherSequenceAndCannotCrossClientIdentity | PASS |
| V20 | changedSameSequencePayloadConflictsAndIdenticalChecksAreIdempotent; conflict corpus | PASS |
| V21 | Same idempotence test; equalSequenceMergeUsesTheOneKnownDigestOrLeavesUnbound | PASS |
| V22 | exactTimeBoundariesUseEffectiveTimeWithoutSleeping; corpus expired/not-yet-valid; heldOfferExpiresAtExactBoundary | PASS |
| V23 | timestampLifetimeIsSchemaOnlyNotCurrentClock | PASS |
| V24 | Clock rollback boundaries; empty/corrupt/bounded state, readonly/write and mandatory read-lock failure tests; no reset | PASS |
| V25 | Unicode/escape/scalar tests and multilingual corpus; original signature retained after whitespace modification fails | PASS |
| V26 | outerWhitespaceAndFieldOrderDoNotAlterMessage | PASS |
| V27 | Two distinct TEST-ONLY keys in corpus; coordinator accepts overlap, old-key-only registry gets UNKNOWN_KEY; permission tests | PASS metadata; production rotation deployment not exercised |
| V28 | concurrentChecksAndOldOfferRevalidationCannotRegressKnowledge; separateProcessCannotAcquireLockWhileParentTransactionIsOpen; sequence-20/10 subprocesses; held-offer revalidation | PARTIAL: metadata ordering PASS; actual activation/installer serialization OPEN |
| V29 | No downloader/installer capability exposed | OPEN: exact downloaded size/hash and no-execution integrity gate needs downloader brief |
| V30 | URLSyntaxRejectsDangerousOrAmbiguousForms; URI length/path/credentials tests; host/extra-field rejection; bad-url corpus | PARTIAL: metadata syntax/host/arguments PASS; redirects/DNS/private-address downloader enforcement OPEN |
| V31 | futureSchemaDispatchesBeforeV1FieldsButAfterStructure; signed future-schema corpus | PASS |
| V32 | Malformed registry/key tests; direct cancellation regression; packaged provider-disabled process returns CONFIGURATION_ERROR and no Riff state | PASS |
| V33 | absentStateBootstrapsWithKnownOrUnknownDigest; bootstrapAboveStoredUsesCompleteBootstrapBinding; storedAboveBootstrapRetainsEntireStoredBinding; equal merge and independent floor tests | PASS |
| V34 | equalSequenceLocalVersionsOrKnownDigestsCannotConflict | PASS |
| V35 | unboundBootstrapBindsOnlyItsDeclaredVersion | PASS |
| V36 | Future-schema extra fields, global limits and duplicate schemaVersion tests; no premature v1 fields/counts | PASS |

## Runtime, isolation and evidence limits

Bundled runtime release records Java 21.0.12.1 with jdk.crypto.ec and jdk.accessibility; launcher/resource identity both 0.1.0. Valid/tampered/wrong-key/missing-provider/unsigned/extra-field cases execute the actual packaged binary. These are signature/error probes only; full parser/policy decisions are separately covered by source/JVM coordinator tests using identical emitted bytes. No claim of running an installer or live collector.

Six packaged smoke launches (three at simulated 1.0, three 1.25) all exit 0 with one Compose-frame marker. New roots contain the actual temporary .riff database/WAL/window state and GPU cache, then are removed. No default real-user database is used. Per-run observed compose marker 1850.04–2413.76 ms, sampled process-tree working set 254.7–269.1 MiB. These are warm-file-cache process starts, not GPU presentation/120Hz, mixed-monitor hardware, idle-tray RAM, gapless audio or clean-VM evidence. Temporary storage means cache conditions differ from historical measurements; no performance improvement/regression conclusion is inferred.

Original Module 2 full-suite expected-position-0/observed-1671 failure remains historical evidence, not erased. Module 3 injected temporary host databases; current full suite reruns all tests with no skipped or unexplained failing case. Current smoke storage isolation additionally closes the previously separate real-data launch risk. Earlier M6 authentication claims were corrected by Module 5 and remain superseded, not retroactively validated by old packaging passes.

## Verdict and separate gates

Required PR lane passed all nine gates after 177 script tests and fresh desktop/package evidence. Scoped metadata self-review and acceptance work are complete; no remaining high-severity finding is accepted as ready. All three demonstrated integration/harness findings are closed by regressions and fresh package tests. Final external review/security approval is not claimed. Production deployment is disabled and M6 remains incomplete until separately specified collector/download/integrity/Authenticode/activation/recovery/VM/owner gates are implemented and accepted.