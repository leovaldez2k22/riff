# Riff — 0.9.0-rc7

This release implements the requested catalog, player, library, radio discovery and navigation changes. Native live playback remains blocked on the tested stations by media-server HTTP 403; it is not verified as working. It preserves the existing account session and timed lyrics integration. The image reference was used for capsule progress/volume tracks and softened icon silhouettes.

## Requested changes

| Request | Implementation |
| --- | --- |
| 1. Discover albums and artists | Search has separate Songs, Albums, Artists, Playlists and Podcasts filters. Typed provider cards open real collections from Home, Search and Library. Artist shelves link to albums and songs; Back restores nested collection navigation. |
| 2. Album artwork | Artwork parsing accepts the provider’s YouTube and Google image CDNs, selects the largest thumbnail, and retains covers in media metadata, queues and collection headers. Music rows expose playback through both direct and thumbnail-overlay endpoints. |
| 3. Podcast placement | Home removes Shows for you, podcast and episode shelves. Podcasts uses the provider’s podcast filter endpoint when available, then existing recommendation shelves and real podcast search. |
| 4. Player/lyrics transitions | Both pages remain available through the transition. Song title and artist use matching shared bounds, with coordinated page fade/slide, an opaque transition backdrop to prevent Search showing through, and reduced-motion support. |
| 5. Star as YouTube Music like | The star reads remote like status and calls authenticated like/unlike APIs. UI confirmation and library refresh follow a successful response; errors keep the prior state. Sign-in is required. |
| 6. Automatic queue | The playback service requests YouTube Music radio recommendations and continuations, then related/artist catalog rows when needed. It resolves up to six playable recommendations per batch, replenishes near the queue end, and skips unavailable tracks. The queue supports track selection, reordering, removal, Play next and Add to queue. |
| 7. Live channel radio | Radio reads Lofi Girl, Chillhop Music and College Music channel stream pages and shows only broadcasts carrying live badges. Station discovery is refreshed from the channels, without fixed sample videos. Live streams use live-aware resolution, a LIVE label and disabled seeking. HTTP failures trigger a bounded retry through supported provider profiles. The tested live media currently returns HTTP 403, including the alternate-profile attempt. |
| 8. Stop after clearing the app | Removing this app’s task from recent apps cancels queue work, stops and clears playback, and stops the media session service. Background playback while switching apps still works. |
| 9. Artist profile from player | Tap the artist label or choose View artist. A known artist ID is preferred; provider metadata/search supplies a fallback. View album is also available. |
| 10. Settings from profile photo | Home’s top-right profile control opens Settings. Settings links account management, display/accessibility, Reduce motion and listening-history preferences. |
| 11. Mini-player jump | Mini player and navigation retain their layout space during the morph; bounds are captured only in the resting mini state. This avoids a shrinking bottom container changing the animation destination. |
| 12. Seek and volume styling | Both sliders use a thin rounded capsule with continuous rounded fill, no floating thumb and no gap. Material slider touch/keyboard accessibility remains. |
| 13. Home heading | The top-left heading reads Home. |
| 14. YouTube Music library | Account Library reads all provider categories, playlists and their songs, and exposes continuation pagination. Liked songs refresh after star changes. Explicit local saves are labeled Saved on this phone. |
| 15. Rounded icon edges | Player, mini player, lyrics and output controls use rounded icon variants. This changes the icon silhouettes without adding circular borders. |

## Validation

The signed release build passed, with 46 unit tests passing and Android lint reporting zero errors (39 warnings). APK signature verification passed. The release is built with the existing signing key for an in-place upgrade. Unit tests cover catalog typing, album/artist metadata, thumbnail-overlay song IDs, queue order and candidate filtering, pagination, radio continuation, live-versus-archived tiles, podcast placement, artwork URL validation and nested artist/album navigation, alongside the existing lyrics and playback tests.

Public provider-response probes were parsed by the actual Kotlin catalog parser: Lofi Girl returned 23 live cards, Chillhop Music four live cards, and College Music’s current response contained archived broadcasts only. An artist-filter search produced nine artist cards. These are observed responses during verification, not permanent station counts.

The first rc7 installation exposed an initialization-order crash during account refresh. It was fixed by initializing new like/cache state before refreshAccount. The repaired process started without an AndroidRuntime exception. Final release checks and device status are recorded in artifacts/app-revamp/verification.json.

USB debugging checks subsequently verified the Home heading, profile-to-Settings navigation, album-filter search, The Weeknd’s After Hours cover and song list, the player-to-artist profile with biography/artwork, and real account likes. A like persisted after reopening the player and reading the provider again; its original state was restored. The existing player showed seven real queue tracks with provider attribution and editing controls, and the artwork, capsule sliders and rounded transport icons rendered correctly.

After restoring USB debugging, a fresh music selection played and advanced from 2.7 to 5.7 seconds while its queue filled to seven tracks. Selecting K. from the queue started playback. Moving K. up and down changed and restored its row position; deleting one up-next song reduced the queue from seven to six. The account library returned remote playlists, playlist songs, and continuation pages. Podcasts displayed Keep listening and Shows for you in the Podcasts section.

Better Lyrics returned real word-synced lyrics for we fell in love in october. The player/lyrics round trip retained the page and sync state. Recording inspection found Search bleeding through the original crossfade; an opaque transition backdrop fixed it in the second recording. A mini/full-player round trip returned the mini player to exactly [44,2019][1036,2190].

Removing only Riff’s Recents card while music was playing caused task_removed_stop and service_destroy events, removed its media session, and cleared the queue. Reopening had no mini player and an empty queue. This verifies the requested stop-on-clearing behavior on the connected iQOO phone.

Live discovery shows actual Lofi Girl and Chillhop broadcasts, with LIVE labels and disabled seeking. Their tested HLS media segments returned HTTP 403. The new supported-profile fallback was attempted and did not return another playable transport. Native live playback is the remaining failed device check. No synthetic stream or proxy is substituted.

The app now exposes its existing sanitized playback event ring through RiffPlayback in USB logcat. Events include transport/profile, readiness, HTTP status/failure category and lifecycle state, with no signed URLs or credentials. Retry preserves artwork, artist/album links and live metadata, and a delayed live fallback respects a pause request.

Completed checks and the live-media failure are recorded in artifacts/app-revamp/usb-device-checks.json. Provider catalog availability depends on the account, region and service response.

## Relevant code

- ProviderCatalog.kt and YouTubeMusicProvider.kt: catalog typing, provider navigation, pagination, likes, radio discovery and recommendation retrieval.
- CatalogScreens.kt and HomeFeed.kt: discovery, collection profiles, library, podcasts, radio and settings.
- MusicPlaybackService.kt and PlaybackGateway.kt: per-track stream configuration, automatic queue and task-removal lifecycle.
- NowPlaying.kt, CapsuleSlider.kt, LyricsTransition.kt, LyricsPanel.kt and RiffApp.kt: player actions, queue controls, sliders and transitions.

## Provider and Android references

- [Media3 background playback and task removal](https://developer.android.com/media/media3/session/background-playback)
- [Compose shared elements](https://developer.android.com/develop/ui/compose/animation/shared-elements)
- [ytmusicapi library endpoint implementation](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/library.py)
- [ytmusicapi search parser](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/parsers/search.py)
- [ytmusicapi watch/radio implementation](https://github.com/sigma67/ytmusicapi/blob/main/ytmusicapi/mixins/watch.py)
- [Lofi Girl current streams](https://www.youtube.com/@LofiGirl/streams)
- [Chillhop Music current streams](https://www.youtube.com/@ChillhopMusic/streams)
