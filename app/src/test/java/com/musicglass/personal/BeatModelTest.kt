package com.musicglass.personal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Automix stage 4: the Kotlin port of Beat This!'s front end and its chunking and peak picking against its own code
 * (scripts/automix/beat_model_fixture.py), so the model on the phone sees what it saw on the PC.
 */
class BeatModelTest {
    private val fixture: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("automix/beat-model-fixture.json")!!.readText()).jsonObject
    private fun floats(name: String) = fixture[name]!!.jsonArray.map { it.jsonPrimitive.float }.toFloatArray()

    @Test fun theLogMelIsBeatThisOwn() {
        val n = 66_150
        val x = FloatArray(n) { i ->
            (.5 * sin(2 * PI * 440 * i / 22_050) + .3 * sin(2 * PI * 3_000 * i / 22_050 + .5) + if (i % 11_025 == 0) .8 else 0.0).toFloat()
        }
        val mel = logMel(x)
        assertEquals(fixture["logMelFrames"]!!.jsonPrimitive.int, mel.size / MODEL_BANDS)
        for ((frame, values) in fixture["logMel"]!!.jsonObject) {
            val f = frame.toInt()
            val want = values.jsonArray.map { it.jsonPrimitive.float }
            for (b in 0 until MODEL_BANDS) {
                val got = mel[f * MODEL_BANDS + b]
                assertTrue("frame $f band $b: $got, Beat This! ${want[b]}", abs(got - want[b]) <= 1e-3f + 1e-4f * abs(want[b]))
            }
        }
    }

    /** A model that marks each frame's place in its chunk, as the fixture's fake does. */
    private val marker = BeatLogits { spect, frames ->
        FloatArray(frames) { spect[it * MODEL_BANDS] + .001f * it } to FloatArray(frames) { frames.toFloat() }
    }
    private fun fake(frames: Int) = FloatArray(frames * MODEL_BANDS) { sin((it / MODEL_BANDS) * .37 + it % MODEL_BANDS).toFloat() }

    @Test fun chunksOverlapAndKeepTheEarlierAsBeatThisDoes() {
        val (beat, downbeat) = predictChunks(fake(3_100), 3_100, marker)
        assertArrayEquals(floats("chunkedBeat"), beat, 1e-4f)
        assertArrayEquals(floats("chunkedDownbeat"), downbeat, 0f)
        val (short, _) = predictChunks(fake(1_000), 1_000, marker)
        assertArrayEquals("a piece shorter than a chunk", floats("shortBeat"), short, 1e-4f)
    }

    @Test fun peaksAreBeatThisMinimalOnes() {
        val grid = gridFromLogits(floats("peaksBeat"), floats("peaksDownbeat"), startMs = 0)
        assertNotNull(grid); grid!!
        assertArrayEquals(floats("beatsS").map { Math.round(it * 1_000.0) }.toLongArray(), grid.beatsMs)
        assertArrayEquals(floats("downbeatsS").map { Math.round(it * 1_000.0) }.toLongArray(), grid.downbeatsMs)
        assertEquals(120.0, grid.bpm, .5)
        assertTrue("steady and likely beats are sure", grid.confidence > .35f)
    }

    @Test fun withoutAPulseThereIsNoGrid() {
        assertNull(gridFromLogits(FloatArray(400) { -4f }, FloatArray(400) { -4f }, 0))
    }

    @Test fun resamplingKeepsATonesPitchAndLevel() {
        val tone = FloatArray(48_000) { sin(2 * PI * 1_000 * it / 48_000).toFloat() }
        val out = resample(tone, 48_000, MODEL_RATE)
        assertEquals(MODEL_RATE, out.size)
        // Away from the edges it is the same tone at the new rate.
        for (i in 1_000 until 21_000 step 97) assertEquals(sin(2 * PI * 1_000 * i / MODEL_RATE).toFloat(), out[i], .01f)
        val high = FloatArray(48_000) { sin(2 * PI * 15_000 * it / 48_000).toFloat() }
        assertTrue("above the new Nyquist it is gone", resample(high, 48_000, MODEL_RATE).drop(1_000).dropLast(1_000).maxOf { abs(it) } < .01f)
    }
}
