package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Edition-specific UI and native-model initialization on the actual Android runtime, offline. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class EditionJourneysDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FeatureTestActivity>()
    private val app: Context get() = compose.activity.applicationContext
    private val saved = linkedMapOf<String, Map<String, Any?>>()

    @Before fun preserveSettings() {
        PlaybackDeviceHarness().requireDisposableInstall()
        for (name in listOf(CROSSFADE_PREFS, EQUALIZER_PREFS)) {
            saved[name] = app.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
        }
    }

    @After fun restoreSettings() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((name, values) in saved) {
                val edit = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for ((key, value) in values) put(edit, key, value)
                assertTrue("restore fixture preferences", edit.commit())
            }
            CrossfadeSettings.get(app).reload()
        }
    }

    private fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> edit.remove(key)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
            is Set<*> -> {
                val strings = value.filterIsInstance<String>().toSet()
                check(strings.size == value.size)
                edit.putStringSet(key, strings)
            }
            else -> error("Unsupported preference value: ${value.javaClass.name}")
        }
    }

    private fun show(content: @Composable () -> Unit) {
        val accessibility = AccessibilitySettings(app)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides accessibility,
                LocalMotionPolicy provides MotionPolicy(reduced = true, scale = 1f)) {
                RiffTheme { Box(Modifier.fillMaxSize()) { content() } }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(tag))
    }

    @Test fun storedAutomixSurvivesAndOnlyTheInstalledEditionsModesAreOffered() {
        val prefs = app.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(prefs.edit().putBoolean("enabled", true).putString(BLEND_MODE_KEY, "automix").putInt("seconds", 6).commit())
            CrossfadeSettings.get(app).reload()
        }
        val before = prefs.all.toMap()
        assertEquals(Edition.plus, Edition.hasBeatModel(app))
        // This opens the real ONNX session in Plus; it does not claim beat accuracy or timing evidence.
        assertEquals(Edition.plus, Edition.beatAnalysis(app) != null)
        val model = ShellModel()
        show { SoundSection(model, 0.dp) }
        scrollTo("settings_blend_smart")
        compose.onNodeWithTag("settings_blend_crossfade").assertExists()
        compose.onNodeWithTag("settings_blend_smart").assertExists()
        if (Edition.plus) compose.onNodeWithTag("settings_blend_automix").assertIsSelected()
        else {
            compose.onNodeWithTag("settings_blend_automix").assertDoesNotExist()
            compose.onNodeWithTag("settings_blend_smart").assertIsSelected()
        }
        assertEquals("opening this edition does not rewrite stored Automix", before, prefs.all)
        compose.onNodeWithTag("settings_blend_crossfade").performClick()
        compose.waitForIdle()
        assertEquals("a deliberate selection is saved", BlendMode.Crossfade.wire, prefs.getString(BLEND_MODE_KEY, null))
    }

    @Test fun equalizerControlsWorkInPlusAndTheLightFallbackKeepsTheirSavedValues() {
        val prefs = EqualizerSettings.prefs(app)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            EqualizerSettings.write(prefs, EqualizerConfig(enabled = false, mode = EqMode.Dynamic))
        }
        val before = prefs.all.toMap()
        val model = ShellModel()
        show { Edition.EqualizerPage(model, 0.dp, null) }
        if (Edition.plus) {
            compose.onNodeWithTag("equalizer_switch").performClick()
            compose.waitForIdle()
            assertTrue(EqualizerSettings.read(prefs).enabled)
            compose.onNodeWithTag("equalizer_tab_manual").performClick()
            compose.waitForIdle()
            assertEquals(EqMode.Manual, EqualizerSettings.read(prefs).mode)
            compose.onNodeWithTag("equalizer_preset_Bass boost").performClick()
            compose.waitForIdle()
            assertEquals(EQ_PRESETS.getValue("Bass boost"), EqualizerSettings.read(prefs).manual)
            assertEquals("Bass boost", EqualizerSettings.read(prefs).preset)
        } else {
            compose.onNodeWithTag("equalizer_switch").assertDoesNotExist()
            scrollTo("settings_phone_equalizer")
            compose.onNodeWithTag("settings_phone_equalizer").assertExists()
            assertEquals("the Sound fallback preserves Plus EQ settings", before, prefs.all)
        }
        val effects = Edition.audioEffects(app)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { effects.start() }
        try {
            assertEquals(if (Edition.plus) 1 else 0, effects.beforeBoost.size)
            val tail = effects.createTail()
            try { assertEquals(if (Edition.plus) 2 else 0, tail.processors.size) }
            finally { tail.release() }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { effects.release() }
        }
    }
}
