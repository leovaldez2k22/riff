# Timed lyrics upgrade — 30 September 2026

## Provider choice

Better Lyrics supplies the finest timing available here: TTML syllable/word timing, followed by its QQ word-timed source. LRCLIB provides an independent line-synced and plain-text fallback. Better Lyrics' Kugou endpoint and the existing YouTube Music session provide additional fallback coverage.

Live public-metadata checks returned HTTP 200 from Better Lyrics TTML, Better Lyrics QQ, and LRCLIB for a known vocal recording. This confirms endpoint access for that recording, not universal catalogue coverage.

Musixmatch was evaluated; its official API requires a registered key/plan. No private tokens, scraped signing secrets, or paid provider credentials are bundled. Better Lyrics cached TTML responses need no key, while uncached TTML can return 401; the QQ/Kugou endpoints and LRCLIB can continue without one.

References: [Better Lyrics providers](https://lyrics-api-docs.boidu.dev/docs/providers), [authentication](https://lyrics-api-docs.boidu.dev/docs/authentication), [TTML format](https://lyrics-api-docs.boidu.dev/docs/response-format), [QRC format](https://lyrics-api-docs.boidu.dev/docs/qrc-format), [LRCLIB API](https://lrclib.net/docs), [Musixmatch official SDK](https://github.com/musixmatch/musixmatch-sdk).

## Behavior

- Lookup starts when the lyrics screen opens. Better Lyrics, LRCLIB, and YouTube fallback can run independently; requests to each public host are serialized and throttled. Initial results can upgrade to finer timing without reverting to a lower-quality result.
- Standard LRC, enhanced LRC, TTML, QRC, YouTube timed rows, and plain text map into one document model. Supplied timestamps drive word/syllable highlights; line-only data remains line synced. No word timestamps are invented from sentence length.
- LRCLIB candidates must match normalized title and artist and a recording duration within three seconds. Official video/audio/lyrics suffixes can be normalized; live/remix/version names are preserved. Album and duration are sent when available. Better Lyrics match confidence is checked when supplied.
- The redesigned full-screen view shows the current lyric, smoothly follows it, permits manual browsing with a Follow lyrics action, and seeks when a timed line is tapped. Playback controls remain available.
- Fine highlighting reads MediaController's current position through a lifecycle-bound Compose frame loop while the visible timed view is playing. Word sweeps happen in drawing, retaining measured text and glyph paths; no global 8 ms backend polling was added.
- Pause, seek, item change, and backgrounding stop or re-anchor timing. A ±5-second timing control changes only lyrics alignment; tapping a line accounts for that adjustment.
- Source and sync quality are visible. Instrumental, no match, connection failure, loading, and plain-text results have explicit states.
- Cached best results can be reopened without network access for seven days. The app keeps at most 80 lyric documents under Android's private cache directory. Missing matches are cached briefly in memory; Retry bypasses cache. Track changes cancel old requests. Provider 429 responses honor Retry-After.
- Third-party lyrics requests contain public song metadata only. They share no account cookies with the existing YouTube session.
- In-app reduced motion disables line-scale animation and animated auto-scroll, and uses a static loading view. TalkBack reads stable line text; word drawing does not announce every frame.

## Changed files

`LyricsModels.kt`: data model, normalization, matching, timestamp parsers, active-line lookup.

`LyricsRepository.kt`: independent fallback lookup, quality ranking, cache, bounded response reads, cancellation, and throttling.

`LyricsPanel.kt`: lyrics design, word drawing, follow/seek, timing adjustment, source attribution, and lifecycle timing.

`PlaybackGateway.kt`: album metadata and current-position reads; seek immediately refreshes position state.

`YouTubeMusicProvider.kt`: lyrics-tab lookup through existing next/browse integration.

`RiffApp.kt`, `NowPlaying.kt`: repository lifecycle and replacement of the unavailable-only lyrics panel.

`LyricsTest.kt`: sixteen new regression tests. The release is version 0.9.0-rc6, code 7.

## Validation

36 unit tests passed (16 lyrics tests). Debug assembly, final unit tests, debug lint, release vital lint, and signed release assembly passed. Debug lint reports zero errors and 37 warnings. APK signature verification passed; version 0.9.0-rc6 (code 7) was installed successfully over the existing app on the connected iQOO Neo 7 Pro running Android 16.

A real recording, Blinding Lights by The Weeknd, returned word-synced lyrics from Better Lyrics. On-device checks cover visible provider attribution, active word highlighting, manual scrolling and Follow lyrics, timing adjustment, play/pause, line seeking, and cached reopening. A paused line tap changed the actual media-session position from 102,480 ms to 27,395 ms. The final build repeats the lyric UI controls and cached reopening checks. A regression test also verifies that reopening during a silent gap centers the last started line without highlighting it.

The lyrics view requests the existing 120 Hz motion policy and uses the display frame clock for word drawing. A short on-device rendering sample is recorded under `artifacts/lyrics-upgrade/lyrics-framestats.txt`; this is an indicative sample, not a guarantee of sustained 120 fps. The display, Android scheduler, and workload determine delivered frames.

Evidence: `artifacts/lyrics-upgrade/unit-tests.json`, `device-checks.json`, `seek-check.json`, `build-release.log`, and the captured screenshots. Signed APK: `artifacts/lyrics-upgrade/youtube-music-glass-0.9.0-rc6.apk`.

## Limits

Word/syllable coverage depends on the recording and provider. Uncached TTML may require a key that the upstream currently does not issue publicly; other sources continue. Provider timestamps may differ for music videos or live edits; duration matching and manual adjustment reduce mismatch but cannot fix missing or inaccurate data. This is supplied-timestamp karaoke, not audio recognition or forced alignment.

Offline cache can be evicted by Android. The timing adjustment is retained in the screen's saved state rather than permanently per track. Translations and romanization are not displayed in this version. Simultaneous vocal lines are retained but only the most recently started active line receives focus. Long words wrapping across multiple display lines use the supplied word interval for one glyph-path sweep.
