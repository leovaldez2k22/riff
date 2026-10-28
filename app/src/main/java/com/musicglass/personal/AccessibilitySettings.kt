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

    /** Feature 5: blur the lyric lines not being sung. */
    var lyricsBlur by mutableStateOf(prefs.getBoolean("lyrics_blur", true))
        private set
    fun updateLyricsBlur(enabled: Boolean) { lyricsBlur = enabled; prefs.edit().putBoolean("lyrics_blur", enabled).apply() }

    /** Feature 34.4: the current lyric line above the player's seek bar. */
    var playerLyricLine by mutableStateOf(prefs.getBoolean("player_lyric_line", true))
        private set
    fun updatePlayerLyricLine(enabled: Boolean) { playerLyricLine = enabled; prefs.edit().putBoolean("player_lyric_line", enabled).apply() }

    /** Features 16 and 34.3: the format line over the player's art. */
    var playerStats by mutableStateOf(prefs.getBoolean("player_stats", false))
        private set
    fun updatePlayerStats(enabled: Boolean) { playerStats = enabled; prefs.edit().putBoolean("player_stats", enabled).apply() }

    /** Feature 25: lyrics in their own script, in Latin letters, or both. */
    var lyricsRoman by mutableStateOf(lyricsRomanModeFrom(prefs.getString("lyrics_roman", null)))
        private set
    fun updateLyricsRoman(mode: LyricsRomanMode) { lyricsRoman = mode; prefs.edit().putString("lyrics_roman", mode.key).apply() }

    var motionPace by mutableStateOf(motionPaceFrom(prefs.getString("motion_pace", null)))
        private set
    fun updateMotionPace(pace: MotionPace) { motionPace = pace; prefs.edit().putString("motion_pace", pace.key).apply() }

    var frostedGlass by mutableStateOf(prefs.getBoolean("frosted_glass",FROSTED_GLASS_DEFAULT))
        private set
    var glassIntensity by mutableStateOf(prefs.getFloat("glass_intensity",1f).coerceIn(0f,1.5f))
        private set
    var frostedIntensity by mutableStateOf(prefs.getFloat("frosted_intensity",FROSTED_INTENSITY_DEFAULT).coerceIn(0f,1.5f))
        private set
    fun updateFrostedIntensity(value:Float) {frostedIntensity=value.coerceIn(0f,1.5f);prefs.edit().putFloat("frosted_intensity",frostedIntensity).apply()}
    fun updateFrostedGlass(enabled:Boolean) {frostedGlass=enabled;prefs.edit().putBoolean("frosted_glass",enabled).apply()}
    fun updateGlassIntensity(value:Float) {glassIntensity=value.coerceIn(0f,1.5f);prefs.edit().putFloat("glass_intensity",glassIntensity).apply()}

    /** Reads the stored values again, after an import wrote them. */
    fun reload() {
        hapticFeedback = prefs.getBoolean("haptic_feedback", true)
        solidControls = runCatching { prefs.getBoolean("solid_controls", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE)
        reduceMotion = runCatching { prefs.getBoolean("reduce_motion", BuildConfig.LITE) }.getOrDefault(BuildConfig.LITE)
        motionPace = motionPaceFrom(prefs.getString("motion_pace", null))
        lyricsBlur = prefs.getBoolean("lyrics_blur", true)
        playerLyricLine = prefs.getBoolean("player_lyric_line", true)
        playerStats = prefs.getBoolean("player_stats", false)
        lyricsRoman = lyricsRomanModeFrom(prefs.getString("lyrics_roman", null))
        frostedGlass = prefs.getBoolean("frosted_glass", FROSTED_GLASS_DEFAULT)
        glassIntensity = prefs.getFloat("glass_intensity", 1f).coerceIn(0f, 1.5f)
        frostedIntensity = prefs.getFloat("frosted_intensity", FROSTED_INTENSITY_DEFAULT).coerceIn(0f, 1.5f)
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

/**
 * Q18-049, owner's decision 20 Oct 2026: liquid glass is meant to show what is beneath it, so frosted glass is the
 * default (it blurs the words under the bars) and its frost bends at the rim like liquid glass, at 20 % so the bend
 * shows through the blur. A choice the owner made in Settings is kept; only a phone where it was never set takes these.
 */
internal const val FROSTED_GLASS_DEFAULT = true
internal const val FROSTED_INTENSITY_DEFAULT = .2f
