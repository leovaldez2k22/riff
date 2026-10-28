package com.musicglass.personal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

/** Feature 34.4: what the player's lyric row shows at a moment. */
internal sealed interface PlayerLyricRow {
    /** A line of the lyrics; [past] when it is the last line sung rather than one being sung. */
    data class Line(val text: String, val past: Boolean) : PlayerLyricRow
    /** An instrumental stretch, [progress] of the way through. */
    data class Gap(val progress: Float) : PlayerLyricRow
}

internal const val PLAYER_LYRIC_POLL_MS = 250L

/**
 * The row for [document] at [atMs]: the line being sung; between lines (and before the first) the last one sung
 * or the first to come, dimmed; the dots during an instrumental stretch; the first line of untimed lyrics. Null, and
 * no row, for an instrumental song or no lyrics.
 */
internal fun playerLyricRow(document: LyricsDocument?, atMs: Long): PlayerLyricRow? {
    if (document == null || document.instrumental) return null
    val sung = document.lines.filter { it.text.isNotBlank() }
    if (sung.isEmpty()) return null
    if (!document.timed) return PlayerLyricRow.Line(sung.first().text, past = false)
    val lines = withInstrumentalGaps(document.lines)
    lines.firstOrNull { it.gap && atMs >= it.startMs && atMs < it.endMs }?.let {
        return PlayerLyricRow.Gap(((atMs - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f))
    }
    val active = activeLyricLine(lines, atMs)
    if (active >= 0) return PlayerLyricRow.Line(lines[active].text, past = false)
    val last = lines.lastOrNull { !it.gap && it.text.isNotBlank() && it.startMs in 0..atMs }
    return PlayerLyricRow.Line((last ?: sung.first()).text, past = true)
}

/**
 * The current lyric line just above the seek bar. Tapping opens the full lyrics. It reads the lyrics the player has
 * already asked for, and the position only while it is shown.
 */
@Composable
internal fun PlayerLyricLine(playback: PlaybackGateway, provider: YouTubeMusicProvider, visible: Boolean, onLyrics: () -> Unit,
    modifier: Modifier = Modifier) {
    val state = playback.state
    val context = LocalContext.current
    val repository = remember(context) { LyricsRepository.get(context) }
    val track = remember(state.mediaId, state.title, state.artist, state.album, state.durationMs) {
        LyricsTrack(state.mediaId, state.title, state.artist, state.album, state.durationMs)
    }
    var document by remember(track) { mutableStateOf<LyricsDocument?>(null) }
    LaunchedEffect(track) {
        if (state.mediaId.isEmpty() || state.live || state.video) return@LaunchedEffect
        repository.observe(track) { provider.lyricsFor(track.mediaId.removePrefix("yt:"), track.durationMs) }
            .collect { document = it.document }
    }
    var position by remember(state.mediaId) { mutableLongStateOf(playback.currentPositionMs()) }
    LaunchedEffect(visible, state.mediaId, state.playing) {
        position = playback.currentPositionMs()
        while (visible && state.playing) { delay(PLAYER_LYRIC_POLL_MS); position = playback.currentPositionMs() }
    }
    val row = if (visible) playerLyricRow(document, position + DEFAULT_LYRICS_OFFSET_MS) else null
    val motion = LocalMotionPolicy.current
    AnimatedVisibility(row != null, modifier,
        enter = fadeIn(GlassMotion.timed(GlassMotion.selectionMs, motion)), exit = fadeOut(GlassMotion.exit(GlassMotion.selectionMs, motion))) {
        val shown = row ?: return@AnimatedVisibility
        val spoken = (shown as? PlayerLyricRow.Line)?.text ?: string(R.string.instrumental)
        Row(Modifier.fillMaxWidth().heightIn(min = RiffPlayer.circle).clickable(onClick = hapticClick(onLyrics))
            .semantics(mergeDescendants = true) { contentDescription = string(R.string.lyrics_x_open_full_lyrics, spoken) }
            .testTag("player_lyric_line"), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(shown, Modifier.weight(1f, fill = false), label = "player lyric line",
                contentKey = { (it as? PlayerLyricRow.Line)?.text ?: "gap" },
                transitionSpec = {
                    if (motion.reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                    else (fadeIn(GlassMotion.timed(GlassMotion.selectionMs, motion)) + slideInVertically(GlassMotion.timed(GlassMotion.selectionMs, motion)) { it / 4 }) togetherWith
                        fadeOut(GlassMotion.exit(GlassMotion.selectionMs, motion))
                }) { current ->
                when (current) {
                    is PlayerLyricRow.Line -> Text(current.text, color = White.copy(alpha = if (current.past) .55f else .92f),
                        fontSize = RiffPlayer.lyricLine, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    is PlayerLyricRow.Gap -> Canvas(Modifier.width(RiffPlayer.lyricDot * (LYRIC_GAP_DOTS * 2 - 1)).heightIn(min = RiffPlayer.lyricDot)) {
                        val radius = RiffPlayer.lyricDot.toPx() / 2f
                        repeat(LYRIC_GAP_DOTS) { dot ->
                            drawCircle(White.copy(alpha = .3f + .62f * lyricGapDotFill(current.progress, dot)), radius,
                                Offset(radius + dot * radius * 4, size.height / 2f))
                        }
                    }
                }
            }
            Text("›", color = White.copy(alpha = .55f), fontSize = RiffPlayer.lyricLine, modifier = Modifier.padding(start = RiffSpace.tight))
        }
    }
}
