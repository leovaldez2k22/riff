# Performance and resources (Q14)

What is measured, with what, on which build, and what has and has not been measured so far.

**Status on 24 October 2026 (0.10.0, measured on rc10).** Tier R is measured on the owner's phone (vivo I2217,
Android 16, 7.2 GiB) with the benchmark lane on rc10's code (8adb2d1); rc11 and 0.10.0 change only colours (the
artwork tone and the collection title block), which no budget measures. Evidence:
`..\Riff-rc10rtifacts\quality\lanesenchmark-20261024T102331Z` (local, not committed).

| Budget (Tier R) | Measured | Budget | Verdict |
| --- | --- | --- | --- |
| Cold start to a usable shell, p95 | 1,788 ms (1,870 ms without the profile; first frame 459 ms) | 2,000 ms (owner, 21 Oct) | PASS |
| 2,000-entry queue, p95 | 369 ms (main thread 205 ms) | 2,000 ms | PASS |
| Local bundle first audio, p95 | 294 ms | 500 ms | PASS |
| Controlled online first audio, p95 | 263 ms | 3,000 ms | PASS |
| Six action timings (player, queue, toggle, skip) | not measured: the harness lost a tap (Q18-097) | 150 ms | MISSING |
| Missed-deadline frames at 60 Hz: Home, a collection, player and queue | 8.0 %, 7.0 %, 17.1 % | 2 % | FAIL (Q18-030, left as is by the owner) |
| Memory over 10 journeys | +5.4 MB of 22.1 MB allowed; three blocks of 10: 210.0, 210.0, 218.2, 221.8 MB | 10 % or 20 MiB | PASS (Q18-073) |

Earlier phone runs: frosted glass at 20 % cost Home scroll 1.67 % janky frames against 1.33 % with it off (rc6,
by hand, B6); battery three 2-hour runs on 23 October, about 3 % an hour streaming or from downloads and 2.5 %
idle (recorded as measured, no budget: vivo's counter is not mAh and its stats cannot be reset); soaks: 2 hours
offline (19 Oct) PASS with no memory growth, 4 hours live (23 Oct, B9) PASS on stability, accepted by the owner
with the single-reading memory rule's FAIL noted (medians of the first and last hour both about 334 MB). Not
measured: Tier L (no such phone), frames at 120 Hz as a verdict, and the API matrix beyond the I2217 and the
emulators. The sections below are the Q14 to Q17 record and are kept as written.

**Status on 15 October 2026 (Q17, 0.10.0-rc1): unchanged since Q14.** Still no budget measured on a phone;
no phone has been available. The release APK grew from 26.89 MiB (Q14) to 26.95 MiB (28,263,130 bytes, Q16B)
and stays under the 40 MiB ceiling. Q16A and Q16B added per-letter lyric drawing; its frame cost is one of the
unmeasured budgets (the player and lyrics journey on Tier R). The cold-start finding below still awaits a
product decision.

**State on 12 October 2026: no budget has been measured on a phone.** Every timing, frame, battery and soak
gate below is BLOCKED until the benchmarks are run on the two physical tiers. What exists is the tooling, a
shipped Baseline Profile, the checks that do not depend on a phone's speed (APK size, profile packaging,
main-thread disk and network), and one defect the journeys found and this package fixed. Nothing in this file
is a claim that Riff is fast.

## Tooling

| Piece | Version | Where |
| --- | --- | --- |
| Jetpack Macrobenchmark (`androidx.benchmark:benchmark-macro-junit4`) | 1.5.0 | `:benchmark` |
| Baseline Profile Gradle plugin (`androidx.baselineprofile`) | 1.5.0 | `:app`, `:benchmark` |
| UI Automator (comes with Macrobenchmark) | 2.4.0 | `:benchmark` |
| `androidx.profileinstaller` | 1.4.1 | `:app` (shipped) |

Checked against developer.android.com on 12 October 2026: 1.5.0 is the current stable release of the benchmark
libraries and the plugin; the plugin asks for Android Gradle Plugin 8.0 or newer (the project uses 8.13.2);
profile generation needs a rooted emulator or device below API 33, or any device from API 33.

`:benchmark` is a `com.android.test` module. It holds tests only and nothing from it is in any app build. It
is the one exception to ADR-Q01 (single `:app` module), and it is the plan's own requirement.

## The build that is measured

The plugin derives two build types from `release`:

- `benchmarkRelease`: shrunk and obfuscated like release, not debuggable, profileable. This one is measured.
- `nonMinifiedRelease`: not shrunk. The Baseline Profile is collected from it, and the main-thread audit uses
  it because its stack traces can be read.

Both differ from release in three ways, all set in `app/build.gradle.kts`:

1. The package id is `com.musicglass.personal.benchmark`. Neither can install over Riff or see its data.
2. They include `app/src/benchmarkFeed`: a fixture feed. Provider requests are answered from the invented
   pages of `app/src/test/resources/quality/provider`; every song is one generated 30 second tone and every
   artwork a generated picture, both served from the app's own process on 127.0.0.1; the session is synthetic.
   A benchmark therefore measures the app and never a network or an account.
3. Their manifest allows cleartext to 127.0.0.1 only, and declares the feed's provider, which only the shell
   can call.

`scripts/quality/check_performance.py apk` fails a release APK that contains any part of the feed.

Two things still reach for the network in these builds: the media notification asks `i.ytimg.com` for a cover
by video id, and a Home mood photograph may be asked for. **Run the benchmarks in aeroplane mode**, so that no
request for an invented id leaves the phone and no real latency enters a number. The loopback server works in
aeroplane mode.

### Feed switches

```
adb shell content call --uri content://com.musicglass.personal.benchmark.benchmark-feed --method <m> --arg <v>
```

| Method | Value | Read |
| --- | --- | --- |
| `strict` | `on` / `off`: the platform thread policy (disk and network on the main thread are logged) | at process start |
| `latency` | milliseconds before each audio answer | at process start |
| `rate` | audio bytes per second, 0 for no limit | at process start |
| `tracks` | rows in every playlist page, up to 2,000; 0 for the fixture as it is | at each request |

The benchmarks set these themselves.

## What the app marks

`PerfTrace` (in `:app`) writes sections to the system trace. When nothing is tracing, each call is one check.

| Section | From | To |
| --- | --- | --- |
| `riff:player_open`, `riff:player_close`, `riff:queue_open`, `riff:queue_close` | the handler of the tap | the first frame drawn after it |
| `riff:play_toggle` | the transport request | the frame that shows the new play state reported by the player |
| `riff:skip` | the request for the next entry | the frame that shows the new entry |
| `riff:queue_prepare` | a request that replaces the queue | the playback service's answer |
| `riff:queue_io_encode`, `riff:queue_io_decode` | writing, and reading back, the queue file (off the main thread) | |
| `riff:queue_main` | the service building and setting the queue (main thread) | |
| `riff:first_audio` | a request that replaces the queue and plays | the frame after the player reports that it is playing |

A section opens in the handler, so the time the system took to deliver the touch is not in it.
`first_audio` ends when the player reports playing and the screen shows it; it is slightly later than the
first sample leaving the speaker's buffer and never earlier.

The start is complete (`reportFullyDrawn`, Macrobenchmark's `timeToFullDisplayMs`) when Home shows its shelves
**and the startup logo has gone**. That is the "first usable shell".

## Budgets and how each is measured

Tier R: an iQOO I2217-class ARM64 phone. Tier L: a physical phone with 4 GB of RAM or less. An emulator is not
a tier.

| Budget | R | L | Protocol | Measured by |
| --- | --- | --- | --- | --- |
| Cold start to first usable shell, p95 | 2.0 s | 3.0 s | 20 cold launches, fixture feed | `StartupBenchmark.coldStartWithBaselineProfile`, `timeToFullDisplayMs` |
| Player or queue action to responsive visible state, p95 | 150 ms | 250 ms | 30 of each action | `ActionBenchmark.playerAndQueueActions`, six sections |
| Missed-deadline frames at 60 Hz | 2% | 5% | 5 warmed loops | `FrameBenchmark` (`homeScroll`, `collectionScroll`, `playerAndQueue`), `missedDeadlinePercent` |
| Missed-deadline frames at a high refresh rate | 3% | 3% | 5 warmed loops | the same, on a display running above 60 Hz |
| 2,000 entry queue prepared, p95 | 2 s | 4 s | 10 runs, IO and main phases apart | `ActionBenchmark.queueOfTwoThousand` |
| Complete local bundle to first audio, p95 | 500 ms | 800 ms | 20 starts | `ActionBenchmark.firstAudioLocalBundle` |
| Controlled online first audio, p95 | 3 s | 5 s | 20 starts, 100 ms latency, 5 Mbit/s | `ActionBenchmark.firstAudioOnline` |
| Standard release APK | 40 MiB | 40 MiB | every release build | `check_performance.py apk` |
| App-owned network on the main thread | none | none | warmed journey | `MainThreadAudit` |
| Avoidable disk access on the main thread, warmed journey | none | none | warmed journey | `MainThreadAudit` |
| Retained memory growth | max(10%, 20 MiB) | same | short run here; the soaks are the real test | `MemoryCheck`, and the soaks |

`missedDeadlinePercent` is the share of the app's frames that the platform's frame timeline records as
finished after their deadline. `FrameTimingMetric` is reported beside it; its percentiles of overrun cannot
give a percentage of frames.

Every benchmark and check starts from a cleared app that has been opened once (`cleanSlate`), so two runs are
comparable. `check_performance.py summary` turns the result files into a table with n, p50, p95, budget and a
verdict. It gives PASS or FAIL only for a physical device with at least the protocol's number of samples;
an emulator's rows read `NOT EVIDENCE` and too few samples read `INSUFFICIENT`.

The startup benchmark and `homeScroll` are also run with nothing compiled
(`coldStartWithoutProfile`, `homeScrollWithoutProfile`); the difference is what the Baseline Profile is worth.

Live-provider startup is not a budget and is not measured here. If it is ever reported, it is reported apart
from these numbers, with the network it was taken on.

## The Baseline Profile

`app/src/release/generated/baselineProfiles/baseline-prof.txt`, 31,341 rules, committed as source. It was
collected by `BaselineProfileGenerator` (`BaselineProfileRule`) over: cold start to the usable shell, a Home
scroll, the invented playlist opening, playing and scrolling, the mini player expanding to the full player,
the queue opening and scrolling, and both closing. Fixture content only.

Collected on 12 October 2026 from `nonMinifiedRelease` on the disposable API 31 emulator (rooted `adbd`, which
API 31 needs). Where it was collected does not matter to a profile in the way it matters to a timing: a
profile lists the classes and methods the journeys used.

How it was collected this time, and why not by the Gradle task: `:app:generateReleaseBaselineProfile` drives
the device through AGP's test runner, whose artifacts (`com.android.tools.utp`, 31.13.2) are not in this
project's offline Gradle cache. The generator was therefore started with `am instrument`, the file pulled with
`adb pull`, and written into place by `check_performance.py adopt`, which drops the rules for the feed's own
classes exactly as the plugin's `filter` in `app/build.gradle.kts` would. The collection is the official rule;
only the orchestration differs. With network access the one command does all of it:

```
adb root    # below API 33 only
gradlew :app:generateReleaseBaselineProfile -PriffBenchmarkOnEmulator   # drop the flag on a phone
```

Verified on the release build of this package: the APK holds `assets/dexopt/baseline.prof` and
`baseline.profm`, is 26.89 MiB, and holds no part of the feed (`check_performance.py apk`, in the Q14 evidence).

Not verified: what the profile is worth. That is the with/without comparison above and it needs a device whose
tracing works. Regenerate the profile after a change to the shell, the player, the queue or a collection
screen; `check_performance.py apk --profile` fails if the committed profile loses the journey classes.

## What was run, and where

The disposable emulator (API 31, `sdk_gphone64_x86_64`, network off, synthetic identity only) is the only
device this package has run on. Installing on a phone was not authorized.

| Check | Result on the emulator | Counts as |
| --- | --- | --- |
| `JourneyCheck` (6 journeys: every step the benchmarks take, untimed) | 6 of 6 pass | the journeys work on API 31 |
| `MainThreadAudit` | 0 violations in the warmed pass; none of any kind is a network violation | evidence for API 31: behaviour, not speed |
| `MemoryCheck` | noisy, see below | not a verdict |
| `BaselineProfileGenerator` | passes, 31,406 rules collected | the shipped profile |
| `StartupBenchmark`, `FrameBenchmark`, `ActionBenchmark` | **cannot run**: the image's system tracer (`traced_probes`) dies with SIGILL when tracing starts, so Macrobenchmark stops with "did not detect tracing on" | nothing |

Because no trace could be recorded, the two custom metrics (`SectionMetric`, `MissedDeadlineMetric`) and the
`summary` parser have been compiled and unit-tested but **never run against a real trace or a real result
file**. The first phone run is also their first real test; if a metric comes back empty, look there first.

### Main-thread audit, in full

The policy was on (it logged 24 disk reads during cold start and the first pass, all of them reads of
preference files while objects are built: `ProviderSessionManager`, `MoodPhotos`, `LyricsRepository`,
`AppPlayerConfigRepository`, `AudioTransitionCoordinator`, `removeRetiredContent`, `StartupLogo`). After every
screen had been visited once, the second pass through Home, the collection, the player, the queue, play, pause
and skip logged none. The cold-start reads are not in the "warmed journey" budget; they are a cost of the
start and belong to the cold-start number.

### Memory, in full

PSS over blocks of ten journeys, 200-entry queue, emulator:

- Read in the foreground: 107.8 to 132.2 MB over the first ten (24.3 MB, over the 20 MiB allowance); in a
  second run 117.9, 126.9, 139.3, 137.5, 130.1 MB over forty.
- Read after ten seconds in the background: 114.6, 122.3, 143.3, 127.1, 149.2 MB over forty (7.6 MB over the
  first ten).

The Java heap swings between 15 and 36 MB depending on when the runtime last collected, and returns to
15-17.5 MB at its low points; the native heap moves between 33 and 40 MB. Nothing here shows a steady climb,
and nothing here rules one out: the reading is noisier than the allowance. The budget is decided by the soaks.

### Emulator timings, for the record only

Six cold starts of `benchmarkRelease`, read from the system's own log lines: first frame 0.55 to 0.80 s, fully
drawn 2.2 to 2.6 s. A software-rendered x86 emulator; not a tier, not evidence, and not to be compared with a
budget.

## Findings

1. **Fixed: opening the full player ended the app on Android 12 and 13.** The output row asked the selected
   route for its type (`MediaRoute2Info.getType`, API 34). On API 31 and 32, where the newer audio-device
   query is not used either, the call was always made and the process died with `NoSuchMethodError`. Found
   by the player journey on the API 31 emulator. `routeDeviceType` now asks only from API 34; below that the
   route's name decides the icon. `AudioRouteTypeTest` covers it. Lint's NewApi check did not report this
   call, so that check cannot be relied on to have found every such call.

2. **Settled by the owner, 21 October 2026: the cold-start budget is relaxed to 2.0 s on Tier R (3.0 s on Tier L)
   and the startup logo stays** ("it is fast enough"). The budget table and `check_performance.py` carry the new
   numbers; the 1,838 ms phone measurement below passes it. What led there: the cold-start budget on Tier R could
   not be met with the startup logo as it was. The logo runs for 1,150 ms and the app's first composition waits for the logo's first 740 ms
   (deliberately: the comment says the first composition must not stall the logo). The shell is therefore
   usable no sooner than about 1.15 s after the first frame, and the budget is 1.2 s from process start. This
   is arithmetic from `StartupLogo.kt`, not a measurement, but no phone creates a process and draws a first
   frame in 50 ms. The choices are: shorten or drop the logo on a cold start; compose the shell under the logo
   from the first frame and accept the risk the comment describes; or a product-level waiver that defines the
   budget from the first frame. This package changed nothing here and awards no credit for it. With system
   animations off or Reduce Motion on, the logo is skipped and the question does not arise.
   Measured on the owner's phone (I2217, Android 16, animations at 1x) on 21 October 2026, the first benchmark
   run there after Q18-023: first frame p95 548 ms, usable shell p95 1,838 ms with the Baseline Profile and
   1,950 ms without, over 20 cold launches each. The gap is the logo, as the arithmetic said.

3. Recorded, not a budget: 24 preference reads on the main thread during a cold start (list above).

## The optimization rules, checked against the code

| Rule | State |
| --- | --- |
| Profile before changing | No optimization was made on a guess. The one behavioural change is the crash fix. |
| No high refresh rate in the background | `MainActivity.onPause` sets the preferred refresh rate to 0; it is asked for again on resume and focus. Present before Q14. |
| Translators and recognizers released | `LyricsTranslation` closes its language identifier and translator in `finally`; `VoiceSearchController` destroys its recognizer. Present before Q14. |
| Lite stays reduced | Lite keeps a smaller image cache and 16-bit opaque covers (`ArtworkImageLoader`). Not otherwise re-audited here. |
| Performance Auto / Full / Reduced setting | Not added. The plan asks for it only if adaptive rendering turns out to be needed, and nothing measured says so yet. |
| Effect construction cached, reads narrowed; offscreen video and tail players released | **Not audited in Q14.** These need frame and memory profiles from a phone to say where the cost is; they stay open with the frame budget. |
| A failed budget needs an optimization or a product-level waiver | Finding 2 is waiting for exactly that. |
| Ship a Baseline Profile | Done, above. |

## BLOCKED, and how to unblock each

Written in Q14. Since then Tier R timings, frames, memory, battery and both soaks have been run on the owner's
phone (status at the top); what is still blocked is Tier L, the API matrix and a sustained-120 fps claim.

| Gate | Needs | Then run |
| --- | --- | --- |
| Every timing and frame budget, Tier R | the I2217-class phone, approval to install `com.musicglass.personal.benchmark` on it | the commands below |
| Every timing and frame budget, Tier L | a phone with 4 GB of RAM or less, the same approval | the same |
| API matrix 31 / 33 / 36 | a device or a working emulator image for each (the API 31 image here cannot trace) | the same; emulators give function, not budgets |
| Profile with/without comparison | any device whose tracing works | `StartupBenchmark`, `FrameBenchmark.homeScroll*` |
| Battery: three 2-hour runs | Tier R phone, unplugged, a measured discharge method | not scripted; a run without a trustworthy measurement stays BLOCKED rather than passing |
| Soaks: 4 hours live, 2 hours offline | Tier R phone; the live soak also needs an account, which this work may not use | not scripted |
| No sustained-120 fps claim | high-refresh frame results from a phone | until then no such claim is made anywhere; a display mode request is not a frame rate |

On a phone, in aeroplane mode, with system animations on, charged and cool:

```
gradlew :app:assembleBenchmarkRelease :benchmark:assembleBenchmarkRelease
adb install -r app/build/outputs/apk/benchmarkRelease/app-benchmarkRelease.apk
adb install -r benchmark/build/outputs/apk/benchmarkRelease/benchmark-benchmarkRelease.apk
adb shell am instrument -w -e class com.musicglass.personal.benchmark.JourneyCheck \
    com.musicglass.personal.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e androidx.benchmark.enabledRules Macrobenchmark \
    com.musicglass.personal.macrobenchmark/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/media/com.musicglass.personal.macrobenchmark/ results/
python scripts/quality/check_performance.py summary --tier R --refresh-hz 60 results/com.musicglass.personal.macrobenchmark/*benchmarkData.json
```

Keep the result JSON, the traces beside it, the phone's model, API level and build fingerprint, and the
revision of the build; the summary prints the first three. Run the frame benchmarks once at 60 Hz and once at
the panel's highest rate, and pass the rate that was in force as `--refresh-hz`. `gradlew
:benchmark:connectedBenchmarkReleaseAndroidTest` does the install, run and pull in one step where Gradle may
use the network once to fetch AGP's test runner.

`MainThreadAudit` and `MemoryCheck` run the same way by class name; install `app-nonMinifiedRelease.apk` for
the audit.
