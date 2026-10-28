package com.musicglass.personal

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Feature 19: casting to Chromecast and Google speakers. The speaker runs Google's Default Media Receiver and fetches
 * each song itself from a direct, header-free URL the phone resolved. The phone keeps Riff's queue: its own player is
 * paused while casting and follows the speaker's song, and the speaker is sent the current song and the next few.
 */

/** How many songs past the current one the speaker holds. */
internal const val CAST_AHEAD = 3

/** Google's Default Media Receiver; Riff's own notification and media session stay the only ones. */
class RiffCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build())
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** What a speaker can be given for a song: a URL it fetches itself, or why not. */
internal sealed interface CastSource {
    data class Direct(val url: String, val mimeType: String) : CastSource
    data class Refused(val reason: String) : CastSource
}

/**
 * A speaker can't send headers, speak SABR or reach the phone's files. Songs on the phone, and streams that need any
 * of those, are refused; the relay that would serve them from the phone is not built (see the plan's log).
 */
internal fun castSource(mediaId: String, stream: ProviderStream?): CastSource = when {
    !mediaId.startsWith("yt:") -> CastSource.Refused("on_device")
    stream == null -> CastSource.Refused("no_stream")
    stream.sabrBootstrap != null -> CastSource.Refused("sabr")
    stream.mimeType?.contains("mpegurl", ignoreCase = true) == true -> CastSource.Refused("hls")
    stream.boundedRange -> CastSource.Refused("bounded_range")
    !stream.url.startsWith("https://") -> CastSource.Refused("not_https")
    else -> CastSource.Direct(stream.url, stream.mimeType?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty) ?: "audio/mp4")
}

/** Where [entry] is in [entries]; of several, the one nearest [near]. -1 when it isn't there. */
internal fun nearestEntry(entries: List<String>, entry: String, near: Int): Int =
    if (entry.isEmpty()) -1 else entries.indices.filter { entries[it] == entry }.minByOrNull { kotlin.math.abs(it - near) } ?: -1

/** Riff's items to the receiver and back. The entry id travels in the custom data, so the phone can find the song. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal object RiffCastConverter : MediaItemConverter {
    override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
        val m = item.mediaMetadata
        val metadata = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            m.title?.let { putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, it.toString()) }
            m.artist?.let { putString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST, it.toString()) }
            m.albumTitle?.let { putString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            // The speaker fetches the picture itself: a file on the phone is no use to it.
            m.artworkUri?.takeIf { it.scheme == "https" }?.let { addImage(WebImage(Uri.parse(highQualityArtworkUrl(it.toString(), 720)))) }
        }
        val url = item.localConfiguration?.uri?.toString().orEmpty()
        val info = MediaInfo.Builder(url).setContentUrl(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(item.localConfiguration?.mimeType ?: "audio/mp4").setMetadata(metadata).build()
        val custom = JSONObject().put("mediaId", item.mediaId).put(ENTRY_ID, item.entryId())
            .put("artwork", m.artworkUri?.toString().orEmpty())
        return MediaQueueItem.Builder(info).setCustomData(custom).build()
    }

    override fun toMediaItem(item: MediaQueueItem): MediaItem {
        val info = item.media
        val custom = item.customData
        val cast = info?.metadata
        return MediaItem.Builder().setMediaId(custom?.optString("mediaId").orEmpty().ifEmpty { info?.contentId.orEmpty() })
            .setUri(info?.contentUrl ?: info?.contentId).setMimeType(info?.contentType)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(cast?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE))
                .setArtist(cast?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ARTIST))
                .setAlbumTitle(cast?.getString(com.google.android.gms.cast.MediaMetadata.KEY_ALBUM_TITLE))
                .setArtworkUri(custom?.optString("artwork")?.takeIf(String::isNotEmpty)?.let(Uri::parse))
                .setExtras(Bundle().apply { putString(ENTRY_ID, custom?.optString(ENTRY_ID).orEmpty()) }).build())
            .build()
    }
}

/**
 * Moves playback between the phone and a speaker, and keeps the speaker on the phone's queue while casting.
 * [local] is the phone's player; [streamFor] resolves a song's stream; [refused] tells the listener a song was skipped.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class CastSync(
    private val scope: CoroutineScope,
    private val local: () -> Player?,
    private val streamFor: suspend (MediaItem) -> ProviderStream?,
    private val events: (String) -> Unit,
    private val refused: (MediaItem) -> Unit,
) : CastPlayer.TransferCallback {
    var remote: RemoteCastPlayer? = null
        set(value) { field?.removeListener(remoteListener); field = value; value?.addListener(remoteListener) }
    /** True while a speaker plays. */
    var casting = false; private set
    /** Whether the listener wants the speaker playing; read when a load finishes, so a later press wins. */
    private var wantPlay = false
    private var load: Job? = null
    private var ahead: Job? = null

    override fun transferState(source: Player, target: Player) {
        when {
            target is RemoteCastPlayer -> start(source)
            source is RemoteCastPlayer -> stop(source, target)
            else -> CastPlayer.TransferCallback.DEFAULT.transferState(source, target)
        }
    }

    private fun start(source: Player) {
        wantPlay = source.playWhenReady
        val index = source.currentMediaItemIndex; val position = source.currentPosition
        source.pause()
        casting = true
        events("cast_started playing=$wantPlay")
        send(index, position)
    }

    private fun stop(source: Player, target: Player) {
        val entry = source.currentMediaItem?.entryId().orEmpty(); val position = source.currentPosition; val play = source.playWhenReady
        casting = false; load?.cancel(); ahead?.cancel()
        val index = nearestEntry(entries(target), entry, target.currentMediaItemIndex)
        events("cast_ended found=${index >= 0} playing=$play")
        if (index >= 0) target.seekTo(index, position)
        if (play) target.play()
    }

    private fun entries(player: Player) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).entryId() }

    /** The speaker's copy of a song, or null when it can't play it. */
    private suspend fun forSpeaker(item: MediaItem): MediaItem? = when (val source = castSource(item.mediaId, if (item.mediaId.startsWith("yt:")) streamFor(item) else null)) {
        is CastSource.Direct -> item.buildUpon().setUri(source.url).setMimeType(source.mimeType).setCustomCacheKey(null).build()
        is CastSource.Refused -> { events("cast_refused reason=${source.reason}"); null }
    }

    /** Replaces what the speaker holds with the phone's queue from [from], starting at [position] in that song. */
    private fun send(from: Int, position: Long) {
        load?.cancel(); ahead?.cancel()
        load = scope.launch {
            val player = local() ?: return@launch
            val items = ArrayList<MediaItem>()
            var index = from; var start = 0L
            while (items.size <= CAST_AHEAD && index in 0 until player.mediaItemCount) {
                val item = player.getMediaItemAt(index)
                val ready = forSpeaker(item)
                if (ready != null) { if (items.isEmpty() && index == from) start = position; items += ready }
                else if (items.isEmpty()) refused(item)
                index++
            }
            val speaker = remote ?: return@launch
            if (!casting) return@launch
            if (items.isEmpty()) { speaker.stop(); return@launch }
            speaker.setMediaItems(items, 0, start)
            speaker.prepare()
            speaker.playWhenReady = wantPlay
        }
    }

    /** Adds the phone's next songs after the speaker's last one, up to [CAST_AHEAD] past the current. */
    private fun topUp() {
        if (ahead?.isActive == true || load?.isActive == true) return
        val speaker = remote ?: return
        val player = local() ?: return
        val count = speaker.mediaItemCount
        if (count == 0 || count - speaker.currentMediaItemIndex - 1 >= CAST_AHEAD) return
        val last = nearestEntry(entries(player), speaker.getMediaItemAt(count - 1).entryId(), player.currentMediaItemIndex)
        if (last < 0) return
        ahead = scope.launch {
            val items = ArrayList<MediaItem>()
            var index = last + 1
            while (items.size < CAST_AHEAD && index < player.mediaItemCount) { forSpeaker(player.getMediaItemAt(index))?.let(items::add); index++ }
            if (casting && items.isNotEmpty() && remote === speaker) speaker.addMediaItems(items)
        }
    }

    /** The phone's player while casting: a new song or queue there goes to the speaker, and it never plays itself. */
    val localListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!casting) return
            val player = local() ?: return
            if (mediaItem != null && mediaItem.entryId() != remote?.currentMediaItem?.entryId()) send(player.currentMediaItemIndex, player.currentPosition)
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!casting || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || load?.isActive == true) return
            // Songs after the current one changed: the speaker's are sent again.
            val speaker = remote ?: return
            val next = speaker.currentMediaItemIndex + 1
            ahead?.cancel()
            if (next < speaker.mediaItemCount) speaker.removeMediaItems(next, speaker.mediaItemCount)
            topUp()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!casting || !playWhenReady) return
            local()?.pause()
            wantPlay = true
            remote?.play()
        }
    }

    private val remoteListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!casting || mediaItem == null) return
            val player = local() ?: return
            val index = nearestEntry(entries(player), mediaItem.entryId(), player.currentMediaItemIndex)
            // The phone follows the speaker, paused; its own transition then matches and sends nothing.
            if (index >= 0 && index != player.currentMediaItemIndex) player.seekTo(index, 0)
            topUp()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (casting) wantPlay = playWhenReady }
    }
}
