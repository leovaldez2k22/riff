package com.musicglass.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Every qualified play, newest first and grouped by day. Plays from YouTube Music belong to the
 * owner that is active (an account, or signed-out use); plays of audio on this phone belong to the
 * device and are shown separately. Opened from Library's top-right icon.
 */
@Composable internal fun ListeningHistoryScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current),
    onCleared: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = process.history
    val revision by history.revision.collectAsStateWithLifecycle()
    val queues by process.snapshots.revision.collectAsStateWithLifecycle()
    var onDevice by rememberSaveable { mutableStateOf(false) }
    val accountKey = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val owner = if (onDevice) DEVICE_LOCAL_SCOPE_KEY else accountKey.takeIf { it != UNVERIFIED_SCOPE_KEY }
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var next by remember { mutableStateOf<HistoryCursor?>(null) }
    var total by remember { mutableStateOf<Int?>(null) }
    var earlier by remember { mutableStateOf(UnassignedInventory()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(owner, query, revision) {
        if (query.isNotBlank()) delay(250) // one lookup per pause in typing
        if (owner == null) { entries = emptyList(); next = null; total = 0; return@LaunchedEffect }
        val (page, count) = withContext(process.runtime.dispatchers.io) {
            suspendCatching { process.stateReady.await(); history.page(owner, null, query) to history.count(owner) }
                .getOrDefault(HistoryPage(emptyList(), null) to 0)
        }
        entries = page.entries; next = page.next; total = count
    }
    LaunchedEffect(revision, queues) {
        earlier = withContext(process.runtime.dispatchers.io) { suspendCatching { process.stateReady.await(); process.legacyState.inventory() }.getOrDefault(UnassignedInventory()) }
    }
    fun older() {
        val cursor = next ?: return; val from = owner ?: return; val wanted = query
        scope.launch {
            val page = withContext(process.runtime.dispatchers.io) { runCatching { history.page(from, cursor, wanted) }.getOrNull() } ?: return@launch
            if (from == owner && wanted == query && cursor == next) { entries = entries.orEmpty() + page.entries; next = page.next }
        }
    }
    val now = remember(revision) { System.currentTimeMillis() }
    val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    fun play(entry: HistoryEntry) {
        val track = entry.track()
        if (track != null) { scope.launch { playRealTrack(provider, playback, track, onNotice) }; return }
        val id = entry.mediaId.removePrefix("local:").toLongOrNull() ?: return
        scope.launch {
            // The name must match too: after an import from another phone the same number can be a different file.
            val local = LocalAudioRepository(context).list().getOrNull()?.firstOrNull { it.id == id && it.title.take(180) == entry.title }
            if (local != null) playback.playLocal(local) else onNotice(string(R.string.this_file_is_no_longer_on_your))
        }
    }
    val shown = entries
    // Clears the list on screen and nothing else: the account's plays or the phone's, never both.
    var clearing by remember { mutableStateOf<String?>(null) }
    var clearWorking by remember { mutableStateOf(false) }
    var clearFailure by remember { mutableStateOf<String?>(null) }
    clearing?.let { target ->
        val action = if (target == DEVICE_LOCAL_SCOPE_KEY) PrivacyAction.ClearDeviceHistory else PrivacyAction.ClearAccountHistory
        val count = total ?: 0
        val prompt = privacyPrompt(action, ownerLabel(provider, accountKey), LocalDataSummary(accountPlays = count, devicePlays = count))
        FrostedAlertDialog(onDismissRequest = { if (!clearWorking) { clearing = null; clearFailure = null } }, title = { Text(prompt.title) },
            text = { Text(clearFailure ?: prompt.body) },
            confirmButton = { TextButton(enabled = !clearWorking, onClick = {
                uiHaptic(HapticCue.Confirm); clearWorking = true
                scope.launch {
                    try {
                        val result = process.localData.clearHistory(target)
                        onCleared()
                        if (result is DataActionResult.Done) { clearing = null; clearFailure = null; onNotice(privacyOutcome(action, result)) }
                        else clearFailure = privacyOutcome(action, result)
                    } finally { clearWorking = false }
                }
            }, modifier = Modifier.testTag("history_clear_confirm")) { Text(if (clearFailure == null) prompt.confirm else string(R.string.retry), color = Pink) } },
            dismissButton = { TextButton(enabled = !clearWorking, onClick = { clearing = null; clearFailure = null },
                modifier = Modifier.testTag("history_clear_cancel")) { Text(if (clearFailure == null) string(R.string.cancel) else string(R.string.close)) } })
    }
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.library), model::back) }
        item { ScreenTitle(string(R.string.history), total?.takeIf { query.isBlank() }?.let { quantity(R.plurals.n_plays_kept_on_this_phone_only, it) }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!onDevice, { uiHaptic(); onDevice = false }, { Text("YouTube Music") }, modifier = Modifier.testTag("history_scope_account"))
                FilterChip(onDevice, { uiHaptic(); onDevice = true }, { Text(string(R.string.on_this_phone)) }, modifier = Modifier.testTag("history_scope_device"))
                Spacer(Modifier.weight(1f))
                if (owner != null && (total ?: 0) > 0 && query.isBlank()) TextButton({ uiHaptic(); clearFailure = null; clearing = owner },
                    modifier = Modifier.testTag("history_clear")) { Text(string(R.string.clear), color = Muted) }
            }
        }
        if (!historyEnabled(context)) item {
            Text(string(R.string.saving_history_is_off_turn_it_on), color = Muted, fontSize = 13.sp)
        }
        if (!onDevice && accountKey == UNVERIFIED_SCOPE_KEY) item {
            Text(string(R.string.riff_could_not_verify_this_account_so),
                color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("history_unverified"))
        }
        if (!earlier.empty) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Raised).padding(12.dp).testTag("history_earlier"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(earlierDataSummary(earlier), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton({ uiHaptic(); model.push(Route.Settings(SettingsSection.EarlierData)) }, modifier = Modifier.testTag("history_earlier_review")) { Text(string(R.string.review), color = Pink) }
            }
        }
        if (!shown.isNullOrEmpty() || query.isNotBlank()) item {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                placeholder = { Text(string(R.string.search_your_history)) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().testTag("history_search"))
        }
        when {
            shown == null -> item { Text(string(R.string.loading_your_history), color = Muted) }
            shown.isEmpty() && query.isNotBlank() -> item { Text(string(R.string.no_plays_match_x, query), color = Muted) }
            shown.isEmpty() -> item { Text(if (onDevice) string(R.string.audio_you_play_from_this_phone_will) else string(R.string.songs_you_play_will_appear_here), color = Muted) }
        }
        var lastDay = ""
        shown.orEmpty().forEachIndexed { index, entry ->
            val day = historyDayLabel(entry.playedAt, now)
            if (day != lastDay) {
                lastDay = day
                item(key = "day:$index") {
                    Text(day, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            item(key = "play:${entry.eventId}") {
                HistoryRow(entry, timeFormat.format(java.util.Date(entry.playedAt))) { play(entry) }
            }
        }
        if (next != null) item {
            TextButton({ uiHaptic(); older() }, modifier = Modifier.testTag("history_more")) { Text(string(R.string.show_older_plays)) }
        }
    }
}

internal fun earlierDataSummary(earlier: UnassignedInventory): String {
    val parts = listOfNotNull(
        earlier.providerPlays.takeIf { it > 0 }?.let { quantity(R.plurals.n_plays, it) },
        earlier.queueItems.takeIf { it > 0 }?.let { quantity(R.plurals.a_queue_of_n_songs, it) },
        earlier.searches.takeIf { it > 0 }?.let { quantity(R.plurals.n_searches, it) })
    val text = when (parts.size) { 0 -> string(R.string.data); 1 -> parts[0]; 2 -> string(R.string.x_and_x, parts[0], parts[1]); else -> string(R.string.x_x_and_x, parts[0], parts[1], parts[2]) }
    val several = parts.size > 1 || earlier.providerPlays > 1 || earlier.searches > 1
    return string(if (several) R.string.x_from_before_this_update_are_not_assigned else R.string.x_from_before_this_update_is_not_assigned, text)
}

@Composable private fun HistoryRow(entry: HistoryEntry, time: String, onPlay: () -> Unit) {
    val card = entry.track()?.homeCard()
    val base = Modifier.fillMaxWidth().heightIn(min = 60.dp)
    Row((if (card != null) base.cardPeek(card, 12.dp, onPlay) else base.clip(RoundedCornerShape(12.dp)).clickable(onClick = hapticClick(onPlay)))
        .testTag("history_row"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Raised), contentAlignment = Alignment.Center) {
            if (entry.artworkUrl != null || card != null) AsyncImage(entry.artworkUrl ?: card?.artworkUrl, null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Rounded.MusicNote, null, tint = Muted)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title.ifBlank { string(R.string.unknown_song) }, color = White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playerArtistLabel(entry.artist).ifBlank { if (card == null) string(R.string.on_this_phone) else "" }, color = Muted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(time, color = Muted, fontSize = 12.sp)
    }
}

/** Settings → Library → Not interested: everything hidden with "Not interested", each with Undo. */
@Composable internal fun NotInterestedScreen(model: ShellModel, bottom: Dp, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { NotInterestedStore.get(context) }
    val entries = store.entries.asReversed()
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.settings), model::back) }
        item { ScreenTitle(string(R.string.not_interested), string(R.string.riff_hides_these_from_home_and_autoplay)) }
        if (entries.isEmpty()) item { Text(string(R.string.nothing_hidden_use_not_interested_in_a), color = Muted) }
        items(entries, key = { it.key }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("not_interested_row"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Raised), contentAlignment = Alignment.Center) {
                    val art = entry.artworkUrl ?: entry.key.takeIf { entry.song }?.let(::providerArtworkUrl)
                    if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Muted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.title.ifBlank { if (entry.song) string(R.string.song) else string(R.string.collection) }, color = White, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(if (entry.song) string(R.string.song) else string(R.string.album_or_playlist), playerArtistLabel(entry.subtitle))
                        .filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ uiHaptic(HapticCue.Confirm); store.remove(entry.key); onNotice(string(R.string.riff_can_suggest_this_again)) },
                    modifier = Modifier.testTag("not_interested_undo")) { Text(string(R.string.undo), color = Pink) }
            }
        }
        if (entries.isNotEmpty()) item {
            Text(string(R.string.youtube_music_keeps_its_own_record_of), color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
