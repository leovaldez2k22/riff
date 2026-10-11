# History, saved queues and the move from shared state (Q04)

Where listening history and saved queues live, who owns each row, and what happens to the history file and queue that earlier builds shared between every account. Code: `LibraryState.kt`, `LegacyStateMigration.kt`, `PlaybackSnapshot.kt`, `ListeningHistory.kt`, `EarlierDataScreen.kt`.

Owners are defined in [account-scope.md](account-scope.md).

## Database

`library-state.db`, schema version 1, write-ahead logging, parameterized SQL only. Every write runs in a transaction. A database written by a newer build is not opened for rewriting: the downgrade path throws and the store reports a failure instead.

| Table | Holds |
|---|---|
| `history_events` | One row per play. Key: owner + event identifier |
| `playback_snapshots` | One paused queue per owner, with its payload version |
| `scope_generations` | Per-owner counters that make clears and deletions reject older writes |
| `migration_runs` | What each migration read, its state, and how many rows it imported or rejected |
| `scope_aliases` | Journal of older account keys being folded into an account's primary key |

Rows hold identifiers and display metadata only. No cookies, stream addresses or account identifiers.

## History

- One event per logical play. A pause, a resume, a seek back to the start and later progress ticks belong to the same play. Each pass under repeat-one and a replay after the song ended are new plays. Live broadcasts are not recorded.
- Plays from YouTube Music belong to the owner that was active: an account or signed-out use. Plays of audio stored on the phone belong to the device, whichever account was signed in. An unverified account records nothing.
- The History screen shows one owner at a time, with **YouTube Music** and **On this phone** as separate lists.
- Reads are pages of 100, newest first, continued from the last row's time and event identifier, so plays that share a timestamp are neither repeated nor skipped.
- Search matches title or artist, ignores case and accents, and treats `%` and `_` as the characters typed.
- Writes report an outcome: stored, duplicate, stale, unavailable or failed. A retried write of the same event is stored once.
- Clearing an owner's history raises that owner's counter in the same transaction, so a write prepared before the clear is refused. The counter is stored, so this holds across a restart.
- Limits: 100,000 plays per owner; title and artist 180 characters. When a retention period is set, older plays are removed per owner. Plays dated in the future are not removed by retention. The retention setting has no screen yet (Q05).

## Saved queues

- One paused queue per owner. Emptying the queue clears only its own owner's row.
- Payload: named format `riff.playback-snapshot`, version 2. Version 1, written by earlier builds with no format name, is still read.
- A payload from a newer build is left untouched on read. If this build later has to save over it, the newer payload is first copied to `files/unassigned/playback-snapshot-rejected.json`.
- A payload that cannot be read is copied to the same file once and its row removed, so it is not parsed on every start.
- Ceiling: 2,000 items and 6 MiB measured in UTF-8 bytes, the same limit for storing a queue and for sending one to the playback service. A queue over the byte ceiling is refused, not truncated, and the app says the collection is too large to queue. A queue longer than 2,000 items keeps its first 2,000, as before.
- Saves from the player are applied in order on a background thread. Leaving the app or switching account waits for the last save for a bounded time.
- Local items are restored only from `content://media/external/audio/media/<id>` matching their identifier.

## What happens to data from earlier builds

Earlier builds kept one history file and one saved queue for every account used on the phone. Neither can be attributed to an account, so neither is given to one automatically.

Runs once per start on a background thread, before any saved queue is read, and is safe to repeat:

| Source | What happens |
|---|---|
| `files/listening-history/history-v1.jsonl` | Moved to `files/unassigned/` and named by its SHA-256. Plays of on-device audio are imported into the device's history. Plays from YouTube Music stay in the unassigned file |
| `last_playback` preference, key `snapshot` | Copied to `files/unassigned/playback-snapshot-v1.json`, then removed from preferences. Not loaded into the player |
| `playback_snapshots` preferences (per-owner queues from the Q03 build) | Imported into the database under the same owners, keeping their save times |

Rules:

- Imported plays get identifiers derived from the source's fingerprint and the line number. Running again, or finding the same file again, adds nothing. The same play listed three times stays three plays.
- A second, different copy of the old file (an older build was run again) adds only device plays not already imported from an earlier copy.
- Imported rows and their `migration_runs` marker commit in one transaction. The source is retired only after that.
- Lines that are not valid, or longer than 16 KiB, are counted as rejected and skipped. A source over 64 MiB is not read.
- A failure, including a full disk, leaves the source where it is and is reported with a stage and an error kind. The next start tries again.

### What the user decides

**Settings → Library → Earlier data**, also linked from a card on the History screen, appears while something is waiting.

- **Import plays**: adds the unassigned YouTube Music plays to the history of the owner active now, after a confirmation that says plays from other accounts are included and cannot be separated later. Not offered to an unverified account. The unassigned copy stays until deleted, and importing twice adds nothing.
- **Delete unassigned plays**: removes the unassigned copy. Imported plays stay.
- **Restore the earlier queue**: after confirmation, stores it as the active owner's queue and loads it paused, replacing the current queue. A queue of only on-device audio goes to the device.
- **Delete earlier queue**: removes it.

Nothing on this screen is sent to YouTube Music.

## Verification

JVM and Robolectric: `LibraryStateTest`, `LegacyStateMigrationTest`, `PlaybackSnapshotTest`, `ListeningHistoryTest`, `ScopedPlaybackSnapshotsTest`, `HistoryScreensTest`.

`LegacyStateMigrationTest` stops the migration at every checkpoint in turn, starts again as a new process would, and checks the end state is the same each time. It also covers a rollback inside the transaction and a full-disk failure followed by a successful retry.

Emulator, real playback service and database, network off, synthetic identities: `LegacyUpgradeDeviceTest`, with `AccountSwitchDeviceTest` and `FixturePlaybackTest` rerun.

Not verified here:

- An upgrade over a real install of an earlier build with real history. The migration is exercised in the running process on the emulator, not by installing the older APK and then this one.
- The phone-only checks that borrow the listener's saved queue were changed to read the database and are compiled only. `NativeIslandInstrumentation` is not ported and refuses to run.

See the Q04 entry in `execution-ledger.md`.
