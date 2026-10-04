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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun historyMatches(entry: HistoryEntry, query: String) = query.isBlank() ||
    entry.title.contains(query.trim(), true) || entry.artist.contains(query.trim(), true)

/** Every song played on this phone, newest first and grouped by day. Opened from Library's top-right icon. */
@Composable internal fun ListeningHistoryScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider,
    playback: PlaybackGateway, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember(context) { ListeningHistory.get(context) }
    val revision by history.revision.collectAsState()
    var entries by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var limit by remember(query) { mutableIntStateOf(300) }
    LaunchedEffect(revision) { entries = withContext(Dispatchers.IO) { history.entries() } }
    val shown = remember(entries, query) { entries.orEmpty().filter { historyMatches(it, query) } }
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
    ScreenList(model, bottom) {
        item { BackHeading("Library", model::back) }
        item { ScreenTitle("History", entries?.let { "${it.size} plays · kept until you clear Riff's storage" }) }
        if (!ListeningHistory.enabled(context)) item {
            Text("Saving history is off. Turn it on in Settings → Library.", color = Muted, fontSize = 13.sp)
        }
        if (!entries.isNullOrEmpty()) item {
            OutlinedTextField(query, { query = it.take(80) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                placeholder = { Text("Search your history") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().testTag("history_search"))
        }
        when {
            entries == null -> item { Text("Loading your history…", color = Muted) }
            entries!!.isEmpty() -> item { Text("Songs you play will appear here.", color = Muted) }
            shown.isEmpty() -> item { Text("No plays match “$query”.", color = Muted) }
        }
        var lastDay = ""
        shown.take(limit).forEachIndexed { index, entry ->
            val day = historyDayLabel(entry.playedAt, now)
            if (day != lastDay) {
                lastDay = day
                item(key = "day:$index") {
                    Text(day, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
            item(key = "play:$index:${entry.playedAt}") {
                HistoryRow(entry, timeFormat.format(java.util.Date(entry.playedAt))) { play(entry) }
            }
        }
        if (shown.size > limit) item {
            TextButton({ uiHaptic(); limit += 300 }, modifier = Modifier.testTag("history_more")) { Text("Show older plays") }
        }
    }
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
