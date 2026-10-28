package com.musicglass.personal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/** How often a hot phone is looked for: the thermal forecast changes over tens of seconds. */
private const val THERMAL_CHECK_MS = 10_000L

/**
 * Feature 1, step 10: the glass the phone can afford, from the settings, power saver and the thermal forecast. A
 * phone about to throttle (headroom 1.0) and Android 12 draw the frost; power saver or 0.9 drops the colour fringe.
 */
@Composable internal fun rememberGlassStyle(settings: AccessibilitySettings): GlassStyle {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    var powerSave by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    var headroom by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { powerSave = power?.isPowerSaveMode == true }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(power) {
        while (power != null) {
            // NaN when the phone has no forecast: then nothing is assumed.
            headroom = power.getThermalHeadroom(10).takeUnless { it.isNaN() }
            delay(THERMAL_CHECK_MS)
        }
    }
    val quality = glassQuality(Build.VERSION.SDK_INT, settings.solidControls, powerSave, headroom)
    return remember(settings.glassIntensity, settings.frostedGlass, settings.frostedIntensity, quality) {
        GlassStyle(settings.glassIntensity, settings.frostedGlass, settings.frostedIntensity, quality)
    }
}
