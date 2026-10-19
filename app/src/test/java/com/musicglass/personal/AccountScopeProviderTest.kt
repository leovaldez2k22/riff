package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** The provider's scope wiring with synthetic session fields; no account and no provider request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class AccountScopeProviderTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val sync = "fixture-sync-A"

    private fun runtime(stored: Set<String>) = RuntimeDependencies(
        networkConditions = { NetworkConditions(false, false, false) },
        storageCapacity = { StorageCapacity(0, 1_000_000) },
        batteryPercent = { 15 },
        httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
        clocks = FakeClock(), dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
        providerTransport = { OriginalAudioEngine.create { config { addInterceptor { throw IOException("Unexpected provider HTTP request") } } } },
        resolver = ScriptedResolver(), storedScopeKeys = { stored }, startProviderBackgroundWork = false)

    private fun session(syncId: String?, selector: String, signedIn: Boolean = true) {
        context.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear()
            .putString("sync", syncId).putString("account", selector).commit()
        CookieManager.getInstance().removeAllCookies(null)
        if (signedIn) CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture")
    }

    @Test fun chooserReorderKeepsTheAccountsStorageKey() {
        val legacy = legacyScopeKey(sync, "1")
        session(sync, "1")
        val provider = AppContainer(context, runtime(setOf(legacy))).provider
        try {
            assertTrue(provider.signedIn)
            val owner = providerScope(VerifiedAccountIdentity(sync))
            assertEquals(owner, provider.accountScope())
            assertEquals(legacy, provider.accountScopeKey())
            val before = provider.session.value
            assertEquals(owner, before.scope)
            session(sync, "0")
            assertTrue(provider.refreshAccount())
            assertEquals(legacy, provider.accountScopeKey())
            assertEquals(legacy, provider.storageScopeKey())
            assertEquals(owner, provider.session.value.scope)
            assertTrue(provider.session.value.generation > before.generation)
            assertEquals(before.nonce, provider.session.value.nonce)
        } finally { provider.close() }
    }

    /** Q18-010: another account signed in through the shared cookie store, and the change was not finished. */
    @Test fun cookiesOfAnotherSignInAreNeverUsedUnderTheCommittedAccount() {
        session(sync, "0")
        val provider = AppContainer(context, runtime(emptySet())).provider
        try {
            val owner = providerScope(VerifiedAccountIdentity(sync))
            assertTrue(provider.signedIn); assertFalse(provider.detached)
            // A rotation of other cookies, and the same login cookie written again, change nothing.
            CookieManager.getInstance().setCookie("https://music.youtube.com", "__Secure-3PSIDTS=rotated")
            CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture")
            assertTrue(provider.refreshAccount()); assertFalse(provider.detached)
            // The sign-in browser signed another account in and the user came back without finishing.
            CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture-other-account")
            assertFalse(provider.refreshAccount())
            assertTrue(provider.detached)
            assertEquals(SessionState.Expired, provider.sessionState)
            assertEquals("This account's local data stays its own", owner, provider.accountScope())
            assertTrue(PlaybackEventLog(context).recent().any { it.endsWith(" session_cookies_changed") })
            // Finishing a sign-in commits the cookies that are there now.
            assertTrue(provider.useWebSession(null, sync, "0"))
            assertFalse(provider.detached)
            assertTrue(provider.refreshAccount())
        } finally { provider.close() }
    }
    @Test fun theCookieDigestHoldsNoCookieValue() {
        val digest = cookieIdentityDigest("SAPISID=fixture-secret-value; HSID=x")!!
        assertEquals(64, digest.length); assertFalse("fixture-secret-value" in digest)
        assertEquals(digest, cookieIdentityDigest("HSID=y; SAPISID=fixture-secret-value"))
        assertNull(cookieIdentityDigest("HSID=x")); assertNull(cookieIdentityDigest(null))
    }

    @Test fun signedInWithoutStableIdentifierIsUnverifiedNotGuest() {
        session(null, "0")
        val provider = AppContainer(context, runtime(setOf(GUEST_SCOPE_KEY, legacyScopeKey(sync, "0")))).provider
        try {
            assertTrue(provider.signedIn)
            assertEquals(AccountScope.Unverified, provider.accountScope())
            assertNull(provider.accountScopeKey())
            assertEquals(UNVERIFIED_SCOPE_KEY, provider.storageScopeKey())
            assertEquals(AccountScope.Unverified, provider.session.value.scope)
        } finally { provider.close() }
    }

    @Test fun signedOutIsGuest() {
        session(sync, "0", signedIn = false)
        val provider = AppContainer(context, runtime(emptySet())).provider
        try {
            assertFalse(provider.signedIn)
            assertEquals(AccountScope.Guest, provider.accountScope())
            assertNull(provider.accountScopeKey())
            assertEquals(GUEST_SCOPE_KEY, provider.storageScopeKey())
        } finally { provider.close() }
    }

    @Test fun signOutPublishesGuestWithANewGeneration() {
        session(sync, "0")
        val provider = AppContainer(context, runtime(emptySet())).provider
        try {
            val before = provider.session.value
            provider.signOut()
            assertEquals(AccountScope.Guest, provider.session.value.scope)
            assertTrue(provider.session.value.generation > before.generation)
        } finally { provider.close() }
    }
}
