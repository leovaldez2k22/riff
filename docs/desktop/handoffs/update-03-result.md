# UPDATE-03 — Strict authenticated payload parser

Date: 2 November 2026. Implementer: Codex. Status: READY_FOR_REVIEW. Implementation and required checks passed; independent review is still required.

## Source identity and scope

Starting HEAD: `e4748bf6a59aa4d56575ef1c97215f2a58b05c92`. Existing shared checkout has extensive tracked Android changes and untracked desktop/documentation trees. No commit, reset, cleanup or unrelated source replacement was performed. A sandboxed Git status failed to identify a worktree; access-authorized Git confirmed the actual HEAD and dirty tree. The eventual evidence inventory records hashes of this module's files because HEAD alone cannot identify untracked desktop source.

This implements update module UPDATE-03, not desktop milestone M3. The existing unsigned `DesktopUpdateClient` is unchanged. No live updater integration, eligibility, freshness storage, feed access, download, installation, production keys or account/device actions are included. No new dependencies were requested or downloaded. Existing fixed Module 2 vectors were not modified.

## Changed files and interface

All Kotlin files below are in `desktop/src/{main,test}/kotlin/com/musicglass/desktop/`.

| File | Purpose |
|---|---|
| main/UpdatePayloadParser.kt | Bounded structural JSON reader; authenticated manifest schema and URI syntax validation; immutable manifest/artifact |
| main/UpdateVersion.kt | Strict numeric three-component version value, comparator, equality and hash |
| test/SignedUpdatePayloadFixture.kt | Test-only signed payload builder through the real envelope verifier |
| test/UpdatePayloadParserTest.kt | Positive, authenticated-negative and boundary cases |
| test/DesktopHostTestFixture.kt | Temporary injected database roots and host shutdown for existing host tests |
| test/WorkspaceLayoutTest.kt | Uses isolated host fixture instead of real user-home database |
| test/RenderingContractTest.kt | Karaoke host test uses the same isolated fixture |

`ValidatedUpdateManifest.parse(VerifiedUpdatePayload)` returns `UpdatePayloadParseResult.Valid(manifest)` or `Rejected(UpdatePayloadError)`. Errors are `INVALID_PAYLOAD`, `SIZE_LIMIT`, `UNSUPPORTED_FORMAT`. No String/ByteArray production parser entry point exists. Manifest construction is private, with no data-class copy bypass. It retains schema fields, a single immutable `artifact`, authenticated `payloadSha256` and immutable `signer` context. `UpdateVersion.parse(String)` returns a strict version or null; numeric comparison does not subtract components or overflow.

Module 4 should consume these values and the test-only fixture builder. It must still check configured product/channel/target, signer permissions, host allowlist, local configuration, clock, upgrade compatibility, freshness and durable state. A schema-valid manifest is not an eligible update. No existing caller was migrated into update decisions.

## Error precedence and bounded parsing

1. Authentication happens in `VerifiedUpdatePayload.verify` before this parser can be called. The parser defensively checks the body cap again, strictly decodes UTF-8, then reads a complete bounded JSON value.
2. Generic structural checks reject malformed input, decoded duplicate names, null, BOM, trailing input, invalid escapes/surrogates, invalid unsigned-integer grammar and resource violations. Root must be an object. No raw remote text, URI, query or exception message enters a rejection.
3. Missing/noninteger schemaVersion is INVALID_PAYLOAD. After complete bounded structure, a well-typed unsupported numeric schema returns UNSUPPORTED_FORMAT before v1 field sets/counts. Future fields cannot bypass global resource limits.
4. Version 1 requires exactly 14 root fields, exactly one artifact with six fields, all exact types/ranges, strict versions, timestamp ordering/lifetime, identifiers, target/artifact consistency, notes, digest and URI rules. Field-range/URI/schema failures are INVALID_PAYLOAD; parser resource/token bounds are SIZE_LIMIT.

Body: 65536 bytes. Depth: three containers including root. Property names: 32 scalars/128 decoded UTF-8 bytes. Ordinary strings: 4096 scalars/16384 decoded bytes. Raw string tokens: 32768 bytes including quotes/escapes. Numbers: at most 16 digits. String accounting is incremental, including surrogate pairs. The reader's generic node/member/array safety caps are above the maximum physically possible within a 64 KiB body; v1 exact member counts apply only after version dispatch, preserving revision-2 future-schema behavior. No unbounded tree or recursive depth is accepted.

URI parsing uses one `java.net.URI` interpretation. Accepted path/query spelling is retained; no DNS, host allowlist, redirect or network work occurs. HTTPS, ASCII DNS authorities/default port, absolute path, percent encoding, forbidden path octets and decoded dot segments are checked. Notes remain plain text; presentation escaping is the later consumer's responsibility.

## Acceptance mapping

| Criterion / contract cases | Evidence | Status |
|---|---|---|
| Verified valid fields, digest and signer; immutable boundary | verifiedManifestRetainsValuesDigestAndSigner; constructor/factory manual trace | PASS |
| V11 duplicate/escaped-equivalent names | duplicateDecodedNamesFailAtEveryObjectLevel | PASS |
| V12 UTF-8/BOM/surrogate/trailing input | signedMalformedUnicodeBOMAndTrailingDataFail | PASS |
| V13 body, decoded/raw strings, names, scalars/bytes, numbers, depth, members | bodyBoundaryIsEnforcedByAuthenticatedPipeline; scalarAndDecodedByteLimitsAreEnforced; rawTokenLimitCountsEscapeBytesAndQuotes; integerLexicalRulesAreStrict; arraysMembersAndContainerDepthAreChecked; final boundary fixtures | PASS |
| V14 hash/types/null/missing/unknown fields | hashesAreExactlyLowercaseHex; unknownMissingNullAndWrongTypesFail; artifactCardinalityAndHashTypesAreExact | PASS |
| V17 remote version syntax/order/range | versionGrammarAndComparisonHaveNoCoercionOrOverflow; numericFieldRangesAndVersionOrderingAreChecked | PASS |
| V23 timestamp lifetime/order | timestampLifetimeIsSchemaOnlyNotCurrentClock | PASS |
| V25 valid multilingual/escaped Unicode | validUnicodeEscapesAndNotesArePreserved; scalar boundary tests | PASS |
| V30 URI syntax, hash, extra arguments; opaque query preservation | URLSyntaxRejectsDangerousOrAmbiguousForms; URLLengthBoundaryAndNoSensitiveDiagnosticText; unknown-field tests | PASS for syntax; policy/downloader portions deferred |
| V31/V36 future schema and duplicate schemaVersion | futureSchemaDispatchesBeforeV1FieldsButAfterStructure; genericStructureAndPropertyBoundariesApplyToFutureSchemas | PASS |
| R2 syntax versus policy | targetMismatchIsLeftForPolicyWhileInconsistencyFails | PASS |
| Envelope verifier regression | 15 existing verifier tests passed with initial focused and full runs | PASS |
| Existing persistent-state test prerequisite | Final full desktop suite: 92 tests, zero failures/errors/skips after temporary-root injection | PASS |
| Required PR lane | artifacts/quality/lanes/pr-20261102T064451Z | PASS_LOCAL; all nine gates |

All negative parser fixtures are freshly signed with the public TEST-ONLY RFC 8032 seed already documented by Module 2, using JCA Ed25519 and the exact length-prefixed preimage. They must successfully traverse the real envelope verifier before reaching parser assertions. The old independently generated OpenSSL vector remains unchanged; these additional fixtures exercise schema validation rather than claim independent crypto reproduction. Oversized body rejection occurs at the verifier boundary as intended.

## Commands and evidence

Toolchain: cached `.toolchain/jdk-21.0.12.1+1`, `.toolchain/sdk`, `.toolchain/gradle-user-home`; offline Gradle. Commands run sequentially.

1. `./gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdateEnvelopeVerifierTest' --tests '*UpdatePayloadParserTest' --offline --console=plain`: initial XML reports 15 verifier + 18 parser tests, zero failures/errors/skips. Terminal completion metadata was lost during context rollover; a final explicit rerun is required rather than infer its exit status.
2. `./gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain`: exit 0; 90 tests across 13 classes, zero failures/errors/skips, after host storage isolation. This closes the previously recorded expected-0/observed-1671 failure without touching real user data. Two additional boundary tests were then added and passed in the final focused/full reruns below.
3. `python scripts/quality/verify.py --lane pr`: exit 0, PASS_LOCAL; all nine gates PASS. Evidence: `artifacts/quality/lanes/pr-20261102T064451Z/lane-summary.json`; collector build log under `artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-0ed34c22bb7b/lane-pr-20261102T064451Z/`. Script tests: 174 passed; static: 232 files, zero findings. Repository/Android lane does not replace desktop acceptance.
4. Final focused command, identical to command 1: exit 0; 35 tests (15 verifier + 20 parser), zero failures/errors/skips. Evidence: `artifacts/desktop/update-parser/focused-build.log` and `focused-suite.json`.
5. Final full command, identical to command 2: exit 0; 92 tests across 13 classes, zero failures/errors/skips. Evidence: `artifacts/desktop/update-parser/full-build.log` and `full-suite.json`. Initial full-suite evidence is retained in `initial-full-suite.json`.
6. Ending HEAD remains the starting HEAD. `artifacts/desktop/update-parser/source-inventory.json` records SHA-256 hashes of implementation, affected tests and the governing verifier/contract/brief. Existing unrelated dirty changes remain. Build deprecation warnings remain; no new dependency download was needed.

## Deviations and handoff

No contract or production verifier changes. No unresolved schema-policy ambiguity was used to invent deployment values. Future-schema member handling follows revision 2: generic bounded structure precedes exact v1 field-set/count validation.

All assigned implementation checks are complete. Next action: review the parser, version type, signed fixtures, isolation change and evidence against contract revision 2. Resolve any review findings before accepting the immutable manifest API for Module 4. Independent review remains a separate step. Module 4 has not started.

Owner-controlled production keys, release/channel/version decisions, hosts, sequence bootstrap and compatibility tables remain unset and outside this module. Later downloader/installer and clean-machine acceptance remain open; this report makes no M6 or production-security completion claim.