package com.musicglass.personal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import java.security.MessageDigest

/** Feature 3 (Q3): what may be downloaded, what is checked before Android's installer sees it, and the flow around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateInstallerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("update_install_fixture", Context.MODE_PRIVATE)
    private val staging get() = File(context.noBackupFilesDir, "update-staging-fixture")
    private val v30 = ReleaseVersion(0, 30, 0, null)
    private val v31 = ReleaseVersion(0, 31, 0, null)
    private val signer = "c".repeat(64)
    private val installed = InstalledApp("com.musicglass.personal", 36, v30, ReleaseEdition.Riff, setOf(signer))
    private val payload = ByteArray(300_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val github = "https://github.com/example/riff/releases/download/v0.31.0/riff-0.31.0.apk"

    private fun asset(name: String = "riff-0.31.0.apk", bytes: Long = payload.size.toLong(), url: String? = github, sha: String? = payloadSha) =
        ReleaseAsset(name, bytes, url, sha)
    private fun update(asset: ReleaseAsset = asset()) = InstallTarget(InstallKind.Update, ReleaseEdition.Riff, v31, asset,
        "https://github.com/example/riff/releases/tag/v0.31.0")
    private fun switch() = InstallTarget(InstallKind.Switch, ReleaseEdition.Plus, v30,
        ReleaseAsset("riff-plus-0.30.0.apk", payload.size.toLong(), github.replace("v0.31.0/riff-0.31.0", "v0.30.0/riff-plus-0.30.0")),
        "https://github.com/example/riff/releases/tag/v0.30.0")
    private fun facts(version: String = "0.31.0", code: Long = 37, edition: String? = "riff", signers: Set<String> = setOf(signer),
        name: String = "com.musicglass.personal") = ApkFacts(name, code, version, signers, edition)

    @Before fun reset() { prefs.edit().clear().commit(); staging.deleteRecursively(); requests.clear(); committed.clear(); abandoned.clear() }

    // ---- what the APK must say about itself

    @Test fun anUpdateMustBeThisAppSignedTheSameInThisEditionAndNewer() {
        assertNull(checkApk(facts(), update(), installed))
        val cases = mapOf(
            null to InstallFailure.Unreadable,
            facts(name = "com.musicglass.personal.preview") to InstallFailure.NotTheApp,
            facts(signers = setOf("d".repeat(64))) to InstallFailure.WrongSigner,
            facts(signers = emptySet()) to InstallFailure.WrongSigner,
            facts(signers = setOf(signer, "d".repeat(64))) to InstallFailure.WrongSigner,
            facts(edition = "plus") to InstallFailure.WrongEdition,
            facts(edition = null) to InstallFailure.WrongEdition,
            facts(version = "0.32.0") to InstallFailure.WrongVersion,
            facts(version = "0.31.0-rc1") to InstallFailure.WrongVersion,
            facts(code = 36) to InstallFailure.WrongVersion,
            facts(code = 35) to InstallFailure.WrongVersion,
        )
        for ((given, reason) in cases) assertEquals("$given", reason, checkApk(given, update(), installed))
        // An installed app with no readable signer accepts nothing.
        assertEquals(InstallFailure.WrongSigner, checkApk(facts(), update(), installed.copy(signers = emptySet())))
        // A downgrade dressed as an update.
        assertEquals(InstallFailure.WrongVersion, checkApk(facts(version = "0.29.0", code = 37),
            update().copy(version = ReleaseVersion(0, 29, 0, null)), installed))
    }

    @Test fun aSwitchKeepsTheExactVersionAndChangesTheEdition() {
        assertNull(checkApk(facts(version = "0.30.0", code = 36, edition = "plus"), switch(), installed))
        assertEquals(InstallFailure.WrongEdition, checkApk(facts(version = "0.30.0", code = 36, edition = "riff"), switch(), installed))
        assertEquals(InstallFailure.WrongVersion, checkApk(facts(version = "0.30.0", code = 37, edition = "plus"), switch(), installed))
        assertEquals(InstallFailure.WrongVersion, checkApk(facts(version = "0.31.0", code = 36, edition = "plus"), switch(), installed))
        // A "switch" to the installed edition is not a switch.
        assertEquals(InstallFailure.WrongEdition, checkApk(facts(version = "0.30.0", code = 36, edition = "riff"),
            switch().copy(edition = ReleaseEdition.Riff), installed))
    }

    // ---- what may be fetched

    @Test fun onlyTheAssetsOwnGitHubReleasePathIsADownloadAddress() {
        assertEquals(github, releaseAssetAddress(github, "riff-0.31.0.apk", v31))
        for (bad in listOf(null, "", "http://github.com/example/riff/releases/download/v0.31.0/riff-0.31.0.apk",
            "https://github.com.evil.example/example/riff/releases/download/v0.31.0/riff-0.31.0.apk",
            "https://evil.example/example/riff/releases/download/v0.31.0/riff-0.31.0.apk",
            "https://user@github.com/example/riff/releases/download/v0.31.0/riff-0.31.0.apk",
            "https://github.com:8443/example/riff/releases/download/v0.31.0/riff-0.31.0.apk",
            "https://github.com/example/riff/releases/download/v0.32.0/riff-0.31.0.apk",
            "https://github.com/example/riff/releases/download/v0.31.0/riff-plus-0.31.0.apk",
            "https://github.com/example/riff/releases/download/v0.31.0/riff-0.31.0.apk?x=1",
            "https://github.com/example/riff/releases/download/v0.31.0/x/riff-0.31.0.apk",
            "https://api.github.com/repos/example/riff/releases/assets/1"))
            assertNull(bad, releaseAssetAddress(bad, "riff-0.31.0.apk", v31))
    }

    @Test fun theFeedsAddressAndDigestAreKeptOnlyWhenWellFormed() {
        fun parse(extra: String) = parseReleaseFeed("""[{"tag_name":"v0.31.0","html_url":"https://github.com/example/riff/releases/tag/v0.31.0",
            "assets":[{"name":"riff-0.31.0.apk","size":9000000$extra}]}]""", listOf("github.com"), v30)!!.single().assets.single()
        val good = parse(""","browser_download_url":"$github","digest":"sha256:${"A".repeat(64)}"""")
        assertEquals(github, good.url); assertEquals("a".repeat(64), good.sha256)
        val bad = parse(""","browser_download_url":"https://evil.example/riff-0.31.0.apk","digest":"md5:abc"""")
        assertNull(bad.url); assertNull(bad.sha256)
        assertNull(parse("").url)
    }

    // ---- the transfer

    private val requests = mutableListOf<Request>()
    private var script: (Request) -> Response = { serve(it) }
    private fun reply(request: Request, code: Int, body: ByteArray = ByteArray(0), location: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
    private val cdn = "https://release-assets.githubusercontent.com/signed/riff-0.31.0.apk?sig=secret"
    private fun serve(request: Request, body: ByteArray = payload) =
        if (request.url.host == "github.com") reply(request, 302, location = cdn) else reply(request, 200, body)
    private val client get() = OkHttpClient.Builder().addInterceptor { chain -> requests += chain.request(); script(chain.request()) }.build()

    private fun fetch(asset: ReleaseAsset = asset()): Pair<InstallFailure?, File> = runBlocking {
        staging.mkdirs(); val file = File(staging, "t.apk")
        ApkTransfer(client).fetch(asset, file) {} to file
    }

    @Test fun aRedirectToGitHubsDownloadHostIsFollowedAndTheBytesAreChecked() {
        val (failure, file) = fetch()
        assertNull(failure)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), requests.map { it.url.host })
        for (request in requests) {
            assertNull(request.header("Cookie")); assertNull(request.header("Authorization"))
            assertEquals("Riff-update", request.header("User-Agent"))
        }
    }

    @Test fun aSpoofedRedirectAWrongLengthOrAWrongDigestStopsTheTransfer() {
        val cases = listOf<Pair<(Request) -> Response, InstallFailure>>(
            { r: Request -> if (r.url.host == "github.com") reply(r, 302, location = "https://evil.example/riff.apk") else serve(r) } to InstallFailure.Refused,
            { r: Request -> if (r.url.host == "github.com") reply(r, 302, location = "http://release-assets.githubusercontent.com/x") else serve(r) } to InstallFailure.Refused,
            { r: Request -> if (r.url.host == "github.com") reply(r, 302, location = "https://release-assets.githubusercontent.com.evil.example/x") else serve(r) } to InstallFailure.Refused,
            { r: Request -> if (r.url.host == "github.com") reply(r, 302) else serve(r) } to InstallFailure.Refused,
            { r: Request -> reply(r, 302, location = cdn) } to InstallFailure.Refused, // a loop
            { r: Request -> serve(r, payload + byteArrayOf(1)) } to InstallFailure.WrongSize,
            { r: Request -> serve(r, payload.copyOf(payload.size - 1)) } to InstallFailure.WrongSize,
            { r: Request -> serve(r, payload.copyOf().also { it[7] = 99 }) } to InstallFailure.WrongDigest,
            { r: Request -> reply(r, 404) } to InstallFailure.Unreachable,
            { _: Request -> throw IOException("down") } to InstallFailure.Unreachable,
        )
        for ((given, reason) in cases) { script = given; assertEquals(reason, fetch().first) }
        // Without a listed digest the length still decides.
        script = { serve(it, payload.copyOf().also { b -> b[7] = 99 }) }
        assertNull(fetch(asset(sha = null)).first)
        assertEquals(InstallFailure.Refused, fetch(asset(url = null)).first)
    }

    // ---- the flow

    private val committed = mutableListOf<InstallTarget>()
    private val abandoned = mutableListOf<Int>()
    private var allowed = true
    private var conditions = NetworkConditions(wifi = true, unmetered = true, validated = true)
    private var free = Long.MAX_VALUE
    private var inspected: ApkFacts? = facts()
    private var now = installed
    private val events = mutableListOf<String>()
    private var onProgress: (() -> Unit)? = null

    private fun installer(): UpdateInstaller {
        val transfer = ApkTransfer(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request(); onProgress?.invoke(); script(chain.request()) }.build())
        return UpdateInstaller(prefs, staging, transfer, { conditions }, { free }, { file ->
            assertTrue("the staged file exists while it is checked", file.isFile); inspected }, { now }, { allowed },
            object : InstallSink {
                override fun commit(apk: File, target: InstallTarget): Int { assertTrue(apk.isFile); committed += target; return 7 }
                override fun abandon(sessionId: Int) { abandoned += sessionId }
            }, CoroutineScope(Dispatchers.Unconfined), { events += it.text })
    }

    @Test fun nothingIsSentUntilTheUserConfirmsAndAMeteredNetworkIsNamed() {
        val installer = installer()
        conditions = conditions.copy(wifi = false, unmetered = false)
        installer.offer(update())
        assertEquals(InstallState.Confirm(update(), metered = true), installer.state)
        installer.dismiss()
        assertEquals(InstallState.Idle, installer.state)
        assertTrue(requests.isEmpty()); assertTrue(committed.isEmpty())
    }

    @Test fun aConfirmedUpdateIsDownloadedCheckedCommittedAndTheStagedCopyRemoved() {
        val installer = installer()
        installer.offer(update()); installer.confirm()
        assertEquals(listOf(update()), committed)
        assertEquals(InstallState.Installing(update()), installer.state)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(7, prefs.getInt("session", -1))
        // Android asks; the visible app opens its screen.
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        installer.sessionResult(PackageInstaller.STATUS_PENDING_USER_ACTION, confirm)
        assertEquals(InstallState.AwaitingUser(update(), confirm), installer.state)
        installer.offer(switch())
        assertTrue("a second request waits", installer.state is InstallState.AwaitingUser)
        installer.confirmationShown()
        installer.sessionResult(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals(InstallState.Failed(update(), InstallFailure.Declined), installer.state)
        assertEquals(-1, prefs.getInt("session", -1))
        assertTrue(events.none { "http" in it || "github" in it || "secret" in it })
    }

    @Test fun aFileThatFailsItsChecksNeverReachesTheInstaller() {
        for (bad in listOf(null, facts(edition = "plus"), facts(signers = setOf("d".repeat(64))), facts(code = 36))) {
            reset(); inspected = bad
            val installer = installer()
            installer.offer(update()); installer.confirm()
            assertTrue(installer.state is InstallState.Failed)
            assertTrue(committed.isEmpty())
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun withoutInstallAccessRiffAsksAndRecoversFromARefusal() {
        allowed = false
        val installer = installer()
        installer.offer(switch()); installer.confirm()
        assertEquals(InstallState.NeedsAccess(switch()), installer.state)
        installer.accessReturned()
        assertEquals("not asked yet: no denial shown", InstallState.NeedsAccess(switch()), installer.state)
        installer.accessAsked(); installer.accessReturned()
        assertEquals(InstallState.NeedsAccess(switch(), asked = true, denied = true), installer.state)
        assertTrue(requests.isEmpty())
        allowed = true; inspected = facts(version = "0.30.0", code = 36, edition = "plus")
        installer.accessAsked(); installer.accessReturned()
        assertEquals(listOf(switch()), committed)
    }

    @Test fun offlineOrFullStorageFailsBeforeAnyRequest() {
        conditions = conditions.copy(validated = false)
        installer().apply { offer(update()); confirm(); assertEquals(InstallState.Failed(update(), InstallFailure.Offline), state) }
        conditions = conditions.copy(validated = true); free = payload.size * 2L
        installer().apply { offer(update()); confirm(); assertEquals(InstallState.Failed(update(), InstallFailure.NoSpace), state) }
        assertTrue(requests.isEmpty())
        // A failed transfer offers a manual retry, which asks again only when pressed.
        free = Long.MAX_VALUE; script = { throw IOException("down") }
        val installer = installer()
        installer.offer(update()); installer.confirm()
        assertEquals(InstallState.Failed(update(), InstallFailure.Unreachable), installer.state)
        val asked = requests.size
        script = { serve(it) }
        installer.confirm()
        assertTrue(requests.size > asked)
        assertEquals(listOf(update()), committed)
    }

    @Test fun aCancelledDownloadNeverInstallsAndLeavesNothing() {
        val installer = installer()
        onProgress = { if (requests.size == 2) installer.cancel() }
        installer.offer(update()); installer.confirm()
        assertEquals(InstallState.Idle, installer.state)
        assertTrue(committed.isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun aSessionLeftByAnEarlierProcessIsClosedAtStart() {
        prefs.edit().putInt("session", 9).putString("kind", "Update").putString("edition", "Riff").putString("version", "0.31.0").commit()
        staging.mkdirs(); File(staging, "staged.apk").writeText("left over")
        installer().reconcile()
        assertEquals("not installed: abandoned", listOf(9), abandoned)
        assertTrue(staging.listFiles().orEmpty().isEmpty())
        assertEquals(-1, prefs.getInt("session", -1))
        prefs.edit().putInt("session", 10).putString("kind", "Update").putString("edition", "Riff").putString("version", "0.31.0").commit()
        now = installed.copy(version = v31, versionCode = 37)
        installer().reconcile()
        assertEquals("installed: nothing to abandon", listOf(9), abandoned)
    }

    @Test fun downloadHopsAreCheckedOneByOne() {
        assertTrue(allowedDownloadHop("https://objects.githubusercontent.com/x".toHttpUrl()))
        assertFalse(allowedDownloadHop("https://objects.githubusercontent.com:444/x".toHttpUrl()))
        assertFalse(allowedDownloadHop("https://a@objects.githubusercontent.com/x".toHttpUrl()))
        assertFalse(allowedDownloadHop("https://github.com/x".toHttpUrl()))
    }
}
