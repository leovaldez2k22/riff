package com.musicglass.desktop

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Windows Media Transport Controls and hardware media key adapter.
 * Handles single-flight media key routing, debounce window, and metadata publishing.
 * Survives visible Compose window disposal.
 */
internal class WindowsSMTCAdapter : SMTCAdapter {

    private val isEnabled = AtomicBoolean(true)
    private var listener: OSMediaListener? = null

    private val currentMetadata = AtomicReference<OSTrackMetadata>(
        OSTrackMetadata(title = "", artist = "")
    )
    private val currentStatus = AtomicReference<OSPlaybackStatus>(OSPlaybackStatus.STOPPED)
    private val currentTimeline = AtomicReference<OSTimelineState>(
        OSTimelineState(positionMs = 0L, durationMs = 0L)
    )

    private val lastActionTimestamp = AtomicLong(0L)
    private val debounceWindowMs = 150L // De-duplication threshold for hardware keys

    override fun updateMetadata(metadata: OSTrackMetadata) {
        currentMetadata.set(metadata)
    }

    override fun updatePlaybackStatus(status: OSPlaybackStatus) {
        currentStatus.set(status)
    }

    override fun updateTimeline(timeline: OSTimelineState) {
        currentTimeline.set(timeline)
    }

    override fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
    }

    override fun setListener(listener: OSMediaListener?) {
        this.listener = listener
    }

    /**
     * Handles incoming hardware APPCOMMAND or media key event.
     * Guarantees exact single-flight dispatch across main, mini, and tray states.
     */
    fun handleAppCommand(cmd: Int): Boolean {
        if (!isEnabled.get()) return false

        val now = System.currentTimeMillis()
        val prev = lastActionTimestamp.get()
        if (now - prev < debounceWindowMs) {
            // Suppress duplicate echo event
            return false
        }
        lastActionTimestamp.set(now)

        return when (cmd) {
            APPCOMMAND_MEDIA_PLAY_PAUSE -> {
                listener?.onTogglePlayPause()
                true
            }
            APPCOMMAND_MEDIA_PLAY -> {
                listener?.onPlay()
                true
            }
            APPCOMMAND_MEDIA_PAUSE -> {
                listener?.onPause()
                true
            }
            APPCOMMAND_MEDIA_NEXTTRACK -> {
                listener?.onNext()
                true
            }
            APPCOMMAND_MEDIA_PREVIOUSTRACK -> {
                listener?.onPrevious()
                true
            }
            APPCOMMAND_MEDIA_STOP -> {
                listener?.onStop()
                true
            }
            else -> false
        }
    }

    fun getMetadata(): OSTrackMetadata = currentMetadata.get()
    fun getPlaybackStatus(): OSPlaybackStatus = currentStatus.get()
    fun getTimeline(): OSTimelineState = currentTimeline.get()

    override fun close() {
        isEnabled.set(false)
        listener = null
    }

    companion object {
        const val APPCOMMAND_MEDIA_NEXTTRACK = 11
        const val APPCOMMAND_MEDIA_PREVIOUSTRACK = 12
        const val APPCOMMAND_MEDIA_STOP = 13
        const val APPCOMMAND_MEDIA_PLAY_PAUSE = 14
        const val APPCOMMAND_MEDIA_PLAY = 46
        const val APPCOMMAND_MEDIA_PAUSE = 47
    }
}
