package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

/**
 * Q12 (UX02, UX09): loading, nothing found and something failed are three different screens. A failure is
 * never drawn as an empty list, it stays until it is retried, and its action fits what went wrong.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContentStateUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }
    private val player = RecordingGateway()
    private val notices = mutableListOf<String>()

    private fun search(model: ShellModel, route: (ProviderWorld.Call) -> ProviderWorld.Reply): ProviderWorld {
        val w = ProviderWorld(context).also { world = it }
        w.route = route
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                CatalogSearchScreen(model, 0.dp, player.gateway, w.provider) { notices += it }
            }
        }
        return w
    }
    private fun songs() = ShellModel().apply { searchQuery = "fixture"; searchFilter = "Songs" }
    private fun offline() = ProviderWorld.Reply("", failure = UnknownHostException("fixture: no network"))

    // ---- the three states, on the Search screen

    @Test fun whileSearchingAPlaceholderOfFixedSizeIsShownAndNeitherEmptyNorFailed() {
        compose.mainClock.autoAdvance = false
        search(songs()) { reply("search-songs.json") }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("list_skeleton").assertExists().assertHeightIsAtLeast(RiffSpace.listRow * 6)
        assertEquals(0, compose.count("content_empty")); assertEquals(0, compose.count("content_problem"))
    }

    @Test fun aSearchThatFindsNothingSaysSoAndIsNotAFailure() {
        search(songs()) { reply("search-empty.json") }
        compose.awaitTag("content_empty")
        compose.onNodeWithTag("content_empty").assertTextEquals("No matching results.")
        assertEquals(0, compose.count("content_problem")); assertEquals(0, compose.count("list_skeleton"))
    }

    @Test fun aSearchThatFailsIsNeverShownAsNoResultsAndRetryAsksAgain() {
        var fail = true
        val w = search(songs()) { if (fail) offline() else reply("search-songs.json") }
        compose.awaitTag("content_problem")
        compose.onNodeWithTag("content_problem_message", useUnmergedTree = true).assertTextEquals(OFFLINE_MESSAGE)
        compose.onNodeWithTag("content_problem_action").assertTextEquals("Retry")
        assertEquals("A failure is not an empty result", 0, compose.count("content_empty"))
        // It stays: time passing does not clear it, and nothing is asked again by itself.
        val asked = w.calls.size
        compose.mainClock.advanceTimeBy(30_000)
        assertEquals(1, compose.count("content_problem")); assertEquals(asked, w.calls.size)

        fail = false
        compose.onNodeWithTag("content_problem_action").performClick()
        compose.awaitTag("catalog_song_fxsong00001")
        assertEquals(0, compose.count("content_problem"))
        assertEquals("Said on the screen, not in a passing message", emptyList<String>(), notices)
    }

    @Test fun aBlankSearchInvitesAndAsksTheProviderNothing() {
        val w = search(ShellModel()) { error("Nothing is searched for an empty query") }
        compose.awaitTag("content_empty")
        compose.onNodeWithTag("content_empty").assertTextEquals("Find your next listen.")
        assertTrue(w.calls.isEmpty())
    }

    @Test fun aNextPageThatFailsKeepsTheRowsAndSaysSoBesideTheButton() {
        var fail = false
        search(songs()) { if (it.continuation == null) reply("search-songs.json") else if (fail) offline() else reply("search-songs-page-2.json") }
        compose.awaitTag("catalog_song_fxsong00001")
        fail = true
        compose.reach("catalog_load_more"); compose.onNodeWithTag("catalog_load_more").performClick()
        compose.waitUntil(5_000) { runCatching { compose.reach("load_more_problem") }.isSuccess }
        compose.onNodeWithTag("load_more_problem").assertTextEquals(OFFLINE_MESSAGE)
        assertEquals("The whole list is not replaced by the failure", 0, compose.count("content_problem"))
        compose.reach("catalog_song_fxsong00001")
        // The same button tries again, and the message goes when it works.
        fail = false
        compose.reach("catalog_load_more"); compose.onNodeWithTag("catalog_load_more").performClick()
        compose.waitUntil(5_000) { runCatching { compose.reach("catalog_song_fxsong00003") }.isSuccess }
        assertEquals(0, compose.count("load_more_problem"))
    }

    @Test fun clearingTheQueryEmptiesTheFieldAndTheResults() {
        val model = songs()
        search(model) { reply("search-songs.json") }
        compose.awaitTag("catalog_song_fxsong00001")
        compose.onNodeWithTag("search_clear", useUnmergedTree = true).performClick()
        compose.awaitNoTag("catalog_song_fxsong00001")
        assertEquals("", model.searchQuery); assertEquals(0, compose.count("search_clear"))
    }

    // ---- the action fits the failure

    private fun problem(message: String?, signIn: Boolean): Pair<MutableList<String>, String> {
        val pressed = mutableListOf<String>()
        compose.setContent { ContentProblem(message, onRetry = { pressed += "retry" }, onSignIn = if (signIn) ({ pressed += "sign in" }) else null) }
        compose.onNodeWithTag("content_problem_action").assertHeightIsAtLeast(RiffSpace.touch).performClick()
        return pressed to compose.onNodeWithTag("content_problem_action").fetchSemanticsNode().config
            .first { it.key.name == "Text" }.value.toString()
    }
    @Test fun noConnectionOffersRetry() = assertEquals(listOf("retry"), problem(OFFLINE_MESSAGE, true).first)
    @Test fun aTimeoutOffersRetry() = assertEquals(listOf("retry"), problem(TIMEOUT_MESSAGE, true).first)
    @Test fun anExpiredSessionOffersSignInBecauseRetryCannotHelp() {
        val (pressed, _) = problem(SESSION_EXPIRED_MESSAGE, true)
        assertEquals(listOf("sign in"), pressed)
        compose.onNodeWithTag("content_problem_action").assertTextEquals("Sign in")
    }
    @Test fun anExpiredSessionWhereSigningInIsNotOnOfferFallsBackToRetry() =
        assertEquals(listOf("retry"), problem(SESSION_EXPIRED_MESSAGE, false).first)
    @Test fun aFailureWithNoWordsStillSaysSomething() {
        problem(null, false)
        compose.onNodeWithTag("content_problem_message", useUnmergedTree = true).assertTextEquals(ProviderFault.Unknown.message)
    }
    @Test fun theKindOfAFailureComesFromTheFixedSentences() {
        assertEquals(ProblemKind.Offline, problemKind(OFFLINE_MESSAGE)); assertEquals(ProblemKind.Offline, problemKind(TIMEOUT_MESSAGE))
        assertEquals(ProblemKind.SessionExpired, problemKind(SESSION_EXPIRED_MESSAGE))
        assertEquals(ProblemKind.Unavailable, problemKind("anything else")); assertEquals(ProblemKind.Unavailable, problemKind(null))
    }

    // ---- a collection

    @Test fun aCollectionThatCannotBeLoadedSaysSoWithItsNameAndRetryLoadsIt() {
        var fail = true
        val w = ProviderWorld(context).also { world = it }
        w.route = { if (fail) ProviderWorld.Reply("", failure = IOException("fixture: connection reset")) else reply("album.json") }
        val model = ShellModel().apply { openCollection("MPREb_fixtureAlbum01", "Fixture album") }
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                CatalogDetailScreen(model, 0.dp, w.provider, player.gateway) { notices += it }
            }
        }
        compose.awaitTag("content_problem")
        assertEquals(0, compose.count("content_empty")); assertEquals(0, compose.count("list_skeleton"))
        compose.onNodeWithTag("back").assertExists()
        fail = false
        compose.onNodeWithTag("content_problem_action").performClick()
        compose.awaitTag("album_detail")
        assertEquals(0, compose.count("content_problem"))
    }
}
