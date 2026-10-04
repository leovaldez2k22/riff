package com.musicglass.personal

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun songCountLabel(count: Int, noun: String = "song") = when (count) {
    0 -> "None yet"
    1 -> "1 $noun"
    else -> "$count ${noun}s"
}

/** Downloads summary for its Library tile: finished songs, how many are still downloading, and up to 4 covers. */
internal data class DownloadSummary(val ready: Int, val active: Int, val covers: List<String>) {
    val label get() = if (active > 0) "Downloading $active" + if (ready > 0) " · $ready ready" else "" else songCountLabel(ready)
}

/** Library's Downloads and On device entries: one compact row of two tiles with live counts. */
@Composable internal fun LibraryShortcutTiles(model: ShellModel) {
    val context = LocalContext.current
    val store = remember(context) { OfflineMusicStore.get(context) }
    val revision by store.revision.collectAsState()
    var downloads by remember { mutableStateOf<DownloadSummary?>(null) }
    LaunchedEffect(revision) {
        downloads = withContext(Dispatchers.IO) {
            val scope = store.currentScope
            val rows = store.rows(scope).filter { it.state != "metadata" && it.state != "cancelled" }
            val ready = rows.filter { store.audio(scope, it.track.videoId) != null }
            DownloadSummary(ready.size, rows.count { it.state in setOf("queued", "downloading", "assets") },
                ready.sortedByDescending { it.downloadedAt }.take(4).mapNotNull {
                    store.artwork(scope, it.track.videoId)?.toURI()?.toString() ?: it.track.artworkUrl })
        }
    }
    val permitted = context.checkSelfPermission(audioLibraryPermission()) == PackageManager.PERMISSION_GRANTED
    var deviceCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(permitted) {
        if (permitted) deviceCount = LocalAudioRepository(context).list().getOrNull()?.size
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ShortcutTile("Downloads", downloads?.label ?: " ", Modifier.weight(1f).testTag("library_downloads"),
            { model.push("downloads") }) { CoverCollage(downloads?.covers.orEmpty(), Icons.Rounded.DownloadDone) }
        ShortcutTile("On device", when {
            !permitted -> "Allow access"
            deviceCount == null -> " "
            else -> songCountLabel(deviceCount!!, "file")
        }, Modifier.weight(1f).testTag("library_device"), { model.push("device") }) {
            Box(Modifier.fillMaxSize().background(Pink.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = Pink, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable private fun ShortcutTile(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit,
    visual: @Composable () -> Unit) {
    Row(modifier.height(58.dp).clip(RoundedCornerShape(16.dp)).background(White.copy(alpha = .06f))
        .clickable(onClick = hapticClick(onClick)).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) { visual() }
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Up to four covers in a 2×2 grid; one cover fills the square; none shows [fallback]. */
@Composable private fun CoverCollage(covers: List<String>, fallback: ImageVector) {
    when (covers.size) {
        0 -> Box(Modifier.fillMaxSize().background(White.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
            Icon(fallback, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
        1, 2, 3 -> AsyncImage(covers.first(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Raised))
        else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            covers.chunked(2).forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight().background(Raised)) }
                }
            }
        }
    }
}
