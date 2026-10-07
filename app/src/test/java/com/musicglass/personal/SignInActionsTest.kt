package com.musicglass.personal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restartReplacesTheDisplayedWebViewRatherThanLeavingTheDestroyedBrowserAttached() {
        val generation = mutableIntStateOf(0)
        val browsers = mutableListOf<WebView>()
        compose.setContent {
            val context = LocalContext.current
            val browser = remember(generation.intValue) { WebView(context).also(browsers::add) }
            SignInWebView(browser)
        }
        compose.runOnIdle {
            assertEquals(1, browsers.size)
            assertNotNull(browsers[0].parent)
            generation.intValue++
        }
        compose.runOnIdle {
            assertEquals(2, browsers.size)
            assertNull(browsers[0].parent)
            assertNotNull(browsers[1].parent)
        }
        compose.runOnIdle { generation.intValue++ }
        compose.runOnIdle {
            assertEquals(3, browsers.size)
            assertNull(browsers[1].parent)
            assertNotNull(browsers[2].parent)
        }
    }

    @Test fun browserStartupShowsNativeFeedbackInsteadOfAnEmptyBlackPanel() {
        compose.setContent { SignInBrowserLoading(verifying = false) }
        compose.onNodeWithTag("account_browser_placeholder").assertIsDisplayed()
        compose.onNodeWithText("Opening Google sign-in…").assertIsDisplayed()
    }

    @Test fun accountVerificationHasItsOwnVisibleBrowserPlaceholder() {
        compose.setContent { SignInBrowserLoading(verifying = true) }
        compose.onNodeWithText("Checking your account…").assertIsDisplayed()
    }

    @Test fun aLoadedButStuckGooglePageAlwaysOffersRecoveryAtLargeFont() {
        var continued = 0; var retries = 0; var restarts = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.width(320.dp)) {
                    SignInBrowserActions(false, { continued++ }, { retries++ }, { restarts++ })
                }
            }
        }
        compose.onNodeWithTag("account_finish").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("account_retry").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("account_restart").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, continued); assertEquals(1, retries); assertEquals(1, restarts)
    }

    @Test fun verificationPreventsDuplicateCompletionOrReload() {
        compose.setContent { SignInBrowserActions(true, {}, {}, {}) }
        compose.onNodeWithTag("account_verifying").assertIsDisplayed()
        compose.onNodeWithTag("account_finish").assertDoesNotExist()
        compose.onNodeWithTag("account_retry").assertDoesNotExist()
        compose.onNodeWithTag("account_restart").assertDoesNotExist()
    }
}
