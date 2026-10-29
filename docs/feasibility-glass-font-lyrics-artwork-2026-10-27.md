# Feasibility and implementation plan: 33 features for Riff, from Liquid Glass to Encore and Automix

27 October 2026, revised ten times up to 29 October 2026. A report and plan only: nothing in the app has changed.
- **First revision:** added scrolling song titles in the player and "See all" on artist pages, removed the lossless section at the owner's request, and added an implementation plan for every item.
- **Second revision:** added skipping the silence at the end of a song, a built-in equalizer that can open the phone's own equalizer, and a dark fade at the top and bottom of the screen. It also updates the font section with the owner's font screenshot.
- **Third revision:** added Encore, a statistics page with day, week, month, year and lifetime data, interactive charts and Instagram-style stories.
- **Fourth revision** (28 October 2026): added seven features recommended from BitChord (links, Android Auto, playback speed, stats for nerds, quality per network, sleep timer additions, Cast) and a detailed study of Automix.
- **Fifth revision** (28 October 2026): Automix became Feature 20, linked to its full design in the [AutoMix engine report](automix-engine-report-2026-10-28.md); added Features 21–31 (recommended features and UI/UX changes); added the implementation guide below.
- **Sixth revision** (28 October 2026): removed Features 21 (smooth scrolling first) and 31 (TalkBack walk-through) at the owner's request. The other features keep their numbers, so 21 and 31 are unused.
- **Seventh revision** (28 October 2026): Feature 10's equalizer redesigned after BitChord's (Dynamic tone pad and Manual faders, from the owner's screenshot); added Feature 32 (three dots during instrumental sections) and Feature 33 (dark status bar icons over light artwork).
- **Eighth revision** (28 October 2026): added Feature 34, the player redesigned after BitChord's from the owner's screenshots, keeping Riff's shuffle, repeat, X and "NOW PLAYING".
- **Ninth revision** (28 October 2026): added Feature 35, calmer and slightly slower transitions across the app.
- **Tenth revision** (29 October 2026): removed Feature 6 Stage B (motion clips from Apple Music) at the owner's request. No way to get the clips was one the owner liked, so artwork motion stays made on the phone (Stage A).
- **Eleventh revision** (29 October 2026): removed Feature 6 Stage A (cover motion made on the phone) at the owner's request, after the phone checks: the owner won't use it. With Stage B gone too, Feature 6 is dropped; the cover is still, as before it.

Based on Riff's source at 9ea71f4, frames from the owner's reference video (`WhatsApp Video 2026-10-27 at 11.24.00.mp4`), and BitChord's source ([kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord), GPL-3.0, commit 04a4a83 of 26 Oct 2026). File and line references are to Riff unless marked BitChord.

## How to use this plan during implementation

This document is the working plan for Riff's next features. Anyone implementing them, a person or an AI coding session, should:

1. **Start from the feature's own section.** Each lists, in this order:
   - what Riff has today, with file and line references;
   - the steps, with the files to create or change;
   - settings, privacy and docs to update;
   - the tests.

   Line numbers refer to commit 9ea71f4: re-check them in the current code before editing.
2. **Follow the order** in [Order of work, testing and release](#order-of-work-testing-and-release).
3. **For Automix (Feature 20), use the [AutoMix engine report](automix-engine-report-2026-10-28.md)** as the design reference: equations, filter coefficients, state machine, JSON schemas, planner rules and fallback cases.
4. **Keep the project's rules.** They're in [README](../README.md), [architecture](quality/architecture.md) and [testing](quality/testing.md):
   - one `:app` module;
   - screens get what they need from `AppContainer`;
   - every dependency added to `gradle/verification-metadata.xml`, the dependency inventory and NOTICE;
   - every new network destination added to [privacy](privacy.md);
   - `python scripts/quality/verify.py --lane pr` passing before a stage is called done.
5. **When reality differs from this plan,** for example an API that behaves differently or a measurement that misses its target, update the feature's section in the same change and say why, so the plan stays true.
6. **Owner decisions** (answered 28 October 2026):
   - **Outside services are all approved:** Cast with Google Play services (Feature 19), MusicBrainz genres for Encore (12.4), and Automix's optional model download (engine report, section 6.2, stage F). Each is still listed in `docs/privacy.md`, and the ones that send listening data stay off by default, as each section says.
   - **Encore:** offer the setting "Keep Encore totals beyond history retention", off by default (12.3).
   - **Git:** work on a feature branch with a commit per feature or small group; never push. The owner reviews and pushes.
   - **Testing:** emulators (API 31, 33, 36) and JVM tests first; ask the owner to connect the phone when a stage needs real hardware (audio, glass performance, Android Auto, Cast).
   - **Still ask first:** anything that publishes, pushes, installs over the owner's own Riff, or writes to the owner's YouTube Music account.
   - **29 October 2026, overnight:** the owner green-lit every remaining stage, asked for open choices to be decided by best fit (each logged here), and allowed downloads, the phone, writes to their YouTube Music account, and pushing `feat/next-features`. Blockers are logged and skipped.

## Implementation log

Work happens on the branch `feat/next-features`. Each entry says what was built and where it differs from the feature's section below.

| Feature | Status | Differences from the plan, and why |
|---|---|---|
| 35 Calmer transitions | Built (28 Oct) | **The check:** the static check (`motion` rule in `scripts/quality/static_checks.py`) uses the project's reviewed list (`static_checks_reviewed.json`, with a reason per site) instead of inline `// motion:` markers, which is how every other rule there works. **Setting:** a "Calm transitions" switch (on = Calm, off = Quick) rather than a two-choice row. **Kept as they were:** sign-in's designed sequence, card peek, the lyric-share hold, the loading shimmer and the navigation lens. |
| 7 Scrolling title | Built (28 Oct) | **Edge fade:** only the right edge fades; a left fade would cover the title's first letters while it rests at its start. **Lyrics view:** the marquee also stops whenever the panel isn't fully open (`interactive`), which covers the lyrics transition. |
| 8 Artist "See all" | Built (28 Oct) | **Song shelves:** reached through YouTube's link to their playlist ("VL…") and open as the existing collection screen. **No separate "See all songs" row:** artist shelves are drawn as card rows, so the heading's "See all" serves both. |
| 5 Lyric blur and fade | Built (28 Oct) | **Setting:** "Blur other lyric lines" is in Settings → Appearance, as Riff has no lyrics settings section. **Top padding:** the list's top padding grows to the fade's height, so the first line can scroll clear of it. |
| 4 lrc.red | Built (28 Oct) | **Ranking:** third, behind Better Lyrics and its QQ route, ahead of NetEase. **AI-synced flag:** not read, because the per-track `song.json` that carries it is undocumented. Revisit if lrc.red publishes its format. |
| 11 Edge fade | Built (28 Oct) | **Placement:** drawn inside the glass scene (`GlassScene` in `ShellChrome.kt`), so the floating bar refracts the darkened page. **Bottom height:** a fixed 120 dp above the navigation bar rather than the measured chrome height. |
| 32 Instrumental dots | Built (28 Oct) | **Cache:** lyrics are now cached under version 4, so every song's lyrics are fetched once more to learn which line ends are real. **Blank LRC lines:** those shorter than 4 s are now left out instead of drawn as empty rows. |
| 25 Romanized lyrics | Built (28 Oct) | **Mode:** remembered once for all songs, not per script (simpler, and a song rarely mixes scripts). **Fallback:** ICU's mechanical transliteration (`Any-Latin; Latin-ASCII`); Chinese characters in Japanese lyrics come out in Mandarin readings. **Chip:** an "Original / Romanized / Both" chip in the lyrics page's bottom row, shown only for lyrics with non-Latin lines. |
| 26 Undo for removals | Built (28 Oct; downloads 29 Oct); download Undo seen on the owner's phone 29 Oct | **Built:** queue removals put the song back at its row (a new `insertAt` in the queue command); playlist removals add it back at the end and say so. **Downloads (29 Oct):** a finished download leaves Downloads, the Library tile and Android Auto at once, and its files go when the notice ends without Undo (`UndoNotices.show` gained `expire`, run when the notice times out, is dismissed or is replaced), so a second removal commits the first. The wait is the notice's own (Material's short duration, 4 s), not the plan's 5 s, so the two can't disagree. **A download still on its way** is removed at once, without Undo: there is nothing worth keeping and its transfer should stop. **On the phone (29 Oct):** Undo brought the song back with its files; a tap after the 4 s found it gone, as meant. **Fixed there:** the Downloads header still counted the hidden song's size ("0 songs · 2.9 MB"); it now counts only the songs shown, while the download engine's storage budget still counts every file on disk. Downloading the song again during the wait keeps it. If Riff is closed during the wait the song stays downloaded, which loses nothing. |
| 12.3 Encore data | Built (28 Oct) | **Schema:** `library-state.db` version 2. **Stored:** listened time is written every 30 s and when a play ends; daily totals per song, album and artist, plus listening per hour. **Upgrade:** plays from version 1 count as plays without time, built once after the upgrade. **Source:** a stream, a download (a file or content address) or a file on the phone, read from the queue entry's address. **Setting:** "Keep Encore totals beyond history" is in Privacy and data, off by default. **Not built:** genres (12.4) belong to the Encore page stage. |
| 17 Quality per network | Built (28 Oct) | **Network check:** the resolver asks Android whether the active network is metered (`isActiveNetworkMetered`) rather than reading the container's network state. **Not built:** dropping a stream prepared ahead when the network changes before it plays. |
| 18 Sleep timer | Built (28 Oct) | **Targets:** End of this song, After 2/3/5 songs, End of queue. The last counted song's end becomes the timer's deadline, so the existing fade and the crossfade guard apply. **Fade:** off, 5, 15, 30 or 60 s, kept on the phone. **Not built:** restoring a song-counted timer after Android kills Riff (a timed one is still restored). |
| 15 Playback speed | Built (28 Oct) | **Memory:** episodes share one remembered speed rather than one per show; songs return to 1× after a restart. **Where:** the control is in the player's menu. There is no speed chip on the player yet; that comes with the player redesign (Feature 34). |
| 13 Links | Built (28 Oct) | **Song links:** resolved once, then played with the title and author the stream gives. **Open-by-default:** a row in Settings → Playback opens Android's screen; there is no first-run tip yet (Feature 29). |
| 33 Status bar icons | Built (28 Oct) | **Only the full-screen player asks.** Screens inside the shell (album, artist and playlist headers, Home) sit under Feature 11's top fade, which makes white icons readable on any picture. The player is drawn over that fade, so it measures its cover. |
| 16 Stats for nerds | Built (28 Oct) | **Where:** a dialog from the player's menu, polling a `GET_NERD_STATS` session command every second; **Copy** puts the rows on the clipboard. **Not built yet:** the "Show stats for nerds" setting and the tech line over the art, which come with the player redesign (Feature 34, 34.3). **Quality rule:** not shown; the resolver doesn't keep which rule chose a stream. |
| 22 Predictive back | Built (28 Oct) | **Pages:** a pushed page slides 15 % and fades to 80 %, but the page under it isn't drawn behind it during the gesture (keeping the previous route composed needs the navigation rework of Feature 30). **Test:** a JVM test of the preview geometry rather than a Compose test driving `BackEventCompat`. |
| 23 Lock-screen buttons | Built (28 Oct) | **Buttons:** Shuffle and Like. **Not built:** Add to playlist, because a notification button can't open an activity from the background on Android 14+ without a trampoline. **Who may press:** Riff, the notification and lock screen, Android Auto and automotive; other apps can't change likes. **Unliking from the notification:** the song isn't taken off Riff's on-phone copy of Liked songs, as the player's own Like button does; a follow-up. |
| 24 Search suggestions | Built (28 Oct) | **Finding corrected:** Riff already searched as you type (450 ms after the last key); suggestions now fill the gap before results. **API:** innertubex 0.7.2 has `getSearchSuggestions`. **Top result:** the first "All" result when it is an artist or album, as a large card that opens it; no Play or Shuffle buttons on it yet. **Setting:** in Privacy and data, on by default. |
| 27 Shortcuts and tile | Built (28 Oct) | **Shortcuts:** published from code (`ShortcutManager`), not `res/xml/shortcuts.xml`, because debug builds have a different package id that static shortcuts can't follow. **Shuffle liked songs:** the first page of liked songs. **Tile:** one tile (play/pause); no sleep-timer tile. It is a fifth exported component, guarded by `BIND_QUICK_SETTINGS_TILE`; the release-content check and `docs/quality/security-review.md` list it. |
| 28 Loading, empty, offline | Built (28 Oct) | **Found:** Riff already had shared `ListSkeleton`, `ContentEmpty` and `ContentProblem` (Q12). **Added:** `EmptyState` (icon, title, help, action), used on Downloads; Search keeps its one-line `ContentEmpty`, which its tests and screen baselines pin. Also a card-shelf skeleton in place of Home's "Loading from YouTube Music" line, and a **Downloads** button on every offline failure (through `LocalOpenDownloads`). **Not changed:** the lyrics page keeps its own loading and failure layout. |
| 34 Player redesign | Built (28 Oct), not yet checked on a screen | **Backdrop:** `PlayerBackdrop.kt` draws the 6 × 6 mesh under the whole panel (so the art's existing fade melts into it) and the blurred 128 px copy under the deck; both made once per cover off the main thread and kept for the last 12 covers; cross-fades over the ambient token (1200 ms) rather than 900 ms. **Seek and volume:** `ThinSlider.kt`, 7/12 dp and 6/10 dp, but the touch area stays Riff's 48 dp, not 34 dp. **Lyric row:** polls the position every 250 ms while shown; opens the lyrics without scrolling them to the line; hidden at font scale 1.5 and over, for live radio and video. Its setting is in Settings → Appearance, as there is no Lyrics section. **Stats line:** "Show stats for nerds" in Settings → Appearance, polling every 2 s. **Quality label:** shows the network's song quality or "Data saver"; tapping it does nothing yet. **Transport:** shuffle and repeat are white (50 % off, a dot under when on) instead of pink; Riff's skip glyphs already are two joined triangles, so they stay; previous isn't faded at a song's start. **Not done:** the art band shrinking to 35 % at large text, glass on the circles and pill (Features 1–2), and the screenshot baselines (34.7), which need the emulator. |
| 10 Equalizer | Built (28 Oct), not yet heard on a phone | **Written fresh**, not adapted from BitChord: TPT state-variable sections with Simper's mixes (`EqualizerCurve.kt`, `EqualizerProcessor.kt`), so NOTICE.md needs no new entry. **Chain:** `SilenceWatchProcessor → EqualizerProcessor → VolumeBoostProcessor`; the crossfade's tail player gets its own equalizer with the same settings. **Store:** `EqualizerSettings.kt` is a preferences file read by the service's prefs listener, not a `StateFlow`; the screen keeps its own copy. It travels in the export file. **Screen:** a Settings → Sound row and the player menu's **Equalizer** open it; the puck uses Riff's `GlassMotion.settle` spring (the motion check forbids literal stiffness) rather than 0.7 / 600; the curve glides over the selection token (260 ms) rather than 250 ms; the Dynamic/Manual control is a plain pill pair until Feature 2's glass control exists. **Phone equalizer:** opened without `EXTRA_AUDIO_SESSION`, as the screen has no access to the service's session; the panel then applies to the whole phone. **Not done:** profiles per output (10.2 step 4, optional), the UI tests for the pad and faders (10.3), which need the emulator. |
| 9 Silence at the end | Built (28 Oct), Path B only; Path A and the seek fix added 29 Oct after the phone check, Path A seen skipping on the owner's phone | **Path B:** `SilenceWatch.kt`, first in the PCM chain; the service checks every 250 ms. It **skips straight to the next song** even when crossfade is on (no 1.5 s blend into silence), and never while a blend runs. **Phone check (29 Oct, item 1 of `docs/phone-check-bugs-2026-10-29.md`): it never skipped** for the owner, on streams or downloads. Two reasons. The four downloads on the phone end with only 0.6 to 2.8 s below −50 dBFS, and Path B waits for 1.5 s of quiet measured at the decoder, ahead of what is heard, so it had almost nothing left to save. And a seek anywhere in the last minute turned the skip off for that song, so scrubbing to the end to try it could never show it working. **Fixed:** **Path A** now runs: for a song the phone holds, the tail Automix's `TrackAnalysis.kt` already reads (now also read when this switch is on, without smart crossfade) gives `audibleEndMs` (the last block above −50 dBFS, plus 300 ms), and the service skips as soon as playback passes it, when at least **1 s** would be saved (the plan's 2 s would have left three of the four downloads as they were). With crossfade on, the plain blend now ends there too. No `track_end` table: the tail is read again when the song comes up, which is cheap. **Seeking:** only a seek that lands in the silence itself keeps it (Path A: landing after the audible end; Path B: a quiet run that began at the landing). **On the phone:** "Something Just Like This", downloaded, skipped at 243.2 s of 247.6 s (`path=a`); the cut falls in the reverb's last tail, at about −49 dBFS RMS. **Streams (29 Oct, seen on the owner's phone in Riff Debug):** Path A also reads a stream's tail from Riff's audio cache (`TrackAnalysis.cachedSource`); nothing is fetched for it. It needs the start of the file (the container's header, 256 KB) and an unbroken cached run that reaches the end: the cache learns a stream's length when its download gets there, and Riff buffers 50 s ahead. A seek leaves a hole before that run, so the tail read is as long as the run allows, up to 45 s, less 3 s kept back; under 10 s, the end is left to Path B. The silence watch asks every 2 s. **What the phone showed:** the first version read nothing, for three reasons, each fixed and each now visible in the event log: (1) a song is often asked for before its stream is resolved, when it has no cache key, and that first "not yet" was never asked again; (2) reading through the cache for each of the extractor's small jumps took 45 s, so the start and the tail are now copied into memory in two reads (about 0.2 s); (3) the platform `MediaExtractor` reads a WebM file's clusters from the start, into the hole, and failed, so a cached tail is read with Media3's own extractor (`MediaExtractorCompat`, new dependency `media3-inspector` 1.11.1, Apache-2.0), which goes to the tail by the file's index. Then, on a direct WebM/Opus stream resumed 36 s before its end: `automix_analysis part=tail found=1 source=cache ms=4439`, and the crossfade was planned from it. A file more than 2 s from the player's length is not used (`reason=length`). Near the end of a stream whose tail was not read, `stream_tail_unread known=… init=… cached=… ms=…` says why. Streams the cache does not keep (live, video, HLS) still use Path B. Smart crossfade gets the same cached tails. **Not yet seen:** a SABR stream (all played here were direct), and a `silence_skip … path=a` on a stream with crossfade off. **Settings:** "Skip silence at the end of songs" and "Skip all silence" (Media3's `skipSilenceEnabled`), both off, in Settings → Sound. **Event:** `silence_skip at=… quiet_ms=… path=a\|b`. |
| 20 Automix, stage 1 | Built (28 Oct), not yet heard on a phone | **Setting:** a "Smart crossfade" switch under Crossfade, not yet the Crossfade / Smart / Automix choice, as stages 2–3 don't exist. **Analysis:** `TrackAnalysis.kt` decodes the last 45 s and first 30 s of songs that are **downloaded or on the phone**, in memory only (the last 24); streams get the plain timing with the bass swap. No `AnalysisStore` on disk yet (engine report stage A). **Plan** (`SmartTransition.kt`): as written, the later of the outro start and the setting before the audible end, 4–12 s, so an abrupt end gets 4 s and the setting caps a long outro; a quiet intro under 8 s is skipped to 1 s before the song builds up. **Bass swap:** an 11th equalizer section, a 150 Hz low shelf to −26 dB, crossed over 1 s at the midpoint, instead of a Linkwitz–Riley split. **Rules:** an album played in order and podcasts, live radio and videos blend plainly; classical waits for Encore's genres. |
| 3 Font | Built (28 Oct), not yet seen on a screen | **Font:** `InterVariable.ttf` from the official Inter 4.1 release replaces the four static files (APK about 0.7 MB smaller); hashes are in `docs/dependency-license-inventory.md`. Two families in `AmbientTheme.kt`, Text (`opsz` 14) and Display (`opsz` 32), each with weights 300–800. **Scale:** `RiffText` in `DesignTokens.kt` holds the table's styles, plus a 26 sp `headline`. Applied to page titles (`ScreenTitle`, Home, Downloads, the lyrics page), Home's section headings, the Library sheet's title, the player title (and its morph into the mini player), sung lyric lines, and Settings rows (19 sp regular, help at 15 sp and 55 %). The player title stays 26 sp (Feature 34) rather than 22 sp. **Not done:** the sweep of every remaining hard-coded `fontSize` (step 4). The existing `measures` rule in `static_checks.py` already fails on any new raw sp in a screen file and only lets the counts fall, so no second check was added. Tab labels and the 10 sp tab size are left until the glass bar (Feature 2). The JVM screenshot baselines of Search and the mini player were re-recorded (the title is the only change; large text still fits); the emulator baselines and its large-text check are still to do. |
| 1 Glass | Built (28 Oct); seen on an API 36 emulator, not yet on a phone | **Written in Riff** (`RiffGlass.kt`, `GlassQuality.kt`), as the owner chose, instead of the Kyant backdrop library, so no new dependency, NOTICE entry or verification change. **Backdrop:** `GlassScene`'s existing layer is the page every piece reads (`LocalRiffBackdrop`), at full size: no 0.5× downscale yet, as the frame cost hasn't been measured. Each piece records the backdrop around itself into its own layer and runs blur and an AGSL lens as one `RenderEffect` chain. **Lens:** the sample moves inward along the shape's normal with the quarter-circle profile; the rim and the bend stop at half the shape's short side, because the table's 56 dp bend folded the image of a 64 dp lens over itself (seen on the emulator). **Applied to:** the tab bar, the search button and the mini player; the drag lens is a glass pill lifted 6 dp over the tabs while a finger is down, magnifying them 1.2×. The category chips stay on the old shader (`bottomChrome = false`) until Feature 2. **Quality (step 10):** power saver or a thermal headroom of 0.9 gives Reduced (no colour fringe); 1.0 (Android's severe) and Android 12 give Frost; high contrast gives Solid. 0.9 for Frost, tried first, tripped on an idle emulator (0.903). Frost keeps the drag lens unblurred so the tab under the finger stays readable. No `JankStats` level yet, and no benchmark measurement. **Not done:** the collapsing bar (step 6), the merge shader (step 7), the tilt light (step 8) and the adaptive tint (step 9), all optional or depending on measurement; Android 31 and 33 emulators. `AgslNavigationLens.kt` is gone; `NavigationLens.kt` keeps only the pill geometry. |
| 2 Glass everywhere | Built (28 Oct); seen on API 31, 33 and 36 emulators, not yet on a phone | **Controls:** one file, `GlassControls.kt`, instead of `GlassSlider.kt` and `GlassSwitch.kt`. Slider thumbs are glass **while held** (1.3×, magnifying the track); at rest the settings sliders show a white pill and the player's hairlines (Feature 34) show none. The switch is white at rest and glass while it travels, 1.25× wide halfway. Each control records its own track as the thumb's backdrop. **Backdrops:** glass inside a page can't read the shell's backdrop, which is the page with the glass in it, so each kind of page records its own background: `AmbientBackdrop` (already the artwork-palette gradient the plan asks for) became `LocalPageBackdrop` for back buttons, Settings and the equalizer; the player and its queue read the blurred cover; the lyrics page its own gradient; sign-in its backdrop (`LocalSignInBackdrop`); popups keep the frosted backdrop and gain the Menu lens; snack bars sit above the page and read it. **Chips:** their faces were already drawn above the page (`GlassCategoryLabels`), so each became Button glass reading the page, and the old full-screen shader went with them: `AgslChromeGlassEffect.kt`, `ChromeGlassEffect.kt`, `ChromeGlassGeometry.kt`, their frost strips and `FrostStripTest` are deleted, `GlassScene` only records the page, and chips are no longer limited to 8. There was no merge shader to keep (step 7 of Feature 1 isn't built). The corner rule moved to `glassRadius` and `GlassTileTest` follows it. **Lens tuning:** the gallery showed a second ring inside round lenses, so the bend is now at most 0.6 of the rim (`RiffGlass.kt`). **Equalizer:** the mode pill has no zoom: a magnified copy of the word over the word read as doubled. **Gallery:** Diagnostics → Glass gallery, debug builds only. **Not glass, on purpose:** list rows and artwork (Apple's rule), the player's transport icons (Feature 34), the equalizer's preset chips (inside a glass card). `docs/quality/glass-controls.md` lists every control. Emulator baselines and the contrast check are still to do. **Player backdrop fix (29 Oct):** recording the player's backdrop for its glass froze it on its first frame, so the cover's colours never arrived and the art faded into a flat tone. `PlayerBackdrop` now draws itself and paints a second copy into the glass layer with the standalone `GraphicsLayer.record`, and its blurred copy fades in below the seam instead of starting on a hard line. |
| 12 Encore page | Built (29 Oct); seen on an API 36 emulator with a week of plays | **Files:** `EncoreRepository.kt` (periods, queries, habits, personality, milestones), `EncoreText.kt`, `EncoreCharts.kt`, `EncoreScreen.kt`, `EncoreStories.kt` (slides and sharing together, instead of `EncoreSlides.kt` and `EncoreShare.kt`), `EncoreGenres.kt`; `GlassSegmented` in `GlassControls.kt` for the period and sort controls. **Schema:** `library-state.db` version 3 adds `artist_genres`; clearing history clears it. **Scope:** the page reads the signed-in account and the phone's own files together. **Swipe:** on the headline (and the empty state), not the whole page, so the chart's drag stays free. **See all:** expands the list in place up to 100 instead of a separate screen. **Old plays:** a play from before version 2 (no time, never ended, over 6 h old) counts as its length up to 3 min, and the headline says "estimated". **Story ring:** shows the top song's cover, since the daily totals keep no artist photo; the top-artist slide uses that artist's top cover. **Share:** the slide is captured once on Share, scaled to fill 1080 × 1920, and sent through the lyric cards' file provider. **Extras, built 29 Oct, checked on the owner's phone in Riff Debug the same day:** the recap switch asked for notifications and stayed off on "Don't allow"; the CSV saved through the system dialog (songs then artists, quoted, UTF-8 intact); Export wrote the `encore` line and 60 `encore_top` lines; the launcher lists the widget (not placed, so the owner's home screen stays as it is); "Your history with this" showed first played, plays, time and the year's rank. **Fixed on the phone:** from Now Playing's menu the history page opened behind the player; the item now closes the player first, as "View album" does. Save to YouTube Music is left for the owner to try. **What was built:** "Your history with this" (`EncoreDetail.kt`, a route of its own), reached from the song menus, Now Playing, and the album and artist peeks; the album and artist pages themselves have no entry yet. The long-press menu on Encore's rows (Play or Open, Play next, history). "Save to YouTube Music": the top songs as a new private playlist the owner names, only from a tap. The weekly goal (off, 2, 5, 10 or 20 h; a ring on a week's headline; `EncoreWeekly.kt`). The Sunday Home row (after Speed dial, only once the week has listening; where the week starts on Sunday it names the week just ended). The weekly recap notification (`EncoreRecap.kt`): opt-in on the Encore page, which asks for notifications on Android 13+; one persisted JobScheduler job for Sunday 18:00, run up to 3 h late, once per week; a tap opens Encore through an unpublished shortcut action. The home widget "Encore today" (`EncoreWidget.kt`, a receiver of its own rather than a seventh player preset, so it is in the launcher's widget list but not in Settings' widget list); refreshed when a play ends and every 3 h. **Export** (`EncoreExport.kt`): each owner's totals by year and all-time top 25 songs, artists and albums go into Riff's Export file as `encore` and `encore_top` lines; import passes them over (not counted as unreadable) and rebuilds Encore from the plays. The CSV of top lists is saved from the Encore page for the period shown, rather than from Export, because Export writes one file; titles are quoted and a leading `= + - @` is made plain text. |
| 14 Android Auto | Built (29 Oct); browsed through a `MediaBrowser` on an API 36 emulator, not yet in a car or the Desktop Head Unit | **Files:** `CarLibrary.kt` (the tree, testable alone through `CarSource`) and `CarBrowse.kt` (Media3's futures, the real source, and who may browse), instead of one `CarLibrary.kt`. **Who may browse:** Media3's own Android Auto and Automotive checks, or Android Auto, the system UI or the Google app when the system installed them (`ControllerInfo.isTrusted`), in place of checking signatures by hand. Only these get library commands and may set the queue, and `onSetMediaItems` takes only ids from Riff's own tree. **Playing:** a song plays its whole folder from that song through `QueueCoordinator.carQueue`, the same items as a catalog queue; an empty spoken request plays the most recent plays. **Artwork:** `https://` covers at 400 px for every row; downloaded songs don't use `content://` art yet, so with no signal the car shows them without covers. **Signed out:** Library shows "Sign in on your phone to see your library". **Bug fixed on the way:** a service started by anything other than the app (a car, a test) made the provider inside a read-only Compose snapshot and crashed; it's now made before the snapshot. Not done: `onPlaybackResumption` (Bluetooth or the car's "resume"), and the DHU check, which needs a phone with Android Auto's "Unknown sources" on. |
| 30 Large screens | Built (29 Oct); seen on the emulator at 1280 × 800 dp and 800 × 1280 dp | **Width classes:** read from the window's size with the adaptive library's thresholds (600 and 840 dp) instead of adding material3-adaptive; a window under 480 dp tall (a phone on its side) keeps the bottom bar, since five tabs don't fit a short rail. **Rail:** `GlassRail` in `ShellRail.kt`, Bar glass, centred at the start; the bottom bar keeps only the mini player. **Pane:** at Expanded with something loaded, the player sits in the right 40 % (at least 360 dp), always open, with no drag to close; lyrics and the queue open inside it. **Not done:** the tabletop posture on foldables (needs `androidx.window`'s folding feature), Encore's side-by-side layout, hiding the player's close button inside the pane (it does nothing there), and screenshot tests at 600, 840 and 1280 dp. |
| 6 Animated song art, Stage A | **Removed** (29 Oct) at the owner's request; built and seen on the emulator first | **Removed after the phone checks** (item 16 of `docs/phone-check-bugs-2026-10-29.md`): the owner won't use it, and the setting couldn't go without the motion, or the cover would move with no way to stop it. `LivingArtwork.kt`, the Settings row, the player's mesh phase and the strings are gone; a saved `artwork_motion` value is ignored. What was built: **Setting:** Settings → Appearance → Artwork motion (Off, Drift, Depth, Mesh), off by default. **Drift:** 1.00 to 1.08 and back over 24 s each way with a slight pan, under the cover's fade, which stays put. **Depth:** the game rotation sensor (none existed to share with Feature 1), centred on how the phone was held when the player opened, up to 2 % of the width. **Mesh:** Riff's cover runs edge to edge, so there's no smaller cover to put a mesh behind; instead the player backdrop's colour mesh, drawn 30 % larger, travels round a 30 s circle. It's a moving image, not an AGSL shader, so Android 12 and 13 get the same thing. **Stops:** under reduced motion, battery saver, and whenever Riff isn't resumed. Depth is not yet tried on a phone. Stage B (outside motion clips) was removed from the plan on 29 October at the owner's request. |
| 20 Automix, stage 2 | Built (29 Oct); measured on the owner's 50-song set 29 Oct and **short of the report's targets**; not yet heard on a phone | **Beats:** `BeatTracker.kt`, as the engine report's section 2.2: spectral flux at 11,025 Hz (512-sample frames, 128-sample hop, about 11.6 ms), autocorrelation tempo in 60–200 BPM with a log-normal prior at 120 BPM and half/double-time support, Ellis's dynamic programme, downbeats from the phase of four with the most energy under 150 Hz. Confidence is the beats' steadiness times how far the tempo peak stands out. It is fed by the same decode as stage 1, so it reads only downloaded and on-device songs. **Use:** within the "Smart crossfade" switch rather than a separate Automix choice: when both songs' beats are at least 0.35 sure and their downbeats 0.3, the blend's start moves to the outgoing song's downbeat within 2.5 s of stage 1's start (a four-bar phrase line first) and the incoming song enters on its first downbeat after its stage 1 entry. Neither song changes speed, and the blend still ends at the outgoing song's last sound, so no music is cut. Otherwise stage 1's timing stands. **Tested:** 87, 92, 120, 128 and 140 BPM drum patterns (tempo within 2 %, beats and downbeats within 70 ms), noise (no confident pulse), and the planner. **Measured on the 50-song set (29 Oct):** the owner gave a folder of 426 MP3s; 50 were picked across genres, 35 with a steady beat and 15 hard (jazz in 5/4, 3/4 and swing, rubato piano, classical, ballads in 6/8, ambient), avoiding music-video versions whose skits fill the start or end (`artifacts/automix/set50.json`, local). **Deviation:** the reference beats are **Beat This!**'s (CPJKU, ISMIR 2024, `final0`, code and weights MIT, run only on the PC from `.toolchain/beat-this`), not tapped by hand: the owner chose it, and spot-checked 10 songs by ear from click tracks (`scripts/automix/click_tracks.py`): on 30 Oct the owner heard all 10 on the beat, Kryptonite's 200 BPM ticks included, so where the reference and Riff differ only by double or half, neither is wrong and Acc2 is the tempo measure that matters. Riff's tracker ran on each song's first 30 s and last 45 s, decoded as Riff decodes them (`scripts/automix/extract_windows.py`, `AutomixSetEvaluation`, `score_beats.py`). **Steady songs (70 windows): tempo Acc1 66 % (target 85 %), Acc2 91 % (95 %), beat F 0.74 (0.85), downbeat F 0.31 (0.70).** Hard songs: Acc1 33 %, beat F 0.54, downbeat F 0.19. What the misses are: 18 of the steady misses are only octave choices (double or half), several of them the reference's (Kryptonite at 200 BPM); Riff hears Water and Calm Down (amapiano and afrobeats) at 156 and 143 BPM instead of about 115 and 107; in three places the tempo is right but Riff's beats fall between the real ones, once while sure of them (Lovin On Me's ending). **Downbeats are the weak part:** where every beat is right, Riff finds the bar's first beat about half the time (mean F 0.45), and it was sure of downbeats in 8 of 70 steady windows, right in 3. Its gate keeps it from acting most of the time, so stage 2 rarely changes a blend, and when it does the bar line can be wrong. About 0.08 s per window on the PC. **So stage 3 stays blocked** on its own rule until the tracker meets the targets; the options (improving the downbeat finder, or running a small learned model such as Beat This!'s 8 MB `small0` on the phone) are the owner's to choose. **Not done:** phrase lines found from the music, and the CPU measure on the phone. |
| 19 Cast | Built (29 Oct); no Cast device here, so never cast for real: tested on the JVM, and on an API 36 emulator with Play services the picker opened and Cast discovery ran (mDNS `_googlecast`, no devices found) | **Player:** Media3 1.11's own `CastPlayer` (local player plus `RemoteCastPlayer`) rather than a forwarding player of Riff's: it already moves the session, notification, lock screen and volume keys between the phone and the speaker. Riff gives it `CastSync` (`CastSupport.kt`) as the transfer callback. The phone keeps the queue, paused; the speaker gets the current song and the next 3 (`CAST_AHEAD`), is topped up as it plays, and is sent the queue again when the phone's changes. Songs are matched by Riff's entry id, carried in the receiver item's custom data. A play pressed on the phone's own player while casting goes to the speaker. **Streams:** direct, header-free URLs only. Riff's ordinary music streams already ask for no SABR, so no `forCast` request was needed: `castSource` refuses SABR, HLS, bounded-range and non-HTTPS streams and on-device songs, and the speaker skips them with a message. The headers YouTube's URLs come with (user agent and the like) are not sent by the speaker; whether every client's URLs play without them is not known yet. **Not built:** the relay (steps 4.2–4.3), so no on-device songs, no Wi-Fi lock and no cleartext rule; refreshing URLs older than 6 hours on the speaker; a separate "Casting to …" bar (the output pill shows the speaker and a Cast icon instead). **Picker:** the output pill opens a sheet ("Speakers and TVs": this phone, then the speakers MediaRouter finds while the sheet is open, and Stop casting); without Play services it opens the system switcher as before. **Dependencies:** `media3-cast` 1.11.1 and `mediarouter` 1.8.1 (the version media3-cast asks for); Play services' Cast framework 22.3.1 comes with them. Found in the licence audit: they also bring Firebase's data transport, whose `transport-backend-cct` uploads the Cast library's own usage reports to Google. Excluding the module broke R8 (the Cast library refers to `CCTDestination`), so it stays and the manifest removes its backend registration instead (`tools:node="remove"`): the transport finds no uploader and drops the reports on the phone. This is in `docs/privacy.md`. **R8:** `RiffCastOptions` is named only in the manifest's meta-data, which R8 doesn't read, so the first release build dropped it; `proguard-rules.pro` keeps it. **Lane:** the first lane on this stage also failed two timing-sensitive JVM tests (`ExitDiagnosticsTest`, `ContentStateUiTest`) while a second Gradle build and the emulator ran beside it; both pass alone. **Also fixed here:** the Stage 8 lane had failed. Thirteen lint errors came from the car code (missing `UnstableApi` opt-ins, and `MissingIntentFilterForMediaSearch`, which is suppressed with the reason in the manifest: the car's voice search reaches the session, and asking the phone's Assistant to play in Riff is not built). Two source-text tests still expected `MediaSession.Builder` and the old foreign-controller line. |
| 29 What's new | Built (29 Oct); JVM-tested, and the tips seen on a fresh install on the emulator (after the restore offer); the release sheet not yet seen after a real update | **Shown:** once when the version code rises and `res/raw/whats_new.json` was written for a newer version than was last seen; the file is written for code 34, so nothing shows until the release is numbered (the branch is still 33). A fresh install (first install time equals last update time) gets the tips instead; an update from a build before this one (nothing recorded) still gets the sheet. **Sheet:** a Material bottom sheet with glass cards (`riffGlass(GlassRole.Menu)`), not a glass sheet of its own. **Tips:** links (opens Android's "Open by default" page) and Android Auto; the Encore tip after the first week is not built. **Settings:** About → "What's new and tips" opens the sheet again rather than a Help section, which Riff doesn't have. **Changelog:** an Unreleased section for the whole branch was added here, as no stage had updated it. |
| Phone benchmark (29 Oct) | **Unsolved**, stopped by the owner | The benchmark lane on the owner's I2217 (Tier R) ran 19 of 20 journeys; `playerAndQueueActions` lost its Open queue tap (fixed in `73bf2d9`: the tap goes to the point where the node was found). Against rc10 (24 Oct) frames were 2–4× worse (missed deadlines, p95: Home 18.6–26.7 % against 8.0 %, a collection 24–31 % against 7.0 %; player and queue 14–24 % against 17.1 %) and cold start p95 was 1,990 ms, then 3,470 ms with one bad start in 20 (median 1,899 ms). **Ruled out:** heat (reruns waited for thermal status 0 and a cool battery), the 120 Hz screen (a rerun at 60 Hz was no better), the phone's software (build of July 2026, unchanged), and Feature 2's glass by itself (Home scrolled the same with High-contrast controls on). **Found:** rc10's own code, built again and run on the phone the same day, also missed 18.9 % (Home), 20.6 % (collection) and 25.9 % (player and queue): most of the difference is the phone's state, not this branch. **Not done:** the same-day run of this branch's build (it failed every journey on the restore-a-backup offer a fresh install shows; `cleanSlate()` now answers Not now, not yet run), and why the phone measures worse than on 24 Oct (aeroplane mode was off for the rc10 rerun; the USB connection dropped four times). Evidence: `artifacts/quality/lanes/benchmark-20261029T021338Z`, `rerun-20261029T044923Z`, `rerun60-20261029T052005Z`, `ab-20261029T053255Z` (local). |
| 20 Automix, stage 3 and later (Stage 10) | Not started (29 Oct): blocked by its own gate | The order of work allows Stage 3 only after Stage 2 is measured on the 50-song set and a timing prototype holds the two players within 15 ms on the phone. Neither exists yet: the test set is the owner's, and the timing can only be measured by playing on the phone. Building beat-matching without them risks audible flams, so it waits. **29 Oct:** the 50 songs are picked and stage 2 is measured (row above): it misses the targets, so stage 3 waits for a better tracker. **Then:** a measuring build (debug, never over the owner's Riff) logs the beat offset `CrossfadeTailSynchronizer` achieves at 10 ms targets. |

## Contents

1. [Summary](#summary)
2. [What the reference video shows](#what-the-reference-video-shows)
3. [Feature 1: Apple-like Liquid Glass](#feature-1-apple-like-liquid-glass)
4. [Feature 2: Glass on every control](#feature-2-glass-on-every-control)
5. [Feature 3: A font like the video's](#feature-3-a-font-like-the-videos)
6. [Feature 4: lrc.red as a lyrics source](#feature-4-lrcred-as-a-lyrics-source)
7. [Feature 5: Blur on lines not being sung, faded edges](#feature-5-blur-on-lines-not-being-sung-faded-edges)
8. [Feature 6: Animated song art](#feature-6-animated-song-art)
9. [Feature 7: Scrolling long titles in the player only](#feature-7-scrolling-long-titles-in-the-player-only)
10. [Feature 8: "See all" on artist pages](#feature-8-see-all-on-artist-pages)
11. [Feature 9: Skipping the silence at the end of a song](#feature-9-skipping-the-silence-at-the-end-of-a-song)
12. [Feature 10: A built-in equalizer, with the phone's equalizer one tap away](#feature-10-a-built-in-equalizer-with-the-phones-equalizer-one-tap-away)
13. [Feature 11: A dark fade at the top and bottom of the screen](#feature-11-a-dark-fade-at-the-top-and-bottom-of-the-screen)
14. [Feature 12: Encore, a listening statistics page with stories](#feature-12-encore-a-listening-statistics-page-with-stories)
15. [Feature 13: Opening YouTube Music links in Riff](#feature-13-opening-youtube-music-links-in-riff)
16. [Feature 14: Android Auto](#feature-14-android-auto)
17. [Feature 15: Playback speed](#feature-15-playback-speed)
18. [Feature 16: Stats for nerds](#feature-16-stats-for-nerds)
19. [Feature 17: Separate quality for Wi-Fi and mobile data](#feature-17-separate-quality-for-wi-fi-and-mobile-data)
20. [Feature 18: "Stop after this song" and fade length in the sleep timer](#feature-18-stop-after-this-song-and-fade-length-in-the-sleep-timer)
21. [Feature 19: Cast to Chromecast and Google speakers](#feature-19-cast-to-chromecast-and-google-speakers)
22. [Feature 20: Automix (DJ-style transitions)](#feature-20-automix-dj-style-transitions)
23. [Features 22–30: further recommended features and UI/UX changes](#features-2230-further-recommended-features-and-uiux-changes)
24. [Feature 32: Three dots during instrumental sections of the lyrics](#feature-32-three-dots-during-instrumental-sections-of-the-lyrics)
25. [Feature 33: Dark status bar icons over light artwork](#feature-33-dark-status-bar-icons-over-light-artwork)
26. [Feature 34: The player redesigned after BitChord's](#feature-34-the-player-redesigned-after-bitchords)
27. [Feature 35: Calmer, slightly slower transitions](#feature-35-calmer-slightly-slower-transitions)
28. [Order of work, testing and release](#order-of-work-testing-and-release)
29. [Sources](#sources)

## Summary

| # | Request | Feasible? | Likely match to Apple | Size | Main risk |
|---|---|---|---|---|---|
| 1 | Apple-like Liquid Glass | Yes, on Android 13+ | About 85 % on Android 13+; blur only on Android 12 | Large | Frame time: Riff already misses the 60 Hz budget (Q18-030) |
| 2 | Glass on every control | Yes, for controls; not for content | High where something sits behind the glass | Large | Frame time and battery; flat screens give glass nothing to bend |
| 3 | SF-like font | Yes, with Inter (already bundled), tuned | About 90 % to the eye | Small | Bundling SF Pro itself breaks Apple's licence |
| 4 | lrc.red lyrics source | Yes | Same data BitChord uses | Small | No published terms; some word timings are AI-made |
| 5 | Blur on lines not being sung, faded edges | Yes | About 95 % | Small | A few blurred text layers per frame |
| 6 | Animated song art | Dropped (29 Oct): outside clips, then the motion made on the phone, removed at the owner's request | A moving cover for every song, not Apple's designed clips | Medium | Battery; motion that distracts (off by default) |
| 7 | Scrolling long titles, player only | Yes | About 100 % | Small | Interaction with the player's open/close morph |
| 8 | "See all" on artist pages | Yes | Same as YouTube Music's own "See all" | Small to medium | Riff's parser currently drops the link YouTube sends |
| 9 | Skip the silence at the end of a song | Yes | Exact for downloaded songs and for streams once cached to the end (about 50 s before it); about 1.5 s of silence still heard on streams the cache does not keep | Medium | Hidden tracks after a long silence; quiet song endings |
| 10 | Built-in equalizer, plus the phone's equalizer | Yes; the phone's equalizer opens as its own screen, not inside Riff | Riff's own equalizer: full; phone's equalizer: whatever the phone offers | Medium | Clipping when bands are raised; doubled effect if both are on |
| 11 | Dark fade at the top and bottom of the screen | Yes | About 100 % of the screenshot | Small | Must not darken the full-screen player's artwork or the glass itself |
| 12 | Encore: statistics for day, week, month, year and lifetime, with charts and stories | Yes; genres need an optional outside lookup | Comparable to Apple Music Replay and Spotify Wrapped, but live all year | Large | Riff doesn't yet record listening time, album or genre per play; retention settings limit "lifetime" |
| 13 | Open YouTube Music links in Riff | Yes | Full, through Share; direct link opening needs a one-time switch in Android's settings | Small | Android won't open unverified web links in Riff by default |
| 14 | Android Auto | Yes | Same browsing as other music apps in the car | Medium | Sideloaded apps appear only with Android Auto's "Unknown sources" developer setting |
| 15 | Playback speed (0.5×–2×) | Yes | Full | Small | Interaction with crossfade's own speed correction |
| 16 | Stats for nerds | Yes | Full | Small | None |
| 17 | Quality per network | Yes | Full | Small | None |
| 18 | "Stop after this song" and fade length | Yes | Full | Small | Must keep crossfade from starting the next song |
| 19 | Cast to Chromecast and Google speakers | Yes, with a stream relay on the phone for some songs | Comparable to other casting apps; phone must stay on the same Wi-Fi | Medium to large | YouTube's stream format (SABR) and per-request headers can't be played by the speaker directly; adds Google Play services |
| 20 | Automix (DJ-style transitions); full design in the [AutoMix engine report](automix-engine-report-2026-10-28.md) | In stages: smart crossfades first, beat-matching later | Stage 1 near Apple's quality for every song; full beat-matching good on steady-beat music, weaker elsewhere | Very large | Timing two players to ~10 ms; model licences; APK size |
| 22 | Predictive back animations | Yes | Full | Small | None |
| 23 | Like, Shuffle, Add to playlist on lock screen and notification | Yes | Full | Small | None |
| 24 | Search suggestions and top result | Yes | Same as YouTube Music | Small to medium | Sends partial typing to YouTube |
| 25 | Romanized lyrics | Yes | Full where providers send it; mechanical otherwise | Small | Transliteration isn't perfect |
| 26 | Undo for removals | Yes | Full | Small | Playlist order after undo |
| 27 | App shortcuts and Quick Settings tile | Yes | Full | Small | None |
| 28 | Consistent loading, empty and offline states | Yes | Full | Small to medium | None |
| 29 | What's new sheet and first-run tips | Yes | Full | Small | None |
| 30 | Large-screen and landscape layout | Yes | Full | Medium | More screenshot baselines |
| 32 | Three dots during instrumental lyrics | Yes | Same as Apple Music and BitChord where the lyrics give line ends | Small | Plain LRC without break marks can't show them |
| 33 | Dark status bar icons over light artwork | Yes | Full | Small | Must not flicker during scrolls and crossfades |
| 34 | Player redesigned after BitChord's (keeping shuffle, repeat, X and NOW PLAYING) | Yes | Very close | Medium | The mini player morph must land on the new positions |
| 35 | Calmer, slightly slower transitions | Yes | Full | Small | Gestures and music-timed motion must stay immediate |

## What the reference video shows

From frames at 1 s spacing and two full-size frames:

- **A selection lens you can drag.** The tab bar is a clear glass capsule (Home, Explore, Library) with a separate round search button. Under a finger the selected-tab highlight becomes a larger, magnifying lens. It rises above the bar, stretches in the direction of the drag, and springs into the next tab when released.
- **Strong refraction at the rims.** The rims bend the photo behind them hard: colours smear around the curve and a bright edge highlight follows the shape. The centre stays nearly clear and only slightly blurred. The lens magnifies what is under it.
- **Light rims and a faint colour fringe.** A thin bright line runs around each shape; the curved ends split colours slightly.
- **Chrome that shrinks while scrolling.** Scrolling down collapses the bar into a small home button, a mini player capsule ("I Wonder" with play) and the round search button. Scrolling back up expands it again, with the mini player above the tabs.
- **Font.** The titles, labels and rows are SF Pro. No separate picture was attached, so the font comparison uses the video.

---

## Feature 1: Apple-like Liquid Glass

### 1.1 Where Riff stands

Riff's current glass:

- [AgslChromeGlassEffect.kt](../app/src/main/java/com/musicglass/personal/AgslChromeGlassEffect.kt) is one full-screen AGSL shader, applied as a `RenderEffect` on the whole screen. It knows a fixed set of shapes: `nav`, `search`, `mini` and `categoryRects[8]`. Their bounds come from `ChromeGlassGeometry.kt` and `LocalGlassBounds`.
- [ChromeGlassEffect.kt](../app/src/main/java/com/musicglass/personal/ChromeGlassEffect.kt) decides between that shader (Android 13+) and a blur with a bitmap mask and tint (Android 12). Frosted glass draws half-size blurred strips (`frostBlurEffect`) and cuts them with `frostCutEffect`.
- [AgslNavigationLens.kt](../app/src/main/java/com/musicglass/personal/AgslNavigationLens.kt) and [NavigationLens.kt](../app/src/main/java/com/musicglass/personal/NavigationLens.kt) bulge the tab *icons* under a press or drag (`gain = contact × 0.085 + strain × 0.55`).

Why it doesn't look like Apple's glass:

| | Riff today | Apple, and the Kyant shader BitChord uses |
|---|---|---|
| How far the rim bends the picture | `refraction = 3 dp × intensity` over a 14 dp bevel, linear: a few pixels | Tens of dp along a quarter-circle profile `1 − √(1 − x²)`: gentle in the middle, steep at the rim |
| Bend direction | Surface normal from a finite-difference SDF gradient | Exact SDF gradient plus an optional "depth" term towards the centre, so the shape reads as a convex lens |
| Colour fringe | None | Red, orange, yellow, green, cyan, blue, violet sampled at offsets weighted by `(x·y)/(w·h)`: strongest at the corners |
| Highlight | One specular from the top left, 0.26 strength | A rim highlight, an inner shadow and a drop shadow |
| Magnifying lens | Bends the icons but not the backdrop | Magnifies and stretches the backdrop |
| Shapes | Hard-coded list in one shader | Any composable, any rounded shape, its own effect chain |
| Corners | Circular | Continuous ("squircle") corners |

The main gap is the size and profile of the bend, not missing technology. The hard-coded shape list is what stops glass spreading to other screens.

### 1.2 How the good Android ports do it

BitChord's glass is **Kyant0/backdrop** v2.0.0 (Apache-2.0), copied into `app/src/main/java/com/music/bitchord/ui/components/backdrop/` with its licence header. BitChord copied it because the published AAR, built against an older Compose, failed at runtime, and to add a backdrop resolution scale.

1. **Recording the backdrop.** `rememberLayerBackdrop()` makes a `LayerBackdrop` that owns a `GraphicsLayer`. The content behind the glass is drawn into it with `Modifier.layerBackdrop(backdrop)`.
2. **The glass.** `Modifier.drawBackdrop(backdrop, shape = { RoundedCornerShape(..) }, effects = { vibrancy(); blur(r); lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration) }, highlight = { Highlight(..) }, shadow = { Shadow(..) }, innerShadow = { InnerShadow(..) }, onDrawSurface = { drawRect(tint) })`. Each effect is a `RenderEffect` in a chain.
3. **The lens shader** (BitChord `internal/Shaders.kt`, `RoundedRectRefractionShaderString`):
   - work out `sd`, the signed distance to the rounded rectangle;
   - inside the rim (`-sd < refractionHeight`), the displacement is `d = circleMap(1 − (−sd / refractionHeight)) × refractionAmount`;
   - its direction is `normalize(gradSdRoundedRect(..) + depthEffect × normalize(centeredCoord))`;
   - the pixel is read from `coord + d × grad`.

   The dispersion version samples seven times along `d × grad × (x·y)/(w·h)` and adds each sample into its own colour channel.
4. **Interaction.** BitChord's `floatingtabbar/GlassSelectionPill.kt` is a separate glass layer drawn over the tab bar, with its own `lens`. Springs widen and lift it while it's dragged and settle it on the nearest tab: the behaviour in the video.

Other ports confirm the method: [KMPLiquidGlass](https://github.com/Kashif-E/KMPLiquidGlass), and the Flutter [fluid_glass](https://pub.dev/packages/fluid_glass), which carries Kyant's refraction, dispersion and highlight maths over unchanged.

### 1.3 Implementation plan

**Step 1: add the library.**
- Add to `gradle/libs.versions.toml`:
  - `backdrop = "2.0.0"`, library `io.github.kyant0:backdrop`;
  - `kyantShapes`, library `io.github.kyant0:shapes`, for continuous corners.
- Add the checksums to `gradle/verification-metadata.xml`: Riff verifies every dependency, and the build fails without them.
- Build against Compose BOM 2026.06. If the AAR fails at runtime, as BitChord saw, copy the sources into `app/src/main/java/com/musicglass/personal/glass/backdrop/` with the Apache header and a "copied from" note.
- Either way, add the library to `NOTICE.md`, `docs/dependency-license-inventory.md` and `docs/dependency-license-audit.csv`. Apache-2.0 code may be combined into a GPL-3.0 app.

**Step 2: one glass API for the whole app.** New file `RiffGlass.kt`:

```kotlin
/** The page content every glass control refracts. One per screen, set by the shell. */
internal val LocalRiffBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal enum class GlassRole { Bar, Button, Lens, Card, Menu, Slider }

@Composable
internal fun Modifier.riffGlass(role: GlassRole, shape: Shape = continuousCapsule()): Modifier {
    val backdrop = LocalRiffBackdrop.current ?: return this.frostFallback(role, shape) // Android 12, or no backdrop
    val style = LocalGlassStyle.current // intensity, frosted, reduced motion, power saver
    val spec = glassSpec(role, style)   // the numbers in step 4
    return this.drawBackdrop(backdrop, shape = { shape },
        effects = {
            vibrancy()
            blur(spec.blur.toPx())
            if (spec.refractionAmount > 0.dp)
                lens(spec.refractionHeight.toPx(), spec.refractionAmount.toPx(),
                    depthEffect = spec.depth, chromaticAberration = spec.dispersion)
        },
        highlight = { Highlight.Default.copy(alpha = spec.highlight) },
        innerShadow = { InnerShadow(radius = 6.dp, alpha = spec.innerShadow) },
        shadow = { Shadow(radius = 18.dp, alpha = .18f) },
        onDrawSurface = { drawRect(spec.tint) })
}
```

- `frostFallback` reuses Riff's existing Android 12 path: the half-size blur from `frostBlurEffect` and the tint colour from `frostCutEffect`. `LocalGlassStyle` reads the existing "Glass and frosted intensity" setting, reduced motion and Data saver.
- Riff's settings keep their meaning: "liquid" means clear glass (small blur, full lens); "frosted" means a larger blur and a stronger tint.

**Step 3: record the backdrop once per screen.**
- In `RiffApp` and `ShellChrome.kt`, wrap the screen content (the `NavHost` area) in `Modifier.layerBackdrop(backdrop)` and provide `LocalRiffBackdrop`.
- The full-screen player gets its own backdrop over the artwork and background, so the player controls bend the artwork.
- Draw the backdrop layer at reduced resolution (BitChord's change, 0.5×) to halve the blur and lens cost.

**Step 4: starting values** (tune on the phone against the video):

| Role | Blur | Refraction height | Refraction amount | Depth | Dispersion | Highlight | Tint |
|---|---|---|---|---|---|---|---|
| Bar (tab bar, mini player) | 2 dp | 20 dp | 36 dp | on | on | 0.45 | white 6 % |
| Lens (drag selection) | 0 dp | 28 dp | 56 dp | on | on | 0.55 | none |
| Button (search, play, back) | 2 dp | 14 dp | 28 dp | on | on | 0.45 | white 6 % |
| Card (settings group) | 8 dp | 12 dp | 20 dp | off | off | 0.30 | white 8 % |
| Menu and sheet | 20 dp | 10 dp | 16 dp | off | off | 0.30 | black 25 % |
| Slider thumb | 0 dp | 12 dp | 24 dp | on | on | 0.50 | none |

**Step 5: the drag lens.**
- Replace the icon-only lens in `AgslNavigationLens.kt` with a glass pill (`GlassRole.Lens`) drawn above the tab row.
- Its centre follows the finger. Its size and stretch reuse `navigationLens(...)`'s `contact` (press) and `strain` (drag lag) values:
  - width × (1.25 + 0.16 × contact + strain);
  - height × (1.35 + 0.28 × contact − 0.35 × strain);
  - lift −6 dp × contact.
- Magnify by scaling the sampled backdrop by `1 + 0.2 × contact` around the pill centre. Use an extra `RenderEffect.createRuntimeShaderEffect` with a small zoom shader, or Kyant's `lens` with `depthEffect = true` and a larger amount.
- On release, animate to the nearest tab with `spring(dampingRatio = 0.72f, stiffness = 380f)`, and morph back to the normal selected pill.
- Touch targets and semantics stay on the fixed tabs, as `NavigationLens.kt` already says.

**Step 6: the collapsing bar.**
- The shell already has `compact` and `collapsed` states (`chromeGlassEffect(..., compact, collapsed, ...)`). Drive one `collapse` fraction from the scroll position (nested scroll connection on the screen's list), 0 to 1 over about 48 dp of scroll, with a spring when the scroll stops.
- At `collapse = 1`:
  - the tab capsule shrinks to the selected tab's icon in a circle;
  - the mini player moves down beside it as a capsule (artwork, title, play);
  - search stays a circle on the right.

**Step 7: merging shapes.** Where the mini player and the tab capsule touch during the collapse, Apple merges them like liquid. Kyant's per-element glass can't do this, because each element is drawn separately. Keep a small version of Riff's current single shader just for the bottom chrome:
- shapes: tab capsule, mini player, search;
- join their distance fields with a smooth minimum, `smin(a, b, k) = min(a, b) − h²k/4` with `h = max(k − |a − b|, 0)/k` and `k ≈ 16 dp`;
- apply Kyant's circular refraction profile and dispersion to the joined shape.

The rest of the app uses the per-element modifier.

**Step 8: light that follows the phone (optional).**
- Read `Sensor.TYPE_GAME_ROTATION_VECTOR` at about 30 Hz while the app is in front.
- Turn the tilt into a highlight angle (±20°) and smooth it.
- Off with reduced motion and battery saver.

**Step 9: tint that adapts to the background (optional).**
- Every 250 ms, read the backdrop layer at 8 × 8 px into a bitmap.
- If the average luminance is above 0.6, switch the glass to dark tint and dark icons; otherwise light, with a 300 ms crossfade.

**Step 10: frame-time protection.**
- Add a `GlassQuality` level (Full, Reduced, Frost) chosen from:
  - `PowerManager.isPowerSaveMode`;
  - `PowerManager.getThermalHeadroom`;
  - reduced motion;
  - a frame-drop measurement from `JankStats`: drop one level after 3 s above 5 % janky frames.
- At Reduced: no dispersion, backdrop at 0.33×. At Frost: Riff's existing frost.
- Measure before and after with the `:benchmark` lane (`verify.py --lane benchmark`).

### 1.4 How close it gets, and the limits

- **Android 13+:** about 85 %. Refraction, magnification, colour fringe, highlights and springs can match closely. Approximations:
  - the tint that adapts to the background (step 9);
  - the light that follows the phone (step 8).
- **Android 12 (Riff's minSdk 31):** no `RuntimeShader`, so frost only. Raising minSdk to 33 would drop Android 12 phones.
- **Performance** is the constraint. BitChord makes full animation opt-in behind a High Performance Mode; Riff's step 10 does the same automatically.

---

## Feature 2: Glass on every control

Apple's rule in iOS 26 is that glass belongs to the **controls layer** (bars, buttons, sliders, toggles, menus, sheets) floating over content. Rows and artwork stay solid. Following that rule is also what keeps it fast enough.

### 2.1 Inventory and what changes

| Area | Files | Elements to make glass | How |
|---|---|---|---|
| Shell | `ShellChrome.kt`, `ScreenParts.kt` | tab bar, drag lens, search button, mini player, top bars, back buttons | `riffGlass(Bar / Button / Lens)`; remove the uses of `chromeGlassEffect` and `LocalGlassBounds` |
| Player | `NowPlaying.kt`, `PlayerPresentation.kt` | play/pause/skip, scrubber and volume sliders, lyrics/queue/output/more buttons, queue sheet, lyrics header | Player backdrop over the artwork; `GlassRole.Button` circles for controls; sliders below |
| Sliders | new `GlassSlider.kt` | scrubber, volume, glass intensity, crossfade length | Solid track; glass thumb (`GlassRole.Slider`) that grows 1.3× while held, with the existing slider haptics |
| Toggles | new `GlassSwitch.kt` | every switch in Settings | Track tinted by state; glass thumb that stretches in the direction it moves (width × 1.25 during the move) |
| Settings | settings screens, `DataSaverSettings.kt`, Privacy and data | grouped sections as glass cards, choosers, segmented controls | Give Settings a background to bend: a slow, blurred gradient from the current song's artwork palette (`ArtworkPalette.kt`), or a fixed Riff gradient when nothing plays. Then `GlassRole.Card` per group |
| Menus and dialogs | `FrostedPopup.kt`, `SongActionMenu`, sign-in (`SignInDesign.kt`), snack bars | popups, context menus, sheets | `GlassRole.Menu`; popups need their own backdrop of the screen under them (`rememberLayerBackdrop` on the root) |
| Search | search screen, `GlassCategoryChip.kt` | search field, filter chips | Chips become `GlassRole.Button` capsules; remove them from `categoryRects` |
| Lists | home, library, collection, artist | rows stay solid; the pressed state and swipe actions are glass | One shared backdrop; no glass per row |
| Lyrics | `LyricsPanel.kt` | header buttons, share card buttons | `GlassRole.Button` |

### 2.2 Steps

1. Build `RiffGlass.kt`, `GlassSlider.kt` and `GlassSwitch.kt` first, with a debug "glass gallery" screen showing every role over a test image, so tuning happens in one place.
2. Move the shell (Feature 1) onto it and delete the old full-screen shader paths, except the bottom chrome merge shader (step 7).
3. Player.
4. Menus and sheets.
5. Settings, with the artwork-gradient background.
6. Search and chips.
7. Sweep every remaining screen. List each control in a table in `docs/quality/` so nothing is missed.

### 2.3 Accessibility

- High contrast and reduced transparency: Riff already has high contrast. Under it, glass becomes an opaque surface with a 1 dp outline.
- Check text on glass for 4.5:1 contrast against the darkest and lightest backdrop. The tint in step 9 is what keeps light backgrounds readable.
- Semantics don't change: glass is only drawing.

### 2.4 Tests

- Update the JVM semantics sweeps for the new switch and slider components.
- Re-baseline the screenshot checks in `scripts/run-ui-regression.ps1`.
- Run `verify.py --lane emulator` on API 31 (frost path), API 33 and API 36.

---

## Feature 3: A font like the video's

### 3.1 Findings

- **The owner's font screenshot** (a settings page: "Liquid Glass", "Animated cover art", "Blur unfocused lyrics") shows the same font as the video: **SF Pro Display**.
  - Clues: round, open letters; the flat-sided "g" and "y"; a tall x-height; tight spacing at 17–20 sp in the row titles; grey subtitles in the same face.
  - The settings in it ("Set up Spotify Canvas", "Artist signatures", the lyrics source list starting "lrc.red, BiniLyrics, BetterLyrics") are BitChord's, and BitChord bundles SF Pro Display (below). So this is the font wanted.
- Apple's licence allows SF Pro only for software on Apple platforms, so it can't go in an Android APK.
- **BitChord does bundle it** (`app/src/main/res/font/sf_pro_display_*.otf`). Riff shouldn't copy that: it would also be a problem for Riff's GPL source archive and NOTICE.
- Riff already ships **Inter** (`res/font/inter_regular/medium/semibold/bold.ttf`; `InterFamily` in `AmbientTheme.kt:29`; replaced by `inter_variable.ttf` in Stage 4). Inter was drawn for screens with metrics close to SF and is the usual open substitute (SIL OFL 1.1).

### 3.2 Implementation

1. **Replace the static fonts with Inter 4 variable.** Use `InterVariable.ttf` with the `opsz` axis, from the official Inter release; check the file's hash into the asset provenance note.
2. **Make two families in `AmbientTheme.kt`.** "Text" uses `opsz` 14. "Display" uses `opsz` 32, for 20 sp and up, as SF switches from Text to Display. In code: `Font(R.font.inter_variable, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("opsz", 32f)))`.
3. **Type scale in `DesignTokens.kt`**, modelled on Apple's:

   | Use | Size | Weight | Tracking | Family |
   |---|---|---|---|---|
   | Large title (Home, Library) | 34 sp | Bold | −0.9 sp | Display |
   | Player song title | 22 sp | Bold | −0.45 sp | Display |
   | Section heading | 22 sp | Bold | −0.4 sp | Display |
   | Row title | 17 sp | Semibold | −0.4 sp | Text |
   | Body | 15 sp | Regular | −0.2 sp | Text |
   | Subtitle (artist, counts) | 13 sp | Regular | −0.05 sp | Text, 60 % white |
   | Tab label | 10 sp | Medium | +0.1 sp | Text |
   | Lyrics | 28–32 sp | Bold | −0.6 sp | Display |

4. **Find hard-coded sizes.** Many screens set `fontSize` and `fontWeight` directly (for example `NowPlaying.kt:350`, `PlayerPresentation.kt:423`). Search for `fontSize =` and move each one onto a token, so the change is complete and stays complete. Add a static check to `scripts/quality` that fails on new hard-coded sizes outside `DesignTokens.kt`.
5. **Letter shapes.** Keep Inter's default double-storey a, which matches SF. Turn on `tnum` (tabular figures) for times and counts, as SF does in Music.

### 3.3 Match and tests

About 90 % to most people. The visible differences are small details in letters such as R, G and t. Screenshot baselines change. Check large text: Riff's large-text layout and landscape cases (A11Y02).

**Checked against the owner's screenshot:**
- Row titles ("Liquid Glass", "Synced lyrics") are about 20 sp regular, white.
- Subtitles are about 15–16 sp regular, about 55 % white, and wrap to two lines.
- The mini player title ("Goodbye Yellow Brick …") is about 17 sp semibold.

In the type scale above, that means:
- Row titles in **Settings** use 19 sp Regular (not Semibold) with −0.45 sp tracking.
- Settings subtitles use 15 sp Regular at 55 % white with −0.2 sp tracking.

Inter Display at those sizes and weights is the closest legal match to the screenshot.

---

## Feature 4: lrc.red as a lyrics source

### 4.1 Findings

lrc.red is a synced-lyrics database built by the Monochrome team. It has no published API documentation; the endpoints below come from BitChord's tests (`app/src/test/java/com/music/bitchord/LrcRedTest.kt`, recorded 4 Oct 2026). The search endpoint answered 200 JSON when checked on 27 Oct 2026.

| Endpoint | Returns |
|---|---|
| `GET https://lrc.red/search.json?q=<title artist>` | A list of hits: `isrc`, `title`, `artist`, `album`, `year`, `duration` (seconds, decimal), `cover`, `color` |
| `GET https://lrc.red/s/<ISRC>.ttml` | TTML lyrics: word-timed where available, background vocals as a second span |
| `GET https://lrc.red/s/<ISRC>.song.json` | Per-track metadata, including a flag when the word timing came from "Sync with AI" |

- Riff may already get some lrc.red lyrics indirectly: the [Better Lyrics API](https://github.com/better-lyrics/api), Riff's top source, uses lrc.red by ISRC.
- The direct source adds text search for songs where Better Lyrics has no ISRC.
- AI-synced word timings can drift from the audio ([LRCLib word-sync issue](https://github.com/Andbal23/dynamic-music-pill/issues/193)).

### 4.2 Implementation

1. **New file `LrcRedLyrics.kt`:**
   - `lrcRedSearchUrl(track)`, built with OkHttp's `HttpUrl.Builder`. Query: title plus the first artist, with "(feat. …)", "- Topic", "Official Video" and the like removed. Reuse `lyricsQueryTitle` (`LyricsModels.kt:267`), which the other sources already use.
   - `lrcRedMatchScore(hit, track)`: the existing `lyricsMatchScore(track, title, artist, durationMs)`, with `duration × 1000`.
   - `lrcRedDocumentUrl(isrc)`: upper-case and trimmed (BitChord's test checks `" gbum71029604 "` → `GBUM71029604`).
   - `parseLrcRedTtml(raw, durationMs)`: reuse the TTML parsing in `LyricsModels.kt` that the Better Lyrics source already uses for word spans and background vocals.
   - `lrcRedAiSynced(songJson)`: reads the AI flag.
2. **In `LyricsRepository.lyrics()`:**
   - Add `Source("lrc.red", 3) { offer(lrcRed(track, ::request)) }` to `wordSources`.
   - `lrcRed`:
     - search, then keep hits scoring above Riff's usual threshold, best first, at most 2;
     - fetch the TTML of the best;
     - if it is word-timed and the song JSON says AI-synced, make it line-timed: keep the lines, drop the word parts, so it ranks with the line sources;
     - return a `LyricsDocument("lrc.red", "https://lrc.red/s/<ISRC>", lines)`.
3. **In `AdditionalLyricsProviders.kt`, `lyricsProviderPriority`:** `"lrc.red" -> 2`, between QQ and NetEase. Move the later entries down by one. Human word timings from lrc.red then win over NetEase's.
4. **Behaviour:** the repository's per-provider limit (15 s), cache, retry-after handling and diagnostics (`lyrics_answer source=…`) already apply to every `Source`. Add `lrc.red` to the diagnostics' allowed values in `DiagnosticEvent.kt`.
5. **Privacy:** add lrc.red to `docs/privacy.md` and to the network destinations list in `docs/quality/architecture.md`. What it is sent: title and artist text. No cookies.
6. **Tests** (`app/src/test/.../LrcRedLyricsTest.kt`):
   - search parsing and scoring, using fixtures like BitChord's;
   - ISRC URL normalisation;
   - TTML word and background parsing;
   - the AI flag lowering to line timing;
   - priority ordering;
   - a `LyricsRepository` test with a fake server proving lrc.red loses to Better Lyrics word timing and wins over NetEase.

   Add a fixture to `app/src/test/resources/quality/provider/` and a check to the daily canary (`provider-canary.yml`).

---

## Feature 5: Blur on lines not being sung, faded edges

### 5.1 Implementation

**Blur** (in `LyricsKaraoke.kt`, where each line's colour and scale are already animated, around line 214):

```kotlin
val distance = abs(index - activeIndex)                   // 0 for the line being sung
val target = when {
    reduced || userScrolling || !synced -> 0.dp           // readable while browsing, and for plain lyrics
    distance == 0 -> 0.dp
    distance == 1 -> 1.dp
    distance == 2 -> 1.75.dp
    else -> 2.5.dp
}
val blur by animateDpAsState(target, GlassMotion.timed(GlassMotion.selectionMs, motion), label = "lyric blur")
Modifier.blur(blur, BlurredEdgeTreatment.Unbounded)       // before the existing graphicsLayer scale
```

- `userScrolling` is `!follow` from `LyricsPanel.kt:56`. Riff already turns `follow` off when the user drags the lyrics (line 70) and back on with the "Follow lyrics" button or a tap on a line. Pass it down to the line composable.
- `Modifier.blur` needs API 31, Riff's minimum.
- `BlurredEdgeTreatment.Unbounded` keeps the held-word glow (`Shadow` in `LyricsKaraoke.kt:313`) from being clipped. BitChord uses the same call in `PlayerLyrics.kt`.
- Only lines in view are composed (lazy list), so the cost is the handful on screen.

**Edge fade** (in `LyricsPanel.kt`, on the lyrics list):

```kotlin
Modifier
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(
            0f to Color.Transparent, .12f to Color.Black,
            .82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
```

This is the same technique `PlayerArtwork.kt` uses on the artwork. Add top and bottom content padding equal to the faded height, so the first and last lines can scroll into the clear part.

### 5.2 Settings and tests

- Add "Blur other lines" to the lyrics settings (on by default; off under reduced motion).
- JVM tests:
  - the blur target for distances 0–3;
  - blur 0 under reduced motion, while scrolling, and for unsynced lyrics.
- Screenshot test of the lyrics page with a fixed position.
- Benchmark the lyrics page in the `:benchmark` lane before and after.

Match: about 95 %.

---

## Feature 6: Animated song art

### 6.1 Findings

YouTube Music has no motion artwork, so the clips must come from somewhere else. BitChord combines three sources (`app/src/main/java/com/music/bitchord/data/canvas/`):

1. **Apple Music** (`AppleMusicCanvas.kt`): the `editorialVideo` field of Apple's catalog API (`amp-api.music.apple.com/v1/catalog`), with the read-only bearer token taken from Apple's web player. The clips are HLS. Search results are strictly scored, and a wrong clip is never shown.
2. **Spotify Canvas** (`SpotifyCanvas.kt`, `SpotifyToken.kt`): Spotify's internal canvas endpoint, with a token from the user's own `sp_dc` cookie.
3. **A community manifest** (`CommunityCanvas.kt`): a Cloudflare worker that lists clips.

Limits:
- **Coverage:** Apple has motion art for a minority of albums, mostly recent and popular.
- **Terms:** using Apple's web-player token, or Spotify's internal API, outside their own players isn't permitted. Either can stop working without notice.
- **Privacy:** each song's artist and album would go to Apple or Spotify.
- **Battery and data:** a few MB per clip.

**Decision (29 October 2026):** Riff doesn't use any of these sources. Apple's clips need the web player's token, or a MusicKit developer token shipped in a public app; Spotify's need its internal API; the community list copies both. The owner removed outside clips from the plan, so only Stage A below is built.

### 6.2 Implementation

**Removed (29 October 2026, eleventh revision):** Stage A was built, then removed at the owner's request after the phone checks. Kept below as the record of what was planned.

**Stage A: animate the cover on the phone, for every song, no new service.**
- New `LivingArtwork.kt`, drawn by `PlayerArtwork.kt` instead of the still image when enabled.
- Three styles:
  - **Drift:** a slow pan and zoom (scale 1.00–1.08 over 24 s, eased, reversing).
  - **Depth:** tilt parallax. Shift the image by up to 8 dp from the rotation sensor (shared with Feature 1 step 8).
  - **Mesh:** a moving mesh gradient from the four strongest colours in `ArtworkPalette.kt`, behind a slightly smaller cover. An AGSL shader on Android 13+; a slowly rotating radial gradient on 12.
- Pauses when the player is closed, the screen is off, or under reduced motion and battery saver.
- Setting: Player → Artwork motion: Off / Drift / Depth / Mesh.

Match: a moving cover for every song, offline, with no outside service; never Apple's own clips.

---

## Feature 7: Scrolling long titles in the player only

### 7.1 Where the title is drawn

| Place | File | Today | After |
|---|---|---|---|
| Full-screen player title | `NowPlaying.kt:350` (`Text(song.title, fontSize = 22.sp, maxLines = 1 or 2, overflow = Ellipsis)`) | Cut with "…" | **Scrolls** |
| Full-screen player artist | `NowPlaying.kt:356` | Cut with "…" | Unchanged (optional: scrolls too, if the owner wants) |
| Mini player | `PlayerPresentation.kt:169` | Cut with "…" | Unchanged |
| Open/close morph between the two | `PlayerPresentation.kt:423` | Cut with "…" | Unchanged: the scroll starts only once the player is fully open |
| Lists, queue, lyrics header, notifications | various | Cut with "…" | Unchanged |

### 7.2 Implementation

In `NowPlaying.kt`, replace the title's `maxLines`/`overflow` with a marquee, using Compose's built-in `Modifier.basicMarquee` (foundation, stable since Compose 1.7; Riff's BOM 2026.06 has it):

```kotlin
val scrollTitle = !reducedMotion && fontScale < 1.5f && playerFullyOpen && !queueOpen
Text(song.title, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
    maxLines = if (scrollTitle) 1 else if (fontScale >= 1.5f) 2 else 1,
    overflow = if (scrollTitle) TextOverflow.Clip else TextOverflow.Ellipsis,
    softWrap = !scrollTitle,
    modifier = Modifier.lyricsSharedBounds("song-title:${song.mediaId}")
        .graphicsLayer { alpha = sharedContentAlpha() }
        .onGloballyPositioned { if (song.mediaId == state.mediaId) onMorphElementBounds("title", it) }
        .then(if (scrollTitle) Modifier.marqueeFade().basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 2_000,     // the start of the title is read first
            repeatDelayMillis = 2_500,      // rests at the start between passes
            spacing = MarqueeSpacing(48.dp),
            velocity = 30.dp) else Modifier))
```

- **`basicMarquee` only moves when the text is wider than its box,** so short titles don't move.
- **`marqueeFade()`** fades the left and right 16 dp of the box while text is moving, as Apple does. It uses an offscreen layer plus a horizontal `DstIn` gradient, the same pattern as `PlayerArtwork.kt`.
- **`playerFullyOpen`** comes from the presentation state (the morph fraction `t` in `PlayerPresentation.kt` equals 1). Without it, the marquee would start during the morph. The morph measures the title's box with `onMorphElementBounds("title")`, and the box size doesn't change with the marquee, so the morph stays correct.
- **Song changes:** `songTransition.SongContentFade` makes a new `Text` per song, so each new title starts at its beginning after the 2 s delay.
- **Large text** (font scale ≥ 1.5) keeps the current two-line ellipsis: two lines are more readable than a moving line at that size.
- **Reduced motion** keeps the ellipsis.
- **TalkBack** already reads the full title (the text node holds it); the motion doesn't change semantics.
- **Battery:** the marquee only animates while the full-screen player is in front. Compose stops it when the activity stops.
- **Lyrics view:** the title moves to the lyrics header via `lyricsSharedBounds`. Turn the marquee off while lyrics are open (`queueOpen` or a lyrics-open flag), so the shared title doesn't move during that transition.

### 7.3 Tests

- A JVM Compose test that a long title in the full player has `basicMarquee` (check the semantics via a test tag such as `player_title_marquee`) and that the mini player and lists still use `TextOverflow.Ellipsis`.
- A test that reduced motion and font scale 1.5 turn the marquee off.
- A screenshot at time 0 (the start of the title, faded right edge).
- On the phone, check that the morph from the mini player still lines up.

Match: about 100 % of Apple Music's behaviour, which scrolls long titles in the player, pauses at the start, and fades the edges.

---

## Feature 8: "See all" on artist pages

### 8.1 Why Riff shows only a few

Two causes:

1. **The parser drops YouTube's "See all" link.** In `ProviderCatalog.kt` (around line 185), each artist shelf (`musicShelfRenderer` for Top songs, `musicCarouselShelfRenderer` for Albums, Singles, Videos, Featured on, Fans also like) becomes `ProviderHomeSection(title, cards)`. That class (`YouTubeMusicProvider.kt:27`) has no field for the shelf's link. YouTube sends the link in two places:
   - `musicShelfRenderer.bottomEndpoint.browseEndpoint` (Top songs): usually a playlist (`browseId` starting `VL`) of the artist's songs;
   - `musicCarouselShelfBasicHeaderRenderer.moreContentButton.buttonRenderer.navigationEndpoint.browseEndpoint` (carousels): a `browseId` (the artist's `UC…` channel, or `MPAD…`) plus `params` for a grid of all albums, singles or videos. Some shelves also put it on the header title's `navigationEndpoint`.
2. **The row shows at most 12 cards:** `HomeCardShelf(..., maxCards = 12)` in `HomeFeed.kt:289`. Artist shelves usually carry fewer than that anyway (5 top songs, about 10 albums), so the link is what matters.

### 8.2 Implementation

1. **Model.** Add the link to the section:

   ```kotlin
   internal data class ProviderMoreEndpoint(val browseId: String, val params: String? = null)
   internal data class ProviderHomeSection(val title: String, val cards: List<ProviderHomeCard>,
       val more: ProviderMoreEndpoint? = null)
   ```

   The default keeps every other user of `ProviderHomeSection` unchanged.
2. **Parser.** In `ProviderCatalog.kt`, add `catalogShelfMore(shelf)`, which reads, in order:
   1. `bottomEndpoint.browseEndpoint`;
   2. the header's `moreContentButton…browseEndpoint`;
   3. the header title's `navigationEndpoint.browseEndpoint`.

   It returns `browseId` and `params`, or null. Use it in the shelf visitor: `shelves += ProviderHomeSection(title, cards, catalogShelfMore(shelf))`. Use the same function for Home and mood shelves so they get "See all" too, if wanted.
3. **Fetching the full list.** `ProviderCatalogRepository` already has `requestCatalog(browseId, token, params)` and continuation paging:
   - **`VL…` (a playlist of songs):** open it in Riff's existing collection screen (`ImmersiveCollection.kt`). It already pages through continuations (`completeCollection`), supports sort, and shows songs with menus, Play and Shuffle. Nothing new to build.
   - **`UC…` or `MPAD…` with `params` (a grid of albums, singles or videos):** add `suspend fun artistShelfPage(more: ProviderMoreEndpoint, token: String?): Result<CatalogPage>` that calls `requestCatalog(more.browseId, token, more.params)`. `parseCatalogCards` already reads `musicTwoRowItemRenderer` grid items. The next page token comes from `catalogContinuation(root)`, the same as Home.
4. **Screen.** New `ArtistShelfScreen.kt`:
   - title "Albums · <Artist>" (the shelf title plus the artist name);
   - `LazyVerticalGrid(GridCells.Adaptive(150.dp))` of cards, using the same card composable as `HomeCardShelf`;
   - loads the next page when within 6 items of the end;
   - a sort chip if YouTube's grid has one (`sortEndpoints` already exists on `CatalogPage`);
   - tapping a card uses `openCatalogCard`, as everywhere else.

   Videos and songs as a list rather than a grid: choose by the first card's `kind`.
5. **Artist page.** In `ImmersiveCollection.kt` (around line 209), pass an `onMore` to `HomeCardShelf` when `shelf.more != null`. `HomeCardShelf` gets an optional `onMore: (() -> Unit)?`:
   - when set, show a "See all" text button (or a chevron) at the right of the heading;
   - add a last "See all" card at the end of the row.

   Top songs on the artist page shows 5 rows; add a "See all songs" row under them. Navigation: add an `ArtistShelf(browseId, params, title)` destination to the shell's navigation.
6. **Strings:** `see_all`, `see_all_songs`, `x_albums_by_y` and so on, in `res/values/strings.xml`. Riff routes all text through `string(R.string…)`.
7. **When there's no link:** some shelves (Fans also like, for example) may come without one. Then no "See all" is shown; never guess a link.
8. **Tests:**
   - Parser: record an artist page response into `app/src/test/resources/quality/provider/artist-page.json`, with a Top songs `bottomEndpoint` and an Albums `moreContentButton`. Assert `more` is read for both and is null for a shelf without one.
   - Repository: with a fake HTTP server, `artistShelfPage` follows continuations and stops when the token repeats.
   - Screen: the "See all" button appears only when `more` is set, and opens the right destination.
   - Canary: add an artist page check to `provider-canary.yml`, so a change in YouTube's layout shows up in the daily run.

Match: the same lists YouTube Music shows under its own "See all".

---

## Feature 9: Skipping the silence at the end of a song

### 9.1 Findings

- **Riff today:** Riff has no silence handling. Its only processor in the audio chain is `VolumeBoostProcessor` (`VolumeBoostProcessor.kt:67`, installed by `riffAudioRenderers`). The crossfade starts from the duration YouTube states (`shouldStartCrossfade`, `Crossfade.kt:151`), so a song with 10 s of silence at the end crossfades into that silence, or plays it in full with crossfade off.
- **BitChord** uses Media3's built-in `SilenceSkippingAudioProcessor` through `player.skipSilenceEnabled` (`PlaybackService.kt`; the setting in the owner's screenshot, "Trim gaps longer than a second"). That trims **every** silence in a song, including pauses in the middle that are part of the music. The owner asked for something narrower: **only the silence at the end**. That needs Riff's own detector.

### 9.2 How it works: two paths

**Path A: the end is known before it is reached** (downloaded songs, on-device files, and streams whose last part is in Riff's cache).
1. **New `TrailingSilenceScanner.kt`.** When a song becomes the current or next item, a background job on the container's IO scope:
   - opens the file (or the cached bytes) with `MediaExtractor` and seeks to 45 s before the end;
   - decodes to PCM with `MediaCodec`;
   - measures loudness in 50 ms blocks (RMS in dBFS, the louder channel).
2. **Finding the last sound:** the last block louder than **−50 dBFS**. Everything after it is "trailing silence" if it lasts at least **2 s**.
3. **Stored result:** `effectiveEndMs = lastAudibleMs + 300 ms` (a short tail, so a reverb or fade isn't cut). Store it per video ID in `library-state.db` next to the history (a small `track_end` table: `videoId`, `durationMs`, `effectiveEndMs`, `scannedAt`), so it is scanned once.
4. **Using it:** the service treats `effectiveEndMs` as the song's end.
   - Crossfade off: at `effectiveEndMs`, call `seekToNextMediaItem()`.
   - Crossfade on: pass `effectiveEndMs` instead of `durationMs` to `shouldPrepareCrossfade` and `shouldStartCrossfade`, so the blend finishes as the music ends rather than in the silence.
   - The scrubber and the time shown keep the real length.

**Path B: the end is found while playing** (streams that aren't cached).
1. **New `SilenceWatchProcessor`**, a pass-through `BaseAudioProcessor` added beside `VolumeBoostProcessor` in `riffAudioRenderers`. It doesn't change any sample. Per 50 ms block it records whether the audio was below −50 dBFS, and publishes the position where the current quiet run began. These are atomic fields read by the service, as `processedFrames` already is.
2. **Rule:** if the quiet run has lasted **1.5 s**, and it started in the last **25 %** of the song or the last **60 s**, whichever is shorter, the service skips to the next song (or starts a short 1.5 s crossfade if crossfade is on).
3. **What the listener hears:** 1.5 s of silence instead of all of it.

### 9.3 Guards

- **Hidden tracks.** Some albums put a second song after minutes of silence. Path A sees it: the scan finds sound again before the end, so no skip, because only silence that runs to the very end counts. Path B can't see ahead, so it is only used when the silence starts within the last 60 s. Long hidden-track gaps are almost always longer than that, so they keep playing.
- **Quiet endings.** −50 dBFS is far below a fading outro (classical and ambient fades usually sit around −35 to −45 dBFS before the true end). The threshold is a constant in one place, so it can be tuned on real songs.
- **Repeat one, the last song in the queue, and podcasts:** never skip; reuse `crossfadeSkipsItem` and the existing `repeatOne` checks.
- **The user seeking** into the silence cancels Path B's timer for that song.
- **Diagnostics:** log `silence_skip from=… to=… path=A|B` to the playback event log. Add the new keys to the allowed list in `DiagnosticEvent.kt`.

### 9.4 Settings and tests

- **Settings:** Playback → "Skip silence at the end of songs" (off by default for the first release, so it can be judged).
- **Optional second setting:** "Skip all silence (Media3)", which turns on `skipSilenceEnabled` for those who want BitChord's behaviour.
- **JVM tests:**
  - the scanner on generated PCM: music then 8 s of silence; music, silence, then a hidden track; a quiet fade;
  - the Path B rule with fake positions;
  - the crossfade decisions taking `effectiveEndMs`;
  - the guards (repeat one, last item, podcast).
- **Emulator lane:** a fixture song with 6 s of trailing silence advances within 0.5 s of the music ending (Path A) and within 2 s (Path B).

Match: exact for downloaded and cached songs. On streams, about 1.5 s of silence is still heard.

---

## Feature 10: A built-in equalizer, with the phone's equalizer one tap away

### 10.1 Findings

- **Riff today:** Riff has no equalizer. `AudioSessionEffects.kt` already announces the player's audio session (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`), so the phone's own equalizer, Dolby or other sound modes can already attach. Riff just has no button to open them.
- **BitChord** has its own equalizer, and the owner wants Riff's to look and work like it (screenshot of 28 Oct 2026: "Equalizer" switch, **Dynamic | Manual** tabs, a "Tone" dot grid with a round puck, two readouts, and **Broad / Focused**). Sources:
  - `app/…/ui/screens/EqualizerScreen.kt`, 923 lines;
  - `shared/…/playback/EqualizerCurve.kt` (`EqLayout`, `toneCurve`, `preampFor`);
  - `app/…/playback/EqualizerProcessor.kt`.

  How it's built:
  - **Ten filter sections**, all inside the player's audio chain:
    - **seven** for the Manual tab: 60 Hz low shelf, bells at 150 Hz, 400 Hz, 1 kHz, 2.5 kHz and 6 kHz, and a 14 kHz high shelf, Q 1.0, ±12 dB;
    - **three** for the Dynamic tab: a 250 Hz low shelf, a 1 kHz bell and a 4 kHz high shelf.
  - **Both tabs drive the same filters,** so switching tabs glides instead of clicking.
  - **Filters:** state-variable filters (trapezoidal SVF), chosen because gain and width changes don't shift the centre frequency and stay stable while a finger drags.
  - **Extras:** a pre-amp worked out from the summed response; bass and treble tone controls; left/right balance with a centre detent.
  - **Licence:** BitChord is GPL-3.0, like Riff, so its equalizer code may be adapted with attribution in NOTICE.md and the file headers.
- **Two kinds of equalizer on Android:**
  - Android's `android.media.audiofx.Equalizer`: phone-dependent, often 5 bands, and some phones refuse it.
  - Riff's own filters in the audio chain: the same on every phone.

  The plan uses Riff's own filters.
- **"Open the phone's equalizer inside Riff":** Android opens another app's or the system's equalizer as **its own screen**. Riff can't draw it inside its own screens. What Riff can do: a "Phone equalizer" row on Riff's equalizer screen, which opens it with the right audio session, and returns to Riff with the back button.

### 10.2 Implementation

1. **The filters: new `EqualizerProcessor.kt`.**
   - A `BaseAudioProcessor` in `riffAudioRenderers`, placed **before** `VolumeBoostProcessor`, so the booster's limiter catches peaks the equalizer adds.
   - **Ten sections, as in BitChord's `EqLayout`:**

     | Slot | Owner | Type | Frequency | Q | Range |
     |---|---|---|---|---|---|
     | 0 | Manual | Low shelf | 60 Hz | 1.0 | ±12 dB |
     | 1–5 | Manual | Bell | 150 Hz, 400 Hz, 1 kHz, 2.5 kHz, 6 kHz | 1.0 | ±12 dB |
     | 6 | Manual | High shelf | 14 kHz | 1.0 | ±12 dB |
     | 7 | Dynamic | Low shelf | 250 Hz | Broad 0.5 / Focused 0.9 | ±6 dB |
     | 8 | Dynamic | Bell | 1 kHz | Broad 0.7 / Focused 2.2 | ±6 dB |
     | 9 | Dynamic | High shelf | 4 kHz | Broad 0.5 / Focused 0.9 | ±6 dB |

     Slots a tab doesn't own stay at 0 dB. A section at 0 dB is skipped entirely, so flat stays bit-exact.
   - **Filters:** trapezoidal state-variable filters (Zavalishin's TPT SVF), as BitChord uses, or RBJ biquads (Audio EQ Cookbook). SVF is preferred because it stays stable and click-free while gain and Q change every block.
   - **Processing:** float, per channel, on the 16-bit PCM Riff's chain carries (`onConfigure` already requires `ENCODING_PCM_16BIT`).
   - **Gain and Q changes** move towards their targets over about 30 ms (a one-pole smoother per section, updated every 32 samples), so dragging the puck or a fader glides.
2. **Keeping it from clipping (BitChord's `preampFor`):**
   - Sum every section's response at 64 log-spaced frequencies from 20 Hz to 20 kHz, take the peak, and set the pre-amp to −peak dB. The largest single band would under-read it, because overlapping bands add up.
   - After that, the existing limiter in `PcmVolumeBoost` (the booster already limits at full scale).
   - **Balance:** a left/right trim, constant-power: `L = cos(π(b+1)/4)·√2`, `R = sin(π(b+1)/4)·√2`, with b from −1 to +1. It has a detent at centre and doesn't run on mono files.
3. **Crossfade:** the tail player used during crossfades (`tailAudioEffects` in `AudioTransitionCoordinator.kt`) is built with the same renderers. Give it its own `EqualizerProcessor` with the same settings, so both songs sound the same during a blend.
4. **Settings store: new `EqualizerSettings.kt`**, like `CrossfadeSettings`: a preferences file and a `StateFlow`. It holds:
   - on/off;
   - the mode (Dynamic or Manual);
   - the tone puck (x, y in −5..5) and Broad/Focused;
   - the seven manual gains and the manual preset;
   - balance;
   - optionally **a profile per output** (phone speaker, wired, Bluetooth, USB), picked from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` and an `AudioDeviceCallback`. Headphones and the speaker usually need different curves.
5. **The Dynamic tab, the tone pad** (`toneCurve`, as in BitChord):
   - **Grid:** an 11 × 11 dot grid with a round puck. The centre (0, 0) is flat. The finger position snaps to whole steps: x and y each run −5..+5, at **1.2 dB per step**, so ±6 dB at the edges.
   - **x (left warm, right bright):** a tilt. The low shelf gets `−x·1.2 dB` and the high shelf `+x·1.2 dB`, so left raises the lows and lowers the highs.
   - **y (down scoops the middle, up brings it forward):** the 1 kHz bell gets `+y·1.2 dB`.
   - **Broad / Focused** change only Q: Broad (shelves 0.5, bell 0.7) moves wide stretches for a fuller change; Focused (shelves 0.9, bell 2.2) works on narrower stretches, so it changes less around what it moves.
   - **Readouts under the pad:** "≋ x" (the tilt) and "↗ y" (the contour), signed numbers, 0 shown without a sign.
   - **Drawing:** the grid is drawn on one `Canvas` (121 dots), not as 121 composables, so dragging stays smooth.
     - Dots on the puck's row and column are brighter: alpha 0.55 there, 0.25 elsewhere.
     - The puck springs between dots (`spring(dampingRatio = 0.7f, stiffness = 600f)`), with a light haptic tick at each new step.
     - Taking the gesture on first touch (`awaitEachGesture` + `awaitFirstDown`) means a drag inside the pad never scrolls the page.
   - **Text** under the card, as in the screenshot: "Drag left for a warmer sound and right for a brighter one; down scoops the middle, up brings it forward. Focused works on narrower stretches, so it changes less around what it moves."
6. **The Manual tab:**
   - **Seven vertical faders,** drawn on a canvas (not rotated Material sliders, whose touch geometry stays horizontal).
     - The fill runs **from the centre line**, because the value is signed.
     - Half-decibel steps, with a wider **detent at 0 dB** (within ±0.75 dB snaps to 0), because flat is where a band is dragged back to most.
   - **Labels:** "60", "150", "400", "1k", "2.5k", "6k", "14k" under the faders; "+6", "−3.5" above them.
   - **Presets** list above the faders: Flat, Bass boost, Bass reduce, Treble boost, Vocal, Rock, Pop, Electronic, Hip-hop, Jazz, Classical, Acoustic, Loudness, Small speakers, and "Custom", saved when a fader moves.
   - **Balance** row at the bottom.
7. **The screen: new `EqualizerScreen.kt`** under Settings → Playback → Equalizer, and from the player's more menu. Top to bottom, as in the screenshot:
   - a card with the equalizer icon, "Equalizer" and a switch;
   - the caption "Shapes the sound inside Riff, before it reaches the system";
   - a two-segment **Dynamic | Manual** control (Feature 2's glass segmented control; the selected segment is a white pill with black text);
   - then the tab's content: the **"TONE"** section card (pad, readouts, Broad / Focused rows with a tick on the selected one) or the faders.
   - Below both tabs: a small curve preview (the summed response, the same 64 points as the pre-amp), the output profile in use ("Headphones", "Speaker"), and the phone equalizer row (step 8).
   - Switching tabs **glides** between the two curves over 250 ms. Dynamic and Manual each keep their own settings; only the selected tab's sections are active.
8. **The phone's equalizer row:** "Phone equalizer: open your phone's sound settings".
   - The intent is `Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)` with `EXTRA_AUDIO_SESSION` (the player's session from `AudioSessionEffects`), `EXTRA_PACKAGE_NAME`, and `EXTRA_CONTENT_TYPE = CONTENT_TYPE_MUSIC`, started with `startActivityForResult` (the platform requires a result).
   - Android 11+ hides other apps' screens unless declared. Add to the `<queries>` block in `AndroidManifest.xml`: `<intent><action android:name="android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" /></intent>`.
   - Check `intent.resolveActivity(packageManager)` first. If nothing answers (some phones have no system equalizer), show the row disabled with "Your phone has no equalizer of its own".
   - **When both are on,** Riff's and the phone's curves add together. Show a one-line note under the row: "Using both adds their effects together."
9. **Docs:** features.md (Equalizer), NOTICE.md (code adapted from BitChord, GPL-3.0), and the "Playback" part of architecture.md: the audio chain becomes `EqualizerProcessor → VolumeBoostProcessor`, plus `SilenceWatchProcessor` from Feature 9.

### 10.3 Tests

- **JVM tests on generated sine waves through the processor:**
  - a +6 dB Manual band at 1 kHz raises a 1 kHz tone by 6 ± 0.3 dB and leaves 100 Hz within 0.5 dB;
  - the tone pad at (−5, 0) raises 100 Hz and lowers 8 kHz by about the same amount;
  - at (0, +5), Focused raises 1 kHz by 6 ± 0.3 dB and 250 Hz by less than Broad does;
  - `preampFor` equals minus the measured peak of the summed response, within 0.2 dB;
  - flat leaves the signal unchanged bit for bit;
  - a full-scale tone with +12 dB doesn't clip after the pre-amp and limiter;
  - a gain change mid-buffer has no step larger than the smoothing allows.
- **Settings tests:** the store round-trips; the right profile is picked per output.
- **UI tests:**
  - the puck snaps to steps and gives one haptic per step;
  - a drag inside the pad doesn't scroll the page;
  - the fader detent snaps to 0;
  - switching tabs keeps each tab's values.
- **UI test:** the phone equalizer row is disabled when no activity resolves. A test intent resolver covers both cases.
- **Phone check:** the phone's equalizer opens and returns, and a band change is heard.

Match: Riff's own equalizer is complete and identical on every phone. The phone's equalizer is whatever that phone provides, opened as its own screen.

---

## Feature 11: A dark fade at the top and bottom of the screen

### 11.1 What the screenshot shows

- **Top:** content scrolling up under the status bar ("Skip silence", "Reduce dynamic blur") fades smoothly into near-black over about the status bar's height plus 24 dp. The clock and icons stay readable, and there's no hard edge.
- **Bottom:** the same fade, darker towards the bottom edge, behind the floating home button, mini player and search button ("Lyrics sources" fading out under them).
- **Strength:** "slightly dark", as the owner says. Text under the fade is still faintly visible, not hidden.

### 11.2 Riff today

- Riff draws edge to edge: `MainActivity.kt:17` makes the status bar transparent, and screens add `statusBarsPadding()` themselves.
- There is no shared fade. Some screens have their own: `speedDialScrim` in `HomeFeed.kt:67`, `moodScrim` in `MoodBackdrop.kt:23`. Content therefore scrolls under the status bar with a hard edge, or none.

### 11.3 Implementation

1. **New `EdgeFade.kt`** with one composable, drawn by the shell in `RiffApp`/`ShellChrome.kt` **above the screen content and below the glass chrome**:

   ```kotlin
   @Composable internal fun EdgeFades(topInset: Dp, bottomHeight: Dp, strength: Float = 1f) {
       Box(Modifier.fillMaxSize()) {
           Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topInset + 28.dp)
               .background(Brush.verticalGradient(edgeFadeStops(.72f * strength), startY = 0f)))
           Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomHeight)
               .background(Brush.verticalGradient(edgeFadeStops(.62f * strength).reversed())))
       }
   }
   /** Twelve stops on an ease-out curve, so a near-flat colour doesn't band. */
   internal fun edgeFadeStops(peak: Float) = Array(12) { i -> val x = i / 11f
       x to Color.Black.copy(alpha = peak * (1f - x).let { it * it * (3 - 2 * it) }) }
   ```

   - Top height: the status bar inset plus 28 dp.
   - Bottom height: the floating chrome's height, from `ChromeGlassGeometry`, plus the navigation inset plus 24 dp.
   - Peak darkness 0.72 at the top edge and 0.62 at the bottom ("slightly dark"). Put these in `DesignTokens.kt` (`RiffContrast.edgeFadeTop` / `edgeFadeBottom`).
   - Twelve stops, as BitChord's `TopFadeBlur.kt` uses, so the gradient doesn't band.
2. **Order of layers:** draw the fades into the **backdrop layer** the glass samples (Feature 1, step 3), so the glass tab bar refracts the darkened content. Otherwise the bar would sit on a darker band than what it shows, which looks like a mistake.
3. **Touches pass through:** the fades have no click or pointer handling, and `clearAndSetSemantics {}` keeps them away from TalkBack.
4. **Where not to draw:**
   - the full-screen player: it has its own artwork treatment (`PlayerArtwork.kt`, Q18-002);
   - the lyrics page: it has its own edge fade (Feature 5);
   - full-bleed artwork headers (collection and artist pages) while the header is in view. There, fade in the top fade only once the header has scrolled under the status bar, driven by the list's scroll offset, as BitChord does in `MainActivity.kt:3389`.

   Remove per-screen scrims that become duplicates (check `speedDialScrim` and `moodScrim`; keep them only where they serve text over a picture).
5. **Optional: blur under the top fade,** as in BitChord's `TopFadeBlur` (which uses the Haze library). With Feature 1's backdrop already recorded, a top strip can apply a blur that ramps from 12 dp at the edge to 0. Android 13+ only, off under reduced transparency and the lower glass quality levels. Not needed for the screenshot's look.
6. **High contrast:** raise the peak to 0.9 at the top, so the status bar icons always have a dark base.

### 11.4 Tests

- **Screenshot checks** of Home, Library and Settings scrolled half-way, at API 31 and 36.
- **A test that the fades take no touches:** a tap at the top 20 dp still reaches a list row under it.
- **A semantics test:** no node from the fades.
- **Frame time:** two gradient boxes cost nearly nothing, but include them in the benchmark run for Feature 1.

Match: about 100 % of the screenshot.

---

## Feature 12: Encore, a listening statistics page with stories

**Name: Encore.** In music, an encore is the part the audience asks for again. That's what the page shows: what you came back to. It's short, fits Riff's naming, and doesn't borrow Apple's "Replay" or Spotify's "Wrapped".

### 12.1 What Riff records today, and what's missing

| Needed for Encore | Riff today | Gap |
|---|---|---|
| When a song was played | `history_events.played_at` (`LibraryState.kt:32`), one row per play, counted after about 5 s (`LogicalPlays`, `ListeningHistory.kt`) | None |
| Title, artist, artwork | `title`, `artist`, `artwork` | Artist is a display string ("Elton John, Bernie Taupin"); no artist ID |
| Song length | `duration_ms` | None |
| **How long it was actually heard** | Not stored. The service computes it already: every 5 s tick in `MusicPlaybackService.kt:304–310` works out `delta` (capped at 5 s, so pauses don't count) and passes it to the offline store for Smart Downloads | Store it per play |
| **Album** | Not stored, though `ProviderTrack` carries `album` and `albumId` | Store it |
| **Genre** | Nothing Riff talks to states one; YouTube Music doesn't give a genre per song | An optional outside lookup (12.4) |
| Skipped or finished | Not stored | Store it |
| Where it came from (stream, download, on-device) | Implied by the media ID prefix (`yt:` / `local:`) and the offline store | Store it |
| How long history is kept | 30 / 90 / 365 days or forever (`historyRetentionDays`); at most 100,000 rows per owner (`HISTORY_ROW_CAP`) | "Lifetime" means "as far as history goes" (12.3) |
| Per account | Each owner has its own `scope` | None: Encore follows the same scopes |

**How BitChord does it** (`data/stats/ListeningRecorder.kt`, `ListeningStats.kt`, `ArtistFacts.kt`, `ui/replay/`):
- Listening time is summed from the playback ticks, never from the song position, so seeking and long pauses can't inflate it. Riff's `delta` already works that way.
- A play counts after half the song or 4 minutes, the scrobbling rule.
- Genres come from Last.fm's `artist.getTopTags`, behind a setting, and only tags on a fixed list of real genres count.

### 12.2 What Encore shows

**Periods.** A glass segmented control at the top: **Day · Week · Month · Year · All time**.
- Under it, a period stepper ("‹ This week ›", "‹ October 2026 ›"). Swipe left or right on the page to step back through earlier periods.
- Weeks start on the locale's first day (`WeekFields.of(Locale)`).

**For every period:**

1. **Headline card.**
   - Listening time, as a number counting up ("14 h 32 m").
   - Plays, different songs, different artists, different albums.
   - The change against the previous period ("▲ 2 h 10 m on last week"), in green or grey.
2. **Listening time chart,** an interactive bar chart whose bars depend on the period:
   - Day: 24 hourly bars.
   - Week: 7 days.
   - Month: each day.
   - Year: 12 months.
   - All time: years, or months if under 2 years.

   Dragging along the chart moves a highlight from bar to bar, with a light haptic tick and a label ("Tue 21 Oct · 2 h 05 m"). Tapping a bar opens that day, month or year as its own period. Bars grow from zero when the period changes (no animation under reduced motion).
3. **Peak hours:**
   - a 24-hour **radial clock** (Riff already has an hours clock in its design tokens), each hour's segment as long as the time listened in it;
   - a **week × hour heatmap** (7 rows × 24 columns) for Week and longer.

   Tap a cell for "Fridays at 22:00 · 3 h 40 m in total". The busiest hour is named: "Your golden hour: 10 PM".
4. **Top songs, albums, artists and genres.**
   - Each list shows 5, with a "See all" (the Feature 8 screen pattern) for up to 100.
   - A toggle sorts by **minutes** or **plays**.
   - Each row has a rank-movement mark against the previous period: ▲3, ▼1, NEW.
   - Tapping opens the song, album or artist; long-press offers Play, Play next, and "Your history with this" (12.2, extras).
   - Genres also as a **donut chart**: tap a slice to list that genre's artists.
5. **Listening habits:**
   - **streak:** consecutive days with at least 10 minutes;
   - **longest session:** plays without a gap over 20 minutes;
   - **discovery:** the share of time on artists first heard in this period;
   - **completion:** how often songs are finished rather than skipped;
   - **most repeated:** the most plays of one song in a single day;
   - **sources:** streaming, downloads and on-device files.
6. **A calendar heatmap** (Month, Year, All time): one square per day, shaded by time listened. Tap a day to open it.
7. **"Watch your Encore":** a ring around the top artist's photo at the top of the page, like an Instagram story ring, which opens the stories (12.5). The ring is coloured while the period's story hasn't been watched.

**Extras:**
- **Your history with this:** from any song, album or artist (its menu, and its own page). First play, total plays and time, a plays-over-time chart, your rank for it this year.
- **On this day:** what you played a year ago today, shown when there is something. Tap to play it.
- **Milestones:** quiet badges for 10 / 100 / 1,000 hours, a 1,000th play, a 30-day streak, 100 different artists. They show in the stories when reached and in a "Milestones" row.
- **Listening personality:** one label per period from the habits, with the rule shown underneath. Examples:
  - "Night owl": over 40 % after 22:00;
  - "Explorer": discovery over 30 %;
  - "Loyal": top artist over 25 % of time.
- **Forgotten favourites:** top 50 songs of all time not played in 60 days, with a Play button.
- **Weekly goal (optional):** set hours per week; a progress ring on the headline card.
- **Make a playlist from this:** "Play your top 25" as a queue. "Save to YouTube Music" uses Riff's existing playlist create (account writes are already confirmed by the user in Riff).
- **Home widget:** today's listening time and top song, beside Riff's existing widgets.
- **Weekly recap notification (opt-in, off by default):** "Your week in Encore is ready", on Sunday evening.
- **Export:** Encore's numbers in Riff's existing Export (`LocalDataTransfer`), as JSON, plus a CSV of top lists.

### 12.3 Data: what to add and how

1. **More columns on each play.** A new `LibraryStateDb` version that adds to `history_events`:
   - `listened_ms INTEGER NOT NULL DEFAULT 0`;
   - `album TEXT`, `album_id TEXT`, `artist_id TEXT`;
   - `ended INTEGER` (0 still playing, 1 finished, 2 skipped, 3 stopped);
   - `source TEXT` (`stream` / `download` / `device`).

   Follow the existing migration pattern and its kill-at-every-checkpoint tests (DAT03). Old rows keep 0 listened time. Encore estimates them as `min(duration_ms, 3 min)` and marks such totals "estimated" in small text.
2. **Writing listened time.**
   - In the service's 5 s loop, add `delta` to an in-memory total for the current play's `eventId` (kept from `recordHistory`).
   - Every 30 s, and when the song changes or the service stops, write `UPDATE history_events SET listened_ms=listened_ms+? WHERE scope=? AND event_id=?`. Writes are few, and nothing is lost but the last 30 s on a crash.
   - Set `ended` from `onMediaItemTransition`: `AUTO` means finished; `SEEK` before 50 % of the song means skipped.
   - On-device audio gets the same treatment; today `offline.listened` is called only for `yt:` items.
3. **Daily totals,** so lifetime queries stay fast and survive the 100,000-row cap. New tables:
   - `stats_daily(scope, day TEXT, kind TEXT, key TEXT, title TEXT, subtitle TEXT, artwork TEXT, plays INT, listened_ms INT, PRIMARY KEY(scope, day, kind, key))`, where `kind` is song / album / artist / genre;
   - `stats_hourly(scope, day TEXT, hour INT, listened_ms INT, PRIMARY KEY(scope, day, hour))`.

   They're updated in the same transaction as each listened-time write. Days are local dates, so a play near midnight is filed where its ticks fell (an hour's time goes to that hour, which is what peak hours needs).

   A rebuild job (`EncoreRollup.rebuild(scope)`) recomputes them from `history_events` after a migration, an import, or a history deletion, on the container's state scope.
4. **Artists.** The artist string often names several people. Split it the way `playerArtistLabel` reads it (", ", " & ", " feat. ", " x "), and use `artist_id` (the `UC…` channel) as the key when present, so one artist under two spellings counts once. Each credited artist gets the full time (as Last.fm does); the "Top artists" footnote says so.
5. **Retention and deletion.**
   - **Clearing history clears Encore.** Encore is part of listening history, so `HistoryRepository.clear` also deletes the scope's `stats_*` rows, and advances the same history epoch.
   - **Retention (30 / 90 / 365 days):** the daily totals are removed with the plays by default. "All time" then means "since your history begins", and the page says so ("since 27 July 2026").
   - **For the owner to decide:** a setting "Keep Encore totals beyond history retention" that keeps only the daily totals (no individual plays) for lifetime numbers. It's off by default and explained in Privacy and data.
6. **Turning history off** (Settings → Privacy and data → Save listening history) also stops Encore. The page then says why it's empty and links to the setting.
7. **Performance.** Every Encore query runs on the IO dispatcher against the daily tables (at most 366 rows × kinds per year), with indexes on `(scope, day)`. The page renders from a `StateFlow<EncoreState>` and never blocks the main thread.

### 12.4 Genres (optional, off by default)

Nothing Riff already uses states a genre, so it needs one more service.

- **MusicBrainz, recommended.** Free, no account or key.
  - The lookup: `https://musicbrainz.org/ws/2/artist/?query=artist:"<name>"&fmt=json`, then the best match's `tags` / `genres`.
  - Its rules: at most 1 request per second, and a `User-Agent` naming the app with a contact address.
- **Last.fm `artist.getTopTags`,** as BitChord does. Needs an API key built into the app.
- **How the lookup works:**
  - once per artist, cached for 90 days in `artist_genres(artist_key, genres, source, fetched_at)`;
  - a background job runs only on Wi-Fi and only for artists in your top 200;
  - keep only tags on a fixed list of real genres (pop, rock, hip hop, R&B, electronic, house, techno, indie, metal, jazz, classical, country, folk, reggae, latin, K-pop, J-pop, bollywood, soundtrack, ambient, lo-fi, and their main sub-genres), as BitChord does, so "seen live" or "favourites" never shows as a genre;
  - an artist's time is split across its top 2 genres.
- **Setting:** Encore → "Find genres for your artists", off by default, with the line "Sends artist names to MusicBrainz". Add the destination to `docs/privacy.md`.
- **Off:** the genre list and donut are hidden. There's no empty chart.

### 12.5 Stories

Full-screen, one per period (today, this week, this month, this year, all time), opened from the ring, from the headline card, or from a long-press on any period chip.

**Controls (as Instagram):**
- segmented progress bars at the top, one per slide, about 6 s each;
- tap the right third for the next slide, the left third for the previous;
- press and hold to pause;
- swipe down to close; swipe left or right to move to the previous or next period's story.

TalkBack reads each slide's text, and the bars announce "Slide 3 of 10". Under reduced motion, slides don't advance by themselves and motion is replaced by fades.

**Slides** (each skipped when it has no data):

| # | Slide | Content |
|---|---|---|
| 1 | Intro | "Your week in Encore", the dates, a slow gradient from the top song's artwork palette (`ArtworkPalette.kt`) |
| 2 | Listening time | The big number counting up; "That's 2 h more than last week" |
| 3 | Top song | Its artwork rising and turning gently; plays and minutes. Optional: the song plays quietly behind the story (off by default; never if something else is playing) |
| 4 | Top 5 songs | Artwork row in rank order, each sliding in |
| 5 | Top artist | Artist photo (from the artist page), share of your time ("31 % of your week") |
| 6 | Top albums | A fan of covers |
| 7 | Top genres | The donut drawing itself (only when genres are on) |
| 8 | Golden hour | The radial clock filling to the busiest hour: "Most of your listening happens at 10 PM" |
| 9 | Habits | Streak, discovery, personality label with its rule |
| 10 | Milestone | When one was reached in the period |
| 11 | Summary card | One shareable card: listening time, top song, top artist, top genre, personality, and Riff's two waves in the corner (as the lyric card has) |

**Sharing.**
- Every slide has a share button. It draws the slide at **1080 × 1920** into a bitmap: Compose `rememberGraphicsLayer()` → `toImageBitmap()`, then pause the animations at their final frame so the picture is complete.
- It then shares the bitmap through the same path as lyric cards (`LyricsShare.kt`: a file under Riff's `FileProvider`, then `Intent.ACTION_SEND`), or saves it to Pictures/Riff with no permission, as lyric cards already do.
- Sharing straight to an Instagram story needs a Facebook app ID registered with Meta. Until there is one, the system share sheet does the job, and Instagram is one of its targets.

**Implementation files:**
- `EncoreStories.kt`: the pager, gestures, progress bars and slide scheduler;
- `EncoreSlides.kt`: one composable per slide;
- `EncoreShare.kt`: bitmap capture, reusing `shareLyricCard`'s file and intent code.

### 12.6 Screens and code

| File | What it holds |
|---|---|
| `EncoreRepository.kt` | Reads the daily tables; builds `EncorePeriod`, `EncoreSummary`, top lists, chart series, habits, milestones; previous-period comparison |
| `EncoreRollup.kt` | Updates the daily tables on each listened-time write; rebuild job; deletion with history |
| `EncoreGenres.kt` | The optional MusicBrainz lookup, its cache and the genre list |
| `EncoreScreen.kt` | The page: period control, stepper, headline, sections, empty states |
| `EncoreCharts.kt` | Bar chart, radial clock, heatmaps and donut, drawn with Compose `Canvas`, each with drag or tap selection, haptics and semantics (each bar or cell is an accessibility node with its value) |
| `EncoreStories.kt`, `EncoreSlides.kt`, `EncoreShare.kt` | Stories (12.5) |
| `EncoreDetail.kt` | "Your history with this" for a song, album or artist |
| `EncoreWidget.kt` | The home widget |

- **Navigation:** add `Route.Encore(period)` and `Route.EncoreDetail(kind, key)` to `ShellNavigation.kt`.
- **Entry points:**
  - a large "Encore" card at the top of Library, showing this week's time and the story ring;
  - a row in the Home feed on Sundays ("Your week in Encore");
  - the "Your history with this" item in song, album and artist menus.
- **Look:** glass period control and cards (Features 1 and 2), Inter type tokens (Feature 3), and the artwork palette for colour. Charts use Riff's pink for the selected bar and white at 30 % for the rest.
- **Strings:** all through `string(R.string…)`, with plurals for counts (`<plurals>`) and locale-aware durations and dates.

### 12.7 Tests

- **Recording:**
  - listened time adds up across ticks and ignores pauses (ticks capped at 5 s);
  - a seek doesn't add time;
  - the 30 s flush and the flush on song change;
  - `ended` values from each transition reason;
  - on-device songs counted.
- **Daily tables:**
  - totals match a rebuild from events;
  - plays across midnight and across a daylight-saving change land on the right days and hours;
  - week boundaries per locale;
  - clearing history clears them, with the epoch advanced;
  - retention removes old days;
  - an import rebuilds them.
- **Artists:** splitting rules, merging by channel ID, a credited group counted once.
- **Genres:** the vocabulary filter; the cache; nothing is sent when the setting is off (a fake server sees no request).
- **Repository:**
  - fixed fixtures of a week, month and year produce the expected tops, deltas, streaks, discovery, golden hour and personality;
  - empty and one-play periods.
- **UI:**
  - semantics for chart values;
  - the stories' tap zones, hold and swipe;
  - reduced motion stops auto-advance;
  - a share produces a 1080 × 1920 bitmap.
- **Performance:** opening All time with 100,000 plays and five years of daily rows stays under 300 ms to first draw on the phone (the benchmark lane).
- **Privacy:** an export contains Encore; deleting an account's data removes it.

Match: the same kind of insight as Apple Music Replay and Spotify Wrapped, available every day rather than once a year, with all data kept on the phone (except the optional genre lookup).

---

## Feature 13: Opening YouTube Music links in Riff

### 13.1 Findings

- **Riff today:** Riff's `MainActivity` has only the launcher entry in `AndroidManifest.xml`, so a shared `music.youtube.com` or `youtu.be` link opens the browser or YouTube Music, never Riff.
- **Two ways in,** and Riff should offer both:
  1. **Share to Riff.** In any app, Share → Riff. This works on every phone with no setup, because sharing doesn't need domain verification.
  2. **Tapping a link.** Since Android 12, a web link opens in an app only when the app has proved it owns the domain (`autoVerify` with a file on the website). Riff can't do that for YouTube's domains. Android still lets the user turn it on: App info → Open by default → Add links. Riff should explain this once and offer a button to that settings page.

### 13.2 Implementation

1. **Manifest** (`MainActivity`):
   - A `VIEW` filter: `<action VIEW/>`, `<category DEFAULT/>`, `<category BROWSABLE/>`, `<data android:scheme="https"/>` with hosts `music.youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtube.com` and `youtu.be`.
   - No `autoVerify`.
   - A `SEND` filter with `mimeType="text/plain"`, so Riff appears in the share sheet.
2. **New `SharedLinks.kt`** with `parseYouTubeLink(text): SharedTarget?`:
   - It finds the first URL in shared text (share sheets often add a title before it).
   - It reads only these forms; anything else is `null`:

   | Link | Result |
   |---|---|
   | `music.youtube.com/watch?v=<id>&list=<list>` | Play the song; queue the list after it if one is given |
   | `youtube.com/watch?v=<id>`, `youtu.be/<id>`, `youtube.com/shorts/<id>` | Play the song (videos play as audio, like any song in Riff) |
   | `music.youtube.com/playlist?list=<id>` | Open the playlist (`Route.Collection("VL<id>")`) |
   | `music.youtube.com/browse/MPREb_…` | Open the album |
   | `music.youtube.com/channel/UC…`, `youtube.com/channel/UC…` | Open the artist |
   | `…&t=1m30s` or `?t=90` | Start at that time |

   IDs are checked against the same patterns Riff already uses (`[A-Za-z0-9_-]{11}` for videos, `COLLECTION_ID` in `ShellNavigation.kt`). Nothing else from the link is used. That matters because links come from outside the app.
3. **`MainActivity`**: handle the intent in `onCreate` and `onNewIntent` (Riff runs `singleTask`, so a second link arrives in `onNewIntent`). Pass the `SharedTarget` to `RiffApp` through a small `MutableStateFlow`. The shell then opens the route, or calls the existing `playRealTrack`.
4. **Signed-out and offline:** both still work. A song plays signed out like any other. Offline, Riff opens the song if it's downloaded, otherwise shows "This needs a connection".
5. **One-time tip:** the first time Riff opens, or in Settings → Playback, show "Open YouTube Music links in Riff" with a button that starts `Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` for Riff (Android 12+). Once the user has turned it on, `DomainVerificationManager.getDomainVerificationUserState(packageName)` shows the hosts as selected, and the tip says "On".
6. **Tests:**
   - parser tests for every form in the table, with titles before the URL, tracking parameters, malformed IDs and other hosts (must be `null`);
   - an instrumented test sending a `VIEW` and a `SEND` intent to the activity.

Match: complete through Share. Tapping links works once the user has allowed it in Android's settings.

---

## Feature 14: Android Auto

### 14.1 Findings

- **Riff today:** `MusicPlaybackService` is a Media3 `MediaSessionService`, so a car can already show what's playing and control it. The manifest comment already mentions cars. But Android Auto lists only apps that offer a **browsable library**: a `MediaLibraryService`, the Media3 class that answers "what are the root items, what's inside this folder, search for X". Riff has none, so it doesn't appear in the car's app list.
- **Sideloading:** Android Auto shows apps not installed from the Play Store only after the user turns on **"Unknown sources"** in Android Auto's developer settings (tap the version number in Android Auto's settings 10 times). Riff isn't on the Play Store, so this one-time step will be needed. The report flags it because it's outside Riff's control.
- **Driver distraction rules:** Android Auto draws the screens itself. Riff only supplies lists, artwork and actions. So there's no glass or Riff design in the car: the car's look is fixed.

### 14.2 Implementation

1. **Change the service to `MediaLibraryService`.** `MediaLibraryService` extends `MediaSessionService`, so the session, notification and everything else stay. `MediaSession.Builder` becomes `MediaLibrarySession.Builder(this, player, libraryCallback)`. Riff's custom commands (`PLAY_PROVIDER_STREAM`, `SET_SLEEP_TIMER` and so on) stay as they are.
2. **Manifest:**
   - add `<action android:name="androidx.media3.session.MediaLibraryService"/>` and `<action android:name="android.media.browse.MediaBrowserService"/>` to the service's intent filter;
   - add `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>` to the application, with `res/xml/automotive_app_desc.xml` = `<automotiveApp><uses name="media"/></automotiveApp>`.
3. **The library tree** (new `CarLibrary.kt`, the `MediaLibrarySession.Callback`):

   | Root tab (Auto shows up to 4) | Children |
   |---|---|
   | Home | Riff's Home sections (`homeSections()`), each a folder of playable items |
   | Library | Playlists, Albums, Artists, Liked songs, from `ProviderLibraryRepository` |
   | Downloads | Downloaded songs and collections (`OfflineMusicStore`), so the car works without signal |
   | Recent | The last 50 plays from listening history |

   - `onGetLibraryRoot`: return a root with `BrowserRoot` extras so Auto shows them as tabs (`androidx.media.utils.MediaConstants` content-style hints: grid for albums, list for songs).
   - `onGetChildren`: page by Auto's `page`/`pageSize`; reuse the same repositories the screens use. Results are cached for 5 minutes to keep the car fast.
   - `onSearch` and `onGetSearchResult`: Riff's catalog search, songs first. This also makes voice search work ("Hey Google, play … on Riff").
   - `onAddMediaItems` / `onSetMediaItems`: turn a chosen item into Riff's normal queue, the same as tapping it on the phone (`PLAY_CATALOG_QUEUE`).
4. **Artwork:** Auto loads artwork URIs itself. Pass YouTube's `https://` thumbnails at about 400 px. For on-device songs, use `content://` URIs served by Riff's existing `FileProvider`, with read access granted to Auto's package.
5. **Who may browse:** Riff's `onConnect` currently gives other apps transport controls only. Allow browsing for Android Auto (`com.google.android.projection.gearhead`) and the system UI after checking the caller's package and signature with Media3's `ControllerInfo` (Google's own sample apps do this check). Others keep transport only.
6. **When signed out or offline:** the tabs still appear. Library shows "Sign in on your phone", and Home shows Downloads when offline.
7. **Testing:** Android's Desktop Head Unit (DHU) from the SDK runs Auto on the computer with the phone connected. Add a JVM test of the tree (root → each tab → paging) and an instrumented test with `MediaBrowser` connecting as a controller.

Match: browsing, search, voice and playback, like other music apps in Android Auto. Each phone needs Android Auto's one-time "Unknown sources" setting.

---

## Feature 15: Playback speed

### 15.1 Findings

- **Riff today:** there's no speed control. The only use of `PlaybackParameters` is `Crossfade.kt:342`, which slightly speeds up or slows down the tail player to keep the two songs in step during a blend (`parameters.speed * correction`).
- Media3 changes speed while keeping pitch, with its built-in Sonic processor. That's good from 0.5× to 2×.

### 15.2 Implementation

1. **Setting:** speed per kind of audio, in a new `PlaybackSpeedSettings.kt`:
   - **music:** default 1.0×, reset to 1.0× when the app restarts unless "Remember for music" is on;
   - **podcasts and episodes:** remembered **per show** (by the show's browse ID), as podcast apps do.
2. **Service:** a custom command `SET_SPEED(speed, scope)` beside `SET_SLEEP_TIMER`. The service calls `player.playbackParameters = PlaybackParameters(speed)` when the item changes and when the setting changes.
3. **Crossfade:** make the tail player start from the *chosen* speed, and let `Crossfade.kt:342`'s correction multiply it, as it already does (`parameters.speed * correction`). Fade lengths are measured in song time, so a 6 s crossfade at 1.5× lasts 4 s of real time, which is expected.
4. **Other features:**
   - Encore counts real listening time (wall clock), so 1 hour at 2× counts as 1 hour.
   - Silence skipping (Feature 9) divides its 1.5 s by the speed.
   - The lyrics follow the player's position, so they stay in sync automatically.
5. **UI:** a "1×" chip in the player's more menu, and on the player for podcasts. It opens a sheet with:
   - a glass slider from 0.5× to 2× in 0.05× steps, with haptics at each 0.25×;
   - chips for 0.75, 1, 1.25, 1.5 and 2;
   - "Remember for this show" for episodes.

   Show the speed in the player ("1.5×") whenever it isn't 1×.
6. **Tests:**
   - the per-show memory;
   - the reset rule for music;
   - the crossfade multiplying the chosen speed (extend the existing crossfade tests);
   - an emulator check that 2× plays half the wall time.

Match: full.

---

## Feature 16: Stats for nerds

### 16.1 What it shows

A glass panel over the artwork, opened from the player's more menu, or by a long-press on the quality label. It updates once a second, with a Copy button.

| Row | Source in Riff |
|---|---|
| Source | Stream / cached / downloaded / on-device, from the media ID and `OfflineMusicStore` |
| Delivery | SABR / direct / HLS, from the resolver's log fields (`ProviderStreamResolver.kt:219`) |
| Format | `itag`, MIME type and codec (`player.audioFormat.codecs`), e.g. "251 · audio/webm · opus" |
| Bitrate | Stated by YouTube (`ProviderStream.bitrate`) and measured from bytes over time (`AnalyticsListener.onBandwidthEstimate`) |
| Sample rate, channels | `player.audioFormat.sampleRate`, `channelCount` |
| Decoder | `AnalyticsListener.onAudioDecoderInitialized` (e.g. "c2.android.opus.decoder") |
| Output | 16-bit PCM (Riff's chain), the output device (`AudioManager`'s current route: speaker, wired, Bluetooth with codec name when Android reports it, USB) |
| Quality setting in use | High / Medium / Low, and which network rule chose it (Feature 17) |
| YouTube client that answered | Already logged for Q18-089 |
| Premium format | `premiumOnlyFormat(itag)` |
| Buffer ahead | `bufferedPosition − currentPosition`, in seconds |
| Normalization | The loudness correction applied, in dB (Riff already logs `loudness_db`) |
| Booster, equalizer | The gain and preset in use (Feature 10) |
| Crossfade | Off, waiting, or blending with the next song's title |
| Speed | Feature 15 |
| Dropped audio | `AnalyticsListener.onAudioUnderrun` count since the song started |

### 16.2 Implementation

- New `NerdStats.kt`: an `AnalyticsListener` attached to the main player in the service collects the fields into a `NerdStatsSnapshot`.
- The service publishes the snapshot through a custom session command (`GET_NERD_STATS`, polled once a second only while the panel is open). Nothing runs when it's closed.
- `NerdStatsPanel` in the player: monospaced numbers (`tnum`), `GlassRole.Card`, and a Copy button that puts the rows as text on the clipboard.
- Setting: Settings → Playback → "Show stats for nerds" adds the long-press shortcut. The menu item is always there.
- The same snapshot goes into Riff's diagnostics report (Settings → About → Diagnostics), which helps bug reports.
- Tests: the snapshot from a fake analytics event sequence; the panel rows; the copy text.

Match: full.

---

## Feature 17: Separate quality for Wi-Fi and mobile data

### 17.1 Findings

- **Riff today:** one "Song quality" (High / Medium / Low, `DataSaverSettings.kt`), with Data saver forcing Low. The resolver reads them at each lookup (`ProviderStreamResolver.kt:60`), and Riff already knows the network: `NetworkConditions(wifi, unmetered, validated)` in `AppContainer.kt`, which also handles VPNs correctly (Q18-017).

### 17.2 Implementation

1. **Settings:** replace `song_quality` with `song_quality_unmetered` and `song_quality_metered`.
   - The first time the new build runs, copy the old value into both, so nothing changes for the listener.
   - Use **unmetered** rather than "Wi-Fi". It covers Ethernet and unmetered 5G plans, and correctly treats a metered phone hotspot as mobile data.
2. **Rule** (`streamingAudioQuality`), first match wins:
   1. a download uses High;
   2. Data saver uses Low;
   3. on an unmetered network, use the unmetered choice;
   4. otherwise use the metered choice.
3. **Resolver:** `chosenQuality` reads the current `NetworkConditions` from the container. A network change during a song doesn't interrupt it; the next lookup uses the new rule, as today.
4. **Preloading:** Riff's prewarming of the next song resolves its stream early. When the network type changes before that song starts, drop its prewarmed stream and resolve again, so a song prepared on Wi-Fi doesn't stream at High on mobile data.
5. **Downloads quality:** show it too, read-only for now ("Downloads always use High").
6. **UI:** Settings → Playback → Quality: two rows, "On Wi-Fi" and "On mobile data", each High / Medium / Low, with the data each uses per hour (about 115 / 60 / 30 MB at 256 / 128 / 64 kbps). Data saver stays as a switch above them.
7. **Stats for nerds** (Feature 16) shows which rule chose the quality.
8. **Tests:**
   - the rule table for every combination, including a VPN over mobile data;
   - the migration of the old key;
   - the prewarm being dropped on a network change.

Match: full.

---

## Feature 18: "Stop after this song" and fade length in the sleep timer

### 18.1 Findings

- **Riff today:** `SleepTimerController.kt` runs a deadline in minutes (1–1440) and fades the player's volume over the last **15 s** (`sleepFadeGain`). The deadline survives the process being killed (W6). The dialog in `SleepTimer.kt` offers fixed times.
- `shouldStartCrossfade(..., sleepActive)` (`Crossfade.kt:151`) already refuses to start a crossfade while a sleep timer is running, so stopping at a song's end won't be undone by a blend.

### 18.2 Implementation

1. **Timer modes:** generalise the controller from a deadline to a target:

   ```kotlin
   internal sealed interface SleepTarget {
       data class At(val deadline: Long) : SleepTarget               // today's timer
       data class EndOfSong(val mediaId: String) : SleepTarget       // stop after this song
       data class AfterSongs(val count: Int) : SleepTarget           // stop after N more songs (2-5)
       data object EndOfQueue : SleepTarget
   }
   ```

2. **Song-end targets:**
   - The service watches `onMediaItemTransition` and `onPlaybackStateChanged(STATE_ENDED)`. When the target song ends, it pauses at the start of the next one, position 0, so pressing play continues from there.
   - The fade starts `fadeMs` before the song's end, worked out from `duration − position` divided by the playback speed (Feature 15) on every tick. It restarts correctly after a seek or a pause.
   - With silence skipping (Feature 9), "the end" means `effectiveEndMs`.
   - If the user skips to another song, `EndOfSong` follows the new current song, and the dialog says so.
3. **Fade length:** a setting with the choices Off, 5 s, 15 s (today's), 30 s and 60 s. `sleepFadeGain` takes the length as a parameter instead of the fixed 15,000.
4. **Persistence:** extend the saved deadline to a small JSON (`mode`, `deadline` or `mediaId` or `count`, `fadeMs`), so all modes survive a kill like today's.
5. **Session command:** `SET_SLEEP_TIMER` gains a mode in its `Bundle`. Old callers send minutes and keep working.
6. **UI** (`SleepTimerDialog`):
   - the existing times;
   - "End of this song" (showing its remaining time), "After 2 / 3 / 5 songs", "End of queue";
   - a "Fade out" chooser.

   The countdown chip shows "Ends after this song" or "3 songs left".
7. **Tests:**
   - each target fires at the right moment with real transition reasons;
   - fade timing with seeks, pauses and 1.5× speed;
   - the crossfade staying off;
   - restore after a simulated kill for each mode;
   - old `SET_SLEEP_TIMER` callers.

Match: full.

---

## Feature 19: Cast to Chromecast and Google speakers

### 19.1 Findings

- **How Cast works:** the phone tells the speaker which URL to play; the speaker fetches and plays it by itself. Google's "Default Media Receiver" plays ordinary MP4, WebM, MP3, HLS and DASH from a URL.
- **Riff's streams make that harder:**
  1. **SABR.** Riff often streams with YouTube's SABR protocol (`sabrBootstrap`, `po_token` in `ProviderStreamResolver.kt`). SABR is a POST-based protocol a speaker can't speak.
  2. **Headers.** Some streams need request headers (`stream.headers`), which a speaker won't send.
  3. **IP-bound URLs.** YouTube's direct stream URLs are tied to the IP address that asked for them. A speaker on the same Wi-Fi shares the phone's public IP, so this usually works; it fails when the phone is on a VPN or mobile data.
  4. **On-device songs** live on the phone, and the speaker can't reach them.
- **BitChord** (`playback/cast/`) resolves a direct URL on the phone and hands it to the speaker. Songs that need headers and on-device files are refused with a message. The phone's player stays paused and keeps the queue; the speaker owns the clock, and the two follow each other by media ID.
- **Dependency:** Cast needs Google Play services (`play-services-cast-framework`, through Media3's `media3-cast`). That's a closed-source dependency, unusual for Riff. It must go into the dependency inventory and verification metadata, and it means Cast does nothing on phones without Play services.

### 19.2 Implementation

1. **Dependencies:** `androidx.media3:media3-cast` (same version as Media3), `androidx.mediarouter:mediarouter`, and `play-services-cast-framework`. Add a `CastOptionsProvider` (manifest `meta-data` `com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`) using the Default Media Receiver (`CC1AD845`). As BitChord does, turn **off** Cast's own notification and media session, so Riff's stay the only ones.
2. **Choosing a speaker:** Riff's output switcher (`AudioOutputControls.kt`, which uses `MediaRouter2`) gains a "Speakers and TVs" group, filled by `MediaRouter` with the Cast category. Selecting one starts a Cast session.
3. **The player swap:** when a Cast session starts, the service:
   - pauses the local player;
   - builds Media3's `CastPlayer`;
   - points the media session at a forwarding player that reports the Cast player's state, so the notification, lock screen, widgets and Riff's own player show the speaker's position and state;
   - loads the current song and the next 3 onto the speaker.

   When the session ends, it moves the local player to the speaker's song and position and resumes locally if it was playing.
4. **Stream for the speaker,** in order:
   1. ask the resolver for a **direct, header-free** format (`StreamRequest(..., forCast = true)`: no SABR, opus/webm itag 251 or m4a itag 140) and hand the URL to the speaker;
   2. if YouTube only offers SABR or header-bound streams for that song, or the phone is on a VPN, or the song is on the phone: **relay** it. A small HTTP server in the service on the phone's Wi-Fi address (NanoHTTPD-style, on a random port, reachable only on the local network, with a random token in the path) fetches the stream through Riff's normal player data source (with headers and SABR) and sends plain audio to the speaker. The phone must stay awake and on the same Wi-Fi while casting; Riff holds a Wi-Fi lock for it.
   3. Downloads and on-device songs use the relay directly from the file.

   Cleartext HTTP to a local address needs an entry in Riff's network security config for the LAN only. It must not open cleartext to the internet.
5. **Queue sync:**
   - Skips, seeks and queue edits on the phone are sent to the speaker.
   - The speaker finishing a song, or its own remote being used, moves the phone's queue (matched by media ID, as BitChord found more reliable than flags).
   - Riff tops up the speaker's queue 3 songs ahead and refreshes URLs that are about to expire (YouTube's last about 6 hours).
6. **Features that pause while casting:**
   - crossfade, the booster, the equalizer and silence skipping (they run inside the phone's player);
   - the volume keys switch to the speaker's volume, which Media3's Cast integration provides.

   The player shows a small "Casting to Living room speaker" bar.
7. **Privacy and docs:** the speaker fetches from YouTube itself, except relayed songs. Add Cast to `docs/privacy.md` and the architecture doc's network list.
8. **Tests:**
   - the forwarding player state, with a fake Cast player;
   - queue sync both ways;
   - the relay serving a byte range with the right content type, and refusing requests without its token or from outside the local network;
   - stream choice (direct versus relay) for each case.
   - On real hardware: a Chromecast or Nest speaker on the owner's network.

Match: comparable to other casting apps, with the phone as a relay when needed. Crossfade and effects stop while casting.

---

## Feature 20: Automix (DJ-style transitions)

> **Implementation reference:** the complete design is the [AutoMix engine report](automix-engine-report-2026-10-28.md): equations, filter coefficients, the two-deck state machine, the capture-sink mixer, JSON schemas, the planner and every fallback case. This section is the summary. Where they differ, the engine report wins.

This section covers what Apple and BitChord do, what Riff already has, what can be built, how good each stage can be, and the risks.

### A.1 What Apple's AutoMix does

AutoMix arrived in Apple Music with iOS 26 ([9to5Mac](https://9to5mac.com/2025/06/16/automix-apple-music-ios-26/), [MusicTech](https://musictech.com/news/gear/apple-music-automix-ai/), [iGeeksBlog](https://www.igeeksblog.com/apple-music-automix-in-ios-26/)):

- **Analysis first.** It analyses songs for tempo and key, then crafts a transition between each pair using **time-stretching** (changing tempo without changing pitch) and **beat-matching**.
- **The transition varies.** Depending on the pair, it overlaps the beats, fades, or leaves a short pause.
- **Scope.** It works on playlists and Apple's stations, not on full albums, out of respect for the artist's intended pacing.
- **It replaced the plain crossfade** in Apple Music.

Apple has advantages Riff doesn't: the original masters, analysis done once on Apple's servers for the whole catalog, and audio hardware it controls.

### A.2 How BitChord built it

BitChord's Automix is labelled **beta**. It's large: about 3,100 lines in `CrossfadeController.kt`, 1,400 in `TrackAnalyzer.kt`, 2,500 in `shared/…/smart/`, plus native C++.

1. **Analysis, ahead of time** (`playback/smart/TrackAnalyzer.kt`, `native/analyzer/`):
   - **signal analysis in C++:** mel spectrogram, resampling, energy curves (full and low-frequency), tempo, key with a confidence, audible start, intro end, outro start, content end;
   - **beats and downbeats:** the **Beat This!** neural network (`beat_this_int8.onnx`, 4.5 MB) through ONNX Runtime (`onnxruntime-android:1.28.0`, the full package);
   - **vocal activity:** an **open-unmix** vocals model (`vocals_umxhq_int8.onnx`, 9 MB), so two singers aren't overlapped;
   - **analysis results:** phrase boundaries, and ranked **mix-in and mix-out candidates** (drops, pickups, phrase lines), stored to disk so a song is analysed once.

   Analysis covers only a song's head and tail, which is all a transition reads. It runs off the playback thread, with "Efficient / Balanced / Performance" modes (1, 2 or 4 threads).
2. **A policy that falls back step by step** (`shared/…/TransitionPolicy.kt`, ported from the open-source **Orchard** project). Each transition gets a tier from how trustworthy the analysis is:

   | Tier | When | What it does |
   |---|---|---|
   | `BEATMATCHED` | Both beat grids trusted, tempos close enough | Time-stretch and line up the beats |
   | `DJ_ASSISTED` | Some trust, but tempos too far apart | Start the blend on a downbeat or phrase; bass swap with filters; no stretching |
   | `PLAIN_CROSSFADE` | Weak evidence | An equal-power fade at the best analysed point |

   Rules worth copying:
   - A tempo from metadata alone never permits beat-matching.
   - Only **speed up**, never slow down, at most about 4 %. Speeding up drops tiny slices of audio cleanly; slowing down repeats them, which sounds metallic.
   - Never cut more than a small share of the outgoing song's remaining music.
3. **Filters during the blend** (`TransitionFilterProcessor.kt`): a 24 dB/octave low-pass closing over the outgoing song and a high-pass lifting the bass out of one side, so the two basslines never sound together.

Two caveats for Riff:

- **Licences.**
  - BitChord's `TransitionPolicy.kt` and planner are ported from Orchard, which is **AGPL-3.0**. GPL-3.0 allows combining with AGPL code (section 13), but those files stay under AGPL. If Riff copied them, Riff's NOTICE and source archive would have to say so.
  - **Beat This!** is MIT-licensed, code and published weights, according to secondary sources ([Hugging Face mirrors](https://huggingface.co/cstr/beat-this-GGUF)). Check the licence file in `CPJKU/beat_this` before shipping it.
  - **open-unmix**'s pretrained weights are described in its own README as non-commercial only (CC BY-NC-SA 4.0); a third-party export claims MIT ([sources](https://pytorch.org/hub/sigsep_open-unmix-pytorch_umx/), [ONNX export](https://huggingface.co/edgetools/umx-hq)). A non-commercial licence adds a restriction GPL doesn't allow, so **Riff shouldn't ship those weights** unless the official Zenodo record says otherwise.
- **Size.** Riff's APK is 9.5 MB, after Q18-101 cut it from 28 MB by removing ML Kit. ONNX Runtime (roughly 6–8 MB for arm64) plus Beat This! (4.5 MB) would about double it again; open-unmix would add 9 MB more.

### A.3 What Riff already has

- **Two players and a blend:** `Crossfade.kt` has the main and tail players, the crossfade envelope, the ownership grant (`CrossfadeOwner`), the start and prepare rules (`shouldStartCrossfade`, `shouldPrepareCrossfade`), and rules for what never crossfades (`crossfadeSkipsItem`).
- **Keeping the players together:** `CrossfadeTailSynchronizer` holds the two players within **120 ms** (`crossfadeTailAligned`) by nudging the tail player's speed (`Crossfade.kt:342`). That's fine for a fade, but **not for beat-matching**: at 120 BPM, one beat is 500 ms and 120 ms is a quarter-beat, which sounds like a flam. Beat-matching needs the two within about **10–15 ms**.
- **Reading songs ahead of time:** the end scanner of Feature 9 decodes a song's tail with `MediaExtractor` and `MediaCodec`. The same code can feed analysis.
- **The audio chain:** the booster today, the equalizer next (Feature 10). The blend filters fit in the same chain.

### A.4 Proposed path, in stages

Each stage is useful on its own and ships separately, behind Settings → Playback → Transitions: **Crossfade / Smart / Automix (beta)**.

**Stage 1: Smart crossfade.** No beat analysis; works on every song.
- **Analysis** (pure Kotlin, on the decoded tail and head, about 0.2 s of CPU per song):
  - loudness curve in 100 ms blocks;
  - low-band (below 150 Hz) energy curve;
  - audible start and end (reusing Feature 9);
  - "outro start" (where loudness falls and stays below 60 % of the song's body);
  - "intro end" (where it first reaches 80 %).
- **Choosing the transition:**
  - start the blend at the outro start, or N seconds before the audible end (N = the crossfade setting), whichever is later;
  - let the incoming song enter at its audible start, or skip a quiet intro of up to 8 s when it's music;
  - choose the blend length from how long the outro is (4–12 s);
  - use equal-power curves.
- **Bass swap:** during the blend, the incoming song's low end comes in only at the midpoint (a high-pass at 150 Hz removed over 1 s at the midpoint), and the outgoing song's is removed at the same moment. This is the single largest improvement DJs make, and it doesn't need beats.
- **Rules:** never on albums played in order, podcasts, live radio, or classical (when the genre is known from Encore's optional lookup), as Apple does.
- **Quality:** clearly better than a plain crossfade on most songs, with no risk of a mistimed beat. **Near Apple's quality for every song.**

**Stage 2: DJ-assisted.** Beats found by signal analysis, no stretching.
- **Beat tracking without a model,** using a long-established method:
  - onset strength from spectral flux;
  - tempo from autocorrelation (60–200 BPM, with octave correction);
  - beat positions by dynamic programming (Ellis, 2007);
  - downbeats from the strongest of the 4 phases by low-band energy;
  - phrase lines every 8 or 16 bars.

  A confidence comes from how regular the beats are. In Kotlin with a small FFT, about 50 KB of code and no model.
- **Use:** start the blend on a downbeat of the outgoing song at a phrase line near the Stage 1 point, and enter the incoming song on its first downbeat. No time-stretching. When both songs' confidences are low, fall back to Stage 1.
- **Quality:**
  - expected tempo accuracy on steady-beat music (pop, dance, hip-hop, most rock) is roughly in the 80–90 % range that published classic beat trackers reach;
  - lower on live drumming with drift, rubato, acoustic and orchestral music, which falls back to Stage 1 automatically.

**Stage 3: Beatmatched (Automix beta).**
- **Condition:** both confidences high, and tempos within **4 %** after octave alignment (e.g. 124 and 128 BPM).
- **Stretching:** speed up the slower song with Media3's pitch-preserving speed, the same Sonic processor as Feature 15. Ramp in over 2 beats before the blend; return to 1.0× over 4 bars after it.
- **Getting the timing to ~10 ms.** This is the hard part, and it decides whether Stage 3 sounds good:
  1. start the incoming player early, muted, then use `AudioTrack.getTimestamp` (through Media3's audio sink position) on both players to measure the true offset between their beats;
  2. correct with small speed nudges (±0.5 % for a few hundred ms), as `CrossfadeTailSynchronizer` does today, but with the target tightened from 120 ms to 10 ms and the error measured on the beat grid;
  3. if the offset can't be brought under 20 ms within 2 beats, abandon the beat-match quietly and do the Stage 2 transition instead.

  An alternative is to mix both songs inside **one** audio chain, with a custom mixing processor and one output, which removes drift between two outputs completely. It's a larger change to Riff's playback design, so it's kept as a later option if the two-player approach can't hold 10 ms.
- **Quality:** good on electronic, dance, hip-hop and pop where tempos are close. Most pairs in a mixed playlist won't qualify, and they get Stage 2 or Stage 1, as in BitChord's design.

**Stage 4 (optional): A model for beats.**
- **Only if** Stage 2's accuracy, measured on the test set (A.6), is too low.
- **Model:** Beat This! (MIT, to be confirmed) through ONNX Runtime.
- **Delivery:** a separate download on first use (about 11 MB), not in the APK, so Riff's size stays down. With the owner's consent, from a fixed URL listed in `docs/privacy.md`, checked by SHA-256.
- **Vocals:** no vocal model. Instead, a cheap vocal estimate (energy in the 300 Hz–3 kHz band against the rest, with spectral flatness) keeps two dense vocal sections from overlapping. That avoids open-unmix's licence problem.

**Stage 5 (optional): Key.**
- **Key estimate:** a chroma profile matched against the Krumhansl key profiles.
- **Use:** prefer shorter blends when two keys clash on the Camelot wheel.
- **Autoplay:** when Riff picks the next song itself, prefer a compatible key among equally good candidates. Riff never reorders the user's queue.

### A.5 Where analysis gets its audio

- **Downloaded and on-device songs:** decode the first and last 60 s at any time. Analyse in the background after download, so it's ready.
- **Streamed songs:**
  - the **next** song's head is fetched when Riff prepares it (`shouldPrepareCrossfade`), so it's analysed then;
  - the **current** song's tail is available once its last minute is in the player's cache. Most songs are fully cached within a minute or two at 128–160 kbps, so analyse it as soon as the cache covers it;
  - if it isn't ready in time, use Stage 1 with the data available, or a plain crossfade. Never delay the music.
- **Results:** cache them per video ID in a small `track_analysis` table (version number, tempo, confidence, beats in the tail and head, outro start, intro end, energy curves downsampled), so each song is analysed once.
- **Cost:** Stage 1–2 analysis is a few hundred ms of one core per song. Run it at low priority, never during the blend, and skip it under battery saver.

### A.6 How to tell if it's good

- **A test set of 50 songs** the owner owns as files, across genres, with beats tapped by hand once in a free tool.
- **Measurements:**
  - tempo accuracy (within 4 %, counting double or half as an octave error);
  - beat F-measure (a beat within ±70 ms);
  - for Stage 3, the measured offset between the two players' beats during blends (target ≤ 15 ms for 95 % of the blend);
  - CPU time per analysis on the phone.
- **Listening:** 30 pairs, each rendered as plain crossfade, Stage 1 and Stage 3 where possible, in random order, rated 1–5 by the owner without knowing which is which. Ship a stage only when it wins.
- **Automated:** JVM tests on synthetic click tracks (known tempo and phase), filter responses, tier choice from analysis values, and fall-back when timing fails.

### A.7 Effort and risk

| Stage | Size | Main risk |
|---|---|---|
| 1 Smart crossfade | Medium | Choosing outro points well on quiet or long-fade songs |
| 2 DJ-assisted | Medium to large | Beat tracking accuracy outside steady-beat genres; handled by falling back |
| 3 Beatmatched | Large | Holding two players within 10–15 ms; may need the single-chain mixer |
| 4 Model | Medium, plus download hosting | Licence confirmation; extra size and consent flow |
| 5 Key | Small to medium | Key detection errors on modal or atonal music; used only as a soft preference |

**Recommendation:** build **Stage 1** soon after Features 9 and 10. It shares their decoder and audio chain, and gives most of the audible benefit. Then **Stage 2**, measured against the test set. Go to **Stage 3** only if Stage 2's beat accuracy is good on the owner's music and the timing prototype holds 15 ms on the phone. Don't copy BitChord's AGPL planner or its open-unmix model; Stages 1–3 can be written fresh in Kotlin.

---

## Features 22–30: further recommended features and UI/UX changes

These come from reading Riff's code against the features above and against what a listener notices day to day. Each is grounded in what Riff has today.

| # | Change | Why | Size |
|---|---|---|---|
| 22 | Predictive back animations | Riff targets Android 16 (API 36), where the back gesture previews where it goes | Small |
| 23 | Like, Add to playlist and Shuffle on the lock screen and notification | No custom buttons today | Small |
| 24 | Search suggestions as you type, and a top result | Search waits for Enter today | Small to medium |
| 25 | Romanized lyrics | Riff already receives romanization from Better Lyrics and drops it | Small |
| 26 | Undo for removals | Only "Not interested" has Undo today | Small |
| 27 | App shortcuts and a Quick Settings tile | None today | Small |
| 28 | Consistent loading, empty and offline states | Each screen handles them its own way | Small to medium |
| 29 | "What's new" sheet and first-run tips | Many new features and one-time Android settings (Features 13 and 14) need explaining | Small |
| 30 | Large-screen and landscape layout | Riff is checked on large windows but has no two-pane layout | Medium |

### Feature 22: Predictive back animations

**Findings:**
- Riff uses `BackHandler` in three files. On Android 16, apps targeting API 36 get the predictive back gesture by default: the system animates "back to home" previews. In-app screens should preview too.

**Implementation:**
- For the full-screen player, use `PredictiveBackHandler { events -> events.collect { e -> progress = e.progress } }` (activity-compose, already in Riff). As the user swipes, scale the player to `1 − 0.1·progress`, round its corners, and move it down by `progress × 64 dp`. On commit, run the existing close morph; on cancel, spring back.
- For pushed screens (collection, artist, settings), use the same pattern: the current page slides right by `progress × 15 %` and the previous page shows behind it (keep the previous route composed while the gesture is active).
- Respect reduced motion: no preview, an immediate back.
- Add `android:enableOnBackInvokedCallback="true"` to the application in the manifest, so behaviour is the same on Android 13–15.
- **Tests:** a Compose UI test that drives `BackEventCompat` progress and checks the player's scale and that cancel restores it.

### Feature 23: Buttons on the lock screen and notification

**Findings:**
- Riff's media notification and lock screen show Media3's default previous, play/pause and next buttons only.

**Implementation:**
- Media3 1.6+ supports `MediaSession.Builder.setMediaButtonPreferences(listOf(CommandButton…))`.
- Add:
  - **Like** (heart filled or empty, slot `SLOT_BACK_SECONDARY` or overflow), sent as a custom command `TOGGLE_LIKE` that the service handles with the existing like code;
  - **Shuffle**;
  - **Add to playlist**, which opens Riff at the playlist picker through a `PendingIntent`.
- Update the button state when the song or like state changes (`session.setMediaButtonPreferences` again).
- Android Auto (Feature 14) shows the same buttons.
- **Tests:** a `MediaController` test that the buttons are offered and that `TOGGLE_LIKE` changes the like state.

### Feature 24: Search suggestions and a top result

**Findings:**
- Riff's search runs when the user submits (`CatalogScreens.kt`). Recent searches are shown; suggestions as you type aren't.

**Implementation:**
- **Suggestions:**
  - YouTube Music's web client asks the InnerTube `music/get_search_suggestions` endpoint as the user types. Check whether `innertubex` 0.7.2 exposes it; if not, make one request through `ProviderSessionManager` like the other catalog calls.
  - Debounce 200 ms; cancel the previous request on each keystroke.
  - Show up to 6 text suggestions and up to 3 quick results (song, artist, album) above them. Recent searches still show when the field is empty.
- **Top result:** when the first result of a search is an artist or album with high confidence (YouTube's "Top result" shelf), show it as a large card above the list, with Play and Shuffle.
- **Privacy:** suggestions send partial typing to YouTube, like YouTube Music does. Add a line to `docs/privacy.md` and a setting "Search suggestions" (on by default; respects the account in use).
- **Tests:** parser fixtures for the suggestions response; debounce and cancellation; the top-result card shown only for the top-result shelf.

### Feature 25: Romanized lyrics

**Findings:**
- `LyricsModels.kt:153` drops TTML spans with roles `x-roman`, `x-romanization` and `x-translation`, because they aren't the sung text. Better Lyrics sends romanization for many non-Latin songs.
- Hindi, Japanese, Korean, Chinese and Russian lyrics are hard to sing along to for many listeners.

**Implementation:**
1. **Keep romanization from providers:** parse `x-roman`/`x-romanization` spans into a new optional `romanized: String?` on `LyricLine`, aligned by line. Translations stay dropped; translation was removed in Q18-101.
2. **On-device fallback:** when a line contains a non-Latin script and no provider romanization exists, use Android's built-in ICU `android.icu.text.Transliterator` (API 29+, no extra size): `"Any-Latin; Latin-ASCII"`, or script-specific IDs such as `"Devanagari-Latin"` and `"Hiragana-Latin"`. It's a mechanical transliteration, so label it "Auto" in the UI.
3. **Display:** a toggle in the lyrics menu: **Original / Romanized / Both**. "Both" shows the romanized line below the original in a smaller, 70 %-white line. Word timing applies to the original. In "Romanized" mode, timing is per line (the provider's word timing doesn't map onto transliterated text).
4. **Remembered per script:** for example, always romanize Japanese.
5. **Tests:** TTML fixtures with `x-roman` spans; the ICU fallback on sample lines in each script; the toggle state.

### Feature 26: Undo for removals

**Findings:**
- Undo exists only for "Not interested" (`LibraryHistoryScreens.kt:219`). Removing a song from the queue or a playlist, deleting a download, or clearing a search happens at once.

**Implementation:**
- A shared glass snack bar (`GlassRole.Menu`) at the bottom, above the mini player: "Removed from queue · Undo", for 5 s, one at a time.
- **Queue:** keep the removed item and its index; Undo reinserts it through the existing queue command.
- **Playlist:** Riff removes entries with their `setVideoId`. Undo re-adds the song with the existing add path (YouTube appends it at the end; the snack bar says "Added back at the end" when the order can't be kept).
- **Download:** delay the file deletion by 5 s, hiding the item at once; Undo cancels the deletion.
- TalkBack announces the snack bar (`LiveRegionMode.Polite`) and the Undo action.
- **Tests:** each undo path; that a second removal commits the first.

### Feature 27: App shortcuts and a Quick Settings tile

**Implementation:**
- **App shortcuts** (long-press Riff's icon): "Search", "Downloads", "Shuffle liked songs", "Resume". Use static shortcuts in `res/xml/shortcuts.xml` plus a dynamic "Resume <song>" through `ShortcutManagerCompat`. Each is an intent that `MainActivity` routes, sharing Feature 13's routing.
- **Quick Settings tile:** a `TileService` "Riff" whose tap toggles play/pause, and whose long-press opens Riff (`android.service.quicksettings.TOGGLEABLE_TILE`). Optional second tile: "Sleep timer", which cycles 15 / 30 / 60 min / off. The tile subscribes to the media session state while it is listening.
- **Tests:** shortcut intents route correctly; the tile's state follows the player (instrumented).

### Feature 28: Consistent loading, empty and offline states

**Findings:**
- Placeholders exist in seven files, each in its own style. Some screens show text ("Loading from YouTube Music"), some show nothing.

**Implementation:**
- One set of components in `ScreenParts.kt`:
  - `ShelfSkeleton` and `RowSkeleton`: shapes in white at 8 % with a slow shimmer of 1.2 s, off under reduced motion;
  - `EmptyState(icon, title, body, action)`;
  - `OfflineState(downloadsAction)`: "You're offline · Your downloads still play" with a button to Downloads.
- Use them on Home, Library, Search, collections, the artist page, Encore and lyrics.
- Every network-backed screen gets the three states and a retry.
- **Tests:** screenshot tests of each state; a test that each screen shows `OfflineState` when the network is off (the emulator lane already runs network-off cases).

### Feature 29: "What's new" sheet and first-run tips

**Implementation:**
- After an update (the version code is higher than the last one seen, stored in preferences), show a glass sheet once: 3–5 cards for the release's main features, each with "Try it", which opens the feature. "Not now" closes it.
- The content is a small JSON in `res/raw/whats_new.json`, written for each release beside the changelog.
- **First-run tips** (each shown once, dismissible, listed again in Settings → Help):
  - "Open YouTube Music links in Riff" (Feature 13);
  - "Riff in your car" with the Android Auto "Unknown sources" steps (Feature 14);
  - "Watch your Encore" when the first week of data is ready (Feature 12).
- **Tests:** shown once per version; not shown on a fresh install (show the first-run tips instead); "Try it" routes.

### Feature 30: Large-screen and landscape layout

**Findings:**
- Riff is checked at large windows and in landscape (A11Y02), but the layout is the phone layout stretched.

**Implementation:**
- Use `currentWindowAdaptiveInfo()` (Compose material3-adaptive). At the **Expanded** width class (≥ 840 dp: tablets, unfolded foldables, landscape on large phones):
  - the bottom bar becomes a side rail (glass, Feature 1);
  - the player opens as a **right-hand pane** (40 % width) beside the browsing content, instead of covering it;
  - lyrics open in the player pane;
  - Encore's charts and lists go side by side.
- At **Medium** (600–840 dp): the rail, with the player still full screen.
- Foldables: on a half-folded (tabletop) posture (`WindowInfoTracker` folding feature), put the artwork above the hinge and the controls and lyrics below it.
- **Tests:** screenshot tests at 600, 840 and 1280 dp widths; the existing large-window journey.

---

## Feature 32: Three dots during instrumental sections of the lyrics

### 32.1 Findings

- **What the owner sees:** while a song has no singing (an intro, a solo, a break between verses), Riff's lyrics page shows nothing new: the last sung line stays highlighted or nothing is lit. Apple Music and BitChord show **three dots** that fill up one after another as the instrumental passes (owner's screenshot from BitChord, 28 Oct 2026).
- **Riff today:**
  - `LyricsPanel.kt:91–98` works out the active lines with `activeLyricLines`. When no line is active, it falls back to the last started line (`startedLyricLine`), so the view stays on the previous line through a break.
  - Lines carry `startMs`, `endMs`, `text`, `parts` and `backing` (`LyricsModels.kt`). `finishLyricLines` (`LyricsModels.kt:96`) gives every line an end: the source's own end where it has one (word-timed TTML, NetEase YRC), otherwise the next line's start (plain LRC). So Riff can't tell a real silence from "the next line starts later".
  - `songLyrics.instrumental` already marks whole-song instrumentals ("This recording has no sung lyrics"). That part stays as it is.
- **BitChord** (`shared/…/data/lyrics/LyricGaps.kt`, `sharedUi/…/player/PlayerLyrics.kt:2116–2200`):
  - It inserts empty "gap" lines where a line's known end is followed by **at least 4 s** of silence, plus one for the intro when the first line starts at 4 s or later. Lines without a known end never get a gap, because every line of a plain LRC would otherwise get one.
  - The gap row shows three 13 dp dots, 5 dp apart. Each dot owns one third of the break: `lit = clamp(progress·3 − i, 0, 1)` and `alpha = 0.25 + 0.75·lit`, so they fill left to right.
  - The row opens (height and a scale from 0.76 to 1 over 400 ms) when the break starts, and closes over 350 ms when singing returns, so no empty space sits in the list between verses.
  - The fill is drawn in `drawBehind`, reading the clock there, so a running break redraws three circles rather than recomposing.
  - BitChord is GPL-3.0 like Riff, so this code can be adapted with attribution.

### 32.2 Implementation

1. **Know which ends are real.** Add `knownEnd: Boolean` to `LyricLine`.
   - In `finishLyricLines`, set it when the source gave an end (`line.endMs > line.startMs` on input): word-timed TTML, YRC, and LRC lines with word stamps.
   - It stays false when the end was filled in from the next line.
2. **Insert gap markers: new `withInstrumentalGaps(lines, durationMs)` in `LyricsModels.kt`**, applied after `finishLyricLines` for timed documents only:
   - **Intro:** if the first line starts at ≥ 4,000 ms, add a gap from 0 to that start.
   - **Between lines:** for each line with `knownEnd`, if `next.startMs − line.endMs ≥ 4,000`, add a gap from `line.endMs` to `next.startMs`.
   - **Plain LRC:** LRC files mark breaks with a bare timestamp (`[02:13.40]` and no text). Keep such empty lines as gaps when the next line is ≥ 4 s later, instead of dropping or showing them blank.
   - A gap is `LyricLine(startMs, endMs, text = "", parts = empty, gap = true)`. It's never shared on lyric cards (`LyricsShare.kt` skips it), never exported, and has no backing vocals.
   - Constant `MIN_LYRIC_GAP_MS = 4_000` in `LyricsModels.kt`, as BitChord uses.
3. **Active line and following:** `activeLyricLines` returns the gap while the position is inside it, so `scrollIndex` centres the dots and the view moves to them when the break starts. Feature 5's blur treats the gap as the active line (no blur), and the next sung line gets the "next line" blur.
4. **The gap row** (in `LyricsKaraoke.kt`, beside the sung-line composable):
   - **Size:** three dots of 13 dp, 5 dp apart, left-aligned with the lyric text inset, vertically centred in a 40 dp row with 16 dp below it.
   - **Fill:** `progress = clamp((position − startMs) / (endMs − startMs), 0, 1)`. Dot `i` (0, 1, 2) has `lit = clamp(progress·3 − i, 0, 1)`, drawn in white at `alpha = 0.25 + 0.75·lit`. So the first dot fills over the first third of the instrumental, the second over the next, the third over the last.
   - **Open and close:** when the gap becomes active, the row opens from 0 height and scale 0.76 to full over 400 ms (`GlassMotion` easing). When the next line starts, it closes over 350 ms. Inactive gaps have zero height, so they add no space between verses.
   - **Breathing, as Apple does:** while active, the three dots together scale gently, `1 + 0.06·sin(2πt / 1.6 s)`.
   - **The hand-off:** in the last 300 ms before singing returns, the dots shrink to 0.6 and fade out, so the next line lights up into an empty space.
   - **Drawing:** in `Modifier.drawBehind`, reading the playback position there (Riff already polls `playback.positionMs` in `LyricsPanel.kt:86`), so only the three circles redraw each frame.
   - **Reduced motion:** no breathing or open animation; the dots still fill.
5. **Tapping the dots** seeks to the start of the break, like tapping a line.
6. **Accessibility:** the row's `contentDescription` is "Instrumental" (the existing `R.string.instrumental`), with `stateDescription` "1 of 3", "2 of 3" or "3 of 3" as the dots fill. It isn't announced every frame.
7. **Elsewhere:** if the player shows a lyric preview line, show the same three dots there during a gap.

### 32.3 Tests

- **Gap insertion:**
  - an intro gap at ≥ 4 s and none at 3.9 s;
  - a gap after a word-timed line followed by 6 s of silence;
  - no gap between plain LRC lines 8 s apart (no known end);
  - an LRC bare timestamp becoming a gap;
  - no gap for an untimed document.
- **Fill math:** the dots at progress 0, ⅙, ⅓, ½ and 1 (expected `lit` 0/0/0, ½/0/0, 1/0/0, 1/½/0, 1/1/1).
- **Panel:** with a fake clock, the gap row opens when the break starts, the list centres it, it closes when the next line starts, and the next line isn't blurred.
- **Share card:** gaps never appear in it.

Match: the same behaviour as Apple Music and BitChord, wherever the lyrics source says when a line's singing ends (most word-timed sources), or marks breaks in its LRC.

---

## Feature 33: Dark status bar icons over light artwork

### 33.1 Findings

- **What the owner sees:** on a white or very light cover (owner's screenshot, 28 Oct 2026: "Appaloosa Bones" by Gregory Alan Isakov, cream artwork), the status bar's white clock and icons disappear into the picture. Riff's own "NOW PLAYING" label and the close button also become hard to read. The same happens on album, artist and playlist pages with light headers.
- **Riff today:** `MainActivity.kt:16–20` makes the status and navigation bars transparent and sets `window.decorView.systemUiVisibility = 0`. That's a deprecated API, and it forces **white icons always**. Nothing changes them per screen. Over-picture controls use `OverPictureIcon` and `RiffOverPicture.textShadow`: white with a dark shadow.
- **Android's mechanism:** `WindowInsetsControllerCompat(window, decorView).isAppearanceLightStatusBars = true` turns the status bar's icons and text **dark**; `false` turns them light. `isAppearanceLightNavigationBars` does the same for the gesture handle or the navigation buttons. Android has no animation for the switch; it changes on the next frame.

### 33.2 Implementation

1. **Modern edge-to-edge in `MainActivity`:**
   - replace the deprecated window calls with `enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))` (activity-compose, already in Riff);
   - keep a `WindowInsetsControllerCompat` for the per-screen changes below.

   Riff's default stays white icons on its dark UI.
2. **Measure how light the strip under the status bar is: new `StatusBarTone.kt`.**
   - **The pixels:** take the bitmap the screen actually shows behind the status bar: the player artwork, a collection or artist header, a Home mood photo. Coil's `AsyncImage(onSuccess = { state -> … })` gives the drawable. Work out which part of the source bitmap lands under the top `statusBarHeight + 16 dp`, given `ContentScale.Crop` and the image's size.
   - **Sampling:**
     - Scale that strip down to 32 × 6 px with `Bitmap.createScaledBitmap(filter = true)` on `Dispatchers.Default`.
     - Coil may hand out hardware bitmaps, so copy to `ARGB_8888` first (or request `allowHardware(false)` for this small sampling request only).
   - **Luminance:** for each pixel, linearise sRGB (`c ≤ 0.04045 ? c/12.92 : ((c+0.055)/1.055)^2.4`) and compute `L = 0.2126 R + 0.7152 G + 0.0722 B`. Take the **75th percentile** of the 192 values, so a bright sky over a dark band counts as bright.
   - **Scrims Riff draws on top:** for example Home's speed-dial scrim, or the dark edge fade of Feature 11 once it exists. Combine them: `L_eff = L · (1 − scrimAlpha)`, using the scrim's alpha at the top of the screen.
   - **Decision:** dark icons when black would contrast better than white. By the WCAG contrast formula, that crossover is at `L_eff ≈ 0.179`. Use **hysteresis** to stop flicker: switch to dark above 0.21, back to light below 0.15.
   - **Cost:** one small calculation per artwork, cached by artwork URL, so it costs nothing per frame.
3. **Who decides: new `LocalStatusBarRequests`** in `RiffApp`.
   - Each screen or overlay that draws a picture under the status bar reports `StatusBarRequest(lightBackground: Boolean, priority)`. Priorities, highest first:
     1. the full-screen player;
     2. open sheets and menus;
     3. a collection, artist or album header **while it is under the status bar**;
     4. Home's mood photo;
     5. the default (dark UI, so white icons).
   - The highest-priority active request wins. The shell applies it in a `SideEffect`: `controller.isAppearanceLightStatusBars = winner.lightBackground`.
   - Changes are debounced by 120 ms, so a fast scroll or a song crossfade doesn't flash the icons.
4. **Screens that report:**
   - **Player** (`NowPlaying.kt`): from the current song's artwork. It changes with the song, after the artwork crossfade's midpoint.
   - **Collections, albums, artists, playlists** (`ImmersiveCollection.kt`, `DownloadsScreen.kt`): from the header image. They stop reporting once the header's bottom has scrolled above the status bar, so the dark list below gets white icons again.
   - **Home:** from the current mood photo, combined with the speed-dial scrim.
   - **Lyrics:** its background is a blurred, darkened version of the art, so it reports from the blurred colour (usually dark).
5. **Riff's own controls over pictures follow the same decision.** When the strip is light:
   - the close and back buttons (`OverPictureIcon`), "NOW PLAYING", and other over-picture text switch to dark ink (`#1C1C1E` at 90 %) with a soft light shadow (`Shadow(Color.White.copy(alpha = .35f), blurRadius = 6f)`) instead of white with a dark shadow;
   - the change cross-fades over 200 ms;
   - glass buttons (Features 1 and 2) take the dark tint at the same time (Feature 1, step 9).
6. **Navigation bar:** when artwork reaches the bottom of the screen (the "Full-screen cover art" option, or a white header filling the screen), the same measurement on the bottom strip sets `isAppearanceLightNavigationBars`. Riff's player controls sit on the darker artwork tone (`readableArtworkTone`), so this is rarely needed, but it's the same code.
7. **High contrast setting:** when on, prefer the scrim. Draw a 24 dp dark gradient (alpha 0.5) under the status bar on light pictures and keep white icons, for users who find dark icons on busy pictures harder to read.

### 33.3 Tests

- **Luminance:**
  - white → dark icons, black → light icons, mid grey around the threshold with hysteresis (no change between 0.15 and 0.21);
  - a white image with a 0.6 black scrim → light icons.
- **Crop mapping:** a tall image cropped to a wide header samples the right rows.
- **Priority:** the player beats a collection header; a header that scrolled away stops reporting; with no requests, the default is light icons.
- **Instrumented, on the emulator:**
  - open a fixture collection with a white header and check `WindowInsetsControllerCompat.isAppearanceLightStatusBars` is true;
  - scroll down and check it's false;
  - play a song with white artwork in the player and check it's true.
- **Screenshot tests:** the player and a collection on white artwork, showing dark status-bar icons and dark over-picture controls.

Match: the same as Apple Music and YouTube Music on light artwork. The status bar becomes readable wherever Riff shows a light picture behind it.

---

## Feature 34: The player redesigned after BitChord's

The owner wants Riff's full-screen player to look like BitChord's (screenshots of 28 Oct 2026, "Hush Hush" by Meira Omar in both apps), with two exceptions: **keep Riff's shuffle and repeat buttons**, and **keep Riff's "NOW PLAYING" label and X close button**.

### 34.1 The two players side by side

| Part | BitChord (target) | Riff today | Change |
|---|---|---|---|
| Top | Grab handle and "Playing from Recents" | X and "NOW PLAYING" (`NowPlaying.kt:326–336`) | **Keep Riff's** (exception 2) |
| Artwork | Full-bleed sleeve over the top ~45 %, its lower edge melting into the background | Full-bleed sleeve that fades into a flat colour (`PlayerArtwork.kt` `DstIn` fade; `playerBackground` in a single `readableArtworkTone`) | Melt into a blurred artwork backdrop (34.2) |
| Lower half | Blurred, glassy continuation of the artwork's own colours | Flat colour block | **Glass deck** (34.2) |
| Tech line over the art | "Opus · 48.0 kHz · 270 kbps · Stereo" (white 65 %) and "Automix · …" (white 50 %), with a soft shadow | None | Feature 16's stats line, shown when "Show stats for nerds" is on (34.3) |
| Title | Large, bold, white | 22 sp bold white | 26 sp bold, Feature 3 type tokens; scrolls when long (Feature 7) |
| Artist | Large, regular, white ~55 % | 13 sp, `Muted` | 22 sp regular, white 55 % |
| Like and more | Two 44 dp glass circles (star, ···) | Plain 40 dp icons | Glass circles (34.3) |
| Lyrics line | Current line ("Break me, come follow me, take me ›") just above the seek bar | None | **New lyric line** (34.4) |
| Seek bar | Hairline capsule, no thumb, white 92 % on 26 %; grows while dragged | Material slider with thumb (`CapsuleSlider`) | Thin slider (34.3) |
| Times | White 55 %, small | `Muted`, 12 sp | White 55 %, 12 sp, tabular figures |
| Centre of the time row | "🎧 High quality" at white 45 % | None | Quality label (34.3) |
| Transport | Previous / play-pause / next only, large (53 dp skips), previous faded at a song's start | Shuffle, previous, play, next, repeat (42/54/64 dp) | **Keep shuffle and repeat** (exception 1); BitChord's sizes and fades for the others |
| Volume | Thin slider with speaker glyphs at white 50 % | `CapsuleSlider`, white icons | Thin slider, icons at 50 % |
| Bottom row | Lyrics button · a two-part pill (output │ listen-together) · queue button; device name below at 55 % | Lyrics · output icon with device name · queue (`AudioOutputControls.kt`) | Pill for output (34.3); same three positions |

### 34.2 The lower-half glass deck

BitChord builds the lower half from the cover itself, so it always matches the sleeve (`ArtworkMeshBackdrop.kt`):

1. **Mesh.** The sleeve is averaged into a **6 × 6 grid** of colours (`MESH_GRID = 6`), sampled from a 128 px copy.
   - The row against the seam (where the sharp art ends) is kept in place.
   - Everything below it is **flipped, then rotated sideways**, so the backdrop holds the cover's own colours in the cover's own proportions without reading as a mirror image of a face or horizon.
   - It's held as a tiny bitmap (32 px texture) and drawn with bilinear filtering in one `drawImage`.
   - Colours get a slight vibrance boost (×1.12) and a floor of 0.045, so near-black covers still have depth.
2. **Blur.** A 128 px copy of the cover is blurred once on a worker thread (3 box-blur passes), cached by URL, and drawn scaled up. There's no live full-screen blur, so opening the player never pays for one. It cross-fades in over 900 ms when the song changes.
3. **Legibility.** A dark gradient over the deck keeps white text readable, from 0 % at the seam to 40 % from 28 % down the deck.

**For Riff:**
- **New `PlayerBackdrop.kt`** with the same three steps, fed by Coil's existing artwork request (`PlayerArtwork.kt` requests 1024 px; add a second 128 px request for the backdrop).
- `NowPlaying.kt:264` draws `PlayerBackdrop` instead of `playerBackground(artworkColor)`.
- `PlayerArtwork.kt`'s bottom fade stays. It now fades into the backdrop rather than a flat colour, so the sleeve "melts".
- **Glass:** with Feature 1's backdrop recorded, the deck's buttons and pill refract this layer. Before Feature 1, they're translucent white fills (34.3), which already match the screenshot closely.
- **Reduce dynamic blur** (Riff's frosted-glass settings): the backdrop is a pre-blurred bitmap, so it stays on. Only live effects are reduced.
- **Video and live radio** keep today's black background (`state.video || state.live`).

### 34.3 Components and opacities

All colours are **white at the stated opacity** over the deck (BitChord's `LocalPlayerTint`). On light artwork, Feature 33's dark ink applies to the parts over the sleeve.

| Element | Size | Opacity / colour | Notes |
|---|---|---|---|
| Stats line 1 (format) | 11 sp | 65 %, shadow black 55 %, 4 px blur, 1 px down | Centred over the art's lower edge; only with "Show stats for nerds" (Feature 16) |
| Stats line 2 (Automix status) | 11 sp | 50 %, same shadow | Only when Automix (Feature 20) is on |
| Title | 26 sp bold | 100 % | Left, 30 dp gutter (`PLAYER_GUTTER`) |
| Artist | 22 sp regular | 55 % | Tap opens the artist, as today |
| Like / more circles | 44 dp circles, 20 dp icons | Fill white 18 %, icon 100 % | Glass (`GlassRole.Button`) once Feature 1 lands |
| Lyric line | 17 sp semibold | 92 %, chevron 55 % | 34.4 |
| Seek bar | 7 dp idle, 12 dp while dragged; no thumb | Played 92 %, rest 26 %, marker 50 % | New `ThinSlider.kt`; touch target 34 dp; Riff's slider haptics |
| Times | 12 sp, tabular | 55 % | "0:06" and "−2:47" |
| Quality label | 12.5 sp medium, 14 dp headphone icon | 45 % | "High quality" / "Medium" / "Low" from Feature 17's rule; tap opens the quality setting |
| Shuffle, repeat (**kept**) | 42 dp, 22 dp icons | Off 50 %, on 100 % with a small dot under | Same positions as today: outside previous and next |
| Previous, next | 53 dp icons, drawn 0.85 × tall | 100 %; 35 % when unavailable | BitChord's skip glyphs: two joined triangles |
| Play / pause | 64 dp | 100 % | Two rounded bars for pause |
| Volume icons | 20 dp | 50 % | Thin slider between, 6 dp idle, 10 dp dragged |
| Bottom buttons (lyrics, queue) | 44 dp, 24 dp icons | 100 % | Lyrics icon is a speech bubble with quotes |
| Output pill | 44 dp tall, 64 dp per segment | Fill 12 %, hairline divider 20 % | One segment (output) now; a second (Cast, Feature 19) when it exists |
| Device name | 11 sp | 55 % | Under the pill, as today |

**Spacing, top to bottom** (on a 360 × 800 dp phone):
- art band 45 %;
- stats lines;
- 20 dp gap;
- title and artist block with the circles;
- 24 dp gap;
- lyric line;
- 8 dp gap;
- seek bar and time row;
- 28 dp gap;
- transport;
- 28 dp gap;
- volume;
- 20 dp gap;
- bottom row and device name.

Use `Arrangement.SpaceBetween` within a min/max so short screens compress the gaps first, as Riff's `controlHeight` logic already does (`NowPlaying.kt` comment "Where the page scrolls…", Q13).

### 34.4 The current lyric line above the seek bar

- **Data:**
  - **Line:** the player already loads lyrics for the current song (`LyricsRepository.observe`, used by `LyricsPanel`). Collect the same `StateFlow<LyricsLoadState>` in the player and find the active line with the existing `activeLyricLines(lines, position + offset)`.
  - **Position:** read through `playback.positionMs`, polled as `LyricsPanel.kt:86` does, but only while the player is open and the row is visible.
- **What shows:**
  - **Timed lyrics:** the current line, or before the first line and between lines, the last sung line at 55 % until the next starts. During an instrumental gap (Feature 32), the three dots, small (9 dp), filling the same way.
  - **Plain (untimed) lyrics:** the first line of the lyrics, then "›". Tapping still opens them.
  - **Instrumental songs and no lyrics:** no row. The space closes, so nothing jumps when lyrics arrive late; the row fades in over 250 ms.
- **Look:** one line, 17 sp semibold, white 92 %, left-aligned with the title.
  - **Long lines:** cut with "…". They don't scroll, because a moving line under a scrolling title (Feature 7) would compete.
  - **Chevron:** "›" at 55 % after the text.
  - **Line changes:** the new line slides up 6 dp and cross-fades over 250 ms. Under reduced motion, an instant change.
- **Tap:** opens the full lyrics (`onLyrics`, the same as the bottom-left lyrics button), scrolled to the current line. The row is a 44 dp tall touch target with `contentDescription` "Lyrics: <line>. Open full lyrics".
- **Word timing** isn't shown in this row; it's a preview. The full lyrics keep the word sweep.
- **Setting:** Settings → Lyrics → "Show the current line in the player" (on by default).

### 34.5 What stays as it is

- **Riff's X and "NOW PLAYING"** (exception 2): position, size and over-picture shadow. With Feature 33, they switch to dark ink on light covers.
- **Shuffle and repeat** (exception 1): their actions, test tags (`playback_shuffle`, `playback_repeat`), semantics, and the repeat-one icon.
- **The mini player ↔ player morph:** it measures the title, artist, play and next with `onMorphElementBounds` (`PlayerPresentation.kt`). The new sizes must keep reporting those bounds, so the morph lands on the new positions. Check the morph on the phone after the change.
- **The queue and lyrics sheets,** the more menu, the sleep timer countdown, and the playback problem banner (`PlaybackProblem`) move with the layout but don't change.
- **Large text** (font scale ≥ 1.5): the player already scrolls on short screens. The new layout keeps that: the art band shrinks to 35 %, and the lyric row and stats lines are hidden.

### 34.6 Steps

1. `PlayerBackdrop.kt` (mesh, pre-blur, scrim) and its use in `NowPlaying.kt`.
2. `ThinSlider.kt`, used for the seek bar (replacing `CapsuleSlider` in `SeekControl`) and for the volume in `AudioOutputControls.kt`.
3. The title block: sizes, colours, and the two circle buttons.
4. `PlayerLyricLine.kt` (34.4) and its setting.
5. The time row with the quality label.
6. The transport row with BitChord's sizes and fades, keeping shuffle and repeat.
7. The bottom row with the output pill.
8. Stats lines (when Feature 16 exists).
9. Glass for the circles and pill (when Features 1 and 2 exist).

### 34.7 Tests

- **Screenshots** (dark, light and colourful covers; 360 × 800 and 411 × 915 dp; font scale 1.0 and 1.5) compared with the owner's BitChord reference by eye, then kept as baselines in `scripts/run-ui-regression.ps1`.
- **Semantics:**
  - all existing player tags still present (`playback_toggle`, `playback_previous`, `playback_next`, `playback_shuffle`, `playback_repeat`, `playback_favorite`, `player_more`, `player_close`);
  - the lyric row is labelled and opens lyrics.
- **Lyric line:** with a fake clock and a timed document, the row shows the active line, the last line between lines, dots in a gap, and no row for instrumentals; a tap calls `onLyrics`.
- **Backdrop:** the mesh of a solid colour is that colour; a half-black, half-red cover gives a mostly black backdrop (proportions kept); the cache returns the same bitmap for the same URL.
- **Performance:** the player's frame timings in the `:benchmark` lane before and after; no live full-screen blur in the trace.
- **Morph:** the existing morph tests (`morph_mini_title`, and the bounds for title, artist, play, next) still pass.

Match: very close to BitChord's player, with Riff's shuffle, repeat, X and "NOW PLAYING" kept.

---

## Feature 35: Calmer, slightly slower transitions

The owner wants transitions across the app to take a little longer, so they feel comfortable to the eye rather than snappy.

### 35.1 Riff today

- **Shared timings:** most animations use the timings in `GlassMotion.kt`:

  | Token | Today | Used for | Uses (`GlassMotion.<token>`) |
  |---|---|---|---|
  | `pressMs` | 100 ms | Press feedback | 2 |
  | `selectionMs` | 180 ms | Selection, toggles, lyric line dimming, menus | 13 |
  | `screenMs` | 280 ms | Screen changes, sheets | 8 |
  | `playerMs` | 380 ms | Mini player ↔ player morph, queue and lyrics in the player | 14 |
  | `artworkMs` | 360 ms | Artwork changes | 1 |
  | `ambientMs` | 900 ms | Backgrounds, mood photos | 1 |
  | `settle` (spring) | `StiffnessMedium`, no bounce | Things that settle into place | 3 |

- **Curve:** `GlassMotion.timed` uses `FastOutSlowInEasing` (cubic 0.4, 0, 0.2, 1) and scales by the phone's animator setting. Reduced motion (Riff's setting, or the system scale at 0) turns animations off with `snap()`. That's 40 uses of `timed`.
- **Outliers:** about **20 animations** (`fadeIn`, `fadeOut`, `slideIn…`, `expandVertically` in 18 files) don't use the tokens, and about **10 hard-coded durations** (120–1,800 ms) and **6 springs** (stiffness 140–700, `StiffnessMediumLow`) are set in place. They won't follow a change to the tokens unless moved onto them.

### 35.2 The new timings

Longer, with a gentler curve: Material 3's "standard" easing (cubic 0.2, 0, 0, 1), which starts promptly and spends longer settling. That slow ending is what reads as comfortable. Exits run at 75 % of the entry time, so the eye follows what arrives, not what leaves.

| Token | Today | Calm (new default) | Notes |
|---|---|---|---|
| `pressMs` | 100 ms | **130 ms** | Kept short: a press must answer the finger at once |
| `selectionMs` | 180 ms | **260 ms** | Toggles, chips, tabs, menu open |
| `screenMs` | 280 ms | **420 ms** | Push/pop between screens: fade-through with a 24 dp slide |
| `sheetMs` (new) | — | **450 ms** | Sheets and dialogs rising; previously `screenMs` |
| `playerMs` | 380 ms | **520 ms** | Mini player ↔ player morph, queue/lyrics inside the player |
| `artworkMs` | 360 ms | **520 ms** | Artwork crossfades between songs |
| `ambientMs` | 900 ms | **1,200 ms** | Backgrounds and mood photos |
| `settle` spring | Stiffness 1,500 (Medium), damping 1.0 | **Stiffness 400 (MediumLow), damping 0.9** | Softer arrival, a hint of give, no visible bounce |
| Exit durations | Same as entry | **75 % of entry** | E.g. a screen leaving in 315 ms while the next arrives in 420 ms |

### 35.3 What must not slow down

Making these slower would feel laggy rather than calm, so they keep their timing:

- **Anything that follows a finger:** drags, the player pull-down, the drag lens (Feature 1), sliders, the tone pad (Feature 10), scroll and fling. These track the finger 1:1; only the **release** animation uses the new `settle` spring.
- **Music-timed motion:** the lyric word sweep and line lighting (`LYRIC_LIGHT_MS = 32`, deliberately instant since Q18-056, "a slow fade read as late"), the instrumental dots (Feature 32), the seek bar, and the crossfade's visual blend, which follows the audio.
- **Press feedback and haptics:** 130 ms at most, as above.
- **Loading:** these changes affect only visual transitions, never when data arrives or a song starts.

### 35.4 Implementation

1. **`GlassMotion.kt`:**
   - change the token values and the curve as in the table;
   - add `sheetMs`;
   - add `exit(ms)` returning 75 % of `ms`;
   - change `settle` to `spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)`.

   The existing scaling by the phone's animator setting and the reduced-motion `snap()` stay as they are.
2. **Move the outliers onto the tokens.** Each of the ~20 untokenised enter/exit animations, ~10 hard-coded `tween(…)` values and ~6 springs gets the token that matches its role:
   - a sheet uses `sheetMs`;
   - a background uses `ambientMs`;
   - a gesture release uses `settle`.

   Keep a written list in the change of each site and its role. Values tied to music or gestures (35.3) are left alone, with a comment saying why.
3. **A static check** in `scripts/quality` (beside the existing checks run by `verify.py --lane pr`) fails on a new `tween(`, `spring(`, `fadeIn(` or `fadeOut(` with literal timing outside `GlassMotion.kt`, unless the line carries a `// motion: music-timed` or `// motion: gesture` marker. That keeps future screens consistent.
4. **Setting:** Settings → Appearance → **Motion: Calm / Quick**.
   - Calm is the new default.
   - Quick restores today's values (a 0.7 multiplier on the new ones, applied in `GlassMotion.timed`).
   - Reduced motion still overrides both.
5. **New features use the tokens from the start:** glass (1, 2), Encore (12) including story slide changes at `screenMs`, the equalizer (10), the player redesign (34), "What's new" (29) and the rest.

### 35.5 Tests

- **Tokens:**
  - unit tests for `GlassMotion.timed` under Calm, Quick, a system animator scale of 0.5 and reduced motion;
  - `exit` at 75 %;
  - `settle`'s stiffness and damping.
- **Compose UI tests** with `composeTestRule.mainClock.autoAdvance = false`:
  - opening a screen isn't settled at 300 ms and is settled at 450 ms (Calm);
  - with Quick, settled by 300 ms;
  - with reduced motion, settled at once.
- **The static check:** it passes on the moved code and fails on a sample with a literal `tween(300)`.
- **Benchmark lane:** journeys that wait for a transition to finish take slightly longer. Update their expected settle times in `docs/quality/performance.md`, and confirm frame timing (longer animations make dropped frames more visible).

Match: the whole app moves at one calmer pace, set in one place, with Quick available for anyone who prefers today's feel.

---

## Order of work, testing and release

### Suggested order

| Stage | Work | Why this order |
|---|---|---|
| 1 | Feature 7 (scrolling title), Feature 8 ("See all"), Feature 5 (lyric blur and fade), Feature 4 (lrc.red), Feature 11 (edge fade), Feature 25 (romanized lyrics), Feature 26 (undo), Feature 32 (instrumental dots), Feature 33 (status bar icons), Feature 35 (calmer transitions, first, so every later feature uses the new timings), and Encore's data recording (Feature 12, section 12.3) | Small, independent, low risk; visible at once. Recording listening data early means weeks of real data exist when the Encore page is built |
| 2 | Features 13 (links), 15 (speed), 16 (stats for nerds), 17 (quality per network), 18 (sleep timer), 22 (predictive back), 23 (notification buttons), 24 (search suggestions), 27 (shortcuts and tile), 28 (loading states), 34 (player redesign; its circles and pill become real glass in stage 6) | Small and independent; any order |
| 3 | Feature 9 (silence at the end), Feature 10 (equalizer), then Automix Stage 1 (smart crossfade) | All three work in the same audio chain (`riffAudioRenderers`) and share the song decoder, so build and test them together |
| 4 | Feature 3 (font and type tokens) | Touches many screens' text; best done before the glass work changes the same screens |
| 5 | Feature 1 (glass engine: library, `RiffGlass`, shell, drag lens, collapsing bar, merge shader, quality levels) | Everything else in glass depends on it; the edge fade moves into the glass backdrop here |
| 6 | Feature 2 (glass everywhere: player, menus, settings, search, the equalizer's sliders, the rest) | Built on stage 5 |
| 7 | Feature 12 (Encore page and stories) | Its data has been recording since stage 1; uses the glass and type of stages 4–6 |
| 8 | Feature 14 (Android Auto), Feature 30 (large screens), Feature 6 Stage A (cover motion on the phone) | Independent; Auto needs the Desktop Head Unit for testing. |
| 9 | Feature 19 (Cast), Automix Stage 2 | Larger. Cast adds Google Play services and needs a Cast device; Automix Stage 2 is measured against the test set |
| 10 | Automix Stage 3 and later | Only after Stage 2 is measured and the timing prototype holds 15 ms on the phone |
| Each release | Feature 29 (what's new sheet) | Written for every release that ships the stages above |

### For every stage

- `python scripts/quality/verify.py --lane pr`: JVM tests, lint, static checks, release build and content checks.
- `verify.py --lane emulator --allow-install` on API 31, 33 and 36, especially for the glass (API 31 takes the frost path).
- For stage 3: the device lane on the phone with headphones and with the speaker. Listen for clicks while moving equalizer bands, and check that crossfades still meet in time when Feature 9 moves the end and when Automix chooses the points.
- For stages 5 and 6: `verify.py --lane benchmark` on the phone before and after. Cold start, scrolling frames and memory against the recorded rc10 numbers in `docs/quality/performance.md`.
- Update `docs/features.md`, `docs/privacy.md` (lrc.red), `NOTICE.md` and the dependency inventory (backdrop, shapes, Inter variable), and `CHANGELOG.md`.
- Keep each stage in its own branch and commits, so any one can be dropped without the others.

## Sources

- BitChord: [repository](https://github.com/kushagrasinghx/BitChord), [Liquid Glass fidelity PR #1108](https://github.com/kushagrasinghx/BitChord/pull/1108), [lrc.red request #410](https://github.com/kushagrasinghx/BitChord/issues/410), [Android Police review](https://www.androidpolice.com/open-source-youtube-music-alternative-bitchord-features-comparison/)
- Kyant0 backdrop: [AndroidLiquidGlass on klibs](https://klibs.io/project/Kyant0/AndroidLiquidGlass); ports: [KMPLiquidGlass](https://github.com/Kashif-E/KMPLiquidGlass), [fluid_glass](https://pub.dev/packages/fluid_glass); [AGSL glass write-up](https://dev.to/wrack/liquid-glass-on-android-in-react-native-i-refracted-the-backdrop-with-a-real-time-agsl-shader-1dp4)
- [Liquid Glass (Wikipedia)](https://en.wikipedia.org/wiki/Liquid_Glass)
- Lyrics: [Better Lyrics API](https://github.com/better-lyrics/api), [LRCLib word-sync issue](https://github.com/Andbal23/dynamic-music-pill/issues/193)
- Automix: [9to5Mac on AutoMix](https://9to5mac.com/2025/06/16/automix-apple-music-ios-26/), [MusicTech on AutoMix](https://musictech.com/news/gear/apple-music-automix-ai/), [iGeeksBlog on AutoMix](https://www.igeeksblog.com/apple-music-automix-in-ios-26/), [Beat This! GGUF (licence notes)](https://huggingface.co/cstr/beat-this-GGUF), [Beat This! ONNX](https://huggingface.co/musetric/beat-this-onnx), [open-unmix on PyTorch Hub](https://pytorch.org/hub/sigsep_open-unmix-pytorch_umx/), [UMX-HQ ONNX export](https://huggingface.co/edgetools/umx-hq)
- Cast: [Default Media Receiver overview](https://www.tothenew.com/blog/what-is-chromecast-receiver/)
