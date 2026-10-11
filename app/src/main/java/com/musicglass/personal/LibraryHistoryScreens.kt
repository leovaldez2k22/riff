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

/**
 * Every qualified play, newest first and grouped by day. Plays from YouTube Music belong to the
 * owner that is active (an account, or signed-out use); plays of audio on this phone belong to the
 * device and are shown separately. Opened from Library's top-right icon.
 */
@Composable internal fun ListeningHistoryScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit, process: AppContainer = AppContainer.get(LocalContext.current)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = process.history
    val revision by history.revision.collectAsState()
    val queues by process.snapshots.revision.collectAsState()
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
            runCatching { process.stateReady.await(); history.page(owner, null, query) to history.count(owner) }
                .getOrDefault(HistoryPage(emptyList(), null) to 0)
        }
        entries = page.entries; next = page.next; total = count
    }
    LaunchedEffect(revision, queues) {
        earlier = withContext(process.runtime.dispatchers.io) { runCatching { process.stateReady.await(); process.legacyState.inventory() }.getOrDefault(UnassignedInventory()) }
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
            val local = LocalAudioRepository(context).list().getOrNull()?.firstOrNull { it.id == id }
            if (local != null) playback.playLocal(local) else onNotice("This file is no longer on your phone")
        }
    }
    val shown = entries
    ScreenList(model, bottom) {
        item { BackHeading("Library", model::back) }
        item { ScreenTitle("History", total?.takeIf { query.isBlank() }?.let { "$it plays · kept on this phone until you clear Riff's storage" }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!onDevice, { uiHaptic(); onDevice = false }, { Text("YouTube Music") }, modifier = Modifier.testTag("history_scope_account"))
                FilterChip(onDevice, { uiHaptic(); onDevice = true }, { Text("On this phone") }, modifier = Modifier.testTag("history_scope_device"))
            }
        }
        if (!historyEnabled(context)) item {
            Text("Saving history is off. Turn it on in Settings → Library.", color = Muted, fontSize = 13.sp)
        }
        if (!onDevice && accountKey == UNVERIFIED_SCOPE_KEY) item {
            Text("Riff could not verify this account, so its history is not saved or shown. Open Account and reconnect.",
                color = Muted, fontSize = 13.sp, modifier = Modifier.testTag("history_unverified"))
        }
        if (!earlier.empty) item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Raised).padding(12.dp).testTag("history_earlier"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(earlierDataSummary(earlier), color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton({ uiHaptic(); model.push("earlier_data") }, modifier = Modifier.testTag("history_earlier_review")) { Text("Review", color = Pink) }
            }
        }
        if (!shown.isNullOrEmpty() || query.isNotBlank()) item {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search your history") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().testTag("history_search"))
        }
        when {
            shown == null -> item { Text("Loading your history…", color = Muted) }
            shown.isEmpty() && query.isNotBlank() -> item { Text("No plays match “$query”.", color = Muted) }
            shown.isEmpty() -> item { Text(if (onDevice) "Audio you play from this phone will appear here." else "Songs you play will appear here.", color = Muted) }
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
            TextButton({ uiHaptic(); older() }, modifier = Modifier.testTag("history_more")) { Text("Show older plays") }
        }
    }
}

internal fun earlierDataSummary(earlier: UnassignedInventory): String = listOfNotNull(
    earlier.providerPlays.takeIf { it > 0 }?.let { "$it ${if (it == 1) "play" else "plays"}" },
    earlier.queueItems.takeIf { it > 0 }?.let { "a queue of $it ${if (it == 1) "song" else "songs"}" },
).joinToString(" and ").ifBlank { "Data" } + " from before this update ${if (earlier.providerPlays > 0 && earlier.queueItems > 0 || earlier.providerPlays > 1) "are" else "is"} not assigned to an account."

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
            Text(entry.title.ifBlank { "Unknown song" }, color = White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playerArtistLabel(entry.artist).ifBlank { if (card == null) "On this phone" else "" }, color = Muted, fontSize = 12.sp,
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
        item { BackHeading("Settings", model::back) }
        item { ScreenTitle("Not interested", "Riff hides these from Home and autoplay. Undo lets Riff suggest them again.") }
        if (entries.isEmpty()) item { Text("Nothing hidden. Use “Not interested” in a song's options to hide it.", color = Muted) }
        items(entries, key = { it.key }) { entry ->
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("not_interested_row"), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Raised), contentAlignment = Alignment.Center) {
                    val art = entry.artworkUrl ?: entry.key.takeIf { entry.song }?.let(::providerArtworkUrl)
                    if (art != null) AsyncImage(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.MusicNote, null, tint = Muted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.title.ifBlank { if (entry.song) "Song" else "Collection" }, color = White, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(if (entry.song) "Song" else "Album or playlist", playerArtistLabel(entry.subtitle))
                        .filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton({ uiHaptic(HapticCue.Confirm); store.remove(entry.key); onNotice("Riff can suggest this again") },
                    modifier = Modifier.testTag("not_interested_undo")) { Text("Undo", color = Pink) }
            }
        }
        if (entries.isNotEmpty()) item {
            Text("YouTube Music keeps its own record of feedback you've sent from Riff.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
