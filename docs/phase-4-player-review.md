# Phase 4 player review — 28 September 2026

## Current implementation

- Twelve scheduled ambient palettes blend three colors at the top and fade to AMOLED black. Album pages use a full-width artwork hero with a fade into artwork-derived track-row colors.
- The mini-player artwork fits inside its capsule. The scrolling content has no black fade mask behind the floating chrome. The tab bar stays full size; its selector lifts while dragged and settles on the released tab.
- Mini and full player animate between measured bounds. Swipe up on mini to open; swipe down on player or Close to return. Mini stays hidden until the reverse animation completes.
- Seek previews remain under the user's finger while scrubbing. Controls have reactive outlines, color, and press motion. A horizontal system-volume slider and Android's output picker show the current phone or Bluetooth route.
- The Media3 demo queue supports local reorder and removal. Queue and lyrics open above the player; queue can be dragged from partial to expanded position. Demo lyrics have timed lines, line-tap seeking, and an active-line highlight.

## Build and device evidence

assembleDebug and lintDebug passed after the final Home cleanup. APK: app/build/outputs/apk/debug/app-debug.apk.

The iQOO Neo 7 Pro (Android 16) was checked through USB debugging. M04-refresh/player-final.png shows the controls, volume slider, and current Bluetooth route without clipping. M04-refresh/queue-half.png and queue-full.png show the two queue positions. M04-refresh/nav-drag2.png shows the selector lifted during drag; nav-settled2.png shows Library selected after release. The system output picker displayed the connected Noise Airwave Max XR device and This phone as choices in output-picker-confirm.png. The full-bleed album hero and player-to-mini return were captured. The earlier Home capture revealed a bright card behind floating controls; that card was removed and the APK rebuilt.

Earlier M04 checks captured queue reorder, lyric seeking, and progress after scrubbing. Queue order survived an app reinstall in that test.

## Final Phase 4 device check

The revised mini-to-player and player-to-mini gestures were recorded in `M04-final/m04-final-morph-revised.mp4` and inspected frame by frame with `morph-revised-sheet.png`. The mini-player is present during the return transition and the transport icons move with the gesture. The latest landscape build was installed and captured in `M04-final/landscape-final.png`; Home's scrollable content is clipped above the bottom controls in the short viewport. Portrait orientation was restored after the check.

Removing the third queue item changed the saved order from `1,0,2,3` to `1,0,3`, which remained after an app restart. The original order was restored afterward. The queue was also inspected at collapsed, half and full positions (`queue-collapsed.png`, `queue-half.png`, `queue-full.png`).

The demo lyrics reached the second timed line after seeking to the start and playing for four seconds (`lyrics-playing.png`). The active line remained fixed after pausing for two seconds (`lyrics-paused.png`). Back from lyrics returned to the player. The original playback preferences were restored. No app crash or ANR appeared in the checked logcat window. `assembleDebug` and `lintDebug` passed on the final landscape build.

The Phase 4 player and demo-lyrics gate is complete on the iQOO Neo 7 Pro. Provider streaming remains an experimental Phase 3 path and is not established by these demo-track checks.

## Player and lyrics refinement

The 28 September follow-up replaces the boxed Now Playing art with a full-width upper hero that fades into the controls. Transport and secondary player icons have no circular fill or outline. Mini-to-full and full-to-mini now use drag-driven morph progress; play and Next icons travel between their two positions while the surrounding layout stays fixed. Player scrolling is disabled until expansion completes, preventing the opening swipe from scrolling away the header. Lyrics moved from a draggable modal sheet to a stable full-screen surface.

Device captures: `M04-refresh/player-fullbleed-fixed.png` shows the hero and redesigned icons; `M04-refresh/lyrics-fixed-final.png` shows lyrics after an upward swipe without the top gap; `M04-refresh/player-down-fixed.png` shows the mini-player after a downward swipe from the artwork. The continuous motion recording and final landscape capture are listed above.
