package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings, redesigned (owner, 30 Oct): an opening page of tiles, each opening its section's page of glass tiles, and
 * Moods as a section of its own. The rows keep their tags.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class SettingsPagesTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    /** Robolectric replaces the application between tests; process-owned settings must follow it. */
    @Before fun freshBlendSettings() {
        CrossfadeSettings::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { world?.close() }

    private val sections = listOf("sound" to SettingsSection.Sound, "look" to SettingsSection.Look, "lyrics" to SettingsSection.Lyrics,
        "downloads" to SettingsSection.Downloads, "library" to SettingsSection.Library, "about" to SettingsSection.About,
        "moods" to SettingsSection.Moods)

    private fun show(model: ShellModel = ShellModel(), section: SettingsSection? = null) {
        val provider = ProviderWorld(context).also { world = it }.provider
        compose.setContent {
            // The sections' little pictures move every frame while shown; with reduced motion they hold still, so the
            // test can wait for the page to settle.
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context),
                LocalMotionPolicy provides MotionPolicy(reduced = section != null, scale = 1f)) {
                RiffTheme {
                    if (section == null) SettingsScreen(model, 0.dp, provider, LocalCollections(context)) {}
                    else if (section == SettingsSection.Equalizer) Edition.EqualizerPage(model, 0.dp, null)
                    else SettingsSectionScreen(model, section, 0.dp) {}
                }
            }
        }
        settle()
    }
    private fun settle() = compose.waitForIdle()
    private fun scrollTo(tag: String) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun eachTileOnTheOpeningPageOpensItsSection() {
        val model = ShellModel()
        show(model)
        for ((id, section) in sections) {
            scrollTo("settings_group_$id")
            compose.onNodeWithTag("settings_group_$id").performClick(); settle()
            assertEquals(id, Route.Settings(section), model.screen.route)
            model.back()
        }
    }

    @Test fun theQuickSwitchesTurnTheirSettingsOnAndOff() {
        context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        CrossfadeSettings.get(context).reload()
        assertFalse("quick-switch fixture starts disabled", CrossfadeSettings.get(context).enabled)
        context.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        show()
        compose.onNodeWithTag("settings_quick_saver").performClick(); settle()
        assertTrue(DataSaverSettings(context).enabled)
        assertFalse("Data saver must not enable blending", CrossfadeSettings.get(context).enabled)
        compose.onNodeWithTag("settings_quick_automix").performClick(); settle()
        assertTrue(CrossfadeSettings.get(context).enabled); assertEquals(if (Edition.plus) BlendMode.Automix else BlendMode.Crossfade, CrossfadeSettings.get(context).mode)
        compose.onNodeWithTag("settings_quick_saver").performClick(); settle()
        assertFalse(DataSaverSettings(context).enabled)
    }

    @Test fun theSoundPageOffersOnlyTheEditionsBlendModesAndKeepsStoredAutomix() {
        val prefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(BLEND_MODE_KEY, "automix").putBoolean("enabled", true).commit()
        CrossfadeSettings.get(context).reload()
        assertEquals("imported preference read before UI", if (Edition.plus) BlendMode.Automix else BlendMode.Smart, CrossfadeSettings.get(context).mode)
        show(section = SettingsSection.Sound)
        scrollTo("settings_blend_smart")
        assertTrue(has("settings_blend_crossfade"))
        assertTrue(has("settings_blend_smart"))
        assertEquals(Edition.plus, has("settings_blend_automix"))
        compose.onNodeWithTag(if (Edition.plus) "settings_blend_automix" else "settings_blend_smart").assertIsSelected()
        assertEquals("automix", prefs.getString(BLEND_MODE_KEY, null))
    }

    @Test fun automixSearchIsAvailableOnlyInPlus() {
        show()
        compose.onNodeWithTag("settings_search").performTextInput("automix"); settle()
        compose.onNodeWithTag("settings_group_sound").assert(SemanticsMatcher.expectValue(SettingsSearchMatch, Edition.plus))
        if (!Edition.plus) {
            compose.onNodeWithTag("settings_search").performTextReplacement("beat"); settle()
            compose.onNodeWithTag("settings_group_sound").assert(SemanticsMatcher.expectValue(SettingsSearchMatch, false))
        }
    }

    @Test fun searchingLeavesTheMatchingSectionsAndClears() {
        show()
        compose.onNodeWithTag("settings_search").performTextInput("crossfade"); compose.waitForIdle()
        assertTrue(has("settings_group_sound"))
        compose.onNodeWithContentDescription(string(R.string.settings_search_clear)).performClick(); settle()
        compose.onNodeWithTag("settings_search").assertTextEquals("")
    }

    @Test fun soundHoldsItsRowsAndTheBlendChoiceIsTheTabBarsPill() {
        show(section = SettingsSection.Sound)
        for (tag in listOf("settings_crossfade", "settings_volume_boost", if (Edition.plus) "settings_equalizer" else "settings_phone_equalizer", "settings_normalize_volume",
            "settings_skip_end_silence", "settings_skip_all_silence", "settings_bluetooth_resume", "settings_pause_on_mute")) { scrollTo(tag); assertTrue(tag, has(tag)) }
        val crossfade = CrossfadeSettings.get(context)
        scrollTo("settings_blend_smart")
        compose.onNodeWithTag("settings_blend_smart").performClick(); settle()
        assertEquals(BlendMode.Smart, crossfade.mode); assertTrue("choosing a blend turns it on", crossfade.enabled)
        compose.onNodeWithTag("settings_blend_smart").assertIsSelected()
        assertFalse("the length is the plain crossfade's", has("settings_crossfade_length"))
        compose.onNodeWithTag("settings_blend_crossfade").performClick(); settle()
        scrollTo("settings_crossfade_length"); assertTrue(has("settings_crossfade_length"))
    }

    @Test fun aRestoredEqualizerRouteShowsOnlyThisEditionsControls() {
        show(section = SettingsSection.Equalizer)
        if (Edition.plus) {
            scrollTo("equalizer_switch")
            assertTrue(has("equalizer_switch"))
            assertFalse(has("settings_crossfade"))
        } else {
            scrollTo("settings_phone_equalizer")
            assertTrue(has("settings_phone_equalizer"))
            assertFalse(has("equalizer_switch"))
            assertFalse(has("settings_equalizer"))
        }
    }

    @Test fun unsupportedEqualizerSettingsDoNotAppearInTheSoundSummaryOrSearch() {
        EqualizerSettings.write(EqualizerSettings.prefs(context), EqualizerConfig(enabled = true))
        show()
        compose.onNodeWithTag("settings_search").performTextInput("bass"); settle()
        compose.onNodeWithTag("settings_group_sound").assert(SemanticsMatcher.expectValue(SettingsSearchMatch, Edition.plus))
        if (!Edition.plus) {
            compose.onNodeWithText(string(R.string.settings_eq_on), substring = true).assertDoesNotExist()
        }
    }

    @Test fun lookAndLyricsHoldTheirSwitches() {
        show(section = SettingsSection.Look)
        for (tag in listOf("settings_glass_intensity", "settings_frosted_intensity", "settings_frosted_glass", "settings_reduce_motion",
            "settings_solid_controls", "settings_haptics")) { scrollTo(tag); assertTrue(tag, has(tag)) }
    }

    /** Q18-099: Song quality, now the tab bar's pill per network, starts at Medium, keeps a choice, and says when Data saver wins. */
    @Test fun lookSettingsKeepTheStoredStatsChoiceAndShowItOnlyInPlus() {
        val prefs = context.getSharedPreferences("accessibility_display", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("player_stats", true).commit()
        show(section = SettingsSection.Look)
        scrollTo("settings_haptics")
        assertEquals(Edition.plus, has("settings_player_stats"))
        assertTrue(prefs.getBoolean("player_stats", false))
    }

    @Test fun statsSearchIsAvailableOnlyInPlus() {
        show()
        compose.onNodeWithTag("settings_search").performTextInput("stats"); settle()
        compose.onNodeWithTag("settings_group_look").assert(SemanticsMatcher.expectValue(SettingsSearchMatch, Edition.plus))
    }

    @Test fun songQualityIsChosenPerNetworkAndSaysWhenDataSaverWins() {
        context.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        show(section = SettingsSection.Downloads)
        scrollTo("settings_song_quality_note")
        compose.onNodeWithTag("settings_song_quality_medium").assertIsSelected()
        compose.onNodeWithTag("settings_song_quality_note").assertTextEquals(string(R.string.song_quality_note))
        compose.onNodeWithTag("settings_song_quality_low").performClick(); settle()
        compose.onNodeWithTag("settings_song_quality_low").assertIsSelected()
        compose.onNodeWithTag("settings_song_quality_medium").assertIsNotSelected()
        assertEquals(SongQuality.Low, DataSaverSettings(context).unmeteredQuality)
        assertEquals(SongQuality.Medium, DataSaverSettings(context).meteredQuality)
        scrollTo("settings_data_saver"); compose.onNodeWithTag("settings_data_saver").performClick(); settle()
        scrollTo("settings_song_quality_note")
        compose.onNodeWithTag("settings_song_quality_note").assertTextEquals(string(R.string.song_quality_data_saver_on))
    }

    @Test fun aMoodSetsItsSettingsAndIsTickedWhileTheyHold() {
        context.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        show(section = SettingsSection.Moods)
        scrollTo("settings_mood_commute")
        compose.onNodeWithTag("settings_mood_commute").performClick(); settle()
        val crossfade = CrossfadeSettings.get(context)
        assertTrue(crossfade.enabled); assertEquals(BlendMode.Crossfade, crossfade.mode); assertEquals(3, crossfade.seconds)
        assertTrue(DataSaverSettings(context).enabled)
        assertTrue(compose.onAllNodesWithContentDescription(string(R.string.mood_is_on)).fetchSemanticsNodes().isNotEmpty())
    }
}
