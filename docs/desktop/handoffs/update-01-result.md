# UPDATE-01 result report

Date: 2 November 2026
Implementer: Codex, taking over at the owner's request after Gemini encountered issues.
Status: Specification delivered; ready for design review. No updater implementation or M6 completion is claimed. Module 2 has not started.

## Ownership and files

No draft existed at either assigned output path when checked. Created only:

- docs/desktop/update-trust-contract-v1.md
- docs/desktop/handoffs/update-01-result.md

The brief, production source, tests, dependencies and existing acceptance documents were not edited for this task. The shared dirty worktree contains unrelated changes and remains intact. No private key was generated, no feed contacted, no artifact downloaded/installed and nothing committed/pushed/published.

## Verified source observations

The updater still uses parseSimpleJson without source authentication. Missing URL/hash defaults and permissive version handling remain. Existing unit tests accept invalid future-protocol hash lengths and string booleans. The desktop package version (0.1.0) and updater default (0.20.0) differ; the inventory uses another schema and advertises Windows 10 despite the initial Windows 11-only desktop scope. These are follow-up integration corrections, not changes made here.

## Decisions and rationale

1. Pure Ed25519 via JDK JCA, with a bundled scoped public-key registry. Avoid new crypto dependencies or handwritten primitives. The pinned full JDK exposes the necessary algorithms; trimmed-runtime capability remains a required implementation check.
2. Five-field bounded envelope over exact Base64 payload bytes. The signing preimage binds domain, version, key ID, algorithm and payload with fixed-width big-endian length prefixes. No JSON reserialization/canonicalization ambiguity.
3. Strict JSON parsing and explicit limits, including duplicate/escaped-equivalent name rejection. The existing regex helper is unsuitable and will not be extended for update authentication.
4. One Windows x64 MSI per signed manifest; strict numeric three-part version grammar; no mandatory policy introduced without owner deployment decision.
5. Per-app/channel/target release sequences, signed expiry and durable accepted-metadata high-water state. Explicit limitations cover unseen replay, broken clocks, deleted state, key compromise and unavailable feeds. Higher accepted metadata can block older incompatible fallback; this trades availability for fail-closed behavior and is called out in the contract.
6. Unsigned legacy manifests are rejected. SHA-256 remains an artifact integrity mechanism after metadata authentication, separate from Authenticode.
7. Owner-owned production values remain unselected. Test-only configuration can implement the protocol without them.

## Brief coverage

| Brief requirement | Contract section |
|---|---|
| Objective/current baseline and migration | 1, 9 |
| A: threat model/trust boundaries | 2, 8 |
| B: algorithm/keys/rotation | 4, 8 |
| C: wire format/exact bytes | 3, 5 |
| D: strict parsing/resource bounds | 3, 6, 7 |
| E: schema/compatibility/URL rules | 6, 9 |
| F: ordered workflow/types/errors | 7 |
| G: replay/downgrade/recovery | 8 |
| Verification matrix and vector plan | 10 (V01-V32) |
| Module ownership and remaining owner choices | 9 |
| References/review follow-ups | 11 |

## Checks actually performed

- Read the handover, repository instructions, task brief and current updater/build/inventory sources; confirmed absence of existing deliverable drafts.
- Browsed primary JDK provider documentation, RFC 8032, RFC 8259, RFC 8410, RFC 4648 and TUF specification. Protocol rules distinguish sourced primitives from Riff-specific design choices.
- Capability-only JShell probe on .toolchain/jdk-21.0.12.1+1: runtime 21.0.12.1+1-LTS; Ed25519 Signature, KeyFactory and KeyPairGenerator found through SunEC. No keys/signatures generated. This is not a packaged-runtime test.
- Python document checks: parsed both JSON blocks; envelope has 5 members, payload root 14 and artifact 6; all-zero illustrative signature is canonical Base64 of 64 bytes; its illustrative payload decodes to {}; domain prefix is 20 bytes with the stated hex; 65536 bytes encode to 87384 Base64 characters; example expiry window and digest syntax valid; 32 matrix cases and all 11 sections present. PASS.
- Self-review corrected the numeric token limit to 16 digits so the chosen safe-integer sequence bound is representable. The illustrative envelope is explicitly non-verifying; no signed test-vector result is fabricated.
- A long initial shell write hit Windows command-length limits before creating the file. Writing in two bounded calls succeeded; the complete output was then validated.
- No application tests or full PR lane were run for this documentation-only delivery. No runtime behavior changed and no implementation stage is being certified. Repository instructions still require `python scripts/quality/verify.py --lane pr` before calling an implementation stage done; a prior passing lane is not represented as validating this specification.

## Review status and limitations

This is a self-reviewed proposed contract, not independent cryptographic certification. No verifier/parser/signer, real signature vector, trusted production key, downloader or installer trust chain has been implemented. The new parsing rules must be validated against the chosen parser before implementation is accepted. The trimmed runtime must expose the provider. Replay-state persistence/concurrency and actual installer recovery require their later modules.

Independent review should concentrate on: preimage interoperability; parser duplicate and resource-limit enforcement; error precedence; state commitment before compatibility outcomes; unseen-replay and reset limitations; production key rotation/bridge releases; URL and redirect enforcement. A policy decision to use TUF or threshold roles would require revisiting this single-signature contract rather than claiming equivalent guarantees.

## Owner/release configuration still needed

- Real desktop application version, channels and sequence/bootstrap allocation.
- Production public keys, custody, rotation and independently authenticated rescue route.
- Actual feed/artifact host allowlists and refresh operations within expiry limits.
- Supported minimum Windows build, worker upgrade policy and database migration capabilities.
- Production Authenticode identity. Installer signing and manifest signing are separate.

These are not blockers to isolated test implementation and are not invented in examples.

## Module 2 handoff

Issue a separate reviewed brief for envelope/signature verification only. Use sections 3-5 and 7 plus V01-V13, V25-V27 and V32. Build an immutable verified-payload boundary with non-public construction. Test exact preimage bytes, canonical Base64, registry restrictions, bounded parsing and negative signature cases. Use public TEST-ONLY material and primitive RFC vectors; arrange a fixed application vector with independent verification before accepting interoperability. Run in both full and trimmed packaged runtime. Do not implement payload eligibility, network download, production key setup or installation in Module 2.

The task ends here, awaiting design review and a separate Module 2 brief.

## Follow-up: review resolutions

Owner authorized corrections and Module 2. Contract revision 2 resolves R1-R4 as recorded in its section 12; original review remains an immutable findings record. Protocol implementation starts separately; this does not close M6.
