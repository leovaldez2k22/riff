package com.musicglass.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM Compose tests for components added in W3, W5 and W6 (plan W7.2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComponentUiTest {
    @get:Rule val compose = createComposeRule()

    // ---- W5 session banner
    @Test fun sessionBannerOffersSignInAgainAndDismiss() {
        var signIns = 0; var dismissals = 0
        compose.setContent { SessionExpiredBanner(onSignIn = { signIns++ }, onDismiss = { dismissals++ }) }
        compose.onNodeWithText("Session expired").assertIsDisplayed()
        compose.onNodeWithTag("session_sign_in_again").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, signIns); assertEquals(1, dismissals)
    }

    @Test fun sessionBannerIsAnnouncedPolitely() {
        compose.setContent { SessionExpiredBanner(onSignIn = {}, onDismiss = {}) }
        compose.onNodeWithTag("session_expired_banner").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Polite))
    }

    @Test fun sessionBannerKeepsItsActionsUsableAtDoubleFontOnANarrowPhone() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) { SessionExpiredBanner(onSignIn = {}, onDismiss = {}) }
            }
        }
        // The title may ellipsize; the actions must stay on screen and keep a 48 dp touch target.
        // (Material 3 draws icon buttons at 40 dp and extends their touch bounds to 48 dp.)
        compose.onNodeWithTag("session_sign_in_again").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val dismiss = compose.onNodeWithContentDescription("Dismiss").assertIsDisplayed().fetchSemanticsNode()
        val touch = dismiss.touchBoundsInRoot
        val dp = dismiss.layoutInfo.density.density
        assertTrue("dismiss touch target ${touch.width / dp} x ${touch.height / dp} dp", touch.width / dp >= 47.5f && touch.height / dp >= 47.5f)
    }

    // ---- W3 skeleton
    @Test fun skeletonIsOneAccessibleLoadingNode() {
        compose.setContent { HomeFeedSkeleton() }
        compose.onNodeWithTag("home_skeleton").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading Home").assertIsDisplayed()
    }

    @Test fun skeletonIsStaticUnderReducedMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduced = true, scale = 0f)) { HomeFeedSkeleton() }
        }
        compose.mainClock.advanceTimeBy(2_000)
        // With no infinite transition running the clock can go idle; an animated skeleton never would.
        compose.waitForIdle()
        compose.onNodeWithTag("home_skeleton").assertIsDisplayed()
    }

    // ---- W6 sleep countdown
    @Test fun sleepCountdownShowsTheRemainingTime() {
        val deadline = SystemClock.elapsedRealtime() + 125_000
        compose.setContent { SleepTimerCountdown(deadline = deadline, onClick = {}) }
        compose.onNodeWithTag("player_sleep_countdown").assertIsDisplayed()
        compose.onNodeWithText("Sleep in 2:05").assertIsDisplayed()
    }
}
