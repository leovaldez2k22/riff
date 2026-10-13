package com.musicglass.personal

import android.content.Context

/**
 * Small on-device diagnostic ring. Every line passes [DiagnosticEvent.parse] before it is stored or written
 * to logcat, so the ring holds names, numbers and short words and nothing else (Q15).
 */
internal class PlaybackEventLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("playback_events", Context.MODE_PRIVATE)
    fun append(event: String) = append(DiagnosticEvent.parse(event))
    fun append(event: DiagnosticEvent) {
        android.util.Log.i("RiffPlayback", event.text)
        val entry = "${System.currentTimeMillis()} ${event.text}"
        // Several instances share one preference key; serialize the read-modify-write.
        synchronized(lock) {
            // Lines written by a version from before the reduction are reduced once, on the first write.
            val reduced = prefs.getInt(FORMAT, 0) >= RING_FORMAT
            val kept = prefs.getString("recent", "").orEmpty().lineSequence()
                .filter { it.isNotBlank() }.toList().takeLast(EVENT_LOG_LINES - 1)
            val lines = (if (reduced) kept else kept.map(::scrubStoredLine)) + entry
            prefs.edit().putString("recent", lines.joinToString("\n")).putInt(FORMAT, RING_FORMAT).apply()
        }
    }
    fun recent(): List<String> = synchronized(lock) {
        prefs.getString("recent", "").orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    }
    private companion object { val lock = Any(); const val FORMAT = "format"; const val RING_FORMAT = 2 }
}

internal const val EVENT_LOG_LINES = 200

private val storedLine = Regex("([0-9]{1,16}) (.*)", RegexOption.DOT_MATCHES_ALL)

/** A stored line, `<time> <event>`, with the event reduced again. A line of any other form keeps nothing. */
internal fun scrubStoredLine(line: String): String {
    val match = storedLine.matchEntire(line.take(DiagnosticEvent.MAX_INPUT)) ?: return "0 ${DiagnosticEvent.REJECTED}"
    return "${match.groupValues[1]} ${DiagnosticEvent.parse(match.groupValues[2]).text}"
}

private val unsafeHeader = Regex("[^\\x20-\\x7E]")

/**
 * The text "Copy playback diagnostics" puts on the clipboard (plan W1.4). This is the export scrubber: every
 * line is reduced again on the way out, whatever wrote it and whenever, and the heading is kept to one line
 * of plain characters. The content is fixed: build identity and the event ring. No file is read for it.
 */
internal fun playbackDiagnosticsReport(lines: List<String>, version: String, sdk: Int, device: String, identity: BuildIdentity = BuildIdentity.current()): String {
    fun plain(value: String) = value.replace(unsafeHeader, " ").take(64)
    val recent = lines.takeLast(EVENT_LOG_LINES)
    val body = recent.joinToString("\n", transform = ::scrubStoredLine)
    return "Riff ${plain(version)}, Android SDK $sdk, ${plain(device)}\n${identity.diagnosticLine}\n${recent.size} playback events (oldest first)\n$body"
}
