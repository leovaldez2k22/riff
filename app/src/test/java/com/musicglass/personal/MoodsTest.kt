package com.musicglass.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moods (owner, 30 Oct): which one is on is worked out from what is set, and kept ones survive being written down. */
class MoodsTest {
    private val party = builtInMoods.first { it.id == "party" }.values
    private val commute = builtInMoods.first { it.id == "commute" }.values

    @Test fun aMoodIsOnWhileEverythingItSetsIsSo() {
        assertEquals("party", activeMood(builtInMoods, party.copy(dataSaver = true))?.id)
        assertNull("the booster changed by hand", activeMood(builtInMoods, party.copy(boost = 200)))
    }

    @Test fun ofTwoMoodsThatBothHoldTheTappedOneIsOn() {
        val night = builtInMoods.first { it.id == "night" }.values
        assertEquals("night", activeMood(builtInMoods, night, chosen = "night")?.id)
        assertEquals("focus", activeMood(builtInMoods, night, chosen = "focus")?.id)
        assertEquals("the one setting more, when none was tapped", "night", activeMood(builtInMoods, night)?.id)
        assertEquals("a tapped mood no longer holding gives way", "focus", activeMood(builtInMoods, night.copy(dataSaver = true), chosen = "night")?.id)
    }

    @Test fun theLengthCountsOnlyForThePlainCrossfade() {
        assertTrue(moodMatches(party, party.copy(seconds = 11)))
        assertFalse(moodMatches(commute, commute.copy(seconds = 4)))
    }

    @Test fun dataSaverCountsOnlyWhenTheMoodSetsIt() {
        assertTrue(moodMatches(party, party.copy(dataSaver = false)))
        assertFalse(moodMatches(commute, commute.copy(dataSaver = false)))
    }

    @Test fun aKeptMoodIsWrittenAndReadBack() {
        val mood = Mood("kept-1", MoodValues(true, BlendMode.Smart, 5, null, normalize = true, dataSaver = null), name = "Late\tnight")
        val back = decodeMood(encodeMood(mood))!!
        assertEquals(mood.values, back.values); assertEquals("Late night", back.name)
        assertNull(decodeMood("broken"))
    }
}
