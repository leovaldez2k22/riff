# Handoff: writing the plan for Riff 0.30.0's features (7 November 2026)

This is for the AI that writes the next plan. Everything was decided with the owner on 6–7 November 2026 in one conversation; this file carries what that conversation knew, so you don't have to rediscover it.

**Your task:** write the implementation plan for the 23 new features chosen for Riff 0.30.0, on top of the Riff / Riff+ split. The split already has its own plan ([riff-plus-split-plan-2026-11-07.md](riff-plus-split-plan-2026-11-07.md)); treat it as the foundation, not something to redo, unless the owner asks you to rewrite it. Confirm the scope with the owner before writing: one plan covering all features, or one plan per feature.

**Nothing is built.** No code was changed for any of this. Only documents were written.

---

## 1. Read these first, in this order

1. [CLAUDE.md](../CLAUDE.md): project rules. Especially: check line references against the current code; if the code disagrees with a plan, update the plan and say why; ask the owner before decisions a plan leaves open and before anything that publishes, pushes or writes to their YouTube Music account; `python scripts/quality/verify.py --lane pr` before calling a stage done.
2. [feature-picks-2026-11-06.md](feature-picks-2026-11-06.md): **the source of truth** for what each feature is, the owner's specific requests, and which edition it belongs to. Where this handoff and that file disagree, that file wins.
3. [riff-plus-split-plan-2026-11-07.md](riff-plus-split-plan-2026-11-07.md): how the two editions are built (product flavors `riff` and `plus`, the `Edition` seam, `src/plus`), what moves, how switching works, the release and checks. Every Riff+ feature plugs into this.
4. [feasibility-glass-font-lyrics-artwork-2026-10-27.md](feasibility-glass-font-lyrics-artwork-2026-10-27.md): the previous feature plan, now a record. **Copy its format**: per feature, findings, implementation, guards, settings and tests, then an implementation log. The owner is used to it.
5. [automix-engine-report-2026-10-28.md](automix-engine-report-2026-10-28.md): needed for DJ mode (18) and Sing (20), which reuse the Automix engine's analysis and ONNX Runtime.
6. [docs/quality/update-notice.md](quality/update-notice.md): needed for the update feature (3) and the edition switch.
7. [docs/privacy.md](privacy.md): every new network destination or permission must be added here; several features add one.

---

## 2. The state of the repository

- **Branch:** `android-11`. Its working tree has **uncommitted** changes from another piece of work (Android 11 support, `minSdk` 30; see [handoff-android-11-2026-11-06.md](handoff-android-11-2026-11-06.md)). Plans should assume `minSdk` 30, target 36.
- **New, uncommitted documents from this conversation:** `docs/feature-picks-2026-11-06.md`, `docs/riff-plus-split-plan-2026-11-07.md`, this file, and a line in `docs/README.md`.
- **Not ours, leave alone:** `docs/cleanup-list-2026-11-06.md`, `output/`, `research_notes/`.
- **Latest release:** 0.21.0 (version code 35), published 6 November 2026, 44.3 MiB.
- **Nothing is committed, pushed or published.** Don't do any of those without the owner asking.

---

## 3. Decisions you must not reopen

All made by the owner on 6–7 November. Details in the picks file.

**Two editions, one app.** **Riff** (light, everyday) and **Riff+** (Riff with every extra). Same package, version and signing key; either installs over the other and keeps all data. Both free, both GPL-3.0. "Premium" never means paid, and nothing in the app mentions a price. Riff hides Riff+ features completely, except one row in About offering "Get Riff+" (and "Switch to Riff" in Riff+).

**Why:** 0.10.0 was about 10 MB; 0.21.0 is 44.3 MiB, almost all ONNX Runtime (34.2 MB) and the beat model (4.6 MB). The owner wants Riff light again (about 10–12 MB) and fewer rarely used features in the everyday app, since each adds bugs, glitches and lag.

**Riff+ only, from what exists today:** Automix (the blend, the beat model, ONNX Runtime, the echo-out, the bass swap), the 0.20.0 equalizer, Stats for nerds.

**In both, changed from earlier decisions:** Smart blends (they don't use ONNX), and **all of Encore, untouched** (the owner reversed an earlier move of Encore's extras to Riff+).

**Version and timing:** the split is built first, then the features. **Nothing is published until every feature is done**; then everything ships together as **0.30.0**. One GitHub release with `riff-0.30.0.apk` and `riff-plus-0.30.0.apk`. 64-bit ARM only. The old 32-bit `lite` build is removed.

**Not chosen or removed, don't plan them:** song recognition (14, removed), SponsorBlock skipping, automatic replacement of unplayable tracks, a Bluetooth lyric delay, a lyrics editor, Android TV or a browser remote. Also off the table from earlier: animated covers, lossless streaming, the music video in the player.

---

## 4. The features to plan

Numbers are the picks file's; 14 is unused. "Code" lists the files you'll most likely touch: **check each against the current code**, these were found by searching, not by reading every file.

### Riff (both editions have these)

| # | Feature | The owner's specifics | Code to start from | Things to work out |
|---|---|---|---|---|
| 1 | Lyric translations | A button on the lyrics turns translations on; each translated line shows **beneath** its original line. | `LyricsModels.kt:167` currently **drops** provider `x-translation` lines; `LyricsRomanize.kt` is the pattern for a second line; `LyricsKaraoke.kt`, `PlayerLyricLine.kt`, `LyricsPanel.kt` | Which providers send translations; whether to add machine translation for songs without one (a new network destination: ask the owner); how the line beneath interacts with word sweeping, blur and the instrumental dots. |
| 2 | Last.fm and ListenBrainz scrobbling | Off by default; Encore stays private and unchanged. | `EncoreStore.kt` and `ListeningHistory.kt` already record each play; `MusicPlaybackService.kt` | Last.fm's rule (half the song or 4 minutes); now-playing; an offline queue; sign-in (Last.fm needs an API key and secret shipped in the APK; ListenBrainz uses a user token); two new destinations in `privacy.md`. |
| 3 | In-app update | Check GitHub; ask the user; if they confirm, **download, install, then delete the APK**, all inside the app, with a **progress bar for every download**. | `UpdateNotice.kt`, `docs/quality/update-notice.md`; the manifest's `FileProvider` (`${applicationId}.files`) | `UpdateNoticeTest` currently **asserts there is no install code** (no `.apk`, no installer, no install permission): those checks change on purpose. Needs `REQUEST_INSTALL_PACKAGES` and Android's own install confirmation, which can't be skipped. Each edition downloads its own asset (asset names are a contract, split plan 7.1). The same machinery later powers the edition switch (split plan 7.3). Verify the download's signature matches the installed app's before installing. |
| 4 | Jump to the most-replayed part | | `ProviderCatalog.kt`, the innertubex player/next responses | Where YouTube sends the "most replayed" heatmap, and whether it exists for music tracks; a seek-bar marker and a menu item. |
| 5 | Never play this artist or song | | `NotInterested.kt` (Riff's "Not interested"), `QueueCoordinator.kt` | Applies to autoplay and radio, not to a playlist the user chose? Ask the owner. Undo, and where the blocked list is managed. |
| 6 | Bluetooth resume, pause on mute | | `AudioOutputPolicy.kt`, `AudioOutputControls.kt`, `MediaButtons.kt` | Resume only if Bluetooth disconnected while playing; settings for each. |
| 7 | Search by lyrics | | `LyricsRepository.kt` (uses LRCLIB), `AdditionalLyricsProviders.kt`, `SearchSuggestions.kt` | LRCLIB's search endpoint; matching results back to YouTube Music songs; sends typed text to LRCLIB (`privacy.md`). |
| 8 | song.link sharing | | `SharedLinks.kt`, `LyricsShare.kt` | Odesli's API or a plain `song.link` URL; new destination. |
| 9 | Set as ringtone | Downloaded songs only. | `OfflineMusicStore.kt`, `DownloadRepack.kt` | `RingtoneManager` needs the "modify system settings" special permission; copying to `MediaStore`; trimming? Ask the owner. |
| 12 | Playlist import from Spotify, CSV or M3U | | `PlaylistEditing.kt`, `ProviderLibraryRepository.kt`, [spotify-feasibility-assessment-2026-10-02.md](spotify-feasibility-assessment-2026-10-02.md) | Matching each song through YouTube Music search; how unmatched songs are shown; whether the import writes to the user's YouTube Music account (CLAUDE.md: **ask the owner** before anything that writes there) or makes a local playlist. |
| 22 | Select several songs and act on them | Long-press one row, tap more, then Play next, Download or Add to playlist, **keeping their order**. | `CollectionTools.kt`, `PlaylistEditing.kt`, `QueueCoordinator.kt`, `OfflineDownloadEngine.kt` | No multi-select exists today (checked 7 Nov; `musicMultiSelectMenuItemRenderer` in `ProviderCatalog.kt` is only YouTube's menu being parsed). Which lists get it (albums, playlists, search, Library, history); TalkBack. |

### Riff+ only

Each lives in `src/plus` behind the `Edition` seam (split plan 4.3), and its size counts against Riff+ only.

| # | Feature | The owner's specifics | Code to start from | Things to work out |
|---|---|---|---|---|
| 10 | Visualizer without the microphone permission | | `EqualizerProcessor.kt` and `riffAudioRenderers` in `AudioTransitionCoordinator.kt`: Riff already processes its own PCM, so an FFT tap there needs no mic permission (other apps use Android's Visualizer API, which does) | Where it's drawn; battery; off by default? |
| 11 | AutoEq headphone presets | | `EqualizerSettings.kt`, `EqualizerCurve.kt`, `EqualizerScreen.kt` | AutoEq data is MIT; bundle (1–2 MB) or download per headphone; fitting AutoEq's curves to Riff's 7 bands or adding a parametric mode. |
| 13 | On-device mixes from Encore data | | `EncoreRepository.kt`, `HomeFeed.kt` | Which mixes (daily, on repeat, time of day); refresh schedule; where on Home. Encore itself must not change. |
| 15 | Export downloads as tagged M4A | | `DownloadRepack.kt`, `OfflineMusicStore.kt` | Tags (title, artist, album, cover, lyrics); `MediaStore` or a folder the user picks; a tagging library or own code. |
| 16 | New-release alerts | **A switch in Settings; off by default.** | `EncoreRecap.kt` (JobScheduler and notification pattern) | Which artists ("followed" in YouTube Music, or top artists in Encore?); how often; data use; the split plan's `Edition.onAppStart` must cancel it in Riff (split plan 6.3). |
| 17 | Song and artist background | | `LyricsRepository.kt` as a network-fetch pattern | Start with Wikipedia and MusicBrainz (open licences); Genius has no official annotations API and its text is copyrighted, so ask the owner before using it. Fetch only when the panel opens. |
| 18 | DJ mode by key and tempo | Orders the queue by key and tempo, e.g. Steady, Build up, Wind down (as in Prism). | `AnalysisStore.kt` (tempo already stored by Automix), `BeatModel.kt`, the Automix engine report | Key detection is new (signal processing or a model on the existing ONNX Runtime); analysing songs before they play; how the order meets Automix's blends. Check the engine report's stage 3 timing gate. |
| 19 | Listen Together on the same Wi-Fi | No server. | `QueueCoordinator.kt`, `PlaybackSnapshot.kt`, `CastSupport.kt` | Discovery (NSD), clock sync, who controls the queue, each phone streams on its own account or signed out; permissions (nearby Wi-Fi devices on Android 13+). |
| 20 | Sing (vocal remover) | | `OrtBeatLogits.kt` and `BeatModel.kt` as the ONNX pattern; the audio processor chain | A separation model is 15–60 MB: bundle it or **download on first use** (ask the owner; downloading keeps Riff+ around 50 MB). Real-time or analysed ahead; phone speed. |
| 21 | Accent colours and icon styles | Riff+ can change its colour to **preset colours**: Rose, Violet, Blue, Teal, Green, Amber, Coral, Silver. The colour also colours the **launch screen's icon**. The user also picks the **icon style: A, C or D** (picks file, feature 21). **D's partner colours are confirmed** (Rose↔Violet, Blue+Amber, Teal+Coral, Green+Blue, Amber+Violet, Coral+Teal, Silver+darker grey). The icon on the **home screen** changes too. Riff keeps its own colours and icon. | `DesignTokens.kt`, `AmbientTheme.kt`, `ArtworkPalette.kt`; launcher: `res/mipmap-anydpi*/ic_launcher.xml`, `drawable/ic_launcher_foreground.xml` (two ribbons, `#F45B9A`, on `launcher_background` `#09090B`), `ic_launcher_monochrome.xml` | Home-screen icon: one `activity-alias` per style and colour (24) switched with `setComponentEnabledSetting`; some launchers take a moment or drop a pinned icon, so the picker should say so. Launch screen: `SplashScreen.setSplashScreenTheme` (Android 13+) with a theme per combination; Android 12 shows the default; Android 11 has no system launch screen. How the accent meets Riff's artwork-driven colours. Moving down to Riff must restore Riff's icon and launch screen (split plan 6.2–6.3). The icon concepts were SVG sketches in the chat, not files; redraw A, C and D as vector drawables from the existing ribbon path. |
| 23 | Pitch without speed | Transpose up or down in semitones, tempo steady. | `PlaybackSpeed.kt` (speed without pitch exists) | Media3 `PlaybackParameters` pitch; how it meets crossfades and Automix, which adjust speed themselves (the old plan's Feature 15 noted "interaction with crossfade's own speed correction"); per song or global; reset rules. |
| 24 | Blend with a friend by QR code | Each phone shows a QR code of its listener's tastes from Encore; scanning each other's builds a shared playlist; **no server**. | `EncoreRepository.kt` | Riff has no QR code today: generating needs a library (for example ZXing core, Apache-2.0) and scanning needs the camera permission plus a decoder; what fits in a QR code; where the playlist is saved (local, or the user's YouTube Music account: ask the owner). |

---

## 5. Order and dependencies

- **The split comes first** (split plan stages 0–9). Riff+ features need `src/plus` and the `Edition` seam.
- **3 before the edition switch's download:** the switch opens the release page until feature 3 adds in-app installs (split plan Q2).
- **18 and 20** need Riff+'s ONNX Runtime, which only exists after the split's stage 2.
- **10, 11 and 23** all touch the audio chain; plan them together or one after another, with listening checks on the phone.
- **13 and 24** read Encore, which must not change.
- **16 and 21** need cleanup in Riff when a user moves down (`Edition.onAppStart`).
- **21** gives the split's placeholder icon (style A in Rose) its real choice.

---

## 6. How the owner works

- **One step at a time.** The owner approves each stage before the next starts. Ask before starting each task.
- **Ask, don't assume,** on anything a plan leaves open: list open questions in the plan with a recommendation each, as the split plan's section 2 does.
- **Plain language.** The owner asks for explanations of technical ideas (scrobbling, recognition) and decides from them. Say what a feature costs in size, battery, privacy and new permissions.
- **When the owner says "record this, don't start",** only write it down.
- **Plans live in `docs/`**, are linked from `docs/README.md`, and use dated names (`...-2026-11-07.md`).
- **Facts over guesses:** the split plan's findings section (what the code says) caught that Smart doesn't use ONNX, which changed a decision. Do the same: read the code before planning each feature, and say where a decision rests on a mistaken premise.

## 7. Already established, so you don't have to measure again

| Fact | Where it came from |
|---|---|
| ONNX Runtime arm64 is 34.2 MB stored uncompressed; the beat model is 4.6 MB; 0.21.0 is 44.3 MiB | `docs/release-notes-0.21.0.md`, `app/src/main/assets/automix/`, the dependency notes |
| Only `BeatModel.kt` and `OrtBeatLogits.kt` import ONNX Runtime | `grep ai.onnxruntime`, 7 Nov |
| Smart blends use loudness envelopes, not the beat model | `AudioTransitionCoordinator.kt:62–66` |
| The equalizer processor is also Automix's bass swap | `AudioTransitionCoordinator.kt:288`, `:309` |
| Riff saves one queue per account; no multi-select, no QR, no pitch control exist | Code search, 7 Nov |
| Rough size of each Riff+ feature: 10, 13, 17, 19: about 0.1–0.2 MB each; 11: 0–2 MB; 15: 0.1–1 MB; 18: 0.1–2 MB; 20: 15–60 MB, or 0 if downloaded | Estimates from 6 Nov, not measured |
| A trimmed ONNX Runtime build could be about 5–10 MB instead of 34 MB | Mentioned to the owner as a later option; not decided |
