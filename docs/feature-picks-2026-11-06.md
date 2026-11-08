# Feature picks (6 November 2026)

The owner chose these features on 6 November 2026, from a comparison with other open-source YouTube Music clients (Metrolist, SimpMusic, Prism, Levyra, Echo Music, N-Zik, Kreate, BitChord and others). The owner is refining the details before any of them is planned or built. Nothing here is designed or scheduled yet; do not start one without the owner's go-ahead.

Numbers are kept from the first list, so 14 is unused.

## Small to medium

1. **Lyric translations.** Owner's request (6 Nov): a button on the lyrics turns translations on. When it's on, each line's translation shows beneath the original line.
2. Last.fm and ListenBrainz scrobbling
3. **In-app update check.** Owner's request (6 Nov), all inside the app:
   - Check GitHub for a newer release of Riff.
   - Ask the user whether to download the new version.
   - If the user confirms, download the update, install it, then delete the downloaded APK from storage.
   - A progress bar tracks every download.
4. Jump to the most-replayed part
5. Never play this artist or song
6. Bluetooth resume and pause on mute
7. Search by lyrics
8. song.link sharing
9. Set as ringtone

## Medium

10. Visualizer without the microphone permission
11. AutoEq headphone presets
12. Playlist import from Spotify, CSV or M3U
13. On-device mixes from Encore data
15. Export downloads as tagged M4A
16. **New-release alerts.** Owner's request (6 Nov): a switch in Settings turns them on and off. Off by default, since it checks in the background.
17. Song and artist background

## Large

18. DJ mode by key and tempo
19. Listen Together on the same Wi-Fi (removed, then added back by the owner on 6 Nov; dropped by the owner on 8 Nov as too complex for one maintainer)
20. Sing (vocal remover)

## Added by the owner (7 Nov)

21. **Accent colours (Riff+ only).** Owner's request (7 Nov): Riff+ can change its colour to one of a set of preset colours. The chosen colour also colours the icon on the launch (loading) screen. Riff keeps its own colours. **Icon styles (7 Nov):** Riff+ also lets the user choose its icon among three styles, shown to the owner on 7 Nov: **A** (Riff's ribbons with a plus badge), **C** (inverted: a coloured tile with dark ribbons and plus) and **D** (crossed ribbons: two ribbon pairs at right angles, forming a plus). Each style follows the chosen colour. **D** has two colours: its front pair takes the chosen colour and the crossing pair a fixed partner colour, so the cross never disappears (Rose and Violet partner each other; Blue with Amber, Teal with Coral, Green with Blue, Amber with Violet, Coral with Teal, Silver with a darker grey; confirmed by the owner, 7 Nov). The style and colour change the home-screen icon and the launch screen's icon.
22. **Select several songs and act on them together.** Long-press one row, tap more, then Play next, Download or Add to playlist, keeping their order. Riff can already put one song into several playlists; this is the opposite job: several songs from an album or search result into one playlist. Riff today has no multi-select (checked 7 Nov). **Riff** (owner, 7 Nov): a core library action.
23. **Change pitch independently of speed.** Transpose a song up or down in semitones while its tempo stays the same. Riff already changes speed without changing pitch (feature 15 of the old plan); this adds the other control, for singers and players practising in a comfortable key. **Riff+ only** (owner, 7 Nov): a practice tool most listeners won't use. It adds no size.
24. **Blend with a friend by QR code (Riff+ only).** Each phone shows a QR code of its listener's tastes from Encore; scanning each other's codes builds a shared playlist from both. Nothing goes through a server. Seen in Prism.

25. **Choose the lyrics provider.** The listener can choose a source and Riff must search that source for the song. If it finds no lyrics, fall back to automatic source selection. Both Riff and Riff+ include this feature (owner decision). Whether the preference is per song or global is open.

## Two versions of Riff (owner's decisions, 6 Nov)

**Why:** Riff has moved away from its first idea, a lightweight alternative to YouTube Music. 0.10.0 was about 10 MB; 0.21.0 is 44.3 MiB, almost all of it Automix's ONNX Runtime (`libonnxruntime.so`, 34.2 MB, stored uncompressed) and beat model (4.6 MB). The owner also wants fewer rarely used features in the everyday app, since each adds bugs, glitches and lag. Most people won't use the extras most of the time, so they shouldn't pay for them in download size.

**Names (decided 6 Nov):** **Riff** is the base version and **Riff+** the premium one. "Riff" is the one most people should get, so it carries the plain name; "Riff+" says "Riff plus extras". Both are free: release notes and the README must say so, since "+" can read as paid, and the in-app switch never mentions a price. 0.21.0 users' app becomes Riff+, which the split's release notes say in one line. **Launcher icon (7 Nov):** Riff keeps its icon; Riff+'s is chosen by the user from styles A, C and D in any accent colour (feature 21). The old 32-bit "lite" build (`scripts/build-lite.ps1`) is out of date and unused.

**Riff+ only:**

- Automix (the Automix blend, the beat model and ONNX Runtime). Smart stays in both (7 Nov): it does not use the ONNX engine;
- the equalizer of 0.20.0 (kept here by choice: it is about 0.2 MB, so this is about clutter, not size);
- Stats for nerds (moved 6 Nov);
- features 10, 11, 13, 15, 16, 17, 18, 19, 20, 21, 23 and 24 above (16 moved 6 Nov).

**Riff:** everything else, including features 1–9, 12, 22 and 25 above. It keeps:

- **all of Encore, unchanged** (owner, 7 Nov: Encore looks the same in both). This supersedes the 6 November move of Encore's stories, recap, genres, weekly view and widget to Riff+;
- Crossfade and **Smart** (7 Nov: Smart doesn't use the ONNX engine, so it stays in both);
- Moods, which choose Crossfade or Smart (an Automix mood, such as Party, blends as Smart in Riff);
- the volume booster, normalize volume, and the button that opens the phone's own equalizer.

Kept in Riff on purpose (6 Nov): playlist import (12), which people moving from Spotify need on day one; Liquid Glass; Cast and Android Auto; Smart downloads, lyrics and lyric cards.

**Decisions:**

1. **Same app:** both versions share the package name and signing key, so either installs over the other and keeps downloads, the queue and settings. Only one is installed at a time.
2. **Both directions:** a user can move from Riff to Riff+ and back.
3. **0.21.0 users continue on Riff+**, not Riff.
4. **Not paid.** "Premium" only means the bigger version; both are free, and both have their source published (GPL-3.0).
5. **Updates:** each version checks for updates to itself. Riff also offers to download and switch to Riff+, and Riff+ offers to switch back to Riff.
6. **No 32-bit builds.** 64-bit ARM only.
7. **One codebase** builds both versions; Riff+'s extras, and their libraries, are only in its build.
8. **Size:** about 10–12 MB for Riff is right. The 9 MB of 0.10.0 was only an example of the small size friends liked, not a target. Cast stays in Riff.
9. **Hidden:** Riff hides premium features completely (no "In Riff+" hints), apart from the switch to Riff+ in decision 5.
10. **Releases:** one GitHub release per version number, with both APKs. Both versions always share the version name and code, so switching down is an install of the same version, which Android allows.
11. **Order:** the split is built first, then the new features. **Nothing is published until every feature is done** (7 Nov); the split and the features are released together as **0.30.0**, the first release with both editions.
12. **Switching down:** settings fall back quietly. Automix becomes Smart; the equalizer turns off but keeps its settings for a move back up; new-release alerts stop; Riff+'s accent colour is set aside and comes back on a move up. Encore and its widget are unchanged. No listening data is deleted.

13. **Version:** 0.30.0 (7 Nov), since splitting Riff is a major junction.
14. **Previews:** the release script builds a Riff Preview of each edition (7 Nov).
15. **The old `lite` build** is removed completely (7 Nov).

The split's plan: [riff-plus-split-plan-2026-11-07.md](riff-plus-split-plan-2026-11-07.md).

## Removed by the owner (6 Nov)

- 14 Song recognition

## Not chosen

SponsorBlock non-music skipping, automatic replacement for unplayable tracks, a separate Bluetooth lyric delay, a lyrics editor, and Android TV or a browser remote.
