package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.extraction.AudioQuality

internal const val DATA_SAVER_PREFS = "streaming_data"
internal const val SONG_QUALITY_KEY = "song_quality"

/** Q18-099: the quality chosen for streamed songs, kept per phone. Medium is what Riff always asked for. */
internal enum class SongQuality(val key: String) { High("high"), Medium("medium"), Low("low") }

internal fun songQualityFrom(key: String?): SongQuality = SongQuality.entries.firstOrNull { it.key == key } ?: SongQuality.Medium

/** Read at resolution time so an already running song isn't interrupted by a settings change. */
internal class DataSaverSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
    }
    var songQuality by mutableStateOf(songQualityFrom(prefs.getString(SONG_QUALITY_KEY, null))); private set
    fun updateSongQuality(value: SongQuality) {
        songQuality = value
        prefs.edit().putString(SONG_QUALITY_KEY, value.key).apply()
    }
    /** Feature 17: the quality on an unmetered network (usually Wi-Fi) and on mobile data; both start as [songQuality]. */
    var unmeteredQuality by mutableStateOf(networkSongQuality(prefs, metered = false)); private set
    var meteredQuality by mutableStateOf(networkSongQuality(prefs, metered = true)); private set
    fun updateNetworkQuality(metered: Boolean, value: SongQuality) {
        if (metered) meteredQuality = value else unmeteredQuality = value
        prefs.edit().putString(if (metered) SONG_QUALITY_METERED_KEY else SONG_QUALITY_UNMETERED_KEY, value.key).apply()
    }
}

internal const val SONG_QUALITY_UNMETERED_KEY = "song_quality_unmetered"
internal const val SONG_QUALITY_METERED_KEY = "song_quality_metered"

/** Feature 17: the quality for this kind of network; until one is chosen, the single choice older builds kept. */
internal fun networkSongQuality(prefs: android.content.SharedPreferences, metered: Boolean): SongQuality =
    songQualityFrom(prefs.getString(if (metered) SONG_QUALITY_METERED_KEY else SONG_QUALITY_UNMETERED_KEY, null)
        ?: prefs.getString(SONG_QUALITY_KEY, null))

/** Downloads always ask for the best; Data saver wins over the chosen quality while it is on. */
internal fun streamingAudioQuality(dataSaver: Boolean, download: Boolean, song: SongQuality = SongQuality.Medium): AudioQuality = when {
    download -> AudioQuality.HIGH
    dataSaver -> AudioQuality.LOW
    else -> when (song) { SongQuality.High -> AudioQuality.HIGH; SongQuality.Medium -> AudioQuality.AUTO; SongQuality.Low -> AudioQuality.LOW }
}
