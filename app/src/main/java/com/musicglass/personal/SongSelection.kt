package com.musicglass.personal

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Feature 22 (owner, Q22): several songs picked in one list and acted on together, in the order they were tapped.
 * Each pick is one row ([key], an occurrence: the same song twice in a playlist is two picks). Kept only while the
 * list is on screen; Back, Close, an account change or leaving the list ends it.
 */
@Stable
internal class SongSelection {
    private val picked = mutableStateListOf<Pair<String, ProviderTrack>>()
    val active get() = picked.isNotEmpty()
    val count get() = picked.size
    /** The picks in tap order. */
    val tracks: List<ProviderTrack> get() = picked.map { it.second }
    operator fun contains(key: String) = picked.any { it.first == key }
    fun toggle(key: String, track: ProviderTrack) {
        val at = picked.indexOfFirst { it.first == key }
        if (at >= 0) picked.removeAt(at) else picked.add(key to track)
    }
    fun clear() = picked.clear()
}

internal val LocalSongSelection = staticCompositionLocalOf<SongSelection?> { null }

/**
 * What Play next adds: the picks in tap order, each song once, leaving out songs already in the queue (the queue
 * keeps its own copy where it is). [skipped] counts the picks left out.
 */
internal data class PlayNextPlan(val tracks: List<ProviderTrack>, val skipped: Int)
internal fun playNextPlan(picks: List<ProviderTrack>, queued: Collection<String>): PlayNextPlan {
    val seen = queued.mapNotNullTo(HashSet()) { it.removePrefix("yt:").takeIf { _ -> it.startsWith("yt:") } }
    val tracks = picks.filter { !it.isLive && seen.add(it.videoId) }
    return PlayNextPlan(tracks, picks.size - tracks.size)
}

/** The result of adding several songs to one playlist. */
internal data class PlaylistSongsResult(val added: Int, val alreadyThere: Int)

/**
 * Adds [tracks] to one playlist, in order: read it whole once, refuse a read-only or partly loaded one, leave songs
 * already there (or picked twice) alone. Stops at the first failed add, saying how many went in before it.
 */
internal suspend fun addTracksToPlaylist(reader: ProviderCatalogReader, mutations: ProviderMutations, id: String, name: String,
    tracks: List<ProviderTrack>, still: () -> Boolean = { true }): Result<PlaylistSongsResult> =
    reader.completeCollection(id).fold(onSuccess = { page ->
        when {
            !page.editable -> Result.failure(IllegalStateException("$name is read-only"))
            page.truncation != null -> Result.failure(IllegalStateException("$name is too large for Riff to check for these songs"))
            else -> {
                val there = page.albumTracks.mapTo(HashSet()) { it.videoId }
                var added = 0; var skipped = 0
                for (track in tracks) {
                    if (!there.add(track.videoId)) { skipped++; continue }
                    if (!still()) return@fold Result.failure(IllegalStateException(string(R.string.account_changed)))
                    mutations.addPlaylistSong(id, track.videoId).onFailure { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        return@fold Result.failure(if (added == 0) error else IllegalStateException(
                            string(R.string.selection_partly_added, added, name, error.message.orEmpty().trimEnd('.')), error))
                    }
                    added++
                }
                Result.success(PlaylistSongsResult(added, skipped))
            }
        }
    }, onFailure = { Result.failure(it) })

/**
 * Gives [content] a selection that ends with the account, Back or [listKey] changing, and shows the action bar over
 * it while anything is picked.
 */
@Composable internal fun SongSelectionHost(listKey: Any?, provider: YouTubeMusicProvider, playback: PlaybackGateway,
    onNotice: (String) -> Unit, content: @Composable () -> Unit) {
    // Sorting, filtering or paging the list keeps every pick: each holds its own song.
    val selection = remember(listKey, provider.authRevision) { SongSelection() }
    BackHandler(selection.active) { uiHaptic(HapticCue.Dismiss); selection.clear() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSongSelection provides selection) { content() }
        // Owner, 10 November 2026: a glass bar that slides down, not a grey strip. It is a window of its own, as every
        // pop-out is: glass drawn inside the page it reads drew itself into itself until the render thread's stack ran
        // out (the phone, 10 November). Not focusable, so the list under it still scrolls and Back still clears.
        val policy = LocalMotionPolicy.current
        val shown = remember { androidx.compose.animation.core.MutableTransitionState(false) }
        shown.targetState = selection.active
        val density = androidx.compose.ui.platform.LocalDensity.current
        val top = WindowInsets.statusBars.getTop(density)
        if (shown.currentState || shown.targetState) androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter,
            offset = androidx.compose.ui.unit.IntOffset(0, top),
            properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false)) {
            AnimatedVisibility(shown,
                enter = slideInVertically(GlassMotion.timed(GlassMotion.selectionMs, policy)) { -it } + fadeIn(GlassMotion.timed(GlassMotion.selectionMs, policy)),
                exit = slideOutVertically(GlassMotion.exit(GlassMotion.selectionMs, policy)) { -it } + fadeOut(GlassMotion.exit(GlassMotion.selectionMs, policy))) {
                SongSelectionBar(selection, provider, playback, onNotice,
                    Modifier.padding(horizontal = RiffSpace.rowGap, vertical = RiffSpace.item))
            }
        }
    }
}

@Composable private fun SongSelectionBar(selection: SongSelection, provider: YouTubeMusicProvider, playback: PlaybackGateway,
    onNotice: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<List<ProviderTrack>?>(null) }
    picking?.let { PlaylistPicker(it, { picking = null }, onNotice) }
    val count = selection.count
    val shape = RoundedCornerShape(RiffRadius.card)
    // The count has a line of its own, so it never squeezes to one letter's width beside the actions (owner's screenshot).
    Column(modifier.fillMaxWidth().clip(shape).frostedPopupBackground(RiffRadius.card)
        .padding(horizontal = RiffSpace.tight, vertical = RiffSpace.tight).testTag("selection_bar")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ uiHaptic(HapticCue.Dismiss); selection.clear() }, modifier = Modifier.testTag("selection_close")) {
                Icon(Icons.Rounded.Close, string(R.string.selection_clear), tint = White) }
            Text(quantity(R.plurals.n_selected, count), color = White, style = RiffText.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag("selection_count"))
        }
        Row(Modifier.fillMaxWidth()) {
            SelectionAction(Icons.AutoMirrored.Rounded.QueueMusic, string(R.string.selection_play_next), playback is MusicPlaybackGateway, "selection_play_next") {
                val plan = playNextPlan(selection.tracks, playback.state.queue.map { it.mediaId })
                if (plan.tracks.isNotEmpty()) (playback as? MusicPlaybackGateway)?.enqueueSongs(plan.tracks)
                uiHaptic(if (plan.tracks.isEmpty()) HapticCue.Reject else HapticCue.Queue)
                onNotice(listOfNotNull(quantity(R.plurals.n_playing_next, plan.tracks.size),
                    if (plan.skipped > 0) quantity(R.plurals.n_already_in_queue, plan.skipped) else null).joinToString(" "))
                selection.clear()
            }
            SelectionAction(Icons.Rounded.Download, string(R.string.selection_download), true, "selection_download") {
                val picks = selection.tracks
                scope.launch {
                    val songs = picks.filter { !it.isLive && it.isMusic }
                    val count = withContext(Dispatchers.IO) { OfflineMusicStore.get(context).enqueueSongs(songs) }
                    uiHaptic(if (count > 0) HapticCue.Download else HapticCue.Reject)
                    onNotice(listOfNotNull(quantity(R.plurals.n_songs_downloading, count),
                        (picks.size - count).takeIf { it > 0 }?.let { quantity(R.plurals.n_not_downloadable_or_repeated, it) }).joinToString(" "))
                }
                selection.clear()
            }
            SelectionAction(Icons.AutoMirrored.Rounded.PlaylistAdd, string(R.string.selection_add_to_playlist), provider.signedIn, "selection_playlist") {
                uiHaptic(); picking = selection.tracks.filter { it.isMusic && !it.isLive }
            }
        }
    }
}

/** One of the bar's actions: an icon over its name, a third of the bar each. */
@Composable private fun RowScope.SelectionAction(icon: ImageVector, label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val tint = if (enabled) Pink else Muted
    Column(Modifier.weight(1f).heightIn(min = RiffSelection.action).clip(RoundedCornerShape(RiffRadius.card))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = RiffSpace.tight).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = tint)
        Text(label, color = tint, style = RiffText.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = RiffSpace.tight))
    }
}

/**
 * Owner, 10 November 2026: while songs are being selected, a row moves its content right to open a gutter with a round
 * check, which fills pink with a white tick when the song is picked; leaving selection closes it again. Replaces the pink
 * tint. Drawn, not composed, so a long list pays one draw call per row.
 */
@Composable internal fun Modifier.selectionCheck(picking: Boolean, picked: Boolean): Modifier {
    val policy = LocalMotionPolicy.current
    val open by animateFloatAsState(if (picking) 1f else 0f, GlassMotion.timed(GlassMotion.selectionMs, policy), label = "selection gutter")
    val fill by animateFloatAsState(if (picked) 1f else 0f, GlassMotion.pop(policy), label = "selection check")
    if (open == 0f) return this
    return this.drawWithContent {
        drawContent()
        val gutter = RiffSelection.gutter.toPx()
        val radius = RiffSelection.check.toPx() / 2
        val center = Offset(gutter * open - gutter / 2, size.height / 2)
        drawCircle(White.copy(alpha = .55f * open * (1f - fill)), radius, center, style = Stroke(RiffSelection.stroke.toPx()))
        if (fill > 0f) {
            drawCircle(Pink.copy(alpha = open), radius * fill.coerceAtMost(1.1f), center)
            val tick = Path().apply {
                moveTo(center.x - radius * .42f, center.y + radius * .02f)
                lineTo(center.x - radius * .1f, center.y + radius * .34f)
                lineTo(center.x + radius * .45f, center.y - radius * .3f)
            }
            drawPath(tick, Color.White.copy(alpha = open * fill.coerceIn(0f, 1f)),
                style = Stroke(RiffSelection.stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }.padding(start = RiffSelection.gutter * open)
}
