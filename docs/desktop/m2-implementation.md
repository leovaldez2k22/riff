# Desktop M2 implementation — Multi-pane workspace, Mini-Player and desktop ergonomics

The owner authorized implementation on 1–2 November 2026. The desktop module remains an additive opt-in exception to the Android-only module rule (`:desktop` under `-PriffDesktop=true`); `:app` and `:benchmark` retain their boundaries.

## Implemented in M2

1. **Responsive 3-Pane Spatial Architecture**:
   - Implemented `WorkspaceLayoutCalculator` based on Section 3.1 of `reports/Riff Windows desktop architecture.md`:
     - `< 1024dp`: 80dp compact navigation rail.
     - `1024–1439dp`: 224dp navigation sidebar, main stage minimum 480dp, collapsible inspector 360dp.
     - `1440–1919dp`: 224dp navigation sidebar, expanded main stage minimum 640dp, 360dp inspector.
     - `1920dp+`: Main stage content width bounded to 1280dp with distributed horizontal margins.
   - Dynamic protection: Auto-collapses the right-hand inspector before squeezing the main stage below 480dp.

2. **Persistent Playback Dock (88dp)**:
   - Fixed 88dp height across the bottom of the window (`DesktopDock.kt`).
   - Left: 48dp album artwork with 12dp radius, track title (20sp bold, white, tracking -0.35sp), artist (17sp muted), like heart button.
   - Center: Riff's signature play button (52dp/64dp), previous/next (44dp/56dp), shuffle and repeat toggles, timeline seek slider with elapsed/duration timestamps in tabular numerals.
   - Right: Volume slider with speaker icon, Queue inspector toggle, Lyrics inspector toggle, Mini-Player mode toggle.

3. **Workspace View Stages**:
   - `HomeStage`: Greeting header, speed dial cards, "Listen again" album cards, "Quick picks" song rows.
   - `SearchStage`: Top search input bar with `Ctrl+K` shortcut indicator, filter category chips, real-time matching query results.
   - `LibraryStage`: Category tabs ("Playlists", "Songs", "Albums", "Artists"), library track list with direct play actions.
   - `SettingsStage`: Section panels for "Look and feel" (glass quality mode: Full, Reduced, Frost, Solid; reduced motion toggle with `GlassSwitch`) and "About" system information.
   - `EncoreStage`: Listening statistics (hours, tracks played, top artists) and monthly frequency bars.

4. **Right-Hand Collapsible Inspector**:
   - 360dp dockable panel (`InspectorPane.kt`) with mutual tab switcher:
     - `Queue`: Now Playing banner, upcoming songs list, up/down reordering, remove actions.
     - `Lyrics`: Full live karaoke rendering synced to the playback clock with word-by-word sweeps, held word glows, ambient line defocusing, and tap-to-seek.

5. **Dedicated Mini-Player Window Mode**:
   - Frameless mini window (`MiniPlayer.kt`) with two modes:
     - `Compact`: 360×160 outer (360×120 content) with 48dp artwork, title/artist, previous/play-pause/next transport, and restore action.
     - `Expanded`: 360×460 outer (360×420 content) with album cover mesh (180dp), title/artist, live lyric preview, transport, and compact/restore buttons.
   - State continuity: Shares the central `DesktopHost` coordinator—zero clock or audio disruption when switching between main workspace and Mini-Player.

6. **Desktop Keyboard Ergonomics**:
   - `Space`: Toggle play/pause (when outside text entry).
   - `Ctrl+K`: Jump to Search and focus input field.
   - `Ctrl+M`: Toggle Mini-Player mode.
   - `Escape`: Close open Inspector pane or dialogs.
   - `Ctrl+Q`: Orderly exit application.
