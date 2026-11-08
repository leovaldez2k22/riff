package com.musicglass.personal

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Feature 12: one import at a time, from the file read to the rows the user keeps. */
@Stable
private class ImportWizard(val lists: List<ImportedList>) {
    var picked by mutableStateOf(lists.singleOrNull())
    var name by mutableStateOf(lists.singleOrNull()?.name.orEmpty())
    var matches by mutableStateOf<List<ImportMatch>?>(null)
    var done by mutableIntStateOf(0)
    var matching by mutableStateOf(false)
}

/** Reads at most [IMPORT_MAX_BYTES] of a picked document; null when it is larger or cannot be read. */
private fun readPicked(context: android.content.Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(16 * 1024); var total = 0
        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > IMPORT_MAX_BYTES) return@runCatching null; out.write(buffer, 0, n) }
        out.toByteArray()
    } ?: return@runCatching null
    name to bytes
}.getOrNull()

@Composable internal fun PhonePlaylistsScreen(model: ShellModel, bottom: Dp, provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { PhonePlaylistStore.get(context) }
    val owner = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val lists = store.revision.let { store.lists(owner) }
    var wizard by remember(owner) { mutableStateOf<ImportWizard?>(null) }
    var reading by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf<PhonePlaylist?>(null) }
    var deleting by remember { mutableStateOf<PhonePlaylist?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            val read = withContext(Dispatchers.IO) { readPicked(context, uri)?.let { (name, bytes) -> readImportFile(name, bytes) } }
            reading = false
            if (read.isNullOrEmpty()) { uiHaptic(HapticCue.Reject); onNotice(string(R.string.import_unreadable)) } else wizard = ImportWizard(read)
        }
    }
    fun match(w: ImportWizard, list: ImportedList, only: Set<Int>? = null) {
        w.matching = true; w.done = 0
        scope.launch {
            val previous = w.matches
            val rows = if (only == null) list.rows else list.rows.filter { it.position in only }
            val found = matchImport(rows, providerImportSearch(provider)) { w.done = it }
            w.matches = if (previous == null || only == null) found
                else previous.map { m -> found.firstOrNull { it.row.position == m.row.position } ?: m }
            w.matching = false
        }
    }
    wizard?.let { w ->
        BackHandler { if (!w.matching) wizard = null }
        ImportPreview(model, bottom, w, onPick = { list -> w.picked = list; w.name = list.name; match(w, list) },
            onRetry = { w.picked?.let { list -> match(w, list, w.matches.orEmpty().filter { it.failed }.mapTo(HashSet()) { it.row.position }) } },
            onCancel = { wizard = null },
            onSave = {
                val keep = w.matches.orEmpty().mapNotNull { it.chosen }
                val made = store.create(owner, w.name, keep)
                if (made == null) { uiHaptic(HapticCue.Reject); onNotice(string(R.string.import_full)) }
                else { uiHaptic(HapticCue.Confirm); onNotice(string(R.string.import_saved, made.name, keep.size)); wizard = null }
            })
        LaunchedEffect(w) { w.picked?.let { if (w.matches == null) match(w, it) } }
        return
    }
    copying?.let { list ->
        val account = provider.accountProfile?.name?.takeIf(String::isNotBlank) ?: string(R.string.phone_playlist_account)
        FrostedAlertDialog(onDismissRequest = { if (busy == null) copying = null }, title = { Text(string(R.string.phone_playlist_copy_title)) },
            text = { Column(Modifier.testTag("phone_playlist_copy")) {
                Text(string(R.string.phone_playlist_copy_detail, list.name, list.songs.size - list.copied.size, account))
                busy?.let { Text(it, color = Muted, modifier = Modifier.padding(top = RiffSpace.item)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = RiffSpace.tight)) }
            } },
            confirmButton = { TextButton(enabled = busy == null && provider.signedIn, modifier = Modifier.testTag("phone_playlist_copy_confirm"), onClick = {
                val revision = provider.authRevision; busy = string(R.string.saving)
                scope.launch {
                    val result = copyPhonePlaylist(store, owner, list, providerCopyTarget(provider, provider), { provider.authRevision == revision }) { busy = string(R.string.phone_playlist_copy_progress, it, list.songs.size) }
                    busy = null; copying = null; provider.refreshLibraryIndex()
                    if (result.error == null) { uiHaptic(HapticCue.Confirm); onNotice(string(R.string.phone_playlist_copied, list.name, result.added + result.alreadyThere)) }
                    else { uiHaptic(HapticCue.Reject); onNotice(string(R.string.phone_playlist_copy_stopped, result.added, result.error.trimEnd('.'))) }
                }
            }) { Text(string(R.string.phone_playlist_copy_confirm)) } },
            dismissButton = { TextButton(enabled = busy == null, onClick = { copying = null }) { Text(string(R.string.cancel)) } })
    }
    deleting?.let { list ->
        FrostedAlertDialog(onDismissRequest = { deleting = null }, title = { Text(string(R.string.phone_playlist_delete_title)) },
            text = { Text(string(R.string.phone_playlist_delete_detail, list.name)) },
            confirmButton = { TextButton({ store.delete(owner, list.id); deleting = null; uiHaptic(HapticCue.Confirm) }, modifier = Modifier.testTag("phone_playlist_delete_confirm")) { Text(string(R.string.remove)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(string(R.string.cancel)) } })
    }
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.library), model::back) }
        item { ScreenTitle(string(R.string.phone_playlists), string(R.string.phone_playlists_detail)) }
        item(key = "import") { ToolLink(if (reading) string(R.string.loading) else string(R.string.import_playlist)) {
            if (!reading) { uiHaptic(); runCatching { pick.launch(arrayOf("text/*", "application/json", "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/octet-stream")) } } } }
        item { Text(string(R.string.import_kinds), color = Muted, fontSize = RiffType.detail) }
        item(key = "blend") { Edition.BlendWithFriend(provider, playback, onNotice) }
        if (lists.isEmpty()) item(key = "empty") { ContentEmpty(string(R.string.phone_playlists_empty)) }
        items(lists.asReversed(), key = { it.id }) { list -> PhonePlaylistRow(list, provider.signedIn,
            onPlay = { shuffle -> if (list.songs.isNotEmpty()) { uiHaptic(); playback.playCollection(list.songs.map { it.track }, 0, list.name, shuffle) } },
            onCopy = { copying = list }, onDelete = { deleting = list }) }
    }
}

@Composable private fun PhonePlaylistRow(list: PhonePlaylist, signedIn: Boolean, onPlay: (Boolean) -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.listRow).clickable { onPlay(false) }.testTag("phone_playlist_row"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(list.name, color = White, fontSize = RiffType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(quantity(R.plurals.n_songs, list.songs.size),
                if (list.remoteId != null) string(if (list.copied.size >= list.songs.size) R.string.phone_playlist_on_youtube_music else R.string.phone_playlist_copy_unfinished) else null)
                .joinToString(" · "), color = Muted, fontSize = RiffType.detail)
        }
        Box {
            IconButton({ uiHaptic(); menu = true }, modifier = Modifier.testTag("phone_playlist_menu")) {
                Icon(androidx.compose.material.icons.Icons.Rounded.MoreHoriz, string(R.string.options), tint = Muted) }
            SongActionMenu(menu, { menu = false }) {
                SongActionItem("Play") { menu = false; onPlay(false) }
                SongActionItem("Shuffle") { menu = false; onPlay(true) }
                if (signedIn && list.songs.isNotEmpty() && (list.remoteId == null || list.copied.size < list.songs.size))
                    SongActionItem("Copy to YouTube Music") { menu = false; onCopy() }
                SongActionItem("Remove from this phone") { menu = false; onDelete() }
            }
        }
    }
}

@Composable private fun ImportPreview(model: ShellModel, bottom: Dp, w: ImportWizard, onPick: (ImportedList) -> Unit, onRetry: () -> Unit,
    onCancel: () -> Unit, onSave: () -> Unit) {
    val matches = w.matches
    ScreenList(model, bottom) {
        item { BackHeading(string(R.string.cancel), { if (!w.matching) onCancel() }) }
        item { ScreenTitle(string(R.string.import_playlist), w.picked?.let { quantity(R.plurals.n_songs, it.rows.size) }) }
        if (w.picked == null) {
            item { Text(string(R.string.import_choose_list), color = Muted) }
            items(w.lists, key = { "list:${it.name}:${it.rows.size}:${w.lists.indexOf(it)}" }) { list ->
                Row(Modifier.fillMaxWidth().heightIn(min = RiffSpace.compactRow).clickable { uiHaptic(); onPick(list) }.testTag("import_list"), verticalAlignment = Alignment.CenterVertically) {
                    Text(list.name, color = White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(quantity(R.plurals.n_songs, list.rows.size), color = Muted, fontSize = RiffType.detail)
                }
            }
            return@ScreenList
        }
        if (w.matching) item { Column {
            Text(string(R.string.import_matching, w.done, w.picked?.rows?.size ?: 0), color = White, modifier = Modifier.testTag("import_matching"))
            LinearProgressIndicator({ w.done.toFloat() / (w.picked?.rows?.size ?: 1).coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(top = RiffSpace.tight))
        } }
        if (matches != null && !w.matching) {
            val strong = matches.count { it.status == ImportStatus.Matched }; val unsure = matches.count { it.status == ImportStatus.Ambiguous }
            val missing = matches.count { it.status == ImportStatus.Missing }; val failed = matches.count { it.failed }
            item { Text(string(R.string.import_summary, strong, unsure, missing), color = White, modifier = Modifier.testTag("import_summary")) }
            w.picked?.let { if (it.unreadable > 0 || it.cut) item { Text(listOfNotNull(
                if (it.unreadable > 0) quantity(R.plurals.n_import_rows_unreadable, it.unreadable) else null,
                if (it.cut) string(R.string.import_cut, IMPORT_MAX_ROWS) else null).joinToString(" "), color = Muted, fontSize = RiffType.detail) } }
            if (failed > 0) item { ToolLink(quantity(R.plurals.n_import_retry, failed), onRetry) }
            item { OutlinedTextField(w.name, { w.name = it.take(PLAYLIST_TITLE_LIMIT) }, singleLine = true, label = { Text(string(R.string.playlist_name)) },
                modifier = Modifier.fillMaxWidth().testTag("import_name")) }
            val keep = matches.count { it.chosen != null }
            item { Row(horizontalArrangement = Arrangement.spacedBy(RiffSpace.item)) {
                Button({ onSave() }, enabled = keep > 0 && w.name.isNotBlank(), modifier = Modifier.testTag("import_save")) { Text(string(R.string.import_save, keep)) }
                TextButton(onCancel) { Text(string(R.string.cancel)) }
            } }
            items(matches.indices.toList(), key = { "row:${matches[it].row.position}" }) { i -> ImportRowView(matches[i]) { next -> w.matches = w.matches?.toMutableList()?.also { it[i] = next } } }
        }
    }
}

/** One row: what the file said, what will be saved, and the choices (another close song, or skip). */
@Composable private fun ImportRowView(match: ImportMatch, onChange: (ImportMatch) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = RiffSpace.tight).testTag("import_row")) {
        Text("${match.row.position}. ${match.row.title}" + (match.row.artist.takeIf(String::isNotBlank)?.let { " · $it" } ?: ""),
            color = White, fontSize = RiffType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val state = when {
            match.chosen != null -> string(R.string.import_will_save, match.chosen.title, playerArtistLabel(match.chosen.artist))
            match.failed -> string(R.string.import_search_failed)
            match.status == ImportStatus.Ambiguous -> string(R.string.import_choose)
            match.status == ImportStatus.Missing -> string(R.string.import_not_found)
            else -> string(R.string.import_skipped)
        }
        Text(state, color = if (match.chosen != null) Muted else Pink, fontSize = RiffType.detail, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RiffSpace.chipGap)) {
            match.options.filter { it.videoId != match.chosen?.videoId }.take(3).forEach { option ->
                TextButton({ uiHaptic(HapticCue.Selection); onChange(match.copy(chosen = option)) }, modifier = Modifier.testTag("import_option")) {
                    Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = RiffType.detail, color = Pink) }
            }
            if (match.chosen != null) TextButton({ uiHaptic(HapticCue.Selection); onChange(match.copy(chosen = null)) }, modifier = Modifier.testTag("import_skip")) {
                Text(string(R.string.import_skip), fontSize = RiffType.detail, color = Muted) }
        }
    }
}
