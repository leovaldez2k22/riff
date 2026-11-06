# UPDATE-04 — Compatibility and durable freshness: Gemini execution brief

Date: 2 November 2026. Implementer: Gemini. Reviewer: Codex after handoff.
Prerequisite: reviewed Module 3. Read [shared instructions](README.md).

## Objective

Decide whether authenticated, schema-valid metadata is eligible for this installation, while durably preventing regression below accepted release knowledge. A signed newer-than-installed release can still be stale, expired, incompatible or wrongly scoped.

## Current state

Read contract sections 6–10 and revision-2 resolutions R1/R2; UPDATE-01 review and Modules 2/3 results. Inspect UpdateEnvelopeVerifier.kt, Module 3's actual parser/version types, DesktopUpdateClient.kt, desktop/build.gradle.kts, DesktopHost.kt and existing desktop storage/worker protocol definitions found with rg.

The old client compares only permissive versions. There is no demonstrated durable sequence/digest state in that implementation. Desktop package version is 0.1.0 while the old client defaults to 0.20.0. Do not pick one as a production release decision.

Proposed owned files: UpdateEligibilityPolicy.kt, UpdateFreshnessStore.kt, UpdateEligibilityPolicyTest.kt, UpdateFreshnessStoreTest.kt and update-04-result.md. Follow real source names where equivalent owners already exist.

## Implementation approach

1. Introduce explicit trusted local configuration: app/channel/target, installed strict version, minimum OS/build observations, supported worker protocols/migration paths, host allowlist, registry signer context and bundled bootstrap. Inject a fixed clock and state store. Invalid local configuration returns CONFIGURATION_ERROR.
2. Enforce product/channel/target, signer permissions and case-insensitive deployment host membership after syntax validation. Valid but unsupported context returns WRONG_TARGET. Never learn trust/configuration from the feed.
3. Serialize the entire scoped reconciliation/evaluation/commit transaction. Scope is appId/channel/target, independent of key. Define process and multi-instance locking behavior; all supported instances must share the transaction lock, or explicitly prevent competing instances. Do not rely on a coroutine mutex alone for cross-process durability.
4. Implement section 8's complete bootstrap/persisted-record merge exactly. Sequence/digest/version travel together. At equal sequence versions agree and known digests cannot conflict. Preserve independent highest-version and evaluation-time floors. Distinguish absent file from empty/corrupt/unreadable. Do not delete/reset corrupt state.
5. Capture UTC once per transaction; implement max(now,lastAcceptedTime), >300-second clock rollback rejection, publication +300-second tolerance and strict expiry without grace. No clock-reset bypass.
6. Enforce stale sequence, equal-sequence digest binding/conflict and version floors, including a higher sequence with lower target version. Bootstrap lacking a digest can bind only its declared version. Keep state unchanged on rejected incoming metadata; follow contract normalization/commit semantics and document exact transaction ordering.
7. Atomically persist accepted/normalized high-water state off the UI thread before returning offers, including a matching valid release later deemed incompatible. Use an owned app-data path with injected temporary storage in tests. Define flush/atomic-replacement and crash behavior; if required durability is unavailable fail STATE_UNAVAILABLE rather than silently use a non-atomic fallback.
8. Apply installed-version/minimum-Windows/worker/migration capability checks after required state commit. Return UpToDate for equal installed version, EligibleUpdate for newer eligible metadata, or typed ineligibility. Keep construction restricted. Keep installation receipt separate; no installation implemented.
9. Preserve stable errors and cancellation; bound persisted-state parsing. Document the absence of tamper-proof storage/latest-release oracle and state-deletion limitations.

## Scope boundaries

No downloader, installer, feed access, production deployment values, UI redesign or Android persistence changes. Do not add migration code merely because a signed integer requests it. No real user database for tests. Do not lower freshness floors for recovery. Module 5 will wire the policy into the client.

## Acceptance criteria

- V15–V24, V27 permissions, V28 transaction ordering and V33–V35 reconciliation have deterministic tests with injected clocks/storage.
- B below/equal/above S, absent S, optional digest, equal-sequence local conflicts and independent maximum version/time all match R1. Example S=10/A/1.0.0 and B=20/B/2.0.0 accepts B, not digest A.
- Expiry exactly now rejects; +300s and rollback boundary values are tested without sleeping.
- Lower sequence cannot win a race or overwrite higher knowledge; failed writes never expose an eligible result. Concurrent processes or an enforced single-instance mechanism are exercised.
- Corruption, empty state, access denial, truncated/interrupted writes and failed atomic replacement fail closed; prior durable state survives interruption.
- Incompatible matching releases advance metadata knowledge as specified; wrong-context/expired/malformed/rollback inputs cannot advance it.
- Repeated same-sequence/same-digest checks are idempotent. Same sequence/different payload returns RELEASE_CONFLICT; malformed bootstrap returns CONFIGURATION_ERROR.
- Policy outcomes contain no raw remote exception strings; real user data remains untouched.

## Verification commands

```powershell
.\gradlew.bat -PriffDesktop=true :desktop:test --tests '*UpdateEligibilityPolicyTest' --tests '*UpdateFreshnessStoreTest' --offline --console=plain
.\gradlew.bat -PriffDesktop=true :desktop:test --offline --console=plain
python scripts/quality/verify.py --lane pr
```

Add an owned subprocess test/probe command if cross-process serialization requires it; record exact invocation. Manual review: walk the full section-8 transaction with a write failure at each persistence boundary and confirm UI-thread IO is absent.

## Handoff requirements

Write update-04-result.md with the shared evidence format. Include state format/location, atomicity and locking guarantees, bootstrap truth table, policy API and all acceptance mappings. State filesystem/platform assumptions and unresolved configuration. Give Module 5 explicit transaction and revalidation entry points, without implying metadata authorizes installation.
