# Riff

Android music player (Kotlin, Compose, Media3), one `:app` module plus `:benchmark`. Build, checks and lanes: [README.md](README.md). Documentation index: [docs/README.md](docs/README.md).

## Release 0.20.0 in progress

The 0.20.0 release was moved to another laptop part-way through on 30 October 2026. Before anything else, read [docs/handover-0.20.0.md](docs/handover-0.20.0.md): the owner's decisions, what is done, the open questions, the remaining steps in order, and how to set up a new laptop (the signing password must be restored there by the owner).

## Implementing new features

The next features (glass, Encore statistics, equalizer, Android Auto, Cast, Automix and more, 33 in all) are planned in [docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md](docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md). Before working on any of them:

- Read that plan's "How to use this plan during implementation" section, then the feature's own section.
- For Automix, the design reference is [docs/automix-engine-report-2026-10-28.md](docs/automix-engine-report-2026-10-28.md).
- Follow the plan's order of work.
- Line references in the plans are to commit 9ea71f4; check them against the current code.
- If the code or a measurement disagrees with the plan, update the plan in the same change and say why.
- Ask the owner before the decisions the plan leaves open, and before anything that publishes, pushes or writes to their YouTube Music account.

Before calling a stage done: `python scripts/quality/verify.py --lane pr`.

## Desktop phase execution

When the owner says to start a phase, work toward fully completing that phase. Do not end after a component increment or interpret passing component tests as phase acceptance. Stop unfinished phase work only for a concrete external restriction, and report the exact restriction and remaining gates. Required Phase 1 dependency downloads were approved on 2 November 2026; do not request the same approval again.

## Desktop Phase 2/3 owner decisions (3 November 2026)

The owner requested full completion of Phase 2, followed by Phase 3, and delegated routine implementation decisions and required development downloads while away. Official codec test downloads are approved; retain source URLs/checksums and keep test media out of the distribution. The owner explicitly approved sending track title, artist, album and duration to lrclib.net and lrc.red and requested automatic lyrics lookup as soon as playback starts (not only when the lyrics view opens). Do not ask again for these approvals. Account credentials/provisioning and physical hardware acceptance still require genuine evidence; no passing fixture can replace them. Publishing and unrelated account/system changes remain outside this desktop implementation request.


## Screen acceptance scheduling (3 November 2026)

The owner uses this laptop during the day. Hold all screen/interactive UI acceptance until the owner explicitly says to start screen tests. Continue implementation and non-UI background verification. Do not activate or automate desktop windows while this hold is in effect.
