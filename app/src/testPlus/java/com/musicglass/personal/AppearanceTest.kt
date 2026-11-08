package com.musicglass.personal

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Feature 21 (Riff+, Q21 and the owner's tint rules of 8 November). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppearanceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun reset() { context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE).edit().clear().commit(); RiffAccent.ui = RiffPink }

    private fun contrast(a: Color, b: Color): Double { val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending(); return (hi + .05) / (lo + .05) }

    @Test fun everyAccentReadsOnRiffsSurfacesAndUnderInkWords() {
        AccentPreset.entries.forEach { p ->
            val c = Color(p.ui)
            assertTrue("${p.key} on Ink", contrast(c, Ink) >= 4.5); assertTrue("${p.key} on Raised", contrast(c, Raised) >= 4.5)
            assertTrue("Ink words on a ${p.key} button", contrast(Ink, c) >= 4.5)
        }
        assertEquals("Rose is Riff's own pink", RiffPink, Color(AccentPreset.Rose.ui))
    }

    @Test fun theIconColoursAndPartnersAreTheConfirmedOnes() {
        AccentPreset.entries.forEach { p ->
            val id = context.resources.getIdentifier("launcher_tile_${p.key}", "color", context.packageName)
            assertEquals("Generated colour for ${p.key}", Color(p.icon).hashCode(), Color(context.getColor(id)).hashCode())
        }
        val partners = AccentPreset.entries.associate { it.key to it.partnerKey }
        assertEquals(mapOf("rose" to "violet", "violet" to "rose", "blue" to "amber", "teal" to "coral", "green" to "blue", "amber" to "violet",
            "coral" to "teal", "silver" to null), partners)
        assertTrue("Silver's partner is a darker grey", Color(AccentPreset.Silver.partner).luminance() < Color(AccentPreset.Silver.icon).luminance())
    }

    @Test fun twentyFourIconsEachWithItsOwnAliasInTheManifest() {
        assertEquals(24, launcherIcons.size); assertEquals(24, launcherIcons.values.map { it.alias }.toSet().size)
        IconStyle.entries.forEach { s -> AccentPreset.entries.forEach { a -> assertNotNull(launcherIcons["${s.key}_${a.key}"]) } }
        val manifest = File("src/plus/AndroidManifest.xml").readText()
        launcherIcons.values.forEach { assertTrue(it.alias, manifest.contains("android:name=\".${it.alias.substringAfterLast('.')}\"")) }
        assertEquals("Only A/Rose is on before a choice", 1, Regex("android:enabled=\"true\"").findAll(manifest.substringAfter("appearance aliases")).count())
    }

    @Test fun tintIsOnByDefaultAndRoseNeverTints() {
        val first = Appearance.choice(context)
        assertEquals(AppearanceChoice(AccentPreset.Rose, IconStyle.A, tint = true), first)
        assertFalse(first.tints); assertNull(Appearance.widgetAccent(context))
        Appearance.save(context, AppearanceChoice(AccentPreset.Teal, IconStyle.D, tint = true))
        assertEquals(Color(AccentPreset.Teal.ui), Pink); assertEquals(AccentPreset.Teal.ui.toInt(), Appearance.widgetAccent(context))
        Appearance.save(context, AppearanceChoice(AccentPreset.Teal, IconStyle.D, tint = false))
        assertEquals("Off: the app stays pink", RiffPink, Pink); assertNull("and so do the widgets", Appearance.widgetAccent(context))
        assertEquals("The startup logo follows the icon either way", StartupLook(AccentPreset.Teal.icon, partner = AccentPreset.Coral.icon), Appearance.choice(context).startupLook())
        assertEquals(StartupLook(0xFF09090B, tile = AccentPreset.Amber.icon), AppearanceChoice(AccentPreset.Amber, IconStyle.C).startupLook())
    }

    @Test fun switchingLeavesExactlyOneLauncherEntry() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val pm = context.packageManager
        fun on() = launcherIcons.values.filter { alias ->
            when (pm.getComponentEnabledSetting(ComponentName(context, alias.alias))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> alias.alias.endsWith("LauncherARose")
                else -> false
            }
        }.map { it.alias.substringAfterLast('.') }
        assertEquals(listOf("LauncherARose"), on())
        Appearance.applyLauncher(activity, AppearanceChoice(AccentPreset.Blue, IconStyle.D))
        assertEquals(listOf("LauncherDBlue"), on())
        Appearance.applyLauncher(activity, AppearanceChoice(AccentPreset.Blue, IconStyle.D))
        assertEquals("Idempotent", listOf("LauncherDBlue"), on())
        Appearance.applyLauncher(activity, AppearanceChoice())
        assertEquals(listOf("LauncherARose"), on())
    }

    @Test fun riffClearsTheLaunchScreenOnlyWhenRiffPlusSetOne() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val prefs = context.getSharedPreferences(APPEARANCE_PREFS, Context.MODE_PRIVATE)
        clearPlusLaunchScreen(activity); assertFalse(prefs.getBoolean("launch_screen_set", false))
        prefs.edit().putBoolean("launch_screen_set", true).commit()
        clearPlusLaunchScreen(activity); assertFalse(prefs.getBoolean("launch_screen_set", true))
    }
}
