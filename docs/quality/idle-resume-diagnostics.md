# Idle mini-player resume diagnostics

Stage 1 of the [execution plan](../idle-playback-investigation-plan-2026-11-01.md).
This instrumentation leaves solver lifetime, warm-up scheduling, extraction priority,
cache policy, network budgets and recovery behavior unchanged. `StartupDiagnostics`
is enabled by `BuildConfig.DEBUG`; release builds do not emit these events.

## Capture contract

Use a side-by-side diagnostic debug build (`-PriffSideBySide=true`). Verify the APK
package is `com.musicglass.personal.debug` before installation as part of Stage 2.
The owner signs in themselves. Stage 2 was resumed on 1 November; physical measurement
awaits a connected test phone and signed-in test app.

Capture **one deliberate mini-player tap per log**, on one device during one boot.
Do not press other transport controls or select another track during the measured
window. The analyzer rejects multiple tap markers. Start recording before backgrounding
the app, retain the actual 11–15-minute idle period, foreground immediately, tap once,
then keep recording through sound or the declared measurement timeout. Use the same
capture length for paired runs and record failures/timeouts, not only successes.

For an authorized test device, record logcat with threadtime format and the
`RiffStartup:I` tag filter (`*:S` for other tags) into a local evidence file. Do not clear
app data or system log buffers. Record test conditions separately: account unchanged,
queue/index/position unchanged, output route, network, quality, download/byte-cache
status, thermal status and whether the native callback endpoint was available.

Analyze a saved capture:

```powershell
python scripts/quality/analyze_startup.py artifacts/idle-baseline/run-01.log
```

## Controlled physical capture runner

`scripts/quality/capture_idle_resume.py` captures one mini-player tap per sample and
uses a shell-side `IdleBaselineControl.java` helper to pause/restore the selected
position after recording stops. The helper is compiled separately with the local
Android SDK and run from `/data/local/tmp/riff-idle-control.dex`; it does not alter
the diagnostic APK. Its framework initialization has been verified on the API 36
test phone. It fails rather than selecting another app or an ambiguous session.

After the owner prepares the signed-in, paused queue and audible volume:

```powershell
python scripts/quality/capture_idle_resume.py --serial <test-phone> --output artifacts/idle-baseline/samples --case warm --repetitions 5
python scripts/quality/capture_idle_resume.py --serial <test-phone> --output artifacts/idle-baseline/samples --case idle-immediate
```

The first invocation saves numeric reference state. Subsequent runs require the same
paused position, queue size/index and volume. The runner confirms logcat delivery
before a tap, preserves incomplete captures, and verifies restoration afterward.
Idle captures background with Home, wait a real 660 seconds, then reopen and tap
the previously observed mini-player bounds. A missing/extra tap invalidates the
capture; it is never silently repeated. If the screen locks, the runner reports
that the owner must unlock to Home, without opening Riff, within the remaining
15-minute window. It changes no lock, timeout, volume or power settings.

The `idle-settled` case waits for a recorded foreground warm-up completion before
tapping, with a bounded wait. Conditions and observations are in each sample's
`metadata.json`, and the timeline is in `analysis.json`. Check actual pause age and
process/state branches before grouping samples; a long foreground pause belongs
in a separate control group. Capture support has compilation/device checks; the
full PR/lint/emulator checks remain at the batch end per the owner's workflow.

`pid` plus `epoch` (logger initialization in boot-relative microseconds) identifies a
logger incarnation. `lookup` identifies a span within it. `index` is only local context;
it is **not** propagated to Binder or worker coroutines. Cross-process attribution
uses the controlled single-tap window and shared boot-relative `at` timestamps.
Overlapping background work remains visible and must not be assumed caused by Play.

## Reading the evidence

- `minitap` is emitted in the mini-player click handler before gateway dispatch.
  `dispatch` is inside `withPlayer`, so their difference includes controller waiting.
- `entrystate` reports controller-visible state, buffered position/duration, position,
  suppression, play intent and queue size/index. `servicestate` reports the underlying
  player when play intent changes; `queuerestore` reports its restored state. Sources
  are fixed categories only. A controller may omit local configuration (`unknown`);
  a catalog item can resolve to a download, so confirm cache/download status separately.
- `foreground`/`background` include observations of own running services' foreground
  flags (`serviceforeground`, count 0 or 1). Main and solver service creation/destruction
  are explicit events in their own processes. These observations plus existing process
  exit diagnostics classify recreation; absence of a PID or graceful destroy event
  does not establish a crash. Record `dumpsys activity services`/process observations
  before and after idle when classifying OS demotion or reclamation; notification
  updates alone are not proof of an actual foreground state change.
- Spans distinguish resolver flights, token mutex waiting, WebView callbacks, Binder
  binding/RPC, cipher work and HTTP stages. First body bytes and decoder initialization
  are separate events. Inspect their relative positions; do not add overlapping spans.
- `audioadvancing` is a native sink callback estimate. A retained AudioTrack may not
  emit it again. In that case `output_endpoint=unavailable` and latency is null.
  Obtain a device trace or external output probe for such runs. UI `playing`, decoder
  readiness and the sink's reported start time are not acoustic/Bluetooth latency.

No URLs, tokens, account IDs, track metadata, response bodies or exception messages
are logged by these hooks. All fields pass the existing diagnostic allowlist.
