# M6 packaging evidence and open update acceptance gates

UPDATE-07 local metadata acceptance (2 November 2026): scoped self-review closed three integration/harness findings; 146 desktop tests, 12 signer tests, six isolated packaged smoke launches and all nine PR gates passed. See [review](handoffs/update-07-review.md) and [result / separate production gates](handoffs/update-07-result.md). This is not independent security approval, live deployment, Authenticode or installer acceptance. M6 remains incomplete.

Current status (2 November 2026): **M6 incomplete**. UPDATE-02 through UPDATE-05 implement offline authenticated metadata decisions; production configuration defaults disabled. Collector, downloader/staging, exact artifact size/hash enforcement, Authenticode, activation/recovery and clean Windows VM acceptance remain open. See [UPDATE-05 handoff](handoffs/update-05-result.md) for current evidence.

The packaging, MSI, smoke timings and 57-test results below are historical local reports from 1–2 November 2026, not fresh UPDATE-05 measurements or proof of update authentication. The legacy client originally cited here accepted unsigned JSON; its earlier authentication claim was incorrect. UPDATE-05 removes that client path. Historical evidence paths and observations are retained with this correction.

## 1. Implemented & Integrated Deliverables

1. **Standalone Windows Application Distribution (`createDistributable`)**:
   - Packaged through Compose Multiplatform native distribution pipeline with bundled minimal OpenJDK 21 runtime.
   - Built to: `desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe`.
   - Includes Java Access Bridge accessibility modules (`jdk.accessibility`, `com.sun.java.accessibility.AccessBridge`) for NVDA, JAWS, and Windows Narrator.
   - Custom Win32 frameless chrome (`WindowsChrome.kt`) and bundled Inter variable font.

2. **Windows Installer Package (`packageMsi`)**:
   - Built with WiX toolset through `:desktop:packageMsi`.
   - Packaged to: `desktop/build/compose/binaries/main/msi/Riff Desktop Preview-0.1.0.msi`.
   - Configured with per-user installation (`perUserInstall = true`), Start Menu shortcut group (`Riff`), and stable upgrade UUID (`6C31D8DF-8C9B-4F7D-B8D5-A1E2F3B4C5D6`).

3. **Automated Smoke Diagnostic Benchmark (`scripts/quality/desktop_smoke.py`)**:
   - Executed against the packaged executable with scale factor matrix (1.0 and 1.25).
   - Warm first frame: ~2.70s – 2.75s (markerCount: 1).
   - Active JVM heap: 13.7 MB – 17.4 MB.
   - Descendant process tree peak working set: 274 MB – 281 MB.
   - All 6/6 runs exited with exit code 0 (`artifacts/quality/desktop/smoke-test.json`).

4. **Update metadata coordinator (current correction)**:
   - DesktopUpdateClient now composes Ed25519 envelope verification, strict schema parsing and durable compatibility/freshness policy.
   - Legacy unsigned evaluation, permissive version comparison and directly constructible legacy offers were removed.
   - Production trust and deployment configuration remain absent; defaults are disabled.
   - Metadata eligibility is not installer authorization. Later activation must serialize/revalidate against newer accepted knowledge and expiry.

5. **Software Bill of Materials (`docs/desktop/sbom.md`)**:
   - Manually maintained direct/runtime inventory with corrected identity and security scope; not a complete generated dependency audit.

6. **PR Quality Lane Verification (`python scripts/quality/verify.py --lane pr`)**:
   - Passed with `# pr lane: PASS_LOCAL` across all 9 validation checks:
     - `script-tests`: PASS
     - `static`: PASS
     - `build`: PASS
     - `artifactIdentity`: PASS
     - `jvm`: PASS
     - `lint`: PASS
     - `release-content`: PASS
     - `apk`: PASS
     - `solver-seam`: PASS

## 2. Automated Checks

- **Desktop Unit Test Suite**: **57 unit tests, 0 failures, 0 skipped** (including `DesktopUpdateClientTest`, `LargeLibraryBenchmarkTest`, `WorkspaceLayoutTest`).
- **Smoke Benchmark**: 6/6 runs pass with exit code 0 (`artifacts/quality/desktop/smoke-test.json`).
- **PR Quality Lane**: PASS_LOCAL across all 9 checks.

## 3. Manual Checks & Observations

- Verified packaged binary boots and reaches first Compose frame in ~2.7s.
- Verified absence of uncaught thread interruption exceptions during clean exit.
- Verified MSI installer package created in output build directory.

## 4. Outstanding Hardware Gates

- **[Other hardware]** Code signing with production Authenticode certificate and trusted timestamping (requires owner signing credentials).
- **[Other hardware]** Clean Windows VM test (machine without developer toolchain or pre-installed JDK).
- **[Other hardware]** Authenticated auto-update feed client against live production server endpoint.
