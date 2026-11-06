# M5 Acceptance Evidence — Windows Integrations (SMTC, Tray, Power, Shortcuts, Presence)

Status: Substantially Complete on Local Hardware; Native Adapters & Automated Verification Passing.

## 1. Implemented & Integrated Deliverables

1. **Windows SMTC & Media Transport Commands (`WindowsSMTC.kt`)**:
   - Hardware media command routing (`APPCOMMAND_MEDIA_PLAY_PAUSE`, `NEXTTRACK`, `PREVTRACK`, etc.).
   - 150ms debounce threshold preventing duplicate key events.
   - Synchronized with real M3 audio worker playback state and timeline updates.
   - Survives Compose window disposal and runs continuously when minimized to tray.

2. **Windows System Tray (`WindowsTray.kt`)**:
   - High-fidelity tray icon with popup menu (Play/Pause, Next, Previous, Restore, Quit).
   - Single-click restore handler on `BUTTON1`.
   - Explorer-restart recovery handler (`onExplorerRestarted`) to re-create the tray icon if `explorer.exe` restarts.

3. **Power Management (`PowerManagement.kt`)**:
   - Win32 `SetThreadExecutionState` with `ES_CONTINUOUS | ES_SYSTEM_REQUIRED` preventing OS sleep during active playback while allowing displays to power down.
   - Clean restoration to `ES_CONTINUOUS` upon pause or shutdown.

4. **Global Shortcuts (`GlobalShortcuts.kt`)**:
   - Win32 `RegisterHotKey` / `UnregisterHotKey` with conflict detection.
   - Standard hardware media keys registered with fail-safe error recovery.

5. **Discord Rich Presence (`DiscordPresence.kt`)**:
   - Opt-in named pipe IPC (`\\.\pipe\discord-ipc-*`).
   - Non-blocking asynchronous update dispatch; fail-safe recovery when Discord is absent or disconnected.

6. **Desktop OS Coordinator (`DesktopOSCoordinator.kt`)**:
   - Unified bridge coordinating SMTC, System Tray, Power, Hotkeys, and Presence with `DesktopHost` and `DesktopAudioBridge`.

## 2. Automated Checks

- **Desktop Unit Tests (`WindowsIntegrationContractTest`)**:
  - `smtcDeduplicatesMediaKeysWithinDebounceWindow`: PASS
  - `smtcUpdatesMetadataAndTimelineAccurately`: PASS
  - `powerAdapterTogglesSystemSleepLock`: PASS
  - `globalShortcutsRegistersAndDispatches`: PASS
  - `discordPresenceHandlesOptOutAndMissingPipeGracefully`: PASS
  - `osCoordinatorOrchestratesStateTransitions`: PASS
  - Total in class: **6 tests, 0 failures**.
- **Desktop Total Test Suite**: **57 passing unit tests, 0 failures, 0 skipped**.

## 3. Manual Checks & Observations

- Verified System Tray menu initialization and window restoration on click.
- Verified absence of crashes when Discord is disconnected or absent.
- Verified power lock state toggling in response to play/pause state transitions.

## 4. Outstanding Hardware Gates

- **[Other hardware]** Multi-monitor mixed-DPI taskbar repositioning and physical sleep/standby cycle verification.
