# Phase 9 offline accessibility and resilience pass

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Status: **offline implementation complete; M9 DEVICE_CHECK_PENDING**. The user chose to defer USB and phone-dependent Phase 9 work. This review records implementation and automated checks only. It does not claim TalkBack, visual scaling, thermal or frame-pacing results.

## Implemented without the phone

- Library → Display and accessibility provides persisted **Solid, high-contrast controls** and **Reduce motion** switches. Solid mode disables the scene glass shader and gives the mini-player, tab bar, selector and morph surface opaque backgrounds. The default clear-glass look remains available. Reduced motion snaps ambient/artwork color changes, the player open/close transition, tab selector and button micro-animations, and substitutes static artwork for the silent visual-session loop; drag gestures still track the finger.
- Screen titles and section titles expose heading semantics. Screen titles request focus when their route appears, and the tab selector exposes a Tab role. Track rows and Speed Dial page controls have explicit actions/labels. These need TalkBack traversal and focus verification on the phone.
- Demo track rows and online saved rows can grow vertically. The player allows scrolling at large font scales and reserves more control height; the mini-player hides its secondary line at 180% or greater to keep the title and buttons usable. Exact 200% layout still needs capture on the phone.
- Raw touch coordinates in the liquid tab bar now map correctly in RTL; Speed Dial drag direction and arrows adapt to RTL. Logical Compose offsets were retained for the player morph. RTL screenshot and gesture checks remain pending.
- Saved navigation values are bounded and unknown routes are dropped. Legacy comma-separated demo IDs and queues are parsed with bounds checking; invalid repeat values fall back to Repeat All. Wrong-typed podcast positions, offline-copy metadata and display preferences fall back safely rather than crashing on read. These changes keep the existing on-disk format and add no database migration.
- The resolved release runtime graph has 206 dependencies. `dependency-license-audit.csv` records each coordinate and license source: 202 POM declarations and four verified against upstream Guava/SLF4J sources. No account or network request was needed for the local graph audit.

## Automated verification

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes: seven unit tests across four suites, with no failures or errors. The focused tests cover malformed/restored shell state, prior Samples tab mapping, legacy queue parsing, repeat fallback, RTL tab hit testing and existing activity ranking logic. A passing build proves compilation and these logic contracts; it does not prove visual focus or rendering behavior.

## Device work still required for M9

- Walk Home, player, lyrics, Podcasts, Radio, search and settings with TalkBack; verify spoken labels, tab selection, focus restoration after Back/sheets, and keyboard navigation.
- Capture 200% font scale and RTL on the iQOO Neo 7 Pro; fix any clipping or reversed morph/gesture defects found. Test reduced motion and solid controls after app restart.
- Profile a release-like build for startup, shader/overdraw, scrolling, player transitions, memory retention and thermal response. Add an app-specific Baseline Profile after those user journeys are stable.
- Test process death/restart, missing media, denied permissions, offline/slow network and a long mixed-use soak. Keep the known YouTube Music SABR `RELOAD_PLAYER` playback failure in the defect list.

M9 cannot pass until the real-phone checks above pass. The Phase 10 release candidate may be built offline, but its fresh-install and upgrade gates remain pending too.
