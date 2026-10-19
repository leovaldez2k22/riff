package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Plan 2 S02 (Q19F5): what the player-config store sends and keeps, on Riff's own provider HTTP client while signed in
 * (synthetic cookie, visitor and account values; nothing leaves the process). PC01 and PC02 of the supplement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayerConfigBoundaryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var world: ProviderWorld? = null
    @After fun close() { world?.close() }

    private val player = "https://www.youtube.com/s/player/0123abcd/player_ias.vflset/en_US/base.js"
    private val table = """{"schemaVersion":1,"players":{"0123abcd":{"sig":"Xy(1,2,INPUT)","nClass":"Ab","sts":20123}}}"""

    /** A repository like Riff's, pointed at [url], kept in memory. */
    private class Repository(override val sourceUrl: String) : PlayerConfigRepository {
        override val enabled = true
        override val defaultSourceUrl = sourceUrl
        override var cachedJson = ""
        override var cachedAtMs = 0L
        override var cachedSourceUrl = ""
        override var cachedEtag = ""
    }

    private fun signedInClient(reply: (ProviderWorld.Call) -> ProviderWorld.Reply): ProviderSessionManager {
        val w = ProviderWorld(context, signedIn = true).also { world = it; it.route = reply }
        return ProviderSessionManager(context, w.runtime)
    }

    @Test fun riffsConfiguredAddressIsRefusedByThePinnedLibrarySoNothingIsFetched() = runBlocking {
        val session = signedInClient { error("no request expected: ${it.host}${it.endpoint}") }
        val store = RemotePlayerConfigStore(session.http, AppPlayerConfigRepository(context, "player_config_boundary_test"))
        assertNull(store.getSignatureTimestamp(player))
        assertEquals(emptyList<ProviderWorld.Call>(), world!!.calls)
    }

    @Test fun aConfigRequestCarriesNoCookieAuthorisationVisitorOrAccount() = runBlocking {
        val session = signedInClient { ProviderWorld.Reply(table) }
        val repository = Repository("https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json")
        assertEquals(20123, RemotePlayerConfigStore(session.http, repository).getSignatureTimestamp(player))
        val call = world!!.calls.single()
        assertEquals("raw.githubusercontent.com", call.host)
        val names = call.headers.map { it.first.lowercase() }
        for (secret in listOf("cookie", "authorization", "x-goog-visitor-id", "x-goog-authuser", "x-goog-pageid", "x-origin"))
            assertFalse("$secret sent: $names", secret in names)
        val everything = call.headers.joinToString { "${it.first}=${it.second}" } + call.endpoint + call.query
        for (value in listOf("SAPISID", "fixture", "fixture-visitor", "fixture-sync-A")) assertFalse("$value sent", value in everything)
    }

    @Test fun aRedirectIsNotFollowedAndTheLastGoodTableIsKept() = runBlocking {
        val repository = Repository("https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json")
        val session = signedInClient { ProviderWorld.Reply("", status = 302, headers = mapOf("Location" to "https://evil.example/player_configs.json")) }
        repository.cachedJson = table; repository.cachedSourceUrl = repository.sourceUrl; repository.cachedAtMs = 0
        assertEquals(20123, RemotePlayerConfigStore(session.http, repository).getSignatureTimestamp(player))
        assertEquals(listOf("raw.githubusercontent.com"), world!!.calls.map { it.host })
        assertEquals(table, repository.cachedJson)
    }

    @Test fun aMalformedOrOversizedTableIsRejectedAndTheLastGoodTableIsKept() = runBlocking {
        for (body in listOf("{not json", """{"schemaVersion":9,"players":{}}""", "x".repeat(4 * 1024 * 1024 + 1))) {
            val repository = Repository("https://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json")
            repository.cachedJson = table; repository.cachedSourceUrl = repository.sourceUrl; repository.cachedAtMs = 0
            val session = signedInClient { ProviderWorld.Reply(body) }
            assertEquals(body.take(20), 20123, RemotePlayerConfigStore(session.http, repository).getSignatureTimestamp(player))
            assertEquals(body.take(20), table, repository.cachedJson)
            world?.close(); world = null
        }
    }

    @Test fun aTableFromAnotherHostIsNeverAsked() = runBlocking {
        for (url in listOf("http://raw.githubusercontent.com/MetrolistGroup/faraday/main/player_configs.json",
            "https://evil.example/MetrolistGroup/faraday/player_configs.json",
            "https://raw.githubusercontent.com/someone/else/main/player_configs.json")) {
            val session = signedInClient { error("no request expected for $url") }
            assertNull(url, RemotePlayerConfigStore(session.http, Repository(url)).getSignatureTimestamp(player))
            assertTrue(url, world!!.calls.isEmpty())
            world?.close(); world = null
        }
    }

    /** PC03: the solver process, where config-derived code runs, holds no provider, no cookies and no WebView. */
    @Test fun theSolverProcessCreatesNoProviderCookiesOrWebView() {
        fun code(name: String) = File("src/main/java/com/musicglass/personal/$name").readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("//.*"), "")
        val solver = code("SolverService.kt") + code("PreprocessedPlayerCache.kt")
        for (name in listOf("YouTubeMusicProvider", "ProviderSessionManager", "AppContainer", "CookieManager", "WebView", "getCookie"))
            assertFalse("$name in the solver process", name in solver)
        // The application object makes nothing but the crash recorder outside the main process.
        val start = code("Strings.kt").substringAfter("class RiffApplication").substringAfter("override fun onCreate()").substringBefore("\n    }")
        assertEquals(listOf("super.onCreate()", "val role = processRole(getProcessName(), packageName)", "CrashRecorder.install(this, role)",
            "if (role == ProcessRole.Main) Thread({ runCatching { recordProcessExits(this) } }, \"riff-exit-records\").start()"),
            start.lines().map(String::trim).filter { it.isNotEmpty() && it != "{" })
    }
}
