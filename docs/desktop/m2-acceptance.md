# M2 Acceptance Evidence — Multi-Pane Workspace, Mini-Player & Desktop Ergonomics

Status: Substantially Complete on Local Hardware; Packaging and Automated Verification Passing.

## 1. Implemented & Integrated Deliverables

1. **Responsive 3-Pane Spatial Layout & Draggable Dividers**:
   - Evaluated breakpoints from 800×600 to 1920×1080 (and ultrawide clamped at 1280dp center).
   - Sidebar divider and inspector divider implemented in `WorkspaceDivider.kt`:
     - Mouse drag pointer handling with resize cursor (`E_RESIZE_CURSOR`).
     - Keyboard adjustments: Left/Right arrow keys adjust by 8dp (24dp with Shift).
     - Reset-to-default: Double-click, Enter, or Space restores standard widths (224dp sidebar, 360dp inspector).
     - Focus ring indicator (`border(1.dp, pink)`) on keyboard focus.
     - Screen reader semantics: `Role.Button` with current width state description.
   - Divider hit areas (8dp) incorporated into stage width calculations.
   - Enforced 640dp main-stage minimum threshold at wider breakpoints (>= 1440dp); 480dp protected at < 1440dp.
   - Narrow-width fallback: when docked inspector would squeeze the stage below threshold, `isNarrowInspectorOverlay` floating glass sheet activates, keeping queue and lyrics 100% accessible on 800×600 and 1024×768.

2. **Complete Screen Containers (`WorkspaceStages.kt`)**:
   - `HomeStage`: Greeting, Quick picks, Listen again albums, Featured artists shelf, Recent tracks.
   - `SearchStage`: Input bar (Ctrl+K focusable), category chips (All, Songs, Artists, Albums), empty state view when no matches found.
   - `LibraryStage`: Category tabs (Playlists, Songs, Albums, Artists, Downloads) dynamically switch between playlists grid, songs list, album covers, artist circular avatars, and offline downloaded items.
   - `PodcastsStage`: Show cards with publisher, episode counts, and descriptions.
   - `RadioStage`: Algorithmic stations and mood frequencies with seed track launching.
   - `AlbumDetailStage`: Header banner with album art, title, artist, year, track count, "Play Album" action, and numbered track list.
   - `ArtistDetailStage`: Hero banner with artist avatar, monthly listener count, top tracks shelf, discography shelf.
   - `PlaylistDetailStage`: Playlist artwork, title, description, track count, "Play All", track list.
   - `FocusedPlayerStage`: Immersive full-window stage with bounded artwork (no overflow), large typography, transport controls, and synchronized lyrics.
   - `SettingsStage`: Mood filter selector (Energize, Focus, Chill, Uplift, Sleep), Glass Quality switcher (Full, Reduced, Frost, Solid), Reduced Motion switch, Audio DSP section (Equalizer Presets, Volume Boost with limiter).
   - `EncoreStage`: Listening hours, track count, top artists, top tracks monthly bars.
   - `StageStateView`: Reusable empty, offline, and error states.

3. **Playback Dock & Ergonomics (`DesktopDock.kt`)**:
   - Click-to-seek and drag-to-seek with `detectTapGestures` and `detectHorizontalDragGestures`.
   - Seek keyboard support: Left/Right arrow keys seek ±5s (±15s with Shift), Home (0s), End.
   - Scoped volume wheel support: mouse scroll adjusts volume ±4% per notch only when hovering volume slider.
   - Volume keyboard support: Left/Down (-5%), Right/Up (+5%), Mute toggle (`M`).
   - Accessible slider semantics: `ProgressBarRangeInfo` and dynamic content descriptions on seek and volume sliders.
   - Responsive dock layout at 800dp width: `widthIn(min = 180.dp, max = 280.dp)` left block and `widthIn(min = 180.dp, max = 260.dp)` right block keep transport buttons and metadata fully reachable.
   - Clicking album art or track titles routes to `FocusedPlayerStage`.

4. **Queue & Lyrics Interactions (`InspectorPane.kt`)**:
   - Pointer vertical drag reordering implemented on grip handle using `detectVerticalDragGestures`, computing target index from drag offset and dispatching `MoveQueueEntryToPosition(entry.queueId, targetIndex, queueRevision)` upon release.
   - Stable queue-entry identities (`QueueEntry`) supporting duplicate song occurrences and revision-safe movements.
   - Keyboard reordering: `Alt+Up` / `Alt+Down` keyboard reordering and quick move action buttons.
   - Scoped multiselect support and deletion with Undo notification banner (`UndoQueueAction`).
   - Themed glass context menu popup on secondary click (`awaitPointerEventScope`) or `Shift+F10` ("Play Now", "Go to Album", "Go to Artist", "Remove from Queue").
   - Synced Lyrics: auto-scroll using `animateScrollToItem()`. User manual scroll suspends auto-follow and surfaces floating "Resume follow" pill.
   - Dedicated timestamp seek buttons prevent accidental seeking during text selection.

5. **Keyboard & Focus Handling (`Main.kt`)**:
   - Spacebar handler in `onKeyEvent` allows typing spaces in Search input without toggling playback.
   - `Ctrl+K`: reliable sequencing navigates to Search and schedules focus request once text field is composed.
   - `Escape`: hierarchically dismisses notification toast, floating inspector overlay, and navigates back from detail screens.
   - External file drop: AWT `DropTarget` attached to window accepts dropped `.wav`, `.mp3`, `.flac`, `.m4a` files with validation and accessible toast notification.
   - `Ctrl+M`: toggles Mini-Player.
   - `Ctrl+Q`: clean application exit with state persistence.

6. **Window State Persistence (`DesktopWindowStateManager.kt`)**:
   - Durable JSON persistence to `~/.riff/desktop-window-state.json`.
   - Saves/restores window position, size, maximized state, always-on-top, mode, sidebar width, and inspector width.
   - Clamps coordinates to primary monitor work-area upon startup, recovering off-screen windows if an external monitor was disconnected.

7. **Mini-Player Window Modes (`MiniPlayer.kt`)**:
   - Compact (360×160) and Expanded (360×460) layouts fitting their respective content regions.
   - Explicit always-on-top toggle (`isAlwaysOnTop` pinned button wired to `window.alwaysOnTop`).
   - Expanded mode includes seek slider with timestamps, volume, current lyric preview line, and Restore Main button.

---

## 2. Automated Check Evidence

1. **Desktop Unit Test Suite**:
   - `WorkspaceLayoutTest`: 7 comprehensive tests passing:
     - `layoutProtectsMainStageMinimumWidth`: PASS
     - `hostHandlesPlaybackAndClockAdvancement`: PASS
     - `hostQueueReorderingAndTrackAdvancement`: PASS
     - `hostQueueRemovalAndUndo`: PASS
     - `hostRouteAndWindowModeTransitions`: PASS
     - `hostPointerDragMoveQueueEntryToPosition`: PASS
     - `hostPlayCollectionAndNavigateBack`: PASS
     - `hostFileDropHandlingAndNotifications`: PASS
     - `windowStateManagerSavesAndRestoresGeometry`: PASS
   - Total Desktop Test Suite: **57 tests, 0 failures, 0 skipped**.
2. **Packaged Smoke Diagnostic Benchmark (`scripts/quality/desktop_smoke.py`)**:
   - Executed against `desktop/build/compose/binaries/main/app/Riff Desktop Preview/Riff Desktop Preview.exe`.
   - Scale 1.0 warm first frame: ~2.4s – 2.9s (exitCode: 0, markerCount: 1).
   - Scale 1.25 warm first frame: ~2.7s (exitCode: 0, markerCount: 1).
   - Descendant working set: 274 MB – 281 MB (active JVM heap: 13.5 MB – 19.8 MB).
   - All 6 runs passed with exit code 0 (`artifacts/quality/desktop/smoke-test.json`).
3. **PR Quality Lane (`python scripts/quality/verify.py --lane pr`)**:
   - Result: `# pr lane: PASS_LOCAL` (all 9 checks passed: script-tests, static, build, artifactIdentity, jvm, lint, release-content, apk, solver-seam).

---

## 3. Manual Checks & Observations

- Verified draggable dividers in running desktop UI with mouse drag and Left/Right keyboard arrows.
- Verified Search input accepts spaces without toggling playback.
- Verified Library category switching dynamically updates content between Playlists, Songs, Albums, Artists, and Downloads.
- Verified Mini-Player always-on-top pinning and Compact/Expanded transitions.
- Verified NVDA screen reader speech over Java Access Bridge across controls.

---

## 4. Outstanding Gates for Final Milestone Closure

- **[Other hardware]**: Physical mixed-DPI display moves across multiple monitors, display disconnection, and 120Hz high-refresh presentation verification.
- Sustained physical listening test during continuous stage switching and library filtering.
