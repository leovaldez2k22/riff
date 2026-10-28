# Glass controls

Feature 2 of the [plan](../feasibility-glass-font-lyrics-artwork-2026-10-27.md) puts glass on the controls layer: bars, buttons, sliders, switches, menus and sheets. Rows and artwork stay solid. This table lists each control, what it is now, and what reads behind it, so nothing is missed. Status is as of 28 October 2026 (stages 5 and 6).

## What each piece of glass reads

A piece of glass draws a recorded backdrop through the lens (`RiffGlass.kt`). Which backdrop matters: a piece can't read a layer that contains itself.

| Backdrop | Recorded by | Read by |
|---|---|---|
| The page (`LocalRiffBackdrop`) | `GlassScene` in `ShellChrome.kt` | the shell's chrome, which sits outside the page |
| The screen's background (`LocalPageBackdrop`) | `AmbientBackdrop` in `ShellScene.kt`: the artwork-coloured gradient | glass inside a page (the Settings compartments and cards) |
| The player's backdrop | `PlayerBackdrop` in `NowPlaying.kt`: the blurred cover | the player's circles and output pill |
| The app under a popup (`FrostedBackdrop`) | the app root in `RiffApp.kt`, in screen coordinates | popups and dialogs (`FrostedPopup.kt`) |
| A control's own track | `rememberRiffBackdrop` in the control | slider and switch thumbs (`GlassControls.kt`) |
| A test pattern | `GlassGalleryScreen` | the gallery, debug builds only |

## Controls

| Area | Control | File | Now | Role |
|---|---|---|---|---|
| Shell | Tab bar | `ShellChrome.kt` | Glass | Bar |
| Shell | Search button | `ShellChrome.kt` | Glass | Button |
| Shell | Drag lens over the tabs | `ShellChrome.kt` | Glass | Lens |
| Shell | Mini player | `ShellChrome.kt` | Glass | Bar |
| Shell | Category and filter chips | `GlassCategoryChip.kt` | Old full-screen shader | — |
| Shell | Back headings, top bars | `ScreenParts.kt` | Solid | to do |
| Player | Like and more circles | `NowPlaying.kt` | Glass over the cover | Button |
| Player | Output pill | `AudioOutputControls.kt` | Glass over the cover | Button |
| Player | Seek and volume bars | `ThinSlider.kt` | Glass thumb while held, none at rest | Slider |
| Player | Play, skip, shuffle, repeat | `NowPlaying.kt` | Plain icons (Feature 34) | — |
| Player | Queue sheet, lyrics header | `PlayerPresentation.kt`, `LyricsPanel.kt` | Solid | to do |
| Menus | Song menus | `SongActionMenu.kt` | Glass | Menu |
| Menus | Player menu | `PlayerActionMenu.kt` | Glass | Menu |
| Menus | Dialogs | `FrostedPopup.kt` | Glass | Menu |
| Menus | Snack bars, sign-in sheets | `RiffApp.kt`, `SignInDesign.kt` | Solid | to do |
| Settings | Compartments on the main page | `SettingsScreen.kt` | Glass over the screen's background | Card |
| Settings | Section cards on the subpages (`SettingsSection`: Privacy and data, Diagnostics, backups, earlier data) | `SettingsScreen.kt` | Glass over the screen's background | Card |
| Settings | Switches | `SettingsScreen.kt` | Glass thumb on a pink or grey track | Slider |
| Settings | Sliders (boost, crossfade, glass, speed, budget, balance) | `CapsuleSlider.kt` | White thumb, glass while held | Slider |
| Equalizer | Dynamic / Manual choice | `EqualizerScreen.kt` | Pill pair | to do |
| Lists | Rows, artwork | home, library, collection, artist | Solid, by Apple's rule | — |

## High contrast and the quality levels

High contrast (`Solid`) draws every piece as an opaque surface with a 1 dp outline; thumbs stay white. `Frost` (Android 12, or a phone about to throttle) keeps the blur and tint without the lens, and leaves the drag lens and the thumbs clear. `Reduced` (power saver, or a warm phone) drops only the colour fringe.

## Checked

- API 36 emulator: the tab bar, search button, mini player, drag lens, player circles and pill, the player menu, the Settings compartments, switches and sliders (held and at rest), and the gallery.
- Over a smooth gradient, glass cards change little; the lower cards sit where the gradient has faded to black and show mostly their rim. A stronger Settings background is a later tuning choice.
- Not yet: API 31 and 33 emulators, the phone, and the 4.5:1 text contrast check over the lightest covers.
