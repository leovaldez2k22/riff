# Animation upgrade validation — 30 September 2026

## Delivered

Detailed implementation brief: [animation-upgrade-prompt.md](animation-upgrade-prompt.md), approximately 2,650 words, grounded in the current Kotlin sources, real provider/local content states, and historical device evidence. It separates this upgrade from proposed follow-on work.

Signed APK: `artifacts/animation-120hz/youtube-music-glass-120hz.apk`.

SHA-256: `cc282e775052f19c1be6f0877f69cb2dfeafdcdecae9e16ec66cedb333e549e0`.

## Implementation

- `MainActivity.kt` requests a supported refresh rate at the current resolution, preferring nominal 120 Hz and falling back to the fastest available lower rate. It renews on resume/focus and clears on pause. It does not change the phone's system refresh settings.
- `GlassMotion.kt` contains the frame-rate target and smooth alpha-ramp helper. Animation durations remain time-based. The existing Compose frame clock continues to drive transitions.
- `RiffApp.kt` and `NowPlaying.kt` add 120 Hz drawing hints to the app root and separate queue/library sheet content.
- Derived player/chrome visibility thresholds and child progress readers prevent morph progress from directly invalidating the app root each frame. The morph's own layout and the full-player content still have further opportunities for draw-phase optimization.
- `PlayerMorphController.kt` joins canceled settling jobs, bounds progress to 0–1, carries final drag progress across conflated commands, and supplies normalized release velocity to the settle spring.
- The player content and artwork use coordinated smooth handoffs; the final handoff spans progress 0.88–1.0 instead of the former narrow 0.96–1.0 text and abrupt 0.98 artwork thresholds.
- Favorites crossfade using the shared motion policy and retain one semantic label. Reusable surface press feedback places the drawn transform inside the click modifier.

## Build and regression checks

Final `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease --offline --console=plain` run passed. Log: `artifacts/animation-120hz/build.log`.

20 unit tests passed with zero failures/errors, including four refresh-rate policy cases and five player gesture cases. The new cases cover nominal 120 Hz, 60/90 Hz fallback, avoiding unnecessary 144 Hz selection, invalid/absent rates, release before the last drag is consumed, and out-of-range drag positions.

Android lint: zero errors, 37 warnings. Existing Gradle/Kotlin compatibility and compile-SDK support warnings remain. The APK passed `apksigner verify` and was installed with `adb install -r` using the existing release key; app data was preserved. Activity startup after installation succeeded in 601 ms in one ADB launch sample.

## Device observations

Device: iQOO Neo 7 Pro / I2217, 1080 × 2400, Android 16. The device exposes nominal 60, 90, and 120 Hz modes.

After installation, the focused window was `com.musicglass.personal/.MainActivity`; its window requested 120.00001 Hz. `dumpsys display` reported an active 120.00001 Hz display mode. The recent frame-stat ring recorded frame intervals around 8.286–8.291 ms. This establishes high-refresh display/frame scheduling for this sample, not 120 unique successful presentations every second.

The expanded player exposed close, seek, transport, favorite, volume, lyrics, output, and queue controls. A final player screenshot was captured and visually inspected; the artwork, title/artist, seek, and controls were present. A static screenshot does not verify every intermediate frame of the morph.

An indicative warmed loop exercised 12 tab changes and nine tap-open/back-close player cycles using ADB input with 480–500 ms pauses. Aggregate `gfxinfo` results:

| Metric | Observed |
| --- | --- |
| Frames rendered | 1,439 |
| Janky/missed-deadline frames | 41 (2.85%) |
| Median reported render duration | 11 ms |
| p90 / p95 / p99 | 15 / 20 / 30 ms |
| GPU median / p90 / p95 | 5 / 9 / 11 ms |
| Active display refresh | 120 Hz |

The total duration includes pipelined rendering/GPU/queue work and should not be treated as a direct per-frame CPU budget. Android's deadline allowance varied in the recent rows. The aggregate stats cover the loop; the detailed frame-stat ring retains only its latest 119 valid rows. Evidence: `release-framestats.txt`, `device-metrics.json`, and `refresh-window.txt` under `artifacts/animation-120hz/`.

Separate on-device checks passed: short slow drag returns to mini; long slow drag opens full player; fast downward fling closes it. Evidence: `gesture-checks.json`. The app was returned to the mini-player/Home state.

## Limits and next work

120 Hz support is enabled and observed. Sustained 120 fps with every animation meeting its presentation opportunity is **not established**: this sample still had 2.85% missed deadlines. There is no comparable before/after baseline for the current source and no Perfetto FrameTimeline presentation trace. Do not compare these numbers directly with the historical rc3 60 Hz loop.

Mid-gesture reversal, simultaneous track changes, keyboard/inset motion, large text, RTL, TalkBack traversal, platform/in-app reduced-motion behavior on-device, long thermal/memory behavior, and all separate popup/dialog rendering paths were not exhaustively exercised in this pass. The detailed prompt specifies the next engineering and design checks, including narrowing full-player recomposition, retaining artwork ownership, stable loading layouts, truthful seek interpolation, and shader/offscreen profiling.

Source backups and a reviewable diff are in `artifacts/animation-120hz/source-before/` and `source-changes.diff`.
