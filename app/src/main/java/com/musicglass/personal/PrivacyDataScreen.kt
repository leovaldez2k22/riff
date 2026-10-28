package com.musicglass.personal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal enum class PrivacyAction { ClearAccountHistory, ClearDeviceHistory, ClearSearches, DeleteDownloads, DeleteAccountData, ShortenRetention,
    HistoryOff, HistoryOn, ChangeRetention }


/** How the owner is named in confirmations: the account's name, or what signed-out use is called elsewhere. */
internal fun ownerLabel(provider: YouTubeMusicProvider, ownerKey: String): String = when {
    ownerKey == UNVERIFIED_SCOPE_KEY -> string(R.string.this_account)
    provider.accountScope() == AccountScope.Guest -> string(R.string.signed_out_listening_2)
    else -> provider.accountProfile?.name?.takeIf { it.isNotBlank() } ?: string(R.string.this_account)
}

internal data class PrivacyPrompt(val title: String, val body: String, val confirm: String)

/**
 * Each confirmation says whose data, that it cannot be undone, and that YouTube Music is not changed. Q18-102: every
 * action here asks first, also the history switch and a retention that removes nothing (retentionDays 0: until cleared).
 */
internal fun privacyPrompt(action: PrivacyAction, owner: String, data: LocalDataSummary, retentionDays: Int = 0, retentionCount: Int = 0): PrivacyPrompt = when (action) {
    PrivacyAction.ClearAccountHistory -> PrivacyPrompt(quantity(R.plurals.clear_n_plays, data.accountPlays),
        string(R.string.removes_the_listening_history_of_x_from, owner), string(R.string.clear))
    PrivacyAction.ClearDeviceHistory -> PrivacyPrompt(quantity(R.plurals.clear_n_plays, data.devicePlays),
        string(R.string.removes_the_history_of_audio_stored_on), string(R.string.clear))
    PrivacyAction.ClearSearches -> PrivacyPrompt(quantity(R.plurals.clear_n_searches, data.searches),
        string(R.string.removes_the_recent_searches_of_x_from, owner), string(R.string.clear))
    PrivacyAction.DeleteDownloads -> PrivacyPrompt(quantity(R.plurals.delete_n_downloads, data.downloads),
        string(R.string.removes_the_downloaded_songs_artwork_and_lyr, owner), string(R.string.delete))
    PrivacyAction.DeleteAccountData -> PrivacyPrompt(string(R.string.delete_local_data),
        string(R.string.removes_everything_riff_keeps_on_this_phone, owner), string(R.string.delete))
    PrivacyAction.ShortenRetention -> PrivacyPrompt(string(R.string.keep_history_for_x, retentionLabel(retentionDays).lowercase()),
        quantity(R.plurals.n_older_plays_will_be_removed_now, retentionCount), string(R.string.remove_and_keep_x, retentionLabel(retentionDays).lowercase()))
    PrivacyAction.HistoryOff -> PrivacyPrompt(string(R.string.stop_saving_listening_history),
        string(R.string.stop_saving_listening_history_body), string(R.string.turn_off))
    PrivacyAction.HistoryOn -> PrivacyPrompt(string(R.string.start_saving_listening_history),
        string(R.string.start_saving_listening_history_body), string(R.string.turn_on))
    PrivacyAction.ChangeRetention -> PrivacyPrompt(string(R.string.keep_history_for_x, retentionLabel(retentionDays).lowercase()),
        if (retentionDays == 0) string(R.string.keep_history_until_cleared_body) else string(R.string.keep_history_for_x_body, retentionLabel(retentionDays).lowercase()),
        string(R.string.keep_x, retentionLabel(retentionDays).lowercase()))
}

internal fun retentionLabel(days: Int?) = when (days) { 30 -> string(R.string.n_30_days); 90 -> string(R.string.n_90_days); 365 -> string(R.string.n_1_year); else -> string(R.string.until_cleared) }

/** What to tell the user once an action has run. Partial and failed outcomes never read as success. */
internal fun privacyOutcome(action: PrivacyAction, result: DataActionResult, retentionDays: Int = 0): String = when (result) {
    is DataActionResult.Done -> when (action) {
        PrivacyAction.ClearAccountHistory, PrivacyAction.ClearDeviceHistory -> quantity(R.plurals.n_plays_cleared, result.removed)
        PrivacyAction.ClearSearches -> quantity(R.plurals.n_searches_cleared, result.removed)
        PrivacyAction.DeleteDownloads -> quantity(R.plurals.n_downloads_deleted, result.removed)
        PrivacyAction.DeleteAccountData -> string(R.string.local_data_for_this_account_deleted)
        PrivacyAction.ShortenRetention -> quantity(R.plurals.n_older_plays_removed, result.removed)
        PrivacyAction.HistoryOff -> string(R.string.listening_history_is_off)
        PrivacyAction.HistoryOn -> string(R.string.listening_history_is_on)
        PrivacyAction.ChangeRetention -> if (retentionDays == 0) string(R.string.history_is_kept_until_you_clear_it)
            else string(R.string.history_is_kept_for_x, retentionLabel(retentionDays).lowercase())
    }
    is DataActionResult.Partial -> string(R.string.not_everything_could_be_removed_still_on, result.remaining.joinToString(", ") { keyLabel(it) })
    is DataActionResult.Failed -> string(R.string.nothing_was_removed_riff_could_not_change)
}

/**
 * Settings → Privacy and data. Everything here acts on this phone only. Every action asks first, and clearing
 * and deleting name the owner they apply to; cancelling changes nothing.
 */
@Composable internal fun PrivacyDataScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider, collections: LocalCollections,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current),
    data: LocalDataController = process.localData, transfer: LocalDataTransfer = process.localTransfer,
    documents: DocumentPicker = rememberSystemDocuments(), onSettingsImported: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plays by process.history.revision.collectAsStateWithLifecycle()
    val queues by process.snapshots.revision.collectAsStateWithLifecycle()
    val downloadsRevision by remember { OfflineMusicStore.get(context).revision }.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val ownerKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val verified = ownerKey != UNVERIFIED_SCOPE_KEY
    val owner = ownerLabel(provider, ownerKey)
    var summary by remember { mutableStateOf<LocalDataSummary?>(null) }
    var earlier by remember { mutableStateOf(UnassignedInventory()) }
    var retention by remember { mutableStateOf(historyRetentionDays(context)) }
    var asking by remember { mutableStateOf<PrivacyAction?>(null) }
    var pendingRetention by remember { mutableIntStateOf(0) }
    var pendingRetentionCount by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(plays, queues, downloadsRevision, refresh, ownerKey) {
        val (found, waiting) = withContext(process.runtime.dispatchers.io) {
            // A reading that was superseded must not answer: caught, its cancellation would show everything as empty.
            try { process.stateReady.await(); data.summary(ownerKey) to process.legacyState.inventory() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { LocalDataSummary() to UnassignedInventory() }
        }
        summary = found; earlier = waiting
    }
    fun run(action: PrivacyAction) {
        working = true
        scope.launch {
            try {
                val result = when (action) {
                    PrivacyAction.ClearAccountHistory -> data.clearHistory(ownerKey)
                    PrivacyAction.ClearDeviceHistory -> data.clearHistory(DEVICE_LOCAL_SCOPE_KEY)
                    PrivacyAction.ClearSearches -> data.clearSearches(ownerKey)
                    PrivacyAction.DeleteDownloads -> data.deleteDownloads(ownerKey)
                    PrivacyAction.DeleteAccountData -> data.deleteAccountData(ownerKey) {
                        // The owner's queue is stopped and emptied before its rows go, so nothing still playing writes one back.
                        playback.clearProviderPlayback()
                        withTimeoutOrNull(2_000) { while (playback.hasProviderItems()) delay(50) }
                    }
                    PrivacyAction.ShortenRetention -> data.setRetention(pendingRetention).also { retention = historyRetentionDays(context) }
                    PrivacyAction.ChangeRetention -> data.setRetention(pendingRetention.takeIf { it > 0 }).also { retention = historyRetentionDays(context) }
                    PrivacyAction.HistoryOff, PrivacyAction.HistoryOn -> {
                        collections.updateHistoryPreference(action == PrivacyAction.HistoryOn); DataActionResult.Done(0)
                    }
                }
                collections.refresh()
                if (result is DataActionResult.Done) { asking = null; failure = null; uiHaptic(HapticCue.Confirm); onNotice(privacyOutcome(action, result, pendingRetention)) }
                else failure = privacyOutcome(action, result)
            } finally { working = false; refresh++ }
        }
    }
    fun ask(action: PrivacyAction, count: Int) {
        if (count == 0) onNotice(string(R.string.there_is_nothing_to_remove)) else { failure = null; asking = action }
    }
    fun chooseRetention(days: Int?) {
        if (days == retention || working) return
        scope.launch {
            val older = if (days == null) 0 else data.retentionPreview(days)
            pendingRetention = days ?: 0; pendingRetentionCount = older; failure = null
            asking = if (days != null && older > 0) PrivacyAction.ShortenRetention else PrivacyAction.ChangeRetention
        }
    }
    asking?.let { action ->
        val prompt = privacyPrompt(action, owner, summary ?: LocalDataSummary(), pendingRetention, pendingRetentionCount)
        FrostedAlertDialog(onDismissRequest = { if (!working) { asking = null; failure = null } }, title = { Text(prompt.title) },
            text = { Column {
                Text(failure ?: prompt.body, modifier = Modifier.testTag(if (failure == null) "privacy_prompt" else "privacy_failure"))
            } },
            confirmButton = { TextButton(enabled = !working, onClick = { uiHaptic(HapticCue.Confirm); run(action) },
                modifier = Modifier.testTag("privacy_confirm")) { Text(if (failure == null) prompt.confirm else string(R.string.retry), color = Pink) } },
            dismissButton = { TextButton(enabled = !working, onClick = { asking = null; failure = null },
                modifier = Modifier.testTag("privacy_cancel")) { Text(if (failure == null) string(R.string.cancel) else string(R.string.close)) } })
    }
    val found = summary
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.settings), model::back) }
        item { ScreenTitle(string(R.string.privacy_and_data), string(R.string.what_riff_keeps_on_this_phone_nothing)) }
        item {
            SettingsSection(string(R.string.listening_history)) {
                SettingsToggleRow(string(R.string.save_listening_history), collections.historyEnabled,
                    { on -> uiHaptic(); failure = null; asking = if (on) PrivacyAction.HistoryOn else PrivacyAction.HistoryOff },
                    Icons.Rounded.History, "settings_history",
                    string(R.string.for_every_account_on_this_phone_turning))
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(string(R.string.keep_history_for), color = White, fontSize = 15.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Int?>(30, 90, 365, null).forEach { days ->
                            FilterChip(retention == days, { uiHaptic(); chooseRetention(days) }, { Text(retentionLabel(days)) },
                                modifier = Modifier.testTag("privacy_retention_${days ?: "keep"}"))
                        }
                    }
                    Text(string(R.string.for_every_account_on_this_phone_riff),
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                SettingsDivider()
                // Feature 12: Encore's daily totals normally go with the plays; this keeps them (no single play is kept).
                var keepTotals by remember { mutableStateOf(keepEncoreTotals(context)) }
                SettingsToggleRow(string(R.string.keep_encore_totals), keepTotals,
                    { on -> uiHaptic(); setKeepEncoreTotals(context, on); keepTotals = on },
                    Icons.Rounded.BarChart, "privacy_keep_encore_totals", string(R.string.keep_encore_totals_detail))
                SettingsDivider()
                if (verified) SettingsLinkRow(string(R.string.clear_history_of_x, owner), found?.let { quantity(R.plurals.n_plays, it.accountPlays) }, Icons.Rounded.History,
                    "privacy_clear_account_history") { ask(PrivacyAction.ClearAccountHistory, found?.accountPlays ?: 0) }
                else Text(string(R.string.riff_could_not_verify_this_account_so_2), color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("privacy_unverified"))
                SettingsDivider()
                SettingsLinkRow(string(R.string.clear_history_of_audio_on_this_phone), found?.let { quantity(R.plurals.n_plays, it.devicePlays) }, Icons.Rounded.PhoneAndroid,
                    "privacy_clear_device_history") { ask(PrivacyAction.ClearDeviceHistory, found?.devicePlays ?: 0) }
            }
        }
        if (verified) item {
            SettingsSection(string(R.string.kept_for_x, owner)) {
                SettingsLinkRow(string(R.string.clear_search_history), found?.let { quantity(R.plurals.n_searches, it.searches) }, Icons.Rounded.Search,
                    "privacy_clear_searches") { ask(PrivacyAction.ClearSearches, found?.searches ?: 0) }
                SettingsDivider()
                SettingsLinkRow(string(R.string.delete_downloads), found?.let { quantity(R.plurals.n_songs, it.downloads) }, Icons.Rounded.DownloadDone,
                    "privacy_delete_downloads") { ask(PrivacyAction.DeleteDownloads, found?.downloads ?: 0) }
                SettingsDivider()
                SettingsLinkRow(string(R.string.delete_local_data_for_this_account), null, Icons.Rounded.DeleteOutline, "privacy_delete_account_data") {
                    failure = null; asking = PrivacyAction.DeleteAccountData
                }
            }
        }
        item { LocalBackupSection(onNotice) { collections.refresh(); retention = historyRetentionDays(context); onSettingsImported(); refresh++ } }
        item {
            LocalDataTransferSection(provider, ownerKey, owner, onNotice, transfer, documents) {
                // What is on screen is read again from what the import stored.
                collections.refresh(); retention = historyRetentionDays(context); CrossfadeSettings.get(context).reload(); onSettingsImported(); refresh++
            }
        }
        if (!earlier.empty) item {
            SettingsSection(string(R.string.from_earlier_versions)) {
                SettingsLinkRow(string(R.string.earlier_data), null, Icons.Rounded.History, "settings_earlier_data") { model.push(Route.Settings(SettingsSection.EarlierData)) }
            }
        }
    }
}
