# Downloads: lifecycle and integrity (Q09)

How a song becomes a download, what survives a stop at any moment, and what Riff does when a download turns out to be unusable. Code: `DownloadState.kt` (states and failure messages), `NetworkStoragePolicy.kt` (space, budget, network and battery rules), `BundleValidator.kt` (the files), `OfflineMusicStore.kt` (rows, ownership, startup check), `OfflineDownloadEngine.kt` (the run), `OfflineDownloadService.kt` (foreground service, jobs, scheduling).

## What a download is

A folder for one song in one account, holding:

| File | Meaning |
|---|---|
| `audio.media` | The audio, rebuilt on the device into WebM or MP4 with a seek index |
| `ready` | Written last. Holds the length of `audio.media`. Builds before rc11 wrote `1`; that is still accepted |
| `loudness` | The provider's loudness value, when it gave one |
| `artwork`, `lyrics.json` | Optional. Their absence never makes the song unplayable |
| `*.part` | Belongs to a transfer that has not committed. Never played, removed at the next start |

A song is playable when `audio.media` exists and `ready` matches its length. The row's state alone never makes it playable, and nothing else is consulted: no provider request is made to play a finished download.

## States

| State | Stored as | Meaning |
|---|---|---|
| Metadata | `metadata` | Known to the app, not a download |
| Queued | `queued` | Wanted, waiting for a run |
| Resolving | `resolving` | A run holds the row and is asking for a stream |
| Transferring | `transferring` | The source is arriving in `audio.part` |
| Validating | `validating` | The source is being rebuilt and checked |
| Assets | `assets` | Audio is committed and playable; artwork and lyrics are being fetched |
| Complete | `complete` | Done |
| Paused | `paused` | Stopped by the user or by Smart Downloads being switched off |
| Failed | `failed` | Stopped with a reason shown to the user |
| Cancelled | `cancelled` | A removal has begun |

`downloading`, written by earlier builds, is read as Transferring. A string no build writes is not guessed at: the row is kept, shown as **Needs downloading again**, and repaired at the next start (complete if its files are sound, otherwise failed).

Each row carries a generation. A run takes the generation when it begins a song and every later write names it. Remove, pause, the startup check and marking a download damaged all raise it, so a run that has lost its song cannot write to the row or commit files for it, however late its last response arrives. The run notices at its next step, deletes its unfinished files and moves on to the next song.

## Order of a transfer

1. Resolve a stream for the song (highest quality).
2. Admit it: budget and device space, below.
3. Receive the source into `audio.part`, flushed to storage at the end. The length received must equal the length declared; a range that returns more or less than asked is refused.
4. Rebuild into `assembled.part` with Android's extractor and muxer, without re-encoding. The result must contain audio and run as long as the provider said, within 3 seconds or 5 percent.
5. Commit, under the store's lock and only if the run still holds the row: flush the rebuilt file, rename it to `audio.media`, write `loudness`, write `ready.part`, flush it, rename it to `ready`.
6. Mark the row Assets, then fetch artwork (must decode as an image, at most 8 MB) and lyrics, each to a `.part` file renamed into place. Each has its own state (`available`, `failed`, `unavailable`, `instrumental`).
7. Mark the row Complete.

A stop between any two steps leaves either no `ready` file, so the song is not a download yet, or a `ready` file that matches the audio. It never leaves a row marked Complete without playable audio written first.

A failure during steps 1 to 5 is tried again inside the same run at most twice more, each time with a newly resolved stream and with the profiles already refused excluded. Nothing is resumed from the bytes already received: a stream address is short-lived and tied to the request that produced it.

## At app start

`OfflineMusicStore` checks every row that is neither Metadata nor Complete before anything else uses it:

- Resolving, Transferring, Validating, Assets: unfinished files are deleted and the song is Queued again. Audio already committed with its marker is kept; the next run fetches only the artwork and lyrics that are missing.
- Cancelled: the removal is finished.
- Queued, Paused, Failed: unfinished files are deleted; the state stays.
- Unknown state: repaired as above.

No row is deleted. What was done is written to the event log as one `download_reconcile` line with counts only.

Complete rows are not opened at start. They are checked when the Downloads screen loads and at the start of every run: a Complete row whose audio is gone or no longer matches its marker becomes Failed with **This download is damaged or missing. Download it again.**

## Space and budget

Checked before a transfer, about every megabyte while the source arrives, and again before the rebuild.

- The budget set in Settings counts what the song will occupy when done: its audio plus 9 MB for artwork and lyrics.
- The device must also hold what exists only while the song is made: the source and the rebuilt copy side by side. That is twice the song's size plus 9 MB.
- A tenth of the device's storage, at least 1 GB and at most 2 GB, is always left free (capped since Q18-066: a tenth of a 128 GB phone held back 10 GB). **Unlimited** removes the budget, not this reserve.
- One song is never larger than 512 MB.

Changed in Q09: before, one copy of the song had to fit; now two must while it is being made. A download that used to start on a nearly full phone and fail during the rebuild is now refused at the start with **Not enough free space**.

## Failures

The row's message is one of a fixed set; nothing from a response, an address or an account is stored or logged.

| Shown | When |
|---|---|
| Not enough free space. Free space or raise the download budget, then restart. | Device full at any check, or a write failed |
| Download budget reached. Raise the budget or remove downloads, then restart. | Over the budget |
| YouTube Music refused the audio. Restart the download later. | HTTP refusal on every attempt |
| The audio did not arrive in full. Check the connection, then restart. | Wrong length, wrong range, connection lost |
| The audio received could not be used. Restart the download. | The rebuild found no audio or the wrong duration |
| No downloadable audio is available for this song. | No length, a live stream manifest, or over 512 MB |
| Could not save the download. Check storage, then restart. | The commit failed |
| This download is damaged or missing. Download it again. | A finished download failed a later check |
| This download was left in a state Riff does not know. Download it again. | Unknown state repaired at start |

A Failed song is tried again by later runs without being asked, at most three runs in all. After that it waits until the user chooses **Restart download**, which clears the count. Space, budget and no-audio failures are not tried again inside the run that met them.

## Labels

| State | Audio present | Label |
|---|---|---|
| Queued | no / yes | Waiting to download / Waiting to save artwork and lyrics |
| Resolving | no / yes | Preparing download / Saving artwork and lyrics |
| Transferring | | Downloading 42% |
| Validating | | Checking audio |
| Assets | | Saving artwork and lyrics |
| Paused | no / yes | Paused · starts again from the beginning / Paused |
| Failed | no / yes | Download failed / Artwork or lyrics missing |
| Complete | yes / no | Downloaded / Download missing |
| Unknown | | Needs downloading again |

The retry action is **Restart download** when there is no audio and **Retry missing assets** when there is. Neither is called "Resume": a transfer never continues from where it stopped.

## By hand and automatic

A download the user asked for runs on any connection and at any battery level. It is their request.

Smart Downloads run only while all of these hold, checked before the run, before each song and about once a second during a transfer:

- Smart Downloads is on;
- the connection is validated and, unless mobile data is allowed, unmetered;
- the battery is at 20 percent or more;
- nothing is playing.

When one stops holding mid-transfer, the song in hand goes back to Queued and its unfinished files are deleted. Switching Smart Downloads off pauses the songs it chose and leaves songs asked for by hand alone. Songs the user removed are excluded from its choices; songs the user pinned are never removed by it.

## Bulk requests, removal and accounts

- **Download all** keeps songs that are complete, running or paused as they are, queues the rest once each, and reports how many were added.
- Removing a song, or **Delete downloads**, marks the rows Cancelled, raises their generations, deletes the folders and returns the rows to Metadata. A transfer running for any of them stops at its next step. It is limited to the account it was asked for.
- A run belongs to the account that was active when it began. When the account changes, the run stops, the song in hand returns to Queued under its own account, and jobs are scheduled again for the new one.

## Scheduling

Three jobs, all through `OfflineDownloadScheduler`:

| Job | When | Conditions |
|---|---|---|
| Smart Downloads, periodic | About every 6 hours, best effort; Android decides | Unmetered (or any, when mobile data is allowed), battery not low, storage not low, persisted |
| Smart Downloads, now | When asked to run now | The same |
| By hand, later | 30 s after any request, with exponential backoff | Any network, storage not low, persisted |

Scheduling is idempotent. JobScheduler replaces, and stops, a job scheduled again under its id; before Q09 every new request, account event or settings change stopped the transfer in progress. Now a job that is already waiting or running with the same conditions is left alone, and only a changed condition replaces it.

A request by hand starts the foreground service (type `dataSync`) and always schedules the job behind it.

- Android 12 and later refuse a foreground service started from the background. The refusal is caught and logged (`download_service_refused`, `download_foreground_refused`); the songs stay Queued and the job downloads them.
- Android 14 requires the declared type and its permission; both are in the manifest.
- Android 15 limits a `dataSync` service to about six hours a day. `onTimeout` stops the run, returns the song in hand to Queued and leaves the rest to the job.
- A job is stopped by Android after about ten minutes. The run ends itself at eight, so the song in hand is put back cleanly.

A second start of the service or a job while a run is active changes nothing; the run looks for newly queued songs itself before it ends. Only one run exists at a time in the process.

**Pause** in the notification pauses every download asked for by hand in the active account and stops the service.

## When a finished download will not play

Two checks, cheapest first:

1. When a song is opened from its download, the marker is checked and the first bytes must be a WebM or MP4 container. If not, the download is never given to the decoder: `download_invalid reason=container` is logged, the row becomes Failed, the marker is withdrawn, and the song streams instead (or waits for a connection).
2. If the player then fails on the download, the failure is a download failure whether the song was opened from the Downloads screen or from any queue: **Download incomplete or unavailable** with **Redownload** and **Remove**. It is not retried by itself and the provider is not asked. If the files do not pass the check, the container could not be parsed, the file was not found or it ended early, the download is also marked Failed so it is not opened again.

Before Q09 the second case, for a song queued from the catalog, was treated as a provider failure and re-opened the same file up to the recovery budget.

## Events

`download_reconcile`, `download_integrity damaged=N`, `download_retry phase=… attempt=N failure=…`, `download_failed phase=… kind=… failure=…`, `download_stopped reason=… phase=…`, `download_invalid reason=…`, `download_service_refused`, `download_foreground_refused`, `download_foreground_timeout`. Phases are `resolve`, `transfer`, `validate`, `commit`, `assets`. No song identifier, address, header or account value appears in any of them.

## Tests

JVM and Robolectric, no network, synthetic songs and accounts:

- `DownloadPolicyTest` (17): state decoding including unknown strings, labels, failure messages, every space and budget rule, the automatic conditions, the validator, the commit order, sweep and revoke.
- `DownloadReconcileTest` (14): a stop in each phase, committed audio kept, an interrupted removal, an unknown state, the upgrade of the older table, row ownership by generation, the failure count, pause scopes, bulk requests, removal limited to one account.
- `DownloadEngineTest` (36): the whole path with every outside part scripted. A stop of the app (an `Error` thrown from a scripted part, so no handler in the engine runs) at six points, each followed by a restart; 403 mid-transfer, always refused, wrong range, no length, wrong length, a manifest, corrupt audio, resolver failure; device full before the transfer, during it, before the rebuild, during the rebuild and at the commit; artwork and lyrics failing independently; delete-all while artwork is being fetched; pause; remove and ask again; songs queued during a run; duplicates in a bulk request; Smart Downloads selection, exclusions and pins; the network changing to mobile mid-transfer; battery and playback; Smart Downloads switched off; requests by hand ignoring those conditions; a finished download found damaged and fetched again.
- `DownloadSchedulingTest` (5): repeated scheduling, a changed condition, a refused foreground service, the notification's pause.
- `DownloadControlsTest`: clearing the playback cache leaves a finished download sound.

Emulator (API 31, network off), `DownloadLifecycleDeviceTest` (6): the engine with Android's real extractor and muxer on AAC audio encoded on the device from a generated tone, then the real playback service. A rebuilt download plays, seeks far forward and back, moves to the next download and plays again from an empty player with no stream lookup, no recovery and no further request; truncated audio and audio shorter than declared are refused and leave no files; artwork that is not an image leaves a song that plays; a finished download replaced by something that is not audio is refused once, streams instead, and plays again after being downloaded again; a download that begins correctly but is cut short fails as a download with Redownload and Remove and is not retried.

## Not covered

- A real process kill on a device during a transfer. The JVM tests stop the engine without running its handlers, which is the same state on disk, but the device's own file-system behaviour at power loss is not exercised.
- The SABR transfer path. Its contiguity checks are in the engine; no fixture produces a SABR stream.
- Foreground-service refusal, the Android 15 time limit and job expiry on real Android 14 and 15. The refusal is tested with a scripted exception under Robolectric; the emulator is API 31.
- WebM/Opus through the real muxer. The emulator test uses AAC in MP4; WebM seeking is covered by the existing `WebmSeekIndex` tests.
- A real provider. Nothing here downloads from YouTube Music.

## Known limits

- A song too large to finish inside one job (about eight minutes on the connection in use) starts again from the beginning in the next job. It finishes when the app is open or the foreground service is allowed. There is no resume.
- The periodic Smart Downloads job is best effort. Android may run it much later than six hours.
- The container check reads twelve bytes. Damage further in is found only when the player reads it.
