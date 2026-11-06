# UPDATE-06 — Release signer and interoperable vectors: Gemini execution brief

Date: 2 November 2026. Implementer: Gemini. Reviewer: Codex after handoff.
Prerequisite: reviewed Modules 2–5. Read [shared instructions](README.md).

## Objective

Build an offline maintainer tool that signs the exact contract bytes and produces envelopes accepted by the application, plus reproducible positive/negative vectors. This implements tooling; it does not sign or publish a real release.

## Current state

Read contract sections 3–10, prior module results, UpdateEnvelopeVerifier.kt, parser/version implementation and coordinator. Inspect desktop/src/test/resources/update-v1/README.md, generate.cjs, immutable payload/preimage/signature/envelope files and hash inventory.

Module 2 already has a fixed independently generated Node/OpenSSL vector verified by JCA, and includes jdk.crypto.ec in desktop/build.gradle.kts. Keep those immutable interoperability anchors. The opaque fixture is not proof that a full v1 manifest is accepted. No production signer or deployment key is currently established by the inspected result.

Proposed owned files: scripts/desktop/sign-update-manifest.cjs, focused signer tests under scripts/desktop/, additional distinctly named update-v1 fixture subdirectories, scripts/desktop/README.md and update-06-result.md. Use the installed Node crypto implementation already demonstrated by generate.cjs; do not download a crypto package.

## Implementation approach

1. Specify a narrow CLI accepting an existing exact UTF-8 payload file, explicit key ID, local Ed25519 private-key path and explicit output path. The key stays outside Git; no private key text in arguments/env/logs. Development tests use only clearly labelled public RFC test material or ephemeral temporary keys.
2. Validate payload syntax/schema with the same contract semantics before signing. Prefer reuse via a narrow offline validation tool/API rather than maintaining a silently divergent parser. Independently implement the preimage construction for interoperability. If validation is implemented in another language, document it and cross-test every schema boundary against Kotlin.
3. Read bounded payload bytes without trimming, pretty-printing or newline conversion. Construct section-5 domain/version/length-prefixed message exactly; use vetted Ed25519 and canonical Base64. Validate key algorithm and keyId. No algorithm negotiation or invented JSON canonicalization.
4. Emit only the exact five-field envelope, UTF-8, within resource limits. Refuse clobbering an existing output by default; use an owned temp file and atomic replacement policy without leaving partial usable output. Do not reveal private-key bytes/passphrases on parse errors.
5. Fail closed for malformed/oversized payload/key, unsupported algorithm, mismatch between supplied public key/expected fixture identity when checked, output failure or verification failure. Exit codes and concise stable diagnostics must be documented. A signer invocation must not fetch artifact URLs.
6. Expand vectors with a complete syntactically valid manifest and fixed clock/config/bootstrap/state. Use reserved example.invalid host accepted only in test configuration. Record exact final-newline bytes, public key, preimage binary/hex, signature, envelope, expectations and SHA-256 inventory.
7. Include correctly re-signed invalid-schema/policy vectors and tampered-signature vectors so error precedence is intentional. Cover Unicode, whitespace, key ID binding, unsupported versions, limits, expiry/sequence/target conflicts and key overlap. Never overwrite immutable Module 2 vectors just to make a new implementation pass.
8. Cross-verify Node/OpenSSL output with JCA verifier and full coordinator. Explain sequence allocation, same-sequence immutability and expiry refresh in the operator README. Allocation authority, production custody/rotation, target capability claims and actual host/channel remain owner/release decisions. Tooling must not guess or recycle a sequence.

## Scope boundaries

No production key generation/access, actual release signing, Authenticode, artifact download, installer invocation, push or publication. Keep signer/test private material out of packaged production resources. Do not weaken parser/verifier limits to accept signer output. An offline signing tool does not prove production key custody or release readiness.

## Acceptance criteria

- Existing immutable Node/JCA vector still matches byte-for-byte; new complete-manifest signature verifies independently in JCA and reaches the expected coordinator outcome under fixed test configuration.
- Two runs with identical payload/key/context produce the same signature; payload newline/whitespace change changes the signature. Outer property ordering remains semantically irrelevant to verification.
- Invalid payloads/keys/paths and output failures produce nonzero exit and no partial accepted output; normal logs/errors contain no key contents.
- Distinct negative cases fail at intended verifier/parser/policy phase, with case IDs and expected-results files.
- No network performed, production keys touched or new trust installed. Test-only keys/seed files excluded from production distributable.
- Operator README states sequence/expiry/rotation duties and explicitly separates Ed25519 metadata signing from Windows Authenticode.

## Verification commands

After confirming Node is installed and cached toolchain is ready:

```powershell
node --version
node desktop/src/test/resources/update-v1/generate.cjs
node --test scripts/desktop/sign-update-manifest.test.cjs
.\gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain
python scripts/quality/verify.py --lane pr
```

Use the final owned test filename if different and record it. Run the signer once against TEST-ONLY fixture paths and verify its envelope with the existing packaged --verify-update-fixture probe, then with the coordinator tests. Record the exact signer/probe commands and results. Inspect packaged resources for fixture/private-material leakage; do not execute an installer.

## Handoff requirements

Write update-06-result.md with CLI contract, validation reuse, fixture hashes/provenance, independent implementation/version, error cases and actual test outputs. State unresolved production custody/sequence/deployment decisions. Give Module 7 exact offline reproduction commands. Stop for review.
