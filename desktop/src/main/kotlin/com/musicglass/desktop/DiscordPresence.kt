package com.musicglass.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * Lightweight Windows Discord Rich Presence adapter via Discord IPC named pipes.
 * Fully opt-in, non-blocking, and fail-safe: absent or crashed Discord never affects playback.
 */
internal class DiscordPresenceAdapter(
    private val clientId: String = "1198765432109876543",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : PresenceAdapter {

    private val isEnabled = AtomicBoolean(false)
    private var pipeFile: RandomAccessFile? = null
    private var isConnected = false

    override fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        if (!enabled) {
            clearPresence()
            disconnect()
        }
    }

    fun isOptedIn(): Boolean = isEnabled.get()

    override fun updatePresence(
        title: String,
        artist: String,
        isPlaying: Boolean,
        durationMs: Long,
        positionMs: Long
    ) {
        if (!isEnabled.get()) return

        scope.launch {
            try {
                ensureConnected()
                if (!isConnected || pipeFile == null) return@launch

                val startEpochSeconds = if (isPlaying && positionMs > 0L) {
                    (System.currentTimeMillis() - positionMs) / 1000L
                } else null

                val activityJson = buildString {
                    append("{")
                    append("\"cmd\":\"SET_ACTIVITY\",")
                    append("\"args\":{")
                    append("\"pid\":${ProcessHandle.current().pid()},")
                    append("\"activity\":{")
                    append("\"details\":${escapeJson(title)},")
                    append("\"state\":${escapeJson("by $artist")},")
                    if (startEpochSeconds != null) {
                        append("\"timestamps\":{\"start\":$startEpochSeconds},")
                    }
                    append("\"assets\":{\"large_image\":\"riff_logo\",\"large_text\":\"Riff Desktop\"}")
                    append("}")
                    append("},")
                    append("\"nonce\":\"${UUID.randomUUID()}\"")
                    append("}")
                }

                sendFrame(1, activityJson)
            } catch (_: Throwable) {
                // Fail-safe: connection drop or IO error silently resets connection without affecting audio
                disconnect()
            }
        }
    }

    override fun clearPresence() {
        if (!isConnected || pipeFile == null) return
        scope.launch {
            try {
                val clearJson = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":${ProcessHandle.current().pid()},\"activity\":null},\"nonce\":\"${UUID.randomUUID()}\"}"
                sendFrame(1, clearJson)
            } catch (_: Throwable) {
                disconnect()
            }
        }
    }

    private fun ensureConnected() {
        if (isConnected && pipeFile != null) return

        // Probe Discord IPC named pipes 0 through 3
        for (i in 0..3) {
            try {
                val pipe = RandomAccessFile(File("\\\\.\\pipe\\discord-ipc-$i"), "rw")
                pipeFile = pipe
                val handshakeJson = "{\"v\":1,\"client_id\":\"$clientId\"}"
                sendFrame(0, handshakeJson)
                isConnected = true
                return
            } catch (_: Throwable) {
                // Try next pipe index
            }
        }
    }

    private fun sendFrame(opcode: Int, jsonPayload: String) {
        val raf = pipeFile ?: return
        val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(opcode)
        header.putInt(payloadBytes.size)

        synchronized(this) {
            raf.write(header.array())
            raf.write(payloadBytes)
        }
    }

    private fun disconnect() {
        try {
            pipeFile?.close()
        } catch (_: Throwable) {
        } finally {
            pipeFile = null
            isConnected = false
        }
    }

    private fun escapeJson(value: String): String {
        val escaped = value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    override fun close() {
        clearPresence()
        disconnect()
        scope.cancel()
    }
}
