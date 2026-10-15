# Final report (draft for Q18)

Riff 0.10.0-rc1, version code 22. Written 15 October 2026 in Q17; Q18 completes it after the final verification. The detailed record of every package, every run and every rerun is the [execution ledger](execution-ledger.md). This page summarizes it and does not add evidence of its own.

## Verdict so far

**Ready to hand to the owner as a prepared candidate. Not ready to publish.** Every package of the plan up to Q17 passes locally (JVM tests, lint, release checks) and, where it has device tests, on a disposable API 31 emulator with the network off. Nothing has been checked on a physical phone, against YouTube Music with an account, or on API 33 or 36. Publication also waits on two license questions (below) and the owner's explicit decision.

## What was done

| Package | Subject | Status |
|---|---|---|
| Q00-Q02 | Baseline, build identity and evidence, deterministic test harness | PASS |
| Q03-Q04 | Account scope v2, serialized account switching, history and queues per owner | PASS locally; two-account phone check BLOCKED |
| Q05, Q05A | Privacy and data controls; export and import | PASS locally with open items |
| Q06-Q08A | Playback state, recovery, crossfade and audio, normalize volume | PASS locally with open items; listening on a phone BLOCKED |
| Q09 | Downloads: restart, validation, damage | PASS locally with open items |
| Q10, Q10A | Provider contracts and fixtures; provider canary | PASS locally; live checks on a test account BLOCKED |
| Q11 | Ownership, structure, static checks | PASS locally |
| Q12, Q12A | Core journeys and design tokens; update notice | PASS locally; device walk-through BLOCKED; no release feed exists |
| Q13 | Accessibility and strings | PASS locally; TalkBack and keyboard walk-throughs BLOCKED |
| Q14 | Performance tooling, Baseline Profile | Tooling PASS; every phone budget BLOCKED; cold-start finding awaits a product decision |
| Q15, Q15A | Security review; exit-reason diagnostics | PASS locally; two medium items open |
| Q16 | CI lanes and portable tooling | PASS locally; hosted CI and API 33/36 BLOCKED |
| Q16A, Q16B | Lyrics: overlap, background vocals, held words, letter pop and shake; Remove download in menus | PASS locally and on the emulator; phone not yet checked |
| Q17 | Current documentation and packaging | See the ledger's Q17 entry |
| Q18 | Final verification and handoff | Not started |

Latest full runs (Q16B tuning, 15 October 2026): 993 JVM tests in 110 suites with no failures; lint 85 warnings, 3 hints, no errors; emulator lane 49 tests. Release APK 28,263,130 bytes, under the 40 MiB ceiling.

## Open items, by weight

**Needs the owner's decision**

- Publishing: where, when and whether. No release feed or public repository exists (Q12A).
- ML Kit and its Play services parts are under Google's terms in a GPL-3.0 app; needs a qualified review, or translation removed ([inventory](../dependency-license-inventory.md)).
- The four design reference images in `references/` are in the source archive with no recorded copyright holder.
- The jsDelivr pre-processed player that the pinned library runs in the solver: keep, or turn off and accept earlier breakage ([security review](security-review.md), medium).
- The cold-start budget cannot be met while the start-up logo runs for 1.15 s ([performance](performance.md)).
- Visual changes made from arithmetic rather than a design review: darker scrims (Q13), black text on pink buttons (Q12).

**Needs a phone (the owner will lend one; installing needs their confirmation of the phone and the build)**

- The device lane, the 13-step journey walk-through, TalkBack and keyboard walk-throughs.
- Listening: crossfade, booster, normalization, audio focus, Bluetooth.
- Kill and power loss during a download; Android 14 and 15 foreground-service limits.
- Upgrade over the real rc20 install with real history; the release APK on a device.
- Every performance budget, battery and soak (tier R and tier L).
- Lyrics letter pop and shake on a real song; the player's menu on a downloaded song.

**Needs network or an account (a dedicated test account, never a personal one)**

- Provider live checks in [provider compatibility](provider-compatibility.md), the stream phase of the canary, a live loudness value, a real download.
- Hosted CI (needs a push, not authorized), and the emulator lane on API 33 and 36.

**Engineering follow-ups**

- Subpackages and removing the provider facade (ADR-Q01 deviation, [adr.md](adr.md)).
- A pinned formatter or analyzer; R8 Kotlin-metadata warnings since Q00.
- An upstream seam in innertubex to replace the reflection seam (ADR-Q05).
- The unused ZemerTeam configuration address in `AppPlayerConfigRepository`.

## What Q18 must do

Run every lane that can run, rebuild the candidate from the final commit, compare it with the Q17 package, rerun the phone items if the phone is available and authorized, update this report with the results, and hand the package to the owner. Publication stays the owner's decision.
