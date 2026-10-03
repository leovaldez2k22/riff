package com.musicglass.personal

import android.content.Context

/** Small on-device diagnostic ring; never records track names, signed URLs, or credentials. */
internal class PlaybackEventLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_events", Context.MODE_PRIVATE)
    fun append(event: String) {
        android.util.Log.i("RiffPlayback",event)
        val entry = "${System.currentTimeMillis()} $event"
        // Several instances share one preference key; serialize the read-modify-write.
        synchronized(lock) {
            val lines = prefs.getString("recent", "").orEmpty().lineSequence()
                .filter { it.isNotBlank() }.toList().takeLast(EVENT_LOG_LINES - 1) + entry
            prefs.edit().putString("recent", lines.joinToString("\n")).apply()
        }
    }
    fun recent(): List<String> = synchronized(lock) {
        prefs.getString("recent", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    }
    private companion object { val lock = Any() }
}

internal const val EVENT_LOG_LINES = 200

private val urlPattern = Regex("https?://\\S+")
private val secretPattern = Regex("(?i)\\b(pot|sig|signature|n|key|token|sapisid|[a-z0-9_-]*psid[a-z0-9_-]*)=[^\\s&\"]+")

/**
 * The text "Copy playback diagnostics" puts on the clipboard (plan W1.4). The log is written
 * without URLs or tokens; this scrubs them again in case a future event line slips one in.
 */
internal fun playbackDiagnosticsReport(lines: List<String>, version: String, sdk: Int, device: String): String {
    val body = lines.takeLast(EVENT_LOG_LINES).joinToString("\n") {
        it.replace(urlPattern, "<url>").replace(secretPattern) { match -> match.groupValues[1] + "=<redacted>" }
    }
    return "Riff $version, Android SDK $sdk, $device\n${lines.size.coerceAtMost(EVENT_LOG_LINES)} playback events (oldest first)\n$body"
}
