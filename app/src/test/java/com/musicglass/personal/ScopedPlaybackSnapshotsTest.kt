package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** Which owner a queue belongs to; storage is covered by LibraryStateTest. Synthetic owner keys only. */
class ScopedPlaybackSnapshotsTest {
    @Test fun pureOnDeviceQueueBelongsToTheDevice() {
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, queueOwnerKey(listOf("local:1", "local:2"), "owner-a"))
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, queueOwnerKey(listOf("local:1"), UNVERIFIED_SCOPE_KEY))
        assertFalse(queueStopsWithAccount(listOf("local:1", "local:2")))
    }

    @Test fun providerAndMixedQueuesBelongToTheAccount() {
        assertEquals("owner-a", queueOwnerKey(listOf("yt:fixture0001"), "owner-a"))
        assertEquals("owner-a", queueOwnerKey(listOf("local:1", "yt:fixture0001"), "owner-a"))
        assertEquals(GUEST_SCOPE_KEY, queueOwnerKey(listOf("yt:fixture0001"), GUEST_SCOPE_KEY))
        assertTrue(queueStopsWithAccount(listOf("local:1", "yt:fixture0001")))
    }

    @Test fun emptyAndUnverifiedQueuesAreNeverSaved() {
        assertNull(queueOwnerKey(emptyList(), "owner-a"))
        assertNull(queueOwnerKey(listOf("yt:fixture0001"), UNVERIFIED_SCOPE_KEY))
        assertNull(queueOwnerKey(listOf("local:1", "yt:fixture0001"), UNVERIFIED_SCOPE_KEY))
        assertFalse(queueStopsWithAccount(emptyList()))
    }
}
