# Phase 10 release candidate handoff (historical rc1 preparation)

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

This record predates the rc3 phone installation. The current handoff is [phase-10-rc3-handoff.md](phase-10-rc3-handoff.md).

Status: **offline release preparation complete; M10 DEVICE_CHECK_PENDING; M9 DEVICE_CHECK_PENDING**.

The user deferred phone-dependent Phase 9 work and asked to do viable Phase 10 work without USB. Phase 9's offline implementation is recorded in `phase-9-offline-review.md`; its phone gate remains pending. This candidate is therefore a handoff for later device validation, not a passed release gate or public distribution approval.

## Candidate contents

- Version `0.9.0-rc1`, code `2`, application ID `com.musicglass.personal`.
- Signed release APK and SHA-256: `artifacts/release/`. The APK is signed by a new local release key in ignored `.toolchain/signing/`. The source archive excludes that key and all account data. Back up the key and password file together before any upgrade testing; losing either would require another migration.
- Reproducible build entry point: `scripts/build-release-candidate.ps1`. Source archive: `scripts/package-source.ps1`. Source includes the GPLv3 license and notices. Build output is not byte-for-byte reproducible across toolchains or signing keys; the hash identifies this particular candidate.
- Original demo tracks and art stay in the app, with Demo labels and an explicit provider source selector. The Library's Connected features screen labels cloud sync, collaboration, Cast, AI generation, humming recognition, release alerts and billing unavailable. YouTube Music search, liked songs and saved online rows explicitly say browse-only; the Play affordance is removed while real-song playback remains unsupported by the current provider response.
- There is no SQL database. Local collections, playback, podcast progress and offline-copy metadata use app-private preferences/files; their stored keys and formats are unchanged in this candidate. The installed debug app and this release candidate have different signing certificates, so Android cannot upgrade directly between them while preserving app data. The prior authorized data migration is documented in `phase-8-account-review.md`; do not uninstall the current app as part of this offline pass. [Android's update requirements](https://developer.android.com/google/play/app-updates) require the same signing certificate or a valid rotation proof.

## Offline checks

The release script runs `:app:testDebugUnitTest`, `:app:lintRelease` and `:app:assembleRelease`, then `apksigner verify --verbose --print-certs` and records the APK SHA-256. Dependency resolution is captured separately in `artifacts/release/dependencies.txt`. Direct dependency families and asset licenses are listed in `dependency-license-inventory.md`; the transitive audit is `dependency-license-audit.csv`. The source archive is built from an allowlist of source and handoff files, with a fixed ZIP entry timestamp, and is hashed in the same checksum file.

## Device matrix and remaining gate

| Case | Existing evidence | Candidate status |
|---|---|---|
| iQOO Neo 7 Pro, Android 16, gesture navigation | Phase 1–8 original-demo and account checks in linked reviews | This `0.9.0-rc1` APK not installed |
| Fresh install | Earlier debug builds only | Pending phone check |
| Same-key upgrade preserving app data | Earlier debug updates and an authorized selective migration after certificate mismatch | Pending two release-key builds on phone |
| 200% text, TalkBack, reduced motion, RTL, thermal soak | Phase 9 plan only | Pending Phase 9 phone validation |
| Real YouTube Music stream | SABR endpoint returned `RELOAD_PLAYER` before bytes | Known unsupported path; no pass claimed |

M10 requires the complete phone smoke suite on a fresh install and a same-key upgrade, including library, playback, permissions, player, Podcasts, Radio, account isolation, and restoration. Record the installed APK's hash in the device report and compare it with `SHA256SUMS.txt`. Keep the phone's current app data untouched until a migration or isolated test profile is approved. Public distribution remains a separate decision.

## Follow-up backlog

1. Complete Phase 9 on the primary phone, including accessibility and release-like profiling, then fix any findings.
2. Validate this exact release candidate on a fresh installation and validate an upgrade signed by the same release key. Check saved collections and offline demo copies before and after.
3. Resolve the upstream SABR `RELOAD_PLAYER` failure before claiming real-song playback or the original streaming milestone.
4. If public distribution is intended, review the full resolved dependency license set, source delivery channel, trademark positioning and long-term release-key custody.

No phone, USB debugging, account session or playback data was accessed for this Phase 10 preparation.
