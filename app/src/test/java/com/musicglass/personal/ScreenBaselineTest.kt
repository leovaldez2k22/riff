package com.musicglass.personal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import kotlin.math.abs

/**
 * Q12: pictures of the states a screen can be in, drawn off-device and compared with the ones kept in
 * src/test/resources/quality/screens. Invented titles only, no artwork is fetched, and time stands still.
 *
 * A picture that differs fails the test and is written to build/reports/screens for comparison. After an
 * intended change, record again with: gradlew :app:testDebugUnitTest --tests "*ScreenBaselineTest" -PriffRecordScreens
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenBaselineTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    private val baselines = File("src/test/resources/quality/screens")
    private val record = System.getProperty("riff.screens.record") == "true"

    @Before fun noArtworkIsFetched() {
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components {
            add(Interceptor { chain -> ErrorResult(null, chain.request, IOException("fixture: no artwork is fetched")) })
        }.build())
        compose.mainClock.autoAdvance = false
    }
    @After fun close() { world?.close(); SingletonImageLoader.reset() }

    /** Draws what is on screen after [settleMs] of test time and holds it against the kept picture [name]. */
    private fun matches(name: String, settleMs: Long = 2_000) {
        compose.mainClock.advanceTimeBy(settleMs)
        // Drawn straight from the view: the test clock stands still, so nothing waits for a frame that will not come.
        val view = compose.activity.findViewById<View>(android.R.id.content)
        val drawn = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val kept = File(baselines, "$name.png")
        if (record) { baselines.mkdirs(); kept.outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }; return }
        assertTrue("No kept picture for $name. Record it (see the class comment).", kept.exists())
        val expected = BitmapFactory.decodeFile(kept.path)
        val different = differing(expected, drawn)
        if (different > TOLERATED) {
            File("build/reports/screens").apply { mkdirs() }.resolve("$name-actual.png").outputStream().use { drawn.compress(Bitmap.CompressFormat.PNG, 100, it) }
            fail("$name differs from its kept picture in %.2f%% of its pixels; see build/reports/screens/$name-actual.png".format(different * 100))
        }
    }

    private fun search(route: (ProviderWorld.Call) -> ProviderWorld.Reply, query: String = "fixture", fontScale: Float = 1f) {
        val w = ProviderWorld(context).also { world = it }
        w.route = route
        val model = ShellModel().apply { searchQuery = query; searchFilter = "Songs" }
        val player = RecordingGateway()
        compose.setContent { Screen(fontScale) { CatalogSearchScreen(model, 0.dp, player.gateway, w.provider) {} } }
    }
    @Composable private fun Screen(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context),
            LocalDensity provides Density(density.density, fontScale)) {
            RiffTheme { Box(Modifier.fillMaxSize().background(Ink)) { content() } }
        }
    }
    /** Waits, in real time, until the provider's answer has been drawn; test time moves in small fixed steps. */
    private fun settle(tag: String) { repeat(400) { if (compose.count(tag) > 0) return; compose.mainClock.advanceTimeBy(50); Thread.sleep(5) }; fail("$tag never appeared") }

    @Test fun empty() { search({ reply("search-empty.json") }); settle("content_empty"); matches("search-empty") }
    @Test fun loading() { search({ reply("search-songs.json") }); compose.mainClock.advanceTimeBy(100); assertEquals(1, compose.count("list_skeleton")); matches("search-loading", settleMs = 0) }
    @Test fun offline() { search({ ProviderWorld.Reply("", failure = UnknownHostException("fixture")) }); settle("content_problem"); matches("search-offline") }
    @Test fun error() { search({ ProviderWorld.Reply("{}", status = 500) }); settle("content_problem"); matches("search-error") }
    @Test fun results() { search({ reply("search-songs.json") }); settle("catalog_song_fxsong00001"); matches("search-results") }
    @Test fun largeText() { search({ reply("search-songs.json") }, fontScale = 2f); settle("catalog_song_fxsong00001"); matches("search-results-large-text") }

    private fun mini(playing: Boolean, fontScale: Float = 1f) {
        val player = RecordingGateway(PlaybackUiState(ready = true, playing = playing, wantsPlayback = playing, mediaId = "fxsong00001",
            title = "Fixture Song With A Title Long Enough To Be Cut", artist = "Fixture Artist", durationMs = 200_000, positionMs = 65_000))
        compose.setContent { Screen(fontScale) { Box(Modifier.fillMaxWidth().height(62.dp).background(Raised)) { MiniPlayerContents(player.gateway, {}) } } }
    }
    @Test fun playing() { mini(true); matches("mini-playing") }
    @Test fun paused() { mini(false); matches("mini-paused") }
    @Test fun pausedLargeText() { mini(false, fontScale = 2f); matches("mini-paused-large-text") }

    @Test fun aChangedPictureIsNoticedAndASmallDifferenceIsNot() {
        val a = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF101010.toInt()) }
        val nearly = a.copy(Bitmap.Config.ARGB_8888, true).apply { eraseColor(0xFF121212.toInt()) }
        val changed = a.copy(Bitmap.Config.ARGB_8888, true).apply { for (x in 0 until 100) for (y in 0 until 10) setPixel(x, y, 0xFFFFFFFF.toInt()) }
        assertEquals(0.0, differing(a, nearly), 0.0)
        assertEquals(0.10, differing(a, changed), 0.0001); assertTrue(differing(a, changed) > TOLERATED)
        assertEquals("Another size is another picture", 1.0, differing(a, Bitmap.createBitmap(100, 90, Bitmap.Config.ARGB_8888)), 0.0)
    }

    private companion object {
        /** The share of pixels that may differ: room for a rounding difference at an edge, not for a moved line of text. */
        const val TOLERATED = 0.002
        const val CHANNEL = 8
        /** The share of pixels whose colour differs by more than [CHANNEL] in any channel; 1 when the sizes differ. */
        fun differing(a: Bitmap, b: Bitmap): Double {
            if (a.width != b.width || a.height != b.height) return 1.0
            val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
            val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            var count = 0
            for (i in pa.indices) {
                val x = pa[i]; val y = pb[i]
                if (abs((x shr 16 and 255) - (y shr 16 and 255)) > CHANNEL || abs((x shr 8 and 255) - (y shr 8 and 255)) > CHANNEL ||
                    abs((x and 255) - (y and 255)) > CHANNEL) count++
            }
            return count.toDouble() / pa.size
        }
    }
}
