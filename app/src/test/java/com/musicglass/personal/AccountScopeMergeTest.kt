package com.musicglass.personal

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Folding a verified alias of one account into its primary key; synthetic keys and files only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class AccountScopeMergeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun store() = OfflineMusicStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        .newInstance(context)
    private fun track(id: String) = ProviderTrack(id, "Song", "Artist")
    private fun bundle(store: OfflineMusicStore, scope: String, id: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        val dir = store.directory(scope, id).apply { mkdirs() }
        File(dir, "audio.media").writeBytes(bytes); File(dir, "ready").writeText(bytes.size.toString())
        File(dir, "artwork").writeText("art"); File(dir, "lyrics.json").writeText("lyrics")
        store.update(scope, id, "state" to "complete", "bytes" to bytes.size.toLong())
    }

    @Test fun downloadedSongMovesToThePrimaryKey() {
        val store = store()
        store.enqueue(track("merge000001"), scope = "alias"); bundle(store, "alias", "merge000001")
        assertEquals(0, store.mergeScope("alias", "primary"))
        assertNotNull(store.audio("primary", "merge000001"))
        assertEquals("complete", store.song("primary", "merge000001")?.state)
        assertTrue(store.rows("alias").isEmpty())
        assertFalse(store.directory("alias", "merge000001").exists())
    }

    @Test fun validatedBundleBeatsQueuedMetadataPinWinsAndTotalsAdd() {
        val store = store()
        store.enqueue(track("merge000002"), pinned = false, scope = "alias"); bundle(store, "alias", "merge000002")
        store.update("alias", "merge000002", "plays" to 3, "lastPlayed" to 900L)
        store.enqueue(track("merge000002"), pinned = true, scope = "primary")
        store.update("primary", "merge000002", "plays" to 2, "lastPlayed" to 400L)
        assertEquals(0, store.mergeScope("alias", "primary"))
        val merged = store.song("primary", "merge000002")!!
        assertEquals("complete", merged.state); assertTrue(merged.pinned)
        assertEquals(5, merged.plays); assertEquals(900L, merged.lastPlayed)
        assertNotNull(store.audio("primary", "merge000002"))
        assertNull(store.song("alias", "merge000002"))
    }

    @Test fun targetsValidatedAudioSurvivesAnUnfinishedAliasRow() {
        val store = store()
        store.enqueue(track("merge000003"), scope = "primary"); bundle(store, "primary", "merge000003", byteArrayOf(9, 9, 9))
        store.enqueue(track("merge000003"), scope = "alias")
        File(store.directory("alias", "merge000003").apply { mkdirs() }, "audio.part").writeText("partial")
        assertEquals(0, store.mergeScope("alias", "primary"))
        assertArrayEquals(byteArrayOf(9, 9, 9), store.audio("primary", "merge000003")!!.readBytes())
        assertEquals("complete", store.song("primary", "merge000003")?.state)
        assertNull(store.song("alias", "merge000003"))
    }

    @Test fun twoCompleteCopiesAreBothKept() {
        val store = store()
        store.enqueue(track("merge000004"), scope = "alias"); bundle(store, "alias", "merge000004", byteArrayOf(1))
        store.enqueue(track("merge000004"), scope = "primary"); bundle(store, "primary", "merge000004", byteArrayOf(2))
        assertEquals(1, store.mergeScope("alias", "primary"))
        assertArrayEquals(byteArrayOf(1), store.audio("alias", "merge000004")!!.readBytes())
        assertArrayEquals(byteArrayOf(2), store.audio("primary", "merge000004")!!.readBytes())
    }

    @Test fun songTheTargetIsTransferringIsLeftForALaterPass() {
        val store = store()
        store.enqueue(track("merge000005"), scope = "alias"); bundle(store, "alias", "merge000005")
        store.enqueue(track("merge000005"), scope = "primary"); assertTrue(store.beginDownload("primary", "merge000005"))
        assertEquals(1, store.mergeScope("alias", "primary"))
        assertNotNull(store.audio("alias", "merge000005"))
        assertEquals("downloading", store.song("primary", "merge000005")?.state)
    }

    @Test fun rerunAfterAnInterruptionAdoptsTheMovedFolder() {
        val store = store()
        store.enqueue(track("merge000006"), scope = "alias"); bundle(store, "alias", "merge000006")
        // Interrupted after the folder moved and before the row did.
        val target = store.directory("primary", "merge000006").apply { parentFile?.mkdirs() }
        assertTrue(store.directory("alias", "merge000006").renameTo(target))
        assertEquals(0, store.mergeScope("alias", "primary"))
        assertNotNull(store.audio("primary", "merge000006"))
        assertEquals("complete", store.song("primary", "merge000006")?.state)
        assertTrue(store.rows("alias").isEmpty())
        // A finished merge has nothing left to do.
        assertEquals(0, store.mergeScope("alias", "primary"))
        assertEquals(1, store.rows("primary").size)
    }

    @Test fun otherAccountsAndUnverifiedDataAreNotTouched() {
        val store = store()
        store.enqueue(track("merge000007"), scope = "alias"); bundle(store, "alias", "merge000007")
        store.enqueue(track("merge000007"), scope = "other"); bundle(store, "other", "merge000007")
        assertEquals(0, store.mergeScope("alias", UNVERIFIED_SCOPE_KEY))
        assertEquals(0, store.mergeScope(UNVERIFIED_SCOPE_KEY, "primary"))
        assertNotNull(store.audio("alias", "merge000007"))
        assertEquals(0, store.mergeScope("alias", "primary"))
        assertNotNull(store.audio("other", "merge000007"))
        assertEquals(1, store.rows("other").size)
    }

    private val prefs get() = context.getSharedPreferences("music_collections", Context.MODE_PRIVATE)
    private fun saved(vararg ids: String) = JSONArray().apply { ids.forEach { put(org.json.JSONObject().put("id", it)) } }.toString()
    private fun ids(key: String) = JSONArray(prefs.getString(key, "[]")).let { array -> (0 until array.length()).map { array.getJSONObject(it).getString("id") } }

    @Test fun savedTracksMergeTargetFirstWithoutDuplicates() {
        prefs.edit().clear()
            .putString("account_mprimary_online_favorites", saved("a", "b"))
            .putString("account_malias_online_favorites", saved("b", "c"))
            .putString("account_malias_online_history", saved("h"))
            .putString("account_mother_online_favorites", saved("z")).commit()
        assertTrue(mergeCollectionScope(context, "malias", "mprimary"))
        assertEquals(listOf("a", "b", "c"), ids("account_mprimary_online_favorites"))
        assertEquals(listOf("h"), ids("account_mprimary_online_history"))
        assertFalse(prefs.contains("account_malias_online_favorites"))
        assertFalse(prefs.contains("account_malias_online_history"))
        assertEquals(listOf("z"), ids("account_mother_online_favorites"))
        // Nothing left under the alias: a second pass changes nothing.
        assertFalse(mergeCollectionScope(context, "malias", "mprimary"))
        assertEquals(listOf("a", "b", "c"), ids("account_mprimary_online_favorites"))
    }

    @Test fun mergingAKeyIntoItselfDoesNothing() {
        prefs.edit().clear().putString("account_mprimary_online_favorites", saved("a")).commit()
        assertFalse(mergeCollectionScope(context, "mprimary", "mprimary"))
        assertEquals(listOf("a"), ids("account_mprimary_online_favorites"))
    }
}
