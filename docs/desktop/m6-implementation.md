# Desktop M6 implementation — Packaging, signing, and update manifests

UPDATE-07 local metadata acceptance (2 November 2026): scoped self-review closed three integration/harness findings; 146 desktop tests, 12 signer tests, six isolated packaged smoke launches and all nine PR gates passed. See [review](handoffs/update-07-review.md) and [result / separate production gates](handoffs/update-07-result.md). This is not independent security approval, live deployment, Authenticode or installer acceptance. M6 remains incomplete.

The owner authorized implementation on 1–2 November 2026. The desktop module remains an additive opt-in exception to the Android-only module rule (`:desktop` under `-PriffDesktop=true`); Android `:app` and `:benchmark` retain their boundaries.

## Current update status — 2 November 2026

M6 is incomplete. [UPDATE-05](handoffs/update-05-result.md) integrates offline authenticated metadata decisions and removes unsigned evaluation. Runtime version comes from the declared desktop package version (0.1.0); production update configuration is absent and defaults disabled. Collector, downloader/staging, installer integrity, Authenticode, activation/recovery and clean Windows VM acceptance remain separate gates. No production signing, installer execution or live-feed acceptance is claimed. Offline TEST-ONLY signing is documented in UPDATE-06/07.

The packaging and smoke measurements below are historical local evidence from 1–2 November 2026, not rerun by UPDATE-05. The prior inventory example was unsigned and did not implement authentication; current inventory now follows contract revision 2.

## Historical packaging work

1. **Standalone Windows Release Packaging (`createDistributable`)**:
   - Packaged through Compose Multiplatform native distribution pipeline with bundled minimal OpenJDK 21 runtime.
   - Includes full Java Access Bridge accessibility modules (`jdk.accessibility`, `com.sun.java.accessibility.AccessBridge`) for NVDA, JAWS, and Windows Narrator.
   - Output directory: `desktop/build/compose/binaries/main/app/Riff Desktop Preview/`.
   - Windows executable: `Riff Desktop Preview.exe`.

2. **Automated Startup & Memory Verification Pipeline (`desktop_smoke.py`)**:
   - Executes packaged binary across standard 1.0 and 1.25 high-DPI scaling factors.
   - Accurately tracks Win32 descendant process tree working set, pagefile, private memory, and JVM heap.
   - Verified warm first frame latency: ~2.3s – 2.4s.
   - Verified total process tree working set: 265 MB – 273 MB (JVM active heap: 13.8 MB – 18.2 MB).

3. **Manifest and Licensing Topology (`manifest-inventory.md`)**:
   - Complete inventory of runtime libraries, graphics engines, and licenses.
   - Bundled Inter 4.1 variable font OFL license in `META-INF/licenses/INTER-OFL.txt`.
   - Current manifest specification follows the signed envelope/schema-v1 contract for Windows 11 x64; the earlier portable-zip/rollbackPolicy example is superseded.
