package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recent searches are kept per owner; synthetic owners and queries only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SearchHistoryOwnerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    /** A new instance reads what a new process would. */
    private fun fresh() = SearchHistory(context, Dispatchers.Unconfined)
    private fun earlierBuild(vararg queries: String) = prefs.edit().putString("queries", JSONArray(queries.toList()).toString()).commit()

    @Test fun eachOwnerSeesOnlyItsOwnSearches() = runBlocking {
        val searches = fresh()
        searches.bind("owner-a"); searches.record("alpha one"); searches.record("alpha two")
        searches.bind("owner-b")
        assertTrue("the previous owner's list leaves the screen at once", searches.entries.isEmpty())
        searches.record("beta")
        searches.bind(GUEST_SCOPE_KEY); assertTrue(searches.entries.isEmpty())
        searches.bind("owner-a")
        assertEquals(listOf("alpha two", "alpha one"), searches.entries)
        val restarted = fresh()
        restarted.bind("owner-b"); assertEquals(listOf("beta"), restarted.entries)
        assertEquals(2, restarted.count("owner-a")); assertEquals(0, restarted.count("owner-c"))
    }

    @Test fun eachOwnerKeepsThirty() = runBlocking {
        val searches = fresh()
        searches.bind("owner-a"); repeat(40) { searches.record("query $it") }
        searches.bind("owner-b"); searches.record("other")
        assertEquals(SEARCH_HISTORY_LIMIT, searches.count("owner-a")); assertEquals(1, searches.count("owner-b"))
        searches.bind("owner-a"); assertEquals("query 39", searches.entries.first())
    }

    @Test fun anUnverifiedAccountsSearchesAreNotStored() = runBlocking {
        val searches = fresh()
        searches.bind(UNVERIFIED_SCOPE_KEY); searches.record("typed while unverified")
        assertEquals(listOf("typed while unverified"), searches.entries)
        assertTrue(prefs.all.isEmpty())
        searches.bind("owner-a"); assertTrue(searches.entries.isEmpty())
        searches.bind(UNVERIFIED_SCOPE_KEY); assertTrue(searches.entries.isEmpty())
    }

    @Test fun removingOneAndClearingStayWithTheOwner() = runBlocking {
        val searches = fresh()
        searches.bind("owner-b"); searches.record("kept")
        searches.bind("owner-a"); searches.record("one"); searches.record("two")
        searches.remove("one"); assertEquals(1, searches.count("owner-a"))
        searches.clear(); assertEquals(0, searches.count("owner-a")); assertEquals(1, searches.count("owner-b"))
    }

    @Test fun clearingAnOwnerThatIsNotOnScreenLeavesTheScreenAlone() = runBlocking {
        val searches = fresh()
        searches.bind("owner-a"); searches.record("one")
        searches.bind("owner-b"); searches.record("shown")
        assertEquals(1, searches.clearOwner("owner-a"))
        assertEquals(listOf("shown"), searches.entries); assertEquals(0, searches.count("owner-a"))
        assertEquals(0, searches.clearOwner("owner-a"))
    }

    @Test fun theSharedListOfEarlierBuildsIsNotShownToAnyOwner() = runBlocking {
        earlierBuild("mixed one", "mixed two")
        val searches = fresh()
        listOf("owner-a", GUEST_SCOPE_KEY, UNVERIFIED_SCOPE_KEY).forEach { searches.bind(it); assertTrue(it, searches.entries.isEmpty()) }
        assertEquals(2, legacySearchCount(context))
        searches.bind("owner-a"); searches.record("mine")
        assertEquals("recording does not touch the shared list", 2, legacySearchCount(context))
    }

    @Test fun importAddsTheSharedListAfterTheOwnersOwnAndRetiresIt() = runBlocking {
        earlierBuild("Mixed One", "mine", "mixed two")
        val searches = fresh()
        searches.bind("owner-a"); searches.record("mine")
        assertEquals(2, searches.importLegacy("owner-a"))
        assertEquals(listOf("mine", "Mixed One", "mixed two"), searches.entries)
        assertEquals(0, legacySearchCount(context)); assertEquals(3, searches.count("owner-a"))
        assertEquals(0, searches.count("owner-b"))
        assertEquals("nothing is left to import twice", 0, searches.importLegacy("owner-a"))
        val restarted = fresh(); restarted.bind("owner-a")
        assertEquals(listOf("mine", "Mixed One", "mixed two"), restarted.entries)
    }

    @Test fun importIsRefusedForAnUnverifiedAccountAndTheDevice() = runBlocking {
        earlierBuild("mixed")
        val searches = fresh()
        assertNull(searches.importLegacy(UNVERIFIED_SCOPE_KEY)); assertNull(searches.importLegacy(DEVICE_LOCAL_SCOPE_KEY))
        assertEquals(1, legacySearchCount(context))
    }

    @Test fun deletingTheSharedListLeavesEveryOwnersSearches() = runBlocking {
        earlierBuild("mixed")
        val searches = fresh()
        searches.bind("owner-a"); searches.record("mine")
        assertTrue(searches.deleteLegacy())
        assertEquals(0, legacySearchCount(context)); assertEquals(1, searches.count("owner-a"))
    }
}
