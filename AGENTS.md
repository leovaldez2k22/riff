# Riff

Android music player (Kotlin, Compose, Media3), one `:app` module plus `:benchmark`. Build, checks and lanes: [README.md](README.md). Documentation index: [docs/README.md](docs/README.md).

## Releases

The latest release is 0.21.0, published on GitHub on 6 November 2026 ([release notes](docs/release-notes-0.21.0.md)); 0.20.0 was published on 31 October. For how a release is built, signed and published, the owner's standing decisions, and how to set up a new laptop (the signing password must be restored there by the owner), read [docs/handover-0.20.0.md](docs/handover-0.20.0.md).

## Current Android work

The working source is unreleased 0.30.0 (version code 36). The supported floor is Android 12 / API 31 (owner, 8 November); Android 11 / API 30 work is out of scope. Do not resume its investigation or the reverted codec experiment. Follow [the active implementation plan](docs/feature-implementation-plan-0.30.0-2026-11-07.md) and its [Riff / Riff+ prerequisite](docs/riff-plus-split-plan-2026-11-07.md), including their verification logs. The old feature plan below is a shipped-feature record.

`riff` is the default edition; use `assembleRiffDebug` / `assemblePlusDebug` and the corresponding test/release tasks. Shared code stays in `app/src/main`; Plus-only code, model, libraries and rules stay in `app/src/plus` with neutral Riff seams in `app/src/riff`. Both use the same package, version and signing key. The PR lane checks both editions. Continuous implementation is authorized; publication, pushes and real-account writes retain their explicit owner gates.

## The feature plan

Every feature of [docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md](docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md) (glass, Encore statistics, equalizer, Android Auto, Cast, Automix and more) shipped in 0.20.0, except those the owner dropped (6, 21 and 31). It is now a record, not a list of next features. What it still leaves open: Automix stage 3 and later (beat-matching, blocked by its own timing gate), and the "Not done" notes in its implementation log. Before working on any of those:

- Read the feature's section and its row in the implementation log.
- For Automix, the design reference is [docs/automix-engine-report-2026-10-28.md](docs/automix-engine-report-2026-10-28.md).
- Line references in the plans are to commit 9ea71f4; check them against the current code.
- If the code or a measurement disagrees with the plan, update the plan in the same change and say why.
- Ask the owner before decisions the plan leaves open, and before anything that publishes, pushes or writes to their YouTube Music account.

Before calling a stage done: `python scripts/quality/verify.py --lane pr`.
