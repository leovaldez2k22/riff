package com.musicglass.personal

import android.content.Context
import android.media.audiofx.AudioEffect
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class EditionAudioTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun importingPlusSettingsAndCreatingAudioNeverChangesTheirStoredValues() {
        val prefs = EqualizerSettings.prefs(context)
        prefs.edit().clear().commit()
        val stored = EqualizerConfig(true, EqMode.Manual, 3, -2, true,
            listOf(6f, 4f, 1f, 0f, -2f, 3f, 5f), "Custom", -.25f)
        EqualizerSettings.write(prefs, stored)
        val before = prefs.all.toMap()
        val audio = Edition.audioEffects(context)
        audio.start()
        val tail = audio.createTail()
        // Riff+: Sing then the equalizer; its blend tail adds the echo (features 20, 10).
        assertEquals(if (Edition.plus) 2 else 0, audio.beforeBoost.size)
        assertEquals(if (Edition.plus) 3 else 0, tail.processors.size)
        assertEquals(Edition.plus, audio.shelves != null)
        assertEquals(Edition.plus, audio.echo != null)
        tail.release(); audio.release()
        assertEquals(stored, EqualizerSettings.read(prefs))
        assertEquals(before, prefs.all)
        if (!Edition.plus) {
            for (name in listOf("EqualizerProcessor", "PcmEqualizer", "EqualizerScreenKt", "PcmEcho", "SingProcessor", "SingDspKt"))
                assertTrue(name, runCatching { Class.forName("com.musicglass.personal.$name") }.isFailure)
        }
    }

    @Test fun phoneEqualizerUsesTheExistingSystemActionAndThisPackage() {
        val intent = phoneEqualizerIntent(context)
        assertEquals(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
        assertEquals(AudioEffect.CONTENT_TYPE_MUSIC, intent.getIntExtra(AudioEffect.EXTRA_CONTENT_TYPE, -1))
    }

    @Test fun presetStorageAndImportValidationAreAvailableInBothEditions() {
        assertEquals("Rock", presetFor(EQ_PRESETS.getValue("Rock")))
        assertEquals(List(7) { 0f }, EqualizerSettings.parseManual(null))
        val specs = equalizerSettingSpecs().toMap()
        assertEquals("Rock", specs.getValue("preset")("Rock"))
        assertNull(specs.getValue("preset")("Unknown"))
        assertEquals("manual", specs.getValue("mode")("manual"))
        assertEquals(-.5f, specs.getValue("balance")(-.5))
        assertNull(specs.getValue("balance")(2.0))
    }
}
