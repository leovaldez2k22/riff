package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Q17A: the six player problems the user found on their phone before Q18 - the patches behind the close button and
 * the heading, the Play video button, touches passing through the lyrics page, the doubled title and buttons while
 * the player opens, square-ended shuffle and repeat, and slow starts after the app has been away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class PlayerFixesTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    private val folder: File = Files.createTempDirectory("players").toFile()
    @Before fun noSolver() { shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, SolverService::class.java)) }
    @After fun close() { world?.close(); folder.deleteRecursively() }

    private val track = PlaybackUiState(ready = true, playing = true, wantsPlayback = true, mediaId = "fxsong00002", title = "Fixture Song 2",
        artist = "Fixture Artist", durationMs = 200_000, source = "YouTube Music", index = 1,
        queue = (1..3).map { QueueEntry("fxsong0000$it", "Fixture Song $it", "Fixture Artist", queueId = "q$it") })

    private fun showPlayer() {
        val p = ProviderWorld(context, true).also { world = it; it.route = { error("unscripted ${it.endpoint}") } }.provider
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                RiffTheme { Box(Modifier.fillMaxSize()) {
                    NowPlayingPanel(RecordingGateway(track).gateway, LocalCollections(context), p, onClose = {}, onSwipeDrag = { _, _ -> },
                        onSwipeRelease = {}, onQueue = {}, onLibrary = {}, onLyrics = {}, onArtist = {}, onAlbum = {},
                        onFailureAction = {}, onNotice = {})
                } }
            }
        }
    }

    @Test fun thePlayerHasNoVideoButton() {
        showPlayer()
        assertEquals(0, compose.onAllNodes(hasTestTag("player_video")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText("Play video")).fetchSemanticsNodes().size)
        // The rest of the player is still there.
        compose.onNodeWithTag("playback_toggle").fetchSemanticsNode()
    }

    @Test fun theCloseButtonIsOneButtonWithOneName() {
        showPlayer()
        // Its shadow is a second drawing of the icon, which a screen reader must not hear.
        val close = compose.onNodeWithTag("player_close", useUnmergedTree = false).fetchSemanticsNode().config
        assertEquals(listOf("Close player"), close[SemanticsProperties.ContentDescription])
    }

    @Test fun noFadeOrPatchSitsOverTheArtworkAndTheControlsCarryTheirOwnShadows() {
        // Q18-002 and Q18-008, the owner's choice: no dark fade across the top of the player, no patch under a back arrow.
        val source = java.io.File("src/main/java/com/musicglass/personal")
        val player = java.io.File(source, "NowPlaying.kt").readText()
        assertFalse(player.contains("scrimForControl"))
        assertTrue(player.contains("OverPictureIcon(Icons.Rounded.Close"))
        assertTrue(player.contains("TextStyle(shadow = RiffOverPicture.textShadow)"))
        val parts = java.io.File(source, "ScreenParts.kt").readText()
        assertFalse(parts.contains("scrimPatch"))
        assertTrue(parts.contains("if (overPicture) OverPictureIcon(Icons.AutoMirrored.Filled.ArrowBack"))
        assertTrue("The shadow is close and deep", RiffOverPicture.textShadow.color.alpha >= .85f && RiffOverPicture.iconEdgeAlpha >= .85f)
    }

    @Test fun theBackArrowOverAPictureIsOneButtonWithOneName() {
        compose.setContent { BackHeading("Fixture Album", {}, overPicture = true) }
        val back = compose.onNodeWithTag("back", useUnmergedTree = false).fetchSemanticsNode().config
        assertEquals("Its shadows are drawings a screen reader does not hear", listOf("Back to Fixture Album"), back[SemanticsProperties.ContentDescription])
    }

    @Test fun shuffleAndRepeatHaveRoundEndsAndCorners() {
        for (icon in listOf(RoundedTransportIcons.Shuffle, RoundedTransportIcons.Repeat, RoundedTransportIcons.RepeatOne)) {
            val paths = paths(icon)
            assertTrue(icon.name, paths.isNotEmpty())
            paths.forEach {
                assertEquals(icon.name, StrokeCap.Round, it.strokeLineCap)
                assertEquals(icon.name, StrokeJoin.Round, it.strokeLineJoin)
                assertEquals(icon.name, RoundedTransportIcons.STROKE, it.strokeLineWidth, 0f)
            }
        }
        assertFalse(RoundedTransportIcons.Repeat == RoundedTransportIcons.RepeatOne)
    }

    private fun paths(icon: ImageVector): List<VectorPath> {
        fun walk(group: VectorGroup): List<VectorPath> = group.flatMap { node ->
            when (node) { is VectorPath -> listOf(node); is VectorGroup -> walk(node) } }
        return walk(icon.root)
    }

    @Test fun aTouchOnALayerThatBlocksTouchesNeverReachesThePageBelow() {
        var below = 0; var control = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { below++ }.testTag("page"))
                Box(Modifier.fillMaxSize().blockTouchesBelow().testTag("layer")) {
                    Box(Modifier.size(48.dp).align(Alignment.TopStart).clickable { control++ }.testTag("control"))
                }
            }
        }
        compose.onNodeWithTag("layer").performTouchInput { click(center) }
        compose.onNodeWithTag("layer").performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(4f, 4f)) }
        compose.onNodeWithTag("control").performClick()
        compose.runOnIdle { assertEquals("the page under the layer", 0, below); assertEquals("the layer's own control", 1, control) }
    }

    @Test fun withoutTheBlockTheSameTouchReachesThePageBelow() {
        // The case the lyrics page was in: a full-screen layer with nothing that listens between its controls.
        var below = 0
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { below++ })
                Box(Modifier.fillMaxSize().testTag("layer"))
            }
        }
        compose.onNodeWithTag("layer").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, below) }
    }

    @Test fun theFlyingCopiesHaveArrivedWheneverTheRealElementsShow() {
        var p = 0f
        while (p <= 1f) {
            if (morphHandoff(p) > 0f) assertEquals("at $p", 1f, morphTravel(p), 0f)
            // Between them, the copy and the real element never add up to more than one.
            assertTrue("at $p", morphCopyAlpha(p) + morphHandoff(p) <= 1.0001f)
            p += .0005f
        }
        assertEquals(0f, morphTravel(0f), 0f)
        assertEquals(.5f, morphTravel(.49f), 1e-6f)
        assertEquals(0f, morphCopyAlpha(1f), 0f)
    }

    @Test fun theFlyingCopiesSitOnTheMiniPlayerThroughItsHandOver() {
        // Q18-001, Q18-055: through the hand-over nothing moves, and the mini player and its copies are never drawn
        // together: at rest only the mini player, once the player is drawn only the copies (drawn exactly like it).
        assertEquals(1f, miniPlayerAlpha(0f), 0f); assertEquals(0f, morphCopyAlpha(0f), 0f)
        assertEquals(1f, miniPlayerAlpha(MORPH_SHOWN), 0f); assertEquals(0f, morphCopyAlpha(MORPH_SHOWN), 0f)
        var p = MORPH_SHOWN + .0001f
        while (p < MORPH_MINI_HANDOFF_END) {
            assertEquals("at $p", 0f, morphGeometry(p), 0f)
            assertEquals("at $p", 0f, morphTravel(p), 0f)
            assertEquals("at $p", 0f, miniPlayerAlpha(p), 0f)
            assertEquals("at $p", 1f, morphCopyAlpha(p), 0f)
            p += .0005f
        }
        // Q18-055: over the hand-over only the copies drawn like the mini player show; the player's look comes later.
        p = 0f
        while (p < MORPH_MINI_HANDOFF_END) { assertEquals("at $p", 0f, morphRestyle(morphTravel(p)), 0f); p += .0005f }
        assertEquals(1f, morphRestyle(.45f), 0f)
        assertEquals(14f, miniTitleSize(1f).value, 0f); assertEquals(12f, miniTitleSize(1.6f).value, 0f)
        assertEquals(1f, morphCopyAlpha(MORPH_MINI_HANDOFF_END), 0f)
        assertEquals(0f, miniPlayerAlpha(MORPH_MINI_HANDOFF_END), 0f)
        assertTrue("then the surface moves", morphGeometry(.035f) > 0f && morphTravel(.035f) > 0f)
        assertEquals(1f, morphGeometry(1f), 0f)
    }

    @Test fun thePreparedPlayerIsKeptOnDiskAndReadBack() {
        val cache = PreprocessedPlayerCache(folder)
        val key = "a".repeat(40)
        assertNull(cache.read(key))
        cache.write(key, "var player = 1;")
        // A new solver process: a new cache over the same folder.
        assertEquals("var player = 1;", PreprocessedPlayerCache(folder).read(key))
        cache.write(key, "var player = 2;")
        assertEquals("var player = 2;", cache.read(key))
        // The library removes a copy it found unusable by writing nothing.
        cache.write(key, "")
        assertNull(cache.read(key))
        cache.write(key, "var player = 3;"); cache.write(key, null)
        assertNull(cache.read(key))
        assertEquals(0, folder.listFiles().orEmpty().size)
    }

    @Test fun onlyTheLibrarysOwnKeysNameAFile() {
        val cache = PreprocessedPlayerCache(folder)
        for (key in listOf("../outside", "A".repeat(40), "a".repeat(39), "a".repeat(41), "", "${"a".repeat(40)}/x")) {
            cache.write(key, "code"); assertNull(key, cache.read(key))
        }
        assertEquals(0, folder.listFiles().orEmpty().size)
        assertFalse(File(folder.parentFile, "outside.js").exists())
    }

    @Test fun anOversizedScriptIsNotKept() {
        val cache = PreprocessedPlayerCache(folder, maxChars = 10)
        cache.write("b".repeat(40), "x".repeat(11)); assertNull(cache.read("b".repeat(40)))
        cache.write("b".repeat(40), "x".repeat(10)); assertEquals(10, cache.read("b".repeat(40))?.length)
        // A file grown beyond the limit on disk is removed when read.
        File(folder, "${"b".repeat(40)}.js").writeText("x".repeat(60))
        assertNull(cache.read("b".repeat(40)))
        assertFalse(File(folder, "${"b".repeat(40)}.js").exists())
    }

    @Test fun onlyTheMostRecentlyUsedScriptsAreKept() {
        var now = 1_000_000L
        val cache = PreprocessedPlayerCache(folder, maxEntries = 3, clock = { now })
        val keys = ('0'..'4').map { it.toString().repeat(40) }
        keys.take(3).forEach { now += 10_000; cache.write(it, "code $it") }
        now += 10_000; cache.read(keys[0]) // used again: now the newest
        now += 10_000; cache.write(keys[3], "code")
        assertNull("the least recently used goes", cache.read(keys[1]))
        listOf(keys[0], keys[2], keys[3]).forEach { assertTrue(it, cache.read(it) != null) }
        File(folder, "${keys[4]}.js.partial").writeText("half")
        now += 10_000; cache.write(keys[4], "code")
        assertEquals(3, folder.listFiles().orEmpty().count { it.name.endsWith(".js") })
        assertEquals("a half-written file is cleared", 0, folder.listFiles().orEmpty().count { it.name.endsWith(".partial") })
    }

    @Test fun comingBackToTheAppWarmsTheStreamsAtMostOnceInFiveMinutes() {
        assertTrue(streamRewarmDue(null, 0L))
        assertFalse(streamRewarmDue(1_000L, 1_000L + STREAM_REWARM_INTERVAL_MS - 1))
        assertTrue(streamRewarmDue(1_000L, 1_000L + STREAM_REWARM_INTERVAL_MS))
        assertEquals(5 * 60_000L, STREAM_REWARM_INTERVAL_MS)
    }
}
