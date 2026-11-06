# Desktop M5 implementation — Windows integrations (SMTC, Tray, Power, Shortcuts, Presence)

The owner authorized implementation on 1–2 November 2026. The desktop module remains an additive opt-in exception to the Android-only module rule (`:desktop` under `-PriffDesktop=true`); Android `:app` and `:benchmark` retain their boundaries.

## Implemented in M5

1. **Windows SMTC & Media Key Adapter (`WindowsSMTC.kt`)**:
   - Conforms to Section 5.3 of `reports/Riff Windows desktop architecture.md`.
   - Hardware media command router (`APPCOMMAND_MEDIA_PLAY_PAUSE`, `APPCOMMAND_MEDIA_NEXTTRACK`, `APPCOMMAND_MEDIA_PREVIOUSTRACK`, `APPCOMMAND_MEDIA_STOP`, etc.).
   - Hardware key debounce threshold (150ms window) eliminates double-toggling across main, mini-player, and tray states.
   - Decoupled from visible Compose window lifetime: retains state across window minimization or disposal.
   - Publishes active track metadata (`title`, `artist`, `album`, `durationMs`), playback status, and timeline seek state.

2. **Windows System Notification Area Tray (`WindowsTray.kt`)**:
   - Native notification area integration via `SystemTray` / `TrayIcon`.
   - Generates anti-aliased 32×32 high-DPI Riff glass wave tray icon.
   - Native context menu with quick transport actions: `Restore Riff`, `Play / Pause` (dynamically updated label), `Next Track`, `Previous Track`, and `Quit`.
   - Left-click single tap restores main workspace window.
   - Dynamic tooltip updates displaying `Riff - [Track Title] - [Artist]`.

3. **Power Management & Sleep Prevention (`PowerManagement.kt`)**:
   - Win32 `SetThreadExecutionState` integration via Kernel32.
   - Engages `ES_CONTINUOUS | ES_SYSTEM_REQUIRED` on playback start to prevent system standby during listening sessions.
   - Explicitly omits `ES_DISPLAY_REQUIRED`: allows screens to turn off and save power while background audio continues uninterrupted.
   - Automatically releases execution state back to `ES_CONTINUOUS` upon pause, stop, or application shutdown.

4. **Global Media Shortcuts & Collision Handling (`GlobalShortcuts.kt`)**:
   - Win32 `RegisterHotKey` / `UnregisterHotKey` via User32 with `MOD_NOREPEAT`.
   - Supports hardware media keys (`VK_MEDIA_PLAY_PAUSE`, `VK_MEDIA_NEXT_TRACK`, `VK_MEDIA_PREV_TRACK`, `VK_MEDIA_STOP`).
   - Detects OS shortcut registration conflicts and reports error codes cleanly without crash.
   - Guaranteed cleanup of all registered hotkeys on process termination.

5. **Discord Rich Presence (`DiscordPresence.kt`)**:
   - Connects via Windows Discord IPC named pipes (`\\.\pipe\discord-ipc-0` through `3`).
   - Strict opt-in privacy control: disabled by default; only transmits activity when enabled by user.
   - Non-blocking asynchronous I/O over dedicated coroutine scope: absent, disconnected, or crashed Discord never interrupts audio playback or blocks UI.
   - Clears activity on track completion, pause, or application quit.

6. **Desktop OS Coordinator (`DesktopOSCoordinator.kt`)**:
   - Central bridge uniting SMTC, Tray, Power, Shortcuts, and Discord Presence with the audio engine and workspace lifecycle.
   - Coordinates window restoration and explicit clean application termination.
