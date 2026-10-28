package com.musicglass.personal

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeRetiredContent(this)
        AppContainer.get(this).stateReady // Starts on IO; nothing here waits for it.
        AppContainer.get(this).prefetchHome()
        configureArtworkLoader(this)
        requestSmoothRefreshRate()
        // Feature 33: edge to edge with white status icons by default; screens over a light picture turn them dark
        // (StatusBarTone.kt). The deprecated systemUiVisibility forced white everywhere.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT))
        val panel=display
        val uiRate=panel?.let {p->preferredUiRefreshRate(p.supportedModes.filter {it.physicalWidth==p.mode.physicalWidth && it.physicalHeight==p.mode.physicalHeight}.map {it.refreshRate})}?:60f
        setContent { androidx.compose.runtime.CompositionLocalProvider(LocalUiRefreshRate provides uiRate) {StartupLogo(savedInstanceState==null) { RiffApp() }} }
    }

    override fun onResume() {
        super.onResume()
        requestSmoothRefreshRate()
        YouTubeMusicProvider.checkExistingFeedWindow()
        YouTubeMusicProvider.checkExistingSession()
        MoodPhotos.get(this).foreground()
        AppContainer.get(this).checkForUpdatesIfDue()
        AppContainer.get(this).resumeWaitingDownloads()
    }

    override fun onStop() {
        super.onStop()
        // Riff is leaving the screen: the backup on this phone catches up (at most every 10 minutes).
        AppContainer.get(this).backUpSoon()
    }

    override fun onPause() {
        window.attributes = window.attributes.apply { preferredRefreshRate = 0f }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestSmoothRefreshRate()
    }

    private fun requestSmoothRefreshRate() {
        val panel = display ?: return
        val current = panel.mode
        val rates = panel.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.map { it.refreshRate }
        val requested = preferredUiRefreshRate(rates)
        window.attributes = window.attributes.apply { preferredRefreshRate = requested }
    }
}

