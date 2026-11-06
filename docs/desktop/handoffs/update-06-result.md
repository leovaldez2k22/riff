# UPDATE-06 — Offline release signer and interoperable vectors

Date: 2 November 2026. Implementer: Codex. Status: READY_FOR_REVIEW. Signer, desktop, packaged interoperability and required PR checks passed. Independent review has not occurred.

## Identity and scope

Starting HEAD e4748bf6a59aa4d56575ef1c97215f2a58b05c92; extensively dirty shared checkout, including untracked desktop/docs. No reset, cleanup, commit, push or unrelated replacement. Ending HEAD is unchanged. artifacts/desktop/update-signer/source-inventory.json records 14 source/dependency fingerprints, with the 168 generated corpus files individually covered by its referenced SHA-256 inventory. Prior module reports and actual verifier/parser/coordinator boundaries were reviewed; this is self-review, not independent security approval.

This implements update module UPDATE-06, not completion of desktop milestone M6. No production key generation/access, production release signing, Authenticode, artifact/feed request, installer execution, publication, account/device operation or Android behavior change occurred. No dependency was downloaded. Only public TEST-ONLY RFC/label-derived seeds and ephemeral temporary test keys were used. Private DER test files were created outside Git in owned OS temporary roots and removed. Nothing was installed as production trust; the default updater remains disabled.

## Changed files and interfaces

| File | Purpose |
|---|---|
| scripts/desktop/sign-update-manifest.cjs | Offline maintainer CLI, exact bytes, independent preimage/signature, stable diagnostics and no-clobber publication |
| scripts/desktop/sign-update-manifest.test.cjs | 12 Node tests, including all 27 corpus payloads, key errors, determinism, output races and injected partial-write/force/link failure |
| scripts/desktop/generate-update-vectors.cjs | TEST-ONLY independent corpus generator; owns only release-corpus subtree |
| scripts/desktop/README.md | CLI, validation/runtime prerequisites, failure/atomicity contract and release operator duties |
| desktop/src/main/kotlin/com/musicglass/desktop/UpdatePayloadParser.kt | Reuses one private schema reader for authenticated construction and status-only signing validation |
| desktop/src/main/kotlin/com/musicglass/desktop/UpdateManifestValidationMain.kt | Separate CLI class returning syntax status/digest only; no UI/trust/state/offer |
| desktop/src/test/kotlin/com/musicglass/desktop/UpdateReleaseVectorsTest.kt | Four tests covering all frozen signatures, coordinator phases, shared-parser parity and hash inventory |
| desktop/src/test/resources/update-v1/release-corpus/ | New distinctly named 27-case subtree, public key, fixed configuration, byte artifacts, expectations, README and SHA-256 inventory |

Original parent-directory Module 2 fixtures, generator and hash inventory are byte-identical. They were deterministically regenerated and inventory equality checked before/after; Node tests recompute every original inventory hash and compare the signer's independently assembled preimage to the immutable anchor. The new generator never writes parent anchors.

## CLI contract and validation reuse

Required flags: --payload, --key-id, --private-key, --output, --java, --validator-app. Optional --public-key specifies expected canonical 44-byte SPKI DER. No environment/argument carries private-key contents or passphrase. Input private key is bounded unencrypted canonical PKCS#8 DER Ed25519, verified by algorithm and exact export equality; unsupported/encrypted/PEM/noncanonical keys fail closed. No conversion or key generation command is supplied.

Payload file is read once into a bounded buffer (<=65536 bytes). It is not decoded/reformatted/trimmed/newline-normalized by Node. Empty/oversized/filesystem failures reject. The current rebuilt application jars and trusted JDK 21 are explicit tool inputs. The stripped application runtime lacks java.exe, so --java points to the existing external cached JDK; this is a documented maintainer prerequisite, not a download.

Node invokes the Java command directly with `-cp <validator-app>/app/* com.musicglass.desktop.UpdateManifestValidationMain`, payload bytes on stdin, a 15-second process timeout and bounded output. It does not launch graphical Main or an unknown-flag fallback. Missing/old/wrong validators fail VALIDATOR_ERROR. Success requires the exact protocol marker and SHA-256 matching the original bytes.

The new `ValidatedUpdateManifest.validateForSigning(ByteArray): UpdatePayloadError?` calls the same private bounded reader as `parse(VerifiedUpdatePayload)`. Null means syntax valid; otherwise the existing stable schema/resource error. When called without an authenticated payload, the reader returns status before manifest construction. It cannot return a manifest, signer or offer; the authenticated factory still requires VerifiedUpdatePayload and a private constructor. No unsigned update-decision entry point was introduced. Production coordinator continues verify -> parse -> policy unchanged. No second-language JSON/schema implementation exists to drift from Kotlin. Corpus parity tests compare raw validation with the authenticated parser for every original payload, including malformed bytes and limits.

Validation completes before private-key access and signing. Node independently constructs the section-5 domain/version/key ID/algorithm/length-prefixed exact payload preimage, uses built-in OpenSSL-backed Ed25519, verifies the result locally, then emits precisely five envelope fields with canonical padded standard Base64 and a final LF. Changing payload whitespace/LF changes signature; no JSON canonicalization is invented. Syntax validation does not establish actual release compatibility, artifact integrity, correct sequence allocation, current-clock validity or per-installation policy.

The offline validator is part of trusted local tooling. It is not a sandbox against replaced JDK/jars and does not establish production key custody. Shared-schema status outputs contain only a fixed code or payload digest, no raw payload/query/exception.

## Publication, key handling and stable diagnostics

Existing output is never overwritten; no --force option. A generated owned sibling temp is opened exclusively, written completely, fsynced and closed. Same-volume hard-link creation atomically publishes the final name and refuses EEXIST even if another writer won after initial checking. No copy/delete/overwrite fallback. Local NTFS publication was exercised. Unsupported filesystems/permissions return OUTPUT_ERROR. Temp cleanup targets only the generated path. Tests inject a partial temporary write, fsync failure and link failure; each leaves no final output or temp. A separate race creates a competing output just before link; its bytes survive unchanged.

Before publication, interruption can leave only a private orphan temp, not a usable final envelope. After publication the final name references a complete flushed file. A post-publication cleanup/error can leave that complete output; operators must verify it rather than overwrite/retry blindly. These are process-crash/atomic-visibility guarantees, not proof of all device power-loss behavior or directory-entry durability. Output directories and key permissions remain release-host responsibilities.

Only RIFF_SIGN_UPDATE plus stable marker is logged. No key bytes, passphrases, raw exception text, signature, envelope, payload or URL query are printed. Raw input key buffer is wiped best-effort; OpenSSL/GC copies are not promised erased.

| Exit | Stable markers |
|---|---|
| 0 | OK |
| 2 | USAGE, KEY_ID |
| 3 | PAYLOAD_ERROR, INVALID_PAYLOAD, SIZE_LIMIT, UNSUPPORTED_FORMAT |
| 4 | KEY_ERROR, KEY_MISMATCH, VERIFY_ERROR |
| 5 | OUTPUT_EXISTS, OUTPUT_ERROR |
| 6 | VALIDATOR_ERROR |
| 7 | INTERNAL_ERROR |

## Corpus, provenance and expected policy state

Node v24.19.0, OpenSSL 3.5.7; no crypto package. Primary key uses the public RFC 8032 section 7.1 seed, identical to Module 2. A distinct second TEST-ONLY seed is SHA-256 of the public label RIFF RELEASE CORPUS SECOND PUBLIC TEST KEY - NOT FOR PRODUCTION. Both public DER/raw hex keys are recorded. IDs test-rfc8032-01 and test-rfc8032-rotated demonstrate real two-key overlap; an old-key-only registry rejects the new-key envelope as UNKNOWN_KEY. No production trust was installed. Kotlin/JCA independently verifies every original preimage/signature; the full coordinator then evaluates emitted (possibly intentionally tampered) envelopes.

27 cases: valid full multilingual manifest; whitespace; no final LF; duplicate field; invalid UTF-8; BOM; unpaired surrogate; future schema; wrong product; wrong host; expired; not-yet-valid; stale sequence; same-sequence digest conflict; version rollback; incompatible worker; overlapping key; changed key ID; modified signature; changed payload whitespace without resigning; exact/over body cap; exact/over Unicode notes cap; oversized number; excessive depth; forbidden URL path.

Each case has payload.bin, preimage.bin, preimage.hex, signature.bin, envelope.json and expected-results.json. Root includes both distinct public DER/raw hex keys, configuration.json, expectations.tsv and sha256.json covering 168 generated files. The hash inventory excludes itself and README. Negative-schema/policy cases are correctly re-signed. Tampered cases preserve the original signing operation in binary files while envelope.json carries the intentional mutation. TEST-ONLY negative generator bypasses release-tool validation solely to construct rejection fixtures; it is not a release entry point.

Fixed clock 1793578000; app com.musicglass.desktop, preview/windows-x64; installed 0.1.0, Windows build 26100, worker 1, database 1->1 path, releases.example.invalid allowlist; bootstrap sequence 10/version 0.1.0/unbound digest. Normal state absent; conflict case preloads sequence 20/version 0.2.0/digest of valid payload/highest 0.2.0/time fixed. Valid metadata is sequence 20/version 0.2.0. Per-case files and configuration record all context; these values are not production defaults.

Default JSON payload/envelope/hex files end LF; no-final-newline deliberately does not, padded body-boundary payloads end spaces. Each case records payload byte count/final-byte value. Preimage/signature binaries have no added terminator. UTF-8 Unicode bytes and final-newline behavior are explicit, not dependent on platform text newline conversion.

## Verification and acceptance

| Criterion | Evidence | Status |
|---|---|---|
| Original immutable vector unchanged | Regeneration inventory equality; first Node test verifies all original file hashes and exact preimage parity | PASS |
| Complete manifest independently accepted | 27-case Kotlin coordinator test; all original signatures/preimages verify in JCA | PASS |
| Shared validation, no raw-to-offer bypass | Parser parity test across all corpus payloads; private constructor/branch trace | PASS |
| Determinism and exact newline/whitespace handling | Two identical CLI runs match bytes/signature; LF and formatting variants change signature | PASS |
| Malformed/oversized/unsupported key, bad paths/public expectation | Node key/path/CLI tests, stable nonzero codes, no emitted output | PASS |
| No clobber/partial output | Existing-output/race/partial-write/force/link failure tests | PASS |
| Intended rejection phases and state behavior | All 27 expected outcomes; commits only for eligible or valid-but-incompatible matching metadata | PASS |
| Actual emitted output in packaged JCA | signer-run.json and packaged-results.json; exit 0 VERIFIED_OPAQUE_PAYLOAD | PASS |
| Actual emitted bytes match full-coordinator fixture | Direct byte equality with valid/envelope.json; same bytes tested by full coordinator | PASS |
| No fixture/private material in production | package-inspection.json; corpus/test helpers/DER/PEM resources absent, syntax-only entry point present | PASS |
| Full desktop regressions | 145 tests across 16 classes, zero failures/errors/skips | PASS |
| Required PR lane | artifacts/quality/lanes/pr-20261102T101301Z/lane-summary.json | PASS_LOCAL, all nine gates |

## Commands and actual outcomes

1. node --version: v24.19.0. Corpus generation records OpenSSL 3.5.7.
2. `node desktop/src/test/resources/update-v1/generate.cjs`: exit 0; old inventory identical before/after. `node scripts/desktop/generate-update-vectors.cjs`: exit 0; 27 named TEST-ONLY cases.
3. `./gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdatePayloadParserTest' :desktop:createDistributable --offline --console=plain`: exit 0, existing 20 parser tests and rebuilt application validator. artifacts/desktop/update-signer/validator-build.log.
4. `node --test scripts/desktop/sign-update-manifest.test.cjs`: initial 10-test run exit 0; added output-race/partial-write/force cases, final 12-test run exit 0, no failures/skips. node-tests.log and node-tests-final.log.
5. `./gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain`: exit 0; 145 tests/16 classes, zero failures/errors/skips. full-build.log, full-suite.json, vector-tests.xml.
6. Actual signer command recorded as an exact argv array in signer-run.json: node scripts/desktop/sign-update-manifest.cjs --payload <release-corpus/valid/payload.bin> --key-id test-rfc8032-01 --private-key <owned TEST-ONLY temporary DER> --public-key <release-corpus/public-key.der> --output <artifacts/desktop/update-signer/signer-envelope.json> --java <cached JDK 21 java.exe> --validator-app <rebuilt app directory>. Exit 0, RIFF_SIGN_UPDATE OK. Temporary public-test private DER removed. Emitted file byte-identical to coordinator corpus valid/envelope.json.
7. Actual executable C:/Volume D/Riff/desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe with --verify-update-fixture test-rfc8032-01 <release-corpus/public-key.der> <emitted signer-envelope.json>: exit 0, RIFF_UPDATE_VERIFY VERIFIED_OPAQUE_PAYLOAD. This is intentionally an opaque verification probe; independent full-coordinator assertions are separate. Exact paths/args in packaged-results.json. No GUI/installer/feed was opened.
8. Package jar inspected for test resources/helpers/DER/PEM material and presence of the separate validation class. package-inspection.json records application-jar hash.
9. `python scripts/quality/verify.py --lane pr`: exit 0, PASS_LOCAL across all nine gates; 174 script tests passed, static checks zero findings. Evidence artifacts/quality/lanes/pr-20261102T101301Z/lane-summary.json and collector manifest/build log under artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-0ed34c22bb7b/lane-pr-20261102T101301Z/. Builds/lane sequential with cached Windows JDK/SDK/Gradle home.
10. Final coverage review strengthened the overlap vector from same-key aliases to two distinct public test keys and added an old-key-only UNKNOWN_KEY assertion. After the PR lane finished, regenerated only the new corpus and reran the complete Node and desktop commands: exit 0, 12 Node tests and 145 desktop tests, no failures/errors/skips. Final logs and XML/JSON counts include this strengthening. Production signer/parser code remained unchanged; final emitted CLI output still matches the coordinator vector byte-for-byte. Recomputed repository lane digest is identical to its recorded digest; pr-source-check.json records reuse only for unchanged repository/Android inputs, not for desktop tests. Package scan checks both distinct test seeds/private DER sequences absent.

All artifact filenames above are under artifacts/desktop/update-signer/ unless otherwise stated. Initial PowerShell edit anchor did not match CRLF and stopped before writing anything; corrected anchored edit succeeded. No failed build/test was concealed or treated as a pass. No schema limits were loosened.

## Operator decisions, limitations and handoff

All assigned checks are complete. Next action is review of the signer, shared status-only parser path, operator contract, distinct-key corpus and evidence before Module 7. Source fingerprints and final corpus hashes are recorded; no subsequent production change occurred. scripts/desktop/README.md specifies sequence authority, never-reused digest/version binding, <=30-day expiry refresh with a higher sequence, nondecreasing versions, overlap-key bridge rotation, no remote revocation and independent rescue duties. It separates Ed25519 metadata from Windows Authenticode and installation authorization.

Owner/release decisions remain production key custody, approval/allocation ledger, actual release version/channel/hosts/bootstrap, compatibility claims and rotation/recovery operations. The tool neither invents nor increments/recycles sequences, fetches artifact bytes nor enables an updater. State deletion/reinstall, clock trust, compromised signer and lack of a latest-release oracle remain protocol limitations.

Module 7 can reproduce with the operator README commands and the exact signer/probe argv artifacts; inspect private constructor/status-only validator boundaries and corpus expected outcomes. Stop for review before Module 7. Downloader/staging/size/hash/redirect/address enforcement, Authenticode, activation/receipts/serialization, safe data recovery, clean Windows VM and production deployment remain separate gates. No production readiness or full M6 completion claim.