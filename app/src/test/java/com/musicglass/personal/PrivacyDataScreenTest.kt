package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy

/** Settings → Privacy and data as the user meets it; synthetic session fields and data, no account, no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp")
class PrivacyDataScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val notices = mutableListOf<String>()
    private val playbackCalls = mutableListOf<String>()
    private var container: AppContainer? = null
    private val processField = AppContainer::class.java.getDeclaredField("process").apply { isAccessible = true }
    private var previousProcess: Any? = null

    /** The stores are one per process; each test starts with its own. */
    @Before fun freshStores() {
        OfflineMusicStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        SearchHistory::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
        previousProcess = processField.get(null)
    }
    @After fun close() {
        processField.set(null, previousProcess)
        container?.let { it.existingProvider()?.close(); it.libraryState.close() }
    }

    private fun runtime() = RuntimeDependencies(
        networkConditions = { NetworkConditions(false, false, false) },
        storageCapacity = { StorageCapacity(0, 1_000_000) },
        batteryPercent = { 15 },
        httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
        clocks = FakeClock(), dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
        providerTransport = { OriginalAudioEngine.create { config { addInterceptor { throw IOException("Unexpected provider HTTP request") } } } },
        resolver = ScriptedResolver(), storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)

    /** Signed in with a verified synthetic identity, or unverified for null. The download store follows this container's provider. */
    private fun start(syncId: String? = "fixture-sync-A"): AppContainer {
        context.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear()
            .putString("sync", syncId).putString("account", "0").commit()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture")
        return AppContainer(context, runtime()).also { container = it; processField.set(null, it); runBlocking { it.stateReady.await() } }
    }

    /** Stopping the account's queue is the only playback request expected, and it empties the player. */
    private val gateway: PlaybackGateway = Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args[0]
            "hashCode" -> System.identityHashCode(self)
            "toString" -> "fixture gateway"
            "clearProviderPlayback" -> { playbackCalls += "clear"; Unit }
            "hasProviderItems" -> false
            else -> throw AssertionError("Unexpected playback call ${method.name}")
        }
    } as PlaybackGateway

    private val other = "fixture-other-owner"
    private var serial = 0
    private fun AppContainer.play(owner: String, at: Long = runtime.clocks.wallTimeMillis(), id: String = if (owner == DEVICE_LOCAL_SCOPE_KEY) "local:7" else "yt:fixtureA001") =
        assertEquals(StoreOutcome.Done, history.record(HistoryEntry(id, "Song", "Artist", null, at, eventId = "e-${serial++}"), history.ticket(owner)))
    private fun search(owner: String, vararg queries: String) = runBlocking { SearchHistory.get(context).let { s -> s.bind(owner); queries.forEach(s::record) } }
    private fun download(owner: String, id: String) {
        val store = OfflineMusicStore.get(context)
        store.enqueue(ProviderTrack(id, "Song", "Artist"), scope = owner)
        val dir = store.directory(owner, id).apply { mkdirs() }
        File(dir, "audio.media").writeBytes(byteArrayOf(1, 2, 3)); File(dir, "ready").writeText("3")
        store.update(owner, id, "state" to "complete", "bytes" to 3L)
    }
    private fun favorite(owner: String, id: String) = context.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit()
        .putString("account_${owner}_online_favorites", JSONArray(listOf(JSONObject().put("id", id).put("title", "Song").put("artist", "Artist"))).toString()).commit()
    private fun AppContainer.queue(owner: String) = assertEquals(StoreOutcome.Done,
        snapshots.save(snapshots.ticket(owner), PlaybackSnapshot(listOf(SavedQueueItem("yt:fixtureA001", "Song", "Artist")), 0, 0)))
    /** Three plays, two searches, two downloads, one saved track and a queue for [owner]. */
    private fun AppContainer.seed(owner: String) {
        repeat(3) { play(owner) }; search(owner, "first", "second"); download(owner, "fixtureD001"); download(owner, "fixtureD002"); favorite(owner, "fixtureF001"); queue(owner)
    }
    private fun summary(process: AppContainer, owner: String) = runBlocking { process.localData.summary(owner) }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun onScreen() = compose.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString(" | ") { root ->
        fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        texts(root).joinToString("; ")
    }
    private fun await(what: String, condition: () -> Boolean) = try { compose.waitUntil(10_000, condition) } catch (error: ComposeTimeoutException) {
        throw AssertionError("Timed out waiting for $what. On screen: ${onScreen()}", error)
    }
    private fun awaitText(text: String) = await("text \"$text\"") { shown(text) }
    private fun awaitTag(tag: String) = await("tag $tag") { tagged(tag) }
    private fun awaitGone(tag: String) = await("$tag to close") { !tagged(tag) }
    private fun tap(tag: String) { awaitTag(tag); compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) }
    private fun awaitNotice(text: String) { await("notice \"$text\", have $notices") { text in notices } }

    private fun show(process: AppContainer, model: ShellModel = ShellModel(), data: LocalDataController = process.localData): LocalCollections {
        val collections = LocalCollections(context, process.provider.accountScopeKey())
        compose.setContent { PrivacyDataScreen(model, 0.dp, process.provider, collections, gateway, { notices += it }, process, data) }
        return collections
    }

    // ---- what is offered

    @Test fun rowsShowWhatIsKeptForTheActiveOwnerAndTheDevice() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); process.play(other); repeat(2) { process.play(DEVICE_LOCAL_SCOPE_KEY) }
        show(process)
        awaitText("3 plays"); awaitText("2 plays"); awaitText("2 searches"); awaitText("2 songs")
        assertFalse(tagged("privacy_unverified")); assertFalse(tagged("settings_earlier_data"))
    }

    @Test fun anUnverifiedAccountIsOfferedOnlyWhatBelongsToThePhone() {
        val process = start(syncId = null)
        process.play(DEVICE_LOCAL_SCOPE_KEY)
        show(process)
        awaitTag("privacy_unverified"); awaitText("1 play")
        listOf("privacy_clear_account_history", "privacy_clear_searches", "privacy_delete_downloads", "privacy_delete_account_data").forEach { assertFalse(it, tagged(it)) }
        tap("privacy_clear_device_history"); tap("privacy_confirm")
        awaitNotice("1 play cleared")
    }

    @Test fun earlierDataIsLinkedWhileSomethingIsWaiting() {
        context.getSharedPreferences("search_history", Context.MODE_PRIVATE).edit().putString("queries", "[\"from before\"]").commit()
        val process = start(); val model = ShellModel()
        show(process, model)
        tap("settings_earlier_data")
        assertEquals("earlier_data", model.route)
    }

    // ---- cancel

    @Test fun cancellingAnyConfirmationChangesNothing() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); process.play(DEVICE_LOCAL_SCOPE_KEY)
        val before = summary(process, owner)
        show(process)
        awaitText("3 plays")
        listOf("privacy_clear_account_history" to "Clear 3 plays?", "privacy_clear_device_history" to "Clear 1 play?", "privacy_clear_searches" to "Clear 2 searches?",
            "privacy_delete_downloads" to "Delete 2 downloads?", "privacy_delete_account_data" to "Delete local data?").forEach { (row, title) ->
            tap(row); awaitText(title); awaitTag("privacy_prompt")
            tap("privacy_cancel"); awaitGone("privacy_cancel")
        }
        assertEquals(before, summary(process, owner))
        assertNotNull(OfflineMusicStore.get(context).audio(owner, "fixtureD001"))
        assertTrue(notices.isEmpty()); assertTrue(playbackCalls.isEmpty())
    }

    @Test fun everyConfirmationSaysWhoseDataThatItIsPermanentAndThatTheProviderIsNotChanged() {
        val data = LocalDataSummary(accountPlays = 3, devicePlays = 1, searches = 2, downloads = 2, savedTracks = 1, hasQueue = true)
        PrivacyAction.entries.forEach { action ->
            val prompt = privacyPrompt(action, "Fixture Listener", data, retentionDays = 30, retentionCount = 4)
            assertTrue("$action permanence: ${prompt.body}", prompt.body.contains("cannot be undone"))
            if (action == PrivacyAction.ClearDeviceHistory) assertTrue(prompt.body.contains("no account's history is changed"))
            else assertTrue("$action provider: ${prompt.body}", prompt.body.contains("YouTube Music"))
            if (action !in setOf(PrivacyAction.ClearDeviceHistory, PrivacyAction.ShortenRetention)) assertTrue("$action owner", prompt.body.contains("Fixture Listener"))
        }
        assertTrue(privacyPrompt(PrivacyAction.ClearAccountHistory, "x", data).body.contains("audio stored on this phone is kept"))
        assertTrue(privacyPrompt(PrivacyAction.DeleteAccountData, "x", data).body.contains("You stay signed in"))
        assertEquals("Clear 1 play?", privacyPrompt(PrivacyAction.ClearDeviceHistory, "x", data).title)
    }

    @Test fun partialAndFailedOutcomesNeverReadAsSuccess() {
        val partial = privacyOutcome(PrivacyAction.DeleteAccountData, DataActionResult.Partial(5, listOf("search history", "2 download files")))
        assertEquals("Not everything could be removed. Still on this phone: search history, 2 download files. The rest is gone.", partial)
        val failed = privacyOutcome(PrivacyAction.ClearSearches, DataActionResult.Failed("storage"))
        assertTrue(failed.startsWith("Nothing was removed")); assertFalse(failed.contains("storage\""))
        assertEquals("1 search cleared", privacyOutcome(PrivacyAction.ClearSearches, DataActionResult.Done(1)))
    }

    // ---- confirm

    @Test fun clearingAccountHistoryLeavesThePhonesHistoryAndEverythingElse() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); process.play(DEVICE_LOCAL_SCOPE_KEY); process.play(other)
        show(process)
        tap("privacy_clear_account_history"); tap("privacy_confirm")
        awaitNotice("3 plays cleared"); awaitGone("privacy_confirm")
        assertEquals(LocalDataSummary(accountPlays = 0, devicePlays = 1, searches = 2, downloads = 2, savedTracks = 1, hasQueue = true), summary(process, owner))
        assertEquals(1, process.history.count(other))
        awaitText("0 plays")
        tap("privacy_clear_account_history")
        awaitNotice("There is nothing to remove.")
        assertFalse(tagged("privacy_confirm"))
    }

    @Test fun clearingThePhonesHistoryLeavesTheAccounts() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); repeat(2) { process.play(DEVICE_LOCAL_SCOPE_KEY) }
        show(process)
        tap("privacy_clear_device_history"); tap("privacy_confirm")
        awaitNotice("2 plays cleared")
        assertEquals(3, process.history.count(owner)); assertEquals(0, process.history.count(DEVICE_LOCAL_SCOPE_KEY))
    }

    @Test fun clearingSearchesAndDeletingDownloadsEachRemoveOnlyTheirOwnKind() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); search(other, "someone else"); download(other, "fixtureD009")
        show(process)
        tap("privacy_clear_searches"); tap("privacy_confirm")
        awaitNotice("2 searches cleared"); awaitGone("privacy_confirm")
        assertEquals(LocalDataSummary(accountPlays = 3, searches = 0, downloads = 2, savedTracks = 1, hasQueue = true), summary(process, owner))
        tap("privacy_delete_downloads"); tap("privacy_confirm")
        awaitNotice("2 downloads deleted")
        assertEquals(LocalDataSummary(accountPlays = 3, searches = 0, downloads = 0, savedTracks = 1, hasQueue = true), summary(process, owner))
        assertEquals(1, runBlocking { SearchHistory.get(context).count(other) })
        assertNotNull(OfflineMusicStore.get(context).audio(other, "fixtureD009"))
        assertTrue(playbackCalls.isEmpty())
    }

    @Test fun deletingLocalDataStopsTheAccountsPlaybackAndRemovesItAllButNothingElse() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner); process.seed(other); process.play(DEVICE_LOCAL_SCOPE_KEY)
        val collections = show(process)
        collections.updateHistoryPreference(false)
        assertEquals(1, collections.onlineFavorites.size)
        tap("privacy_delete_account_data"); tap("privacy_confirm")
        awaitNotice("Local data for this account deleted")
        assertEquals(listOf("clear"), playbackCalls)
        assertEquals(LocalDataSummary(devicePlays = 1), summary(process, owner))
        assertFalse(OfflineMusicStore.get(context).directory(owner, "fixtureD001").exists())
        assertTrue("the saved tracks on screen elsewhere are gone too", collections.onlineFavorites.isEmpty())
        assertEquals(LocalDataSummary(accountPlays = 3, devicePlays = 1, searches = 2, downloads = 2, savedTracks = 1, hasQueue = true), summary(process, other))
        assertFalse("device settings are kept", historyEnabled(context))
        assertTrue("signing out is a separate action", process.provider.signedIn)
    }

    @Test fun aFailureIsShownAsOneAndRetryFinishesTheJob() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner)
        var broken = true
        val failing = LocalDataController(context, process.history, process.snapshots, { OfflineMusicStore.get(context) },
            { if (broken) throw IllegalStateException("fixture") else SearchHistory.get(context) }, Dispatchers.Unconfined, { 0L })
        show(process, data = failing)
        awaitTag("privacy_clear_account_history")
        tap("privacy_delete_account_data"); tap("privacy_confirm")
        awaitTag("privacy_failure")
        awaitText("Not everything could be removed. Still on this phone: search history. The rest is gone.")
        awaitText("Retry")
        assertTrue("a partial result is not announced as done", notices.isEmpty())
        assertEquals(2, runBlocking { SearchHistory.get(context).count(owner) }); assertEquals(0, process.history.count(owner))
        broken = false
        tap("privacy_confirm")
        awaitNotice("Local data for this account deleted"); awaitGone("privacy_confirm")
        assertEquals(0, runBlocking { SearchHistory.get(context).count(owner) })
    }

    // ---- saving and retention

    @Test fun turningSavingOffKeepsWhatIsSaved() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.seed(owner)
        show(process)
        tap("settings_history")
        await("saving to be off") { !historyEnabled(context) }
        assertEquals(3, process.history.count(owner))
        awaitText("3 plays")
    }

    private val day = 86_400_000L

    @Test fun aShorterPeriodAsksBeforeRemovingOlderPlaysAndCancelKeepsThePeriod() {
        val process = start(); val owner = process.provider.storageScopeKey(); val now = process.runtime.clocks.wallTimeMillis()
        process.play(owner, now - 40 * day); process.play(owner, now - 2 * day); process.play(DEVICE_LOCAL_SCOPE_KEY, now - 100 * day)
        show(process)
        tap("privacy_retention_30")
        awaitText("Keep history for 30 days?")
        awaitText("2 older plays will be removed now, from every account on this phone, and plays will be removed as they pass that age. " +
            "This cannot be undone. Your YouTube Music history is not changed.")
        tap("privacy_cancel"); awaitGone("privacy_cancel")
        assertNull(historyRetentionDays(context)); assertEquals(2, process.history.count(owner)); assertEquals(1, process.history.count(DEVICE_LOCAL_SCOPE_KEY))
        tap("privacy_retention_30"); tap("privacy_confirm")
        awaitNotice("2 older plays removed")
        assertEquals(30, historyRetentionDays(context)); assertEquals(1, process.history.count(owner)); assertEquals(0, process.history.count(DEVICE_LOCAL_SCOPE_KEY))
    }

    @Test fun aPeriodThatRemovesNothingAppliesWithoutAsking() {
        val process = start(); val owner = process.provider.storageScopeKey()
        process.play(owner)
        show(process)
        tap("privacy_retention_365")
        awaitNotice("History is kept for 1 year")
        assertFalse(tagged("privacy_confirm")); assertEquals(365, historyRetentionDays(context))
        tap("privacy_retention_keep")
        awaitNotice("History is kept until you clear it")
        assertNull(historyRetentionDays(context)); assertEquals(1, process.history.count(owner))
    }
}
