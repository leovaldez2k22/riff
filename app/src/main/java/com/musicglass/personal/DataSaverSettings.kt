package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.innertubex.extraction.AudioQuality

internal const val DATA_SAVER_PREFS = "streaming_data"

/** Read at resolution time so an already running song isn't interrupted by a settings change. */
internal class DataSaverSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean("enabled", value).apply()
    }
}

internal fun streamingAudioQuality(dataSaver: Boolean, download: Boolean): AudioQuality = when {
    download -> AudioQuality.HIGH
    dataSaver -> AudioQuality.LOW
    else -> AudioQuality.AUTO
}
