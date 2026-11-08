package com.musicglass.personal

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

/** Opus, AAC, … from the player's sample type. */
internal fun codecName(mime: String?): String? = when {
    mime == null -> null
    mime.contains("opus") -> "Opus"
    mime.contains("mp4a") || mime.contains("aac") -> "AAC"
    mime.contains("vorbis") -> "Vorbis"
    mime.contains("mpeg") -> "MP3"
    mime.contains("flac") -> "FLAC"
    else -> mime.substringAfter('/').uppercase()
}

/** "Opus · 48.0 kHz · 270 kbps · Stereo" from the stats for nerds figures; null until the player knows the format. */
internal fun nerdStatsLine(stats: Bundle): String? {
    val codec = codecName(stats.getString("mime")) ?: return null
    val parts = mutableListOf(codec)
    stats.getInt("sampleRate").takeIf { it > 0 }?.let { parts += "%.1f kHz".format(java.util.Locale.ROOT, it / 1000f) }
    (stats.getInt("formatBitrate").takeIf { it > 0 } ?: stats.getInt("streamBitrate").takeIf { it > 0 })?.let { parts += "${(it + 500) / 1000} kbps" }
    when (stats.getInt("channels")) { 1 -> parts += string(R.string.mono); 2 -> parts += string(R.string.stereo); 0 -> Unit; else -> parts += "${stats.getInt("channels")} ch" }
    return parts.joinToString(" · ")
}

private const val PLAYER_STATS_POLL_MS = 2_000L

/** The format line over the art's lower edge, shown only with "Show stats for nerds" on. */
@Composable
internal fun PlayerStatsLine(playback: PlaybackGateway, visible: Boolean, modifier: Modifier = Modifier) {
    var line by remember(playback.state.mediaId) { mutableStateOf<String?>(null) }
    LaunchedEffect(visible, playback.state.mediaId) {
        while (visible) { playback.nerdStats()?.let(::nerdStatsLine)?.let { line = it }; delay(PLAYER_STATS_POLL_MS) }
    }
    val shown = line.takeIf { visible } ?: return
    Text(shown, color = White.copy(alpha = .65f), fontSize = RiffPlayer.statsLine, textAlign = TextAlign.Center,
        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 4f)),
        modifier = modifier.fillMaxWidth().testTag("player_stats_line"))
}

