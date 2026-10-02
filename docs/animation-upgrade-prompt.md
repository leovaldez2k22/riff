# Riff: next-generation motion implementation prompt

Prepared from the current app source and local motion evidence on 30 September 2026.

## Copy-ready implementation prompt

You are a senior Android interaction designer and Jetpack Compose performance engineer. Upgrade Riff into a fluid, tactile music experience with coordinated, interruptible transitions and a 120 fps rendering target on compatible 120 Hz hardware. Work in the existing Kotlin/Compose app. Preserve its black surfaces, clear edge-refracted glass, pink accent, Inter typography, real YouTube Music content, local audio, navigation history, and accessibility behavior.

Start by reading the current code and collecting a release-build baseline. Implement changes in small, reviewable stages. Make interactions feel connected through geometry, timing, velocity, and continuity. Do not equate longer animations or additional effects with better motion.

### 1. Ground the work in the app that exists today

Use these source files as the starting map:

- `GlassMotion.kt`: shared duration tokens, non-bouncy settle spring, reduced-motion policy, platform animator-scale observer, smooth alpha ramps, and 120 Hz target.
- `PlayerMorphController.kt`: one conflated command actor owns bounded player progress. The current upgrade cancels and joins the old animation, seeds the final drag position, and passes normalized release velocity into spring settling.
- `RiffApp.kt`: shell, mini-player, full-player morph, liquid navigation selector, collapsed queue, lyrics screen, and Material bottom sheets. Player visibility now uses derived thresholds; morph progress is read inside the chrome and player composables instead of directly invalidating the app root every frame.
- `MainActivity.kt`: requests the fastest supported rate up to nominal 120 Hz at the current resolution, renews the request on resume/focus, and clears the preference on pause. The system can override this request.
- `NowPlaying.kt`: artwork transitions, playback controls, seek slider, favorites, queue mutations, and lyrics availability. Favorite state now crossfades with a single accessibility label.
- `ShellScene.kt`: tab/detail transitions, chrome padding, search, library filters, online collections, account, accessibility, and on-device audio. Search and collection loading currently use text rows; search clears old rows before the debounced request.
- `HomeFeed.kt`: provider-backed shelves, recent history and favorites, a speed-dial pager, category pills, and artwork. Home clears shelves while refreshing; some shelf identities still use their index. Its horizontally scrolling shelves use eagerly composed rows.
- `PlaybackGateway.kt`: metadata and position are separate Compose states. Position is sampled every 500 ms, so the seek display is not yet a frame-synchronous visual timeline.
- `ArtworkImageLoader.kt`, `ProviderArtwork.kt`, `PlayerArtwork.kt`: cached Coil artwork loading and fallbacks, plus an offscreen destination-alpha mask for the player hero.
- `ChromeGlassEffect.kt`: cached shader source and effects rebuilt when glass geometry changes. The center stays clear and refraction is limited to edges. Category scrolling still changes effect geometry.
- `AmbientTheme.kt`: ambient palette changes and background drawing.

Treat older audit documents as historical evidence. The current app has removed demo content. Podcasts and Radio currently expose availability/exploration states; `LyricsPanel` says lyrics are unavailable. Do not invent working timed lyrics, visual sessions, stations, album routes, or fabricated provider data. Keep new animation proposals tied to real states, and gate future data-dependent interactions on an actual feature implementation.

Historical context: the rc3 phone check used a 60 Hz iQOO Neo 7 Pro and reported a 16 ms median, 28 ms p90, and 16/417 missed deadlines in an indicative ADB interaction loop. That build and workload differ from the current app. It is evidence to investigate, not a current performance baseline or proof of 120 fps.

### 2. Define one coherent motion language

Keep the current tokens as initial defaults: press 100 ms; selection 180 ms; content 220 ms; screen 280 ms; modal 300 ms; player tap 380 ms; artwork 360 ms; ambient 900 ms. Tune by observing real journeys, not by multiplying durations to match a frame rate. A 380 ms transition should take the same time on 60, 90, and 120 Hz panels; the higher rate supplies more intermediate frames.

Use non-bouncy, velocity-aware springs for gesture release and interruption. Use time-based easing for route/content fades and palette changes. Let alpha handoffs use smooth ramps with zero slope at their endpoints. Press feedback should begin immediately, compress drawn content by only about 1–2%, and release smoothly without changing the clickable target or list geometry.

Give each interaction a clear hierarchy: the element under the finger moves directly; related elements follow the same progress; secondary controls arrive later. Limit overlapping decorative motion. Avoid repeated overshoot, pulsing backgrounds, whole-screen zoom, and slow transitions that hold input hostage.

Route every custom transition through the existing motion policy. Verify whether the platform Compose duration scale and `GlassMotion.timed` are both scaling the same animation; ensure user-selected system scaling is applied once. Reduced motion must bypass spatial animation and animated decorative loading states, with state changes still fully usable. Preserve focus, touch targets, and a single semantic owner throughout handoffs.

### 3. Refine the mini-to-full player as a continuous object

Preserve one progress owner and one coherent geometry path. Carry the measured mini-player artwork, title, artist, play, and next bounds into their full-player positions. On release, seed the exact final drag progress even if the actor conflated the last drag command. Normalize release velocity by the actual gesture travel distance. On reversal, continue from current position and velocity; never restart at an endpoint.

Retain the broader smooth full-content handoff now introduced between progress 0.88 and 1.0. Artwork and shared text/control alpha must complement the moving overlay. Inspect slow scrubbing through both endpoints: no blank interval, sudden artwork reveal, duplicate bright image, or title baseline jump. Crossfading two identical opaque layers is not automatically brightness-neutral; visually verify the overlap and move to a single retained artwork owner if necessary.

Next, replace per-frame size/font measurements with a fixed measured content layout and translation/scale/clip transforms where they preserve sharp text, proper cropping, and accessible sizing. Cache mini/full endpoint bounds and invalidate them only for actual layout changes such as rotation, split-screen, font scale, and insets. Keep full controls laid out once; separate moving overlay visuals from the interactive content. Never expose both copies to TalkBack.

Use Android's velocity tracker or an equivalent time-window estimator instead of a fixed six-sample window, whose duration changes with input sampling frequency. Handle pointer cancellation distinctly from a deliberate fling. Release interrupted drags from their current position. Keep dragging available during settling; preserve nested-scroll ownership when full-player controls scroll at large font sizes.

Acceptance: slow open/close, tap open/back close, fast fling, mid-animation reversal, repeated tap/drag, rotation, and background/resume all converge to the correct state without changing playback or losing navigation.

### 4. Make navigation, keyboard, and sheets move together

The liquid selector should follow finger position without tweening behind it. Improve release so it starts exactly at the last finger coordinate and springs into the selected slot. Its shape, width, highlight, lift, and position should share coordinated progress. Inspect crossing the gap into Search and test RTL layout. Avoid a release jump from the finger coordinate to a separately animated logical index.

Tab switches should use a restrained fade; detail navigation may add a short directional translation. Derive push/pop direction from actual stack changes, including back between nested collection routes. Keep remembered scroll positions, filters, queries, and selection stable. Verify rapid navigation while the outgoing scene is still visible so it does not accidentally read incoming mutable model state.

Coordinate keyboard appearance with chrome visibility and bottom padding using the real inset animation. Remove the feeling of two unrelated animations chasing one another. Keep Search visible and focused, and avoid layout jumps when the keyboard closes.

For queue expansion and library shortcuts, preserve spatial continuity with the full player. Use one sheet state owner and drag/release behavior consistent with the player. Audit Material's built-in sheet and dropdown transitions for the in-app reduced-motion setting; the app preference does not automatically disable every library animation. All separate sheet/dialog windows need the same refresh-rate intent as the activity.

### 5. Introduce useful motion in browsing and search

Replace text-only loading rows with stable, correctly sized skeletons for Home, Search, and collections. Match final artwork boxes, row height, heading spacing, and bottom insets. Keep cached content visible during refresh; distinguish first load from refresh and pagination. For a new query, preserve layout without presenting old results as current results, and cancel stale requests.

Reveal loaded content with a subtle 160–220 ms fade. Optionally stagger only the first few visible groups by 20–30 ms, with a short total cap. Run the entrance once for that data arrival, not every time an item re-enters the viewport or a tab is restored. Reduced motion uses static placeholders and immediate replacement. Avoid a large continuous shimmer over every shelf.

Replace positional shelf keys with provider identities or stable section identities; explicitly disambiguate duplicate headings. Use lazy horizontal lists where shelf size warrants them, stable card IDs, and placement animation for actual insertions/removals. Do not animate every row on normal scrolling. Preserve the first visible item's identity and offset during refresh, filter changes, and pagination.

Add restrained press feedback to Home cards, category pills, provider song rows, and library actions. Keep targets at least Android's recommended touch size. Filter selection should animate its highlight and then transition the result group once. For collection navigation, introduce a shared cover transition only where the destination has a genuine matching cover and reliable measured geometry; otherwise use the existing route transition.

The speed-dial pager can use a small draw-layer emphasis for the active page/card, driven by pager offset, with no expensive animated blur or repeated image requests. Paging must preserve media IDs and avoid triggering playback until an actual card activation.

### 6. Improve player microinteractions and real-state feedback

Keep play/pause, shuffle, repeat, favorite, and queue feedback concise and grounded in committed state. Extend the favorite crossfade into a restrained confirmation scale/color change if useful, with no looping glow. Report persistence failures honestly and restore the previous state. Use haptics only at a meaningful committed action, respecting user/device settings.

Smooth the visual seek timeline independently of backend polling. While the visible player is actively playing, extrapolate from the last confirmed position using a monotonic clock and the actual playback speed; sample the visual state with Compose's frame clock. Keep labels at whole-second cadence where possible. Re-anchor on seek, pause, buffering, item change, service reconnect, and position discontinuity. Stop the visual ticker when the player is hidden or the activity is backgrounded. Read frame state in a narrow draw/layout scope rather than rebuilding the player, queue, or metadata at 120 Hz. Do not change service polling to a global 8 ms loop.

During scrubbing, the thumb follows the finger directly. On release, show the requested position and reconcile against a confirmed service event without snapping back to the old sample. Bound preview lifetime and handle failed/rejected seeks. Keep progress truthful during buffering and pause.

On track changes, retain the outgoing cover until the next image loads, then crossfade once. Prefetch only likely adjacent artwork with bounded memory and decode size appropriate to its displayed dimensions. Avoid a placeholder flash or restarting image loads during morph frames. Align cover, title, artist, and palette changes as one transition keyed by media ID. Verify large artwork during simultaneous player drag and track change.

Queue reorder/removal should retain stable entry identity, animate placement, and preserve the active track marker. Duplicate media IDs can exist in a queue; use stable occurrence identity rather than assuming media ID uniqueness. Avoid replaying entrance animations for the entire queue after one mutation.

Lyrics currently have no timed data. Keep the unavailable state honest. If real lyric timing is added later, use a brief active-line color/weight transition and gentle auto-scroll, suspend following during manual scrolling, and provide an explicit resume action.

### 7. Make the 120 fps target an engineering budget

120 Hz gives roughly 8.33 ms between presentation opportunities; 90 Hz gives 11.11 ms and 60 Hz gives 16.67 ms. Request 120 Hz through the current window preference and Compose `preferredFrameRate` drawing hints, including separate modal windows. Let unsupported hardware use a supported rate at unchanged resolution. Respect battery saver, thermal policy, and the user's display setting. A refresh preference cannot guarantee the app presents 120 unique frames per second.

Keep existing Compose animations frame-clock driven. Do not add fixed-step timers, busy loops, forced system refresh settings, or double animation speed. Static screens should not render 120 frames per second just to satisfy a counter. Video retains its real source cadence; UI smoothness does not create missing video frames.

Profile composition, measurement, rendering, and GPU work independently. Move high-frequency state reads into `graphicsLayer`, lambda offsets, or draw callbacks where appropriate. Split frequently animated components from content and metadata. Remember reusable objects and brushes, retain artwork requests, and avoid per-frame text measurement or queue/list reconstruction.

Measure the full-screen glass effect and artwork offscreen compositing during scrolling/morphing. Keep clear-center edge refraction; do not restore the former 49-sample interior blur. Bound shader coverage and eliminate redundant effect creation where a measured alternative preserves live glass. Prefer a cheaper effect variant based on sustained measured rendering cost rather than silently changing visual style based only on refresh-rate labels. Preserve the existing solid-controls choice.

Add a Macrobenchmark journey suite and Baseline Profile for real startup, scrolling, navigation, search, and player interaction. Use a profileable release-like build. Keep provider network timing and artwork cache conditions explicit so repeated tests are comparable.

### 8. Verification and delivery

Run unit tests, debug assembly, release assembly, and Android lint. Add meaningful regressions for final drag-position delivery, interrupted settling, refresh fallback, duplicate queue identity, and playback position reconciliation as those changes are implemented.

On the connected 120 Hz-capable phone, capture active physical refresh rate and app render cadence during interaction. Use Perfetto FrameTimeline and release-build frame metrics, including frame duration percentiles, missed presentation deadlines, main/render thread slices, GPU time, thermal state, and memory. Compare identical warmed journeys before and after. Record 60, 90, and 120 Hz where the user's device policy permits them; do not force system settings without explicit authorization. Do not use a 30/60 fps screen recording alone as proof of 120 fps output.

Test: rapid tab switching; Search keyboard open/close; first load and refresh; pagination; long scrolling under glass; speed-dial swipes; repeated skips with slow cover loads; seek while playing/paused/buffering; player reversals; queue reorder/removal; sheets; cold start; background/resume; large text; RTL; TalkBack; in-app reduced motion; and system animator scale disabled or slowed.

Treat sustained 120 fps as achieved only with observed 120 Hz rendering and an agreed measured deadline/jank threshold across representative journeys. Use an initial goal of at least 99% on-time presented app frames in each warmed journey and no frozen frames, then report any misses honestly. A p95 duration under 8.33 ms is a useful diagnostic, not a substitute for FrameTimeline presentation evidence. If the OS keeps the phone at 60 Hz, report that limitation and validate source/build behavior without claiming a 120 fps pass.

Deliver the implementation, a file-by-file summary, an updated motion inventory, the signed APK, and the actual verification evidence. Mark each proposal as implemented, deferred, or dependent on missing data. Keep performance claims separate from visual design decisions and build success.

## Applied in the current upgrade

- Added a supported-rate window preference, targeting nominal 120 Hz with lower-rate fallback and lifecycle renewal/reset.
- Added 120 Hz Compose drawing hints to the app root, player/queue content, and library shortcut sheet.
- Isolated morph progress reads from the app root using derived visibility thresholds and child progress readers.
- Added bounded, velocity-aware player settling with exact final drag progress and joined cancellation.
- Replaced abrupt player content/artwork handoffs with coordinated smooth ramps; broadened the final crossfade.
- Added favorite-state crossfade while retaining one accessibility label, and moved reusable press transforms inside the click modifier.
- Added refresh fallback and gesture release/bounds regression checks.

Remaining improvements above are the next implementation brief. Current measurements and limitations are recorded separately in `animation-120hz-validation.md`.

## Platform references

- [Android window refresh preferences](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredRefreshRate)
- [Android and Compose adaptive refresh-rate guidance](https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate)
- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
