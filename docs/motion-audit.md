# Motion audit (code inspection, 2026-09-29)

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

No device or emulator frame trace was available for this audit. The observations below describe code behavior; visible stutter, frame drops, and shader cost remain unmeasured hypotheses.

| Surface | Existing behavior | Assessment |
| --- | --- | --- |
| Player morph | A 470 ms sheet effect and separate 260 ms gesture settles target one `Animatable`; drag callbacks cancel and launch a job per delta. | Confirmed competing ownership and coroutine churn in code. Visual jank is unverified. |
| Mini and full player | Chrome disappears at a progress threshold; mini contents fade with 12× progress, full contents appear after 30% progress; duplicate mini contents and artwork are composed during the handoff. | Confirmed discontinuous visibility and duplicate composition. Actual flash is unverified. |
| Navigation | Selector width, position, lift, and scale each use separate state animations; dragged position still animates behind the finger. | Confirmed lag mechanism in code. Perceived lag is unverified. |
| Player controls and seeking | Buttons scale their full touch area to 88%; seeking holds a preview until a 1.6 s polling loop sees the target position. | Confirmed touch-area scaling and delayed reconciliation. Seek stutter is unverified. |
| Playback state | A new full `PlaybackUiState`, including a rebuilt queue, is published every 500 ms. | Confirmed broad invalidation and allocations. Frame impact is unverified. |
| Queue and lyrics | Queue uses stable media IDs; lyric auto-scroll launches on each active line and manual drag disables following. | Stable queue identity is good. Auto-scroll can still issue unnecessary movement; visual impact unverified. |
| Browsing | Some dynamic lazy lists use positional keys, notably playlists and online search results; most rows have no press feedback. | Confirmed identity/feedback gaps. Scroll jumps are unverified. |
| Ambient/artwork | Ambient palette uses 2400 ms and artwork palette 450 ms, with separate color state animations. | Confirmed inconsistent timing. This is not itself proof of poor appearance. |
| Glass | RuntimeShader samples the backdrop 49 times for deep interior pixels. Effect creation is remembered by geometry. | Confirmed sample count and cached creation. GPU cost and overdraw need a device trace. |
| Video | One `SurfaceView` is created for a visual session; the player is released when the surface is destroyed. | Lifecycle is reasonable. Black frames on surface recreation remain unverified. |
| Accessibility | Reduced motion bypasses some animations but does not read the platform animator scale; hidden morph content remains in the semantics tree. | Confirmed coverage gaps. TalkBack behavior needs device testing. |

Validation to collect on a phone: Macrobenchmark or Perfetto FrameTimeline for opening/collapsing the player, dragging the nav selector, scrolling under glass, and paging visual sessions; record refresh rate, slow/frozen frame counts, main-thread slices, and GPU/render-thread cost. Compare the same journeys and build type before and after. No frame-rate claims should be inferred from successful builds.
