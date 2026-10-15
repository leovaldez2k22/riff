# Testing (Q17)

How Riff is tested, what each layer needs and how to rerun it. Current as of 0.10.0-rc1, 15 October 2026. The lanes themselves are described in [CI lanes](ci-lanes.md); fixtures in [deterministic harness](deterministic-harness.md); what each run proved is in the [execution ledger](execution-ledger.md).

## Layers

| Layer | Where | What it covers | Needs |
|---|---|---|---|
| Script tests | `scripts/quality/test_*.py` | The lane runner, the result collector, the static checks, release-content, solver-seam, performance and canary scripts | Python 3.10+ |
| Static checks | `scripts/quality/static_checks.py` | Cancellation, blocking calls, dependency direction, lifecycle, per-frame position reads, file size (700 lines), raw measures, user-visible strings | Python |
| JVM and Robolectric | `app/src/test` (helpers in `app/src/testSupport`) | Logic, stores, migrations, the provider against recorded-shape fixtures (`ProviderWorld`), the download engine end to end, screens drawn with native graphics (pixel checks, accessibility sweep at four font scales, both directions, three window shapes) | JDK 21, the SDK, the Gradle cache. About 990 tests in 110 suites |
| Lint | `:app:lintDebug`, `:app:lintRelease` | Android lint; the collector compares warnings with the previous run | as above |
| Release content | `scripts/quality/check_release_content.py`, `check_solver_seam.py`, `check_performance.py apk` | The shrunk release: no backup, no cleartext, exported components and permissions as reviewed, no test, fixture, benchmark or key file, the Baseline Profile, the solver seam, the 40 MiB ceiling | a release build |
| Emulator fixture suite | `app/src/androidTest`, the classes in `FIXTURE_CLASSES` (`verify.py`) | Account switching, the legacy upgrade, local data and its export, playback from a loopback server, play order, crossfade, normalization, recovery, downloads, the lyrics renderer, the player and its open and close morph. Synthetic identities and generated audio; the emulator's network is off | An emulator. 51 tests |
| Physical device | the same suite, `--lane device` | The same, on real hardware | An authorized phone, named by serial |
| Benchmarks | `:benchmark` | Cold start, first audio, scrolling, the player, jank; Baseline Profile generation | A tier R or L phone. Emulator timings are refused |
| Packaging | `--lane packaging` | Source archive contents, release identity and certificate, checksums, in-place upgrade over the previous APK | A signed candidate; an emulator for the upgrade |
| Provider canary | `scripts/quality/provider_canary.py`, `provider-canary.yml` | A few well-known public songs and albums, read without an account, to notice a provider change early | Network. Never an account |
| Stress | `scripts/stress/scenarios.py` | Targeted scenarios on the `stress` build type over adb: crashes, ANRs, exit records per scenario | A device and approval to install |

Device journeys that need a person (TalkBack and keyboard walk-throughs, listening to a crossfade, a real account switch with real history) are in [core journeys](core-journeys.md) and [accessibility](accessibility.md). They are not automated.

## Commands

From the repository root, with `JAVA_HOME` and `ANDROID_HOME` set or the project's `.toolchain` present:

```bash
python -m unittest discover -s scripts/quality -p "test_*.py"
```

```bash
python scripts/quality/static_checks.py
```

```bash
python scripts/quality/verify.py --lane pr
```

```bash
python scripts/quality/verify.py --lane emulator --allow-install --serial emulator-5580
```

```bash
python scripts/quality/verify.py --lane device --allow-install --serial <id> --tier R
```

```bash
python scripts/quality/verify.py --lane packaging --allow-install --serial emulator-5580 --previous-apk <previous.apk>
```

Gradle directly (add `--offline` once the cache is filled):

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest
```

One test class: `./gradlew :app:testDebugUnitTest --tests com.musicglass.personal.LyricsTest`. One device class: `adb -s <serial> shell am instrument -w -r -e class com.musicglass.personal.LyricsKaraokeDeviceTest com.musicglass.personal.test/androidx.test.runner.AndroidJUnitRunner`.

Evidence runs go through `scripts/quality/collect_results.py --variant debug|release`, which runs the suite and lint, rejects an incomplete or stale result, and writes a manifest binding the results to the source digest and the APK hashes. See [build identity and evidence](build-identity-and-evidence.md).

## Hardware

| Device | Used for | Status |
|---|---|---|
| Disposable emulator `riff_q02_fixture`, API 31, x86_64, port 5580, network off | Emulator lane, development of device tests, the packaging upgrade | In use. Created for this work; holds no account |
| Emulators API 33 and 36 | Emulator lane in CI (`emulator.yml`) | Images not installed locally; hosted CI not yet run |
| Tier R phone (iQOO I2217-class, ARM64) | Device lane, benchmarks, journeys | Not yet available. The owner will lend a phone; installing on it needs their confirmation of the phone and the build |
| Tier L phone (4 GB of RAM or less) | Benchmarks | Not available |

Never install on a personal phone without that confirmation, never uninstall the user's app to get past a signing or version error, and never use a personal account in a test.

## Writing device tests

- A device test reports its numbers to logcat or to a file in the app's external files folder, never with `sendStatus`: the collector reads every status block as a test case.
- Keep pixel checks tolerant of the renderer's rounding (Robolectric places text on whole pixels) and measure what stands out from the background, not the background itself.
- Stop the emulator before a JVM evidence run: one Robolectric screen test has timed out under the emulator's CPU load.

## Reruns

- A failed run is kept, not overwritten. The rerun gets the next number (`-02`) and the ledger records both.
- A flaky result is not a pass. Rerun the one class alone to tell a load timeout from a fault, fix the cause or record it, then run the full collector again.
- Delete stale APKs and lint reports before a collector run (`app/build/outputs/apk/*/`, `app/build/reports/lint-results-*`); the collector refuses outputs older than its run.
- A BLOCKED gate stays BLOCKED until it runs on what it needs. Each BLOCKED item in the ledger carries its rerun steps.
