# Glass controls

Feature 2 of the [plan](../feasibility-glass-font-lyrics-artwork-2026-10-27.md) puts glass on the controls layer: bars, buttons, sliders, switches, menus and sheets. Rows and artwork stay solid. This table lists each control, what it is now, and what reads behind it, so nothing is missed. Status is as of 28 October 2026 (stage 6 finished).

## What each piece of glass reads

A piece of glass draws a recorded backdrop through the lens (`RiffGlass.kt`). Which backdrop matters: a piece can't read a layer that contains itself.

| Backdrop | Recorded by | Read by |
|---|---|---|
| The page (`LocalRiffBackdrop`) | `GlassScene` in `ShellChrome.kt` | what sits above the page: the shell's chrome, the chip faces (`GlassCategoryLabels`), snack bars |
| The screen's background (`LocalPageBackdrop`) | `AmbientBackdrop` in `ShellScene.kt`: the artwork-coloured gradient | glass inside a page: back buttons, Settings compartments and cards, the equalizer's cards and mode switcher |
| The player's backdrop | `PlayerBackdrop.kt`, which paints a copy of itself for the glass: the cover's colours and blurred cover | the player's circles and output pill, the queue's buttons and toggles |
| The lyrics page's background | `LyricsPanel.kt`: its gradient and faint cover | the lyrics header buttons, script pill and play button |
| The sign-in backdrop (`LocalSignInBackdrop`) | `SignInBackdrop` in `AccountScreen.kt` | the sign-in panels and bars (`signInGlass`) |
| The app under a popup (`FrostedBackdrop`) | the app root in `RiffApp.kt`, in screen coordinates | popups and dialogs (`FrostedPopup.kt`) |
| A control's own track | `rememberRiffBackdrop` in the control | slider and switch thumbs (`GlassControls.kt`), the equalizer's mode pill (its words) |
| A test pattern | `GlassGalleryScreen` | the gallery, debug builds only |

## Controls

| Area | Control | File | Now | Role |
|---|---|---|---|---|
| Shell | Tab bar | `ShellChrome.kt` | Glass | Bar |
| Shell | Search button | `ShellChrome.kt` | Glass | Button |
| Shell | Drag lens over the tabs | `ShellChrome.kt` | Glass | Lens |
| Shell | Mini player | `ShellChrome.kt` | Glass | Bar |
| Shell | Category and filter chips, Library shortcut tiles | `GlassCategoryChip.kt` | Glass faces drawn above the page | Button |
| Shell | Back buttons | `ScreenParts.kt` | Glass circle; bare arrow over a picture | Button |
| Player | Like and more circles | `NowPlaying.kt` | Glass over the cover | Button |
| Player | Output pill | `AudioOutputControls.kt` | Glass over the cover | Button |
| Player | Seek and volume bars | `ThinSlider.kt` | Glass thumb while held, none at rest | Slider |
| Player | Play, skip, shuffle, repeat | `NowPlaying.kt` | Plain icons (Feature 34) | — |
| Player | Queue's like, more and close buttons; shuffle, repeat and radio toggles | `InlineQueue.kt` | Glass over the cover | Button |
| Lyrics | Back, timing and share-cancel buttons, script pill, play button | `LyricsPanel.kt` | Glass over the page's background | Button |
| Menus | Song menus | `SongActionMenu.kt` | Glass | Menu |
| Menus | Player menu | `PlayerActionMenu.kt` | Glass | Menu |
| Menus | Dialogs | `FrostedPopup.kt` | Glass | Menu |
| Menus | Snack bars | `RiffApp.kt` | Glass over the page, dark tint | Menu |
| Sign-in | Panels, bars, glass buttons | `SignInDesign.kt`, `SignInScreens.kt` | Glass over the sign-in backdrop | Card |
| Settings | Compartments on the main page | `SettingsScreen.kt` | Glass over the screen's background | Card |
| Settings | Section cards on the subpages (`SettingsSection`: Privacy and data, Diagnostics, backups, earlier data) | `SettingsScreen.kt` | Glass over the screen's background | Card |
| Settings | Switches | `SettingsScreen.kt` | Glass thumb on a pink or grey track | Slider |
| Settings | Sliders (boost, crossfade, glass, speed, budget, balance) | `CapsuleSlider.kt` | White thumb, glass while held | Slider |
| Equalizer | Cards | `EqualizerScreen.kt` | Glass over the screen's background | Card |
| Equalizer | Dynamic / Manual choice | `EqualizerScreen.kt` | Glass track, glass pill sliding to the chosen side | Card, Button |
| Equalizer | Preset chips | `EqualizerScreen.kt` | Material filter chips | not glass: they sit inside a glass card |
| Lists | Rows, artwork | home, library, collection, artist | Solid, by Apple's rule | — |

## High contrast and the quality levels

High contrast (`Solid`) draws every piece as an opaque surface with a 1 dp outline; thumbs stay white. `Frost` (Android 12, or a phone about to throttle) keeps the blur and tint without the lens, and leaves the drag lens and the thumbs clear. `Reduced` (power saver, or a warm phone) drops only the colour fringe.

## Checked

- API 36 emulator: the tab bar, search button, mini player, drag lens, chips, back buttons, player circles and pill, the player menu, the Settings compartments, switches and sliders (held and at rest), the equalizer, and the gallery.
- API 33 emulator: the lens and fringe as on 36; chips; the frosted dialog. Two ANRs in the playback and solver services right after a cold boot (load 33), none after it settled.
- API 31 emulator: the frost path. The drag lens stays clear and the tab under it grows (the pre-33 scale), the dialog and Settings cards frost, no crash.
- Over a smooth gradient, glass cards change little; the lower cards sit where the gradient has faded to black and show mostly their rim. A stronger Settings background is a later tuning choice.
- Not yet: the phone, the emulator screenshot lane, and the 4.5:1 text contrast check over the lightest covers.
