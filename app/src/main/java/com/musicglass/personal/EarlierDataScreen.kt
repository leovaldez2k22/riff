package com.musicglass.personal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EarlierAction { ImportPlays, DeletePlays, RestoreQueue, DeleteQueue, ImportSearches, DeleteSearches }

/**
 * History and the queue saved before Riff kept data per account. Nothing here happens without a
 * confirmation, and nothing is sent to YouTube Music.
 */
@Composable internal fun EarlierDataScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current)) {
    val scope = rememberCoroutineScope()
    val legacy = process.legacyState
    val context = LocalContext.current
    val searches = remember(context) { SearchHistory.get(context) }
    val plays by process.history.revision.collectAsState()
    val queues by process.snapshots.revision.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var earlier by remember { mutableStateOf<UnassignedInventory?>(null) }
    var preview by remember { mutableStateOf(ImportPreview(0, 0, false)) }
    var asking by remember { mutableStateOf<EarlierAction?>(null) }
    var working by remember { mutableStateOf(false) }
    val ownerKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val verified = ownerKey != UNVERIFIED_SCOPE_KEY
    val ownerLabel = when {
        !verified -> "this account"
        provider.accountScope() == AccountScope.Guest -> "signed-out listening"
        else -> provider.accountProfile?.name?.takeIf { it.isNotBlank() } ?: "this account"
    }
    LaunchedEffect(plays, queues, refresh, ownerKey) {
        val (found, importing) = withContext(process.runtime.dispatchers.io) {
            runCatching { process.stateReady.await(); legacy.inventory() to legacy.previewImport(ownerKey) }
                .getOrDefault(UnassignedInventory() to ImportPreview(0, 0, false))
        }
        earlier = found; preview = importing
    }
    fun run(action: EarlierAction) {
        working = true
        scope.launch {
            try {
                when (action) {
                    EarlierAction.ImportPlays -> {
                        val added = withContext(process.runtime.dispatchers.io) { legacy.importProviderHistory(ownerKey) }
                        onNotice(if (added == null) "Could not import. Nothing was changed."
                            else "$added ${if (added == 1) "play" else "plays"} added to the history of $ownerLabel")
                    }
                    EarlierAction.DeletePlays -> {
                        val clean = withContext(process.runtime.dispatchers.io) { legacy.deleteUnassignedHistory() }
                        onNotice(if (clean) "Unassigned plays deleted" else "Some unassigned plays could not be deleted. Try again.")
                    }
                    EarlierAction.RestoreQueue -> {
                        val owner = withContext(process.runtime.dispatchers.io) { legacy.adoptQuarantinedQueue(ownerKey) }
                        if (owner == null) onNotice("Could not restore the earlier queue. It was kept.")
                        else playback.restoreSavedQueue { loaded ->
                            onNotice(if (loaded) "Earlier queue restored, paused" else "The earlier queue is saved and loads the next time Riff starts")
                        }
                    }
                    EarlierAction.DeleteQueue -> {
                        val gone = withContext(process.runtime.dispatchers.io) { legacy.deleteQuarantinedQueue() }
                        onNotice(if (gone) "Earlier queue deleted" else "Could not delete the earlier queue. Try again.")
                    }
                    EarlierAction.ImportSearches -> {
                        val added = searches.importLegacy(ownerKey)
                        onNotice(if (added == null) "Could not import. Nothing was changed." else "${plural(added, "search", "searches")} added for $ownerLabel")
                    }
                    EarlierAction.DeleteSearches ->
                        onNotice(if (searches.deleteLegacy()) "Earlier searches deleted" else "Could not delete the earlier searches. Try again.")
                }
            } finally { working = false; asking = null; refresh++ }
        }
    }
    asking?.let { action ->
        val found = earlier ?: UnassignedInventory()
        val (title, body, confirm) = when (action) {
            EarlierAction.ImportPlays -> Triple("Import ${preview.kept} ${if (preview.kept == 1) "play" else "plays"}?",
                "They are added to the history of $ownerLabel on this phone. If more than one account was used here, " +
                    "plays from the other accounts are included, and they cannot be separated afterwards." +
                    (if (preview.kept < preview.available) " Only the newest ${preview.kept} of ${preview.available} are kept." else "") +
                    " The unassigned copy stays until you delete it.", "Import")
            EarlierAction.DeletePlays -> Triple("Delete ${found.providerPlays} unassigned ${if (found.providerPlays == 1) "play" else "plays"}?",
                "The unassigned copy is removed from this phone and cannot be recovered. Plays you already imported stay. Nothing changes in YouTube Music.", "Delete")
            EarlierAction.RestoreQueue -> Triple("Restore the earlier queue?",
                "It replaces the queue you have now and loads paused, as the queue of $ownerLabel. It may have been made while another account was signed in.", "Restore")
            EarlierAction.DeleteQueue -> Triple("Delete the earlier queue?", "It is removed from this phone and cannot be recovered.", "Delete")
            EarlierAction.ImportSearches -> Triple("Import ${plural(found.searches, "search", "searches")}?",
                "They are added to the recent searches of $ownerLabel on this phone. If more than one account was used here, " +
                    "searches made in the other accounts are included. Riff keeps the newest ${SEARCH_HISTORY_LIMIT} for each account.", "Import")
            EarlierAction.DeleteSearches -> Triple("Delete ${plural(found.searches, "earlier search", "earlier searches")}?",
                "They are removed from this phone and cannot be recovered. Nothing changes in YouTube Music.", "Delete")
        }
        FrostedAlertDialog(onDismissRequest = { if (!working) asking = null }, title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton(enabled = !working, onClick = { uiHaptic(HapticCue.Confirm); run(action) },
                modifier = Modifier.testTag("earlier_confirm")) { Text(confirm, color = Pink) } },
            dismissButton = { TextButton(enabled = !working, onClick = { asking = null }, modifier = Modifier.testTag("earlier_cancel")) { Text("Cancel") } })
    }
    ScreenList(model, bottom) {
        item { BackHeading("Back", model::back) }
        item { ScreenTitle("Earlier data", "Saved before Riff kept history and queues separately for each account.") }
        val found = earlier
        when {
            found == null -> item { Text("Checking…", color = Muted) }
            found.empty -> item { Text("Nothing from earlier versions is waiting for a decision.", color = Muted, modifier = Modifier.testTag("earlier_empty")) }
        }
        if (found != null && found.sources > 0) item {
            SettingsSection("Listening history") {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${found.providerPlays} YouTube Music ${if (found.providerPlays == 1) "play was" else "plays were"} saved in one list for every " +
                        "account used on this phone, so Riff cannot tell whose they are. They are not shown in History unless you import them. " +
                        "Plays of audio stored on this phone were moved to History → On this phone.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_history_summary"))
                    when {
                        found.providerPlays == 0 -> Unit
                        !verified -> Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        preview.alreadyImported -> Text("Already imported into the history of $ownerLabel.", color = Muted, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp).testTag("earlier_imported"))
                        else -> TextButton({ uiHaptic(); asking = EarlierAction.ImportPlays }, modifier = Modifier.testTag("earlier_import")) {
                            Text("Import into the history of $ownerLabel", color = Pink)
                        }
                    }
                    TextButton({ uiHaptic(); asking = EarlierAction.DeletePlays }, modifier = Modifier.testTag("earlier_delete_history")) {
                        Text("Delete unassigned plays", color = Muted)
                    }
                }
            }
        }
        if (found != null && found.searches > 0) item {
            SettingsSection("Searches") {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("${plural(found.searches, "recent search", "recent searches")} ${if (found.searches == 1) "was" else "were"} saved in one list for every account " +
                        "used on this phone. They are not shown in Search unless you import them.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_search_summary"))
                    if (verified) TextButton({ uiHaptic(); asking = EarlierAction.ImportSearches }, modifier = Modifier.testTag("earlier_import_searches")) {
                        Text("Import for $ownerLabel", color = Pink)
                    } else Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton({ uiHaptic(); asking = EarlierAction.DeleteSearches }, modifier = Modifier.testTag("earlier_delete_searches")) {
                        Text("Delete earlier searches", color = Muted)
                    }
                }
            }
        }
        if (found != null && found.queueItems > 0) item {
            SettingsSection("Queue") {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("A queue of ${found.queueItems} ${if (found.queueItems == 1) "song" else "songs"} was saved by an earlier version. " +
                        "It was not loaded automatically because it may belong to another account.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_queue_summary"))
                    if (verified) TextButton({ uiHaptic(); asking = EarlierAction.RestoreQueue }, modifier = Modifier.testTag("earlier_restore_queue")) {
                        Text("Restore it, paused", color = Pink)
                    } else Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton({ uiHaptic(); asking = EarlierAction.DeleteQueue }, modifier = Modifier.testTag("earlier_delete_queue")) {
                        Text("Delete earlier queue", color = Muted)
                    }
                }
            }
        }
    }
}
