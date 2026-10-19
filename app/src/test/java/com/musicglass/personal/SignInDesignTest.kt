package com.musicglass.personal

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Q18-013: the agreed sign-in screens (docs/quality/sign-in-design.md) and the saved-accounts list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h740dp-xhdpi")
class SignInDesignTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = 86_400_000L

    private fun accounts(vararg names: String): List<SavedAccount> =
        names.mapIndexed { i, name -> SavedAccount("$name@example.com", name, "$name@example.com", 10 * day - i * day) }

    @Test fun savedAccountsKeepTheMostRecentFirstAndNeverMoreThanTheLimit() {
        val store = SavedAccounts(context.getSharedPreferences("saved_accounts_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        store.used("a@example.com", "Ann", "a@example.com", 1_000)
        store.used("b@example.com", "Ben", "b@example.com", 2_000)
        store.used("a@example.com", "", null, 3_000) // used again: keeps its name and email
        assertEquals(listOf("Ann", "Ben"), store.all().map { it.name })
        assertEquals("a@example.com", store.all().first().email)
        store.remove("b@example.com")
        assertEquals(listOf("a@example.com"), store.all().map { it.key })
        repeat(SAVED_ACCOUNTS_LIMIT + 5) { store.used("k$it", "N$it", null, 10_000L + it) }
        assertEquals(SAVED_ACCOUNTS_LIMIT, store.all().size)
    }

    @Test fun emailsAreShortenedAndLastUseIsInWords() {
        assertEquals("leov…20@gmail.com", shortEmail("leovaldez2k20@gmail.com"))
        assertEquals("ann@example.com", shortEmail("ann@example.com"))
        assertEquals("", shortEmail(null))
        assertEquals("Today", lastUsedLabel(10 * day, 10 * day - 1_000))
        assertEquals("Yesterday", lastUsedLabel(10 * day, 9 * day))
        assertEquals("3 days ago", lastUsedLabel(10 * day, 7 * day))
        assertEquals("2 weeks ago", lastUsedLabel(30 * day, 15 * day))
    }

    @Test fun theAgreedTransitionsHaveTheAgreedLengths() {
        assertEquals(2000, signInTransitionMs(SignInPhase.Welcome, SignInPhase.AllAccounts))
        assertEquals(1900, signInTransitionMs(SignInPhase.AllAccounts, SignInPhase.Welcome))
        assertEquals(2200, signInTransitionMs(SignInPhase.Welcome, SignInPhase.Google))
        assertEquals(2900, signInTransitionMs(SignInPhase.Google, SignInPhase.NoLoad))
        assertEquals(3800, signInTransitionMs(SignInPhase.Google, SignInPhase.Checking))
        assertEquals(5100, signInTransitionMs(SignInPhase.Checking, SignInPhase.Connected))
    }

    @Test fun theRecordSitsInTheBarOnGooglesPageAndInTheMiddleWhileChecking() {
        val bar = recordSpot(SignInPhase.Google, true, 360.dp, 740.dp)
        assertEquals(SIGN_IN_BAR_HEIGHT / 2, bar.y)
        assertTrue(bar.size < 60.dp)
        val checking = recordSpot(SignInPhase.Checking, true, 360.dp, 740.dp)
        assertEquals(180.dp, checking.x)
        assertTrue(checking.size > 150.dp)
    }

    @Test fun welcomeShowsThreeAccountsAndSeeAllWhenThereAreMore() {
        var picked: String? = null; var seeAll = 0; var another = 0
        compose.setContent {
            WelcomeScreen(accounts("Ann", "Ben", "Cat", "Dev", "Eve"), "Ann@example.com", 10 * day, null, {}, { picked = it.name },
                { seeAll++ }, { another++ })
        }
        compose.onNodeWithText("Welcome back").assertExists()
        compose.onNodeWithText("Current").assertExists()
        compose.onNodeWithText("Cat").assertExists()
        compose.onNodeWithText("Dev").assertDoesNotExist()
        compose.onNodeWithText("Ben").performClick()
        assertEquals("Ben", picked)
        compose.onNodeWithTag("signin_see_all").assertTextContains("See all 5 accounts").performClick()
        compose.onNodeWithTag("account_open_google").assertTextContains("Use another Google account").performClick()
        assertEquals(1 to 1, seeAll to another)
    }

    @Test fun aFirstRunWelcomeBringsYourMusicWithoutAnAccountList() {
        compose.setContent { WelcomeScreen(emptyList(), null, 0, null, {}, {}, {}, {}) }
        compose.onNodeWithText("Bring your music").assertExists()
        compose.onNodeWithTag("account_open_google").assertTextContains("Continue with Google")
        compose.onNodeWithTag("signin_see_all").assertDoesNotExist()
    }

    @Test fun theAccountsPageSearchesAndRemovesFromThisPhoneOnly() {
        var list by mutableStateOf(accounts("Ann", "Ben", "Cat", "Dev"))
        compose.setContent { AllAccountsScreen(list, null, 10 * day, {}, {}, { gone -> list = list - gone }, {}) }
        compose.onNodeWithText("4 used on this phone").assertExists()
        compose.onNodeWithTag("signin_search").performTextInput("de")
        compose.onNodeWithText("Ann").assertDoesNotExist()
        compose.onNodeWithText("Dev").assertExists()
        compose.onNodeWithTag("signin_account_menu").performClick()
        compose.onNodeWithTag("signin_account_remove").performClick()
        assertEquals(listOf("Ann", "Ben", "Cat"), list.map { it.name })
    }

    @Test fun googlesPageThatDidNotLoadOffersTryAgainAndStartOverAtLargeFont() {
        var retries = 0; var restarts = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) { GoogleFrame("accounts.google.com", true, false, {}, {}, {}, { retries++ }, { restarts++ }) }
            }
        }
        compose.onNodeWithTag("account_no_load").assertExists()
        compose.onNodeWithTag("account_retry").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("account_restart").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1 to 1, retries to restarts)
    }

    @Test fun aWorkingPageHasNoMessageAndContinueOnlyOnceBackOnYouTubeMusic() {
        var onMusic by mutableStateOf(false); var continued = 0
        compose.setContent { GoogleFrame("accounts.google.co.in", false, onMusic, {}, {}, { continued++ }, {}, {}) }
        compose.onNodeWithTag("account_no_load").assertDoesNotExist()
        compose.onNodeWithText("accounts.google.co.in").assertExists()
        compose.onNodeWithText("Riff never sees your password").assertExists()
        compose.onNodeWithTag("account_finish").assertDoesNotExist()
        onMusic = true
        compose.onNodeWithTag("account_finish").performClick()
        assertEquals(1, continued)
    }

    @Test fun stopSigningInAsksFirstAndKeepsTheAccount() {
        var open by mutableStateOf(true); var stopped = 0
        compose.setContent { StopSheet(open, reduced = true, onKeep = { open = false }, onStop = { stopped++ }) }
        compose.onNodeWithText("Your current account stays exactly as it is.").assertExists()
        compose.onNodeWithTag("account_keep").performClick()
        compose.onNodeWithTag("account_stop_sheet").assertDoesNotExist()
        open = true
        compose.onNodeWithTag("account_stop").performClick()
        assertEquals(1, stopped)
    }

    @Test fun checkingTicksItsStepsAndOffersRecoveryIfItCannotFinish() {
        var failed by mutableStateOf<String?>(null); var retries = 0
        compose.setContent { CheckingScreen(found = true, library = false, failed = failed, onRetry = { retries++ }, onStartOver = {}) }
        compose.onNodeWithText("Found your YouTube Music").assertExists()
        compose.onNodeWithTag("account_retry").assertDoesNotExist()
        failed = "Could not finish"
        compose.onNodeWithTag("account_status").assertTextContains("Could not finish")
        compose.onNodeWithTag("account_retry").performClick()
        assertEquals(1, retries)
    }
}
