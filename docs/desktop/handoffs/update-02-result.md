# UPDATE-02 — Envelope and signature verification

Date: 2 November 2026. Status: implemented and scoped verification passed: 15 targeted tests, bundled-runtime positive/negative probes and PR lane. Broader desktop-suite caveat remains below. No M6 completion or production update integration is claimed.

## Scope and implementation

- `UpdateEnvelopeVerifier.kt`: a bounded five-field JSON envelope tokenizer/parser; strict UTF-8 and surrogate handling; duplicate/unescaped-name detection; raw/decoded token, member and body limits; canonical standard Base64; explicit error categories.
- `UpdateTrustKey`/`UpdateTrustRegistry`: immutable copied key bytes and unmodifiable context sets, at most 32 keys, duplicate ID rejection, canonical Ed25519 SPKI checks, inactive/unknown key handling, test-key rejection by default. Registry comes from caller-owned local configuration, never envelope fields.
- `VerifiedUpdatePayload`: private constructor; caller receives defensive byte copies plus signer context. It authenticates opaque bytes only. Payload schema, freshness and eligibility are intentionally NOT implemented in this module.
- Signing message matches contract revision 2's domain, version, key ID, algorithm and length-prefixed exact payload. JDK Ed25519 is used, no crypto library download or handwritten crypto primitive.
- `UpdateVerificationProbe.kt` and an early Main argument branch: offline `--verify-update-fixture <test-key-id> <public-key.der> <envelope.json>`. It exits without UI, network, data-store initialization or update installation. Supplied test trust is local to that diagnostic and is not installed in application updater configuration.
- `desktop/build.gradle.kts` includes `jdk.crypto.ec` in the bundled runtime so the Ed25519 provider is retained.
- Existing unsigned DesktopUpdateClient remains untouched and must not be treated as authenticated. Module 5 will replace its production decision entry point after Modules 3/4. No production trust keys have been added.

## Fixed vector and tests

`desktop/src/test/resources/update-v1/` contains the fixed opaque payload, exact binary/hex preimage, public-key DER, signature, JSON envelope, expected verifier-only result/configuration, raw public-key hex, SHA-256 inventory, README and `generate.cjs`.

Generator uses Node 24.19.0 built-in crypto/OpenSSL and the publicly documented RFC 8032 test seed. It independently constructs, signs and verifies the message. Kotlin/JCA verifies the committed envelope and reproduces the signature over committed preimage bytes. The verifier implementation independently builds that message from the envelope. Test seed is public TEST-ONLY data, not a secret or production key. Fixtures are test resources, not production resources. No production signing tool has been implemented.

Targeted suite: `UpdateEnvelopeVerifierTest`, 15 tests, zero failures/skips. Coverage includes OpenSSL/JCA interoperability, returned-byte immutability, context-set immutability, tampering, ID binding, registry rejection, unsupported format, duplicate/escaped-equivalent keys, whitespace/order, invalid JSON/UTF-8/surrogates, canonical Base64, stream read cap, payload 65536/65537-byte boundary and envelope 131072/131073-byte boundary. Payloads in this module need not satisfy the later payload schema; authenticated does not mean installable.

## Commands and recorded outcomes

1. `node desktop/src/test/resources/update-v1/generate.cjs`: PASS; Node/OpenSSL signing and verification.
2. `./gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain`: initial full run had 70 tests, one existing `WorkspaceLayoutTest.hostHandlesPlaybackAndClockAdvancement` failure: expected initial position 0, observed 1671. Source constructs DesktopHost with the default persistent user-home database and restores saved playback state. No persisted user data was cleared, and this unrelated test was not modified. All 13 initial verifier tests passed in that run.
3. After boundary/raw-byte hardening, `./gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdateEnvelopeVerifierTest' :desktop:createDistributable --offline --console=plain`: PASS, 15 verifier tests and distributable rebuilt after canonical public-key configuration validation. A filtered test pass does not turn the earlier full-suite failure into a pass.
4. Packaged executable probe with committed vector: exit 0, `RIFF_UPDATE_VERIFY VERIFIED_OPAQUE_PAYLOAD`.
5. Packaged executable probe with altered payload: exit 2, `RIFF_UPDATE_VERIFY INVALID_SIGNATURE`.
6. Probe evidence: `artifacts/desktop/update-verifier/packaged-results.json`.
7. `python scripts/quality/verify.py --lane pr`: PASS_LOCAL across all nine gates; evidence directory `artifacts/quality/lanes/pr-20261102T061734Z`. The lane checks the Android repository gates; it does not replace the separate targeted desktop tests/probe or erase the initial desktop-suite failure.

## Review resolutions and follow-ups

UPDATE-01 R1-R4 are resolved in contract revision 2 and the original review's follow-up section. State reconciliation, target/schema policies and future-version dispatch are specification corrections for Modules 3/4, not implementations added here. Independent application-vector acceptance was brought forward and exercised in this module.

Remaining prerequisites for production update decisions: strict authenticated payload parser (Module 3), compatibility/freshness state (Module 4), coordinator integration (Module 5), release signer (Module 6), review/acceptance (Module 7), production key/feed configuration and later downloader/installer work. No authentication guarantee is made for the current old unsigned updater path.

The broader desktop test's persisted-state isolation problem remains open. Before accepting the entire desktop release, isolate its fixtures from the real user database and rerun the full suite without deleting user data.
