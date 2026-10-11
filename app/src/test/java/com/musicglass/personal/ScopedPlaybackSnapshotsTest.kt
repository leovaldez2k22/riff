package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

/** One paused queue per owner; synthetic owner keys only. */
class ScopedPlaybackSnapshotsTest {
    private class MemoryValues : SnapshotValues {
        val map = mutableMapOf<String, String>()
        var failDurable = false
        var durableWrites = 0
        override fun read(key: String) = map[key]
        override fun write(values: Map<String, String?>, durable: Boolean): Boolean {
            if (durable) { durableWrites++; if (failDurable) return false }
            values.forEach { (key, value) -> if (value == null) map.remove(key) else map[key] = value }
            return true
        }
    }

    private val clock = FakeClock()
    private val values = MemoryValues()
    private val snapshots = ScopedPlaybackSnapshots(values, clock::wallTimeMillis)
    private fun queue(vararg ids: String, position: Long = 0) =
        PlaybackSnapshot(ids.map {
            SavedQueueItem(it, "Song", "Artist", localUri = if (it.startsWith("local:")) "content://media/external/audio/media/${it.removePrefix("local:")}" else null)
        }, 0, position)

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

    @Test fun eachOwnerGetsItsOwnQueueBack() {
        snapshots.save("owner-a", queue("yt:fixtureA001", position = 41_000))
        snapshots.save("owner-b", queue("yt:fixtureB001", "yt:fixtureB002"))
        assertEquals(listOf("yt:fixtureA001"), snapshots.load("owner-a")?.snapshot?.queue?.map { it.mediaId })
        assertEquals(41_000L, snapshots.load("owner-a")?.snapshot?.positionMs)
        assertEquals(2, snapshots.load("owner-b")?.snapshot?.queue?.size)
        assertNull(snapshots.load("owner-c"))
    }

    @Test fun clearingOneOwnerLeavesTheOthers() {
        snapshots.save("owner-a", queue("yt:fixtureA001"))
        snapshots.save(GUEST_SCOPE_KEY, queue("yt:fixtureG001"))
        snapshots.clear(GUEST_SCOPE_KEY)
        assertNull(snapshots.load(GUEST_SCOPE_KEY))
        assertNotNull(snapshots.load("owner-a"))
    }

    @Test fun newestPicksTheMostRecentlySavedOwner() {
        snapshots.save("owner-a", queue("yt:fixtureA001"))
        clock.wall += 1_000
        snapshots.save(DEVICE_LOCAL_SCOPE_KEY, queue("local:1"))
        assertEquals(DEVICE_LOCAL_SCOPE_KEY, snapshots.newest(listOf("owner-a", DEVICE_LOCAL_SCOPE_KEY))?.ownerKey)
        clock.wall += 1_000
        snapshots.save("owner-a", queue("yt:fixtureA001"))
        assertEquals("owner-a", snapshots.newest(listOf("owner-a", DEVICE_LOCAL_SCOPE_KEY))?.ownerKey)
        // Another owner's newer queue is not a candidate unless it was asked for.
        clock.wall += 1_000
        snapshots.save("owner-b", queue("yt:fixtureB001"))
        assertEquals("owner-a", snapshots.newest(listOf("owner-a", DEVICE_LOCAL_SCOPE_KEY))?.ownerKey)
        assertNull(snapshots.newest(listOf("owner-c")))
    }

    @Test fun durableSaveReportsFailureAndKeepsThePreviousQueue() {
        snapshots.save("owner-a", queue("yt:fixtureA001"))
        values.failDurable = true
        assertFalse(snapshots.save("owner-a", queue("yt:fixtureA002"), durable = true))
        assertEquals(1, values.durableWrites)
        assertEquals(listOf("yt:fixtureA001"), snapshots.load("owner-a")?.snapshot?.queue?.map { it.mediaId })
    }

    @Test fun corruptStoredQueueLoadsAsNothing() {
        values.map["snapshot_owner-a"] = "{not json"
        assertNull(snapshots.load("owner-a"))
        assertNull(snapshots.newest(listOf("owner-a")))
    }

    @Test fun legacySharedSnapshotIsNeverRead() {
        values.map["snapshot"] = encodePlaybackSnapshot(queue("yt:fixtureL001"))
        assertNull(snapshots.newest(listOf("owner-a", GUEST_SCOPE_KEY, DEVICE_LOCAL_SCOPE_KEY)))
        assertTrue("snapshot" in values.map)
    }
}
