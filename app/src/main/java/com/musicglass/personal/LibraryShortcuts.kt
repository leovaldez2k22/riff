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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val revision by store.revision.collectAsStateWithLifecycle()
    var downloads by remember { mutableStateOf<DownloadSummary?>(null) }
    LaunchedEffect(revision) {
        downloads = withContext(Dispatchers.IO) {
            val scope = store.currentScope
            val rows = store.rows(scope).filter { it.status != DownloadState.Metadata && it.status != DownloadState.Cancelled }
            val ready = rows.filter { store.audio(scope, it.track.videoId) != null }
            DownloadSummary(ready.size, rows.count { it.status?.pending == true },
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
        ShortcutTile("library:tile:downloads", "Downloads", downloads?.label ?: " ", Modifier.weight(1f).testTag("library_downloads"),
            { model.push(Route.Downloads) }) { CoverCollage(downloads?.covers.orEmpty(), Icons.Rounded.DownloadDone) }
        ShortcutTile("library:tile:device", "On device", when {
            !permitted -> "Allow access"
            deviceCount == null -> " "
            else -> songCountLabel(deviceCount!!, "file")
        }, Modifier.weight(1f).testTag("library_device"), { model.push(Route.DeviceAudio) }) {
            Box(Modifier.fillMaxSize().background(Pink.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PhoneAndroid, null, tint = Pink, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A Library tile. With glass on it is a liquid-glass shape like the filter chips: its bounds and corner go to the
 * scene shader and its face is drawn above it, while the cover and text stay in the page, inside the refracting rim.
 */
@Composable private fun ShortcutTile(key: String, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit,
    visual: @Composable () -> Unit) {
    val bounds = LocalGlassBounds.current; val chips = LocalGlassChips.current
    val glass = !LocalAccessibilitySettings.current.solidControls && bounds != null && chips != null
    val corner = with(LocalDensity.current) { 16.dp.toPx() }
    val body = remember(title, subtitle, visual) { @Composable { TileBody(title, subtitle, visual) } }
    SideEffect { chips?.let { val style = it[key]; if (style?.corner != corner || style.content !== body)
        it[key] = GlassChipStyle("", false, corner = corner, content = body) } }
    DisposableEffect(key, bounds, chips) { onDispose { bounds?.remove(key); chips?.remove(key) } }
    Box(modifier.height(58.dp).onGloballyPositioned { bounds?.set(key, it.transformedBoundsInRoot()) }
        .clip(RoundedCornerShape(16.dp)).then(if (glass) Modifier else Modifier.background(White.copy(alpha = .06f)))
        .clickable(onClick = hapticClick(onClick))) {
        Box(Modifier.graphicsLayer { alpha = if (glass) 0f else 1f }) { body() }
    }
}

@Composable private fun TileBody(title: String, subtitle: String, visual: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().padding(horizontal = 9.dp),
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
