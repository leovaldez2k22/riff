# Architecture decisions: status and deviations (Q17)

The decisions are written in [the implementation plan](../RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md), section 4 (ADR-Q01 to ADR-Q06). This page records, as of 15 October 2026 and version 0.10.0-rc1, how each was carried out, where the code departs from it and why, with the smallest alternative. The evidence is in the [execution ledger](execution-ledger.md) under the package named.

## ADR-Q01: Incremental architecture

Status: **carried out with one deviation and one exception.**

- Kept: one app module, `:app`; a process-owned `AppContainer` that builds the stores, provider parts and coordinators and hands them to screens and the service; entry-point class names unchanged (`MainActivity`, `MusicPlaybackService`, `OfflineDownloadService`, `OfflineDownloadJob`, `SolverService`, the widgets). One source of truth each for the active account (`SessionCoordinator`), the queue (`QueueCoordinator`), the sleep deadline and the crossfade (`CrossfadeOwner`). See [architecture](architecture.md).
- **Deviation: no subpackages.** The plan names target packages (`account`, `playback`, `downloads` and so on). Every class stayed in `com.musicglass.personal` (Q11). Moving them would have turned each move into a visibility change across hundreds of `internal` and `private` members and mixed mechanical moves with behaviour changes, which the ADR itself forbids. Ownership is enforced instead by the owner table in architecture.md and by `scripts/quality/static_checks.py` (direction, lifecycle, blocking and cancellation rules). Smallest alternative: move one owner group per commit once a phone is available to rerun the device lanes after each.
- **Deviation: the provider facade remains.** `YouTubeMusicProvider` forwards to four parts (session, catalog, library, streams) so no caller or test changed in the move. New code takes the narrow interface. Removing the facade is a follow-up.
- **Exception: a second module**, `:benchmark` (Q14), required by the plan for Macrobenchmark. It holds tests only and sees nothing internal.

## ADR-Q02: Identity and data ownership

Status: **carried out**, with one addition.

- Guest, DeviceLocal and Provider owners as written. The provider key is `s2_` + SHA-256 of a versioned prefix and the verified stable identifier; the chooser position is excluded. Older keys become aliases only when re-derivation from the verified identifier reproduces them and they hold data (Q03). See [account scope](account-scope.md).
- **Addition: a fourth variant, Unverified**, for a signed-in session without a stable identifier. The ADR requires that such a session never merge into Guest; making it a type of its own lets the store refuse writes for it and the screens say so.
- Not checked: identifier stability across two real accounts on a phone (the ADR's two-account device check). Fixtures and the emulator pass; the phone check is open (ledger Q03).

## ADR-Q03: Persistence

Status: **carried out**, with one deviation.

- `library-state.db` (SQLiteOpenHelper, WAL, parameterized SQL, transactions) holds history, queue snapshots, the scope registry and migration records. `offline-music.db` and the collection preferences are unchanged.
- **Deviation: recent searches stay in preferences**, keyed per owner (Q05). They are 30 short strings per owner with no query need; moving them would have added a migration for no behaviour. The search list shared by earlier builds is shown to no owner and imported or deleted from Earlier data.

## ADR-Q04: Account transition

Status: **carried out** as the seven steps, through `SessionCoordinator.transition` (Q03, Q04). Interpretations recorded in the ledger: explicit import of earlier data is offered to signed-out use as well as to a verified account (unverified is refused); a queue over 2,000 entries keeps its first 2,000 and only the byte ceiling refuses. Not checked on a phone: an upgrade over a real earlier install with real history (BLOCKED, needs an authorized device).

## ADR-Q05: Solver safety

Status: **carried out**; the reflection seam remains, as the ADR allows.

- Challenges are solved only in the `:solver` process. If the bridge cannot be installed, every online resolution is refused with a typed `SolverUnavailable`; local, downloaded and already playing audio keeps working (Q05, Q08).
- innertubex 0.7.2 has no public way to supply a cipher, so the contract-tested reflection seam is kept, and `scripts/quality/check_solver_seam.py` checks it in each shrunk release. Reflection has not been removed and nothing claims it has. A minimal upstream seam is a follow-up.
- **Recorded in Q17:** when a challenge is not solved otherwise, the pinned library fetches a pre-processed YouTube player from `cdn.jsdelivr.net/gh/MetrolistGroup/faraday` and runs it in the solver's QuickJS (no cookies, size-capped, cached a day). It was not disclosed before this package. It is now in [privacy](../privacy.md) and a medium open item in the [security review](security-review.md). The app also names a ZemerTeam configuration address that the library's allow-list refuses, so it is never requested. Smallest alternative: turn the remote source off (`AppPlayerConfigRepository.enabled`), at the cost of playback breaking sooner when YouTube changes its player.

## ADR-Q06: Privacy controls

Status: **carried out** as listed (Q05), with **Earlier data** in place of a single "Delete unassigned legacy data" row, because it also offers import. Clearing an account's history also clears its recently played list and the play counts kept with its downloads. Known limit: data under an older, not yet merged key of the same account is not covered by Delete local data until the background merge has moved it. See [privacy controls](privacy-controls.md).

## Decisions made outside the ADRs

| Package | Decision | Smallest alternative |
|---|---|---|
| Q12A | The update check ships with no feed address: no endpoint exists, and the plan forbids inventing one. No setting is shown and no request made | Set `riffUpdateFeed` once a public release feed exists |
| Q13 | Darker scrims and tones over artwork, chosen from contrast arithmetic rather than a design review | One constant each in `RiffContrast` |
| Q12 | Filled buttons use black text on pink for contrast | One line in `RiffTheme` |
| Q15 | Three media-session commands (remove, move, replace queue items) withheld from other apps | One array in `WebBoundaries.kt` |
| Q14 | Performance budgets are evidence only from a physical phone; none has been measured | Run the benchmark lane on a tier R or L phone |
