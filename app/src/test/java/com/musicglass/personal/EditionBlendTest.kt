package com.musicglass.personal

import org.junit.Assert.*
import org.junit.Test

class EditionBlendTest {
    @Test fun everyStoredModeHasTheCorrectEffectiveMode() {
        assertEquals(if (Edition.plus) BlendMode.entries.toList() else listOf(BlendMode.Crossfade, BlendMode.Smart), Edition.blendModes)
        for (stored in BlendMode.entries) {
            val expected = if (!Edition.plus && stored == BlendMode.Automix) BlendMode.Smart else stored
            assertEquals(expected, effectiveBlendMode(stored))
        }
    }

    @Test fun anAutomixMoodIsPreservedButMatchedAndDescribedByWhatThisEditionPlays() {
        val original = Mood("kept", MoodValues(true, BlendMode.Automix, 6, 150, false), name = "Party of my own")
        val encoded = encodeMood(original)
        val restored = decodeMood(encoded)!!
        assertEquals(BlendMode.Automix, restored.values.mode)
        assertEquals(encoded, encodeMood(restored))
        val heard = restored.values.copy(mode = effectiveBlendMode(restored.values.mode))
        assertTrue(moodMatches(restored.values, heard))
        assertEquals("kept", activeMood(listOf(restored), heard, "kept")!!.id)
        assertTrue(moodSummary(restored.values).startsWith(string(if (Edition.plus) R.string.blend_automix_short else R.string.smart_crossfade)))
    }


}
