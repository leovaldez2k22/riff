# UPDATE-03 — Strict authenticated payload parser: Gemini execution brief

Date: 2 November 2026. Implementer: Gemini. Reviewer: Codex after handoff.
Prerequisite: Module 2 reviewed, or its unresolved evidence/defects explicitly recorded. Read [shared instructions](README.md) first.

## Objective

Convert only cryptographically verified payload bytes into a deeply immutable, schema-valid desktop update manifest. Prevent signed malformed metadata from reaching eligibility decisions. Authentication alone must never produce an update offer.

## Current state

Read:
- docs/desktop/update-trust-contract-v1.md, sections 3, 6–7, 10 and 12.
- docs/desktop/handoffs/update-02-result.md and update-01-review.md (including follow-up resolutions).
- desktop/src/main/kotlin/com/musicglass/desktop/UpdateEnvelopeVerifier.kt.
- desktop/src/test/kotlin/com/musicglass/desktop/UpdateEnvelopeVerifierTest.kt and src/test/resources/update-v1/ under desktop/.
- DesktopUpdateClient.kt and DesktopWindowStateManager.kt in the same production package.

Existing VerifiedUpdatePayload has a private constructor, defensive bytes(), sha256Hex() and authenticated signer context. The envelope reader is private and implements only the five-field outer envelope. Its current opaque fixture is not necessarily a valid manifest. The old client uses parseSimpleJson and permissive version conversion; neither is an acceptable payload parser.

Proposed owned files: UpdatePayloadParser.kt, UpdateVersion.kt, UpdatePayloadParserTest.kt and clearly named test payload fixtures. All Kotlin paths are under the existing desktop production/test package. Create docs/desktop/handoffs/update-03-result.md. Keep the existing opaque vector immutable.

## Implementation approach

1. Reconfirm actual verifier accessors and introduce a parser accepting VerifiedUpdatePayload, never arbitrary production String/ByteArray input. Return a validated manifest or a stable typed rejection. Keep payload digest and signer permissions attached to the result.
2. Implement a bounded structural JSON reader or narrowly reusable existing reader. No regex/parseSimpleJson, unbounded JSON tree, new library download or parse-after-reserialization. Validate strict UTF-8, BOM rejection, escapes, scalar/byte accounting, duplicate decoded names and trailing input. Enforce depth/token/member limits while reading; use contract section 3 verbatim for constants.
3. Dispatch schemaVersion only after valid bounded structure, duplicate checks and global limits. Missing/noninteger schemaVersion is INVALID_PAYLOAD. An authenticated well-typed unsupported version is UNSUPPORTED_FORMAT before v1-specific fields/counts, even with future fields. Do not let future schemas bypass global limits.
4. For v1 require the exact 14 root fields and one six-field artifact. Enforce exact types, unsigned integer lexical grammar/ranges, strict three-component version, timestamp ordering/lifetime, identifier syntax and target/artifact consistency. Reject unknown fields, null, strings-as-booleans/numbers and mandatory. Avoid overflowing arithmetic at boundary values.
5. Create one strict numeric version value/comparator for later policy/signer reuse. Components 0..2147483647, total 5..32 ASCII bytes; reject prefixes/suffixes, signs, spaces, missing components and leading zeros.
6. Validate the contract's URI syntax and path restrictions, digest encoding, size and bounded plain-text notes. Preserve accepted URL path/query bytes and never log queries. Keep host allowlist, configured product/channel/target equality and signer permissions in Module 4. Syntactically valid other identifiers reach policy; internally inconsistent artifacts fail here.
7. Use private construction/factories without a public data-class copy bypass. Copy collections/byte arrays defensively. Map schema/URI/type errors to INVALID_PAYLOAD, resource/token limits to SIZE_LIMIT. Preserve cancellation. Do not parse payload JSON for update decisions until signature verification succeeds.

## Scope boundaries

Own parsing and syntax only. No clock/state storage, compatibility decisions, network, artifact access, production keys or coordinator integration. Do not change unrelated window-state parseSimpleJson callers. Do not broaden the outer-envelope contract or mutate existing crypto vectors. Any required verifier API refactor must preserve its tests and be explained.

## Acceptance criteria

- Verified valid v1 fixture yields the exact fields, digest and signer context; no unverified parser-to-offer path exists.
- Signed duplicate/escaped-equivalent keys, malformed UTF-8/BOM/surrogates, null, missing/unknown fields, wrong types, bad integers/versions/hashes/URI paths fail deterministically.
- Exact-limit/one-over cases cover body, raw/decoded strings, Unicode scalars/bytes, numbers, depth and members. Deliberately invalid shapes cannot cause excessive allocation or recursion.
- Future-schema extra-field fixture returns UNSUPPORTED_FORMAT; duplicate schemaVersion remains INVALID_PAYLOAD (V36).
- Well-formed wrong product/target survives syntax validation for Module 4; inconsistent target/artifact fails (R2).
- V11–V14, V17 remote-version portion, V23, V25 Unicode, V30 syntax, V31 and V36 are mapped to tests. Negative-schema fixtures have correct signatures so they actually exercise this parser.
- Existing verifier suite remains green; no live feed or real user state touched.

## Verification commands

After shared toolchain setup:

```powershell
.\gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdateEnvelopeVerifierTest' --tests '*UpdatePayloadParserTest' --offline --console=plain
.\gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain
python scripts/quality/verify.py --lane pr
```

Include the chosen version-test class in the focused command if separate. Inspect test reports; do not assume a zero exit code with zero matching tests proves coverage. Manual review: compare all field/limit constants to contract tables, trace every constructor/factory and confirm URL/query data is absent from errors.

## Handoff requirements

Write update-03-result.md using the shared report format. Include parser API, exact error precedence, limit coverage, fixture signing method and case-ID mapping. Identify any contract ambiguity before changing policy. Hand Module 4 a reviewed immutable manifest/version API and test fixture builder; stop for review.
