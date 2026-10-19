package com.musicglass.personal

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Q18-031: a download's phase line is a polite live region whose spoken text is the phase alone. TalkBack announces
 * a live region when its spoken text changes, so a new phase is one announcement and a climbing percentage none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class DownloadPhaseAnnouncementTest {
    @get:Rule val compose = createComposeRule()

    private fun song(state: String, bytes: Long = 0, total: Long = 0) =
        OfflineSong("s", ProviderTrack("fixtureD001", "Song", "Artist"), state, true, bytes, total, "", 0, "", "pending", "pending", 0, 0, 0)

    private fun spoken(): Pair<List<String>?, LiveRegionMode?> {
        val config = compose.onNodeWithTag("download_phase_fixtureD001", useUnmergedTree = true).fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription) to config.getOrNull(SemanticsProperties.LiveRegion)
    }

    @Test fun aPercentageChangeSpeaksNothingNewAndAPhaseChangeSpeaksThePhase() {
        val row = mutableStateOf(song("resolving"))
        compose.setContent { DownloadPhaseLine(row.value, false) }
        assertEquals(listOf("Preparing download") to LiveRegionMode.Polite, spoken())

        row.value = song("transferring", 37, 100); compose.waitForIdle()
        val downloading = spoken()
        assertEquals(listOf("Downloading…") to LiveRegionMode.Polite, downloading)
        assertNull("The percentage is not part of what is spoken",
            compose.onNodeWithTag("download_phase_fixtureD001", useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text))

        for (percent in listOf(38L, 52L, 99L)) {
            row.value = song("transferring", percent, 100); compose.waitForIdle()
            assertEquals("Downloading $percent% changes nothing TalkBack reads", downloading, spoken())
        }

        row.value = song("validating"); compose.waitForIdle()
        assertEquals(listOf("Checking audio") to LiveRegionMode.Polite, spoken())
        row.value = song("assets"); compose.waitForIdle()
        assertEquals(listOf("Saving artwork and lyrics") to LiveRegionMode.Polite, spoken())
    }

    @Test fun everyPhaseHasSpokenTextWithoutDigits() {
        for (state in DownloadState.entries) for (ready in listOf(true, false)) {
            val text = downloadPhaseSpoken(song(state.db, 25, 100), ready)
            assertTrue("$state ready=$ready", text.isNotBlank() && text.none { it.isDigit() })
        }
    }
}
