# Phase 9 device check — 29 September 2026

Device: iQOO Neo 7 Pro (I2217), Android 16/API 36, 1080×2400 at 440 dpi, gesture navigation, active refresh 60 Hz. App: signed release variants rc1–rc3. Evidence is under `artifacts/device-checks/M09/20260929-motion-release/`.

## Confirmed results

- At 200% font scale, rc1 Home nav labels collided. Rc2 hides the visual nav labels at 160% and above while preserving icon semantics, shortens/ellipsizes the mini title, constrains section actions and hides the tiny audio-route caption while keeping its accessibility name. The rc2 200% capture showed the nav collision removed. The phone's original 1.0 font scale was restored in the same command.
- `adb install -r` upgraded rc1 → rc2 → rc3 with the same local release key; a demo favorite survived both upgrades.
- A warmed ADB loop of 12 mini/full transitions plus six tab/scroll cycles was measured with `dumpsys gfxinfo ... framestats`. Rc2 clear glass: 544 frames, 31 missed-deadline frames (5.70%), median 22 ms, p90 42 ms, 31 slow draw-command frames. Rc2 solid controls: 455 frames, 22 misses (4.84%), median 15 ms, p90 31 ms, four slow draw-command frames. Rc3 clear glass: 417 frames, 16 misses (3.84%), median 16 ms, p90 28 ms, seven slow draw-command frames. The device's 60 Hz frame budget is about 16.7 ms. These runs have different frame counts and may include ADB input overhead; they are indicative, not a controlled Perfetto benchmark.
- Source inspection identified 49 backdrop samples per interior glass pixel in rc2. Rc3 leaves the center optically clear and samples for edge refraction only. The comparative measurements support a rendering-cost improvement; they do not prove this was the only cause of jank.
- Five rc3 cold activity starts took 722, 592, 505, 484 and 397 ms. Total PSS immediately after those starts was approximately 192–196 MB.
- In a bounded five-minute scripted interaction sample, total PSS read 217, 254, 247, 248, 247 and 240 MB at successive samples. Battery temperature read 32.7°C initially and 32.0°C at the end. The app remained responsive and recent logs contained no app crash or ANR. The loop ended on an album detail page, so these actions are not a verified repeat of each intended tab path. This short, unrepresentative sample does not establish long-session leak or thermal behavior.
- Rc3 UI Automator trees confirmed Home, Podcasts, Radio, Library and Search; the expanded player exposed seek, transport, output, lyrics and queue controls. Queue expanded to numbered tracks and timed Pine Echo lyrics exposed four selectable lines.
- The app's Reduce motion switch changed from off to on, remained on after a force-stop/restart, and was restored to its original off state.

## Unverified or interrupted

- The force-RTL setting was restored to its original value after capture, but the screenshot did not show a mirrored layout. RTL gestures and layout remain unverified.
- TalkBack traversal, spoken labels, focus restoration, actual reduced-motion animation behavior, platform animator-scale behavior, high-contrast reading across all artwork, and a representative long thermal/memory soak remain unverified.
- ADB screen captures have intermittently omitted or shown a stale player layer while the accessibility tree exposed full-player controls. This limits screenshot-only judgment of player animation. Human observation or a reliable surface recording is still needed.
- A final tab smoke loop reached the Camera app during testing; those tab assertions were discarded. No crash or layout defect is inferred from that interrupted sequence.
- No Perfetto FrameTimeline trace or Baseline Profile is present. The rc3 frame loop still missed 3.84% of deadlines, so M9 is **not passed**.

The app's original clear-glass setting and the phone's original font scale and force-RTL setting were restored after the checks.
