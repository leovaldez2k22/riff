# Q02 deterministic test harness

Q02 is authorized independently of Q03. These fixtures use synthetic identities and original generated audio; they require no phone, personal cookies or live provider access.

## Runtime boundaries

`AppContainer` owns the process runtime and its lazy provider; `shared()` remains a compatibility facade. Custom containers retain their injected runtime. `RuntimeDependencies` supplies wall and monotonic clocks, IO/main dispatchers, network validation/metering, battery, storage capacity, OkHttp calls, the provider HTTP engine factory, coroutine waits and an optional stream resolver. Android implementations remain the production defaults.

`YouTubeMusicProvider` accepts these dependencies at construction. Resolver injection runs through the existing stream cache, quality keys and expiry policy. Explicit `StreamRequest` quality is honored; the existing convenience API still derives quality from Data Saver/download settings. Test instances can disable scheduled refresh, prewarming, session probes and retired-cache cleanup. This switch is a constructor argument, not an exported control.

`OfflineDownloadEngine` uses injected download conditions, capacity, calls, resolution, retry waits and timestamps. Since Q09 the audio rebuild (`AudioAssembler`) and the source of a download's lyrics are injected as well, so a JVM test can run the engine from request to a committed bundle; see [downloads](downloads.md). Custom-runtime providers and SABR HTTP engines use the injected transport; temporary providers/engines close after downloads. Engine-construction failures release the download gate. Provider-specific lyrics and SABR protocol handling retain their current implementation. `MusicPlaybackGateway`, the service scope/sleep timer and crossfader accept runtime dispatchers/clocks/waits at their existing boundaries. This phase does not move all ownership or replace every Android/media clock; broader ownership changes belong to Q11.

Since Q10 `ProviderWorld` (`ProviderContractSupport.kt`) runs the real `YouTubeMusicProvider` on the pinned library with its HTTP transport replaced: each request is recorded and answered by the test from invented fixtures. See `provider-compatibility.md`.

Required `PlaybackGateway` commands are abstract. The verification queue fake implements reorder and explicitly throws unsupported for transport commands; it owns no transport to close. The computed current-position helper remains.

## Synthetic fixtures

Shared helpers live in `app/src/testSupport/java`, included only in JVM and instrumentation tests.

- `FakeClock`: independently controlled wall/monotonic policy time.
- `FakeSession`: synthetic scope, generation and verification state.
- `FakeCatalog`: manually completed requests retaining the requesting identity.
- `ScriptedResolver`: manually ordered/deferred results; offline, forbidden, rate limit, server, auth expiry, malformed and expired-stream scripts.
- `FixtureHttpServer`: loopback binding, immutable in-memory routes, GET/HEAD and single byte ranges; maximum 64 requests, 32 routes, 8 KiB headers, 16 MiB per body, two-second client read timeout and bounded shutdown. No filesystem path access or outbound forwarding.

The account-race test exercises the existing `PageMemoryCache` generation boundary: B finishes before delayed A; A cannot overwrite B. It is a prerequisite for Q03, not proof of an account/session coordinator that has not been implemented.

## Audio and packaging

Run `python scripts/quality/generate_audio_fixtures.py` to regenerate original PCM16, 16 kHz fixtures. Assets are under `app/src/androidTest/assets/quality/audio`; metadata records exact frames, duration, channels, frequencies, provenance and SHA-256.

| Fixture | Duration | Channels/frequency |
|---|---:|---|
| tone-a | 30 s | mono 440 Hz |
| tone-b | 30 s | mono 660 Hz |
| long | 300 s | mono 220 Hz |
| short | 5 s | mono 880 Hz |
| stereo | 30 s | left 440 Hz/right 660 Hz |
| silence | 5 s | mono zero PCM |

JVM tests read these through test resources. Only the instrumentation APK packages audio. The app APK does not contain it. Debug alone permits loopback cleartext via its network security config; release retains cleartext disabled and contains no fixture config/activities/fakes/assets. Stress receiver controls use `ContextCompat.RECEIVER_NOT_EXPORTED`; shell broadcasts cannot inject faults. Fault setters also reject non-debuggable builds. Custom service commands require both the app package and UID.

## Verification and rerun

Set JAVA_HOME to the bundled JDK, ANDROID_HOME to `.toolchain/sdk`, and GRADLE_USER_HOME to `.toolchain/gradle-user-home`. From the repository root:

~~~powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --continue --console=plain
~~~

Use an explicitly selected disposable emulator. Installing these APKs over a personal app is outside the fixture workflow. On a fresh isolated emulator:

~~~powershell
$fixtureSerial = 'emulator-5580'
./.toolchain/sdk/platform-tools/adb.exe -s $fixtureSerial install app/build/outputs/apk/debug/app-debug.apk
./.toolchain/sdk/platform-tools/adb.exe -s $fixtureSerial install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
./.toolchain/sdk/platform-tools/adb.exe -s $fixtureSerial shell am instrument -w -r -e class com.musicglass.personal.FixturePlaybackTest com.musicglass.personal.test/androidx.test.runner.AndroidJUnitRunner
~~~

`FixturePlaybackTest` drives a real Media3 player from the bounded HTTP server, checks preparation within 10 seconds, real playback advancement, 30-second duration within 100 ms and seek completion. It mutes output. Real elapsedRealtime and bounded waits measure the decoder; fake policy time never drives decoder assertions.

The app and instrumentation APK must have identical embedded build identity. Preserve the structured instrumentation output and run `scripts/quality/collect_results.py --validate-instrumentation <log>`. Use the Q01 collector with fixture mode, emulator tier, API and model to bind results to the paired APK hashes. Emulator success does not satisfy physical audio capture, device performance, live provider, accessibility or upgrade gates.

## Latest execution

Completed 11 October 2026 on source digest bac26f301045 (revision b99865fe, dirty). Fresh run: 318 JVM tests in 58 suites with zero failures, errors or skips; debug and release lint with zero errors; debug, instrumentation, shrunk unsigned release and stress APKs assembled. `FixturePlaybackTest` passed in 2.523 s on the disposable API 31 emulator against the paired final-source APKs, and the packaging audit passed on the same three APKs.

Evidence is under `artifacts/quality/b99865fe84b1d83d7495bb98fbd07b6e3abb7b20-dirty-bac26f301045/`: `q02-debug-fixture-03/manifest.json` binds the JVM, lint and instrumentation results to the app and test APK hashes; `q02-release-01/manifest.json` covers the release build. Hashes, failed attempts and limits are recorded in execution-ledger.md.

When collecting by hand, set ANDROID_HOME first so the collector can verify the debug signature, and pass UTC timestamps with at most six fractional digits.
