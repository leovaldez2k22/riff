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

    @Test fun oneAccountSavedBeforeAndAfterItsAddressWasKnownIsOneRow() {
        // Q18-085: the dummy account was listed twice, once by its scope (no address) and once by its address.
        val prefs = context.getSharedPreferences("saved_accounts_merge", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val store = SavedAccounts(prefs)
        store.used("storage-key-d", "Dee", null, 1_000)
        store.used("o@example.com", "Owen", "o@example.com", 1_500, scope = "s2_owen")
        store.used("d@example.com", "Dee", "d@example.com", 2_000, scope = "s2_dee", aliases = setOf("storage-key-d"))
        assertEquals(listOf("d@example.com", "o@example.com"), store.all().map { it.key })
        assertEquals("s2_dee", store.all().first().scope)
        // Saved again by its scope alone, it is still the one row, with its address kept.
        store.used("s2_dee", "", null, 3_000, scope = "s2_dee", index = "1")
        assertEquals(2, store.all().size)
        assertEquals(SavedAccount("d@example.com", "Dee", "d@example.com", 3_000, "s2_dee", "1"), store.all().first())
        // A list written before the fix, with the account twice, reads as one row.
        prefs.edit().putString("accounts", """[{"key":"s2_x","name":"Ex","email":"","used":5,"scope":"s2_x"},
            {"key":"x@example.com","name":"Ex","email":"x@example.com","used":4,"scope":"s2_x"}]""").commit()
        assertEquals(listOf(SavedAccount("x@example.com", "Ex", "x@example.com", 5, "s2_x", null)), store.all())
        store.clear()
        assertEquals(emptyList<SavedAccount>(), store.all())
    }

    @Test fun googlesSessionFillsInAddressesAndPositions() {
        // Q18-087, Q18-088: YouTube sends no address for some accounts; Google's own account list does.
        val store = SavedAccounts(context.getSharedPreferences("saved_accounts_learn", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        store.used("s2_owner", "Owner", null, 2_000, scope = "s2_owner")
        store.used("d@example.com", "Dee", "d@example.com", 1_000, scope = "s2_dee", index = "0")
        store.learn(listOf(SessionAccount("d@example.com", "1", "s2_dee", true), SessionAccount("owner@example.com", "0", "s2_owner", true),
            SessionAccount("stranger@example.com", "2", "s2_stranger", true)))
        assertEquals(listOf(SavedAccount("owner@example.com", "Owner", "owner@example.com", 2_000, "s2_owner", "0"),
            SavedAccount("d@example.com", "Dee", "d@example.com", 1_000, "s2_dee", "1")), store.all())
    }

    @Test fun googlesAccountListIsReadDefensively() {
        val body = """)]}'
            ["gaia.l.a.r",[["gaia.l.a",1,"Owner","owner@example.com","https://p/1",1,1,0,null,1,"111222333",null,null,null,0,1],
            ["gaia.l.a",1,"Dee","d@example.com","https://p/2",1,1,0,null,1,"444555666",null,null,null,0,1],
            ["gaia.l.a",1,"Gone","gone@example.com","https://p/3",1,1,0,null,1,"777888999",null,null,null,1,1]]]"""
        assertEquals(listOf(GoogleSessionEntry("owner@example.com", "0", "111222333", true), GoogleSessionEntry("d@example.com", "1", "444555666", true),
            GoogleSessionEntry("gone@example.com", "", "777888999", false)), parseGoogleSessionAccounts(body))
        assertEquals(emptyList<GoogleSessionEntry>(), parseGoogleSessionAccounts("<html>sign in</html>"))
        assertEquals(emptyList<GoogleSessionEntry>(), parseGoogleSessionAccounts("""["gaia.l.a.r"]"""))
        // A field that is not an address or an id is left out, never guessed.
        assertEquals(listOf(GoogleSessionEntry(null, "0", null, true)), parseGoogleSessionAccounts("""["gaia.l.a.r",[["gaia.l.a",1,"N","not an email",null,1,1,0,null,1,"id with spaces"]]]"""))
    }

    @Test fun theAccountsAnswerIsDescribedByCountsAndAReasonOnly() {
        // Q18-100: which way Google's list came back empty is logged, never an address, id or the body's text.
        fun shape(body: String) = googleSessionAccountsShape(body, parseGoogleSessionAccounts(body))
        val body = """)]}'
["gaia.l.a.r",[["gaia.l.a",1,"Owner","owner@example.com","https://p/1",1,1,0,null,1,"111222333"],
            ["gaia.l.a",1,"Gone","gone@example.com","https://p/3",1,1,0,null,1,"777888999",null,null,null,1,1]]]"""
        assertEquals("rows=2 parsed=2 found=1 reason=read", shape(body))
        assertEquals("rows=0 parsed=0 found=0 reason=empty", shape(""))
        assertEquals("rows=0 parsed=0 found=0 reason=not_json_array", shape("<html>owner@example.com</html>"))
        assertEquals("rows=0 parsed=0 found=0 reason=no_rows", shape("""["gaia.l.a.r"]"""))
        assertEquals("rows=1 parsed=0 found=1 reason=no_addresses", shape("""["gaia.l.a.r",[["gaia.l.a",1,"N","not an email"]]]"""))
        val logged = DiagnosticEvent.parse("session_accounts cookie=true status=200 " + shape(body)).text
        assertEquals("session_accounts cookie=true status=200 rows=2 parsed=2 found=1 reason=read", logged)
        assertFalse(logged.contains("example") || logged.contains("111222333"))
    }

    @Test fun aDirectSwitchNeedsAKnownAccountAndLandsOnlyOnIt() {
        // Q18-087: straight to the account only when Riff is signed in and knows its scope and Google position.
        val known = SavedAccount("d@example.com", "Dee", "d@example.com", 1, "s2_dee", "1")
        assertEquals(known, directSwitchAccount(known, signedIn = true))
        assertNull("signed out: Google's session holds nobody", directSwitchAccount(known, signedIn = false))
        assertNull("no position yet: Google's page", directSwitchAccount(known.copy(index = null), signedIn = true))
        assertNull("no scope: nothing to check it against", directSwitchAccount(known.copy(scope = null), signedIn = true))
        assertTrue(directSwitchLanded("s2_dee", AccountScope.Provider("s2_dee")))
        assertFalse("another account at that position commits nothing", directSwitchLanded("s2_dee", AccountScope.Provider("s2_other")))
        assertFalse(directSwitchLanded("s2_dee", AccountScope.Unverified))
        assertFalse(directSwitchLanded(null, AccountScope.Provider("s2_dee")))
    }

    /** Q18-110: a pick that fell back from the direct switch ended silently on the account in use (the owner could not change account). */
    @Test fun anAccountPickedFromTheListIsCommittedOnlyWhenItIsTheOneReached() {
        val owner = AccountScope.Provider("s2_owner"); val dummy = AccountScope.Provider("s2_dee")
        assertTrue("the picked account was reached", pickedAccountReached("s2_dee", "d@example.com", dummy, null))
        assertFalse("the account in use was reached instead: nothing is committed", pickedAccountReached("s2_dee", "d@example.com", owner, null))
        assertFalse("an unverified landing is no match", pickedAccountReached("s2_dee", null, AccountScope.Unverified, null))
        assertFalse("without a scope the page's address decides", pickedAccountReached(null, "d@example.com", owner, "o@example.com"))
        assertTrue(pickedAccountReached(null, "d@example.com", dummy, "D@Example.com"))
        assertTrue("an address the page does not send never blocks", pickedAccountReached(null, "o@example.com", owner, null))
        assertTrue("nothing picked, nothing to match", pickedAccountReached(null, null, owner, null))
    }

    @Test fun theDirectFallbackAndTheMismatchAreLoggedAsWordsAndNumbers() {
        for (event in listOf("account_switch_direct_fallback reason=index_mismatch asked=1 page=0", "account_switch_direct_fallback reason=no_page asked=1", "account_pick_mismatch"))
            assertEquals("kept whole by the diagnostic log", event, DiagnosticEvent.parse(event).text)
    }

    @Test fun lastUseIsInWords() {
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
        compose.setContent { CheckingScreen(ticks = 2, failed = failed, onRetry = { retries++ }, onStartOver = {}) }
        compose.onNodeWithText("Found your YouTube Music").assertExists()
        compose.onNodeWithTag("account_retry").assertDoesNotExist()
        failed = "Could not finish"
        compose.onNodeWithTag("account_status").assertTextContains("Could not finish")
        compose.onNodeWithTag("account_retry").performClick()
        assertEquals(1, retries)
    }
}
