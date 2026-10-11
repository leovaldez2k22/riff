# Crossfade, levels, focus and the solver (Q08)

Code: `Crossfade.kt` (`CrossfadeOwner`, `CrossfadeEnvelope`, `Crossfader`), `SleepTimerController.kt`, `VolumeBoostProcessor.kt`, `AudioSessionEffects.kt`, `RemoteCipherClient.kt`, `CipherBridgeInstaller.java`.

## What a blend is

When a song ends on its own and crossfade is on, a second, short-lived player picks up the end of that song at the same position. The main player moves to the next song and the two levels cross over. The main player, its session, queue and notification are never paused or rebuilt. The design and the curve are unchanged by Q08.

A blend is started only for an automatic change to the next song, and not when any of these holds: there is no next song; repeat-one is on; the sleep timer is set; either song is live, a video or not music; the song is shorter than two fades plus ten seconds. These are asked again at every step up to the moment the levels change hands, so turning repeat-one or the sleep timer on late still prevents the blend. (Before Q08, repeat-one was not asked again once preparation had begun.)

## One owner of the level

`CrossfadeOwner` is the only thing that decides the player's level during a blend. It holds:

| Held | Meaning |
|---|---|
| Outgoing and incoming entry | The media IDs of the two songs |
| Progress | 0 to 1, never decreasing |
| Length | The overlap chosen at the start: the setting, or less if the song has less left (never under 0.5 s) |
| Level to put back | The player's level read at the moment the blend takes it |

Rules:

- One blend at a time. A second one is refused while the first holds the level.
- The level is handed back exactly once. Whoever ends the blend first (the user's action, or the blend's own cleanup) gets it; the other gets nothing and writes nothing.
- A blend that never reached the handoff holds nothing and puts nothing back.
- After a blend has ended it writes no further levels.
- The copy of the outgoing song and its audio effects are released on every path: completion, cancellation, an error, and a copy that could not be built (that is a skipped blend, logged, not a failure of playback). The service logs `crossfade_tail_created` and `crossfade_tail_released`; the two counts match.

## The curve

Equal power: outgoing = cos(progress x 90 degrees), incoming = sin(progress x 90 degrees), each multiplied by the level the player had. While both songs play, the squares of the two gains add up to one (tested to within 0.03). Both gains are clamped to 0..1.

Exception, early end: if the copy of the outgoing song stops playing during the blend (it ended, buffered or failed), it is silent for the rest of that blend and never comes back. The incoming song keeps its own curve and does not jump to full level. From that moment the two no longer add up to constant power.

Equal power keeps one song about as loud as the blend of two. It does not make a quiet recording as loud as a loud one. Songs mastered at different levels still sound different after the blend.

## Time

- Progress follows a clock, not decoder positions, and advances only while the incoming song is playing. A late start or buffering holds both levels where they are; the blend then takes longer on the wall clock and is never shortened.
- One update moves the ramp by at most 50 ms of the blend, however late it arrives. A clock that goes back changes nothing.
- On a device that is not buffering, a blend runs for its length within the larger of 150 ms and 5 percent. If updates arrive more than 50 ms apart for a stretch, the blend runs longer by the time lost; it does not run shorter.
- If the incoming song does not play for 15 s, the blend ends.

## What ends a blend early

Pause; a seek; selecting or skipping to another song; turning crossfade off; setting the sleep timer; an account switch that stops the queue; losing audio focus; a playback error; the service stopping. In each case the copy is muted at once, the level goes back, the outgoing song disappears from the screen, and the next song continues alone (or stays paused).

## The other things that change loudness

| Who | What it changes | When |
|---|---|---|
| Crossfade | The player's level | During a blend |
| Sleep timer | The player's level | The last 15 s before it pauses |
| Volume booster | An effect after the player (Android's LoudnessEnhancer, or Riff's own PCM stage if that is unavailable) | Whenever it is on |
| Android and Media3 | Ducking and pausing for other apps | Audio focus changes |

Crossfade and the sleep timer never run together: a blend does not start while the timer is set, and setting the timer ends a running blend first, so the timer starts its fade from the level the blend handed back and restores that level after it pauses. The booster does not touch the player's level, so it cannot conflict with either. During a blend each player has its own booster stage with the same setting.

## Volume booster and clipping

Off is 100 percent (unchanged audio, bit for bit in Riff's own stage). The default when turned on is 200 percent, the maximum 300 percent (about +9.5 dB).

- With Android's LoudnessEnhancer, limiting is done by the platform effect.
- In Riff's own stage, a gain change ramps over 30 ms. Samples above 80 percent of full scale are compressed by a soft knee so that nothing exceeds 16-bit range or wraps. Loud passages are therefore flattened, and at 200 percent a full-scale peak comes out slightly below full scale. This is limiting, not clean amplification.
- In the middle of a blend two songs play at 0.71 each. If both are at full scale at the same instant their sum is 1.41 times full scale at the device's mixer. Riff does not limit that sum; what happens is up to the device's output stage. In practice it needs two songs peaking at the same moment.

## Normalize volume (Q08A)

**Settings -> Normalize volume**, off by default. The row says what it does: "Turns down louder songs from YouTube Music when it supplies loudness data".

Before Q08A Riff had no normalization of any kind: every song played at the level it was mastered at.

Where the value comes from:

- YouTube Music's player response carries, per track, how many decibels the recording is above the provider's reference loudness (`playerConfig.audioConfig.loudnessDb`). innertubex 0.7.2 exposes it on the resolved stream as `ExtractedStream.loudnessDb`. Riff reads it there and nowhere else.
- No audio is analysed on the phone, and no file tags are read. Audio from the phone's own library has no value and is never changed.
- A download stores the value in its own folder, in a file named `loudness` next to the audio, before the download is marked complete. It holds one number and nothing else. A downloaded song therefore normalizes offline without asking the provider.
- A download made before Q08A has no such file. It plays unchanged, and nothing is added to it afterwards. Downloading it again stores the value.

The rule (`normalizationGain`):

| Value | Gain |
|---|---|
| Setting off | Unchanged |
| Missing, not a number, not finite, or outside -60..60 dB | Unchanged |
| At or below the reference (0 or negative) | Unchanged. Riff never turns a song up |
| Above the reference by x dB | Turned down by x dB |
| Above the reference by more than 12 dB | Turned down by 12 dB, no further |

Where it is applied:

- In the same PCM stage as the volume booster (`PcmVolumeBoost`), as a second factor: booster gain (1 to 3) times the track's gain (0.25 to 1). It is the only place samples are scaled.
- Below 1 the product is plain multiplication; nothing is limited, because turning down cannot leave the 16-bit range. Above 1 (booster on) the booster's soft knee limits the product exactly as it limits the booster alone.
- The player's level is not touched. It still belongs to the blend's owner, and after a blend to the sleep timer. Normalization cannot fight either of them because it never writes that level.
- Each player has its own stage. In a blend the outgoing copy keeps the outgoing song's gain to its end and the main player takes the incoming song's gain. The event log shows both (`normalize_outgoing gain_db=...`, `normalize gain_db=...`).
- A track starts at its gain. Switching the setting, or the booster, in the middle of a song ramps over 30 ms.
- With LoudnessEnhancer in use for the booster, the booster's part is done by the platform effect and the track's gain is still applied in Riff's stage.

Known limits:

- On a gapless change to the next song without crossfade, the new gain is set when the player reports the change. Audio already handed to the device's buffer by then was scaled with the previous song's gain, so the first fraction of a second of the new song can be at the old gain before the 30 ms ramp. With crossfade on, the main player is at the bottom of its fade at that moment.
- The value is the provider's, measured its way. Riff does not check it against the audio. Whether two songs then sound equally loud has not been heard on a device.
- The provider field has not been read from a live response in this work (the emulator has no network). `LoudnessNormalizationTest` decodes a hand-written response with the pinned library's own model; it proves the field name the library reads, not what the provider sends today.
- Values are remembered per queue entry while the queue lives (at most 2048 entries). A song whose stream has not been opened yet has no value until it is.

## Focus, calls and routes

Riff uses Media3's handling and adds nothing to it.

| Event | Media3 does | Blend |
|---|---|---|
| Another app takes focus for good | Pauses | Ends; level back |
| Short loss (call, spoken direction) | Holds playback, keeps the wish to play, resumes after | Ends; level back; the next song resumes alone |
| Short loss that allows ducking | Lowers the main player | Continues. The copy has no focus handling of its own |
| Headphones unplugged, Bluetooth gone | Pauses | Ends; level back |

Known limit: during a blend, ducking by Media3 applies to the main player only. Whether Android also lowers the copy depends on the system's own automatic ducking. This has not been heard on a device.

## Solver

Stream challenges are solved in the separate `:solver` process, never in the process that plays audio.

- innertubex 0.7.2 has no public way to supply a cipher. Riff still replaces the extractor's `cipherService` field by reflection. Reflection has not been removed.
- If the replacement fails, online stream resolution is refused with `SolverUnavailable` ("Online playback resolver unavailable", Retry). Nothing is fetched and nothing is solved in the main process. Before Q08 the in-process cipher was used silently in that case. Downloads, files on the phone and audio already playing are unaffected.
- If the solver dies during a request, the request is retried once on a fresh solver. After three deaths within ten minutes the solver is not started again for ten minutes and requests fail at once with the same typed failure.
- The contract of the pinned library is checked by `CipherBridgeTest` and `SolverFailClosedTest` on every build. For a shrunk release, `python scripts/quality/check_solver_seam.py --mapping app/build/outputs/mapping/release/mapping.txt` checks that the field kept its name and type, that the bridge code is present and that the service kept its name.

## Tests

| Where | What |
|---|---|
| `CrossfadeOwnerTest` (JVM) | 1, 6 and 12 s; short overlap; late start; buffering; early end; late updates; exactly-once restore; one owner; sleep timer after a blend; repeat-one, sleep and short songs |
| `CrossfadeAndSeekTest` (JVM) | Alignment, envelope and eligibility (from before Q08) |
| `VolumeBoostTest` (JVM) | Generated full-scale and quiet tones at off, 200 and 300 percent: finite, in range, sign kept, limiter monotonic |
| `LoudnessNormalizationTest` (JVM) | The rule and its clamp; missing and invalid values; the stored value, older and damaged bundles; generated tones at different levels with scripted loudness; toggle mid-track without a step; every combination with the booster finite and in range; two players in a blend; the setting defaults to off |
| `SongPresentationTest` (JVM, Compose) | Screens follow the live blend; reduced motion swaps at once |
| `SolverFailClosedTest`, `CipherBridgeTest`, `SolverProtocolTest` (JVM) | Fail closed; bounded restarts; library contract |
| `CrossfadeFixtureDeviceTest` (emulator, real service) | 1, 6 and 12 s; boost at maximum; pause; seek; select; off; sleep timer; repeat-one; focus loss; short interruption; account switch |
| `NormalizationDeviceTest` (emulator, real service) | Downloads built from tones with a scripted value: applied; toggled mid-track; clamped at 12 dB; own gain per song; with the booster at maximum; no value; damaged value; setting off; on-device audio; a blend between two differently normalized songs |
| `CrossfadeDeviceTest` (emulator, two real players) | Fourteen scenarios including buffering before the blend and an outgoing copy that ends or restarts |

Not covered without a phone: what a blend sounds like; ducking; a real call; unplugging headphones; Bluetooth; streamed songs; the solver against the provider; the provider's loudness value on a live stream and how normalized songs sound.
