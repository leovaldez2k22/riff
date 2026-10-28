package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Feature 17: a quality for Wi-Fi and one for mobile data, both starting from the single choice older builds kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkQualityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE)
    @Before fun clean() { prefs.edit().clear().commit() }

    @Test fun anEarlierSingleChoiceCarriesOverToBoth() {
        prefs.edit().putString(SONG_QUALITY_KEY, SongQuality.High.key).commit()
        assertEquals(SongQuality.High, networkSongQuality(prefs, metered = false))
        assertEquals(SongQuality.High, networkSongQuality(prefs, metered = true))
    }

    @Test fun eachNetworkKeepsItsOwnOnceChosen() {
        val settings = DataSaverSettings(context)
        settings.updateNetworkQuality(metered = true, SongQuality.Low)
        settings.updateNetworkQuality(metered = false, SongQuality.High)
        assertEquals(SongQuality.Low, networkSongQuality(prefs, metered = true))
        assertEquals(SongQuality.High, networkSongQuality(prefs, metered = false))
        val reopened = DataSaverSettings(context)
        assertEquals(SongQuality.Low, reopened.meteredQuality)
        assertEquals(SongQuality.High, reopened.unmeteredQuality)
    }

    @Test fun nothingChosenIsMedium() {
        assertEquals(SongQuality.Medium, networkSongQuality(prefs, metered = true))
    }

    @Test fun dataSaverAndDownloadsStillWin() {
        assertEquals(com.metrolist.innertubex.extraction.AudioQuality.LOW, streamingAudioQuality(true, false, SongQuality.High))
        assertEquals(com.metrolist.innertubex.extraction.AudioQuality.HIGH, streamingAudioQuality(false, true, SongQuality.Low))
    }
}
