# Riff

Android music player (Kotlin, Compose, Media3), one `:app` module plus `:benchmark`. Build, checks and lanes: [README.md](README.md). Documentation index: [docs/README.md](docs/README.md).

## Releases

The latest release is 0.21.0, published on GitHub on 6 November 2026 ([release notes](docs/release-notes-0.21.0.md)); 0.20.0 was published on 31 October. For how a release is built, signed and published, the owner's standing decisions, and how to set up a new laptop (the signing password must be restored there by the owner), read [docs/handover-0.20.0.md](docs/handover-0.20.0.md).

## Implementing new features

The next features (glass, Encore statistics, equalizer, Android Auto, Cast, Automix and more, 33 in all) are planned in [docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md](docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md). Before working on any of them:

- Read that plan's "How to use this plan during implementation" section, then the feature's own section.
- For Automix, the design reference is [docs/automix-engine-report-2026-10-28.md](docs/automix-engine-report-2026-10-28.md).
- Follow the plan's order of work.
- Line references in the plans are to commit 9ea71f4; check them against the current code.
- If the code or a measurement disagrees with the plan, update the plan in the same change and say why.
- Ask the owner before the decisions the plan leaves open, and before anything that publishes, pushes or writes to their YouTube Music account.

Before calling a stage done: `python scripts/quality/verify.py --lane pr`.
