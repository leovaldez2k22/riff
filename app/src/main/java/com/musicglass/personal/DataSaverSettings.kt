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
}

/** Downloads always ask for the best; Data saver wins over the chosen quality while it is on. */
internal fun streamingAudioQuality(dataSaver: Boolean, download: Boolean, song: SongQuality = SongQuality.Medium): AudioQuality = when {
    download -> AudioQuality.HIGH
    dataSaver -> AudioQuality.LOW
    else -> when (song) { SongQuality.High -> AudioQuality.HIGH; SongQuality.Medium -> AudioQuality.AUTO; SongQuality.Low -> AudioQuality.LOW }
}
