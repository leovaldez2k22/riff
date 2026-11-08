package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Feature 4 (Riff+, owner 8 Nov 2026): the most-replayed part. The fixture copies the shape of the web `next`
 * answer seen on 8 Nov (100 buckets of `intensityScoreNormalized`, the first scored 1.0) with made-up numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReplayPeakTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val id = "Ab-_12345cd"

    private fun answer(scores: List<Double>, video: String = id, type: String = "MARKER_TYPE_HEATMAP", bucket: Long = 2_000) = buildJsonObject {
        put("frameworkUpdates", buildJsonObject { put("entityBatchUpdate", buildJsonObject { putJsonArray("mutations") {
            add(buildJsonObject { put("payload", buildJsonObject { put("somethingElse", 1) }) })
            add(buildJsonObject { put("payload", buildJsonObject { put("macroMarkersListEntity", buildJsonObject {
                put("externalVideoId", video)
                put("markersList", buildJsonObject {
                    put("markerType", type)
                    putJsonArray("markers") { scores.forEachIndexed { i, s -> add(buildJsonObject {
                        put("startMillis", (i * bucket).toString()); put("durationMillis", bucket.toString()); put("intensityScoreNormalized", s) }) } }
                })
            }) }) })
        } }) })
    }
    private fun scores(vararg peaks: Pair<Int, Double>) = List(100) { i -> peaks.firstOrNull { it.first == i }?.second ?: .3 }.toMutableList().also { it[0] = 1.0 }

    @Test fun theOpeningNeverWinsAndTheEarliestTiedPeakDoes() {
        val buckets = parseReplayHeatmap(answer(scores(30 to .8, 60 to .8)), id, 200_000)!!
        assertEquals(100, buckets.size)
        assertEquals(60_000L, replayPeakMs(buckets))
        // A flat song, or one whose only peak is its opening, has no most-replayed part.
        assertNull(replayPeakMs(parseReplayHeatmap(answer(List(100) { if (it == 0) 1.0 else .4 }), id, 200_000)!!))
    }

    @Test fun anotherVideoAnotherMarkerOrCorruptBucketsGiveNothing() {
        assertNull(parseReplayHeatmap(answer(scores(30 to .8), video = "zzzzzzzzzzz"), id, 200_000))
        assertNull(parseReplayHeatmap(answer(scores(30 to .8), type = "MARKER_TYPE_CHAPTERS"), id, 200_000))
        assertNull(parseReplayHeatmap(answer(scores(30 to 1.5)), id, 200_000))
        assertNull(parseReplayHeatmap(answer(scores(30 to Double.NaN)), id, 200_000))
        assertNull(parseReplayHeatmap(answer(listOf(.5)), id, 200_000))
        // Buckets running past the song: a different recording.
        assertNull(parseReplayHeatmap(answer(scores(30 to .8)), id, 150_000))
        assertNull(parseReplayHeatmap(JsonObject(emptyMap()), id, 200_000))
    }

    @Test fun onlyPublicSongsAreLookedUp() {
        assertEquals(id, replayVideoId("yt:$id"))
        for (media in listOf("local:42", "yt:short", id, "yt:$id/x")) assertNull(media, replayVideoId(media))
    }

    @Test fun eachSongIsAskedOnceAndAMissIsRemembered() = runBlocking {
        var asked = 0
        val body = answer(scores(40 to .9), video = "Peak0000001").toString()
        assertEquals(80_000L, ReplayPeaks.lookup(context, "Peak0000001", 200_000, { asked++; body }, { true }))
        assertEquals(80_000L, ReplayPeaks.lookup(context, "Peak0000001", 200_000, { asked++; body }, { true }))
        assertNull(ReplayPeaks.lookup(context, "Miss0000001", 200_000, { asked++; null }, { true }))
        assertNull(ReplayPeaks.lookup(context, "Miss0000001", 200_000, { asked++; null }, { true }))
        assertEquals(2, asked)
        // Offline: nothing is asked, and nothing is remembered as a miss.
        assertNull(ReplayPeaks.lookup(context, "Offline0001", 200_000, { asked++; body }, { false }))
        assertEquals(2, asked)
        assertFalse(ReplayPeaks.cached("Offline0001").second)
    }

    @Test fun theLookupWaitsUntilTheSongHasPlayedFiveSeconds() {
        val recording = RecordingGateway()
        var shown by mutableStateOf(PlaybackUiState(ready = true, playing = true, mediaId = "yt:$id", durationMs = 200_000))
        var position by mutableLongStateOf(0L)
        val gateway = object : PlaybackGateway by recording.gateway {
            override val state get() = shown
            override val positionMs get() = position
        }
        var asked = 0
        compose.setContent { RiffTheme { ReplayPeakBar(gateway, "yt:$id", 200_000, lookup = { _, _ -> asked++; 60_000L }) {} } }
        compose.waitForIdle()
        position = 4_000; compose.waitForIdle()
        assertEquals("Not before the song has played", 0, asked)
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("replay_peak")).fetchSemanticsNodes().size)
        position = 5_000; compose.waitForIdle()
        assertEquals(1, asked)
        compose.onNode(androidx.compose.ui.test.hasTestTag("replay_peak")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasTestTag("replay_peak_mark"), useUnmergedTree = true).assertExists()
    }
}
