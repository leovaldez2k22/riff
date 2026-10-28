package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences

internal const val EQUALIZER_PREFS = "riff_equalizer"

/** Feature 10: the equalizer's saved settings. The playback service follows changes through its prefs listener. */
internal object EqualizerSettings {
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(EQUALIZER_PREFS, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): EqualizerConfig = EqualizerConfig(
        enabled = prefs.getBoolean("enabled", false),
        mode = EqMode.entries.firstOrNull { it.key == prefs.getString("mode", null) } ?: EqMode.Dynamic,
        toneX = prefs.getInt("tone_x", 0), toneY = prefs.getInt("tone_y", 0), focused = prefs.getBoolean("focused", false),
        manual = parseManual(prefs.getString("manual", null)),
        preset = prefs.getString("preset", null) ?: "Flat",
        balance = prefs.getFloat("balance", 0f))

    fun write(prefs: SharedPreferences, config: EqualizerConfig) {
        prefs.edit().putBoolean("enabled", config.enabled).putString("mode", config.mode.key)
            .putInt("tone_x", config.toneX).putInt("tone_y", config.toneY).putBoolean("focused", config.focused)
            .putString("manual", config.manual.joinToString(",")).putString("preset", config.preset)
            .putFloat("balance", config.balance).apply()
    }

    /** Seven gains, comma-separated; anything unreadable is flat. */
    fun parseManual(text: String?): List<Float> {
        val values = text?.split(',')?.map { it.trim().toFloatOrNull() ?: 0f }.orEmpty()
        return List(EqLayout.manualFrequencies.size) { values.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB) }
    }
}

/** Feature 10: what of the equalizer travels in an export, each value checked as it comes back in. */
internal fun equalizerSettingSpecs(): Array<Pair<String, (Any?) -> Any?>> {
    val bool = { v: Any? -> v as? Boolean }
    val tone = { v: Any? -> (v as? Number)?.toDouble()?.takeIf { it == Math.rint(it) && kotlin.math.abs(it) <= EqLayout.TONE_STEPS }?.toInt() }
    return arrayOf("enabled" to bool, "focused" to bool, "tone_x" to tone, "tone_y" to tone,
        "mode" to { v -> (v as? String)?.takeIf { m -> EqMode.entries.any { it.key == m } } },
        "balance" to { v -> (v as? Number)?.toFloat()?.takeIf { it in -1f..1f } },
        "preset" to { v -> (v as? String)?.takeIf { it in EQ_PRESETS.keys || it == "Custom" } },
        "manual" to { v -> (v as? String)?.takeIf { it.length <= 80 }?.let { EqualizerSettings.parseManual(it).joinToString(",") } })
}

/** The preset whose gains [manual] matches, or "Custom". */
internal fun presetFor(manual: List<Float>): String = EQ_PRESETS.entries.firstOrNull { it.value == manual }?.key ?: "Custom"
