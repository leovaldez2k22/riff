# Playback recovery (Q07)

One coordinator decides every automatic retry of playback. It replaces three paths that ran side by side: stream recovery (one attestation refresh, one stream refresh, three excluded profiles), live fallback (three profiles) and network recovery (five attempts on timers of 5, 20 and 60 seconds). A failure could start more than one of them.

Code: `PlaybackRecovery.kt` (`classifyFault`, `RecoveryPolicy`, `RecoveryEpisode`, `RecoveryCoordinator`). The service supplies the player and the device through `RecoveryHost`.

## Classes

Every playback failure is sorted into one class. The first rule that matches wins.

| Order | Class | Signal | What happens |
|---|---|---|---|
| 1 | LocalFileMissing | The entry plays from a file on this device and it could not be read | Stops. Never retried |
| 2 | SessionExpired | HTTP 401, or the provider's session-expired answer | Stops. **Sign in** |
| 3 | UnsupportedMedia | The provider says the item is unavailable, age-restricted or has no playable stream; or the decoder rejects it | Stops. Never retried |
| 4 | Offline | No validated connection | Waits for one |
| 5 | RateLimited | HTTP 429 | Waits as long as the provider asks, up to 60 s, then retries |
| 6 | AttestationExpired | SABR asks for attestation | Refreshes the attestation once, then excludes the profile |
| 7 | SolverUnavailable | The solver process died, refused or could not start | Retries |
| 8 | Timeout | A network error while a validated connection is present | Retries the same source |
| 9 | SessionExpired | The session is known to be expired and the provider refused the request | Stops. **Sign in** |
| 10 | RejectedStream | Any other SABR failure, or HTTP 4xx | Refreshes the stream once on 403 or 410, then excludes the rejected profile |
| 11 | ProviderUnavailable | Anything else: HTTP 5xx, an unexpected answer | Looks the stream up again |

A provider song played from its downloaded files is its own source: any failure to read them is a storage failure ("Download incomplete or unavailable"), not a provider failure. This holds however the song was queued: an entry queued from the catalog and opened from its download is a download for as long as it is served from those files (Q09; see [downloads](downloads.md)). A file from the phone's library that the decoder rejects is UnsupportedMedia.

Without a validated connection, nothing the provider appears to have said is trusted: a 403 from a captive portal is Offline.

## Limits

All retries for one entry draw on one budget, whatever the cause.

- At most 3 automatic attempts per entry per episode.
- The first attempt is immediate, the second after 1 s, the third after 3 s. Each delay gets up to 250 ms of jitter.
- An episode may spend 60 s trying while online. This covers the delays, each lookup, and the time the player takes to answer. A lookup still running at the limit is cancelled. A player that never answers ends the episode at the limit.
- Waiting for a connection is not counted and has no limit. It ends when a validated connection has stayed for 500 ms. A connection that drops inside that half second starts the wait again.
- A delay the provider asked for (`Retry-After`) is not counted either. Longer than 60 s, the episode stops and the user decides when to retry.
- An episode ends when the entry has played for 10 s after recovering. A failure before that continues the same episode with the same budget, so a stream that becomes ready and is rejected again cannot loop.
- A recommendation queue passes over at most 3 unavailable entries in a row. The count resets when an entry has played for 10 s, or when the user retries. Entries the user queued (album, playlist, a chosen song, a restored queue) are never passed over: playback stops there with **Retry** and **Skip**.

Time is the monotonic clock injected through `RuntimeDependencies`. No wall-clock time is used.

## Intent

- A pause holds recovery, whether it comes from the app, the notification, a headset, a widget, the sleep timer or loss of audio focus. Nothing resumes afterwards by itself.
- Play after a hold or a stop starts a new episode with a full budget.
- Selecting another entry, replacing the queue or emptying it ends recovery for the entry left behind. A lookup that finishes later is dropped.
- A failure while paused is held: nothing is retried and nothing is shown as failed, except the classes that are never retried.
- Signing in again starts nothing. The user presses play.
- The position is kept. A retry prepares the same source or replaces the stream at the current position. A live stream restarts at its live edge.
- A failure reported while an episode is already waiting or trying is the same failure seen twice and starts nothing.

## What the user sees

| State | Text | Actions |
|---|---|---|
| Waiting for a connection | Connection lost; waiting to reconnect | Pause ends the wait |
| Retries exhausted, item unavailable, or `Retry-After` over 60 s | Could not play this item | Retry, Skip |
| Session expired | Sign in again to access account content | Sign in |
| File from the phone's library missing | File unavailable on this device | Skip |
| Downloaded files unreadable | Download incomplete or unavailable | Redownload, Remove |
| Solver unavailable after retries | Online playback resolver unavailable | Retry |

While the app retries online, the status is Recovering and no text is shown. The texts are fixed; no exception or provider message is displayed. The earlier texts ("YouTube Music could not start this track…", "YouTube could not start this live stream…", "Playback failed (code)") are gone.

**Retry** asks the service to try the selected entry again. The queue, the entry and the position are kept. Before Q07 it replaced the queue with that one song.

**Redownload** deletes the unusable files and queues the download again; **Remove** deletes them. In both cases the entry is then looked up again and streams when a connection is present.

Play in the notification, on a headset or on a widget now retries a failed entry. Before, it did nothing until the app was opened.

## Published state

The service publishes three session extras next to those of Q06: `recovery` (`stream`, `live`, `network` or empty), `failure` (the name of a `PlaybackFailureKind` or empty) and `recoveryHeld`. The app no longer derives a failure from the player's own error; it would arrive before the decision whether to retry.

## Events

```
recovery class=<class> attempt=<n> action=<prepare|refresh|attestation|exclude> delay_ms=<n> hold_ms=<n> active_ms=<n>
recovery_wait_online class=<class>
recovery_done attempts=<n> active_ms=<n>
recovery_stop class=<class> attempts=<n> active_ms=<n>
recovery_skip count=<n>
recovery_held
recovery_user_retry
recovery_cancelled reason=entry
recovery_obsolete
```

They carry the class, the attempt and timing. They never carry exception text, a profile, an address, an entry or a video identifier. The older `player_error=` line still lists the class names of the cause chain, not their messages.

## Verification

JVM, virtual time (`PlaybackRecoveryTest`, 36 tests): every class and the order of the rules; the budget; delays and jitter bounds; `Retry-After`; exhaustion; a failure seen twice; offline wait, flapping, an unreported connection change, a connection lost during a delay; pause while waiting, during a delay, and unreported; failure while paused; skip and switch during a retry; a late completion for an entry the user left; a missing download; expired session; the skip limit and its reset; an authored collection; a recovery that does not last; a player that never answers; a lookup that hangs; events.

JVM, Compose (`ComponentUiTest`, 3 tests): each failure shows its own actions with 48 dp targets and reports the one chosen; waiting to reconnect is a notice without actions.

Emulator, real service, network off (`RecoveryDeviceTest`): a provider entry waits to reconnect with the wish to play kept, uses no attempt while waiting, is held by a pause, waits again after play, and is left behind by a skip; a library file deleted under a queued entry stops as a storage failure and is not retried.

Not verified here:

- Wi-Fi to mobile handover and a 60 s drop on a physical phone (PLY03, PLY04). Needs a phone.
- A rejected or expired stream, an attestation failure and solver death against the real provider (PLY05, PLY06). Needs a connection to the provider, which these runs do not use.
- `Retry-After` from a real server.
- The Redownload and Remove actions, and the Sign in action, end to end. The row that offers them is tested; what they do in the app is not.

## Known limits

- The service reads connectivity from the default network. A VPN or a captive portal that reports itself validated is treated as online.
- Entries added by recommendations are remembered only while the service runs. After a restart a restored queue is treated as queued by the user.
- A provider failure that carries no status and no known message is ProviderUnavailable and is retried three times, even if the item will never play.
- An age-restricted item stops as "Could not play this item". It does not offer Sign in.
