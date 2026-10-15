package com.musicglass.personal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Q16B: a song's menu says "Remove download", in pink with a trash can, once the song is downloaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongDownloadMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val track = ProviderTrack("menudl00001", "Song", "Artist")

    private fun downloaded(store: OfflineMusicStore) {
        val scope = store.currentScope
        store.enqueue(track, scope = scope)
        val dir = store.directory(scope, track.videoId).apply { mkdirs() }
        File(dir, BundleValidator.AUDIO).writeBytes(byteArrayOf(1, 2, 3, 4)); File(dir, BundleValidator.READY).writeText("4")
        store.update(scope, track.videoId, "state" to DownloadState.Complete.db, "bytes" to 4L)
    }

    private fun pinkAndWhite(): Pair<Int, Int> {
        compose.waitForIdle()
        val box = compose.onNodeWithTag("menu").fetchSemanticsNode().boundsInRoot
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        var pink = 0; var white = 0
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            val c = bitmap.getPixel(x, y)
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            if (r > 205 && g < 140 && b > 115) pink++
            if (r > 205 && g > 205 && b > 205) white++
        }
        return pink to white
    }

    @Test fun aDownloadedSongOffersToRemoveItAndThenToDownloadItAgain() {
        val store = OfflineMusicStore.get(context)
        YouTubeMusicProvider.shared(context).close()
        downloaded(store)
        var notice = ""
        compose.setContent {
            Column(Modifier.width(280.dp).background(Color(0xFF15151A)).testTag("menu")) { DownloadSongAction(track, { notice = it }) }
        }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Remove download").assertExists() }.isSuccess }
        compose.onNodeWithText("Download song").assertDoesNotExist()
        val (pink, white) = pinkAndWhite()
        assertTrue("the words and the trash can are pink ($pink)", pink > 150)
        assertTrue("nothing in the row is white ($white)", white < 10)

        compose.onNodeWithText("Remove download").performClick()
        compose.waitUntil(10_000) { store.audio(store.currentScope, track.videoId) == null && notice.isNotEmpty() }
        assertEquals(string(R.string.download_removed), notice)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Download song").assertExists() }.isSuccess }
        compose.onNodeWithText("Remove download").assertDoesNotExist()
        val (pinkAfter, whiteAfter) = pinkAndWhite()
        assertTrue("an ordinary row again ($pinkAfter pink, $whiteAfter white)", pinkAfter < 10 && whiteAfter > 50)
    }

    /** Nothing in these menus plays; any call to the player fails the test. */
    private val playback = java.lang.reflect.Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull(); "hashCode" -> System.identityHashCode(proxy); "toString" -> "Menu fixture"
            "getState" -> PlaybackUiState(); "getPositionMs" -> 0L
            else -> error("Unexpected playback call: ${method.name}")
        }
    } as PlaybackGateway

    @Test fun theHomeLongPressMenuOffersToRemoveADownloadedSong() {
        val store = OfflineMusicStore.get(context)
        YouTubeMusicProvider.shared(context).close()
        downloaded(store)
        lateinit var peek: CardPeekController
        compose.setContent {
            val scope = rememberCoroutineScope()
            peek = remember { CardPeekController(scope) }
            Box(Modifier.fillMaxSize()) { CardPeekLayer(peek, ShellModel(), YouTubeMusicProvider.shared(context), playback) {} }
        }
        compose.runOnIdle { peek.open(ProviderHomeCard("Song", "Artist", null, videoId = track.videoId), Rect(40f, 200f, 300f, 460f), 12f, true) {} }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Remove download").assertExists() }.isSuccess }
        compose.onNodeWithText("Download song").assertDoesNotExist()
        compose.onNodeWithText("Play next").assertExists()
    }

    @Test fun aSongNeverDownloadedOffersToDownloadIt() {
        val fresh = ProviderTrack("menudl00002", "Other", "Artist")
        compose.setContent { Column { DownloadSongAction(fresh) } }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Download song").assertExists() }.isSuccess }
        compose.onNodeWithText("Remove download").assertDoesNotExist()
    }
}
