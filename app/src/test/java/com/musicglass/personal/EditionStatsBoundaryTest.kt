package com.musicglass.personal

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class EditionStatsBoundaryTest {
    @Test fun collectorUiAndOwnControllerCommandArePresentOnlyInPlus() {
        val collector = Edition.createStats()
        assertEquals(Edition.plus, collector != null)
        assertEquals(Edition.plus, Edition.statsUi != null)
        val action = "com.musicglass.personal.GET_NERD_STATS"
        assertEquals(if (Edition.plus) action else null, Edition.statsCommand)
        val commands = SessionCommands.EMPTY.buildUpon().addEditionStats().build()
        assertEquals(Edition.plus, commands.contains(SessionCommand(action, Bundle.EMPTY)))
        if (!Edition.plus) {
            for (name in listOf("NerdStatsCollector", "NerdStatsKt", "PlayerStatsLineKt", "PlusStatsUi"))
                assertTrue(name, runCatching { Class.forName("com.musicglass.personal.$name") }.isFailure)
        }
    }

    @Test fun menuFilteringPreservesTheOrderAndEverySharedAction() {
        val input = listOf("Library shortcuts", "Sleep timer", "Playback speed", "Equalizer", "Stats for nerds", "View credits")
        assertEquals(if (Edition.plus) input else listOf("Library shortcuts", "Sleep timer", "Playback speed", "View credits"),
            editionPlayerOptions(input))
    }
}
