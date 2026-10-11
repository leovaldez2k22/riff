# Account scope and ownership (Q03)

How Riff decides whose data, queue and downloads are active, and what happens when the account changes. Code: `AccountSession.kt`, `ScopedPlaybackSnapshots.kt` (owner rules), `LibraryState.kt` (storage).

## Owners

| Owner | When | Storage key |
|---|---|---|
| Guest | Signed out | `guest` |
| Provider account | Signed in with a stable account identifier | Chosen once per account, see below |
| Unverified | Signed in, but the provider returned no stable identifier | None. Nothing is saved or downloaded |
| Device | On-device audio files | `device-local` (queue and history) |

Unverified is never treated as Guest. Save and download actions say that the account could not be verified, and the Account screen offers **Verify account**, which reopens sign-in. Existing data is kept.

## Storage key

The account's scope is `s2_` + SHA-256 of `riff-account-scope:v2:` + the stable identifier. The account-chooser position is not part of it, so reordering accounts in the chooser no longer changes which data the app shows.

Older builds keyed data by identifier plus chooser position. That data is not moved on sign-in:

- An older key is accepted as belonging to an account only when re-deriving it from the verified identifier reproduces it and it already holds data. Names, pictures and "the account currently signed in" are never used as proof.
- The first time an account is seen, one key becomes its primary key and is stored in the `account_scopes` preferences. Later launches read that preference only.
- If the same account has data under several older keys, a background pass folds them into the primary key. Download folders are moved, not copied. Rules: validated audio beats queued metadata; a manual pin wins; play counts and listening time add up; saved tracks keep the primary key's order and add new entries from the other key. A song that both keys downloaded in full, or that the primary key is transferring right now, is left where it is and counted in the `account_scope` event. The pass can be interrupted and run again.

Raw identifiers are never logged. Events carry owner kinds and counts only.

## Session token and stale work

The provider publishes one `SessionToken` (owner, generation, per-process nonce). The generation rises on every sign-in, switch, cookie refresh and sign-out.

- `SessionCoordinator.accepts(token)` is true only for the current token and never during a transition. Queue requests and provider streams carry the token they were built with; the playback service answers a stale one with "skipped" and changes nothing. Radio continuation checks it before adding tracks.
- `SessionCoordinator.owns(token)` is true while the same owner is active, including after a cookie refresh. Downloads use it: a refresh does not interrupt a transfer, a switch does. An interrupted song goes back to "queued" under its own owner and no file is written under the new one.

## Switching accounts

Sign-in commit and sign-out run through `SessionCoordinator.transition`, one at a time:

1. Participants quiesce while the outgoing owner is still active. The playback service cancels any crossfade, pauses, saves the queue under the outgoing owner with a confirmed write, then stops and clears it. If the save fails the switch is abandoned and the previous account stays active.
2. The provider commits the new session.
3. Participants bind. The playback service loads the incoming owner's saved queue paused, if it has one and the player is empty. Collections rebind. Downloads are rescheduled.

A queue made only of on-device files belongs to the device: it keeps playing through a switch and nothing is cleared. A queue with any provider item, including a mixed one, belongs to the account that created it.

Refreshing the same account (same owner) does not quiesce anything.

## Saved queues

One paused queue per owner, stored in `library-state.db` since Q04. Emptying the queue clears only its own owner's row. On a cold start the service restores the newer of the active account's queue and the device queue, paused.

The single shared queue written by builds before owners existed is not loaded automatically, because it cannot be attributed to an owner. It is set aside and can be restored, paused, or deleted from **Settings → Privacy and data → Earlier data**. Storage, limits and the upgrade steps are in [state-and-migration.md](state-and-migration.md).

## Verification

JVM and Robolectric: `AccountSessionTest`, `AccountScopeProviderTest`, `ScopedPlaybackSnapshotsTest` (owner rules; storage is in `LibraryStateTest`), `AccountScopeMergeTest`, `AccountScreenSessionTest`, `DownloadOwnerSwitchTest`.

Emulator, real playback service, network off, synthetic session fields: `AccountSwitchDeviceTest`. It skips itself on an install that holds a real account.

Not verified: two real accounts on a physical phone (identifier stability across chooser reorder, and the full sign-in screens). See the Q03 entry in `execution-ledger.md` for the rerun steps.
