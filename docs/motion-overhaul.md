# Motion overhaul validation

This section records the original offline implementation pass. For subsequent rc2/rc3 phone measurements, see [phase-9-device-review.md](phase-9-device-review.md). The rc3 clear-glass shader removed the 49-sample interior blur after an on-device clear/solid comparison; the short measured loop improved from 22 ms median and 31/544 missed-deadline frames on rc2 clear glass to 16 ms median and 16/417 on rc3 clear glass. The runs are indicative, not a controlled trace.

The before-change inventory is in `motion-audit.md`. This pass changed animation ownership and state invalidation rather than lengthening existing transitions.

| Journey | Before (source evidence) | After (source evidence) | Device result |
| --- | --- | --- | --- |
| Mini/full player | 470 ms sheet tween, 260 ms gesture tween, and a cancel/launch job per drag delta | One `PlayerMorphController` actor receives drag positions and interruptible settles; release uses distance and sampled velocity | Not tested on a phone |
| Player handoff | Duplicate mini contents and an early 12× fade | Measured mini bounds feed the morph; one overlay carries artwork, title, play, and next until full content takes over; hidden full controls have no accessibility semantics or touch input | Not visually verified |
| Tab selector | Four independently timed values; finger position animated | One logical coordinate determines position and width; finger sets position directly | Not visually verified |
| Seek | Drag target held in a polling loop up to 1.6 seconds | Drag preview is direct; release sends the seek and displays reported playback position | Not tested with playback |
| Playback ticker | Full state and queue published every 500 ms | Position is separate Compose state; metadata publishes only when changed | Code path verified by tests/build, frame impact unmeasured |
| Browsing | Several changing lists used positional keys; online search collapsed while loading | Stable keys and item motion for selected dynamic lists; matched-size online search placeholders | Not visually verified |
| Reduced motion | Incomplete coverage of custom transitions | Shared policy includes in-app preference and system animator duration scale; player, navigation, palette, artwork, list, lyrics, and route motion honor it | Device settings and TalkBack not verified |
| Glass/video | Effect wrapper could be recreated during composition; video active setter always synced | Compose render-effect wrapper is cached by effect identity; video only syncs on active changes | GPU cost and video frame continuity unmeasured |

Offline checks: `:app:testDebugUnitTest` (10 tests, 0 failures), `:app:assembleDebug`, and `:app:lintDebug` passed on 2026-09-29. Lint reported 0 errors, 50 warnings, and 3 hints across the project. The gesture tests cover fling override, nearest-end settling, and an interrupted transition in reduced-motion mode.

No phone or emulator was connected during this pass. There is no before/after frame trace, screen recording, refresh-rate measurement, or TalkBack verification. The source inspection confirms competing animation jobs and broad state invalidation in the old code; it cannot confirm which one produced perceptible stutter or how many frames the new code saves.

When a device is available, run identical journeys on comparable builds: rapid tab changes, Search keyboard entry/exit, album/artist/playlist/podcast navigation, slow and reversed player drags, repeated skips, seek during playback, queue and lyrics, long scrolling beneath glass, and visual pager swipes. Reset and capture `adb shell dumpsys gfxinfo com.musicglass.personal framestats` for each journey and capture a Perfetto FrameTimeline trace, including main and render threads. Record refresh rate, slow and frozen frame counts, frame duration distribution, shader/render thread time, memory, and thermal state. Repeat at large font size, with TalkBack, and with both in-app and system reduced motion. Treat the 60/90/120 Hz frame budgets as 16.7/11.1/8.3 ms respectively.
