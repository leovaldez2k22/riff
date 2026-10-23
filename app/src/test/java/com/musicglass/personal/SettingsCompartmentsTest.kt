package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F5 (Q19F3): the settings compartments open one at a time, keep their rows' tags, and say whether they are open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class SettingsCompartmentsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }

    private val groups = listOf("sound", "look", "downloads", "library", "widgets", "about")

    private fun show() {
        val provider = ProviderWorld(context).also { world = it }.provider
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context)) {
                RiffTheme { SettingsScreen(ShellModel(), 0.dp, provider, LocalCollections(context)) {} }
            }
        }
        compose.waitForIdle()
    }
    private fun group(id: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("settings_group_$id"))
        return compose.onNodeWithTag("settings_group_$id")
    }
    private fun state(id: String) = group(id).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun everyCompartmentStartsClosedAndIsAHeading() {
        show()
        for (id in groups) {
            assertEquals(id, "Closed", state(id))
            assertTrue(id, group(id).fetchSemanticsNode().config.contains(SemanticsProperties.Heading))
        }
        assertFalse(has("settings_volume_boost"))
        assertFalse(has("settings_data_saver"))
    }

    @Test fun openingOneClosesTheOtherAndTheRowsKeepTheirTags() {
        show()
        group("sound").performClick(); compose.waitForIdle()
        assertEquals("Open", state("sound"))
        for (tag in listOf("settings_volume_boost", "settings_normalize_volume", "settings_crossfade")) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
            assertTrue(tag, has(tag))
        }
        group("downloads").performClick(); compose.waitForIdle()
        assertEquals("Closed", state("sound"))
        assertEquals("Open", state("downloads"))
        assertFalse(has("settings_volume_boost"))
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("settings_data_saver"))
        assertTrue(has("settings_data_saver"))
        group("downloads").performClick(); compose.waitForIdle()
        assertEquals("Closed", state("downloads"))
        assertFalse(has("settings_data_saver"))
    }

    /** Q18-099: Song quality sits under Data saver, starts at Medium, keeps a choice, and says when Data saver wins. */
    @Test fun songQualityIsChosenUnderDataSaverAndSaysWhenDataSaverWins() {
        context.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        show()
        group("downloads").performClick(); compose.waitForIdle()
        fun scrollTo(tag: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
        scrollTo("settings_song_quality_note")
        compose.onNodeWithTag("settings_song_quality_medium").assertIsSelected()
        compose.onNodeWithTag("settings_song_quality_note").assertTextEquals(string(R.string.song_quality_note))
        compose.onNodeWithTag("settings_song_quality_low").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("settings_song_quality_low").assertIsSelected()
        compose.onNodeWithTag("settings_song_quality_medium").assertIsNotSelected()
        assertEquals(SongQuality.Low, DataSaverSettings(context).songQuality)
        scrollTo("settings_data_saver"); compose.onNodeWithTag("settings_data_saver").performClick(); compose.waitForIdle()
        scrollTo("settings_song_quality_note")
        compose.onNodeWithTag("settings_song_quality_note").assertTextEquals(string(R.string.song_quality_data_saver_on))
    }

    @Test fun theLookCompartmentHoldsTodaysSliders() {
        show()
        group("look").performClick(); compose.waitForIdle()
        for (tag in listOf("settings_frosted_glass", "settings_reduce_motion", "settings_solid_controls", "settings_haptics")) {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
            assertTrue(tag, has(tag))
        }
    }
}
