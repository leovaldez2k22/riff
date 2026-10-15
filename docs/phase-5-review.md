# Phase 5 browse and collection review — 28 September 2026

> **Historical record.** This document describes earlier work and is kept as it was written. It is not current documentation and may not match the code. Current documents: [documentation index](README.md). Current plan: [Riff excellence implementation plan](RIFF_EXCELLENCE_IMPLEMENTATION_PLAN.md).

## Implemented

- Home uses listening history for Quick picks and Recently played. Speed Dial has two pages, swipe and button paging, and persistent track pinning from the track menu.
- Search has Demo / YouTube Music sources, Songs / Albums / Artists types, a title sort, 450 ms debouncing and cancellation of stale first-page requests. Demo metadata uses `CatalogIndex`. YouTube Music song search caches successful pages for five minutes and exposes continuation paging when the anonymous provider returns a token.
- The provider adapter now recognizes both shelf-level `nextContinuationData` and item-level continuation tokens. If a fixed Songs-filter response has no rows, it makes one bounded unfiltered request, looks for a query-specific Songs chip, and retries that filter when present. A privacy-limited diagnostic logs only response-shape counts and booleans, never query text, song IDs or tokens. These response shapes follow the independent [Kaset API discovery notes](https://github.com/sozercan/kaset/blob/main/docs/api-discovery.md); they are not an official YouTube Music contract.
- Library has filters and locally persistent named demo playlists. Users can create, rename, add/remove tracks, reorder with arrows, delete a playlist, and Undo track or playlist removal. A separate existing Saved demo mix remains available. Provider favorites, mix and history saved on this phone remain available without claiming account sync.
- Play on a demo playlist or album now replaces the active demo queue with that collection's order. Tapping a track within the collection starts at its position. Playlist order and selected-row start passed the follow-up phone check; album Play uses the same gateway path but was not separately tapped.
- Playback persistence now keeps the last demo resume point when an on-device or provider track temporarily replaces the session; non-demo position updates do not overwrite the saved demo queue index.
- Album and artist pages route to the selected fixture. Album detail has a full-width artwork hero, centered metadata, a derived-color transition into numbered tracks, Play, save/remove from the local demo mix and More actions. The rows are generated from album membership rather than a fixed single row. Playlist and route state survive activity recreation where the shell saves state.
- A deterministic metadata-only generator covers 10,000 entries including duplicate titles, long names and missing artwork. Library Preview tools can run its search timing on device without shipping audio files.
- The component gallery includes a clearly labeled, non-playable missing-art/long-title row to inspect ellipsis and menu clearance without presenting synthetic metadata as a catalog song. Its album tiles now open their own selected albums.
- The player exposes a Library shortcut sheet for All, Favorites, History and Playlists, retaining the playback session when it closes.
- The optional On-device Library route requests `READ_MEDIA_AUDIO` only when opened, lists MediaStore metadata, searches it locally and submits selected files to Media3 playback. Denial, empty results, query failure and unavailable playback are represented in the UI. Grant, denial, populated search and playback have been exercised on the phone.

## Build and connected-phone evidence

`assembleDebug` and `lintDebug` pass. On the iQOO Neo 7 Pro, a named playlist was created, received a track, survived force-stop/restart, and then passed remove/Undo and delete/Undo checks. The test playlist was removed, leaving the earlier Saved demo mix (`1,3`) and history intact. The Saved demo mix passed reorder and restore (`1,3` → `3,1` → `1,3`). Captures in `artifacts/device-checks/M05/` show Home, Library, the track menu, playlist detail, album detail, Demo search and album type results. Speed Dial's second page exposed items `2` and `3` after the Next control was pressed. On-device metadata benchmark logged **10,000 items, 536 matches, worst 24.0 ms over 20 searches** (`RiffBenchmark`, 28 September 2026), under the 300 ms target.

The later device pass installed the updated APK. Player → Library shortcut → Favorites opened the expected filter while the mini-player remained visible. The On-device route explained its permission request, showed a retry after denial, then listed 101 MediaStore audio files after permission was granted. Searching a local title narrowed the result. Selecting a local file changed the mini-player source to “On device”; Play/Pause worked. The player omitted online favorite and mix actions for this source. The test track was paused and Home restored. No matching AndroidRuntime fatal exception appeared in the checked recent logcat output.

The follow-up Phase 5 phone pass captured the revised Night Transit album page at `artifacts/device-checks/M05/20260928-final/album-final.png`: full-height artwork-derived hero, centered title, Play/save controls and a numbered track row were visible. Playing the saved demo mix created queue `1,3`; tapping its second song started index 1 without changing that order. After force-stop/restart, the song and queue recovered. The phone's pre-test paused Low Orbit playback, queue `1,0,2,3`, saved mix `1,3` and history `3,2,0,1` were restored and verified. The album screenshot revealed a hard lower edge after the single track; a subsequent gradient fade fixes that edge in code and has not yet been recaptured on the phone. The source-aware demo-resume fix is also build-only.

A separate approved public-song search for “Blinding Lights” on the iQOO returned “Song catalog unavailable on this device” with the fixed Songs filter. The bounded unfiltered/live-chip fallback build returned the same state. That build did not record whether a Songs chip was present; the later shape-only diagnostic is build-only. No provider playback was started, and the phone's pre-test paused playback, queue and collections remained unchanged.

## Source capability boundary

| Area | Demo / on-device | Anonymous YouTube Music |
|---|---|---|
| Home, shelves, search, album/artist detail | Implemented for fixtures; local files have metadata list and search only | Song search implemented; account Home, albums and artists unavailable |
| Collections | Demo favorites, pins, playlists, history persist locally | Track favorites, saved mix and history persist locally; no account synchronization or remote playlist mutation |
| Playback | Demo queue/reorder and background audio checked; local file playback checked | Direct stream resolution and playback are experimental; selected-track queue has no editable next items |
| Search paging and rate limits | Local search is indexed; 10,000-item benchmark passed | Continuation token handling, short-lived cache and paced requests implemented; live continuation remains unverified |
| Lyrics, Samples, offline media | Demo timing/visuals only; local audio reads files already on the phone | No verified catalog lyrics, Samples feed or authorized downloads |

The app labels fixture and local content by source. No YouTube Music account access or offline download entitlement is implied.

## Remaining M5 checks and limits

- Install and inspect the final APK after the album's lower gradient fade, MediaStore defensive changes and source-aware demo-resume fix; those edits have passed build/lint but have not been recaptured on the phone.
- Complete a device pass through Search to play to player to queue reorder and restart recovery. Check missing art, long names and empty states at large font scale, along with album hero colors and bottom-row clearance. Playlist rename passed in the 28 September follow-up below.
- Exercise empty MediaStore lists, missing files and revoked permission on the phone. Grant, denial, populated list, search and playback already passed.
- Anonymous YouTube Music access supplies experimental song search and playback resolution. Account library and playlist mutations are not exposed as account features. Continuation paging is implemented but needs a live provider response with a token to verify it on the phone.
- Investigate the shape-only search diagnostic on a new approved device window. If the anonymous response still has no usable song shelf, provider streaming cannot pass the M5 gate through this guest client; avoid showing a false successful catalog.
- The fixture catalog now contains five two-track albums. Multi-disc and unavailable-track rows still have no fixture coverage; five-artwork-family comparison and two-track album layout need a fresh device capture.

Phase 5 is in progress. Do not mark the M5 gate passed until the remaining device checks and source-capability review are complete.

## Subsequent UI and demo update (build-only)

- The user replaced Samples with Podcasts and Radio. Both sections currently expose clearly labeled original playable demo material; neither claims a live podcast feed or radio stream. Search remains a detached navigation destination.
- The original catalog now has ten tracks on five two-track albums, each with full original image artwork. The pale-neutral fifth cover expands the color families available for album checks. Demo playback resources, album membership, and timed demo lyric lines were expanded together.
- Online search rows use the provider's artwork thumbnail when available. Playback metadata retains the selected artwork URI so the mini-player and full player can display it. If the response has no thumbnail, a YouTube image URL based on the validated video ID is used.
- The player layout has a shorter artwork region and tighter controls, with a continuous artwork-color fade into the controls. The player status inset is black. Mini/full swipes require less travel, and the play/next glyphs crossfade later in the morph. Output route text now polls `AudioManager.getAudioDevicesForAttributes` for the current media destination.
- Album detail has full-width artwork, centered metadata, a compact action row, and numbered track rows on the sampled album color. The chrome shader blurs live pixels within each glass control without adding a dark tint; its compact navigation geometry now matches the five-destination layout.
- `assembleDebug` and `lintDebug` pass after these edits. The phone was detached at the user's request, so route switching, player fit and swipe feel, shader appearance, and album contrast still require a device pass when USB debugging resumes.

## 28 September phone follow-up

The updated APK installed with `adb install -r` without clearing app data. The full player displayed every control, including volume and output, without scrolling on the iQOO. Its output row showed the connected **Noise Airwave Max XR**, matching the selected Bluetooth device. The status bar background was black. The first player capture showed a narrow dark seam between artwork and controls, which was removed in source and rebuilt.

Podcasts and Radio each opened from their new tab and displayed the expected original demo content. A 200-pixel upward swipe on the mini-player opened the full player; a 240-pixel downward artwork swipe restored the mini-player. Dragging the selected tab from Radio to Podcasts switched pages. Blue Hour and White Static album captures showed full-width image artwork, two numbered songs, a sampled-color background, and the compact action row.

The first transparent-chrome capture still showed legible text through the mini-player, and a 16-sample blur produced visible repeated text. A denser 49-sample untinted blur was installed in a later approved two-minute check. The runtime shader loaded and the final Home/player captures showed no crash. Some high-contrast text remains visible through the clear mini-player, so optical legibility is still a tuning item; frame smoothness has not been measured. The final player capture showed the artwork-to-control seam removed. Its route row read “This phone,” whereas the earlier connected-device capture read “Noise Airwave Max XR,” consistent with the route changing between captures. Evidence is in `artifacts/device-checks/M05/20260928-ui-update/`.

## Later Phase 5 continuation

The latest approved phone window verified playlist rename: a temporary `M5Check` playlist was created, renamed to `M5Renamed`, and deleted. UI hierarchy captures are under `artifacts/device-checks/M05/20260928-ui-update/`. An incoming call interrupted the next album capture, so phone interaction stopped and the accidental call screenshot was removed. No Search → queue or permission-revocation result is claimed from that window.

The artist page now lists every demo song and album by the selected artist and starts playback from the corresponding position in that artist's queue. Demo search preserves distinct songs with the same title while album and artist results show each collection once. Library Albums and Artists filters likewise show unique entries. The on-device Library rechecks audio permission and metadata when the app resumes, clearing stale rows if permission was revoked. Selecting a missing or inaccessible local file now fails before replacing the current playback queue. These changes pass `assembleDebug` and `lintDebug`. The artist and album layouts were checked on the phone; local-permission recovery and missing-file handling still need device checks.

The later upgrade install preserved app data. On the phone, Night Transit showed its artwork-to-track gradient and both numbered songs; Glasshouse Atlas showed both songs and its album. A Demo-only search found “Midnight Platform”. After selecting it, the saved playback index changed to its queue position, but the Android media session still reported the previous “Evergreen Signal” item. This playback mismatch needs diagnosis before the Search → queue → restart journey can pass. The attempted logcat read was rejected by automatic approval review because the approval service had reached its usage limit, so no further device action or conclusion followed.

## Final Phase 5 continuation and deferral

The apparent Demo Search playback mismatch was timing: an immediate MediaSession capture showed “Midnight Platform” buffering, and the short demo advanced to “Evergreen Signal” before the slower UI snapshot. Demo Search playback, queue reorder (`0,4,3` to `4,0,3`), force-stop recovery, and restoration of the original queue were verified. Night Transit and Glasshouse Atlas each displayed both matching tracks on the phone. A 130% font-scale gallery check kept the long-title row clear of the mini-player. Revoking `READ_MEDIA_AUDIO` showed recovery UI and cleared local rows; granting it again restored 101 files. The original font scale, permission, queue, and paused playback state were restored.

The user approved in-app YouTube Music sign-in. A WebView account screen now collects the session on the phone without asking for credentials in chat. Cookies remain in WebView storage; app-private preferences hold the noncookie page session fields, and sign-out clears both. Authenticated search returned real song rows and survived force-stop/restart. The live first result for “Blinding Lights” reached the extractor, but direct and HLS-only extraction returned `NO_PLAYABLE_STREAM`. With SABR enabled, the extractor selected SABR; its first media request returned `RELOAD_PLAYER`. A single fresh-player-response retry returned `RELOAD_PLAYER` again. The app now reports the unsupported transport cleanly and keeps the prior playback session. No real-song playback success is claimed.

At the user's direction, work moves to later phases with the **M5 streaming gate still open**. Other account library features remain bounded by the unofficial provider's capabilities. The temporary SABR probing code was removed after the last failed retry. `assembleDebug` and `lintDebug` pass on the cleaned build.
