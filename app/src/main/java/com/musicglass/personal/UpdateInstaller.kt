package com.musicglass.personal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Feature 3: in-app updates and the edition switch (Q3). Only after the user confirms, with the edition and size in
 * front of them and a warning on metered data, the APK the feed names is streamed into private storage, then checked
 * (length, digest when listed, package, version, signer and the edition marker inside it) before Android's own
 * installer is handed the bytes and asks the user to confirm. Nothing installs by itself and nothing retries unasked.
 * Requests: the GitHub release asset and its download host only, with no cookies; see docs/privacy.md.
 */

internal enum class InstallKind { Update, Switch }

/** What to fetch: an update keeps the edition and raises the version; a switch keeps the version and changes the edition. */
internal data class InstallTarget(val kind: InstallKind, val edition: ReleaseEdition, val version: ReleaseVersion,
    val asset: ReleaseAsset, val page: String)

internal enum class InstallFailure { Offline, NoSpace, Unreachable, Refused, WrongSize, WrongDigest, Unreadable, NotTheApp,
    WrongSigner, WrongEdition, WrongVersion, InstallerRefused, Declined }

internal sealed interface InstallState {
    data object Idle : InstallState
    data class Confirm(val target: InstallTarget, val metered: Boolean) : InstallState
    /** Android's "Install unknown apps" access for Riff is off; [asked] once the user was sent to the setting. */
    data class NeedsAccess(val target: InstallTarget, val asked: Boolean = false, val denied: Boolean = false) : InstallState
    data class Downloading(val target: InstallTarget, val done: Long, val total: Long?) : InstallState
    data class Verifying(val target: InstallTarget) : InstallState
    data class Installing(val target: InstallTarget) : InstallState
    /** Android's confirmation screen, which the visible app opens. */
    data class AwaitingUser(val target: InstallTarget, val confirm: Intent) : InstallState
    data class Failed(val target: InstallTarget?, val reason: InstallFailure) : InstallState
}

/** What an APK file says about itself; [signers] are SHA-256 digests of the signing certificates. */
internal data class ApkFacts(val packageName: String, val versionCode: Long, val versionName: String?, val signers: Set<String>,
    val edition: String?)

internal data class InstalledApp(val packageName: String, val versionCode: Long, val version: ReleaseVersion,
    val edition: ReleaseEdition, val signers: Set<String>)

/**
 * Whether [facts] may replace [installed] as [target] asks. The two editions share package and signer, so the marker
 * is what tells them apart. An update must raise the version code in the same edition; a switch must keep the exact
 * version and change the edition. Null when every check passes.
 */
internal fun checkApk(facts: ApkFacts?, target: InstallTarget, installed: InstalledApp): InstallFailure? = when {
    facts == null -> InstallFailure.Unreadable
    facts.packageName != installed.packageName -> InstallFailure.NotTheApp
    installed.signers.isEmpty() || facts.signers != installed.signers -> InstallFailure.WrongSigner
    ReleaseEdition.ofMarker(facts.edition) != target.edition -> InstallFailure.WrongEdition
    ReleaseVersion.parseTag(facts.versionName) != target.version -> InstallFailure.WrongVersion
    target.kind == InstallKind.Update && target.edition != installed.edition -> InstallFailure.WrongEdition
    target.kind == InstallKind.Update && (facts.versionCode <= installed.versionCode || target.version <= installed.version) -> InstallFailure.WrongVersion
    target.kind == InstallKind.Switch && target.edition == installed.edition -> InstallFailure.WrongEdition
    target.kind == InstallKind.Switch && (facts.versionCode != installed.versionCode || target.version != installed.version) -> InstallFailure.WrongVersion
    else -> null
}

/** GitHub sends a release asset from one of these; a redirect anywhere else ends the transfer. */
internal val RELEASE_DOWNLOAD_HOSTS = setOf("objects.githubusercontent.com", "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com")

internal fun allowedDownloadHop(url: HttpUrl) = url.isHttps && url.host in RELEASE_DOWNLOAD_HOSTS && url.port == 443 &&
    url.username.isEmpty() && url.password.isEmpty()

/** Streams one release asset: redirects followed by hand, each hop checked; no cookies; the body bounded by the listed size. */
internal class ApkTransfer(client: Call.Factory) {
    private val client = (client as? OkHttpClient ?: OkHttpClient()).newBuilder()
        .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).build()
    @Volatile private var current: Call? = null

    fun cancel() { current?.cancel() }

    suspend fun fetch(asset: ReleaseAsset, file: File, progress: (Long) -> Unit): InstallFailure? {
        var address = asset.url?.toHttpUrlOrNull() ?: return InstallFailure.Refused
        for (hop in 0 until MAX_HOPS) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(address).get()
                .header("Accept", "application/octet-stream").header("User-Agent", "Riff-update").build()
            val call = client.newCall(request).also { current = it }
            val next: HttpUrl = try {
                call.execute().use { response ->
                    if (response.isRedirect) {
                        response.header("Location")?.let(address::resolve)?.takeIf(::allowedDownloadHop) ?: return InstallFailure.Refused
                    } else {
                        if (!response.isSuccessful) return InstallFailure.Unreachable
                        val length = response.body.contentLength()
                        if (length >= 0 && length != asset.bytes) return InstallFailure.WrongSize
                        return stream(response.body.byteStream(), asset, file, progress)
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                return InstallFailure.Unreachable
            } finally { current = null }
            address = next
        }
        return InstallFailure.Refused
    }

    private suspend fun stream(input: java.io.InputStream, asset: ReleaseAsset, file: File, progress: (Long) -> Unit): InstallFailure? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        FileOutputStream(file).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                done += read
                if (done > asset.bytes) return InstallFailure.WrongSize
                out.write(buffer, 0, read); digest.update(buffer, 0, read)
                progress(done)
            }
            out.fd.sync()
        }
        if (done != asset.bytes) return InstallFailure.WrongSize
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return if (asset.sha256 != null && asset.sha256 != sha) InstallFailure.WrongDigest else null
    }

    private companion object { const val MAX_HOPS = 6 }
}

/** Android's installer, behind a seam: tests record what would be committed. */
internal interface InstallSink {
    /** Writes [apk] into a new session and commits it; the result comes back to [UpdateInstallReceiver]. */
    fun commit(apk: File, target: InstallTarget): Int
    fun abandon(sessionId: Int)
}

/**
 * The one download and install at a time. State is Compose state, read by the About rows and the host dialogs.
 * Only the pending session's id, kind, edition and version outlive the process; the staged file never does.
 */
internal class UpdateInstaller(
    private val prefs: SharedPreferences,
    private val staging: File,
    private val transfer: ApkTransfer,
    private val network: () -> NetworkConditions,
    private val freeBytes: (File) -> Long,
    private val inspect: (File) -> ApkFacts?,
    private val installed: () -> InstalledApp,
    private val canInstall: () -> Boolean,
    private val sink: InstallSink,
    private val scope: CoroutineScope,
    private val log: (DiagnosticEvent) -> Unit,
) {
    var state by mutableStateOf<InstallState>(InstallState.Idle); private set
    private var job: Job? = null

    /** A transfer, a check or Android's installer is under way: a second request waits for it. */
    val busy: Boolean get() = state.let { it is InstallState.Downloading || it is InstallState.Verifying ||
        it is InstallState.Installing || it is InstallState.AwaitingUser }

    /** The user chose Update or the switch: show what it is first. Nothing is sent yet. */
    fun offer(target: InstallTarget) {
        if (busy) return
        state = InstallState.Confirm(target, metered = !network().unmetered)
    }

    /** Closes a question or a failure; a running transfer is stopped with [cancel] instead. */
    fun dismiss() { if (!busy) state = InstallState.Idle }

    fun confirm() {
        val target = (state as? InstallState.Confirm)?.target ?: (state as? InstallState.Failed)?.target ?: return
        if (busy) return
        if (!canInstall()) { state = InstallState.NeedsAccess(target); return }
        start(target)
    }

    /** The user went to Android's setting for Riff. */
    fun accessAsked() { (state as? InstallState.NeedsAccess)?.let { state = it.copy(asked = true, denied = false) } }

    /** Back in the app: the setting is read again. Allowed continues; still off says so and offers to ask again. */
    fun accessReturned() {
        val waiting = state as? InstallState.NeedsAccess ?: return
        if (canInstall()) start(waiting.target) else if (waiting.asked) state = waiting.copy(denied = true)
    }

    fun cancel() {
        if (state !is InstallState.Downloading && state !is InstallState.Verifying) return
        run++ // whatever the old transfer still does, it can no longer change the state or reach the installer
        transfer.cancel(); job?.cancel(); job = null
        state = InstallState.Idle
        log(DiagnosticEvent.parse("update_install result=Cancelled"))
    }

    /** Which start the running work belongs to; [cancel] moves it on. */
    @Volatile private var run = 0

    private fun start(target: InstallTarget) {
        if (!network().validated) return fail(target, InstallFailure.Offline)
        staging.mkdirs(); clearStaging()
        // Room for the staged copy and Android's own copy in the session, and a margin.
        if (freeBytes(staging) < target.asset.bytes * 2 + SPACE_MARGIN) return fail(target, InstallFailure.NoSpace)
        val token = ++run
        fun current() = token == run
        state = InstallState.Downloading(target, 0, target.asset.bytes)
        job = scope.launch {
            val file = File(staging, STAGED)
            try {
                var shown = -1L
                val failure = transfer.fetch(target.asset, file) { done ->
                    val step = done * 100 / target.asset.bytes
                    if (step != shown && current()) { shown = step; state = InstallState.Downloading(target, done, target.asset.bytes) }
                }
                if (!current()) return@launch
                if (failure != null) return@launch fail(target, failure)
                state = InstallState.Verifying(target)
                val problem = checkApk(inspect(file), target, installed())
                if (!current()) return@launch
                if (problem != null) return@launch fail(target, problem)
                state = InstallState.Installing(target)
                val session = sink.commit(file, target)
                prefs.edit().putInt(KEY_SESSION, session).putString(KEY_KIND, target.kind.name)
                    .putString(KEY_EDITION, target.edition.name).putString(KEY_VERSION, target.version.toString()).apply()
                log(DiagnosticEvent.parse("update_install result=Committed kind=${target.kind.name}"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (current()) fail(target, InstallFailure.InstallerRefused)
            } finally {
                file.delete()
            }
        }
    }

    /** What Android's installer said about the committed session ([UpdateInstallReceiver]). */
    fun sessionResult(status: Int, confirm: Intent?) {
        val target = currentTarget()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                if (confirm != null && target != null) state = InstallState.AwaitingUser(target, confirm)
            PackageInstaller.STATUS_SUCCESS -> { clearPending(); state = InstallState.Idle; log(DiagnosticEvent.parse("update_install result=Installed")) }
            PackageInstaller.STATUS_FAILURE_ABORTED -> { clearPending(); fail(target, InstallFailure.Declined) }
            else -> { clearPending(); fail(target, InstallFailure.InstallerRefused) }
        }
    }

    /** The visible app opened Android's confirmation screen. */
    fun confirmationShown() { (state as? InstallState.AwaitingUser)?.let { state = InstallState.Installing(it.target) } }

    /**
     * At start: a session left by an earlier process is closed. When the installed app is now what it asked for,
     * it worked; otherwise it is abandoned. A staged file is never left behind.
     */
    fun reconcile() {
        val session = prefs.getInt(KEY_SESSION, -1)
        if (session >= 0 && !busy) {
            val now = installed()
            val edition = prefs.getString(KEY_EDITION, null)?.let { name -> ReleaseEdition.entries.firstOrNull { it.name == name } }
            val version = ReleaseVersion.parseTag(prefs.getString(KEY_VERSION, null))
            val worked = edition == now.edition && version == now.version
            if (!worked) sink.abandon(session)
            clearPending()
            log(DiagnosticEvent.parse("update_install result=${if (worked) "Installed" else "Abandoned"}"))
        }
        if (!busy) clearStaging()
    }

    private fun currentTarget(): InstallTarget? = when (val s = state) {
        is InstallState.Installing -> s.target
        is InstallState.AwaitingUser -> s.target
        is InstallState.Verifying -> s.target
        is InstallState.Downloading -> s.target
        else -> null
    }

    private fun fail(target: InstallTarget?, reason: InstallFailure) {
        state = InstallState.Failed(target, reason)
        log(DiagnosticEvent.parse("update_install result=Failed reason=${reason.name}"))
    }

    private fun clearPending() { prefs.edit().remove(KEY_SESSION).remove(KEY_KIND).remove(KEY_EDITION).remove(KEY_VERSION).apply() }
    private fun clearStaging() { staging.listFiles()?.forEach { it.delete() } }

    companion object {
        const val PREFS = "update_install"
        private const val KEY_SESSION = "session"
        private const val KEY_KIND = "kind"
        private const val KEY_EDITION = "edition"
        private const val KEY_VERSION = "version"
        private const val STAGED = "staged.apk"
        internal const val SPACE_MARGIN = 32L * 1024 * 1024
        const val EDITION_META = "com.musicglass.personal.EDITION"

        fun android(context: Context, runtime: RuntimeDependencies, scope: CoroutineScope, log: (DiagnosticEvent) -> Unit): UpdateInstaller {
            val app = context.applicationContext
            return UpdateInstaller(
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), File(app.noBackupFilesDir, "update-staging"),
                ApkTransfer(runtime.httpCalls), runtime.networkConditions, { runtime.storageCapacity(it).availableBytes },
                { apkFacts(app, it) }, { installedApp(app) }, { app.packageManager.canRequestPackageInstalls() },
                AndroidInstallSink(app), scope, log,
            )
        }

        private fun signersOf(info: android.content.pm.PackageInfo): Set<String> =
            info.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()

        private const val FACT_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA

        fun apkFacts(context: Context, file: File): ApkFacts? = runCatching {
            val manager = context.packageManager
            val info = (if (Build.VERSION.SDK_INT >= 33) manager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(FACT_FLAGS.toLong()))
                else @Suppress("DEPRECATION") manager.getPackageArchiveInfo(file.path, FACT_FLAGS)) ?: return null
            ApkFacts(info.packageName, info.longVersionCode, info.versionName, signersOf(info),
                info.applicationInfo?.metaData?.getString(EDITION_META))
        }.getOrNull()

        fun installedApp(context: Context): InstalledApp {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                else @Suppress("DEPRECATION") manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            return InstalledApp(context.packageName, info.longVersionCode,
                ReleaseVersion.parseInstalled(BuildConfig.VERSION_NAME) ?: ReleaseVersion(0, 0, 0, null), ReleaseEdition.current, signersOf(info))
        }
    }
}

/** One PackageInstaller session, which asks the user: no FileProvider and no shared APK folder are involved. */
internal class AndroidInstallSink(private val context: Context) : InstallSink {
    override fun commit(apk: File, target: InstallTarget): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val result = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    override fun abandon(sessionId: Int) { runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) } }
}

/** Not exported: only the system, through Riff's own PendingIntent, reports a session here. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        AppContainer.get(context).installer.sessionResult(status, confirm)
    }
}
