package com.musicglass.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.*

class WorkspaceLayoutTest {
    @get:org.junit.Rule
    internal val hosts = DesktopHostTestFixture()

    @Test
    fun layoutProtectsMainStageMinimumWidth() {
        // At 800 width, sidebar is compact rail (80dp).
        // 800 - 80 - (360 + 8) = 352dp, which is < 480dp min main stage.
        // Docked inspector collapses and narrow overlay activates!
        val constraints800 = WorkspaceLayoutCalculator.calculate(
            windowWidth = 800.dp,
            windowHeight = 600.dp,
            desiredInspectorTab = InspectorTab.Queue
        )
        assertTrue(constraints800.isCompactRail)
        assertEquals(80.dp, constraints800.sidebarWidth)
        assertFalse(constraints800.isInspectorVisible)
        assertTrue(constraints800.isNarrowInspectorOverlay, "Narrow overlay must activate when docked inspector collapses")
        assertEquals(0.dp, constraints800.inspectorWidth)
        assertEquals(720.dp, constraints800.mainStageWidth)

        // At 1280 width, sidebar is 224dp, sidebar divider is 8dp, inspector divider is 8dp.
        // 1280 - 224 - 8 - 360 - 8 = 680dp, which is >= 480dp min stage, so docked inspector is shown.
        val constraints1280 = WorkspaceLayoutCalculator.calculate(
            windowWidth = 1280.dp,
            windowHeight = 800.dp,
            desiredInspectorTab = InspectorTab.Lyrics
        )
        assertFalse(constraints1280.isCompactRail)
        assertEquals(224.dp, constraints1280.sidebarWidth)
        assertTrue(constraints1280.isInspectorVisible)
        assertFalse(constraints1280.isNarrowInspectorOverlay)
        assertEquals(360.dp, constraints1280.inspectorWidth)
        assertEquals(680.dp, constraints1280.mainStageWidth)

        // At 1440 width, required main stage minimum is 640dp.
        // 1440 - 224 - 8 - 360 - 8 = 840dp >= 640dp.
        val constraints1440 = WorkspaceLayoutCalculator.calculate(
            windowWidth = 1440.dp,
            windowHeight = 900.dp,
            desiredInspectorTab = InspectorTab.Lyrics
        )
        assertTrue(constraints1440.isInspectorVisible)
        assertTrue(constraints1440.mainStageWidth >= 640.dp, "Stage must satisfy 640dp minimum on wide displays")

        // At 1920 width, max content width is clamped to 1280dp
        val constraints1920 = WorkspaceLayoutCalculator.calculate(
            windowWidth = 1920.dp,
            windowHeight = 1080.dp,
            desiredInspectorTab = InspectorTab.None
        )
        assertEquals(1280.dp, constraints1920.mainContentMaxWidth)
    }

    @Test
    fun hostHandlesPlaybackAndClockAdvancement() {
        val host = hosts.create()
        assertFalse(host.snapshot.isPlaying)
        assertEquals(0L, host.snapshot.playbackPositionMs)

        host.dispatch(DesktopCommand.TogglePlayPause)
        assertTrue(host.snapshot.isPlaying)

        // Step clock by 1.5 seconds
        host.dispatch(DesktopCommand.SeekTo(1500L))
        assertEquals(1500L, host.snapshot.playbackPositionMs)

        // Seek to 45 seconds
        host.dispatch(DesktopCommand.SeekTo(45_000L))
        assertEquals(45_000L, host.snapshot.playbackPositionMs)

        // Verify the exact paused clamp; a playing EOF may legitimately transition to the next song.
        host.dispatch(DesktopCommand.SetPlaying(false))
        val duration = host.snapshot.currentTrack.durationMs
        host.dispatch(DesktopCommand.SeekTo(duration + 100_000L))
        assertEquals(duration, host.snapshot.playbackPositionMs)
    }

    @Test
    fun hostQueueReorderingAndTrackAdvancement() {
        val host = hosts.create()
        val initialFirst = host.snapshot.queue[0]
        val initialSecond = host.snapshot.queue[1]

        // Reorder tracks
        host.dispatch(DesktopCommand.ReorderQueue(0, 1))
        assertEquals(initialSecond.id, host.snapshot.queue[0].id)
        assertEquals(initialFirst.id, host.snapshot.queue[1].id)

        // Reorder by entry ID with expected revision
        val currentRev = host.snapshot.queueRevision
        val topEntryId = host.snapshot.queueEntries[0].queueId
        host.dispatch(DesktopCommand.ReorderQueueByEntryId(topEntryId, moveUp = false, expectedRevision = currentRev))
        assertEquals(topEntryId, host.snapshot.queueEntries[1].queueId)
        assertEquals(currentRev + 1, host.snapshot.queueRevision)

        // Repeat mode cycling
        assertEquals(RepeatMode.Off, host.snapshot.repeatMode)
        host.dispatch(DesktopCommand.ToggleRepeat)
        assertEquals(RepeatMode.All, host.snapshot.repeatMode)
        host.dispatch(DesktopCommand.ToggleRepeat)
        assertEquals(RepeatMode.One, host.snapshot.repeatMode)
        host.dispatch(DesktopCommand.ToggleRepeat)
        assertEquals(RepeatMode.Off, host.snapshot.repeatMode)

        // Shuffle toggling
        assertFalse(host.snapshot.isShuffle)
        host.dispatch(DesktopCommand.ToggleShuffle)
        assertTrue(host.snapshot.isShuffle)
    }

    @Test
    fun hostQueueRemovalAndUndo() {
        val host = hosts.create()
        val initialSize = host.snapshot.queueEntries.size
        val targetEntry = host.snapshot.queueEntries[1]

        // Remove queue entry
        host.dispatch(DesktopCommand.RemoveQueueEntry(targetEntry.queueId))
        assertEquals(initialSize - 1, host.snapshot.queueEntries.size)
        assertNotNull(host.snapshot.lastRemovedQueueItem)
        assertEquals(targetEntry.queueId, host.snapshot.lastRemovedQueueItem?.second?.queueId)

        // Undo removal restores item to queue
        host.dispatch(DesktopCommand.UndoQueueAction)
        assertEquals(initialSize, host.snapshot.queueEntries.size)
        assertEquals(targetEntry.queueId, host.snapshot.queueEntries[1].queueId)
        assertNull(host.snapshot.lastRemovedQueueItem)
    }

    @Test
    fun hostRouteAndWindowModeTransitions() {
        val host = hosts.create()
        assertEquals(WorkspaceRoute.Home, host.snapshot.currentRoute)
        host.dispatch(DesktopCommand.SetRoute(WorkspaceRoute.Search))
        assertEquals(WorkspaceRoute.Search, host.snapshot.currentRoute)

        assertEquals(WindowMode.Main, host.snapshot.windowMode)
        host.dispatch(DesktopCommand.SetWindowMode(WindowMode.MiniCompact))
        assertEquals(WindowMode.MiniCompact, host.snapshot.windowMode)
        host.dispatch(DesktopCommand.ShowMain)
        assertEquals(WindowMode.Main, host.snapshot.windowMode)

        // Test always-on-top toggle
        assertFalse(host.snapshot.isAlwaysOnTop)
        host.dispatch(DesktopCommand.SetAlwaysOnTop(true))
        assertTrue(host.snapshot.isAlwaysOnTop)

        // Test sidebar and inspector width adjustment
        host.dispatch(DesktopCommand.SetSidebarWidth(280f))
        assertEquals(280f, host.snapshot.sidebarWidthDp)
        host.dispatch(DesktopCommand.SetInspectorWidth(400f))
        assertEquals(400f, host.snapshot.inspectorWidthDp)
    }

    @Test
    fun hostPointerDragMoveQueueEntryToPosition() {
        val host = hosts.create()
        val initialEntries = host.snapshot.queueEntries
        assertTrue(initialEntries.size >= 3)
        val draggedEntry = initialEntries[0]
        val currentRev = host.snapshot.queueRevision

        // Move entry from index 0 to index 2
        host.dispatch(DesktopCommand.MoveQueueEntryToPosition(draggedEntry.queueId, targetIndex = 2, expectedRevision = currentRev))
        assertEquals(currentRev + 1, host.snapshot.queueRevision)
        assertEquals(draggedEntry.queueId, host.snapshot.queueEntries[2].queueId)

        // Stale revision is rejected safely
        val staleRev = currentRev // older revision
        val otherEntry = host.snapshot.queueEntries[1]
        host.dispatch(DesktopCommand.MoveQueueEntryToPosition(otherEntry.queueId, targetIndex = 0, expectedRevision = staleRev))
        assertEquals(currentRev + 1, host.snapshot.queueRevision) // revision unchanged
        assertEquals(draggedEntry.queueId, host.snapshot.queueEntries[2].queueId) // unchanged
    }

    @Test
    fun hostPlayCollectionAndNavigateBack() {
        val host = hosts.create()
        val album = FixtureTracks.albums.first()

        // From Home, open album detail
        assertEquals(WorkspaceRoute.Home, host.snapshot.currentRoute)
        host.dispatch(DesktopCommand.OpenAlbumDetail(album.id))
        assertEquals(WorkspaceRoute.AlbumDetail, host.snapshot.currentRoute)
        assertEquals(album.id, host.snapshot.activeAlbumId)

        // Play entire album collection or track list
        host.dispatch(DesktopCommand.PlayCollection(FixtureTracks.all, startIndex = 1))
        assertTrue(host.snapshot.isPlaying)
        assertEquals(FixtureTracks.all[1].id, host.snapshot.currentTrack.id)
        assertEquals(FixtureTracks.all.size, host.snapshot.queueEntries.size)

        // Navigate back returns to previous route
        host.dispatch(DesktopCommand.NavigateBack)
        assertEquals(WorkspaceRoute.Home, host.snapshot.currentRoute)
    }

    @Test
    fun hostFileDropHandlingAndNotifications() {
        val host = hosts.create()
        val tempDir = java.nio.file.Files.createTempDirectory("riff-test-drop").toFile()
        try {
            val validAudio = java.io.File(tempDir,"song.wav")
            val format=javax.sound.sampled.AudioFormat(48000f,16,2,true,false)
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray(4800*4)),format,4800).use {
                javax.sound.sampled.AudioSystem.write(it,javax.sound.sampled.AudioFileFormat.Type.WAVE,validAudio)
            }
            val invalidFile = java.io.File(tempDir, "document.pdf").apply { writeBytes(ByteArray(100)) }

            val initialSize = host.snapshot.queueEntries.size

            // Drop valid audio file
            host.dispatch(DesktopCommand.AddFileToQueue(validAudio))
            assertEquals(initialSize + 1, host.snapshot.queueEntries.size)
            assertTrue(host.snapshot.notificationMessage?.contains("Added song.wav") == true)

            // Dismiss notification
            host.dispatch(DesktopCommand.DismissNotification())
            assertNull(host.snapshot.notificationMessage)

            // Drop unsupported file
            host.dispatch(DesktopCommand.AddFileToQueue(invalidFile))
            assertEquals(initialSize + 1, host.snapshot.queueEntries.size) // unchanged
            assertNotNull(host.snapshot.notificationMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun windowStateManagerSavesAndRestoresGeometry() {
        val tempFile = java.io.File.createTempFile("riff-window-state", ".json")
        try {
            val manager = DesktopWindowStateManager(tempFile,workAreas={listOf(java.awt.Rectangle(0,0,1920,1080))})
            val record = WindowGeometryRecord(
                mainWidth = 1400f,
                mainHeight = 900f,
                mainX = 120f,
                mainY = 80f,
                isMaximized = true,
                isAlwaysOnTop = true,
                lastMode = WindowMode.MiniExpanded,
                sidebarWidthDp = 260f,
                inspectorWidthDp = 380f
            )

            manager.saveState(record)
            val restored = manager.loadState()

            assertEquals(1400f, restored.mainWidth)
            assertEquals(900f, restored.mainHeight)
            assertEquals(120f, restored.mainX)
            assertEquals(80f, restored.mainY)
            assertTrue(restored.isMaximized)
            assertTrue(restored.isAlwaysOnTop)
            assertEquals(WindowMode.MiniExpanded, restored.lastMode)
            assertEquals(260f, restored.sidebarWidthDp)
            assertEquals(380f, restored.inspectorWidthDp)
        } finally {
            tempFile.delete()
        }
    }
}
