# Riff Desktop Preview — Manifest and packaging inventory

UPDATE-07 local metadata acceptance (2 November 2026): scoped self-review closed three integration/harness findings; 146 desktop tests, 12 signer tests, six isolated packaged smoke launches and all nine PR gates passed. See [review](handoffs/update-07-review.md) and [result / separate production gates](handoffs/update-07-result.md). This is not independent security approval, live deployment, Authenticode or installer acceptance. M6 remains incomplete.

Updated 2 November 2026 for UPDATE-05. Current protocol: [update trust contract revision 2](update-trust-contract-v1.md). Earlier unsigned inventory examples are superseded and are not accepted by the client.

## Identity and runtime

| Attribute | Value |
|---|---|
| Application | Riff Desktop Preview |
| Declared desktop version | 0.1.0, preserved; riffDesktopVersion in desktop/build.gradle.kts generates both packageVersion and META-INF/riff-desktop.properties |
| Supported initial target | Windows 11 x64; update schema minimum build 22000, actual release compatibility configured explicitly |
| Executable | Riff Desktop Preview.exe |
| Runtime | Bundled JDK 21; includes jdk.accessibility, jdk.crypto.ec and desktop runtime dependencies |
| Graphics / native interop | Skiko / Skia; JNA 5.19.1 |

Desktop version is independent of Android 0.20.0. Missing/malformed runtime identity or disagreement with configured installed version disables updates with CONFIGURATION_ERROR.

## Licenses and data

Inter variable font uses the Android-identical binary and bundled META-INF/licenses/INTER-OFL.txt. Other runtime license metadata is supplied in dependency jars; see [SBOM](sbom.md), which is an inventory rather than a full generated dependency audit.

Actual current host defaults are user.home/.riff/db for library-state.db/WAL and user.home/.riff/desktop-window-state.json for geometry. Audio/art cache classes accept injected roots; this inventory does not claim they are all integrated at LOCALAPPDATA. The update coordinator opens no default user-data root. Deployment must inject an owned canonical app-data freshness root, shared by all instances. File freshness publication currently supports fixed local Windows NTFS storage. Tests exclusively use temporary directories.

## Authenticated metadata format

Transport contains exactly envelopeVersion, keyId, algorithm, payloadBase64 and signatureBase64. Ed25519 signs exact payload bytes with the contract's domain/length prefixes. No remote publicKey, filename, shell arguments or mandatory flag is accepted. The immutable independently generated envelope is [the test vector](../../desktop/src/test/resources/update-v1/envelope.json); its key is TEST-ONLY and is not production trust.

Decoded schema-v1 payload example below is **not an envelope or an update offer**. It must be signed, then verified, parsed and pass durable policy; illustrative hosts/times/hashes are not deployment configuration.

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
  "releaseNotes": "Illustration only.",
  "artifacts": [{
    "type": "msi", "os": "windows", "arch": "x64",
    "url": "https://releases.example.invalid/desktop/example.msi",
    "sizeBytes": 123456,
    "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
  }]
}
```

DesktopUpdateClient accepts bounded caller-supplied envelope bytes/streams. Verification precedes strict parsing, target/signer/host checks and locked durable sequence/digest/version/time evaluation. Only a committed compatible result can become eligible metadata. Revalidation verifies retained bytes again and checks current state/time. Missing production registry/channel/hosts/bootstrap/capabilities/storage leaves the default client disabled. No feed transport, artifact download, Authenticode, installer execution or recovery transaction is implemented by this coordinator.