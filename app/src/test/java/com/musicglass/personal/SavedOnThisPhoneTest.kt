package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q18-083: Riff's list on this phone is apart from the YouTube like, and can be undone on the phone alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SavedOnThisPhoneTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val song = ProviderTrack("video000001", "Song", "Artist")

    @Test fun aLikeLeavesThePhonesListAloneAndAnUnlikeTakesTheSongOff() {
        val collections = LocalCollections(context, "s2_saved_on_phone")
        unlikedOffPhone(collections, song, liked = true)
        assertEquals("a like is YouTube's alone", emptyList<String>(), collections.onlineFavorites.map { it.videoId })
        collections.setOnlineFavorite(song, true)
        unlikedOffPhone(collections, song, liked = true)
        assertEquals(listOf("video000001"), collections.onlineFavorites.map { it.videoId })
        unlikedOffPhone(collections, song, liked = false)
        assertEquals("not left under Liked songs after an unlike", emptyList<String>(), collections.onlineFavorites.map { it.videoId })
    }

    @Test fun theOptionsSayWhatTheyDo() {
        assertEquals("Add to Saved on this phone", keyLabel("Save on this phone"))
        assertEquals("Remove from Saved on this phone", keyLabel("Remove from Saved on this phone"))
    }
}
