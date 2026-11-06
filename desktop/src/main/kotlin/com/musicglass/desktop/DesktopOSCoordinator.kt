package com.musicglass.desktop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level coordinator managing Windows OS integrations:
 * SMTC, notification tray, power locks, global shortcuts, and presence.
 * Conforms to Section 5.3 of reports/Riff Windows desktop architecture.md.
 */
internal class DesktopOSCoordinator(
    val smtc: SMTCAdapter = WindowsSMTCAdapter(),
    val tray: TrayAdapter = WindowsTrayAdapter(),
    val power: PowerAdapter = WindowsPowerAdapter(),
    val shortcuts: GlobalShortcutsAdapter = WindowsGlobalShortcuts(),
    val presence: PresenceAdapter = DiscordPresenceAdapter(),
    private val onRestoreRequested: () -> Unit = {},
    private val onTogglePlayPauseRequested: () -> Unit = {},
    private val onNextRequested: () -> Unit = {},
    private val onPreviousRequested: () -> Unit = {},
    private val onQuitRequested: () -> Unit = {}
) : AutoCloseable {

    private val isDisposed = AtomicBoolean(false)
    private var isPlaying = false

    init {
        // Wire up SMTC media listener
        smtc.setListener(object : OSMediaListener {
            override fun onPlay() { setPlaybackState(true) }
            override fun onPause() { setPlaybackState(false) }
            override fun onTogglePlayPause() {
                onTogglePlayPauseRequested()
                setPlaybackState(!isPlaying)
            }
            override fun onNext() { onNextRequested() }
            override fun onPrevious() { onPreviousRequested() }
            override fun onSeekTo(positionMs: Long) { /* delegated to audio bridge */ }
            override fun onStop() { setPlaybackState(false) }
        })

        // Wire up Tray listener
        tray.initTray(
            title = "Riff Music Player",
            listener = object : TrayListener {
                override fun onRestoreWindow() { onRestoreRequested() }
                override fun onTogglePlayPause() {
                    onTogglePlayPauseRequested()
                    setPlaybackState(!isPlaying)
                }
                override fun onNext() { onNextRequested() }
                override fun onPrevious() { onPreviousRequested() }
                override fun onQuit() { onQuitRequested() }
            }
        )

        // Register default hardware media keys
        shortcuts.registerShortcut(
            id = 101,
            name = "MediaPlayPause",
            modifiers = 0,
            keyCode = WindowsGlobalShortcuts.VK_MEDIA_PLAY_PAUSE
        ) {
            onTogglePlayPauseRequested()
            setPlaybackState(!isPlaying)
        }
    }

    fun onTrackChanged(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        artworkUrl: String? = null
    ) {
        val meta = OSTrackMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artworkUrl = artworkUrl
        )
        smtc.updateMetadata(meta)
        tray.updateTooltip("Riff - $title - $artist")
        if (isPlaying) {
            presence.updatePresence(title, artist, isPlaying = true, durationMs = durationMs, positionMs = 0L)
        }
    }

    fun setPlaybackState(playing: Boolean) {
        this.isPlaying = playing
        val status = if (playing) OSPlaybackStatus.PLAYING else OSPlaybackStatus.PAUSED

        smtc.updatePlaybackStatus(status)
        tray.updatePlaybackStatus(status)
        power.requestPlaybackExecution(playing)

        if (!playing) {
            presence.clearPresence()
        }
    }

    fun updateTimeline(positionMs: Long, durationMs: Long) {
        smtc.updateTimeline(OSTimelineState(positionMs, durationMs))
    }

    fun isPlaybackActive(): Boolean = isPlaying

    override fun close() {
        if (isDisposed.compareAndSet(false, true)) {
            power.requestPlaybackExecution(false)
            shortcuts.close()
            tray.close()
            smtc.close()
            presence.close()
        }
    }
}
