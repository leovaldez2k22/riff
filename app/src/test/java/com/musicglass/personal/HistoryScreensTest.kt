package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

/** History and Earlier data as the user meets them; synthetic session fields and plays, no account, no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class HistoryScreensTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val notices = mutableListOf<String>()
    private val restoreRequests = mutableListOf<Unit>()
    private var container: AppContainer? = null
    @After fun close() { container?.let { it.existingProvider()?.close(); it.libraryState.close() } }

    private fun runtime() = RuntimeDependencies(
        networkConditions = { NetworkConditions(false, false, false) },
        storageCapacity = { StorageCapacity(0, 1_000_000) },
        batteryPercent = { 15 },
        httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
        clocks = FakeClock(), dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
        providerTransport = { OriginalAudioEngine.create { config { addInterceptor { throw IOException("Unexpected provider HTTP request") } } } },
        resolver = ScriptedResolver(), storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)

    /** Signed in with a verified synthetic identity, or unverified for null. */
    private fun start(syncId: String? = "fixture-sync-A"): AppContainer {
        context.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear()
            .putString("sync", syncId).putString("account", "0").commit()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture")
        return AppContainer(context, runtime()).also { container = it; runBlocking { it.stateReady.await() } }
    }

    /** Only the request to load the saved queue is expected; it reports that the queue loaded. */
    private val gateway: PlaybackGateway = Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args[0]
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "fixture gateway"
            "restoreSavedQueue" -> { restoreRequests += Unit; @Suppress("UNCHECKED_CAST") (args[0] as (Boolean) -> Unit)(true) }
            else -> throw AssertionError("Unexpected playback call ${method.name}")
        }
    } as PlaybackGateway

    private var serial = 0
    private fun AppContainer.play(owner: String, title: String, id: String = "local:${serial + 1}") =
        assertEquals(StoreOutcome.Done, history.record(HistoryEntry(id, title, "Artist", null, 1_800_000_000_000L - serial, eventId = "e-${serial++}"), history.ticket(owner)))
    private fun line(id: String, title: String, at: Long) = encodeHistoryEntry(HistoryEntry(id, title, "Artist", null, at))
    private fun writeLegacyHistory() = File(context.filesDir, "listening-history/history-v1.jsonl").apply { parentFile!!.mkdirs() }.writeText(
        listOf(line("local:1", "Old memo", 1_000), line("yt:fixtureA001", "Unassigned one", 2_000), line("yt:fixtureB001", "Unassigned two", 3_000),
            line("yt:fixtureA002", "Unassigned three", 4_000)).joinToString("") { it + "\n" })
    private fun writeLegacyQueue() {
        val old = encodePlaybackSnapshot(PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Queued one", "Artist"), SavedQueueItem("yt:fixtureA002", "Queued two", "Artist")), 1, 9_000))
            .replace("\"format\":\"$SNAPSHOT_FORMAT\",", "").replace("\"version\":2", "\"version\":1")
        context.getSharedPreferences("last_playback", Context.MODE_PRIVATE).edit().putString("snapshot", old).commit()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    /** A timeout names what was on screen instead, so a failure can be read without rerunning. */
    private fun await(what: String, condition: () -> Boolean) = try { compose.waitUntil(10_000, condition) } catch (error: ComposeTimeoutException) {
        throw AssertionError("Timed out waiting for $what. On screen: " + compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString(" | ") { root ->
            fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
            texts(root).joinToString("; ")
        }, error)
    }
    private fun awaitText(text: String) = await("text \"$text\"") { shown(text) }
    private fun awaitTag(tag: String) = await("tag $tag") { tagged(tag) }
    private fun tap(tag: String) { awaitTag(tag); compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) }

    private fun history(process: AppContainer, model: ShellModel = ShellModel()) = compose.setContent {
        ListeningHistoryScreen(model, 0.dp, process.provider, gateway, { notices += it }, process)
    }
    private fun earlier(process: AppContainer) = compose.setContent {
        EarlierDataScreen(ShellModel(), 0.dp, process.provider, gateway, { notices += it }, process)
    }

    @Test fun historyShowsTheActiveOwnersPlaysAndKeepsOnDevicePlaysApart() {
        val process = start()
        val owner = process.provider.storageScopeKey()
        assertNotEquals(UNVERIFIED_SCOPE_KEY, owner); assertNotEquals(GUEST_SCOPE_KEY, owner)
        process.play(DEVICE_LOCAL_SCOPE_KEY, "Voice memo")
        process.play(owner, "Mine on this account")
        process.play("fixture-other-owner", "Someone else's play")
        process.play(GUEST_SCOPE_KEY, "Signed-out play")
        history(process)
        awaitText("Mine on this account")
        assertFalse(shown("Voice memo")); assertFalse(shown("Someone else's play")); assertFalse(shown("Signed-out play"))
        assertFalse(tagged("history_earlier")); assertFalse(tagged("history_unverified"))
        tap("history_scope_device")
        awaitText("Voice memo")
        assertFalse(shown("Mine on this account")); assertFalse(shown("Someone else's play"))
        tap("history_scope_account")
        awaitText("Mine on this account")
    }

    @Test fun aNewPlayAppearsWithoutReopeningTheScreen() {
        val process = start()
        history(process)
        awaitText("Songs you play will appear here.")
        process.play(process.provider.storageScopeKey(), "Just played")
        awaitText("Just played")
    }

    @Test fun olderPlaysLoadInPagesOnRequest() {
        val process = start()
        val owner = process.provider.storageScopeKey()
        repeat(HISTORY_PAGE_SIZE + 5) { process.play(owner, "Play %03d".format(it)) }
        history(process)
        awaitText("${HISTORY_PAGE_SIZE + 5} plays · kept on this phone only")
        assertFalse("the oldest five are not loaded yet", shown("Play 104"))
        val list = compose.onNode(hasScrollToNodeAction())
        list.performScrollToNode(hasTestTag("history_more"))
        tap("history_more")
        compose.waitUntil(10_000) { runCatching { list.performScrollToNode(hasText("Play 104")) }.isSuccess }
        assertFalse(tagged("history_more"))
    }

    @Test fun unverifiedAccountShowsNoAccountHistoryButStillShowsThePhones() {
        val process = start(syncId = null)
        assertEquals(UNVERIFIED_SCOPE_KEY, process.provider.storageScopeKey())
        process.play(DEVICE_LOCAL_SCOPE_KEY, "Voice memo")
        process.play(GUEST_SCOPE_KEY, "Signed-out play")
        history(process)
        awaitTag("history_unverified")
        assertFalse(shown("Signed-out play")); assertFalse(shown("Voice memo"))
        tap("history_scope_device")
        awaitText("Voice memo")
    }

    @Test fun earlierPlaysAreAnnouncedNotShownAndTheCardOpensTheReview() {
        writeLegacyHistory(); writeLegacyQueue()
        val process = start()
        val model = ShellModel()
        history(process, model)
        awaitTag("history_earlier")
        awaitText("3 plays and a queue of 2 songs from before this update are not assigned to an account.")
        assertFalse(shown("Unassigned one")); assertFalse(shown("Old memo"))
        tap("history_scope_device")
        awaitText("Old memo")
        tap("history_earlier_review")
        assertEquals("earlier_data", model.route)
    }

    @Test fun clearOnTheHistoryScreenRemovesOnlyTheListShownAndCancelRemovesNothing() {
        val process = start()
        val owner = process.provider.storageScopeKey()
        repeat(2) { process.play(owner, "Account play $it") }
        process.play(DEVICE_LOCAL_SCOPE_KEY, "Voice memo"); process.play("fixture-other-owner", "Someone else's play")
        var refreshed = 0
        compose.setContent { ListeningHistoryScreen(ShellModel(), 0.dp, process.provider, gateway, { notices += it }, process) { refreshed++ } }
        awaitText("2 plays · kept on this phone only")
        tap("history_clear")
        awaitText("Clear 2 plays?")
        tap("history_clear_cancel")
        compose.waitUntil(10_000) { !tagged("history_clear_cancel") }
        assertEquals(2, process.history.count(owner)); assertTrue(notices.isEmpty()); assertEquals(0, refreshed)
        tap("history_clear"); tap("history_clear_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(listOf("2 plays cleared"), notices); assertEquals(1, refreshed)
        assertEquals(0, process.history.count(owner))
        assertEquals(1, process.history.count(DEVICE_LOCAL_SCOPE_KEY)); assertEquals(1, process.history.count("fixture-other-owner"))
        awaitText("Songs you play will appear here.")
        assertFalse("nothing left to clear", tagged("history_clear"))
        // The phone's list is cleared separately, and a play made afterwards is kept.
        tap("history_scope_device")
        awaitText("Voice memo")
        tap("history_clear"); awaitText("Clear 1 play?"); tap("history_clear_confirm")
        compose.waitUntil(10_000) { notices.size == 2 }
        assertEquals(0, process.history.count(DEVICE_LOCAL_SCOPE_KEY))
        process.play(DEVICE_LOCAL_SCOPE_KEY, "Played after the clear")
        awaitText("Played after the clear")
    }

    @Test fun earlierSearchesAreImportedOrDeletedOnlyWhenTheUserSaysSo() {
        context.getSharedPreferences("search_history", Context.MODE_PRIVATE).edit().putString("queries", "[\"mixed one\",\"mixed two\"]").commit()
        SearchHistory::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
        val process = start()
        val owner = process.provider.storageScopeKey()
        assertEquals(UnassignedInventory(searches = 2), process.legacyState.inventory())
        earlier(process)
        awaitTag("earlier_search_summary")
        tap("earlier_import_searches")
        awaitText("Import 2 searches?")
        tap("earlier_cancel")
        compose.waitUntil(10_000) { !tagged("earlier_cancel") }
        assertEquals(0, runBlocking { SearchHistory.get(context).count(owner) }); assertEquals(2, legacySearchCount(context))
        tap("earlier_import_searches"); tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertTrue(notices.single(), notices.single().startsWith("2 searches added for "))
        assertEquals(2, runBlocking { SearchHistory.get(context).count(owner) }); assertEquals(0, legacySearchCount(context))
        awaitTag("earlier_empty")
    }

    @Test fun earlierSearchesCanBeDeletedWithoutImporting() {
        context.getSharedPreferences("search_history", Context.MODE_PRIVATE).edit().putString("queries", "[\"mixed one\"]").commit()
        SearchHistory::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
        val process = start(syncId = null)
        earlier(process)
        awaitTag("earlier_delete_searches")
        assertFalse("an unverified account cannot take them", tagged("earlier_import_searches"))
        tap("earlier_delete_searches"); awaitText("Delete 1 earlier search?"); tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(listOf("Earlier searches deleted"), notices); assertEquals(0, legacySearchCount(context))
    }

    @Test fun earlierDataSummaryReadsNaturallyForEachCombination() {
        assertEquals("1 search from before this update is not assigned to an account.", earlierDataSummary(UnassignedInventory(searches = 1)))
        assertEquals("2 plays, a queue of 1 song and 3 searches from before this update are not assigned to an account.", earlierDataSummary(UnassignedInventory(2, 1, 1, 3)))
        assertEquals("1 play from before this update is not assigned to an account.", earlierDataSummary(UnassignedInventory(1, 1, 0)))
        assertEquals("2 plays from before this update are not assigned to an account.", earlierDataSummary(UnassignedInventory(2, 1, 0)))
        assertEquals("a queue of 1 song from before this update is not assigned to an account.", earlierDataSummary(UnassignedInventory(0, 0, 1)))
        assertEquals("1 play and a queue of 5 songs from before this update are not assigned to an account.", earlierDataSummary(UnassignedInventory(1, 1, 5)))
    }

    @Test fun cancellingAnImportChangesNothingAndConfirmingImportsIntoTheActiveOwnerOnly() {
        writeLegacyHistory()
        val process = start()
        val owner = process.provider.storageScopeKey()
        earlier(process)
        tap("earlier_import")
        awaitText("Import 3 plays?")
        tap("earlier_cancel")
        compose.waitUntil(10_000) { !tagged("earlier_cancel") }
        assertEquals(0, process.history.count(owner)); assertTrue(notices.isEmpty())
        tap("earlier_import"); tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(1, notices.size); assertTrue(notices.single(), notices.single().startsWith("3 plays added to the history of "))
        assertEquals(3, process.history.count(owner)); assertEquals(0, process.history.count(GUEST_SCOPE_KEY))
        awaitTag("earlier_imported")
        assertFalse(tagged("earlier_import"))
        assertEquals("the unassigned copy stays until the user deletes it", 3, process.legacyState.inventory().providerPlays)
    }

    @Test fun deletingUnassignedPlaysAsksFirstAndLeavesImportedOnes() {
        writeLegacyHistory()
        val process = start()
        val owner = process.provider.storageScopeKey()
        assertEquals(3, process.legacyState.importProviderHistory(owner))
        earlier(process)
        tap("earlier_delete_history")
        awaitText("Delete 3 unassigned plays?")
        tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(listOf("Unassigned plays deleted"), notices)
        awaitTag("earlier_empty")
        assertEquals(3, process.history.count(owner))
    }

    @Test fun restoringTheEarlierQueueAsksFirstThenStoresItForTheActiveOwnerAndAsksThePlayerToLoadIt() {
        writeLegacyQueue()
        val process = start()
        val owner = process.provider.storageScopeKey()
        assertNull("nothing is adopted on upgrade", process.snapshots.newest(listOf(owner, GUEST_SCOPE_KEY, DEVICE_LOCAL_SCOPE_KEY)))
        earlier(process)
        awaitTag("earlier_queue_summary")
        tap("earlier_restore_queue")
        awaitText("Restore the earlier queue?")
        tap("earlier_cancel")
        compose.waitUntil(10_000) { !tagged("earlier_cancel") }
        assertNull(process.snapshots.load(owner)); assertTrue(restoreRequests.isEmpty())
        tap("earlier_restore_queue"); tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(listOf("Earlier queue restored, paused"), notices); assertEquals(1, restoreRequests.size)
        val stored = process.snapshots.load(owner)!!.snapshot
        assertEquals(listOf("Queued one", "Queued two"), stored.queue.map { it.title }); assertEquals(1, stored.index); assertEquals(9_000L, stored.positionMs)
        assertNull(process.snapshots.load(GUEST_SCOPE_KEY))
        awaitTag("earlier_empty")
    }

    @Test fun deletingTheEarlierQueueAsksFirst() {
        writeLegacyQueue()
        val process = start()
        earlier(process)
        tap("earlier_delete_queue"); tap("earlier_confirm")
        compose.waitUntil(10_000) { notices.isNotEmpty() }
        assertEquals(listOf("Earlier queue deleted"), notices); assertTrue(restoreRequests.isEmpty())
        assertTrue(process.legacyState.inventory().empty)
    }

    @Test fun unverifiedAccountCanDeleteEarlierDataButNotTakeIt() {
        writeLegacyHistory(); writeLegacyQueue()
        val process = start(syncId = null)
        earlier(process)
        awaitTag("earlier_delete_history"); awaitTag("earlier_delete_queue")
        assertFalse(tagged("earlier_import")); assertFalse(tagged("earlier_restore_queue"))
    }
}
