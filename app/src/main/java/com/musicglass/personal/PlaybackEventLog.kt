package com.musicglass.personal

import android.content.Context

/** Small on-device diagnostic ring; never records track names, signed URLs, or credentials. */
internal class PlaybackEventLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_events", Context.MODE_PRIVATE)
    fun append(event: String) {
        android.util.Log.i("RiffPlayback",event)
        val entry = "${System.currentTimeMillis()} $event"
        val lines = prefs.getString("recent", "").orEmpty().lineSequence()
            .filter { it.isNotBlank() }.toList().takeLast(79) + entry
        prefs.edit().putString("recent", lines.joinToString("\n")).apply()
    }
}
