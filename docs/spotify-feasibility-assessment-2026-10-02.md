**Riff Spotify feasibility assessment — 2 October 2026**

**Verdict: a Spotify companion version is technically feasible, with substantial changes and reduced feature parity. A supported, native Android replacement that sends Spotify audio directly into Riff's existing ExoPlayer/download pipeline is not available through the public integration reviewed.** Public distribution is also conditional on Spotify's access and product rules.

This assessment covers the current Riff 0.9.0-rc16 source and current official Spotify documentation. It is a static code and platform assessment. No Spotify account, client ID, token, device connection, or end-to-end Spotify playback was tested. No application code was changed.

The supported Android route uses the Web API for catalog/account operations and App Remote for playback commands. Spotify's installed app fetches, decodes, caches, and plays the audio. Riff can retain its Compose interface and display the resulting player state. [Spotify Android SDK](https://developer.spotify.com/documentation/android), [Android setup](https://developer.spotify.com/documentation/android/tutorials/getting-started).

Spotify retired its earlier mobile streaming SDKs in 2022. They are not a viable way to restore native in-app streaming. [Mobile streaming SDK retirement](https://developer.spotify.com/blog/2022-07-15-mobile-streaming-sdks-update).

**Evidence from this app**

| Area | Current implementation | Migration implication |
| --- | --- | --- |
| Catalog and account | `YouTubeMusicProvider.kt:59,69,102` defines tracks around `videoId`, resolved audio URLs/headers, and a concrete InnerTube provider. Authentication reads Google/YouTube WebView cookies. | Introduce provider-independent catalog models and a separate Spotify OAuth/catalog adapter. Replacing a hostname cannot perform this migration. |
| UI wiring | `RiffApp.kt:274,280` directly creates the YouTube provider and Media3 gateway. Home, catalog, collection, playlist-editing and lyrics screens take the concrete provider. | Inject catalog/account contracts and playback capabilities into the screens. Sixteen production Kotlin files reference the concrete provider. |
| Audio ownership | `MusicPlaybackService.kt:73,113` resolves catalog IDs into media sources, then owns ExoPlayer, audio focus, queue, cache and downloads. | Spotify playback must bypass the stream-resolution path and use a remote backend. Preserve Media3 for the existing app's YouTube/on-device functionality. |
| Existing playback seam | `PlaybackGateway.kt:45` already exposes an interface and reusable UI state. | A useful starting point, but stream arguments, video surfaces, arbitrary queue mutations and mandatory method assumptions need capabilities or separate interfaces. |
| Stored identities | `PlaybackSnapshot.kt:25` accepts only `yt:<11-character ID>` or local IDs. `LocalCollections.kt` and `OfflineMusicStore.kt` also enforce YouTube IDs. | Add typed identities and provider/account namespaces. Twenty-four Kotlin files mention `videoId` or `yt:`. These overlap the sixteen provider references; the counts are not additive. |
| Artwork | `ProviderArtwork.kt:9` allows only YouTube/Google image hosts. | Accept documented Spotify image URLs through a provider-specific validator. Do not apply YouTube thumbnail rewriting to them. |
| Extra features | Downloads resolve transferable source audio; widgets publish from a Media3 Player; lyrics preload invokes YouTube; the sleep fade controls player volume. | These integrations require separate redesign or removal from the Spotify variant. |

**What can carry over**

Most glass styling, navigation, motion, accessibility settings, reusable collection layouts, search history UI and ordinary player presentation can be reused. Spotify content attribution and image presentation still need the service's applicable design rules. Data and playback behavior require adapters.

| Existing feature | Assessment for a Spotify variant |
| --- | --- |
| Songs/albums/artists search and detail pages | Feasible using catalog responses and different ID/pagination models. Development-mode search currently allows at most ten results per request. |
| Saved tracks, albums, followed artists and account playlists | Feasible within granted scopes and available endpoints. The 2026 generic `/me/library` operations replace several older save/remove methods. |
| Add/remove songs in editable playlists | Feasible for eligible owned/collaborative playlists, using `/playlists/{id}/items` and Spotify snapshot/position semantics. YouTube's exact entry IDs cannot be reused. |
| Browse every public/editorial playlist's track list | Reduced in development mode: playlist contents are available only for playlists the user owns or collaborates on. Metadata or opening playback can be separate capabilities. |
| Play, pause, seek, next/previous, shuffle and repeat | Supported through App Remote. Commands must honor Spotify player restrictions and confirmed results. Spotify owns the audio. |
| Native queue dragging/removal and Play next | Partial. App Remote exposes adding to the queue and context/index playback, but not Riff's unrestricted queue reorder/removal or explicit Play next insertion. Reordering a saved playlist is different from editing the live queue. |
| Home moods, Listen again and endless recommendations | Redesign. The current YouTube renderer/continuation feed cannot be reused. Do not depend on the deprecated Recommendations Web API. Evaluate available App Remote content suggestions and allowed account/library data in a pilot. |
| Permanent audio downloads, Smart Downloads, app-controlled bitrate/cache | No equivalent in the reviewed Android remote integration. Spotify owns its own offline media; Riff cannot reuse its raw-file transfer/remux engine for it. |
| Offline listening | Potentially through content already available offline in Spotify. This is distinct from Riff downloading files; confirm actual offline authorization and playback on the target phone. |
| YouTube live stations and Song/Video switch | No corresponding feature in the reviewed public Spotify integration. Remove these from the Spotify variant. |
| Karaoke lyrics and English translation | No documented public Spotify lyrics endpoint was found. Current third-party and YouTube fallback behavior should not be carried over without resolving content rights and Spotify integration rules. Exclude it from the initial scope. |
| Six Android widgets | Layouts reusable; state publishing and commands need a remote-control implementation, reconnect behavior and Android background-lifecycle testing. |
| Sleep timer | Pausing can be adapted. The current per-ExoPlayer 15-second volume fade has no matching App Remote volume control; omit the fade rather than silently change system volume. |
| Paused restart, background playback and swipe-away stop | Redesign around Spotify's current session. Riff no longer owns the audio process or its playback lifetime; avoid restoring stale local state over changes made in Spotify. |

The catalog limitations above are documented in the [February 2026 changelog](https://developer.spotify.com/documentation/web-api/references/changes/february-2026) and [migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide). Playback and queue distinctions follow the [PlayerApi reference](https://spotify.github.io/android-sdk/app-remote-lib/docs/com/spotify/android/appremote/api/PlayerApi.html). Recommendation access needs care: [Recommendations endpoint](https://developer.spotify.com/documentation/web-api/reference/get-recommendations), [ContentApi reference](https://spotify.github.io/android-sdk/app-remote-lib/docs/com/spotify/android/appremote/api/ContentApi.html). Feature conclusions are engineering inferences from those interfaces and the current source, not device-test results.

**Access and release constraints**

A new development-mode integration requires a Premium app owner and allows five authenticated, allowlisted users. Wider access requires Spotify approval; current new-partner requirements include an established organization, a launched service and at least 250,000 monthly active users. An individual personal app should not assume it can receive unrestricted public access. [Quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes).

The July 2026 update increased client IDs to 25 per developer and made development quotas shared per developer account. Older guidance saying one client ID is stale. Adding client IDs does not multiply quota. [July 2026 changelog](https://developer.spotify.com/documentation/web-api/references/changes/july-2026).

Spotify requires written permission for products that replace its core experience and expects independent value. Music streaming requires Premium; commercial streaming, integrations with other services' content and synchronized visual media have restrictions. Therefore a full Spotify replacement or combined YouTube/Spotify player cannot be assumed permitted. This is a platform-release dependency, not a reason to stop the assessment. [Spotify Developer Policy](https://developer.spotify.com/policy).

Riff is GPL-3.0. Before distributing an APK containing additional Spotify SDK components, review the actual component license and distribution obligations alongside the project's existing license. This assessment does not establish license compatibility.

**Recommended technical approach**

Use a separately scoped Spotify companion variant. Retain the current application while proving the remote integration, and avoid committing to replacing the existing provider until the pilot passes.

1. Define a small independent companion use case and its supported features. Treat a complete Spotify replacement as dependent on Spotify's written permission.
2. Register an integration, allowlist the test account, configure the redirect and register package/certificate fingerprints for the build being tested. Use OAuth Authorization Code with PKCE, refreshed tokens and protected storage; embed no client secret. [Mobile PKCE guidance](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow).
3. Build a bounded pilot: sign in, search, load an owned playlist, start one song through App Remote, seek, pause/resume, and observe state changes originating in Spotify. Test absence of Spotify, denied consent, connection loss, account changes and background/foreground transitions.
4. Extract `MusicCatalog` and `MusicAccount` contracts. Replace video/browse-specific entities with typed media references, explicit content kind, duration and pagination. Add optional capabilities for downloadable audio, video, lyrics, editable playlists and queue operations.
5. Implement `SpotifyCatalog` and `SpotifyPlaybackGateway`. Map confirmed Spotify state into `PlaybackUiState`; local progress interpolation must be corrected by state updates. Use the Spotify context URI/index for album/playlist playback when appropriate.
6. Refactor screens and settings to show only supported actions. Replace cookie sign-in, revise Home and queue behavior, adapt widgets, and remove Spotify-source download/video/live-radio controls. Avoid duplicate audio ownership or competing media notifications.
7. Version persistence and separate accounts by provider. The current Spotify profile offers a stable `account_id` for account linking. [May 2026 changelog](https://developer.spotify.com/documentation/web-api/references/changes/may-2026). Existing YouTube saves need explicit metadata matching and ambiguity handling if an import is later requested; they cannot become Spotify tracks by changing their prefix. Preserve original saves.
8. Validate token expiry, scope denial, HTTP 403, rate/quota 429, market/unavailable tracks, pagination, playlist permissions, process recreation, widgets and other Spotify controls. Measure on-device behavior before promising offline operation or background timer guarantees.

A pilot has moderate implementation scope; a polished variant has high scope because catalog, auth, persistence, remote lifecycle and product features change together. Full feature parity remains blocked by unavailable capabilities even after the engineering work. A calendar estimate should follow the pilot and the chosen feature scope.

**Other approaches considered**

- Web Playback SDK streams inside supported browsers, including Android browsers. It is a possible web/PWA direction, with a different playback/lifecycle architecture; it does not return audio URLs for Riff's native ExoPlayer. An Android WebView wrapper should not be assumed supported merely because Chrome is supported. [Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk).
- Spotify Soloist is now an official headless Linux client aimed at dedicated playback hardware. It is not a documented Android playback SDK, and its binaries cannot simply be bundled into this APK. [Spotify Soloist](https://developer.spotify.com/documentation/soloist).
- Unofficial Spotify protocol clients would require a separate technical and terms assessment. They are not a supported migration path and are not included in the feasibility verdict.
- Using Spotify only for catalog metadata while continuing to play YouTube audio would still use YouTube servers and would not meet a pure Spotify playback requirement.

**Decision:** proceed with a narrow personal companion pilot if installed Spotify, Premium music playback and a reduced feature set are acceptable. Do not plan an endpoint-only conversion, retain Riff-owned Spotify downloads, or promise a publicly distributable full Spotify replacement on the evidence available.

