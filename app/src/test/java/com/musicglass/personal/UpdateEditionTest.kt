package com.musicglass.personal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateEditionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("edition_update_fixture", Context.MODE_PRIVATE)
    private val clean = BuildIdentity("a".repeat(40), "clean", "b".repeat(64), "release", 36)
    private val clock = FakeClock()
    private var body = "[]"
    private var requests = 0
    private var route: ((Request) -> String)? = null
    private val asked = mutableListOf<String>()
    @Before fun reset() { prefs.edit().clear().commit(); requests = 0; route = null; asked.clear() }

    private fun release(version: String, assets: String): String =
        """{"tag_name":"v$version","html_url":"https://github.com/example/riff/releases/tag/v$version","assets":[$assets]}"""
    private fun asset(name: String, size: String = "12000000") = """{"name":"$name","size":$size}"""
    private fun both(version: String) = release(version,
        asset("riff-$version.apk", "9000000") + "," + asset("riff-plus-$version.apk", "45000000"))
    private fun checker(edition: ReleaseEdition, installed: String = "0.30.0", identity: BuildIdentity = clean,
        feed: String = "https://feed.example/releases") = UpdateChecker(
        prefs, feed, listOf("github.com"), installed, identity,
        OkHttpClient.Builder().addInterceptor { chain ->
            requests++; asked += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body((route?.invoke(chain.request()) ?: body).toResponseBody("application/json".toMediaType())).build()
        }.build(), clock, { NetworkConditions(wifi = true, unmetered = true, validated = true) }, { false }, Dispatchers.Unconfined, {}, edition)

    @Test fun anUpdateRequiresTheExactAssetForTheInstalledEdition() = runBlocking {
        for (edition in ReleaseEdition.entries) {
            reset()
            val version = ReleaseVersion(0, 31, 0, null)
            body = "[" + release("0.31.0", asset(edition.other.assetName(version))) + "]"
            assertEquals(UpdateResult.Current(null), checker(edition).checkNow())
            body = "[" + release("0.31.0", asset(edition.assetName(version))) + "]"
            assertEquals(version, (checker(edition).checkNow() as UpdateResult.Newer).release.version)
        }
    }

    @Test fun wrongVersionNamesMissingSizesAndDuplicateAssetsCannotAdvertiseAnUpdate() = runBlocking {
        val name = ReleaseEdition.current.assetName(ReleaseVersion(0, 31, 0, null))
        val cases = listOf(asset(name.replace("0.31.0", "0.32.0")), asset(name, "0"), asset(name, "-1"),
            asset(name, "\"12000000\""), asset(name, "1.2"), asset(name, "536870913"), asset(name, "9223372036854775808"),
            """{"name":"$name"}""", asset(name) + "," + asset(name))
        for (assets in cases) {
            reset(); body = "[" + release("0.31.0", assets) + "]"
            assertEquals(assets, UpdateResult.Current(null), checker(ReleaseEdition.current).checkNow())
        }
    }

    @Test fun switchingUsesTheSameVersionEvenWhenTheFeedHasANewerRelease() = runBlocking {
        for (edition in ReleaseEdition.entries) {
            reset(); body = "[" + both("0.31.0") + "," + both("0.30.0") + "]"
            val updates = checker(edition)
            assertTrue(updates.checkNow() is UpdateResult.Newer)
            val target = updates.switchAvailable!!
            assertEquals(ReleaseVersion(0, 30, 0, null), target.release.version)
            assertEquals(edition.other, target.edition)
            assertEquals(if (edition.other == ReleaseEdition.Plus) 45000000L else 9000000L, target.bytes)
            assertTrue(target.release.page.endsWith("v0.30.0"))
            assertEquals(1, requests)
        }
    }

    @Test fun missingSameVersionOrOppositeAssetLeavesNoSwitch() = runBlocking {
        for (edition in ReleaseEdition.entries) {
            reset(); body = "[" + both("0.31.0") + "]"
            val updates = checker(edition); updates.checkNow(); assertNull(updates.switchAvailable)
            body = "[" + release("0.30.0", asset(edition.assetName(ReleaseVersion(0,30,0,null)))) + "]"
            updates.checkNow(); assertNull(updates.switchAvailable)
        }
    }

    @Test fun cachedAssetsAreRecheckedForTheEditionAndPermitSwitchingBackWithoutANewRequest() = runBlocking {
        body = "[" + both("0.30.0") + "," + both("0.31.0") + "]"
        val riff = checker(ReleaseEdition.Riff); riff.checkNow()
        val plus = checker(ReleaseEdition.Plus)
        assertEquals(ReleaseEdition.Riff, plus.switchAvailable!!.edition)
        assertEquals(ReleaseVersion(0,31,0,null), plus.available!!.version)
        assertEquals(1, requests)
        prefs.edit().putString("newer_assets", """[{"name":"riff-plus-0.31.0.apk","size":45000000}]""").commit()
        assertNull(checker(ReleaseEdition.Riff).available)
        assertNotNull(checker(ReleaseEdition.Plus).available)
        assertEquals(1, requests)
    }

    @Test fun anUpgradeReusesOnlyMetadataForItsExactInstalledVersion() = runBlocking {
        body = "[" + both("0.31.0") + "]"
        checker(ReleaseEdition.Riff, installed = "0.30.0").checkNow()
        val upgraded = checker(ReleaseEdition.Plus, installed = "0.31.0")
        assertNull(upgraded.available)
        assertEquals(ReleaseVersion(0,31,0,null), upgraded.switchAvailable!!.release.version)
        assertEquals(ReleaseEdition.Riff, upgraded.switchAvailable!!.edition)
        assertNull(checker(ReleaseEdition.Plus, installed = "0.32.0").switchAvailable)
        // Feature 3: the installed 0.30.0 was not on the list, so it was asked for once by its tag (a list here is no single release).
        assertEquals(2, requests)
    }

    @Test fun legacyCachesAndUnsafeCachedPagesAreNotAvailabilityProof() = runBlocking {
        prefs.edit().putString("newer_version", "0.31.0").putString("newer_page", "https://github.com/example/riff/releases/tag/v0.31.0").commit()
        assertNull(checker(ReleaseEdition.current).available)
        body = "[" + both("0.30.0") + "," + both("0.31.0") + "]"
        checker(ReleaseEdition.current).checkNow()
        prefs.edit().putString("newer_page", "https://evil.example/x").putString("switch_page", "https://github.com.evil.example/x").commit()
        val updates = checker(ReleaseEdition.current)
        assertNull(updates.available); assertNull(updates.switchAvailable)
    }

    @Test fun offSuppressesNoticesSwitchingAndAllNewRequests() = runBlocking {
        body = "[" + both("0.30.0") + "," + both("0.31.0") + "]"
        val updates = checker(ReleaseEdition.current); updates.checkNow(); assertNotNull(updates.pendingNotice)
        updates.updateEnabled(false)
        assertNull(updates.pendingNotice); assertNull(updates.switchAvailable)
        val before = requests
        assertEquals(UpdateResult.Disabled, updates.checkNow())
        assertEquals(UpdateResult.Disabled, updates.checkIfDue())
        assertEquals(before, requests)
    }

    private fun installable(version: String, vararg names: String) = release(version, names.joinToString(",") { name ->
        """{"name":"$name","size":9000000,"browser_download_url":"https://github.com/example/riff/releases/download/v$version/$name"}""" })

    @Test fun anInstalledVersionPushedOffTheListIsLookedUpByItsTagOnce() = runBlocking {
        route = { request -> if (request.url.encodedPath.endsWith("/tags/v0.30.0")) installable("0.30.0", "riff-0.30.0.apk", "riff-plus-0.30.0.apk")
            else "[" + both("0.31.0") + "]" }
        val updates = checker(ReleaseEdition.Riff, feed = "https://api.example/repos/o/r/releases?per_page=10")
        assertTrue(updates.checkNow() is UpdateResult.Newer)
        assertEquals(listOf("https://api.example/repos/o/r/releases?per_page=10", "https://api.example/repos/o/r/releases/tags/v0.30.0"), asked)
        val target = updates.switchTarget!!
        assertEquals(InstallKind.Switch, target.kind); assertEquals(ReleaseEdition.Plus, target.edition)
        assertEquals(ReleaseVersion(0, 30, 0, null), target.version)
        assertEquals("https://github.com/example/riff/releases/download/v0.30.0/riff-plus-0.30.0.apk", target.asset.url)
        // A local build never asks for the tag and has no install target.
        reset(); route = { "[" + both("0.31.0") + "]" }
        val local = checker(ReleaseEdition.Riff, identity = clean.copy(sourceState = "dirty"), feed = "https://api.example/repos/o/r/releases")
        local.checkNow()
        assertEquals(1, requests); assertNull(local.updateTarget); assertNull(local.switchTarget)
    }

    @Test fun anInstallTargetNeedsAReleaseBuildTheSettingAndACheckedAddress() = runBlocking {
        body = "[" + installable("0.31.0", "riff-0.31.0.apk", "riff-plus-0.31.0.apk") + "]"
        val updates = checker(ReleaseEdition.Riff)
        updates.checkNow()
        val target = updates.updateTarget!!
        assertEquals(InstallKind.Update, target.kind); assertEquals(ReleaseEdition.Riff, target.edition)
        assertEquals("riff-0.31.0.apk", target.asset.name)
        // The address survives a restart through the cache, and is checked again there.
        assertEquals(target, checker(ReleaseEdition.Riff).updateTarget)
        updates.updateEnabled(false); assertNull(updates.updateTarget)
        updates.updateEnabled(true)
        for (identity in listOf(clean.copy(variant = "debug"), clean.copy(sourceState = "dirty")))
            assertNull(checker(ReleaseEdition.Riff, identity = identity).updateTarget)
        // Listed without an address: the notice still tells, and the page is the way.
        reset(); body = "[" + both("0.31.0") + "]"
        val pageOnly = checker(ReleaseEdition.Riff); pageOnly.checkNow()
        assertNotNull(pageOnly.available); assertNull(pageOnly.updateTarget)
    }

    @Test fun localDirtyPreviewAndUnknownBuildsHaveNoEditionSwitch() = runBlocking {
        body = "[" + both("0.30.0") + "]"
        checker(ReleaseEdition.current).checkNow()
        for (identity in listOf(clean.copy(sourceState = "dirty"), clean.copy(variant = "preview"),
            clean.copy(variant = "debug"), clean.copy(revision = "local-unknown", sourceState = "local-unknown")))
            assertNull(checker(ReleaseEdition.current, identity = identity).switchAvailable)
        assertFalse(checker(ReleaseEdition.current, installed = "0.30.0-preview").configured)
    }
}
