package com.musicglass.personal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Q18-038 and Q18-039: the real numbers on screen 6, and Checking played at its agreed pace. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInCountsTest {
    private fun header(second: String) = Json.parseToJsonElement("""{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":
        {"content":{"sectionListRenderer":{"contents":[{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Liked music"}]},
        "subtitle":{"runs":[{"text":"Auto playlist"}]},"secondSubtitle":{"runs":[{"text":"$second"},{"text":" • "},{"text":"70+ hours"}]}}}]}}}}]}}}""")

    @Test fun theHeadersNumberIsUsedWhenItIsExact() {
        assertEquals(1234, collectionHeaderSongCount(header("1,234 songs")))
        assertEquals(1, collectionHeaderSongCount(header("1 song")))
        assertEquals(98, collectionHeaderSongCount(header("98 tracks")))
    }

    @Test fun aFloorOrNoNumberMeansCountingThePages() {
        assertNull(collectionHeaderSongCount(header("100+ songs")))
        assertNull(collectionHeaderSongCount(header("Auto playlist")))
        assertNull(collectionHeaderSongCount(Json.parseToJsonElement("""{"contents":{}}""")))
    }

    private fun page(from: Int, to: Int, next: String?) =
        ProviderSearchPage((from until to).map { ProviderTrack("v$it", "Song $it", "Artist") }, next)

    @Test fun everyPageIsCountedToTheEndAndTheCountRisesAsItGoes() = runTest {
        val pages = mapOf("p2" to page(100, 200, "p3"), "p3" to page(195, 237, null)) // five songs repeat across pages
        val seen = mutableListOf<Int>()
        val count = countThroughPages(page(0, 100, "p2"), LIKED_SONGS_COUNT_LIMIT, { pages.getValue(it) }) { seen += it }
        assertEquals(LibraryCount(237, complete = true), count)
        assertEquals(listOf(100, 200, 237), seen)
    }

    @Test fun aRepeatedTokenEndsTheCountAndTheLimitGivesAFloor() = runTest {
        assertEquals(LibraryCount(200, true), countThroughPages(page(0, 100, "loop"), 10_000, { page(100, 200, "loop") }))
        var asked = 0
        val capped = countThroughPages(page(0, 100, "n0"), 250, { asked++; page(asked * 100, asked * 100 + 100, "n$asked") })
        assertEquals(LibraryCount(250, complete = false), capped)
        assertEquals(2, asked)
    }

    // ---- Q18-039: Checking's minimum timeline, on a test clock

    @Test fun aSignInThatVerifiesIn50MsStillShowsEveryTickAtItsSlot() = runTest {
        val ticks = mutableListOf<Pair<Int, Long>>()
        val finished = async {
            signInCheckingTimeline(false, { testScheduler.currentTime }, done = { testScheduler.currentTime >= 50 },
                failed = { false }) { ticks += it to testScheduler.currentTime }
        }
        assertTrue(finished.await())
        assertEquals(listOf(1, 2, 3), ticks.map { it.first })
        assertEquals(listOf(3800L, 5000L, 6200L), ticks.map { it.second })
        assertTrue("Checking lasts at least 7.4 s: ${testScheduler.currentTime}", testScheduler.currentTime in 7400..7450)
    }

    @Test fun aSlowSignInIsNotMadeSlower() = runTest {
        val ticks = mutableListOf<Long>()
        val finished = async {
            signInCheckingTimeline(false, { testScheduler.currentTime }, done = { step -> step == 0 || testScheduler.currentTime >= 10_000 },
                failed = { false }) { ticks += testScheduler.currentTime }
        }
        assertTrue(finished.await())
        assertEquals(3800L, ticks[0])
        // Both later steps were waiting on the real work; the second ticks the moment it lands, the third a beat after.
        assertTrue(ticks[1] in 10_000..10_050)
        assertTrue(ticks[2] - ticks[1] < 100)
        assertTrue("no extra wait after a slow finish: ${testScheduler.currentTime}", testScheduler.currentTime < 10_150)
    }

    @Test fun aFailureShowsAtOnce() = runTest {
        val ticks = mutableListOf<Int>()
        val finished = async {
            signInCheckingTimeline(false, { testScheduler.currentTime }, done = { it == 0 },
                failed = { testScheduler.currentTime >= 1_000 }) { ticks += it }
        }
        assertFalse(finished.await())
        assertTrue(testScheduler.currentTime < 1_100)
        assertTrue(ticks.isEmpty())
    }

    @Test fun removeAnimationsAddsNoWaiting() = runTest {
        val finished = async { signInCheckingTimeline(true, { testScheduler.currentTime }, { true }, { false }) {} }
        assertTrue(finished.await())
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(0L, signInCheckingMinimumMs(true))
        assertEquals(7400L, signInCheckingMinimumMs(false))
    }
}
