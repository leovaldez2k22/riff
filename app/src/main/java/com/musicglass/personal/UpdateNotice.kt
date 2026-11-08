package com.musicglass.personal

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException

/**
 * The update notice (Q12A). Riff is installed from a file, so nothing else tells a user that a fix exists. Once a
 * day, while the app is open, it asks the public release feed for the newest version and says so when that is newer
 * than the one installed. The request carries no account, device or install detail. This file only reads the feed:
 * downloading and installing, after the user asks, is feature 3's UpdateInstaller.kt.
 */

/** A version as releases are numbered (Q01): 0.10.0, or 0.10.0-rc1 for a candidate, which ranks below 0.10.0. */
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val candidate: Int?) : Comparable<ReleaseVersion> {
    val prerelease get() = candidate != null

    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        { it.major }, { it.minor }, { it.patch }, { it.candidate ?: Int.MAX_VALUE })

    override fun toString() = "$major.$minor.$patch" + (candidate?.let { "-rc$it" } ?: "")

    companion object {
        private val tag = Regex("""v?(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-rc(\d{1,4}))?""")
        /** The installed version name may carry the Lite build's suffix; a release tag never does. */
        private val installed = Regex("""(\d{1,4})\.(\d{1,4})\.(\d{1,4})(?:-rc(\d{1,4}))?(?:-lite)?""")

        fun parseTag(text: String?): ReleaseVersion? = text?.let { tag.matchEntire(it) }?.let(::from)
        fun parseInstalled(text: String?): ReleaseVersion? = text?.let { installed.matchEntire(it) }?.let(::from)
        private fun from(match: MatchResult) = match.groupValues.let {
            ReleaseVersion(it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].takeIf(String::isNotEmpty)?.toInt())
        }
    }
}

/** One entry of the feed that passed every check: a version and an HTTPS page on an allowed host. */
internal data class PublishedRelease(val version: ReleaseVersion, val page: String, val assets: List<ReleaseAsset> = emptyList()) {
    fun asset(edition: ReleaseEdition): ReleaseAsset? = assets.singleOrNull { it.name == edition.assetName(version) }
}

/** Largest feed answer read. A list of ten releases with long notes stays well under it. */
internal const val MAX_UPDATE_FEED_BYTES = 256 * 1024
private const val MAX_FEED_ENTRIES = 30
internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/**
 * The releases in a feed answer: GitHub's release list, or its single latest release. Null when the answer is not
 * that shape at all. An entry is skipped when it is a draft, its tag is not a version, or its page is not an HTTPS
 * address on [pageHosts]; a skipped entry is never shown. A prerelease counts only for a prerelease install.
 */
internal fun parseReleaseFeed(body: String, pageHosts: Collection<String>, installed: ReleaseVersion): List<PublishedRelease>? {
    val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
    val entries = when (root) {
        is JsonArray -> root.take(MAX_FEED_ENTRIES)
        is JsonObject -> listOf(root)
        else -> return null
    }
    return entries.mapNotNull { entry ->
        val fields = entry as? JsonObject ?: return@mapNotNull null
        if (fields.flag("draft")) return@mapNotNull null
        val version = ReleaseVersion.parseTag(fields.text("tag_name")) ?: return@mapNotNull null
        if ((fields.flag("prerelease") || version.prerelease) && !installed.prerelease) return@mapNotNull null
        val page = httpsAddressOn(fields.text("html_url"), pageHosts) ?: return@mapNotNull null
        PublishedRelease(version, page.toString(), releaseAssets(fields["assets"], version))
    }
}

/** Only the two APK names are relevant; malformed, oversized and ambiguous entries fail closed. */
private fun releaseAssets(value: JsonElement?, version: ReleaseVersion): List<ReleaseAsset> =
    (value as? JsonArray)?.take(32).orEmpty().mapNotNull { element ->
        val fields = element as? JsonObject ?: return@mapNotNull null
        val name = fields.text("name") ?: return@mapNotNull null
        if (ReleaseEdition.entries.none { name == it.assetName(version) }) return@mapNotNull null
        val size = (fields["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?.takeIf { it in 1L..512L * 1024 * 1024 } ?: return@mapNotNull null
        // Feature 3: an address is kept only when it is this asset's own GitHub release path; a digest only when well formed.
        val url = releaseAssetAddress(fields.text("browser_download_url") ?: fields.text("url"), name, version)
        val sha256 = (fields.text("digest") ?: fields.text("sha256"))?.removePrefix("sha256:")?.lowercase()?.takeIf { it.matches(SHA256) }
        ReleaseAsset(name, size, url, sha256)
    }

private val SHA256 = Regex("[0-9a-f]{64}")

/**
 * Feature 3: https://github.com/<owner>/<repo>/releases/download/<tag>/<name>, exactly, for this name and version.
 * Anything else (another host, a user part, a port, an extra segment, a tag for another version) is not an address.
 */
internal fun releaseAssetAddress(text: String?, name: String, version: ReleaseVersion): String? {
    val url = text?.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.host != "github.com" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = url.pathSegments
    if (path.size != 6 || path[2] != "releases" || path[3] != "download" || path[5] != name) return null
    if (ReleaseVersion.parseTag(path[4]) != version || path[0].isEmpty() || path[1].isEmpty()) return null
    return url.toString()
}

private fun encodeReleaseAssets(release: PublishedRelease): String = JsonArray(release.assets.map { asset ->
    JsonObject(buildMap {
        put("name", JsonPrimitive(asset.name)); put("size", JsonPrimitive(asset.bytes))
        asset.url?.let { put("url", JsonPrimitive(it)) }; asset.sha256?.let { put("sha256", JsonPrimitive(it)) }
    })
}).toString()

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/** What a check found. A manual check shows it; an automatic one shows only [Newer], through the notice. */
internal sealed interface UpdateResult {
    /** A strictly newer release than the one installed. */
    data class Newer(val release: PublishedRelease) : UpdateResult
    /** The install is the newest release, or newer. [latest] is null when the feed lists no release. */
    data class Current(val latest: ReleaseVersion?) : UpdateResult
    /** A local, debug or modified build: it is not a release, so it is never called current. */
    data class LocalBuild(val latest: ReleaseVersion?) : UpdateResult
    data class Failed(val reason: UpdateFailure) : UpdateResult
    /** The setting is off: nothing was sent. */
    data object Disabled : UpdateResult
    /** This build has no release feed: nothing was sent. */
    data object NotConfigured : UpdateResult
    /** An automatic check that was not due, or was held back on a metered network with Data saver on. */
    data object Skipped : UpdateResult
}

internal enum class UpdateFailure { Offline, Unreachable, Refused, RateLimited, TooLarge, Malformed }

/** The result of a check for a given install, from the releases a feed listed. */
internal fun updateResult(releases: List<PublishedRelease>, installed: ReleaseVersion, releaseBuild: Boolean,
    edition: ReleaseEdition = ReleaseEdition.current): UpdateResult {
    val newest = releases.filter { it.asset(edition) != null }.maxByOrNull { it.version }
    return when {
        newest != null && newest.version > installed -> UpdateResult.Newer(newest)
        !releaseBuild -> UpdateResult.LocalBuild(newest?.version)
        else -> UpdateResult.Current(newest?.version)
    }
}

/** What a manual check says. Null for the results a manual check never gives. */
internal fun updateResultText(result: UpdateResult): String? = when (result) {
    is UpdateResult.Newer -> string(R.string.riff_version_is_available, result.release.version.toString())
    is UpdateResult.Current -> result.latest?.let { string(R.string.riff_is_up_to_date, it.toString()) } ?: string(R.string.no_release_published)
    is UpdateResult.LocalBuild -> result.latest?.let { string(R.string.local_build_newest_release, it.toString()) } ?: string(R.string.local_build_no_release)
    is UpdateResult.Failed -> when (result.reason) {
        UpdateFailure.Offline -> string(R.string.update_check_offline)
        UpdateFailure.RateLimited -> string(R.string.update_check_busy)
        else -> string(R.string.update_check_failed)
    }
    UpdateResult.Disabled, UpdateResult.NotConfigured, UpdateResult.Skipped -> null
}

/** Only a release built from unmodified, known source is a release; anything else says what it is instead. */
internal fun isReleaseBuild(identity: BuildIdentity) =
    identity.variant == "release" && identity.sourceState == "clean" && identity.revision != "local-unknown"

/**
 * Checks the release feed. Every input is passed in, so tests run it with a scripted feed, clock and network.
 * [feed] empty or not a valid HTTPS address means the check does not exist in this build.
 */
internal class UpdateChecker(
    private val prefs: SharedPreferences,
    private val feed: String,
    val pageHosts: List<String>,
    installedVersion: String,
    private val identity: BuildIdentity,
    private val calls: Call.Factory,
    private val clocks: AppClocks,
    private val network: () -> NetworkConditions,
    private val dataSaver: () -> Boolean,
    private val io: CoroutineDispatcher,
    private val log: (DiagnosticEvent) -> Unit,
    private val edition: ReleaseEdition = ReleaseEdition.current,
) {
    private val installed = ReleaseVersion.parseInstalled(installedVersion)
    /** Whether this build can check at all: a feed address and a version to compare. */
    val configured = httpsAddress(feed) != null && installed != null
    /** The host asked, as the setting names it. */
    val feedHost: String? = httpsAddress(feed)?.host
    private val lock = Mutex()
    /** When this process last asked, on the clock that does not move with the date setting. */
    private var lastAskedMonotonic: Long? = null

    var enabled by mutableStateOf(prefs.getBoolean(KEY_ENABLED, true)); private set
    /** The newest release known to be newer than the install, from the last answer. Kept across restarts. */
    var available by mutableStateOf(storedRelease()); private set
    private var switchRelease by mutableStateOf(storedSwitchRelease())
    private var noticed by mutableStateOf(prefs.getString(KEY_NOTICED, null))
    /** [available], when its notice has not been shown yet. Each version gets one notice. */
    val pendingNotice get() = available?.takeIf { enabled && it.version.toString() != noticed }
    /** A switch never also updates the version, and never appears for a local/preview build. */
    val switchAvailable: EditionSwitch? get() {
        if (!enabled || !configured || !isReleaseBuild(identity)) return null
        val release = switchRelease?.takeIf { it.version == installed } ?: return null
        val target = edition.other
        val asset = release.asset(target) ?: return null
        return EditionSwitch(release, target, asset.bytes)
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** The notice for [release] was shown (and dismissed, or acted on). It is not shown again. */
    fun noticeShown(release: PublishedRelease) {
        noticed = release.version.toString()
        prefs.edit().putString(KEY_NOTICED, noticed).apply()
    }

    /** The automatic check: at most once a day, and not on a metered network while Data saver is on. */
    suspend fun checkIfDue(): UpdateResult = withContext(io) {
        lock.withLock {
            readiness()?.let { return@withLock it }
            if (!due()) return@withLock UpdateResult.Skipped
            val conditions = network()
            if (!conditions.unmetered && dataSaver()) return@withLock UpdateResult.Skipped
            ask(conditions)
        }
    }

    /** Check for updates in About: asks now, whatever the time since the last check, and says what it found. */
    suspend fun checkNow(): UpdateResult = withContext(io) {
        lock.withLock { readiness() ?: ask(network()) }
    }

    private fun readiness(): UpdateResult? = when {
        !configured -> UpdateResult.NotConfigured
        !enabled -> UpdateResult.Disabled
        else -> null
    }

    /**
     * Due when a day has passed on both clocks this process can see. The stored date covers restarts; the
     * monotonic clock covers a date that is moved back and forth while the app runs, which could otherwise ask
     * on every resume. A stored date in the future (the date was moved back) counts as due once.
     */
    private fun due(): Boolean {
        val now = clocks.wallTimeMillis()
        val stored = prefs.getLong(KEY_LAST_ASKED, 0L)
        val byDate = stored == 0L || now - stored >= UPDATE_CHECK_INTERVAL_MS || now < stored
        val byProcess = lastAskedMonotonic?.let { clocks.monotonicMillis() - it >= UPDATE_CHECK_INTERVAL_MS } ?: true
        return byDate && byProcess
    }

    private fun ask(conditions: NetworkConditions): UpdateResult {
        val version = installed!!
        // Recorded before asking: a failure waits for the next day like a success does, so there is no retry storm.
        lastAskedMonotonic = clocks.monotonicMillis()
        prefs.edit().putLong(KEY_LAST_ASKED, clocks.wallTimeMillis()).apply()
        if (!conditions.validated) return failed(UpdateFailure.Offline)
        val body = when (val answer = read(feed)) {
            is FeedRead.Body -> answer.text
            FeedRead.NotFound -> null // No release has been published yet.
            is FeedRead.Failure -> return failed(answer.reason)
        }
        val releases = if (body == null) emptyList() else parseReleaseFeed(body, pageHosts, version) ?: return failed(UpdateFailure.Malformed)
        val result = updateResult(releases, version, isReleaseBuild(identity), edition)
        val newer = (result as? UpdateResult.Newer)?.release
        fun switchable(release: PublishedRelease) = release.version == version && release.asset(edition.other) != null
        // Feature 3: the switch needs the installed version itself. When every listed release is newer, the installed
        // one fell off the bounded list; it is asked for by its tag, once, on the same host. A failure only hides the switch.
        val sameVersion = releases.firstOrNull(::switchable) ?: if (isReleaseBuild(identity) && releases.isNotEmpty() &&
            releases.all { it.version > version }) tagAddress(version)?.let { address ->
            (read(address) as? FeedRead.Body)?.let { parseReleaseFeed(it.text, pageHosts, version) }?.firstOrNull(::switchable)
        } else null
        storeReleases(newer, sameVersion)
        log(DiagnosticEvent.parse("update_check result=${result::class.simpleName} count=${releases.size}"))
        return result
    }

    private sealed interface FeedRead {
        data class Body(val text: String) : FeedRead
        data object NotFound : FeedRead
        data class Failure(val reason: UpdateFailure) : FeedRead
    }

    /** One bounded HTTPS read of the feed host, with the same two headers every time. */
    private fun read(address: String): FeedRead {
        val request = Request.Builder().url(address).get()
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Riff-update-check").build()
        return try {
            calls.newCall(request).execute().use { response ->
                when {
                    !response.request.url.isHttps -> FeedRead.Failure(UpdateFailure.Refused)
                    response.code == 403 || response.code == 429 -> FeedRead.Failure(UpdateFailure.RateLimited)
                    response.code == 404 -> FeedRead.NotFound
                    !response.isSuccessful -> FeedRead.Failure(UpdateFailure.Refused)
                    response.body.contentLength() > MAX_UPDATE_FEED_BYTES -> FeedRead.Failure(UpdateFailure.TooLarge)
                    else -> {
                        val source = response.body.source()
                        source.request(MAX_UPDATE_FEED_BYTES + 1L)
                        if (source.buffer.size > MAX_UPDATE_FEED_BYTES) FeedRead.Failure(UpdateFailure.TooLarge)
                        else FeedRead.Body(source.buffer.readUtf8())
                    }
                }
            }
        } catch (_: IOException) {
            FeedRead.Failure(UpdateFailure.Unreachable)
        }
    }

    /** The feed's own release list (…/releases) turned into …/releases/tags/v<version>; any other feed has no lookup. */
    private fun tagAddress(version: ReleaseVersion): String? {
        val url = feed.toHttpUrlOrNull() ?: return null
        if (url.pathSegments.lastOrNull() != "releases") return null
        return url.newBuilder().query(null).addPathSegment("tags").addPathSegment("v$version").build().toString()
    }

    /** Feature 3: the installer works for a release build only; a local or modified one keeps the release page. */
    val installs: Boolean get() = enabled && configured && isReleaseBuild(identity)

    /** The newer release as something the installer can fetch: this edition's asset with a verified address. */
    val updateTarget: InstallTarget? get() {
        if (!installs) return null
        val release = available ?: return null
        val asset = release.asset(edition)?.takeIf { it.url != null } ?: return null
        return InstallTarget(InstallKind.Update, edition, release.version, asset, release.page)
    }

    /** The same version in the other edition, likewise. */
    val switchTarget: InstallTarget? get() {
        if (!installs) return null
        val switch = switchAvailable ?: return null
        val asset = switch.release.asset(switch.edition)?.takeIf { it.url != null } ?: return null
        return InstallTarget(InstallKind.Switch, switch.edition, switch.release.version, asset, switch.release.page)
    }

    private fun failed(reason: UpdateFailure): UpdateResult {
        log(DiagnosticEvent.parse("update_check result=Failed reason=${reason.name}"))
        return UpdateResult.Failed(reason)
    }

    private fun storeReleases(newer: PublishedRelease?, sameVersion: PublishedRelease?) {
        val editor = prefs.edit()
        fun store(release: PublishedRelease?, versionKey: String, pageKey: String, assetsKey: String) {
            if (release == null) editor.remove(versionKey).remove(pageKey).remove(assetsKey)
            else editor.putString(versionKey, release.version.toString()).putString(pageKey, release.page)
                .putString(assetsKey, encodeReleaseAssets(release))
        }
        store(newer, KEY_VERSION, KEY_PAGE, KEY_ASSETS)
        store(sameVersion, KEY_SWITCH_VERSION, KEY_SWITCH_PAGE, KEY_SWITCH_ASSETS)
        editor.apply()
        available = newer
        switchRelease = sameVersion
    }

    /** Old caches with no edition asset proof are deliberately ignored. */
    private fun stored(versionKey: String, pageKey: String, assetsKey: String): PublishedRelease? {
        val version = ReleaseVersion.parseTag(prefs.getString(versionKey, null)) ?: return null
        val page = httpsAddressOn(prefs.getString(pageKey, null), pageHosts) ?: return null
        val assetText = prefs.getString(assetsKey, null)?.takeIf { it.length <= 8192 } ?: return null
        val value = runCatching { Json.parseToJsonElement(assetText) }.getOrNull() ?: return null
        return PublishedRelease(version, page.toString(), releaseAssets(value, version))
    }

    private fun storedRelease(): PublishedRelease? {
        val current = installed ?: return null
        return stored(KEY_VERSION, KEY_PAGE, KEY_ASSETS)?.takeIf { it.version > current && it.asset(edition) != null }
    }

    private fun storedSwitchRelease(): PublishedRelease? = listOfNotNull(
        stored(KEY_SWITCH_VERSION, KEY_SWITCH_PAGE, KEY_SWITCH_ASSETS),
        stored(KEY_VERSION, KEY_PAGE, KEY_ASSETS),
    ).firstOrNull { it.version == installed && it.asset(edition.other) != null }

    companion object {
        const val PREFS = "update_check"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_ASKED = "last_asked"
        private const val KEY_VERSION = "newer_version"
        private const val KEY_PAGE = "newer_page"
        private const val KEY_NOTICED = "noticed_version"
        private const val KEY_ASSETS = "newer_assets"
        private const val KEY_SWITCH_VERSION = "switch_version"
        private const val KEY_SWITCH_PAGE = "switch_page"
        private const val KEY_SWITCH_ASSETS = "switch_assets"

        fun android(context: Context, runtime: RuntimeDependencies, log: (DiagnosticEvent) -> Unit): UpdateChecker {
            val app = context.applicationContext
            return UpdateChecker(
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), BuildConfig.UPDATE_FEED,
                BuildConfig.UPDATE_PAGES.split(',').map(String::trim).filter(String::isNotEmpty),
                BuildConfig.VERSION_NAME, BuildIdentity.current(), runtime.httpCalls, runtime.clocks,
                runtime.networkConditions, { DataSaverSettings(app).enabled }, runtime.dispatchers.io, log,
            )
        }
    }
}
