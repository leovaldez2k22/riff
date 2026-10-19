package com.musicglass.personal

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

/**
 * Q19C3: the sign-in screens on an emulator with no account and the network off. Saved accounts are seeded with
 * made-up names on example.test, so the pictures kept hold no personal data. Google's page cannot load here, which
 * is the "didn't load" case; a page that loads (and the stop sheet over it) is checked on the phone in Q19B.
 */
class SignInScreensDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<FeatureTestActivity>()
    private val context: Context get() = compose.activity
    private val prefs get() = context.getSharedPreferences("saved_accounts", Context.MODE_PRIVATE)
    private var before: String? = null
    private var provider: YouTubeMusicProvider? = null

    @After fun restore() {
        prefs.edit().apply { if (before == null) remove("accounts") else putString("accounts", before) }.commit()
    }

    /** The screen only passes the gateway along; any call would be a defect in this test. */
    private val gateway = java.lang.reflect.Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader,
        arrayOf(PlaybackGateway::class.java)) { self, method, args ->
        when (method.name) {
            "equals" -> self === args?.get(0); "hashCode" -> System.identityHashCode(self); "toString" -> "no playback"
            else -> throw AssertionError("Unexpected playback call ${method.name}")
        }
    } as PlaybackGateway

    private fun seed(count: Int) {
        before = prefs.getString("accounts", null)
        prefs.edit().remove("accounts").commit()
        val names = listOf("Fixture One", "Fixture Two", "Fixture Three", "Fixture Four", "Fixture Five")
        val saved = SavedAccounts(context)
        val now = System.currentTimeMillis()
        names.take(count).forEachIndexed { i, name ->
            saved.used("fixture-${i + 1}", name, "fixture.${i + 1}@example.test", now - i * 86_400_000L * (i + 1))
        }
    }

    private fun show(reduced: Boolean = false) {
        val shared = YouTubeMusicProvider.shared(context).also { provider = it }
        assumeFalse("This emulator's debug app is signed in; C3 runs with no account", shared.signedIn)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilitySettings provides AccessibilitySettings(context),
                LocalMotionPolicy provides MotionPolicy(reduced, if (reduced) 0f else 1f)) {
                RiffTheme {
                    AccountScreen(ShellModel(), shared, LocalCollections(context), gateway,
                        {}, SessionCoordinator({ shared.session.value }))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = context.getString(id)
    private fun shows(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(timeoutMs: Long = 10_000, what: () -> Boolean) = compose.waitUntil(timeoutMs) { what() }
    private val accountRow = SemanticsMatcher("a saved account row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.matches(Regex("signin_account_\\d+")) == true
    }
    private fun rows() = compose.onAllNodes(accountRow, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun keep(name: String) {
        compose.waitForIdle(); Thread.sleep(1_500); compose.waitForIdle()
        // The whole screen, so a menu's popup and Google's frame are in the picture too.
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        context.getExternalFilesDir(null)?.resolve("q19c3-$name.png")?.outputStream()?.use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("RiffQ19C3", "kept q19c3-$name.png")
    }

    @Test fun aFirstRunSaysBringYourMusic() {
        seed(0)
        show()
        assertTrue(shows(text(R.string.signin_bring_music)))
        assertEquals(0, rows())
        assertFalse(has("signin_see_all"))
        keep("1-welcome-first-run")
    }

    @Test fun welcomeShowsTheThreeLatestAndAllAccountsSearchesAndRemoves() {
        seed(5)
        show()
        assertTrue(shows(text(R.string.signin_welcome_back)))
        assertEquals(3, rows())
        assertTrue(shows("Fixture One")); assertFalse(shows("Fixture Four"))
        keep("1-welcome-saved")

        compose.onNodeWithTag("signin_see_all").performClick()
        waitFor { shows(text(R.string.signin_accounts)) && !shows(text(R.string.signin_welcome_back)) }
        assertEquals(5, rows())
        keep("1b-all-accounts")

        compose.onNodeWithTag("signin_search").performTextInput("three")
        waitFor { rows() == 1 }
        assertTrue(shows("Fixture Three"))
        keep("1b-all-accounts-search")
        compose.onNodeWithTag("signin_search").performTextClearance()
        // From API 35 the open keyboard shrinks the list, and a lazy list holds only the rows on screen.
        compose.runOnUiThread {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        waitFor { rows() == 5 }

        // "Remove from this phone" on the latest: the list and the stored list both lose it, and only it.
        compose.onAllNodesWithTag("signin_account_menu", useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        keep("1b-remove-menu")
        compose.onNodeWithTag("signin_account_remove", useUnmergedTree = true).performClick()
        waitFor { rows() == 4 }
        assertFalse(shows("Fixture One"))
        assertEquals(listOf("fixture-2", "fixture-3", "fixture-4", "fixture-5"), SavedAccounts(context).all().map { it.key })

        back()
        waitFor { shows(text(R.string.signin_welcome_back)) && !shows(text(R.string.signin_accounts)) }
        assertEquals(3, rows())
    }

    @Test fun googlesPageOpensInTheFrameAndSaysItDidNotLoadWithTheNetworkOff() {
        seed(2)
        show()
        compose.onNodeWithTag("account_open_google").performClick()
        waitFor { has("account_browser") && shows(text(R.string.signin_with_google)) }
        // The frame asks for the sign-in hand-off on Google's own hosts and nothing else.
        val asked = arrayOfNulls<String>(1)
        waitFor { compose.runOnUiThread { asked[0] = compose.activity.window.decorView.findWebViewUrl() }; asked[0] != null }
        assertTrue("The frame asked for ${asked[0]}", allowedSignInUrl(asked[0]!!))
        waitFor(SIGN_IN_TIMEOUT_MS + 10_000) { has("account_no_load") }
        assertTrue(shows(text(R.string.signin_didnt_load)))
        assertTrue(has("account_retry")); assertTrue(has("account_restart"))
        keep("4-didnt-load")

        // Back from "didn't load" goes to Welcome; the browser is gone.
        back()
        waitFor { shows(text(R.string.signin_welcome_back)) && !has("account_browser") }
        keep("1-welcome-after-back")

        // Start over does the same from the card.
        compose.onNodeWithTag("account_open_google").performClick()
        waitFor(SIGN_IN_TIMEOUT_MS + 10_000) { has("account_no_load") }
        compose.onNodeWithTag("account_restart").performClick()
        waitFor { shows(text(R.string.signin_welcome_back)) && !has("account_browser") }
    }

    @Test fun backWalksEveryStepToTheStart() {
        seed(5)
        show()
        compose.onNodeWithTag("signin_see_all").performClick()
        waitFor { shows(text(R.string.signin_accounts)) }
        back()
        waitFor { shows(text(R.string.signin_welcome_back)) && !shows(text(R.string.signin_accounts)) }
        compose.onNodeWithTag("signin_see_all").performClick()
        waitFor { shows(text(R.string.signin_accounts)) }
        compose.onNodeWithTag("signin_back").performClick()
        waitFor { shows(text(R.string.signin_welcome_back)) && !shows(text(R.string.signin_accounts)) }
        compose.onNodeWithTag("account_open_google").performClick()
        waitFor(SIGN_IN_TIMEOUT_MS + 10_000) { has("account_no_load") }
        back()
        waitFor { shows(text(R.string.signin_welcome_back)) && !has("account_browser") }
    }

    /** With Remove animations on, a screen change is a cut: a few frames later only the new screen is there. */
    @Test fun withReducedMotionEveryTransitionIsACut() {
        seed(5)
        show(reduced = true)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("signin_see_all").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(shows(text(R.string.signin_accounts)))
        assertFalse(shows(text(R.string.signin_welcome_back)))
        compose.onNodeWithTag("signin_back").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(shows(text(R.string.signin_welcome_back)))
        assertFalse(shows(text(R.string.signin_accounts)))
        compose.mainClock.autoAdvance = true
        keep("reduced-welcome")
    }

    /** The same change with full motion is a crossfade, so the check above is not passing by accident. */
    @Test fun withFullMotionTheSameTransitionOverlaps() {
        seed(5)
        show(reduced = false)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("signin_see_all").performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        assertTrue(shows(text(R.string.signin_accounts)))
        assertTrue(shows(text(R.string.signin_welcome_back)))
        compose.mainClock.autoAdvance = true
    }
}

private fun android.view.View.findWebViewUrl(): String? = when (this) {
    is android.webkit.WebView -> url
    is android.view.ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findWebViewUrl() }
    else -> null
}
