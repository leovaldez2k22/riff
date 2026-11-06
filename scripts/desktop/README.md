# Offline desktop update signing

Implements UPDATE-06 against [contract revision 2](../../docs/desktop/update-trust-contract-v1.md). This is maintainer tooling, not an enabled feed, Windows Authenticode tool or installer. No production key or release is supplied. Do not pass private-key contents/passphrases in arguments, environment variables, logs or Git.

## Build and run

Use the existing Node installation (tested Node 24.19.0 / OpenSSL 3.5.7), JDK 21 and current standalone desktop jars. Build first with the repository's cached toolchain:

```powershell
.\gradlew.bat -PriffDesktop=true :desktop:test :desktop:createDistributable --offline --console=plain
node scripts/desktop/sign-update-manifest.cjs --payload <exact-payload-file> --key-id <approved-key-id> --private-key <external-pkcs8-der-file> --public-key <expected-spki-der-file> --output <new-envelope-file> --java <jdk-21-java.exe> --validator-app <rebuilt-Riff-Desktop-Preview-directory>
```

Quote paths containing spaces. All flags are explicit, with no endpoint/channel/version/sequence defaults. --public-key is optional but recommended to catch key selection mistakes. Private key must be an unencrypted, canonical PKCS#8 DER Ed25519 key; encrypted/PEM/RSA/EC/Ed448 forms are not supported. Public expectation is the exact canonical 44-byte SPKI DER. This tool provides no key generation, custody, passphrase handling, hardware-token support or key conversion. Keep production material outside the checkout and use an owner-approved custody workflow. Test-only RFC material is public and unsuitable for any real release.

--validator-app is the rebuilt distribution containing app/*.jar. --java is a trusted local JDK 21 java.exe; the stripped application runtime does not include a Java command launcher. The tool starts the separate com.musicglass.desktop.UpdateManifestValidationMain class directly, never UI Main or an unknown-argument fallback. It reads exact bounded bytes on stdin, invokes the same Kotlin schema reader as authenticated parsing and returns only a protocol marker/error or SHA-256 of syntax-valid bytes. No trust registry, user data, manifest, offer or network is initialized. Missing/old validator classes or mismatched output fail closed. Use trusted jars built from the reviewed contract; this is not a sandbox against a replaced local validator/JDK.

Syntax validation completes before private-key reading/signing. Node does not duplicate JSON/schema parsing. Bytes are read once (maximum 65536), not trimmed/reformatted/decoded/reserialized. Domain, big-endian lengths and Ed25519 preimage are independently assembled in Node; signature is verified locally before output. Envelope is exactly five fields with canonical padded standard Base64 and one final LF. Payload bytes, including any final LF/whitespace, remain unchanged. No URL is fetched.

## Output and failure contract

No --force and no clobbering: choose a new output path. Tool writes an owned sibling temporary file, fsyncs it, closes it and atomically creates the final name using a same-volume hard link. Existing output wins even if created concurrently. Unsupported hard-link filesystems/permissions fail with no copy/delete/overwrite fallback. Windows local NTFS was exercised. Temporary cleanup removes only the generated file. Before publication a process interruption leaves no final output, possibly a private orphan temp; after publication the final name references a complete flushed file. This is process-crash/atomic-visibility protection, not proof against every storage power failure or flush-ignoring device. A failure after publication/cleanup may leave a complete output: verify it rather than blindly retrying or overwriting.

Only stable diagnostics are printed; no key, envelope, signature, payload, exception detail or URL query is printed. Exit codes:

| Exit | Marker after RIFF_SIGN_UPDATE | Meaning |
|---|---|---|
| 0 | OK | Complete envelope published |
| 2 | USAGE / KEY_ID | Missing/duplicate/unknown flags or bad identifier |
| 3 | PAYLOAD_ERROR / INVALID_PAYLOAD / SIZE_LIMIT / UNSUPPORTED_FORMAT | File or shared schema rejection |
| 4 | KEY_ERROR / KEY_MISMATCH / VERIFY_ERROR | Invalid/unsupported/noncanonical private key, unexpected public key or failed verification |
| 5 | OUTPUT_EXISTS / OUTPUT_ERROR | No-clobber refusal or write/flush/publication/cleanup failure |
| 6 | VALIDATOR_ERROR | Missing/unusable/timed-out validator or wrong digest/protocol |
| 7 | INTERNAL_ERROR | Other failure; raw error intentionally withheld |

Private input buffers are wiped best-effort; OpenSSL/GC copies are not guaranteed erased. File paths rather than key bytes appear in process arguments. Protect the host, output directory and key permissions through the approved release environment.

## Operator duties before a real release

The owner must approve version/channel/target, keys and key custody, host allowlists, bootstrap floors, release-sequence allocation and compatibility tables. The signer validates syntax, not a release's real artifact/compatibility claims, clock validity or eligibility for a specific installation. Do not copy TEST-ONLY values into production.

Allocate a strictly nondecreasing target version and a never-reused increasing releaseSequence for the product/channel/target scope, independent of key ID. One sequence binds one exact payload digest/version: changing notes, whitespace, URL, hash or expiry requires a higher sequence. Publish within its <=30-day signed lifetime; refreshing expiry needs a new sequence, not editing/re-signing an existing sequence. Maintain an allocation ledger/authority; this tool never guesses, increments or recycles a sequence. Correct an incompatible release with a higher sequence/nondecreasing version, not lowered client floors.

Rotation needs a bridge application trusting old+new keys, followed by new-key releases and later old-key removal. An old-only client cannot authenticate a new-only feed. Compromised-key recovery needs an independently authenticated manual path; there is no remote root/revocation mechanism here. State deletion/reinstall, bad initial clocks, trusted-signer compromise and response withholding remain limitations.

Ed25519 authenticates metadata. Windows Authenticode publisher checks, exact downloaded artifact size/hash, redirect/address policy, staging, activation serialization/receipts and data-compatible recovery are separate gates. Signing an envelope does not authorize executing an installer. Production remains disabled until explicitly configured and accepted.

## Offline reproduction and review

```powershell
node --version
node desktop/src/test/resources/update-v1/generate.cjs
node scripts/desktop/generate-update-vectors.cjs
node --test scripts/desktop/sign-update-manifest.test.cjs
.\gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain
python scripts/quality/verify.py --lane pr
```

Run builds/lane sequentially. Original Module 2 vector files must remain byte-identical; the first generator is a deterministic reproduction check, not permission to change those anchors. The second generator owns only the distinctly named release-corpus subtree and uses public TEST-ONLY RFC and label-derived material. It intentionally signs malformed negative fixtures without the release signer's rejection gate; it must never be used to produce a release.

The 27-case corpus includes exact payload/preimage binary+hex/signature/envelope, fixed configuration and case expectations with SHA-256 inventory. Node tests exercise the actual release tool against the shared Kotlin validator, deterministic byte preservation, keys, no-clobber/races and interrupted output. Kotlin verifies independent signatures and all coordinator outcomes with fixed clocks/isolated state. Actual signer output is byte-identical to valid/envelope.json; packaged JCA diagnostics verify the actual emitted file. See [Module 6 result](../../docs/desktop/handoffs/update-06-result.md) for exact local evidence and commands.
### Reproduce an emitted TEST-ONLY envelope without real credentials

From the repository root, after rebuilding the validator, this PowerShell block reconstructs only the already-public RFC fixture key in a new temporary directory. Choose a fresh output name because the signer will not overwrite it. Do not substitute production key bytes into this example.

```powershell
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("riff-signer-manual-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$testKey = Join-Path $testRoot 'TEST-ONLY.der'
try {
    $testHex = '302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60'
    $testBytes = [byte[]](0..(($testHex.Length / 2) - 1) | ForEach-Object { [Convert]::ToByte($testHex.Substring($_ * 2, 2), 16) })
    [IO.File]::WriteAllBytes($testKey, $testBytes)
    node scripts/desktop/sign-update-manifest.cjs --payload desktop/src/test/resources/update-v1/release-corpus/valid/payload.bin --key-id test-rfc8032-01 --private-key $testKey --public-key desktop/src/test/resources/update-v1/release-corpus/public-key.der --output artifacts/desktop/update-signer/reproduced-envelope.json --java .toolchain/jdk-21.0.12.1+1/bin/java.exe --validator-app 'desktop/build/compose/binaries/main/app/Riff Desktop Preview'
    if ($LASTEXITCODE -ne 0) { throw 'TEST signing failed; inspect the stable diagnostic.' }
} finally {
    if (Test-Path -LiteralPath $testKey) { Remove-Item -LiteralPath $testKey }
    Remove-Item -LiteralPath $testRoot
}
```

Verify emitted bytes equal release-corpus/valid/envelope.json. Then invoke the rebuilt executable's existing `--verify-update-fixture test-rfc8032-01 <release-corpus/public-key.der> <reproduced-envelope.json>` diagnostic, with paths quoted; this verifies opaque authentication only. `UpdateReleaseVectorsTest` separately checks those identical bytes through full coordinator policy. Exact actual invocation/exit evidence is in the Module 6 result and signer-run.json/packaged-results.json artifacts.

## Real codec acceptance inputs

`python scripts/desktop/download-codec-test-inputs.py` fetches only the approved official FFmpeg test files pinned in `native/riff-audio/codec-test-inputs.json`. It checks host, bounded byte size and SHA-256, keeps the complete original HE-AAC v2 check package under `.toolchain/desktop-codec-tests`, and never adds media to the shipped app. The package is retained for local noncommercial codec testing under its supplied terms. `prepare-phase3-corpus.py` creates the other variants and Float32 reference hashes from an actual local recording; `:desktop:nativeCodecTest` verifies real complete decoded PCM and exact seeks. A codec family name is not evidence for every corrupt/container profile.

The explicit `:desktop:stageSoakRuntime` diagnostic task freezes runtime files and hashes. JFR is initialized with jcmd after a healthy worker handshake and before any playback, bounded to 32 MiB and ten minutes of history. JVM StartFlightRecording startup logging was observed on stdout despite an Xlog warning/stderr setting, so that startup option is not passed into the binary-IPC worker. The standalone eight-hour process is muted and fails on the first observed underrun; it does not change endpoint/master volume or substitute elapsed time for hardware presentation.

## Soak status

`python scripts/desktop/soak-status.py <result.json or evidence directory>` prints `RUNNING`, `STALE`, `PASS` or `FAIL`. A file that says `RUNNING` is believed only while the process named by its `pid` and `processStartMs` is alive and `heartbeatEpochMs` is at most 2 minutes old (the soak refreshes it every 30 s); otherwise the run is `STALE`. `--json` prints one JSON object. Exit code 0 for RUNNING or PASS, 1 for STALE or FAIL, 2 if the file can't be read.

## Evidence manifest

`artifacts/` is not in git. `python scripts/desktop/evidence-manifest.py write` records the desktop evidence files (relative path, size, SHA-256) in `docs/desktop/evidence-manifest.json`. `check` reports any recorded file that is missing or changed and exits 1 if so; newer files are listed but don't fail it. Run `write` again after adding evidence you want kept.
