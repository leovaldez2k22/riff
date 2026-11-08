# Riff visual identity and desktop translation audit

## What is the real visual baseline?

### Takeaway

Riff is a native Android Compose UI, with custom Android graphics effects, a bundled variable font, deterministic artwork processing, and unusually detailed lyrics animation. None of its composables or Android shader calls can be called “direct reuse” in a React/Tauri/Electron or Flutter implementation. Its **design values, assets, algorithms, timing semantics and component anatomy** can be preserved exactly; rendering and layout implementations must be ported.

Current inspected repository HEAD is `04aabff5c088d2e91786923b6ef047ad6ed4ef15`. `git status --short` failed with “must be run in a work tree,” so I cannot attest to a clean worktree. No application files changed.

### Cited findings

Actual screenshots were visually inspected: [Home](</C:/Volume D/Riff/docs/images/home.png>), [player](</C:/Volume D/Riff/docs/images/player.png>), [lyrics](</C:/Volume D/Riff/docs/images/lyrics.png>), [Settings](</C:/Volume D/Riff/docs/images/settings.png>), [Library](</C:/Volume D/Riff/docs/images/library.png>), [Encore](</C:/Volume D/Riff/docs/images/encore.png>), [Look & feel](</C:/Volume D/Riff/docs/images/settings-look.png>). README says screenshots were taken 30 October; code and handover contain subsequent 31 October refinements, particularly menus and glass defaults. A screenshot is therefore one approved state, not a full token specification.

The visual DNA is concrete:

- Near-black canvas, subtle purple/blue/teal ambient light concentrated at the top and fading into black.
- Large tightly tracked Inter headings; clear hierarchy without changing font families between app sections.
- Artwork-first surfaces: square rounded Home artwork tiles, rounded square list art, circular artist art.
- Floating capsule navigation/mini-player, bright optical rims, visible distortion/refraction of underlying content.
- Pink selected/accent state, white primary type, cool muted secondary type.
- Full player artwork melts into a derived cover-colour deck; transport controls remain white and broad; shuffle/repeat stay.
- Lyrics use large left-aligned text, subdued out-of-focus neighbouring lines, active-word white sweep, and pink accents/glow.
- Settings are glass bento tiles with icon badges, illustrated audio/glass previews, substantial corners and spacing.
- Encore uses the same glass/tokens, with pink selected bars and substantial numerical typography.
- Current pop-outs are neutral grey frosted panels with large corners, icon-led rows and group separators. The handover explicitly says appearance only changed; menu actions were not replaced with those from a visual reference.

Sources: [RiffApp.kt:76](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffApp.kt:76>), [DesignTokens.kt:102](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:102>), [handover](</C:/Volume D/Riff/docs/handover-0.20.0.md>).

The owner approved the blends, font and redesigned player by listening/inspection. Handover retains unresolved Automix load decisions and says do not make those changes without owner instruction. It also explicitly says: “Don't bundle: SF Pro, BitChord's AGPL code, or open-unmix weights.” This is the exact handover restriction, not an independent assertion about the license of every BitChord repository or component. Preserve Riff’s owned implementation and Inter, and individually verify any proposed external asset/code provenance. [Handover](</C:/Volume D/Riff/docs/handover-0.20.0.md>).

### Exact tokens to freeze

| Area | Current source value |
|---|---|
| Base colours | `Ink #000000`, `Raised #181A22`, `Muted #ADB0BF`, `Pink #FF5EAC`, white |
| Global spacing | screen edge 20dp; art/text gap 12dp; list item 8dp; tight 4dp; block 16dp |
| Rows/art/icons | press target minimum 48dp; list row 60dp; list art 48dp; base icon 24dp |
| Radii | artwork 12dp; card 14dp; hero 24dp; pill 28dp |
| Large title | Inter Display, 34sp/40sp, Bold, tracking −0.9sp |
| Section heading | Inter Display, 22sp/27sp, Bold, −0.4sp |
| Headline | Inter Display, 26sp, Bold, −0.5sp |
| Row title | Inter Text, 17sp, Semibold, −0.4sp |
| Body/subtitle | 15sp/−0.2sp; 13sp/−0.05sp |
| Settings row/help | 19sp/−0.45sp; 15sp/−0.2sp |
| Player | **20sp title**, −0.35sp tracking; **17sp artist**; 17sp lyric preview |
| Player controls | like/more circles 44dp with 20dp icons; play 64dp/52dp icon; skip 56dp/46dp icon |
| Player sliders | seek 7dp idle/12dp dragged; volume 6dp idle/10dp dragged |
| Menu geometry | corner 28dp; width 230–290dp; row 48dp; edge 20dp; icon 24dp; gap 16dp |
| Menu text | 17sp/22sp, −0.3sp; detail 14sp/18sp; title 20sp/25sp Semibold |
| Menu material | tint `#2A2C31` α0.72; white rim α0.10, 1dp; group separators white α0.12 |
| Slider thumb/switch | thumb 30×20dp and ×1.3 held; switch track 51×31dp, thumb27dp, travel stretch25% |

Sources: [DesignTokens.kt:25](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:25>), [player:125](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:125>), [radius/type:285](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:285>).

**Typography is more than `font-family: Inter`.** Bundled Inter 4.1 variable font has weights 300/400/500/600/700/800; Text optical size `opsz14`, Display `opsz32`. General typography tracking uses `−0.0223 + 0.185·exp(−0.1745·size)` em, with explicit component overrides above. Times/counts use `tnum`. Preserve optical sizing, weight, tracking, line height and numeral features explicitly; do not let browser optical auto-sizing silently choose a different shape. [AmbientTheme.kt:33](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/AmbientTheme.kt:33>).

### Important historical-plan differences

Do not mechanically implement old feature text. It references commit `9ea71f4`. Its player tables propose title26sp/artist22sp, but current tokens are20sp/17sp following owner feedback. Its player morph520ms was superseded for full player opening/closing by700/600ms. Its original glass library proposal is superseded by Riff’s current custom shader implementation. Screenshots show a user’s glass150%/frost20%; current defaults are125%/25%, preserving saved choices. [Plan use instructions:18](</C:/Volume D/Riff/docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md:18>), [AccessibilitySettings.kt:96](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/AccessibilitySettings.kt:96>).

## Which effects, animations and spatial behaviours must survive?

### Takeaway

CSS `backdrop-filter: blur()` alone cannot reproduce current Riff. Glass is a real backdrop-sampling refraction shader; lyrics use measured glyph bounds and script-sensitive animation; the player backdrop is deterministic preprocessed artwork. These are release-critical rendering work, not optional decoration.

### Cited findings

**Glass pipeline.** Each screen records a Compose GraphicsLayer. A glass piece samples the page under its current position, includes out-of-bounds reach for blur/refraction, applies blur, lens, tint, rim, then sharp foreground content. Per-frame invalidation follows the underlying page only while the window draws. Popup/dialog sampling must follow the app window’s frames; an earlier failure produced stale lyrics under a timing dialog. [RiffGlass.kt:44](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffGlass.kt:44>), [FrostedPopup.kt:59](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/FrostedPopup.kt:59>).

`GLASS_SHADER` uses rounded-rectangle signed distance, finite-difference normal plus depth towards centre, quarter-circle displacement profile `1−sqrt(1−x²)`, red/blue channel offsets stronger at corners, zoom, and top-left/bottom-right glints. Current refraction is intentionally strong; the lifted nav lens reads **outward** to show the page beyond the bar. Browser ports need a GPU texture of the correct underlying scene; WebGL cannot magically sample arbitrary DOM pixels. This capture/composition architecture is the primary parity feasibility risk. [RiffGlass.kt:269](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffGlass.kt:269>).

Role parameters `(blur,rim,bend,depth,dispersion,highlight,tint)` in dp/fractions:

- Bar `(2,20,36,1,1,.45,white .06)`
- Lens `(0,14,12,0,3,.6,transparent)`, outward
- Button `(2,14,28,1,1,.45,white .06)`
- Card `(8,12,20,0,0,.30,white .08)`
- Menu `(20,10,16,0,0,.30,black .25)`
- Slider `(0,12,24,1,1,.50,transparent)`

Intensity scales bend0–1.5; highlight scales up to1. Frost adds `14·frost` blur and `.10·frost` tint except lens/slider. Full includes dispersion; Reduced removes it; Frost removes lens/refraction; Solid draws opaque `#11131A` and white.72 outline. Do not silently treat a platform incapable of Full as visually equivalent. [RiffGlass.kt:95](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffGlass.kt:95>).

**Motion.** Exact current durations: press130ms, selection260ms, screen420ms, sheet450ms, player internal520ms, artwork520ms, ambient1200ms. General easing cubic(.2,0,0,1); exits75% of entry. Full player open700ms/close600ms cubic(.32,0,.12,1), distinct from generic player internal transitions. Settle damping.9/stiffnessMediumLow; player settle damping1/stiffnessLow. Settings pop damping.42/stiffnessMedium; tile stagger35ms capped280ms. Quick multiplies pace by.7; reduced motion snaps. Gesture tracking/music timing must not be slowed. [GlassMotion.kt:62](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/GlassMotion.kt:62>).

The nav selection lens has its own meaningful spring behaviour: rest height83.3% of bar; lift33% wider/36% taller;350ms hold; lift spring1400/.55; catch900/.9; glide700/1; velocity stretch up to25%; squash7% narrower/5% taller at bounds; trailing rim max35% opacity. Carry these state/physics rules to pointer and touch navigation where retained, not arbitrary ease transitions. [NavigationLens.kt:174](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/NavigationLens.kt:174>).

**Ambient and artwork.** App ambient uses twelve low-luminance top/middle/accent triplets, selected by `(dayOfYear*5 + hour/2)%12`, recomputed every minute, crossfaded1200ms. Vertical glow stops0/.18/.53; three radial glows at normalized positions(.08,.10),(.92,.03),(.55,.22). It is not simply “blur current album everywhere.” [AmbientTheme.kt:73](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/AmbientTheme.kt:73>).

Artwork dominant colour uses deterministic five-cluster k-means, ten iterations, rejecting extreme values where available, grouping nearby hues within52° and brightness distance110, choosing combined dominance. Preserve outputs through fixture tests; replacing it with a library’s “vibrant” swatch changes brand colour behaviour. [ArtworkPalette.kt:4](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ArtworkPalette.kt:4>).

Player backdrop:128px image →6×6 averaged mesh; seam row preserved, remaining rows flipped/rotated; vibrance1.12 and floor.045; three box blur passes radius6; blurred copy α.6; sharp art seam at50%; legibility dark gradient reaches40%. Derived images generated on worker and cache12 entries. Previous derived cover remains visible until incoming ready, then ambient1200ms crossfade. Sharp artwork requests a stable1024px image so morphing layout does not trigger requests each frame; its alpha mask stops at0/.40/.60/.76/.92/1. Desktop should preserve mask/algebra while selecting adequate DPI-sized artwork derivatives. [PlayerBackdrop.kt:38](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlayerBackdrop.kt:38>), [PlayerArtwork.kt:24](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/PlayerArtwork.kt:24>).

**Lyrics.** 29sp/38sp Display with−.6sp; backing20sp/27sp; romanized-under19sp/25sp at70%. Held-word rise2dp/glow14dp/sweep soft edge10dp/scale1.07. Letters rise1dp, swell1.12, held shimmer.25dp. Unfocused blur1/1.75/2.5dp by distance; fades become clear at12% and stop82%. Instrumental dots13dp,5dp gaps,1600ms breathing,300ms exit. [DesignTokens.kt:325](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:325>).

Line activation32ms, deactivation260ms; active line scales1.025 anchored at reading edge (RTL-aware), held rise220ms/release380ms; word/glyph highlighting follows measured layout. Joined scripts are not blindly animated letter-by-letter. Current position sampled on animation frame while visible/resumed, not timer increments. Default offset+200ms plus two display frames while playing; follow centres earliest overlapping active line at one-third viewport. User scroll disables follow and blur, tap-line seeks/restores follow, long-press enters share selection. [LyricsKaraoke.kt:47](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/LyricsKaraoke.kt:47>), [LyricsPanel.kt:42](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/LyricsPanel.kt:42>).

**Existing adaptation.** Width<600dp compact;600–839 medium rail;≥840 expanded right player; height<480 forces compact. Rail80dp with12dp sides; player40% min360dp. Five nav destinations Home/Podcasts/Radio/Library/Search. Existing expanded arrangement suppresses mini-player and avoids overlaying player over browsing; current code is a useful spatial starting point, not a production desktop layout. [ShellRail.kt:19](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ShellRail.kt:19>), [RiffApp.kt:243](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/RiffApp.kt:243>).

### Inferences and verification guidance

“Zero regression” can be an acceptance policy, not a guaranteed runtime property. Freeze Android references plus token/shader/timing fixtures. Compare a fixed-size isolated component across runtimes before desktop containers change its context. Use screenshot and time-sequenced motion captures, dark/light/multicolour artwork, RTL/Devanagari/Latin, reduced motion, high contrast, font scaling and DPI/mixed monitor changes. Font antialiasing is platform-dependent; compare glyph geometry/line breaks separately from raster edge noise.

The highest-risk milestone is M1: prove backdrop refraction from live scrolling content and lyrics glyph treatment. A flat blur facsimile should fail the gate. Existing tests include `RiffGlassTest`, `TabLensMotionTest`, `TypeScaleTest`, `ArtworkPaletteTest`, `LyricsKaraokeTest`, `LyricsHeldWordRoomTest`, `PlayerRedesignTest`, `ScreenBaselineTest`, plus device screenshot checks. Port fixture expectations where possible rather than inventing another identity.

Existing contrast policy is not uniformly hard-backed: `RiffContrast` defines4.5:1 text and3:1 controls, but `RiffOverPicture` documents owner-chosen shadow-only icons/text over pictures, with limitations recorded in accessibility docs. Do not promise already-achieved universal contrast. [DesignTokens.kt:440](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/DesignTokens.kt:440>).

## Component migration classification

### Takeaway

For non-Compose desktop stacks, “Keep1:1” below means **same visual/state contract after a faithful port**, not drop-in Kotlin component reuse. The only literal reusable parts are suitably licensed assets and platform-independent data/algorithm specifications.

### Cited findings and proposed matrix

All screen inventory comes from [ShellScene.kt:104](</C:/Volume D/Riff/app/src/main/java/com/musicglass/personal/ShellScene.kt:104>) and visual components above.

| Current component/view | Classification | Desktop treatment |
|---|---|---|
| Design tokens, Inter variable font, icons/artwork assets | Keep1:1 | Versioned canonical token/asset package; preserve optical axes and SVG/path geometry |
| Artwork k-means, deck mesh/blur/tone algorithms | Keep1:1 | Same math and expected fixture outputs; host bitmap API replaced |
| Glass roles, shader math/rim/tint, switches, sliders, segmented controls | Keep1:1 | Port renderer adapters; no stock desktop skins |
| Home speed dial/recommendation shelves | Restructure layout | Same cards/chips, cap artwork size, increase columns rather than scale |
| Search/top-result/filter chips | Restructure layout | Main-stage search; command-search entry; same result anatomy |
| Podcasts/Radio | Restructure layout | Preserve artwork/cards/live status; expand grid, detail beside list when useful |
| Library, collection/album/playlist, artist/shelves | Restructure layout | Navigation/content/detail regions; same art/title/actions; optional table density preserving tokens |
| Full player | Restructure layout | Artwork stage and control deck in bounded proportions; retain shuffle/repeat/label/close |
| Current phone mini-player | Restructure layout | Persistent dock; separate compact always-on-top window reuses control anatomy |
| Lyrics/KaraokeLine | Keep1:1 + layout restructure | Same glyph/timing rules; reading-width cap in inspector/focus stage; no stretched text |
| Queue sheet | Replace/augment | Dockable inspector, explicit drag handles, multiselect and keyboard reorder |
| Bottom navigation & drag lens | Replace/augment | Themed rail/sidebar, preserve lens where selected state retained |
| Song/player menus, timing dialog | Replace/augment | Right-click + keyboard context menus, anchored popovers; exact RiffMenu material/type |
| Swipe/long-press actions | Replace/augment | Hover affordances, right-click, keyboard alternatives; long-press remains for touch |
| Settings landing and sections/moods | Restructure layout | Section navigation + bounded bento stage; retain previews, switches and tile pop physics |
| Equalizer/Dynamic tone pad | Keep1:1 + layout restructure | Same pad/curve/faders, pointer precision and keyboard value controls |
| Encore + detail/stories | Restructure layout | Charts/lists side-by-side, same selected pink and typography; stories bounded cinematic stage |
| Downloads/on-device/history | Restructure layout | File drop/import, source/account scopes, desktop location control |
| Account/sign-in/privacy/data/backup | Replace/augment | Desktop auth and filesystem adapters; preserve Riff visual components; platform flow cannot be copied |
| Output/Cast picker | Replace/augment | Desktop device chooser/provider capability model; Android APIs not reusable |
| Diagnostics/loading/empty/offline/notices/What’s new | Keep1:1 + layout restructure | Same component styling and state semantics; desktop-sized containers |
| Android widget/Quick Settings/Android Auto | Replace/augment | Tray/media controls/Mini-Player; these are OS adaptations, not component ports |

### Gaps and confirmed scope

No desktop render proof, performance measurements, screen reader/keyboard audit or complete motion capture was possible in this research-only inspection. The owner has now confirmed **Windows 11 only initially**, with **visual fidelity and reliable playback the priorities**. Memory should be measured rather than capped; do not introduce a speculative <100MB gate. Future OS support should remain architecturally possible without expanding the initial validation matrix beyond Windows. All implementation milestones must still run `python scripts/quality/verify.py --lane pr` before being called done; no lane was run for this research-only audit.
