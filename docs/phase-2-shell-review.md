# Phase 2 shell review

Updated 2026-09-27. **M2 passed for the user's gesture-navigation phone setup.** The user explicitly excluded a three-button layout check because they do not use that mode. The fixture-backed shell and core M2 phone journey passed.

## What is in the build

- Home, Samples, Search, and Library use separate tab state and per-tab route stacks. Back closes the preview sheet or detail first, then returns toward Home. Search query, Library filter, and Home scroll position survive tab changes. Saveable shell state also covers activity recreation.
- A floating mini-player sits above the navigation capsule, with a separate Search circle on the normal-width layout. At a narrow window or 130% text it becomes one labeled four-item capsule. Library can hide the fixture mini-player to exercise the no-queue layout.
- Cards, album tiles, track rows, filter chips, a sheet, and snackbars have a component gallery in Library. The album page and Samples screen are explicit fixtures. Local artwork drawables load through Coil. No action claims real media playback.
- The bottom shapes use a separate AGSL effect over the live Compose scene. Centers remain unfrosted; a dark translucent control surface and a bottom fade make text readable over moving bright and dark content. Controls and semantics are drawn in the foreground. The original Glass Lab is still reachable from Library.
- The scene stays in the primary Compose tree. An early nested ComposeView rendered visually but disappeared from the Android accessibility tree; the final graphics-layer implementation exposes all screen content and controls to UI Automator and TalkBack semantics.

## Reference use

[R02](../references/R02-mini-player-and-navigation.png) guided the mini-player above a separate navigation capsule and detached Search circle. The app keeps the agreed dark theme, pink selection, and clear center, while adding a stronger dark tint for readable controls. [R03](../references/R03-album-detail.png) informed the artwork-led album fixture. R01's full player and R04's lyrics remain later-phase work.

## Device result

The [final M02 device report](../artifacts/device-checks/M02/20260927-184520/report.md) ties captures and UI XML to APK SHA-256 `4366EDB4AEE1A16B53FE88069BEDCB5CBE4C982C167417E4A23E942184E0BD17`. The iQOO I2217 ran Android 16/API 36 at 1080×2400, 440 dpi. Build and lint passed with 0 errors and 8 non-blocking version/resource warnings.

The semantic-selector script reached all four tabs, searched with the keyboard open, verified a result stayed visible, preserved the Search query and Library filter across tab changes, toggled the mini-player, opened/dismissed the gallery sheet, returned from Glass Lab and album detail, and reached the last Home item above the bottom controls. Home footer bounds were identical after a tab round-trip. Captures include dark and bright fixture artwork. UI Automator shows selected tab descriptions and 48 dp or larger principal control hit regions. A direct spoken TalkBack session remains for the later accessibility hardening phase.

The separate layout script captured a 130% font scale and 900×2000 display override; both showed the labeled four-item navigation. It temporarily enabled Android's three-button overlay and saw `navigation_mode=0` with app chrome present, then restored the original 1.0 font, 1080×2400 display, and gesture mode 2. The OEM's `navigationBars` frame remained `[0,0][0,0]` under that overlay. The user confirmed they do not use button navigation and waived a manual three-button check; it is not claimed as verified.

## Phase boundary

This phase contains no music provider, account, audio service, queue playback, video Samples, or full player. The separate YouTube Music feasibility gate is still open. Phase 3 begins only after user approval and must resolve the supported playback path before promising streaming behavior.
