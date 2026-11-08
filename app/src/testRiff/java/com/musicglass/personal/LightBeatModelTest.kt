package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LightBeatModelTest {
    @Test fun noRuntimeOrModelCanBeLoadedByTheLightEdition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(Edition.plus)
        assertFalse(Edition.hasBeatModel(context))
        assertFalse(BeatModel.present(context))
        assertNull(BeatModel.get(context))
        assertNull(Edition.beatAnalysis(context))
        assertTrue(runCatching { Class.forName("ai.onnxruntime.OrtEnvironment") }.isFailure)
        assertFalse(context.assets.list("automix").orEmpty().any { it.endsWith(".onnx") })
    }

    @Test fun sharedEnvelopeAnalysisStillWorksWithoutTheModel() {
        val builder = EnvelopeBuilder(1_000, 1, 0)
        builder.add(ShortArray(2_000) { if (it < 1_000) 8_000 else 0 })
        val result = builder.build(2_000)
        assertTrue(result.loudnessDb.isNotEmpty())
        assertTrue(result.audibleEndMs()!! < 2_000)
    }
}
