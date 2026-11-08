package com.musicglass.personal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.PlanarYUVLuminanceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** The friend's song names are looked up here, and carry on if the dialog closes. */
private val blendLabelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
/** Names looked up at once: quick for 100 songs, gentle on YouTube Music. */
private const val BLEND_LOOKUPS = 6
/** How long the blend waits for names before it is kept; any still missing fill in afterwards. */
private const val BLEND_LOOKUP_WAIT_MS = 30_000L

private enum class BlendStage { Preview, Code, Scan }

/** What the panel says after a scan: the saved blend, or why a code was not taken. */
private sealed interface BlendOutcome {
    data class Saved(val list: PhonePlaylist, val shared: Int) : BlendOutcome
    data class Problem(val text: Int) : BlendOutcome
    /** The friend's song names being looked up before the blend is kept: [done] of [total]. */
    data class Looking(val done: Int, val total: Int) : BlendOutcome
}

/** Feature 24 (Riff+): "Blend with a friend" on Phone playlists. */
@Composable internal fun BlendEntry(provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ToolLink(string(R.string.blend_with_friend)) { uiHaptic(); open = true }
    if (open) BlendDialog(provider, playback, onNotice) { open = false }
}

@Composable private fun BlendDialog(provider: YouTubeMusicProvider, playback: PlaybackGateway, onNotice: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val process = remember(context) { AppContainer.get(context) }
    val owner = remember(provider.authRevision, provider.storageRevision) { provider.storageScopeKey() }
    val scopes = remember(owner) { listOfNotNull(owner.takeIf { it != UNVERIFIED_SCOPE_KEY }, DEVICE_LOCAL_SCOPE_KEY) }
    val mine by produceState<List<BlendEntry>?>(null, scopes) {
        value = withContext(Dispatchers.IO) {
            val never = NeverPlayStore.get(context).set(scopes.first())
            blendSnapshot(process.libraryState.readableDatabase, scopes, LocalDate.now()) { never.blocks(it, null) }
        }
    }
    var stage by remember { mutableStateOf(BlendStage.Preview) }
    var outcome by remember { mutableStateOf<BlendOutcome?>(null) }
    val songs = mine
    val enough = songs != null && songs.size >= BLEND_MIN_SONGS
    val code = remember(songs) { songs?.takeIf { it.size >= BLEND_MIN_SONGS }?.let { encodeBlend(it.map(BlendEntry::id)) } }

    fun scanned(text: String) {
        if (outcome is BlendOutcome.Saved || outcome is BlendOutcome.Looking) return
        when (val read = decodeBlend(text)) {
            is BlendRead.Refused -> outcome = BlendOutcome.Problem(when (read.problem) {
                BlendCodeProblem.NotABlend -> R.string.blend_not_a_blend
                BlendCodeProblem.NewerVersion -> R.string.blend_newer_version
                BlendCodeProblem.Damaged -> R.string.blend_damaged
            })
            is BlendRead.Songs -> {
                val own = songs.orEmpty().map(BlendEntry::id)
                if (read.ids == own) { outcome = BlendOutcome.Problem(R.string.blend_own_code); return }
                // The names come first, so the list and a queue played from it show them (owner, 9 November).
                outcome = BlendOutcome.Looking(0, 0)
                val app = context.applicationContext; val mineNow = songs.orEmpty()
                blendLabelScope.launch {
                    val saved = makeBlend(app, provider, owner, mineNow, read.ids) { done, total ->
                        blendLabelScope.launch(Dispatchers.Main) { if (outcome is BlendOutcome.Looking) outcome = BlendOutcome.Looking(done, total) }
                    }
                    withContext(Dispatchers.Main) {
                        outcome = if (saved == null) BlendOutcome.Problem(R.string.blend_not_saved) else { uiHaptic(HapticCue.Confirm); saved }
                    }
                }
            }
        }
    }

    val saved = outcome as? BlendOutcome.Saved
    val looking = outcome as? BlendOutcome.Looking
    FrostedAlertDialog(onDismissRequest = onDismiss, title = { Text(string(R.string.blend_with_friend)) }, text = {
        Column(Modifier.fillMaxWidth().testTag("blend_panel")) {
            when {
                songs == null -> Text(string(R.string.loading), color = Muted)
                !enough -> Text(string(R.string.blend_too_few, BLEND_MIN_SONGS), color = Muted, modifier = Modifier.testTag("blend_too_few"))
                looking != null -> Text(string(R.string.blend_looking, looking.done, looking.total), color = White, modifier = Modifier.testTag("blend_looking"))
                saved != null -> {
                    Text(string(R.string.blend_saved, saved.list.name, saved.list.songs.size, saved.shared), color = White, modifier = Modifier.testTag("blend_saved"))
                    Text(string(R.string.blend_saved_detail), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                }
                stage == BlendStage.Preview -> {
                    Text(string(R.string.blend_preview, songs.size), color = White)
                    Text(string(R.string.blend_privacy), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(vertical = RiffSpace.tight))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = RiffBlend.previewMax).testTag("blend_preview")) {
                        itemsIndexed(songs, key = { _, s -> s.id }) { i, s ->
                            Text("${i + 1}. ${s.title.ifBlank { s.id }} · ${s.artist}", color = White, fontSize = RiffType.detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                stage == BlendStage.Code -> {
                    BlendQr(code!!, Modifier.fillMaxWidth().aspectRatio(1f).testTag("blend_code"))
                    Text(string(R.string.blend_code_help), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                }
                stage == BlendStage.Scan -> {
                    BlendScan(Modifier.fillMaxWidth().aspectRatio(1f).testTag("blend_scanner")) { scanned(it) }
                    (outcome as? BlendOutcome.Problem)?.let { Text(string(it.text), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight).testTag("blend_problem")) }
                        ?: Text(string(R.string.blend_scan_help), color = Muted, fontSize = RiffType.detail, modifier = Modifier.padding(top = RiffSpace.tight))
                }
            }
        }
    }, confirmButton = {
        when {
            looking != null -> Unit
            // The blend as kept now: a name found after it was saved is in the queue too.
            saved != null -> TextButton({ uiHaptic(); playback.playCollection(currentBlend(context, owner, saved.list).songs.map { it.track }, 0, saved.list.name, false); onNotice(string(R.string.blend_playing)); onDismiss() },
                Modifier.testTag("blend_play")) { Text(string(R.string.play)) }
            enough && stage != BlendStage.Code -> TextButton({ uiHaptic(); stage = BlendStage.Code }, Modifier.testTag("blend_show_code")) { Text(string(R.string.blend_show_code)) }
            enough -> TextButton({ uiHaptic(); outcome = null; stage = BlendStage.Scan }, Modifier.testTag("blend_scan")) { Text(string(R.string.blend_scan)) }
            else -> TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) }
        }
    }, dismissButton = {
        when {
            looking != null -> Unit
            saved != null -> TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) }
            enough && stage == BlendStage.Preview -> TextButton({ uiHaptic(); outcome = null; stage = BlendStage.Scan }, Modifier.testTag("blend_scan_first")) { Text(string(R.string.blend_scan)) }
            enough -> TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) }
            else -> Unit
        }
    })
}

/** The code, dark squares on white whatever the theme (a scanner reads that best), one pixel a square, scaled sharp. */
@Composable private fun BlendQr(text: String, modifier: Modifier) {
    val bitmap = remember(text) {
        val m = blendMatrix(text)
        val pixels = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(bitmap, string(R.string.blend_code_description), modifier.background(Color.White), filterQuality = FilterQuality.None)
}

/**
 * The scanner: the back camera only while this is on screen (bound to the screen's lifecycle, so it stops in the
 * background and is released on close). Frames are read for a code and dropped; none is kept.
 */
@Composable private fun BlendScan(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    when {
        !hasCamera -> Text(string(R.string.blend_no_camera), color = Muted, modifier = Modifier.testTag("blend_no_camera"))
        !granted -> Column {
            Text(string(if (asked) R.string.blend_camera_denied else R.string.blend_camera_why), color = Muted, modifier = Modifier.testTag("blend_camera_permission"))
            if (!asked) TextButton({ uiHaptic(); ask.launch(Manifest.permission.CAMERA) }) { Text(string(R.string.blend_allow_camera)) }
        }
        else -> BlendCamera(modifier, onText)
    }
}

@Composable private fun BlendCamera(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onText)
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycle) {
        val analysis = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var bound: ProcessCameraProvider? = null
        var last: String? = null
        future.addListener({
            val cameras = runCatching { future.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            // 1080p: a code of 100 songs has about 120 squares a side, too fine for the default 640×480.
            val frames = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            frames.setAnalyzer(analysis) { image ->
                val text = image.use(::qrInFrame)
                if (text != null && text != last) { last = text; main.execute { deliver(text) } }
            }
            runCatching { cameras.unbindAll(); cameras.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, frames); bound = cameras }
        }, main)
        onDispose { runCatching { bound?.unbindAll() }; analysis.shutdown() }
    }
    AndroidView({ view }, modifier)
}

/** The brightness plane of a camera frame, read for a QR code; the frame itself is not kept. */
private fun qrInFrame(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val width = image.width; val height = image.height
    val buffer = plane.buffer; val stride = plane.rowStride
    val luma = ByteArray(width * height)
    for (row in 0 until height) { buffer.position(row * stride); buffer.get(luma, row * width, width) }
    return readQr(PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false))
}

/**
 * Makes the blend of this phone's songs and [theirs] and keeps it in Phone playlists for [owner]. The friend's songs
 * are named first by a YouTube Music lookup (outside the exchange), [BLEND_LOOKUPS] at a time, [progress] told each
 * one done; after [BLEND_LOOKUP_WAIT_MS] it is kept anyway and any names still missing are filled in as they arrive.
 * Null when it could not be kept.
 */
private suspend fun makeBlend(context: Context, provider: YouTubeMusicProvider, owner: String, mine: List<BlendEntry>,
                              theirs: List<String>, progress: (Int, Int) -> Unit): BlendOutcome.Saved? {
    val blend = blendLists(mine.map(BlendEntry::id), theirs)
    val known = mine.associateBy(BlendEntry::id)
    val unknown = blend.map(BlendSong::id).filter { it !in known }
    val found = ConcurrentHashMap<String, ProviderTrack>()
    val done = AtomicInteger()
    progress(0, unknown.size)
    val gate = Semaphore(BLEND_LOOKUPS)
    val lookups = blendLabelScope.launch {
        unknown.map { id -> launch { gate.withPermit {
            blendSongName(provider, id)?.let { found[id] = it }
            progress(done.incrementAndGet(), unknown.size)
        } } }.joinAll()
    }
    withTimeoutOrNull(BLEND_LOOKUP_WAIT_MS) { lookups.join() }
    fun track(id: String) = known[id]?.let { ProviderTrack(id, it.title, it.artist) } ?: found[id] ?: ProviderTrack(id, "", "")
    val tracks = blend.map { track(it.id) }
    val name = string(R.string.blend_name, LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")))
    val store = PhonePlaylistStore.get(context)
    val list = store.create(owner, name, tracks) ?: run { lookups.cancel(); return null }
    if (tracks.any { it.title.isBlank() }) blendLabelScope.launch {
        lookups.join()
        store.update(owner, list.id) { p -> p.copy(songs = p.songs.map { s ->
            if (s.track.title.isBlank()) found[s.track.videoId]?.let { s.copy(track = it) } ?: s else s }) }
    }
    return BlendOutcome.Saved(list, blend.count { it.shared })
}

/** A song's name, artist and cover from YouTube Music's next-songs answer, which leads with the song itself. */
private suspend fun blendSongName(provider: YouTubeMusicProvider, id: String): ProviderTrack? =
    suspendCatching { provider.recommendations(id, null).getOrNull()?.first?.firstOrNull { it.videoId == id && it.title.isNotBlank() } }.getOrNull()

/** [list] as Phone playlists keeps it now, with any names found since it was saved. */
private fun currentBlend(context: Context, owner: String, list: PhonePlaylist): PhonePlaylist =
    PhonePlaylistStore.get(context).lists(owner).firstOrNull { it.id == list.id } ?: list
