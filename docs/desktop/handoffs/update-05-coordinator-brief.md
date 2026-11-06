# UPDATE-05 — Coordinator and unsigned-path removal: Gemini execution brief

Date: 2 November 2026. Implementer: Gemini. Reviewer: Codex after handoff.
Prerequisite: reviewed Modules 3/4 and Module 2 evidence checked. Read [shared instructions](README.md).

## Objective

Make authenticated verification, strict parsing and durable eligibility the only update-decision path. Remove the old unsigned shortcut and misleading authentication claims while keeping updates disabled when production configuration is missing.

## Current state

Inspect DesktopUpdateClient.kt, DesktopUpdateClientTest.kt, DesktopWindowStateManager.kt, UpdateEnvelopeVerifier.kt, UpdateVerificationProbe.kt, Main.kt, desktop/build.gradle.kts and actual Modules 3/4 APIs. Search all usages of DesktopUpdateManifest, UpdateCheckResult, compareSemVer, evaluateManifest and update-related UI before deciding integration points.

Read contract sections 7–10, all prior result reports, docs/desktop/manifest-inventory.md, m6-implementation.md, m6-acceptance.md, capability-matrix.md and sbom.md.

The old client accepts unsigned JSON, short digests and string mandatory; its class comment claims cryptographic protection it does not supply. Version defaults disagree with packaging. Existing standalone hash checking does not authenticate metadata. Main's --verify-update-fixture probe intentionally verifies opaque bytes only.

Owned scope: DesktopUpdateClient.kt/tests, minimal actual update call sites/build-version metadata wiring, relevant desktop claims/inventory documents and update-05-result.md. Preserve unrelated window-state parsing.

## Implementation approach

1. Define a coordinator whose input is bounded untrusted envelope bytes/stream plus locally owned configuration. Compose verifier -> parser -> target/state/policy in contract order. Preserve each stable error category and cancellation. No parser or state work on invalid signatures.
2. Replace/remove production evaluateManifest(String) and permissive version helpers as updater entry points; migrate tests and callers to signed fixtures. Do not leave an unsigned fallback, debug backdoor or direct trusted-object constructor.
3. Centralize installed desktop version so the packaged application and eligibility use one verified source. Inspect packaging/build APIs first. Preserve the declared release version unless the owner approves changing it; remove the Android-derived default and fail on malformed/missing identity. Test that packaged metadata matches.
4. Inject explicit keys, channel, hosts, bootstrap and compatibility tables. Do not bundle TEST-ONLY trust into production. Missing owner configuration gives a typed disabled/configuration-error state, never an available offer or invented production endpoint.
5. Keep concurrent checks serialized through Module 4. Attach offer identity to the committed sequence/digest/scope and expose a revalidation path so a delayed consumer cannot reuse an offer superseded by a newer check or expiry. Installation remains absent; describe the future activation lock requirement.
6. Do not add a network collector implicitly. Accept caller-supplied bounded bytes for this module; if an existing collector exists, inspect its byte/encoding/cancellation limits and isolate it from artifact access. No real feed calls. Request identity encoding/reject unsupported encoding if collector integration is actually in scope; otherwise report that collector gate separately.
7. Keep release notes bounded plain text, failure presentation stable and logging sanitized. Preserve the opaque diagnostic's explicit semantics; add a separate test-only coordinator probe if needed rather than relabeling signature verification as eligibility.
8. Update inventory/examples to contract revision 2 and Windows 11 x64. Correct M6 and capability claims to distinguish implemented authentication from download/install/Authenticode/live-feed/VM gates. Retain historical evidence with its source/date rather than rewriting old passes as fresh results.

## Scope boundaries

No downloading/staging/executing installers, Authenticode implementation, new mandatory-update policy, production keys/feed or Android behavior changes. No wholesale settings/window-state refactor. Hash utility may remain as a utility, with no install authorization implied.

## Acceptance criteria

- A valid signed, compatible, fresh fixture reaches one eligible result only after state commit; equal version reaches UpToDate.
- Unsigned legacy/extra public-key input, bad signature, malformed signed payload, wrong target and state failure cannot produce an offer or trigger artifact access.
- Invalid signatures do not invoke payload parser/store (spies or equivalent observable tests).
- No remaining production unsigned decision callers/helpers; usage search and typed-boundary review recorded.
- Package/runtime version matches one source, and missing deployment configuration is explicitly disabled/fail-closed.
- A held offer becomes unusable after higher accepted sequence or expiry; revalidation uses the same scoped transaction rules.
- Full desktop suite uses temporary storage. Resolve the documented WorkspaceLayoutTest isolation defect if still present through minimal test injection, without deleting user state or masking failures.
- Docs no longer call the legacy client authenticated or claim M6 completion from metadata tests alone.

## Verification commands

```powershell
.\gradlew.bat -PriffDesktop=true :desktop:test --tests '*DesktopUpdateClientTest' --offline --console=plain
.\gradlew.bat -PriffDesktop=true :desktop:test :desktop:createDistributable --offline --console=plain
python scripts/quality/verify.py --lane pr
rg -n 'evaluateManifest|compareSemVer|DesktopUpdateManifest|UpdateAvailable|parseSimpleJson' desktop/src
```

Review search matches individually; window-state parsing may legitimately remain. Run packaged opaque verification and any coordinator probe offline using the exact existing probe argument contract; log actual executable paths/exit codes. Do not open a live feed or installer.

## Handoff requirements

Write update-05-result.md with changed interfaces/callers, removed shortcuts, version source, configuration behavior and docs corrected. Map tests to each pipeline phase and include full-suite results/failures. List still-open collector/downloader/installer/deployment gates. Stop for review before Module 6.
