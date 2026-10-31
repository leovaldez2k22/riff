package com.musicglass.personal

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.annotation.StringRes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.delay

/** Feature 16: asks the service for what is playing right now, at the format level. Riff's own controller only. */
internal const val GET_NERD_STATS = "com.musicglass.personal.GET_NERD_STATS"
internal const val NERD_STATS_REFRESH_MS = 1_000L

/** Decoder, dropouts and the network estimate, which the player only reports as events. Dropouts count per song. */
@UnstableApi
internal class NerdStatsCollector : AnalyticsListener {
    var decoder = ""; private set
    var underruns = 0; private set
    var bandwidthBps = 0L; private set
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }
    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) { underruns++ }
    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidthBps = bitrateEstimate }
    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: MediaItem?, reason: Int) { underruns = 0 }
}

/** How the song reaches the player, from its address; signed URLs themselves are never read out. */
internal fun nerdDelivery(scheme: String?, mimeType: String?): String = when {
    scheme == "sabr" -> "SABR"
    mimeType?.contains("mpegurl", true) == true -> "HLS"
    scheme == "https" -> "Direct"
    scheme == "file" || scheme == "content" -> "On this phone"
    else -> scheme.orEmpty()
}

@UnstableApi
internal fun nerdStatsBundle(player: ExoPlayer, collector: NerdStatsCollector, blend: AutomixNote? = null): Bundle = Bundle().apply {
    val item = player.currentMediaItem
    val extras = item?.mediaMetadata?.extras
    val format = player.audioFormat
    putString("delivery", nerdDelivery(item?.localConfiguration?.uri?.scheme, item?.localConfiguration?.mimeType))
    putInt("itag", extras?.getInt("itag") ?: 0)
    putInt("streamBitrate", extras?.getInt("bitrate") ?: 0)
    putString("mime", format?.sampleMimeType ?: format?.containerMimeType)
    putString("codecs", format?.codecs)
    putInt("formatBitrate", format?.bitrate?.takeIf { it != Format.NO_VALUE } ?: 0)
    putInt("sampleRate", format?.sampleRate?.takeIf { it != Format.NO_VALUE } ?: 0)
    putInt("channels", format?.channelCount?.takeIf { it != Format.NO_VALUE } ?: 0)
    putString("decoder", collector.decoder)
    putInt("underruns", collector.underruns)
    putLong("bandwidth", collector.bandwidthBps)
    val position = player.currentPosition
    putLong("bufferAhead", (player.bufferedPosition - position).coerceAtLeast(0))
    putFloat("speed", player.playbackParameters.speed)
    putFloat("volume", player.volume)
    putInt("audioSession", player.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET } ?: 0)
    // Automix: the last smart blend planned, the "Automix · …" line of the plan (Feature 34.3).
    blend?.let { putString("blendMode", it.mode.wire); putLong("blendMs", it.blendMs); putBoolean("blendOnBeat", it.onBeat) }
}

internal data class NerdRow(@StringRes val label: Int, val value: String)

private fun kbps(bitsPerSecond: Long) = "${(bitsPerSecond + 500) / 1000} kbps"

/** The panel's rows, in reading order; a value the player hasn't reported yet is left out rather than shown as 0. */
internal fun nerdStatsRows(stats: Bundle): List<NerdRow> = buildList {
    stats.getString("delivery")?.takeIf { it.isNotEmpty() }?.let { add(NerdRow(R.string.nerd_delivery, it)) }
    stats.getInt("itag").takeIf { it > 0 }?.let { add(NerdRow(R.string.nerd_itag, it.toString())) }
    val codec = listOfNotNull(stats.getString("mime"), stats.getString("codecs")?.takeIf { it.isNotEmpty() }).joinToString(" · ")
    if (codec.isNotEmpty()) add(NerdRow(R.string.nerd_codec, codec))
    (stats.getInt("formatBitrate").takeIf { it > 0 } ?: stats.getInt("streamBitrate").takeIf { it > 0 })
        ?.let { add(NerdRow(R.string.nerd_bitrate, kbps(it.toLong()))) }
    val rate = stats.getInt("sampleRate"); val channels = stats.getInt("channels")
    if (rate > 0) add(NerdRow(R.string.nerd_sample_rate, if (channels > 0) "$rate Hz · $channels ch" else "$rate Hz"))
    stats.getString("decoder")?.takeIf { it.isNotEmpty() }?.let { add(NerdRow(R.string.nerd_decoder, it)) }
    add(NerdRow(R.string.nerd_buffer, "%.1f s".format(java.util.Locale.ROOT, stats.getLong("bufferAhead") / 1000.0)))
    stats.getLong("bandwidth").takeIf { it > 0 }?.let { add(NerdRow(R.string.nerd_network, kbps(it))) }
    add(NerdRow(R.string.nerd_dropouts, stats.getInt("underruns").toString()))
    add(NerdRow(R.string.playback_speed, playbackSpeedLabel(stats.getFloat("speed", 1f))))
    add(NerdRow(R.string.nerd_volume, "${(stats.getFloat("volume", 1f) * 100).toInt()} %"))
    stats.getInt("audioSession").takeIf { it > 0 }?.let { add(NerdRow(R.string.nerd_audio_session, it.toString())) }
    stats.getString("blendMode")?.let { mode ->
        val label = when { stats.getBoolean("blendOnBeat") -> R.string.nerd_blend_on_beat; mode == BlendMode.Automix.wire -> R.string.nerd_blend_automix
            else -> R.string.nerd_blend_smart }
        add(NerdRow(label, "%.1f s".format(java.util.Locale.ROOT, stats.getLong("blendMs") / 1000.0)))
    }
}

/** Plain text for the Copy button, one "label: value" a line. */
internal fun nerdStatsText(rows: List<NerdRow>, label: (Int) -> String): String = rows.joinToString("\n") { "${label(it.label)}: ${it.value}" }

@Composable
internal fun NerdStatsDialog(playback: PlaybackGateway, onNotice: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<NerdRow>?>(null) }
    LaunchedEffect(Unit) {
        while (true) { rows = playback.nerdStats()?.let(::nerdStatsRows) ?: emptyList(); delay(NERD_STATS_REFRESH_MS) }
    }
    FrostedAlertDialog(onDismissRequest = onDismiss, title = { Text(string(R.string.stats_for_nerds)) }, text = {
        Column(Modifier.testTag("nerd_stats")) {
            val shown = rows
            when {
                shown == null -> Text(string(R.string.loading), color = Muted)
                shown.isEmpty() -> Text(string(R.string.nerd_stats_unavailable), color = Muted)
                else -> shown.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = RiffNerdStats.rowGap)) {
                        Text(string(row.label), color = Muted, fontSize = RiffType.detail, modifier = Modifier.width(RiffNerdStats.label))
                        Text(row.value, fontSize = RiffType.detail, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }, dismissButton = {
        TextButton({
            uiHaptic()
            val text = nerdStatsText(rows.orEmpty()) { string(it) }
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText(string(R.string.stats_for_nerds), text))
            onNotice(string(R.string.nerd_stats_copied))
        }, enabled = !rows.isNullOrEmpty()) { Text(string(R.string.copy)) }
    }, confirmButton = { TextButton({ uiHaptic(); onDismiss() }) { Text(string(R.string.done)) } })
}
