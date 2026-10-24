# Documentation

Current as of 0.10.0 (version code 33), 24 October 2026. Anything not listed under "Current" is a historical record, kept as written and marked as such at its top.

## Current

For people using Riff:

- [Features and behavior](features.md)
- [Privacy and permissions](privacy.md)
- [Release notes for 0.10.0](release-notes-0.10.0.md) (final candidate, not published) and the [changelog](../CHANGELOG.md)
- [Security policy](../SECURITY.md)

For people building or changing it:

- [README](../README.md): build, phones, check lanes
- [Contributing](../CONTRIBUTING.md), [release signing](release-signing.md)
- [Dependency license inventory](dependency-license-inventory.md) and its [CSV](dependency-license-audit.csv); [third-party notices](../NOTICE.md); [asset provenance](../licenses/placeholder-art.md)
- The plan the current work follows: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md)

Quality documents (`quality/`):

| Topic | Document |
|---|---|
| Final state, open items, readiness | [final-report.md](quality/final-report.md) |
| Architecture, ownership, static checks | [architecture.md](quality/architecture.md), [adr.md](quality/adr.md) |
| Testing and lanes | [testing.md](quality/testing.md), [ci-lanes.md](quality/ci-lanes.md), [deterministic-harness.md](quality/deterministic-harness.md), [build-identity-and-evidence.md](quality/build-identity-and-evidence.md) |
| Accounts and data | [sign-in-design.md](quality/sign-in-design.md), [account-scope.md](quality/account-scope.md), [state-and-migration.md](quality/state-and-migration.md), [privacy-controls.md](quality/privacy-controls.md), [local-export-import.md](quality/local-export-import.md) |
| Playback | [playback-state.md](quality/playback-state.md), [recovery.md](quality/recovery.md), [crossfade-and-audio.md](quality/crossfade-and-audio.md), [downloads.md](quality/downloads.md) |
| Provider | [provider-compatibility.md](quality/provider-compatibility.md) |
| Interface | [core-journeys.md](quality/core-journeys.md), [accessibility.md](quality/accessibility.md) |
| Performance | [performance.md](quality/performance.md) |
| Security and diagnostics | [security-review.md](quality/security-review.md), [exit-diagnostics.md](quality/exit-diagnostics.md), [update-notice.md](quality/update-notice.md) |
| Record of the work | [execution-ledger.md](quality/execution-ledger.md), [q18-bugs.md](quality/q18-bugs.md), [final-stretch-plan.md](quality/final-stretch-plan.md); [baseline.md](quality/baseline.md) (historical, Q00) |

## Historical records

Earlier plans, phase reviews, release candidates 0.9.0-rc1 to rc20 and the 0.10.0-rc1 notes, dated fixes and verification reports: `app-revamp-*`, `phase-*`, `release-notes-0.9.0-*`, `release-notes-0.10.0-rc1`, `update-2026-10-06`, `improvement-plan-*`, `remediation-plan-*`, `stress-test-*`, `overnight-*`, `motion-*`, `animation-*`, `lyrics-upgrade`, `playback-fix-*`, `sign-in-crossfade-fixes-*`, `downloads-crossfade-verification-*`, `artwork-fix-*`, `ui-polish-*`, `no-demo-update-*`, `spotify-feasibility-*`, `youtube-music-playback-check-*`, and at the top of the repository `MUSIC_APP_IMPLEMENTATION_PLAN.md` and `YTMUSIC_INTEGRATION_AGENT_GUIDE.md`. They describe the builds of their time and may name the project's earlier account or its earlier name, YouTube Music Glass.
