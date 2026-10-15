# Riff rc9: Android 12 and playback/UI fixes

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

Version 0.9.0-rc9, version code 10. Completed September 30–October 1, 2026.

## Changes

- Android 12 / API 31 is now the minimum. API-specific audio permissions and output routing are guarded. Android 12 uses translucent glass and native masked frosted blur; liquid refraction uses AGSL on Android 13 and newer. The blur mask uses quarter-resolution alpha geometry to reduce allocation.
- Album and playlist tracks form the complete ordered collection queue. Selecting track four retains tracks one through three before it and the remaining tracks after it. Collection shelf continuations, including YouTube's modern appendContinuationItemsAction pages, are loaded; recommendation/radio continuations cannot enter that queue. Explicit repeated playlist entries retain their positions. Queues support up to 2,000 tracks, with a visible error for larger collections. Large command payloads pass through an app-private temporary file to avoid Binder limits.
- Playlists share albums' immersive artwork, gradient background, centered metadata, Shuffle/Play/Save controls and numbered track list. Rows are 48 dp tall, album/playlist song titles are 16 sp and numbers are 14 sp. Shared catalog song titles are 14 sp and home card titles are 13 sp.
- The inline queue owns its vertical scrolling. Dragging its list no longer collapses the player, including at list boundaries. Automatic recommendations reject duplicate IDs and matching audio/video versions of the same recording. Restored automatic queues are cleaned while retaining the current recording and position; authored collection repeats remain intact. Play next moves an existing queued recording instead of adding it twice.
- UTF-8 text was repaired in affected sources. Separators, remaining-time minus signs, lyrics and playlist metadata display correctly.
- Home and Search glass category labels render above the backdrop effect. Search pills use matching rounded geometry and separate touch targets. Scrolling shelves use the full viewport without the old edge masks or inset clipping.
- Search history persists the latest 30 queries, with individual removal and Clear all. Double-tap Search still focuses the rounded field and opens the keyboard.
- Song menus use rounded translucent panels, compact rows, trailing rounded icons, separators and destructive accent colors. Existing functional queue, album, artist and YouTube Music favorite actions are preserved.
- Startup uses moving colored blooms, orbital highlights, a damped scale/rotation settle and a soft exit. Reduce motion and disabled system animations skip it.
- English lyric translation uses ML Kit language identification and on-device translation. Auto-detection and manual source language selection are available. Repeated lines reuse translation results, and completed translations are cached. Original line/word timing remains unchanged; English text appears below the original. First use downloads the required models. Google receives SDK performance/usage metrics; lyric processing stays on the device. The UI, README and notices state this. See [Google ML Kit terms](https://developers.google.com/ml-kit/terms).
- Radio, live streams and song videos open in the full player with a black background. Live playback first attempts a native YouTube transport; when that transport is unavailable, a visible YouTube renderer remains inside the player. It pauses when hidden or backgrounded, and the app's transport button controls it. The fallback renderer is embedded YouTube, rather than a native Media3 decoder. Provider availability and playback restrictions still depend on YouTube.

## Verification

The signed release build passed 64 unit tests with zero failures/errors/skips. Android lint reported zero errors and 51 warnings. The release was installed over the existing phone app, preserving its account and settings.

On the physical phone, queue scrolling remained open through repeated upward/downward gestures at its boundaries. The final Chill Music check selected song four, produced a four-item paused queue at 4.403 seconds, and displayed queue row indices 0–3. Its History view retained the first three songs in the collection's order. The source rows, session state and history labels are recorded in `artifacts/bugfix-rc9/verification.json`. The final Favourites session contains 284 queue entries after loading the modern continuation pages; the initial 100-item truncation was corrected.

Frosted Home labels and aligned Search pills were inspected in `phone-frost-labels-fixed.png` and `phone-search-pills-fixed.png`. Search history showed the stored Coldplay query. Playlist headers, compact rows and repaired text were inspected on the phone.

Chillhop Essentials Radio displayed real changing video inside the black player. The two captures `phone-live-updated.png` and `phone-live-updated-later.png` differ in 293,937 of 648,000 sampled video pixels by more than 15 color levels, and the sampled player background pixel is RGB (0,0,0). The app transport changed from Pause to Play after tapping it. Measurements are saved in `live-frame-measurements.json`. Earlier unsuccessful attempts remain in the chronological verification log and do not describe this final result.

An Android 12 / API 31 emulator launched the app and rendered frosted controls. Scrolling a paused 60-track test queue changed the visible rows while keeping the queue open; evidence is `android12-queue-scroll.png`. An on-device instrumentation check translated Spanish lyric lines into “I love you more than yesterday” and “Dance with me under the moon”, verified unchanged timing and reused cached results. Its output is `translation-device-test.txt`. The emulator's YouTube Music Home request was region-restricted, so actual music playback on Android 12 was not verified. The initial emulator System UI ANR was resolved; it was not an app crash.

Animation frame scheduling requests 120 Hz on supported displays. This does not guarantee 120 rendered frames per second on every device. Apple SF font files were not bundled; the existing compatible rounded font remains.

## Final phone check

After USB reconnection on October 1, the phone's current saved Lofi Girl broadcast and one-item queue survived force-stop/reopen. The same item remained ready in the mini player, with a Play action, no autoplay and a zero-millisecond position difference. Media3 reports NONE while this restored queue is intentionally unprepared; it resolves the stream only when playback is requested. Verification: `final_restore` in `artifacts/bugfix-rc9/verification.json`.

The temporary rc9 XML/PNG capture files are removed after each inspection, and the final check found no leftovers. The phone was put to sleep with the power key and confirmed Dozing with the keyguard showing.
