package com.musicglass.personal

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Feature 3: the installer's questions and progress, over whatever screen is showing. Nothing is shown while Idle. */
@Composable internal fun UpdateInstallHost(installer: UpdateInstaller, pageHosts: Collection<String>) {
    val context = LocalContext.current
    val state = installer.state
    // Android's confirmation screen must come from the visible app.
    LaunchedEffect(state) {
        val waiting = state as? InstallState.AwaitingUser ?: return@LaunchedEffect
        val opened = runCatching { context.startActivity(waiting.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) installer.confirmationShown() else installer.sessionResult(PackageInstaller.STATUS_FAILURE, null)
    }
    // Back from Android's "Install unknown apps" setting: read it again.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) installer.accessReturned() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    fun name(target: InstallTarget) = "${target.edition.title} ${target.version}"
    when (state) {
        is InstallState.Confirm -> FrostedAlertDialog(onDismissRequest = installer::dismiss,
            title = { Text(string(R.string.update_install_title, name(state.target))) },
            text = {
                Column(Modifier.testTag("update_install_prompt")) {
                    Text(string(R.string.update_install_body, size(state.target.asset.bytes)))
                    if (state.metered) { Spacer(Modifier.height(8.dp)); Text(string(R.string.update_install_metered)) }
                }
            },
            confirmButton = { TextButton(onClick = installer::confirm, modifier = Modifier.testTag("update_install_confirm")) {
                Text(string(R.string.update_install_download), color = Pink) } },
            dismissButton = { TextButton(onClick = installer::dismiss) { Text(string(R.string.cancel)) } })
        is InstallState.NeedsAccess -> FrostedAlertDialog(onDismissRequest = installer::dismiss,
            title = { Text(string(R.string.update_install_access_title)) },
            text = { Text(string(if (state.denied) R.string.update_install_access_denied else R.string.update_install_access_body),
                modifier = Modifier.testTag("update_install_access")) },
            confirmButton = { TextButton(onClick = {
                installer.accessAsked()
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(string(R.string.update_install_open_settings), color = Pink) } },
            dismissButton = { TextButton(onClick = installer::dismiss) { Text(string(R.string.cancel)) } })
        is InstallState.Downloading, is InstallState.Verifying -> {
            val target = (state as? InstallState.Downloading)?.target ?: (state as InstallState.Verifying).target
            FrostedAlertDialog(onDismissRequest = {},
                title = { Text(string(R.string.update_install_downloading, name(target))) },
                text = {
                    Column(Modifier.testTag("update_install_progress")) {
                        if (state is InstallState.Downloading && state.total != null && state.total > 0) {
                            LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(string(R.string.update_install_progress, size(state.done), size(state.total)))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(string(R.string.update_install_checking))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = installer::cancel, modifier = Modifier.testTag("update_install_cancel")) {
                    Text(string(R.string.cancel)) } })
        }
        is InstallState.Failed -> FrostedAlertDialog(onDismissRequest = installer::dismiss,
            title = { Text(string(R.string.update_install_failed_title)) },
            text = { Text(installFailureText(state.reason), modifier = Modifier.testTag("update_install_failed")) },
            confirmButton = {
                val target = state.target
                if (target != null && state.reason.retryable) TextButton(onClick = installer::confirm) { Text(string(R.string.try_again), color = Pink) }
                else if (target != null) TextButton(onClick = { installer.dismiss(); openWebPage(context, target.page, pageHosts) }) {
                    Text(string(R.string.update_install_release_page), color = Pink) }
                else TextButton(onClick = installer::dismiss) { Text(string(R.string.close)) }
            },
            dismissButton = if (state.target != null) ({ TextButton(onClick = installer::dismiss) { Text(string(R.string.close)) } }) else null)
        else -> Unit
    }
}

/** Failures a second attempt can fix; the others mean the file or the release is wrong, so the page is offered. */
internal val InstallFailure.retryable: Boolean get() = this in setOf(InstallFailure.Offline, InstallFailure.Unreachable,
    InstallFailure.NoSpace, InstallFailure.Declined, InstallFailure.InstallerRefused)

internal fun installFailureText(reason: InstallFailure): String = string(when (reason) {
    InstallFailure.Offline -> R.string.update_install_offline
    InstallFailure.NoSpace -> R.string.update_install_no_space
    InstallFailure.Unreachable -> R.string.update_install_unreachable
    InstallFailure.Refused -> R.string.update_install_refused
    InstallFailure.WrongSize, InstallFailure.WrongDigest, InstallFailure.Unreadable, InstallFailure.NotTheApp,
    InstallFailure.WrongSigner, InstallFailure.WrongEdition, InstallFailure.WrongVersion -> R.string.update_install_mismatch
    InstallFailure.Declined -> R.string.update_install_declined
    InstallFailure.InstallerRefused -> R.string.update_install_installer
})
