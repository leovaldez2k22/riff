package com.musicglass.personal

import android.os.Bundle
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PlaylistSortDeviceTest {
    @Test fun nativeSortPreferencesReloadEveryTrackAndRestoreOriginalSelection() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        fun progress(step:String)=instrumentation.sendStatus(0,Bundle().apply {putString("sort_step",step)})
        instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val provider=YouTubeMusicProvider.shared(instrumentation.targetContext)
        progress("library")
        val playlist=withTimeout(30_000) {provider.libraryCatalog("Playlists").getOrThrow()}.cards.firstOrNull {
            it.title.equals("Favourites",true) || it.title.equals("Favorites",true)
        }
        assertNotNull("Find the existing playlist",playlist)
        val id=playlist!!.browseId!!
        progress("initial playlist")
        val initial=withTimeout(60_000) {provider.completeCollection(id).getOrThrow()}
        progress("initial tracks=${initial.albumTracks.size}, selected=${initial.selectedSort}")
        val original=initial.selectedSort
        assertNotNull("Provider supplies the current selection",original)
        val restore=initial.sortEndpoints.getValue(requireNotNull(original))
        val entries=initial.albumTracks.map {it.videoId to it.setVideoId}.groupingBy {it}.eachCount()
        var latest=initial
        try {
            for(sort in listOf(CollectionSort.TopVoted,CollectionSort.Original,CollectionSort.Oldest,
                CollectionSort.Newest,CollectionSort.Title,CollectionSort.Artist,CollectionSort.Album)) {
                val endpoint=latest.sortEndpoints[sort]
                assertNotNull("Provider supplies $sort",endpoint)
                progress("request $sort")
                latest=withTimeout(60_000) {provider.sortedCollection(id,endpoint!!).getOrThrow()}
                progress("loaded $sort tracks=${latest.albumTracks.size}, selected=${latest.selectedSort}")
                assertEquals("Provider confirms $sort",sort,latest.selectedSort)
                assertEquals("$sort preserves every playlist entry",entries,
                    latest.albumTracks.map {it.videoId to it.setVideoId}.groupingBy {it}.eachCount())
                assertEquals(sort,provider.cachedCollection(id)!!.selectedSort)
            }
        } finally {
            progress("restore $original")
            val restored=withTimeout(60_000) {provider.sortedCollection(id,latest.sortEndpoints[original]?:restore).getOrThrow()}
            progress("restored ${restored.selectedSort}")
            assertEquals(original,restored.selectedSort)

        }
    }
}
