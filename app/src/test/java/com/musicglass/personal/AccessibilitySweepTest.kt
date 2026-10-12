package com.musicglass.personal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Q13: every screen that can be drawn off-device, at each text size (1.0, 1.3, 1.6, 2.0) and in both reading
 * directions. For everything that can be pressed: the region that takes the press is at least 48 dp each way,
 * it does not share that region with a neighbour, it says what it is, and it is inside the screen. Synthetic
 * content only. The three classes below are the same screens on a narrow upright phone, the same phone on its
 * side, and a window as wide as a tablet's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // text is only measured at its real size in this mode
class AccessibilitySweepTest : AccessibilitySweepCases() { override val pictures = listOf(1f, 2f) }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySweepLandscapeTest : AccessibilitySweepCases()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w840dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySweepLargeWindowTest : AccessibilitySweepCases()

/**
 * The same screens with every string from strings.xml made a third longer and marked, as a translation would
 * be. Words on screen that are not marked did not come from strings.xml, and could never be translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PseudoLocaleSweepTest : AccessibilitySweepCases() { override val pseudo = true; override val pictures = listOf(1f) }

@Suppress("DEPRECATION") // the only constructor there is
internal class PseudoResources(private val real: Resources) : Resources(real.assets, real.displayMetrics, real.configuration) {
    private fun mark(text: String) = "[$text ${"~".repeat(text.length * 3 / 10 + 1)}]"
    override fun getString(id: Int): String = mark(real.getString(id))
    override fun getString(id: Int, vararg formatArgs: Any?): String = mark(real.getString(id, *formatArgs))
    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = mark(real.getQuantityString(id, quantity, *formatArgs))
}

abstract class AccessibilitySweepCases {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    protected open val pseudo = false
    /** The text sizes at which each screen is also saved as a picture, in both directions, for a person to look at. */
    protected open val pictures = emptyList<Float>()
    @get:Rule val test = TestName()
    private fun picture(scale: Float, way: LayoutDirection) {
        // Drawn straight from the view: under Robolectric nothing ever reaches a screen to be copied from.
        val view = (compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).last().root as androidx.compose.ui.platform.ViewRootForTest).view
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        assertTrue("the picture of ${test.methodName} is blank", pixels.distinct().size > 2)
        val folder = java.io.File("build/reports/q13-screens").apply { mkdirs() }
        java.io.File(folder, "${test.methodName}-${if (pseudo) "pseudo" else "text"}${(scale * 100).toInt()}-${way.name.lowercase()}.png").outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @After fun close() { world?.close(); AppStrings.install(context.resources) }
    @Before fun language() { if (pseudo) AppStrings.install(PseudoResources(context.resources)) }
    // Nothing here plays anything, so the solver process is never needed; without this a late connection
    // attempt lands in whichever test happens to be running.
    @Before fun noSolver() { shadowOf(context as Application).declareComponentUnbindable(ComponentName(context, SolverService::class.java)) }

    private var fontScale by mutableFloatStateOf(1f)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private val track = PlaybackUiState(ready = true, playing = true, wantsPlayback = true, mediaId = "fxsong00001",
        title = "Fixture Song With A Title Long Enough To Be Cut Off At Any Size", artist = "Fixture Artist", durationMs = 200_000, positionMs = 65_000,
        source = "YouTube Music", index = 1,
        queue = (1..6).map { QueueEntry("fxsong0000$it", "Fixture Song $it", "Fixture Artist", queueId = "q$it") })
    private val player = RecordingGateway(track)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context),
                LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides direction) {
                // A change of direction comes with a change of language, and that starts the activity again: the
                // screen is built afresh for each direction. Text size changes while it is shown, as it does in the app.
                RiffTheme { key(direction) { Box(Modifier.fillMaxSize().background(Ink)) { content() } } }
            }
        }
    }

    private fun provider(signedIn: Boolean = true, route: (ProviderWorld.Call) -> ProviderWorld.Reply = { error("unscripted ${it.endpoint}") }) =
        ProviderWorld(context, signedIn).also { world = it; it.route = route }.provider

    private fun SemanticsNode.said(): String = (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.said() }).joinToString(" ").trim()
    private fun SemanticsNode.tag() = config.getOrNull(SemanticsProperties.TestTag) ?: "node $id"
    private fun SemanticsNode.within(other: SemanticsNode) = generateSequence(parent) { it.parent }.any { it.id == other.id }

    /** What is wrong with the pressable things now on screen; empty when nothing is. */
    private fun problems(): List<String> {
        val scale = compose.density.density
        val min = 48 * scale - 0.5f
        val width = context.resources.configuration.screenWidthDp * scale
        val pressable = compose.onAllNodes(SemanticsMatcher("can be pressed") {
            it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled)
        }).fetchSemanticsNodes(atLeastOneRootRequired = false)
        // Scrolled out of sight is not out of reach: such a node has no region until it is scrolled to.
        // Nor is one cut off by the edge of its scroller: it is whole once scrolled to.
        return pressable.filterNot { scrolls(it) && (it.touchBoundsInRoot.isEmpty ||
            it.size.width >= min && it.size.height >= min && (it.touchBoundsInRoot.width < min || it.touchBoundsInRoot.height < min)) }.flatMap { node ->
            val name = node.said().take(40).ifBlank { node.tag() }
            val touch = node.touchBoundsInRoot; val seen = node.boundsInRoot
            // A small control borrows the space around it. That is only a 48 dp target if no neighbour claims the
            // same space: where two regions overlap by more than a hair, a press there can go to either.
            val shared = if (seen.width >= min && seen.height >= min) null else pressable.firstOrNull { other ->
                other.id != node.id && !other.within(node) && !node.within(other) &&
                    other.touchBoundsInRoot.intersect(touch).let { it.width > 2 * scale && it.height > 2 * scale }
            }
            listOfNotNull(
                "'$name' takes presses in ${(touch.width / scale).toInt()} x ${(touch.height / scale).toInt()} dp".takeIf { touch.width < min || touch.height < min },
                shared?.let { "'$name' (${dp(seen)}, pressed in ${dp(touch)}) shares its press region with '${it.said().take(40).ifBlank { it.tag() }}' (${dp(it.touchBoundsInRoot)})" },
                "'${node.tag()}' does not say what it is".takeIf { node.said().isBlank() },
                "'$name' is outside the screen".takeIf { seen.width > 0 && (seen.right <= 0f || seen.left >= width) && !scrollsSideways(node) })
        }
    }
    /** Not words of ours: the fixture's own titles and names, and names that are the same in every language. */
    private val data = listOf(Regex("Fixture Song With A Title Long Enough To Be Cut Off At Any Size"), Regex("Fixture (Song|Artist|list)( \\d)?"), Regex("Row \\d"), Regex("YouTube Music"), Regex("Riff"))
    private val marked = Regex("\\[[^\\[\\]]*]")
    /** Words on screen that did not come from strings.xml. */
    private fun unmarked(): List<String> = compose.onAllNodes(SemanticsMatcher("anything") { true }, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).flatMap { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(node.config.getOrNull(SemanticsProperties.StateDescription))
    }.distinct().filter { said ->
        var rest = said
        while (true) { val next = marked.replace(rest, ""); if (next == rest) break; rest = next }
        data.fold(rest) { left, known -> known.replace(left, "") }.any { it.isLetter() }
    }.map { "'$it' is not from strings.xml" }
    private fun dp(r: androidx.compose.ui.geometry.Rect) = compose.density.density.let { "${(r.left / it).toInt()},${(r.top / it).toInt()} ${(r.width / it).toInt()}x${(r.height / it).toInt()}" }
    private fun scrolls(node: SemanticsNode): Boolean = generateSequence(node.parent) { it.parent }.any {
        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) || it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
    private fun scrollsSideways(node: SemanticsNode): Boolean =
        generateSequence(node.parent) { it.parent }.any { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }

    /** The screen already shown, looked at again at every text size and in both directions. */
    private fun everywhere(settle: () -> Unit = { compose.waitForIdle() }) {
        val found = linkedMapOf<String, MutableList<String>>()
        for (scale in listOf(1f, 1.3f, 1.6f, 2f)) for (way in LayoutDirection.entries) {
            fontScale = scale; direction = way
            try { settle(); problems() } catch (e: Throwable) { throw AssertionError("never settled at $scale ${way.name}", e) }
            (problems() + if (pseudo) unmarked() else emptyList()).forEach { found.getOrPut(it) { mutableListOf() } += "$scale ${way.name}" }
            if (scale in pictures) picture(scale, way)
        }
        assertEquals(emptyList<String>(), found.map { (problem, where) -> if (where.size == 8) problem else "$problem at $where" })
    }

    private fun clean(content: @Composable () -> Unit) { show(content); everywhere() }

    @Test fun searchResults() {
        val p = provider { reply("search-songs.json") }
        val model = ShellModel().apply { searchQuery = "fixture"; searchFilter = "Songs" }
        show { CatalogSearchScreen(model, 0.dp, player.gateway, p) {} }
        everywhere { compose.awaitTag("catalog_song_fxsong00001") }
    }
    @Test fun searchFailure() {
        val p = provider { ProviderWorld.Reply("{}", status = 500) }
        show { CatalogSearchScreen(ShellModel().apply { searchQuery = "fixture" }, 0.dp, player.gateway, p) {} }
        everywhere { compose.awaitTag("content_problem") }
    }
    @Test fun librarySignedOut() { val p = provider(signedIn = false); clean { AccountLibraryScreen(ShellModel().apply { select("Library") }, 0.dp, LocalCollections(context), player.gateway, p) {} } }
    @Test fun settings() { val p = provider(); clean { SettingsScreen(ShellModel(), 0.dp, p, LocalCollections(context)) {} } }
    @Test fun account() { val p = provider(); clean { AccountScreen(ShellModel(), p, LocalCollections(context), player.gateway, {}, SessionCoordinator({ p.session.value })) } }
    @Test fun downloads() = clean { DownloadsScreen(ShellModel(), 0.dp, player.gateway) {} }
    @Test fun notInterested() = clean { NotInterestedScreen(ShellModel(), 0.dp) {} }
    @Test fun collection() {
        val p = provider()
        val rows = (1..3).map { ProviderHomeCard("Row $it", "Fixture Artist", null, videoId = fixtureVideoId(it), setVideoId = "row-set-$it") }
        val model = ShellModel().apply { openCollection("VLPLfixtureOwned0000000001", "Fixture list") }
        clean { ImmersiveCollectionDetail(model, 0.dp, CatalogPage(rows, title = "Fixture list", albumTracks = rows, editable = true), p, player.gateway, {}) }
    }
    @Test fun player() {
        val p = provider()
        clean { NowPlayingPanel(player.gateway, LocalCollections(context), p, onClose = {}, onSwipeDrag = { _, _ -> }, onSwipeRelease = {},
            onQueue = {}, onLibrary = {}, onLyrics = {}, onArtist = {}, onAlbum = {}, onFailureAction = {}, onNotice = {}) }
    }
    @Test fun queue() { val p = provider(); clean { InlineQueue(player.gateway, p, {}, {}, {}) } }
    @Test fun miniPlayer() = clean { Box(Modifier.fillMaxWidth().height(62.dp).background(Raised)) { MiniPlayerContents(player.gateway, {}) } }
    @Test fun positionAndVolume() = clean { Column { SeekControl(player.gateway, "fxsong00001", 200_000); AudioOutputControls({}, {}) } }
    // The sleep timer dialog is not here: a text field inside a dialog never goes idle under Robolectric (an
    // AlertDialog holding nothing but an OutlinedTextField does the same), so it is on the device checklist instead.
    @Test fun lyricsTiming() = clean { LyricsTimingDialog(DEFAULT_LYRICS_OFFSET_MS, {}) {} }
    @Test fun sessionBannerAndProblems() = clean {
        Column { SessionExpiredBanner(onSignIn = {}, onDismiss = {}); ContentProblem(OFFLINE_MESSAGE, {}); ContentProblem(SESSION_EXPIRED_MESSAGE, {}, onSignIn = {}) }
    }
    @Test fun tabs() = clean { Box(Modifier.fillMaxWidth().height(64.dp)) { LiquidTabBar(ShellModel(), true, 64.dp) } }
}
