package com.musicglass.desktop

/**
 * Cross-platform contracts for desktop OS integrations (SMTC, System Tray, Power, Shortcuts, Presence).
 * Conforms to Section 5.3 of reports/Riff Windows desktop architecture.md.
 */

enum class OSPlaybackStatus {
    STOPPED,
    PLAYING,
    PAUSED
}

data class OSTrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val artworkUrl: String? = null
)

data class OSTimelineState(
    val positionMs: Long,
    val durationMs: Long,
    val isSeekable: Boolean = true
)

interface OSMediaListener {
    fun onPlay()
    fun onPause()
    fun onTogglePlayPause()
    fun onNext()
    fun onPrevious()
    fun onSeekTo(positionMs: Long)
    fun onStop()
}

interface SMTCAdapter : AutoCloseable {
    fun updateMetadata(metadata: OSTrackMetadata)
    fun updatePlaybackStatus(status: OSPlaybackStatus)
    fun updateTimeline(timeline: OSTimelineState)
    fun setEnabled(enabled: Boolean)
    fun setListener(listener: OSMediaListener?)
}

interface TrayListener {
    fun onRestoreWindow()
    fun onTogglePlayPause()
    fun onNext()
    fun onPrevious()
    fun onQuit()
}

interface TrayAdapter : AutoCloseable {
    fun initTray(title: String, listener: TrayListener)
    fun updateTooltip(tooltip: String)
    fun updatePlaybackStatus(status: OSPlaybackStatus)
    fun isSupported(): Boolean
    fun removeTray()
}

interface PowerAdapter : AutoCloseable {
    /**
     * Prevents system sleep during active playback.
     * Display sleep is always allowed so the screen can turn off.
     */
    fun requestPlaybackExecution(enable: Boolean): Boolean
}

sealed class ShortcutResult {
    data class Success(val id: Int, val name: String) : ShortcutResult()
    data class Conflict(val id: Int, val name: String, val errorCode: Int) : ShortcutResult()
}

interface GlobalShortcutsAdapter : AutoCloseable {
    fun registerShortcut(id: Int, name: String, modifiers: Int, keyCode: Int, action: () -> Unit): ShortcutResult
    fun unregisterShortcut(id: Int)
    fun unregisterAll()
}

interface PresenceAdapter : AutoCloseable {
    fun setEnabled(enabled: Boolean)
    fun updatePresence(
        title: String,
        artist: String,
        isPlaying: Boolean,
        durationMs: Long,
        positionMs: Long
    )
    fun clearPresence()
}
