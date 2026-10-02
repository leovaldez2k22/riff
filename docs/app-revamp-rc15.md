# Riff 0.9.0-rc15

This release implements the latest Home-photo, sleep-timer, collection artwork, navigation, official-video and live-radio requests. The 20 fallback covers follow the revised direction: minimal compositions with visibly designed curves, folds, subtle grain and restrained colour. It keeps the existing package ID and signing key for an in-place upgrade.

## Resulting behavior

| Request | Implementation |
| --- | --- |
| Real Home mood photographs, refreshed daily | Openverse photography search with Wikimedia Commons fallback; date-aware per-mood private file cache; a different candidate from the previous image; background daily job and foreground catch-up; smooth fade into black; creator, licence and source links. Failed requests retain the last successful photo. |
| Sleep countdown and final fade | A small ticking player label works independently of song position, including paused tracks and live radio. A monotonic service timer reduces the player's own volume linearly during the last 15 seconds, pauses at zero and restores its previous level. Cancelling/replacing the timer also restores volume. |
| Playlist and Downloads rows | Compact rows put 42 dp clipped artwork at the left, one-line song title with ellipsis, muted artist underneath and options at the right. Playlist numbering remains removed; album numbering is unchanged. |
| 20 missing-cover designs | Original generated covers are bundled as 512 × 512 WebP assets totaling 331,184 bytes. A stable hash of the collection/song identifier chooses a cover when provider artwork is absent or fails. |
| Output-device icons | Phone, earphones, headphones and external-speaker icons follow the selected media output, with product-name classification for Bluetooth earbuds/headsets/speakers. |
| Liquid navigation contact | A spring-driven selector deforms in both axes and refracts nearby icon/text pixels through a local AGSL lens on Android 13+. Android 12 uses a local elastic fallback. Reduced motion and solid controls disable refraction. Transparent shader boundaries prevent stretched edge bars. |
| Speed dial and Listen again | Speed dial reserves/excludes the provider's Listen again items, fills from other real feed sections and deduplicates IDs. The duplicate custom Recently played shelf is hidden when the provider supplies Listen again. |
| Consistent official song video | The authenticated YouTube Music next request enables its exact Song/Video switcher configuration. Only the requested song's official-video counterpart or its own official video can be chosen, with recording-title checks and rejection of lyric/audio/fan variants. No generic video search fallback is used. The selected song's queue, title and position are preserved when switching. |
| Expanded Radio | 42 curated YouTube channels cover ten genre/news groups plus Featured and All. Only actual current live broadcasts are included; globally repeated video IDs and archive entries are removed. Shared channel requests are bounded to three concurrent fetches and retained in RAM. Lazy horizontal shelves expose every discovered broadcast without composing every off-screen card. |

Native video surface ownership was also repaired: disposing the outgoing video during an artwork crossfade can no longer clear the new video's surface. The final visual pass caught and fixed Radio's glass-category label layer; Home, Search and Radio now have distinct keys and matching foreground/background filtering. Main feed entries are retained against eviction by the larger radio/video cache traffic, while scheduled/account/process clearing still invalidates them.

Artwork provenance, prompts and individual concepts are in [placeholder-art](../licenses/placeholder-art.md). The PNG originals, WebP conversion, contact sheet and generation metadata are retained under `artifacts/features-rc15/placeholder-art-minimal/`. Generation used the built-in image_gen tool, one call per cover, followed by inspection of all 20 covers and the final contact sheet.

## Verification

The final signed production build passes 132 unit tests with zero failures/errors. Full release lint reports zero errors, 77 warnings and five hints. The application-context-only photo singleton accounts for a static-context lint warning; other new findings are resource/KTX suggestions. The APK excludes FeaturePreviewActivity, FixturePlayback and OfflineSmokeInstrumentation from its DEX. Test-only scene activities are available only under the explicit releaseSmoke property.

Physical checks on the I2217:

- The Relax feed downloaded a real 301,013-byte mountain-lake photograph for 2026-10-02, retained creator John William Hammond and CC BY-SA 2.0 attribution, reused the same day's cached object, displayed it behind Home and faded it into black. Offline retention, daily selection and unsafe/non-photographic search-result rejection have policy tests.
- Real Home returned 12 shelves. Speed dial had 27 items, Listen again had 20 and their IDs did not intersect. Repeat feed calls reused the same objects.
- All Radio returned 25 currently broadcasting channels and 154 unique live videos at the time of the check. Other curated channels can be offline. Repeat Radio calls reused its cache. Production horizontal shelf scrolling and category labels were visually checked.
- Exact YouTube Music pairings were Numb → kXYiU_JCYtU, Billie Jean → Zi_XLOBDo_Y and BIRDS OF A FEATHER → V9PVRfjEBTI. Each actual provider request included the switcher configuration. Native Numb video played and resumed after seeking to 30 seconds.
- A native Lofi Girl Halloween broadcast rendered in Riff with its own controls and black player background, survived moving the activity to the background, and completed a one-minute sleep timer. Twenty-nine half-second fade samples fell monotonically from 0.9529 to 0.0167; expiry paused playback and restored the original player level. The output UI correctly showed realme Buds Air7 as earphones.
- A separate paused-player countdown changed from 0:58 to 0:56, proving that its label does not depend on playback position ticks. Cancellation cleared the timer.
- The liquid-navigation and high-contrast fixtures reported 90.0 Hz. Visual captures confirm local contact deformation, no stretched shader edge bars, and readable stable labels in solid controls. The queue handle moved a row immediately from index 1 to 4, auto-scrolled at the bottom from index 6 to 11, reversed to 4, preserved all 51 queue entries and retained the playing item.
- The real four-song Chill Music playlist displayed artwork, artist subtitles, compact rows and single-line title ellipses. The current account has no ready offline audio bundles, so the changed populated Downloads row layout is source-verified; a new download was not created solely for this cosmetic check. Existing download seek/order tests remain green.
- The original seven-item podcast queue and 6,551 ms position were restored paused after native tests. No existing remote playlist was edited. The final app-process crash capture contains no new fatal exception.

Earlier failed attempts are retained as diagnostics and excluded from passes. They revealed the outgoing native-surface bug and a test-only screenshot exceeding Android's Binder transaction size; both were fixed and their acceptance checks then passed. One video-test attempt used a changed top-ranked search result without an official pair; subsequent playback checks used a fixed known source ID, while the separate three-song pairing check tested real provider search results.

## Release

- Version: 0.9.0-rc15; version code: 16.
- Android 12/API 31 minimum, API 36 target, Riff launcher label.
- Native libraries: arm64-v8a, armeabi-v7a, x86 and x86_64.
- Signing certificate SHA-256: ac70342323a39eb9cbd1fe80a9a5b060e22c01ba9a9102a9f698367bca0bf6e1, unchanged from the previous installed release.
- Signed APK: `artifacts/release/riff-0.9.0-rc15.apk`, copied to `C:\Users\KIIT0001\Downloads\riff-0.9.0-rc15.apk`.
- APK SHA-256: 81937a663e9f5d828d7d9253652267c77043be2313e32f5a9fd2623f256c53d8.
- Matching source: `artifacts/release/riff-0.9.0-rc15-source.zip`.

Daily jobs run when Android permits background network work; opening the app catches up after an interrupted refresh. Photos, live broadcasts and official video pairings depend on source availability. Pixel refraction is unavailable on Android 12, and a requested 90/120 Hz display mode does not establish a sustained frame rate on every device. The app does not create invented videos or broadcasts when a source is unavailable.

The test APK is uninstalled. The exact public debug paths /sdcard/Download/riff-rc9-debug.xml and /sdcard/Download/riff-rc9-debug.png are absent, and the private native-test queue backup was deleted after successful restoration. Final phone checks report mWakefulness=Asleep and keyguard showing=true. Matching source and APK hashes are recorded in artifacts/release/SHA256SUMS.txt.
