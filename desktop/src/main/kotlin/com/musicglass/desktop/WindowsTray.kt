package com.musicglass.desktop

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

/**
 * Windows notification-area system tray adapter.
 * Provides quick transport controls, window restoration, and quit actions.
 */
internal class WindowsTrayAdapter : TrayAdapter {

    private var trayIcon: TrayIcon? = null
    private var isInitialized = false
    private var playPauseMenuItem: MenuItem? = null

    override fun isSupported(): Boolean {
        return try {
            !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
        } catch (_: Throwable) {
            false
        }
    }

    override fun initTray(title: String, listener: TrayListener) {
        if (!isSupported()) return
        if (isInitialized) return

        try {
            val systemTray = SystemTray.getSystemTray()
            val image = createRiffTrayImage()
            val popup = PopupMenu()

            val restoreItem = MenuItem("Restore Riff").apply {
                addActionListener { listener.onRestoreWindow() }
            }
            popup.add(restoreItem)
            popup.addSeparator()

            val playPause = MenuItem("Play / Pause").apply {
                addActionListener { listener.onTogglePlayPause() }
            }
            playPauseMenuItem = playPause
            popup.add(playPause)

            val nextItem = MenuItem("Next Track").apply {
                addActionListener { listener.onNext() }
            }
            popup.add(nextItem)

            val prevItem = MenuItem("Previous Track").apply {
                addActionListener { listener.onPrevious() }
            }
            popup.add(prevItem)
            popup.addSeparator()

            val quitItem = MenuItem("Quit").apply {
                addActionListener { listener.onQuit() }
            }
            popup.add(quitItem)

            val icon = TrayIcon(image, title, popup).apply {
                isImageAutoSize = true
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            listener.onRestoreWindow()
                        }
                    }
                })
            }

            systemTray.add(icon)
            this.trayIcon = icon
            this.isInitialized = true
        } catch (e: Throwable) {
            System.err.println("Failed to initialize system tray: ${e.message}")
        }
    }

    override fun updateTooltip(tooltip: String) {
        trayIcon?.toolTip = tooltip.take(127) // Windows tooltip length cap
    }

    override fun updatePlaybackStatus(status: OSPlaybackStatus) {
        val label = when (status) {
            OSPlaybackStatus.PLAYING -> "Pause"
            OSPlaybackStatus.PAUSED, OSPlaybackStatus.STOPPED -> "Play"
        }
        playPauseMenuItem?.label = label
    }

    override fun removeTray() {
        if (trayIcon != null && isSupported()) {
            try {
                SystemTray.getSystemTray().remove(trayIcon)
            } catch (_: Throwable) {
            } finally {
                trayIcon = null
                isInitialized = false
            }
        }
    }

    /** Re-registers the system tray icon when Windows Explorer restarts. */
    fun onExplorerRestarted(title: String, listener: TrayListener) {
        removeTray()
        initTray(title, listener)
    }

    override fun close() {
        removeTray()
    }

    private fun createRiffTrayImage(): Image {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2d = image.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // Draw background circle with glass gradient
            val gradient = RadialGradientPaint(
                16f, 16f, 16f,
                floatArrayOf(0.0f, 0.7f, 1.0f),
                arrayOf(Color(0x00, 0xE5, 0xFF, 240), Color(0x62, 0x00, 0xEE, 220), Color(0x0A, 0x0E, 0x1A, 255))
            )
            g2d.paint = gradient
            g2d.fillOval(2, 2, 28, 28)

            // Draw inner play glyph
            g2d.color = Color.WHITE
            val xPoints = intArrayOf(12, 22, 12)
            val yPoints = intArrayOf(10, 16, 22)
            g2d.fillPolygon(xPoints, yPoints, 3)
        } finally {
            g2d.dispose()
        }
        return image
    }
}
