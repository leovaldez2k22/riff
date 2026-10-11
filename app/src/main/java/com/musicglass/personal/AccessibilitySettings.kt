package com.musicglass.personal

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persistent glass and accessibility preferences. */
internal class AccessibilitySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "accessibility_display", Context.MODE_PRIVATE)

    var hapticFeedback by mutableStateOf(prefs.getBoolean("haptic_feedback",true))
        private set
    fun updateHapticFeedback(enabled:Boolean) {hapticFeedback=enabled;prefs.edit().putBoolean("haptic_feedback",enabled).apply()}

    var solidControls by mutableStateOf(
        runCatching { prefs.getBoolean("solid_controls", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE))
        private set
    var reduceMotion by mutableStateOf(
        runCatching { prefs.getBoolean("reduce_motion", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE))
        private set

    var frostedGlass by mutableStateOf(prefs.getBoolean("frosted_glass",false))
        private set
    var glassIntensity by mutableStateOf(prefs.getFloat("glass_intensity",1f).coerceIn(0f,1.5f))
        private set
    var frostedIntensity by mutableStateOf(prefs.getFloat("frosted_intensity",1f).coerceIn(0f,1.5f))
        private set
    fun updateFrostedIntensity(value:Float) {frostedIntensity=value.coerceIn(0f,1.5f);prefs.edit().putFloat("frosted_intensity",frostedIntensity).apply()}
    fun updateFrostedGlass(enabled:Boolean) {frostedGlass=enabled;prefs.edit().putBoolean("frosted_glass",enabled).apply()}
    fun updateGlassIntensity(value:Float) {glassIntensity=value.coerceIn(0f,1.5f);prefs.edit().putFloat("glass_intensity",glassIntensity).apply()}

    /** Reads the stored values again, after an import wrote them. */
    fun reload() {
        hapticFeedback = prefs.getBoolean("haptic_feedback", true)
        solidControls = runCatching { prefs.getBoolean("solid_controls", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE)
        reduceMotion = runCatching { prefs.getBoolean("reduce_motion", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE)
        frostedGlass = prefs.getBoolean("frosted_glass", false)
        glassIntensity = prefs.getFloat("glass_intensity", 1f).coerceIn(0f, 1.5f)
        frostedIntensity = prefs.getFloat("frosted_intensity", 1f).coerceIn(0f, 1.5f)
    }

    fun updateSolidControls(enabled: Boolean) {
        solidControls = enabled
        prefs.edit().putBoolean("solid_controls", enabled).apply()
    }

    fun updateReduceMotion(enabled: Boolean) {
        reduceMotion = enabled
        prefs.edit().putBoolean("reduce_motion", enabled).apply()
    }
}

internal val LocalAccessibilitySettings = compositionLocalOf<AccessibilitySettings> {
    error("Accessibility settings were not provided")
}
