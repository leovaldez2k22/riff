package com.musicglass.personal

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal const val AUDIO_BOOST_PREFS = "audio_boost"
internal fun boostPercent(value: Int) = value.coerceIn(100, 300)
internal fun boostGain(enabled: Boolean, percent: Int) = if (enabled) boostPercent(percent) / 100f else 1f

/** Audio preferences shared by Settings and the playback service. Disabled by default. */
internal class AudioBoostSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(AUDIO_BOOST_PREFS, Context.MODE_PRIVATE)
    var enabled by mutableStateOf(prefs.getBoolean("enabled", false)); private set
    var percent by mutableIntStateOf(boostPercent(prefs.getInt("percent", 200))); private set
    fun updateEnabled(value: Boolean) { enabled = value; prefs.edit {putBoolean("enabled", value)} }
    fun updatePercent(value: Int) { percent = boostPercent(value); prefs.edit {putInt("percent", percent)} }
}
