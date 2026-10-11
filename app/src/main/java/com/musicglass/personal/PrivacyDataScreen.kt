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

internal enum class PrivacyAction { ClearAccountHistory, ClearDeviceHistory, ClearSearches, DeleteDownloads, DeleteAccountData, ShortenRetention }

internal fun plural(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

/** How the owner is named in confirmations: the account's name, or what signed-out use is called elsewhere. */
internal fun ownerLabel(provider: YouTubeMusicProvider, ownerKey: String): String = when {
    ownerKey == UNVERIFIED_SCOPE_KEY -> "this account"
    provider.accountScope() == AccountScope.Guest -> "signed-out listening"
    else -> provider.accountProfile?.name?.takeIf { it.isNotBlank() } ?: "this account"
}

internal data class PrivacyPrompt(val title: String, val body: String, val confirm: String)

/** Each confirmation says whose data, that it cannot be undone, and that YouTube Music is not changed. */
internal fun privacyPrompt(action: PrivacyAction, owner: String, data: LocalDataSummary, retentionDays: Int = 0, retentionCount: Int = 0): PrivacyPrompt = when (action) {
    PrivacyAction.ClearAccountHistory -> PrivacyPrompt("Clear ${plural(data.accountPlays, "play")}?",
        "Removes the listening history of $owner from this phone, with its recently played list and play counts. " +
            "History of audio stored on this phone is kept. This cannot be undone. Your YouTube Music history is not changed.", "Clear")
    PrivacyAction.ClearDeviceHistory -> PrivacyPrompt("Clear ${plural(data.devicePlays, "play")}?",
        "Removes the history of audio stored on this phone. The audio files stay, and no account's history is changed. This cannot be undone.", "Clear")
    PrivacyAction.ClearSearches -> PrivacyPrompt("Clear ${plural(data.searches, "search", "searches")}?",
        "Removes the recent searches of $owner from this phone. This cannot be undone. Nothing changes in YouTube Music.", "Clear")
    PrivacyAction.DeleteDownloads -> PrivacyPrompt("Delete ${plural(data.downloads, "download")}?",
        "Removes the downloaded songs, artwork and lyrics of $owner from this phone and cancels its waiting downloads. " +
            "This cannot be undone, but the songs can be downloaded again. Your YouTube Music library is not changed.", "Delete")
    PrivacyAction.DeleteAccountData -> PrivacyPrompt("Delete local data?",
        "Removes everything Riff keeps on this phone for $owner: listening history, searches, the saved queue, tracks saved in Riff and downloads. " +
            "Playback from this account stops. Settings, other accounts' data and the audio files stored on this phone are kept. " +
            "You stay signed in, and nothing changes in YouTube Music. This cannot be undone.", "Delete")
    PrivacyAction.ShortenRetention -> PrivacyPrompt("Keep history for ${retentionLabel(retentionDays).lowercase()}?",
        "${plural(retentionCount, "older play")} will be removed now, from every account on this phone, and plays will be removed as they pass that age. " +
            "This cannot be undone. Your YouTube Music history is not changed.", "Remove and keep ${retentionLabel(retentionDays).lowercase()}")
}

internal fun retentionLabel(days: Int?) = when (days) { 30 -> "30 days"; 90 -> "90 days"; 365 -> "1 year"; else -> "Until cleared" }

/** What to tell the user once an action has run. Partial and failed outcomes never read as success. */
internal fun privacyOutcome(action: PrivacyAction, result: DataActionResult): String = when (result) {
    is DataActionResult.Done -> when (action) {
        PrivacyAction.ClearAccountHistory, PrivacyAction.ClearDeviceHistory -> "${plural(result.removed, "play")} cleared"
        PrivacyAction.ClearSearches -> "${plural(result.removed, "search", "searches")} cleared"
        PrivacyAction.DeleteDownloads -> "${plural(result.removed, "download")} deleted"
        PrivacyAction.DeleteAccountData -> "Local data for this account deleted"
        PrivacyAction.ShortenRetention -> "${plural(result.removed, "older play")} removed"
    }
    is DataActionResult.Partial -> "Not everything could be removed. Still on this phone: ${result.remaining.joinToString(", ")}. The rest is gone."
    is DataActionResult.Failed -> "Nothing was removed. Riff could not change its storage."
}

/**
 * Settings → Privacy and data. Everything here acts on this phone only. Clearing and deleting name
 * the owner they apply to and ask first; cancelling changes nothing.
 */
@Composable internal fun PrivacyDataScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider, collections: LocalCollections,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current),
    data: LocalDataController = process.localData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val plays by process.history.revision.collectAsState()
    val queues by process.snapshots.revision.collectAsState()
    val downloadsRevision by remember { OfflineMusicStore.get(context).revision }.collectAsState()
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
            runCatching { process.stateReady.await(); data.summary(ownerKey) to process.legacyState.inventory() }
                .getOrDefault(LocalDataSummary() to UnassignedInventory())
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
                }
                collections.refresh()
                if (result is DataActionResult.Done) { asking = null; failure = null; uiHaptic(HapticCue.Confirm); onNotice(privacyOutcome(action, result)) }
                else failure = privacyOutcome(action, result)
            } finally { working = false; refresh++ }
        }
    }
    fun ask(action: PrivacyAction, count: Int) {
        if (count == 0) onNotice("There is nothing to remove.") else { failure = null; asking = action }
    }
    fun chooseRetention(days: Int?) {
        if (days == retention || working) return
        scope.launch {
            val older = if (days == null) 0 else data.retentionPreview(days)
            if (days != null && older > 0) { pendingRetention = days; pendingRetentionCount = older; failure = null; asking = PrivacyAction.ShortenRetention }
            else { data.setRetention(days); retention = historyRetentionDays(context); onNotice("History is kept ${if (days == null) "until you clear it" else "for ${retentionLabel(days).lowercase()}"}") }
        }
    }
    asking?.let { action ->
        val prompt = privacyPrompt(action, owner, summary ?: LocalDataSummary(), pendingRetention, pendingRetentionCount)
        FrostedAlertDialog(onDismissRequest = { if (!working) { asking = null; failure = null } }, title = { Text(prompt.title) },
            text = { Column {
                Text(failure ?: prompt.body, modifier = Modifier.testTag(if (failure == null) "privacy_prompt" else "privacy_failure"))
            } },
            confirmButton = { TextButton(enabled = !working, onClick = { uiHaptic(HapticCue.Confirm); run(action) },
                modifier = Modifier.testTag("privacy_confirm")) { Text(if (failure == null) prompt.confirm else "Retry", color = Pink) } },
            dismissButton = { TextButton(enabled = !working, onClick = { asking = null; failure = null },
                modifier = Modifier.testTag("privacy_cancel")) { Text(if (failure == null) "Cancel" else "Close") } })
    }
    val found = summary
    ScreenList(model, bottom) {
        item { BackHeading("Settings", model::back) }
        item { ScreenTitle("Privacy and data", "What Riff keeps on this phone. Nothing here changes your YouTube Music account.") }
        item {
            SettingsSection("Listening history") {
                SettingsToggleRow("Save listening history", collections.historyEnabled, hapticToggle(collections::updateHistoryPreference),
                    Icons.Rounded.History, "settings_history",
                    "For every account on this phone. Turning it off stops new plays being saved; what is already saved stays until you clear it.")
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text("Keep history for", color = White, fontSize = 15.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Int?>(30, 90, 365, null).forEach { days ->
                            FilterChip(retention == days, { uiHaptic(); chooseRetention(days) }, { Text(retentionLabel(days)) },
                                modifier = Modifier.testTag("privacy_retention_${days ?: "keep"}"))
                        }
                    }
                    Text("For every account on this phone. Riff keeps at most 100,000 plays for each account; beyond that the oldest are removed.",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                SettingsDivider()
                if (verified) SettingsLinkRow("Clear history of $owner", found?.let { plural(it.accountPlays, "play") }, Icons.Rounded.History,
                    "privacy_clear_account_history") { ask(PrivacyAction.ClearAccountHistory, found?.accountPlays ?: 0) }
                else Text("Riff could not verify this account, so it keeps no history for it.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("privacy_unverified"))
                SettingsDivider()
                SettingsLinkRow("Clear history of audio on this phone", found?.let { plural(it.devicePlays, "play") }, Icons.Rounded.PhoneAndroid,
                    "privacy_clear_device_history") { ask(PrivacyAction.ClearDeviceHistory, found?.devicePlays ?: 0) }
            }
        }
        if (verified) item {
            SettingsSection("Kept for $owner") {
                SettingsLinkRow("Clear search history", found?.let { plural(it.searches, "search", "searches") }, Icons.Rounded.Search,
                    "privacy_clear_searches") { ask(PrivacyAction.ClearSearches, found?.searches ?: 0) }
                SettingsDivider()
                SettingsLinkRow("Delete downloads", found?.let { plural(it.downloads, "song") }, Icons.Rounded.DownloadDone,
                    "privacy_delete_downloads") { ask(PrivacyAction.DeleteDownloads, found?.downloads ?: 0) }
                SettingsDivider()
                SettingsLinkRow("Delete local data for this account", null, Icons.Rounded.DeleteOutline, "privacy_delete_account_data") {
                    failure = null; asking = PrivacyAction.DeleteAccountData
                }
            }
        }
        if (!earlier.empty) item {
            SettingsSection("From earlier versions") {
                SettingsLinkRow("Earlier data", null, Icons.Rounded.History, "settings_earlier_data") { model.push("earlier_data") }
            }
        }
    }
}
