# Riff and Riff+: the split plan (7 November 2026)

This plan turns one app into two editions built from one codebase: **Riff**, the light everyday player, and **Riff+**, Riff with every extra. Implementation started with stage 0 on 7 November at the owner's request. Current status: stopped by owner, 8 November 01:50 IST; stages 0–7 complete, stage 8 technical verification passed with owner review pending, stage 9 incomplete; Android 12 / API 31 minimum. Resume only on owner instruction. See the latest implementation log and handoff before resuming. On 7 November the owner authorized continuing through implementation and verification without stopping after each stage. Each subsequent stage still waits for its passing quality gate; publication remains separate.

- **What was decided, and why:** [feature-picks-2026-11-06.md](feature-picks-2026-11-06.md), section "Two versions of Riff". The owner's answers of 7 November to this plan's questions are in [section 2](#2-the-owners-answers-7-november); where they changed an earlier decision, the earlier one is superseded.
- **Scope:** the split only. It is built first, before any new feature (decision 11), but **nothing is published until every feature of the picks list is done** (owner, 7 Nov). The first published release with both editions is **0.30.0**. The new features each get their own plan.
- **Line references** are to the working tree of 7 November 2026 (branch `android-11`, after `2409a1d`). Check them against the code before acting on one.

## Contents

1. [What the code says](#1-what-the-code-says)
2. [Open questions for the owner](#2-open-questions-for-the-owner)
3. [What goes where](#3-what-goes-where)
4. [How the two editions are built](#4-how-the-two-editions-are-built)
5. [Feature by feature](#5-feature-by-feature)
6. [Moving between Riff and Riff+](#6-moving-between-riff-and-riff)
7. [Updates and the switch](#7-updates-and-the-switch)
8. [Release, checks and CI](#8-release-checks-and-ci)
9. [Tests](#9-tests)
10. [Stages](#10-stages)
11. [Risks](#11-risks)
12. [Documents to update](#12-documents-to-update)
13. [Done means](#13-done-means)

---

## 1. What the code says

Read before planning, 7 November 2026.

### 1.1 Where the size is

| Part | Size in the 0.21.0 APK | Where |
|---|---|---|
| ONNX Runtime, arm64 | 34.2 MB, stored uncompressed | `libs.onnxruntime.android` 1.31.1, `app/build.gradle.kts:238` |
| Beat model (Beat This! small0, int8) | 4.6 MB | `app/src/main/assets/automix/beat-this-small0-int8.onnx` |
| Everything else | original estimate about 5–6 MB; superseded by the 9.58 MiB stage 2 light measurement below | Code, resources, Media3, Cast, Coil, Ktor, innertubex |
| **Total** | **44.3 MiB** | `riff-0.21.0.apk` |

Stage 2 measurement (7 November): the unsigned shrunk ARM64-configured Riff APK is **10,041,462 bytes (9.58 MiB)**, versus 46,511,187 bytes (44.36 MiB) before separation. Evidence: `artifacts/quality/lanes/pr-20261106T214952Z/riff-apk.log` and `riff-release-content.log`, source digest `e226f0a23967…` on dirty `2409a1d`. Compiled checks prove both native libraries, ONNX Java bindings and the model absent, with Encore widget/recap retained. This is below the approximate 10–12 MB target and 16 MiB ceiling. Plus verification and signed-candidate measurements remain pending; these are unsigned local build bytes.

### 1.2 Only two files use ONNX Runtime

`BeatModel.kt` and `OrtBeatLogits.kt` import `ai.onnxruntime`. Everything else reaches the model through `BeatModel.get(context)`, which already returns null when the runtime or the model is missing (`BeatModel.kt:222–224`, written for 32-bit phones and Riff Lite). The analysis then falls back to stage 2's tracker. So Riff needs a stand-in `BeatModel` that always returns null, and no other playback code has to know which edition it is in.

### 1.3 Smart blends do not use the ONNX engine

`AudioTransitionCoordinator.kt:62–66`: "The beat model runs only for Automix; Smart and the silence skip never use the beats (owner, 31 Oct)." Smart reads each song's loudness envelope (`TrackAnalysis`, `SmartTransition.kt`), which is plain Kotlin and costs nothing in size. The owner's first decision put Smart in Riff+ because it "uses the ONNX engine"; it does not. Asked (Q1), the owner kept **Smart in both editions** (7 Nov). Only Automix is Riff+ only.

### 1.4 Things the extras share with what stays

- **The silence skip (Feature 9) stays in Riff** and uses the same envelope reading as Smart (`TrackAnalysis`, `AnalysisStore`). Those files stay in `main`.
- **The equalizer processor is also Automix's bass swap.** `AudioTransitionCoordinator.kt:288` gives the crossfade's outgoing player its own `EqualizerProcessor`, and the `shelves` callback (`:309`) sets `bassCutDb` on both. In Riff+ the processor stays where it is. In Riff, plain crossfade has no bass swap, so Riff needs no processor at all.
- **Encore's genres are read by Smart and Automix** (`AudioTransitionCoordinator.kt:49–51`, `:76–77`): songs by classical artists blend plainly. Encore stays whole in both editions (Q3) and Smart is in both (Q1), so this link needs no change.
- **The echo-out** (`EchoProcessor.kt`) is used only by Automix's blends and moves with it.

### 1.5 Background work and system parts

- **Weekly recap:** `EncoreRecap.schedule` (`EncoreRecap.kt:57`) books a `JobScheduler` job run by `EncoreRecapJob` (manifest line 131), which posts a notification opening Encore (`AppShortcut.Encore`). It is the only scheduled background work Encore has. It stays in both editions (Q3).
- **Encore widget:** `EncoreWidget` receiver (manifest line 137) with `res/xml/widget_encore_info.xml`. Stays in both editions (Q3).
- **ONNX telemetry:** the manifest removes `ai.onnxruntime.TelemetryInitializer` (line 38) and `proguard-rules.pro:19–21` keeps `ai.onnxruntime.**`. Both belong to Riff+ only.
- **Stats for nerds:** collected in the playback service (`MusicPlaybackService.kt:45–46`, `:231`, the `GET_NERD_STATS` command at `:370`) and drawn in `NowPlaying.kt`.

### 1.6 Settings that name the extras

- **Moods** store a blend mode: Party is Automix, Focus and Night are Smart, Commute is Crossfade (`Moods.kt:24–27`). Moods of the user's own are saved with `mode.wire` (`Moods.kt:43–49`).
- **Export, import and the phone backup** carry the equalizer's settings and the blend mode (`LocalDataTransfer.kt:133–135`).

### 1.7 Build setup today

Stage 1 verification confirms the wrapper is Gradle 9.8.2 with AGP 9.4.1 and Kotlin 2.4.10. Earlier line references and AGP 8.13 examples describe older code; the implementation uses the current wrapper. README and the remaining device helper now agree with it.

- Build types: `debug`, `release`, `stress`, `lite`, `preview`, plus `benchmarkRelease` and `nonMinifiedRelease` from the Baseline Profile plugin (`app/build.gradle.kts:67–140`, `:183–196`). There are no product flavors.
- **The old `lite` build type** (own package, 32-bit, `BuildConfig.LITE`, `src/lite`, `scripts/build-lite.ps1`) is out of date and unused (owner, 6 Nov). `BuildConfig.LITE` is read in `AccessibilitySettings.kt:19–22, 62–63`, `ArtworkImageLoader.kt:45` and `ProviderArtwork.kt:44`.
- **The update notice** (`docs/quality/update-notice.md`) already asks GitHub's release feed once a day. It only opens the release page: it never downloads or installs, and that is checked by `UpdateNoticeTest` ("No code path downloads or installs").
- **Release tooling** builds `:app:assembleRelease` (and `:app:assemblePreview` with `-Preview`) into `riff-<version>.apk` (`scripts/build-release-candidate.ps1:121–178`). `scripts/quality/verify.py` looks for APKs under `app/build/outputs/apk/release` (`:402`, `:607`, `:622`, `:634`) and `.../debug` (`:431–432`).
- **A mismatch to fix:** `.github/workflows/ci.yml` says the lane enforces a 40 MiB ceiling; `verify.py:15` says 64 MiB. The split sets a ceiling per edition (section 8.3), and both texts are corrected then.

---

## 2. The owner's answers (7 November)

| # | Question | Answer |
|---|---|---|
| Q1 | Smart blends don't use ONNX (1.3). Riff, Riff+ or both? | **Both.** Riff offers Crossfade and Smart; Automix is Riff+ only. |
| Q2 | Should the switch open the release page now (a), or download and install in the app (b)? | **(a).** The switch opens the release page; it becomes an in-app download with feature 3. |
| Q3 | Which parts of Encore stay in Riff? | **All of it. Encore is not touched**: it looks and works the same in both editions. This supersedes the 6 November move of Encore's extras (stories, recap, genres, weekly view, widget) to Riff+. |
| Q4 | Version number. | **0.30.0** (version code 36): the split is a major junction. Published only once every feature of the picks list is done. |
| Q5 | A preview of each edition? | **Yes, both**: `riff-<v>-preview.apk` and `riff-plus-<v>-preview.apk`. |
| Q6 | Launcher icon. | **Riff keeps its icon. Riff+'s icon is the user's choice** of styles A, C and D in any accent colour (feature 21 of the picks list), which also colours the launch screen's icon. Feature 21 builds the choice; until then the split ships Riff+ with style A in Rose. |
| Q7 | Remove the old `lite` build? | **Yes, all of it.** |

---

## 3. What goes where

### 3.1 Riff+ only

| Part | From the decision |
|---|---|
| The Automix blend, the beat model, ONNX Runtime, the echo-out, the bass swap | Automix |
| The equalizer of 0.20.0 (Dynamic pad, 7 bands, presets, pre-amp, balance) | Equalizer |
| Stats for nerds | 6 Nov |
| Features 10, 11, 13, 15, 16, 17, 18, 19, 20, 21, 23 and 24 of the picks list | Later, each in its own plan; none exists yet |

### 3.2 Riff

Everything else, unchanged from 0.21.0 except where the extras are hidden: browsing, search, the player, lyrics and lyric cards, downloads and Smart downloads, Liquid Glass, Cast, Android Auto, the volume booster, normalize volume, the button that opens the phone's equalizer, Crossfade with its length, the Smart blend, the silence skip, speed, the sleep timer, Moods (Crossfade or Smart), **all of Encore** (stories, recap and its weekly notification, genres, the weekly view, charts, artist photos and the Encore widget), all widgets, backups, export and import, the update notice, accounts and privacy.

### 3.3 The rule for the code

- **A file moves out of `main`** when it brings something Riff must not carry (a library, an asset, a manifest entry, a background job) or is a whole screen only Riff+ shows.
- **A file stays in `main`** when both editions use it, or when it is plain logic that Riff simply never calls. R8 removes code that is never reached, so it costs Riff nothing; but it must never be reachable in Riff, which the tests in section 9 check.
- **`main` never names a Riff+-only class.** It reaches Riff+ through one seam, `Edition` (section 4.3).

---

## 4. How the two editions are built

### 4.1 A product flavor dimension

```kotlin
android {
    flavorDimensions += "edition"
    productFlavors {
        create("riff") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "PLUS", "false")
            buildConfigField("String", "EDITION", "\"riff\"")
        }
        create("plus") {
            dimension = "edition"
            buildConfigField("boolean", "PLUS", "true")
            buildConfigField("String", "EDITION", "\"plus\"")
        }
    }
}
```

- **Flavors, not build types.** The editions must combine with every existing build type: `riffDebug`, `plusDebug`, `riffRelease`, `plusRelease`, `riffStress`, `plusPreview` and so on. Build types can't be combined with each other; flavors can.
- **Same `applicationId`, `versionCode`, `versionName` and signing key** for both (decisions 1 and 10). Neither flavor sets an `applicationIdSuffix` or `versionNameSuffix`. Android installs a package over an installed one of the same version code, so moving down to Riff installs the same version, which Android allows.
- **`riff` is the default** (`isDefault = true`), so Android Studio and plain commands pick the light edition first.
- **Names on the phone:** `src/plus/res/values/strings.xml` overrides `app_name` with "Riff+". Every other string stays in `main`; strings only Riff+ uses move to `src/plus/res`.

### 4.2 Source sets

| Folder | Holds |
|---|---|
| `app/src/main` | Everything both editions use, and the `Edition` interface |
| `app/src/riff/java` | Riff's `Edition` object and the stand-in `BeatModel` |
| `app/src/plus/java` | Riff+'s `Edition` object and the Riff+-only files listed in section 5 |
| `app/src/plus/assets/automix` | The beat model, moved from `main/assets/automix` |
| `app/src/plus/AndroidManifest.xml` | The removal of ONNX's `TelemetryInitializer` (Encore's widget and recap job stay in `main`, Q3) |
| `app/src/plus/res` | `app_name` = "Riff+", Riff+-only strings and drawables (Encore widget resources stay in `main`, Q3) |
| `app/src/plus/proguard-rules.pro` | `-keep class ai.onnxruntime.** { *; }`, moved from the shared rules |
| `app/src/test`, `app/src/testRiff`, `app/src/testPlus` | Shared tests, and tests that only make sense in one edition |

The existing per-build-type folders (`debug`, `release`, `stress`, `preview`, `verification`, `benchmarkFeed`, `testSupport`) keep working: AGP merges `main`, then the flavor, then the build type, then the variant.

### 4.3 The `Edition` seam

One interface in `main`, one object per flavor with the same name and package, so `main` code calls `Edition` without knowing which it is:

```kotlin
// main: com/musicglass/personal/EditionFeatures.kt
internal interface EditionFeatures {
    val plus: Boolean
    val blendModes: List<BlendMode>                 // Riff: [Crossfade, Smart]. Riff+: all three.
    fun audioProcessors(...): List<AudioProcessor>   // Riff: none. Riff+: the equalizer (and its tail copy).
    @Composable fun EqualizerEntry(...)              // Riff: nothing. Riff+: the Sound settings tile.
    @Composable fun NerdStatsPanel(...)              // Riff: nothing.
    fun onAppStart(context: Context)                 // Nothing yet; later Riff+ features use it (6.3).
    // ...one entry per place in section 5, no more.
}

// src/riff: object Edition : EditionFeatures { override val plus = false; ... }
// src/plus: object Edition : EditionFeatures { override val plus = true;  ... }
```

- Each call site in `main` is a one-line call into `Edition`; the list of call sites is section 5.
- `BuildConfig.PLUS` is a compile-time constant. Where a seam would be heavier than the code it guards (one `if` in a settings list), `if (BuildConfig.PLUS)` is fine: R8 removes the branch from Riff.
- The seam is grown one stage at a time, so each stage's diff shows exactly what it moved.

### 4.4 Dependencies

```kotlin
dependencies {
    "plusImplementation"(libs.onnxruntime.android)   // was implementation(...)
    // everything else unchanged
}
```

`gradle/verification-metadata.xml` needs no change: the same artifacts are resolved, only by fewer variants. The `packaging { jniLibs.excludes ... }` block stays (it only matters to Riff+). `docs/dependency-license-inventory.md` and `NOTICE.md` say ONNX Runtime and the Beat This! model ship in Riff+ only.

### 4.5 The Baseline Profile and `:benchmark`

- The installed Baseline Profile plugin retains the synthetic build types `benchmarkRelease` and `nonMinifiedRelease`; AGP combines them with each flavor into `riffBenchmarkRelease`, `plusBenchmarkRelease`, `riffNonMinifiedRelease`, and `plusNonMinifiedRelease` (verified against the stage 1 task graph). The `finalizeDsl` block continues setting the fixture source set and `.benchmark` suffix on those two shared synthetic build types, covering both flavors. The planning examples with the edition inside a build-type name were incorrect; the current task graph is the source of truth. This is the most fiddly part of stage 1; try it first.
- `:benchmark` (`com.android.test`, `targetProjectPath = ":app"`) needs the dimension: add `missingDimensionStrategy("edition", "riff")`, so journeys measure Riff by default, and `-PriffBenchmarkEdition=plus` to measure Riff+.
- **The profile:** generate it from Riff+ (it runs the most code) into `src/main/generated/baselineProfiles`, so both editions package it; rules for classes Riff doesn't have are ignored. If the plugin refuses that layout, generate one per flavor (`saveInSrc` per variant) and say so here.

### 4.6 Removing the old `lite` build (stage 0; Q7: yes)

- Delete the `lite` build type (`app/build.gradle.kts:121–131`), `app/src/lite`, `scripts/build-lite.ps1`, and the `benchmarkLite`/`nonMinifiedLite` lines in `finalizeDsl` (`:191–194`).
- Replace `BuildConfig.LITE` with its release value (`false`) in `AccessibilitySettings.kt`, `ArtworkImageLoader.kt` and `ProviderArtwork.kt`, and drop the `buildConfigField("boolean", "LITE", ...)`.
- Remove the Lite mentions in `BeatModel.kt`'s comments and `README.md` ("Phones"), and the 32-bit note in `app/build.gradle.kts:148–150` (keep the excludes).
- Its own commit, before the flavors, so the flavor diff isn't mixed with it.

---

## 5. Feature by feature

For each extra: what moves, what stays, what Riff shows, and how it is checked.

### 5.1 Automix and Smart

**Moves to `src/plus`:** `BeatModel.kt`, `OrtBeatLogits.kt`, `BeatTracker.kt`, `EchoProcessor.kt`, and the beat model asset.

**Stays in `main`:** `Crossfade.kt` (the `BlendMode` enum and the crossfader), `SmartTransition.kt`, `TrackAnalysis.kt`, `AnalysisStore.kt`, `AudioTransitionCoordinator.kt`. The silence skip needs the envelope code, and the enum must stay readable in both editions (section 6).

**`src/riff` gets** a stand-in `BeatModel` whose `get(context)` returns null, so `TrackAnalysis.kt:124, 133` compile and fall back as they do on a phone without the runtime today. If `BeatTracker` turns out to be needed by the silence skip, it stays in `main` instead (check the call sites in stage 2).

**Behaviour in Riff:**

- `Edition.blendModes` is `[Crossfade, Smart]` in Riff and all three in Riff+ (Q1).
- `effectiveBlendMode()` (new, in `Crossfade.kt`): the stored mode if `Edition.blendModes` contains it; otherwise Automix plays as **Smart**, its closest blend without beats. Every reader of `blendMode(prefs)` that decides behaviour uses it: `AudioTransitionCoordinator.kt:62` (`analyse`), `:127` (`smartBlend`), and the settings summaries. **The stored value is never rewritten** (section 6.2).
- In Riff, `analyse` never asks for the beat model (the effective mode is never Automix), and `planSmartTransition` is called with `onBeat = false`. The `shelves` and `echo` callbacks are not passed in Riff (no tail equalizer, no echo), so Smart blends without the bass swap and the echo-out, as required by the light edition. Code review corrected the earlier claim that Smart already did this: 0.21.0 passed bass-swap callbacks for Smart too. Riff must also bypass the matching gain compensation when those filters are absent; Plus keeps both.
- The silence skip keeps reading tails (`wanted` at `:67` is still true when the skip is on).
- **Settings → Sound:** the segmented control shows Crossfade and Smart in Riff, and the live blend drawing draws those two. The Settings opening page's "Automix" quick switch is named "Blend" in Riff.
- **Search inside Settings** must not find "Automix" or "beat" in Riff.

**Checks:** the Riff APK has no `libonnxruntime.so` and no `automix/*.onnx` (section 8.3); a Robolectric test per edition for what Sound settings shows; a JVM test that `effectiveBlendMode` maps Automix to Smart in Riff and leaves every mode alone in Riff+.

### 5.2 The equalizer

**Moves to `src/plus`:** `EqualizerScreen.kt`, `EqualizerProcessor.kt`, `EqualizerCurve.kt`.

**Stays in `main`:** `EqualizerSettings.kt` (the stored values and their keys), because export, import and the backup carry them (`LocalDataTransfer.kt:134`) and a move down must keep them (decision 12). Stage 4 inspection also found that `EqualizerCurve.kt` contains the stored `EqMode`, `EqLayout`, `EqualizerConfig` and preset keys. Extract these plain data definitions to shared `EqualizerConfig.kt` before moving the response and filter math; import validation requires them in both editions. The current settings have no separate pre-amp key; headroom is calculated from the curve, so the preservation table names the actual stored values.

**Seam:** `AudioTransitionCoordinator` takes its processors from `Edition.audioProcessors(...)` instead of building `EqualizerProcessor()` itself (`:34`, `:288`). `riffAudioRenderers(...)` gets the list. `updateEqualizer` and its preference listener (`:159`, `:236`, `:272–273`, `:321`) move behind the same seam. `SettingsSound.kt`, `SettingsScreen.kt` and `ShellScene.kt` call `Edition.EqualizerEntry(...)` and the equalizer route only exists in Riff+.

**Riff shows** the phone’s own controls action in the Sound grid, including an unavailable state on phones without a handler, and no app-equalizer tile. Inspection found this action was private inside `EqualizerScreen.kt`; extract its system intent and launcher to shared `PhoneEqualizer.kt` before moving the screen. Keep the existing package/content-type extras and manifest query. A restored old equalizer route displays Sound in Riff; it never constructs Plus UI or rewrites saved settings.

**Watch for:** the bass swap in Automix sets `bassCutDb` on the processors (`:309`), so it must reach them through the same seam in Riff+.

**Checks:** a Robolectric test that Riff's Sound page has the phone-equalizer button and no equalizer tile; a JVM test that Riff's renderer chain has the booster and the silence watch and nothing else; the existing equalizer tests move to `testPlus`.

### 5.3 Encore: not touched

Owner, 7 Nov (Q3): Encore looks and works the same in both editions. Every `Encore*.kt` file, the recap job, the widget and their manifest entries stay in `main`, and no stage changes them. The 6 November move of Encore's extras to Riff+ is superseded.

**Checks:** the existing Encore tests run in both flavors unchanged; the content check (8.3) finds the `EncoreWidget` receiver and `EncoreRecapJob` service in both APKs.

### 5.4 Stats for nerds

**Moves to `src/plus`:** `NerdStats.kt` (the panel and `NerdStatsCollector`). Inspection during stage 3 verification also found `codecName`, `nerdStatsLine` and `PlayerStatsLine` in `PlayerDeckParts.kt`, called from Now Playing, plus a Look switch in `SettingsSound.kt`. Move those display/polling helpers to Plus, omit its switch in Riff, and keep the stored `player_stats` value unchanged so switching back restores it. Riff must never start the format-line polling loop even after importing an enabled Plus preference.

**Seam:** the playback service asks `Edition` for an analytics listener (`MusicPlaybackService.kt:45–46`, `:231`): Riff+ returns the collector, Riff returns none. The `GET_NERD_STATS` session command (`:324`, `:370`) is only registered in Riff+. `NowPlaying.kt` calls `Edition.NerdStatsPanel(...)` and the player menu item is only added in Riff+.

**Checks:** a Robolectric test that Riff's player menu has no "Stats for nerds"; the session's command list differs as expected.

### 5.5 Moods

**Stays in `main`.** Moods keep their stored blend mode in both editions (so a mood made in Riff+ survives a move down and back).

**In Riff:** applying a mood sets the blend through `effectiveBlendMode` (`Moods.kt:88–89`): Focus and Night are Smart as before, Commute is a 3-second Crossfade, and **Party, an Automix mood, blends as Smart**. Its booster, even loudness and Data saver apply as before. The summary (`SettingsMoods.kt:49–52`) names the effective blend, so Party reads "Smart crossfade" in Riff. "Keep what is set now" saves the stored mode, which in Riff is Crossfade, Smart, or an Automix choice made earlier in Riff+.

The owner's 6 November rule ("Moods only turn Crossfade on or off in Riff") was written when Smart was going to Riff+; with Smart in both (Q1), Riff's Moods can choose Crossfade or Smart.

**`moodMatches`** (`Moods.kt:31–32`) compares effective modes in Riff, so the active-mood check is right after a move down.

**Checks:** JVM tests for applying and matching each built-in mood in each edition, and for a saved Riff+ mood read in Riff.

### 5.6 Settings, What's new and search

- **Settings' opening page** (`SettingsBento.kt`, `SettingsScreen.kt`): the quick switches become Crossfade, Boost, Data saver, Haptics in Riff. Section tiles that summarise the equalizer or Automix show Crossfade's state instead.
- **What's new cards** (`WhatsNew.kt`): the split release's cards are written per edition (section 12). Cards that describe Riff+-only features from earlier releases are not shown in Riff.
- **Settings search** indexes only what the edition shows.

### 5.7 Export, import and the backup

Unchanged in both editions: every setting is exported and imported, including the equalizer's and the blend mode, so data moves freely between editions and phones. A Riff+ backup restored into Riff keeps the values on disk and simply doesn't use them (section 6).

---

## 6. Moving between Riff and Riff+

### 6.1 How

Both editions are the same package, version and key. Installing one over the other is an ordinary update: Android keeps the app's data (downloads, the queue, settings, accounts, history). Only one edition is installed at a time.

### 6.2 What happens to settings

Settings are never rewritten when the edition changes. Each edition reads the stored values and uses what it supports. That gives decision 12's quiet fall-back and keeps everything for a move back up, with no migration code and nothing to go wrong if the user switches twice.

| Stored setting | In Riff | Back in Riff+ |
|---|---|---|
| Blend mode Automix | Plays as Smart | As stored |
| Blend mode Crossfade or Smart, length | Used | Used |
| Equalizer on, bands, tone, preset and balance (headroom is derived) | Not applied; kept | As stored |
| Moods with Automix (Party) | Apply as Smart | As stored |
| Accent colour (feature 21) | Riff's own colours; the choice is kept | As stored |
| Song analyses (`AnalysisStore`) | Envelopes used by Smart and the silence skip; beats unused | Used |
| Encore, all of it | Unchanged | Unchanged |

Stage 1 code review found a required stage 2 correction: `AnalysisStore.get` selects version 1 without a model, while `read` rejects and deletes valid version 2 files. The implementation must let Smart and the silence skip reuse their envelopes without discarding the model analysis; add a Plus → Riff → Plus cache round-trip test. This is an observed code mismatch with the table above, not completed migration evidence.

### 6.3 Cleaning up after Riff+

Since Encore and its recap job stay in both editions, Riff has no Riff+ job or widget to clean up today. `Edition.onAppStart` stays in the seam for the later Riff+ features that will need it: new-release alerts (16) must cancel their background check in Riff, and accent colours (21) must put the launch screen's colour back (section 6 of that feature's plan).

### 6.4 Checks

On the emulator (packaging lane, section 8.5): install Riff+ 0.30.0, set Automix, the equalizer and a Party mood, add an Encore widget, play songs; install Riff 0.30.0 over it; check data is kept, Party blends as Smart, no equalizer applies, and the Encore widget still works; install Riff+ again and check every setting is as it was. Then the same from 0.21.0 to Riff+ 0.30.0 (the path existing users take).

---

## 7. Updates and the switch

### 7.1 Release assets

One GitHub release per version with both APKs (decision 10):

| Asset | Edition |
|---|---|
| `riff-<version>.apk` | Riff |
| `riff-plus-<version>.apk` | Riff+ |

These names are a contract between the release and the app. The update notice and the switch depend on them, and `docs/release-signing.md` and `docs/handover-0.20.0.md` will say so.

### 7.2 The update notice, per edition

The notice (`docs/quality/update-notice.md`) stays as it is, with three changes: Bind cached availability to the installed edition so switching cannot reuse an unchecked opposite-edition notice. Keep debug/preview and dirty builds classified as local. Distinguish the two identity paths when testing: `BuildIdentity.current()` currently reads `BuildConfig.BUILD_TYPE` (`release`), so its existing classifier remains valid; the archived APK identity records the full `riffRelease`/`plusRelease` variant. If that runtime representation changes later, change the classifier and its tests together.

- It **names the edition**: "Riff+ 0.30.1 is available".
- It **counts a release only if it has this edition's asset**, so a release missing the Riff+ APK by mistake never prompts Riff+ users to "update" to a page without their file. Assets are read from the same feed response (`assets[].name`), which is already fetched; nothing new is sent.
- **View** opens the release page as now. With feature 3 it becomes the in-app download.

### 7.3 The switch

**Where:** Settings → About, below "Check for updates".

- In Riff: **Get Riff+**. It says what Riff+ adds in one line, and how big it is (read from the feed's asset size).
- In Riff+: **Switch to Riff**. It says what Riff leaves out, and that nothing is deleted: settings come back on a move up.

**What it does:** it opens the installed version’s release page in the browser, only when the response lists the opposite edition’s exact asset, after the same HTTPS host check the notice uses (Q2 (a)). Inspection during stage 4 verification corrected “latest release” here: that contradicted the same-version-only rule below. If that version is absent from the bounded response, hide the switch; feature 3 adds explicit tag lookup and the installer. When feature 3 is built, it downloads the other APK and starts Android's installer instead, with the same progress bar as updates.

**Rules:**

- Never a price; never a nag. It's one row, shown only in About, never on Home or in the player.
- Only offered for the same version the user has. Switching and updating are separate steps, so a switch never also changes the version.
- Hidden when the update setting is off (no feed request at all, as now).

### 7.4 Users of 0.21.0

Nothing between 0.21.0 and 0.30.0 is published (owner, 7 Nov): the split and every new feature are built and checked first, then released together as 0.30.0. 0.21.0 has no idea of editions. Its notice opens the 0.30.0 release page, where both APKs sit. The release notes say, first thing:

> **Riff is now two apps.** Riff is the light everyday player (about N MB). Riff+ is Riff with Automix, the equalizer and other extras (about M MB). Both are free. **If you use Automix or the equalizer, download Riff+**; your app becomes Riff+ and keeps everything. Riff installs over it too, and you can switch either way at any time.

`riff-plus-<v>.apk` is listed first among the assets. A 0.21.0 user who picks Riff by mistake loses nothing (section 6) and can switch up from About.

---

## 8. Release, checks and CI

### 8.1 Build commands

| Before | After |
|---|---|
| `:app:assembleRelease` | `:app:assembleRiffRelease :app:assemblePlusRelease` |
| `:app:assembleDebug` | `:app:assembleRiffDebug` (and `assemblePlusDebug` where Riff+ is tested) |
| `:app:testDebugUnitTest` | `:app:testRiffDebugUnitTest :app:testPlusDebugUnitTest` |
| `:app:lintRelease` | `:app:lintRiffRelease :app:lintPlusRelease` |
| `:app:assemblePreview` | `:app:assembleRiffPreview :app:assemblePlusPreview` |
| `:app:assembleBenchmarkRelease` | `:app:assembleRiffBenchmarkRelease` (Riff+ on request) |
| `app/build/outputs/apk/release` | `app/build/outputs/apk/riff/release`, `.../plus/release` |

`README.md`'s build command and `docs/quality/testing.md` are updated with them.

### 8.2 `scripts/build-release-candidate.ps1`

- Builds both releases in one Gradle run (one configuration, shared cache).
- Copies them to `riff-<v>.apk` and `riff-plus-<v>.apk`, each with its R8 mapping (`riff-<v>-mapping.txt`, `riff-plus-<v>-mapping.txt`) and SHA-256.
- Checks the certificate of both (`Assert-ReleaseCertificate`) against the pinned one.
- Runs the release content check on both (8.3).
- `-Preview` builds both previews (Q5).
- Prints both sizes, which the release notes quote.

`scripts/package-source.ps1` still produces one source archive for both editions. Stage 7 inspection found that tracked-file enumeration could omit new flavor sources; packaging now validates every current APK fingerprint input and its bytes before checksums are written. New implementation files must be visible to Git even for a local dirty archive.

### 8.3 Release content check, per edition

`scripts/quality/check_release_content.py` gets `--edition riff|plus`:

| Check | Riff | Riff+ |
|---|---|---|
| Size ceiling | 16 MiB | 64 MiB |
| `lib/arm64-v8a/libonnxruntime.so` | Must be absent | Must be present |
| `assets/automix/*.onnx` | Must be absent | Must be present |
| Receiver `.EncoreWidget`, service `.EncoreRecapJob` | Must be present | Must be present |
| Launcher label | "Riff" | "Riff+" |
| Package, version code, version name, certificate | Same in both, and equal to each other | |

The two APKs are checked against each other too: same package, version code, version name and certificate, or the release is refused. The lane's text and `ci.yml` state the per-edition ceilings, which also settles the 40/64 MiB mismatch (1.7).

### 8.4 The pull-request lane (`verify.py --lane pr`) and CI

- Unit tests for both flavors (Riff's run is the gate for Riff-only rules; Riff+'s keeps every Automix and equalizer test). Expect the JVM test time to almost double; if CI time becomes a problem, Riff+'s Robolectric screen tests can move to a nightly job, with the owner's agreement.
- Lint both releases.
- Build both unsigned releases and run the content check on each.
- `static_checks.py` gets two rules: no `ai.onnxruntime` import outside `src/plus`, and no `src/riff` file referring to a class that exists only in `src/plus` (the compiler catches most of this; the check gives a clear message).
- The solver seam check (`check_solver_seam.py`) runs on both R8 outputs.

### 8.5 Emulator, device, packaging and benchmark lanes

- **Emulator lane:** runs the instrumentation suite on Riff (`riffDebug`), and the Automix and equalizer journeys on Riff+.
- **Packaging lane:** gains the edition switch of 6.4: Riff+ over Riff, Riff over Riff+, and 0.21.0 to Riff+ 0.30.0 (`--previous-apk` given the 0.21.0 APK).
- **Device lane:** on the owner's phone, Riff+ 0.30.0 installed over the installed 0.21.0, then down to Riff and back up.
- **Benchmark lane:** Riff is the edition measured by default. A Riff+ run once before the release, to confirm the split didn't slow it.

### 8.6 Riff Preview, stress and debug builds

Each exists per edition (`riffPreview`, `plusPreview`, ...) and keeps its rules: `-PriffSideBySide=true` debug and stress get their own package and launcher names, which become "Riff Debug" / "Riff+ Debug" and "Riff Stress" / "Riff+ Stress" (`manifestPlaceholders`).

---

## 9. Tests

New or moved, by stage. Every stage ends with `python scripts/quality/verify.py --lane pr` passing (CLAUDE.md).

| Area | Test | Kind | Edition |
|---|---|---|---|
| Edition | `BuildConfig.PLUS`/`EDITION` are as expected; `Edition.plus` matches | JVM | both |
| Blend | `effectiveBlendMode` for every stored mode, both editions; the stored value is unchanged after reading | JVM | both |
| Blend | Riff keeps Smart timing without beat alignment and never builds the echo or tail equalizer | JVM | riff |
| Blend | Silence skip still reads tails in Riff | JVM | riff |
| Analysis | Stand-in `BeatModel.get` returns null; `TrackAnalysis` falls back | JVM | riff |
| Equalizer | Riff's renderer chain contents | JVM | riff |
| Sound settings | What the page shows (Crossfade and Smart; phone equalizer button; no tile after stage 4) | Robolectric | riff |
| Sound settings | Unchanged from 0.21.0 | Robolectric | plus |
| Moods | Each built-in mood applied and matched; a Riff+ mood read in Riff | JVM | both |
| Encore | Existing tests, unchanged, pass in both flavors | JVM, Robolectric | both |
| Encore | Recording a play is identical in both (same rows written) | JVM | both |
| Nerd stats | Menu item and session command absent | Robolectric | riff |
| Settings search | Finds no Riff+-only term | Robolectric | riff |
| Update notice | Edition named; release without this edition's asset ignored; asset size read; malformed `assets` ignored (UPD01 grows) | JVM | both |
| Switch | Shown in About only; opens the release page through the host check; hidden when updates are off (UPD02 grows) | Robolectric | both |
| Transfer | A Riff+ export imported into Riff keeps the equalizer and blend values on disk | JVM | riff |
| Screens | Screen baselines (`ScreenBaselineTest`) recorded per edition for Settings, Sound, Encore and the player menu | Robolectric | both |
| APK | Content check per edition (8.3) | Lane | both |
| Install | Switch and upgrade paths (6.4) | Packaging lane | both |

Tests that exercise Riff+-only code (`BeatModel`, `OrtBeatLogits`, the equalizer processor and curve, nerd stats) move to `app/src/testPlus` unchanged.

---

## 10. Stages

Each stage passes the pull-request lane before the next starts. Stage 1 validates both builds and benchmark dependency resolution; emulator/device/benchmark execution stays in stage 9, where the plan supplies those prerequisites. The owner authorized continuous implementation and verification on 7 November, superseding the earlier requirement to wait for approval after each stage. Report each stage and retain its evidence. Stages 1–2 change nothing a user can see in Riff+. Stages 0–9 build and check the split; stage 10 waits until every feature of the picks list is built, because nothing is published before then (owner, 7 Nov).

| Stage | Work | Needs | Ends with |
|---|---|---|---|
| **0. Clean-up** | Remove the old `lite` build (4.6). Q1–Q5 and Q7 are answered (section 2). | — | Lane passes; README's "Phones" section updated |
| **1. Two identical flavors** | Add the `edition` dimension; both flavors contain everything; app names "Riff" and "Riff+"; update `verify.py`, the release script, CI and `:benchmark` for the new task and folder names; Baseline Profile working per 4.5 | Q6 | Both APKs build and pass every lane; they differ only in the label |
| **2. ONNX out of Riff** | `plusImplementation` for ONNX Runtime; move the model, `BeatModel.kt`, `OrtBeatLogits.kt`, the ONNX keep rule and the telemetry removal to `plus`; Riff's stand-in `BeatModel`; first `Edition` objects | — | **Riff's real size measured** and written in 1.1; content check's ONNX rule on |
| **3. Blends** | `effectiveBlendMode`; Riff's Sound page and quick switch; Moods; move `EchoProcessor` and `BeatTracker` if unused by Riff | — | Riff offers Crossfade and Smart; Riff+ unchanged |
| **4. Equalizer** | Processors through `Edition`; equalizer files to `plus`; Settings entry | — | Riff has no equalizer tile; Riff+ unchanged |
| **5. Stats for nerds** | Collector and panel to `plus`; session command and menu item | — | Riff's player menu without it |
| **6. The switch and the notice** | Edition-aware notice; About row; asset contract | Q2 | UPD01/UPD02 grown and passing |
| **7. Release tooling** | Release script, content check per edition, previews, CI ceilings, packaging lane switch tests | Q4, Q5 | A local signed candidate of both, checked |
| **8. Documents** | Section 12 | — | Docs reviewed by the owner |
| **9. Verification** | Emulator lane both editions; packaging lane; device lane on the owner's phone (0.21.0 → Riff+ → Riff → Riff+); one benchmark run each | Owner's phone | Evidence in `artifacts/quality/lanes/` |
| **10. Release** | After every feature of the picks list is done (7 Nov). Owner approves; version 0.30.0; release notes; signed build of both; owner publishes (or approves publishing) on GitHub | Owner's go-ahead | Release live with both APKs |

Nothing is pushed, published or tagged without the owner asking for it (CLAUDE.md).

### Rough effort

| Stage | Size |
|---|---|
| 0 | Small |
| 1 | Medium: mostly build plumbing; the Baseline Profile and `:benchmark` are the unknowns |
| 2 | Small to medium |
| 3 | Medium: the coordinator is dense, and Moods and Settings both read the mode |
| 4 | Medium: the processor seam touches the audio chain, which needs listening checks |
| 5 | Small |
| 6 | Small to medium |
| 7 | Medium |
| 8 | Small to medium |
| 9 | Depends on the phone and the lanes |

---

## 11. Risks

| Risk | Why it matters | How it is handled |
|---|---|---|
| A Riff+ class reached from Riff at run time | A crash in Riff (`ClassNotFoundException`) | Riff+-only classes live in `src/plus`, so Riff doesn't compile if `main` names one; Robolectric tests run every Riff screen touched |
| The Baseline Profile plugin and flavors | Stage 1 can stall on build plumbing | Try it first in stage 1; fallback in 4.5 |
| Audio chain change in stage 4 | Clicks, wrong loudness or a silent crossfade | Listening checks on the phone for Riff and Riff+: crossfade, booster, normalization, silence skip, and in Riff+ Automix with the bass swap |
| Users installing the wrong APK | A 0.21.0 user loses Automix until they switch | Section 6: nothing is lost, the switch is in About, release notes say which to pick |
| CI time | Two flavors double the JVM tests | Measure in stage 1; propose a nightly split only if needed |
| Two APKs out of step | Same version, different certificates or codes would break switching | The content check compares the pair (8.3) |
| Riff not as small as hoped | The goal is about 10–12 MB | Measured in stage 2, before the rest of the work; if larger, the plan says what else is big before going on |

---

## 12. Documents to update

| Document | Change |
|---|---|
| `README.md` | Two editions, both free; sizes; which to download; build commands; "Phones" without Lite |
| `docs/features.md` | Which edition has what |
| `docs/privacy.md` | ONNX Runtime and its model are in Riff+ only; every network destination is the same in both editions |
| `docs/quality/update-notice.md` | Edition-aware notice, the asset contract, the switch |
| `docs/release-signing.md`, `docs/handover-0.20.0.md` | Two APKs per release, names, both checked |
| `docs/quality/testing.md`, `docs/quality/ci-lanes.md` | New task names, per-edition ceilings, packaging switch tests |
| `docs/dependency-license-inventory.md`, `NOTICE.md` | ONNX Runtime and Beat This! in Riff+ only |
| `docs/feature-picks-2026-11-06.md` | Answers to Q1–Q7 (done 7 Nov) |
| `docs/feasibility-glass-font-lyrics-artwork-2026-10-27.md` | A line in its log: Automix (20) and the equalizer (10) are Riff+ only from 0.30.0 |
| `CHANGELOG.md`, `docs/release-notes-0.30.0.md` | The split, written for users (7.4) |
| `CLAUDE.md` | Two editions; the default build variant; that Riff+-only code lives in `src/plus` |
| `docs/README.md` | Links to this plan and the picks |
| `WhatsNew.kt` | 0.30.0's cards per edition |

---

## 13. Done means

- Riff and Riff+ 0.30.0 build from one source, are signed with the release key, and pass every check of section 8.
- Riff contains none of section 3.1, shows none of it, and is about 10–12 MB (or the measured size is accepted by the owner).
- Riff+ behaves exactly as 0.21.0.
- Either edition installs over the other, both ways, keeping all data, and settings fall back and return as in section 6.
- A 0.21.0 phone updates to Riff+ 0.30.0 in place.
- Each edition's update notice and switch work against the published release.
- Every document in section 12 says the same thing.

## Implementation log

| Date | Stage | Status | Evidence / remaining work |
|---|---|---|---|
| 7 Nov 2026 | 0 · Clean-up | Complete | Owner authorized starting the 0.30.0 plan. Removed Lite build configuration, overlay and script; shared display defaults, artwork cache and cover sizing now use the existing release behavior. Kept stored preferences and the legacy installed-version suffix parser for compatibility. Existing Android 11 working-tree edits preserved. PR lane [pr-20261106T202650Z](../artifacts/quality/lanes/pr-20261106T202650Z/lane-summary.md) PASS_LOCAL, all nine gates. Stage 1 now in progress. |
| 7 Nov 2026 | 1 · Two identical flavors | In progress | Added both edition identities and labels, exact collector and lane paths, dual candidate/preview packaging, shared existing profile and benchmark selection. Tooling tests: 177 pass (including failure propagation and ambiguous APK checks). Gradle task configuration passes; actual synthetic variants differ from the planning examples as corrected in §4.5. Full PR gate pending. |
| 7 Nov 2026 | 1 · First gate attempt | Failed, retained | `pr-20261106T205103Z`: Riff ran 1,595 tests, one failed: JVM `DefaultStrings` could not read new flavor-only labels. APK/profile checks passed. Canceled the subsequent Plus run to fix both editions consistently. Added flavor XML to each test classpath and applied its overrides after shared strings. Focused `StringResourcesTest` passes in both editions (4 tests each, zero failures/errors/skips); `artifacts/quality/stage1-string-regression.log`. Fresh full PR gate rerun started. |
| 7 Nov 2026 | 1 · Regression rerun | Riff passed; Plus running | Fresh `pr-20261106T211133Z`: Riff PASS_LOCAL, 1,595 tests across 207 suites, zero failures/errors/skips; lint and all APK gates pass. Compiled label Riff, package `com.musicglass.personal`, version 0.21.0/35, ARM64; unsigned APK 46,511,187 bytes (44.36 MiB), model still present as required until stage 2. Plus run pending; stage 1 not complete. |
| 7 Nov 2026 | 1 · Two editions | Complete | `pr-20261106T211133Z` PASS_LOCAL in all nine gates for both editions; 1,595 tests each, zero failures/errors/skips. Compiled labels and shared package/version/source identity verified in `edition-comparison.json`. Unsigned APKs: Riff 46,511,187 bytes; Plus 46,511,199 bytes. Plus benchmark dependency resolution dry-run passed; installed/device trials remain in stage 9. |
| 7 Nov 2026 | 2 · ONNX separation | In progress | Runtime, model, model code/test, JNI keep rules and telemetry removal moved to Plus. Shared `BeatAnalysis` interface is necessary because `EnvelopeBuilder` also called model frontend functions, missed by the sample seam. Added null model provider for Riff and preserved model cache bytes while reusing envelopes. BeatTracker stays shared because EnvelopeBuilder/FFT use it. Compiled absence, size and full PR verification pending. |
| 7 Nov 2026 | 2 · Targeted verification | Targeted tests pass; full gate running | Model/cache/identity tests pass in both editions (`artifacts/quality/stage2-targeted-r2.log`); native/model/bytecode containment, labels, Encore and budget tests pass. Model SHA-256 unchanged. Riff actual APK size and full two-edition PR result pending. |
| 7 Nov 2026 | 2 · Light APK measured | Riff passed; Plus running | `pr-20261106T214952Z`: Riff PASS_LOCAL; unsigned APK 10,041,462 bytes (9.58 MiB). Compiled absence checks pass for both ONNX native libraries, bindings and all model assets; Encore components, correct label and profile present. Plus gate still running; stage 2 not complete. |
| 7 Nov 2026 | 2 · First full gate | Failed on Plus manifest lint; fixed, rerun pending | `pr-20261106T214952Z`: Riff passed, 1,594 tests; Plus tests and compiled payload checks passed, but Lint reported `MissingIntentFilterForMediaSearch` on the new flavor overlay. Mirrored main's existing application-scoped suppression and its media-session rationale; no new intent filter or runtime behavior. Focused Plus lint passes (`artifacts/quality/stage2-plus-lint-r2.log`), with no errors in the fresh XML report. Fresh full two-edition gate rerun started. |
| 7 Nov 2026 | 2 · Final verification | Complete | `pr-20261106T222257Z` PASS_LOCAL, all nine gates in both editions. Riff 1,594 tests / Plus 1,597 tests, zero failures/errors/skips; 188 tooling tests. Riff unsigned APK 10,041,462 bytes (9.58 MiB); Plus 46,511,203 bytes (44.36 MiB). Runtime/model/bindings absent in Riff, present in Plus; telemetry initializer absent and Encore retained in both. |
| 7 Nov 2026 | 3 · Blend behavior | In progress | Edition mode lists, effective stored mode, settings/Moods presentation and search implemented; stored Automix remains unchanged. Echo and its tests moved to Plus. Riff tail has no equalizer/echo stages or DSP callbacks, and bypasses bass compensation without matching filters. BeatTracker stays shared for envelope building. Stage 3 targeted tests and full PR gate pending. |
| 7 Nov 2026 | 3 · Targeted recovery | Targeted test fixes; rerun pending | Pure mode, Moods, preference-preservation and resource tests pass. UI checkpoints identified a process singleton retained across Robolectric applications; isolated it per test using the project's existing reflection-reset pattern. Settings search intentionally dims non-matches, so the regression now asserts its actual match semantics rather than tile removal. Full targeted rerun passes in both editions (`artifacts/quality/stage3-targeted-r2.log`), with no failures/errors/skips. Full PR gate started. |
| 7 Nov 2026 | 3 · Description correction | Full gate restarted | Riff build in `pr-20261107T022124Z` passed. Inspection found the shared Smart description still promised the Plus bass swap. Added an accurate Riff resource override and restarted the dual-edition gate before completion. |
| 7 Nov 2026 | 3 · Blend verification | Complete | `pr-20261107T023530Z` PASS_LOCAL in all nine gates for both editions. Riff 1,588 / Plus 1,603 tests, zero failures/errors/skips; 188 tooling tests. Riff unsigned APK 10,025,410 bytes (9.56 MiB), runtime/model absent and Encore retained. Stored Automix survives; Riff uses Smart without shelf compensation or echo. Stage 4 started. |
| 7 Nov 2026 | 4 · Equalizer separation | In progress | Shared saved configuration/preset keys retained; screen, response math and processor moved to Plus. Edition owns main/tail effects and listener lifetime; Riff provides no extra PCM stages. Extracted phone-control intent and launcher, kept Plus note in Plus, and made old routes fall back to Sound in Riff. Static checks: 249 files, zero findings. Focused dual-edition audio, persistence, route, settings and phone-action tests running; full PR gate pending. |
| 7 Nov 2026 | 4 · Focused recovery | Riff compile failure fixed; rerun running | The first focused run exposed `Crossfade` using the general `dbToGain` helper from the moved processor file. Kept this conversion in shared `VolumeBoostProcessor.kt`; equalizer filters remain Plus-only. Plus focused tests passed (44, no failures/errors/skips); Riff did not compile, so the first run is retained as failed in `stage4-targeted.log`. Fresh two-edition rerun started. |
| 7 Nov 2026 | 4 · Incremental-output recovery | Clean rebuild required | The second focused attempt exposed a stale `EqualizerProcessorKt.class`: `javap` still shows the removed `dbToGain`, while the source has only its new shared definition. The first Plus report also omitted the moved `EqualizerTest`; it is not counted as covered. Preserve both failed logs, clean generated app outputs and disable incremental Kotlin for the fresh focused verification. |
| 7 Nov 2026 | 4 · Clean focused verification | Focused checks pass; full PR gate running | `stage4-targeted-clean.log` BUILD SUCCESSFUL after clean non-incremental compilation: Riff 41 / Plus 55 tests, zero failures/errors/skips, including all 11 moved equalizer cases in Plus. The single-definition gain helper and class moves now compile correctly. JUnit evidence preserved in `stage4-targeted-clean-junit`. Full dual-edition PR lane started; stage 4 is not yet complete. |
| 7 Nov 2026 | 4 · Final verification | Complete | `pr-20261107T032711Z` PASS_LOCAL, all nine gates in both editions. Riff 1,583 / Plus 1,612 tests, zero failures/errors/skips; 188 tooling tests. Unsigned bytes: Riff 9995846 (9.53 MiB), Plus 46512255. All 11 equalizer cases run in Plus; Riff class-absence and saved-setting tests pass. Eight Plus effect/screen classes absent from Riff R8 mapping; mapping and hashes preserved. Phone listening remains in stage 9. Stage 5 started. |
| 7 Nov 2026 | 5 · Stats for nerds separation | In progress | Collector, stats dialog, format-line helpers and polling moved to Plus. Shared optional hooks return no collector/UI/command in Riff; gateway returns before sending, player menus filter both extras, and Look uses a full-width Haptics tile. Stored `player_stats` stays unchanged. Added command/class boundaries, real local/catalog menu fixtures, polling/pref checks and Look/search regressions. Static checks: 252 files, zero findings. Clean dual-edition focused compilation/tests running; full PR gate pending. |
| 7 Nov 2026 | 5 · Owner stop / handoff | Focused checks pass; stopped by owner | `stage5-targeted-clean.log` BUILD SUCCESSFUL: Riff 30 / Plus 36 tests, zero failures/errors/skips. Full stage 5 PR gate not run; stage 5 remains incomplete. Reports preserved in `stage5-targeted-clean-junit`. [Handoff](handoff-0.30.0-implementation-2026-11-07.md); no further work until a new owner instruction. |
| 7 Nov 2026 | 5 · Final verification | Complete | `pr-20261107T082052Z` PASS_LOCAL, all nine gates for both editions, same source digest `365cc8d2b2e9…`. Riff 1584 / Plus 1619 tests, zero failures/errors/skips; 188 tooling tests. Unsigned APK bytes: Riff 9972078, Plus 46512255. Stage 6 started. |
| 7 Nov 2026 | 6 · Updates and edition switch | In progress | Added exact versioned asset/size parsing, edition-qualified notices, cache proof and same-version opposite-edition About page action. Off hides notices/switch and sends nothing; local/preview builds cannot switch. Known target-version metadata survives upgrade/switch read-back without another request. Added parser/cache/size/off/UI fixtures and third browser-host guard; updated privacy/notice docs. No installer or permission added. Static checks pass; focused tests running, full PR gate pending. |
| 7 Nov 2026 | 6 · Focused verification | Full PR gate running | `stage6-targeted-clean.log` BUILD SUCCESSFUL; 39 tests in each edition across five suites, zero failures/errors/skips. Archived reports: `artifacts/quality/stage6-focused-results/summary.json`. Full dual-edition lane `pr-20261107T090702Z` is running; no stage-complete claim yet. |
| 7 Nov 2026 | 6 · Final verification | Complete | `pr-20261107T090702Z` PASS_LOCAL, all nine gates in both editions; source digest `af95507758b6…`. Riff 1594 / Plus 1629 tests, zero failures/errors/skips; 188 tooling tests. Unsigned APK bytes: Riff 9973450, Plus 46513627. Stage 7 started. |
| 7 Nov 2026 | 7 · Release tooling | In progress | Applied unreleased 0.30.0/code 36, default Plus A/Rose foreground and monochrome overlays, preview payload/label/source checks, archive fingerprint-input correspondence and pinned signed-source checks. Added a platform-only signed packaging probe and emulator-only upgrade/switch orchestration; fixture and installation trials pending. Tooling tests and source archive dry run pass; full stage PR gate and local signed candidates pending. New implementation files are intent-to-add so tracked-file source enumeration includes them; unrelated index content is preserved. |
| 7 Nov 2026 | 7 · First PR gate and icon correction | Gate passed; final-source rerun required | `pr-20261107T095454Z` PASS_LOCAL, all nine gates in both editions, Riff 1594 / Plus 1629 tests, zero failures/errors/skips. Source digest `c4cf7bb33e31…`; 207 tooling tests. Icon render then exposed the plus at the 72dp viewport edge: Android guarantees a 66dp safe circle, so both Plus foregrounds are uniformly inset 10%, retaining A/Rose composition. Badge radius is 32.384dp, below 33dp. Initial/corrected renders retained under artifacts/quality; [Android guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). Applied queued aapt2 minimum-API and probe-target checks after Windows released the active runner. Fresh full PR gate follows on these corrected sources; no stage-complete claim yet. |
| 7 Nov 2026 | 7 · Owner stop / handoff, 16:06 IST | Stopped; final gate incomplete | Owner requested stopping and a handoff. Interrupted final run `pr-20261107T103339Z` (session 89208 exited 1); no task-owned verifier/collector/wrapper client remains. First pre-inset gate passed, but current inset assets require a fresh full gate on resume. Stages 0–6 complete; stage 7 incomplete; stage 8 artifact drafts only; no device trial or production candidate signing. [Current handoff](handoff-0.30.0-implementation-2026-11-07.md). Do not resume without a new owner instruction. |
| 7 Nov 2026 | 7 · Resume, 18:26 IST | In progress | Owner authorized continuing. Current sources retained; fresh full PR verification follows the canceled run. No replay of applied preparation scripts. |
| 7 Nov 2026 | 7 · Resume preflight | Verified prerequisites; PR running | Confirmed signed 0.21.0 bytes match its release-note SHA-256, clean revision 3c71594, pinned certificate and API 31 minimum. Updated platform-only probe launches with NEW_TASK plus CLEAR_TASK because MainActivity is singleTask and startActivitySync waits for creation; SDK 36 compilation passes. App fingerprint is unchanged. Evidence: stage7-previous-release-verification.json and stage7-probe-resume-compile.log. |
| 7 Nov 2026 | 7 · Corrected-source PR gate | PR passed; signed candidates next | `pr-20261107T125636Z` PASS_LOCAL, all nine gates; 208 tooling tests, Riff 1594 / Plus 1629 tests, zero failures/errors/skips, matching source digest `1e2e3c72b65d…`. Signed build/preview checks remain before stage 7 completion. Current probe was signed and source-bound; runtime trial remains separate. |
| 7 Nov 2026 | 7 · Signed candidate build recovery | Retrying invocation | First candidate attempt repeatedly failed to connect to Kotlin compiler daemons and produced no classes; canceled only its client (exit 1), preserving stage7-signed-candidate-daemon-attempt.log. No shared service was stopped. Retrying with one Gradle worker and Kotlin in-process compilation via process-local properties; app source and passing PR digest remain unchanged. |
| 7 Nov 2026 | 7 · Signed release checkpoint | Releases checked; previews building | In-process single-worker retry produced signed Riff (9989830 bytes, SHA b180b8eb…) and Plus (46530291 bytes, SHA 96a52338…). Both pass pinned certificate/content and signed R8 solver-seam checks; snapshots retained under stage7-first-signed-riff and stage7-first-signed-plus. Compiled adaptive-icon references prove original Riff layers and the 0.9 inset in both Plus layers (stage7-compiled-icon-proof.json). Both previews and full bundle verification are still pending; stage 7 is not complete. |
| 7 Nov 2026 | 7 · Final signed bundle verification | Complete | Corrected-source PR `pr-20261107T125636Z` passed all nine gates, 208 tooling tests and Riff 1594 / Plus 1629 JVM tests with no failures/errors/skips. Both signed releases and previews pass source, package/version, payload and pinned-certificate checks (`artifacts/quality/stage7-candidate/signed-build-verification.json`). Signed release bytes: Riff 9989830; Plus 46530291. Both signed R8 solver seams pass. Source ZIP passes exact input correspondence, SHA-256 `228c4dff7fe827f03ce38dad8dab24719e5e29fa27658493e063df5612e5312e`. Candidate collect-only reports retain BLOCKED for explicitly skipped duplicate checks; the separate passing PR supplies test evidence. Installation/data-preservation trials remain stage 9. Stage 8 documents and edition-specific version-36 cards now applied; full PR gate next. |
| 7 Nov 2026 | 8 · Owner stop / handoff, 20:22 IST | Stopped; verification incomplete | Applied guarded documentation, both edition-specific version-36 WhatsNew resources and test; removed obsolete shared cards. Interrupted `pr-20261107T143956Z`, session 54549 exited 1. All 208 tooling tests and static checks pass; Riff 1594 JVM tests / 212 suites pass with zero failures/errors/skips, archived under stage8-interrupted-riff-junit. Riff release compilation was canceled; Plus did not run. Stages 0–7 complete; stage 8 needs a fresh full PR on resume. No device interaction or publication. [Handoff](handoff-0.30.0-implementation-2026-11-07.md). Do not resume without a new owner instruction. |
| 7 Nov 2026 | 8 · Resume, 22:37 IST | In progress | Owner instructed continuing. Confirmed unchanged source digest `fa3f470e693a407e7fcbfab351880f41505d6b10ee8785361e04c5853e348c15` and no task-owned verifier/collector/Gradle client. Retaining the interrupted run; a fresh full dual-edition PR lane is running. Stage 8 remains incomplete until all required checks pass. |
| 7 Nov 2026 | 8 · Resumed gate checkpoint, 23:01 IST | Riff passed; Plus running | Fresh `pr-20261107T170655Z`: 208 tooling tests and static checks pass; Riff PASS_LOCAL with 1594 tests / 212 suites, zero failures/errors/skips and release lint errors. Unsigned Riff 9973474 bytes, source `fa3f470e693a…`; payload, profile and solver seam pass. Archived stale stage-7 signed build outputs after verifying them against retained candidates, avoiding ambiguous unsigned selection. Plus is running; stage 8 is not complete. |
| 7 Nov 2026 | 8 · Full technical verification | PR and compiled cards pass; owner review pending | `pr-20261107T170655Z` PASS_LOCAL, all nine gates in both editions; 208 tooling tests, Riff 1594 / Plus 1629 JVM tests, zero failures/errors/skips. Source `fa3f470e693a…`. Actual compiled JSON in both APKs exactly matches the flavor-specific version-36 cards (`compiled-release-cards.json`). Owner document review is not claimed. Continuous authorization permits proceeding to ready verification work. |
| 7 Nov 2026 | 9 · Runtime preparation | In progress | Added EditionJourneysDeviceTest to the fixture lane for actual Automix/native-model initialization, mode selection, Plus equalizer controls and Riff fallback, with preference restoration. Corrected the packaging probe cache to match the real audio duration and current 64-second local-tail / 100-ms envelope contract: the previous four-second duration could cause legitimate re-analysis and a false preservation failure (AudioTransitionCoordinator duration check). No production audio behavior changed. Compile, full PR and runtime trials pending; no device interaction yet. |
| 8 Nov 2026 | 9 · API 36 Riff emulator lane | Riff passed; Plus running | `emulator-riff-stage9-api36-20261107-2351` PASS_LOCAL in all four gates; 73 instrumentation tests, zero failures/errors/skips, source `f0ae993d13c4…`. Both new edition journeys executed. Full Plus lane is active on the same fresh owned API 36 emulator. Runtime ABI properties advertise x86_64/arm64-v8a and libndk_translation.so; signed ARM64 upgrade/switch, API 30, full stage 9 PR, phone/listening and benchmarks remain open. |
| 8 Nov 2026 | 9 · API 36 dual-edition runtime | Both passed; floor and release trials pending | Both full API 36 lanes PASS_LOCAL, 73 instrumentation tests each, zero failures/errors/skips. New real-Android journeys confirm Riff fallback/preservation and Plus EQ/native-session initialization. Combined proof: stage9-api36-runtime-summary.json. Source f0ae993d13c4…. Gracefully stopped only the verified owned API 36 AVD; a fresh API 30 AVD is booting. Final PR, signed packaging, owner document review, phone/listening and benchmarks remain open. |
| 8 Nov 2026 | 9 · API 30 seek timing investigation | Failed attempts retained; diagnosis active | Riff full floor lane `emulator-riff-stage9-api30-20261108-0029` executed 66 tests with one assertion failure: repaired-download seek readiness median 510 ms exceeds the unchanged <500 ms requirement. Isolated rerun also failed (median 605 ms, one moving result 1007 ms). No production source or threshold change. Preserved both attempts and two-core VM logs/config; a controlled four-vCPU rerun of the same APK is running. Both API 36 lanes remain passing; API 30 is not passed and Plus floor lane has not run. |
| 8 Nov 2026 | 9 · API 30 codec-queue experiment | In progress | Four-vCPU control still failed the unchanged readiness bound (median 509 ms), so extra CPU alone does not resolve it. Retained stage9-api30-seek-four-core-01. Testing an API-30-only asynchronous codec adapter in riffAudioRenderers: Media3 defaults to async only from API 31 and officially supports enabling it from API 23. Older/supported API 31+ behavior is preserved; native proof and all fresh-source gates pending. [Media3](https://developer.android.com/media/media3/exoplayer/customization#enabling-asynchronous-buffer-queueing). |
| 8 Nov 2026 | 9 · Owner target correction | API 30 work dropped; API 31 floor restored | Owner explicitly said not to spend further time on API 30 and is not targeting Android 11. Canceled only the experimental build client (session 71468 exited 1); API 30 VM is already stopped. Reverted the unverified codec experiment byte-for-byte. App, benchmark and build identity now use minSdk 31; probe and current docs align. API 30 failures remain historical evidence, not release gates. No further API 30 investigation/testing is authorized. Continue supported-target verification, signed packaging and remaining plan work. |
| 8 Nov 2026 | 9 · Owner stop, 01:50 IST | Stopped; final gate incomplete | Owner requested handoff. Interrupted supported-floor PR `pr-20261107T193221Z`, session 43760 exited 1. 208 tooling tests/static checks pass; Riff 1594 / Plus 1629 unit tests pass with zero failures/errors/skips. Riff lint/content/APK/solver passed; Plus release checks incomplete. Preserved XML/checkpoint/logs in stage9-owner-stop-20261108-0150. Current-source source ZIP and signed API-31 probe built; probe not exercised, signed app candidates not rebuilt. API 30 stays out of scope. No emulator or task-owned build client remains. [Handoff](handoff-0.30.0-implementation-2026-11-07.md); resume only on owner instruction. |
| 8 Nov 2026 | 9 · Resume, 01:56 IST | In progress | Owner resumed work, excluding feature phase 1. Confirmed source digest `6d86f2c8154b…` unchanged and no task-owned client/emulator running; shared Kotlin daemon and global ADB left alone. |
| 8 Nov 2026 | 9 · Supported-floor PR gate | Passed | `pr-20261107T202634Z` PASS_LOCAL, all nine gates for both editions; source `6d86f2c8154b…`. Riff 1594 / Plus 1629 JVM tests, zero failures/errors/skips (JUnit timestamps inside this run); release lint zero errors in both. Interrupted `pr-20261107T193221Z` retained as not passed. |
| 8 Nov 2026 | 9 · Signed candidates | Passed | `stage9-candidate`: signed Riff/Plus releases and previews, pinned certificate. `verify-stage9-signed-build.py` against `pr-20261107T202634Z` PASS_LOCAL (`signed-build-verification.json`). Both signed R8 solver seams pass (`stage9-signed-{riff,plus}-solver.log`). Source ZIP regenerated, matches every APK fingerprint input, SHA-256 `7fce900479261741e945a27456c11a97326752d69f7963b79f2a29692180d56a`; SHA256SUMS lists all five. Windows PowerShell 5.1 treated git CRLF warnings as fatal in the build script, so the invocation set process-only `GIT_CONFIG_PARAMETERS='core.safecrlf=false'`; no script or repo setting changed. Probe rebuilt against the signed Riff release in `stage9-probe-signed` (SHA-256 `3444638c…`). |
| 8 Nov 2026 | 9 · Signed packaging trial | Blocked by emulator host crash | Two fresh-install attempts (`packaging-20261107T213428Z`, `packaging-20261107T214050Z`) passed source-archive, release-identity and checksums; both lost the device during the seed probe. Windows logged `qemu-system-x86_64-headless.exe` APPCRASH 0xc0000005 (same fault bucket, offset ending `…0307`) each time, and once more in a diagnosis repro on the second AVD. Not memory: the second run started with ~3.8 GiB free after stopping the task's own Gradle daemon. Repro logcat (`stage9-seed-crash-diagnosis/`) shows no guest error; the VM dies ~2 s after signed 0.21.0 starts, loads arm64 ONNX via libndk_translation and starts its solver process. The seed itself completed (marker written). Leading hypothesis, unproven: translated ARM64 release code on the x86_64 WHPX emulator triggers a host emulator fault. No packaging switch trial has ever passed on this laptop. Both AVD ownership records updated; neither restarted; nothing uninstalled. The emulator helper was made Windows PowerShell 5.1 compatible (record parsing, avdmanager stderr). Owner route decision needed. |
| 8 Nov 2026 | 9 · Signed packaging trial, host GPU | Passed | Owner chose one emulator change. Under WHPX guest code runs natively, so an unknown-module fault inside qemu pointed to host-side JIT rendering (SwiftShader, `-gpu software`) rather than ARM translation; the guest's last lines were the solver process initializing graphics. Added a `-Gpu` option to the helper (default unchanged `software`) and started a third fresh owned AVD, `riff030-stage9-api36-410d894d84dc`, with `-gpu host` (Intel Iris Xe, Vulkan host mode); nothing else changed. `packaging-20261108T012804Z` PASS_LOCAL in all five gates: signed 0.21.0 → Plus → Riff → Plus, source `6d86f2c8154b…`, pinned certificate. The seed, upgrade-plus, switch-riff and switch-plus states are byte-identical (preferences, Encore history/totals/hourly, saved queue, download bytes and metadata, beat analysis, rendered Encore widget); firstInstallTime was unchanged after every install. No qemu crash; AVD stopped gracefully after console-name check. Conclusion: the earlier crashes were the emulator's software renderer on this laptop, not Riff. Use `-Gpu host` for signed ARM64 packaging trials here. Emulator evidence does not replace phone listening or benchmarks. |
| 8 Nov 2026 | 9 · Emulator lanes on current source | Passed | The earlier API 36 emulator passes (73 tests each) predated the minSdk 31 change, so both were re-run on owner request on the same owned AVD `riff030-stage9-api36-410d894d84dc` (`-gpu host`, network off). Riff `emulator-20261108T024147Z` and Plus `emulator-20261108T025819Z` both PASS_LOCAL in all four gates: 73 tests, 0 failures, 0 errors, 0 skipped, source `6d86f2c8154b…`, version 0.30.0 (36), API 36. The first Riff attempt was stopped during the build because it pointed at the wrong Gradle home; its device lock (dead PID, no other lane running) was removed before the rerun. AVD stopped gracefully after console-name check. Emulator evidence does not replace the phone device lane, listening or benchmarks. |
| 8 Nov 2026 | 9 · Phone device lane, side by side | Passed; in-place chain on the phone not run | Owner chose not to disturb the phone's real Riff 0.21.0 (`com.musicglass.personal`, first installed 31 Oct, last updated 6 Nov 08:29), so the 0.21.0 → Riff+ → Riff → Riff+ chain was not run on the phone; it stands on the emulator packaging trial `packaging-20261108T012804Z`. The fixture suite ran as `com.musicglass.personal.debug` on the vivo I2217 (tier R, API 36). A first online Riff run `device-20261108T032655Z` failed only on three offline-only skips (63 passed). With the owner's airplane mode on: Riff `device-20261108T033654Z` and Plus `device-20261108T034532Z` PASS_LOCAL, 66 tests each, zero failures/errors/skips, source `6d86f2c8154b…`. Afterwards Riff 0.21.0 and the rc20 preview showed unchanged versions and update times. Benchmarks and listening remain open. |
| 8 Nov 2026 | 8 · Owner document review | Done | The owner read the section 12 documents and accepted them unchanged ("its perfect"). Stage 8 is complete. |
| 8 Nov 2026 | 9 · Benchmark lane, one run per edition | Run; FAIL on the known budgets plus a harness wait; owner decision needed | Phone I2217, tier R, thermal 1, real Riff 0.21.0 untouched (separate `.benchmark` / `.macrobenchmark` packages). Riff `benchmark-20261108T042938Z`, Plus `benchmark-20261108T053141Z`; 19 of 20 tests each. Both: every action p95 ≤ 64 ms (budget 150), 2,000-entry queue 362 / 352 ms, local first audio 272 / 294 ms, online 275 / 267 ms: PASS. Missed frames at 60 Hz: Home 9.6 / 9.7 %, collection 8.3 / 7.6 %, player and queue 12.7 / 13.0 % (budget 2 %; Q18-030, accepted by the owner for 0.20.0 at 13.4 / 15.6 / 14.8 %). Cold start to usable shell p95: Plus 2,011 ms (budget 2,000; 0.20.0 was 2,132 ms); Riff 32,958 ms, from three stalled iterations of 20 (32–39 s, first frame stalled too) while the other 17 were 1.9–2.1 s (p50 1,988 ms), and its no-profile run p95 2,137 ms. In both, `MemoryCheck` failed on the harness wait `"playback_toggle" did not appear` (Q18-097 pattern), so no memory reading. No app source changed. |
| 8 Nov 2026 | 9 · Benchmark decision | Accepted by the owner | The owner accepted both benchmark runs as is ("Accept as is"), as for 0.20.0 on 31 Oct; no rerun. Still open in stage 9: the owner's listening decision. |
| 8 Nov 2026 | 9 · Owner listening on the phone | Passed | The owner listened on the I2217 in the side-by-side debug app (`com.musicglass.personal.debug`, source `6d86f2c8154b…`), leaving the real Riff 0.21.0 untouched: first Riff+ (crossfade, booster, normalization, silence skip, Automix with the bass swap, equalizer), then Riff installed over it with `install -r` (crossfade, Smart, booster, normalization, silence skip; no Automix, equalizer or Stats for nerds). Owner: Riff+ done with no problem reported; "Riff sounds fine". |
| 8 Nov 2026 | 9 · Verification | Complete | Every stage 9 item is closed on source `6d86f2c8154b…`, the same as the passing PR lane `pr-20261107T202634Z`: signed candidates and solver seams; packaging trial (emulator, `-gpu host`); emulator lanes of both editions (73/73); phone device lanes of both editions side by side (66/66); one benchmark run each (accepted by the owner); owner listening. Deviation, by the owner's choice: the in-place 0.21.0 → Riff+ → Riff → Riff+ chain was proven on the emulator, not on the phone, to keep the real Riff untouched. Next: the 24 feature phases, starting with phase 1, on the owner's go-ahead. Stage 10 waits for all features and the owner's authorization. |
