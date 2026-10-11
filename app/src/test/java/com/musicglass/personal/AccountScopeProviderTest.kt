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
