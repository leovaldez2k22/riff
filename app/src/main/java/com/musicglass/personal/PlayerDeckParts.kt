package com.musicglass.personal

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

/*
 * Feature 34.3: the smaller parts of the redesigned player. All are white at a set opacity over the backdrop.
 */

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

/** What streams ask for on the network in use: "High quality", "Medium quality", "Low quality" or "Data saver". */
internal fun playerQualityLabel(context: Context): String {
    val prefs = context.applicationContext.getSharedPreferences(DATA_SAVER_PREFS, Context.MODE_PRIVATE)
    if (prefs.getBoolean("enabled", false)) return string(R.string.data_saver)
    val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
    return string(when (networkSongQuality(prefs, metered)) {
        SongQuality.High -> R.string.quality_high; SongQuality.Medium -> R.string.quality_medium; SongQuality.Low -> R.string.quality_low
    })
}

/** The middle of the time row: a headphone and the quality streams ask for, at 45 %. Read when the song changes. */
@Composable
internal fun PlayerQualityLabel(mediaId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(mediaId) { playerQualityLabel(context) }
    Row(modifier.testTag("player_quality"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RiffSpace.tight)) {
        Icon(Icons.Rounded.Headphones, null, tint = White.copy(alpha = .45f), modifier = Modifier.size(RiffPlayer.qualityIcon))
        Text(label, color = White.copy(alpha = .45f), fontSize = RiffPlayer.qualityText, fontWeight = FontWeight.Medium)
    }
}

/** Shuffle and repeat when on: full white with a small dot under, rather than the old pink. */
@Composable
internal fun ActiveDot(active: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(RiffPlayer.activeDot).clip(CircleShape).background(if (active) White else Color.Transparent))
    }
}
