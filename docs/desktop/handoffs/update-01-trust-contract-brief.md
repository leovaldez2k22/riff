# Update Module 1 — Trust contract: Gemini execution brief

Date: 2 November 2026
Task ID: UPDATE-01
Status: Ready for Gemini; specification work only. The resulting contract requires Codex review before Module 2 implementation.
Implementer: Gemini in Antigravity
Reviewer: Codex in the shared Riff workspace
Workspace: C:\Volume D\Riff

## 1. Objective and completion boundary

Produce a precise, implementable v1 trust contract for authenticated desktop update manifests. A future verifier, parser, eligibility policy, coordinator and release signer must be able to implement it independently and interoperate without guessing.

This task produces a design specification and review report. Do not implement the verifier, modify production updater code, generate production keys, download dependencies, contact a release feed, download/install updates, sign a release or publish anything. Do not mark M6 complete. No phone or additional monitor is needed.

Success means the byte format, trust source, validation order, schema, resource limits, failure behavior and test-vector plan are unambiguous. A hash supplied by an unsigned manifest does not establish authenticity.

## 2. Read these sources first

Follow repository AGENTS.md and CLAUDE.md, starting with docs/handover-0.20.0.md. The handover requires permission for dependency downloads and reserves publication to the owner. Do not infer permission from this brief to publish, push, change provider accounts or access credentials.

Inspect current files, not just previous completion claims:

- desktop/src/main/kotlin/com/musicglass/desktop/DesktopUpdateClient.kt
- desktop/src/test/kotlin/com/musicglass/desktop/DesktopUpdateClientTest.kt
- desktop/src/main/kotlin/com/musicglass/desktop/DesktopWindowStateManager.kt (contains the shared parseSimpleJson helper)
- desktop/build.gradle.kts
- docs/desktop/manifest-inventory.md
- docs/desktop/m6-implementation.md
- docs/desktop/m6-acceptance.md
- docs/desktop/capability-matrix.md
- reports/Riff Windows desktop architecture.md, M6 and application/worker compatibility sections

Current observations to verify:

1. evaluateManifest uses parseSimpleJson and has no signature verification or trusted-key input.
2. Missing artifact URL/hash values default to empty strings. Existing tests accept incomplete/short digests and a string-valued mandatory flag. These tests are not the future security contract.
3. compareSemVer silently maps invalid numeric segments to zero.
4. The code defaults to application version 0.20.0 while the desktop package declares 0.1.0.
5. The inventory advertises another schema, illustrative domains/hashes and Windows 10; the owner's initial desktop target is Windows 11 only.
6. A SHA-256 file comparison exists and can remain useful after manifest authentication, but it does not authenticate its source.

These observations are starting points; document source changes or disagreements instead of repeating stale claims.

## 3. File ownership and shared-workspace protocol

Create or edit only these two task-owned files:

- docs/desktop/update-trust-contract-v1.md — the proposed normative contract
- docs/desktop/handoffs/update-01-result.md — completion/review report

Read all other files as needed. Do not edit this brief, existing acceptance documents, Gradle metadata, application code or tests. Do not reset, clean, move, commit or revert existing changes. The workspace is dirty and contains unrelated Android and desktop work; untracked files may be valuable work in progress.

Check the two output files before writing. If they already contain work from another agent, preserve it and report the ownership conflict. Do not overwrite silently. Codex will avoid editing your two owned files while you work. Put proposed follow-up edits to existing documents in your result report instead of making them now.

## 4. Required contract content

### A. Threat model and trust boundary

Define trusted application-bundled verification keys versus untrusted network/feed/envelope content. Cover altered manifests, substituted payload/hash, unknown signing keys, unsigned legacy manifests, cross-product/channel substitution, malformed/oversized input, replay/freeze and downgrade attempts.

State limits: a valid signature does not prove the release is bug-free, replace installer Authenticode, defeat a compromised trusted signing key, or by itself prevent replay of an older valid release. Explain what remains protected by TLS, manifest authentication, payload integrity, OS signing and eligibility policy respectively.

### B. Signature algorithm and key model

Propose one v1 algorithm: Ed25519 using the bundled JDK 21 JCA facilities. Verify suitability against primary documentation and the actual pinned runtime; do not implement custom cryptographic primitives. If it is unsuitable, document evidence and a specific alternative for review rather than silently changing the algorithm.

Specify exact public-key representation, signature representation/length, strict Base64 variant/padding rules, key-ID grammar and length, and algorithm identifiers. Public keys come only from a release-controlled registry bundled with the app; a manifest must not supply or replace its own trust key. Unknown IDs and unsupported algorithms fail closed. No algorithm negotiation or unsigned fallback.

Define development test-key isolation from release trust. Describe overlap rotation using keys already trusted by the installed application, removal/revocation limitations and recovery after key compromise. Do not claim remotely supplied key revocation is trustworthy unless its trust chain is specified. Multi-role threshold signing or a full update framework is not automatically in scope: document the security limits of the selected design.

### C. Exact wire format and signed bytes

Preferred design direction: a small JSON envelope containing a version, key ID, algorithm, Base64 payload and Base64 signature. The payload is the exact UTF-8 manifest bytes; the verifier must not parse and reserialize JSON before verification.

Resolve the complete preimage, not just 'sign the JSON'. Specify whether a fixed Riff/update-specific domain separator, envelope version and key ID are included, and give an exact byte concatenation or length-prefixed construction with encoding/endian rules. Avoid ambiguous concatenation. This application prefix is distinct from the Ed25519ctx algorithm variant. Specify precisely which metadata is authenticated and which is only an untrusted routing hint.

Specify BOM, UTF-8 validity, newline handling, whitespace, Base64 decoding and trailing-input behavior. Different encodings of a payload must not accidentally be accepted through normalization. Outer JSON field order can be irrelevant if defined so; signed payload bytes remain exact. Provide one annotated envelope example whose signature is explicitly illustrative, not falsely claimed to verify. Put real signed vectors in the later implementation/vector task.

### D. Strict parsing and bounded resource use

Choose exact numeric limits for envelope bytes, decoded payload bytes, nesting, object members, artifact count, URL/string/changelog lengths and numeric ranges. Identify where each limit applies, including before Base64 allocation and during JSON parsing. Do not rely only on checks performed after an unbounded parse.

Define rejection of duplicate object keys (including escaped-equivalent names), malformed UTF-8, invalid escapes/unpaired surrogates, trailing documents/data, null required fields, wrong JSON types and non-finite/overflowing numbers. Specify extension/unknown-field rules separately for envelope and payload; avoid relying on parser defaults. No regex-based JSON parsing in the future implementation.

### E. Payload schema and compatibility decisions

Provide a field table with exact types, requiredness, allowed values, size/range limits and semantics. Reconcile the current code and inventory into one v1 schema. Cover at least:

- schema version and desktop product/application identity;
- release channel and version grammar/comparison semantics;
- release identifier/monotonic sequence if used for replay defense;
- publication/expiry timestamps and clock-skew policy if used;
- supported Windows target/architecture and minimum OS build;
- application upgrade constraints, worker protocol compatibility and database-migration constraints;
- artifact type, HTTPS URL, expected size and exactly 32-byte SHA-256 digest encoded as 64 hexadecimal characters;
- bounded release notes and actual Boolean values for policy flags, if retained.

Separate installed-version eligibility from target-OS eligibility. Do not silently select the production application version, feed domain, public key, mandatory-update policy or release channel. Mark owner-owned deployment values as configuration requirements. For examples use reserved example domains such as releases.example.invalid, not an invented production service.

Define URL syntax and scheme/credential/fragment/redirect policies, including which checks belong to the later downloader. No arbitrary local paths, shell commands or installer arguments are accepted from a manifest. Specify whether artifact filename is a hint or locally chosen safe name.

### F. Validation order and result contract

Provide implementation-oriented pseudocode for:

bounded bytes -> strict bounded envelope parse -> supported version/algorithm and trusted key lookup -> bounded decode -> exact-byte signature verification -> strict payload parse/schema validation -> product/channel/OS/architecture/compatibility checks -> version/freshness/rollback decision -> authenticated eligible manifest.

No artifact URL fetch or update-available/installable result may occur before authentication and validation. Distinguish untrusted, authenticated-but-ineligible, eligible and failed results in the proposed types. Describe how later modules can prevent callers from constructing a trusted manifest directly from unverified text.

Define stable error categories for invalid envelope, size limit, unsupported format, unknown key, invalid signature, invalid payload, wrong target, incompatibility, stale/replayed release and rollback. State what may be logged without including secrets or unbounded attacker-controlled text. Fail closed without crashing or leaking partial success.

### G. Replay, downgrade and key-rotation boundaries

Explicitly distinguish: installed-version downgrade rejection; replay of an older signed release still newer than the installed version; feed freeze; rollback after a failed installation; and database-safe recovery.

Specify what local high-water state/expiry would be required, when it is committed, how concurrent checks behave and what happens if it is unavailable/corrupt. Define whether successful installation versus mere observation advances state. Discuss clock failure and reinstall/state deletion limitations. Do not claim full rollback/freeze protection from compareSemVer alone.

Policy implementation belongs to Module 4 and installer recovery to a later task, but their signed fields and trust boundaries must be decided here. List owner-dependent deployment decisions without blocking the portable protocol specification.

## 5. Verification and interoperability plan

Include a table: case ID, input change, expected result/error, responsible future module. At minimum cover:

- valid envelope and trusted test key;
- payload bit change, signature bit change and wrong public key;
- changed key ID/algorithm/envelope version under the specified preimage;
- unsigned legacy JSON and unknown key;
- malformed/noncanonical Base64 and signature/key lengths;
- duplicate keys and escaped-equivalent duplicates in both JSON layers;
- invalid UTF-8/BOM/trailing input/oversized input/deep nesting;
- valid signature over payload with invalid schema or hash length;
- wrong product/channel/OS/architecture, incompatible app/worker/schema;
- invalid version, older version, stale signed newer-than-installed release, clock skew/expiry;
- Unicode release notes and payload whitespace changes;
- key overlap rotation and a key not trusted by the installed client;
- valid signed manifest whose downloaded artifact hash/size is wrong (later downloader gate).

Define exact inputs/outputs needed for a language-independent real vector set: test public key, clearly test-only private-key provenance, payload file bytes, signing preimage bytes/hex, signature and envelope. Module 1 only plans these vectors; the signing/vector implementation will produce and independently verify them later. Never label fabricated signature text as a passing vector.

## 6. Deliverables and review gate

The contract must include: threat model; envelope and preimage grammar; payload field table; key trust/rotation model; exact limits; ordered pseudocode; result/error table; legacy migration policy; vector/test matrix; module ownership; deployment decisions; primary-source references.

The result report must include: task ID, completion status (ready for review or blocked), files changed, source observations, decisions and rationale, unresolved owner decisions, checks actually performed, limitations, and a short implementation handoff for Module 2. Include a coverage table mapping every section of this brief to a contract section. Never invent manual checks, benchmarks or test results.

For this documentation task, verify referenced paths, schema examples and internal consistency. Follow repository-required checks before claiming a stage complete, including python scripts/quality/verify.py --lane pr where applicable; record exact commands/results or explain what was not run. A passing PR lane does not validate the security design. No implementation milestone is closed by this task.

Codex review will reject: 'sign the JSON' without exact bytes; hashes described as source authentication; network-supplied trust keys; unsigned compatibility fallback; unspecified duplicate-key behavior; unbounded parsing; replay resistance claimed solely from installed-version comparison; deployment secrets/examples presented as real release configuration; and code changes outside the owned files.

Do not start Module 2 until Codex has reviewed the contract and issued its separate brief. If a requirement conflicts with repository policy or current implementation, report the conflict and a concrete proposal; do not silently weaken it.

## 7. Primary references

Verify the relevant sections when drafting and distinguish standards from Riff-specific proposals:

- JDK 21 algorithm names and provider references: https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html
- EdDSA specification and test vectors: https://www.rfc-editor.org/info/rfc8032
- JSON syntax/interoperability: https://www.rfc-editor.org/rfc/rfc8259
- Update threat-model comparison: https://theupdateframework.github.io/specification/latest/

The references do not imply that Riff implements TUF or that a custom single-signature protocol inherits TUF's guarantees.
