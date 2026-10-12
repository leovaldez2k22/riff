package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q12 (UX02, UX03, UX05): every open screen keeps its own place. A collection opened from another is a
 * second screen; Back returns to the first where it was left, and a closed screen starts again from the top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }
    private val first = "VLPLfixtureOwned0000000001"
    private val second = "VLPLfixtureOwned0000000002"
    private val player = RecordingGateway()

    /** Two playlists: the first long enough to scroll, the second short. Search answers with songs. */
    private fun shell(model: ShellModel): ProviderWorld {
        val w = ProviderWorld(context).also { world = it }
        var asked = ""
        w.route = { call ->
            call.browseId?.let { asked = it }
            when {
                call.endpoint == "search" -> reply("search-songs.json")
                call.continuation == null -> reply("playlist-owned.json")
                else -> ProviderWorld.Reply(fixtureCollectionPage(if (asked == first) 10..70 else 100..102))
            }
        }
        val ambient = mutableStateOf(AmbientPalette(Color.Black, Color.Black))
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                // Keeps the test scheduler turning, so an answer that arrived on another thread is picked up.
                LaunchedEffect(Unit) { while (true) delay(100) }
                ShellScene(model, true, true, player.gateway, LocalCollections(context), w.provider, ambient, {})
            }
        }
        compose.waitForIdle()
        return w
    }
    /** The collection screen is the only screen, it has been read to its end, and [row] is in view. */
    private fun atRow(row: Int) = compose.waitUntil(WAIT_MS) {
        compose.count("playlist_detail") == 1 && compose.count("collection_loading_more") == 0 && runCatching { compose.reach("album_track_$row") }.isSuccess
    }
    private fun onlyScreen(tag: String) = compose.waitUntil(WAIT_MS) { compose.count(tag) == 1 && compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1 }

    @Test fun backFromACollectionOpenedInsideAnotherReturnsToTheFirstWhereItWasLeft() {
        val model = ShellModel().apply { select("Search"); openCollection(first, "First list") }
        val w = shell(model)
        atRow(40)
        val left = compose.listOffset()
        assertTrue("The first list is scrolled", left > 0f)

        compose.runOnIdle { model.openCollection(second, "Second list") }
        runCatching { compose.waitUntil(WAIT_MS) { w.calls.any { it.browseId == second } } }.onFailure {
            fail("asked: " + w.calls.map { c -> c.endpoint + ":" + c.browseId + ":" + c.continuation } + " screen=" + model.screen.key + " tags=" + listOf("playlist_detail","content_problem","list_skeleton","back").map { tag -> tag + "=" + compose.count(tag) }) }
        compose.awaitNoTag("album_track_40"); onlyScreen("playlist_detail")
        assertEquals("The second list opens at its top, not where the first was", 0f, compose.listOffset())

        compose.runOnIdle { model.back() }
        compose.awaitTag("album_track_40"); onlyScreen("playlist_detail")
        assertEquals("The first list is where it was left", left, compose.listOffset())
        compose.onNodeWithTag("album_track_40").assertIsDisplayed()
    }

    @Test fun aCollectionThatWasClosedOpensFromItsTopAgain() {
        val model = ShellModel().apply { select("Search"); openCollection(first, "First list") }
        shell(model)
        atRow(40)
        compose.runOnIdle { model.back() }
        onlyScreen("search_input")
        compose.runOnIdle { model.openCollection(first, "First list") }
        onlyScreen("playlist_detail")
        assertEquals(0f, compose.listOffset())
    }

    @Test fun comingBackToSearchShowsTheSameResultsWithoutSearchingAgain() {
        val model = ShellModel().apply { select("Search"); searchQuery = "fixture"; searchFilter = "Songs" }
        val w = shell(model)
        compose.awaitTag("catalog_song_fxsong00001")
        assertEquals(1, w.endpoints().count { it == "search" })

        compose.runOnIdle { model.openCollection(first, "First list") }
        onlyScreen("playlist_detail")
        compose.runOnIdle { model.back() }
        compose.awaitTag("search_input")
        // No waiting: the rows are there in the first frame of the returned screen, and nothing was asked again.
        assertEquals(1, compose.count("catalog_song_fxsong00001"))
        assertEquals(0, compose.count("list_skeleton"))
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, w.endpoints().count { it == "search" })
    }

    @Test fun aNewQueryIsSearchedAndTheOldResultsAreNotShownForIt() {
        val model = ShellModel().apply { select("Search"); searchQuery = "fixture"; searchFilter = "Songs" }
        val w = shell(model)
        compose.awaitTag("catalog_song_fxsong00001")
        compose.runOnIdle { model.searchQuery = "another" }
        runCatching { compose.waitUntil(WAIT_MS) { w.calls.count { it.endpoint == "search" } == 2 } }.onFailure {
            fail("asked: " + w.calls.map { c -> c.endpoint + ":" + c.field("query") } + " tags=" + listOf("catalog_song_fxsong00001","content_problem","content_empty","list_skeleton").map { tag -> tag + "=" + compose.count(tag) }) }
        assertEquals("another", w.calls.last { it.endpoint == "search" }.field("query"))
    }

    @Test fun eachOpenScreenHasItsOwnKeyAndAClosedOneHasNone() {
        val model = ShellModel().apply { select("Search"); openCollection(first, "First list") }
        val a = model.screen.key
        model.openCollection(second, "Second list")
        val b = model.screen.key
        assertNotEquals(a, b)
        assertTrue(model.screenKeys.containsAll(listOf(a, b, "Search/0/", "Home/0/")))
        model.back()
        assertEquals(a, model.screen.key); assertFalse(b in model.screenKeys)
        // The same collection opened from itself is a second screen.
        model.openCollection(first, "First list")
        assertNotEquals(a, model.screen.key)
    }
}
