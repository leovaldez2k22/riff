package com.musicglass.personal

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeRetiredContent(this)
        configureArtworkLoader(this)
        requestSmoothRefreshRate()
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        val panel=display
        val uiRate=panel?.let {p->preferredUiRefreshRate(p.supportedModes.filter {it.physicalWidth==p.mode.physicalWidth && it.physicalHeight==p.mode.physicalHeight}.map {it.refreshRate})}?:60f
        setContent { androidx.compose.runtime.CompositionLocalProvider(LocalUiRefreshRate provides uiRate) {StartupLogo(savedInstanceState==null) { RiffApp() }} }
    }

    override fun onResume() {
        super.onResume()
        requestSmoothRefreshRate()
        YouTubeMusicProvider.checkExistingFeedWindow()
        MoodPhotos.get(this).foreground()
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

