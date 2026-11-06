package com.musicglass.desktop

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Windows power management adapter.
 * Uses SetThreadExecutionState to prevent system sleep during playback while allowing the display to sleep.
 */
internal class WindowsPowerAdapter : PowerAdapter {

    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    companion object {
        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002
        const val ES_CONTINUOUS = 0x80000000.toInt()
        const val ES_AWAYMODE_REQUIRED = 0x00000040
    }

    private val kernel32: Kernel32? = try {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            Native.load("kernel32", Kernel32::class.java)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private val isExecutionRequested = AtomicBoolean(false)

    override fun requestPlaybackExecution(enable: Boolean): Boolean {
        if (kernel32 == null) {
            isExecutionRequested.set(enable)
            return true
        }

        return try {
            if (enable) {
                // ES_CONTINUOUS | ES_SYSTEM_REQUIRED
                // Intentionally NOT setting ES_DISPLAY_REQUIRED so screens can turn off/sleep
                val flags = ES_CONTINUOUS or ES_SYSTEM_REQUIRED
                val prev = kernel32.SetThreadExecutionState(flags)
                val success = (prev != 0)
                if (success) {
                    isExecutionRequested.set(true)
                }
                success
            } else {
                // Clear system required flag by restoring continuous execution
                val prev = kernel32.SetThreadExecutionState(ES_CONTINUOUS)
                isExecutionRequested.set(false)
                prev != 0
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun isPlaybackExecutionActive(): Boolean = isExecutionRequested.get()

    override fun close() {
        if (isExecutionRequested.get()) {
            requestPlaybackExecution(false)
        }
    }
}
