package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertubex.extraction.AudioQuality
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataSaverTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {context.getSharedPreferences(DATA_SAVER_PREFS,Context.MODE_PRIVATE).edit().clear().commit()}

    @Test fun settingSurvivesReopeningAndControlsStreamQualityWithoutLoweringDownloads() {
        val settings=DataSaverSettings(context)
        assertFalse(settings.enabled)
        assertEquals(AudioQuality.AUTO,streamingAudioQuality(settings.enabled,false))
        settings.updateEnabled(true)
        val restored=DataSaverSettings(context)
        assertTrue(restored.enabled)
        assertEquals(AudioQuality.LOW,streamingAudioQuality(restored.enabled,false))
        assertEquals(AudioQuality.HIGH,streamingAudioQuality(restored.enabled,true))
        restored.updateEnabled(false)
        assertEquals(AudioQuality.AUTO,streamingAudioQuality(DataSaverSettings(context).enabled,false))
        assertEquals(AudioQuality.HIGH,streamingAudioQuality(false,true))
    }
}
