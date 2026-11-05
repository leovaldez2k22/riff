# Riff: idle playback investigation and implementation plan

Date: 1 November 2026. Status: Stage 1 diagnostics implemented and locally validated; Stage 2 partially measured, including one extended-idle prepared-player resume; behavior fixes pending.

Update, 4 November: the owner explicitly requested an Android pipeline audit and implementation. See the [current audit and fixes](quality/playback-startup-warm-resume-2026-11-04.md). It closes confirmed controller, cache-flight, catalog-recovery and solver-binding defects and adds direct-source TTL validation and selected-first preparation. This is a new implementation against the current tree, not blind application of the old combined candidate patch. The older status and candidate table below are historical; device latency attribution/acceptance is still pending, with screen testing on hold. Later that day a [second audit](quality/playback-startup-warm-resume-2026-11-04.md) corrected two regressions in that work and attributed most of the one cold-restored sample (7.2 s) to the cipher stage.

## 1. Objective and reproduction

Resolve the owner's signed-in YouTube Music playback delay: leave Riff backgrounded and idle for more than ten minutes, reopen it, and immediately press **Play on the mini-player to resume the existing queue**. Sound takes more than six seconds and sometimes eight seconds.

The deliverable is a measured explanation of that delay, followed by the smallest supported fixes. A successful build, an eligible hypothesis, or a UI `playing` event does not establish a latency improvement.

Stage 1 now adds diagnostics to application source; behavior candidates remain in the investigation bundle and an isolated detached checkout. The owner requested that the code investigation and diagnostic bundle be completed before connecting a phone. This plan does not authorize an account mutation, clearing data, replacing the owner's installed app, or publishing a release.

## 2. What is already established

Source references below are against reviewed revision `e4748bf6a59aa4d56575ef1c97215f2a58b05c92`; check them against the current source before implementing. Dependency findings are from the locally shipped artifacts, not an assumed current upstream implementation.

| Finding | Evidence | Meaning for this reproduction |
|---|---|---|
| Solver binding expires after ten idle minutes | [RemoteCipherClient.kt:33](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RemoteCipherClient.kt:33>), `scheduleIdleUnbind():159` | A later extraction can require service binding and runtime initialization. |
| Releasing the solver service disposes its runtime | [SolverService.kt:91](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/SolverService.kt:91>), `onDestroy()` | Retained disk player text is not a retained live interpreter. Service destruction does not prove process death. |
| The old idle timer is not cancelled at request entry | [RemoteCipherClient.kt:66](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RemoteCipherClient.kt:66>), `callOnce()` | The release timer can race a new transaction. This is a confirmed defect. |
| Media3 separately demotes an inactive foreground service after ten minutes | [Media3 notification bytecode](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/notification-manager-bytecode.txt>), [service bytecode](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/session-service-bytecode.txt>) | Demotion can precede OS reclamation; it is not a kill or a hardware-suspension event. Its timeout setter is capped at ten minutes. |
| Cold resolver warm-up is delayed two seconds and marked warm at launch | [ProviderStreamResolver.kt:103](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ProviderStreamResolver.kt:103>), `init`; `warmUp():119` | Immediate foreground warm-up can be suppressed while delayed work later competes with Play. |
| Cold startup launches other work | [ProviderSessionManager.kt:198](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ProviderSessionManager.kt:198>), [MainActivity.kt:15](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/MainActivity.kt:15>), `onResume():41` | The initial session probe, Home prefetch and resumed downloads can overlap playback. Their actual cost is unmeasured. |
| A restored queue contains unresolved YouTube catalog entries | [QueueCoordinator.kt:158](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/QueueCoordinator.kt:158>), `restore()` | Saved state restores queue/index/position but deliberately waits until Play to prepare. Former direct URLs are not persisted through this restoration. |
| Selected and following sources have no selected-first extraction gate | [PlaybackSources.kt:74](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlaybackSources.kt:74>), `createMediaSource()` | In a multi-item queue, Media3 preparation can make two lookups compete. The explicit three-second look-ahead delay is a separate mechanism. |
| Cipher preload and lookup share library serialization | [Cipher bytecode](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/cipher-bytecode.txt>), [formats coroutine bytecode](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/cipher-formats-bytecode.txt>), [SolverGate.kt:15](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/SolverGate.kt:15>) | Lookup priority cannot interrupt preload work already holding the operation mutex. It also does not prioritize one lookup over another. |
| Token acquisition has serial and network/renderer stages | [AndroidStreamTokenProvider.kt:69](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/AndroidStreamTokenProvider.kt:69>), `getPoToken()` | Its existing mint timer omits time spent acquiring the mutex. Token expiry depends on the server; a fixed ten-minute expiry is not established. |
| Token WebView callbacks lack a local deadline | [AndroidTokenRuntime.kt:38](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/AndroidTokenRuntime.kt:38>), `eval()` | Missing callbacks can extend token work. This is distinct from native QuickJS, which has a configured 30-second evaluation timeout, 192-MiB memory limit and 256-KiB stack limit. |
| A ten-minute-old stream can remain cache-eligible | [ProviderStreamResolver.kt:160](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ProviderStreamResolver.kt:160>), `resolve()`; expiry construction at line 249 | The resolver caps age through wall-clock expiry at twenty minutes and requires a sixty-second expiry margin. Eligibility is not a guarantee of CDN acceptance. |
| Direct media startup has eight-second socket limits | [OkHttpStreamDataSource.kt:37](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/OkHttpStreamDataSource.kt:37>), `open()` and `read():78` | A stalled operation can be expensive. A matching eight-second symptom does not prove dead socket reuse. The pinned default idle pool lifetime is five minutes. |
| Playback is native Media3/AudioTrack | [MusicPlaybackService.kt:211](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/MusicPlaybackService.kt:211>) | Audio focus/noisy-route handling is enabled and offload disabled. There is no playback AudioContext to resume. |
| UI `first_audio` is not a hardware-output measurement | [RiffApp.kt:141](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffApp.kt:141>) | Measure output separately. Pinned load control requires 1,000 ms of media initially and 2,000 ms after rebuffer; those are media durations, not fixed wall-clock delays. |

Historical phone records in [q18-bugs.md:75](</C:/Volume D/Riff/docs/quality/q18-bugs.md:75>), lines 106 and 117 describe a 6.3-second low-memory/cold start, other 6.1–8.5-second cold starts, and competing queue lookups. These support investigation priorities; they are not a fresh trace of the current reproduction.

## 3. Exact path to investigate

The primary path is:

1. [PlayerPresentation.kt:191](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlayerPresentation.kt:191>): `MiniPlayerContents` Play invokes `playback.toggle()`.
2. [PlaybackGateway.kt:249](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlaybackGateway.kt:249>): `toggle()` runs through `withPlayer`; it checks embedded playback, recovery-held state and whether the action should pause or start.
3. [PlaybackGateway.kt:263](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlaybackGateway.kt:263>): `start()` calls `playFromUser()` when there is no player error. An existing player error instead sends `RETRY_PLAYBACK` to the service.
4. [PlaybackGateway.kt:511](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlaybackGateway.kt:511>): `playFromUser()` seeks from ENDED, prepares from IDLE, then calls `play()`.
5. If preparation is necessary, catalog items enter `PlaybackSources.createMediaSource()` -> `DeferredProviderSource` -> `ProviderStreamResolver.resolve()` -> pinned extractor -> token/cipher stages as needed.
6. A resolved direct stream enters `ProviderMediaDataSource` -> `OkHttpStreamDataSource.open/read`, unless served by the byte cache or a verified download.
7. Media3 extracts, buffers, decodes and feeds processed PCM to the native sink. Observe focus suppression, sink failures and output-position advancement.

**Branch A — surviving prepared player:** a READY player need not extract or bind the solver. Buffered data can make networking unnecessary. If this branch stalls without any extraction, investigate retained-source reads, focus, decoder/sink and routing. A solver shutdown cannot explain a run in which playback makes no solver request.

**Branch B — recreated service/restored queue:** [MusicPlaybackService.kt:425](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/MusicPlaybackService.kt:425>) loads saved state asynchronously; `QueueCoordinator.restore()` builds catalog items and leaves them paused/unprepared. Play starts fresh preparation, with possible following-source competition. If Play arrives before restore completes, the service preserves the requested play intent at lines 428–430.

A normal mini-player resume does not encode a new catalog queue file or call `playCatalogQueue()`. Fresh song selection is a secondary control, not the owner's primary reproduction. The complete alternative path is retained in the [forensic report](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/report.md>).

## 4. Priority and confidence

Ratings describe engineering impact, not certainty about the current delay.

| Priority | Work | Confidence |
|---|---|---|
| Very high | Measure real resume stages and distinguish surviving from recreated player | Required before assigning the 6–8 seconds |
| High | Fix solver idle-release race | Confirmed defect; current frequency unknown |
| High | Make foreground warm-up controlled and selected playback take precedence over speculative extraction | Confirmed scheduling/contention opportunities; current latency contribution unknown |
| High | Bound missing token bridge callbacks and recover renderer death | Confirmed missing local guard; current trigger unknown |
| Medium | Tune direct startup deadlines and pool hygiene | Network stall hypothesis; candidate budgets need phone evidence |
| Medium | Add explicit stream-age provenance and retained-source validation | Resilience gap; shorter caching can increase extraction cost |
| Medium | Recover once from verified native output failures | Conditional resilience fix, not evidence of a suspended device |
| Medium | Remove orphan extraction work and make pre-connection transport intent deterministic | Follow-up concurrency work, not established idle root cause |
| Low | Broader decoupling after the measured fix | Maintainability work; keep it out of the initial latency comparison |

No evidence currently justifies calling this a Critical data-loss/security incident. The repeatable user-facing delay is high priority; it is not proven to be a crash, dead socket, or expired token.

## 5. Work in order

### Stage 1 — finish baseline instrumentation

Implemented 1 November: diagnostics-only patch 01 applied, with mini-player
pre-dispatch timing, safe controller/service state, process-incarnation identities,
service lifecycle and foreground observations. Diagnostics are debug-only. The maintained
analyzer is now `scripts/quality/analyze_startup.py`, with regression coverage for
cross-process span collisions, missing output callbacks and ambiguous captures. See the
[capture contract and field guide](quality/idle-resume-diagnostics.md). This deliberately
uses the plan's one-tap/PID/boot-time correlation alternative; no distributed request ID
is claimed. Retained AudioTrack runs without the callback explicitly have no output
endpoint. Physical baseline and latency acceptance remain open. Do not reapply patch 01
to this implementation.

Validation: `python scripts/quality/verify.py --lane pr` **PASS_LOCAL**, all nine gates,
**1,508 JVM tests, zero failures/errors/skips**, and 174 script tests. Evidence:
`artifacts/quality/lanes/pr-20261101T082332Z/lane-summary.json`; build/test identity:
`artifacts/quality/e4748bf6a59aa4d56575ef1c97215f2a58b05c92-dirty-4fc5e7a73041/lane-pr-20261101T082332Z/manifest.json`.
The new coverage is three JVM diagnostic tests and six analyzer tests. This is local
instrumentation validation, not a phone latency measurement or validation of patches 02–06.

Use `01-diagnostics.patch` as the starting point, without behavior patches. It already records monotonic spans for resolver work, token locks/bootstrap, solver binding/RPC, cipher events, HTTP connection stages, first body bytes, decoder initialization and audio advancement. Keep account tokens, URLs, media identifiers, response bodies and exception messages out of logs.

Before a physical baseline, close these known diagnostic limitations:

- Add a UI event timestamp at the mini-player action before controller dispatch. The existing candidate `toggle()` marker runs inside `withPlayer`, so it cannot measure earlier connection waiting.
- Record safe entry-state fields: playback state, buffered position/duration, suppression reason, queue size/index and current-source type; use local numeric identities rather than personal track metadata.
- Give a resume attempt a stable correlation identity across UI, source and solver work, or enforce one deliberate tap per diagnostic run and explicitly correlate process-local spans by PID plus boot-relative time. The existing `index` and `lookup` values are not global distributed IDs.
- Capture main/solver process identity, service creation and foreground state before/after idle. Use existing exit diagnostics to classify reclamation; a missing solver PID alone is not a crash.
- Treat `onAudioPositionAdvancing` as a useful native-output estimate. It may not refire for every resume on a retained AudioTrack. Where absent, use a suitable device trace/output probe or explicitly mark the endpoint unavailable. Acoustic/Bluetooth latency requires an external output measurement; do not substitute UI `playing`.

Deliverable: one readable timeline separating lock acquisition, computation, network, buffering and output. Do not sum overlapping spans.

### Stage 2 — capture the unchanged baseline when the phone is available

Started 1 November at the owner's request. ADB reported no connected devices, so
physical captures cannot start yet. Prepare and package-check the side-by-side APK
locally; do not mark this stage complete until the signed-in physical samples exist.
The owner must sign into the test app themselves. No baseline or latency result is
inferred from the diagnostic build.

Preparation complete: `:app:assembleDebug -PriffSideBySide=true --offline` passed.
`aapt2 dump badging` verified `com.musicglass.personal.debug`, version code 34,
`0.20.0-debug`, and `application-debuggable`. Preserved APK and receipt:
`artifacts/idle-baseline-20261101T091433Z/` (`riff-idle-baseline-debug.apk`,
SHA-256 `3a45f8d0a7a971b830ce153794473578cd7c421bf5a00521096c113f44d14a1b`).
Its embedded source digest is `4fc5e7a73041d5f9a03cc45a5a78af6213c1fe09e744183c87cbc0383dd33e2c`,
matching Stage 1's validated application source. Initially no device was connected.
The owner then connected the authorized vivo I2217 (tier R, API 36): the APK's hash
and package were checked again and `adb install -r` succeeded without clearing data.
The package was confirmed for the active owner profile (Android user 0) and Riff Debug
opened for the owner to sign in/prepare a paused queue. Preparation readings: thermal
status 0, battery 93%, battery temperature 34.4°C. At preparation, zero baseline samples
had been collected. Full PR/lint/emulator checks were not repeated for this preparation,
per the updated workflow.

The owner then confirmed sign-in, a paused streamed entry in a multi-song queue, and
an audible media volume. Four valid short-pause warm mini-player samples are recorded
under `artifacts/idle-baseline-20261101T091433Z/samples/`: native callback estimates
677.535, 653.781, 668.804 and 668.184 ms (median 668.494 ms). An additional valid
foreground-paused control took 822.979 ms; its last paused state update was 753.462
seconds before the tap, so it is kept separate from the warm group. A replacement
warm sample remains to be collected. These are native-output estimates, not acoustic
measurements or a p95. Every valid control entry
was already READY with approximately 87 seconds buffered, and no resolver/HTTP spans
were observed in those capture windows; thermal status was 0 before/after each.
The same 19-entry queue/index 0 and paused position 30,259 ms were retained/restored.
One additional warm attempt missed its tap marker because logcat attached too late;
it is excluded and preserved. The collector now requires a delivered readiness marker
before tapping. The first real background-idle attempt reached 660 seconds, but the
phone was locked/asleep. The collector requested owner unlock to Home and stopped
without tapping when it was unavailable within the 15-minute window. This attempt
(`idle-immediate-20261101T101439Z`) is incomplete, not an idle-latency sample. At the
660-second snapshot the main process remained present, the solver PID was absent,
thermal status was 0 and volume remained 12; the missing solver PID does not establish
a crash. The app received no Play in that attempt. At that point, zero valid
background-idle resume samples existed. Current counts and individual results are
in the evidence directory's `baseline-progress.json`.

After owner unlock, an **extended-idle control** was captured separately at
`samples/extended-idle-20261101T103408Z/`: background to foreground **1,165.273 s**
(19 min 25 s), foreground to tap **440.696 ms**, tap to dispatch **3.524 ms**, and tap
to native output estimate **561.217 ms**. The main process survived, entry was READY
with **87,402 ms buffered ahead**, no suppression, the same queue/index/position,
and foreground-service status had changed from observed 1 before idle to 0 on return.
The solver was absent before return and recreated afterward. Foreground warm-up took
**13,781.037 ms**, including about 5.9 s and 6.1 s cipher RPCs, while buffered playback
advanced after 0.56 s. Do not attribute that overlapping warm-up to playback latency.
This run did not reproduce a 6–8 s delay in the surviving-prepared branch. It is
outside the controlled 11–15-minute cohort, has an observation gap after the earlier
collector stopped, and is not a p95 or acoustic measurement. Owner audible-output
confirmation and additional branches/samples remain pending; no behavior fix has
been selected. A readable `timeline.md` accompanies its raw/structured evidence.

Capture support: `scripts/quality/capture_idle_resume.py` and shell-only
`IdleBaselineControl.java` (compiled and checked on the API 36 phone). The helper
targets only Riff Debug and reads numeric state/restores paused position outside
capture windows. It adds no code to the diagnostic APK. Exact capture-tool copies
and hashes are retained under the evidence directory's `capture-tools/` folder.

Build a side-by-side diagnostic app using `-PriffSideBySide=true`; verify its package before installation. The owner signs into that test app themselves. Preserve production account/data. The owner resumed this stage on 1 November; physical execution awaits a connected phone and test-app sign-in.

Keep the same signed-in account, queue, selected index, paused position, song, quality, device output route and network for paired runs. Record downloaded/byte-cache status and thermal state.

Run five initial repetitions per relevant case to identify the dominant stage:

1. Warm mini-player resume without an idle gap.
2. Background idle for 11–15 minutes, reopen and immediately press mini-player Play.
3. Same idle interval, reopen and let the recorded warm-up finish before pressing Play.
4. Equivalent downloaded-track resume, to separate extractor/network work from UI/output work.
5. A fresh single-song selection as a control for multi-item queue contention.

Classify each run as surviving READY/prepared, surviving IDLE/reprepared, recreated service/main process, or playback-error recovery. Keep different branches separate in results. Observe natural process reclamation first; a later deliberate cold-start control must be labelled as such and must not clear app data.

Use [analyze_startup.py](../scripts/quality/analyze_startup.py) for the implemented diagnostic format. The bundle's analyzer remains a historical candidate.

### Stage 3 — select the fix from the evidence

| Observed dominant interval | Next action |
|---|---|
| Following lookup completes while selected lookup waits | Add selected-first network-resolution gating; defer speculative cipher work until selected startup completes. |
| Service bind/runtime/cipher cold work dominates | Remove artificial warm-up delay, coalesce foreground preparation, fix idle lease, and prioritize selected work. Keep disk player caching; measure whether a dependency change is actually needed. |
| Token lock/bootstrap dominates | Separate lock wait from minting, prevent redundant bootstrap, bound bridge callbacks and recover renderer death. Refresh only on valid token/account invalidation signals. |
| Acquired connection to headers/first bytes dominates | Compare pooled versus new connections, HTTP status and cancellation; apply scoped deadlines/pool changes and retest. A slow server response is not automatically a dead socket. |
| Stale retained URL fails before refresh | Validate provenance/advertised expiry, invalidate only the affected stream, and route one bounded refresh through the recovery owner. |
| Buffered READY player has no network/extraction but output is delayed | Investigate focus suppression, native sink errors and routing. Do not change URL caching to address this run. |
| UI/controller waiting dominates | Fix dispatch/connection intent handling and measure again; extraction patches do not resolve an earlier dispatch delay. |

If traces show two causes, stage them separately so their effects remain attributable.

### Stage 4 — implement the supported layers

**4A. Solver lifecycle correctness.** Use the active-call/generation lease in patch 06. Cancel the prior idle timer at request entry; schedule release only after all calls finish; reject an obsolete release generation. Preserve the ten-minute idle-release policy. Account for success, error and cancellation. A caller-side coroutine timeout is not a cancellation mechanism for synchronous Binder transactions.

**4B. Foreground preparation and selected priority.** Use the relevant portions of patch 02. Remove the two-second delayed initializer, track/coalesce warm-up and record completion rather than launch. Trigger preparation on foreground return. Ensure selected resolution is admitted before following-source network extraction; local media may preload. Cancel waits on source release and immediately promote a newly selected entry. Already-running library work is not preempted by a new gate. Warm-up must not start speculative work that wins the same mutex immediately before Play. Keep it process-owned, bounded and event-driven; do not retain the helper indefinitely or add periodic background keepalive.

**4C. Socket deadlines and recovery ownership.** Patch 03 proposes explicit two-minute idle pools, two-second connect limits, and real `Call.cancel()` deadlines for each initial direct-song header operation and first body read. Keep ongoing reads at their existing eight-second limit initially. These are separate per-operation limits, not a two-second end-to-end guarantee. Apply only to the intended progressive song path; retain live/video/HLS behavior until separately tested. Pass startup timeout/stale-address failures to one recovery coordinator rather than multiplying transport and application retries. Preserve a bounded episode and the latest play/pause/track intent. Measure false timeouts on slow networks before adopting these values.

**4D. URL provenance and cache policy.** Patch 02 proposes an explicit monotonic resolved-at timestamp, five-minute maximum age and existing sixty-second provider-expiry margin, carried across resolved-stream IPC. Stamp successful injected resolver results too; accept zero uptime and use injected clocks. A recreated process has no trustworthy old elapsed-time origin, so restore catalog items rather than serialized monotonic addresses. Validate before creating/opening an aged source. Importantly, a source already prepared before idle can bypass source-creation validation: extend the design to validate at a relevant reopen/read boundary or replace that source on evidenced expiry/failure while preserving position. Do not tear down a healthy buffered source merely because the UI foregrounded. Five minutes is a candidate policy, not a proven CDN lifetime; compare additional extraction/quota cost.

**4E. Token renderer recovery.** Patch 04 resumes only the owned token WebView, handles `onRenderProcessGone`, and proposes a three-second deadline for each JS bridge callback. Preserve the existing async-computation budget; test heavy scripts on the actual phone. Do not use global WebView timer changes as an audio-engine fix. The native cipher engine's separate timeout does not cover these WebView callbacks.

**4F. Native audio repair.** Patch 05 proposes one reprepare per consecutive failing entry only after an actual AudioTrack initialization/write error. Recheck current entry/error before repair, preserve queue/index/position/settings and current play/pause intent, and reject obsolete repairs. Verify reset behavior on success and entry changes; add a focused recovery test before promotion. A healthy READY sink uses normal Media3 resume. Focus suppression, unplugged hardware and routing must be handled through their actual signals; an unconditional foreground flush can discard useful buffered audio and worsen latency.

### Stage 5 — regressions and performance acceptance

Workflow updated by the owner on 1 November: keep stages as separate, reviewable
changes and use quick compilation/focused tests during development as needed. Run
`python scripts/quality/verify.py --lane pr` (which includes release lint) and the
emulator suites at the end of the implementation batch. Intermediate stages remain
pending final verification until those checks pass. Baseline collection and
evidence-dependent policy decisions retain their order; batching checks does not
replace physical measurements. Previous candidate results do not cover later source changes.

Required regression coverage:

- Timer firing at the idle boundary; overlapping RPCs; failed/cancelled calls; obsolete timer generations.
- Selected and next extraction arriving together; rapid Next/Play/Pause; queue reorder/removal/shuffle/repeat during preparation; released sources and newly selected waiters.
- Zero uptime, injected clocks/resolvers, expiry margin, aged resolved IPC/source and already-prepared-source resume.
- Black-holed initial headers/body, slow but progressing streams, 403/410/429, offline-to-online recovery and expiry mid-track. Honour supported Retry-After/rate-limit policy without introducing unbounded re-extraction.
- Token expiry/account rotation, lost WebView callbacks and renderer death; no cross-account reuse.
- Native initialization/write error, repeated failure, pause/track switch during repair, focus interruption, route change and retained AudioTrack resume.
- Download/cache-hit startup, long listening sessions and no extra selected-source starvation or background resource retention.

Re-run paired physical-device samples after each evidence-supported layer. Five runs are diagnosis, not a dependable p95. For acceptance, collect at least thirty labelled samples per primary compared branch, retain the distribution, and report failures/timeouts as well as median and p95. Idle testing can be scheduled over multiple sessions; do not simulate eleven minutes merely by changing a clock.

Proposed engineering target, subject to baseline/network evidence: remove the repeatable 6–8-second local wake-up overhead, improve idle-resume median by at least 50% in the affected branch, and aim for p95 at or below three seconds on the stable test network. These are proposed targets, not an existing approved budget or a promise under arbitrary network conditions. No meaningful warm-resume/downloaded-track regression, increased retry storm or loss of transport intent is acceptable. Record extraction count and memory/thermal effects with latency.

### Stage 6 — promote only the proven change

Review the final diff against the current branch, preserving unrelated work. Apply the minimal validated source change, update the playback/recovery documentation and measurement evidence, and rerun the required lane. Keep policy changes independently reversible. Device latency acceptance remains open until measured; the production release process and any publishing/account action remain separate owner-controlled steps.

## 6. Candidate patch map and current verification

The [bundle](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/riff-idle-diagnostic-bundle.zip>) contains complete source and these sequential diffs:

| Patch | Contents | Status |
|---|---|---|
| `01-diagnostics.patch` | Baseline hooks and high-resolution timing wrapper | Locally built/tested; limitations in Stage 1 remain |
| `02-selected-first-and-ttl.patch` | Selected-first gate, foreground warm-up and URL age | Candidate, combined concerns; split for attribution |
| `03-startup-network-budget.patch` | Pools, scoped cancellation, stale/error recovery routing | Candidate budgets need device evidence |
| `04-token-renderer-resume.patch` | Token WebView recovery and callback guard | Candidate budget needs device evidence |
| `05-native-output-error.patch` | Error-triggered native reprepare | Candidate; focused lifecycle/intent coverage still required |
| `06-solver-idle-lease.patch` | Active-use accounting and obsolete-timer protection | Confirmed-defect fix candidate |

These diffs apply **in numeric order**, or use `all-candidates.patch` once. Do not apply later patch files out of order. For independent performance comparisons, extract/split the relevant changes into reviewable commits and validate those combinations; the existing test result covers the complete candidate set, not every subset. Never apply the combined diff in addition to its constituent patches.

[validation.json](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/validation.json>) records all nine PR gates passing in the isolated checkout: **1,516 JVM tests, zero failures/errors/skips**. Eleven tests were added for selection gating, URL policy, startup cancellation and solver-use accounting. The first full run caught a resolver-provenance regression; it was corrected and the complete lane rerun. [patch-validation.json](</C:/Volume D/Riff/artifacts/idle-forensics-20261101/patch-validation.json>) confirms that sequential patches reproduce all 26 tested candidate files. These results do not cover a phone startup benchmark or the future work specified here.

## 7. Related follow-up work and boundaries

- [ProviderStreamResolver.kt:160](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ProviderStreamResolver.kt:160>): process-owned extraction flights have a longer lifetime than their source consumers. Investigate reference-counted ownership or explicit selected-request cancellation; do not cancel a flight still needed by another consumer. This may reduce rapid-switch waste but is not established as the ten-minute cause.
- [PlaybackGateway.kt:129](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlaybackGateway.kt:129>): one pre-connection `pending` slot can overwrite earlier commands. Define last transport intent separately from queue-changing operations; add a cold-connect sequence test. Do not promise FIFO semantics the current code lacks.
- Keep foreground orchestration, stream provenance/recovery and output observation behind explicit service/provider boundaries. Delay a large architectural rewrite until the measured startup fix is isolated.
- The signed-in normal-song route uses direct audio. SABR's blocking channel-read behavior is a separate follow-up if a failing trace actually uses SABR, not an assumed cause for this reproduction.
- LocalBackup's ten-minute minimum interval, SolverHealth's crash window/cooldown, daily feed windows, six-hour session checks and thirty-minute extractor config TTL are not proven normal ten-minute playback blockers. Keep them out of the primary fix unless a trace shows their involvement.
- No universal root cause, API-key exposure, database corruption, memory leak or infinite retry loop has been established by this idle investigation. The original five-pillar review request is broader than the completed idle investigation; unrelated modules need their own evidence before findings are added here.

## 8. Completion checklist

- [x] Owner reproduction clarified, including mini-player origin and signed-in state.
- [x] Exact resume branches, two ten-minute boundaries and confirmed timer race documented.
- [x] Candidate source, patches, sanitized diagnostics, pinned dependency evidence and analyzer prepared.
- [x] Complete existing candidate locally passed all nine PR gates and 1,516 JVM tests.
- [x] Close diagnostic timestamp/correlation/output-endpoint limitations (single-tap capture contract; absent native endpoint explicitly unavailable).
- [ ] Capture and classify an unchanged signed-in phone baseline.
- [ ] Attribute the delay using measured stages and choose minimal fixes.
- [ ] Validate independently staged fixes and remaining regression cases.
- [ ] Meet an agreed device latency target without correctness/resource regressions.
- [ ] Promote tested changes into production source with current evidence and required checks.

This plan is the consolidated execution order. The forensic report and bundle remain supporting evidence; neither represents a deployed or physically measured fix.
