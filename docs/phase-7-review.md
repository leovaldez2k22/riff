# Phase 7 adapted visual media review — 29 September 2026

The user chose to keep Samples removed and adapt the planned richer-media work to the existing Podcasts and Radio tabs. Radio has an “Explore visual sessions” entry; each original podcast episode has a “Watch visual preview” entry. These are clearly labeled silent original previews over a generated blue/plum/teal motion loop. No YouTube Music video or feed capability is claimed.

## Implementation

- A vertical pager shows one current original track or episode with artwork, title, artist, page count, a full-track action, and a details action. One `VideoFixtureView` lives behind the pager; neighbouring pages preload only Compose metadata and artwork. The existing Glass Lab retains its bright calibration video, while the user-facing visual pager uses a new subdued original MP4.
- The video has no audio. Entering the pager pauses the Media3 audio session even while it is buffering. Leaving preserves its queue and position and shows “Resume previous audio” if playback was requested. The full-track action replaces the queue with the selected original demo track and opens the player.
- The video view pauses on app backgrounding and releases its `MediaPlayer` when detached. The navigation capsule is hidden in the immersive route; the app's Radio and Podcasts tabs remain unchanged.

## Phone evidence so far

- The visual pager rendered readable controls over the new dark motion loop. An upward swipe advanced the counter from 1/10 to 2/10 and changed metadata from Afterglow Circuit to Low Orbit. The full-track action opened Low Orbit in the expanded player.
- A first audio-handoff test exposed a buffering race: preview entry skipped the pause because the UI's `playing` flag was false during buffering. The fix pauses unconditionally and separately tracks requested playback. In the revised APK, Radio paused at 642 ms on entering visuals. Closing showed “Resume previous audio”; tapping it resumed the same queue at 845 ms.
- `:app:assembleDebug` and `:app:lintDebug` passed on the race-fixed ambient-motion build, and the APK was installed on the phone.
- The approved rapid-swipe run lasted 600 seconds and completed 756 alternating swipes with zero ADB errors. The pager and action controls remained present afterward. App PSS was 272,995 KB before the run and 184,523 KB at the end; interval samples showed no upward trend. A filtered AndroidRuntime/MediaPlayer/MediaCodec error check found no entries. The final page counter was 1/10, consistent with the alternating traversal.
- The app was backgrounded with Home, then returned to the visual pager with its controls still present. Blue Hour Sessions opened its 1/2 podcast preview; an upward swipe showed Tidal Lights at 2/2, and Details returned to the Tidal Lights episode page. The mini-player remained on paused Low Orbit after testing.
- [Radio visual pager on phone](../artifacts/phase7-ambient.png) shows readable labels and actions over the original dark motion loop.

## Scope and limits

The adapted M7 original-demo path passes its device checks: rapid paging, stable memory, a single silent video view, main-audio pause/resume, background/return, and readable controls. The existing Glass Lab established that a `SurfaceView` video cannot be sampled through the app's backdrop `RenderEffect`; the visual pager keeps clear overlays and does not claim live blur over its video surface. The video source is one original abstract loop reused behind track-specific metadata. Provider video discovery and a provider video mode remain unavailable while the unofficial YouTube Music stream transport is unsupported.
