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
class EditionBlendPreferencesTest {
    @Test fun readingAndReloadingImportedAutomixNeverRewritesTheStoredChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(CROSSFADE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putString(BLEND_MODE_KEY, "automix").putBoolean("enabled", true).putInt("seconds", 9).commit()
        val before = prefs.all.toMap()
        val settings = CrossfadeSettings(context)
        val expected = if (Edition.plus) BlendMode.Automix else BlendMode.Smart
        assertEquals(expected, settings.mode)
        assertEquals(expected, effectiveBlendMode(prefs))
        settings.reload()
        assertEquals(expected, settings.mode)
        assertEquals(before, prefs.all)
    }

    @Test fun theLightEditionHasNoEchoProcessor() {
        val stage = Edition.audioEffects(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(Edition.plus, stage.echo != null)
        if (!Edition.plus) assertTrue(runCatching { Class.forName("com.musicglass.personal.PcmEcho") }.isFailure)
    }
}
