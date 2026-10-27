# Feasibility and implementation plan: Liquid Glass, glass everywhere, font, lrc.red, lyric blur, animated artwork, scrolling titles, artist "See all", silence skip, equalizer, edge fade

27 October 2026, revised twice the same day. A report and plan only: nothing in the app has changed.
- **First revision:** added scrolling song titles in the player and "See all" on artist pages, removed the lossless section at the owner's request, and added an implementation plan for every item.
- **Second revision:** added skipping the silence at the end of a song, a built-in equalizer that can open the phone's own equalizer, and a dark fade at the top and bottom of the screen. It also updates the font section with the owner's font screenshot.

Based on Riff's source at 9ea71f4, frames from the owner's reference video (`WhatsApp Video 2026-10-27 at 11.24.00.mp4`), and BitChord's source ([kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord), GPL-3.0, commit 04a4a83 of 26 Oct 2026). File and line references are to Riff unless marked BitChord.

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
14. [Order of work, testing and release](#order-of-work-testing-and-release)
15. [Sources](#sources)

## Summary

| # | Request | Feasible? | Likely match to Apple | Size | Main risk |
|---|---|---|---|---|---|
| 1 | Apple-like Liquid Glass | Yes, on Android 13+ | About 85 % on Android 13+; blur only on Android 12 | Large | Frame time: Riff already misses the 60 Hz budget (Q18-030) |
| 2 | Glass on every control | Yes, for controls; not for content | High where something sits behind the glass | Large | Frame time and battery; flat screens give glass nothing to bend |
| 3 | SF-like font | Yes, with Inter (already bundled), tuned | About 90 % to the eye | Small | Bundling SF Pro itself breaks Apple's licence |
| 4 | lrc.red lyrics source | Yes | Same data BitChord uses | Small | No published terms; some word timings are AI-made |
| 5 | Blur on lines not being sung, faded edges | Yes | About 95 % | Small | A few blurred text layers per frame |
| 6 | Animated song art | Partly: only where another service has a clip; otherwise animate the cover on the phone | 100 % when a clip is found, which is a minority of songs | Medium | Apple and Spotify tokens Riff doesn't own; privacy |
| 7 | Scrolling long titles, player only | Yes | About 100 % | Small | Interaction with the player's open/close morph |
| 8 | "See all" on artist pages | Yes | Same as YouTube Music's own "See all" | Small to medium | Riff's parser currently drops the link YouTube sends |
| 9 | Skip the silence at the end of a song | Yes | Exact for downloaded and cached songs; about 1.5 s of silence still heard on streams | Medium | Hidden tracks after a long silence; quiet song endings |
| 10 | Built-in equalizer, plus the phone's equalizer | Yes; the phone's equalizer opens as its own screen, not inside Riff | Riff's own equalizer: full; phone's equalizer: whatever the phone offers | Medium | Clipping when bands are raised; doubled effect if both are on |
| 11 | Dark fade at the top and bottom of the screen | Yes | About 100 % of the screenshot | Small | Must not darken the full-screen player's artwork or the glass itself |

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
- Riff already ships **Inter** (`res/font/inter_regular/medium/semibold/bold.ttf`; `InterFamily` in `AmbientTheme.kt:29`). Inter was drawn for screens with metrics close to SF and is the usual open substitute (SIL OFL 1.1).

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

### 6.2 Implementation, in two stages

**Stage A: animate the cover on the phone, for every song, no new service.**
- New `LivingArtwork.kt`, drawn by `PlayerArtwork.kt` instead of the still image when enabled.
- Three styles:
  - **Drift:** a slow pan and zoom (scale 1.00–1.08 over 24 s, eased, reversing).
  - **Depth:** tilt parallax. Shift the image by up to 8 dp from the rotation sensor (shared with Feature 1 step 8).
  - **Mesh:** a moving mesh gradient from the four strongest colours in `ArtworkPalette.kt`, behind a slightly smaller cover. An AGSL shader on Android 13+; a slowly rotating radial gradient on 12.
- Pauses when the player is closed, the screen is off, or under reduced motion and battery saver.
- Setting: Player → Artwork motion: Off / Drift / Depth / Mesh.

**Stage B: Apple motion clips (optional; needs the owner to accept the terms and privacy trade-offs above).**
1. **`MotionArtworkRepository.kt`** in the container:
   - Look up by album: search Apple's catalog by album and artist; score title, artist and track count; reject compilations.
   - Read `attributes.editorialVideo.motionDetailSquare.video` (or `motionSquareVideo1x1`) as the HLS URL.
   - Cache the answer per album, including "none", for 30 days, in `library-state.db` or a small file cache.
   - Cache clips in the existing media cache, with a 200 MB cap.
2. **Token:** fetch Apple's web player page, find the JS bundle, read the token. Keep it in memory only; refresh it on 401. Log failures to `PlaybackEventLog` without the token.
3. **Playback:** a second, muted, looping Media3 `ExoPlayer` (`REPEAT_MODE_ONE`, volume 0, `media3-exoplayer-hls` is already a dependency), in a `PlayerSurface` (`SURFACE_TYPE_TEXTURE_VIEW`, so it can be clipped and faded).
   - Crossfade from the still image on the first rendered frame.
   - Release the player when the full-screen player closes.
   - Never let it hold audio focus.
4. **When it plays:** Wi-Fi only by default. Never under Data saver, reduced motion or battery saver. Off by default, with an explanation in Privacy and data.
5. **Privacy:** list `amp-api.music.apple.com` and `music.apple.com` in `docs/privacy.md`, with what is sent (album and artist names).
6. **Tests:** scoring on recorded fixtures, the "none" cache, the player released on close, nothing fetched when off.

Match: identical to Apple Music when a clip exists; Stage A for the rest.

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
- **BitChord** has its own equalizer (`playback/EqualizerProcessor.kt`, `ui/screens/EqualizerScreen.kt`):
  - a 10-band parametric equalizer with shelves at 60 Hz and 14 kHz and eight bell bands;
  - pre-amp, bass and treble, and balance;
  - presets;
  - built as filters inside the player's audio chain, so it works the same on every phone.
- **Two kinds of equalizer on Android:**
  - Android's `android.media.audiofx.Equalizer`: phone-dependent, often 5 bands, and some phones refuse it.
  - Riff's own filters in the audio chain: the same on every phone.

  The plan uses Riff's own filters.
- **"Open the phone's equalizer inside Riff":** Android opens another app's or the system's equalizer as **its own screen**. Riff can't draw it inside its own screens. What Riff can do: a "Phone equalizer" row on Riff's equalizer screen, which opens it with the right audio session, and returns to Riff with the back button.

### 10.2 Implementation

1. **The filters: new `EqualizerProcessor.kt`.**
   - A `BaseAudioProcessor` in `riffAudioRenderers`, placed **before** `VolumeBoostProcessor`, so the booster's limiter catches peaks the equalizer adds.
   - 10 bands at 31, 62, 125, 250, 500 Hz and 1, 2, 4, 8, 16 kHz. Low shelf at 31 Hz, high shelf at 16 kHz, peaking filters between, Q ≈ 1.1.
   - The biquad coefficients come from the standard "Audio EQ Cookbook" formulas.
   - Each band is ±12 dB.
   - Processing in float, per channel, on the 16-bit PCM that Riff's chain carries (`onConfigure` already requires `ENCODING_PCM_16BIT`).
   - Gain changes are smoothed over about 20 ms, so dragging a slider doesn't click.
   - When every band is 0 dB, the processor passes audio through untouched (no rounding).
2. **Keeping it from clipping:**
   - an automatic pre-amp of −(highest band gain) dB, with a manual pre-amp slider from −12 to +6 dB;
   - after that, the existing limiter in `PcmVolumeBoost` (the booster already limits at full scale).
3. **Crossfade:** the tail player used during crossfades (`tailAudioEffects` in `AudioTransitionCoordinator.kt`) is built with the same renderers. Give it its own `EqualizerProcessor` with the same settings, so both songs sound the same during a blend.
4. **Settings store: new `EqualizerSettings.kt`**, like `CrossfadeSettings`: a preferences file and a `StateFlow`. It holds:
   - on/off;
   - the preset;
   - ten band gains;
   - the pre-amp;
   - optionally **a profile per output** (phone speaker, wired, Bluetooth, USB), picked from `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` and an `AudioDeviceCallback`. Headphones and the speaker usually need different curves.
5. **Presets:** Flat, Bass boost, Bass reduce, Treble boost, Vocal, Rock, Pop, Electronic, Hip-hop, Jazz, Classical, Acoustic, Loudness, Small speakers, and "Custom", saved when the user moves a band.
6. **Screen: new `EqualizerScreen.kt`** under Settings → Playback → Equalizer, and from the player's more menu.
   - On/off switch.
   - A curve preview: the summed response drawn from the biquad formulas.
   - Ten vertical sliders (the glass slider of Feature 2) with Riff's slider haptics. Double-tap a band to reset it to 0.
   - Preset chooser, pre-amp slider, and a "Reset" button.
   - The "Output" profile in use ("Headphones", "Speaker") shown at the top.
7. **The phone's equalizer row:** "Phone equalizer: open your phone's sound settings".
   - The intent is `Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)` with `EXTRA_AUDIO_SESSION` (the player's session from `AudioSessionEffects`), `EXTRA_PACKAGE_NAME`, and `EXTRA_CONTENT_TYPE = CONTENT_TYPE_MUSIC`, started with `startActivityForResult` (the platform requires a result).
   - Android 11+ hides other apps' screens unless declared. Add to the `<queries>` block in `AndroidManifest.xml`: `<intent><action android:name="android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL" /></intent>`.
   - Check `intent.resolveActivity(packageManager)` first. If nothing answers (some phones have no system equalizer), show the row disabled with "Your phone has no equalizer of its own".
   - **When both are on,** Riff's and the phone's curves add together. Show a one-line note under the row: "Using both adds their effects together."
8. **Docs:** features.md (Equalizer), and the "Playback" part of architecture.md: the audio chain becomes `EqualizerProcessor → VolumeBoostProcessor`, plus `SilenceWatchProcessor` from Feature 9.

### 10.3 Tests

- **JVM tests on generated sine waves through the processor:**
  - a +6 dB band at 1 kHz raises a 1 kHz tone by 6 ± 0.3 dB and leaves 100 Hz within 0.5 dB;
  - flat leaves the signal unchanged bit for bit;
  - a full-scale tone with +12 dB doesn't clip after the pre-amp and limiter;
  - a gain change mid-buffer has no step larger than the smoothing allows.
- **Settings tests:** the store round-trips; the right profile is picked per output.
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

## Order of work, testing and release

### Suggested order

| Stage | Work | Why this order |
|---|---|---|
| 1 | Feature 7 (scrolling title), Feature 8 ("See all"), Feature 5 (lyric blur and fade), Feature 4 (lrc.red), Feature 11 (edge fade) | Small, independent, low risk; visible at once |
| 2 | Feature 9 (silence at the end), Feature 10 (equalizer) | Both add a processor to the same audio chain (`riffAudioRenderers`), so do them together and test the chain once |
| 3 | Feature 3 (font and type tokens) | Touches many screens' text; best done before the glass work changes the same screens |
| 4 | Feature 1 (glass engine: library, `RiffGlass`, shell, drag lens, collapsing bar, merge shader, quality levels) | Everything else in glass depends on it; the edge fade moves into the glass backdrop here |
| 5 | Feature 2 (glass everywhere: player, menus, settings, search, the equalizer's sliders, the rest) | Built on stage 4 |
| 6 | Feature 6 Stage A (cover motion on the phone); Stage B only on the owner's decision | Independent; Stage B needs a decision on terms and privacy |

### For every stage

- `python scripts/quality/verify.py --lane pr`: JVM tests, lint, static checks, release build and content checks.
- `verify.py --lane emulator --allow-install` on API 31, 33 and 36, especially for the glass (API 31 takes the frost path).
- For stage 2: the device lane on the phone with headphones and with the speaker. Listen for clicks while moving equalizer bands, and check that crossfades still meet in time when Feature 9 moves the end.
- For stages 4 and 5: `verify.py --lane benchmark` on the phone before and after. Cold start, scrolling frames and memory against the recorded rc10 numbers in `docs/quality/performance.md`.
- Update `docs/features.md`, `docs/privacy.md` (lrc.red; Apple if Stage B), `NOTICE.md` and the dependency inventory (backdrop, shapes, Inter variable), and `CHANGELOG.md`.
- Keep each stage in its own branch and commits, so any one can be dropped without the others.

## Sources

- BitChord: [repository](https://github.com/kushagrasinghx/BitChord), [Liquid Glass fidelity PR #1108](https://github.com/kushagrasinghx/BitChord/pull/1108), [lrc.red request #410](https://github.com/kushagrasinghx/BitChord/issues/410), [Android Police review](https://www.androidpolice.com/open-source-youtube-music-alternative-bitchord-features-comparison/)
- Kyant0 backdrop: [AndroidLiquidGlass on klibs](https://klibs.io/project/Kyant0/AndroidLiquidGlass); ports: [KMPLiquidGlass](https://github.com/Kashif-E/KMPLiquidGlass), [fluid_glass](https://pub.dev/packages/fluid_glass); [AGSL glass write-up](https://dev.to/wrack/liquid-glass-on-android-in-react-native-i-refracted-the-backdrop-with-a-real-time-agsl-shader-1dp4)
- [Liquid Glass (Wikipedia)](https://en.wikipedia.org/wiki/Liquid_Glass)
- Lyrics: [Better Lyrics API](https://github.com/better-lyrics/api), [LRCLib word-sync issue](https://github.com/Andbal23/dynamic-music-pill/issues/193)
