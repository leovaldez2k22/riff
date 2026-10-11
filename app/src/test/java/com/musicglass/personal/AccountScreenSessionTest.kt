package com.musicglass.personal

import android.content.Context
import android.webkit.CookieManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy

/** The Account screen drives sign-out through the coordinator; synthetic session fields, no account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccountScreenSessionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun runtime() = RuntimeDependencies(
        networkConditions = { NetworkConditions(false, false, false) },
        storageCapacity = { StorageCapacity(0, 1_000_000) },
        batteryPercent = { 15 },
        httpCalls = OkHttpClient.Builder().addInterceptor { throw IOException("Unexpected outbound fixture request") }.build(),
        clocks = FakeClock(), dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
        providerTransport = { OriginalAudioEngine.create { config { addInterceptor { throw IOException("Unexpected provider HTTP request") } } } },
        resolver = ScriptedResolver(), storedScopeKeys = { emptySet() }, startProviderBackgroundWork = false)

    private fun provider(syncId: String?): YouTubeMusicProvider {
        context.getSharedPreferences("provider_session", Context.MODE_PRIVATE).edit().clear()
            .putString("sync", syncId).putString("account", "0").commit()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setCookie("https://music.youtube.com", "SAPISID=fixture")
        return AppContainer(context, runtime()).provider
    }

    /** The screen only passes the gateway along; any call would be a defect in this test. */
    private val gateway = Proxy.newProxyInstance(PlaybackGateway::class.java.classLoader, arrayOf(PlaybackGateway::class.java)) { _, method, _ ->
        throw AssertionError("Unexpected playback call ${method.name}")
    } as PlaybackGateway

    private fun show(provider: YouTubeMusicProvider, sessions: SessionCoordinator, notices: MutableList<String>) {
        compose.setContent {
            AccountScreen(ShellModel(), provider, LocalCollections(context), gateway, { notices += it }, sessions)
        }
    }

    @Test fun unverifiedAccountSaysSoAndOffersToVerify() {
        val provider = provider(null)
        try {
            show(provider, SessionCoordinator({ provider.session.value }), mutableListOf())
            compose.onNodeWithTag("account_unverified").assertExists()
            compose.onNodeWithTag("account_verify").assertExists()
        } finally { provider.close() }
    }

    @Test fun verifiedAccountShowsNoVerificationPrompt() {
        val provider = provider("fixture-sync-A")
        try {
            show(provider, SessionCoordinator({ provider.session.value }), mutableListOf())
            compose.onNodeWithTag("account_sign_out").assertExists()
            compose.onNodeWithTag("account_unverified").assertDoesNotExist()
            compose.onNodeWithTag("account_verify").assertDoesNotExist()
        } finally { provider.close() }
    }

    @Test fun signOutQuiescesTheAccountThenBindsGuest() {
        val provider = provider("fixture-sync-A")
        try {
            val log = mutableListOf<String>(); val notices = mutableListOf<String>()
            val sessions = SessionCoordinator({ provider.session.value })
            sessions.register(object : SessionParticipant {
                override suspend fun quiesce(outgoing: SessionToken) { log += "quiesce signedIn=${provider.signedIn}" }
                override suspend fun bind(current: SessionToken) { log += "bind ${current.scope} signedIn=${provider.signedIn}" }
            })
            show(provider, sessions, notices)
            compose.onNodeWithTag("account_sign_out").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(listOf("quiesce signedIn=true", "bind ${AccountScope.Guest} signedIn=false"), log)
            assertFalse(provider.signedIn)
            compose.onNodeWithTag("account_status").assertTextContains("Signed out", substring = true)
            assertTrue(notices.isEmpty())
        } finally { provider.close() }
    }

    @Test fun signOutThatCannotSaveStateKeepsTheAccountAndSaysSo() {
        val provider = provider("fixture-sync-A")
        try {
            val notices = mutableListOf<String>()
            val sessions = SessionCoordinator({ provider.session.value })
            sessions.register(object : SessionParticipant {
                override suspend fun quiesce(outgoing: SessionToken) { throw IllegalStateException("Could not save the queue") }
            })
            show(provider, sessions, notices)
            compose.onNodeWithTag("account_sign_out").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertTrue(provider.signedIn)
            assertEquals(listOf("Could not sign out safely. Try again."), notices)
            compose.onNodeWithTag("account_sign_out").assertExists()
        } finally { provider.close() }
    }
}
