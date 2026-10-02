# Music app implementation plan

Status: consolidated planning baseline, 27 September 2026. No app has been built or tested yet. The inspected project directory was empty. Confirmed by the user: preserve the supplied navigation, apply Apple-style glass, use an unofficial YouTube Music integration for a personal APK, and test on an iQOO Neo 7 Pro running Android 16. The initial reference set is complete: R01 player, R02 bottom navigation, R03 album details, R04 lyrics. Integration feasibility and final visual tuning remain to be proven; additional images are not required to begin the planned implementation.

**Current direction (28 September 2026):** This document records the original baseline. Later user instructions supersede its four-tab and no-blur decisions: the app now uses Home / Podcasts / Radio / Library with detached Search, has no Samples tab, and uses clear untinted glass with optical blur. Podcasts and Radio use explicitly labeled original demos for now. Phase reviews in `docs/` track implementation and verification status.

## 1. Product direction and decisions

Build an Android music app with near-black browsing surfaces, artwork-led content, white typography, an immersive artwork-colored player, and clear optical glass on selected floating controls. Preserve the confirmed Home / Samples / Search / Library structure.

The supplied description is the functional specification. Apple Music on iOS is the reference for material, proportion, motion, and control treatment. Where the description calls for a dark strip or translucent pill, designated glass components will instead use the requested clear material. Dense reading surfaces can remain solid charcoal: making every surface glass would obscure the intended hierarchy.

### Decisions to settle before implementation

| Decision | Provisional baseline | Consequence of another choice |
|---|---|---|
| Navigation | Confirmed: supplied four destinations | Apple styling applies to materials and presentation |
| Music source | Confirmed: unofficial YouTube Music integration for a personal APK | Actual playback capabilities must be proven before production playback architecture is frozen |
| First device | Confirmed: iQOO Neo 7 Pro, Android 16, user-reported | Verify actual OS build, GPU path and refresh modes through ADB during setup |
| Minimum Android | Android 13 / API 33 for first prototype | Older support needs a separate renderer or an explicitly reduced visual mode |
| Visual reference | R01: player; R02: bottom navigation; R03: album details; R04: lyrics | Initial reference set complete; still images do not establish optical motion |
| Glass interpretation | Clear interior, no deliberate background blur or milky fill | Any later tint/blur is an explicit design change |
| First release | Personal streaming APK; permitted fixtures for renderer development | Public streaming/social release adds backend, licensing, distribution, and operations work |
| Branding | Working name and original assets | Name, icon, and accent remain adjustable tokens |

Do not infer that the Apple Music catalog is available simply because its UI is a reference. Select and verify any provider's supported SDK, catalog access, authentication, rights, offline playback, and custom-player restrictions before integrating it. Do not build an independent downloader for a provider that does not support one.

### YouTube Music integration decision — added after provider selection

Research of the public documentation did not identify a supported YouTube Music SDK that supplies full music streams to an arbitrary native player. Google's documented YouTube Data API supplies video/channel/playlist data and search; it is not a YouTube Music catalog-and-playback SDK. The documented embedded playback interface is the YouTube IFrame Player API. Do not confuse these products or label general YouTube search as the user's personalized YouTube Music library. Sources: [YouTube Data API](https://developers.google.com/youtube/v3), [IFrame Player API](https://developers.google.com/youtube/iframe_api_reference).

The user selected unofficial integration for a personal APK. Proceed with that research and implementation direction without asking for the same choice again. The documented route below is retained only as a comparison, not the selected architecture:

| Direction | What it can establish | Product consequence |
|---|---|---|
| Documented YouTube APIs and visible embedded player | YouTube search/metadata, supported account operations, visible embedded video playback | Retains custom browsing/glass around the player, but cannot deliver the original audio-only/background-player design unchanged |
| Unofficial YouTube Music integration research | Evaluate Music-specific browsing/library operations and separately investigate actual playback feasibility | Experimental dependency; authentication, playback, compatibility and maintenance must be proven, not assumed |

The unofficial `ytmusicapi` project documents music search, albums, artists, library and playlist operations through emulated web requests and explicitly states it is not supported or endorsed by Google. It is a Python library, not a drop-in Kotlin playback engine. Metadata access does not by itself prove dependable Android audio streaming. If investigated, decide whether a native adapter or a service is appropriate only after a small proof; do not add a Python backend merely because the library exists. [ytmusicapi documentation](https://ytmusicapi.readthedocs.io/en/stable/).

For the documented YouTube API route, current developer policies prohibit background-player features, separation of audio/video and unauthorized downloads; they also constrain modification/obstruction of the embedded player. Therefore this route cannot meet the existing background music, artwork-only playback and offline YouTube-track requirements. Keep custom glass outside the embedded video/player area; do not refract, cover or replace its controls/branding. Do not assume a user's Premium subscription changes the integration contract. [YouTube developer policies](https://developers.google.com/youtube/terms/developer-policies).

**This section governs provider-dependent requirements and milestone gates below.** They describe the desired full product, not a claim that YouTube Music currently supports every feature through a public API. Do not silently change the requested app into a video client. The unofficial direction is confirmed; graphics development can proceed independently of technical feasibility work.

### Selected unofficial integration architecture

- Separate `YouTubeMusicCatalogAdapter`, `YouTubeMusicAccountAdapter`, and `YouTubeMusicPlaybackResolver` behind existing repository and PlaybackGateway contracts. Catalog success must not be mistaken for streaming success.
- Evaluate currently maintained native Android/Kotlin integrations and their licenses, authentication, compatibility and playback behavior during Phase 0Y. Select dependencies based on a reproducible device proof; do not prescribe an unverified library now.
- Prefer an on-device adapter for the personal APK when practical. A backend is not assumed. If one becomes necessary, document exactly why, what credentials/data it would hold, and its operating cost before changing the architecture.
- Start with anonymous browsing where available. Add account linkage only after its mechanism is validated; keep app-owned favorites/playlists distinguishable from synchronized YouTube Music collections.
- Represent playable resources as expiring resolved media, separate from durable video/catalog IDs. Add bounded retry/re-resolution and explicit unavailable/expired/authentication-required states; never persist a temporary stream URL as a track's identity.
- Use Media3 only if the verified integration actually supplies a playable media resource compatible with it. Keep resolver failures out of the UI state machine and never let an expired resource reset the user's entire queue.
- Pin tested dependency revisions, add parsing/contract fixtures without credentials, and maintain provider-independent demo playback for regression diagnosis. Record upstream breakage as a provider fault with a retry path.
- Keep downloads, synchronized lyrics, recommendations, Samples and account mutation capability-gated until individually verified. Personal distribution does not itself prove these capabilities.
- M0Y evidence must include multiple tracks, seeking, track changes, lifecycle/background behavior, expired-resource recovery where reproducible, and catalog failures on the actual phone. No account credentials should appear in logs or milestone reports.

### Phase 0Y — YouTube Music feasibility gate

Complete this before committing Phase 3 to a playback engine:

1. Record the confirmed unofficial direction and personal APK distribution. Keep the full glass/player design as the requested target while documenting any required deviations.
2. Build a capability matrix for Home, search, albums/artists, personalized library, playlist read/write, lyrics, recommendations, Samples, queue control, background audio, offline tracks and casting. Mark each verified, unsupported, or untested; attach evidence.
3. For the documented route, prove an embeddable music video in an Android WebView on the iQOO phone, supported playback commands, correct app identification, visible player sizing, navigation lifecycle and unavailable-video errors. Verify foreground-only behavior instead of passing the background-audio gate.
4. For unofficial research, evaluate current maintenance, authentication and data contracts using the project's primary documentation. Prove browsing separately from playback. Specify credential handling without placing session headers/tokens in chat, source control or test evidence. Do not infer stable authentication from a successful anonymous search.
5. Prove any proposed playback integration with several real items and error/retry cases before selecting Media3 or another engine. Report unresolved stream availability, account/region constraints, URL expiry and upstream breakage. Do not treat a single playable item as a production-ready catalog integration.
6. Record scope decisions and revise M3–M8 accordingly. If full custom music playback remains unproven, leave that gate unresolved; deliver only the glass prototype as such.

Acceptance: a written integration decision plus phone evidence for the actual available capabilities. Estimated effort for the original streaming alpha remains open until this gate is resolved.

### What “same reflection physics” can mean here

The target is a close perceptual reconstruction, validated against a fixed reference. Exact parity with Apple's internal renderer is not an honest acceptance promise. Apple's public material guidance describes adaptive regular and clear variants, not a portable Android optical implementation. Its clear material is described as highly translucent; the user's unfrosted requirement is therefore a deliberate custom target. [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials).

Define transparency precisely: underlying detail remains sharp through the central portion; edges bend that detail, catch highlights, and create a sense of thickness. Zero blur does not mean zero visible surface. Reflection and refraction are distinct: reflection adds light from a modeled environment; refraction changes where the underlying scene is sampled. A 2D UI shader will approximate both, not physically ray-trace a real environment.

## 2. Release boundaries and complete feature coverage

All supplied features are accounted for below. Later work is scheduled, not silently discarded.

| Delivery | Features |
|---|---|
| Glass proof | Live backdrop, capsule/circle/sheet edges, sharp foreground, press response, scrolling and video experiments |
| Core music alpha | Home, Speed Dial, Search, provider Library and optional local files, album/artist/playlist pages, mini-player, Now Playing, queue, playlists, favorites, settings, real supported streaming/background audio |
| Rich media beta | Samples, synchronized lyrics where available, related content, podcasts, provider-backed downloads, audio/video switch, output routing and optional Cast |
| Connected expansion | Accounts, cloud sync/uploads, recommendation feeds, activity, social comments, collaboration, provider search and discovery |
| Optional product expansion | Voice search, music/humming recognition, AI playlists/artwork, Recap, badges, memberships, Upgrade destination |

Provider-dependent UI must be driven by capabilities. Demo data is labeled as demo. Do not show working-looking counts, subscriptions, download success, public comments, or recognition results without the corresponding implementation.

## 3. Architecture

Use native Kotlin, Jetpack Compose for layout and interaction, a custom graphics layer for optical glass, Media3 for app-owned playback where permitted, Room for persistent structured data, and DataStore for preferences. A provider-mandated SDK/player takes precedence over Media3 for that provider. Freeze compatible stable dependency versions during setup in a version catalog; do not copy arbitrary version numbers from this plan.

Before Phase 3, implement a provider feasibility slice: authenticate on the real phone, search its catalog, resolve a playable item through its supported API/SDK, start authorized full-track playback, and verify whether custom transport, background playback and queue management are available. Document regional/account requirements, preview-only limitations, token renewal, DRM ownership, download support and artwork/lyrics permissions. If only remote control of another app is supported, explicitly distinguish that from in-app streaming and resolve product scope before proceeding. Do not silently substitute previews or local files for the requested service.

Define a `PlaybackGateway` so presentation observes one normalized state while Media3 or the provider SDK supplies the implementation. Unsupported operations are capability-gated. The provider may own the authoritative queue/session; in that case mirror it rather than imposing an incompatible independent queue. Keep tokens in platform-appropriate secure storage, exclude them from logs, and never embed provider client secrets in the APK.

AGSL / RuntimeShader is available on Android 13 and above. It is a candidate for the optical shader, but it does not automatically expose whatever is behind a Compose component. Backdrop acquisition and composition must be proven independently. [Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl).

### Logical ownership

Start with a small number of Gradle modules: `app`, `core:design`, `core:glass`, `core:data`, `core:playback`, plus benchmark/test modules when needed. Keep feature packages inside `app` initially; split modules only when boundaries or build times justify it.

- **Presentation:** immutable screen state, explicit user actions, ViewModels, lifecycle-aware Flow collection.
- **Domain:** playback commands, playlist operations, search/filter/sort, capability rules, source-aware media identity.
- **Data:** catalog adapters, Room, media file access, artwork cache, provider clients.
- **Playback service:** owns the active player/session and authoritative queue; UI observes through a controller.
- **Glass renderer:** owns backdrop registration, coordinate mapping, optical parameters, layer ordering, and diagnostics. It never owns navigation or audio state.

Use one authoritative playback queue. Screens must not maintain competing versions of current index, repeat mode, or play state. Persist a resumable snapshot; reconnect through the service after activity recreation rather than starting a second player. Hosting the player and session in a MediaSessionService supports playback independent of the activity. [Media3 background playback](https://developer.android.com/media/media3/session/background-playback).

### Models and contracts

Define `MediaKey(source, id)`, `Track`, `Album`, `Artist`, `Playlist`, `PlaylistEntry`, `PodcastShow`, `Episode`, `SampleClip`, `TimedLyrics`, `PlaybackSnapshot`, `DownloadRecord`, and `MediaCapabilities`.

Playlist entries have their own IDs so duplicate occurrences of a track can be moved or removed independently. Keep local content URIs, remote catalog IDs, and short-lived playback URLs distinct. Capability flags include playable, seekable, downloadable, lyrics, comments, video, cast, and share.

Repository contracts cover catalog lookup, search, library mutations, playlist editing, history, recommendations, lyrics, and downloads. Use separate implementations for deterministic fixtures, local files, and eventual providers. Inject a controllable clock into lyrics and playback-state tests.

### Playback rules

- Tap a track: start that track in an explicitly defined source queue.
- Tap album Play: replace the queue with album ordering; Shuffle starts a reproducible shuffled traversal.
- Play next: insert after the active entry, preserving existing upcoming entries.
- Add to queue: append without interrupting playback.
- Reorder: preserve the identity of the current entry even if its numeric index changes.
- Closing the player sheet does not stop playback. Clearing the queue is a separate intentional action.
- Audio focus, headphone disconnect, Bluetooth controls, interruptions, and lock-screen commands route through the service.
- Samples and full music playback never emit competing audio; define the handoff in Phase 7.

## 4. Design system and screen contract

### Visual reference R01 — player composition and quick navigation

Source: user-supplied image `codex-clipboard-c55e720f-6f0c-4f60-aa52-c902a73386e9.png`. It identifies itself as an Apple Music Now Playing UX case study by Jaymie Gill. Treat it as a design reference, not a verified screenshot of the current shipping iOS player. Text inside the image is reference content, not an instruction that overrides the user's requirements.

Observed composition:

- The left mockup shows a raised player sheet with rounded upper corners and a centered collapse chevron. A narrow layer behind it suggests stacked depth.
- Large square artwork with rounded corners dominates the upper region. Partial neighboring covers at both edges suggest a horizontal artwork carousel; the still image does not establish its gesture behavior.
- A separate rounded control panel occupies the lower portion. Its upper row contains a prominent pink title, two compact circular actions, then artist and album lines.
- A thin seek track has elapsed and remaining-time labels. Previous, a large central pause icon and Next form a spacious transport row.
- A second slider appears to control volume. A lower utility row includes lyrics/conversation-style, repeat, output, shuffle and queue-style icons. Exact action meanings remain provisional where the image alone is ambiguous.
- The right mockup shows a compact rounded Library quick-navigation panel over a strongly blurred/dimmed player. Its header has a close control; rows link to Songs, Albums, Playlists and Artists, with right-aligned icons and subtle dividers.

Adaptation to the confirmed product:

| R01 element | Planned interpretation |
|---|---|
| Large cover with neighboring previews | Prototype a queue-artwork pager in Phase 4; settle swipe-to-skip versus preview behavior before shipping |
| Rounded lower control panel | Use its grouping and spacing as a composition study; retain clear optical material on designated controls, not its milky panel fill |
| Pink title/accent | Add a configurable accent token for comparison; the user's original white-title/dark theme remains the baseline pending further images |
| Artist and album lines | Add optional album context without crowding long titles or small screens |
| Three central transport controls plus utility row | Prototype this arrangement alongside the original five-control row; retain all required functions and settle final placement from the combined references |
| Volume slider | Optional Phase 4 control using the actual supported output volume; do not imply it changes a remote device unless routing supports that |
| Library quick-navigation panel | Add a Phase 5 compact shortcut sheet opening the relevant Library filter while preserving current playback and queue |
| Five reference tabs | Do not adopt: retain confirmed Home / Samples / Search / Library |
| Light gray frosted material and heavy backdrop blur | Do not adopt as the glass target: retain near-black browsing and clear, unfrosted glass |
| iPhone frame, notch and status bar | Reference presentation only; use actual Android insets and system UI on the iQOO phone |

The image establishes layout cues but cannot establish reflection dynamics, refraction accuracy, spring timing or gesture behavior. Do not claim this image proves the requested liquid-glass physics. Additional images will refine the component-specific reference matrix; a short motion reference would help later but is not required to continue planning.

Phase 4 deliverable extension: produce two composition states (full player and player with quick-navigation sheet) using identical content, then inspect at the phone's native dimensions. Check readable metadata, slider hit areas, preserved audio, sheet dismissal and no overlap with navigation. Phase 5 gate extension: each Library shortcut opens its corresponding filter and Back returns without losing playback context.

### Visual reference R02 — floating navigation and mini-player

Source: user-supplied image `codex-clipboard-cccb03e7-355b-4027-a278-223667ec4f35.png`. This is the primary reference for bottom chrome and takes precedence over R01's flat bottom tab strip. Image labels are reference content, not instructions to replace the confirmed destinations.

Observed details:

- A wide floating mini-player capsule sits above the navigation with rounded square artwork, two left-aligned metadata lines, and large Play and Next glyphs at the right.
- A separate elongated navigation capsule sits below it, with icon-over-label destinations and a rounded selected-item region. Home is pink; inactive icons and labels are gray.
- Search occupies a detached circular glass button at the right, separated by a narrow gap and vertically aligned with the navigation capsule.
- Both capsules have bright curved edges, gentle contact shadows, and background color visible through their interiors. The mini-player picks up the yellow artwork behind it.
- The screenshot also shows softened background detail and light haze. Its edge treatment and geometry are useful references, but that softness must not replace the user's explicit unfrosted interior requirement.
- Content extends behind the floating controls. The reference uses a light browsing surface; the app retains its confirmed near-black browsing theme.

Concrete adaptation:

Use a three-item capsule for **Home / Samples / Library**, with **Search** as the detached circular fourth destination. Preserve the same four destinations and their existing navigation state; this is a spatial grouping change, not the introduction of New or Radio. Search gets an accessible label and selected-state feedback even without a visible caption. Retain the original logical tab order Home → Samples → Search → Library in routing definitions if useful, but accessibility traversal follows the actual left-to-right visual order Home → Samples → Library → Search so focus does not jump across the screen.

The default mini-player shows artwork, title, artist, Play/Pause and Next, matching R02 more closely than the earlier Cast-first layout. Output selection stays available in Now Playing and can be added to the mini-player only if width permits. Preserve the planned thin progress indicator, artwork tap/upward expansion and horizontal skip behavior, while preventing gestures on Play/Next from triggering expansion.

Initial sizing rules, to tune on the iQOO phone rather than deriving dp from screenshot pixels:

- Shared horizontal outer inset: 16–20 dp; mini-player spans the combined navigation-plus-Search width.
- Navigation capsule: roughly 68–76 dp high, with three equally sized destinations.
- Search circle: match navigation height; capsule-to-Search gap: 10–12 dp.
- Mini-player: roughly 64–68 dp high; gap above navigation: 10–12 dp; artwork: 44–48 dp.
- Selected item: inset 4–6 dp from the capsule edge, pill-shaped, with restrained local emphasis and optional accent icon/label. Avoid a solid gray slab as the default selection treatment.
- Bottom position: system navigation inset plus 8–12 dp breathing room. Never draw the app's Search control over Android's gesture area.
- Width allocation: navigation width = available width minus Search diameter minus inter-control gap. Labels must fit without shrinking below readable type sizes. At large font scales or narrow widths, use an accessible four-item layout with visible Search text rather than clipping labels or reducing touch targets.

Glass implementation: render three distinct shapes (mini-player, navigation capsule, Search circle) against the same live content layer. Their refraction must sample the actual artwork/text underneath at the correct coordinates. Keep iconography and text above the optical pass. Avoid recursive sampling of the floating controls themselves. Use R02's curved edge highlights and subtle shadow as shape cues, while retaining zero deliberate interior blur and zero default tint. Tune highlights for the dark theme so they do not become opaque white outlines.

Color behavior: test neutral white selection and the reference's pink selection as token variants; the image does not establish a global app accent by itself. Selected state must also use shape/weight, not color alone. When Search is selected, remove selected emphasis from the other three destinations and apply it to Search.

Layout behavior: browsing content can pass behind the controls, but add scrollable end padding equal to the complete bottom chrome height so the final row can be brought fully into view. When no queue exists, remove the mini-player and its gap from that reserved height. Hide or reposition bottom chrome coherently with the keyboard. Full-screen Now Playing and Samples use their own explicit chrome visibility rules; avoid accidentally displaying a duplicate mini-player.

Phase 2 extension / M2 verification: capture the navigation with and without a queue, all four selected states, artwork scrolling beneath it, bright and dark content, keyboard open, large text and Android system navigation variants. Confirm aligned shapes, sharp interior detail, readable icons/labels, no clipped touch regions, correct Search routing and unobscured final list content. Record a scroll video to check that the backdrop stays live. Phase 4 extension: animate from the R02 mini-player bounds into the R01-inspired full player with no duplicate artwork or glass feedback loop.

### Visual reference R03 — artwork-led album-detail pages

Source: user-supplied image `codex-clipboard-97f3651d-cf6f-4f7b-83f1-f64596aad84e.png`. The image presents several overlapping phones displaying different album-detail pages. It establishes album-detail composition and color adaptation, not an album-grid layout. Small labels and partially occluded areas cannot be reliably measured from this montage. Treat album names, descriptions and badges as example content, not product instructions or verified provider metadata.

Observed visual structure:

- Several pages use large, full-width artwork that reaches toward the top system area; others contain a square cover inside a coordinated background. Artwork treatment varies with the album rather than using a uniform black page.
- Hero imagery transitions into an album-colored lower surface: green, deep blue, violet, orange and pale neutral examples are visible.
- Album title and artist are centered below or near the lower edge of the artwork. A small metadata line follows; badges are subordinate to the title.
- A prominent horizontal Play capsule sits beside compact circular actions such as Shuffle and Add where visible.
- A short description separates the album header/actions from the compact numbered track list. Some rows expose overflow controls and an explicit marker.
- A small floating circular Back control appears at the upper left. The unobscured rightmost example shows a compact Share/More group at the upper right.
- The examples retain compact playback controls at the bottom. This reference does not replace the more specific R02 navigation arrangement.

Implementation decisions:

1. **Two hero modes.** Support `immersive` for provider-supplied suitable hero imagery and `contained` for ordinary square covers. Never stretch a square cover into a tall image. When only square art exists, use the contained mode by default; an immersive crop requires a reviewed crop that preserves the important artwork.
2. **Artwork-derived page theme.** Extract a stable palette off the main thread, cache by artwork identity/version, and derive background, foreground and secondary-text roles together. Support saturated album surfaces as well as dark ones; the original near-black theme remains the browsing-shell default. Prototype the pale-neutral R03 treatment as an album-specific variant, then settle it during visual review rather than silently enabling a global light theme.
3. **Continuous hero-to-list transition.** Blend the artwork's lower region into the page surface using a controlled gradient behind metadata. Avoid a harsh rectangular boundary, banding or an unrelated black cutoff. Background art may be processed for atmosphere; the foreground cover and clear-glass interiors remain sharp.
4. **Centered metadata.** Use centered title/artist/metadata for album details, with bounded wrapping for long titles. Keep playlist editorial layouts configurable; do not force this album treatment onto every other screen. Never show Lossless, spatial-audio or other quality badges unless the actual source/playback capability verifies them.
5. **Action geometry.** Place the prominent Play capsule beside 48 dp minimum circular action hit areas. Use source-aware Play/Shuffle/Add semantics, selected/saved feedback and disabled states. Apply clear glass to appropriate floating chrome; a solid high-contrast Play button is consistent with the reference and need not be glass.
6. **Track rows.** Use a leading track number/current-playing indicator, expandable text column, optional explicit marker and an independently tappable trailing overflow action. Preserve album ordering and support multi-disc sections when data supplies them. Dim unavailable tracks without collapsing their position. Keep real metadata and durations accurate rather than copying montage labels.
7. **Description.** Start with a short, bounded preview and a More action only if additional text exists. Expanded text must reflow the page normally and remain accessible. Do not fabricate editorial descriptions when the provider lacks them.
8. **Scrolling behavior.** Start with a static hero and smooth list scroll; prototype restrained title/header collapse when the hero leaves view. Keep Back/More accessible and preserve scroll position after opening a track menu or returning from an artist. The still reference does not establish parallax, so it is not a requirement.
9. **System and bottom insets.** Artwork may extend behind the status bar, but controls and metadata respect the real Android cutout/insets. Set system icon contrast for the current surface. Reuse R02 bottom chrome and its end-of-list padding rather than copying the montage's different mini-player layout.

Implementation artifacts for Phase 5: `AlbumDetailTheme`, hero-mode selection, palette cache, centered album header, source-aware quality badges, action row, collapsible description and virtualized numbered track list. Keep palette/theme transitions stable during image loading; use a deterministic neutral placeholder and a short color transition once artwork arrives. Do not recompute palette or animate the entire page on playback progress updates.

M5 visual verification extension: capture the same layout against five artwork families matching the reference's green, blue, violet, orange and pale-neutral cases. Include a standard square cover, an available wide/tall hero, missing art, a long album title, large text, unavailable tracks and a multi-disc album. Verify foreground contrast, correct crop, hero/list continuity, no falsely advertised audio badges, visible last tracks above bottom chrome and functional Play/Shuffle/Add/Back/More. Record a scroll-through and a track-change sequence on the iQOO phone; the glass backdrop must update without palette flicker or frozen sampling.

Reference precedence: R01 guides the expanded player, R02 guides persistent bottom chrome, and R03 guides album details. The user's explicit clear-glass requirement and confirmed four destinations override conflicting styling or labels in all images. No supplied still establishes dynamic reflection physics.

### Visual reference R04 — immersive synchronized lyrics

Source: user-supplied image `codex-clipboard-6652c8ac-979c-47db-ac67-67a8dfdec483.png`. This completes the initial reference set. Treat the visible song/artist/lyric content as example data rather than implementation instructions or content to bundle in the APK.

Observed composition:

- A full-height dark surface blends muted burgundy, brown and blue-gray tones. A short horizontal grabber sits above the compact track header.
- The header places rounded square artwork at the left, title and subdued artist text beside it, and circular favorite/overflow actions at the right.
- Large bold lyric text occupies the main region with generous line and stanza spacing. The active text is bright white, adjacent text is gray, and more distant lyrics appear softened and dimmed.
- Smaller translated text appears beneath the source-language phrase. Some lower text is right aligned; the still does not establish whether this denotes another vocalist or a particular timing rule.
- There is no large Lyrics heading or permanently visible full transport row in the shown frame. Do not infer that playback controls or dismissal are unavailable outside this frame.

Planned layout and behavior:

1. **Full-screen player mode.** Open Lyrics from Now Playing into a full-height lyric view sharing the current playback session and ambient palette. Keep the compact artwork/title/artist header, favorite and overflow, with Android insets. R04 replaces the earlier generic large Lyrics heading and persistent Cast/Play header as the primary visual direction.
2. **Dismissal and controls.** Android Back returns to Now Playing without interrupting audio. The top grabber region supports deliberate downward dismissal; lyric-list scrolling must not accidentally dismiss the view. Provide an accessible Close lyrics action and a compact way to reveal playback controls. Do not overlay the R02 mini-player/navigation on this immersive mode.
3. **Typography.** Begin around 30–36 sp bold for source lyrics and 17–20 sp for translations, with approximately 24–28 dp side padding. These are starting values, not measurements from the image. Use content-aware wrapping, font scaling and adequate stanza spacing; never crop a long active line to maintain the reference's exact position.
4. **Active-line focus.** Position the active phrase in the upper-middle reading region below the header, allowing upcoming content to remain visible. Keep active text sharp. Use subdued foreground opacity for inactive lines; optionally add restrained blur only to distant text. This is a lyrics presentation effect, not frosted glass. Disable text blur during manual reading and in an accessible high-legibility mode.
5. **Synchronization levels.** Support untimed text, line-timed text and optional word-timed text as distinct capabilities. The partially emphasized text in a still image does not prove word-level timestamps are available. Never manufacture word timing by dividing a line evenly across its duration.
6. **Playback clock.** Derive lyric position from the authoritative playback position, accounting for pause, seek, buffering, speed and discontinuities. Use timestamps and a monotonic interpolation anchor only while playback is advancing; re-anchor on state updates. Stop invisible-frame work and prevent ordinary progress ticks from rebuilding the complete lyric list.
7. **Line selection and scrolling.** Binary-search the current timestamp for line selection. Auto-scroll on line changes with interruption-safe motion. Manual scrolling enters a browsing state with a Return to current line action; seeking, track changes and explicit return re-anchor predictably. Tap-to-seek is enabled only for timestamped lyrics and seekable media, with a screen-reader equivalent.
8. **Translations.** Render a separate optional translated string under each source segment when a supported source supplies reliable alignment. Translation visibility is toggleable. Missing translations simply omit the secondary line. Do not infer a translation API from YouTube Music access or generate live translations as an unrequested default.
9. **Duets and scripts.** Use right-aligned lyric blocks only if structured vocalist/part metadata supports that presentation; otherwise use consistent reading alignment. Handle RTL, mixed scripts, diacritics, long words and missing speaker attribution. Reading order must follow the lyrics, not visual alignment tricks.
10. **State coverage.** Include loading, timed, untimed, unavailable, failed/retry and offline-cached states where permitted. Untimed lyrics remain manually scrollable without a fake active line. Stale responses from the previous track must never overwrite the new track's lyrics. Keep favorite state synchronized with its actual local/provider ownership.

Data extension: `LyricsDocument(trackKey, source, language, timingLevel, segments, attribution)` and segments with stable IDs, source text, optional start/end timestamps, optional word timing, optional translation and optional speaker. Validate timestamp order and duplicate/overlapping segments explicitly. Do not confuse instrumental gaps with network loading. Honor source attribution and caching constraints when a provider is chosen.

Phase 4 extension: implement R04 composition with an original/permitted deterministic timed fixture, compact header, full-screen transition and return-to-player behavior. Phase 6 extension: connect verified lyric data, optional translations and real playback timing. Validate synchronization using known fixture timestamps and seek assertions rather than judging it only from screenshots. Aim for visual line changes within approximately 100 ms of the fixture's playback boundary under normal load, measuring actual event/frame timing on the phone; this is a provisional target, not a claim about source lyric accuracy.

M4/M6 phone checks: active-line screenshot; auto-follow recording; manual scroll and return; seek forwards/backwards; pause/buffer/resume; rapid track changes; instrumental gap; long wrapped verse; translated and untranslated segments; untimed/no-lyrics states; large text, TalkBack, reduced motion and text-blur-off mode. Confirm the active line remains sharp, the background remains subdued, translations do not collide with subsequent lines, no competing player is created, and leaving Lyrics preserves queue and position. Screen readers must not announce every playback tick or repeatedly steal focus as lines advance.

### Consolidated visual reference contract

| Reference | Governs | Does not override |
|---|---|---|
| R01 | Expanded player composition and quick Library navigation | Clear glass, dark browsing, confirmed destinations |
| R02 | Floating mini-player, three-item capsule and detached Search | The four destination identities or Android system insets |
| R03 | Album hero modes, album-derived colors and track-list composition | Provider capabilities, R02 bottom chrome, metadata accuracy |
| R04 | Immersive lyric layout, focus hierarchy and optional translation placement | Actual lyric timing/data availability or clear-glass controls |

The initial reference-gathering phase is complete. Remaining choices such as accent color, transport arrangement and pale album variants are tuning decisions to resolve through the planned prototypes, not reasons to request more images before progress. Preserve the supplied reference assets in a project reference directory during implementation rather than depending on temporary clipboard paths. Use a manifest with IDs, original filenames, intended components and precedence; keep these references separate from shippable app assets.

All values below are initial tuning values, not measured Apple specifications. Store them as tokens so reference matching does not require editing every screen.

| Token group | Initial values or rule |
|---|---|
| Base surfaces | Near-black `#050506`; solid reading sheets around `#151518` |
| Foreground | Primary `#F7F7FA`; secondary `#A4A4AE`; muted `#74747F`, subject to contrast checks |
| Spacing | 4, 8, 12, 16, 20, 24, 32 dp; default horizontal inset 20 dp |
| Typography | Page 32 sp bold; section 23 sp bold; track 16 sp medium; metadata 13 sp; tab 11 sp |
| Shape | Continuous-looking corner curves where practical; covers 10–14 dp; sheets 28–36 dp; capsules half their height |
| Touch regions | At least 48 dp even if visible icon is 20–26 dp |
| Mini-player | Approximately 64 dp, 44–48 dp art, expandable text region, fixed playback action |
| Bottom navigation | Approximately 68–76 dp plus system inset; R02 three-item capsule with separate Search circle, four destinations total |
| Motion | Press 100–160 ms; routine changes 180–260 ms; player transitions around 350–500 ms, then tune spring behavior |

Use an available, properly licensed sans-serif with matched metrics and original or appropriately licensed icons. Typography should capture the reference's hierarchy without assuming Apple's fonts or SF Symbols are Android assets.

### Material allocation

Use clear glass for the floating bottom navigation, mini-player, selected floating player controls, audio/video selector, and short context controls over rich artwork. Use solid near-black surfaces for long lists, settings, complex forms, and comments. Treat large clear sheets as an experiment, not the default. Keep artwork crisp and unobscured; avoid glass around every content card.

### Screen specifications

| Surface | Layout and interaction requirements |
|---|---|
| Home | Brand, activity badge, profile; horizontally scrolling context chips; independent shelves with partial next-card visibility; square albums/playlists, round artists, wide video cards; explicit badges and overflow actions |
| Speed Dial | Three columns, initially two rows; page dots only for multiple pages; tap starts playback in place; long press opens actions; pinning persisted; active-item indicator |
| Samples | Vertical pager, looping video, right action rail, lower metadata; tap pause; up/down navigation; capability-aware like/save/share/full-video actions |
| Search landing | Rounded field, optional voice/recognition buttons, history with query insertion, two-column discovery grid |
| Search results | Persistent query, back control, result filters, type-appropriate artwork; debounce and cancellation; metadata links distinct from playback tap target |
| Library | Source selector, type chips, sorting, list/grid by media type, liked collection, New playlist and Shuffle all; local and cloud availability distinguished |
| Mini-player | Artwork, title/artist, play/pause, optional output control, thin progress; horizontal track swipe; tap/upward drag expands; downward dismissal only after deliberate threshold |
| Now Playing | Artwork-derived dark background; collapse, mode and output/overflow controls; large cover, metadata links, action carousel, seek bar, five transport actions |
| Queue | Collapsed, half, and full states; Playing from header; optional tuning chips; current indicator; reorder, remove, save queue |
| Lyrics | R04 full-height ambient view, compact artwork/metadata/favorite/overflow header, large sharp active lyrics, subdued surrounding text, optional aligned translations; accessible dismissal and manual-scroll return |
| Album/playlist | R03 album hero in immersive/contained modes, artwork-derived page theme, centered album metadata, Play/Shuffle and numbered tracks; playlist layout configurable, with owned-playlist editing |
| Artist | Wide hero, name/follow/radio/play; latest release, top songs, albums, singles, videos, featured/related shelves |
| Podcasts | Show art and follow, dated episodes, duration/description, saved episodes, new episodes collection, per-episode resume state |
| Related | Contextual destination from track metadata, with source-aware tracks/artists/playlists/albums |
| Comments | Sheet with sort, avatars, timestamps, replies and input; keyboard-safe layout; backend-dependent |
| Menus/picker | Rounded upper sheet, grabber, action rows; playlist picker with New playlist; capability-filtered menu content |
| Downloads | Queued/progress/paused/complete/failed states; source and category filters; storage controls and recoverable errors |
| Account/settings | Account/profile/history/activity; conventional settings groups for playback, data, downloads, privacy, devices and accessibility |
| Recap/Upgrade | Separate optional feature flags; Recap has colorful shareable statistics; Upgrade appears only with an actual entitlement system |

Every screen includes loading, empty, populated, offline, partial-failure, and unavailable-content behavior where relevant. Skeletons use dark placeholders. Save/remove operations confirm through a snackbar with Undo when reversible. Never hide the only retry action under an overlay.

### Navigation and gesture arbitration

Maintain each tab's back stack and scroll position. System Back closes the top sheet, collapses the player, or navigates back in that order. Respect system bars, cutouts, IME, gesture navigation, and three-button navigation. Reserve bottom list padding for the real combined navigation/mini-player height.

The mini-player expansion, horizontal skip, queue drag, lyric scroll, and seek gestures need explicit direction locking and touch slop. A seek gesture must not move the queue; queue dragging must yield to list scrolling except at the relevant scroll boundary. Accessibility actions must provide alternatives to every drag-only action.

## 5. Clear-glass engineering specification

### 5.1 Rendering pipeline

1. Render the app's underlying content into a reusable scene layer before foreground glass controls.
2. Expose that same live content as shader input using a verified GPU-backed path. Prove the actual recording/replay or texture-sharing mechanism on device; a conceptual diagram is insufficient.
3. Map each glass surface's local coordinates into scene coordinates, including scroll, insets, density, animated transforms, and clipping.
4. Evaluate the surface boundary and rounded shape using a signed-distance function or equivalent smooth mask.
5. Derive a shallow curved thickness profile near the boundary; derive approximate normals from that profile.
6. Offset backdrop sampling using those normals and a tunable refraction strength. Keep the central area nearly undistorted; concentrate distortion near the bevel.
7. Add directional specular highlights, a subtle reflected environment term, and restrained inner-edge shading. Sample an explicit synthetic environment rather than pretending the shader knows the physical room.
8. Composite the optical result with correct premultiplied alpha and color handling.
9. Draw text, icons, album art, progress, and focus indicators above the shader. They remain sharp and undistorted.

Android's documented shader API allows shader composition and view-content effects, but a shader applied only to a control's own layer does not solve live sibling-backdrop sampling. [Using AGSL](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl).

### 5.2 Parameters and constraints

Expose radius, bevel width, virtual thickness, refraction strength, highlight width/intensity/direction, shadow strength, and press deformation as shared material parameters. Define them in density-independent terms; convert at the render boundary.

- Interior blur radius: zero in the standard requested material.
- Interior tint opacity: zero by default.
- Refraction: begin with low single-digit dp displacement near the rim and tune to the reference.
- Reflection: view-angle-inspired Fresnel weighting is an approximation, not a claim about Apple's implementation.
- Dispersion/chromatic fringes: off by default; add only if reference evidence warrants it.
- Motion: use touch position and component motion for subtle response; no continuous idle wobble.
- Device-tilt response: optional experiment only; stop sensor work when invisible and honor reduced motion.
- On a featureless black background, refraction will have little visible content to bend. Do not compensate with exaggerated white rims.

Avoid white haze, uniform gray opacity, repeated static gradient streaks, or warping the labels as substitutes for live optics. Do not hide failure to sample video beneath a fake transparent fill.

### 5.3 Backdrop and video feasibility gate

Prototype three increasingly expensive cases: static artwork, scrolling Compose content, and playing video behind controls. SurfaceView and TextureView composition differ; validate whether the chosen video path is available to the backdrop renderer. If not, evaluate shared video texture composition or a deliberately simpler video overlay mode. Protected provider video may prevent sampling entirely; record that limitation rather than promising universal refraction.

Compare a Compose/RenderNode-backed approach with a dedicated GPU scene compositor only if necessary. Avoid per-frame CPU Bitmap captures, PixelCopy loops, screenshot-based backdrops, per-control full-screen textures, and shader compilation during animation. Those approaches can create latency, stale content, and allocation spikes.

If the preferred path cannot meet fidelity and frame pacing on the user's phone, resolve the renderer architecture before building the rest of the screens. An OpenGL-based renderer is a possible fallback with a larger integration cost; it is not automatically preferable.

### 5.4 Acceptance scenes

Use a checkerboard, fine text, bright and dark album covers, moving stripes, a rapidly scrolling shelf, and video. Confirm center detail stays crisp, edges bend coherently, highlights remain stable, clipping is correct, and content never freezes under moving glass. Check overlapping glass layers for accidental self-sampling and halos.

For image comparison, align content, crop, density and animation phase. Compare layout regions separately from the shader. Screenshots validate shape and color; recordings and traces validate motion and temporal stability. Require a visual review on the actual phone for perceived depth and legibility.

## 6. Phases and milestone gates

Effort ranges are planning estimates for focused engineering days, not delivery promises. Provider integration and phone findings can change them substantially. Follow dependencies; do not build all features before proving the glass.

### Phase 0 — Scope, reference, and device baseline (1–2 days)

Tasks: identify the streaming provider and perform a documented capability review; verify the iQOO Neo 7 Pro's reported Android 16 build and record API/GPU/resolution/density/refresh modes; inspect installed JDK, SDK, Gradle and ADB; select build versions; fix an app ID and working brand. Establish reference screenshots and short recordings with source/version notes when supplied. Build a requirement matrix mapping this description to phases. Keep graphics experiments independent of provider availability using permitted demo media.

Deliverables: decision record, screen map, reference board, device profile, toolchain lockfile, initial backlog. No guessed reference measurements become final tokens.

**M0 gate:** the phone is recognized and authorized through USB; a minimal signed debug APK installs, launches, and respects insets. Connection failure leaves device verification pending, not passed.

### Phase 1 — Optical renderer research and proof (4–8 days)

Tasks: build an isolated Glass Lab with circle, capsule, rounded rectangle, bottom bar, and moving background. Implement live sampling, curved rim, light response and foreground separation. Add developer controls for parameters and a full-glass-off baseline. Test scrolling/video paths and record capture-to-display lag. Measure overdraw and frame pacing.

Deliverables: renderer prototype, shader source, tuning presets, benchmark evidence, explicit limitations, architecture decision.

**M1 gate:** clear unfrosted centers, convincing edge behavior, live background with no visible freeze/lag, correct foreground text, and an acceptable frame budget on the primary phone. Compare with agreed reference before committing to screen construction. If unresolved, continue this phase rather than disguising the limitation with blur.

### Phase 2 — App shell and reusable components (3–5 days)

Tasks: implement tokens, typography, icons, image loading, insets, four tabs, back stacks, reusable cards/rows/chips/sheets/snackbars. Integrate glass navigation and a fixture-backed mini-player. Add stable semantics/test tags. Create a small-phone layout and large-font layout early.

Deliverables: navigable visual shell and component gallery. Fixture content remains explicitly identified in developer builds.

**M2 gate:** all tabs reachable, navigation state preserved, no bottom occlusion, keyboard-safe Search, readable controls across dark/bright art, correct TalkBack names and hit areas. USB check includes gesture and three-button navigation when available.

### Phase 3 — Real playback and persistent state (4–7 days)

Tasks: first complete the provider feasibility slice; integrate its supported playback implementation through PlaybackGateway, plus Media3 for permitted demo/local media. Implement supported queue commands, repeat/shuffle, progress, errors and session metadata. Handle login, cancellation, expired tokens, unavailable tracks, loss of network and provider errors. Add media permissions and denial recovery as required by the supported OS. Persist playlists/favorites locally or through provider APIs according to capabilities, with ownership clearly defined. Persist history/settings/resumption state. Handle activity recreation, audio focus and headphone removal.

Deliverables: real provider playback with the controls supported by the chosen integration, plus a separate deterministic demo path. If Phase 0Y remains unresolved, this phase can only deliver a playback prototype and cannot pass the streaming-alpha gate. A documented YouTube embed requires revised player layouts and does not pass the original background-audio requirements.

**M3 gate:** play/pause/seek/skip/shuffle/repeat work; UI and lock-screen state agree; audio continues with the screen off; interrupted audio resumes only according to policy; no double audio. Test a 30-minute background session and service recovery separately from activity recreation.

### Phase 4 — Now Playing, queue and optical transitions (4–7 days)

Tasks: artwork palette extraction off the main thread; cache and darken ambient colors; responsive hero layout; carousel and transport controls; progress expansion while scrubbing; reversible mini-to-full transition. Implement queue's collapsed/half/full anchors, nested scrolling, reorder and removal. Add lyrics shell with a permitted synchronized demo track.

Deliverables: complete player flow; reusable transition state; interruption and gesture tests.

**M4 gate:** repeated expansion/collapse and rapid track changes produce no artwork jumps or duplicate surfaces; Back and rotation are safe; seek and queue gestures do not compete; lyrics follow seek and pause correctly. Capture reference-matched videos on USB device.

### Phase 5 — Home, Search, Library and detail pages (6–10 days)

Tasks: implement shelves, quick picks, Speed Dial paging/pinning, history, debounced search with cancellation, source/type/sort filters, album/playlist/artist pages, capability-aware menus, playlist creation/edit/reorder, Undo and related-content navigation. Integrate provider catalog/search/library pagination, rate-limit handling and caches. Use deterministic fixtures for repeatable checks and local metadata for optional device files; describe fixture behavior honestly.

Deliverables: end-to-end streaming music alpha with coherent navigation and persistent user collections within the selected provider's capabilities.

**M5 gate:** cold start → browse/search → play → open player → reorder queue → save playlist → restart → recover state. Include missing artwork, duplicate track titles, long names, empty library, denied permissions and unavailable files. Test a generated 10,000-item metadata library for list/search performance without bundling 10,000 audio files.

### Phase 6 — Lyrics, podcasts and offline media (4–8 days plus provider work)

Tasks: timed/untimed/no-lyrics variants, manual-scroll resumption, line selection where supported; podcast show/episode/resume behavior; extend the already selected provider adapter only where supported; permitted downloads with durable states, cancellation, retries, partial-file cleanup and integrity checks. Respect SDK-managed DRM/download storage. Distinguish artwork caching from user-requested offline audio.

Deliverables: offline-capable media flows for the supported sources, storage settings and meaningful error states.

**M6 gate:** local and completed downloaded items work without network; partial downloads never appear complete; revoked local permissions produce recovery UI; deleting a download updates playback availability; seek/lyrics timing remains aligned. Translation and lyrics sharing stay disabled until their data source supports them.

### Phase 7 — Samples and richer media (4–8 days)

Tasks: vertical pager, one active decoder/player, bounded preload of adjacent clips, lifecycle pause, action rail and metadata navigation. On entering Samples, suspend main playback and keep its queue/position. Full-song action replaces or resumes the main source according to the selected track. On leaving, restore the main context without unsolicited audio; expose an explicit resume action. Implement video mode where media supports it.

Deliverables: stable video discovery and an evidence-backed glass-over-video mode.

**M7 gate:** rapid swiping for 10 minutes does not leak players or steadily grow memory; only one audible source; backgrounding releases unnecessary decoding; video controls remain readable; optical behavior over video matches the tested renderer contract.

### Phase 8 — Connected features and optional expansion (separate estimates after provider selection)

Treat these as independent feature tracks, each with a contract, implementation and device check:

- **Accounts/sync/uploads:** authentication, account-specific caches, offline mutation queue, conflict resolution and sign-out cleanup. Gate: two-device conflict and account-switch isolation.
- **Recommendations/activity:** real ranking/feed source, follow/unfollow, recommendation exclusions, Undo, release notifications and history preferences. Gate: actions persist and affect later results.
- **Comments/collaboration:** pagination, submit/retry, reply and sort, authorization, reporting and moderation behavior. Gate: duplicate submissions and concurrent playlist edits handled.
- **Voice/recognition:** microphone permission, cancellation, provider request/response, unsupported-language and no-match states. Humming recognition requires a suitable service/model and is not ordinary speech-to-text. Gate: test known matches, noise and failures.
- **Casting:** distinguish local/Bluetooth output from Google Cast; integrate actual receiver/provider support and local-to-remote queue handoff. Gate requires a compatible receiver; a USB-connected phone alone cannot verify Cast.
- **AI features:** bounded playlist-generation contract, unavailable catalog item handling, generation progress/cancel and persisted results; optional art generation. Gate: generated playlists contain playable IDs rather than invented songs.
- **Recap/badges:** derive statistics from actual history with defined time windows; respect paused/deleted history. Gate: reconcile calculations against controlled fixtures.
- **Premium/Upgrade:** real entitlement model and billing only if part of the product; no arbitrary subscription restriction on local playback. Gate: purchase/restore/expired/offline states in the appropriate test environment.

**M8 gates:** record each track separately. Mark deferred tracks as deferred, never imply the whole source specification is already implemented.

### Phase 9 — Accessibility, performance and resilience (4–7 days)

Tasks: TalkBack traversal, focus restoration, 200% font scale, reduced motion, RTL and localization stress; long-session profiling; shader allocations and overdraw; startup; thermal response; process restoration; malformed media and network transitions. Build a Baseline Profile after user journeys stabilize.

Provide a user-selectable high-contrast/reduced-transparency mode using solid surfaces, independent from the default clear-glass style. If clear controls fail readability on certain content, first change foreground contrast/local text shadow or control placement; do not silently frost the default glass.

**M9 gate:** no release-blocking defects, no unexplained memory growth, core journeys remain smooth after thermal soak, and controls remain usable with accessibility settings. Benchmark a profileable release-like build; debug builds are for correctness. [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview).

### Phase 10 — Release candidate and handoff (2–4 days)

Tasks: clean build; database migration checks; upgrade installation preserving data; versioning; release notes; dependency/license inventory; signing setup; final fixture removal or clearly separate demo mode; reproducible build instructions. Exclude signing secrets from source control.

Deliverables: installable signed APK, checksum, source, test report, known limitations, device matrix and follow-up backlog. Public distribution is a separate decision; APK handoff does not require publishing.

**M10 gate:** fresh install and upgrade both pass the complete phone smoke suite; release artifact hash matches the tested build; no unsupported feature is presented as operational.

The visual/core engineering baseline through Phase 5 is approximately 22–39 focused engineering days, excluding unresolved provider integration work. This is not yet a reliable estimate for the streaming alpha. Re-estimate after provider selection and the renderer proof; SDK constraints, DRM, account access and custom-player restrictions may materially change the scope.

## 7. USB phone verification protocol

Every milestone has two states: implementation complete and device verified. A milestone passes only when its required real-device checks pass. If the phone is disconnected, locked behind authorization, or unavailable, record `DEVICE_CHECK_PENDING`; continue unrelated engineering but do not substitute emulator results for the required phone check.

### One-time setup

The user enables Developer options and USB debugging, connects a data-capable cable, unlocks the phone, and accepts its computer authorization prompt. Install an OEM USB driver only if needed. The AI cannot bypass the phone's trust prompt. Android documents physical-device setup and ADB connectivity here: [Run on hardware](https://developer.android.com/studio/run/device).

During implementation, the AI will inspect `adb devices -l`, identify the authorized target, and use an explicit serial for every command. With multiple phones attached, select the intended test device before installing. Preserve existing app data unless a separately identified fresh-install scenario requires a disposable test profile or test package.

### Script to build during Phase 0–2

Create `scripts/verify-device.ps1` with milestone, serial, package ID, launcher activity and build-variant parameters. It should:

1. Validate tools, connected serial and `device` authorization state; return actionable errors for unauthorized/offline/no-device.
2. Record git revision when available, APK SHA-256, version, dependency lock, device build/API/GPU, display metrics, refresh mode and renderer settings.
3. Run relevant unit tests and lint, build the APK, check exit codes, install with data preserved, and launch the known activity.
4. Run instrumentation/UI Automator journeys using semantic selectors/test tags. Use coordinates only for explicitly device-calibrated gestures.
5. Capture PNGs and short MP4s for the designated scenes; collect app-relevant logcat, instrumentation results and performance traces.
6. Review images for layout/material defects and inspect logs for crashes/ANRs/errors. Playback state logs alone do not prove audible quality; confirm audibility through an available capture route or a short human listening check.
7. Compare against prior milestone/reference evidence, create a report, fix failures, and repeat affected checks.
8. Emit PASS, FAIL or DEVICE_CHECK_PENDING, with unverified cases listed explicitly.

Representative PowerShell commands, to be parameterized rather than pasted with unresolved values:

```powershell
adb devices -l
.\gradlew.bat :app:assembleDebug
adb -s $deviceSerial install -r $apkPath
adb -s $deviceSerial shell am start -W -n "$packageId/$launcherActivity"
adb -s $deviceSerial shell screencap -p /sdcard/music-app-check.png
adb -s $deviceSerial pull /sdcard/music-app-check.png $evidenceDirectory
adb -s $deviceSerial shell screenrecord --time-limit 20 /sdcard/music-app-check.mp4
adb -s $deviceSerial pull /sdcard/music-app-check.mp4 $evidenceDirectory
adb -s $deviceSerial shell dumpsys gfxinfo $packageId framestats
adb -s $deviceSerial shell dumpsys meminfo $packageId
```

These are planned commands, not actions performed in this planning task. Use device-file capture plus `adb pull` for binary media rather than relying on PowerShell text redirection. Use bounded commands and capture durations. Remove only the script's own remote evidence files after successful pull. ADB supports app installation, shell commands and device-file transfer. [ADB reference](https://developer.android.com/tools/adb).

### Evidence layout

```text
artifacts/device-checks/M04/<timestamp>/
  report.md
  device.json
  build.json
  screenshots/
  recordings/
  tests/
  logs/
  traces/
```

The report states what changed, exact device/build, executed cases, visual findings, measured performance, failures/fixes, skipped cases and next action. Each screenshot or recording must be traceable to the installed APK. Store reference comparisons and shader parameter presets with it.

## 8. Proposed quality budgets and test matrix

Budgets are provisional until measured on the primary device at M1. Do not present them as achieved results.

| Measure | Initial target and measurement |
|---|---|
| Frame pacing | Sustained 60 fps minimum for core UI; higher refresh only if device supports it and renderer passes; 16.67 ms frame interval at 60 Hz, 8.33 ms at 120 Hz |
| Deadline misses | Less than 2% in warmed representative scroll/transition runs on the selected device; investigate long spikes independently |
| Glass overhead | Aim for no more than about 3 ms added GPU work at 60 Hz on the primary device; compare identical glass-on/off scenes |
| Backdrop latency | No visible trailing under moving glass; use recorded moving-stripe tests and traces to diagnose stale frames |
| Startup | Target cold start under 2 seconds to interactive fixture Home in a release-like build on primary device |
| Local search | Target visible result update within 300 ms after configured debounce at 10,000 metadata items |
| Local playback | Target start within 500 ms for a warm readable local track; measure decoder and cold-file paths separately |
| Memory | No monotonic retained growth after 50 player transitions or 10 minutes of Samples; record PSS and texture/decoder peaks rather than assuming a universal MB ceiling |
| Battery/thermal | Compare glass on/off during identical 30-minute active-use sessions; no persistent rendering when UI is backgrounded; avoid a universal battery percentage promise |
| Stability | No crash/ANR during scripted core journeys and 60-minute mixed-use soak |
| Accessibility | Usable at 200% font scale, complete TalkBack labels/actions, no reliance on color alone, readable text and controls |

Run visual recordings separately from clean performance measurements because recording/mirroring adds overhead. Record refresh rate, brightness, power mode and thermal state for comparable tests. Use traces to distinguish UI-thread, GPU, image decode and video decode bottlenecks. `gfxinfo` is supporting evidence, not a complete GPU profiler.

Minimum scenario matrix: bright/dark art; small/tall screen; gesture/three-button navigation; keyboard open; large text; TalkBack; reduced motion; empty/populated library; offline/slow network; denied/revoked permissions; background/foreground; headphones/Bluetooth; process recreation; missing/corrupt media; low storage for downloads; rapid taps and interrupted gestures. The primary phone is mandatory; a second lower-performance device is desirable, with emulator coverage clearly labeled.

## 9. Execution discipline and next step

Build in dependency order: decisions and device → glass proof → shell → playback → immersive player → core catalog → rich media → optional services → hardening → release. No backend dependency should block proving the renderer against fixtures.

For each phase, the implementing AI reads this plan and current decisions, selects a small coherent slice, implements it, runs relevant checks, installs to the authorized USB device at the milestone, inspects evidence, fixes failures and records the result. It must never claim a screenshot proves optical physics, a successful build proves playback, or an emulator run proves phone behavior.

The service, unofficial integration direction, personal APK distribution, navigation and target phone are confirmed. R01–R04 form the completed initial visual reference set; no further images are needed for this planning baseline. The first implementation work is Phase 0 setup plus Phase 0Y provider feasibility and Phase 1 Glass Lab. Complete Phase 0Y before freezing playback architecture; the Glass Lab can proceed independently. This document does not initiate coding, install tools, modify the phone, or set up a recurring automation.
