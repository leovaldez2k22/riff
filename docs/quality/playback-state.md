# Playback state and command order (Q06)

How the app describes what the player is doing, how queue entries are identified, and how commands that arrive close together are ordered. Recovery policy is described in [recovery.md](recovery.md) (Q07).

## Typed state

`PlaybackState` (`PlaybackState.kt`) is published by `PlaybackGateway` as `state.playback`.

| Field | Meaning |
|---|---|
| `status` | Exactly one of the values below |
| `desiredPlayWhenReady` | What the user last asked for. Can be true while the status is Resolving, Buffering or Recovering |
| `queueRevision` | Raised by every structural change to the queue: replace, add, remove, move |
| `queueItemId` | The selected entry |
| `owner` | Storage key of the owner whose queue is loaded; blank when nothing is, or when it may not be saved |
| `durationMs` | Duration of the selected entry |
| `failure` | A typed failure with a fixed kind; the message shown and the actions offered are derived from it |
| `recovery` | Why the app is retrying by itself: looking a stream up again, the same for a live stream, or waiting for a connection |

| Status | When |
|---|---|
| Idle | Nothing is queued |
| Resolving | The selected entry's stream is being looked up; no audio has been requested yet |
| Buffering | Audio is loading |
| Playing | Audio is playing |
| Paused | A queue is loaded and silent: paused, restored after a restart, or held by the system |
| Recovering | Playback failed and the app is retrying by itself |
| Ended | The queue played to its end |
| Failed | Playback failed and nothing is being retried |

The position is separate (`PlaybackGateway.positionMs`) and does not cause the broad state to change.

The service publishes queue revision, owner, the entry being looked up and the recovery reason in the session extras, next to the sleep-timer deadline. They contain no stream address, cookie or account identifier other than the opaque storage key.

Screens still read the older fields (`playing`, `wantsPlayback`, `error`), which are filled from the same refresh. Since Q07 a failure of the player is worded by recovery and published by the service; the wording of command failures is unchanged. Moving screens to `status` belongs to Q11 and Q12.

## Queue entries

- Every entry gets an identifier when it enters the queue. Two copies of one recording are two entries. The video identifier is never used to decide which row is meant.
- A replacement for an entry keeps its identifier: a recovered stream, a live fallback, and a swap between audio and video.
- Identifiers are not stored. A restored queue gets new ones; order and duplicates are preserved.
- Queue edits from the app (move, remove, select) name a row as displayed. The gateway finds that entry by identifier where it is now and does nothing when it has left the queue.
- A swap of the playing stream carries the identifier of the entry it was requested for. If the user has moved to another entry, the swap is dropped.
- **Not interested** still removes every copy of a song. That is by video on purpose.

## Command order

Commands that change the queue (open a collection, play a song, play on-device audio, restore the saved queue, add to queue, swap a stream) go through one lane in the service (`OrderedCommands`).

- Each command gets a ticket when it arrives. Commands are applied one at a time in arrival order, on the main thread. Reading and decoding the request file happens off the main thread inside that turn.
- A command that replaces the queue makes every earlier command obsolete. An obsolete command is answered as cancelled, before it starts or after it has finished reading, and never touches the queue. The app does not show an error for it.
- Commands that only add to the queue do not cancel one another and are applied in the order they were made.
- A pause made after a command arrived wins: the queue loads silent. A play made after that pause starts it again. Pause from the app, the notification, a headset or a widget all count.
- A command built for a previous account is still dropped, as before.
- A command that fails while it is read or applied is answered as failed. It does not stop the service or hold up the commands behind it.
- The app side orders its own requests the same way before they are sent, so a request still being written when a later one is made is not sent.
- Moving to another entry ends recovery for the entry left behind.

Main thread: every Media3 call. Background: request files, the database, history.

## Removing entries

- Removing the selected entry selects the one that follows it in play order. Shuffle and repeat are respected, because the player's own next entry is used.
- If nothing follows, the entry before it is selected at its start, paused. Without this the player jumps to the first entry.
- Removing the only entry leaves an empty, paused queue.
- Removing another entry does not move the selection.

## Saving and restoring

Unchanged from Q04, verified again here:

- The queue is saved about every 5 seconds while the service runs and on every player event. Only the newest save is written.
- Leaving the app or switching account waits a bounded time (1.5 s) for the last save to be written to the database. If Android ends the process first, the last routine save stands. No preference `apply()` is relied on as a guarantee; the sleep-timer deadline uses one and is not part of queue restoration.
- After a restart the queue comes back paused at the stored position. It never starts by itself.
- Removing the app from recents stops playback.
- Opening a queue looks up no streams. Lookup happens for the selected entry when playback is asked for, and for the next entry when the player preloads it.

On the emulator (API 31), with 17 s of on-device playback per run, the stored position trailed the player by at most about 5.0 s across 5 checkpoints. After the process was killed without warning, between 2.8 s and 3.8 s of position was lost in the runs made, and the queue was restored paused at exactly the stored position. The figures of the recorded run are in the execution ledger. The test allows the 5 s cadence plus 1.5 s for the timer, the hand-off and the write.

## Verification

JVM and Robolectric: `PlaybackStateTest` (status table, failure wording, command order, the lane with a slow older request, removal rule on a real player, duplicate entries), with `QueueRemovalTest`, `QueueRegressionTest`, `PlaybackToggleTest`, `PlaybackSnapshotTest`.

Emulator, real playback service, network off, synthetic entries: `PlaybackOrderDeviceTest`, and `ProcessDeathDeviceTest` in two steps (see its header for the commands).

Not verified here:

- Any of this on a physical phone, or with real provider streams. With the network off the status goes from Resolving to Recovering or Failed; Playing was observed only for on-device audio.
- Queue edits racing with radio additions over a real connection.
- Process death while a provider queue is playing; the kill test uses on-device audio.

## Known limits

- Queue edits are validated by entry in the app before the standard player command is sent. The command itself still carries an index, so a change made by the service in the few milliseconds between the two is not excluded.
- With shuffle on, removing every copy of a song (**Not interested**) leaves the choice of the next entry to the player.
