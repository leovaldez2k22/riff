package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Q12A: UPD01 (notice only for a newer release, silent on failure) and UPD02 (off means no request). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateNoticeTest {
    private val feed = "https://releases.example.org/riff/releases.json"
    private val pages = listOf("github.com")
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(UpdateChecker.PREFS, Context.MODE_PRIVATE)
    private val clock = FakeClock()
    private val requests = mutableListOf<Request>()
    private var answer: (Request) -> Response = { reply(it, 200, releases("v0.11.0")) }
    private var conditions = NetworkConditions(wifi = true, unmetered = true, validated = true)
    private var dataSaver = false
    private val events = mutableListOf<String>()
    private val release = BuildIdentity("a".repeat(40), "clean", "b".repeat(64), "release", 22)

    @Before fun clean() { prefs.edit().clear().commit() }

    private fun reply(request: Request, code: Int, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()

    private fun entry(tag: String, page: String = "https://github.com/example/riff/releases/tag/$tag", prerelease: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"$tag","html_url":"$page","prerelease":$prerelease,"draft":$draft,"body":"Fixes."}"""
    private fun releases(vararg tags: String) = tags.joinToString(",", "[", "]") { entry(it) }

    private fun checker(installed: String = "0.10.0", identity: BuildIdentity = release, feedAddress: String = feed) = UpdateChecker(
        prefs, feedAddress, pages, installed, identity,
        OkHttpClient.Builder().addInterceptor { chain -> requests += chain.request(); answer(chain.request()) }.build(),
        clock, { conditions }, { dataSaver }, Dispatchers.Unconfined, { events += it.text })

    @Test fun versionsOrderAsReleasesAreNumbered() {
        val ordered = listOf("0.9.0-rc20", "0.9.0", "0.10.0-rc1", "0.10.0-rc2", "0.10.0", "0.10.1", "1.0.0").map { ReleaseVersion.parseTag(it)!! }
        assertEquals(ordered, ordered.shuffled(java.util.Random(4)).sorted())
        assertEquals(ReleaseVersion(0, 10, 0, 1), ReleaseVersion.parseTag("v0.10.0-rc1"))
        assertEquals(ReleaseVersion(0, 10, 0, 1), ReleaseVersion.parseInstalled("0.10.0-rc1-lite"))
        for (bad in listOf(null, "", "0.10", "0.10.0-beta1", "0.10.0-rc1-lite", "0.10.0 ", "release-0.10.0", "0.10.0-rc", "99999.0.0"))
            assertNull(bad, ReleaseVersion.parseTag(bad))
    }

    @Test fun onlyAStrictlyNewerReleaseIsOffered() = runBlocking {
        answer = { reply(it, 200, releases("v0.9.0", "v0.11.0", "v0.10.0")) }
        val newer = checker().checkNow() as UpdateResult.Newer
        assertEquals("0.11.0", newer.release.version.toString())
        assertEquals("https://github.com/example/riff/releases/tag/v0.11.0", newer.release.page)
        answer = { reply(it, 200, releases("v0.10.0")) }
        assertEquals(UpdateResult.Current(ReleaseVersion(0, 10, 0, null)), checker().checkNow())
        answer = { reply(it, 200, releases("v0.9.0")) }
        assertEquals(UpdateResult.Current(ReleaseVersion(0, 9, 0, null)), checker().checkNow())
        // A release that is no longer newer is forgotten.
        assertNull(checker().available)
    }

    @Test fun aPrereleaseCountsOnlyForAPrereleaseInstall() = runBlocking {
        answer = { reply(it, 200, "[${entry("v0.11.0-rc1", prerelease = true)},${entry("v0.12.0", draft = true)}]") }
        assertEquals(UpdateResult.Current(null), checker(installed = "0.10.0").checkNow())
        assertEquals("0.11.0-rc1", (checker(installed = "0.10.0-rc3").checkNow() as UpdateResult.Newer).release.version.toString())
        // A tag that says rc is a prerelease whatever the flag says.
        answer = { reply(it, 200, "[${entry("v0.11.0-rc2")}]") }
        assertEquals(UpdateResult.Current(null), checker(installed = "0.10.0").checkNow())
    }

    @Test fun aLocalBuildIsNeverCalledCurrent() = runBlocking {
        answer = { reply(it, 200, releases("v0.10.0")) }
        for (identity in listOf(release.copy(sourceState = "dirty"), release.copy(revision = "local-unknown", sourceState = "local-unknown"), release.copy(variant = "debug")))
            assertEquals(UpdateResult.LocalBuild(ReleaseVersion(0, 10, 0, null)), checker(identity = identity).checkNow())
        answer = { reply(it, 404, "{}") }
        assertEquals(UpdateResult.LocalBuild(null), checker(identity = release.copy(sourceState = "dirty")).checkNow())
        assertEquals(string(R.string.local_build_no_release), updateResultText(UpdateResult.LocalBuild(null)))
        // Even a local build hears about a newer release.
        answer = { reply(it, 200, releases("v0.11.0")) }
        assertTrue(checker(identity = release.copy(sourceState = "dirty")).checkNow() is UpdateResult.Newer)
    }

    @Test fun failuresAreSilentAndTruthful() = runBlocking {
        val cases = listOf<Pair<(Request) -> Response, UpdateFailure>>(
            { r: Request -> reply(r, 200, "not json") } to UpdateFailure.Malformed,
            { r: Request -> reply(r, 200, "\"v0.11.0\"") } to UpdateFailure.Malformed,
            { r: Request -> reply(r, 200, "[" + "x".repeat(MAX_UPDATE_FEED_BYTES) + "]") } to UpdateFailure.TooLarge,
            { r: Request -> reply(r, 429, "") } to UpdateFailure.RateLimited,
            { r: Request -> reply(r, 403, "") } to UpdateFailure.RateLimited,
            { r: Request -> reply(r, 500, "") } to UpdateFailure.Refused,
            { _: Request -> throw IOException("unreachable") } to UpdateFailure.Unreachable,
            // Followed to a plain-HTTP address: refused.
            { r: Request -> reply(r.newBuilder().url("http://releases.example.org/riff/releases.json").build(), 200, releases("v0.11.0")) } to UpdateFailure.Refused,
        )
        for ((script, reason) in cases) {
            answer = script
            val checker = checker()
            assertEquals(UpdateResult.Failed(reason), checker.checkNow())
            assertNull(checker.available); assertNull(checker.pendingNotice)
            assertNotNull(updateResultText(UpdateResult.Failed(reason)))
        }
        conditions = conditions.copy(validated = false)
        val before = requests.size
        assertEquals(UpdateResult.Failed(UpdateFailure.Offline), checker().checkNow())
        assertEquals("offline: nothing is sent", before, requests.size)
        assertTrue(events.all { it.startsWith("update_check result=") })
    }

    @Test fun anEntryWhosePageIsNotAnAllowedHttpsAddressIsSkipped() = runBlocking {
        val lookalikes = listOf("http://github.com/example/riff/releases/tag/v0.11.0", "https://github.com.evil.example/x",
            "https://evilgithub.com/x", "https://user@github.com/x", "https://github.com:8443/x", "javascript:alert(1)",
            "intent://github.com/#Intent;end", "https://github.com%40evil.example/x", "")
        answer = { reply(it, 200, lookalikes.joinToString(",", "[", "]") { page -> entry("v0.11.0", page) }) }
        assertEquals(UpdateResult.Current(null), checker().checkNow())
        assertNull(parseReleaseFeed("[${entry("v0.11.0", "https://evil.example/x")}]", pages, ReleaseVersion(0, 10, 0, null))!!.firstOrNull())
        // A subdomain of an allowed host is allowed.
        assertEquals(1, parseReleaseFeed("[${entry("v0.11.0", "https://www.github.com/x")}]", pages, ReleaseVersion(0, 10, 0, null))!!.size)
        // A stored page is checked again when it is read back.
        prefs.edit().putString("newer_version", "0.11.0").putString("newer_page", "https://evil.example/x").commit()
        assertNull(checker().available)
    }

    @Test fun oneNoticePerVersionAndADismissedVersionStaysDismissed() = runBlocking {
        val first = checker()
        first.checkNow()
        val notice = first.pendingNotice!!
        first.noticeShown(notice)
        assertNull(first.pendingNotice)
        assertNotNull("the About row still offers it", first.available)
        // After a restart and another answer naming the same version, no second notice.
        val restarted = checker()
        assertNull(restarted.pendingNotice)
        restarted.checkNow()
        assertNull(restarted.pendingNotice)
        // A later version gets its own notice.
        answer = { reply(it, 200, releases("v0.12.0")) }
        restarted.checkNow()
        assertEquals("0.12.0", restarted.pendingNotice!!.version.toString())
    }

    @Test fun offMeansNoRequestAtAll() = runBlocking {
        val checker = checker()
        checker.updateEnabled(false)
        repeat(3) { clock.advance(UPDATE_CHECK_INTERVAL_MS) ; assertEquals(UpdateResult.Disabled, checker.checkIfDue()) }
        assertEquals(UpdateResult.Disabled, checker.checkNow())
        assertEquals(UpdateResult.Disabled, checker().checkIfDue())
        // A build without a feed has no check either.
        prefs.edit().clear().commit()
        for (address in listOf("", "http://releases.example.org/x", "not an address"))
            assertEquals(UpdateResult.NotConfigured, checker(feedAddress = address).checkNow())
        assertFalse(checker(feedAddress = "").configured)
        assertEquals(0, requests.size)
    }

    @Test fun theAutomaticCheckRunsAtMostOnceADayWhateverTheDate() = runBlocking {
        val checker = checker()
        assertTrue(checker.checkIfDue() is UpdateResult.Newer)
        clock.advance(60 * 60 * 1000L)
        assertEquals(UpdateResult.Skipped, checker.checkIfDue())
        clock.advance(UPDATE_CHECK_INTERVAL_MS)
        assertTrue(checker.checkIfDue() is UpdateResult.Newer)
        assertEquals(2, requests.size)
        // The date is moved forward a year: this process still waits a day.
        clock.wall += 365 * UPDATE_CHECK_INTERVAL_MS
        assertEquals(UpdateResult.Skipped, checker.checkIfDue())
        // The date is moved back: once, then a day again.
        clock.wall -= 400 * UPDATE_CHECK_INTERVAL_MS
        assertEquals(UpdateResult.Skipped, checker.checkIfDue())
        val restarted = checker()
        assertTrue(restarted.checkIfDue() is UpdateResult.Newer)
        assertEquals(UpdateResult.Skipped, restarted.checkIfDue())
        assertEquals(UpdateResult.Skipped, checker().checkIfDue())
        assertEquals(3, requests.size)
        // A failure waits for the next day like a success.
        answer = { throw IOException("down") }
        clock.advance(UPDATE_CHECK_INTERVAL_MS)
        val failing = checker()
        assertEquals(UpdateResult.Failed(UpdateFailure.Unreachable), failing.checkIfDue())
        assertEquals(UpdateResult.Skipped, failing.checkIfDue())
        assertEquals(UpdateResult.Skipped, checker().checkIfDue())
        // The manual check asks whenever it is asked.
        assertEquals(UpdateResult.Failed(UpdateFailure.Unreachable), failing.checkNow())
        assertEquals(5, requests.size)
    }

    @Test fun meteredWithDataSaverWaitsForTheAutomaticCheckOnly() = runBlocking {
        conditions = conditions.copy(wifi = false, unmetered = false)
        dataSaver = true
        assertEquals(UpdateResult.Skipped, checker().checkIfDue())
        assertEquals(0, requests.size)
        assertTrue(checker().checkNow() is UpdateResult.Newer)
        dataSaver = false
        clock.advance(UPDATE_CHECK_INTERVAL_MS)
        assertTrue(checker().checkIfDue() is UpdateResult.Newer)
    }

    @Test fun theRequestCarriesNothingAboutTheUserOrDevice() = runBlocking {
        checker().checkNow()
        val request = requests.single()
        assertEquals(feed, request.url.toString())
        assertEquals("GET", request.method)
        assertEquals(setOf("Accept", "User-Agent"), request.headers.names())
        assertEquals("Riff-update-check", request.header("User-Agent"))
    }

    @Test fun noCodePathDownloadsOrInstalls() {
        val source = File("src/main/java/com/musicglass/personal/UpdateNotice.kt").readText()
        for (forbidden in listOf("PackageInstaller", "DownloadManager", "ACTION_INSTALL_PACKAGE", "REQUEST_INSTALL_PACKAGES", ".apk", "FileOutputStream", "startActivity"))
            assertFalse(forbidden, forbidden in source)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("REQUEST_INSTALL_PACKAGES" in manifest)
        // The release page opens through the Q15 check, which allows HTTPS on the listed hosts only.
        val shell = File("src/main/java/com/musicglass/personal/RiffApp.kt").readText() + File("src/main/java/com/musicglass/personal/SettingsSections.kt").readText()
        assertEquals(2, Regex("""openWebPage\(context, ?\w+\.page, ?updates\.pageHosts\)""").findAll(shell).count())
    }
}
