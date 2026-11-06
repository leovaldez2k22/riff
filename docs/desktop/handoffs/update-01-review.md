# UPDATE-01 design review

Date: 2 November 2026
Reviewer: Codex, reviewing its own earlier draft in a separate pass; not an independent external security assessment.
Scope: docs/desktop/update-trust-contract-v1.md and UPDATE-01 result/brief consistency.
Verdict: REQUEST CHANGES. Do not issue the Module 2 implementation brief against this revision yet.

Line numbers below refer to the reviewed draft on this date. No production updater implementation exists for this protocol, so findings describe specification/implementation risks, not demonstrated exploits in a deployed verifier.

## Findings

### R1 — P1: Reconcile bootstrap and persisted freshness records as complete records

Location: update-trust-contract-v1.md:201 and :205.

The contract computes max(bundled floor, accepted sequence), then says an equal-sequence manifest must match the stored digest. It does not define which digest applies when an application upgrade ships a newer bootstrap than the persisted record.

Example: persisted sequence=10/digest=A/version=1.0.0; newly installed app bundles sequence=20/digest=B/version=2.0.0. A genuine manifest for sequence 20/digest B equals the effective floor, but comparing it to stored A returns RELEASE_CONFLICT. Alternatively, an implementer might drop the newer bundled digest and accept a conflicting sequence-20 payload. Neither is a specified safe outcome. This affects normal application upgrades and manual recovery, not just first installation.

Required correction: define a deterministic merge of scoped bootstrap and persisted records before evaluating incoming metadata. Sequence and its bound digest/version must remain associated. Specify cases for absent state, persisted below/equal/above bootstrap, optional bootstrap digest, same-sequence conflict, independent highest-version/time floors, invalid bootstrap configuration and failed persistence. Never replace a higher persisted floor with a lower bundled one. State whether normalization is committed before accepting an offer, and how concurrent checks see it. Add upgrade/rotation fixtures for every relation, including conflict failures.

Acceptance: implementers derive the same effective record for the example; B is accepted at sequence 20, a different digest at that sequence is rejected when B is known, and sequence 10 cannot regain eligibility. Higher persisted state survives an older installed bootstrap.

### R2 — P2: Target validation contradicts the advertised WRONG_TARGET result

Location: update-trust-contract-v1.md:98, :104, :119-120 and :167; test V15.

Section 6 declares appId exactly com.musicglass.desktop, target exactly windows-x64, and artifact os/arch windows/x64. Section 7 performs full section-6 schema validation before target checks, while V15 requires WRONG_TARGET for a correctly signed manifest for another product/OS/architecture. A literal parser will return INVALID_PAYLOAD before target policy can execute. This is not signature bypass, but it produces incompatible parser/policy APIs and tests across modules.

Required correction: choose one rule. Prefer separating bounded syntactic identifier validation from equality to local supported target values, leaving syntactically valid mismatches to policy. Specify cross-field consistency separately (for example target windows-x64 with artifact arch arm64 is malformed, whereas a consistently named other target is ineligible). Update schema tables, pipeline, error precedence and fixtures together. Channel/key-permission mismatches need the same treatment.

Acceptance: signed, structurally valid wrong-product and wrong-target fixtures have exactly one documented result; malformed identifiers and internally inconsistent artifacts have a distinct deterministic result.

### R3 — P2: Unsupported payload versions have no unambiguous parsing stage

Location: update-trust-contract-v1.md:33, :93-97, :167 and :176; test V31.

The v1 parser requires exactly 14 known root fields and schemaVersion=1, but the error policy also promises UNSUPPORTED_FORMAT for another signed schema version. A future schema may add/remove fields; full v1 schema validation can reject it as INVALID_PAYLOAD before the unsupported-version branch. Object order is unrestricted, so selecting schemaVersion based only on the first token is not sufficient either.

Required correction: define a bounded generic structural pass for authenticated payloads that rejects invalid JSON/duplicates/resource excess, extracts a correctly typed schemaVersion, and dispatches supported schemas before applying their exact field sets. Define precedence for missing/wrong-type schemaVersion, unknown version with extra fields, duplicates, excess nesting and trailing bytes. It is acceptable to keep conservative global structural limits for future versions, but document them as such.

Acceptance: schemaVersion=2 with a bounded extra field returns the chosen unsupported-version result; duplicate schemaVersion or malformed JSON cannot escape structural rejection; v1 unknown fields remain rejected.

### R4 — P2: Move the fixed application-level verification vector before Module 2 acceptance

Location: update-trust-contract-v1.md:219 and :262; update-01-result.md Module 2 handoff.

The final vector bundle and independent reproduction are assigned to Module 6, after verifier/parser/policy/integration work, while earlier modules only consume them 'when available'. Module 2's handoff mentions arranging a vector, but the contract does not make it a prerequisite to accepting the exact-byte verifier. A signer and verifier developed together can share the same preimage bug and pass round-trip tests. RFC primitive vectors do not exercise Riff's prefix, lengths, key ID or payload framing.

Required correction: make at least one immutable valid Riff application vector, plus mutations and independent preimage/signature reproduction, a hard Module 2 acceptance gate. Module 6 can own the production release signer and expand the corpus later. Specify test-only key provenance and exact byte files/hex/digests. This does not require production secrets or implementing a release signer now.

Acceptance: Module 2 cannot be approved only on self-generated round trips; a committed fixed vector demonstrates exact preimage interoperability and detects changes to framing fields.

## Nonblocking design trade-off requiring explicit review

Section 8 intentionally commits accepted high-water metadata even when the release is incompatible. This is documented, so it is not reported as an accidental bug. It can prevent older Windows/app cohorts from accepting otherwise useful older-version maintenance releases after seeing a newer unsupported target. Confirm the intended cohort/feed policy before production; do not silently change this behavior while fixing R1. Likewise the single-signature bridge-key rotation and lack of independently authenticated remote revocation are explicit limitations, not TUF-level recovery.

## What is sound in the draft

- Exact signed bytes and length-prefixed context bind version, key ID, algorithm and payload.
- Payload verification precedes payload interpretation; no feed-supplied trust key or unsigned fallback.
- Bounds, duplicate-name handling and canonical Base64 rules are specified.
- Signature authenticity, payload integrity and Authenticode are separated.
- Replay/freeze, corrupt state and compromised-key limitations are explicitly stated.

These are positive design properties, not certification of an implementation.

## Review evidence and method

- Read the entire contract and the result report; compared against the UPDATE-01 brief and inter-module handoffs.
- Checked the bootstrap counterexample against the written ordering and associated digest requirement. This is a paper trace, not an execution of nonexistent verifier code.
- Traced the wrong-target and unsupported-schema cases through the field tables, pipeline and expected-results table.
- Rechecked the TUF specification as a threat-model comparison, particularly trusted local bootstrap and version/freshness checks: https://theupdateframework.github.io/specification/latest/ (sections 5.2-5.5). This review does not assert this protocol implements TUF or must copy its exact state rules.
- No app code, test code, contract text, private keys or update artifacts changed in this review. No tests/builds/PR lane were run: findings concern the design document. No implementation stage is declared complete.

## Resolution workflow

1. Revise the contract and result report for R1-R4, preserving the review as the original findings record.
2. Add the missing deterministic state/error/vector cases to the matrix.
3. Recheck examples and the preimage/field/limit consistency.
4. Perform a follow-up review and record resolutions per finding.
5. Issue the bounded Module 2 brief only after blocking findings are resolved. Production deployment values remain separate owner decisions.

## Resolution follow-up — owner-authorized revision 2

R1 resolved in contract section 8: scope-specific complete-record merge for absent/lower/equal/higher persisted state; sequence remains bound to digest/version, independent version/time floors preserved; conflicts and persistence failures fail closed.
R2 resolved in sections 6-7: identifier syntax and target/artifact consistency are schema concerns; supported-target equality is policy with WRONG_TARGET.
R3 resolved in section 7: bounded structural parsing and typed schemaVersion precede supported-version field sets; future-schema extra fields do not invoke v1 unknown-member rules.
R4 resolved in sections 9-10: immutable application vector and independent reproduction are a hard Module 2 gate. Node/OpenSSL-generated fixed bytes are now verified and signatures reproduced by JDK JCA tests.

Follow-up verdict: original design blockers resolved for Module 2 scope. This is a self-review follow-up, not an external security certification. Modules 3/4 still need their schema/policy implementation and fixtures; their requirements are not claimed implemented by the envelope verifier.
