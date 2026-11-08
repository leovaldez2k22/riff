package com.musicglass.personal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.Color

/*
 * Feature 21 (Riff+, owner Q21, 7–8 November): eight accent colours and three icon styles. The icon (launcher entry,
 * Android's launch screen and Riff's startup logo) always follows the choice. "Tint the app to match the icon", on by
 * default, also gives the app's actions, switches and selections and the widgets the accent; off, they stay Riff's
 * pink. Artwork-derived backgrounds never change. With Rose the two are the same, so the switch is shown but greyed.
 */

/** [ui]: the app's accent (readable on Riff's dark surfaces); [icon]: the launcher colour; [partner]: style D's crossing pair. */
internal enum class AccentPreset(val key: String, val ui: Long, val icon: Long, val partnerKey: String?, val partnerIcon: Long? = null) {
    Rose("rose", 0xFFFF5EAC, 0xFFF45B9A, "violet"),
    Violet("violet", 0xFFA98BFF, 0xFF8B6CF2, "rose"),
    Blue("blue", 0xFF64AEFF, 0xFF3E8EF0, "amber"),
    Teal("teal", 0xFF45D8C8, 0xFF22B8A8, "coral"),
    Green("green", 0xFF72DD82, 0xFF4CC060, "blue"),
    Amber("amber", 0xFFFFC457, 0xFFF2A93B, "violet"),
    Coral("coral", 0xFFFF8070, 0xFFF2664F, "teal"),
    Silver("silver", 0xFFCDD1DA, 0xFFB4B9C4, null, partnerIcon = 0xFF6E7380);

    /** The colour of style D's crossing pair. */
    val partner: Long get() = partnerIcon ?: entries.first { it.key == partnerKey }.icon
    val label: Int get() = when (this) {
        Rose -> R.string.accent_rose; Violet -> R.string.accent_violet; Blue -> R.string.accent_blue; Teal -> R.string.accent_teal
        Green -> R.string.accent_green; Amber -> R.string.accent_amber; Coral -> R.string.accent_coral; Silver -> R.string.accent_silver
    }
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Rose }
}

/** A: the ribbons with a plus badge. C: a coloured tile with dark ribbons. D: two ribbon pairs crossed into a plus. */
internal enum class IconStyle(val key: String, val label: Int) {
    A("a", R.string.icon_style_a), C("c", R.string.icon_style_c), D("d", R.string.icon_style_d);
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: A }
}

/** One launcher icon: its alias (component class), its picture and its launch-screen theme. */
internal data class LauncherIcon(val alias: String, val mipmap: Int, val splashTheme: Int)

internal data class AppearanceChoice(val accent: AccentPreset = AccentPreset.Rose, val style: IconStyle = IconStyle.A, val tint: Boolean = true) {
    val iconKey get() = "${style.key}_${accent.key}"
    /** The app's accent: the preset's when tinting (Rose is Riff's pink either way). */
    val appAccent: Long get() = if (tint) accent.ui else AccentPreset.Rose.ui
    val tints get() = tint && accent != AccentPreset.Rose
    fun startupLook() = when (style) {
        IconStyle.A -> StartupLook(accent.icon)
        IconStyle.C -> StartupLook(0xFF09090B, tile = accent.icon)
        IconStyle.D -> StartupLook(accent.icon, partner = accent.partner)
    }
}

internal object Appearance {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)

    fun choice(context: Context): AppearanceChoice = prefs(context).let {
        AppearanceChoice(AccentPreset.of(it.getString("accent", null)), IconStyle.of(it.getString("style", null)), it.getBoolean("tint", true))
    }

    /** Applies the accent at once; the launcher icon and launch screen are switched by [applyLauncher] from an Activity. */
    fun save(context: Context, choice: AppearanceChoice) {
        prefs(context).edit().putString("accent", choice.accent.key).putString("style", choice.style.key).putBoolean("tint", choice.tint).apply()
        RiffAccent.ui = Color(choice.appAccent)
        RiffWidgets.refresh(context); EncoreWidgets.refresh(context)
    }

    /**
     * Enables the chosen launcher alias, then disables every other one (so there is never no icon), and sets Android's
     * launch screen for the next cold start. Also run at each start, which repairs a switch interrupted half way.
     */
    fun applyLauncher(activity: Activity, choice: AppearanceChoice = choice(activity)) {
        val pm = activity.packageManager
        val wanted = launcherIcons[choice.iconKey] ?: launcherIcons.getValue("a_rose")
        fun state(alias: String) = runCatching { pm.getComponentEnabledSetting(ComponentName(activity, alias)) }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        val default = launcherIcons.getValue("a_rose").alias
        fun enabled(alias: String) = when (state(alias)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias == default
            else -> false
        }
        val changes = launcherIcons.values.map { it.alias }.mapNotNull { alias ->
            val on = alias == wanted.alias
            if (enabled(alias) == on) null else alias to on
        }.sortedByDescending { it.second }
        if (changes.isNotEmpty()) runCatching {
            if (Build.VERSION.SDK_INT >= 33) pm.setComponentEnabledSettings(changes.map { (alias, on) ->
                PackageManager.ComponentEnabledSetting(ComponentName(activity, alias),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) })
            else changes.forEach { (alias, on) -> pm.setComponentEnabledSetting(ComponentName(activity, alias),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
        }
        val p = prefs(activity)
        val themeKey = choice.iconKey
        if (p.getString("launch_screen", null) != themeKey) runCatching {
            activity.splashScreen.setSplashScreenTheme(wanted.splashTheme)
            p.edit().putString("launch_screen", themeKey).putBoolean("launch_screen_set", true).apply()
        }
    }

    fun onActivityCreate(activity: Activity) {
        val choice = choice(activity)
        RiffAccent.ui = Color(choice.appAccent)
        applyLauncher(activity, choice)
    }

    fun widgetAccent(context: Context): Int? = choice(context).takeIf { it.tints }?.accent?.ui?.toInt()
}
