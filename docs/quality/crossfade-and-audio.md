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
| `SongPresentationTest` (JVM, Compose) | Screens follow the live blend; reduced motion swaps at once |
| `SolverFailClosedTest`, `CipherBridgeTest`, `SolverProtocolTest` (JVM) | Fail closed; bounded restarts; library contract |
| `CrossfadeFixtureDeviceTest` (emulator, real service) | 1, 6 and 12 s; boost at maximum; pause; seek; select; off; sleep timer; repeat-one; focus loss; short interruption; account switch |
| `CrossfadeDeviceTest` (emulator, two real players) | Fourteen scenarios including buffering before the blend and an outgoing copy that ends or restarts |

Not covered without a phone: what a blend sounds like; ducking; a real call; unplugging headphones; Bluetooth; streamed songs; the solver against the provider.
