package com.musicglass.personal

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun OnDeviceScreen(model: ShellModel, bottom: androidx.compose.ui.unit.Dp,
    playback: PlaybackGateway) {
    val motion = LocalMotionPolicy.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember(context) { LocalAudioRepository(context.applicationContext) }
    val audioPermission = remember { audioLibraryPermission() }
    var granted by remember { mutableStateOf(context.checkSelfPermission(
        audioPermission) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<List<LocalAudioTrack>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasAccess = context.checkSelfPermission(audioPermission) ==
                    PackageManager.PERMISSION_GRANTED
                if (!hasAccess && granted) {
                    granted = false
                    denied = true
                    tracks = emptyList()
                } else if (hasAccess) {
                    granted = true
                    refreshKey++
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(granted, refreshKey) {
        if (!granted) { tracks = emptyList(); return@LaunchedEffect }
        if (context.checkSelfPermission(audioPermission) !=
            PackageManager.PERMISSION_GRANTED) {
            granted = false
            denied = true
            tracks = emptyList()
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        repository.list().fold(onSuccess = { tracks = it }, onFailure = {
            tracks = emptyList()
            if (it is SecurityException) {
                granted = false
                denied = true
            } else loadError = "Could not read this device's audio library."
        })
        loading = false
    }
    val index = remember(tracks) { CatalogIndex(tracks.map { track ->
        CatalogEntry("local-${track.id}", track.title, track.artist, track.album)
    }) }
    val results = remember(index, query) { index.search(query) }
    ScreenList(model, bottom) {
        item { BackHeading("Library", model::back) }
        item { ScreenTitle("On device", "Audio files indexed by Android on this phone") }
        if (!granted) {
            item {
                Text(if (denied) "Audio access was denied. You can allow it when ready."
                    else "Allow audio access to show files stored on this phone.", color = Muted)
            }
            item { Button(onClick = { uiHaptic();  permission.launch(audioPermission) },
                modifier = Modifier.testTag("request_audio_permission")) {
                Text("Allow audio access")
            } }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${tracks.size} audio ${if (tracks.size == 1) "file" else "files"}",
                        color = Muted, modifier = Modifier.weight(1f))
                    Text("Refresh", color = Pink, modifier = Modifier.clickable { uiHaptic();  refreshKey++ }
                        .padding(12.dp).testTag("device_refresh"))
                }
            }
            item { OutlinedTextField(query, onValueChange = { query = it },
                singleLine = true, label = { Text("Search on-device audio") },
                modifier = Modifier.fillMaxWidth().testTag("device_search")) }
            if (loading) item { Text("Reading audio metadata\u2026", color = Muted) }
            loadError?.let { error -> item { Text(error, color = Pink) } }
            if (!loading && loadError == null && results.isEmpty()) item {
                Text(if (tracks.isEmpty()) "No music files were found on this phone."
                    else "No audio files match this search.", color = Muted)
            }
            items(results, key = { tracks[it].id }) { position ->
                val track = tracks[position]
                Row(Modifier.fillMaxWidth().height(64.dp)
                    .then(if (motion.reduced) Modifier else Modifier.animateItem())
                    .motionClickable { playback.playLocal(track) }
                    .testTag("local_track_${track.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${track.title}",
                        tint = Pink, modifier = Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("${track.artist} \u00B7 ${track.album}", color = Muted,
                            fontSize = 12.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
