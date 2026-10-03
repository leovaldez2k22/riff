package com.musicglass.personal

import android.os.Debug
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.ref.WeakReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device UI regression suite (plan W7.4), run against the stress build on the phone:
 *
 *   gradle :app:connectedStressAndroidTest -PriffTestBuildType=stress  (or am instrument, see scripts/verify-device.ps1 -UiRegression)
 *
 * It never starts playback. Every phone setting it changes (font size, rotation lock) is read first
 * and restored in @After; scripts/stress/device-state.ps1 -Action Restore is the backstop.
 */
@RunWith(AndroidJUnit4::class)
class UiRegressionInstrumentation {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val tabs = listOf("home", "podcasts", "radio", "library", "search")
    private val saved = mutableMapOf<String, String>()

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }.trim()
    }
    private fun systemSetting(name: String): String = shell("settings get system $name")

    /**
     * Home's skeleton, mood backdrop and player run infinite animations, so Compose is never idle and
     * the rule's automatic waits would time out. The test drives the clock itself instead.
     */
    private fun settle(ms: Long = 600) { compose.mainClock.advanceTimeBy(ms); Thread.sleep(ms / 3) }
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
            settle(100)
        }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Before fun rememberSettings() {
        for (name in listOf("font_scale", "accelerometer_rotation", "user_rotation")) saved[name] = systemSetting(name)
        compose.mainClock.autoAdvance = false
        waitFor(20_000) { exists("tab_home") }
    }

    @After fun restoreSettings() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // UiAutomation.setRotation(UNFREEZE) would switch auto-rotate on; restore the saved values instead.
        uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        for ((name, value) in saved) if (value.isNotEmpty() && value != "null") shell("settings put system $name $value")
    }

    private fun openTab(name: String) {
        compose.onNodeWithTag("tab_$name").performClick()
        settle()
    }

    // 1. Navigation: every tab opens and the shell stays intact.
    @Test fun everyTabOpens() {
        for (tab in tabs) {
            openTab(tab)
            compose.onNodeWithTag("tab_$tab").assert(hasContentDescription("selected", substring = true))
            compose.onNodeWithTag("navigation").assertIsDisplayed()
        }
        if (exists("search_input"))
            compose.onNodeWithTag("search_input").assertIsDisplayed()
        openTab("home")
    }

    // 2. Player expansion: mini player -> full player -> back, with the title kept (needs a restored queue).
    @Test fun miniPlayerOpensAndClosesTheFullPlayer() {
        assumeTrue("no restored queue on this phone", exists("mini_player"))
        compose.onNode(hasContentDescription("Open player:", substring = true)).performClick()
        waitFor(5_000) { exists("player_close") }
        compose.onNodeWithTag("playback_toggle").assertIsDisplayed()
        compose.onNodeWithTag("player_close").performClick()
        waitFor(5_000) { !exists("player_close") }
        compose.onNodeWithTag("mini_player").assertIsDisplayed()
    }

    // 3. Rotation: handled in place (configChanges), so Search text and the selected tab survive.
    @Test fun rotationKeepsSearchTextAndTab() {
        openTab("search")
        compose.onNodeWithTag("search_input").performTextClearance()
        compose.onNodeWithTag("search_input").performTextInput("lofi")
        val activity = WeakReference(compose.activity)
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (rotation in listOf(android.app.UiAutomation.ROTATION_FREEZE_90, android.app.UiAutomation.ROTATION_FREEZE_0)) {
            uiAutomation.setRotation(rotation)
            Thread.sleep(1_500); settle()
            compose.onNodeWithTag("search_input").assert(androidx.compose.ui.test.hasText("lofi", substring = true))
            compose.onNodeWithTag("tab_search").assert(hasContentDescription("selected", substring = true))
        }
        assertTrue("activity was recreated by rotation", activity.get() === compose.activity)
        assertEquals(Surface.ROTATION_0, compose.activity.display.rotation)
        compose.onNodeWithTag("search_input").performTextClearance()
        openTab("home")
    }

    // 4. Font size: the shell and the mini player stay usable at 1.3x.
    @Test fun largerFontKeepsTheShellUsable() {
        shell("settings put system font_scale 1.3")
        Thread.sleep(1_500); settle()
        for (tab in tabs) { openTab(tab); compose.onNodeWithTag("tab_$tab").assertIsDisplayed() }
        if (exists("mini_player"))
            compose.onNodeWithTag("mini_play").assertIsDisplayed()
        openTab("home")
    }

    // 6. Leak check (nav_leak equivalent): 50 tab loops grow the Java heap by less than 8 MB.
    @Test fun tabLoopsDoNotLeak() {
        fun heapMb(): Double {
            repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(200) }
            val info = Debug.MemoryInfo(); Debug.getMemoryInfo(info)
            return info.getMemoryStat("summary.java-heap").toDouble() / 1024
        }
        repeat(2) { tabs.forEach(::openTab) } // warm-up: first visits fill the page caches
        val before = heapMb()
        repeat(50) { tabs.forEach(::openTab) }
        openTab("home")
        val after = heapMb()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("heap_mb", "before=%.1f after=%.1f".format(before, after)) })
        assertTrue("Java heap grew %.1f MB (%.1f -> %.1f)".format(after - before, before, after), after - before < 8.0)
    }
}
