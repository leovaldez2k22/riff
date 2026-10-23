package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q18-104: Rename and Delete are offered on a playlist the account can edit, never on one it cannot. Delete asks first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaylistManageUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }
    private val notices = mutableListOf<String>()

    private fun shell(id: String, fixture: String): ProviderWorld {
        val model = ShellModel().apply { select("Library"); openCollection(id, "Fixture list") }
        val w = ProviderWorld(context).also { world = it }
        w.route = { call ->
            when (call.endpoint) {
                "account/account_menu" -> reply("account-menu.json") // the session is checked before account actions are offered
                "browse/edit_playlist" -> ProviderWorld.Reply("""{"responseContext":{},"status":"STATUS_SUCCEEDED"}""")
                "playlist/delete" -> ProviderWorld.Reply("""{"responseContext":{}}""")
                else -> if (call.continuation == null) reply(fixture) else ProviderWorld.Reply(fixtureCollectionPage(10..12))
            }
        }
        w.provider.checkSessionIfDue(true) // background work is off in this world: check the session as a resume would
        val ambient = mutableStateOf(AmbientPalette(Color.Black, Color.Black))
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                LaunchedEffect(Unit) { while (true) delay(100) }
                ShellScene(model, true, true, RecordingGateway().gateway, LocalCollections(context), w.provider, ambient, { notices += it })
            }
        }
        compose.awaitTag("playlist_detail"); compose.awaitTag("collection_summary")
        return w
    }

    @Test fun aReadOnlyPlaylistOffersNeither() {
        shell("VLPLfixtureReadonly000001", "playlist-readonly.json")
        compose.waitForIdle()
        assertEquals(0, compose.count("playlist_options"))
    }

    @Test fun deleteAsksNamingThePlaylistAndCancelSendsNothing() {
        val w = shell("VLPLfixtureOwned0000000001", "playlist-owned.json")
        compose.awaitTag("playlist_options")
        compose.onNodeWithTag("playlist_options").performClick()
        compose.onNodeWithText("Delete playlist").performClick()
        compose.awaitTag("delete_playlist_dialog")
        compose.onNodeWithText("The playlist is deleted from your YouTube Music account too, everywhere you use it. This cannot be undone.").assertExists()
        compose.onNodeWithTag("delete_playlist_cancel").performClick()
        compose.awaitNoTag("delete_playlist_dialog")
        assertTrue(w.calls.none { it.endpoint == "playlist/delete" })
        compose.onNodeWithTag("playlist_options").performClick()
        compose.onNodeWithText("Delete playlist").performClick()
        compose.onNodeWithTag("delete_playlist_confirm").performClick()
        compose.waitUntil(WAIT_MS) { "Playlist deleted" in notices }
        assertEquals(1, w.calls.count { it.endpoint == "playlist/delete" })
        compose.awaitNoTag("playlist_detail")
    }

    @Test fun aRenameShowsTheNewName() {
        val w = shell("VLPLfixtureOwned0000000001", "playlist-owned.json")
        compose.awaitTag("playlist_options")
        compose.onNodeWithTag("playlist_options").performClick()
        compose.onNodeWithText("Rename playlist").performClick()
        compose.awaitTag("rename_playlist_dialog")
        compose.onNodeWithTag("rename_playlist_name").performTextReplacement("Road trip")
        compose.onNodeWithTag("rename_playlist_confirm").performClick()
        compose.waitUntil(WAIT_MS) { "Playlist renamed" in notices }
        assertEquals(1, w.calls.count { it.endpoint == "browse/edit_playlist" })
        compose.onNodeWithText("Road trip").assertExists()
    }
}
