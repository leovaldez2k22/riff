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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val plays by process.history.revision.collectAsStateWithLifecycle()
    val queues by process.snapshots.revision.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var earlier by remember { mutableStateOf<UnassignedInventory?>(null) }
    var preview by remember { mutableStateOf(ImportPreview(0, 0, false)) }
    var asking by remember { mutableStateOf<EarlierAction?>(null) }
    var working by remember { mutableStateOf(false) }
    val ownerKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val verified = ownerKey != UNVERIFIED_SCOPE_KEY
    val ownerLabel = when {
        !verified -> string(R.string.this_account)
        provider.accountScope() == AccountScope.Guest -> string(R.string.signed_out_listening_2)
        else -> provider.accountProfile?.name?.takeIf { it.isNotBlank() } ?: string(R.string.this_account)
    }
    LaunchedEffect(plays, queues, refresh, ownerKey) {
        val (found, importing) = withContext(process.runtime.dispatchers.io) {
            suspendCatching { process.stateReady.await(); legacy.inventory() to legacy.previewImport(ownerKey) }
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
                        onNotice(if (added == null) string(R.string.could_not_import_nothing_was_changed)
                            else quantity(R.plurals.n_plays_added_to_the_history_of, added, ownerLabel))
                    }
                    EarlierAction.DeletePlays -> {
                        val clean = withContext(process.runtime.dispatchers.io) { legacy.deleteUnassignedHistory() }
                        onNotice(if (clean) string(R.string.unassigned_plays_deleted) else string(R.string.some_unassigned_plays_could_not_be_deleted))
                    }
                    EarlierAction.RestoreQueue -> {
                        val owner = withContext(process.runtime.dispatchers.io) { legacy.adoptQuarantinedQueue(ownerKey) }
                        if (owner == null) onNotice(string(R.string.could_not_restore_the_earlier_queue_it))
                        else playback.restoreSavedQueue { loaded ->
                            onNotice(if (loaded) string(R.string.earlier_queue_restored_paused) else string(R.string.the_earlier_queue_is_saved_and_loads))
                        }
                    }
                    EarlierAction.DeleteQueue -> {
                        val gone = withContext(process.runtime.dispatchers.io) { legacy.deleteQuarantinedQueue() }
                        onNotice(if (gone) string(R.string.earlier_queue_deleted) else string(R.string.could_not_delete_the_earlier_queue_try))
                    }
                    EarlierAction.ImportSearches -> {
                        val added = searches.importLegacy(ownerKey)
                        onNotice(if (added == null) string(R.string.could_not_import_nothing_was_changed) else quantity(R.plurals.n_searches_added_for_n, added, ownerLabel))
                    }
                    EarlierAction.DeleteSearches ->
                        onNotice(if (searches.deleteLegacy()) string(R.string.earlier_searches_deleted) else string(R.string.could_not_delete_the_earlier_searches_try))
                }
            } finally { working = false; asking = null; refresh++ }
        }
    }
    asking?.let { action ->
        val found = earlier ?: UnassignedInventory()
        val (title, body, confirm) = when (action) {
            EarlierAction.ImportPlays -> Triple(quantity(R.plurals.import_n_plays, preview.kept),
                string(R.string.they_are_added_to_the_history_of, ownerLabel) +
                    (if (preview.kept < preview.available) string(R.string.only_the_newest_x_of_x_are, preview.kept, preview.available) else "") +
                    string(R.string.the_unassigned_copy_stays_until_you_delete), string(R.string.import_label))
            EarlierAction.DeletePlays -> Triple(quantity(R.plurals.delete_n_unassigned_plays, found.providerPlays),
                string(R.string.the_unassigned_copy_is_removed_from_this), string(R.string.delete))
            EarlierAction.RestoreQueue -> Triple(string(R.string.restore_the_earlier_queue),
                string(R.string.it_replaces_the_queue_you_have_now, ownerLabel), string(R.string.restore))
            EarlierAction.DeleteQueue -> Triple(string(R.string.delete_the_earlier_queue), string(R.string.it_is_removed_from_this_phone_and), string(R.string.delete))
            EarlierAction.ImportSearches -> Triple(quantity(R.plurals.import_n_searches, found.searches),
                string(R.string.they_are_added_to_the_recent_searches, ownerLabel, SEARCH_HISTORY_LIMIT), string(R.string.import_label))
            EarlierAction.DeleteSearches -> Triple(quantity(R.plurals.delete_n_earlier_searches, found.searches),
                string(R.string.they_are_removed_from_this_phone_and), string(R.string.delete))
        }
        FrostedAlertDialog(onDismissRequest = { if (!working) asking = null }, title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton(enabled = !working, onClick = { uiHaptic(HapticCue.Confirm); run(action) },
                modifier = Modifier.testTag("earlier_confirm")) { Text(confirm, color = Pink) } },
            dismissButton = { TextButton(enabled = !working, onClick = { asking = null }, modifier = Modifier.testTag("earlier_cancel")) { Text(string(R.string.cancel)) } })
    }
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.back), model::back) }
        item { ScreenTitle(string(R.string.earlier_data), string(R.string.saved_before_riff_kept_history_and_queues)) }
        val found = earlier
        when {
            found == null -> item { Text(string(R.string.checking), color = Muted) }
            found.empty -> item { Text(string(R.string.nothing_from_earlier_versions_is_waiting_for), color = Muted, modifier = Modifier.testTag("earlier_empty")) }
        }
        if (found != null && found.sources > 0) item {
            SettingsSection(string(R.string.listening_history)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(quantity(R.plurals.n_youtube_music_plays_were_saved_in, found.providerPlays),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_history_summary"))
                    when {
                        found.providerPlays == 0 -> Unit
                        !verified -> Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        preview.alreadyImported -> Text(string(R.string.already_imported_into_the_history_of_x, ownerLabel), color = Muted, fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp).testTag("earlier_imported"))
                        else -> TextButton({ uiHaptic(); asking = EarlierAction.ImportPlays }, modifier = Modifier.testTag("earlier_import")) {
                            Text(string(R.string.import_into_the_history_of_x, ownerLabel), color = Pink)
                        }
                    }
                    TextButton({ uiHaptic(); asking = EarlierAction.DeletePlays }, modifier = Modifier.testTag("earlier_delete_history")) {
                        Text(string(R.string.delete_unassigned_plays), color = Muted)
                    }
                }
            }
        }
        if (found != null && found.searches > 0) item {
            SettingsSection(string(R.string.searches)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(quantity(R.plurals.n_recent_searches_were_saved_in_one, found.searches),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_search_summary"))
                    if (verified) TextButton({ uiHaptic(); asking = EarlierAction.ImportSearches }, modifier = Modifier.testTag("earlier_import_searches")) {
                        Text(string(R.string.import_for_x, ownerLabel), color = Pink)
                    } else Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton({ uiHaptic(); asking = EarlierAction.DeleteSearches }, modifier = Modifier.testTag("earlier_delete_searches")) {
                        Text(string(R.string.delete_earlier_searches), color = Muted)
                    }
                }
            }
        }
        if (found != null && found.queueItems > 0) item {
            SettingsSection(string(R.string.queue)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(quantity(R.plurals.a_queue_of_n_songs_was_saved, found.queueItems),
                        color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("earlier_queue_summary"))
                    if (verified) TextButton({ uiHaptic(); asking = EarlierAction.RestoreQueue }, modifier = Modifier.testTag("earlier_restore_queue")) {
                        Text(string(R.string.restore_it_paused), color = Pink)
                    } else Text(VERIFY_TO_SAVE_NOTICE, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    TextButton({ uiHaptic(); asking = EarlierAction.DeleteQueue }, modifier = Modifier.testTag("earlier_delete_queue")) {
                        Text(string(R.string.delete_earlier_queue), color = Muted)
                    }
                }
            }
        }
    }
}
