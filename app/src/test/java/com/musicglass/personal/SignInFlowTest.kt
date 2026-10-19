package com.musicglass.personal

import androidx.core.net.toUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignInFlowTest {
    @Test fun changingAccountsOpensSelectionAndRequiresConfirmationEvenWithCachedGoogleCookies() {
        val start = signInStartUrl(changingAccount = true).toUri()
        assertEquals("accounts.google.com", start.host)
        assertEquals("/AccountChooser", start.path)
        assertEquals("youtube", start.getQueryParameter("service"))
        val handoff = start.getQueryParameter("continue")!!.toUri()
        assertEquals("music.youtube.com", handoff.host)
        assertEquals("/signin", handoff.path)
        assertEquals("true", handoff.getQueryParameter("action_handle_signin"))
        assertEquals("/", handoff.getQueryParameter("next"))
        assertFalse(shouldVerifyFinishedSignIn("https://music.youtube.com/", changingAccount = true))
        // Q18-013: once the owner has been on Google's own page (or picked the account in Riff), it finishes by itself.
        assertTrue(shouldVerifyFinishedSignIn("https://music.youtube.com/", changingAccount = true, choseOnGoogle = true))
    }

    @Test fun aSavedAccountOpensGooglesPageWithThatAccountChosen() {
        val start = signInStartUrl(changingAccount = true, email = "someone@example.com").toUri()
        assertEquals("/ServiceLogin", start.path)
        assertEquals("someone@example.com", start.getQueryParameter("Email"))
        assertEquals("someone@example.com", start.getQueryParameter("login_hint"))
        assertNull(signInStartUrl(changingAccount = false).toUri().getQueryParameter("login_hint"))
    }

    @Test fun googlesOwnAccountPagesAreKnownOnEveryCountryHost() {
        assertTrue(googleAccountsPage("https://accounts.google.com/AccountChooser"))
        assertTrue(googleAccountsPage("https://accounts.google.co.in/signin"))
        assertFalse(googleAccountsPage("https://accounts.google.co.in.attacker.example/"))
        assertFalse(googleAccountsPage("http://accounts.google.com/"))
        assertFalse(googleAccountsPage("https://music.youtube.com/"))
    }

    @Test fun linksElsewhereOpenOutsideRiffOnlyOverHttps() {
        assertTrue(signInOutsideLink("https://policies.example.org/privacy"))
        assertFalse(signInOutsideLink("https://accounts.google.com/"))
        assertFalse(signInOutsideLink("http://policies.example.org/"))
        assertFalse(signInOutsideLink("javascript:alert(1)"))
    }

    @Test fun selectedSecondaryAccountSurvivesTheYouTubeHandoffAndOptionalSetup() {
        assertEquals("1", signInSelectedAccount("https://music.youtube.com/signin?authuser=1"))
        assertEquals("2", signInSelectedAccount("https://www.youtube.com/signin?authuser=2"))
        assertNull(signInSelectedAccount("https://accounts.google.com/AccountChooser?authuser=0"))
        assertNull(signInSelectedAccount("https://music.youtube.com.attacker.example/?authuser=1"))
        assertNull(signInSelectedAccount("https://music.youtube.com/?authuser=wrong"))
        assertEquals("1", signInMusicUrl("1").toUri().getQueryParameter("authuser"))
    }

    @Test fun verificationCannotDefaultToThePrimaryAccountOrAcceptADifferentSelection() {
        assertTrue(signInAccountMatches("1", "1"))
        assertFalse(signInAccountMatches("1", "0"))
        assertFalse(signInAccountMatches("1", null))
        assertFalse(signInAccountMatches(null, "null"))
        assertTrue(signInAccountMatches(null, "0"))
        assertFalse(shouldVerifyFinishedSignIn("https://music.youtube.com/signin?authuser=1", false))
        assertFalse(shouldVerifyFinishedSignIn("https://music.youtube.com/oops", false))
    }

    @Test fun optionalGoogleSetupAppLinksStayInTheBrowserAndCannotLaunchExternalApps() {
        assertEquals("https://myaccount.google.com/", signInWebFallback(
            "intent://myaccount.google.com/#Intent;scheme=https;package=com.google.android.gms;end"))
        assertEquals("https://accounts.google.com/", signInWebFallback(
            "intent://untrusted.example/#Intent;scheme=custom;S.browser_fallback_url=https%3A%2F%2Faccounts.google.com%2F;end"))
        assertNull(signInWebFallback("intent://untrusted.example/#Intent;scheme=https;end"))
        assertNull(signInWebFallback("intent://accounts.google.com/#Intent;scheme=http;end"))
        assertNull(signInWebFallback("javascript:alert(1)"))
    }

    @Test fun ordinarySignInCanCompleteOnlyAfterAnHttpsYouTubeMusicReturn() {
        assertEquals("/ServiceLogin", signInStartUrl(changingAccount = false).toUri().path)
        assertTrue(shouldVerifyFinishedSignIn("https://music.youtube.com/", changingAccount = false))
        assertFalse(shouldVerifyFinishedSignIn("https://accounts.google.com/", changingAccount = false))
        assertFalse(shouldVerifyFinishedSignIn("http://music.youtube.com/", changingAccount = false))
    }

    @Test fun verificationUsesTheSameAccountIdentifierAsTheCommittedProvider() {
        assertEquals("account-a", signInSyncId("account-a||session-part"))
        assertEquals("account-a", signInSyncId("account-a"))
        assertNull(signInSyncId("||session-part"))
        assertNull(signInSyncId("null"))
        assertNull(signInSyncId(null))
    }
    @Test fun abandonedBrowserAndVerificationCallbacksAreRejected() {
        val attempt = SignInAttempt()
        val first = attempt.restart()
        assertTrue(attempt.accepts(first))
        val retry = attempt.restart()
        assertFalse(attempt.accepts(first))
        assertTrue(attempt.accepts(retry))
        attempt.restart() // Leaving the screen also invalidates pending completion.
        assertFalse(attempt.accepts(retry))
    }

    @Test fun googlesPageIsOnlyCalledNotLoadedWhenNothingLoaded() {
        // A slow page that is still working never gets a message (Q18-013 point 3).
        assertFalse(signInNothingLoaded(30_000, pageShown = false, mainFrameFailed = false))
        assertFalse(signInNothingLoaded(600_000, pageShown = true, mainFrameFailed = false))
        assertTrue(signInNothingLoaded(45_000, pageShown = false, mainFrameFailed = false))
        assertTrue(signInNothingLoaded(1_000, pageShown = false, mainFrameFailed = true))
    }

    @Test fun cookieRotationDoesNotMistakeAnUnrelatedCookieForAnAccountChange() {
        val before = "other=1; SAPISID=account-a; __Secure-3PAPISID=secure-a"
        val rotated = "other=2; __Secure-3PAPISID=secure-a; SAPISID=account-a"
        assertEquals(signInCookieIdentity(before), signInCookieIdentity(rotated))
        assertNotEquals(signInCookieIdentity(before), signInCookieIdentity("SAPISID=account-b"))
        assertTrue(signInCookieIdentity("visitor=anonymous").isEmpty())
    }

    @Test fun navigationAllowsOnlyHttpsOnKnownDomainsAndTheirRealSubdomains() {
        assertTrue(allowedSignInUrl("https://accounts.google.com/signin"))
        assertTrue(allowedSignInUrl("https://music.youtube.com/"))
        assertFalse(allowedSignInUrl("https://accounts.google.com.attacker.example/"))
        assertFalse(allowedSignInUrl("https://notgoogle.com/"))
        assertFalse(allowedSignInUrl("http://accounts.google.com/"))
        assertFalse(allowedSignInUrl("file:///sdcard/picture.jpg"))
    }
}
