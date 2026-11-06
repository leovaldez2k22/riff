package com.musicglass.desktop

import kotlin.test.*

class PhaseOneControllerRegressionTest {
    @get:org.junit.Rule internal val hosts=DesktopHostTestFixture()
    @Test fun rejectedImportDoesNotFalselyReportThatExistingPlaybackStopped() {
        val host=hosts.create();host.dispatch(DesktopCommand.SetPlaying(true));assertTrue(host.snapshot.isPlaying)
        val entry=host.snapshot.currentEntryId
        val file=kotlin.io.path.createTempFile("riff-invalid-", ".mp3").toFile()
        try {
            file.writeText("not compressed audio")
            host.dispatch(DesktopCommand.AddFileToQueue(file))
            assertTrue(host.snapshot.isPlaying);assertEquals(entry,host.snapshot.currentEntryId)
            assertNotNull(host.snapshot.notificationMessage)
        }finally{file.delete()}
    }
    @Test fun removingTheFinalOccurrenceReleasesTheQueueAndAcknowledgesStoppedPlayback() {
        val host=hosts.create();host.dispatch(DesktopCommand.SetPlaying(true))
        host.snapshot.queueEntries.map{it.queueId}.forEach{host.dispatch(DesktopCommand.RemoveQueueEntry(it))}
        assertTrue(host.snapshot.queueEntries.isEmpty());assertNull(host.snapshot.currentEntryId)
        assertFalse(host.snapshot.isPlaying);assertFalse(host.snapshot.playWhenReady)
    }
    @Test fun playingAQueueOccurrencePreservesDuplicateIdentityAndRejectsStaleRevision(){
        // Controller contract only; real compressed queue playback has its own native acceptance.
        val host=hosts.create();val song=host.snapshot.queueEntries.first().song
        host.dispatch(DesktopCommand.PlayCollection(listOf(song,song,song)))
        val chosen=host.snapshot.queueEntries[2];val revision=host.snapshot.queueRevision
        host.dispatch(DesktopCommand.PlayQueueEntry(chosen.queueId,revision))
        assertEquals(chosen.queueId,host.snapshot.currentEntryId);assertEquals(3,host.snapshot.queueEntries.size)
        host.dispatch(DesktopCommand.MoveQueueEntryToPosition(chosen.queueId,1,revision))
        val current=host.snapshot.currentEntryId
        host.dispatch(DesktopCommand.PlayQueueEntry(host.snapshot.queueEntries.first().queueId,revision))
        assertEquals(current,host.snapshot.currentEntryId,"Stale row activated a different occurrence")
    }
    @Test fun firstLaunchAndOversizedRestoresKeepThePlaybackDockAboveTheTaskbar() {
        val file=kotlin.io.path.createTempFile("riff-window-", ".json").toFile()
        try {
            val manager=DesktopWindowStateManager(file,workAreas={listOf(java.awt.Rectangle(0,0,1280,752))})
            assertEquals(752f,manager.loadState().mainHeight)
            manager.saveState(WindowGeometryRecord(mainWidth=1400f,mainHeight=900f,mainX=120f,mainY=80f))
            val restored=manager.loadState()
            assertEquals(1280f,restored.mainWidth);assertEquals(752f,restored.mainHeight)
            assertEquals(0f,restored.mainX);assertEquals(0f,restored.mainY)
        }finally{file.delete()}
    }
    @Test fun negativeMonitorCoordinatesRestoreWithinThatMonitorsWorkArea() {
        val file=kotlin.io.path.createTempFile("riff-negative-monitor-", ".json").toFile()
        try {
            val manager=DesktopWindowStateManager(file,workAreas={listOf(java.awt.Rectangle(0,0,1280,752),java.awt.Rectangle(-1280,0,1280,752))})
            manager.saveState(WindowGeometryRecord(mainWidth=800f,mainHeight=600f,mainX=-1000f,mainY=100f))
            val restored=manager.loadState();assertEquals(-1000f,restored.mainX);assertEquals(100f,restored.mainY)
        }finally{file.delete()}
    }
}
