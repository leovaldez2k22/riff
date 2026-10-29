package com.musicglass.personal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Automix stage A: a song's analysis kept on the phone, read back as it was written. */
class AnalysisStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun envelope(beats: Boolean = true) = TrackEnvelope(181_000, FloatArray(450) { -20f - it % 7 }, FloatArray(450) { -30f + it % 3 }, 226_000,
        if (beats) BeatGrid(122.0, longArrayOf(181_200, 181_692, 182_184), longArrayOf(181_200), .62f, .41f) else null)

    @Test fun whatIsWrittenIsReadBack() {
        val store = AnalysisStore(folder.root)
        store.write("yt:abc:tail", envelope())
        val read = store.read("yt:abc:tail")!!
        val wrote = envelope()
        assertEquals(wrote.startMs, read.startMs); assertEquals(wrote.durationMs, read.durationMs)
        assertArrayEquals(wrote.loudnessDb, read.loudnessDb, 0f); assertArrayEquals(wrote.lowDb, read.lowDb, 0f)
        assertEquals(122.0, read.beats!!.bpm, 0.0); assertArrayEquals(wrote.beats!!.beatsMs, read.beats!!.beatsMs)
        assertArrayEquals(wrote.beats!!.downbeatsMs, read.beats!!.downbeatsMs); assertEquals(.41f, read.beats!!.downbeatConfidence, 0f)
        assertEquals(wrote.audibleEndMs(), read.audibleEndMs())
    }

    @Test fun aSongWithoutAPulseKeepsNone() {
        val store = AnalysisStore(folder.root)
        store.write("local:7:head", envelope(beats = false))
        assertNull(store.read("local:7:head")!!.beats)
    }

    @Test fun missingOtherKeysAndOtherVersionsReadAsNothing() {
        val store = AnalysisStore(folder.root)
        assertNull(store.read("yt:none:tail"))
        val bytes = encodeEnvelope(envelope(), "yt:a:tail")
        assertNull("another key", decodeEnvelope(bytes, "yt:b:tail"))
        val older = bytes.copyOf().also { it[7] = (ANALYSIS_VERSION + 1).toByte() }
        assertNull("another analysis", decodeEnvelope(older, "yt:a:tail"))
        assertNull("cut short", decodeEnvelope(bytes.copyOf(bytes.size / 2), "yt:a:tail"))
    }

    @Test fun theLeastRecentlyUsedGoFirst() {
        val store = AnalysisStore(folder.root, keep = 3)
        listOf("a", "b", "c").forEachIndexed { i, id ->
            store.write("yt:$id:tail", envelope())
            setAge(store, "yt:$id:tail", i)
        }
        store.read("yt:a:tail"); setAge(store, "yt:a:tail", 10)
        store.write("yt:d:tail", envelope())
        assertNull("b was the oldest", store.read("yt:b:tail"))
        assertNotNull(store.read("yt:a:tail")); assertNotNull(store.read("yt:c:tail")); assertNotNull(store.read("yt:d:tail"))
    }

    /** Files written in one test share a second; give each its own age. */
    private fun setAge(store: AnalysisStore, key: String, order: Int) {
        val f = folder.root.listFiles()!!.single { decodeEnvelope(it.readBytes(), key) != null }
        f.setLastModified(1_000_000_000_000L + order * 10_000L)
    }

    @Test fun theOldSmartSwitchIsReadAsTheChoice() {
        assertEquals(BlendMode.Crossfade, blendMode(null, smart = false))
        assertEquals(BlendMode.Smart, blendMode(null, smart = true))
        assertEquals(BlendMode.Automix, blendMode("automix", smart = false))
        assertEquals("an unknown value", BlendMode.Smart, blendMode("dj", smart = true))
    }

    @Test fun classicalBlendsPlainlyByItsArtistsGenres() {
        assertEquals(listOf("Yo-Yo Ma", "Kathryn Stott"), artistNames("Yo-Yo Ma & Kathryn Stott"))
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), artistNames("Calvin Harris, Dua Lipa"))
        assertEquals(emptyList<String>(), artistNames(null))
        assertEquals(true, classicalGenres(listOf("Classical")))
        assertEquals(true, classicalGenres(listOf("modern classical", "piano")))
        assertEquals(true, classicalGenres(listOf("opera")))
        assertEquals(false, classicalGenres(listOf("pop", "dance")))
    }

    @Test fun aStreamsHeadIsReadFromAsMuchOfItsStartAsThirtySecondsTake() {
        // A 4 MB, 240 s stream: 32 s and a quarter more, never less than the container's header.
        assertEquals((4_000_000.0 * (30_000 * 1.25 + 2_000) / 240_000).toLong(), cachedHeadBytes(4_000_000, 240_000))
        assertEquals(CACHED_HEAD_BYTES, cachedHeadBytes(1_000_000, 600_000))
        assertEquals("a short song is read whole", 300_000L, cachedHeadBytes(300_000, 20_000))
    }

    @Test fun smartKeepsStageOnesTimingAndAutomixMovesItOntoTheBeat() {
        val beats = BeatGrid(120.0, LongArray(100) { 180_000 + it * 500L }, LongArray(25) { 180_000 + it * 2_000L }, .9f, .9f)
        val tail = TrackEnvelope(180_000, FloatArray(450) { if (it < 380) -12f else -80f }, FloatArray(450) { -20f }, 226_000, beats)
        val head = TrackEnvelope(0, FloatArray(300) { -12f }, FloatArray(300) { -20f }, 200_000,
            BeatGrid(120.0, LongArray(60) { 250 + it * 500L }, LongArray(15) { 250 + it * 2_000L }, .9f, .9f))
        val smart = planSmartTransition(tail, head, 6_000, onBeat = false)!!
        val automix = planSmartTransition(tail, head, 6_000)!!
        assertEquals(false, smart.onBeat); assertEquals(true, automix.onBeat)
        assertEquals(0L, (automix.startMs - 180_000) % 2_000)
    }
}
