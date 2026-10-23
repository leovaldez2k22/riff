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
@Composable internal fun rememberBackupRestore(onNotice: (String) -> Unit, onRestored: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val accessibility = LocalAccessibilitySettings.current
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
            if (outcome != null && outcome.restored > 0) {
                readAgainAfterRestore(context, accessibility); onRestored()
                uiHaptic(HapticCue.Confirm); container.backUpSoon(force = true)
            }
        }
    }
    return { runCatching { launcher.launch(PickedBackupFolder.initialUri(backupFolderName(context.packageName))) } }
}

/** Q18-078: what is on screen is read again from what a restore stored, as after an import. */
internal fun readAgainAfterRestore(context: android.content.Context, accessibility: AccessibilitySettings) {
    accessibility.reload(); CrossfadeSettings.get(context).reload()
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

/** Q18-102: the backup's actions that ask first, besides turning it off. */
internal enum class BackupAsk { TurnOn, BackUpNow, Restore }

/** Settings → Privacy and data: the backup on this phone. Every action asks first; cancelling changes nothing. */
@Composable internal fun LocalBackupSection(onNotice: (String) -> Unit, onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = remember(context) { AppContainer.get(context) }
    val backup = container.localBackup
    val restore = rememberBackupRestore(onNotice, onRestored)
    var turningOff by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf<BackupAsk?>(null) }
    var leftovers by remember { mutableIntStateOf(0) }
    if (leftovers > 0) FrostedAlertDialog(onDismissRequest = { leftovers = 0 }, title = { Text(string(R.string.remove_older_backup_files)) },
        text = { Text(quantity(R.plurals.n_older_backup_files_are_still_in_x, leftovers, backup.place)) },
        confirmButton = { TextButton({ leftovers = 0; scope.launch { onNotice(quantity(R.plurals.n_backup_files_removed, backup.removeLeftovers())) } },
            modifier = Modifier.testTag("backup_leftovers_remove")) { Text(string(R.string.remove), color = Pink) } },
        dismissButton = { TextButton({ leftovers = 0 }, modifier = Modifier.testTag("backup_leftovers_keep")) { Text(string(R.string.keep)) } })
    if (turningOff) FrostedAlertDialog(onDismissRequest = { turningOff = false }, title = { Text(string(R.string.turn_off_the_backup)) },
        text = { Text(string(R.string.the_backup_files_riff_made_in_x_are_removed, backup.place)) },
        confirmButton = { TextButton({ turningOff = false; scope.launch {
            backup.turnBackup(false); onNotice(string(R.string.backup_turned_off))
            leftovers = suspendCatching { backup.leftovers().size }.getOrDefault(0)
        } },
            modifier = Modifier.testTag("backup_off_confirm")) { Text(string(R.string.turn_off), color = Pink) } },
        dismissButton = { TextButton({ turningOff = false }, modifier = Modifier.testTag("backup_off_cancel")) { Text(string(R.string.cancel)) } })
    asking?.let { ask ->
        val (title, body, confirm) = when (ask) {
            BackupAsk.TurnOn -> Triple(string(R.string.keep_a_backup_on_this_phone_q), string(R.string.keep_a_backup_body, backup.place), string(R.string.turn_on))
            BackupAsk.BackUpNow -> Triple(string(R.string.back_up_now_q), string(R.string.back_up_now_body, backup.place), string(R.string.back_up))
            BackupAsk.Restore -> Triple(string(R.string.restore_from_a_backup_q), string(R.string.restore_from_a_backup_body), string(R.string.choose_the_folder))
        }
        FrostedAlertDialog(onDismissRequest = { asking = null }, title = { Text(title) },
            text = { Text(body, modifier = Modifier.testTag("backup_ask")) },
            confirmButton = { TextButton({ asking = null; uiHaptic(HapticCue.Confirm); when (ask) {
                BackupAsk.TurnOn -> scope.launch { backup.turnBackup(true); container.backUpSoon(force = true) }
                BackupAsk.BackUpNow -> scope.launch {
                    onNotice(when (val outcome = backup.backUp(force = true)) {
                        is BackupOutcome.Done -> string(R.string.backed_up_to_x, backup.place)
                        else -> string(R.string.the_backup_could_not_be_written).also { if (outcome is BackupOutcome.Failed) uiHaptic(HapticCue.Reject) }
                    })
                }
                BackupAsk.Restore -> restore()
            } }, modifier = Modifier.testTag("backup_ask_confirm")) { Text(confirm, color = Pink) } },
            dismissButton = { TextButton({ asking = null }, modifier = Modifier.testTag("backup_ask_cancel")) { Text(string(R.string.cancel)) } })
    }
    SettingsSection(string(R.string.backup_on_this_phone)) {
        val last = backup.lastBackupAt.takeIf { it > 0 }?.let {
            android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
        }
        SettingsToggleRow(string(R.string.keep_a_backup_on_this_phone), backup.enabled, { on ->
            if (on) asking = BackupAsk.TurnOn else turningOff = true
        }, Icons.Rounded.Backup, "backup_enabled",
            subtitle = if (!backup.enabled) string(R.string.off) else listOfNotNull(backup.place, last?.let { string(R.string.last_backup_x, it) },
                backup.waiting.takeIf { it > 0 }?.let { quantity(R.plurals.n_accounts_restore_when_they_sign_in, it) }).joinToString(" · "))
        if (backup.enabled) {
            SettingsDivider()
            SettingsLinkRow(string(R.string.back_up_now), null, Icons.Rounded.Backup, "backup_now") { asking = BackupAsk.BackUpNow }
        }
        SettingsDivider()
        SettingsLinkRow(string(R.string.restore_from_a_backup), null, Icons.Rounded.Restore, "backup_restore") { asking = BackupAsk.Restore }
        Text(string(R.string.the_backup_stays_when_riff_is_deleted), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
    }
}

/** On the first start of a new install with nothing in it: offer the backup an earlier install may have left. */
@Composable internal fun RestoreOffer(onNotice: (String) -> Unit, onRestored: () -> Unit = {}) {
    val context = LocalContext.current
    val backup = remember(context) { AppContainer.get(context).localBackup }
    var offer by remember { mutableStateOf(false) }
    LaunchedEffect(backup) { offer = suspendCatching { backup.shouldOfferRestore() }.getOrDefault(false) }
    val restore = rememberBackupRestore(onNotice, onRestored)
    if (offer) FrostedAlertDialog(onDismissRequest = { offer = false; backup.restoreDeclined() },
        title = { Text(string(R.string.restore_your_riff_backup)) },
        text = { Text(string(R.string.if_you_used_riff_on_this_phone_before_x, backup.place)) },
        confirmButton = { TextButton({ offer = false; restore() }, modifier = Modifier.testTag("restore_offer_choose")) {
            Text(string(R.string.choose_the_folder), color = Pink) } },
        dismissButton = { TextButton({ offer = false; backup.restoreDeclined() }, modifier = Modifier.testTag("restore_offer_not_now")) {
            Text(string(R.string.not_now)) } })
}
