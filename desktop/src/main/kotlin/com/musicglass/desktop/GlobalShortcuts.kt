package com.musicglass.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.ConcurrentHashMap

/**
 * Windows global shortcut adapter using RegisterHotKey / UnregisterHotKey.
 * Handles collision reporting and clean unregistration.
 */
internal class WindowsGlobalShortcuts(
    private val hostHwnd: Pointer? = null
) : GlobalShortcutsAdapter {

    private interface User32 : StdCallLibrary {
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean
    }

    companion object {
        const val MOD_ALT = 0x0001
        const val MOD_CONTROL = 0x0002
        const val MOD_SHIFT = 0x0004
        const val MOD_WIN = 0x0008
        const val MOD_NOREPEAT = 0x4000

        const val VK_MEDIA_NEXT_TRACK = 0xB0
        const val VK_MEDIA_PREV_TRACK = 0xB1
        const val VK_MEDIA_STOP = 0xB2
        const val VK_MEDIA_PLAY_PAUSE = 0xB3
    }

    private val user32: User32? = try {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            Native.load("user32", User32::class.java)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private data class ShortcutEntry(
        val id: Int,
        val name: String,
        val action: () -> Unit
    )

    private val registeredShortcuts = ConcurrentHashMap<Int, ShortcutEntry>()

    override fun registerShortcut(
        id: Int,
        name: String,
        modifiers: Int,
        keyCode: Int,
        action: () -> Unit
    ): ShortcutResult {
        if (user32 == null) {
            // Emulated fallback for non-Windows or tests
            registeredShortcuts[id] = ShortcutEntry(id, name, action)
            return ShortcutResult.Success(id, name)
        }

        val success = user32.RegisterHotKey(hostHwnd, id, modifiers or MOD_NOREPEAT, keyCode)
        return if (success) {
            registeredShortcuts[id] = ShortcutEntry(id, name, action)
            ShortcutResult.Success(id, name)
        } else {
            val err = Native.getLastError()
            ShortcutResult.Conflict(id, name, err)
        }
    }

    override fun unregisterShortcut(id: Int) {
        registeredShortcuts.remove(id)
        user32?.UnregisterHotKey(hostHwnd, id)
    }

    override fun unregisterAll() {
        for (id in registeredShortcuts.keys) {
            user32?.UnregisterHotKey(hostHwnd, id)
        }
        registeredShortcuts.clear()
    }

    /** Dispatches action if an incoming hotkey ID matches. */
    fun dispatch(id: Int): Boolean {
        val entry = registeredShortcuts[id] ?: return false
        entry.action.invoke()
        return true
    }

    fun isRegistered(id: Int): Boolean = registeredShortcuts.containsKey(id)

    override fun close() {
        unregisterAll()
    }
}
