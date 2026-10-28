package com.musicglass.personal

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Feature 14: the playback service answers a media browser the way Android Auto connects to it. Reads the
 * tabs and the on-device ones (Downloads, Recent), which need no network; nothing is played.
 */
@RunWith(AndroidJUnit4::class)
class CarBrowseDeviceTest {
    private val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var browser: MediaBrowser

    @Before fun connect() {
        browser = MediaBrowser.Builder(target, SessionToken(target, ComponentName(target, MusicPlaybackService::class.java)))
            .setApplicationLooper(Looper.getMainLooper()).buildAsync().get(15, TimeUnit.SECONDS)
    }

    @After fun disconnect() { InstrumentationRegistry.getInstrumentation().runOnMainSync { browser.release() } }

    /** A browser is called on its own (main) thread; the answer is awaited here. */
    private fun <T> ask(call: MediaBrowser.() -> ListenableFuture<T>): T {
        lateinit var answer: ListenableFuture<T>
        InstrumentationRegistry.getInstrumentation().runOnMainSync { answer = browser.call() }
        return answer.get(30, TimeUnit.SECONDS)
    }

    @Test fun theRootOpensOnFourTabsThatEachList() {
        val root = ask { getLibraryRoot(null) }
        assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
        val tabs = ask { getChildren(root.value!!.mediaId, 0, 10, null) }
        assertEquals(listOf(CAR_HOME, CAR_LIBRARY, CAR_DOWNLOADS, CAR_RECENT), tabs.value!!.map { it.mediaId })
        for (tab in listOf(CAR_LIBRARY, CAR_DOWNLOADS, CAR_RECENT))
            assertEquals(tab, LibraryResult.RESULT_SUCCESS, ask { getChildren(tab, 0, 20, null) }.resultCode)
        assertNotEquals("not in the tree", LibraryResult.RESULT_SUCCESS, ask { getChildren("elsewhere", 0, 20, null) }.resultCode)
        assertTrue(ask { getChildren(CAR_RECENT, 0, 20, null) }.value!!.all { it.mediaMetadata.isPlayable == true })
    }
}
