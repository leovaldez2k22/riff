# Desktop update trust contract v1

UPDATE-01 · 2 November 2026 · Revision 2: review findings R1-R4 resolved; Module 2 implementation authorized by owner. This document specifies future behavior; it does not claim implementation or M6 completion. Normative MUST/MUST NOT requirements below are Riff policy unless attributed to a standard.

## 1. Scope and current observations

Authenticate desktop update metadata before interpreting it as an update offer. Initial target: Windows 11 x64. Downloads, installer execution, Authenticode and recovery are later tasks. No phone is required.

Current DesktopUpdateClient accepts unsigned JSON via parseSimpleJson, defaults missing URL/hash to empty strings, and converts malformed version components to zero. Tests accept short hashes and string-valued mandatory. Code defaults to version 0.20.0 while desktop/build.gradle.kts packages 0.1.0. manifest-inventory.md describes another schema and Windows 10. These are migration problems, not authoritative protocol decisions. Existing SHA-256 file comparison is useful only after authenticating the metadata that supplies the expected hash.

## 2. Threat model and trust boundary

Trust the installed application, bundled public-key registry, product/channel configuration, bootstrap release metadata and supported compatibility tables. Network bytes, HTTP metadata and every envelope/payload field are untrusted until validated. Local privileged compromise, replaced application binaries and malicious trusted release maintainers are outside the protection boundary.

Reject modified payloads, substituted URL/hash pairs, unknown keys, unsigned legacy input, wrong products/channels, malformed input and resource exhaustion. HTTPS authenticates transport endpoints; signatures authenticate release metadata; signed size/hash bind artifact bytes; Authenticode establishes Windows publisher identity under a separate policy. Eligibility/freshness adds checks signatures do not provide. A signature does not prove safe application code, defeat compromise of its key, or alone prevent replay/freeze.

This is a single-signature application protocol, not TUF: no threshold roles, delegated targets or remote root-update chain. Its limited rotation and recovery properties must be reflected in release claims.

## 3. Encoding and bounded parsing

UTF-8 JSON only, no BOM, invalid/overlong encoding or unpaired surrogate escapes. One root object followed only by JSON whitespace (SP/TAB/CR/LF). No comments, trailing commas, NaN/Infinity, concatenated documents or null values. No normalization, trimming or newline conversion of decoded payload bytes.

Use a real streaming/bounded JSON parser, not regex. Reject duplicate names after unescaping (version and \u0076ersion are duplicates). Unknown fields are rejected at every object level. Booleans are JSON true/false, never strings. Numeric fields are checked unsigned decimal integers with lexical grammar `0|[1-9][0-9]*`; reject signs, exponents, decimal points and coercion. Parse into checked signed 64-bit values before schema bounds. These stricter choices are Riff rules, not the complete set of RFC 8259 representations.

Base64 is canonical padded standard RFC 4648 (+ and / alphabet), without whitespace. Reject URL-safe variants, missing/excess padding and nonzero unused bits. Re-encoding decoded bytes must exactly equal input. Envelope Base64 values must be literal unescaped ASCII, not Unicode escape spellings.

| Resource | Limit and enforcement |
|---|---|
| Envelope | 131072 bytes, incremental count; reject byte 131073 even without Content-Length |
| Envelope members | Exactly 5; root only, no nested objects or arrays |
| Payload | Nonempty, decoded maximum 65536 bytes |
| payloadBase64 | Maximum 87384 ASCII characters; predict decoded size from length/padding before allocating; enforce decoded limit again |
| signatureBase64 | Exactly 88 characters ending ==; decodes to 64 bytes |
| keyId | 1-64 ASCII bytes: `[a-z0-9][a-z0-9._-]{0,63}` |
| Payload container depth | Maximum 3, root object counts as 1 |
| Payload members/arrays | Root exactly 14 members; artifacts array exactly 1 object, that object exactly 6 members; no other containers |
| Property names | Maximum 32 Unicode scalars and 128 UTF-8 bytes after unescaping |
| Ordinary strings | Maximum 4096 scalars and 16384 decoded UTF-8 bytes; tighter field bounds below |
| Raw string token | Maximum 32768 bytes including quotes/escapes; payloadBase64 uses its dedicated larger bound |
| Numeric token | Maximum 16 digits before checked conversion, followed by field-specific bounds |

Apply token/depth/member limits during parsing, not after building an unrestricted tree. Envelope's large Base64 token is a dedicated exception, not an unbounded generic string. HTTP collector requests identity encoding and rejects unsupported Content-Encoding in v1. Content-Length is an early hint, not a substitute for actual counting. Network timeouts belong to the later collector. No artifact fetch is allowed before the ordered verification pipeline succeeds.

## 4. Algorithm, registry and key lifecycle

Only case-sensitive algorithm `Ed25519`, pure Ed25519 as specified in RFC 8032. Not Ed25519ph or Ed25519ctx. Use JCA Signature.getInstance("Ed25519") and KeyFactory.getInstance("Ed25519"); no handwritten primitives, algorithm negotiation or unsigned fallback. The application message prefix below is ordinary message content, not an Ed25519ctx parameter.

Bundled public keys use canonical DER SubjectPublicKeyInfo: Ed25519 OID, absent algorithm parameters, 32-byte public key, total DER length 44 bytes; canonical standard Base64 representation is 60 characters. Load through X509EncodedKeySpec and validate algorithm/parameters and canonical DER. A malformed local registry is CONFIGURATION_ERROR, not an invitation to retrieve keys from the feed. Signatures are 64-byte Ed25519 encodings, not DER wrappers.

Registry entry: keyId, public key, active flag, permitted appId/channels/targets. Duplicate IDs are invalid. Lookup by untrusted keyId selects a candidate; that ID is itself signed below. Enforce key permissions against authenticated payload context. Manifests may not supply a public key, certificate, key URL or registry update.

A capability-only probe on pinned JDK 21.0.12.1+1-LTS exposed Signature, KeyFactory and KeyPairGenerator for Ed25519 via SunEC on 2 November 2026. Do not hard-code that provider name. Module 2 must also test the trimmed packaged runtime and include required provider modules; full-JDK availability is not packaged proof.

Test keys use IDs prefixed test- and MUST NOT appear in production trust registries. No production private keys are needed for development. Owner supplies production public keys, custody policy and channel permissions.

Rotation: release an application trusting old+new keys under the old key, move release signing to the new key, remove the old key in a subsequent application release. One signature per envelope means old-only clients cannot read a new-only feed: a compatible bridge release/feed strategy is required. No remote revocation protocol exists in v1. Compromised-key recovery can require an independently authenticated manual installation; a revocation document signed by the compromised key is not independent recovery. State deletion/reinstall and trusted-signer compromise limits appear in section 8.

## 5. Envelope and exact signature preimage

Envelope has exactly these required fields:

| Field | Type/value |
|---|---|
| envelopeVersion | Integer exactly 1 |
| keyId | String satisfying section 3 |
| algorithm | String exactly Ed25519 |
| payloadBase64 | Canonical Base64 of exact UTF-8 payload bytes |
| signatureBase64 | Canonical Base64 of 64-byte signature |

Let ASCII encode exactly the displayed ASCII characters, U16BE/U32BE encode unsigned fixed-width big-endian integers, and || denote byte concatenation. K=ASCII(keyId), A=ASCII(algorithm), P=decoded payload bytes.

```
D = ASCII("RIFF-DESKTOP-UPDATE") || 0x00
M = D || U32BE(envelopeVersion)
      || U16BE(length(K)) || K
      || U16BE(length(A)) || A
      || U32BE(length(P)) || P
signature = Ed25519.sign(privateKey, M)
```

D is 20 bytes, hex `524946462d4445534b544f502d55504441544500`. A has length 7. No hash prepass, JSON reserialization, terminator or additional bytes. Length prefixes prevent concatenation ambiguity. The format version, key ID, algorithm, payload length and payload bytes are signed. Envelope whitespace/order and signature text are not signed but have strict parsing rules. Never parse then reserialize P for verification.

Changing whitespace/newlines inside P invalidates its signature. Reordering outer envelope properties or adding allowed outer whitespace preserves M. Changing keyId to a known alias of the same public key still changes M. Unsupported algorithm/version is rejected before cryptography without negotiation.

This is a deliberately NON-VERIFYING shape example: e30= decodes to {}, which fails the payload schema, and the all-zero signature is not a real signature. It is never a passing test vector.

```json
{"envelopeVersion":1,"keyId":"test-fixture-01","algorithm":"Ed25519","payloadBase64":"e30=","signatureBase64":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="}
```

## 6. Payload schema and compatibility

Exactly 14 required root fields; no mandatory flag in v1 because mandatory-update deployment policy is not approved. Preview is a channel, not a version suffix. One target/artifact per manifest; separate targets/channels receive separately scoped signed manifests.

| Field | Type and exact validation |
|---|---|
| schemaVersion | Integer 1 |
| appId | ASCII identifier `[a-z][a-z0-9.-]{0,127}`; policy requires com.musicglass.desktop |
| channel | String enum preview or stable; equals configured channel and signer permissions |
| version | Strict version below; target application version |
| releaseSequence | Integer 1..9007199254740991, monotonic per app/channel/target, never reused |
| publishedAt | Integer Unix UTC seconds 0..253402300799 |
| expiresAt | Integer same range; publishedAt < expiresAt <= publishedAt+2592000 (30 days) |
| target | ASCII `os-arch`, each component `[a-z][a-z0-9]{0,15}`; policy supports windows-x64 |
| minWindowsBuild | Integer 22000..2147483647; runtime build meets it, Windows 10 not eligible |
| minInstalledVersion | Strict version; oldest supported direct-upgrade source, <= version |
| workerProtocolMajor | Integer 1..65535, supported by trusted upgrade capability policy |
| databaseSchemaVersion | Integer 1..2147483647, target schema requiring a locally supported migration path |
| releaseNotes | Plain text 0..4096 Unicode scalars, <=16384 UTF-8 bytes; reject controls except TAB/CR/LF |
| artifacts | Array exactly 1 artifact object below |

Strict version grammar: three components major.minor.patch, each `0|[1-9][0-9]*` in 0..2147483647, total 5..32 ASCII bytes. No v prefix, suffix, prerelease/build metadata, signs, whitespace or missing components. Compare numeric tuples without subtraction overflow. Bad installed build version is CONFIGURATION_ERROR, never version zero. Reconcile actual installed/package version in later integration instead of selecting either conflicting current constant here.

Artifact object has exactly six required fields:

| Field | Validation |
|---|---|
| type | String msi |
| os | ASCII `[a-z][a-z0-9]{0,15}`; policy supports windows |
| arch | ASCII `[a-z][a-z0-9]{0,15}`, target must equal os + "-" + arch; policy supports x64 |
| url | Absolute ASCII HTTPS URL, 1..2048 bytes; policy below |
| sizeBytes | Integer 1..2147483648 (2 GiB) |
| sha256 | Exactly 64 lowercase ASCII hex digits encoding a 32-byte SHA-256 digest |

URL rules: https scheme, DNS ASCII host, absent/default 443 port, nonempty absolute path. Reject userinfo, fragment, IP literals, localhost/single-label hosts, backslashes, raw controls/spaces and opaque URIs. Percent escapes must be valid. Reject percent-encoded path slash/backslash/NUL/CR/LF and decoded dot path segments. Query is opaque, permitted within total bound, never logged. Parse once without conflicting URL interpreters; reject representations the selected URI parser cannot interpret under these rules. Compare host case-insensitively against an application-controlled deployment allowlist; do not normalize path/query bytes. Private-resolution checks and redirects belong to downloader: at most 3 hops, every hop HTTPS with standard TLS checks and allowlisted host, no downgrade. No redirect supplies trust.

No filename/path, shell command or installer arguments from metadata. Downloader chooses a generated basename inside its owned staging directory, uses fixed arguments and rechecks actual size/hash before execution. This document does not authorize the downloader's implementation or execution.

Trusted capability tables decide app/worker protocol and database upgrade compatibility; signed integers cannot introduce new migration code. A future major-protocol upgrade requires explicit supported package policy. Staging must verify bundled worker/data compatibility against metadata before activation.

Complete schema example below is illustrative, not a deployed release. The all-zero hash is syntactically valid placeholder text, not an artifact claim. Reserved example.invalid host is not a production allowlist entry.

```json
{
  "schemaVersion": 1,
  "appId": "com.musicglass.desktop",
  "channel": "preview",
  "version": "0.2.0",
  "releaseSequence": 2,
  "publishedAt": 1793577600,
  "expiresAt": 1794182400,
  "target": "windows-x64",
  "minWindowsBuild": 22000,
  "minInstalledVersion": "0.1.0",
  "workerProtocolMajor": 1,
  "databaseSchemaVersion": 1,
  "releaseNotes": "Illustrative desktop update; not a release.",
  "artifacts": [{"type":"msi","os":"windows","arch":"x64","url":"https://releases.example.invalid/desktop/example.msi","sizeBytes":123456,"sha256":"0000000000000000000000000000000000000000000000000000000000000000"}]
}
```

## 7. Typed boundary, ordered checks and errors

Future type flow: UntrustedEnvelopeBytes -> VerifiedPayload -> ValidatedManifest -> PolicyDecision -> EligibleUpdate. VerifiedPayload owns exact bytes, SHA-256 and signer permissions; only verifier can construct it. Only parser creates ValidatedManifest; only policy/coordinator creates EligibleUpdate. Non-public constructors and defensive immutable byte copies prevent accidental bypass; they are not a sandbox against arbitrary application code. Authentication is distinct from valid schema and eligibility.

Policy returns EligibleUpdate, UpToDate or AuthenticatedIneligible(reason). Invalid/authentication failures return Rejected(code), never a partial UpdateAvailable. Module 5 must remove any production unsigned evaluateManifest(text) shortcut.

```
collect at most MAX_ENVELOPE+1 bytes; reject excess
strict bounded envelope parse; reject duplicate/unknown/type errors
require supported envelopeVersion and algorithm
lookup active trusted registry key; validate local registry
validate Base64 syntax, lengths and predicted decoded size
bounded-decode P/signature; build exact M from section 5
JCA verify M/signature; otherwise INVALID_SIGNATURE
construct VerifiedPayload(P, digest, signer permissions)
strict bounded payload structural parse; extract schemaVersion
reject unsupported schemaVersion before applying v1 field sets
apply full v1 schema validation
check product/channel/target, key permissions and configured HTTPS hosts
capture fixed evaluation time; acquire serialized freshness-state decision lock
apply section 8 time, sequence, equivocation and version-floor rules
atomically persist accepted high-water state or reject STATE_UNAVAILABLE
apply installed-app, OS-build, worker and migration compatibility policy
compare installed/target version: older rejected, equal UpToDate, newer EligibleUpdate
```

Authenticated payload structural parsing checks valid JSON, duplicates and global resource limits first. Missing/wrong-type schemaVersion is INVALID_PAYLOAD. A well-typed unsupported schemaVersion then returns UNSUPPORTED_FORMAT even with unknown fields, before v1 field sets. For supported version 1, enforce exact fields and all schema rules. Global depth/string/body bounds still apply to unknown schemas, but v1 member names/counts do not. Syntactically valid wrong-target identifiers reach policy and return WRONG_TARGET; malformed identifiers or target/artifact inconsistency return INVALID_PAYLOAD. Numeric schema range violations are INVALID_PAYLOAD; SIZE_LIMIT is reserved for parser resource/token bounds. Schema version other than 1 is UNSUPPORTED_FORMAT only after authentication and bounded payload parsing. Unsupported envelope version/algorithm fails before authentication. Do not parse P as JSON for an update decision before verification. Do not mutate state for unauthenticated, malformed, wrong-target or expired data. Before installation, recheck state/time and eligibility: a previously eligible object has no perpetual authority. Freshness commit and eligibility decisions are serialized across concurrent checks; installation coordination must prevent using an offer invalidated by a newer accepted release.

| Stable code | Meaning |
|---|---|
| CONFIGURATION_ERROR | Invalid local key/build/capability configuration or missing crypto provider |
| SIZE_LIMIT | Byte/token/depth/member resource limit exceeded |
| INVALID_ENVELOPE | Envelope encoding/JSON/member/type/Base64/signature-length failure |
| UNSUPPORTED_FORMAT | Unsupported envelope format, algorithm or authenticated payload schema version |
| UNKNOWN_KEY | Unknown/inactive bundled key ID |
| INVALID_SIGNATURE | Properly encoded/sized signature fails verification |
| INVALID_PAYLOAD | Authenticated payload JSON/schema/hash/URI/version syntax invalid |
| WRONG_TARGET | Product/channel/OS/architecture, signer permissions or deployment host mismatch |
| INCOMPATIBLE | Installed version, OS build, worker or migration capability cannot upgrade |
| NOT_YET_VALID / EXPIRED / CLOCK_INVALID | Section 8 time checks |
| STALE_RELEASE / RELEASE_CONFLICT | Lower sequence or same-sequence different signed payload |
| ROLLBACK_REJECTED | Version lower than installed or accepted high-water target version |
| STATE_UNAVAILABLE | Cannot read/validate/commit local freshness state |
| ARTIFACT_INTEGRITY | Later downloader finds incorrect artifact size/hash |

Precedence is pipeline order, with streaming size checks applied where encountered. No raw exception messages or partial success objects escape. Cancellation stays cancellation. Log stable codes/local correlation IDs and bounded allowlisted identifiers; after authentication a payload digest/sequence may be logged. Do not log raw envelope/payload, signatures, keys, query URLs or arbitrary remote text. Render release notes as bounded plain text.

## 8. Replay, freeze, rotation state and recovery

Freshness scope=(appId,channel,target), independent of signing key. A releaseSequence names exactly one payload digest and target version. Republishing even just an expiry/notes change requires a higher sequence; same target version may be reissued under a higher sequence. Publisher must never increase sequence while lowering the target version.

Installed release bundles bootstrap sequence floor and version, optionally own manifest digest. Local state records accepted sequence/digest, highest target version and last accepted evaluation time per scope. These are trusted local configuration/state, not fetched from the current feed. Serialize concurrent decisions; persist atomically off the UI thread. Local state is not claimed tamper-proof against a user-account attacker.

Capture wall-clock UTC seconds once per check. effectiveNow=max(wallClockNow,lastAcceptedEvaluationTime). If wall clock is more than 300s behind persisted time, reject CLOCK_INVALID and request clock correction. Require publishedAt <= effectiveNow+300 and expiresAt > effectiveNow; no expiry grace. Signed lifetime cannot exceed 30 days. A forward/broken clock may deny updates; do not silently accept expired data or lower persisted time. Incorrect time before any local state exists cannot be detected reliably.

Before evaluating a feed response, reconcile bootstrap B and persisted S for the exact scope under the same decision lock. Validate B as trusted configuration and S as local state. If S is absent, use B. If B.sequence > S.sequence, use B.sequence/B.digest/B.version together; do not carry S.digest into that sequence. If S.sequence > B.sequence, retain the entire S sequence/digest/version binding. At equal sequence the versions must agree; two known differing digests are a local conflict (STATE_UNAVAILABLE), while one known digest binds the record and both unknown leave it unbound. Preserve the maximum independent highest-version floor (including installed/B/S versions) and S evaluation time; never lower higher persisted knowledge. Malformed bootstrap is CONFIGURATION_ERROR; malformed persisted state is STATE_UNAVAILABLE. Atomically commit normalization before returning eligibility, even if no new offer advances the sequence. Failed commit returns STATE_UNAVAILABLE. A bootstrap without a digest may bind the first authenticated matching-sequence payload only when its target version equals the bootstrap version. A lower version fails ROLLBACK_REJECTED; a higher version at that same sequence fails RELEASE_CONFLICT; neither binds a digest. Add explicit upgrade/rotation coverage for B<S, B=S, B>S and absent S.

Reject sequence below the reconciled effective sequence as STALE_RELEASE. At equal sequence compare against the digest bound to that effective sequence, not a discarded older stored digest; mismatch is RELEASE_CONFLICT. Same digest, nonexpired metadata is idempotently acceptable. If bootstrap floor has no digest, the first valid matching sequence may bind one. Reject a version below installed version or highest recorded target version even with a higher sequence; do not mutate state on this rejection.

After authentication, schema, target, freshness and version-floor checks, commit high-water metadata BEFORE eligible results, including a valid matching release later deemed incompatible. This deliberately prevents falling back to an older matching signed release merely because the latest is incompatible. Such a publisher mistake can deny automatic updates until corrected by a higher sequence/nondecreasing version or an independently authenticated manual upgrade. State records metadata accepted, not successful installation; install receipt is separate and advances only after confirmed activation.

Missing state bootstraps from bundled metadata; an empty file is corruption, not first run. Corrupt/unreadable/unwritable state returns STATE_UNAVAILABLE and disables automatic installation. Do not silently reset/delete state. Reinstall/state deletion can reset knowledge to bundled floors; v1 cannot distinguish malicious removal from genuine first run. A compromised signing key can authorize malicious releases or raise sequence/version to deny service.

Expiry only bounds replay if clocks are trustworthy; attackers can always withhold all responses. Previously unseen older metadata that remains newer than installed and unexpired can pass: there is no latest-release oracle. Once newer sequence was accepted, an older signed-but-newer-than-installed release is rejected. Installed-version comparison alone does not provide that protection. These limitations must appear in security claims.

Failed-install rollback is a separate recovery transaction. Do not lower freshness floors, silently install old feed releases, undo irreversible migrations or run an older binary against incompatible data. Later installer design must preserve compatible backups and define safe recovery, otherwise stop with an explicit manual path. Manual rescue via independently authenticated installer is outside automatic downgrade authorization.

## 9. Migration, modules and owner decisions

Unsigned legacy JSON and the old inventory schema are rejected, never promoted into trusted objects. Existing permissive tests must become legacy-rejection tests or be replaced by signed fixtures during integration. Do not change unrelated parseSimpleJson window-state callers as part of updater work.

Update modules (distinct from desktop milestones): Module 2 owns envelope/verifier/registry and packaged crypto probe; Module 3 owns strict payload parsing; Module 4 owns compatibility/version/freshness state; Module 5 owns composition and removal of unsigned entry points; Module 6 owns the release signer and expanded vector corpus; Module 2 acceptance already requires an immutable application vector independently generated and verified; Module 7 reviews integration and packaged checks. Downloader, Authenticode, installer execution and recovery need separate briefs. Eligible metadata is necessary, not sufficient, to install.

Owner/release decisions before production: actual application version; channels; sequence allocation/bootstrap metadata; production keys/custody/bridge rotation; real feed/artifact hosts and redirect allowlist; Windows minimum build; supported worker/migration capability tables; refresh operations within 30-day expiry; Authenticode identities; independent rescue distribution. No production values or mandatory policy selected here. Test configuration can exercise the portable protocol without these deployment choices. Wire/schema/security-policy changes require contract review and fixture updates.

## 10. Verification and interoperability plan

Use fixed clock, registry and local state. Sign negative-schema payloads correctly to reach parser/policy errors; avoid accidental expiry obscuring the intended case. U2-U7 below mean update modules, not desktop milestones.

| ID | Input/change | Expected result | Owner |
|---|---|---|---|
| V01 | Valid signed payload, trusted key and eligible config | VerifiedPayload then EligibleUpdate | U2-U5 |
| V02 | Flip payload byte, retain signature | INVALID_SIGNATURE before JSON payload parse | U2 |
| V03 | Flip signature byte or wrong trusted public key | INVALID_SIGNATURE | U2 |
| V04 | Change keyId to known alias of same key | INVALID_SIGNATURE; ID is bound | U2 |
| V05 | Unknown/inactive key | UNKNOWN_KEY | U2 |
| V06 | Change algorithm or envelope version to unsupported value | UNSUPPORTED_FORMAT, no negotiation | U2 |
| V07 | Unsigned legacy JSON or extra publicKey field | INVALID_ENVELOPE | U2/U5 |
| V08 | URL-safe/missing-padding/excess-padding/nonzero-unused-bits/whitespace Base64 | INVALID_ENVELOPE | U2 |
| V09 | Signature 63/65 bytes; malformed local key DER/type | INVALID_ENVELOPE / CONFIGURATION_ERROR | U2 |
| V10 | Duplicate/escaped-equivalent names in envelope | INVALID_ENVELOPE | U2 |
| V11 | Correctly signed duplicate/escaped-equivalent names in payload | INVALID_PAYLOAD | U3 |
| V12 | Invalid UTF-8, BOM, surrogate escape, trailing document | INVALID_ENVELOPE or signed INVALID_PAYLOAD by layer | U2/U3 |
| V13 | Exactly at limits vs one unit over; depth exceeded | Valid-at-limit if otherwise valid; SIZE_LIMIT above | U2/U3 |
| V14 | Valid signature, bad hash length/type/null/missing/unknown member | INVALID_PAYLOAD | U3 |
| V15 | Wrong product/channel/OS/arch/key permissions | WRONG_TARGET | U4 |
| V16 | Unsupported app/OS/worker/migration compatibility | INCOMPATIBLE after section 8 state rule | U4 |
| V17 | Malformed remote version; malformed local installed version | INVALID_PAYLOAD / CONFIGURATION_ERROR | U3/U4 |
| V18 | Older than installed/high-water version, even higher sequence | ROLLBACK_REJECTED; no state mutation | U4 |
| V19 | Older sequence still newer than installed | STALE_RELEASE after higher accepted sequence | U4 |
| V20 | Same sequence with changed, correctly signed payload | RELEASE_CONFLICT | U4 |
| V21 | Same sequence/digest, unexpired, target newer than installed | EligibleUpdate with idempotent state | U4 |
| V22 | Publication beyond +300s; expiry exactly now | NOT_YET_VALID / EXPIRED | U4 |
| V23 | Signed lifetime >30 days or expiresAt<=publishedAt | INVALID_PAYLOAD | U3 |
| V24 | Clock rollback >300s; corrupt/unwritable state | CLOCK_INVALID / STATE_UNAVAILABLE | U4 |
| V25 | Unicode notes; payload whitespace changed without resigning | Unicode accepted if valid; byte change INVALID_SIGNATURE | U2/U3 |
| V26 | Outer member reorder/allowed whitespace | Same preimage, verifies | U2 |
| V27 | Bundled overlap key vs new key absent in old client | Verify if permitted / UNKNOWN_KEY | U2/U4 |
| V28 | Concurrent high/low sequence checks and delayed installation | Serialized state prevents stale offer activation | U4/U5/downloader |
| V29 | Signed metadata, wrong downloaded hash or size | ARTIFACT_INTEGRITY; no execution | Downloader |
| V30 | Forbidden URL/host/credentials/path/extra arguments or redirect | INVALID_PAYLOAD/WRONG_TARGET; redirect denied later | U3/U4/downloader |
| V31 | Trusted signature over unsupported payload schema | UNSUPPORTED_FORMAT after crypto | U3 |
| V32 | Missing crypto provider or malformed local registry | CONFIGURATION_ERROR; no unsigned fallback | U2/U5 |
| V33 | Bootstrap lower/equal/higher than stored record, or no record | Section 8 deterministic merge preserves sequence/digest/version association and independent floors/time | U4 |
| V34 | Equal local bootstrap/stored sequence with conflicting known digest/version | STATE_UNAVAILABLE before offer acceptance | U4 |
| V35 | Bootstrap without digest; same-sequence incoming version mismatch | ROLLBACK_REJECTED if lower; RELEASE_CONFLICT if higher; no digest binding | U4 |
| V36 | Signed future schema with bounded extra fields; duplicate schemaVersion | UNSUPPORTED_FORMAT for future version; INVALID_PAYLOAD for duplicate | U3 |

A fixed real application vector and independent reproduction are mandatory before Module 2 approval, not deferred to Module 6. Module 6 expands the corpus and production signer. The Module 2 vector bundle includes: README with provenance, explicitly TEST-ONLY deterministic seed/private material, public key raw hex and canonical DER, payload.bin, preimage.bin and preimage.hex, signature.bin, envelope.json, expected-results.json with fixed clock/config/state, and SHA-256 file inventory. Cross-verify with JCA and another vetted implementation; use RFC vectors separately to validate primitive behavior. Record final-newline bytes. Invalid vectors mutate a known valid vector. No production key; no invented passing signature. Module 1 provides only the plan and deliberately non-verifying illustration, not a real signed application vector.

## 11. Review notes and primary references

This document is a proposed implementation contract, not a security certification. Remaining implementation checks: parser can enforce incremental token/depth/duplicate/scalar limits; packaged JCA provider availability; independent preimage/vector reproduction; freshness state durability/concurrency; downloader/installer enforcement. Resolve protocol contradictions before issuing Module 2. Do not weaken checks merely to reuse the regex parser.

Follow-up edits for later integration: reconcile package/application versions; replace unsigned tests; correct manifest-inventory schema and Windows target; accurately update M6/capability claims. Do not infer production deployment authorization.

Primary sources checked on 2 November 2026 (algorithm/provider, JSON and TUF sources read; key/Base64 encoding references independently checked before finalizing this document):

- [JDK 21 providers](https://docs.oracle.com/en/java/javase/21/security/oracle-providers.html): algorithm APIs and provider availability; packaged provider still requires testing.
- [Java standard algorithm names](https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html).
- [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032.txt), sections 5.1/7.1: Ed25519 and primitive test vectors.
- [RFC 8410](https://www.rfc-editor.org/rfc/rfc8410.txt): Ed25519 SubjectPublicKeyInfo and absent parameters.
- [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648.txt): Base64 padding, alphabet and canonical encoding.
- [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259.txt): JSON syntax, duplicate-name interoperability and Unicode.
- [TUF specification](https://theupdateframework.github.io/specification/latest/), version 1.0.37 observed: threat-model/freshness/rotation comparison; this protocol does not implement TUF.

## 12. Review resolutions (revision 2)

R1: section 8 now merges complete sequence/digest/version records for upgrades, preserves independent floors/time and defines failure handling. Example S=10/A/1.0.0, B=20/B/2.0.0 yields sequence 20 with B's digest; lower persisted digest A cannot reject B. Higher S survives lower B.

R2: section 6 separates identifier grammar and internal consistency from target policy; section 7 defines INVALID_PAYLOAD versus WRONG_TARGET.

R3: section 7 dispatches schema versions after bounded structural checks and before version-specific field sets.

R4: sections 9-10 make a fixed independently reproduced application vector an explicit Module 2 acceptance prerequisite.

Additional matrix cases required: V33 bootstrap below/equal/above persisted state and absent state; V34 equal-sequence local conflicting binding fails STATE_UNAVAILABLE; V35 bootstrap-without-digest binds only its declared version; V36 signed future schema with extra fields returns UNSUPPORTED_FORMAT after structural checks; duplicate schemaVersion remains INVALID_PAYLOAD. These are Module 3/4 fixtures, not claimed implemented in Module 2.
