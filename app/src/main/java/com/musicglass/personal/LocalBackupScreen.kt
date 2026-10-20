package com.musicglass.personal

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

/**
 * The system folder picker, opening at the backup folder. The folder chosen is kept with read and write access,
 * restored from, and from then on written to; null when the user backs out.
 */
@Composable internal fun rememberBackupRestore(onNotice: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) { AppContainer.get(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree == null) { container.localBackup.restoreDeclined(); return@rememberLauncherForActivityResult }
        scope.launch {
            val outcome = suspendCatching {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                container.localBackup.restore(PickedBackupFolder(context.contentResolver, tree), tree.toString())
            }.getOrNull()
            onNotice(restoreText(outcome))
            if (outcome != null && outcome.restored > 0) { uiHaptic(HapticCue.Confirm); container.backUpSoon(force = true) }
        }
    }
    return { runCatching { launcher.launch(PickedBackupFolder.initialUri(backupFolderName(context.packageName))) } }
}

internal fun restoreText(outcome: RestoreOutcome?): String = when {
    outcome == null -> string(R.string.riff_could_not_read_that_folder)
    outcome.found == 0 -> string(R.string.no_riff_backup_in_that_folder)
    else -> listOfNotNull(
        if (outcome.restored > 0) string(R.string.backup_restored_x, describeCounts(outcome.added)) else null,
        outcome.waiting.takeIf { it > 0 }?.let { quantity(R.plurals.n_accounts_restore_when_they_sign_in, it) },
        outcome.failed.takeIf { it > 0 }?.let { quantity(R.plurals.n_backup_files_could_not_be_read, it) },
    ).joinToString(" ").ifEmpty { string(R.string.nothing_new_in_the_backup) }
}

/** Settings → Privacy and data: the backup on this phone. */
@Composable internal fun LocalBackupSection(onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) { AppContainer.get(context) }
    val backup = container.localBackup
    val restore = rememberBackupRestore(onNotice)
    var turningOff by remember { mutableStateOf(false) }
    if (turningOff) FrostedAlertDialog(onDismissRequest = { turningOff = false }, title = { Text(string(R.string.turn_off_the_backup)) },
        text = { Text(string(R.string.the_backup_files_riff_made_in_x_are_removed, backup.place)) },
        confirmButton = { TextButton({ turningOff = false; scope.launch { backup.setEnabled(false); onNotice(string(R.string.backup_turned_off)) } },
            modifier = Modifier.testTag("backup_off_confirm")) { Text(string(R.string.turn_off), color = Pink) } },
        dismissButton = { TextButton({ turningOff = false }) { Text(string(R.string.cancel)) } })
    SettingsSection(string(R.string.backup_on_this_phone)) {
        val last = backup.lastBackupAt.takeIf { it > 0 }?.let {
            android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
        }
        SettingsToggleRow(string(R.string.keep_a_backup_on_this_phone), backup.enabled, { on ->
            if (on) scope.launch { backup.setEnabled(true); container.backUpSoon(force = true) } else turningOff = true
        }, Icons.Rounded.Backup, "backup_enabled",
            subtitle = if (!backup.enabled) string(R.string.off) else listOfNotNull(backup.place, last?.let { string(R.string.last_backup_x, it) },
                backup.waiting.takeIf { it > 0 }?.let { quantity(R.plurals.n_accounts_restore_when_they_sign_in, it) }).joinToString(" · "))
        if (backup.enabled) {
            SettingsDivider()
            SettingsLinkRow(string(R.string.back_up_now), null, Icons.Rounded.Backup, "backup_now") {
                scope.launch {
                    onNotice(when (val outcome = backup.backUp(force = true)) {
                        is BackupOutcome.Done -> string(R.string.backed_up_to_x, backup.place)
                        else -> string(R.string.the_backup_could_not_be_written).also { if (outcome is BackupOutcome.Failed) uiHaptic(HapticCue.Reject) }
                    })
                }
            }
        }
        SettingsDivider()
        SettingsLinkRow(string(R.string.restore_from_a_backup), null, Icons.Rounded.Restore, "backup_restore", restore)
        Text(string(R.string.the_backup_stays_when_riff_is_deleted), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
    }
}

/** On the first start of a new install with nothing in it: offer the backup an earlier install may have left. */
@Composable internal fun RestoreOffer(onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val backup = remember(context) { AppContainer.get(context).localBackup }
    var offer by remember { mutableStateOf(false) }
    LaunchedEffect(backup) { offer = suspendCatching { backup.shouldOfferRestore() }.getOrDefault(false) }
    val restore = rememberBackupRestore(onNotice)
    if (offer) FrostedAlertDialog(onDismissRequest = { offer = false; backup.restoreDeclined() },
        title = { Text(string(R.string.restore_your_riff_backup)) },
        text = { Text(string(R.string.if_you_used_riff_on_this_phone_before_x, backup.place)) },
        confirmButton = { TextButton({ offer = false; restore() }, modifier = Modifier.testTag("restore_offer_choose")) {
            Text(string(R.string.choose_the_folder), color = Pink) } },
        dismissButton = { TextButton({ offer = false; backup.restoreDeclined() }, modifier = Modifier.testTag("restore_offer_not_now")) {
            Text(string(R.string.not_now)) } })
}
