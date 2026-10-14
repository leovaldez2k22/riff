# CI lanes and portable tooling (Q16)

One entry point, `scripts/quality/verify.py` (or `scripts/quality/verify.ps1` on Windows, which calls it), runs each lane the same way on a developer machine and in CI. It needs Python 3.10 or later, a JDK 21, and the Android SDK packages the README lists. It holds no personal path, account or secret.

## Lanes

| Lane | Command | Installs | Where it runs |
|---|---|---|---|
| Pull request | `verify.py --lane pr` | nothing | CI on every push and pull request (`ci.yml`), and locally |
| Emulator | `verify.py --lane emulator --allow-install` | the debug app and its test APK on one emulator | CI on API 31, 33 and 36 after a push to `main` or `codex/**` (`emulator.yml`), and locally |
| Physical device | `verify.py --lane device --allow-install --serial <id> --tier R\|L` | the same pair, on the named phone | locally, on an authorized test phone |
| Benchmarks | `verify.py --lane benchmark --allow-install --serial <id> --tier R\|L` | the benchmark build (its own package id) | locally, on a phone of that tier |
| Packaging | `verify.py --lane packaging [--allow-install --previous-apk <apk>]` | with the flags, the previous APK and then the candidate, on an emulator | locally, for a candidate |

What each lane holds:

- **Pull request.** The quality script tests, the static checks, the JVM and Compose tests, release lint, an unsigned shrunk release build with its build identity, the release content check (the merged manifest's backup, cleartext, exported components and permissions, and no test, fixture, benchmark or key file), the 40 MiB ceiling and the packaged Baseline Profile, and the solver seam in the R8 mapping.
- **Emulator.** The fixture suite (`FIXTURE_CLASSES` in `verify.py`: account transitions, the legacy upgrade, local data and its export, playback, order, crossfade, normalization, recovery, downloads, and the lyrics renderer drawing a synthetic word-timed song). The network inside the emulator is turned off first and the run fails if it still answers. No Google account is used.
- **Physical device.** The same suite on a phone named by `--serial`. A phone is never picked automatically, even when it is the only one connected. `--mode live` only labels the run: any account on the phone stays there, and nothing here supplies credentials.
- **Benchmarks.** `:benchmark:connectedBenchmarkReleaseAndroidTest` on a tier R or L phone, read by `check_performance.py summary`. An emulator is refused: hosted or emulator timings are not evidence (Q14).
- **Packaging.** A source archive built from the tracked files and checked (no build output, local settings, key, credential or evidence file; the build files present), the release APK's identity and certificate (an unsigned APK is BLOCKED for the certificate, never passed), `SHA256SUMS`, and an in-place upgrade from `--previous-apk` with `adb install -r` (a reinstall, or a refusal for a certificate or version code, fails; nothing is ever uninstalled).

## Results

Each lane writes `lane-summary.json` and `lane-summary.md` in its evidence folder (`artifacts/quality/lanes/<lane>-<time>` unless `--evidence-dir` is given). The summary lists every gate the lane requires. A gate that could not run is BLOCKED with the reason. A required gate missing from the results is FAIL. Nothing counts as zero or as a pass by default. Exit status: 0 PASS_LOCAL, 2 BLOCKED, 1 FAIL.

Device runs go through `collect_results.py`, which:
- reads the structured instrumentation output case by case. An `INSTRUMENTATION_CODE: -1` alone is not success, and a process crash, an abort, a missing case or a truncated log fails. Every status block counts as a case, so a test in a lane reports its numbers to logcat or a file on the device, never with `sendStatus` (the lyrics test writes `q16a-*.txt` beside its images);
- compares the app and test APKs' embedded build identities before anything is installed;
- records both APKs' SHA-256 and binds them to the source digest.

The summary names a device by tier, API level and a cleaned model name, never by serial.

`--checks a,b` runs only those gates of a lane; the others are BLOCKED ("not selected"), so a partial run can never read as a full one.

## Reading and installing are separate

Without `--allow-install` no lane touches a device. A lane that needs one stops at BLOCKED, and the first line of output says `READ-ONLY` or `INSTALLS ON A DEVICE`. A device lane takes a lock for that device under `artifacts/quality/.locks` (named by a hash, not the serial), so two lanes never run on the same device at once. A lock older than six hours is treated as left by a dead run.

## Finding the tools

In this order:
- SDK: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `sdk.dir` in `local.properties`, then the project's own `.toolchain/sdk`.
- JDK: `JAVA_HOME`, then `.toolchain/jdk-*`.
- Gradle's cache: `GRADLE_USER_HOME`, then `.toolchain/gradle-user-home`.

`verify.py --tools` prints what was found. Paths are taken from the script's own location, so the project can be anywhere.

## CI

`ci.yml` runs the pull-request lane on pushes to `main`, `fix/**` and `codex/**`, and on pull requests. `emulator.yml` runs the emulator lane on API 31, 33 and 36 after a push to `main` or `codex/**`, and on request. Both:
- cancel a superseded run of the same branch;
- use a read-only token and no secret;
- pin each action to a reviewed commit;
- upload only sanitized summaries, manifests and the instrumentation text: no APK, no device backup, no signing file, no account export. Retention is 14 days for summaries and 7 for emulator results.

Dependency verification is strict in CI as it is locally. `gradle/verification-metadata.xml` now also lists aapt2's Linux and macOS jars, the one platform-specific artifact the build resolves. Their SHA-256 values were computed from Google's Maven repository; each download matched the SHA-1 published beside it, and the Windows jar from the same place hashed to the value already recorded.

## Other scripts

- `scripts/download_favourites.py` takes `--serial`, `--adb` and `--work-dir`; it no longer names a person's paths or phone. It is a personal helper, not a lane.
- `scripts/verify-device.ps1`, `scripts/run-ui-regression.ps1` and `scripts/stress/` still work as before. The lanes above are the supported way to produce evidence.

## Not verified here

- Neither workflow has run on GitHub: nothing was pushed. The first hosted run of each is the check that the Linux side works.
- The emulator lane was run locally on API 31 only (the system image that is installed). API 33 and 36 need their images: `sdkmanager "system-images;android-33;google_apis;x86_64" "system-images;android-36;google_apis;x86_64"`, an AVD for each, then the emulator lane against each.
- The physical-device and benchmark lanes need an authorized phone and were not run.
- The packaging lane's upgrade gate needs the last distributed APK and a candidate signed with the same key.
