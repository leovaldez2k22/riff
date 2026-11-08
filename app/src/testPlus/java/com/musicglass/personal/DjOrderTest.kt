package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Feature 18 (Riff+), phase 7b: the order of the songs to come, its undo, and the kept analyses. */
class DjOrderTest {
    @get:Rule val folder = TemporaryFolder()

    private fun slot(id: String, bpm: Double?, key: MusicalKey? = null, pinned: Boolean = false) = DjSlot(id, bpm?.let { DjFacts(it, key) } ?: key?.let { DjFacts(null, it) }, pinned)
    private val c = MusicalKey(0, false); private val g = MusicalKey(7, false); private val am = MusicalKey(9, true); private val fs = MusicalKey(6, false)

    @Test fun buildUpAndWindDownFollowTheTempo() {
        val songs = listOf(slot("a", 128.0), slot("b", 100.0), slot("c", 140.0), slot("d", 115.0))
        assertEquals(listOf("b", "d", "a", "c"), djOrder(songs, null, DjMode.BuildUp))
        assertEquals(listOf("c", "a", "d", "b"), djOrder(songs, null, DjMode.WindDown))
    }

    @Test fun unknownAndPinnedSongsStayInPlace() {
        val songs = listOf(slot("a", 140.0), slot("x", null), slot("b", 100.0), slot("p", 90.0, pinned = true), slot("c", 120.0), slot("k", null, c))
        val order = djOrder(songs, null, DjMode.BuildUp)
        assertEquals("x", order[1]); assertEquals("p", order[3]); assertEquals("a song with a key but no tempo stays", "k", order[5])
        assertEquals(listOf("b", "c", "a"), order.filter { it in setOf("a", "b", "c") })
        assertEquals(songs.map { it.entry }.toSet(), order.toSet())
    }

    @Test fun halfAndDoubleTimeCountAsTheSameTempo() {
        assertEquals(140.0, foldedBpm(70.0), 0.0); assertEquals(140.0, foldedBpm(280.0), 0.0)
        assertTrue(tempoSteps(70.0, 140.0) < 1e-9)
        // From a 128 BPM song, a 64 BPM one is the closest, not the 120 one.
        assertEquals("half", djOrder(listOf(slot("near", 120.0), slot("half", 64.0)), DjFacts(128.0, null), DjMode.Steady).first())
    }

    @Test fun steadyPrefersAKeyThatMixesAtTheSameTempo() {
        val songs = listOf(slot("far", 124.0, fs), slot("fifth", 124.0, g), slot("relative", 124.0, am))
        val order = djOrder(songs, DjFacts(124.0, c), DjMode.Steady)
        assertEquals("tritone last", "far", order.last())
        assertEquals("ties keep the queue's order", listOf("fifth", "relative", "far"), order)
    }

    @Test fun theSameQueueAlwaysGivesTheSameOrder() {
        val songs = (0 until 30).map { slot("s$it", 90.0 + (it * 37 % 60), MusicalKey(it * 5 % 12, it % 3 == 0)) }
        DjMode.entries.forEach { mode -> assertEquals(djOrder(songs, null, mode), djOrder(songs, null, mode)) }
    }

    @Test fun coverageCountsWhatTheOrderCanUse() {
        assertEquals(DjCoverage(4, 2, 1, 1), djCoverage(listOf(slot("a", 120.0, c), slot("b", 100.0), slot("c", null), slot("d", null, pinned = true))))
    }

    @Test fun undoPutsTheSongsToComeBackAroundWhatChanged() {
        val previous = listOf("p", "now", "a", "b", "c", "d")
        // Applied order: now, d, c, b, a; one song has played since, and "n" was put to play next.
        val now = listOf("p", "now", "d", "n", "c", "b", "a")
        assertEquals(listOf("p", "now", "d", "n", "a", "b", "c"), djUndoOrder(now, 2, previous, setOf("n")))
        assertEquals("a song added since goes after the ones put back", listOf("now", "a", "b", "x"), djUndoOrder(listOf("now", "x", "b", "a"), 0, listOf("now", "a", "b"), emptySet()))
    }

    @Test fun analysesAreKeptReadBackAndBounded() {
        val file = folder.root.resolve("dj/analysis.tsv")
        val store = DjAnalysisStore(file, keep = 2)
        store.write("yt:a", DjRecord(128.0, .8f, am, .81, .07, 1, 900))
        store.write("local:5", DjRecord(null, .1f, null, .2, .0, 2, 700))
        assertEquals(DjFacts(128.0, am), DjAnalysisStore(file).read("yt:a")!!.facts)
        assertNull("nothing found is kept too, so it is not read again", DjAnalysisStore(file).read("local:5")!!.bpm)
        store.write("yt:c", DjRecord(100.0, .5f, c, .7, .1, 3, 800))
        assertNull("the oldest goes", DjAnalysisStore(file).read("yt:a"))
        file.writeText(file.readText().replace("\n1\t", "\n9\t").replaceFirst("1\t", "9\t"))
        assertNull("another version reads as not analysed", DjAnalysisStore(file).read("yt:c"))
    }
}
