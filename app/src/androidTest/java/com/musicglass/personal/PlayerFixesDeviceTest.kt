package com.musicglass.personal

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Q17A on a device: the player the user found faults in, drawn by the real code on the device's own graphics, with
 * a fixture song and a white cover (the hardest case for the close button). Pictures of the player and of the open
 * and close morph at fixed points are kept for review; a real touch, injected by the system, checks that a layer
 * like the lyrics page keeps touches from the page beneath. No provider request, no network, nothing played.
 */
class PlayerFixesDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FeatureTestActivity>()

    private val cover by lazy {
        // White with a few grey bars, so the shade over it shows in the picture.
        val file = compose.activity.cacheDir.resolve("q17a-cover.png")
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(200, 200, 200) }
            for (i in 0 until 6) canvas.drawRect(0f, i * 100f + 40f, 600f, i * 100f + 60f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.toURI().toString()
    }

    private val state by lazy {
        PlaybackUiState(ready = true, playing = true, wantsPlayback = true, mediaId = "yt:fxsong00002", title = "Higher (feat. Fixture Singer)",
            artist = "Fixture Artist", artworkUrl = cover, durationMs = 230_000, source = "YouTube Music", index = 1,
            queue = (1..3).map { QueueEntry("yt:fxsong0000$it", "Fixture Song $it", "Fixture Artist", queueId = "q$it") })
    }

    private val gateway by lazy {
        Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { self, method, args ->
            when (method.name) {
                "getState" -> state
                "getPositionMs", "currentPositionMs" -> 2_000L
                "hasProviderItems" -> false
                "equals" -> self === args[0]; "hashCode" -> System.identityHashCode(self); "toString" -> "fixture gateway"
                else -> null
            }
        } as PlaybackGateway
    }

    private fun keep(name: String) {
        compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle()
        val image = compose.onNodeWithTag("stage").captureToImage()
        compose.activity.getExternalFilesDir(null)?.resolve("q17a-$name.png")?.outputStream()?.use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("RiffQ17A", "kept q17a-$name.png")
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun thePlayerAndItsMorphAtFixedPoints() {
        val provider = YouTubeMusicProvider.shared(compose.activity)
        val progress = mutableFloatStateOf(1f)
        val accessibility = AccessibilitySettings(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides accessibility, LocalMotionPolicy provides MotionPolicy(false, 1f)) {
                RiffTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1B2A22)).testTag("stage")) {
                        PlayerMorphHost({ progress.floatValue }, ShellModel(), false, gateway, LocalCollections(compose.activity), 24f,
                            miniBounds = null, miniElementBounds = emptyMap<String, Rect>(), fullDragging = false, provider = provider,
                            onFailureAction = {}, onMorphDrag = { _, _ -> }, onNotice = {}, onArtist = {}, onAlbum = {},
                            onMorphRelease = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { exists("player_close") }
        assertTrue("no Play video", !exists("player_video"))
        assertTrue(exists("playback_shuffle") && exists("playback_repeat"))
        keep("player")
        // The points the user's picture came from (just into the hand-over) and either side of it.
        for (p in listOf(.5f, .9f, .965f, .98f)) { compose.runOnIdle { progress.floatValue = p }; keep("morph-${(p * 1000).toInt()}") }
        // The mini-player end (Q18-001): through its hand-over and just after, for review against one title and one cover.
        for (p in listOf(.005f, .01f, .02f, .035f)) { compose.runOnIdle { progress.floatValue = p }; keep("mini-${(p * 1000).toInt()}") }
    }

    /**
     * Q18-055 (Q18-001 again): the real bar with its mini player and the morph above it, as the app lays them out.
     * Slid up a little (inside the hand-over, where the owner saw two titles), the mini player must look as it does at
     * rest: the copies lie on it pixel for pixel. A second title a few px off changes hundreds of pixels.
     */
    @Test fun aSlowSlideUpShowsOneTitle() {
        val provider = YouTubeMusicProvider.shared(compose.activity)
        val progress = mutableFloatStateOf(0f)
        val accessibility = AccessibilitySettings(compose.activity)
        val model = ShellModel().apply { miniVisible = true }
        var miniBounds: Rect? by mutableStateOf(null)
        val elements = mutableStateMapOf<String, Rect>()
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides accessibility, LocalMotionPolicy provides MotionPolicy(false, 1f)) {
                RiffTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1B2A22)).testTag("stage")) {
                        ChromeBar(model, false, gateway, Modifier.align(Alignment.BottomCenter), bottomInset = 24f,
                            miniMorph = { progress.floatValue }, miniDragging = false, onMiniDrag = { _, _ -> }, onMiniRelease = {},
                            onMiniBounds = { miniBounds = it }, onMiniElementBounds = { name, bounds -> elements[name] = bounds },
                            onNotice = {})
                        if (progress.floatValue > MORPH_SHOWN) PlayerMorphHost({ progress.floatValue }, model, false, gateway,
                            LocalCollections(compose.activity), 24f, miniBounds, elements, fullDragging = false, provider = provider,
                            onFailureAction = {}, onMorphDrag = { _, _ -> }, onNotice = {}, onArtist = {}, onAlbum = {},
                            onMorphRelease = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { miniBounds != null && "title" in elements && "artist" in elements }
        fun mini(name: String): Bitmap {
            compose.waitForIdle(); Thread.sleep(400); compose.waitForIdle()
            val stage = compose.onNodeWithTag("stage").captureToImage().asAndroidBitmap()
            val r = miniBounds!!
            compose.activity.getExternalFilesDir(null)?.resolve("q18-055-$name.png")?.outputStream()?.use {
                stage.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Bitmap.createBitmap(stage, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
        }
        val rest = mini("rest")
        for (p in listOf(.002f, .005f, .01f, .015f, .019f)) {
            compose.runOnIdle { progress.floatValue = p }
            val slid = mini("slid-${(p * 1000).toInt()}")
            var changed = 0
            for (y in 0 until rest.height) for (x in 0 until rest.width) {
                val a = rest.getPixel(x, y); val b = slid.getPixel(x, y)
                val d = maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)))
                if (d > 48) changed++
            }
            Log.i("RiffQ18", "q18-055 p=$p changed=$changed of ${rest.width * rest.height}")
            assertTrue("at p=$p, $changed pixels differ from the mini player at rest", changed < rest.width * rest.height / 400)
        }
    }

    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() } }

    @Test fun aRealTouchBetweenTheControlsOfABlockingLayerDoesNotReachThePageBelow() {
        var below = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("stage")) {
                Box(Modifier.fillMaxSize().clickable { below++ })
                Box(Modifier.fillMaxSize().background(Color.Black).blockTouchesBelow())
            }
        }
        compose.waitForIdle()
        val metrics = compose.activity.resources.displayMetrics
        // The lower corners, where the lyrics page has room between its controls.
        for ((x, y) in listOf(metrics.widthPixels / 12 to metrics.heightPixels * 9 / 10, metrics.widthPixels * 11 / 12 to metrics.heightPixels * 9 / 10,
            metrics.widthPixels / 2 to metrics.heightPixels / 2)) {
            shell("input tap $x $y"); Thread.sleep(300)
        }
        compose.waitForIdle()
        assertEquals("touches reaching the page below", 0, below)
    }
}
