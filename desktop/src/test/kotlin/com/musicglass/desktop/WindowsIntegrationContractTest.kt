package com.musicglass.desktop

import kotlin.test.*
import java.util.concurrent.atomic.AtomicInteger

class WindowsIntegrationContractTest {

    @Test
    fun smtcDeduplicatesMediaKeysWithinDebounceWindow() {
        val smtc = WindowsSMTCAdapter()
        val toggleCount = AtomicInteger(0)

        smtc.setListener(object : OSMediaListener {
            override fun onPlay() {}
            override fun onPause() {}
            override fun onTogglePlayPause() { toggleCount.incrementAndGet() }
            override fun onNext() {}
            override fun onPrevious() {}
            override fun onSeekTo(positionMs: Long) {}
            override fun onStop() {}
        })

        // First keypress should dispatch
        val firstHandled = smtc.handleAppCommand(WindowsSMTCAdapter.APPCOMMAND_MEDIA_PLAY_PAUSE)
        assertTrue(firstHandled)
        assertEquals(1, toggleCount.get())

        // Immediate second keypress within debounce window (<150ms) should be suppressed
        val secondHandled = smtc.handleAppCommand(WindowsSMTCAdapter.APPCOMMAND_MEDIA_PLAY_PAUSE)
        assertFalse(secondHandled)
        assertEquals(1, toggleCount.get(), "Duplicate hardware key bounce must be filtered out")

        // Wait past debounce window and press again
        Thread.sleep(160)
        val thirdHandled = smtc.handleAppCommand(WindowsSMTCAdapter.APPCOMMAND_MEDIA_PLAY_PAUSE)
        assertTrue(thirdHandled)
        assertEquals(2, toggleCount.get())

        smtc.close()
    }

    @Test
    fun smtcUpdatesMetadataAndTimelineAccurately() {
        val smtc = WindowsSMTCAdapter()
        val meta = OSTrackMetadata(
            title = "Video Games",
            artist = "Lana Del Rey",
            album = "Born To Die",
            durationMs = 282000L
        )
        smtc.updateMetadata(meta)
        smtc.updatePlaybackStatus(OSPlaybackStatus.PLAYING)
        smtc.updateTimeline(OSTimelineState(positionMs = 45000L, durationMs = 282000L))

        assertEquals("Video Games", smtc.getMetadata().title)
        assertEquals(OSPlaybackStatus.PLAYING, smtc.getPlaybackStatus())
        assertEquals(45000L, smtc.getTimeline().positionMs)

        smtc.close()
    }

    @Test
    fun powerAdapterTogglesSystemSleepLock() {
        val power = WindowsPowerAdapter()

        // Engaging playback requests system sleep block
        val requested = power.requestPlaybackExecution(true)
        assertTrue(requested)
        assertTrue(power.isPlaybackExecutionActive())

        // Pausing releases system sleep block
        val released = power.requestPlaybackExecution(false)
        assertTrue(released)
        assertFalse(power.isPlaybackExecutionActive())

        power.close()
        assertFalse(power.isPlaybackExecutionActive())
    }

    @Test
    fun isolatedNativeHotkeyRegistrationAndBookkeeping() {
        // A private Ctrl+Alt+Shift+F24 reservation does not steal the owner's media key.
        // The second real RegisterHotKey must report Windows conflict 1409: an emulated
        // in-memory fallback cannot satisfy this test. Dispatch below is bookkeeping only,
        // not proof of physical media-key delivery or a working Phase 4 media session.
        WindowsGlobalShortcuts().use { shortcuts ->
            WindowsGlobalShortcuts().use { second ->
                val triggerCount=AtomicInteger()
                val modifiers=WindowsGlobalShortcuts.MOD_CONTROL or WindowsGlobalShortcuts.MOD_ALT or WindowsGlobalShortcuts.MOD_SHIFT
                val result=shortcuts.registerShortcut(200,"Isolated acceptance chord",modifiers,0x87){triggerCount.incrementAndGet()}
                assertTrue(result is ShortcutResult.Success,"Native isolated chord registration failed: $result")
                val conflict=second.registerShortcut(201,"Conflict check",modifiers,0x87){}
                assertTrue(conflict is ShortcutResult.Conflict,"Requires actual Windows registration/conflict, not emulation: $conflict")
                assertEquals(1409,(conflict as ShortcutResult.Conflict).errorCode)
                assertTrue(shortcuts.isRegistered(200));assertTrue(shortcuts.dispatch(200));assertEquals(1,triggerCount.get())
                shortcuts.unregisterShortcut(200);assertFalse(shortcuts.isRegistered(200));assertFalse(shortcuts.dispatch(200))
            }
        }
    }

    @Test
    fun discordPresenceHandlesOptOutAndMissingPipeGracefully() {
        val presence = DiscordPresenceAdapter(clientId = "test-client")
        assertFalse(presence.isOptedIn())

        // When not opted-in, update does not throw
        presence.updatePresence("Song", "Artist", true, 200000L, 1000L)

        // Opt in: attempts pipe connection; if Discord is not running, fails silently without crash
        presence.setEnabled(true)
        assertTrue(presence.isOptedIn())

        presence.updatePresence("Song", "Artist", true, 200000L, 1000L)
        presence.clearPresence()

        // Opt out cleanly
        presence.setEnabled(false)
        assertFalse(presence.isOptedIn())

        presence.close()
    }

    @Test
    fun osCoordinatorOrchestratesStateTransitions() {
        val smtc = WindowsSMTCAdapter()
        val power = WindowsPowerAdapter()
        val shortcuts = WindowsGlobalShortcuts()
        val presence = DiscordPresenceAdapter()

        var restoreTriggered = false
        var quitTriggered = false

        val coordinator = DesktopOSCoordinator(
            smtc = smtc,
            power = power,
            shortcuts = shortcuts,
            presence = presence,
            onRestoreRequested = { restoreTriggered = true },
            onQuitRequested = { quitTriggered = true }
        )

        coordinator.onTrackChanged("Norman Fucking Rockwell", "Lana Del Rey", durationMs = 248000L)
        assertEquals("Norman Fucking Rockwell", smtc.getMetadata().title)

        // Start playback
        coordinator.setPlaybackState(true)
        assertTrue(coordinator.isPlaybackActive())
        assertEquals(OSPlaybackStatus.PLAYING, smtc.getPlaybackStatus())
        assertTrue(power.isPlaybackExecutionActive())

        // Pause playback
        coordinator.setPlaybackState(false)
        assertFalse(coordinator.isPlaybackActive())
        assertEquals(OSPlaybackStatus.PAUSED, smtc.getPlaybackStatus())
        assertFalse(power.isPlaybackExecutionActive())

        coordinator.close()
        assertFalse(power.isPlaybackExecutionActive())
    }
}
