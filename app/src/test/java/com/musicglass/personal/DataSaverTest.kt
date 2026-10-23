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

    /** Q18-099: High, Medium or Low for streamed songs; Data saver wins while it is on and downloads stay HIGH. */
    @Test fun songQualityIsKeptAndMapsToTheLibrarysQuality() {
        assertEquals(SongQuality.Medium,DataSaverSettings(context).songQuality)
        assertEquals(AudioQuality.HIGH,streamingAudioQuality(false,false,SongQuality.High))
        assertEquals(AudioQuality.AUTO,streamingAudioQuality(false,false,SongQuality.Medium))
        assertEquals(AudioQuality.LOW,streamingAudioQuality(false,false,SongQuality.Low))
        SongQuality.entries.forEach {
            assertEquals("Data saver wins over $it",AudioQuality.LOW,streamingAudioQuality(true,false,it))
            assertEquals("a download stays HIGH with $it",AudioQuality.HIGH,streamingAudioQuality(true,true,it))
        }
        DataSaverSettings(context).updateSongQuality(SongQuality.Low)
        assertEquals(SongQuality.Low,DataSaverSettings(context).songQuality)
        context.getSharedPreferences(DATA_SAVER_PREFS,Context.MODE_PRIVATE).edit().putString(SONG_QUALITY_KEY,"ultra").commit()
        assertEquals("an unknown value reads as Medium",SongQuality.Medium,DataSaverSettings(context).songQuality)
    }

    @Test fun thePremiumHintGoesOnlyWithHighAndConfirmedPremium() {
        SongQuality.entries.forEach {
            val hints=streamHints(video=false,live=false,download=false,quality=streamingAudioQuality(false,false,it),premium=true)
            assertEquals(it.name,it==SongQuality.High,hints.premium)
        }
        assertFalse(streamHints(video=false,live=false,download=false,quality=streamingAudioQuality(false,false,SongQuality.High),premium=false).premium)
        assertFalse("Data saver wins",streamHints(video=false,live=false,download=false,quality=streamingAudioQuality(true,false,SongQuality.High),premium=true).premium)
    }
}
