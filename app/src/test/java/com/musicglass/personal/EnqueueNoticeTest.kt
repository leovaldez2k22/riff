package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Q18-018: Add to queue says what the queue did, and "Already in the queue" when nothing was added. */
class EnqueueNoticeTest {
    private val queue = listOf(
        QueueEntry("yt:fixtureA001", "First Song", "Artist One"),
        QueueEntry("yt:fixtureB002", "Second Song (Official Video)", "Artist Two"),
        QueueEntry("yt:fixtureC003", "Third Song", "Artist Three"),
    )
    private val fresh = ProviderTrack("fixtureD004", "Fourth Song", "Artist Four")
    private val queued = ProviderTrack("fixtureC003", "Third Song", "Artist Three")
    private val otherUpload = ProviderTrack("fixtureX999", "Second Song", "Artist Two")

    @Test fun aSongNotQueuedIsAdded() {
        assertEquals(EnqueueOutcome.Added, enqueueOutcome(queue, fresh))
        assertEquals("Added to queue", enqueueNotice(EnqueueOutcome.Added))
    }

    @Test fun addToQueueOnAQueuedSongSaysSoInsteadOfAdded() {
        assertEquals(EnqueueOutcome.AlreadyQueued, enqueueOutcome(queue, queued))
        assertEquals("Already in the queue", enqueueNotice(EnqueueOutcome.AlreadyQueued))
        assertEquals("Another upload of the same song counts as queued", EnqueueOutcome.AlreadyQueued, enqueueOutcome(queue, otherUpload))
    }

    @Test fun theServiceAndTheNoticeUseOneRule() {
        assertTrue(sameRecording("yt:fixtureC003", "Anything", "Anyone", queued))
        assertTrue(sameRecording("yt:other", "Second Song (Official Video)", "Artist Two feat. Guest", otherUpload))
        assertFalse(sameRecording("yt:other", "Second Song", "Somebody Else", otherUpload))
        val coordinator = java.io.File("src/main/java/com/musicglass/personal/QueueCoordinator.kt").readText()
        assertTrue(coordinator.substringAfter("private fun indexOfRecording").substringBefore("}").contains("sameRecording("))
    }
}
